package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the USAC conformance sequences of ISO/IEC 23003-3 and compares them with their reference waveforms under
 * the criteria of the conformance tables: RMS/LSB at 16 bits for the frequency domain streams ({@code Fd}), at 14
 * bits for enhanced SBR ({@code eSbr}), MPEG Surround 2-1-2 ({@code Mps}) and the extensions ({@code Ext}), and a
 * segmental SNR of 50 dB (40 dB for the LPC streams) for the linear prediction domain ({@code Lpd}) and the common
 * core coder tools ({@code Cct}), which may instead meet RMS/LSB at 7 bits. The output is aligned by the edit list
 * of the MP4 file, as the references were cut. The MPEG-D DRC references ({@code Ext_2}) name their playback
 * request: {@code __Lou-24} targets -24 LKFS and {@code _Eff-1} requests the night effect type of
 * ISO/IEC 23003-4 Table A.48.
 *
 * <p>The smoke tier covers each tool once ({@code iso-23003-3-usac-smoke}); the full tier decodes every sequence
 * ({@code iso-23003-3-usac-streams}) against the references at the lowest sampling rate of each test case
 * ({@code iso-23003-3-usac-references}).
 */
@DisplayName("USAC conformance sequences (ISO/IEC 23003-3)")
class UsacConformanceTest {
    private static final Pattern REQUEST = Pattern.compile("__Lou-(\\d+)(?:_Eff-(\\d+))?$");

    private static final AacDrcEffect[] EFFECTS = {AacDrcEffect.NONE, AacDrcEffect.NIGHT, AacDrcEffect.NOISY,
            AacDrcEffect.LIMITED, AacDrcEffect.LOW_LEVEL, AacDrcEffect.DIALOG, AacDrcEffect.GENERAL_COMPRESSION,
            AacDrcEffect.EXPANDED, AacDrcEffect.ARTISTIC};

    @TestFactory
    @DisplayName("one sequence of each tool matches its reference")
    Stream<DynamicTest> smoke() {
        var archive = Conformance.archive("iso-23003-3-usac-smoke");
        var streams = new HashMap<String, Path>();
        return archive.members().stream().sorted().flatMap(member -> {
            var name = member.substring(member.indexOf('/') + 1, member.lastIndexOf('.'));
            if (member.endsWith(".mp4")) {
                return Stream.empty();
            }
            return Stream.of(DynamicTest.dynamicTest(name, () -> {
                var stream = streamOf(name);
                var mp4 = streams.computeIfAbsent(stream, key -> archive.member("compressedMp4/" + key + ".mp4"));
                check(name, mp4, archive.member(member));
            }));
        });
    }

    @TestFactory
    @DisplayName("the DRC sequence plays under every effect request and target level")
    Stream<DynamicTest> drcEffects() {
        var archive = Conformance.archive("iso-23003-3-usac-smoke");
        var mp4 = archive.member("compressedMp4/Ext_2_c1_LnDrc1_0x03.mp4");
        var stream = AacTestSupport.parseMp4(Fixtures.read(mp4));
        return Stream.of(EFFECTS).map(effect -> DynamicTest.dynamicTest(effect.name(), () -> {
            for (var target : new int[]{-1, 4 * 16, 4 * 31}) {
                var builder = AacDecoderOptions.builder().drcEffect(effect);
                if (target >= 0) {
                    builder.targetLevel(target);
                }
                try (var decoder = new AacDecoder(stream.track(), builder.build())) {
                    var planes = AacTestSupport.decodeAll(decoder, stream.frames()).planes();
                    assertTrue(planes.length > 0 && planes[0].length > 0, effect + " decodes");
                    for (var plane : planes) {
                        for (var value : plane) {
                            assertTrue(Float.isFinite(value), effect + " gives finite samples");
                        }
                    }
                }
            }
        }));
    }

