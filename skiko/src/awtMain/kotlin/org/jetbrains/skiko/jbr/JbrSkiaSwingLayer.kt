package org.jetbrains.skiko.jbr

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Component
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
        val scopedCanvas = (g as? Graphics2D)?.let(JbrSkiaInterop::acquireCanvasOrNull)
        if (scopedCanvas != null) {
            scopedCanvas.close()
            // Native direct-canvas rendering is wired in the next slice.
        }
        super.paint(g)
    }
}
