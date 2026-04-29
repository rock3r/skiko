package org.jetbrains.skiko.jbr

import org.jetbrains.skiko.Logger
import java.awt.Graphics2D
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object JbrSkiaInterop {
    const val FALLBACK_MARKER = "SKIKO_JBR_INTEROP_FALLBACK"
    const val SCOPE_ACQUIRED_MARKER = "SKIKO_JBR_INTEROP_SCOPE_ACQUIRED"
    private const val EXPECTED_ABI_ID = 31
    private const val EXPECTED_ABI_ID_FOR_TEST_PROPERTY = "skiko.jbr.interop.expectedAbiIdForTest"
    private const val REQUIRED_COMMAND_CAPABILITIES_FOR_TEST_PROPERTY =
        "skiko.jbr.interop.requiredCommandCapabilitiesForTest"
    private const val COMMAND_CAP_CLEAR = 1
    private const val COMMAND_CAP_FILL_RECT = 2
    private const val COMMAND_CAP_STROKE_LINE = 4
    private const val COMMAND_CAP_FILL_OVAL = 8
    private const val COMMAND_CAP_STROKE_OVAL = 16
    private const val COMMAND_CAP_CLEAR_RECT = 32
    private const val COMMAND_CAP_SAVE_RESTORE = 64
    private const val COMMAND_CAP_CLIP_RECT = 128
    private const val COMMAND_CAP_USER_SPACE_COORDINATES = 256
    private const val COMMAND_CAP_RECORD_ANTIALIAS = 512
    private const val COMMAND_CAP_STROKE_METADATA = 1024
    private const val COMMAND_CAP_BASIC_TRANSFORMS = 2048
    private const val COMMAND_CAP_CLIP_RECT_OP = 4096
    private const val COMMAND_CAP_SAVE_LAYER = 8192
    private const val COMMAND_CAP_DRAW_IMAGE_ARGB = 16384
    private const val COMMAND_CAP_IMAGE_CACHE = 32768
    private const val COMMAND_CAP_DRAW_TEXT_UTF16 = 65536
    private const val COMMAND_CAP_CLEAR_IMAGE_CACHE = 131072
    private const val COMMAND_CAP_DRAW_PARAGRAPH_UTF16 = 262144
    private const val COMMAND_CAP_PARAGRAPH_FONT_STYLE = 524288
    private const val COMMAND_CAP_PARAGRAPH_LAYOUT = 1048576
    private const val COMMAND_CAP_PARAGRAPH_LINE_HEIGHT = 2097152
    private const val COMMAND_CAP_PARAGRAPH_OVERFLOW = 4194304
    private const val COMMAND_CAP_PARAGRAPH_DECORATION = 8388608
    private const val COMMAND_CAP_PARAGRAPH_LETTER_SPACING = 16777216
    private const val COMMAND_CAP_PARAGRAPH_BACKGROUND = 33554432
    private const val COMMAND_CAP_CLIP_PATH = 67108864
    private const val COMMAND_CAP_DRAW_PATH = 134217728
    private const val COMMAND_CAP_DRAW_ARC = 268435456
    private const val COMMAND_CAP_DRAW_ROUND_RECT = 536870912
    private const val COMMAND_CAP_FILL_RECT_LINEAR_GRADIENT = 1073741824
    private const val REQUIRED_COMMAND_CAPABILITIES =
        COMMAND_CAP_CLEAR.toLong() or
            COMMAND_CAP_FILL_RECT.toLong() or
            COMMAND_CAP_STROKE_LINE.toLong() or
            COMMAND_CAP_FILL_OVAL.toLong() or
            COMMAND_CAP_STROKE_OVAL.toLong() or
            COMMAND_CAP_CLEAR_RECT.toLong() or
            COMMAND_CAP_SAVE_RESTORE.toLong() or
            COMMAND_CAP_CLIP_RECT.toLong() or
            COMMAND_CAP_USER_SPACE_COORDINATES.toLong() or
            COMMAND_CAP_RECORD_ANTIALIAS.toLong() or
            COMMAND_CAP_STROKE_METADATA.toLong() or
            COMMAND_CAP_BASIC_TRANSFORMS.toLong() or
            COMMAND_CAP_CLIP_RECT_OP.toLong() or
            COMMAND_CAP_SAVE_LAYER.toLong() or
            COMMAND_CAP_DRAW_IMAGE_ARGB.toLong() or
            COMMAND_CAP_IMAGE_CACHE.toLong() or
            COMMAND_CAP_DRAW_TEXT_UTF16.toLong() or
            COMMAND_CAP_CLEAR_IMAGE_CACHE.toLong() or
            COMMAND_CAP_DRAW_PARAGRAPH_UTF16.toLong() or
            COMMAND_CAP_PARAGRAPH_FONT_STYLE.toLong() or
            COMMAND_CAP_PARAGRAPH_LAYOUT.toLong() or
            COMMAND_CAP_PARAGRAPH_LINE_HEIGHT.toLong() or
            COMMAND_CAP_PARAGRAPH_OVERFLOW.toLong() or
            COMMAND_CAP_PARAGRAPH_DECORATION.toLong() or
            COMMAND_CAP_PARAGRAPH_LETTER_SPACING.toLong() or
            COMMAND_CAP_PARAGRAPH_BACKGROUND.toLong() or
            COMMAND_CAP_CLIP_PATH.toLong() or
            COMMAND_CAP_DRAW_PATH.toLong() or
            COMMAND_CAP_DRAW_ARC.toLong() or
            COMMAND_CAP_DRAW_ROUND_RECT.toLong() or
            COMMAND_CAP_FILL_RECT_LINEAR_GRADIENT.toLong()
    private val JBR_SKIA_CLASSES = arrayOf("com.jetbrains.JBRSkia", "com.jetbrains.desktop.JBRSkia")
    private const val JBR_INTERNAL_SERVICE_CLASS = "com.jetbrains.desktop.JBRSkiaService"
    private const val JBR_ACCESSOR_CLASS = "com.jetbrains.JBR"
    private const val JBR_ACCESSOR_METHOD = "getJBRSkia"
    private const val ACQUIRE_CANVAS_METHOD = "acquireCanvas"

    @Volatile
    private var cachedDiscovery: Discovery? = null
    private val loggedFallbackMarkers = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun isAvailable(): Boolean = discoverCached().isAvailable

    /**
     * Attempts to acquire a JBR-owned Skia canvas for the current Swing paint.
     *
     * A null result is the supported fallback signal. Callers must continue with
     * the regular Swing renderer and should not treat this as an error.
     */
    @JvmStatic
    fun acquireCanvasOrNull(graphics: Graphics2D): AutoCloseable? = acquireCanvas(graphics)

    internal fun logFallback(reason: FallbackReason) {
        logFallbackOnce(Discovery.fallback(reason).fallbackMarker)
    }

    internal fun discover(): Discovery = discover(DefaultClassResolver)

    internal fun acquireCanvas(graphics: Graphics2D): ScopedCanvas? =
        acquireCanvas(graphics) { discoverCached() }

    internal fun acquireCanvas(graphics: Graphics2D, classResolver: ClassResolver): ScopedCanvas? =
        acquireCanvas(graphics) { discover(classResolver) }

    private fun acquireCanvas(
        graphics: Graphics2D,
        discoveryProvider: () -> Discovery,
    ): ScopedCanvas? {
        val discovery = discoveryProvider()
        val service = discovery.service ?: run {
            logFallbackOnce(discovery.fallbackMarker)
            return null
        }
        return try {
            val scope = service.javaClass
                .getMethod(ACQUIRE_CANVAS_METHOD, Graphics2D::class.java)
                .invoke(service, graphics)
                ?: return fallback(FallbackReason.CANVAS_UNAVAILABLE)
            logScopeAcquiredOnce(discovery, scope)
            ReflectiveScopedCanvas(scope)
        } catch (e: NoSuchMethodException) {
            fallback(FallbackReason.PUBLIC_API_MISSING, e)
        } catch (e: IllegalAccessException) {
            fallback(FallbackReason.PUBLIC_API_INACCESSIBLE, e)
        } catch (e: InvocationTargetException) {
            fallback(FallbackReason.CANVAS_UNAVAILABLE, e)
        } catch (e: LinkageError) {
            fallback(FallbackReason.PUBLIC_API_LINKAGE_ERROR, e)
        }
    }

    internal fun discover(classResolver: ClassResolver): Discovery {
        return try {
            val jbrSkiaClass = classResolver.loadFirstClass(JBR_SKIA_CLASSES)
            val abiId = jbrSkiaClass.getDeclaredField("ABI_ID").get(null) as Int
            val buildId = jbrSkiaClass.getDeclaredField("BUILD_ID").get(null) as String
            if (abiId != expectedAbiId()) {
                return Discovery.fallback(FallbackReason.ABI_MISMATCH, abiId = abiId, buildId = buildId)
            }

            val accessorClass = classResolver.loadClass(JBR_ACCESSOR_CLASS)
            val service = accessorClass.getDeclaredMethod(JBR_ACCESSOR_METHOD).invoke(null)
                ?: instantiateInternalServiceForPatchedJbr(classResolver)
                ?: return Discovery.fallback(FallbackReason.SERVICE_UNAVAILABLE, abiId = abiId, buildId = buildId)
            val commandCapabilities = service.javaClass.getMethod("getCommandCapabilities64").invoke(service) as Long
            val requiredCommandCapabilities = requiredCommandCapabilities()
            if (commandCapabilities and requiredCommandCapabilities != requiredCommandCapabilities) {
                return Discovery.fallback(
                    FallbackReason.COMMAND_CAPABILITY_MISMATCH,
                    abiId = abiId,
                    buildId = buildId,
                    commandCapabilities = commandCapabilities,
                )
            }

            Discovery.available(service, abiId, buildId, commandCapabilities)
        } catch (e: ClassNotFoundException) {
            Discovery.fallback(FallbackReason.PUBLIC_API_MISSING, cause = e)
        } catch (e: NoSuchMethodException) {
            Discovery.fallback(FallbackReason.PUBLIC_API_MISSING, cause = e)
        } catch (e: NoSuchFieldException) {
            Discovery.fallback(FallbackReason.PUBLIC_API_MISSING, cause = e)
        } catch (e: IllegalAccessException) {
            Discovery.fallback(FallbackReason.PUBLIC_API_INACCESSIBLE, cause = e)
        } catch (e: InvocationTargetException) {
            Discovery.fallback(FallbackReason.SERVICE_UNAVAILABLE, cause = e)
        } catch (e: LinkageError) {
            Discovery.fallback(FallbackReason.PUBLIC_API_LINKAGE_ERROR, cause = e)
        }.also { discovery ->
            if (!discovery.isAvailable) {
                logFallbackOnce(discovery.fallbackMarker)
            }
        }
    }

    private fun discoverCached(): Discovery =
        cachedDiscovery ?: discover().also { cachedDiscovery = it }

    private fun expectedAbiId(): Int =
        System.getProperty(EXPECTED_ABI_ID_FOR_TEST_PROPERTY)?.toIntOrNull() ?: EXPECTED_ABI_ID

    private fun requiredCommandCapabilities(): Long =
        System.getProperty(REQUIRED_COMMAND_CAPABILITIES_FOR_TEST_PROPERTY)?.toLongOrNull()
            ?: REQUIRED_COMMAND_CAPABILITIES

    private fun instantiateInternalServiceForPatchedJbr(classResolver: ClassResolver): Any? =
        try {
            classResolver.loadClass(JBR_INTERNAL_SERVICE_CLASS).getConstructor().newInstance()
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }

    private fun fallback(reason: FallbackReason, cause: Throwable? = null): ScopedCanvas? {
        val marker = Discovery.fallback(reason, cause = cause).fallbackMarker
        logFallbackOnce(marker)
        return null
    }

    private fun logFallbackOnce(marker: String) {
        if (loggedFallbackMarkers.add(marker)) {
            Logger.warn { marker }
        }
    }

    private fun logScopeAcquiredOnce(discovery: Discovery, scope: Any) {
            val marker = "$SCOPE_ACQUIRED_MARKER abi=${discovery.abiId} build=${discovery.buildId} metalTexture=${scope.metalTexturePtr().toHexString()}"
        if (loggedFallbackMarkers.add(marker)) {
            Logger.info { marker }
        }
    }

    private fun Long?.toHexString(): String = this?.let { "0x${it.toString(16)}" } ?: "unavailable"

    internal interface ClassResolver {
        fun loadClass(name: String): Class<*>
    }

    private fun ClassResolver.loadFirstClass(names: Array<String>): Class<*> {
        var firstFailure: ClassNotFoundException? = null
        for (name in names) {
            try {
                return loadClass(name)
            } catch (e: ClassNotFoundException) {
                if (firstFailure == null) {
                    firstFailure = e
                }
            }
        }
        throw firstFailure ?: ClassNotFoundException(names.joinToString())
    }

    private object DefaultClassResolver : ClassResolver {
        override fun loadClass(name: String): Class<*> = Class.forName(name)
    }

    internal data class Discovery(
        val service: Any?,
        val abiId: Int?,
        val buildId: String?,
        val commandCapabilities: Long?,
        val fallbackReason: FallbackReason?,
        val cause: Throwable? = null,
    ) {
        val isAvailable: Boolean get() = service != null

        val fallbackMarker: String
            get() = "$FALLBACK_MARKER reason=${fallbackReason?.id ?: "none"}"

        companion object {
            fun available(service: Any, abiId: Int, buildId: String, commandCapabilities: Long) =
                Discovery(service, abiId, buildId, commandCapabilities, fallbackReason = null)

            fun fallback(
                fallbackReason: FallbackReason,
                abiId: Int? = null,
                buildId: String? = null,
                commandCapabilities: Long? = null,
                cause: Throwable? = null,
            ) = Discovery(null, abiId, buildId, commandCapabilities, fallbackReason, cause)
        }
    }

    internal enum class FallbackReason(val id: String) {
        ABI_MISMATCH("abi-mismatch"),
        PUBLIC_API_INACCESSIBLE("public-api-inaccessible"),
        PUBLIC_API_LINKAGE_ERROR("public-api-linkage-error"),
        PUBLIC_API_MISSING("public-api-missing"),
        SERVICE_UNAVAILABLE("service-unavailable"),
        CANVAS_UNAVAILABLE("canvas-unavailable"),
        COMMAND_CAPABILITY_MISMATCH("command-capability-mismatch"),
        COMMAND_STREAM_INVALID("command-stream-invalid"),
    }

    internal interface ScopedCanvas : AutoCloseable {
        val metalTexturePtr: Long

        fun renderDiagnosticFrame(width: Int, height: Int, frameTimeNanos: Long): Boolean

        fun renderCommandFrame(width: Int, height: Int, frameTimeNanos: Long, commands: IntArray): Boolean

        fun renderCommandBufferFrame(width: Int, height: Int, frameTimeNanos: Long, commands: ByteArray): Boolean

        fun renderCommandDirectFrame(width: Int, height: Int, frameTimeNanos: Long, commands: ByteBuffer): Boolean

        fun renderPictureFrame(width: Int, height: Int, frameTimeNanos: Long, pictureData: ByteArray): Boolean

        fun flush()
    }

    private class ReflectiveScopedCanvas(private val scope: Any) : ScopedCanvas {
        override val metalTexturePtr: Long
            get() = scope.metalTexturePtr() ?: 0L

        override fun renderDiagnosticFrame(width: Int, height: Int, frameTimeNanos: Long): Boolean =
            scope.invokeScopeMethod("renderDiagnosticFrame", width, height, frameTimeNanos) as? Boolean ?: false

        override fun renderCommandFrame(width: Int, height: Int, frameTimeNanos: Long, commands: IntArray): Boolean =
            scope.invokeScopeMethod("renderCommandFrame", width, height, frameTimeNanos, commands) as? Boolean ?: false

        override fun renderCommandBufferFrame(width: Int, height: Int, frameTimeNanos: Long, commands: ByteArray): Boolean =
            scope.invokeScopeMethod("renderCommandBufferFrame", width, height, frameTimeNanos, commands) as? Boolean ?: false

        override fun renderCommandDirectFrame(width: Int, height: Int, frameTimeNanos: Long, commands: ByteBuffer): Boolean =
            scope.invokeScopeMethod("renderCommandDirectFrame", width, height, frameTimeNanos, commands) as? Boolean ?: false

        override fun renderPictureFrame(width: Int, height: Int, frameTimeNanos: Long, pictureData: ByteArray): Boolean =
            scope.invokeScopeMethod("renderPictureFrame", width, height, frameTimeNanos, pictureData) as? Boolean ?: false

        override fun flush() {
            scope.invokeScopeMethod("flush")
        }

        override fun close() {
            if (scope is AutoCloseable) {
                scope.close()
            } else {
                scope.javaClass.getMethod("close").invoke(scope)
            }
        }
    }

    private fun Any.metalTexturePtr(): Long? {
        return try {
            invokeScopeMethod("getMetalTexturePtr") as? Long
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun Any.invokeScopeMethod(name: String, vararg args: Any): Any? {
        val parameterTypes = args.map { arg ->
            when (arg) {
                is Int -> Integer.TYPE
                is Long -> java.lang.Long.TYPE
                is ByteBuffer -> ByteBuffer::class.java
                else -> arg.javaClass
            }
        }.toTypedArray()
        val method = scopePublicTypes()
            .firstNotNullOfOrNull { type ->
                runCatching { type.getMethod(name, *parameterTypes) }.getOrNull()
            }
            ?: javaClass.getMethod(name, *parameterTypes)
        return method.invoke(this, *args)
    }

    private fun Any.scopePublicTypes(): Sequence<Class<*>> =
        generateSequence(javaClass) { it.superclass }
            .flatMap { type -> sequenceOf(type) + type.interfaces.asSequence() }
            .filter { type -> java.lang.reflect.Modifier.isPublic(type.modifiers) }
}
