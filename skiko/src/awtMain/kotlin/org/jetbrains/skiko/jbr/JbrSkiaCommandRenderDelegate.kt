package org.jetbrains.skiko.jbr

/**
 * Optional companion interface for [org.jetbrains.skiko.SkikoRenderDelegate] implementations that
 * can emit the temporary JBR Skia command-list ABI directly.
 */
interface JbrSkiaCommandRenderDelegate {
    fun renderJbrSkiaCommandFrame(width: Int, height: Int, nanoTime: Long): IntArray?

    fun renderJbrSkiaCommandFrameInfo(width: Int, height: Int, nanoTime: Long): JbrSkiaCommandFrame? =
        renderJbrSkiaCommandFrame(width, height, nanoTime)?.let { commands ->
            JbrSkiaCommandFrame(commands, JbrSkiaCommandFrameKind.Unknown)
        }
}

data class JbrSkiaCommandFrame(
    val commands: IntArray,
    val kind: JbrSkiaCommandFrameKind,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JbrSkiaCommandFrame) return false
        if (!commands.contentEquals(other.commands)) return false
        return kind == other.kind
    }

    override fun hashCode(): Int {
        var result = commands.contentHashCode()
        result = 31 * result + kind.hashCode()
        return result
    }
}

enum class JbrSkiaCommandFrameKind {
    FullScene,
    InteropOnly,
    Unknown,
}
