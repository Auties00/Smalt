package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;

/**
 * The tuning of an {@link OpusEncoder}, which biases its mode and bandwidth decisions.
 *
 * <p>The application is fixed when the encoder is created.
 */
public enum OpusApplication {
    /**
     * Speech tuning, which favors SILK and high-pass filters the input.
     */
    VOIP(OpusDefines.OPUS_APPLICATION_VOIP),

    /**
     * General audio tuning, which favors fidelity to the input.
     */
    AUDIO(OpusDefines.OPUS_APPLICATION_AUDIO),

    /**
     * CELT-only coding, for the lowest algorithmic delay.
     */
    RESTRICTED_LOWDELAY(OpusDefines.OPUS_APPLICATION_RESTRICTED_LOWDELAY);

    private final int code;

    OpusApplication(int code) {
        this.code = code;
    }

    /**
     * {@return the {@code OPUS_APPLICATION_*} code of this application}
     */
    int code() {
        return code;
    }
}
