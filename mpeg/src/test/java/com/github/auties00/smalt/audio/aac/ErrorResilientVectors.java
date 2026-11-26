package com.github.auties00.smalt.audio.aac;

import java.nio.file.Path;

/**
 * The ISO/IEC 14496-26 conformance streams and reference waveforms of ER AAC LC ({@code er_al}), ER AAC LTP
 * with the error resilience tools ({@code er_ap14}, {@code er_ap27}), AAC-LD ({@code er_ad}) and AAC-ELD
 * ({@code er_eld}, {@code er_eld_sbr}), read through {@link Mpeg4AudioVectors}.
 */
final class ErrorResilientVectors {
    private ErrorResilientVectors() {
    }

    /** Returns a stream ({@code .mp4}) or reference waveform ({@code .wav}) of the hosted test data. */
    static Path file(String name) {
        return Mpeg4AudioVectors.file(name);
    }
}
