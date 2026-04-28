package org.jetbrains.skiko.jbr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.awt.image.BufferedImage
import java.nio.ByteBuffer

class JbrSkiaInteropTest {
    @Test
    fun discoversCompatibleService() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = CompatibleJbr::class.java,
        ))

        assertTrue(discovery.isAvailable)
        assertSame(CompatibleJbr.service, discovery.service)
        assertEquals(9, discovery.abiId)
        assertEquals("test-build", discovery.buildId)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
    }

    @Test
    fun rejectsAbiMismatchWithStructuredMarker() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = IncompatibleJbrSkia::class.java,
            jbrAccessorClass = CompatibleJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.ABI_MISMATCH, discovery.fallbackReason)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=abi-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun missingPublicApiFallsBack() {
        val discovery = JbrSkiaInterop.discover(resolver())

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.PUBLIC_API_MISSING, discovery.fallbackReason)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=public-api-missing", discovery.fallbackMarker)
    }

    @Test
    fun nullServiceFallsBack() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = NullServiceJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.SERVICE_UNAVAILABLE, discovery.fallbackReason)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=service-unavailable", discovery.fallbackMarker)
    }

    @Test
    fun nullPublicServiceCanUsePatchedInternalProvider() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = NullServiceJbr::class.java,
            internalServiceClass = FakeJbrSkiaService::class.java,
        ))

        assertTrue(discovery.isAvailable)
        assertEquals(9, discovery.abiId)
        assertEquals("test-build", discovery.buildId)
    }

    @Test
    fun rejectsMissingCommandCapabilities() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingCapabilitiesJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(0, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun acquireCanvasReturnsScopedCanvasFromService() {
        val scopedCanvas = JbrSkiaInterop.acquireCanvas(testGraphics(), resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = CompatibleJbr::class.java,
        ))

        assertNotNull(scopedCanvas)
        assertTrue(scopedCanvas.renderDiagnosticFrame(16, 16, 42L))
        assertTrue(scopedCanvas.renderCommandFrame(16, 16, 42L, intArrayOf(1246972723, 9, 0, 4, 1, 1, 1, 16, 1, 0xff000000.toInt())))
        assertTrue(scopedCanvas.renderCommandBufferFrame(16, 16, 42L, byteArrayOf(1, 0, 0, 0)))
        assertTrue(scopedCanvas.renderCommandDirectFrame(16, 16, 42L, ByteBuffer.allocateDirect(4).also { it.putInt(1); it.flip() }))
        assertTrue(scopedCanvas.renderPictureFrame(16, 16, 42L, byteArrayOf(1, 2, 3)))
        assertEquals(1, CompatibleJbr.service.scope.renderDiagnosticFrameCount)
        assertEquals(1, CompatibleJbr.service.scope.renderCommandFrameCount)
        assertEquals(1, CompatibleJbr.service.scope.renderCommandBufferFrameCount)
        assertEquals(1, CompatibleJbr.service.scope.renderCommandDirectFrameCount)
        assertEquals(1, CompatibleJbr.service.scope.renderPictureFrameCount)
        scopedCanvas.close()
        assertEquals(1, CompatibleJbr.service.scope.closeCount)
    }

    @Test
    fun discoversLegacyDesktopMirrorClass() {
        val discovery = JbrSkiaInterop.discover(resolver(
            desktopJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = CompatibleJbr::class.java,
        ))

        assertTrue(discovery.isAvailable)
        assertEquals(9, discovery.abiId)
        assertEquals("test-build", discovery.buildId)
    }

    @Test
    fun pictureFrameUsesDevicePixelSize() {
        assertEquals(
            DeviceFrameSize(width = 200, height = 100),
            deviceFrameSize(width = 100, height = 50, scale = 2f)
        )
        assertEquals(
            DeviceFrameSize(width = 150, height = 75),
            deviceFrameSize(width = 100, height = 50, scale = 1.5f)
        )
    }

    @Test
    fun pictureFrameSizeFallsBackToUserSpaceForInvalidScale() {
        assertEquals(
            DeviceFrameSize(width = 100, height = 50),
            deviceFrameSize(width = 100, height = 50, scale = 0f)
        )
        assertEquals(
            DeviceFrameSize(width = 1, height = 1),
            deviceFrameSize(width = 0, height = 0, scale = Float.NaN)
        )
    }

    @Test
    fun pictureFrameMarkerIsParseable() {
        assertEquals(
            "SKIKO_JBR_INTEROP_PICTURE_FRAME width=200 height=100 bytes=4096 rendered=true",
            pictureFrameMarker(width = 200, height = 100, bytes = 4096, rendered = true)
        )
    }

    @Test
    fun commandFrameMarkerIsParseable() {
        assertEquals(
            "SKIKO_JBR_INTEROP_COMMAND_FRAME width=200 height=100 commands=32 rendered=true",
            commandFrameMarker(width = 200, height = 100, commands = 32, rendered = true)
        )
    }

    private fun testGraphics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()

    private fun resolver(
        publicJbrSkiaClass: Class<*>? = null,
        desktopJbrSkiaClass: Class<*>? = null,
        jbrAccessorClass: Class<*>? = null,
        internalServiceClass: Class<*>? = null,
    ) = object : JbrSkiaInterop.ClassResolver {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.jetbrains.JBRSkia" -> publicJbrSkiaClass
            "com.jetbrains.desktop.JBRSkia" -> desktopJbrSkiaClass
            "com.jetbrains.desktop.JBRSkiaService" -> internalServiceClass
            "com.jetbrains.JBR" -> jbrAccessorClass
            else -> null
        } ?: throw ClassNotFoundException(name)
    }

    class CompatibleJbrSkia {
        companion object {
            @JvmField
            val ABI_ID: Int = "9".toInt()

            @JvmField
            val BUILD_ID: String = buildString { append("test-build") }
        }
    }

    class IncompatibleJbrSkia {
        companion object {
            @JvmField
            val ABI_ID: Int = "7".toInt()

            @JvmField
            val BUILD_ID: String = buildString { append("test-build") }
        }
    }

    object CompatibleJbr {
        val service = FakeJbrSkiaService()

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object NullServiceJbr {
        @JvmStatic
        fun getJBRSkia(): Any? = null
    }

    object MissingCapabilitiesJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = 0)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    class FakeJbrSkiaService(
        private val commandCapabilities: Int = REQUIRED_COMMAND_CAPABILITIES,
    ) {
        val scope = FakeScopedCanvas()

        fun getCommandCapabilities(): Int = commandCapabilities

        @Suppress("UNUSED_PARAMETER")
        fun acquireCanvas(graphics: java.awt.Graphics2D): FakeScopedCanvas = scope
    }

    class FakeScopedCanvas : AutoCloseable {
        var closeCount = 0
        var renderDiagnosticFrameCount = 0
        var renderCommandFrameCount = 0
        var renderCommandBufferFrameCount = 0
        var renderCommandDirectFrameCount = 0
        var renderPictureFrameCount = 0

        @Suppress("UNUSED_PARAMETER")
        fun renderDiagnosticFrame(width: Int, height: Int, frameTimeNanos: Long): Boolean {
            renderDiagnosticFrameCount++
            return true
        }

        @Suppress("UNUSED_PARAMETER")
        fun renderCommandFrame(width: Int, height: Int, frameTimeNanos: Long, commands: IntArray): Boolean {
            renderCommandFrameCount++
            return commands.isNotEmpty()
        }

        @Suppress("UNUSED_PARAMETER")
        fun renderCommandBufferFrame(width: Int, height: Int, frameTimeNanos: Long, commands: ByteArray): Boolean {
            renderCommandBufferFrameCount++
            return commands.isNotEmpty()
        }

        @Suppress("UNUSED_PARAMETER")
        fun renderCommandDirectFrame(width: Int, height: Int, frameTimeNanos: Long, commands: ByteBuffer): Boolean {
            renderCommandDirectFrameCount++
            return commands.hasRemaining()
        }

        @Suppress("UNUSED_PARAMETER")
        fun renderPictureFrame(width: Int, height: Int, frameTimeNanos: Long, pictureData: ByteArray): Boolean {
            renderPictureFrameCount++
            return pictureData.isNotEmpty()
        }

        override fun close() {
            closeCount++
        }
    }

    private companion object {
        private const val REQUIRED_COMMAND_CAPABILITIES = 2047
    }
}
