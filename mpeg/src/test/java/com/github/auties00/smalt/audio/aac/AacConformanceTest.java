package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the AAC Low Complexity conformance streams of ISO/IEC 14496-26 (the {@code al*}
 * suite: every sampling rate, 1024- and 960-sample frames, mono to 48 channels, program
 * configurations, coupling channels, PNS; every family at every sampling rate the suite covers) and compares
 * them with the reference waveforms. The
 * HE-AAC streams of the suite are covered by {@link HeAacConformanceTest}.
 *
 * <p>The accuracy criterion is that of the standard for 16-bit output: an RMS error of at most
 * {@code 2^-15 / sqrt(12)} and a peak error of at most {@code 2^-14} of full scale. Reference
 * waveforms start either at the first decoded sample or after the priming frames, so every
 * comparison tries offsets of zero to two frames. The reference waveforms are integer PCM, so decoded
 * samples beyond full scale are clipped before the comparison, as a conforming PCM output clips them
 * (the {@code al14} streams at 64 to 96 kHz exceed full scale). The {@code al18} and {@code al19} streams
 * code their content with perceptual noise substitution, whose random noise no two decoders reproduce
 * sample by sample, so they are compared by short-term energy.
 */
class AacConformanceTest {
    static final String BASE = "https://standards.iso.org/ittf/PubliclyAvailableStandards/ISO_IEC_14496-26_2010_Bitstreams/";

    static final double RMS_LIMIT = Math.pow(2, -15) / Math.sqrt(12);

    static final double PEAK_LIMIT = Math.pow(2, -14);

    /** The largest sample of the 24-bit reference waveforms. */
    static final double FULL_SCALE = 1 - Math.pow(2, -23);

