# JBR Skia Interop Notes

This worktree contains an experimental Swing path where Skiko renders Compose command frames into a JBR-owned Skia/Metal destination.

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

If the method is missing, Skiko logs `SKIKO_JBR_INTEROP_COMMAND_CACHES_CLEAR_UNAVAILABLE` and continues. The Magic Jewel `commands-resize-descriptor-redefine` probe asserts the successful marker and verifies descriptors are redefined after resize.
