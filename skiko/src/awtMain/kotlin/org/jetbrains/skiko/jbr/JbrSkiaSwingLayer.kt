package org.jetbrains.skiko.jbr

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.autoCloseScope
import org.jetbrains.skiko.makeMetalContext
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Color as AwtColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.accessibility.AccessibleContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Swing layer entry point for the experimental JBR-owned Skia interop path.
 *
 * The first implementation slice only exposes the feature-gated routing and
 * ABI discovery surface. Rendering intentionally falls back to [SkiaSwingLayer]
 * until the native direct-canvas redrawer is wired.
 */
@ExperimentalSkikoApi
class JbrSkiaSwingLayer(
    private val renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
    accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
    properties: SkiaLayerProperties = SkiaLayerProperties(),
) : SkiaSwingLayer(renderDelegate, analytics, accessibleContextProvider, properties) {
    private var context: DirectContext? = null
    private var commandCanvasUnavailableLogged = false
    private var pictureCanvasUnavailableLogged = false

    override fun paint(g: Graphics) {
        logRenderModeOnce(renderDelegate)
        var renderedWithJbrTexture = false
        if (g is Graphics2D && java.lang.Boolean.getBoolean(RENDER_COMMANDS_PROPERTY)) {
            renderedWithJbrTexture = renderJbrCommandFrame(g)
        } else if (g is Graphics2D && java.lang.Boolean.getBoolean(RENDER_PICTURE_PROPERTY)) {
            renderedWithJbrTexture = renderJbrPictureFrame(g)
        } else if (g is Graphics2D && java.lang.Boolean.getBoolean(RENDER_DIAGNOSTIC_PROPERTY)) {
            renderedWithJbrTexture = renderJbrDiagnosticFrame(g)
        } else if (g is Graphics2D && java.lang.Boolean.getBoolean(RENDER_TO_TEXTURE_PROPERTY)) {
            renderedWithJbrTexture = renderIntoJbrTexture(g)
        } else if (g is Graphics2D) {
            JbrSkiaInterop.acquireCanvas(g)?.close()
        }
        if (!renderedWithJbrTexture) {
            super.paint(g)
        }
        if (g is Graphics2D) {
            JbrSkiaDebugOverlay.paint(g, renderedWithJbrTexture)
        }
        if (renderedWithJbrTexture && java.lang.Boolean.getBoolean(RENDER_COMMANDS_PROPERTY)) {
            repaint()
        }
    }

    override fun removeNotify() {
        context?.close()
        context = null
        super.removeNotify()
    }

    private fun renderIntoJbrTexture(g: Graphics2D): Boolean {
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: run {
            Logger.info { "SKIKO_JBR_INTEROP_TEXTURE_CANVAS_UNAVAILABLE" }
            return false
        }
        try {
            val texturePtr = scope.metalTexturePtr
            if (texturePtr == 0L) {
                return false
            }

            val scale = graphicsConfiguration.defaultTransform.scaleX
            val renderWidth = (width * scale).toInt().coerceAtLeast(1)
            val renderHeight = (height * scale).toInt().coerceAtLeast(1)
            val directContext = context ?: makeMetalContext().also { context = it }

            autoCloseScope {
                val renderTarget = BackendRenderTarget
                    .makeMetal(renderWidth, renderHeight, texturePtr)
                    .autoClose()
                val surface = Surface.makeFromBackendRenderTarget(
                    directContext,
                    renderTarget,
                    SurfaceOrigin.TOP_LEFT,
                    SurfaceColorFormat.BGRA_8888,
                    ColorSpace.sRGB,
                    SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN),
                )?.autoClose() ?: throw IllegalStateException("Cannot wrap JBR Metal texture as a Skia surface")

                surface.canvas.clear(Color.TRANSPARENT)
                renderDelegate.onRender(surface.canvas, renderWidth, renderHeight, System.nanoTime())
                surface.flushAndSubmit(syncCpu = true)
                scope.flush()
            }
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            scope.close()
        }
    }

    private fun renderJbrPictureFrame(g: Graphics2D): Boolean {
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: run {
            logPictureCanvasUnavailableOnce()
            return false
        }
        return try {
            val frameTime = System.nanoTime()
            val frameSize = deviceFrameSize(
                width = width,
                height = height,
                scale = graphicsConfiguration.defaultTransform.scaleX.toFloat()
            )
            val renderWidth = frameSize.width
            val renderHeight = frameSize.height
            val pictureBytes = recordPictureFrame(renderWidth, renderHeight, frameTime)
            scope.renderPictureFrame(renderWidth, renderHeight, frameTime, pictureBytes).also { rendered ->
                Logger.info {
                    pictureFrameMarker(renderWidth, renderHeight, pictureBytes.size, rendered)
                }
                if (rendered) {
                    scope.flush()
                }
            }
        } catch (e: Throwable) {
            Logger.warn(e) { "JBR Skia picture frame failed; falling back to Swing renderer" }
            false
        } finally {
            scope.close()
        }
    }

    private fun renderJbrCommandFrame(g: Graphics2D): Boolean {
        val frameTime = System.nanoTime()
        val frameSize = deviceFrameSize(
            width = width,
            height = height,
            scale = graphicsConfiguration.defaultTransform.scaleX.toFloat()
        )
        val renderWidth = frameSize.width
        val renderHeight = frameSize.height
        val commandDelegate = renderDelegate as? JbrSkiaCommandRenderDelegate
        val commands = if (commandDelegate != null) {
            commandDelegate.renderJbrSkiaCommandFrame(renderWidth, renderHeight, frameTime)
                ?: run {
                    Logger.info { "SKIKO_JBR_INTEROP_COMMAND_UNSUPPORTED fallback=picture" }
                    return renderJbrPictureFrame(g)
                }
        } else {
            buildCommandFrame(renderWidth, renderHeight, frameTime)
        }
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: run {
            logCommandCanvasUnavailableOnce()
            return false
        }
        return try {
            val commandStream = commands.corruptForTestingIfRequested()
            val commandBuffer = commandStream.toDirectLittleEndianByteBuffer()
            scope.renderCommandDirectFrame(renderWidth, renderHeight, frameTime, commandBuffer).also { rendered ->
                Logger.info {
                    commandFrameMarker(renderWidth, renderHeight, commandStream.size, rendered)
                }
                if (rendered) {
                    scope.flush()
                } else {
                    JbrSkiaInterop.logFallback(JbrSkiaInterop.FallbackReason.COMMAND_STREAM_INVALID)
                }
            }
        } catch (e: Throwable) {
            Logger.warn(e) { "JBR Skia command frame failed; falling back to Swing renderer" }
            false
        } finally {
            scope.close()
        }
    }

    private fun logCommandCanvasUnavailableOnce() {
        if (!commandCanvasUnavailableLogged) {
            commandCanvasUnavailableLogged = true
            Logger.info { "SKIKO_JBR_INTEROP_COMMAND_CANVAS_UNAVAILABLE" }
        }
    }

    private fun logPictureCanvasUnavailableOnce() {
        if (!pictureCanvasUnavailableLogged) {
            pictureCanvasUnavailableLogged = true
            Logger.info { "SKIKO_JBR_INTEROP_PICTURE_CANVAS_UNAVAILABLE" }
        }
    }

    private fun renderJbrDiagnosticFrame(g: Graphics2D): Boolean {
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: return false
        return try {
            scope.renderDiagnosticFrame(width, height, System.nanoTime()).also { rendered ->
                if (rendered) {
                    scope.flush()
                }
            }
        } catch (_: Throwable) {
            false
        } finally {
            scope.close()
        }
    }

    private companion object {
        const val RENDER_DIAGNOSTIC_PROPERTY = "skiko.jbr.interop.renderDiagnostic"
        const val RENDER_COMMANDS_PROPERTY = "skiko.jbr.interop.renderCommands"
        const val RENDER_PICTURE_PROPERTY = "skiko.jbr.interop.renderPicture"
        const val RENDER_TO_TEXTURE_PROPERTY = "skiko.jbr.interop.renderToTexture"
        const val CORRUPT_COMMAND_STREAM_PROPERTY = "skiko.jbr.interop.corruptCommandStream"

        private const val COMMAND_CLEAR = 1
        private const val COMMAND_FILL_RECT = 2
        private const val COMMAND_STROKE_LINE = 3
        private const val COMMAND_FILL_OVAL = 4
        private const val COMMAND_STROKE_OVAL = 5
        private const val COMMAND_STREAM_MAGIC = 1246972723
        private const val COMMAND_STREAM_ABI_ID = 17
        private const val COMMAND_STREAM_HEADER_SIZE = 6
        private const val COMMAND_STREAM_FLAGS_NONE = 0
        private const val COMMAND_COORDINATE_SPACE_SWING_USER = 1
        private const val COMMAND_PAINT_FORMAT_SOLID_ARGB = 1
        private const val COMMAND_RECORD_FLAGS_NONE = 0
        private const val COMMAND_RECORD_FLAG_ANTIALIAS = 1
        private const val STROKE_CAP_BUTT = 0
        private const val STROKE_CAP_ROUND = 1
        private const val STROKE_JOIN_MITER = 0
        private const val STROKE_JOIN_ROUND = 1
        private val loggedRenderMode = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun logRenderModeOnce(renderDelegate: SkikoRenderDelegate) {
            if (loggedRenderMode.compareAndSet(false, true)) {
                Logger.info {
                    "SKIKO_JBR_INTEROP_RENDER_MODE commands=${java.lang.Boolean.getBoolean(RENDER_COMMANDS_PROPERTY)} " +
                        "picture=${java.lang.Boolean.getBoolean(RENDER_PICTURE_PROPERTY)} " +
                        "diagnostic=${java.lang.Boolean.getBoolean(RENDER_DIAGNOSTIC_PROPERTY)} " +
                        "delegateCommands=${renderDelegate is JbrSkiaCommandRenderDelegate}"
                }
            }
        }

        private fun buildCommandFrame(width: Int, height: Int, frameTimeNanos: Long): IntArray {
            val phase = ((frameTimeNanos / 16_000_000L) % 900L).toInt() / 900f
            val stripeHeight = height / 5
            val commands = CommandStreamWriter(512)

            commands.addCommand(COMMAND_FILL_RECT, COMMAND_RECORD_FLAGS_NONE, 0xff2da44e.toInt(), 0, 0, width, stripeHeight * 2, 0)
            commands.addCommand(COMMAND_FILL_RECT, COMMAND_RECORD_FLAGS_NONE, 0xff0969da.toInt(), 0, stripeHeight * 2, width, height - stripeHeight * 2, 0)
            commands.addCommand(COMMAND_FILL_RECT, COMMAND_RECORD_FLAG_ANTIALIAS, 0xff824edf.toInt(), 96, 112, 440, 240, 0)
            commands.addCommand(COMMAND_FILL_OVAL, COMMAND_RECORD_FLAG_ANTIALIAS, 0xffffd33d.toInt(), width - 308, 108, 168, 168)

            val progressWidth = (width * 0.24f).toInt().coerceAtLeast(120)
            val progressX = ((phase * width).toInt() % width) - progressWidth
            commands.addCommand(COMMAND_FILL_RECT, COMMAND_RECORD_FLAG_ANTIALIAS, 0xffffa657.toInt(), progressX, 24, progressWidth, 36, 0)

            val lineStep = 86
            val linePhase = (phase * 172f).toInt()
            for (lineX in -120 + linePhase until width + 160 step lineStep) {
                commands.addCommand(COMMAND_STROKE_LINE, COMMAND_RECORD_FLAG_ANTIALIAS, 0x52ffffff, lineX, 76, lineX + 144, height - 36, 6, STROKE_CAP_BUTT, STROKE_JOIN_MITER, 4000)
            }

            val centerX = (width * 0.52f).toInt()
            val centerY = (height * 0.55f).toInt()
            val radiusX = 360
            val radiusY = 240
            val spokePhase = phase * PI * 2.0
            repeat(18) { index ->
                val angle = spokePhase + index * (PI * 2.0 / 18.0)
                val outerX = centerX + (cos(angle) * radiusX).toInt()
                val outerY = centerY + (sin(angle) * radiusY).toInt()
                commands.addCommand(COMMAND_STROKE_LINE, COMMAND_RECORD_FLAG_ANTIALIAS, 0xffffa657.toInt(), centerX, centerY, outerX, outerY, 12, STROKE_CAP_ROUND, STROKE_JOIN_MITER, 4000)
                commands.addCommand(COMMAND_FILL_OVAL, COMMAND_RECORD_FLAG_ANTIALIAS, 0xffffffff.toInt(), outerX - 20, outerY - 20, 40, 40)
            }
            commands.addCommand(COMMAND_STROKE_OVAL, COMMAND_RECORD_FLAG_ANTIALIAS, 0x8cffffff.toInt(), centerX - 380, centerY - 380, 760, 760, 10, STROKE_CAP_BUTT, STROKE_JOIN_ROUND, 4000)
            return commands.toIntArray()
        }

        private class CommandStreamWriter(initialCapacity: Int) {
            private val payload = ArrayList<Int>(initialCapacity)

            fun addCommand(op: Int, recordFlags: Int = COMMAND_RECORD_FLAGS_NONE, vararg args: Int) {
                payload.add(op)
                payload.add((args.size + 3) * Int.SIZE_BYTES)
                payload.add(recordFlags)
                args.forEach(payload::add)
            }

            fun toIntArray(): IntArray =
                IntArray(COMMAND_STREAM_HEADER_SIZE + payload.size).also { stream ->
                    stream[0] = COMMAND_STREAM_MAGIC
                    stream[1] = COMMAND_STREAM_ABI_ID
                    stream[2] = COMMAND_STREAM_FLAGS_NONE
                    stream[3] = payload.size
                    stream[4] = COMMAND_COORDINATE_SPACE_SWING_USER
                    stream[5] = COMMAND_PAINT_FORMAT_SOLID_ARGB
                    payload.forEachIndexed { index, command -> stream[COMMAND_STREAM_HEADER_SIZE + index] = command }
                }
        }

        private fun IntArray.corruptForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_COMMAND_STREAM_PROPERTY)) return this
            return copyOf().also { stream ->
                if (stream.size > 2) {
                    stream[2] = 1
                }
            }
        }

        private fun IntArray.toDirectLittleEndianByteBuffer(): ByteBuffer =
            ByteBuffer.allocateDirect(size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).also { encoded ->
                forEach(encoded::putInt)
                encoded.flip()
            }
    }

    private fun recordPictureFrame(width: Int, height: Int, frameTimeNanos: Long): ByteArray {
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(Rect(0f, 0f, width.toFloat(), height.toFloat()))
        renderDelegate.onRender(canvas, width, height, frameTimeNanos)
        val picture = recorder.finishRecordingAsPicture()
        return picture.serializeToData().bytes
    }
}

