package org.jetbrains.skiko.graphicapi

import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.GpuPriority
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.Library
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.isVideoCardSupported

internal object InternalDirectXApi {
    init {
        Library.load()
    }

    private external fun getTextureAlignment(): Long
    private val rowBytesAlignment = getTextureAlignment().toInt()
    private val widthSizeAlignment = rowBytesAlignment / 4

    /**
     * Calculate aligned width/height that is needed for performance optimization,
     * since DirectX uses aligned bytebuffer.
     */
    fun alignedTextureWidth(width: Int) = if (width % widthSizeAlignment != 0) {
        width + widthSizeAlignment - (width % widthSizeAlignment);
    } else {
        width
    }

    // Called from native code
    private fun isAdapterSupported(name: String) = isVideoCardSupported(GraphicsApi.DIRECT3D, hostOs, name)

    fun chooseAdapter(adapterPriority: GpuPriority): NativePointer = chooseAdapter(adapterPriority.ordinal)
    private external fun chooseAdapter(adapterPriority: Int): NativePointer
    external fun createDirectXOffscreenDevice(adapter: NativePointer): NativePointer
    external fun makeDirectXContext(device: NativePointer): NativePointer

    external fun waitForCompletion(device: NativePointer, texturePtr: NativePointer)
    external fun readPixels(texturePtr: NativePointer, byteArray: ByteArray): Boolean


    /**
     * Provides ID3D12Resource texture taking given [oldTexturePtr] into account
     * since it can be reused if width and height are not changed,
     * or the new one will be created.
     */
    external fun makeDirectXTexture(device: NativePointer, oldTexturePtr: NativePointer, width: Int, height: Int): NativePointer
    external fun disposeDirectXTexture(texturePtr: NativePointer)

    external fun makeDirectXRenderTargetOffScreen(texturePtr: NativePointer): NativePointer

    external fun disposeDevice(device: NativePointer)

    // --- JBR SharedTextures interop bridge (see InternalDirectXApi.cc) ---

    /**
     * Creates the D3D11.1 bridge (same adapter as [device]) used to hand
     * Skia's D3D12 output to the Java2D Direct3D 9Ex pipeline via a
     * legacy-shared texture. Returns 0 on failure.
     */
    external fun createInteropBridge(device: NativePointer): NativePointer
    external fun disposeInteropBridge(bridge: NativePointer)

    /**
     * Provides a shareable D3D12 render texture + its bridge resources,
     * reusing [oldTexturePtr] when the size is unchanged.
     */
    external fun makeDirectXSharedTexture(
        device: NativePointer,
        bridge: NativePointer,
        oldTexturePtr: NativePointer,
        width: Int,
        height: Int
    ): NativePointer

    external fun disposeDirectXSharedTexture(texturePtr: NativePointer)

    external fun makeSharedTextureRenderTarget(texturePtr: NativePointer): NativePointer

    /** The ID3D11Texture2D (legacy-shared) pointer passed to JBR's wrapTexture. */
    external fun getLegacyD3D11TexturePtr(texturePtr: NativePointer): NativePointer

    /**
     * Orders GPU work (producer fence), copies into the legacy-shared texture
     * and drains the copy (A-corr bounded CPU wait).
     */
    external fun bridgeCopyAndSync(
        device: NativePointer,
        bridge: NativePointer,
        texturePtr: NativePointer
    ): Boolean

    /**
     * A-perf: copies into the current slot without waiting and returns the
     * previous slot's legacy texture for the blit (one frame of latency,
     * no steady-state CPU wait). Returns 0 on failure.
     */
    external fun bridgeCopyPipelined(
        device: NativePointer,
        bridge: NativePointer,
        texturePtr: NativePointer
    ): NativePointer

    /** Steady-state stall count of the pipelined path (gate: ~0). */
    external fun getPipelineStalls(texturePtr: NativePointer): Long
}
