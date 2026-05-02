# JBR Skia Interop Notes

This worktree contains an experimental Swing path where Skiko renders Compose command frames into a JBR-owned Skia/Metal destination.

## Compatibility Gate

Skiko reflectively reads the public JBR API `ABI_ID`/`BUILD_ID`, then acquires `com.jetbrains.JBR.getJBRSkia()`.
The current command stream gate is ABI 101 and requires both low-word and high-word command capability masks through
`COMMAND_CAP64_HIGH_DRAW_POINTS`. If either the ABI/build id, native ABI/build metadata, or required capability bits do not match, Skiko emits
`SKIKO_JBR_INTEROP_FALLBACK reason=abi-mismatch`, `native-abi-mismatch`, or `command-capability-mismatch` and uses the
old Swing path for that paint.

The RuntimeEffect shader/color-filter paths still do not pass Skiko-owned Skia objects to JBR. CMP serializes SKSL,
source hashes, uniform floats, named uniform schema, and child descriptor handles; JBR compiles the `SkRuntimeEffect`,
`SkShader`, and `SkColorFilter` objects inside the destination context.

## Artifact Shape

The PoC keeps `org.jetbrains.skiko:skiko-awt` as the JVM/Kotlin API artifact that Compose and Skiko callers compile
against. JBR command replay is an AWT runtime mode discovered reflectively at paint time; it is not a separate raw-Skia
native bridge inside Skiko.

The normal `skiko-awt-runtime-*` artifacts remain the correct dependency for applications that need the old SwingGraphics
fallback, direct Skiko surfaces, text/image helpers that still call Skiko JNI, or non-JBR runtimes. A future JBR-only
distribution can omit the platform runtime only after the Compose Swing command recorder no longer needs Skiko JNI during
recording. Until then, publishing an empty or marker-only `skiko-awt-runtime-jbr-*` artifact would hide real fallback and
recording dependencies rather than removing them.

The JBR-owned native bridge is packaged with JBR as `libjbrskiainterop`. Skiko only negotiates the API/ABI gate and sends
versioned command buffers or descriptors to that bridge.

## Surface Changes

`JbrSkiaSwingLayer` tracks the JBR destination identity returned by the scoped canvas. When the destination surface changes, Skiko clears its own command-frame cache and reflectively calls:

```text
androidx.compose.ui.graphics.JbrSkiaCommandRecorder.clearInteropCachesForSurfaceChange()
```

The reflection keeps Skiko independent from Compose UI at compile time. When the call succeeds, Skiko logs:

```text
SKIKO_JBR_INTEROP_COMMAND_CACHES_CLEARED reason=surfaceChanged
```

or:

```text
SKIKO_JBR_INTEROP_COMMAND_CACHES_CLEARED reason=contextChanged
```

If the method is missing, Skiko logs `SKIKO_JBR_INTEROP_COMMAND_CACHES_CLEAR_UNAVAILABLE`, emits `SKIKO_JBR_INTEROP_FALLBACK reason=command-cache-clear-unavailable`, and falls back for that paint instead of risking stale descriptor handles. The Magic Jewel `commands-resize-descriptor-redefine` probe asserts the successful marker and verifies descriptors are redefined after resize.
