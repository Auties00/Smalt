package com.github.auties00.smalt.audio.voice;

/**
 * Selects the echo canceller of a {@link VoiceProcessor}, which removes from the capture signal
 * the echo of the render signal the local loudspeaker plays.
 */
public enum EchoCancellation {
    /**
     * Disables echo cancellation.
     */
    OFF,

    /**
     * Cancels the echo with WebRTC's AEC3: adaptive filters in the frequency domain, an
     * estimate of the render-to-capture delay of up to about 600 ms and a residual echo
     * suppressor. It suits every device and needs no delay report, and costs more than
     * {@link #MOBILE}.
     */
    STANDARD,

    /**
     * Cancels the echo with WebRTC's mobile echo canceller (AECM): a low-complexity
     * fixed-point canceller for constrained devices whose acoustic echo path is short. It
     * relies on the render-to-capture delay reported through
     * {@link VoiceProcessor#setStreamDelayMs(int)}, suppresses less echo than
     * {@link #STANDARD} and is tuned for the loudspeaker placement
     * {@link VoiceProcessorOptions#mobileEchoRoute()} names.
     */
    MOBILE
}