    private static final Map<String, String> VECTORS = Map.ofEntries(
            Map.entry("al00_08.mp4", "DVD1:6560ade6914c8daa492d9fb2836c889b568471124f658c312e1cd14ec1cd319d"),
            Map.entry("al00_11.mp4", "DVD1:d5fd584801002b7486f6876679121b7bf922808cf33dc62c39f7e3863b038d87"),
            Map.entry("al00_12.mp4", "DVD1:ccb7e37a577c94eba2219f4849f0485cc8e1eaa376d424cdbf6d799c2667563e"),
            Map.entry("al00_16.mp4", "DVD1:1dd72a0db41e058b22a1ac285c4d9106446081a779b11b05ef124382154e4c0d"),
            Map.entry("al00_22.mp4", "DVD1:6e25b27f3621799af8ce941c9c46d7efaf80340833a4da46b0fb114d769a9148"),
            Map.entry("al00_24.mp4", "DVD1:ecca693a6437b7cfc1a77f368922cdfdbf1098867205f19c64147705c109d737"),
            Map.entry("al00_32.mp4", "DVD1:9619e6a980f87e6e710a65675a7f5b59a5386a63b2d87c91fed840479c611623"),
            Map.entry("al00_44.mp4", "DVD1:27017a8c971a09502587b5bd1157277fcab20433b1f2fcd31fb35f21d533f372"),
            Map.entry("al00_48.mp4", "DVD1:bc4320b0c8ca7b7151b4df5ba160caaa725641d2e1feeed5cff56007d350767d"),
            Map.entry("al00_64.mp4", "DVD1:e010991b680aedb62ddacb515c06a138203be7b06e4e404021977aae77fe0826"),
            Map.entry("al00_88.mp4", "DVD1:5feeab5db1e06b56e5b8bb2c27286a1a0289b84bc63fbacf8430299098c8f3f1"),
            Map.entry("al00_96.mp4", "DVD1:2743806ca47e944ddf2e7e118c24fbfe81b1422f9410834603b78f40d156f6e6"),
            Map.entry("al00sf_48.mp4", "DVD1:daf6e300f29e89e7fcf4f0d5ee7e28f38c8d62a72739e027ff851cac1e8b8c46"),
            Map.entry("al01_48.mp4", "DVD1:7bb327dfa70e161e34c8391d046bf98c8c8dcf5ea04b98d7b95ec180fd75b56f"),
            Map.entry("al01sf_48.mp4", "DVD1:352bd1be59ad93994a5a356563e286323946a9aa73c015c7919eb14b89bf0d21"),
            Map.entry("al02_48.mp4", "DVD1:c2fd68f89af6576bc99af58430496b8233ed448fb071b4bfbd7ae0e378943707"),
            Map.entry("al03_48.mp4", "DVD1:475418d607eecf97690eda0cda2c0c6308a8ce5b3e6c52e19d8acbb05144f71c"),
            Map.entry("al04_48.mp4", "DVD1:fd2ac46d859b86a6235e38f56a06d6dece6327489321412017c3663f7f01e373"),
            Map.entry("al05_08.mp4", "DVD1:f6a92b23ad653c8054bbd19f4f406e4f9bb696353f53b050ecc415a4c5ff1dc2"),
            Map.entry("al05_22.mp4", "DVD1:fec323467f6cd1060eef89f8740f26d863f7bb9e5823b276a1e52d4fa4048694"),
            Map.entry("al05_48.mp4", "DVD1:b444769cf7ac019f655ce84017aa89037bf2e3e7c9b8e0922573808395898c12"),
            Map.entry("al05_96.mp4", "DVD1:a52fb913fdb8fabdafc464dcf28acdd5311c771beccf436043b7f456eac9d481"),
            Map.entry("al05sf_48.mp4", "DVD1:bf65422a091c99b6769bceda6518d051a3b12194b575c391bf9b6939d281bf8f"),
            Map.entry("al06_48.mp4", "DVD1:4474d9b2199eb972514a22186034e1fe3d273cab32d5c65f877e33c3b2df5397"),
            Map.entry("al06sf_48.mp4", "DVD1:1b6a2f8ca6c251f30e93789a52b0e7e657a31adedc31ed013d49cf727ccd25e2"),
            Map.entry("al07_48.mp4", "DVD1:735541674f11bb5b3f2107e67d77e1ad250298766b93b3daf6fdffaacba0528d"),
            Map.entry("al07sf_48.mp4", "DVD1:956503140cc59c9fc38e0f53825b7915acb298a00fe79032d1dc5d951d3d1909"),
            Map.entry("al08_48.mp4", "DVD1:b38fd5b6c2dbab0ade2cb1732410b0d0fe41a29173a77c243b4dfd714c502928"),
            Map.entry("al14_48.mp4", "DVD1:452047cdcdbdd89c0d4ecb913103bd2f74c6d441b4c6179b0b3d4393e602eb51"),
            Map.entry("al16_48.mp4", "DVD1:1204b5c5edb68390558ac236683fe90f4906050ed77aee243fe3555ae6d49b47"),
            Map.entry("al17_48.mp4", "DVD1:7a4f3632dbe36f041c6f5e52bc11563e6c5b1560ab143cba613be82ee8af74d7"),
            Map.entry("al17sf_48.mp4", "DVD1:bda0da82acc4afdf64431af6d998cf03d7d23e6753cf5137fc13a94637635137"),
            Map.entry("al00_08.wav", "DVD2:6a089479aefa80b4da891eef6d5bba7204c485a42b987ab97a8dedca8bd26370"),
            Map.entry("al00_11.wav", "DVD2:88a905b73ac478ace04612eda3375bc3dca789d41b2e831fa9f98580add434ec"),
            Map.entry("al00_12.wav", "DVD2:ec8d635f8fb398044753ca334c584e9b1d219623f6358782f8d1f89453df714f"),
            Map.entry("al00_16.wav", "DVD2:6f4fba0b4d08f143d5df77cc698e1617702acd54f80d4d2982fa065a6d56edf3"),
            Map.entry("al00_22.wav", "DVD2:dcf4b550bbd5add17f685ee6fe73a8cb40dc6137522e0976cb5a9de5a4825ea3"),
            Map.entry("al00_24.wav", "DVD2:5e76c1e779d04bd52567f4cc00e4b740efcfef75138ec55e4bba14b634f6b4e0"),
            Map.entry("al00_32.wav", "DVD2:dbcdd585e48d92f63e0ea1df2afa4a8a8d2378647e65c9d46dd4d7a731d7399f"),
            Map.entry("al00_44.wav", "DVD2:fa1eb1a8ac484d06797f1e30a18f9ff942efa117a6311e54f664aeaeb90e5cb0"),
            Map.entry("al00_48.wav", "DVD2:f8b5b4231249beb97f5881d4eea2bcdbc8ba2916c3a4324e340bf39b451e65d2"),
            Map.entry("al00_64.wav", "DVD2:a92cbd799b435bd501850f57f3d4a6a25706641cb065fcb1d77f0b667f7dd0fe"),
            Map.entry("al00_88.wav", "DVD2:c859e794828cdf8510a1025c0f9850a2efd3a92ff3fb21a32e51d075534c2997"),
            Map.entry("al00_96.wav", "DVD2:624018635c3ff28becc5dbbd876c8a044bbf8332bd46f3c6264724af349da95e"),
            Map.entry("al00sf_48.wav", "DVD2:239f878fe5b64126a1c6f8ae150c46f96cc81c91fb5a5b5be75a95e43e08ff73"),
            Map.entry("al01_48.wav", "DVD2:9aec8bd5f65265ca729cb6259223e5435aacb169c4af9230f1f7e03a7d0b7b4e"),
            Map.entry("al01sf_48.wav", "DVD2:993170621ee9992c2aed650e96ac888a266d41ca4773de9918954a013d1f4bee"),
            Map.entry("al02_48.wav", "DVD2:6ab7f0b8a36e4d81e23f09182f10d40b1533ef0cd4bf82e91da4295504cdc0aa"),
            Map.entry("al03_48.wav", "DVD2:dbf1248be11765eeaee4b13f0b3fc26d4873d277260fcf30fb5c10c97b93dd2e"),
            Map.entry("al04_48.wav", "DVD2:ab86ce0fdce80aa1d09e546e393c6d65b2b825da874b659c5721bfe87e92f43b"),
            Map.entry("al05_08.wav", "DVD2:b1673700281461dd2e52e959513c3f41c83ee0191d4c36dd76060a9fb36e2661"),
            Map.entry("al05_22.wav", "DVD2:5bbcee4059a0af580ce49b66821d4c2bcfd78d175fe7fe147a108bfa4017e620"),
            Map.entry("al05_48.wav", "DVD2:e3c8cfdef2d2c2eac96566c88da57d08ad033ff519b2e1d5ab638f905330ba20"),
            Map.entry("al05_96.wav", "DVD2:18a36d7158c214a0d59794fa60154f3a37e35d65499f8daa8d1df523f3a61423"),
            Map.entry("al05sf_48.wav", "DVD2:e2fb8da2774f73db724e4d90a456b28d14811e36f4cf1f836f7d715be32b92b1"),
            Map.entry("al06_48_f00.wav", "DVD2:178d347d443b36c0daeb57fbaad7df10820dc0faba8ce7cf41199160f0071049"),
            Map.entry("al06_48_f01.wav", "DVD2:be569e6141624bcc4a0aca6842a391516df63017cc576a739c8987d432dbec4e"),
            Map.entry("al06_48_f02.wav", "DVD2:a960594df328eb7664f6206b4d2c4aa785b875c7ad689a49ead885abd2aea957"),
            Map.entry("al06sf_48_f00.wav", "DVD2:e2cc88d32c9b6e71b9123a04e79967126169324645e84ded6437eb753f1935ad"),
            Map.entry("al06sf_48_f01.wav", "DVD2:8f015fcc3d01838f6168334688fc8f9878b423ca5f8414d6d226fa69c48bb3dc"),
            Map.entry("al06sf_48_f02.wav", "DVD2:b08fa60c5c30e049c7dd428eff57844572eb10b68f6e4f21a6e87abb188d1145"),
            Map.entry("al07_48_b00.wav", "DVD2:5471dd19f9e9f28510f3398053242f742d8c7b2000cc27c00afa78b1a0f25026"),
            Map.entry("al07_48_b01.wav", "DVD2:9cb3d623f14d27ce24a7d3e7ae2298564695f75dcd135a48aff45b230888a8f3"),
            Map.entry("al07_48_f00.wav", "DVD2:232e1a8b9f4b112944894b01ccf374ead264864f59df978987702fe4b96e7f1a"),
            Map.entry("al07_48_f01.wav", "DVD2:167e9622ecfd0a34e2733bd80269f665ba2b2076af7193b2e58e6cdef85a5f58"),
            Map.entry("al07_48_f02.wav", "DVD2:3b2b039f91cae175a4762519892eea1c0d003e0363eefc777e4509e45293c3d8"),
            Map.entry("al07_48_l00.wav", "DVD2:b23e482a848c15eb95f8daeb555509d927c46d066838b812cc2aebdab3051627"),
            Map.entry("al07sf_48_b00.wav", "DVD2:1a533cf77e9ceb1e6d8934b676ff525c70a9e76ded25edb4eb784d65c2383c29"),
            Map.entry("al07sf_48_b01.wav", "DVD2:67683127da1f95c3229c6f735ab11284111f9228243bd7524680034313f95c24"),
            Map.entry("al07sf_48_f00.wav", "DVD2:7d2f1cedf65196579ab301bf60d5ac89d74136633ab5051301ed5149ffd6ac3c"),
            Map.entry("al07sf_48_f01.wav", "DVD2:722739feed5fdef9ecd66a199a36d931c5effe884364d5a8ba7619a2a6ada0bc"),
            Map.entry("al07sf_48_f02.wav", "DVD2:bd3fdd094331d9fe0e2787c601ead32aa93594f1009f51d6ce024523ea8c0aa4"),
            Map.entry("al07sf_48_l00.wav", "DVD2:cf24c24c6c498c13f929b5919b1c62c90f238ebf52eb1fca890db3925141ad70"),
            Map.entry("al08_48_b00.wav", "DVD2:c231a98951758a9260f205fff84aac3a6905cd2c032be64c1f016476eaa2e328"),
            Map.entry("al08_48_f00.wav", "DVD2:804473a5b09b69e11d040eacbce4724ef54aa84b3c72736cb53f6dfe5a45942b"),
            Map.entry("al08_48_s17.wav", "DVD2:5bc51b6878aa1efaca2dd94cc8598d937b7744d6f709e6eb24e3abe334fda00b"),
            Map.entry("al14_48.wav", "DVD2:d07b50d27910794340ee74cdbe67c88cdbf8fa96a5830806ceec06725e93ddd7"),
            Map.entry("al16_48_f00.wav", "DVD2:ce1d881282fae96570a272eb3e2e85f4edcb78a409f55ddf50af7bcd6598d173"),
            Map.entry("al16_48_f01.wav", "DVD2:2f739c8745ae1e5dfc7b192d3d14e58274d0b24d8a7b924ec03078400007cac8"),
            Map.entry("al16_48_f02.wav", "DVD2:a5108a0cbfff5d7a5508b711f04fd46242ee4ee5f86654af045bd35f5bd9bd3e"),
            Map.entry("al17_48.wav", "DVD2:62592ccaaa32ddd111c02bcd92b34100de3d84d438df93498a50f9f673667652"),
            Map.entry("al17sf_48.wav", "DVD2:95bb6acb8b2b11994f9a60cfc22077ed644e51fe5c94e5c58a625c9c046a029d"));

