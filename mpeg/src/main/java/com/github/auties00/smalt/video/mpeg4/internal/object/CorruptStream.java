package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.io.Serial;

/**
 * Signals that the object decoder met data its syntax does not allow, where the reference software would stop; the
 * engine reports it as malformed data.
 */
final class CorruptStream extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    static final CorruptStream INSTANCE = new CorruptStream();

    private CorruptStream() {
        super("corrupt MPEG-4 video object data", null, false, false);
    }
}