internal data class DeviceFrameSize(val width: Int, val height: Int)

internal fun deviceFrameSize(width: Int, height: Int, scale: Float): DeviceFrameSize {
    val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return DeviceFrameSize(
        width = (width * safeScale).toInt().coerceAtLeast(1),
        height = (height * safeScale).toInt().coerceAtLeast(1),
    )
}

internal fun pictureFrameMarker(width: Int, height: Int, bytes: Int, rendered: Boolean): String =
    "SKIKO_JBR_INTEROP_PICTURE_FRAME width=$width height=$height bytes=$bytes rendered=$rendered"

internal fun commandFrameMarker(width: Int, height: Int, commands: Int, rendered: Boolean): String =
    "SKIKO_JBR_INTEROP_COMMAND_FRAME width=$width height=$height commands=$commands rendered=$rendered"

internal object JbrSkiaDebugOverlay {
    private const val DEBUG_OVERLAY_PROPERTY = "skiko.jbr.interop.debugOverlay"

    fun paint(g: Graphics2D, acquiredJbrScope: Boolean = false) {
        if (!java.lang.Boolean.getBoolean(DEBUG_OVERLAY_PROPERTY)) return

        val previousColor = g.color
        val previousFont = g.font
        try {
            val text = if (acquiredJbrScope) "JBR Skia scope" else "JBR Skia path"
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            val metrics = g.fontMetrics
            val width = metrics.stringWidth(text) + 12
            val height = metrics.height + 6
            val x = 8
            val y = 8
            g.color = if (acquiredJbrScope) AwtColor(255, 192, 0, 235) else AwtColor(0, 96, 72, 210)
            g.fillRoundRect(x, y, width, height, 8, 8)
            g.color = if (acquiredJbrScope) AwtColor(32, 24, 0) else AwtColor(216, 255, 239)
            g.drawString(text, x + 6, y + metrics.ascent + 3)
        } finally {
            g.color = previousColor
            g.font = previousFont
        }
    }
}
