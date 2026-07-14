package org.jetbrains.skiko.swing

import com.jetbrains.JBR
import com.jetbrains.SharedTextures
import org.jetbrains.skiko.RenderException
import java.awt.GraphicsConfiguration
import java.awt.Image

internal interface SharedTexturesAdapter {
    val textureType: Int

    fun wrapTexture(gc: GraphicsConfiguration, texturePtr: Long): Image

    companion object {
        /**
         * Windows Direct3D 9Ex shared-handle texture type.
         * Mirrors com.jetbrains.SharedTextures.D3D9EX_SHARED_HANDLE_TEXTURE_TYPE
         * (kept local so an older jbr-api artifact on the compile classpath
         * does not gate the Windows leg).
         */
        const val D3D9EX_SHARED_HANDLE_TEXTURE_TYPE: Int = 2

        fun createSharedTexturesAdapter(): SharedTexturesAdapter {
            if (!JBR.isSharedTexturesSupported()) {
                throw RenderException("Shared textures are not supported")
            }

            val sharedTextures = JBR.getSharedTextures()
            when (sharedTextures.textureType) {
                SharedTextures.METAL_TEXTURE_TYPE,
                D3D9EX_SHARED_HANDLE_TEXTURE_TYPE ->
                    return JbrSharedTexturesAdapter(sharedTextures)
            }
            throw RenderException("Shared textures are not supported")
        }
    }
}

private class JbrSharedTexturesAdapter(
    private val delegate: SharedTextures
) : SharedTexturesAdapter {
    override val textureType: Int
        get() = delegate.textureType

    override fun wrapTexture(gc: GraphicsConfiguration, texturePtr: Long): Image =
        delegate.wrapTexture(gc, texturePtr)
}