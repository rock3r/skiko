package org.jetbrains.skiko.jbr

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class JbrSkiaDebugOverlayTest {
    @Test
    fun overlayIsOptIn() {
        withDebugOverlay(null) {
            val image = BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB)

            JbrSkiaDebugOverlay.paint(image.createGraphics())

            assertEquals(0, image.nonTransparentPixelCount())
        }
    }

    @Test
    fun overlayPaintsWhenEnabled() {
        withDebugOverlay("true") {
            val image = BufferedImage(128, 40, BufferedImage.TYPE_INT_ARGB)

            JbrSkiaDebugOverlay.paint(image.createGraphics())

            assertNotEquals(0, image.nonTransparentPixelCount())
        }
    }

    private fun BufferedImage.nonTransparentPixelCount(): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((getRGB(x, y) ushr 24) != 0) {
                    count++
                }
            }
        }
        return count
    }

    private inline fun withDebugOverlay(value: String?, block: () -> Unit) {
        val key = "skiko.jbr.interop.debugOverlay"
        val previous = System.getProperty(key)
        try {
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }
}
