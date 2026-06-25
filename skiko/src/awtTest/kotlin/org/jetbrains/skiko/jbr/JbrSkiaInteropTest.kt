package org.jetbrains.skiko.jbr

import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        assertEquals(111, discovery.abiId)
        assertEquals("test-build", discovery.buildId)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH, discovery.commandCapabilitiesHigh)
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
    fun expectedAbiOverrideForTestingForcesMismatch() {
        withSystemProperty("skiko.jbr.interop.expectedAbiIdForTest", "999") {
            val discovery = JbrSkiaInterop.discover(resolver(
                publicJbrSkiaClass = CompatibleJbrSkia::class.java,
                jbrAccessorClass = CompatibleJbr::class.java,
            ))

            assertFalse(discovery.isAvailable)
            assertEquals(JbrSkiaInterop.FallbackReason.ABI_MISMATCH, discovery.fallbackReason)
            assertEquals(111, discovery.abiId)
            assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=abi-mismatch", discovery.fallbackMarker)
        }
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
        assertEquals(111, discovery.abiId)
        assertEquals("test-build", discovery.buildId)
    }

    @Test
    fun rejectsNativeAbiMismatch() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = NativeAbiMismatchJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.NATIVE_ABI_MISMATCH, discovery.fallbackReason)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=native-abi-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun expectedNativeAbiOverrideForTestingForcesMismatch() {
        withSystemProperty("skiko.jbr.interop.expectedNativeAbiVersionForTest", "7") {
            val discovery = JbrSkiaInterop.discover(resolver(
                publicJbrSkiaClass = CompatibleJbrSkia::class.java,
                jbrAccessorClass = CompatibleJbr::class.java,
            ))

            assertFalse(discovery.isAvailable)
            assertEquals(JbrSkiaInterop.FallbackReason.NATIVE_ABI_MISMATCH, discovery.fallbackReason)
            assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=native-abi-mismatch", discovery.fallbackMarker)
        }
    }

    @Test
    fun rejectsNativeCommandStreamAbiMismatch() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = NativeCommandStreamAbiMismatchJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.NATIVE_ABI_MISMATCH, discovery.fallbackReason)
        assertEquals(111, discovery.abiId)
    }

    @Test
    fun rejectsNativeBuildIdMismatch() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = NativeBuildIdMismatchJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.NATIVE_ABI_MISMATCH, discovery.fallbackReason)
        assertEquals("test-build", discovery.buildId)
    }

    @Test
    fun rejectsServiceWithoutNativeMetadata() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = LegacyNativeMetadataJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.NATIVE_ABI_MISMATCH, discovery.fallbackReason)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=native-abi-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingCommandCapabilities() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingCapabilitiesJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(0L, discovery.commandCapabilities)
        assertEquals(0L, discovery.commandCapabilitiesHigh)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun requiredHighCommandCapabilitiesOverrideForTestingForcesMismatch() {
        withSystemProperty("skiko.jbr.interop.requiredCommandCapabilitiesHighForTest", "268435456") {
            val discovery = JbrSkiaInterop.discover(resolver(
                publicJbrSkiaClass = CompatibleJbrSkia::class.java,
                jbrAccessorClass = CompatibleJbr::class.java,
            ))

            assertFalse(discovery.isAvailable)
            assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
            assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
            assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH, discovery.commandCapabilitiesHigh)
            assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
        }
    }

    @Test
    fun rejectsEachMissingHighCommandCapability() {
        val capabilities = listOf(
            "save-layer image-filter ref" to COMMAND_CAP64_HIGH_SAVE_LAYER_IMAGE_FILTER_REF,
            "offset image-filter descriptor" to COMMAND_CAP64_HIGH_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER,
            "chained image-filter descriptor" to COMMAND_CAP64_HIGH_EFFECT_DESCRIPTOR_CHAIN_IMAGE_FILTER,
            "shader descriptor ref" to COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_REF,
            "runtime color-filter descriptor" to COMMAND_CAP64_HIGH_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER,
            "rect dash path-effect stroke" to COMMAND_CAP64_HIGH_STROKE_RECT_DASH_PATH_EFFECT,
            "round-rect dash path-effect stroke" to COMMAND_CAP64_HIGH_STROKE_ROUND_RECT_DASH_PATH_EFFECT,
            "path dash path-effect stroke" to COMMAND_CAP64_HIGH_STROKE_PATH_DASH_PATH_EFFECT,
            "path-effect descriptor ref" to COMMAND_CAP64_HIGH_PATH_EFFECT_DESCRIPTOR_REF,
            "concat matrix33" to COMMAND_CAP64_HIGH_CONCAT_MATRIX33,
            "shadow path" to COMMAND_CAP64_HIGH_DRAW_SHADOW_PATH,
            "shader descriptor color-filter" to COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_COLOR_FILTER,
            "draw points" to COMMAND_CAP64_HIGH_DRAW_POINTS,
            "shader descriptor transform" to COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_TRANSFORM,
            "font data descriptor" to COMMAND_CAP64_HIGH_DEFINE_FONT_DATA,
            "shader descriptor color" to COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_COLOR,
            "shader descriptor Perlin noise" to COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_PERLIN_NOISE,
            "draw vertices" to COMMAND_CAP64_HIGH_DRAW_VERTICES,
            "linear-gradient path stroke" to COMMAND_CAP64_HIGH_STROKE_PATH_LINEAR_GRADIENT,
            "radial-gradient path stroke" to COMMAND_CAP64_HIGH_STROKE_PATH_RADIAL_GRADIENT,
            "sweep-gradient path stroke" to COMMAND_CAP64_HIGH_STROKE_PATH_SWEEP_GRADIENT,
            "shader-ref rect stroke" to COMMAND_CAP64_HIGH_STROKE_RECT_SHADER_REF,
            "image-shader rect stroke" to COMMAND_CAP64_HIGH_STROKE_RECT_IMAGE_SHADER,
            "save translate" to COMMAND_CAP64_HIGH_SAVE_TRANSLATE,
            "restore n" to COMMAND_CAP64_HIGH_RESTORE_N,
            "save translate layer" to COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER,
            "full image ref" to COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL,
            "fill round rect" to COMMAND_CAP64_HIGH_FILL_ROUND_RECT,
            "full image ref run" to COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RUN,
            "stroke line and full image ref run" to COMMAND_CAP64_HIGH_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN,
            "save translate layer and save translate" to COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE,
            "full image ref and restore" to COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RESTORE,
            "full image ref and restore n" to COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RESTORE_N,
            "round rect and restore n" to COMMAND_CAP64_HIGH_DRAW_ROUND_RECT_RESTORE_N,
            "stroke line full image ref run and restore n" to
                COMMAND_CAP64_HIGH_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N,
            "full image ref restore n and save translate layer save translate" to
                COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE,
            "fill rect and save" to COMMAND_CAP64_HIGH_FILL_RECT_SAVE,
            "save fill rect and save" to COMMAND_CAP64_HIGH_SAVE_FILL_RECT_SAVE,
            "save layer and save translate" to COMMAND_CAP64_HIGH_SAVE_LAYER_SAVE_TRANSLATE,
            "save and save layer and save translate" to COMMAND_CAP64_HIGH_SAVE_SAVE_LAYER_SAVE_TRANSLATE,
            "fill rect and save layer clip rect" to COMMAND_CAP64_HIGH_FILL_RECT_SAVE_LAYER_CLIP_RECT,
            "save translate layer save translate and full image ref restore n" to
                COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N,
            "save translate layer full image restore n and save translate layer" to
                COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE,
        )

        capabilities.forEach { (label, capability) ->
            val availableHighCapabilities = REQUIRED_COMMAND_CAPABILITIES_HIGH and capability.inv()
            val discovery = discoverWithCapabilities(commandCapabilitiesHigh = availableHighCapabilities)

            assertFalse(discovery.isAvailable, label)
            assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason, label)
            assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities, label)
            assertEquals(availableHighCapabilities, discovery.commandCapabilitiesHigh, label)
            assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker, label)
        }
    }

    @Test
    fun rejectsEachMissingLowCommandCapability() {
        val capabilities = (0 until Long.SIZE_BITS).map { bit -> "low bit $bit" to (1L shl bit) }

        capabilities.forEach { (label, capability) ->
            val availableCapabilities = REQUIRED_COMMAND_CAPABILITIES and capability.inv()
            val discovery = discoverWithCapabilities(commandCapabilities = availableCapabilities)

            assertFalse(discovery.isAvailable, label)
            assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason, label)
            assertEquals(availableCapabilities, discovery.commandCapabilities, label)
            assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH, discovery.commandCapabilitiesHigh, label)
            assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker, label)
        }
    }

    @Test
    fun rejectsMissingShaderDescriptorColorFilterCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingShaderDescriptorColorFilterCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_COLOR_FILTER, discovery.commandCapabilitiesHigh)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingDrawPointsCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingDrawPointsCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_DRAW_POINTS, discovery.commandCapabilitiesHigh)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingShaderDescriptorTransformCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingShaderDescriptorTransformCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_TRANSFORM, discovery.commandCapabilitiesHigh)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingShaderDescriptorColorCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingShaderDescriptorColorCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES, discovery.commandCapabilities)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_COLOR, discovery.commandCapabilitiesHigh)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingTextFontFamilyCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingTextFontFamilyCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_TEXT_FONT_FAMILY, discovery.commandCapabilities)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_HIGH, discovery.commandCapabilitiesHigh)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingSaveLayerBlendColorFilterCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingSaveLayerBlendColorFilterCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingColorMatrixDescriptorCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingColorMatrixDescriptorCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_COLOR_MATRIX_DESCRIPTOR, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingLightingDescriptorCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingLightingDescriptorCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_LIGHTING_DESCRIPTOR, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingSaveLayerColorFilterRefCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingSaveLayerColorFilterRefCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_COLOR_FILTER_REF, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingImageColorFilterRefCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingImageColorFilterRefCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_IMAGE_COLOR_FILTER_REF, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun rejectsMissingSaveLayerBlendColorFilterRefCommandCapability() {
        val discovery = JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = MissingSaveLayerBlendColorFilterRefCapabilityJbr::class.java,
        ))

        assertFalse(discovery.isAvailable)
        assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
        assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER_REF, discovery.commandCapabilities)
        assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
    }

    @Test
    fun requiredCommandCapabilitiesOverrideForTestingForcesMismatch() {
        withSystemProperty(
            "skiko.jbr.interop.requiredCommandCapabilitiesForTest",
            COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF.toString(),
        ) {
            val discovery = JbrSkiaInterop.discover(resolver(
                publicJbrSkiaClass = CompatibleJbrSkia::class.java,
                jbrAccessorClass = MissingSaveLayerBlendColorFilterRefCapabilityJbr::class.java,
            ))

            assertFalse(discovery.isAvailable)
            assertEquals(JbrSkiaInterop.FallbackReason.COMMAND_CAPABILITY_MISMATCH, discovery.fallbackReason)
            assertEquals(REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER_REF, discovery.commandCapabilities)
            assertEquals("SKIKO_JBR_INTEROP_FALLBACK reason=command-capability-mismatch", discovery.fallbackMarker)
        }
    }

    @Test
    fun acquireCanvasReturnsScopedCanvasFromService() {
        val scopedCanvas = JbrSkiaInterop.acquireCanvas(testGraphics(), resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = CompatibleJbr::class.java,
        ))

        assertNotNull(scopedCanvas)
        assertEquals(99L, scopedCanvas.scopeId)
        assertEquals(0x9abcL, scopedCanvas.contextId)
        assertEquals(0x5678L, scopedCanvas.surfaceId)
        assertEquals(0x1234L, scopedCanvas.metalTexturePtr)
        assertTrue(scopedCanvas.renderDiagnosticFrame(16, 16, 42L))
        assertTrue(scopedCanvas.renderCommandFrame(16, 16, 42L, intArrayOf(1246972723, 42, 0, 4, 1, 1, 1, 16, 1, 0xff000000.toInt())))
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
        assertEquals(111, discovery.abiId)
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

    @Test
    fun commandStreamPreflightRejectsAbiMismatch() {
        assertEquals(
            JbrSkiaInterop.FallbackReason.ABI_MISMATCH,
            commandStreamFallbackReason(intArrayOf(1246972723, 99, 0, 0, 1, 1))
        )
    }

    @Test
    fun commandStreamPreflightRejectsInvalidHeader() {
        assertEquals(
            JbrSkiaInterop.FallbackReason.COMMAND_STREAM_INVALID,
            commandStreamFallbackReason(intArrayOf(1246972723, 109, 0))
        )
        assertEquals(
            JbrSkiaInterop.FallbackReason.COMMAND_STREAM_INVALID,
            commandStreamFallbackReason(intArrayOf(42, 109, 0, 0, 1, 1))
        )
    }

    @Test
    fun commandStreamPreflightAcceptsCurrentAbi() {
        assertEquals(
            null,
            commandStreamFallbackReason(intArrayOf(1246972723, 111, 0, 0, 1, 1))
        )
    }

    @Test
    fun surfaceIdentityTrackerIgnoresUnknownIdentity() {
        val tracker = SurfaceIdentityTracker()

        assertEquals(null, tracker.note(SurfaceIdentity(contextId = 0L, surfaceId = 0L, metalTexturePtr = 0L)))
        assertEquals(null, tracker.note(SurfaceIdentity(contextId = 0x1L, surfaceId = 0x2L, metalTexturePtr = 0x3L)))
    }

    @Test
    fun surfaceIdentityTrackerIgnoresStableIdentity() {
        val tracker = SurfaceIdentityTracker()
        val identity = SurfaceIdentity(contextId = 0x1L, surfaceId = 0x2L, metalTexturePtr = 0x3L)

        assertEquals(null, tracker.note(identity))
        assertEquals(null, tracker.note(identity))
    }

    @Test
    fun surfaceIdentityTrackerReportsChangedIdentity() {
        val tracker = SurfaceIdentityTracker()

        assertEquals(null, tracker.note(SurfaceIdentity(contextId = 0x1L, surfaceId = 0x2L, metalTexturePtr = 0x3L)))
        val change = tracker.note(SurfaceIdentity(contextId = 0x4L, surfaceId = 0x5L, metalTexturePtr = 0x6L))

        assertNotNull(change)
        assertTrue(change.contextChanged)
        assertTrue(change.surfaceChanged)
        assertEquals(
            "SKIKO_JBR_INTEROP_SURFACE_CHANGED oldContextId=0x1 newContextId=0x4 contextChanged=true surfaceChanged=true oldSurfaceId=0x2 newSurfaceId=0x5 oldMetalTexture=0x3 newMetalTexture=0x6",
            change.marker()
        )
    }

    @Test
    fun surfaceIdentityTrackerDistinguishesSameContextSurfaceReplacement() {
        val tracker = SurfaceIdentityTracker()

        assertEquals(null, tracker.note(SurfaceIdentity(contextId = 0x1L, surfaceId = 0x2L, metalTexturePtr = 0x3L)))
        val change = tracker.note(SurfaceIdentity(contextId = 0x1L, surfaceId = 0x4L, metalTexturePtr = 0x5L))

        assertNotNull(change)
        assertFalse(change.contextChanged)
        assertTrue(change.surfaceChanged)
        assertEquals(
            "SKIKO_JBR_INTEROP_SURFACE_CHANGED oldContextId=0x1 newContextId=0x1 contextChanged=false surfaceChanged=true oldSurfaceId=0x2 newSurfaceId=0x4 oldMetalTexture=0x3 newMetalTexture=0x5",
            change.marker()
        )
    }

    @Test
    fun surfaceIdentityTrackerClearForgetsPreviousIdentity() {
        val tracker = SurfaceIdentityTracker()

        assertEquals(null, tracker.note(SurfaceIdentity(contextId = 0x1L, surfaceId = 0x2L, metalTexturePtr = 0x3L)))
        tracker.clear()
        assertEquals(null, tracker.note(SurfaceIdentity(contextId = 0x4L, surfaceId = 0x5L, metalTexturePtr = 0x6L)))
    }

    @Test
    fun commandFrameCacheReplaysLastMeaningfulFrameForMinimalInteropOnlyFrame() {
        val cache = CommandFrameCache(minimumMeaningfulCommandWords = 8)
        val meaningful = IntArray(10) { index -> index + 1 }
        val minimal = intArrayOf(1, 2, 3)

        assertSame(
            meaningful,
            cache.frameForRendering(JbrSkiaCommandFrame(meaningful, JbrSkiaCommandFrameKind.FullScene)),
        )
        assertSame(
            meaningful,
            cache.frameForRendering(JbrSkiaCommandFrame(minimal, JbrSkiaCommandFrameKind.InteropOnly)),
        )
    }

    @Test
    fun commandFrameCacheDoesNotReplaceMeaningfulFrameWithMinimalFullSceneFrame() {
        val cache = CommandFrameCache(minimumMeaningfulCommandWords = 8)
        val meaningful = IntArray(10) { index -> index + 1 }
        val minimal = intArrayOf(1, 2, 3)

        cache.frameForRendering(JbrSkiaCommandFrame(meaningful, JbrSkiaCommandFrameKind.FullScene))

        assertSame(
            meaningful,
            cache.frameForRendering(JbrSkiaCommandFrame(minimal, JbrSkiaCommandFrameKind.FullScene)),
        )
        assertSame(
            meaningful,
            cache.frameForRendering(JbrSkiaCommandFrame(minimal, JbrSkiaCommandFrameKind.InteropOnly)),
        )
    }

    @Test
    fun commandFrameCacheReturnsMinimalFullSceneFrameWhenNoMeaningfulFrameWasSeen() {
        val cache = CommandFrameCache(minimumMeaningfulCommandWords = 8)
        val minimal = intArrayOf(1, 2, 3)

        assertSame(
            minimal,
            cache.frameForRendering(JbrSkiaCommandFrame(minimal, JbrSkiaCommandFrameKind.FullScene)),
        )
    }

    @Test
    fun commandFrameCacheReturnsMinimalFrameWhenNoMeaningfulFrameWasSeen() {
        val cache = CommandFrameCache(minimumMeaningfulCommandWords = 8)
        val minimal = intArrayOf(1, 2, 3)

        assertSame(
            minimal,
            cache.frameForRendering(JbrSkiaCommandFrame(minimal, JbrSkiaCommandFrameKind.InteropOnly)),
        )
    }

    @Test
    fun commandFrameCacheCanBeClearedAfterSurfaceReplacement() {
        val cache = CommandFrameCache(minimumMeaningfulCommandWords = 8)
        val meaningful = IntArray(10) { index -> index + 1 }
        val minimal = intArrayOf(1, 2, 3)

        cache.frameForRendering(JbrSkiaCommandFrame(meaningful, JbrSkiaCommandFrameKind.FullScene))
        cache.clear()

        assertSame(
            minimal,
            cache.frameForRendering(JbrSkiaCommandFrame(minimal, JbrSkiaCommandFrameKind.InteropOnly)),
        )
    }

    private fun testGraphics() = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()

    private fun discoverWithCapabilities(
        commandCapabilities: Long = REQUIRED_COMMAND_CAPABILITIES,
        commandCapabilitiesHigh: Long = REQUIRED_COMMAND_CAPABILITIES_HIGH,
    ): JbrSkiaInterop.Discovery {
        DynamicCapabilitiesJbr.service = FakeJbrSkiaService(
            commandCapabilities = commandCapabilities,
            commandCapabilitiesHigh = commandCapabilitiesHigh,
        )
        return JbrSkiaInterop.discover(resolver(
            publicJbrSkiaClass = CompatibleJbrSkia::class.java,
            jbrAccessorClass = DynamicCapabilitiesJbr::class.java,
        ))
    }

    private fun withSystemProperty(name: String, value: String, block: () -> Unit) {
        val oldValue = System.getProperty(name)
        try {
            System.setProperty(name, value)
            block()
        } finally {
            if (oldValue == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, oldValue)
            }
        }
    }

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
            val ABI_ID: Int = "111".toInt()

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
        private val service = FakeJbrSkiaService(commandCapabilities = 0, commandCapabilitiesHigh = 0)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object DynamicCapabilitiesJbr {
        lateinit var service: FakeJbrSkiaService

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingSaveLayerBlendColorFilterCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingShaderDescriptorColorFilterCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilitiesHigh = REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_COLOR_FILTER)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingDrawPointsCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilitiesHigh = REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_DRAW_POINTS)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingShaderDescriptorTransformCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilitiesHigh = REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_TRANSFORM)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingShaderDescriptorColorCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilitiesHigh = REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_COLOR)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingTextFontFamilyCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_TEXT_FONT_FAMILY)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingColorMatrixDescriptorCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_COLOR_MATRIX_DESCRIPTOR)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingLightingDescriptorCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_LIGHTING_DESCRIPTOR)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingSaveLayerColorFilterRefCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_COLOR_FILTER_REF)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingImageColorFilterRefCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_IMAGE_COLOR_FILTER_REF)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object MissingSaveLayerBlendColorFilterRefCapabilityJbr {
        private val service = FakeJbrSkiaService(commandCapabilities = REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER_REF)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object NativeAbiMismatchJbr {
        private val service = FakeJbrSkiaService(nativeAbiVersion = 7)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object NativeCommandStreamAbiMismatchJbr {
        private val service = FakeJbrSkiaService(nativeCommandStreamAbiId = 7)

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object NativeBuildIdMismatchJbr {
        private val service = FakeJbrSkiaService(nativeBuildId = "other-build")

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaService = service
    }

    object LegacyNativeMetadataJbr {
        private val service = FakeJbrSkiaServiceWithoutNativeMetadata()

        @JvmStatic
        fun getJBRSkia(): FakeJbrSkiaServiceWithoutNativeMetadata = service
    }

    class FakeJbrSkiaService(
        private val commandCapabilities: Long = REQUIRED_COMMAND_CAPABILITIES,
        private val commandCapabilitiesHigh: Long = REQUIRED_COMMAND_CAPABILITIES_HIGH,
        private val nativeAbiVersion: Int = 3,
        private val nativeCommandStreamAbiId: Int = 111,
        private val nativeBuildId: String = "test-build",
    ) {
        val scope = FakeScopedCanvas()

        fun getCommandCapabilities(): Int = commandCapabilities.toInt()

        fun getCommandCapabilities64(): Long = commandCapabilities

        fun getCommandCapabilities64High(): Long = commandCapabilitiesHigh

        fun getNativeAbiVersion(): Int = nativeAbiVersion

        fun getNativeCommandStreamAbiId(): Int = nativeCommandStreamAbiId

        fun getNativeBuildId(): String = nativeBuildId

        @Suppress("UNUSED_PARAMETER")
        fun acquireCanvas(graphics: java.awt.Graphics2D): FakeScopedCanvas = scope
    }

    class FakeJbrSkiaServiceWithoutNativeMetadata {
        val scope = FakeScopedCanvas()

        fun getCommandCapabilities64(): Long = REQUIRED_COMMAND_CAPABILITIES

        fun getCommandCapabilities64High(): Long = REQUIRED_COMMAND_CAPABILITIES_HIGH

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

        fun getScopeId(): Long = 99L

        fun getSurfaceId(): Long = 0x5678L

        fun getContextId(): Long = 0x9abcL

        fun getMetalTexturePtr(): Long = 0x1234L

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
        private const val COMMAND_CAP64_TEXT_FONT_FAMILY = 1099511627776L
        private const val COMMAND_CAP64_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER = 576460752303423488L
        private const val COMMAND_CAP64_EFFECT_DESCRIPTOR_LIGHTING_FILTER = 1152921504606846976L
        private const val COMMAND_CAP64_SAVE_LAYER_COLOR_FILTER_REF = 2305843009213693952L
        private const val COMMAND_CAP64_DRAW_IMAGE_REF_COLOR_FILTER_REF = 4611686018427387904L
        private const val COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF = Long.MIN_VALUE
        private const val COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER = 288230376151711744L
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_TEXT_FONT_FAMILY =
            -1L and COMMAND_CAP64_TEXT_FONT_FAMILY.inv()
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_COLOR_FILTER_REF =
            2305843009213693951L or COMMAND_CAP64_DRAW_IMAGE_REF_COLOR_FILTER_REF or COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_LIGHTING_DESCRIPTOR =
            1152921504606846975L or COMMAND_CAP64_SAVE_LAYER_COLOR_FILTER_REF or COMMAND_CAP64_DRAW_IMAGE_REF_COLOR_FILTER_REF or COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_COLOR_MATRIX_DESCRIPTOR =
            1729382256910270463L or COMMAND_CAP64_SAVE_LAYER_COLOR_FILTER_REF or COMMAND_CAP64_DRAW_IMAGE_REF_COLOR_FILTER_REF or COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER =
            2017612633061982207L or COMMAND_CAP64_SAVE_LAYER_COLOR_FILTER_REF or COMMAND_CAP64_DRAW_IMAGE_REF_COLOR_FILTER_REF or COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_IMAGE_COLOR_FILTER_REF =
            4611686018427387903L or COMMAND_CAP64_SAVE_LAYER_BLEND_COLOR_FILTER_REF
        private const val REQUIRED_COMMAND_CAPABILITIES_WITHOUT_SAVE_LAYER_BLEND_COLOR_FILTER_REF = Long.MAX_VALUE
        private const val REQUIRED_COMMAND_CAPABILITIES =
            -1L
        private const val REQUIRED_COMMAND_CAPABILITIES_HIGH = 140730240598015L
        private const val COMMAND_CAP64_HIGH_SAVE_LAYER_IMAGE_FILTER_REF = 1L
        private const val COMMAND_CAP64_HIGH_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER = 2L
        private const val COMMAND_CAP64_HIGH_EFFECT_DESCRIPTOR_CHAIN_IMAGE_FILTER = 4L
        private const val COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_REF = 8L
        private const val COMMAND_CAP64_HIGH_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER = 16L
        private const val COMMAND_CAP64_HIGH_STROKE_RECT_DASH_PATH_EFFECT = 32L
        private const val COMMAND_CAP64_HIGH_STROKE_ROUND_RECT_DASH_PATH_EFFECT = 64L
        private const val COMMAND_CAP64_HIGH_STROKE_PATH_DASH_PATH_EFFECT = 128L
        private const val COMMAND_CAP64_HIGH_PATH_EFFECT_DESCRIPTOR_REF = 256L
        private const val COMMAND_CAP64_HIGH_CONCAT_MATRIX33 = 512L
        private const val COMMAND_CAP64_HIGH_DRAW_SHADOW_PATH = 1024L
        private const val COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_COLOR_FILTER = 2048L
        private const val COMMAND_CAP64_HIGH_DRAW_POINTS = 4096L
        private const val COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_TRANSFORM = 8192L
        private const val COMMAND_CAP64_HIGH_DEFINE_FONT_DATA = 16384L
        private const val COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_COLOR = 32768L
        private const val COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_PERLIN_NOISE = 65536L
        private const val COMMAND_CAP64_HIGH_DRAW_VERTICES = 131072L
        private const val COMMAND_CAP64_HIGH_STROKE_PATH_LINEAR_GRADIENT = 262144L
        private const val COMMAND_CAP64_HIGH_STROKE_PATH_RADIAL_GRADIENT = 524288L
        private const val COMMAND_CAP64_HIGH_STROKE_PATH_SWEEP_GRADIENT = 1048576L
        private const val COMMAND_CAP64_HIGH_STROKE_RECT_SHADER_REF = 2097152L
        private const val COMMAND_CAP64_HIGH_STROKE_RECT_IMAGE_SHADER = 4194304L
        private const val COMMAND_CAP64_HIGH_SAVE_TRANSLATE = 8388608L
        private const val COMMAND_CAP64_HIGH_RESTORE_N = 16777216L
        private const val COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER = 33554432L
        private const val COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL = 67108864L
        private const val COMMAND_CAP64_HIGH_FILL_ROUND_RECT = 134217728L
        private const val COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RUN = 1073741824L
        private const val COMMAND_CAP64_HIGH_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN = 8589934592L
        private const val COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE = 17179869184L
        private const val COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RESTORE = 34359738368L
        private const val COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RESTORE_N = 68719476736L
        private const val COMMAND_CAP64_HIGH_DRAW_ROUND_RECT_RESTORE_N = 137438953472L
        private const val COMMAND_CAP64_HIGH_STROKE_LINE_DRAW_IMAGE_REF_FULL_RUN_RESTORE_N = 274877906944L
        private const val COMMAND_CAP64_HIGH_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE =
            549755813888L
        private const val COMMAND_CAP64_HIGH_FILL_RECT_SAVE = 1099511627776L
        private const val COMMAND_CAP64_HIGH_SAVE_FILL_RECT_SAVE = 2199023255552L
        private const val COMMAND_CAP64_HIGH_SAVE_LAYER_SAVE_TRANSLATE = 4398046511104L
        private const val COMMAND_CAP64_HIGH_SAVE_SAVE_LAYER_SAVE_TRANSLATE = 8796093022208L
        private const val COMMAND_CAP64_HIGH_FILL_RECT_SAVE_LAYER_CLIP_RECT = 17592186044416L
        private const val COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N =
            35184372088832L
        private const val COMMAND_CAP64_HIGH_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE_DRAW_IMAGE_REF_FULL_RESTORE_N_SAVE_TRANSLATE_LAYER_SAVE_TRANSLATE =
            70368744177664L
        private const val REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_COLOR_FILTER =
            REQUIRED_COMMAND_CAPABILITIES_HIGH and COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_COLOR_FILTER.inv()
        private const val REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_DRAW_POINTS =
            REQUIRED_COMMAND_CAPABILITIES_HIGH and COMMAND_CAP64_HIGH_DRAW_POINTS.inv()
        private const val REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_TRANSFORM =
            REQUIRED_COMMAND_CAPABILITIES_HIGH and COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_TRANSFORM.inv()
        private const val REQUIRED_COMMAND_CAPABILITIES_HIGH_WITHOUT_SHADER_DESCRIPTOR_COLOR =
            REQUIRED_COMMAND_CAPABILITIES_HIGH and COMMAND_CAP64_HIGH_SHADER_DESCRIPTOR_COLOR.inv()
    }
}
