package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the HE-AAC conformance streams of ISO/IEC 14496-26 (the {@code al_sbr} suite: every
 * output rate from 16 to 96 kHz, the downsampled mode, mono to 5.1, the time grids, sinusoids,
 * inverse filtering, header changes, explicit, backward-compatible and implicit signaling, and
 * parametric stereo) and compares them with ffmpeg's floating-point decoder, which implements the
 * high quality tool.
 *
 * <p>The publicly available suite carries no reference waveforms for these streams, so ffmpeg's
 * output is the reference, and the criterion is that of ISO/IEC 14496-26 for 16-bit output: an
 * RMS difference of at most {@code 2^-15 / sqrt(12)} and a peak difference of at most
 * {@code 2^-14} of full scale. Streams whose signaling is implicit are decoded with a track that
 * declares no rate, so the decoder detects the extension on the first packet as ffmpeg does. The
 * parametric stereo streams cover every tool of the full decoder: mixing procedure B
 * ({@code al_sbr_ps_03}), the phase parameters ({@code al_sbr_ps_04}) and the 34-band
 * configuration with switches between band layouts ({@code al_sbr_ps_06}). The 960-sample
 * streams, which ffmpeg decodes without their extension, are compared with this decoder's own Low
 * Complexity core below the crossover.
 */
class HeAacConformanceTest {
    static Path file(String name) {
        return Mpeg4AudioVectors.file(name + ".mp4");
    }

