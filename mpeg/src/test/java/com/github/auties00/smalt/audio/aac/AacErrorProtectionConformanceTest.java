package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ep.EpFrameEncoder;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionDecoder;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes every stream of the ISO/IEC 14496-26 conformance suite that applies the error protection tool to an AAC
 * object type ({@code epConfig} 3 with direct mapping): ER AAC LC, LTP and LD ({@code er_al}, {@code er_ap},
 * {@code er_ad}), ER BSAC ({@code er_bs}) and ER scalable AAC ({@code er_ac}). Every one of these streams has twins
 * of the same content stored with {@code epConfig} 0 and 1, so the EP decoder output is compared bit for bit with
 * the category instances of the {@code epConfig} 1 twin (the definition of direct mapping), and the decoded audio bit
 * for bit with the decode of the {@code epConfig} 0 twin, which the other conformance tests compare with the
 * reference waveforms. The test encoder rebuilds every frame from the decoded classes byte for byte, which validates
 * it for the tests that build streams the suite lacks. Scalable streams over a CELP or TwinVQ core check their AAC
 * layers at the EP level only.
 */
class AacErrorProtectionConformanceTest {
    private static final List<String> AAC = List.of("er_ad1111_22_ep3_srs01", "er_ad1111_44_ep3_srs01",
            "er_ad2050_24_ep3_srs03", "er_ad2050_48_ep3_srs03", "er_ad2170_32_ep3_srs04", "er_al10_08_ep3_crc01",
            "er_al10_22_ep3_crc01", "er_al15_08_ep3_srcpc01", "er_al15_48_ep3_srcpc01", "er_al18_11_ep3_crc02",
            "er_al18_11_ep3_srcpc02", "er_al18_24_ep3_crc02", "er_al18_88_ep3_srcpc02", "er_al21_12_ep3_crc03",
            "er_al21_32_ep3_crc03", "er_al23_16_ep3_crc04", "er_al23_44_ep3_crc04", "er_al26_12_ep3_srcpc03",
            "er_al26_88_ep3_srcpc03", "er_ap14_12_ep3_srs02", "er_ap14_32_ep3_srs02", "er_ap27_16_ep3_srcpc04",
            "er_ap27_96_ep3_srcpc04");

    private static final List<String> SCALABLE = List.of("er_ac111_ep3_cplx01", "er_ac123_ep3_cplx02");

    // CELP (er_ac2xx) and TwinVQ (er_ac3xx) core layers: only the AAC layers decode
    private static final List<String> SCALABLE_OVER_CORE = List.of("er_ac211_ep3_cplx03", "er_ac221_ep3_cplx04",
            "er_ac311_ep3_cplx05", "er_ac321_ep3_cplx06");

    static Stream<String> aac() {
        return AAC.stream();
    }

    static Stream<String> bsac() {
        var names = new ArrayList<String>();
        for (var stream = 1; stream <= 11; stream++) {
            for (var rate : new String[]{"08", "11", "12", "16", "22", "24", "32", "44", "48"}) {
                names.add("er_bs%02d_%s_ep3".formatted(stream, rate));
            }
        }
        return names.stream();
    }

    static Stream<String> scalable() {
        return SCALABLE.stream();
    }

    static Stream<String> everyStream() {
        return Stream.of(aac(), bsac(), SCALABLE.stream(), SCALABLE_OVER_CORE.stream()).flatMap(s -> s);
    }

    private static String base(String stream) {
        return stream.substring(0, stream.indexOf("_ep3"));
    }

    private static List<AacTestSupport.Stream> tracks(String name) {
        return AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
    }

    private static float[][] decode(List<AacTestSupport.Stream> tracks, int maxLayer) {
        var options = AacDecoderOptions.builder().maxLayer(maxLayer).build();
        if (tracks.size() == 1) {
            try (var decoder = new AacDecoder(tracks.getFirst().track(), options)) {
                return AacTestSupport.decodeAll(decoder, tracks.getFirst().frames()).planes();
            }
        }
        return AacTestSupport.decodeInstances(tracks, options).planes();
    }