    static Path fetch(String name) {
        var entry = VECTORS.get(name);
        if (entry == null) {
            return Conformance.fetchListed("mpeg4audio-" + name);
        }
        var dvd = entry.substring(0, entry.indexOf(':'));
        var hash = entry.substring(entry.indexOf(':') + 1);
        var folder = name.endsWith(".mp4") ? "compressedMp4/" : "referencesWav/";
        return Conformance.fetch("mpeg4audio-" + name, BASE + dvd + "/mpeg4audio-conformance/" + folder + name, hash);
    }

    static AacTestSupport.Decoded decode(String stream) {
        return AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(fetch(stream + ".mp4"))));
    }

    /** Returns {rms, peak} of the best alignment of {@code actual} against {@code reference}. */
    static double[] error(float[] reference, float[] actual, int frameLength) {
        double[] best = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (var offset = 0; offset <= 2 * frameLength; offset += frameLength) {
            if (actual.length - offset < reference.length) {
                continue;
            }
            double squared = 0;
            double peak = 0;
            for (var i = 0; i < reference.length; i++) {
                var clipped = Math.max(-1.0, Math.min(FULL_SCALE, actual[i + offset]));
                var difference = Math.abs((double) reference[i] - clipped);
                squared += difference * difference;
                peak = Math.max(peak, difference);
            }
            var rms = Math.sqrt(squared / reference.length);
            if (rms < best[0]) {
                best = new double[]{rms, peak};
            }
        }
        return best;
    }

    static void assertConformant(float[] reference, float[] actual, int frameLength, String what) {
        var error = error(reference, actual, frameLength);
        assertTrue(error[0] <= RMS_LIMIT, what + ": RMS error " + error[0] + " exceeds " + RMS_LIMIT);
        assertTrue(error[1] <= PEAK_LIMIT, what + ": peak error " + error[1] + " exceeds " + PEAK_LIMIT);
    }

    @Nested
    @DisplayName("Low Complexity against reference waveforms")
    class LowComplexity {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"al00_08", "al00_11", "al00_12", "al00_16", "al00_22", "al00_24", "al00_32", "al00_44",
                "al00_48", "al00_64", "al00_88", "al00_96", "al01_48", "al02_48", "al03_48", "al04_48", "al05_08",
                "al05_22", "al05_48", "al05_96", "al14_48", "al17_48"})
        @DisplayName("mono and stereo streams at every sampling rate")
        void single(String stream) {
            var decoded = decode(stream);
            var reference = AacTestSupport.readWav(Fixtures.read(fetch(stream + ".wav")));
            assertEquals(reference.length, decoded.planes().length);
            for (var channel = 0; channel < reference.length; channel++) {
                assertConformant(reference[channel], decoded.planes()[channel], 1024, stream + " channel " + channel);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"al00sf_48", "al01sf_48", "al05sf_48", "al17sf_48"})
        @DisplayName("960-sample frames")
        void shortFrames(String stream) {
            var decoded = decode(stream);
            assertEquals(0, decoded.frames() % 960);
            var reference = AacTestSupport.readWav(Fixtures.read(fetch(stream + ".wav")));
            for (var channel = 0; channel < reference.length; channel++) {
                assertConformant(reference[channel], decoded.planes()[channel], 960, stream + " channel " + channel);
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "al06_48, 1024, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT",
                "al06sf_48, 960, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT",
                "al07_48, 1024, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT b00:BACK_LEFT b01:BACK_RIGHT l00:LOW_FREQUENCY",
                "al07sf_48, 960, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT b00:BACK_LEFT b01:BACK_RIGHT l00:LOW_FREQUENCY",
                "al16_48, 1024, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT"
        })
        @DisplayName("multichannel streams (3.0, 5.1 with a coupling channel) in WAVE order")
        void multichannel(String stream, int frameLength, String channels) {
            assertChannels(stream, frameLength, channels);
        }

        @Test
        @DisplayName("a 48-channel program configuration")
        void programConfiguration() {
            assertProgramConfiguration("al08_48");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#noiseStreams")
        @DisplayName("perceptual noise substitution reproduces the reference's short-term energy")
        void noiseSubstitution(String stream) {
            var frameLength = stream.contains("sf_") ? 960 : 1024;
            var decoded = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(
                    Conformance.fetchListed("mpeg4audio-" + stream + ".mp4")))).planes()[0];
            var reference = AacTestSupport.readWav(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + stream + ".wav")))[0];
            var bestDeviation = Double.POSITIVE_INFINITY;
            for (var offset = 0; offset <= 2 * frameLength; offset += frameLength) {
                double deviation = 0;
                var blocks = 0;
                for (var start = 0; start + 2048 <= reference.length && start + offset + 2048 <= decoded.length; start += 2048) {
                    double a = 0;
                    double b = 0;
                    for (var i = start; i < start + 2048; i++) {
                        a += (double) reference[i] * reference[i];
                        b += (double) decoded[i + offset] * decoded[i + offset];
                    }
                    if (a < 1e-6) {
                        continue;
                    }
                    deviation += Math.abs(10 * Math.log10((b + 1e-12) / a));
                    blocks++;
                }
                bestDeviation = Math.min(bestDeviation, deviation / blocks);
            }
            assertTrue(bestDeviation < 1.0, stream + ": mean short-term energy deviation " + bestDeviation + " dB");
        }
    }

    /** The other sampling rates of the mono and stereo streams, by stream family. */
    private static final Map<String, String> RATES = Map.ofEntries(
            Map.entry("al00sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al01", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al01sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al02", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al02sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al03", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al03sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al04", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al04sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al05", "11 12 16 24 32 44 64 88"),
            Map.entry("al05sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al14", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al14sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al17", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al17sf", "08 11 12 16 22 24 32 44 64 88 96"));

    /** The perceptual noise substitution streams at every sampling rate, by stream family. */
    private static final Map<String, String> NOISE_RATES = Map.ofEntries(
            Map.entry("al18", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al18sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al19", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al19sf", "08 11 12 16 22 24 32 44 48 64 88 96"));

    /** The multichannel streams at the sampling rates the 48 kHz tests do not cover, by stream family. */
    private static final Map<String, String> MULTICHANNEL_RATES = Map.ofEntries(
            Map.entry("al06", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al06sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al07", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al07sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al16", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al16sf", "08 11 12 16 22 24 32 44 48 64 88 96"));

    /** The reference waveforms of each multichannel family, with the speaker each one holds. */
    private static final Map<String, String> MULTICHANNEL_CHANNELS = Map.of(
            "al06", "f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT",
            "al07", "f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT b00:BACK_LEFT b01:BACK_RIGHT l00:LOW_FREQUENCY",
            "al16", "f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT");

    /** The rates of the 48-channel program configuration streams that the 48 kHz test does not cover. */
    private static final Map<String, String> PROGRAM_RATES = Map.ofEntries(
            Map.entry("al08", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al08sf", "08 11 12 16 22 24 32 44 48 64 88 96"));

    static java.util.stream.Stream<String> otherRates() {
        return streams(RATES);
    }

    static java.util.stream.Stream<String> multichannelRates() {
        return streams(MULTICHANNEL_RATES);
    }

    static java.util.stream.Stream<String> programRates() {
        return streams(PROGRAM_RATES);
    }

    static java.util.stream.Stream<String> noiseStreams() {
        return streams(NOISE_RATES);
    }

    private static java.util.stream.Stream<String> streams(Map<String, String> rates) {
        return rates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> java.util.Arrays.stream(entry.getValue().split(" "))
                        .map(rate -> entry.getKey() + "_" + rate));
    }

    /** Asserts that each named reference waveform of a multichannel stream is reproduced by its speaker. */
    static void assertChannels(String stream, int frameLength, String channels) {
        var decoded = decode(stream);
        assertEquals(0, decoded.frames() % frameLength, stream + " frames");
        for (var mapping : channels.split(" ")) {
            var parts = mapping.split(":");
            var index = decoded.layout().indexOf(Speaker.valueOf(parts[1]));
            assertTrue(index >= 0, stream + " lacks " + parts[1]);
            var reference = AacTestSupport.readWav(Fixtures.read(fetch(stream + "_" + parts[0] + ".wav")))[0];
            assertConformant(reference, decoded.planes()[index], frameLength, stream + " " + parts[1]);
        }
    }

    /**
     * Asserts that a stream of the 48-channel program configuration decodes 48 channels, the front center third, and
     * that a front, a back and a side reference waveform are each reproduced by a channel.
     */
    static void assertProgramConfiguration(String stream) {
        var frameLength = stream.contains("sf_") ? 960 : 1024;
        var decoded = decode(stream);
        assertEquals(48, decoded.planes().length);
        assertEquals(Speaker.FRONT_CENTER, decoded.layout().speakers().get(2));
        for (var name : new String[]{"f00", "b00", "s17"}) {
            var reference = AacTestSupport.readWav(Fixtures.read(fetch(stream + "_" + name + ".wav")))[0];
            var matched = false;
            for (var plane : decoded.planes()) {
                var error = error(reference, plane, frameLength);
                matched |= error[0] <= RMS_LIMIT && error[1] <= PEAK_LIMIT;
            }
            assertTrue(matched, stream + ": no channel reproduces " + name);
        }
        var front = AacTestSupport.readWav(Fixtures.read(fetch(stream + "_f00.wav")))[0];
        assertConformant(front, decoded.planes()[decoded.layout().indexOf(Speaker.FRONT_CENTER)], frameLength,
                stream + " center");
    }

    @Nested
    @DisplayName("Low Complexity at the other sampling rates against reference waveforms")
    class OtherRates {
        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#otherRates")
        @DisplayName("mono and stereo streams, 1024- and 960-sample frames, at 8 to 96 kHz")
        void stream(String stream) {
            var frameLength = stream.contains("sf_") ? 960 : 1024;
            var decoded = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(
                    Conformance.fetchListed("mpeg4audio-" + stream + ".mp4"))));
            assertEquals(0, decoded.frames() % frameLength);
            var reference = AacTestSupport.readWav(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + stream + ".wav")));
            assertEquals(reference.length, decoded.planes().length);
            for (var channel = 0; channel < reference.length; channel++) {
                assertConformant(reference[channel], decoded.planes()[channel], frameLength, stream + " channel " + channel);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#multichannelRates")
        @DisplayName("multichannel streams (3.0, 5.1 with a coupling channel), 1024- and 960-sample frames, at 8 to 96 kHz")
        void multichannel(String stream) {
            var family = stream.substring(0, stream.indexOf('_')).replace("sf", "");
            assertChannels(stream, stream.contains("sf_") ? 960 : 1024, MULTICHANNEL_CHANNELS.get(family));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#programRates")
        @DisplayName("the 48-channel program configuration, 1024- and 960-sample frames, at 8 to 96 kHz")
        void programConfiguration(String stream) {
            assertProgramConfiguration(stream);
        }
    }
}
