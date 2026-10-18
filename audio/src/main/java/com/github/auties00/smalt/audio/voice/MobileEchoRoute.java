package com.github.auties00.smalt.audio.voice;

/**
 * Describes where the sound comes out of the device, which tunes the suppression of the mobile
 * echo canceller ({@link EchoCancellation#MOBILE}): the louder the loudspeaker relative to the
 * microphone, the more echo it expects.
 */
public enum MobileEchoRoute {
    /**
     * A quiet earpiece or a headset, the least echo.
     */
    QUIET_EARPIECE_OR_HEADSET(0),

    /**
     * The earpiece held to the ear.
     */
    EARPIECE(1),

    /**
     * A loud earpiece.
     */
    LOUD_EARPIECE(2),

    /**
     * The loudspeaker, the default.
     */
    SPEAKERPHONE(3),

    /**
     * A loud loudspeaker, the most echo.
     */
    LOUD_SPEAKERPHONE(4);

    /**
     * The echo mode of the canceller.
     */
    private final int echoMode;

    /**
     * Creates a constant.
     *
     * @param echoMode the echo mode of the canceller
     */
    MobileEchoRoute(int echoMode) {
        this.echoMode = echoMode;
    }

    /**
     * Returns the echo mode of the canceller.
     *
     * @return the echo mode, in {@code [0, 4]}
     */
    int echoMode() {
        return echoMode;
    }
}