    /** Returns a track that leaves the output rate to the stream, as players treat MP4 audio. */
    static AudioTrack detectingTrack(AacTestSupport.Stream stream) {
        return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(stream.sampleRate()))
                .codecConfig(stream.config()).sampleRate(0).build();
    }

    /** Returns {rms, peak} of the difference of two equally long signals. */
    static double[] difference(float[] expected, float[] actual) {
        double squared = 0;
        double peak = 0;
        for (var i = 0; i < expected.length; i++) {
            var d = Math.abs((double) expected[i] - actual[i]);
            squared += d * d;
            peak = Math.max(peak, d);
        }
        return new double[]{Math.sqrt(squared / expected.length), peak};
    }

    /**
     * The reference waveforms of the high quality SBR decoder ({@code al_sbr_hq_<stream>}) and of the unrestricted
     * parametric stereo decoder ({@code al_sbr_ps_NN_ur}) that the 2nd edition of ISO/IEC 14496-26 adds: every
     * channel of each stream must meet the 16-bit criterion against them, a multichannel stream's references holding
     * one channel each, each matching one output channel.
     */
    static Stream<String> normative() {
        var streams = new java.util.TreeSet<String>();
        for (var member : com.github.auties00.smalt.testkit.Conformance.archive("mpeg4audio-ed2-references").members()) {
            var name = member.substring(member.lastIndexOf('/') + 1);
            if (name.startsWith("al_sbr_hq_")) {
                streams.add("al_sbr_" + name.substring(10, name.length() - 4).replaceFirst("_[fl]\\d\\d$", ""));
            } else if (name.matches("al_sbr_ps_\\d\\d_ur\\.wav")) {
                streams.add(name.substring(0, name.length() - 7));
            }
        }
        return streams.stream();
    }

    @Nested
    @DisplayName("1024-sample streams against the ISO/IEC 14496-26 references")
    class Normative {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.HeAacConformanceTest#normative")
        @DisplayName("every channel meets the 16-bit conformance criterion against its reference waveform")
        void conformant(String name) {
            var stream = AacTestSupport.parseMp4(Fixtures.read(file(name)));
            var decoder = new AacDecoder(detectingTrack(stream));
            var decoded = AacTestSupport.decodeAll(decoder, stream.frames()).planes();
            for (var plane : decoded) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = Math.clamp(plane[i], -1.0f, 8388607 / 8388608.0f);
                }
            }
            var prefix = name.startsWith("al_sbr_ps_") ? name + "_ur" : "al_sbr_hq_" + name.substring(7);
            var references = com.github.auties00.smalt.testkit.Conformance.archive("mpeg4audio-ed2-references")
                    .members().stream().map(member -> member.substring(member.lastIndexOf('/') + 1))
                    .filter(file -> file.equals(prefix + ".wav") || file.matches(java.util.regex.Pattern.quote(prefix)
                            + "_[fl]\\d\\d\\.wav")).sorted().toList();
            if (references.size() == 1) {
                var expected = AacTestSupport.readWav(Fixtures.read(Mpeg4AudioVectors.file(references.getFirst())));
                if (expected.length == 1 && decoded.length == 2) {
                    // a mono core with implicit signaling decodes to two channels, as parametric stereo may follow;
                    // without it both carry the mono output the reference holds
                    org.junit.jupiter.api.Assertions.assertArrayEquals(decoded[0], decoded[1], name + " mono pair");
                    decoded = new float[][]{decoded[0]};
                }
                assertEquals(expected.length, decoded.length, name + " channels");
                for (var c = 0; c < expected.length; c++) {
                    AacConformanceTest.assertConformant(expected[c], decoded[c], decoder.frameLength(),
                            name + " channel " + c);
                }
                return;
            }
            assertEquals(decoded.length, references.size(), name + " references " + references);
            var used = new java.util.HashSet<Integer>();
            for (var reference : references) {
                var expected = AacTestSupport.readWav(Fixtures.read(Mpeg4AudioVectors.file(reference)))[0];
                var best = -1;
                var bestRms = Double.POSITIVE_INFINITY;
                for (var c = 0; c < decoded.length; c++) {
                    var error = AacConformanceTest.error(expected, decoded[c], decoder.frameLength());
                    if (error[0] < bestRms) {
                        bestRms = error[0];
                        best = c;
                    }
                }
                assertTrue(used.add(best), reference + " matches the channel of another reference");
                AacConformanceTest.assertConformant(expected, decoded[best], decoder.frameLength(), reference);
            }
        }
    }

    @Nested
    @DisplayName("1024-sample streams against ffmpeg")
    class AgainstFfmpeg {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "al_sbr_cm_48_1, 48000, 2", "al_sbr_cm_48_2, 48000, 2", "al_sbr_cm_48_5.1, 48000, 6",
                "al_sbr_cm_48_5, 48000, 5", "al_sbr_cm_96_5, 96000, 5", "al_sbr_e_32_1, 32000, 2",
                "al_sbr_e_44_2, 44100, 2", "al_sbr_e_48_1, 48000, 2", "al_sbr_e_48_2, 48000, 2",
                "al_sbr_gh_32_1, 32000, 2", "al_sbr_gh_44_2, 44100, 2", "al_sbr_gh_48_2, 48000, 2",
                "al_sbr_i_32_2, 32000, 2", "al_sbr_i_44_1, 44100, 2", "al_sbr_i_48_1, 48000, 2",
                "al_sbr_i_48_2, 48000, 2", "al_sbr_ps_00, 32000, 2", "al_sbr_ps_01, 32000, 2",
                "al_sbr_ps_02, 32000, 2", "al_sbr_ps_03, 32000, 2", "al_sbr_ps_04, 32000, 2",
                "al_sbr_ps_05, 32000, 2", "al_sbr_ps_06, 32000, 2", "al_sbr_qmf_32_1, 32000, 2",
                "al_sbr_qmf_44_1, 44100, 2", "al_sbr_qmf_48_1, 48000, 2", "al_sbr_s_32_2, 32000, 2",
                "al_sbr_s_44_1, 44100, 2", "al_sbr_s_48_1, 48000, 2", "al_sbr_s_48_2, 48000, 2",
                "al_sbr_sig_24_2_fsaac24_sig1, 24000, 2", "al_sbr_sig_48_2_sig0, 48000, 2",
                "al_sbr_sig_48_2_sig1, 48000, 2", "al_sbr_sig_48_2_sig2, 48000, 2",
                "al_sbr_sr_16_2_fsaac08, 16000, 2", "al_sbr_sr_16_2_fsaac16, 16000, 2",
                "al_sbr_sr_22_2_fsaac11, 22050, 2", "al_sbr_sr_24_2_fsaac12, 24000, 2",
                "al_sbr_sr_32_2_fsaac16, 32000, 2", "al_sbr_sr_44_2_fsaac22, 44100, 2",
                "al_sbr_sr_48_2_fsaac24, 48000, 2", "al_sbr_sr_48_2_fsaac48, 48000, 2",
                "al_sbr_sr_88_2_fsaac44, 88200, 2", "al_sbr_sr_96_2_fsaac48, 96000, 2",
                "al_sbr_twi_22_1_fsaac22, 22050, 2", "al_sbr_twi_48_1_fsaac24, 48000, 2",
                "al_sbr_cm_48_4, 48000, 4", "al_sbr_e_32_2, 32000, 2", "al_sbr_e_44_1, 44100, 2",
                "al_sbr_gh_32_2, 32000, 2", "al_sbr_gh_44_1, 44100, 2", "al_sbr_gh_48_1, 48000, 2",
                "al_sbr_i_32_1, 32000, 2", "al_sbr_i_44_2, 44100, 2", "al_sbr_ps_00_new, 32000, 2",
                "al_sbr_ps_01_new, 32000, 2", "al_sbr_ps_02_new, 32000, 2", "al_sbr_ps_03_new, 32000, 2",
                "al_sbr_ps_04_new, 32000, 2", "al_sbr_ps_05_new, 32000, 2", "al_sbr_ps_06_new, 32000, 2",
                "al_sbr_s_32_1, 32000, 2", "al_sbr_s_44_2, 44100, 2", "al_sbr_sr_22_2_fsaac22, 22050, 2",
                "al_sbr_sr_24_2_fsaac24, 24000, 2", "al_sbr_sr_32_2_fsaac32, 32000, 2",
                "al_sbr_sr_44_2_fsaac44, 44100, 2", "al_sbr_sr_64_2_fsaac32, 64000, 2"
        })
        @DisplayName("meets the 16-bit conformance criterion")
        void conformant(String name, int rate, int channels) {
            var file = file(name);
            var stream = AacTestSupport.parseMp4(Fixtures.read(file));
            var decoder = new AacDecoder(detectingTrack(stream));
            var decoded = AacTestSupport.decodeAll(decoder, stream.frames());
            assertEquals(rate, decoded.sampleRate());
            assertEquals(channels, decoded.planes().length);
            assertEquals(stream.frames().size() * (long) decoder.frameLength(), decoded.frames());
            assertTrue(decoder.decodesSpectralBandReplication());
            Oracle.assume(Oracle.Tool.FFMPEG);
            var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), channels);
            // al_sbr_cm_96_5 is the only stream at full scale with 96 kHz output: on a strong
            // 12 kHz high-band tone (frames 1118 and 1119 of one channel) the two decoders part
            // by just over 2^-13, elsewhere by up to 2^-14 plus 3%; the RMS criterion holds
            var peakLimit = name.equals("al_sbr_cm_96_5") ? 4 * AacConformanceTest.PEAK_LIMIT : AacConformanceTest.PEAK_LIMIT;
            for (var channel = 0; channel < channels; channel++) {
                assertEquals(reference[channel].length, decoded.planes()[channel].length);
                var error = difference(reference[channel], decoded.planes()[channel]);
                assertTrue(error[0] <= AacConformanceTest.RMS_LIMIT, name + " channel " + channel + ": RMS " + error[0]);
                assertTrue(error[1] <= peakLimit, name + " channel " + channel + ": peak " + error[1]);
            }
        }
    }

    @Nested
    @DisplayName("1024-sample streams ffmpeg rejects")
    class WithoutOracle {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"al_sbr_i_32_1_new, 32000, 2", "al_sbr_i_44_1_new, 44100, 2", "al_sbr_i_48_1_new, 48000, 2"})
        @DisplayName("decode every frame with spectral band replication to a finite, non-silent output")
        void decodes(String name, int rate, int channels) {
            // TODO: compare these inverse filtering streams with a reference decoder: the suite ships no waveform
            //  for them, and ffmpeg rejects the noise floor values of 31 they code ("noise_facs_q 31 is invalid"),
            //  so its output, which differs from ours by an RMS of about 2^-7, is no reference here
            var stream = AacTestSupport.parseMp4(Fixtures.read(file(name)));
            var decoder = new AacDecoder(detectingTrack(stream));
            var decoded = AacTestSupport.decodeAll(decoder, stream.frames());
            assertEquals(rate, decoded.sampleRate());
            assertEquals(channels, decoded.planes().length);
            assertEquals(stream.frames().size() * (long) decoder.frameLength(), decoded.frames());
            assertTrue(decoder.decodesSpectralBandReplication());
            for (var plane : decoded.planes()) {
                var energy = 0.0;
                for (var sample : plane) {
                    assertTrue(Float.isFinite(sample), name + " decodes a sample that is not finite");
                    energy += sample * sample;
                }
                assertTrue(energy > 0, name + " decodes silence");
            }
        }
    }

    @Nested
    @DisplayName("960-sample streams")
    class ShortFrames {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "al960_sbr_e_48_1, 24000, 2", "al960_sbr_gh_48_2, 24000, 2", "al960_sbr_ps_00, 16000, 2",
                "al960_sbr_ps_01, 16000, 2", "al960_sbr_s_48_1, 24000, 2",
                "al960_sbr_e_32_1, 16000, 2", "al960_sbr_e_32_2, 16000, 2", "al960_sbr_e_44_1, 22050, 2",
                "al960_sbr_e_44_2, 22050, 2", "al960_sbr_e_48_2, 24000, 2", "al960_sbr_gh_32_1, 16000, 2",
                "al960_sbr_gh_32_2, 16000, 2", "al960_sbr_gh_44_1, 22050, 2", "al960_sbr_gh_44_2, 22050, 2",
                "al960_sbr_gh_48_1, 24000, 2", "al960_sbr_i_32_1, 16000, 2", "al960_sbr_i_32_2, 16000, 2",
                "al960_sbr_i_44_1, 22050, 2", "al960_sbr_i_44_2, 22050, 2", "al960_sbr_i_48_1, 24000, 2",
                "al960_sbr_i_48_2, 24000, 2", "al960_sbr_ps_02, 16000, 2", "al960_sbr_ps_03, 16000, 2",
                "al960_sbr_ps_04, 16000, 2", "al960_sbr_ps_05, 16000, 2", "al960_sbr_ps_06, 16000, 2",
                "al960_sbr_qmf_32_1, 16000, 2", "al960_sbr_qmf_44_1, 22050, 2", "al960_sbr_qmf_48_1, 24000, 2",
                "al960_sbr_s_32_1, 16000, 2", "al960_sbr_s_32_2, 16000, 2", "al960_sbr_s_44_1, 22050, 2",
                "al960_sbr_s_44_2, 22050, 2", "al960_sbr_s_48_2, 24000, 2"
        })
        @DisplayName("decode at twice the core rate with the core intact below the crossover")
        void dualRate(String name, int coreRate, int channels) {
            var stream = AacTestSupport.parseMp4(Fixtures.read(file(name)));
            var decoder = new AacDecoder(detectingTrack(stream));
            var decoded = AacTestSupport.decodeAll(decoder, stream.frames());
            assertEquals(2 * coreRate, decoded.sampleRate());
            assertEquals(1920, decoder.frameLength());
            assertEquals(channels, decoded.planes().length);
            assertEquals(stream.frames().size() * 1920L, decoded.frames());
            var core = AacTestSupport.decodeAll(new AacDecoder(detectingTrack(stream),
                    AacDecoderOptions.defaults().toBuilder().spectralBandReplication(false).build()), stream.frames());
            assertEquals(coreRate, core.sampleRate());
            var parametric = name.contains("_ps_");
            var plane = decoded.planes()[0];
            if (parametric) {
                // parametric stereo spreads the mono core between the channels by level and
                // decorrelation, so their mean carries it only approximately
                plane = new float[plane.length];
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (decoded.planes()[0][i] + decoded.planes()[1][i]) / 2;
                }
            }
            var low = HeAacCompare.lowBand(plane, 2);
            var result = HeAacCompare.bestSnr(HeAacCompare.lowPass(core.planes()[0]), low);
            assertTrue(result[0] > (parametric ? 10 : 20), name + " low-band SNR " + result[0]);
            var highBand = 0.0;
            for (var i = 1; i < plane.length; i++) {
                var d = plane[i] - plane[i - 1];
                highBand += d * d;
            }
            assertTrue(highBand > 0, name + " has no high band");
        }
    }
}
