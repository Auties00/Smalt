package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared access to the ISO/IEC 11172-4 and 13818-4 compliance bitstreams (and the extra
 * robustness streams) of the minimp3 repository (commit ea99364f61c14656440e8d77e9c233ccf3124633), the
 * {@code minimp3-<name>} vectors of the hosted test data.
 */
final class Mp3Vectors {
    /** The reference decodes the hosted data keeps once, by the name of each byte-identical copy. */
    private static final Map<String, String> ALIASES = Map.of(
            "ILL2_dual.pcm", "ILL2_center2.pcm",
            "ILL2_samples.pcm", "ILL2_center2.pcm",
            "ILL2_scf63.pcm", "ILL2_center2.pcm",
            "ILL2_wrongcrc.pcm", "ILL2_center2.pcm",
            "ILL4_ext_id1.pcm", "ILL2_center2.pcm",
            "ILL2_tca30.pcm", "ILL2_tca21.pcm",
            "l3-he_48khz.pcm", "l3-he_32khz.pcm",
            "l3-nonstandard-id3v1.pcm", "l3-nonstandard-apetag.pcm",
            "l3-nonstandard-id3v1-apetag.pcm", "l3-nonstandard-apetag.pcm",
            "l3-nonstandard-id3v2.pcm", "l3-nonstandard-apetag.pcm");

    private Mp3Vectors() {
        throw new AssertionError();
    }

    /** Returns the hosted copy of a minimp3 vector, by its file name in the repository. */
    static Path vector(String name) {
        return Conformance.vector("minimp3-" + ALIASES.getOrDefault(name, name));
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** Decodes a whole stream through the demuxer and decoder, untrimmed. */
    static AudioPipeline.Decoded decode(byte[] data) {
        return decode(data, Mp3DecoderOptions.defaults());
    }

    /** Decodes a whole stream through the demuxer and a decoder with options, untrimmed. */
    static AudioPipeline.Decoded decode(byte[] data, Mp3DecoderOptions options) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, track -> new Mp3Decoder(track, options));
        }
    }

    /** Converts float samples to 16-bit with round-half-even and saturation, like minimp3 on x86. */
    static short[] toS16(float[] samples) {
        var out = new short[samples.length];
        for (var i = 0; i < samples.length; i++) {
            var value = Math.rint(samples[i] * 32768.0);
            out[i] = (short) Math.max(-32768, Math.min(32767, value));
        }
        return out;
    }

    static short[] readPcm(byte[] data) {
        var out = new short[data.length / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    /** Returns minimp3's PSNR over the common length (peak 0x7fff). */
    static double psnr(short[] expected, short[] actual) {
        var length = Math.min(expected.length, actual.length);
        double mse = 0;
        for (var i = 0; i < length; i++) {
            double difference = expected[i] - actual[i];
            mse += difference * difference;
        }
        mse /= Math.max(1, length);
        return mse == 0 ? 99.0 : 10 * Math.log10((double) 0x7fff * 0x7fff / mse);
    }

    static int maxDifference(short[] expected, short[] actual) {
        var length = Math.min(expected.length, actual.length);
        var max = 0;
        for (var i = 0; i < length; i++) {
            max = Math.max(max, Math.abs(expected[i] - actual[i]));
        }
        return max;
    }
}
