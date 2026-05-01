# JBR Skia Interop Notes

This worktree contains an experimental Swing path where Skiko renders Compose command frames into a JBR-owned Skia/Metal destination.

## Compatibility Gate

Skiko reflectively reads the public JBR API `ABI_ID`/`BUILD_ID`, then acquires `com.jetbrains.JBR.getJBRSkia()`.
The current command stream gate is ABI 91 and requires JBR's high capability bit for RuntimeEffect color-filter
descriptors. If either the ABI/build id or required capability bits do not match, Skiko emits
`SKIKO_JBR_INTEROP_FALLBACK reason=abi-mismatch`, `native-abi-mismatch`, or `command-capability-mismatch` and uses the
old Swing path for that paint.

The ABI 91 RuntimeEffect color-filter path still does not pass Skiko-owned Skia objects to JBR. CMP serializes the SKSL,
source hash, uniform floats, and named uniform schema; JBR compiles the `SkRuntimeEffect` and `SkColorFilter` inside the
destination context.

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
