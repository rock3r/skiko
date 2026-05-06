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

private const val COMMAND_STREAM_ABI_ID = 106

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
    private val surfaceIdentityTracker = SurfaceIdentityTracker()
    private val commandFrameCache = CommandFrameCache(MIN_MEANINGFUL_COMMAND_WORDS)
    private var commandCanvasUnavailableLogged = false
    private var pictureCanvasUnavailableLogged = false
    private var meaningfulFullSceneFramesForTesting = 0
    private var tinyFullSceneInjectedForTesting = false
    private var forcedContextChangeInjectedForTesting = false

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
    }

    override fun removeNotify() {
        context?.close()
        context = null
        surfaceIdentityTracker.clear()
        commandFrameCache.clear()
        super.removeNotify()
    }

    private fun renderIntoJbrTexture(g: Graphics2D): Boolean {
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: run {
            Logger.info { "SKIKO_JBR_INTEROP_TEXTURE_CANVAS_UNAVAILABLE" }
            return false
        }
        try {
            noteSurfaceIdentity(scope)
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
            noteSurfaceIdentity(scope)
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
        val commandFrame = if (commandDelegate != null) {
            commandDelegate.renderJbrSkiaCommandFrameInfo(renderWidth, renderHeight, frameTime)
                ?: run {
                    Logger.info { "SKIKO_JBR_INTEROP_COMMAND_UNSUPPORTED fallback=picture" }
                    return renderJbrPictureFrame(g)
                }
        } else {
            JbrSkiaCommandFrame(
                buildCommandFrame(renderWidth, renderHeight, frameTime),
                JbrSkiaCommandFrameKind.FullScene,
            )
        }
        val scope = JbrSkiaInterop.acquireCanvas(g) ?: run {
            logCommandCanvasUnavailableOnce()
            return false
        }
        return try {
            if (!noteSurfaceIdentity(scope, clearCommandCaches = true)) {
                return false
            }
            val commandStream = commandFrameCache
                .frameForRendering(commandFrame.tinyFullSceneForTestingIfRequested())
                .corruptDescriptorUseForTestingIfRequested()
                .corruptDescriptorUseAfterEvictForTestingIfRequested()
                .corruptDescriptorVersionForTestingIfRequested()
                .corruptRuntimeEffectSourceForTestingIfRequested()
                .corruptRuntimeEffectChildTypeForTestingIfRequested()
                .corruptForTestingIfRequested()
            commandStreamFallbackReason(commandStream)?.let { reason ->
                JbrSkiaInterop.logFallback(reason)
                return renderJbrPictureFrame(g)
            }
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
            noteSurfaceIdentity(scope)
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

    private fun noteSurfaceIdentity(
        scope: JbrSkiaInterop.ScopedCanvas,
        clearCommandCaches: Boolean = false,
    ): Boolean {
        surfaceIdentityTracker.note(surfaceIdentityForTestingIfRequested(scope))?.let { change ->
            if (change.contextChanged) {
                context?.close()
                context = null
            }
            if (change.contextChanged || change.surfaceChanged) {
                commandFrameCache.clear()
                if (clearCommandCaches && !JbrSkiaCommandRecorderCacheBridge.clearForSurfaceChange(
                        if (change.contextChanged) "contextChanged" else "surfaceChanged"
                    )
                ) {
                    JbrSkiaInterop.logFallback(JbrSkiaInterop.FallbackReason.COMMAND_CACHE_CLEAR_UNAVAILABLE)
                    return false
                }
            }
            Logger.info { change.marker() }
        }
        return true
    }

    private fun surfaceIdentityForTestingIfRequested(scope: JbrSkiaInterop.ScopedCanvas): SurfaceIdentity {
        val identity = SurfaceIdentity(scope.contextId, scope.surfaceId, scope.metalTexturePtr)
        if (!java.lang.Boolean.getBoolean(FORCE_CONTEXT_CHANGE_ONCE_PROPERTY) ||
            forcedContextChangeInjectedForTesting ||
            !surfaceIdentityTracker.hasCurrent ||
            identity.isUnknown
        ) {
            return if (forcedContextChangeInjectedForTesting && !identity.isUnknown) {
                identity.copy(contextId = identity.contextId xor FORCED_CONTEXT_ID_MASK)
            } else {
                identity
            }
        }

        forcedContextChangeInjectedForTesting = true
        Logger.info { "$FORCED_CONTEXT_CHANGE_MARKER oldContextId=${identity.contextId.toHexString()}" }
        return identity.copy(contextId = identity.contextId xor FORCED_CONTEXT_ID_MASK)
    }

    private companion object {
        const val RENDER_DIAGNOSTIC_PROPERTY = "skiko.jbr.interop.renderDiagnostic"
        const val RENDER_COMMANDS_PROPERTY = "skiko.jbr.interop.renderCommands"
        const val RENDER_PICTURE_PROPERTY = "skiko.jbr.interop.renderPicture"
        const val RENDER_TO_TEXTURE_PROPERTY = "skiko.jbr.interop.renderToTexture"
        const val CORRUPT_COMMAND_STREAM_PROPERTY = "skiko.jbr.interop.corruptCommandStream"
        const val CORRUPT_DESCRIPTOR_USE_PROPERTY = "skiko.jbr.interop.corruptDescriptorUseForTesting"
        const val CORRUPT_DESCRIPTOR_USE_AFTER_EVICT_PROPERTY =
            "skiko.jbr.interop.corruptDescriptorUseAfterEvictForTesting"
        const val CORRUPT_DESCRIPTOR_VERSION_PROPERTY = "skiko.jbr.interop.corruptDescriptorVersionForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SOURCE_PROPERTY = "skiko.jbr.interop.corruptRuntimeEffectSourceForTesting"
        const val CORRUPT_RUNTIME_EFFECT_CHILD_TYPE_PROPERTY = "skiko.jbr.interop.corruptRuntimeEffectChildTypeForTesting"
        const val FORCE_TINY_FULL_SCENE_ONCE_PROPERTY = "skiko.jbr.interop.forceTinyFullSceneOnceForTesting"
        const val FORCE_CONTEXT_CHANGE_ONCE_PROPERTY = "skiko.jbr.interop.forceContextChangeOnceForTesting"
        private const val TINY_FULL_SCENE_INJECTED_MARKER = "SKIKO_JBR_INTEROP_TINY_FULL_SCENE_INJECTED"
        private const val DESCRIPTOR_USE_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_DESCRIPTOR_USE_CORRUPTED"
        private const val DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED"
        private const val DESCRIPTOR_VERSION_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_DESCRIPTOR_VERSION_CORRUPTED"
        private const val RUNTIME_EFFECT_SOURCE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SOURCE_CORRUPTED"
        private const val RUNTIME_EFFECT_CHILD_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_CHILD_TYPE_CORRUPTED"
        private const val FORCED_CONTEXT_CHANGE_MARKER = "SKIKO_JBR_INTEROP_FORCED_CONTEXT_CHANGE"
        private const val FORCED_CONTEXT_ID_MASK = 0x4000000000000000L

        private const val COMMAND_CLEAR = 1
        private const val COMMAND_FILL_RECT = 2
        private const val COMMAND_STROKE_LINE = 3
        private const val COMMAND_FILL_OVAL = 4
        private const val COMMAND_STROKE_OVAL = 5
        private const val COMMAND_FILL_RECT_COLOR_FILTER_REF = 47
        private const val COMMAND_DEFINE_SHADER_DESCRIPTOR = 56
        private const val COMMAND_EVICT_SHADER_HANDLE = 57
        private const val COMMAND_FILL_RECT_SHADER_REF = 58
        private const val COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT = 6
        private const val COMMAND_STREAM_MAGIC = 1246972723
        private const val COMMAND_STREAM_HEADER_SIZE = 6
        private const val COMMAND_STREAM_FLAGS_NONE = 0
        private const val COMMAND_COORDINATE_SPACE_SWING_USER = 1
        private const val COMMAND_PAINT_FORMAT_SOLID_ARGB = 1
        private const val MIN_MEANINGFUL_COMMAND_WORDS = 64
        private const val COMMAND_RECORD_FLAGS_NONE = 0
        private const val COMMAND_RECORD_FLAG_ANTIALIAS = 1
        private const val STROKE_CAP_BUTT = 0
        private const val STROKE_CAP_ROUND = 1
        private const val STROKE_JOIN_MITER = 0
        private const val STROKE_JOIN_ROUND = 1
        private val loggedRenderMode = java.util.concurrent.atomic.AtomicBoolean(false)
        private val descriptorUseCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val descriptorUseAfterEvictCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val descriptorVersionCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectSourceCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectChildTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)

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

        private fun emptyCommandFrame(): IntArray =
            IntArray(COMMAND_STREAM_HEADER_SIZE).also { stream ->
                stream[0] = COMMAND_STREAM_MAGIC
                stream[1] = COMMAND_STREAM_ABI_ID
                stream[2] = COMMAND_STREAM_FLAGS_NONE
                stream[3] = 0
                stream[4] = COMMAND_COORDINATE_SPACE_SWING_USER
                stream[5] = COMMAND_PAINT_FORMAT_SOLID_ARGB
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

        private fun IntArray.corruptDescriptorUseForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_DESCRIPTOR_USE_PROPERTY)) return this
            if (!descriptorUseCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                when (op) {
                    COMMAND_FILL_RECT_SHADER_REF -> {
                        if (argsStart + 1 < recordEnd) {
                            return copyOf().also { stream ->
                                stream[argsStart] = Int.MAX_VALUE
                                stream[argsStart + 1] = Int.MAX_VALUE
                                Logger.info { "$DESCRIPTOR_USE_CORRUPTED_MARKER op=$op" }
                            }
                        }
                    }
                    COMMAND_FILL_RECT_COLOR_FILTER_REF -> {
                        if (argsStart + 2 < recordEnd) {
                            return copyOf().also { stream ->
                                stream[argsStart + 1] = Int.MAX_VALUE
                                stream[argsStart + 2] = Int.MAX_VALUE
                                Logger.info { "$DESCRIPTOR_USE_CORRUPTED_MARKER op=$op" }
                            }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptDescriptorUseAfterEvictForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_DESCRIPTOR_USE_AFTER_EVICT_PROPERTY)) return this
            if (!descriptorUseAfterEvictCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_FILL_RECT_SHADER_REF && argsStart + 1 < recordEnd) {
                    val evict = intArrayOf(
                        COMMAND_EVICT_SHADER_HANDLE,
                        5 * Int.SIZE_BYTES,
                        COMMAND_RECORD_FLAGS_NONE,
                        this[argsStart],
                        this[argsStart + 1],
                    )
                    val corrupted = copyOfRange(0, offset) + evict + copyOfRange(offset, size)
                    corrupted[3] = corrupted[3] + evict.size
                    Logger.info { DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED_MARKER }
                    return corrupted
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptDescriptorVersionForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_DESCRIPTOR_VERSION_PROPERTY)) return this
            if (!descriptorVersionCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 3 < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 3] = Int.MAX_VALUE
                        Logger.info { DESCRIPTOR_VERSION_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectSourceForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SOURCE_PROPERTY)) return this
            if (!runtimeEffectSourceCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        corruptRuntimeEffectDescriptorSource(payloadStart, payloadEnd)?.let {
                            return it
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectDescriptorSource(
            payloadStart: Int,
            payloadEnd: Int,
        ): IntArray? {
            val payload = copyOfRange(payloadStart, payloadEnd)
            val skslLength = payload[0]
            val childCount = payload[2]
            val namedUniformCount = payload[3]
            val namedChildCount = payload[4]
            if (skslLength <= 0) return null

            var schemaOffset = 7 + childCount * 2
            repeat(namedUniformCount) {
                if (schemaOffset + 3 > payload.size) return null
                val nameLength = payload[schemaOffset + 2]
                schemaOffset += 3 + nameLength
            }
            repeat(namedChildCount) {
                if (schemaOffset + 2 > payload.size) return null
                val nameLength = payload[schemaOffset + 1]
                schemaOffset += 2 + nameLength
            }
            val skslStart = schemaOffset
            val skslEnd = skslStart + skslLength
            if (skslEnd > payload.size) return null
            val source = payload.copyOfRange(skslStart, skslEnd).map { it.toChar() }.joinToString("")
            val replacement = source.replaceFirst("return", "retxrn")
            if (replacement == source || replacement.length != source.length || replacement.any { it.code !in 1..127 }) {
                return null
            }

            val replacementPayload = payload.copyOf()
            replacement.forEachIndexed { index, char ->
                replacementPayload[skslStart + index] = char.code
            }
            val sourceHash = replacement.shaderSourceHashForTesting()
            replacementPayload[5] = sourceHash.highIntForTesting()
            replacementPayload[6] = sourceHash.lowIntForTesting()

            val corrupted = copyOf()
            replacementPayload.copyInto(corrupted, destinationOffset = payloadStart)
            Logger.info { RUNTIME_EFFECT_SOURCE_CORRUPTED_MARKER }
            return corrupted
        }

        private fun IntArray.corruptRuntimeEffectChildTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_CHILD_TYPE_PROPERTY)) return this
            if (!runtimeEffectChildTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        corruptRuntimeEffectDescriptorChildType(offset, recordEnd, payloadStart, payloadEnd)?.let {
                            return it
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectDescriptorChildType(
            recordStart: Int,
            recordEnd: Int,
            payloadStart: Int,
            payloadEnd: Int,
        ): IntArray? {
            val payload = copyOfRange(payloadStart, payloadEnd)
            val skslLength = payload[0]
            val childCount = payload[2]
            val namedUniformCount = payload[3]
            val namedChildCount = payload[4]
            if (skslLength <= 0 || childCount <= 0 || namedChildCount <= 0) return null

            var schemaOffset = 7 + childCount * 2
            repeat(namedUniformCount) {
                if (schemaOffset + 3 > payload.size) return null
                val nameLength = payload[schemaOffset + 2]
                schemaOffset += 3 + nameLength
            }
            repeat(namedChildCount) {
                if (schemaOffset + 2 > payload.size) return null
                val nameLength = payload[schemaOffset + 1]
                schemaOffset += 2 + nameLength
            }
            val skslStart = schemaOffset
            val skslEnd = skslStart + skslLength
            if (skslEnd > payload.size) return null
            val source = payload.copyOfRange(skslStart, skslEnd).map { it.toChar() }.joinToString("")
            val replacement = source
                .replace("uniform shader content;", "uniform colorFilter content;")
                .replace("half4 base = content.eval(p);", "half4 base = content.eval(half4(0.25, 0.45, 0.85, 1.0));")
            if (replacement == source || replacement.any { it.code !in 1..127 }) return null

            val replacementPayload = payload.copyOfRange(0, skslStart) +
                replacement.map { it.code }.toIntArray() +
                payload.copyOfRange(skslEnd, payload.size)
            replacementPayload[0] = replacement.length
            val sourceHash = replacement.shaderSourceHashForTesting()
            replacementPayload[5] = sourceHash.highIntForTesting()
            replacementPayload[6] = sourceHash.lowIntForTesting()

            val argsStart = recordStart + 3
            val prefix = copyOfRange(0, payloadStart)
            val suffix = copyOfRange(payloadEnd, size)
            val corrupted = prefix + replacementPayload + suffix
            val recordLengthDelta = replacementPayload.size - payload.size
            corrupted[argsStart + 4] = replacementPayload.size
            corrupted[recordStart + 1] = (recordEnd - recordStart + recordLengthDelta) * Int.SIZE_BYTES
            corrupted[3] = corrupted[3] + recordLengthDelta
            Logger.info { RUNTIME_EFFECT_CHILD_TYPE_CORRUPTED_MARKER }
            return corrupted
        }

        private fun String.shaderSourceHashForTesting(): Long {
            var hash = -3750763034362895579L
            forEach { char ->
                hash = hash xor char.code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }

        private fun Long.highIntForTesting(): Int = (this ushr 32).toInt()

        private fun Long.lowIntForTesting(): Int = this.toInt()

        private fun Int?.orZero(): Int = this ?: 0

        private fun IntArray.toDirectLittleEndianByteBuffer(): ByteBuffer =
            ByteBuffer.allocateDirect(size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).also { encoded ->
                forEach(encoded::putInt)
                encoded.flip()
            }
    }

    private fun JbrSkiaCommandFrame.tinyFullSceneForTestingIfRequested(): JbrSkiaCommandFrame {
        if (!java.lang.Boolean.getBoolean(FORCE_TINY_FULL_SCENE_ONCE_PROPERTY)) return this
        if (kind != JbrSkiaCommandFrameKind.FullScene || commands.size < MIN_MEANINGFUL_COMMAND_WORDS) return this

        meaningfulFullSceneFramesForTesting++
        if (meaningfulFullSceneFramesForTesting < 2 || tinyFullSceneInjectedForTesting) return this

        tinyFullSceneInjectedForTesting = true
        Logger.info { "$TINY_FULL_SCENE_INJECTED_MARKER originalCommands=${commands.size}" }
        return JbrSkiaCommandFrame(emptyCommandFrame(), JbrSkiaCommandFrameKind.FullScene)
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

internal data class SurfaceIdentity(val contextId: Long, val surfaceId: Long, val metalTexturePtr: Long) {
    val isUnknown: Boolean get() = contextId == 0L && surfaceId == 0L && metalTexturePtr == 0L
}

internal data class SurfaceIdentityChange(
    val previous: SurfaceIdentity,
    val current: SurfaceIdentity,
) {
    val contextChanged: Boolean get() = previous.contextId != current.contextId

    val surfaceChanged: Boolean get() =
        previous.surfaceId != current.surfaceId || previous.metalTexturePtr != current.metalTexturePtr

    fun marker(): String =
        "SKIKO_JBR_INTEROP_SURFACE_CHANGED oldContextId=${previous.contextId.toHexString()} " +
            "newContextId=${current.contextId.toHexString()} " +
            "contextChanged=$contextChanged " +
            "surfaceChanged=$surfaceChanged " +
            "oldSurfaceId=${previous.surfaceId.toHexString()} " +
            "newSurfaceId=${current.surfaceId.toHexString()} " +
            "oldMetalTexture=${previous.metalTexturePtr.toHexString()} " +
            "newMetalTexture=${current.metalTexturePtr.toHexString()}"
}

internal class SurfaceIdentityTracker {
    private var current: SurfaceIdentity? = null
    val hasCurrent: Boolean get() = current != null

    fun note(next: SurfaceIdentity): SurfaceIdentityChange? {
        if (next.isUnknown) return null

        val previous = current
        current = next
        return if (previous != null && previous != next) {
            SurfaceIdentityChange(previous, next)
        } else {
            null
        }
    }

    fun clear() {
        current = null
    }
}

internal object JbrSkiaCommandRecorderCacheBridge {
    private const val RECORDER_CLASS = "androidx.compose.ui.graphics.JbrSkiaCommandRecorder"
    private const val CLEAR_METHOD = "clearInteropCachesForSurfaceChange"

    fun clearForSurfaceChange(reason: String): Boolean =
        runCatching {
            Class.forName(RECORDER_CLASS)
                .getMethod(CLEAR_METHOD)
                .invoke(null)
            Logger.info { "SKIKO_JBR_INTEROP_COMMAND_CACHES_CLEARED reason=$reason" }
            true
        }.onFailure {
            Logger.info {
                "SKIKO_JBR_INTEROP_COMMAND_CACHES_CLEAR_UNAVAILABLE reason=$reason " +
                    "error=${it.javaClass.simpleName}"
            }
        }.getOrDefault(false)
}

internal class CommandFrameCache(
    private val minimumMeaningfulCommandWords: Int = 64,
) {
    private var lastMeaningfulFrame: IntArray? = null

    fun frameForRendering(frame: JbrSkiaCommandFrame): IntArray {
        val commands = frame.commands
        val cached = lastMeaningfulFrame
        val isMeaningfulFrame = commands.size >= minimumMeaningfulCommandWords
        if ((frame.kind == JbrSkiaCommandFrameKind.FullScene || frame.kind == JbrSkiaCommandFrameKind.Unknown) &&
            isMeaningfulFrame
        ) {
            lastMeaningfulFrame = commands.copyOf()
            return commands
        }
        if (frame.kind == JbrSkiaCommandFrameKind.InteropOnly && cached != null ||
            frame.kind == JbrSkiaCommandFrameKind.FullScene && cached != null ||
            frame.kind == JbrSkiaCommandFrameKind.Unknown && cached != null
        ) {
            Logger.info {
                "SKIKO_JBR_INTEROP_COMMAND_REPLAY_CACHED kind=${frame.kind.name} currentCommands=${commands.size} cachedCommands=${cached.size}"
            }
            return cached
        }
        return commands
    }

    fun clear() {
        lastMeaningfulFrame = null
    }
}

private fun Long.toHexString(): String = "0x${toString(16)}"

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

internal fun commandStreamFallbackReason(commands: IntArray): JbrSkiaInterop.FallbackReason? {
    if (commands.size < 6 || commands[0] != 1246972723) {
        return JbrSkiaInterop.FallbackReason.COMMAND_STREAM_INVALID
    }
    if (commands[1] != COMMAND_STREAM_ABI_ID) {
        return JbrSkiaInterop.FallbackReason.ABI_MISMATCH
    }
    return null
}

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
