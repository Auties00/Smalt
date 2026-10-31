package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;

/**
 * Fuzz targets of the PCM and DSD decoders: options bits 1 to 7 choose the sample format (see {@link FuzzMedia#audio}).
 */
class PcmFuzzTest {
    /** The PCM codecs, chosen by options bits 1 to 7 modulo their count. */
    private static final Codec[] CODECS = Arrays.stream(Codec.values()).filter(Codec::isPcm).toArray(Codec[]::new);

    /** The DSD codecs, chosen by options bits 1 to 7 modulo their count. */
    private static final Codec[] DSD = Arrays.stream(Codec.values()).filter(Codec::isDsd).toArray(Codec[]::new);

    @FuzzTest
    void pcm(byte[] data) {
        var options = FuzzPackets.parse(data, FuzzPackets.AUDIO_FIELDS).header().options();
        FuzzMedia.audio(CODECS[(options >>> 1) % CODECS.length], data);
    }

    @FuzzTest
    void dsd(byte[] data) {
        var options = FuzzPackets.parse(data, FuzzPackets.AUDIO_FIELDS).header().options();
        FuzzMedia.audio(DSD[(options >>> 1) % DSD.length], data);
    }
}
