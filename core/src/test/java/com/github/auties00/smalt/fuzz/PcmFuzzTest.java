package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;

/**
 * Fuzz targets of the PCM and DSD decoders and of DSD over PCM: options bits 1 to 7 choose the sample format (see
 * {@link FuzzMedia#audio}).
 */
class PcmFuzzTest {
    /** The PCM codecs, chosen by options bits 1 to 7 modulo their count. */
    private static final Codec[] CODECS = Arrays.stream(Codec.values()).filter(Codec::isPcm).toArray(Codec[]::new);

    /** The DSD codecs, chosen by options bits 1 to 7 modulo their count. */
    private static final Codec[] DSD = Arrays.stream(Codec.values()).filter(Codec::isDsd).toArray(Codec[]::new);

    /** The 24 and 32-bit integer PCM codecs that can carry DSD over PCM, chosen by options bits 1 to 7. */
    static final Codec[] DOP = {Codec.PCM_S24LE, Codec.PCM_S24BE, Codec.PCM_S32LE, Codec.PCM_S32BE};

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

    @FuzzTest
    void dop(byte[] data) {
        var options = FuzzPackets.parse(data, FuzzPackets.AUDIO_FIELDS).header().options();
        FuzzMedia.audio(DOP[(options >>> 1) % DOP.length], data, true);
    }
}
