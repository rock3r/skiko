package org.jetbrains.skiko.swing

import org.jetbrains.skia.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.alignedTextureWidth
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.bridgeCopyAndSync
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.bridgeCopyPipelined
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.getPipelineStalls
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.createDirectXOffscreenDevice
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.createInteropBridge
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.disposeDirectXSharedTexture
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.disposeDirectXTexture
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.chooseAdapter
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.disposeDevice
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.disposeInteropBridge
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.getLegacyD3D11TexturePtr
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXContext
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXRenderTargetOffScreen
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXSharedTexture
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXTexture
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeSharedTextureRenderTarget
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.waitForCompletion
import org.jetbrains.skiko.swing.SharedTexturesAdapter.Companion.D3D9EX_SHARED_HANDLE_TEXTURE_TYPE
import org.jetbrains.skiko.swing.SharedTexturesAdapter.Companion.createSharedTexturesAdapter
import java.awt.Graphics2D

// TODO reuse DirectXOffscreenContext
internal class Direct3DSwingRedrawer(
    private val swingLayerProperties: SwingLayerProperties,
    private val renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics
) : SwingRedrawerBase(swingLayerProperties, analytics, GraphicsApi.DIRECT3D) {
    companion object {
        init {
            Library.load()
        }
    }

    private val adapter = chooseAdapter(swingLayerProperties.adapterPriority).also {
        onDeviceChosen("DirectX12") // TODO: properly get name
    }

    private val device = createDirectXOffscreenDevice(adapter)

    private val context = if (device == 0L) {
        throw RenderException("Failed to create DirectX12 device.")
    } else {
        DirectContext(
            makeDirectXContext(device)
        )
    }

    // JBR SharedTextures hand-off (D3D12 -> D3D11 bridge -> Java2D D3D9Ex).
    // Both stay 0/null when the accelerated path is unavailable; the software
    // readback path below is the universal fallback.
    // A-perf pipelined synchronization (previous-frame blit, no steady-state
    // CPU wait) vs A-corr (bounded per-frame copy drain). Pre-registered
    // opt-in for measurement.
    private val pipelinedSync: Boolean =
        System.getProperty("skiko.jbr.interop.pipelined") == "true"
    private var syncVariantReported = false

    private var bridge: Long = 0L
    private var acceleratedPainter: AcceleratedSwingPainter? = createAcceleratedPainter()

    private val softwarePainter: SwingPainter by lazy { SoftwareSwingPainter(swingLayerProperties) }

    private var texturePtr: Long = 0
    private var sharedTexturePtr: Long = 0
    private var acceleratedFrameCount: Long = 0
    private var bytesToDraw = ByteArray(0)

    init {
        onContextInit(context)
    }

    private fun createAcceleratedPainter(): AcceleratedSwingPainter? = try {
        val sharedTextures = createSharedTexturesAdapter()
        if (sharedTextures.textureType != D3D9EX_SHARED_HANDLE_TEXTURE_TYPE) {
            throw RenderException(
                "Unsupported shared texture type on Windows: ${sharedTextures.textureType}"
            )
        }
        bridge = createInteropBridge(device)
        if (bridge == 0L) {
            throw RenderException("Failed to create the D3D11 interop bridge")
        }
        AcceleratedSwingPainter(sharedTextures) { SoftwareSwingPainter(swingLayerProperties) }
    } catch (e: RenderException) {
        Logger.info { "Skiko: accelerated Swing painter unavailable, using software readback (${e.message})" }
        if (bridge != 0L) {
            disposeInteropBridge(bridge)
            bridge = 0L
        }
        null
    }

    override fun dispose() {
        bytesToDraw = ByteArray(0)
        context.close()
        disposeDirectXTexture(texturePtr)
        if (sharedTexturePtr != 0L) {
            disposeDirectXSharedTexture(sharedTexturePtr)
            sharedTexturePtr = 0L
        }
        if (bridge != 0L) {
            disposeInteropBridge(bridge)
            bridge = 0L
        }
        acceleratedPainter?.dispose()
        softwarePainter.dispose()
        super.dispose()
    }

    override fun onRender(g: Graphics2D, width: Int, height: Int, nanoTime: Long) {
        if (width < 1 || height < 1) {
            return
        }

        val accelerated = acceleratedPainter
        if (accelerated != null) {
            try {
                renderAccelerated(accelerated, g, width, height, nanoTime)
                return
            } catch (e: Exception) { // RenderException, or IllegalArgumentException from JBR wrapTexture
                Logger.warn { "Skiko: accelerated frame failed (${e.message}), falling back to software readback" }
                acceleratedPainter = null
                if (sharedTexturePtr != 0L) {
                    disposeDirectXSharedTexture(sharedTexturePtr)
                    sharedTexturePtr = 0L
                }
                if (bridge != 0L) {
                    disposeInteropBridge(bridge)
                    bridge = 0L
                }
            }
        }
        renderSoftware(g, width, height, nanoTime)
    }

    private fun renderAccelerated(
        painter: AcceleratedSwingPainter,
        g: Graphics2D,
        width: Int,
        height: Int,
        nanoTime: Long
    ) {
        autoCloseScope {
            sharedTexturePtr = makeDirectXSharedTexture(device, bridge, sharedTexturePtr, width, height)
            if (sharedTexturePtr == 0L) {
                throw RenderException("Can't allocate shared DirectX resources")
            }
            val renderTarget = BackendRenderTarget(
                makeSharedTextureRenderTarget(sharedTexturePtr)
            ).autoClose()

            val surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
            )?.autoClose() ?: throw RenderException("Cannot create surface")

            val canvas = surface.canvas
            canvas.clear(Color.TRANSPARENT)
            renderDelegate.onRender(canvas, width, height, nanoTime)

            surface.flushAndSubmit(syncCpu = false)
            val blitTexturePtr: Long
            if (pipelinedSync) {
                blitTexturePtr = bridgeCopyPipelined(device, bridge, sharedTexturePtr)
                if (blitTexturePtr == 0L) {
                    throw RenderException("Interop bridge pipelined copy failed")
                }
            } else {
                if (!bridgeCopyAndSync(device, bridge, sharedTexturePtr)) {
                    throw RenderException("Interop bridge copy failed")
                }
                blitTexturePtr = getLegacyD3D11TexturePtr(sharedTexturePtr)
            }
            if (!syncVariantReported) {
                syncVariantReported = true
                val variant = if (pipelinedSync) "a-perf-pipelined3" else "a-corr-drain"
                System.setProperty("skiko.swing.syncVariant", variant)
                System.err.println("SKIKO_SWING_SYNC variant=$variant")
            }
            acceleratedFrameCount++
            if (pipelinedSync && acceleratedFrameCount % 300 == 0L) {
                System.err.println(
                    "SKIKO_SWING_SYNC stalls=${getPipelineStalls(sharedTexturePtr)} frames=$acceleratedFrameCount"
                )
            }
            painter.paint(g, surface, blitTexturePtr)
        }
    }

    /** Steady-state stall counter of the pipelined path (evidence marker). */
    fun pipelineStallsForTesting(): Long =
        if (sharedTexturePtr != 0L) getPipelineStalls(sharedTexturePtr) else -1L

    private fun renderSoftware(g: Graphics2D, width: Int, height: Int, nanoTime: Long) {
        autoCloseScope {
            // We will have [Surface] with width == [alignedWidth],
            // but imitate (for SkikoRenderDelegate and Swing) like it has width == [width].
            val alignedWidth = alignedTextureWidth(width)

            texturePtr = makeDirectXTexture(device, texturePtr, alignedWidth, height)
            if (texturePtr == 0L) {
                throw RenderException("Can't allocate DirectX resources")
            }
            val renderTarget = makeRenderTarget().autoClose()

            val surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
            )?.autoClose() ?: throw RenderException("Cannot create surface")

            val canvas = surface.canvas
            canvas.clear(Color.TRANSPARENT)
            renderDelegate.onRender(canvas, width, height, nanoTime)
            flush(surface, g)
        }
    }

    fun flush(surface: Surface, g: Graphics2D) {
        surface.flushAndSubmit(syncCpu = false)
        waitForCompletion(device, texturePtr)

        softwarePainter.paint(g, surface, texturePtr)
    }

    private fun makeRenderTarget() = BackendRenderTarget(
        makeDirectXRenderTargetOffScreen(texturePtr)
    )
}
