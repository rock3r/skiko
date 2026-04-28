package org.jetbrains.skiko.jbr

/**
 * Optional companion interface for [org.jetbrains.skiko.SkikoRenderDelegate] implementations that
 * can emit the temporary JBR Skia command-list ABI directly.
 */
interface JbrSkiaCommandRenderDelegate {
    fun renderJbrSkiaCommandFrame(width: Int, height: Int, nanoTime: Long): IntArray?
}