    @TestFactory
    @DisplayName("every sequence decodes, and matches its reference at the lowest sampling rate of its test case")
    Stream<DynamicTest> full() {
        var streams = Conformance.archive("iso-23003-3-usac-streams");
        var references = Conformance.archive("iso-23003-3-usac-references");
        Map<String, String> referenceOf = new HashMap<>();
        for (var member : references.members()) {
            var name = member.substring(member.indexOf('/') + 1, member.lastIndexOf('.'));
            referenceOf.merge(streamOf(name), member, (a, b) -> a + "\n" + b);
        }
        return streams.members().stream().sorted().map(member -> {
            var stream = member.substring(member.indexOf('/') + 1, member.lastIndexOf('.'));
            return DynamicTest.dynamicTest(stream, () -> {
                var mp4 = streams.member(member);
                var refs = referenceOf.get(stream);
                if (refs == null) {
                    var decoded = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(mp4)));
                    assertTrue(decoded.frames() > 0, stream + " decodes");
                    return;
                }
                for (var reference : refs.split("\n")) {
                    var name = reference.substring(reference.indexOf('/') + 1, reference.lastIndexOf('.'));
                    check(name, mp4, references.member(reference));
                }
            });
        });
    }

    /** Returns the sequence of a reference waveform: its name without the playback request. */
    static String streamOf(String reference) {
        var index = reference.indexOf("__");
        return index < 0 ? reference : reference.substring(0, index);
    }

    /** Returns the decoder options of the playback request a reference name carries. */
    static AacDecoderOptions optionsOf(String reference) {
        var builder = AacDecoderOptions.builder();
        var matcher = REQUEST.matcher(reference);
        if (matcher.find()) {
            builder.targetLevel(4 * Integer.parseInt(matcher.group(1)));
            if (matcher.group(2) != null) {
                builder.drcEffect(EFFECTS[Integer.parseInt(matcher.group(2))]);
            }
        }
        return builder.build();
    }

    static void check(String reference, Path mp4, Path wav) {
        var stream = AacTestSupport.parseMp4(Fixtures.read(mp4));
        AacTestSupport.Decoded decoded;
        try (var decoder = new AacDecoder(stream.track(), optionsOf(reference))) {
            decoded = AacTestSupport.decodeAll(decoder, stream.frames());
        }
        var expected = AacTestSupport.readWav(Fixtures.read(wav));
        var actual = decoded.planes();
        assertEquals(expected.length, actual.length, reference + " channels");
        var offset = (int) Math.max(0, stream.skip());
        for (var c = 0; c < expected.length; c++) {
            assertTrue(actual[c].length - offset >= expected[c].length - 1, reference + " length: "
                    + (actual[c].length - offset) + " samples for " + expected[c].length);
        }
        var family = reference.substring(0, reference.indexOf('_'));
        switch (family) {
            case "Fd" -> assertRmsLsb(reference, expected, actual, offset, 16);
            case "Lpd" -> assertSegmentalSnr(reference, expected, actual, offset,
                    reference.contains("_Lpc_") ? 40 : 50);
            case "Cct" -> {
                if (rmsLsbError(expected, actual, offset, 7) != null) {
                    assertSegmentalSnr(reference, expected, actual, offset, 50);
                }
            }
            default -> assertRmsLsb(reference, expected, actual, offset, 14);
        }
    }

    private static float sample(float[] plane, int index) {
        return index < plane.length ? Math.clamp(plane[index], -1.0f, 8388607 / 8388608.0f) : 0.0f;
    }

    /** Returns a description of the violation of the RMS/LSB criterion at a resolution, or {@code null}. */
    private static String rmsLsbError(float[][] expected, float[][] actual, int offset, int bits) {
        var rmsLimit = Math.pow(2, 1 - bits) / Math.sqrt(12);
        var peakLimit = Math.pow(2, 2 - bits);
        for (var c = 0; c < expected.length; c++) {
            double squared = 0;
            double peak = 0;
            for (var i = 0; i < expected[c].length; i++) {
                var difference = Math.abs((double) expected[c][i] - sample(actual[c], i + offset));
                squared += difference * difference;
                peak = Math.max(peak, difference);
            }
            var rms = Math.sqrt(squared / Math.max(1, expected[c].length));
            if (rms > rmsLimit || peak > peakLimit) {
                return "channel " + c + ": RMS " + rms * 32768 + " and peak " + peak * 32768
                        + " LSB16 exceed the " + bits + "-bit limits";
            }
        }
        return null;
    }

    private static void assertRmsLsb(String reference, float[][] expected, float[][] actual, int offset, int bits) {
        var error = rmsLsbError(expected, actual, offset, bits);
        assertTrue(error == null, reference + " " + error);
    }

    /**
     * Asserts the segmental SNR: the mean over segments of 256 samples with signal of their SNR, each clipped to
     * [-10, 80] dB.
     */
    private static void assertSegmentalSnr(String reference, float[][] expected, float[][] actual, int offset,
                                           double limit) {
        for (var c = 0; c < expected.length; c++) {
            double sum = 0;
            var segments = 0;
            for (var start = 0; start + 256 < expected[c].length; start += 256) {
                double energy = 0;
                double noise = 0;
                for (var i = start; i < start + 256; i++) {
                    var value = (double) expected[c][i];
                    var difference = value - sample(actual[c], i + offset);
                    energy += value * value;
                    noise += difference * difference;
                }
                if (energy < 1e-10) {
                    continue;
                }
                sum += Math.clamp(10 * Math.log10(energy / Math.max(noise, 1e-20)), -10, 80);
                segments++;
            }
            var snr = segments == 0 ? 80 : sum / segments;
            assertTrue(snr >= limit, reference + " channel " + c + ": segmental SNR " + snr + " dB below " + limit);
        }
    }

    /** Decodes the first track of a file through {@link com.github.auties00.smalt.container.Containers}. */
    static float[][] decodeContainer(byte[] data, AacDecoderOptions options) {
        var chunks = new java.util.ArrayList<float[][]>();
        try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                com.github.auties00.smalt.io.MediaSource.of(data))) {
            var track = (com.github.auties00.smalt.track.AudioTrack) demuxer.tracks().getFirst();
            try (var decoder = new AacDecoder(track, options)) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, buffer -> chunks.add(AacTestSupport.copy(buffer)));
                }
                decoder.flush(buffer -> chunks.add(AacTestSupport.copy(buffer)));
            }
        }
        var channels = chunks.getFirst().length;
        var length = chunks.stream().mapToInt(chunk -> chunk[0].length).sum();
        var planes = new float[channels][length];
        var position = 0;
        for (var chunk : chunks) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(chunk[c], 0, planes[c], position, chunk[c].length);
            }
            position += chunk[0].length;
        }
        return planes;
    }

    /**
     * Real-world xHE-AAC: FATE's stream with loudness metadata, against FATE's 16-bit reference decode, and the
     * Fraunhofer xHE-AAC bitrate switching set of the DASH-IF multichannel audio vectors (Sintel, 16 to 128 kbit/s,
     * the first 12 segments of 5.12 s, each starting with an AudioPreRoll), whose MPD declares bitstream switching.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("real-world xHE-AAC")
    class RealWorld {
        private static final String SET = "dashif-mca-fhg-xheaac-sintel_audio_only_brs";

        private static final String[] RATES = {"16", "32", "64", "128"};

        private static final int SEGMENTS = 12;

        @org.junit.jupiter.api.Test
        @DisplayName("the loudness metadata stream matches FATE's 16-bit reference within the 16-bit criterion")
        void targetLevel() {
            // FATE's reference is ffmpeg's decode, which applies none of the stream's MPEG-D DRC and loudness
            // metadata: the decoder is asked for neither
            var decoded = decodeContainer(Fixtures.read(Conformance.vector("aac-usac-xhe_target_level.m4a")),
                    AacDecoderOptions.builder().noTargetLevel().drcEffect(AacDrcEffect.NONE).build());
            var raw = Fixtures.read(Conformance.vector("aac-usac-xhe_target_level.s16"));
            var shorts = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            var channels = decoded.length;
            assertEquals(2, channels, "channels");
            var frames = shorts.remaining() / channels;
            var expected = new float[channels][frames];
            for (var i = 0; i < frames; i++) {
                for (var c = 0; c < channels; c++) {
                    expected[c][i] = shorts.get(i * channels + c) / 32768.0f;
                }
            }
            // ffmpeg drops the priming the edit list of the file names, as check() does for the conformance streams
            var offset = (int) Math.max(0, AacTestSupport.parseMp4(Fixtures.read(Conformance.vector(
                    "aac-usac-xhe_target_level.m4a"))).skip());
            assertRmsLsb("xhe_target_level", expected, decoded, offset, 16);
        }

        private static List<String> segments(String rate, int first, int last) {
            var names = new java.util.ArrayList<String>();
            for (var n = first; n <= last; n++) {
                names.add(SET + "-eng-2-xheaac-" + rate + "kbps_" + n + ".m4s");
            }
            return names;
        }

        @org.junit.jupiter.api.Test
        @DisplayName("the MPD declares the switching set the segments come from")
        void manifest() throws java.io.IOException {
            var mpd = java.nio.file.Files.readString(Conformance.vector(SET + ".mpd"));
            assertTrue(mpd.contains("bitstreamSwitching=\"true\""), "bitstream switching");
            assertTrue(mpd.contains("codecs=\"mp4a.40.42\""), "xHE-AAC");
            assertTrue(mpd.contains("duration=\"245760\"") && mpd.contains("timescale=\"48000\""), "segment duration");
            for (var rate : RATES) {
                assertTrue(mpd.contains("<Representation id=\"eng-2-xheaac-" + rate + "kbps\""), rate + " kbit/s");
            }
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0} kbit/s")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"16", "32", "64", "128"})
        @DisplayName("each representation decodes to stereo 48 kHz for the duration of its segments")
        void representation(String rate) {
            var names = new java.util.ArrayList<String>();
            names.add(SET + "-eng-2-xheaac-" + rate + "kbps_init.mp4");
            names.addAll(segments(rate, 0, SEGMENTS - 1));
            var decoded = decodeContainer(StreamingVectors.join(names), AacDecoderOptions.defaults());
            assertEquals(2, decoded.length, "channels");
            var expected = 245760L * SEGMENTS;
            assertTrue(Math.abs(decoded[0].length - expected) <= 4096, decoded[0].length + " samples for " + expected);
            for (var plane : decoded) {
                double energy = 0;
                for (var sample : plane) {
                    assertTrue(Float.isFinite(sample), "finite samples");
                    energy += (double) sample * sample;
                }
                assertTrue(energy > 0, "signal");
            }
        }

        /**
         * Switching representation at every segment boundary, after the first representation's initialization
         * segment, must give each segment the samples of that representation's own decode once the AudioPreRoll of
         * the segment has primed the decoder: the segments of the switched stream are compared with the same segments
         * of the representation decoded alone, under the 14-bit criterion of the USAC tables.
         */
        @org.junit.jupiter.api.Test
        @DisplayName("switching representation at every segment gives each segment the decode of its representation")
        void switching() {
            var alone = new java.util.HashMap<String, float[][]>();
            for (var rate : RATES) {
                var names = new java.util.ArrayList<String>();
                names.add(SET + "-eng-2-xheaac-" + rate + "kbps_init.mp4");
                names.addAll(segments(rate, 0, SEGMENTS - 1));
                alone.put(rate, decodeContainer(StreamingVectors.join(names), AacDecoderOptions.defaults()));
            }
            var names = new java.util.ArrayList<String>();
            names.add(SET + "-eng-2-xheaac-16kbps_init.mp4");
            for (var n = 0; n < SEGMENTS; n++) {
                names.addAll(segments(RATES[n % RATES.length], n, n));
            }
            var switched = decodeContainer(StreamingVectors.join(names), AacDecoderOptions.defaults());
            assertEquals(alone.get("16")[0].length, switched[0].length, "length of the switched stream");
            var segment = 245760;
            for (var n = 1; n < SEGMENTS; n++) {
                var reference = alone.get(RATES[n % RATES.length]);
                var start = n * segment;
                var length = Math.min(segment, switched[0].length - start);
                var expected = new float[2][length];
                var actual = new float[2][length];
                for (var c = 0; c < 2; c++) {
                    System.arraycopy(reference[c], start, expected[c], 0, length);
                    System.arraycopy(switched[c], start, actual[c], 0, length);
                }
                assertRmsLsb("segment " + n + " at " + RATES[n % RATES.length] + " kbit/s", expected, actual, 0, 14);
            }
        }
    }
}
