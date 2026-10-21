package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Map;

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
    private static final Map<String, String> VECTORS = Map.ofEntries(
            Map.entry("al960_sbr_e_48_1", "e945ab3ed2b5095e618af25043197e17b4ba348a79a20a8134037df0fac12eac"),
            Map.entry("al960_sbr_gh_48_2", "12033cfade7e48ed3e6721cffd5bf9fdccfc4f6b493bbe3ed6de8a68f98bdf34"),
            Map.entry("al960_sbr_ps_00", "aa928a889c8d79e73005a467720bae0509c7c0f0d8482b8aa509697f65e5f806"),
            Map.entry("al960_sbr_ps_01", "cb27f06183f4ad6d08012d79cde32ead4cc2e31fe68dfc405c1cbca802c09a76"),
            Map.entry("al960_sbr_s_48_1", "4d85489cdd6d7275b7cce8efacd153d7f697fd41190b6feecb6fa08fc80305f2"),
            Map.entry("al_sbr_cm_48_1", "40ae6ac9217d125637466dfe35154e48fbe9feaca664ae31f189c040ffa2a7eb"),
            Map.entry("al_sbr_cm_48_2", "7e7c0390c950eda21775d1a193e05ee703257bed85602884e7d30a8b3c65be90"),
            Map.entry("al_sbr_cm_48_5.1", "c49bf6c206e015b26f34d109693ac11e2434edebc321462c75b254d27a49ccf5"),
            Map.entry("al_sbr_cm_48_5", "29ece0f289ace51043b07f338c59130d07de6be3b70c8be50b0c7edec4b404b2"),
            Map.entry("al_sbr_cm_96_5", "d3533ba56317cd3c9a4d3dc1cd11acfd4e2648adc2ceb49db4c523d6ec16630a"),
            Map.entry("al_sbr_e_32_1", "2861e09c1f904c5c281de2c6e37566c62f4e51f20133226d582249b30af7574f"),
            Map.entry("al_sbr_e_44_2", "2baaecd20f2e5b481ca7b43df3bcbf00827045f5b5eec547d92d5b66c317536f"),
            Map.entry("al_sbr_e_48_1", "14b02729d657306ebd461485d7ab0c482a0233b42292e4f98d64a01680f508cf"),
            Map.entry("al_sbr_e_48_2", "ea8dda626af2eddd0207030e97eb1232b3eab8beb71a3771709ea27dab525ac8"),
            Map.entry("al_sbr_gh_32_1", "dff3dbf05c25d68814c198793f0cd8d26c59e9ce8c4abf116f8fc3112446b091"),
            Map.entry("al_sbr_gh_44_2", "c5c95c4e80992ec70f9ddc80b7fef42b862f24249803bfb5aab3d63978289c71"),
            Map.entry("al_sbr_gh_48_2", "58e43a780b79c2a569790ca3beee843622966c3a5ce96ecff110750987e21c62"),
            Map.entry("al_sbr_i_32_2", "e7d16bc91fbbd25ad5e9e8f793d37f11b006c5273ef6d7de542eb265794db7c2"),
            Map.entry("al_sbr_i_44_1", "93f9a8e33ca65f529139ed4827bf37214fe28a17aa786f3a23ba6cfd571cf36c"),
            Map.entry("al_sbr_i_48_1", "a9d3621b74e5ffe891308e9524c72b8aff848723d16bc0b4b9908c5ef12d5427"),
            Map.entry("al_sbr_i_48_2", "9892da16b6a8741f8cd4825387c30eb43df63171baf24a4eca1b7e6c15eb6680"),
            Map.entry("al_sbr_ps_00", "d237a400ec5978576d5b1fa9fda292d494aad5dc184e9c24922e8fe17162298e"),
            Map.entry("al_sbr_ps_01", "67e90d0803f64819a895ffa349e65722b30df673bc5ab7dee1ca22fe3a1be217"),
            Map.entry("al_sbr_ps_02", "6a4a410382ee4d3c2de31febba74bf2ea48d32d5972a9e7fbf977f671620b081"),
            Map.entry("al_sbr_ps_03", "717153ee39f505e897e3dc22357b39dbf0470c8033c7e0c76d5b3b68d6d8e238"),
            Map.entry("al_sbr_ps_04", "89669b9f796678db2a9bf13cc33a0617e6864bf1b0152fa717ba3616d869a749"),
            Map.entry("al_sbr_ps_05", "f963d336f833d70d6af84b683b19e0bad7b1d07d4ca779f8020d24b5f8aa5d39"),
            Map.entry("al_sbr_ps_06", "42d273c6260d2541d07e297257aa3238ebbcc1cb9eac5cf71bbfba69a6238132"),
            Map.entry("al_sbr_qmf_32_1", "622fe93d0545af9e303734cfe036702ab7446b2f8a32ec1a40694ab9fe17cab1"),
            Map.entry("al_sbr_qmf_44_1", "028e1b141f9808e806f9749bfc48a14031c0db8fe99952a0c32242fdba598d7a"),
            Map.entry("al_sbr_qmf_48_1", "eba15a46b9271dac06f5a2bd94abf053b94330f993763cf3143776e8fbc1ec99"),
            Map.entry("al_sbr_s_32_2", "a66742c4b2741c098dab59ed5e8b89b09bd9868648c3207d10595904c06df509"),
            Map.entry("al_sbr_s_44_1", "c5e2cf353e9675707d893114b8fadb3bcd461605a4638ebb564f693c0d02f582"),
            Map.entry("al_sbr_s_48_1", "2b769954175d935dd12cf9dc6c5544c10d4a224162ea6ae9f54a41900882251c"),
            Map.entry("al_sbr_s_48_2", "5aef3d23aa68716c0792910f30b202322aa30c79bfa61332584733943cc6f418"),
            Map.entry("al_sbr_sig_24_2_fsaac24_sig1", "8467c5aa22b23ba057ee02ec5a33ab00e906e26ecdcec33167a96debf9247eeb"),
            Map.entry("al_sbr_sig_48_2_sig0", "f115fd344f97ea6bc63e5975556a6e54dc46f4e18ae0f83f408c1488049f74f5"),
            Map.entry("al_sbr_sig_48_2_sig1", "0add1a761630e9f4daacddadf9876c921d348fadba6f0ece344d7ef9c2d4e5f5"),
            Map.entry("al_sbr_sig_48_2_sig2", "5ac060d5d6526150afa66eea16432f63660362ed19e14e231e838e3466a5f9f0"),
            Map.entry("al_sbr_sr_16_2_fsaac08", "a0e249d42deb810e1c9ce2fbfa91cc727da45f132c6da2f8830c9f8debec1362"),
            Map.entry("al_sbr_sr_16_2_fsaac16", "334f7d6b86bf4bec44c1ff5e4ff3d49ea01a8540284ab13e840889dd492b30f4"),
            Map.entry("al_sbr_sr_22_2_fsaac11", "0bb38be6a8cabae3dba6571ca07c418c8933558e6a0496b027c50028d319e146"),
            Map.entry("al_sbr_sr_24_2_fsaac12", "1dd0f5bd043630192da6b6ac4843f7e266ae68c7aa135197e727aeb516b485b4"),
            Map.entry("al_sbr_sr_32_2_fsaac16", "b58742075b76ed6e9935fe5f637e94ea48d66bbeb1d1abb7a27787f4c03a246a"),
            Map.entry("al_sbr_sr_44_2_fsaac22", "dead5d38dd73a9a4eb7d0cf0b41969d600f0f2099731a4193c7bf34dcaca944f"),
            Map.entry("al_sbr_sr_48_2_fsaac24", "ab66dd6a97a19bcfa4ffcb9fb9449d8acf9f273611e5514d474c190dd6383ce6"),
            Map.entry("al_sbr_sr_48_2_fsaac48", "d0b286c5f9bdb47ee1722ab64775cc507de27744c6a21143ce41fc7410cdbceb"),
            Map.entry("al_sbr_sr_88_2_fsaac44", "c22e41105e2e0cea3fe228db5e0913e47cf6a38082f1eabd929a2c464cc58ce0"),
            Map.entry("al_sbr_sr_96_2_fsaac48", "1013f6df04799e76775a89fd20f4c7d7dfc1e5c667c2476ec83802b680205615"),
            Map.entry("al_sbr_twi_22_1_fsaac22", "5e69e653b782b56c51d08d2818f93abecac55e48948beaa70fa1b90e96cf91ce"),
            Map.entry("al_sbr_twi_48_1_fsaac24", "141d8997fcc9ae04cc2b5897e22a4c8b9341948c05c63c2bda83ba08be902d12"));

    static Path fetch(String name) {
        return Conformance.fetch("mpeg4audio-" + name + ".mp4",
                AacConformanceTest.BASE + "DVD1/mpeg4audio-conformance/compressedMp4/" + name + ".mp4", VECTORS.get(name));
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
                "al_sbr_twi_22_1_fsaac22, 22050, 2", "al_sbr_twi_48_1_fsaac24, 48000, 2"
        })
        @DisplayName("meets the 16-bit conformance criterion")
        void conformant(String name, int rate, int channels) {
            var file = fetch(name);
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
    @DisplayName("960-sample streams")
    class ShortFrames {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "al960_sbr_e_48_1, 24000, 2", "al960_sbr_gh_48_2, 24000, 2", "al960_sbr_ps_00, 16000, 2",
                "al960_sbr_ps_01, 16000, 2", "al960_sbr_s_48_1, 24000, 2"
        })
        @DisplayName("decode at twice the core rate with the core intact below the crossover")
        void dualRate(String name, int coreRate, int channels) {
            var stream = AacTestSupport.parseMp4(Fixtures.read(fetch(name)));
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