    /**
     * Returns the highest layer the tracks of a twin hold: the {@code er_bs02} to {@code er_bs04} MP4 files list layer
     * tracks whose samples lie past their end.
     */
    private static int layers(String twin) {
        return BsacConformanceTest.TRUNCATED.getOrDefault(twin, 63);
    }

    private static boolean decodable(byte[] config) {
        try {
            AudioSpecificConfig.parse(config);
            return true;
        } catch (MediaException.Unsupported _) {
            return false;
        }
    }

    @Nested
    @DisplayName("the EP decoder")
    class Classes {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacErrorProtectionConformanceTest#everyStream")
        @DisplayName("passes every CRC and outputs the category instances of the epConfig 1 twin")
        void matchesSeparateInstances(String stream) {
            var protectedTracks = tracks(stream);
            var separate = tracks(base(stream) + "_ep1");
            // the epConfig 1 twin of a truncated BSAC file lacks some of its instance tracks
            var complete = !BsacConformanceTest.TRUNCATED.containsKey(base(stream) + "_ep1");
            var decodableTracks = protectedTracks.stream().filter(track -> decodable(track.config())).toList();
            assertTrue(!decodableTracks.isEmpty(), stream + " has no AAC layer");
            var classes = new ArrayList<List<byte[]>>();
            var lengths = new ArrayList<List<Integer>>();
            var classTotal = 0;
            for (var track : decodableTracks) {
                var config = AudioSpecificConfig.parse(track.config());
                assertEquals(3, config.epConfig(), stream);
                var decoder = new ErrorProtectionDecoder(config.errorProtection(), MediaLimits.defaults());
                var trackClasses = new ArrayList<byte[]>();
                var trackLengths = new ArrayList<Integer>();
                var count = -1;
                for (var frame = 0; frame < track.frames().size(); frame++) {
                    var data = track.frames().get(frame);
                    decoder.decode(data, 0, data.length);
                    assertTrue(decoder.intact(0), stream + " frame " + frame + " fails its CRCs");
                    assertEquals(1, decoder.frames());
                    count = decoder.classCount();
                    for (var c = 0; c < count; c++) {
                        trackClasses.add(java.util.Arrays.copyOf(decoder.classData(0, c),
                                (decoder.classBits(0, c) + 7) / 8));
                        trackLengths.add(decoder.classBits(0, c));
                    }
                }
                classTotal += count;
                classes.add(trackClasses);
                lengths.add(trackLengths);
            }
            if (!complete) {
                return;
            }
            // the AAC layers of a scalable stream over a core come last in both files; the classes of every other
            // stream are the first instance tracks (an ER BSAC stream may carry its first elementary streams only)
            var firstSeparate = decodableTracks.size() < protectedTracks.size()
                    ? separate.size() - classTotal : 0;
            assertTrue(firstSeparate >= 0, stream + ": " + classTotal + " classes for " + separate.size() + " tracks");
            var separateTrack = firstSeparate;
            for (var t = 0; t < decodableTracks.size(); t++) {
                var frames = decodableTracks.get(t).frames().size();
                var perFrame = classes.get(t).size() / frames;
                for (var c = 0; c < perFrame; c++) {
                    var instances = separate.get(separateTrack + c).frames();
                    assertEquals(frames, instances.size(), stream + " frame count");
                    for (var frame = 0; frame < frames; frame++) {
                        var expected = instances.get(frame);
                        var actual = classes.get(t).get(frame * perFrame + c);
                        var bits = lengths.get(t).get(frame * perFrame + c);
                        assertEquals(expected.length, actual.length, stream + " frame " + frame + " class " + c);
                        Tolerance.exact().assertWithin(stream + " frame " + frame + " class " + c, prefix(expected, bits),
                                prefix(actual, bits));
                    }
                }
                separateTrack += perFrame;
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacErrorProtectionConformanceTest#everyStream")
        @DisplayName("re-encodes every frame to the conformance bytes")
        void reencodes(String stream) {
            var all = tracks(stream).stream().filter(track -> decodable(track.config())).toList();
            for (var track : all) {
                var protection = AudioSpecificConfig.parse(track.config()).errorProtection();
                var decoder = new ErrorProtectionDecoder(protection, MediaLimits.defaults());
                for (var frame = 0; frame < track.frames().size(); frame++) {
                    var data = track.frames().get(frame);
                    decoder.decode(data, 0, data.length);
                    var classes = new byte[1][decoder.classCount()][];
                    for (var c = 0; c < decoder.classCount(); c++) {
                        classes[0][c] = EpFrameEncoder.unpack(decoder.classData(0, c), decoder.classBits(0, c));
                    }
                    var encoded = EpFrameEncoder.encode(protection, decoder.choice(), classes, null, null);
                    Tolerance.exact().assertWithin(stream + " frame " + frame, data, encoded);
                }
            }
        }

        private static byte[] prefix(byte[] data, int bits) {
            var out = java.util.Arrays.copyOf(data, (bits + 7) / 8);
            if (bits % 8 != 0) {
                out[out.length - 1] &= (byte) (0xFF << (8 - bits % 8));
            }
            return out;
        }
    }

    @Nested
    @DisplayName("the decoded audio")
    class Audio {
        @ParameterizedTest(name = "{0}")
        @MethodSource({"com.github.auties00.smalt.audio.aac.AacErrorProtectionConformanceTest#aac",
                "com.github.auties00.smalt.audio.aac.AacErrorProtectionConformanceTest#bsac",
                "com.github.auties00.smalt.audio.aac.AacErrorProtectionConformanceTest#scalable"})
        @DisplayName("equals the decode of its twin bit for bit, at every layer the twin's file holds")
        void matchesUnprotected(String stream) {
            var twin = layers(base(stream) + "_ep1") >= layers(base(stream) + "_ep0") ? base(stream) + "_ep1"
                    : base(stream) + "_ep0";
            var layer = layers(twin);
            var twinTracks = tracks(twin);
            var protectedTracks = tracks(stream);
            if (stream.startsWith("er_bs")) {
                // an ER BSAC stream with the error protection tool may carry the layers of the first elementary
                // streams of its twins only: compare with the epConfig 0 streams whose access units it holds (when
                // the twin's file is truncated, only its first stream is certain to be complete)
                var unprotected = tracks(base(stream) + "_ep0");
                var leading = leadingStreams(protectedTracks.getFirst(), unprotected);
                var truncated = BsacConformanceTest.TRUNCATED.containsKey(base(stream) + "_ep0");
                if (leading == 1 || (leading > 0 && !truncated)) {
                    twinTracks = unprotected.subList(0, leading);
                    layer = 63;
                }
            }
            var expected = decode(twinTracks, layer);
            var actual = decode(protectedTracks, layer);
            assertEquals(expected.length, actual.length, stream + " channels");
            for (var channel = 0; channel < expected.length; channel++) {
                Tolerance.exact().assertPcm(stream + " channel " + channel, expected[channel], actual[channel]);
            }
        }

        /**
         * Returns how many leading elementary streams of an epConfig 0 file the classes of a protected frame fill,
         * by the bytes of the second frame, or 0 when no prefix matches.
         */
        private static int leadingStreams(AacTestSupport.Stream track, List<AacTestSupport.Stream> unprotected) {
            var protection = AudioSpecificConfig.parse(track.config()).errorProtection();
            var decoder = new ErrorProtectionDecoder(protection, MediaLimits.defaults());
            var frame = track.frames().get(1);
            decoder.decode(frame, 0, frame.length);
            var bytes = 0L;
            for (var c = 0; c < decoder.classCount(); c++) {
                bytes += (decoder.classBits(0, c) + 7) / 8;
            }
            var sum = 0L;
            for (var k = 0; k < unprotected.size(); k++) {
                sum += unprotected.get(k).frames().get(1).length;
                if (sum == bytes) {
                    return k + 1;
                }
            }
            return 0;
        }
    }
}
