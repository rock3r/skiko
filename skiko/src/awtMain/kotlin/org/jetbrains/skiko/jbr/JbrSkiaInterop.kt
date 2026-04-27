package org.jetbrains.skiko.jbr

import org.jetbrains.skiko.Logger
import java.awt.Graphics2D
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

object JbrSkiaInterop {
    const val FALLBACK_MARKER = "SKIKO_JBR_INTEROP_FALLBACK"
    private const val EXPECTED_ABI_ID = 1
    private const val JBR_SKIA_CLASS = "com.jetbrains.desktop.JBRSkia"
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
        val service = discovery.service ?: return null
        return try {
            val scope = service.javaClass
                .getMethod(ACQUIRE_CANVAS_METHOD, Graphics2D::class.java)
                .invoke(service, graphics)
                ?: return fallback(FallbackReason.CANVAS_UNAVAILABLE)
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
            val jbrSkiaClass = classResolver.loadClass(JBR_SKIA_CLASS)
            val abiId = jbrSkiaClass.getDeclaredField("ABI_ID").get(null) as Int
            val buildId = jbrSkiaClass.getDeclaredField("BUILD_ID").get(null) as String
            if (abiId != EXPECTED_ABI_ID) {
                return Discovery.fallback(FallbackReason.ABI_MISMATCH, abiId = abiId, buildId = buildId)
            }

            val accessorClass = classResolver.loadClass(JBR_ACCESSOR_CLASS)
            val service = accessorClass.getDeclaredMethod(JBR_ACCESSOR_METHOD).invoke(null)
                ?: return Discovery.fallback(FallbackReason.SERVICE_UNAVAILABLE, abiId = abiId, buildId = buildId)

            Discovery.available(service, abiId, buildId)
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

    internal interface ClassResolver {
        fun loadClass(name: String): Class<*>
    }

    private object DefaultClassResolver : ClassResolver {
        override fun loadClass(name: String): Class<*> = Class.forName(name)
    }

    internal data class Discovery(
        val service: Any?,
        val abiId: Int?,
        val buildId: String?,
        val fallbackReason: FallbackReason?,
        val cause: Throwable? = null,
    ) {
        val isAvailable: Boolean get() = service != null

        val fallbackMarker: String
            get() = "$FALLBACK_MARKER reason=${fallbackReason?.id ?: "none"}"

        companion object {
            fun available(service: Any, abiId: Int, buildId: String) =
                Discovery(service, abiId, buildId, fallbackReason = null)

            fun fallback(
                fallbackReason: FallbackReason,
                abiId: Int? = null,
                buildId: String? = null,
                cause: Throwable? = null,
            ) = Discovery(null, abiId, buildId, fallbackReason, cause)
        }
    }

    internal enum class FallbackReason(val id: String) {
        ABI_MISMATCH("abi-mismatch"),
        PUBLIC_API_INACCESSIBLE("public-api-inaccessible"),
        PUBLIC_API_LINKAGE_ERROR("public-api-linkage-error"),
        PUBLIC_API_MISSING("public-api-missing"),
        SERVICE_UNAVAILABLE("service-unavailable"),
        CANVAS_UNAVAILABLE("canvas-unavailable"),
    }

    internal interface ScopedCanvas : AutoCloseable

    private class ReflectiveScopedCanvas(private val scope: Any) : ScopedCanvas {
        override fun close() {
            scope.javaClass.getMethod("close").invoke(scope)
        }
    }
}
