package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;

/**
 * A hint of the content an {@link OpusEncoder} codes.
 */
public enum OpusSignal {
    /**
     * No hint: the encoder's speech/music classifier decides, the default.
     */
    AUTO(OpusDefines.OPUS_AUTO),

    /**
     * Speech input.
     */
    VOICE(OpusDefines.OPUS_SIGNAL_VOICE),

    /**
     * Music input.
     */
    MUSIC(OpusDefines.OPUS_SIGNAL_MUSIC);

    private final int code;

    OpusSignal(int code) {
        this.code = code;
    }

    /**
     * {@return the code of this hint, {@code OPUS_AUTO} or an {@code OPUS_SIGNAL_*} code}
     */
    int code() {
        return code;
    }
}
