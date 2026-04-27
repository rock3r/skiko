package org.jetbrains.skiko.jbr

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
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
    renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
    accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
    properties: SkiaLayerProperties = SkiaLayerProperties(),
) : SkiaSwingLayer(renderDelegate, analytics, accessibleContextProvider, properties) {
    override fun paint(g: Graphics) {
        var acquiredJbrScope = false
        val scopedCanvas = (g as? Graphics2D)?.let(JbrSkiaInterop::acquireCanvasOrNull)
        if (scopedCanvas != null) {
            acquiredJbrScope = true
            scopedCanvas.close()
            // Native direct-canvas rendering is wired in the next slice.
        }
        super.paint(g)
        if (g is Graphics2D) {
            JbrSkiaDebugOverlay.paint(g, acquiredJbrScope)
        }
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
            g.color = Color(0, 96, 72, 210)
            g.fillRoundRect(x, y, width, height, 8, 8)
            g.color = Color(216, 255, 239)
            g.drawString(text, x + 6, y + metrics.ascent + 3)
        } finally {
            g.color = previousColor
            g.font = previousFont
        }
    }
}
