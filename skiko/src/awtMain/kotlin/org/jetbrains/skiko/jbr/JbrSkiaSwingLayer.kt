package org.jetbrains.skiko.jbr

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.autoCloseScope
import org.jetbrains.skiko.makeMetalContext
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Color as AwtColor
import javax.accessibility.AccessibleContext

/**
 * Swing layer entry point for the experimental JBR-owned Skia interop path.
 *
 * The first implementation slice only exposes the feature-gated routing and
 * ABI discovery surface. Rendering intentionally falls back to [SkiaSwingLayer]
 * until the native direct-canvas redrawer is wired.
 */
@ExperimentalSkikoApi
class JbrSkiaSwingLayer(
    private val renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
    accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
    properties: SkiaLayerProperties = SkiaLayerProperties(),
) : SkiaSwingLayer(renderDelegate, analytics, accessibleContextProvider, properties) {
    private var context: DirectContext? = null

    override fun paint(g: Graphics) {
        var renderedWithJbrTexture = false
        if (g is Graphics2D && java.lang.Boolean.getBoolean(RENDER_TO_TEXTURE_PROPERTY)) {
            renderedWithJbrTexture = renderIntoJbrTexture(g)
        } else if (g is Graphics2D) {
            JbrSkiaInterop.acquireCanvas(g)?.close()
        }
        if (!renderedWithJbrTexture) {
            super.paint(g)
        }
        if (g is Graphics2D) {
            JbrSkiaDebugOverlay.paint(g, renderedWithJbrTexture)
        }
    }

    override fun removeNotify() {
        context?.close()
        context = null
        super.removeNotify()
    }

    private fun renderIntoJbrTexture(g: Graphics2D): Boolean {
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: return false
        try {
            val texturePtr = scope.metalTexturePtr
            if (texturePtr == 0L) {
                return false
            }

            val scale = graphicsConfiguration.defaultTransform.scaleX
            val renderWidth = (width * scale).toInt().coerceAtLeast(1)
            val renderHeight = (height * scale).toInt().coerceAtLeast(1)
            val directContext = context ?: makeMetalContext().also { context = it }

            autoCloseScope {
                val renderTarget = BackendRenderTarget
                    .makeMetal(renderWidth, renderHeight, texturePtr)
                    .autoClose()
                val surface = Surface.makeFromBackendRenderTarget(
                    directContext,
                    renderTarget,
                    SurfaceOrigin.TOP_LEFT,
                    SurfaceColorFormat.BGRA_8888,
                    ColorSpace.sRGB,
                    SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN),
                )?.autoClose() ?: throw IllegalStateException("Cannot wrap JBR Metal texture as a Skia surface")

                surface.canvas.clear(Color.TRANSPARENT)
                renderDelegate.onRender(surface.canvas, renderWidth, renderHeight, System.nanoTime())
                surface.flushAndSubmit(syncCpu = true)
                scope.flush()
            }
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            scope.close()
        }
    }

    private companion object {
        const val RENDER_TO_TEXTURE_PROPERTY = "skiko.jbr.interop.renderToTexture"
    }
}

internal object JbrSkiaDebugOverlay {
    private const val DEBUG_OVERLAY_PROPERTY = "skiko.jbr.interop.debugOverlay"

    fun paint(g: Graphics2D, acquiredJbrScope: Boolean = false) {
        if (!java.lang.Boolean.getBoolean(DEBUG_OVERLAY_PROPERTY)) return

        val previousColor = g.color
        val previousFont = g.font
        try {
            val text = if (acquiredJbrScope) "JBR Skia scope" else "JBR Skia path"
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            val metrics = g.fontMetrics
            val width = metrics.stringWidth(text) + 12
            val height = metrics.height + 6
            val x = 8
            val y = 8
            g.color = if (acquiredJbrScope) AwtColor(255, 192, 0, 235) else AwtColor(0, 96, 72, 210)
            g.fillRoundRect(x, y, width, height, 8, 8)
            g.color = if (acquiredJbrScope) AwtColor(32, 24, 0) else AwtColor(216, 255, 239)
            g.drawString(text, x + 6, y + metrics.ascent + 3)
        } finally {
            g.color = previousColor
            g.font = previousFont
        }
    }
}
