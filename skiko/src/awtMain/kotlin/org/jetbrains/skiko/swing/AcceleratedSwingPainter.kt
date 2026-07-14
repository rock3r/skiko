package org.jetbrains.skiko.swing

import com.jetbrains.SharedTextures
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.swing.SharedTexturesAdapter.Companion.D3D9EX_SHARED_HANDLE_TEXTURE_TYPE
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Image

internal class AcceleratedSwingPainter(
    internal val sharedTextures: SharedTexturesAdapter,
    private val fallbackPainterCreator: () -> SwingPainter,
) : SwingPainter {
    var imageWrapper: Image? = null
        private set

    var texturePtr: Long = 0L
        private set

    private var gc: GraphicsConfiguration = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration

    // Wrapper cache: the pipelined sync variant alternates between two
    // consumer textures every frame; wrapping opens the shared handle on the
    // Java2D device, so each texture must be wrapped exactly once.
    private val wrapperCache = HashMap<Long, Image>()

    private var fallbackPainter: SwingPainter? = null

    private var identityReported = false
    private var fallbackReported = false

    private val acceleratedIdentity: String
        get() = when (sharedTextures.textureType) {
            SharedTextures.METAL_TEXTURE_TYPE -> "accelerated-metal-sharedtexture"
            D3D9EX_SHARED_HANDLE_TEXTURE_TYPE -> "accelerated-d3d9ex-sharedtexture"
            else -> "accelerated-unknown"
        }

    override fun paint(g: Graphics2D, surface: Surface, texture: Long) {
        val deviceConfiguration = g.deviceConfiguration
        if (!deviceConfiguration.isSharedTextureCompatibleConfiguration()) {
            imageWrapper = null
            texturePtr = 0L
            if (!fallbackReported) {
                fallbackReported = true
                System.setProperty("skiko.swing.painterFallback", "incompatible-config")
                System.err.println(
                    "SKIKO_SWING_PAINTER identity=incompatible-config-fallback " +
                        "configClass=${deviceConfiguration.javaClass.name} " +
                        "textureType=${sharedTextures.textureType}"
                )
            }
            if (fallbackPainter == null) fallbackPainter = fallbackPainterCreator()
            fallbackPainter?.paint(g, surface, texture)
            return
        }

        if (deviceConfiguration != gc) {
            gc = deviceConfiguration
            wrapperCache.clear()
        }
        if (texturePtr != texture || imageWrapper == null) {
            texturePtr = texture
            if (wrapperCache.size > 8) wrapperCache.clear() // resize churn bound
            imageWrapper = wrapperCache.getOrPut(texture) {
                sharedTextures.wrapTexture(gc, texture)
            }
        }

        if (!identityReported) {
            identityReported = true
            System.setProperty("skiko.swing.painterIdentity", acceleratedIdentity)
            System.setProperty("skiko.swing.renderMode", "accelerated")
            System.err.println(
                "SKIKO_SWING_PAINTER identity=$acceleratedIdentity " +
                    "surfaceWidth=${surface.width} surfaceHeight=${surface.height} " +
                    "configClass=${deviceConfiguration.javaClass.name}"
            )
        }

        g.drawImage(imageWrapper, 0, 0, null)
    }

    override fun dispose() {
        fallbackPainter?.dispose()
    }

    internal fun setCachedStateForTesting(imageWrapper: Image?, texturePtr: Long, gc: GraphicsConfiguration) {
        this.imageWrapper = imageWrapper
        this.texturePtr = texturePtr
        this.gc = gc
    }

    private fun GraphicsConfiguration.isSharedTextureCompatibleConfiguration(): Boolean =
        when (sharedTextures.textureType) {
            SharedTextures.METAL_TEXTURE_TYPE ->
                javaClass.name == "sun.java2d.metal.MTLGraphicsConfig"
            D3D9EX_SHARED_HANDLE_TEXTURE_TYPE ->
                javaClass.name == "sun.java2d.d3d.D3DGraphicsConfig"
            else -> false
        }
}
