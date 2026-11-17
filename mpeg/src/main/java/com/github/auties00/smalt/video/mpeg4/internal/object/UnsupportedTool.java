package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.io.Serial;

/**
 * Signals that a layer uses a tool the object decoder does not implement; the engine reports it as unsupported.
 */
final class UnsupportedTool extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    UnsupportedTool(String feature) {
        super(feature, null, false, false);
    }
}
