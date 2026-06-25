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

            JbrSkiaDebugOverlay.paint(image.createGraphics(), layerWidth = image.width, layerHeight = image.height)

            assertNotEquals(0, image.nonTransparentPixelCount())
        }
    }

    @Test
    fun overlayAnchorsAwayFromTopLeftWhenLayerBoundsAreKnown() {
        withDebugOverlay("true") {
            val image = BufferedImage(320, 160, BufferedImage.TYPE_INT_ARGB)

            JbrSkiaDebugOverlay.paint(image.createGraphics(), acquiredJbrScope = true, image.width, image.height)

            assertEquals(0, image.nonTransparentPixelCount(left = 0, top = 0, right = 160, bottom = 80))
            assertNotEquals(0, image.nonTransparentPixelCount(left = 160, top = 80, right = image.width, bottom = image.height))
        }
    }

    private fun BufferedImage.nonTransparentPixelCount(
        left: Int = 0,
        top: Int = 0,
        right: Int = width,
        bottom: Int = height,
    ): Int {
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
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
