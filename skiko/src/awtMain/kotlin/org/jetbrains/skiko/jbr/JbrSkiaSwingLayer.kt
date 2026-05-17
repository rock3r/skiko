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
                .corruptTextFontSizeForTestingIfRequested()
                .corruptLinearGradientStrokeWidthForTestingIfRequested()
                .corruptLinearGradientTileModeForTestingIfRequested()
                .corruptLinearGradientColorCountForTestingIfRequested()
                .corruptLinearGradientStopOrderForTestingIfRequested()
                .corruptLinearGradientPathTileModeForTestingIfRequested()
                .corruptLinearGradientPathColorCountForTestingIfRequested()
                .corruptLinearGradientPathStopOrderForTestingIfRequested()
                .corruptLinearGradientPathFillTypeForTestingIfRequested()
                .corruptLinearGradientPathDataLengthForTestingIfRequested()
                .corruptSweepGradientColorCountForTestingIfRequested()
                .corruptSweepGradientStopOrderForTestingIfRequested()
                .corruptRadialGradientRadiusForTestingIfRequested()
                .corruptRadialGradientTileModeForTestingIfRequested()
                .corruptRadialGradientColorCountForTestingIfRequested()
                .corruptRadialGradientStopOrderForTestingIfRequested()
                .corruptRadialGradientPathRadiusForTestingIfRequested()
                .corruptRadialGradientPathTileModeForTestingIfRequested()
                .corruptRadialGradientPathColorCountForTestingIfRequested()
                .corruptRadialGradientPathStopOrderForTestingIfRequested()
                .corruptRadialGradientPathFillTypeForTestingIfRequested()
                .corruptRadialGradientPathDataLengthForTestingIfRequested()
                .corruptSweepGradientPathColorCountForTestingIfRequested()
                .corruptSweepGradientPathStopOrderForTestingIfRequested()
                .corruptSweepGradientPathFillTypeForTestingIfRequested()
                .corruptSweepGradientPathDataLengthForTestingIfRequested()
                .corruptDescriptorUseForTestingIfRequested()
                .corruptDescriptorUseAfterEvictForTestingIfRequested()
                .corruptEffectChildUseAfterEvictForTestingIfRequested()
                .corruptEffectChildMissingForTestingIfRequested()
                .corruptEffectDescriptorTypeForTestingIfRequested()
                .corruptEffectDescriptorVersionForTestingIfRequested()
                .corruptEffectDescriptorPayloadCountForTestingIfRequested()
                .corruptEffectDescriptorRecordLengthForTestingIfRequested()
                .corruptLightingFilterDescriptorPayloadCountForTestingIfRequested()
                .corruptTintColorFilterDescriptorBlendModeForTestingIfRequested()
                .corruptColorMatrixFilterDescriptorPayloadForTestingIfRequested()
                .corruptBlurImageFilterDescriptorSigmaForTestingIfRequested()
                .corruptBlurImageFilterDescriptorNegativeSigmaForTestingIfRequested()
                .corruptBlurImageFilterDescriptorTileModeForTestingIfRequested()
                .corruptOffsetImageFilterDescriptorDeltaForTestingIfRequested()
                .corruptCornerPathEffectDescriptorRadiusForTestingIfRequested()
                .corruptCornerPathEffectDescriptorNegativeRadiusForTestingIfRequested()
                .corruptStampedPathEffectDescriptorAdvanceForTestingIfRequested()
                .corruptStampedPathEffectDescriptorZeroAdvanceForTestingIfRequested()
                .corruptStampedPathEffectDescriptorPhaseForTestingIfRequested()
                .corruptStampedPathEffectDescriptorNegativePhaseForTestingIfRequested()
                .corruptStampedPathEffectDescriptorStyleForTestingIfRequested()
                .corruptStampedPathEffectDescriptorFillTypeForTestingIfRequested()
                .corruptStampedPathEffectDescriptorPathDataLengthForTestingIfRequested()
                .corruptStampedPathEffectDescriptorNegativePathDataLengthForTestingIfRequested()
                .corruptChainPathEffectDescriptorPayloadCountForTestingIfRequested()
                .corruptShaderDescriptorTypeForTestingIfRequested()
                .corruptShaderDescriptorPayloadCountForTestingIfRequested()
                .corruptColorShaderDescriptorPayloadCountForTestingIfRequested()
                .corruptShaderColorFilterDescriptorPayloadCountForTestingIfRequested()
                .corruptTransformedShaderDescriptorPayloadCountForTestingIfRequested()
                .corruptShaderDescriptorRecordLengthForTestingIfRequested()
                .corruptCompositeShaderDescriptorBlendModeForTestingIfRequested()
                .corruptLinearGradientShaderDescriptorTileModeForTestingIfRequested()
                .corruptLinearGradientShaderDescriptorStopOrderForTestingIfRequested()
                .corruptRadialGradientShaderDescriptorRadiusForTestingIfRequested()
                .corruptRadialGradientShaderDescriptorTileModeForTestingIfRequested()
                .corruptRadialGradientShaderDescriptorStopOrderForTestingIfRequested()
                .corruptSweepGradientShaderDescriptorColorCountForTestingIfRequested()
                .corruptSweepGradientShaderDescriptorStopOrderForTestingIfRequested()
                .corruptImageShaderDescriptorWidthForTestingIfRequested()
                .corruptImageShaderDescriptorMaxWidthForTestingIfRequested()
                .corruptImageShaderDescriptorHeightForTestingIfRequested()
                .corruptImageShaderDescriptorMaxHeightForTestingIfRequested()
                .corruptImageShaderDescriptorTileModeXForTestingIfRequested()
                .corruptImageShaderDescriptorTileModeYForTestingIfRequested()
                .corruptPerlinNoiseShaderDescriptorForTestingIfRequested()
                .corruptDescriptorVersionForTestingIfRequested()
                .corruptColorFilterHandleTypeForTestingIfRequested()
                .corruptImageFilterHandleTypeForTestingIfRequested()
                .corruptPathEffectHandleTypeForTestingIfRequested()
                .corruptShaderHandleTypeForTestingIfRequested()
                .corruptShaderChildMissingForTestingIfRequested()
                .corruptRuntimeEffectColorFilterSkslLengthForTestingIfRequested()
                .corruptRuntimeEffectColorFilterUniformFloatCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterNegativeUniformFloatCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterChildCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterNegativeChildCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterNamedUniformCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterNegativeNamedUniformCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterNamedChildCountForTestingIfRequested()
                .corruptRuntimeEffectColorFilterNegativeNamedChildCountForTestingIfRequested()
                .corruptRuntimeEffectShaderSkslLengthForTestingIfRequested()
                .corruptRuntimeEffectShaderUniformFloatCountForTestingIfRequested()
                .corruptRuntimeEffectShaderNegativeUniformFloatCountForTestingIfRequested()
                .corruptRuntimeEffectShaderChildCountForTestingIfRequested()
                .corruptRuntimeEffectShaderNegativeChildCountForTestingIfRequested()
                .corruptRuntimeEffectShaderNamedUniformCountForTestingIfRequested()
                .corruptRuntimeEffectShaderNegativeNamedUniformCountForTestingIfRequested()
                .corruptRuntimeEffectShaderNamedChildCountForTestingIfRequested()
                .corruptRuntimeEffectShaderNegativeNamedChildCountForTestingIfRequested()
                .corruptRuntimeEffectShaderSourceHashForTestingIfRequested()
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
        const val CORRUPT_TEXT_FONT_SIZE_PROPERTY = "skiko.jbr.interop.corruptTextFontSizeForTesting"
        const val CORRUPT_TEXT_FONT_WEIGHT_PROPERTY = "skiko.jbr.interop.corruptTextFontWeightForTesting"
        const val CORRUPT_TEXT_FONT_WIDTH_PROPERTY = "skiko.jbr.interop.corruptTextFontWidthForTesting"
        const val CORRUPT_TEXT_FONT_SLANT_PROPERTY = "skiko.jbr.interop.corruptTextFontSlantForTesting"
        const val CORRUPT_TEXT_FONT_FAMILY_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptTextFontFamilyCountForTesting"
        const val CORRUPT_PARAGRAPH_FONT_SIZE_PROPERTY = "skiko.jbr.interop.corruptParagraphFontSizeForTesting"
        const val CORRUPT_PARAGRAPH_FONT_WEIGHT_PROPERTY = "skiko.jbr.interop.corruptParagraphFontWeightForTesting"
        const val CORRUPT_PARAGRAPH_FONT_WIDTH_PROPERTY = "skiko.jbr.interop.corruptParagraphFontWidthForTesting"
        const val CORRUPT_PARAGRAPH_FONT_SLANT_PROPERTY = "skiko.jbr.interop.corruptParagraphFontSlantForTesting"
        const val CORRUPT_PARAGRAPH_FONT_FAMILY_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptParagraphFontFamilyCountForTesting"
        const val CORRUPT_LINEAR_GRADIENT_STROKE_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientStrokeWidthForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectStrokeWidthForTesting"
        const val CORRUPT_RADIAL_GRADIENT_STROKE_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientStrokeWidthForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectStrokeWidthForTesting"
        const val CORRUPT_SWEEP_GRADIENT_STROKE_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientStrokeWidthForTesting"
        const val CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STROKE_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientRoundRectStrokeWidthForTesting"
        const val CORRUPT_LINEAR_GRADIENT_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientTileModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectTileModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_STROKE_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientStrokeTileModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectStrokeTileModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientColorCountForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectColorCountForTesting"
        const val CORRUPT_LINEAR_GRADIENT_STROKE_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientStrokeColorCountForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectStrokeColorCountForTesting"
        const val CORRUPT_LINEAR_GRADIENT_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientStopOrderForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectStopOrderForTesting"
        const val CORRUPT_LINEAR_GRADIENT_STROKE_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientStrokeStopOrderForTesting"
        const val CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientRoundRectStrokeStopOrderForTesting"
        const val CORRUPT_LINEAR_GRADIENT_PATH_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientPathTileModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_PATH_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientPathColorCountForTesting"
        const val CORRUPT_LINEAR_GRADIENT_PATH_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientPathStopOrderForTesting"
        const val CORRUPT_LINEAR_GRADIENT_PATH_FILL_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientPathFillTypeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_PATH_DATA_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientPathDataLengthForTesting"
        const val CORRUPT_SWEEP_GRADIENT_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientColorCountForTesting"
        const val CORRUPT_SWEEP_GRADIENT_ROUND_RECT_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientRoundRectColorCountForTesting"
        const val CORRUPT_SWEEP_GRADIENT_STROKE_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientStrokeColorCountForTesting"
        const val CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientRoundRectStrokeColorCountForTesting"
        const val CORRUPT_SWEEP_GRADIENT_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientStopOrderForTesting"
        const val CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientRoundRectStopOrderForTesting"
        const val CORRUPT_SWEEP_GRADIENT_STROKE_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientStrokeStopOrderForTesting"
        const val CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientRoundRectStrokeStopOrderForTesting"
        const val CORRUPT_SWEEP_GRADIENT_PATH_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientPathColorCountForTesting"
        const val CORRUPT_SWEEP_GRADIENT_PATH_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientPathStopOrderForTesting"
        const val CORRUPT_SWEEP_GRADIENT_PATH_FILL_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientPathFillTypeForTesting"
        const val CORRUPT_SWEEP_GRADIENT_PATH_DATA_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientPathDataLengthForTesting"
        const val CORRUPT_RADIAL_GRADIENT_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRadiusForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectRadiusForTesting"
        const val CORRUPT_RADIAL_GRADIENT_STROKE_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientStrokeRadiusForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectStrokeRadiusForTesting"
        const val CORRUPT_RADIAL_GRADIENT_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientTileModeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectTileModeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_STROKE_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientStrokeTileModeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectStrokeTileModeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientColorCountForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectColorCountForTesting"
        const val CORRUPT_RADIAL_GRADIENT_STROKE_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientStrokeColorCountForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectStrokeColorCountForTesting"
        const val CORRUPT_RADIAL_GRADIENT_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientStopOrderForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectStopOrderForTesting"
        const val CORRUPT_RADIAL_GRADIENT_STROKE_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientStrokeStopOrderForTesting"
        const val CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientRoundRectStrokeStopOrderForTesting"
        const val CORRUPT_RADIAL_GRADIENT_PATH_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientPathRadiusForTesting"
        const val CORRUPT_RADIAL_GRADIENT_PATH_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientPathTileModeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_PATH_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientPathColorCountForTesting"
        const val CORRUPT_RADIAL_GRADIENT_PATH_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientPathStopOrderForTesting"
        const val CORRUPT_RADIAL_GRADIENT_PATH_FILL_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientPathFillTypeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_PATH_DATA_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientPathDataLengthForTesting"
        const val CORRUPT_DESCRIPTOR_USE_PROPERTY = "skiko.jbr.interop.corruptDescriptorUseForTesting"
        const val CORRUPT_DESCRIPTOR_USE_AFTER_EVICT_PROPERTY =
            "skiko.jbr.interop.corruptDescriptorUseAfterEvictForTesting"
        const val CORRUPT_EFFECT_CHILD_USE_AFTER_EVICT_PROPERTY =
            "skiko.jbr.interop.corruptEffectChildUseAfterEvictForTesting"
        const val CORRUPT_EFFECT_CHILD_MISSING_PROPERTY =
            "skiko.jbr.interop.corruptEffectChildMissingForTesting"
        const val CORRUPT_EFFECT_DESCRIPTOR_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptEffectDescriptorTypeForTesting"
        const val CORRUPT_EFFECT_DESCRIPTOR_VERSION_PROPERTY =
            "skiko.jbr.interop.corruptEffectDescriptorVersionForTesting"
        const val CORRUPT_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptEffectDescriptorPayloadCountForTesting"
        const val CORRUPT_EFFECT_DESCRIPTOR_RECORD_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptEffectDescriptorRecordLengthForTesting"
        const val CORRUPT_LIGHTING_FILTER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptLightingFilterDescriptorPayloadCountForTesting"
        const val CORRUPT_TINT_COLOR_FILTER_DESCRIPTOR_BLEND_MODE_PROPERTY =
            "skiko.jbr.interop.corruptTintColorFilterDescriptorBlendModeForTesting"
        const val CORRUPT_COLOR_MATRIX_FILTER_DESCRIPTOR_PAYLOAD_PROPERTY =
            "skiko.jbr.interop.corruptColorMatrixFilterDescriptorPayloadForTesting"
        const val CORRUPT_BLUR_IMAGE_FILTER_DESCRIPTOR_SIGMA_PROPERTY =
            "skiko.jbr.interop.corruptBlurImageFilterDescriptorSigmaForTesting"
        const val CORRUPT_BLUR_IMAGE_FILTER_DESCRIPTOR_NEGATIVE_SIGMA_PROPERTY =
            "skiko.jbr.interop.corruptBlurImageFilterDescriptorNegativeSigmaForTesting"
        const val CORRUPT_BLUR_IMAGE_FILTER_DESCRIPTOR_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptBlurImageFilterDescriptorTileModeForTesting"
        const val CORRUPT_OFFSET_IMAGE_FILTER_DESCRIPTOR_DELTA_PROPERTY =
            "skiko.jbr.interop.corruptOffsetImageFilterDescriptorDeltaForTesting"
        const val CORRUPT_CORNER_PATH_EFFECT_DESCRIPTOR_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptCornerPathEffectDescriptorRadiusForTesting"
        const val CORRUPT_CORNER_PATH_EFFECT_DESCRIPTOR_NEGATIVE_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptCornerPathEffectDescriptorNegativeRadiusForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_ADVANCE_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorAdvanceForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_ZERO_ADVANCE_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorZeroAdvanceForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_PHASE_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorPhaseForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PHASE_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorNegativePhaseForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_STYLE_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorStyleForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_FILL_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorFillTypeForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_PATH_DATA_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorPathDataLengthForTesting"
        const val CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PATH_DATA_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptStampedPathEffectDescriptorNegativePathDataLengthForTesting"
        const val CORRUPT_CHAIN_PATH_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptChainPathEffectDescriptorPayloadCountForTesting"
        const val CORRUPT_SHADER_DESCRIPTOR_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptShaderDescriptorTypeForTesting"
        const val CORRUPT_SHADER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptShaderDescriptorPayloadCountForTesting"
        const val CORRUPT_COLOR_SHADER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptColorShaderDescriptorPayloadCountForTesting"
        const val CORRUPT_SHADER_COLOR_FILTER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptShaderColorFilterDescriptorPayloadCountForTesting"
        const val CORRUPT_TRANSFORMED_SHADER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptTransformedShaderDescriptorPayloadCountForTesting"
        const val CORRUPT_SHADER_DESCRIPTOR_RECORD_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptShaderDescriptorRecordLengthForTesting"
        const val CORRUPT_COMPOSITE_SHADER_DESCRIPTOR_BLEND_MODE_PROPERTY =
            "skiko.jbr.interop.corruptCompositeShaderDescriptorBlendModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientShaderDescriptorTileModeForTesting"
        const val CORRUPT_LINEAR_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptLinearGradientShaderDescriptorStopOrderForTesting"
        const val CORRUPT_RADIAL_GRADIENT_SHADER_DESCRIPTOR_RADIUS_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientShaderDescriptorRadiusForTesting"
        const val CORRUPT_RADIAL_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientShaderDescriptorTileModeForTesting"
        const val CORRUPT_RADIAL_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptRadialGradientShaderDescriptorStopOrderForTesting"
        const val CORRUPT_SWEEP_GRADIENT_SHADER_DESCRIPTOR_COLOR_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientShaderDescriptorColorCountForTesting"
        const val CORRUPT_SWEEP_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_PROPERTY =
            "skiko.jbr.interop.corruptSweepGradientShaderDescriptorStopOrderForTesting"
        const val CORRUPT_IMAGE_SHADER_DESCRIPTOR_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptImageShaderDescriptorWidthForTesting"
        const val CORRUPT_IMAGE_SHADER_DESCRIPTOR_MAX_WIDTH_PROPERTY =
            "skiko.jbr.interop.corruptImageShaderDescriptorMaxWidthForTesting"
        const val CORRUPT_IMAGE_SHADER_DESCRIPTOR_HEIGHT_PROPERTY =
            "skiko.jbr.interop.corruptImageShaderDescriptorHeightForTesting"
        const val CORRUPT_IMAGE_SHADER_DESCRIPTOR_MAX_HEIGHT_PROPERTY =
            "skiko.jbr.interop.corruptImageShaderDescriptorMaxHeightForTesting"
        const val CORRUPT_IMAGE_SHADER_DESCRIPTOR_TILE_MODE_X_PROPERTY =
            "skiko.jbr.interop.corruptImageShaderDescriptorTileModeXForTesting"
        const val CORRUPT_IMAGE_SHADER_DESCRIPTOR_TILE_MODE_Y_PROPERTY =
            "skiko.jbr.interop.corruptImageShaderDescriptorTileModeYForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_KIND_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderKindForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_FREQUENCY_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderFrequencyForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_OCTAVES_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderOctavesForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_ZERO_OCTAVES_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderZeroOctavesForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_TILE_SIZE_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderTileSizeForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_TILE_HEIGHT_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderTileHeightForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_NEGATIVE_TILE_SIZE_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderNegativeTileSizeForTesting"
        const val CORRUPT_PERLIN_NOISE_SHADER_NEGATIVE_TILE_HEIGHT_PROPERTY =
            "skiko.jbr.interop.corruptPerlinNoiseShaderNegativeTileHeightForTesting"
        const val CORRUPT_DESCRIPTOR_VERSION_PROPERTY = "skiko.jbr.interop.corruptDescriptorVersionForTesting"
        const val CORRUPT_COLOR_FILTER_HANDLE_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptColorFilterHandleTypeForTesting"
        const val CORRUPT_COLOR_FILTER_HANDLE_TO_PATH_EFFECT_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptColorFilterHandleToPathEffectTypeForTesting"
        const val CORRUPT_IMAGE_FILTER_HANDLE_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptImageFilterHandleTypeForTesting"
        const val CORRUPT_PATH_EFFECT_HANDLE_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptPathEffectHandleTypeForTesting"
        const val CORRUPT_SHADER_HANDLE_TYPE_PROPERTY =
            "skiko.jbr.interop.corruptShaderHandleTypeForTesting"
        const val CORRUPT_SHADER_CHILD_MISSING_PROPERTY =
            "skiko.jbr.interop.corruptShaderChildMissingForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_SOURCE_HASH_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderSourceHashForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_SKSL_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterSkslLengthForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_UNIFORM_FLOAT_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterUniformFloatCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_UNIFORM_FLOAT_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterNegativeUniformFloatCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterNegativeChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NAMED_UNIFORM_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterNamedUniformCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_UNIFORM_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterNegativeNamedUniformCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NAMED_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterNamedChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectColorFilterNegativeNamedChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_SKSL_LENGTH_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderSkslLengthForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_UNIFORM_FLOAT_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderUniformFloatCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_UNIFORM_FLOAT_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderNegativeUniformFloatCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderNegativeChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_NAMED_UNIFORM_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderNamedUniformCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_UNIFORM_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderNegativeNamedUniformCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_NAMED_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderNamedChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_CHILD_COUNT_PROPERTY =
            "skiko.jbr.interop.corruptRuntimeEffectShaderNegativeNamedChildCountForTesting"
        const val CORRUPT_RUNTIME_EFFECT_SOURCE_PROPERTY = "skiko.jbr.interop.corruptRuntimeEffectSourceForTesting"
        const val CORRUPT_RUNTIME_EFFECT_CHILD_TYPE_PROPERTY = "skiko.jbr.interop.corruptRuntimeEffectChildTypeForTesting"
        const val FORCE_TINY_FULL_SCENE_ONCE_PROPERTY = "skiko.jbr.interop.forceTinyFullSceneOnceForTesting"
        const val FORCE_CONTEXT_CHANGE_ONCE_PROPERTY = "skiko.jbr.interop.forceContextChangeOnceForTesting"
        private const val TINY_FULL_SCENE_INJECTED_MARKER = "SKIKO_JBR_INTEROP_TINY_FULL_SCENE_INJECTED"
        private const val TEXT_FONT_SIZE_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_TEXT_FONT_SIZE_CORRUPTED"
        private const val TEXT_FONT_WEIGHT_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_TEXT_FONT_WEIGHT_CORRUPTED"
        private const val TEXT_FONT_WIDTH_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_TEXT_FONT_WIDTH_CORRUPTED"
        private const val TEXT_FONT_SLANT_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_TEXT_FONT_SLANT_CORRUPTED"
        private const val TEXT_FONT_FAMILY_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_TEXT_FONT_FAMILY_COUNT_CORRUPTED"
        private const val PARAGRAPH_FONT_SIZE_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_PARAGRAPH_FONT_SIZE_CORRUPTED"
        private const val PARAGRAPH_FONT_WEIGHT_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_PARAGRAPH_FONT_WEIGHT_CORRUPTED"
        private const val PARAGRAPH_FONT_WIDTH_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_PARAGRAPH_FONT_WIDTH_CORRUPTED"
        private const val PARAGRAPH_FONT_SLANT_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_PARAGRAPH_FONT_SLANT_CORRUPTED"
        private const val PARAGRAPH_FONT_FAMILY_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PARAGRAPH_FONT_FAMILY_COUNT_CORRUPTED"
        private const val LINEAR_GRADIENT_STROKE_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_STROKE_WIDTH_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED"
        private const val RADIAL_GRADIENT_STROKE_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_STROKE_WIDTH_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED"
        private const val SWEEP_GRADIENT_STROKE_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_STROKE_WIDTH_CORRUPTED"
        private const val SWEEP_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED"
        private const val LINEAR_GRADIENT_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_TILE_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_TILE_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_STROKE_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_STROKE_TILE_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_COLOR_COUNT_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED"
        private const val LINEAR_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED"
        private const val LINEAR_GRADIENT_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_STOP_ORDER_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED"
        private const val LINEAR_GRADIENT_STROKE_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_STROKE_STOP_ORDER_CORRUPTED"
        private const val LINEAR_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED"
        private const val LINEAR_GRADIENT_PATH_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_PATH_TILE_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_PATH_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_PATH_COLOR_COUNT_CORRUPTED"
        private const val LINEAR_GRADIENT_PATH_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_PATH_STOP_ORDER_CORRUPTED"
        private const val LINEAR_GRADIENT_PATH_FILL_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_PATH_FILL_TYPE_CORRUPTED"
        private const val LINEAR_GRADIENT_PATH_DATA_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_PATH_DATA_LENGTH_CORRUPTED"
        private const val SWEEP_GRADIENT_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_COLOR_COUNT_CORRUPTED"
        private const val SWEEP_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED"
        private const val SWEEP_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED"
        private const val SWEEP_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED"
        private const val SWEEP_GRADIENT_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_STOP_ORDER_CORRUPTED"
        private const val SWEEP_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED"
        private const val SWEEP_GRADIENT_STROKE_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_STROKE_STOP_ORDER_CORRUPTED"
        private const val SWEEP_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED"
        private const val SWEEP_GRADIENT_PATH_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_PATH_COLOR_COUNT_CORRUPTED"
        private const val SWEEP_GRADIENT_PATH_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_PATH_STOP_ORDER_CORRUPTED"
        private const val SWEEP_GRADIENT_PATH_FILL_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_PATH_FILL_TYPE_CORRUPTED"
        private const val SWEEP_GRADIENT_PATH_DATA_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_PATH_DATA_LENGTH_CORRUPTED"
        private const val RADIAL_GRADIENT_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_RADIUS_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_RADIUS_CORRUPTED"
        private const val RADIAL_GRADIENT_STROKE_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_STROKE_RADIUS_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_STROKE_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_STROKE_RADIUS_CORRUPTED"
        private const val RADIAL_GRADIENT_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_TILE_MODE_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_TILE_MODE_CORRUPTED"
        private const val RADIAL_GRADIENT_STROKE_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_STROKE_TILE_MODE_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_CORRUPTED"
        private const val RADIAL_GRADIENT_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_COLOR_COUNT_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED"
        private const val RADIAL_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED"
        private const val RADIAL_GRADIENT_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_STOP_ORDER_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED"
        private const val RADIAL_GRADIENT_STROKE_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_STROKE_STOP_ORDER_CORRUPTED"
        private const val RADIAL_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED"
        private const val RADIAL_GRADIENT_PATH_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_PATH_RADIUS_CORRUPTED"
        private const val RADIAL_GRADIENT_PATH_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_PATH_TILE_MODE_CORRUPTED"
        private const val RADIAL_GRADIENT_PATH_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_PATH_COLOR_COUNT_CORRUPTED"
        private const val RADIAL_GRADIENT_PATH_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_PATH_STOP_ORDER_CORRUPTED"
        private const val RADIAL_GRADIENT_PATH_FILL_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_PATH_FILL_TYPE_CORRUPTED"
        private const val RADIAL_GRADIENT_PATH_DATA_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_PATH_DATA_LENGTH_CORRUPTED"
        private const val DESCRIPTOR_USE_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_DESCRIPTOR_USE_CORRUPTED"
        private const val DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED"
        private const val EFFECT_CHILD_USE_AFTER_EVICT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_EFFECT_CHILD_USE_AFTER_EVICT_CORRUPTED"
        private const val EFFECT_CHILD_MISSING_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_EFFECT_CHILD_MISSING_CORRUPTED"
        private const val EFFECT_DESCRIPTOR_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_EFFECT_DESCRIPTOR_TYPE_CORRUPTED"
        private const val EFFECT_DESCRIPTOR_VERSION_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_EFFECT_DESCRIPTOR_VERSION_CORRUPTED"
        private const val EFFECT_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val EFFECT_DESCRIPTOR_RECORD_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_EFFECT_DESCRIPTOR_RECORD_LENGTH_CORRUPTED"
        private const val LIGHTING_FILTER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LIGHTING_FILTER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val TINT_COLOR_FILTER_DESCRIPTOR_BLEND_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_TINT_COLOR_FILTER_DESCRIPTOR_BLEND_MODE_CORRUPTED"
        private const val COLOR_MATRIX_FILTER_DESCRIPTOR_PAYLOAD_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_COLOR_MATRIX_FILTER_DESCRIPTOR_PAYLOAD_CORRUPTED"
        private const val BLUR_IMAGE_FILTER_DESCRIPTOR_SIGMA_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_BLUR_IMAGE_FILTER_DESCRIPTOR_SIGMA_CORRUPTED"
        private const val BLUR_IMAGE_FILTER_DESCRIPTOR_NEGATIVE_SIGMA_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_BLUR_IMAGE_FILTER_DESCRIPTOR_NEGATIVE_SIGMA_CORRUPTED"
        private const val BLUR_IMAGE_FILTER_DESCRIPTOR_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_BLUR_IMAGE_FILTER_DESCRIPTOR_TILE_MODE_CORRUPTED"
        private const val OFFSET_IMAGE_FILTER_DESCRIPTOR_DELTA_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_OFFSET_IMAGE_FILTER_DESCRIPTOR_DELTA_CORRUPTED"
        private const val CORNER_PATH_EFFECT_DESCRIPTOR_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_CORNER_PATH_EFFECT_DESCRIPTOR_RADIUS_CORRUPTED"
        private const val CORNER_PATH_EFFECT_DESCRIPTOR_NEGATIVE_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_CORNER_PATH_EFFECT_DESCRIPTOR_NEGATIVE_RADIUS_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_ADVANCE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_ADVANCE_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_ZERO_ADVANCE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_ZERO_ADVANCE_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_PHASE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_PHASE_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PHASE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PHASE_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_STYLE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_STYLE_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_FILL_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_FILL_TYPE_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_PATH_DATA_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_PATH_DATA_LENGTH_CORRUPTED"
        private const val STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PATH_DATA_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PATH_DATA_LENGTH_CORRUPTED"
        private const val CHAIN_PATH_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_CHAIN_PATH_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val SHADER_DESCRIPTOR_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SHADER_DESCRIPTOR_TYPE_CORRUPTED"
        private const val SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val COLOR_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_COLOR_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val SHADER_COLOR_FILTER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SHADER_COLOR_FILTER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val TRANSFORMED_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_TRANSFORMED_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED"
        private const val SHADER_DESCRIPTOR_RECORD_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SHADER_DESCRIPTOR_RECORD_LENGTH_CORRUPTED"
        private const val COMPOSITE_SHADER_DESCRIPTOR_BLEND_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_COMPOSITE_SHADER_DESCRIPTOR_BLEND_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_CORRUPTED"
        private const val LINEAR_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_LINEAR_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED"
        private const val RADIAL_GRADIENT_SHADER_DESCRIPTOR_RADIUS_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_SHADER_DESCRIPTOR_RADIUS_CORRUPTED"
        private const val RADIAL_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_CORRUPTED"
        private const val RADIAL_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RADIAL_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED"
        private const val SWEEP_GRADIENT_SHADER_DESCRIPTOR_COLOR_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_SHADER_DESCRIPTOR_COLOR_COUNT_CORRUPTED"
        private const val SWEEP_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SWEEP_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED"
        private const val IMAGE_SHADER_DESCRIPTOR_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_SHADER_DESCRIPTOR_WIDTH_CORRUPTED"
        private const val IMAGE_SHADER_DESCRIPTOR_MAX_WIDTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_SHADER_DESCRIPTOR_MAX_WIDTH_CORRUPTED"
        private const val IMAGE_SHADER_DESCRIPTOR_HEIGHT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_SHADER_DESCRIPTOR_HEIGHT_CORRUPTED"
        private const val IMAGE_SHADER_DESCRIPTOR_MAX_HEIGHT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_SHADER_DESCRIPTOR_MAX_HEIGHT_CORRUPTED"
        private const val IMAGE_SHADER_DESCRIPTOR_TILE_MODE_X_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_SHADER_DESCRIPTOR_TILE_MODE_X_CORRUPTED"
        private const val IMAGE_SHADER_DESCRIPTOR_TILE_MODE_Y_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_SHADER_DESCRIPTOR_TILE_MODE_Y_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_KIND_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_KIND_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_FREQUENCY_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_FREQUENCY_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_OCTAVES_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_OCTAVES_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_ZERO_OCTAVES_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_ZERO_OCTAVES_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_TILE_SIZE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_TILE_SIZE_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_TILE_HEIGHT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_TILE_HEIGHT_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_NEGATIVE_TILE_SIZE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_NEGATIVE_TILE_SIZE_CORRUPTED"
        private const val PERLIN_NOISE_SHADER_NEGATIVE_TILE_HEIGHT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PERLIN_NOISE_SHADER_NEGATIVE_TILE_HEIGHT_CORRUPTED"
        private const val DESCRIPTOR_VERSION_CORRUPTED_MARKER = "SKIKO_JBR_INTEROP_DESCRIPTOR_VERSION_CORRUPTED"
        private const val COLOR_FILTER_HANDLE_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_COLOR_FILTER_HANDLE_TYPE_CORRUPTED"
        private const val IMAGE_FILTER_HANDLE_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_IMAGE_FILTER_HANDLE_TYPE_CORRUPTED"
        private const val PATH_EFFECT_HANDLE_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_PATH_EFFECT_HANDLE_TYPE_CORRUPTED"
        private const val SHADER_HANDLE_TYPE_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SHADER_HANDLE_TYPE_CORRUPTED"
        private const val SHADER_CHILD_MISSING_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_SHADER_CHILD_MISSING_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_SOURCE_HASH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_SOURCE_HASH_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_SKSL_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_SKSL_LENGTH_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_UNIFORM_FLOAT_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_UNIFORM_FLOAT_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_NAMED_UNIFORM_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_UNIFORM_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_NAMED_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_NAMED_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_SKSL_LENGTH_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_SKSL_LENGTH_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_UNIFORM_FLOAT_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_NEGATIVE_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_NEGATIVE_UNIFORM_FLOAT_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_NEGATIVE_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_NEGATIVE_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_NAMED_UNIFORM_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_UNIFORM_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_NAMED_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_NAMED_CHILD_COUNT_CORRUPTED"
        private const val RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_CHILD_COUNT_CORRUPTED_MARKER =
            "SKIKO_JBR_INTEROP_RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_CHILD_COUNT_CORRUPTED"
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
        private const val COMMAND_DRAW_TEXT_UTF16 = 17
        private const val COMMAND_DRAW_PARAGRAPH_UTF16 = 19
        private const val COMMAND_FILL_RECT_LINEAR_GRADIENT = 24
        private const val COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT = 25
        private const val COMMAND_FILL_RECT_RADIAL_GRADIENT = 26
        private const val COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT = 27
        private const val COMMAND_FILL_PATH_LINEAR_GRADIENT = 28
        private const val COMMAND_FILL_PATH_RADIAL_GRADIENT = 29
        private const val COMMAND_FILL_RECT_SWEEP_GRADIENT = 30
        private const val COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT = 31
        private const val COMMAND_FILL_PATH_SWEEP_GRADIENT = 32
        private const val COMMAND_STROKE_RECT_LINEAR_GRADIENT = 35
        private const val COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT = 36
        private const val COMMAND_STROKE_RECT_RADIAL_GRADIENT = 37
        private const val COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT = 38
        private const val COMMAND_STROKE_RECT_SWEEP_GRADIENT = 39
        private const val COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT = 40
        private const val COMMAND_FILL_RECT_COLOR_FILTER_REF = 47
        private const val COMMAND_EVICT_COLOR_FILTER_HANDLE = 48
        private const val COMMAND_DEFINE_EFFECT_DESCRIPTOR = 49
        private const val COMMAND_SAVE_LAYER_IMAGE_FILTER_REF = 55
        private const val COMMAND_DEFINE_SHADER_DESCRIPTOR = 56
        private const val COMMAND_EVICT_SHADER_HANDLE = 57
        private const val COMMAND_FILL_RECT_SHADER_REF = 58
        private const val COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER = 1
        private const val COMMAND_BLEND_MODE_PLUS = 1
        private const val COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER = 2
        private const val COMMAND_EFFECT_DESCRIPTOR_LIGHTING_FILTER = 3
        private const val FLOAT_NAN_BITS = 0x7fc00000
        private const val FLOAT_NEGATIVE_ONE_BITS = -0x40800000
        private const val COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER = 4
        private const val COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER = 5
        private const val COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT = 6
        private const val COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT = 7
        private const val COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER = 8
        private const val COMMAND_EFFECT_DESCRIPTOR_CORNER_PATH_EFFECT = 9
        private const val COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT = 10
        private const val COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT = 11
        private const val COMMAND_SHADER_DESCRIPTOR_LINEAR_GRADIENT = 1
        private const val COMMAND_SHADER_DESCRIPTOR_RADIAL_GRADIENT = 2
        private const val COMMAND_SHADER_DESCRIPTOR_SWEEP_GRADIENT = 3
        private const val COMMAND_SHADER_DESCRIPTOR_IMAGE = 4
        private const val COMMAND_SHADER_DESCRIPTOR_COMPOSITE = 5
        private const val COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT = 6
        private const val COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER = 7
        private const val COMMAND_SHADER_DESCRIPTOR_TRANSFORM = 8
        private const val COMMAND_SHADER_DESCRIPTOR_COLOR = 9
        private const val COMMAND_SHADER_DESCRIPTOR_PERLIN_NOISE = 10
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
        private val textFontSizeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val textFontWeightCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val textFontWidthCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val textFontSlantCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val textFontFamilyCountCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val paragraphFontSizeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val paragraphFontWeightCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val paragraphFontWidthCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val paragraphFontSlantCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val paragraphFontFamilyCountCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientStrokeWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectStrokeWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientStrokeWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectStrokeWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientStrokeWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientRoundRectStrokeWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientStrokeTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectStrokeTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientStrokeColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectStrokeColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientStrokeStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientRoundRectStrokeStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientPathTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientPathColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientPathStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientPathFillTypeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientPathDataLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientRoundRectColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientStrokeColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientRoundRectStrokeColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientRoundRectStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientStrokeStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientRoundRectStrokeStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientPathColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientPathStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientPathFillTypeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientPathDataLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRadiusCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientStrokeRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectStrokeRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientStrokeTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectStrokeTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientStrokeColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectStrokeColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientStrokeStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientRoundRectStrokeStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientPathRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientPathTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientPathColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientPathStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientPathFillTypeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientPathDataLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val descriptorUseCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val descriptorUseAfterEvictCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val effectChildUseAfterEvictCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val effectChildMissingCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val effectDescriptorTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val effectDescriptorVersionCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val effectDescriptorPayloadCountCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val effectDescriptorRecordLengthCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val lightingFilterDescriptorPayloadCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val tintColorFilterDescriptorBlendModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val colorMatrixFilterDescriptorPayloadCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val blurImageFilterDescriptorSigmaCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val blurImageFilterDescriptorNegativeSigmaCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val blurImageFilterDescriptorTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val offsetImageFilterDescriptorDeltaCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val cornerPathEffectDescriptorRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val cornerPathEffectDescriptorNegativeRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorAdvanceCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorZeroAdvanceCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorPhaseCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorNegativePhaseCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorStyleCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorFillTypeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorPathDataLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val stampedPathEffectDescriptorNegativePathDataLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val chainPathEffectDescriptorPayloadCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val shaderDescriptorTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val shaderDescriptorPayloadCountCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val colorShaderDescriptorPayloadCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val shaderColorFilterDescriptorPayloadCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val transformedShaderDescriptorPayloadCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val shaderDescriptorRecordLengthCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val compositeShaderDescriptorBlendModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientShaderDescriptorTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val linearGradientShaderDescriptorStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientShaderDescriptorRadiusCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientShaderDescriptorTileModeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val radialGradientShaderDescriptorStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientShaderDescriptorColorCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val sweepGradientShaderDescriptorStopOrderCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageShaderDescriptorWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageShaderDescriptorMaxWidthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageShaderDescriptorHeightCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageShaderDescriptorMaxHeightCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageShaderDescriptorTileModeXCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageShaderDescriptorTileModeYCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderKindCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderFrequencyCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderOctavesCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderZeroOctavesCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderTileSizeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderTileHeightCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderNegativeTileSizeCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val perlinNoiseShaderNegativeTileHeightCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val descriptorVersionCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val colorFilterHandleTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val imageFilterHandleTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val pathEffectHandleTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val shaderHandleTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val shaderChildMissingCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderSourceHashCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterSkslLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterUniformFloatCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterNegativeUniformFloatCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterNegativeChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterNamedUniformCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterNegativeNamedUniformCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterNamedChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectColorFilterNegativeNamedChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderSkslLengthCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderUniformFloatCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderNegativeUniformFloatCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderNegativeChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderNamedUniformCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderNegativeNamedUniformCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderNamedChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectShaderNegativeNamedChildCountCorruptedForTesting =
            java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectSourceCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)
        private val runtimeEffectChildTypeCorruptedForTesting = java.util.concurrent.atomic.AtomicBoolean(false)

        private data class PerlinNoiseShaderCorruption(
            val payloadOffset: Int,
            val value: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class TextCommandCorruption(
            val command: Int,
            val argsOffset: Int,
            val value: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class GradientStrokeWidthCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class RadialGradientRadiusCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class LinearGradientTileModeCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class LinearGradientColorCountCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class LinearGradientStopOrderCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class GradientPathCorruption(
            val command: Int,
            val gradientArgsOffset: Int,
            val replacement: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class PathHeaderCorruption(
            val command: Int,
            val argsOffset: Int,
            val replacement: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class SweepGradientColorCountCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class SweepGradientStopOrderCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class RadialGradientTileModeCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class RadialGradientColorCountCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

        private data class RadialGradientStopOrderCorruption(
            val command: Int,
            val argsOffset: Int,
            val once: java.util.concurrent.atomic.AtomicBoolean,
            val marker: String,
        )

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

        private fun IntArray.corruptTextFontSizeForTestingIfRequested(): IntArray {
            val corruption = textCommandCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = corruption.value
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun textCommandCorruptionForTesting(): TextCommandCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_TEXT_FONT_SIZE_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_TEXT_UTF16,
                        argsOffset = 2,
                        value = 0,
                        once = textFontSizeCorruptedForTesting,
                        marker = TEXT_FONT_SIZE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_TEXT_FONT_WEIGHT_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_TEXT_UTF16,
                        argsOffset = 4,
                        value = 0,
                        once = textFontWeightCorruptedForTesting,
                        marker = TEXT_FONT_WEIGHT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_TEXT_FONT_WIDTH_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_TEXT_UTF16,
                        argsOffset = 5,
                        value = 0,
                        once = textFontWidthCorruptedForTesting,
                        marker = TEXT_FONT_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_TEXT_FONT_SLANT_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_TEXT_UTF16,
                        argsOffset = 6,
                        value = 3,
                        once = textFontSlantCorruptedForTesting,
                        marker = TEXT_FONT_SLANT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_TEXT_FONT_FAMILY_COUNT_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_TEXT_UTF16,
                        argsOffset = 7,
                        value = 257,
                        once = textFontFamilyCountCorruptedForTesting,
                        marker = TEXT_FONT_FAMILY_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PARAGRAPH_FONT_SIZE_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_PARAGRAPH_UTF16,
                        argsOffset = 3,
                        value = 0,
                        once = paragraphFontSizeCorruptedForTesting,
                        marker = PARAGRAPH_FONT_SIZE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PARAGRAPH_FONT_WEIGHT_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_PARAGRAPH_UTF16,
                        argsOffset = 5,
                        value = 0,
                        once = paragraphFontWeightCorruptedForTesting,
                        marker = PARAGRAPH_FONT_WEIGHT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PARAGRAPH_FONT_WIDTH_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_PARAGRAPH_UTF16,
                        argsOffset = 6,
                        value = 0,
                        once = paragraphFontWidthCorruptedForTesting,
                        marker = PARAGRAPH_FONT_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PARAGRAPH_FONT_SLANT_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_PARAGRAPH_UTF16,
                        argsOffset = 7,
                        value = 3,
                        once = paragraphFontSlantCorruptedForTesting,
                        marker = PARAGRAPH_FONT_SLANT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PARAGRAPH_FONT_FAMILY_COUNT_PROPERTY) ->
                    TextCommandCorruption(
                        command = COMMAND_DRAW_PARAGRAPH_UTF16,
                        argsOffset = 8,
                        value = 257,
                        once = paragraphFontFamilyCountCorruptedForTesting,
                        marker = PARAGRAPH_FONT_FAMILY_COUNT_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptLinearGradientStrokeWidthForTestingIfRequested(): IntArray {
            val corruption = gradientStrokeWidthCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 0
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun gradientStrokeWidthCorruptionForTesting(): GradientStrokeWidthCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_STROKE_WIDTH_PROPERTY) ->
                    GradientStrokeWidthCorruption(
                        command = COMMAND_STROKE_RECT_LINEAR_GRADIENT,
                        argsOffset = 4,
                        once = linearGradientStrokeWidthCorruptedForTesting,
                        marker = LINEAR_GRADIENT_STROKE_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_WIDTH_PROPERTY) ->
                    GradientStrokeWidthCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 6,
                        once = linearGradientRoundRectStrokeWidthCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_STROKE_WIDTH_PROPERTY) ->
                    GradientStrokeWidthCorruption(
                        command = COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                        argsOffset = 4,
                        once = radialGradientStrokeWidthCorruptedForTesting,
                        marker = RADIAL_GRADIENT_STROKE_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_WIDTH_PROPERTY) ->
                    GradientStrokeWidthCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 6,
                        once = radialGradientRoundRectStrokeWidthCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_STROKE_WIDTH_PROPERTY) ->
                    GradientStrokeWidthCorruption(
                        command = COMMAND_STROKE_RECT_SWEEP_GRADIENT,
                        argsOffset = 4,
                        once = sweepGradientStrokeWidthCorruptedForTesting,
                        marker = SWEEP_GRADIENT_STROKE_WIDTH_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STROKE_WIDTH_PROPERTY) ->
                    GradientStrokeWidthCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT,
                        argsOffset = 6,
                        once = sweepGradientRoundRectStrokeWidthCorruptedForTesting,
                        marker = SWEEP_GRADIENT_ROUND_RECT_STROKE_WIDTH_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptLinearGradientTileModeForTestingIfRequested(): IntArray {
            val corruption = linearGradientTileModeCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 4
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun linearGradientTileModeCorruptionForTesting(): LinearGradientTileModeCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_TILE_MODE_PROPERTY) ->
                    LinearGradientTileModeCorruption(
                        command = COMMAND_FILL_RECT_LINEAR_GRADIENT,
                        argsOffset = 8,
                        once = linearGradientTileModeCorruptedForTesting,
                        marker = LINEAR_GRADIENT_TILE_MODE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_TILE_MODE_PROPERTY) ->
                    LinearGradientTileModeCorruption(
                        command = COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 10,
                        once = linearGradientRoundRectTileModeCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_TILE_MODE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_STROKE_TILE_MODE_PROPERTY) ->
                    LinearGradientTileModeCorruption(
                        command = COMMAND_STROKE_RECT_LINEAR_GRADIENT,
                        argsOffset = 12,
                        once = linearGradientStrokeTileModeCorruptedForTesting,
                        marker = LINEAR_GRADIENT_STROKE_TILE_MODE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_PROPERTY) ->
                    LinearGradientTileModeCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 14,
                        once = linearGradientRoundRectStrokeTileModeCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptLinearGradientColorCountForTestingIfRequested(): IntArray {
            val corruption = linearGradientColorCountCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 1
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun linearGradientColorCountCorruptionForTesting(): LinearGradientColorCountCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_COLOR_COUNT_PROPERTY) ->
                    LinearGradientColorCountCorruption(
                        command = COMMAND_FILL_RECT_LINEAR_GRADIENT,
                        argsOffset = 9,
                        once = linearGradientColorCountCorruptedForTesting,
                        marker = LINEAR_GRADIENT_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_COLOR_COUNT_PROPERTY) ->
                    LinearGradientColorCountCorruption(
                        command = COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 11,
                        once = linearGradientRoundRectColorCountCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_STROKE_COLOR_COUNT_PROPERTY) ->
                    LinearGradientColorCountCorruption(
                        command = COMMAND_STROKE_RECT_LINEAR_GRADIENT,
                        argsOffset = 13,
                        once = linearGradientStrokeColorCountCorruptedForTesting,
                        marker = LINEAR_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_PROPERTY) ->
                    LinearGradientColorCountCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 15,
                        once = linearGradientRoundRectStrokeColorCountCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptLinearGradientStopOrderForTestingIfRequested(): IntArray {
            val corruption = linearGradientStopOrderCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 0
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun linearGradientStopOrderCorruptionForTesting(): LinearGradientStopOrderCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_STOP_ORDER_PROPERTY) ->
                    LinearGradientStopOrderCorruption(
                        command = COMMAND_FILL_RECT_LINEAR_GRADIENT,
                        argsOffset = 13,
                        once = linearGradientStopOrderCorruptedForTesting,
                        marker = LINEAR_GRADIENT_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STOP_ORDER_PROPERTY) ->
                    LinearGradientStopOrderCorruption(
                        command = COMMAND_FILL_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 15,
                        once = linearGradientRoundRectStopOrderCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_STROKE_STOP_ORDER_PROPERTY) ->
                    LinearGradientStopOrderCorruption(
                        command = COMMAND_STROKE_RECT_LINEAR_GRADIENT,
                        argsOffset = 17,
                        once = linearGradientStrokeStopOrderCorruptedForTesting,
                        marker = LINEAR_GRADIENT_STROKE_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_PROPERTY) ->
                    LinearGradientStopOrderCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_LINEAR_GRADIENT,
                        argsOffset = 19,
                        once = linearGradientRoundRectStrokeStopOrderCorruptedForTesting,
                        marker = LINEAR_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptLinearGradientPathTileModeForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_LINEAR_GRADIENT,
                    property = CORRUPT_LINEAR_GRADIENT_PATH_TILE_MODE_PROPERTY,
                    gradientArgsOffset = 4,
                    replacement = 4,
                    once = linearGradientPathTileModeCorruptedForTesting,
                    marker = LINEAR_GRADIENT_PATH_TILE_MODE_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptLinearGradientPathColorCountForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_LINEAR_GRADIENT,
                    property = CORRUPT_LINEAR_GRADIENT_PATH_COLOR_COUNT_PROPERTY,
                    gradientArgsOffset = 5,
                    replacement = 1,
                    once = linearGradientPathColorCountCorruptedForTesting,
                    marker = LINEAR_GRADIENT_PATH_COLOR_COUNT_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptLinearGradientPathStopOrderForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_LINEAR_GRADIENT,
                    property = CORRUPT_LINEAR_GRADIENT_PATH_STOP_ORDER_PROPERTY,
                    gradientArgsOffset = 9,
                    replacement = 0,
                    once = linearGradientPathStopOrderCorruptedForTesting,
                    marker = LINEAR_GRADIENT_PATH_STOP_ORDER_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptLinearGradientPathFillTypeForTestingIfRequested(): IntArray =
            corruptPathHeaderForTestingIfRequested(
                pathHeaderCorruptionForTesting(
                    command = COMMAND_FILL_PATH_LINEAR_GRADIENT,
                    property = CORRUPT_LINEAR_GRADIENT_PATH_FILL_TYPE_PROPERTY,
                    argsOffset = 0,
                    replacement = 99,
                    once = linearGradientPathFillTypeCorruptedForTesting,
                    marker = LINEAR_GRADIENT_PATH_FILL_TYPE_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptLinearGradientPathDataLengthForTestingIfRequested(): IntArray =
            corruptPathHeaderForTestingIfRequested(
                pathHeaderCorruptionForTesting(
                    command = COMMAND_FILL_PATH_LINEAR_GRADIENT,
                    property = CORRUPT_LINEAR_GRADIENT_PATH_DATA_LENGTH_PROPERTY,
                    argsOffset = 1,
                    replacement = -1,
                    once = linearGradientPathDataLengthCorruptedForTesting,
                    marker = LINEAR_GRADIENT_PATH_DATA_LENGTH_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptRadialGradientPathRadiusForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    property = CORRUPT_RADIAL_GRADIENT_PATH_RADIUS_PROPERTY,
                    gradientArgsOffset = 2,
                    replacement = 0,
                    once = radialGradientPathRadiusCorruptedForTesting,
                    marker = RADIAL_GRADIENT_PATH_RADIUS_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptRadialGradientPathTileModeForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    property = CORRUPT_RADIAL_GRADIENT_PATH_TILE_MODE_PROPERTY,
                    gradientArgsOffset = 3,
                    replacement = 4,
                    once = radialGradientPathTileModeCorruptedForTesting,
                    marker = RADIAL_GRADIENT_PATH_TILE_MODE_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptRadialGradientPathColorCountForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    property = CORRUPT_RADIAL_GRADIENT_PATH_COLOR_COUNT_PROPERTY,
                    gradientArgsOffset = 4,
                    replacement = 1,
                    once = radialGradientPathColorCountCorruptedForTesting,
                    marker = RADIAL_GRADIENT_PATH_COLOR_COUNT_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptRadialGradientPathStopOrderForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    property = CORRUPT_RADIAL_GRADIENT_PATH_STOP_ORDER_PROPERTY,
                    gradientArgsOffset = 8,
                    replacement = 0,
                    once = radialGradientPathStopOrderCorruptedForTesting,
                    marker = RADIAL_GRADIENT_PATH_STOP_ORDER_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptRadialGradientPathFillTypeForTestingIfRequested(): IntArray =
            corruptPathHeaderForTestingIfRequested(
                pathHeaderCorruptionForTesting(
                    command = COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    property = CORRUPT_RADIAL_GRADIENT_PATH_FILL_TYPE_PROPERTY,
                    argsOffset = 0,
                    replacement = 99,
                    once = radialGradientPathFillTypeCorruptedForTesting,
                    marker = RADIAL_GRADIENT_PATH_FILL_TYPE_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptRadialGradientPathDataLengthForTestingIfRequested(): IntArray =
            corruptPathHeaderForTestingIfRequested(
                pathHeaderCorruptionForTesting(
                    command = COMMAND_FILL_PATH_RADIAL_GRADIENT,
                    property = CORRUPT_RADIAL_GRADIENT_PATH_DATA_LENGTH_PROPERTY,
                    argsOffset = 1,
                    replacement = -1,
                    once = radialGradientPathDataLengthCorruptedForTesting,
                    marker = RADIAL_GRADIENT_PATH_DATA_LENGTH_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptSweepGradientPathColorCountForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_SWEEP_GRADIENT,
                    property = CORRUPT_SWEEP_GRADIENT_PATH_COLOR_COUNT_PROPERTY,
                    gradientArgsOffset = 2,
                    replacement = 1,
                    once = sweepGradientPathColorCountCorruptedForTesting,
                    marker = SWEEP_GRADIENT_PATH_COLOR_COUNT_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptSweepGradientPathStopOrderForTestingIfRequested(): IntArray =
            corruptGradientPathForTestingIfRequested(
                gradientPathCorruptionForTesting(
                    command = COMMAND_FILL_PATH_SWEEP_GRADIENT,
                    property = CORRUPT_SWEEP_GRADIENT_PATH_STOP_ORDER_PROPERTY,
                    gradientArgsOffset = 6,
                    replacement = 0,
                    once = sweepGradientPathStopOrderCorruptedForTesting,
                    marker = SWEEP_GRADIENT_PATH_STOP_ORDER_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptSweepGradientPathFillTypeForTestingIfRequested(): IntArray =
            corruptPathHeaderForTestingIfRequested(
                pathHeaderCorruptionForTesting(
                    command = COMMAND_FILL_PATH_SWEEP_GRADIENT,
                    property = CORRUPT_SWEEP_GRADIENT_PATH_FILL_TYPE_PROPERTY,
                    argsOffset = 0,
                    replacement = 99,
                    once = sweepGradientPathFillTypeCorruptedForTesting,
                    marker = SWEEP_GRADIENT_PATH_FILL_TYPE_CORRUPTED_MARKER,
                )
            )

        private fun IntArray.corruptSweepGradientPathDataLengthForTestingIfRequested(): IntArray =
            corruptPathHeaderForTestingIfRequested(
                pathHeaderCorruptionForTesting(
                    command = COMMAND_FILL_PATH_SWEEP_GRADIENT,
                    property = CORRUPT_SWEEP_GRADIENT_PATH_DATA_LENGTH_PROPERTY,
                    argsOffset = 1,
                    replacement = -1,
                    once = sweepGradientPathDataLengthCorruptedForTesting,
                    marker = SWEEP_GRADIENT_PATH_DATA_LENGTH_CORRUPTED_MARKER,
                )
            )

        private fun gradientPathCorruptionForTesting(
            command: Int,
            property: String,
            gradientArgsOffset: Int,
            replacement: Int,
            once: java.util.concurrent.atomic.AtomicBoolean,
            marker: String,
        ): GradientPathCorruption? =
            if (java.lang.Boolean.getBoolean(property)) {
                GradientPathCorruption(
                    command = command,
                    gradientArgsOffset = gradientArgsOffset,
                    replacement = replacement,
                    once = once,
                    marker = marker,
                )
            } else {
                null
            }

        private fun IntArray.corruptGradientPathForTestingIfRequested(
            corruption: GradientPathCorruption?
        ): IntArray {
            corruption ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + 1 < recordEnd) {
                    val pathDataLength = this[argsStart + 1]
                    if (pathDataLength < 0) return this
                    val gradientStart = argsStart + 2 + pathDataLength
                    val corruptOffset = gradientStart + corruption.gradientArgsOffset
                    if (corruptOffset < recordEnd) {
                        return copyOf().also { stream ->
                            stream[corruptOffset] = corruption.replacement
                            if (corruption.once.compareAndSet(false, true)) {
                                Logger.info { corruption.marker }
                            }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
            }

        private fun pathHeaderCorruptionForTesting(
            command: Int,
            property: String,
            argsOffset: Int,
            replacement: Int,
            once: java.util.concurrent.atomic.AtomicBoolean,
            marker: String,
        ): PathHeaderCorruption? =
            if (java.lang.Boolean.getBoolean(property)) {
                PathHeaderCorruption(
                    command = command,
                    argsOffset = argsOffset,
                    replacement = replacement,
                    once = once,
                    marker = marker,
                )
            } else {
                null
            }

        private fun IntArray.corruptPathHeaderForTestingIfRequested(
            corruption: PathHeaderCorruption?
        ): IntArray {
            corruption ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = corruption.replacement
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptSweepGradientColorCountForTestingIfRequested(): IntArray {
            val corruption = sweepGradientColorCountCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 1
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun sweepGradientColorCountCorruptionForTesting(): SweepGradientColorCountCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_COLOR_COUNT_PROPERTY) ->
                    SweepGradientColorCountCorruption(
                        command = COMMAND_FILL_RECT_SWEEP_GRADIENT,
                        argsOffset = 6,
                        once = sweepGradientColorCountCorruptedForTesting,
                        marker = SWEEP_GRADIENT_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_ROUND_RECT_COLOR_COUNT_PROPERTY) ->
                    SweepGradientColorCountCorruption(
                        command = COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT,
                        argsOffset = 8,
                        once = sweepGradientRoundRectColorCountCorruptedForTesting,
                        marker = SWEEP_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_STROKE_COLOR_COUNT_PROPERTY) ->
                    SweepGradientColorCountCorruption(
                        command = COMMAND_STROKE_RECT_SWEEP_GRADIENT,
                        argsOffset = 10,
                        once = sweepGradientStrokeColorCountCorruptedForTesting,
                        marker = SWEEP_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_PROPERTY) ->
                    SweepGradientColorCountCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT,
                        argsOffset = 12,
                        once = sweepGradientRoundRectStrokeColorCountCorruptedForTesting,
                        marker = SWEEP_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptSweepGradientStopOrderForTestingIfRequested(): IntArray {
            val corruption = sweepGradientStopOrderCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 0
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun sweepGradientStopOrderCorruptionForTesting(): SweepGradientStopOrderCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_STOP_ORDER_PROPERTY) ->
                    SweepGradientStopOrderCorruption(
                        command = COMMAND_FILL_RECT_SWEEP_GRADIENT,
                        argsOffset = 10,
                        once = sweepGradientStopOrderCorruptedForTesting,
                        marker = SWEEP_GRADIENT_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STOP_ORDER_PROPERTY) ->
                    SweepGradientStopOrderCorruption(
                        command = COMMAND_FILL_ROUND_RECT_SWEEP_GRADIENT,
                        argsOffset = 12,
                        once = sweepGradientRoundRectStopOrderCorruptedForTesting,
                        marker = SWEEP_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_STROKE_STOP_ORDER_PROPERTY) ->
                    SweepGradientStopOrderCorruption(
                        command = COMMAND_STROKE_RECT_SWEEP_GRADIENT,
                        argsOffset = 14,
                        once = sweepGradientStrokeStopOrderCorruptedForTesting,
                        marker = SWEEP_GRADIENT_STROKE_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_PROPERTY) ->
                    SweepGradientStopOrderCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_SWEEP_GRADIENT,
                        argsOffset = 16,
                        once = sweepGradientRoundRectStrokeStopOrderCorruptedForTesting,
                        marker = SWEEP_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptRadialGradientRadiusForTestingIfRequested(): IntArray {
            val corruption = radialGradientRadiusCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 0
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun radialGradientRadiusCorruptionForTesting(): RadialGradientRadiusCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_RADIUS_PROPERTY) ->
                    RadialGradientRadiusCorruption(
                        command = COMMAND_FILL_RECT_RADIAL_GRADIENT,
                        argsOffset = 6,
                        once = radialGradientRadiusCorruptedForTesting,
                        marker = RADIAL_GRADIENT_RADIUS_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_RADIUS_PROPERTY) ->
                    RadialGradientRadiusCorruption(
                        command = COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 8,
                        once = radialGradientRoundRectRadiusCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_RADIUS_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_STROKE_RADIUS_PROPERTY) ->
                    RadialGradientRadiusCorruption(
                        command = COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                        argsOffset = 10,
                        once = radialGradientStrokeRadiusCorruptedForTesting,
                        marker = RADIAL_GRADIENT_STROKE_RADIUS_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_RADIUS_PROPERTY) ->
                    RadialGradientRadiusCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 12,
                        once = radialGradientRoundRectStrokeRadiusCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_STROKE_RADIUS_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptRadialGradientTileModeForTestingIfRequested(): IntArray {
            val corruption = radialGradientTileModeCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 4
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun radialGradientTileModeCorruptionForTesting(): RadialGradientTileModeCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_TILE_MODE_PROPERTY) ->
                    RadialGradientTileModeCorruption(
                        command = COMMAND_FILL_RECT_RADIAL_GRADIENT,
                        argsOffset = 7,
                        once = radialGradientTileModeCorruptedForTesting,
                        marker = RADIAL_GRADIENT_TILE_MODE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_TILE_MODE_PROPERTY) ->
                    RadialGradientTileModeCorruption(
                        command = COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 9,
                        once = radialGradientRoundRectTileModeCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_TILE_MODE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_STROKE_TILE_MODE_PROPERTY) ->
                    RadialGradientTileModeCorruption(
                        command = COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                        argsOffset = 11,
                        once = radialGradientStrokeTileModeCorruptedForTesting,
                        marker = RADIAL_GRADIENT_STROKE_TILE_MODE_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_PROPERTY) ->
                    RadialGradientTileModeCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 13,
                        once = radialGradientRoundRectStrokeTileModeCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_STROKE_TILE_MODE_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptRadialGradientColorCountForTestingIfRequested(): IntArray {
            val corruption = radialGradientColorCountCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 1
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun radialGradientColorCountCorruptionForTesting(): RadialGradientColorCountCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_COLOR_COUNT_PROPERTY) ->
                    RadialGradientColorCountCorruption(
                        command = COMMAND_FILL_RECT_RADIAL_GRADIENT,
                        argsOffset = 8,
                        once = radialGradientColorCountCorruptedForTesting,
                        marker = RADIAL_GRADIENT_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_COLOR_COUNT_PROPERTY) ->
                    RadialGradientColorCountCorruption(
                        command = COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 10,
                        once = radialGradientRoundRectColorCountCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_STROKE_COLOR_COUNT_PROPERTY) ->
                    RadialGradientColorCountCorruption(
                        command = COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                        argsOffset = 12,
                        once = radialGradientStrokeColorCountCorruptedForTesting,
                        marker = RADIAL_GRADIENT_STROKE_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_PROPERTY) ->
                    RadialGradientColorCountCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 14,
                        once = radialGradientRoundRectStrokeColorCountCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_STROKE_COLOR_COUNT_CORRUPTED_MARKER,
                    )
                else -> null
            }

        private fun IntArray.corruptRadialGradientStopOrderForTestingIfRequested(): IntArray {
            val corruption = radialGradientStopOrderCorruptionForTesting() ?: return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == corruption.command && argsStart + corruption.argsOffset < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + corruption.argsOffset] = 0
                        if (corruption.once.compareAndSet(false, true)) {
                            Logger.info { corruption.marker }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun radialGradientStopOrderCorruptionForTesting(): RadialGradientStopOrderCorruption? =
            when {
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_STOP_ORDER_PROPERTY) ->
                    RadialGradientStopOrderCorruption(
                        command = COMMAND_FILL_RECT_RADIAL_GRADIENT,
                        argsOffset = 12,
                        once = radialGradientStopOrderCorruptedForTesting,
                        marker = RADIAL_GRADIENT_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STOP_ORDER_PROPERTY) ->
                    RadialGradientStopOrderCorruption(
                        command = COMMAND_FILL_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 14,
                        once = radialGradientRoundRectStopOrderCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_STROKE_STOP_ORDER_PROPERTY) ->
                    RadialGradientStopOrderCorruption(
                        command = COMMAND_STROKE_RECT_RADIAL_GRADIENT,
                        argsOffset = 16,
                        once = radialGradientStrokeStopOrderCorruptedForTesting,
                        marker = RADIAL_GRADIENT_STROKE_STOP_ORDER_CORRUPTED_MARKER,
                    )
                java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_PROPERTY) ->
                    RadialGradientStopOrderCorruption(
                        command = COMMAND_STROKE_ROUND_RECT_RADIAL_GRADIENT,
                        argsOffset = 18,
                        once = radialGradientRoundRectStrokeStopOrderCorruptedForTesting,
                        marker = RADIAL_GRADIENT_ROUND_RECT_STROKE_STOP_ORDER_CORRUPTED_MARKER,
                    )
                else -> null
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
                    Logger.info { "$DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED_MARKER op=$op" }
                    return corrupted
                } else if (op == COMMAND_FILL_RECT_COLOR_FILTER_REF && argsStart + 2 < recordEnd) {
                    val evict = intArrayOf(
                        COMMAND_EVICT_COLOR_FILTER_HANDLE,
                        5 * Int.SIZE_BYTES,
                        COMMAND_RECORD_FLAGS_NONE,
                        this[argsStart + 1],
                        this[argsStart + 2],
                    )
                    val corrupted = copyOfRange(0, offset) + evict + copyOfRange(offset, size)
                    corrupted[3] = corrupted[3] + evict.size
                    Logger.info { "$DESCRIPTOR_USE_AFTER_EVICT_CORRUPTED_MARKER op=$op" }
                    return corrupted
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptEffectChildUseAfterEvictForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_EFFECT_CHILD_USE_AFTER_EVICT_PROPERTY)) return this
            if (!effectChildUseAfterEvictCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if ((descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT ||
                            descriptorType == COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT) &&
                        payloadIntCount == 4
                    ) {
                        val evict = intArrayOf(
                            COMMAND_EVICT_COLOR_FILTER_HANDLE,
                            5 * Int.SIZE_BYTES,
                            COMMAND_RECORD_FLAGS_NONE,
                            this[argsStart + 5],
                            this[argsStart + 6],
                        )
                        val target = if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT) {
                            "offsetImageFilterChild"
                        } else {
                            "chainPathEffectChild"
                        }
                        val corrupted = copyOfRange(0, offset) + evict + copyOfRange(offset, size)
                        corrupted[3] = corrupted[3] + evict.size
                        Logger.info { "$EFFECT_CHILD_USE_AFTER_EVICT_CORRUPTED_MARKER target=$target" }
                        return corrupted
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptEffectChildMissingForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_EFFECT_CHILD_MISSING_PROPERTY)) return this
            if (!effectChildMissingCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this

            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val target = when {
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER && payloadIntCount >= 9 -> {
                            val payloadStart = argsStart + 5
                            val childCount = this[payloadStart + 2]
                            if (childCount <= 0 || payloadStart + 8 > recordEnd) null else "runtimeEffectColorFilterChild"
                        }
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT &&
                            payloadIntCount == 4 -> "offsetImageFilterChild"
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT &&
                            payloadIntCount == 4 -> "chainPathEffectChild"
                        else -> null
                    }
                    if (target != null) {
                        return copyOf().also { stream ->
                            val childHandleOffset =
                                if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER) argsStart + 12 else argsStart + 5
                            stream[childHandleOffset] = 0x7f10_0001
                            stream[childHandleOffset + 1] = 0x7f10_0002
                            Logger.info { "$EFFECT_CHILD_MISSING_CORRUPTED_MARKER target=$target" }
                        }
                    }
                } else if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER && payloadIntCount == 4) {
                        return copyOf().also { stream ->
                            stream[argsStart + 7] = 0x7f10_0001
                            stream[argsStart + 8] = 0x7f10_0002
                            Logger.info { "$EFFECT_CHILD_MISSING_CORRUPTED_MARKER target=shaderColorFilterEffectChild" }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptEffectDescriptorTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_EFFECT_DESCRIPTOR_TYPE_PROPERTY)) return this
            if (!effectDescriptorTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 2 < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 2] = Int.MAX_VALUE
                        Logger.info { EFFECT_DESCRIPTOR_TYPE_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptEffectDescriptorVersionForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_EFFECT_DESCRIPTOR_VERSION_PROPERTY)) return this
            if (!effectDescriptorVersionCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 3 < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 3] = Int.MAX_VALUE
                        Logger.info { EFFECT_DESCRIPTOR_VERSION_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptEffectDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!effectDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 4 < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 4] = Int.MAX_VALUE
                        Logger.info { EFFECT_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptEffectDescriptorRecordLengthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_EFFECT_DESCRIPTOR_RECORD_LENGTH_PROPERTY)) return this
            if (!effectDescriptorRecordLengthCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && recordLengthInts > 3) {
                    return copyOf().also { stream ->
                        stream[offset + 1] = (recordLengthInts - 1) * Int.SIZE_BYTES
                        Logger.info { EFFECT_DESCRIPTOR_RECORD_LENGTH_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptLightingFilterDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_LIGHTING_FILTER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!lightingFilterDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_LIGHTING_FILTER &&
                        payloadIntCount == 2 &&
                        recordEnd + 1 <= commandEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[offset + 1] = (recordLengthInts + 1) * Int.SIZE_BYTES
                            stream[argsStart + 4] = 3
                            Logger.info { LIGHTING_FILTER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptTintColorFilterDescriptorBlendModeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_TINT_COLOR_FILTER_DESCRIPTOR_BLEND_MODE_PROPERTY)) return this
            if (!tintColorFilterDescriptorBlendModeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER && payloadIntCount == 2) {
                        return copyOf().also { stream ->
                            stream[argsStart + 6] = COMMAND_BLEND_MODE_PLUS
                            Logger.info { TINT_COLOR_FILTER_DESCRIPTOR_BLEND_MODE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptColorMatrixFilterDescriptorPayloadForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_COLOR_MATRIX_FILTER_DESCRIPTOR_PAYLOAD_PROPERTY)) return this
            if (!colorMatrixFilterDescriptorPayloadCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 24 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER && payloadIntCount == 20) {
                        return copyOf().also { stream ->
                            stream[argsStart + 9] = FLOAT_NAN_BITS
                            Logger.info { COLOR_MATRIX_FILTER_DESCRIPTOR_PAYLOAD_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptBlurImageFilterDescriptorSigmaForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_BLUR_IMAGE_FILTER_DESCRIPTOR_SIGMA_PROPERTY)) return this
            if (!blurImageFilterDescriptorSigmaCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val sigmaOffset = when {
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER && payloadIntCount == 3 ->
                            argsStart + 5
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT &&
                            payloadIntCount == 5 -> argsStart + 7
                        else -> -1
                    }
                    if (sigmaOffset >= 0 && sigmaOffset < recordEnd) {
                        return copyOf().also { stream ->
                            stream[sigmaOffset] = FLOAT_NAN_BITS
                            Logger.info { BLUR_IMAGE_FILTER_DESCRIPTOR_SIGMA_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptBlurImageFilterDescriptorNegativeSigmaForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_BLUR_IMAGE_FILTER_DESCRIPTOR_NEGATIVE_SIGMA_PROPERTY)) return this
            if (!blurImageFilterDescriptorNegativeSigmaCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val sigmaOffset = when {
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER && payloadIntCount == 3 ->
                            argsStart + 5
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT &&
                            payloadIntCount == 5 -> argsStart + 7
                        else -> -1
                    }
                    if (sigmaOffset >= 0 && sigmaOffset < recordEnd) {
                        return copyOf().also { stream ->
                            stream[sigmaOffset] = FLOAT_NEGATIVE_ONE_BITS
                            Logger.info { BLUR_IMAGE_FILTER_DESCRIPTOR_NEGATIVE_SIGMA_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptBlurImageFilterDescriptorTileModeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_BLUR_IMAGE_FILTER_DESCRIPTOR_TILE_MODE_PROPERTY)) return this
            if (!blurImageFilterDescriptorTileModeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val tileModeOffset = when {
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER && payloadIntCount == 3 ->
                            argsStart + 7
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT &&
                            payloadIntCount == 5 -> argsStart + 9
                        else -> -1
                    }
                    if (tileModeOffset >= 0 && tileModeOffset < recordEnd) {
                        return copyOf().also { stream ->
                            stream[tileModeOffset] = 99
                            Logger.info { BLUR_IMAGE_FILTER_DESCRIPTOR_TILE_MODE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptOffsetImageFilterDescriptorDeltaForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_OFFSET_IMAGE_FILTER_DESCRIPTOR_DELTA_PROPERTY)) return this
            if (!offsetImageFilterDescriptorDeltaCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val deltaOffset = when {
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER && payloadIntCount == 2 ->
                            argsStart + 5
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT &&
                            payloadIntCount == 4 -> argsStart + 7
                        else -> -1
                    }
                    if (deltaOffset >= 0 && deltaOffset < recordEnd) {
                        return copyOf().also { stream ->
                            stream[deltaOffset] = FLOAT_NAN_BITS
                            Logger.info { OFFSET_IMAGE_FILTER_DESCRIPTOR_DELTA_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptCornerPathEffectDescriptorRadiusForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_CORNER_PATH_EFFECT_DESCRIPTOR_RADIUS_PROPERTY)) return this
            if (!cornerPathEffectDescriptorRadiusCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_CORNER_PATH_EFFECT && payloadIntCount == 1) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = FLOAT_NAN_BITS
                            Logger.info { CORNER_PATH_EFFECT_DESCRIPTOR_RADIUS_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptCornerPathEffectDescriptorNegativeRadiusForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_CORNER_PATH_EFFECT_DESCRIPTOR_NEGATIVE_RADIUS_PROPERTY)) return this
            if (!cornerPathEffectDescriptorNegativeRadiusCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_CORNER_PATH_EFFECT && payloadIntCount == 1) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = FLOAT_NEGATIVE_ONE_BITS
                            Logger.info { CORNER_PATH_EFFECT_DESCRIPTOR_NEGATIVE_RADIUS_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorAdvanceForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_ADVANCE_PROPERTY)) return this
            if (!stampedPathEffectDescriptorAdvanceCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = FLOAT_NAN_BITS
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_ADVANCE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorZeroAdvanceForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_ZERO_ADVANCE_PROPERTY)) return this
            if (!stampedPathEffectDescriptorZeroAdvanceCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = 0
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_ZERO_ADVANCE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorPhaseForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_PHASE_PROPERTY)) return this
            if (!stampedPathEffectDescriptorPhaseCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 6] = FLOAT_NAN_BITS
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_PHASE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorNegativePhaseForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PHASE_PROPERTY)) return this
            if (!stampedPathEffectDescriptorNegativePhaseCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 6] = FLOAT_NEGATIVE_ONE_BITS
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PHASE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorStyleForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_STYLE_PROPERTY)) return this
            if (!stampedPathEffectDescriptorStyleCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 7] = 99
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_STYLE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorFillTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_FILL_TYPE_PROPERTY)) return this
            if (!stampedPathEffectDescriptorFillTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 8 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 8] = 99
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_FILL_TYPE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorPathDataLengthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_PATH_DATA_LENGTH_PROPERTY)) {
                return this
            }
            if (!stampedPathEffectDescriptorPathDataLengthCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 9] = 4097
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_PATH_DATA_LENGTH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptStampedPathEffectDescriptorNegativePathDataLengthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(
                    CORRUPT_STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PATH_DATA_LENGTH_PROPERTY
                )
            ) {
                return this
            }
            if (!stampedPathEffectDescriptorNegativePathDataLengthCorruptedForTesting.compareAndSet(false, true)) {
                return this
            }
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT && payloadIntCount >= 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 9] = -1
                            Logger.info { STAMPED_PATH_EFFECT_DESCRIPTOR_NEGATIVE_PATH_DATA_LENGTH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptChainPathEffectDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_CHAIN_PATH_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!chainPathEffectDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT &&
                        payloadIntCount == 4 &&
                        recordEnd + 1 <= commandEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[offset + 1] = (recordLengthInts + 1) * Int.SIZE_BYTES
                            stream[argsStart + 4] = 5
                            Logger.info { CHAIN_PATH_EFFECT_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptShaderDescriptorTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SHADER_DESCRIPTOR_TYPE_PROPERTY)) return this
            if (!shaderDescriptorTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 2 < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 2] = Int.MAX_VALUE
                        Logger.info { SHADER_DESCRIPTOR_TYPE_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptShaderDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SHADER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!shaderDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 4 < recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 4] = Int.MAX_VALUE
                        Logger.info { SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptColorShaderDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_COLOR_SHADER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!colorShaderDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 6 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_COLOR &&
                        payloadIntCount == 1 &&
                        recordEnd + 1 <= commandEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[offset + 1] = (recordLengthInts + 1) * Int.SIZE_BYTES
                            stream[argsStart + 4] = 2
                            Logger.info { COLOR_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptShaderColorFilterDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SHADER_COLOR_FILTER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!shaderColorFilterDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER &&
                        payloadIntCount == 4 &&
                        recordEnd + 1 <= commandEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[offset + 1] = (recordLengthInts + 1) * Int.SIZE_BYTES
                            stream[argsStart + 4] = 5
                            Logger.info { SHADER_COLOR_FILTER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptTransformedShaderDescriptorPayloadCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_TRANSFORMED_SHADER_DESCRIPTOR_PAYLOAD_COUNT_PROPERTY)) return this
            if (!transformedShaderDescriptorPayloadCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 4 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_TRANSFORM && payloadIntCount == 11) {
                        return copyOf().also { stream ->
                            stream[argsStart + 4] = 10
                            Logger.info { TRANSFORMED_SHADER_DESCRIPTOR_PAYLOAD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptShaderDescriptorRecordLengthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SHADER_DESCRIPTOR_RECORD_LENGTH_PROPERTY)) return this
            if (!shaderDescriptorRecordLengthCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && recordLengthInts > 3) {
                    return copyOf().also { stream ->
                        stream[offset + 1] = (recordLengthInts - 1) * Int.SIZE_BYTES
                        Logger.info { SHADER_DESCRIPTOR_RECORD_LENGTH_CORRUPTED_MARKER }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptCompositeShaderDescriptorBlendModeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_COMPOSITE_SHADER_DESCRIPTOR_BLEND_MODE_PROPERTY)) return this
            if (!compositeShaderDescriptorBlendModeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_COMPOSITE && payloadIntCount == 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 9] = 99
                            Logger.info { COMPOSITE_SHADER_DESCRIPTOR_BLEND_MODE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptLinearGradientShaderDescriptorTileModeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_PROPERTY)) return this
            if (!linearGradientShaderDescriptorTileModeCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_LINEAR_GRADIENT &&
                        payloadIntCount >= 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 4] = 99
                            Logger.info { LINEAR_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptLinearGradientShaderDescriptorStopOrderForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_LINEAR_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_PROPERTY)) {
                return this
            }
            if (!linearGradientShaderDescriptorStopOrderCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_LINEAR_GRADIENT &&
                        payloadIntCount >= 10 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 9] = stream[payloadStart + 7]
                            Logger.info { LINEAR_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRadialGradientShaderDescriptorRadiusForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_SHADER_DESCRIPTOR_RADIUS_PROPERTY)) return this
            if (!radialGradientShaderDescriptorRadiusCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RADIAL_GRADIENT &&
                        payloadIntCount >= 5 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = 0
                            Logger.info { RADIAL_GRADIENT_SHADER_DESCRIPTOR_RADIUS_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRadialGradientShaderDescriptorTileModeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_PROPERTY)) return this
            if (!radialGradientShaderDescriptorTileModeCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RADIAL_GRADIENT &&
                        payloadIntCount >= 5 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = 99
                            Logger.info { RADIAL_GRADIENT_SHADER_DESCRIPTOR_TILE_MODE_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRadialGradientShaderDescriptorStopOrderForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RADIAL_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_PROPERTY)) {
                return this
            }
            if (!radialGradientShaderDescriptorStopOrderCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RADIAL_GRADIENT &&
                        payloadIntCount >= 9 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 8] = stream[payloadStart + 6]
                            Logger.info { RADIAL_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptSweepGradientShaderDescriptorColorCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_SHADER_DESCRIPTOR_COLOR_COUNT_PROPERTY)) return this
            if (!sweepGradientShaderDescriptorColorCountCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_SWEEP_GRADIENT &&
                        payloadIntCount >= 3 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = 17
                            Logger.info { SWEEP_GRADIENT_SHADER_DESCRIPTOR_COLOR_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptSweepGradientShaderDescriptorStopOrderForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SWEEP_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_PROPERTY)) {
                return this
            }
            if (!sweepGradientShaderDescriptorStopOrderCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_SWEEP_GRADIENT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 6] = stream[payloadStart + 4]
                            Logger.info { SWEEP_GRADIENT_SHADER_DESCRIPTOR_STOP_ORDER_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageShaderDescriptorWidthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_SHADER_DESCRIPTOR_WIDTH_PROPERTY)) return this
            if (!imageShaderDescriptorWidthCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_IMAGE &&
                        payloadIntCount == 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = 0
                            Logger.info { IMAGE_SHADER_DESCRIPTOR_WIDTH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageShaderDescriptorMaxWidthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_SHADER_DESCRIPTOR_MAX_WIDTH_PROPERTY)) return this
            if (!imageShaderDescriptorMaxWidthCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_IMAGE &&
                        payloadIntCount == 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = 4097
                            Logger.info { IMAGE_SHADER_DESCRIPTOR_MAX_WIDTH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageShaderDescriptorHeightForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_SHADER_DESCRIPTOR_HEIGHT_PROPERTY)) return this
            if (!imageShaderDescriptorHeightCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_IMAGE &&
                        payloadIntCount == 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = 0
                            Logger.info { IMAGE_SHADER_DESCRIPTOR_HEIGHT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageShaderDescriptorMaxHeightForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_SHADER_DESCRIPTOR_MAX_HEIGHT_PROPERTY)) return this
            if (!imageShaderDescriptorMaxHeightCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_IMAGE &&
                        payloadIntCount == 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = 4097
                            Logger.info { IMAGE_SHADER_DESCRIPTOR_MAX_HEIGHT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageShaderDescriptorTileModeXForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_SHADER_DESCRIPTOR_TILE_MODE_X_PROPERTY)) return this
            if (!imageShaderDescriptorTileModeXCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_IMAGE &&
                        payloadIntCount == 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 4] = 99
                            Logger.info { IMAGE_SHADER_DESCRIPTOR_TILE_MODE_X_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageShaderDescriptorTileModeYForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_SHADER_DESCRIPTOR_TILE_MODE_Y_PROPERTY)) return this
            if (!imageShaderDescriptorTileModeYCorruptedForTesting.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_IMAGE &&
                        payloadIntCount == 6 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 5] = 99
                            Logger.info { IMAGE_SHADER_DESCRIPTOR_TILE_MODE_Y_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptPerlinNoiseShaderDescriptorForTestingIfRequested(): IntArray {
            val corruption = when {
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_KIND_PROPERTY) ->
                    PerlinNoiseShaderCorruption(0, 2, perlinNoiseShaderKindCorruptedForTesting, PERLIN_NOISE_SHADER_KIND_CORRUPTED_MARKER)
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_FREQUENCY_PROPERTY) ->
                    PerlinNoiseShaderCorruption(1, 0, perlinNoiseShaderFrequencyCorruptedForTesting, PERLIN_NOISE_SHADER_FREQUENCY_CORRUPTED_MARKER)
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_OCTAVES_PROPERTY) ->
                    PerlinNoiseShaderCorruption(3, 17, perlinNoiseShaderOctavesCorruptedForTesting, PERLIN_NOISE_SHADER_OCTAVES_CORRUPTED_MARKER)
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_ZERO_OCTAVES_PROPERTY) ->
                    PerlinNoiseShaderCorruption(
                        3,
                        0,
                        perlinNoiseShaderZeroOctavesCorruptedForTesting,
                        PERLIN_NOISE_SHADER_ZERO_OCTAVES_CORRUPTED_MARKER
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_TILE_SIZE_PROPERTY) ->
                    PerlinNoiseShaderCorruption(5, 4097, perlinNoiseShaderTileSizeCorruptedForTesting, PERLIN_NOISE_SHADER_TILE_SIZE_CORRUPTED_MARKER)
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_TILE_HEIGHT_PROPERTY) ->
                    PerlinNoiseShaderCorruption(
                        6,
                        4097,
                        perlinNoiseShaderTileHeightCorruptedForTesting,
                        PERLIN_NOISE_SHADER_TILE_HEIGHT_CORRUPTED_MARKER
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_NEGATIVE_TILE_SIZE_PROPERTY) ->
                    PerlinNoiseShaderCorruption(
                        5,
                        -1,
                        perlinNoiseShaderNegativeTileSizeCorruptedForTesting,
                        PERLIN_NOISE_SHADER_NEGATIVE_TILE_SIZE_CORRUPTED_MARKER
                    )
                java.lang.Boolean.getBoolean(CORRUPT_PERLIN_NOISE_SHADER_NEGATIVE_TILE_HEIGHT_PROPERTY) ->
                    PerlinNoiseShaderCorruption(
                        6,
                        -1,
                        perlinNoiseShaderNegativeTileHeightCorruptedForTesting,
                        PERLIN_NOISE_SHADER_NEGATIVE_TILE_HEIGHT_CORRUPTED_MARKER
                    )
                else -> null
            } ?: return this
            if (!corruption.once.compareAndSet(false, true)) return this
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
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_PERLIN_NOISE &&
                        payloadIntCount == 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + corruption.payloadOffset] = corruption.value
                            Logger.info { corruption.marker }
                        }
                    }
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

        private fun IntArray.corruptColorFilterHandleTypeForTestingIfRequested(): IntArray {
            val corruptToImageFilter = java.lang.Boolean.getBoolean(CORRUPT_COLOR_FILTER_HANDLE_TYPE_PROPERTY)
            val corruptToPathEffect =
                java.lang.Boolean.getBoolean(CORRUPT_COLOR_FILTER_HANDLE_TO_PATH_EFFECT_TYPE_PROPERTY)
            if (!corruptToImageFilter && !corruptToPathEffect) return this
            if (!colorFilterHandleTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this

            val wrongTypeHandle = if (corruptToPathEffect) {
                firstEffectDescriptorHandle(commandEnd) { descriptorType ->
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_CORNER_PATH_EFFECT ||
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_STAMPED_PATH_EFFECT ||
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT
                }
            } else {
                firstEffectDescriptorHandle(commandEnd) { descriptorType ->
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER ||
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER ||
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_BLUR_IMAGE_FILTER_WITH_INPUT ||
                        descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT
                }
            } ?: return this

            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 9 &&
                        !corruptToPathEffect
                    ) {
                        val payloadStart = argsStart + 5
                        val childCount = this[payloadStart + 2]
                        if (childCount > 0 && payloadStart + 8 <= recordEnd) {
                            return copyOf().also { stream ->
                                stream[payloadStart + 7] = wrongTypeHandle.first
                                stream[payloadStart + 8] = wrongTypeHandle.second
                                Logger.info {
                                    "$COLOR_FILTER_HANDLE_TYPE_CORRUPTED_MARKER target=runtimeEffectColorFilterChild"
                                }
                            }
                        }
                    }
                } else if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER &&
                        payloadIntCount == 4 &&
                        !corruptToPathEffect
                    ) {
                        return copyOf().also { stream ->
                            stream[argsStart + 7] = wrongTypeHandle.first
                            stream[argsStart + 8] = wrongTypeHandle.second
                            Logger.info { "$COLOR_FILTER_HANDLE_TYPE_CORRUPTED_MARKER target=shaderColorFilter" }
                        }
                    }
                } else if (op == COMMAND_FILL_RECT_COLOR_FILTER_REF && argsStart + 7 <= recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 1] = wrongTypeHandle.first
                        stream[argsStart + 2] = wrongTypeHandle.second
                        val target = if (corruptToPathEffect) "fillRectColorFilterPathEffect" else "fillRectColorFilter"
                        Logger.info { "$COLOR_FILTER_HANDLE_TYPE_CORRUPTED_MARKER target=$target" }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptImageFilterHandleTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_IMAGE_FILTER_HANDLE_TYPE_PROPERTY)) return this
            if (!imageFilterHandleTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this

            val colorFilterHandle = firstEffectDescriptorHandle(commandEnd) { descriptorType ->
                descriptorType == COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER ||
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER ||
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER
            } ?: return this

            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_OFFSET_IMAGE_FILTER_WITH_INPUT &&
                        payloadIntCount == 4
                    ) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = colorFilterHandle.first
                            stream[argsStart + 6] = colorFilterHandle.second
                            Logger.info { "$IMAGE_FILTER_HANDLE_TYPE_CORRUPTED_MARKER target=offsetImageFilterChild" }
                        }
                    }
                } else if (op == COMMAND_SAVE_LAYER_IMAGE_FILTER_REF && argsStart + 7 <= recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart + 5] = colorFilterHandle.first
                        stream[argsStart + 6] = colorFilterHandle.second
                        Logger.info { "$IMAGE_FILTER_HANDLE_TYPE_CORRUPTED_MARKER target=saveLayerImageFilter" }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptPathEffectHandleTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_PATH_EFFECT_HANDLE_TYPE_PROPERTY)) return this
            if (!pathEffectHandleTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this

            val colorFilterHandle = firstEffectDescriptorHandle(commandEnd) { descriptorType ->
                descriptorType == COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER ||
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER ||
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER
            } ?: return this

            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_CHAIN_PATH_EFFECT &&
                        payloadIntCount == 4
                    ) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = colorFilterHandle.first
                            stream[argsStart + 6] = colorFilterHandle.second
                            Logger.info { "$PATH_EFFECT_HANDLE_TYPE_CORRUPTED_MARKER target=chainPathEffectChild" }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptShaderHandleTypeForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SHADER_HANDLE_TYPE_PROPERTY)) return this
            if (!shaderHandleTypeCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this

            val colorFilterHandle = firstEffectDescriptorHandle(commandEnd) { descriptorType ->
                descriptorType == COMMAND_EFFECT_DESCRIPTOR_TINT_COLOR_FILTER ||
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_COLOR_MATRIX_FILTER ||
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER
            } ?: return this

            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 7 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT && payloadIntCount >= 9) {
                        val payloadStart = argsStart + 5
                        val childCount = this[payloadStart + 2]
                        if (childCount > 0 && payloadStart + 8 <= recordEnd) {
                            return copyOf().also { stream ->
                                stream[payloadStart + 7] = colorFilterHandle.first
                                stream[payloadStart + 8] = colorFilterHandle.second
                                Logger.info { "$SHADER_HANDLE_TYPE_CORRUPTED_MARKER target=runtimeEffectShaderChild" }
                            }
                        }
                    } else if (descriptorType == COMMAND_SHADER_DESCRIPTOR_COMPOSITE && payloadIntCount == 5) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = colorFilterHandle.first
                            stream[argsStart + 6] = colorFilterHandle.second
                            Logger.info { "$SHADER_HANDLE_TYPE_CORRUPTED_MARKER target=compositeShaderDstChild" }
                        }
                    } else if (descriptorType == COMMAND_SHADER_DESCRIPTOR_TRANSFORM && payloadIntCount == 11) {
                        return copyOf().also { stream ->
                            stream[argsStart + 5] = colorFilterHandle.first
                            stream[argsStart + 6] = colorFilterHandle.second
                            Logger.info { "$SHADER_HANDLE_TYPE_CORRUPTED_MARKER target=transformedShaderChild" }
                        }
                    }
                } else if (op == COMMAND_FILL_RECT_SHADER_REF && argsStart + 7 <= recordEnd) {
                    return copyOf().also { stream ->
                        stream[argsStart] = colorFilterHandle.first
                        stream[argsStart + 1] = colorFilterHandle.second
                        Logger.info { "$SHADER_HANDLE_TYPE_CORRUPTED_MARKER target=fillRectShader" }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptShaderChildMissingForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_SHADER_CHILD_MISSING_PROPERTY)) return this
            if (!shaderChildMissingCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this

            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 7 <= recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val target = when {
                        descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT && payloadIntCount >= 9 -> {
                            val payloadStart = argsStart + 5
                            val childCount = this[payloadStart + 2]
                            if (childCount <= 0 || payloadStart + 8 > recordEnd) null else "runtimeEffectShaderChild"
                        }
                        descriptorType == COMMAND_SHADER_DESCRIPTOR_COMPOSITE && payloadIntCount == 5 ->
                            "compositeShaderDstChild"
                        descriptorType == COMMAND_SHADER_DESCRIPTOR_COLOR_FILTER && payloadIntCount == 4 ->
                            "shaderColorFilterShaderChild"
                        descriptorType == COMMAND_SHADER_DESCRIPTOR_TRANSFORM && payloadIntCount == 11 ->
                            "transformedShaderChild"
                        else -> null
                    }
                    if (target != null) {
                        return copyOf().also { stream ->
                            val childHandleOffset =
                                if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT) argsStart + 12 else argsStart + 5
                            stream[childHandleOffset] = 0x7f00_0001
                            stream[childHandleOffset + 1] = 0x7f00_0002
                            Logger.info { "$SHADER_CHILD_MISSING_CORRUPTED_MARKER target=$target" }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private inline fun IntArray.firstEffectDescriptorHandle(
            commandEnd: Int,
            typePredicate: (Int) -> Boolean,
        ): Pair<Int, Int>? {
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return null
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    if (typePredicate(descriptorType)) {
                        return this[argsStart] to this[argsStart + 1]
                    }
                }
                offset = recordEnd
            }
            return null
        }

        private fun IntArray.corruptRuntimeEffectShaderSourceHashForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_SOURCE_HASH_PROPERTY)) return this
            if (!runtimeEffectShaderSourceHashCorruptedForTesting.compareAndSet(false, true)) return this
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
                        return copyOf().also { stream ->
                            stream[payloadStart + 5] = stream[payloadStart + 5] xor 1
                            Logger.info { RUNTIME_EFFECT_SHADER_SOURCE_HASH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderSkslLengthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_SKSL_LENGTH_PROPERTY)) return this
            if (!runtimeEffectShaderSkslLengthCorruptedForTesting.compareAndSet(false, true)) return this
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
                        return copyOf().also { stream ->
                            stream[payloadStart] = 0
                            Logger.info { RUNTIME_EFFECT_SHADER_SKSL_LENGTH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterSkslLengthForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_SKSL_LENGTH_PROPERTY)) return this
            if (!runtimeEffectColorFilterSkslLengthCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 5 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart] = 0
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_SKSL_LENGTH_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterUniformFloatCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_UNIFORM_FLOAT_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectColorFilterUniformFloatCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 1] = 257
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterNegativeUniformFloatCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(
                    CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_UNIFORM_FLOAT_COUNT_PROPERTY
                )
            ) {
                return this
            }
            if (!runtimeEffectColorFilterNegativeUniformFloatCountCorruptedForTesting.compareAndSet(false, true)) {
                return this
            }
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 1] = -1
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_CHILD_COUNT_PROPERTY)) return this
            if (!runtimeEffectColorFilterChildCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = 9
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterNegativeChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_CHILD_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectColorFilterNegativeChildCountCorruptedForTesting.compareAndSet(false, true)) {
                return this
            }
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = -1
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterNamedUniformCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NAMED_UNIFORM_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectColorFilterNamedUniformCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 8 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = 17
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterNegativeNamedUniformCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(
                    CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_UNIFORM_COUNT_PROPERTY
                )
            ) {
                return this
            }
            if (!runtimeEffectColorFilterNegativeNamedUniformCountCorruptedForTesting.compareAndSet(false, true)) {
                return this
            }
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 8 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = -1
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterNamedChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NAMED_CHILD_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectColorFilterNamedChildCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 4] = 9
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_NAMED_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectColorFilterNegativeNamedChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(
                    CORRUPT_RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_CHILD_COUNT_PROPERTY
                )
            ) {
                return this
            }
            if (!runtimeEffectColorFilterNegativeNamedChildCountCorruptedForTesting.compareAndSet(false, true)) {
                return this
            }
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR && argsStart + 9 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 4] = -1
                            Logger.info { RUNTIME_EFFECT_COLOR_FILTER_NEGATIVE_NAMED_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderUniformFloatCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_UNIFORM_FLOAT_COUNT_PROPERTY)) return this
            if (!runtimeEffectShaderUniformFloatCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 1] = 257
                            Logger.info { RUNTIME_EFFECT_SHADER_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderNegativeUniformFloatCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_UNIFORM_FLOAT_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectShaderNegativeUniformFloatCountCorruptedForTesting.compareAndSet(false, true)) {
                return this
            }
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 6 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 1] = -1
                            Logger.info { RUNTIME_EFFECT_SHADER_NEGATIVE_UNIFORM_FLOAT_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_CHILD_COUNT_PROPERTY)) return this
            if (!runtimeEffectShaderChildCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = 9
                            Logger.info { RUNTIME_EFFECT_SHADER_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderNegativeChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_CHILD_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectShaderNegativeChildCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 7 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 2] = -1
                            Logger.info { RUNTIME_EFFECT_SHADER_NEGATIVE_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderNamedUniformCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_NAMED_UNIFORM_COUNT_PROPERTY)) return this
            if (!runtimeEffectShaderNamedUniformCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 8 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = 17
                            Logger.info { RUNTIME_EFFECT_SHADER_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderNegativeNamedUniformCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_UNIFORM_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectShaderNegativeNamedUniformCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 8 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 3] = -1
                            Logger.info { RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_UNIFORM_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderNamedChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_NAMED_CHILD_COUNT_PROPERTY)) return this
            if (!runtimeEffectShaderNamedChildCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 9 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 4] = stream[payloadStart + 2] + 1
                            Logger.info { RUNTIME_EFFECT_SHADER_NAMED_CHILD_COUNT_CORRUPTED_MARKER }
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectShaderNegativeNamedChildCountForTestingIfRequested(): IntArray {
            if (!java.lang.Boolean.getBoolean(CORRUPT_RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_CHILD_COUNT_PROPERTY)) {
                return this
            }
            if (!runtimeEffectShaderNegativeNamedChildCountCorruptedForTesting.compareAndSet(false, true)) return this
            val commandEnd = COMMAND_STREAM_HEADER_SIZE + getOrNull(3).orZero()
            if (commandEnd > size) return this
            var offset = COMMAND_STREAM_HEADER_SIZE
            while (offset + 3 <= commandEnd) {
                val op = this[offset]
                val recordLengthInts = this[offset + 1] / Int.SIZE_BYTES
                val recordEnd = offset + recordLengthInts
                if (recordLengthInts < 3 || recordEnd > commandEnd) return this
                val argsStart = offset + 3
                if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && argsStart + 9 < recordEnd) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        return copyOf().also { stream ->
                            stream[payloadStart + 4] = -1
                            Logger.info { RUNTIME_EFFECT_SHADER_NEGATIVE_NAMED_CHILD_COUNT_CORRUPTED_MARKER }
                        }
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
                if ((op == COMMAND_DEFINE_SHADER_DESCRIPTOR || op == COMMAND_DEFINE_EFFECT_DESCRIPTOR) &&
                    argsStart + 5 < recordEnd
                ) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (isRuntimeEffectSourceDescriptor(op, descriptorType) &&
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

        private fun isRuntimeEffectSourceDescriptor(op: Int, descriptorType: Int): Boolean =
            (op == COMMAND_DEFINE_SHADER_DESCRIPTOR && descriptorType == COMMAND_SHADER_DESCRIPTOR_RUNTIME_EFFECT) ||
                (op == COMMAND_DEFINE_EFFECT_DESCRIPTOR &&
                    descriptorType == COMMAND_EFFECT_DESCRIPTOR_RUNTIME_COLOR_FILTER)

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
                if ((op == COMMAND_DEFINE_SHADER_DESCRIPTOR || op == COMMAND_DEFINE_EFFECT_DESCRIPTOR) &&
                    argsStart + 5 < recordEnd
                ) {
                    val descriptorType = this[argsStart + 2]
                    val payloadIntCount = this[argsStart + 4]
                    val payloadStart = argsStart + 5
                    val payloadEnd = payloadStart + payloadIntCount
                    if (isRuntimeEffectSourceDescriptor(op, descriptorType) &&
                        payloadIntCount >= 7 &&
                        payloadEnd <= recordEnd
                    ) {
                        corruptRuntimeEffectDescriptorChildType(op, offset, recordEnd, payloadStart, payloadEnd)?.let {
                            return it
                        }
                    }
                }
                offset = recordEnd
            }
            return this
        }

        private fun IntArray.corruptRuntimeEffectDescriptorChildType(
            op: Int,
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
            val replacement = if (op == COMMAND_DEFINE_SHADER_DESCRIPTOR) {
                source
                    .replace("uniform shader content;", "uniform colorFilter content;")
                    .replace(
                        "half4 base = content.eval(p);",
                        "half4 base = content.eval(half4(0.25, 0.45, 0.85, 1.0));",
                    )
            } else {
                source
                    .replace("uniform colorFilter content;", "uniform shader content;")
                    .replace("half4 base = content.eval(inColor);", "half4 base = content.eval(float2(0.25, 0.45));")
            }
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
