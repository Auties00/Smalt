package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.Configuration;
import com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.Segment;
import com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.SegmentList;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.C;
import static com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.L;
import static com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.LS;
import static com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.R;
import static com.github.auties00.smalt.audio.mp3.Layer3MultichannelStreams.RS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Layer III multichannel extension on synthetic streams of {@link Layer3MultichannelStreams}: because the
 * filterbanks are linear, an output channel must equal the plain stereo decoding of the spectral lines the standard
 * says it holds, or the same combination of plain decodings when it is composite coded or dematrixed. The real encoder's
 * use of region counts, the bit reservoir and the extension frame is what {@link Mp2MultichannelConformanceTest} set 23
 * pins.
 */
class Layer3MultichannelTest {
    private static final int FRAMES = 6;

    private static final int LINES = 80;

    /** The rounding of the float filterbanks over the combinations here; the signal is thousands of times larger. */
    private static final float EPSILON = 2e-5f;

    private static final double ALPHA_0 = 1 / (1 + Math.sqrt(2));

    private static final double ALPHA_1 = 1 / (1.5 + 0.5 * Math.sqrt(2));

    private static final double BETA = 1 / Math.sqrt(2);

    private static final int[] CHANNELS = {L, R, C, LS, RS};

    /** The random lines of a test: base channels at numbers 0 and 1, then the transmission channels. */
    private static int[][][][] lines(long seed, int count, Configuration configuration) {
        var random = new SplittableRandom(seed);
        var out = new int[FRAMES][2][7][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                for (var ch = 0; ch < 7; ch++) {
                    out[frame][granule][ch] = Layer3MultichannelStreams.randomLines(random, count);
                }
            }
        }
        return out;
    }

    private static AudioTrack track() {
        return AudioTrack.builder().id(1).codec(Codec.MP3).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .layout(ChannelLayout.STEREO).build();
    }

    /** Decodes packets, with the flush, and gathers the output by speaker. */
    private static Map<Speaker, float[]> decode(List<byte[]> packets, Mp3DecoderOptions options,
                                                List<ChannelLayout> layouts) {
        var decoder = new Mp3Decoder(track(), options);
        var output = new LinkedHashMap<Speaker, AudioPipeline.FloatList>();
        Consumer<AudioBuffer> sink = buffer -> {
            if (!layouts.contains(buffer.layout())) {
                layouts.add(buffer.layout());
            }
            for (var c = 0; c < buffer.channels(); c++) {
                var list = output.computeIfAbsent(buffer.layout().speakers().get(c),
                        ignored -> new AudioPipeline.FloatList());
                for (var i = 0; i < buffer.frames(); i++) {
                    list.add(buffer.plane(c)[i]);
                }
            }
        };
        for (var i = 0; i < packets.size(); i++) {
            decoder.decode(new Packet(1, packets.get(i), i * 1152L, i * 1152L, 1152, true), sink);
        }
        decoder.flush(sink);
        var result = new LinkedHashMap<Speaker, float[]>();
        output.forEach((speaker, list) -> result.put(speaker, list.toArray()));
        return result;
    }

    /** Decodes the plain stereo stream of two channels' lines, each masked per granule, into its two planes. */
    private static float[][] plainPair(int[][][][] lines, int first, int second, Configuration configuration,
                                       GroupMask mask) {
        var frames = new int[FRAMES][2][2][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                var type = configuration.blockTypes.of(frame, granule);
                frames[frame][granule][0] = maskedLines(lines[frame][granule][first], type, mask, granule, first);
                frames[frame][granule][1] = maskedLines(lines[frame][granule][second], type, mask, granule, second);
            }
        }
        var plain = new Configuration();
        plain.blockTypes = configuration.blockTypes;
        plain.globalGain = configuration.globalGain;
        plain.compress = configuration.compress;
        plain.preflag = configuration.preflag;
        plain.scalefacScale = configuration.scalefacScale;
        plain.scfsi = configuration.scfsi;
        plain.scalefactorKey = configuration.scalefactorKey;
        plain.subblockGain = configuration.subblockGain;
        var packets = Layer3MultichannelStreams.plain(frames, plain);
        var output = decode(packets, Mp3DecoderOptions.builder().multichannel(false).build(), new ArrayList<>());
        return new float[][]{output.get(Speaker.FRONT_LEFT), output.get(Speaker.FRONT_RIGHT)};
    }

    private static int[] maskedLines(int[] lines, int type, GroupMask mask, int granule, int ch) {
        return Layer3MultichannelStreams.masked(lines, type, group -> mask.keeps(granule, ch, group));
    }

    /** Which band groups of which channel a plain decoding keeps. */
    private interface GroupMask {
        boolean keeps(int granule, int ch, int group);
    }

    /**
     * The plain decodings of one set of lines, per channel number.
     *
     * @return the plane of every channel number, 0 and 1 the base channels
     */
    private static Map<Integer, float[]> planes(int[][][][] lines, Configuration configuration, GroupMask mask) {
        var out = new HashMap<Integer, float[]>();
        var base = plainPair(lines, 0, 1, configuration, mask);
        out.put(0, base[0]);
        out.put(1, base[1]);
        var front = plainPair(lines, L, R, configuration, mask);
        out.put(L, front[0]);
        out.put(R, front[1]);
        var middle = plainPair(lines, C, LS, configuration, mask);
        out.put(C, middle[0]);
        out.put(LS, middle[1]);
        var rear = plainPair(lines, RS, RS, configuration, mask);
        out.put(RS, rear[0]);
        return out;
    }

    /** Builds the stream of a configuration over random lines. */
    private static List<byte[]> stream(Configuration configuration, int[][][][] lines) {
        var base = new int[FRAMES][2][2][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                base[frame][granule][0] = lines[frame][granule][0];
                base[frame][granule][1] = lines[frame][granule][1];
            }
        }
        return Layer3MultichannelStreams.build(configuration, base, lines, null);
    }

    private static void assertPlane(String what, float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, what + " length");
        var energy = 0.0;
        for (var i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - actual[i]) > EPSILON) {
                throw new AssertionError(what + " differs at sample " + i + ": " + expected[i] + " vs " + actual[i]);
            }
            energy += expected[i] * expected[i];
        }
        assertTrue(energy > 1e-3, what + " is silent, which proves nothing: " + energy);
    }

    private static float[] combine(double a, float[] x, double b, float[] y, double c, float[] z) {
        var out = new float[x.length];
        for (var i = 0; i < out.length; i++) {
            out[i] = (float) (a * x[i] + b * y[i] + c * z[i]);
        }
        return out;
    }

    private static Speaker speaker(int ch) {
        return switch (ch) {
            case L -> Speaker.FRONT_LEFT;
            case R -> Speaker.FRONT_RIGHT;
            case C -> Speaker.FRONT_CENTER;
            case LS -> Speaker.BACK_LEFT;
            default -> Speaker.BACK_RIGHT;
        };
    }

    @ParameterizedTest(name = "block types {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("a channel in its own transmission channel decodes as a plain stereo channel with the same lines")
    void transmitted(int type) {
        var configuration = new Configuration().defaults();
        configuration.blockTypes = (frame, granule) -> type;
        var lines = lines(11 + type, LINES, configuration);
        var layouts = new ArrayList<ChannelLayout>();
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), layouts);
        assertEquals(1, layouts.size());
        assertEquals(5, layouts.getFirst().channels());
        var planes = planes(lines, configuration, (granule, ch, group) -> true);
        for (var ch : CHANNELS) {
            assertPlane("channel " + ch, planes.get(ch), output.get(speaker(ch)));
        }
    }

    @Test
    @DisplayName("block types change across granules as the windows switch")
    void switching() {
        var configuration = new Configuration().defaults();
        int[] cycle = {0, 1, 2, 3};
        configuration.blockTypes = (frame, granule) -> cycle[(frame * 2 + granule) % 4];
        var lines = lines(17, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var planes = planes(lines, configuration, (granule, ch, group) -> true);
        for (var ch : CHANNELS) {
            assertPlane("channel " + ch, planes.get(ch), output.get(speaker(ch)));
        }
    }

    /** Runs a dematrixing test: the named channels are dematrixed, the others transmitted. */
    private static Map<Integer, float[]> dematrixed(Configuration configuration, long seed, Map<Speaker, float[]>[] out,
                                                    int... channels) {
        configuration.defaults();
        for (var ch = L; ch <= RS; ch++) {
            if (!configuration.channelPresent(ch)) {
                configuration.tcPresent[ch] = false;
            }
        }
        for (var ch : channels) {
            configuration.dematrixed(ch);
        }
        var lines = lines(seed, LINES, configuration);
        out[0] = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        return planes(lines, configuration, (granule, ch, group) -> true);
    }

    @Test
    @DisplayName("3/2: each single channel and each legal pair recover from the procedure 0 downmix")
    void dematrix32() {
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var a = 1 / ALPHA_0;

        var p = dematrixed(new Configuration(), 21, out, L);
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(C), -BETA, p.get(LS)), out[0].get(speaker(L)));

        p = dematrixed(new Configuration(), 22, out, R);
        assertPlane("R", combine(a, p.get(1), -BETA, p.get(C), -BETA, p.get(RS)), out[0].get(speaker(R)));

        p = dematrixed(new Configuration(), 23, out, LS, RS);
        assertPlane("LS", combine(a / BETA, p.get(0), -1 / BETA, p.get(L), -1, p.get(C)), out[0].get(speaker(LS)));
        assertPlane("RS", combine(a / BETA, p.get(1), -1 / BETA, p.get(R), -1, p.get(C)), out[0].get(speaker(RS)));

        p = dematrixed(new Configuration(), 24, out, L, R);
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(C), -BETA, p.get(LS)), out[0].get(speaker(L)));
        assertPlane("R", combine(a, p.get(1), -BETA, p.get(C), -BETA, p.get(RS)), out[0].get(speaker(R)));

        p = dematrixed(new Configuration(), 25, out, C);
        assertPlane("C", combine(a / BETA, p.get(0), -1 / BETA, p.get(L), -1, p.get(LS)), out[0].get(speaker(C)));

        // left and center: the center comes from the Ro equation, the left then from Lo with that center
        p = dematrixed(new Configuration(), 26, out, L, C);
        var center = combine(a / BETA, p.get(1), -1 / BETA, p.get(R), -1, p.get(RS));
        assertPlane("C", center, out[0].get(speaker(C)));
        assertPlane("L", combine(a, p.get(0), -BETA, center, -BETA, p.get(LS)), out[0].get(speaker(L)));

        // center and left surround: the same order
        p = dematrixed(new Configuration(), 27, out, C, LS);
        center = combine(a / BETA, p.get(1), -1 / BETA, p.get(R), -1, p.get(RS));
        assertPlane("C", center, out[0].get(speaker(C)));
        assertPlane("LS", combine(a / BETA, p.get(0), -1 / BETA, p.get(L), -1, center), out[0].get(speaker(LS)));
    }

    @Test
    @DisplayName("procedure 1 weights the surround channels by one half")
    void procedure1() {
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var a = 1 / ALPHA_1;
        var configuration = new Configuration();
        configuration.procedure = 1;
        var p = dematrixed(configuration, 31, out, L, RS);
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(C), -0.5, p.get(LS)), out[0].get(speaker(L)));
        assertPlane("RS", combine(2 * a, p.get(1), -2, p.get(R), -2 * BETA, p.get(C)), out[0].get(speaker(RS)));
    }

    @Test
    @DisplayName("procedure 2 puts the mean surround into the equations with opposite signs")
    void procedure2() {
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var a = 1 / ALPHA_0;
        var configuration = new Configuration();
        configuration.procedure = 2;
        var p = dematrixed(configuration, 32, out, L, R);
        var mean = combine(0.5, p.get(LS), 0.5, p.get(RS), 0, p.get(RS));
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(C), BETA, mean), out[0].get(speaker(L)));
        assertPlane("R", combine(a, p.get(1), -BETA, p.get(C), -BETA, mean), out[0].get(speaker(R)));

        var mono = new Configuration();
        mono.procedure = 2;
        mono.surround = 1;
        p = dematrixed(mono, 33, out, L);
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(C), BETA, p.get(LS)), out[0].get(speaker(L)));
        var output = out[0];
        assertPlane("S", p.get(LS), output.get(Speaker.BACK_CENTER));
    }

    @Test
    @DisplayName("procedure 3 plays the base channels as left and right")
    void procedure3() {
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var configuration = new Configuration();
        configuration.procedure = 3;
        var p = dematrixed(configuration, 34, out, L, R);
        assertPlane("L", p.get(0), out[0].get(speaker(L)));
        assertPlane("R", p.get(1), out[0].get(speaker(R)));
        assertPlane("C", p.get(C), out[0].get(speaker(C)));
    }

    @Test
    @DisplayName("3/1, 2/2, 2/1 and 3/0 dematrix with the absent channels as zero")
    void otherConfigurations() {
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var a = 1 / ALPHA_0;
        var mono = new Configuration();
        mono.surround = 1;
        var p = dematrixed(mono, 41, out, LS);
        assertPlane("S", combine(a / BETA, p.get(0), -1 / BETA, p.get(L), -1, p.get(C)),
                out[0].get(Speaker.BACK_CENTER));

        var quad = new Configuration();
        quad.center = 0;
        p = dematrixed(quad, 42, out, L);
        assertFalse(out[0].containsKey(Speaker.FRONT_CENTER));
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(LS), 0, p.get(LS)), out[0].get(speaker(L)));
        assertPlane("R", p.get(R), out[0].get(speaker(R)));
        assertPlane("LS", p.get(LS), out[0].get(speaker(LS)));
        assertPlane("RS", p.get(RS), out[0].get(speaker(RS)));

        var triple = new Configuration();
        triple.center = 0;
        triple.surround = 1;
        p = dematrixed(triple, 43, out, R);
        assertPlane("R", combine(a, p.get(1), -BETA, p.get(LS), 0, p.get(LS)), out[0].get(speaker(R)));
        assertPlane("L", p.get(L), out[0].get(speaker(L)));
        assertPlane("S", p.get(LS), out[0].get(Speaker.BACK_CENTER));

        var front = new Configuration();
        front.surround = 0;
        p = dematrixed(front, 44, out, C);
        assertPlane("C", combine(a / BETA, p.get(0), -1 / BETA, p.get(L), 0, p.get(L)), out[0].get(speaker(C)));
        assertPlane("L", p.get(L), out[0].get(speaker(L)));
        assertPlane("R", p.get(R), out[0].get(speaker(R)));
    }

    @Test
    @DisplayName("a phantom center keeps only the lines the standard gives it, before the other channel recovers")
    void phantom() {
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var configuration = new Configuration();
        configuration.center = 3;
        configuration.defaults();
        configuration.dematrixed(C);
        configuration.dematrixed(L);
        // sparse lines reaching past line 230, so that the main data fits
        var lines = lines(51, 300, configuration);
        var limited = new int[FRAMES][2][7][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                for (var ch = 0; ch < 7; ch++) {
                    var values = lines[frame][granule][ch];
                    for (var i = 0; i < 576; i++) {
                        if (i % 6 != 0) {
                            values[i] = 0;
                        }
                    }
                    limited[frame][granule][ch] = java.util.Arrays.copyOf(values, 576);
                    java.util.Arrays.fill(limited[frame][granule][ch], 230, 576, 0);
                }
            }
        }
        out[0] = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var a = 1 / ALPHA_0;
        var all = planes(lines, configuration, (granule, ch, group) -> true);
        var low = planes(limited, configuration, (granule, ch, group) -> true);
        // the center comes from the Ro equation over the lines it keeps, and the left from Lo with that center
        var center = combine(a / BETA, low.get(1), -1 / BETA, low.get(R), -1, low.get(RS));
        assertPlane("C", center, out[0].get(speaker(C)));
        assertPlane("L", combine(a, all.get(0), -BETA, center, -BETA, all.get(LS)), out[0].get(speaker(L)));
    }

    @Test
    @DisplayName("dematrix_select names the channels of the first band groups, the lists those above")
    void dematrixSelect() {
        var configuration = new Configuration().defaults();
        configuration.dematrixLength = 5;
        // L and C dematrixed in the first three band groups, R and LS in the next two
        configuration.dematrixSelect = new int[]{1 | 4, 1 | 4, 1 | 4, 2 | 8, 2 | 8};
        var lines = lines(61, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var low = planes(lines, configuration, (granule, ch, group) -> group < 3);
        var middle = planes(lines, configuration, (granule, ch, group) -> group >= 3 && group < 5);
        var high = planes(lines, configuration, (granule, ch, group) -> group >= 5);
        var a = 1 / ALPHA_0;
        var centerLow = combine(a / BETA, low.get(1), -1 / BETA, low.get(R), -1, low.get(RS));
        var leftLow = combine(a, low.get(0), -BETA, centerLow, -BETA, low.get(LS));
        var rightMiddle = combine(a, middle.get(1), -BETA, middle.get(C), -BETA, middle.get(RS));
        var surroundMiddle = combine(a / BETA, middle.get(0), -1 / BETA, middle.get(L), -1, middle.get(C));
        assertPlane("L", combine(1, leftLow, 1, middle.get(L), 1, high.get(L)), output.get(speaker(L)));
        assertPlane("R", combine(1, low.get(R), 1, rightMiddle, 1, high.get(R)), output.get(speaker(R)));
        assertPlane("C", combine(1, centerLow, 1, middle.get(C), 1, high.get(C)), output.get(speaker(C)));
        assertPlane("LS", combine(1, low.get(LS), 1, surroundMiddle, 1, high.get(LS)), output.get(speaker(LS)));
    }

    @ParameterizedTest(name = "range {0}, scale {1}, attenuation {2}, gain {3}")
    @CsvSource({
            "0, 0, 1, 0.8408964", // 2^(-1/4)
            "0, 1, 1, 0.70710678", // 2^(-1/2)
            "1, 1, 3, 0.35355339", // below three quarters of 8
            "0, 1, 3, 1.41421356", // 3 of 4 amplifies: a0^(3 - 4)
            "3, 0, 31, 1.18920712", // 31 of 32: a0^(31 - 32)
    })
    @DisplayName("a composite coded segment scales its source by the attenuation")
    void composite(int range, int scale, int attenuation, double gain) {
        var configuration = new Configuration().defaults();
        // the right surround is carried by the left surround's transmission channel in every band group
        var attenuations = new int[15];
        java.util.Arrays.fill(attenuations, attenuation);
        configuration.list(RS, new SegmentList(range, scale, List.of(new Segment(15, LS, attenuations))));
        configuration.tcPresent[RS] = false;
        var lines = lines(71, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var planes = planes(lines, configuration, (granule, ch, group) -> true);
        assertPlane("LS", planes.get(LS), output.get(speaker(LS)));
        assertPlane("RS", combine(gain, planes.get(LS), 0, planes.get(LS), 0, planes.get(LS)), output.get(speaker(RS)));
    }

    @Test
    @DisplayName("a transmission channel codes only the band groups a segment refers to, and a list may stop early")
    void omittedBands() {
        var configuration = new Configuration().defaults();
        configuration.list(L, new SegmentList(0, 0, List.of(new Segment(2, L), new Segment(0, L))));
        configuration.list(R, new SegmentList(0, 0, List.of(new Segment(1, 7), new Segment(2, R), new Segment(12, 7))));
        var lines = lines(81, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var l = planes(lines, configuration, (granule, ch, group) -> group < 2);
        assertPlane("L", l.get(L), output.get(speaker(L)));
        var kept = planes(lines, configuration, (granule, ch, group) -> group == 1 || group == 2);
        var rest = planes(lines, configuration, (granule, ch, group) -> group != 1 && group != 2);
        var a = 1 / ALPHA_0;
        var dematrixed = combine(a, rest.get(1), -BETA, rest.get(C), -BETA, rest.get(RS));
        assertPlane("R", combine(1, kept.get(R), 1, dematrixed, 0, dematrixed), output.get(speaker(R)));
    }

    @Test
    @DisplayName("the second granule may repeat the first one's segment list")
    void repeatedList() {
        var configuration = new Configuration().defaults();
        configuration.repeatLists = true;
        configuration.list(L, new SegmentList(0, 0, List.of(new Segment(4, L), new Segment(0, L))));
        var lines = lines(83, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var planes = planes(lines, configuration, (granule, ch, group) -> group < 4);
        assertPlane("L", planes.get(L), output.get(speaker(L)));
    }

    @Test
    @DisplayName("scalefactors of the coded bands apply as in a plain stream; scfsi repeats those of the first granule")
    void scalefactors() {
        for (var scfsi : new int[]{0, 15, 5}) {
            var configuration = new Configuration().defaults();
            configuration.compress = 15;
            configuration.preflag = 1;
            configuration.scalefacScale = 1;
            configuration.scfsi = scfsi;
            configuration.scalefactorKey = 1;
            // the left channel omits the band groups above the seventh, whose scalefactors it does not send
            configuration.list(L, new SegmentList(0, 0, List.of(new Segment(7, L), new Segment(0, L))));
            var lines = lines(91 + scfsi, LINES, configuration);
            var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
            var left = plainPair(lines, L, R, configuration, (granule, ch, group) -> ch != L || group < 7);
            assertPlane("L, scfsi " + scfsi, left[0], output.get(speaker(L)));
            assertPlane("R, scfsi " + scfsi, left[1], output.get(speaker(R)));
        }
    }

    @Test
    @DisplayName("short blocks apply scalefactors and subblock gains as in a plain stream, also over omitted band groups")
    void shortScalefactors() {
        var configuration = new Configuration().defaults();
        configuration.blockTypes = (frame, granule) -> 2;
        configuration.compress = 15;
        configuration.scalefacScale = 1;
        configuration.scalefactorKey = 1;
        configuration.subblockGain = 1 << 6 | 2 << 3 | 3;
        configuration.globalGain = 168;
        configuration.list(L, new SegmentList(0, 0, List.of(new Segment(7, L), new Segment(0, L))));
        var lines = lines(95, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var front = plainPair(lines, L, R, configuration, (granule, ch, group) -> ch != L || group < 7);
        assertPlane("L", front[0], output.get(speaker(L)));
        assertPlane("R", front[1], output.get(speaker(R)));
    }

    @Test
    @DisplayName("a predictor adds its coefficient times the base channel, in the band groups it applies to")
    void prediction() {
        var configuration = new Configuration().defaults();
        var predictors = new int[15][][];
        predictors[0] = new int[7][2];
        for (var row : predictors[0]) {
            java.util.Arrays.fill(row, -1);
        }
        predictors[0][L][0] = 4;
        predictors[0][C][1] = 7;
        configuration.predictors = predictors;
        var lines = lines(101, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var all = planes(lines, configuration, (granule, ch, group) -> true);
        var first = planes(lines, configuration, (granule, ch, group) -> group == 0);
        assertPlane("L", combine(1, all.get(L), 1.15831, first.get(0), 0, all.get(L)), output.get(speaker(L)));
        assertPlane("C", combine(1, all.get(C), 5, first.get(1), 0, all.get(C)), output.get(speaker(C)));
        assertPlane("R", all.get(R), output.get(speaker(R)));
    }

    @Test
    @DisplayName("matrix attenuation scales a carrier's band groups only in the dematrixing equations")
    void matrixAttenuation() {
        var configuration = new Configuration().defaults();
        configuration.dematrixed(LS);
        var attenuations = new int[15];
        java.util.Arrays.fill(attenuations, 2);
        // the center shares the left channel's transmission channel at gain 2^(-1/2)
        configuration.list(C, new SegmentList(0, 0, List.of(new Segment(15, L, attenuations))));
        configuration.tcPresent[C] = false;
        var matrix = new int[2][7][15][2];
        for (var granule : matrix) {
            for (var group : granule[L]) {
                group[0] = 4;
                group[1] = 8;
            }
        }
        configuration.matrix = matrix;
        var lines = lines(111, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var p = planes(lines, configuration, (granule, ch, group) -> true);
        var amount = Math.sqrt(0.5);
        assertPlane("L", p.get(L), output.get(speaker(L)));
        assertPlane("C", combine(amount, p.get(L), 0, p.get(L), 0, p.get(L)), output.get(speaker(C)));
        // Lo / alpha = ml L + beta ml C + gamma LS, with ml = 2^(-4/4)
        var a = 1 / ALPHA_0;
        var left = 0.5 * (1 + BETA * amount) / BETA;
        assertPlane("LS", combine(a / BETA, p.get(0), -left, p.get(L), 0, p.get(L)), output.get(speaker(LS)));
    }

    @Test
    @DisplayName("the low-frequency enhancement channel is the windowed short transform of both granules' lines")
    void lfe() {
        var configuration = new Configuration().defaults();
        configuration.lfe = true;
        configuration.surround = 2;
        var lines = lines(121, LINES, configuration);
        var lfe = new int[FRAMES][2][6];
        for (var frame = 0; frame < FRAMES; frame++) {
            lfe[frame][0][0] = 3;
            lfe[frame][1][0] = -2;
            lfe[frame][0][1] = 1;
            lfe[frame][1][2] = 2;
        }
        var base = new int[FRAMES][2][2][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                base[frame][granule][0] = lines[frame][granule][0];
                base[frame][granule][1] = lines[frame][granule][1];
            }
        }
        var layouts = new ArrayList<ChannelLayout>();
        var output = decode(Layer3MultichannelStreams.build(configuration, base, lines, lfe),
                Mp3DecoderOptions.defaults(), layouts);
        assertEquals(ChannelLayout.SURROUND_5_1.channels(), layouts.getFirst().channels());
        var plane = output.get(Speaker.LOW_FREQUENCY);
        assertEquals(FRAMES * 1152, plane.length);
        var scale = Math.pow(2, (configuration.globalGain + 30 - 210) / 4.0);
        var overlap = new double[6];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                var y = new double[12];
                for (var i = 0; i < 12; i++) {
                    for (var k = 0; k < 6; k++) {
                        var value = lfe[frame][granule][k];
                        var line = Math.signum(value) * Math.pow(Math.abs(value), 4.0 / 3) * scale;
                        y[i] += line * Math.cos(Math.PI / 24 * (2 * i + 1 + 6) * (2 * k + 1));
                    }
                    y[i] *= Math.sin(Math.PI / 12 * (i + 0.5));
                }
                for (var i = 0; i < 6; i++) {
                    var expected = y[i] + overlap[i];
                    overlap[i] = y[i + 6];
                    var actual = plane[(frame * 12 + granule * 6 + i) * 96 + 95];
                    assertEquals(expected, actual, 1e-5, "frame " + frame + " granule " + granule + " sample " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("the extension's own reservoir and an extension frame carry the main data past the base frame")
    void extensionFrame() {
        var simple = new Configuration().defaults();
        var lines = lines(131, 200, simple);
        var carried = new Configuration().defaults();
        carried.extensionFrame = true;
        var output = decode(stream(carried, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var planes = planes(lines, carried, (granule, ch, group) -> true);
        for (var ch : CHANNELS) {
            assertPlane("channel " + ch, planes.get(ch), output.get(speaker(ch)));
        }
    }

    @Test
    @DisplayName("when the base channels use their bit reservoir, the next frame tells where the extension frame continues")
    void baseReservoir() {
        var configuration = new Configuration().defaults();
        configuration.extensionFrame = true;
        configuration.reservoir = 40;
        var lines = lines(141, 200, configuration);
        var layouts = new ArrayList<ChannelLayout>();
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), layouts);
        assertEquals(1, layouts.size());
        var planes = planes(lines, configuration, (granule, ch, group) -> true);
        for (var ch : CHANNELS) {
            assertPlane("channel " + ch, planes.get(ch), output.get(speaker(ch)));
        }
    }

    @ParameterizedTest(name = "base reservoir {0}, extension frame {1}")
    @CsvSource({"0, false", "40, false", "40, true"})
    @DisplayName("mc_data_begin counts only the extension's own bytes, whatever the frame's last bytes hold for the next frame")
    void extensionReservoir(int reservoir, boolean extensionFrame) {
        var configuration = new Configuration().defaults();
        configuration.reservoir = reservoir;
        configuration.extensionFrame = extensionFrame;
        configuration.mcReservoir = true;
        var lines = lines(143 + reservoir, LINES, configuration);
        var output = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), new ArrayList<>());
        var planes = planes(lines, configuration, (granule, ch, group) -> true);
        for (var ch : CHANNELS) {
            assertPlane("channel " + ch, planes.get(ch), output.get(speaker(ch)));
        }
    }

    @Test
    @DisplayName("a channel that is wholly its base channel keeps the base channel's mixed blocks")
    void mixedBase() {
        var configuration = new Configuration();
        configuration.procedure = 3;
        configuration.blockTypes = (frame, granule) -> 2;
        configuration.baseMixed = true;
        @SuppressWarnings("unchecked") Map<Speaker, float[]>[] out = new Map[1];
        var p = dematrixed(configuration, 145, out, L, R);
        var lines = lines(145, LINES, configuration);
        var base = new int[FRAMES][2][2][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                base[frame][granule][0] = lines[frame][granule][0];
                base[frame][granule][1] = lines[frame][granule][1];
            }
        }
        var plain = new Configuration();
        plain.blockTypes = configuration.blockTypes;
        plain.globalGain = configuration.globalGain;
        plain.baseMixed = true;
        var mixed = decode(Layer3MultichannelStreams.plain(base, plain),
                Mp3DecoderOptions.builder().multichannel(false).build(), new ArrayList<>());
        assertPlane("L", mixed.get(Speaker.FRONT_LEFT), out[0].get(speaker(L)));
        assertPlane("R", mixed.get(Speaker.FRONT_RIGHT), out[0].get(speaker(R)));
        assertPlane("C", p.get(C), out[0].get(speaker(C)));
    }

    @Test
    @DisplayName("a single channel base plays as mono and carries a second stereo program")
    void monoBaseSecondStereo() {
        var configuration = new Configuration();
        configuration.monoBase = true;
        configuration.center = 0;
        configuration.surround = 3;
        configuration.defaults();
        configuration.tcPresent[L] = false;
        configuration.tcPresent[R] = false;
        var lines = lines(147, LINES, configuration);
        var packets = stream(configuration, lines);
        var layouts = new ArrayList<ChannelLayout>();
        var main = decode(packets, Mp3DecoderOptions.defaults(), layouts);
        assertEquals(List.of(ChannelLayout.MONO), layouts);
        var p = planes(lines, configuration, (granule, ch, group) -> true);
        assertPlane("base", p.get(0), main.get(ChannelLayout.MONO.speakers().getFirst()));
        var second = decode(packets, Mp3DecoderOptions.builder().program(Mp2Program.SECOND_STEREO).build(),
                new ArrayList<>());
        assertPlane("L2", p.get(LS), second.get(Speaker.FRONT_LEFT));
        assertPlane("R2", p.get(RS), second.get(Speaker.FRONT_RIGHT));
    }

    @Test
    @DisplayName("a single channel base carries multilingual channels")
    void monoBaseMultilingual() {
        var configuration = new Configuration();
        configuration.monoBase = true;
        configuration.center = 0;
        configuration.surround = 0;
        configuration.multilingual = 2;
        configuration.defaults();
        configuration.tcPresent[L] = false;
        configuration.tcPresent[R] = false;
        var random = new SplittableRandom(148);
        var extra = new int[FRAMES][2][2][];
        var mirror = new int[FRAMES][2][7][];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                for (var ch = 0; ch < 2; ch++) {
                    extra[frame][granule][ch] = Layer3MultichannelStreams.randomLines(random, 40);
                    mirror[frame][granule][ch] = extra[frame][granule][ch];
                }
            }
        }
        configuration.multilingualLines = extra;
        var lines = lines(149, 40, configuration);
        var packets = stream(configuration, lines);
        var main = decode(packets, Mp3DecoderOptions.defaults(), new ArrayList<>());
        var p = planes(lines, configuration, (granule, ch, group) -> true);
        assertPlane("base", p.get(0), main.get(ChannelLayout.MONO.speakers().getFirst()));
        var expected = plainPair(mirror, 0, 1, configuration, (granule, ch, group) -> true);
        for (var language = 1; language <= 2; language++) {
            var alone = decode(packets, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                    .multilingualChannel(language).build(), new ArrayList<>());
            assertPlane("language " + language, expected[language - 1], alone.get(Speaker.FRONT_CENTER));
        }
    }

    @Test
    @DisplayName("a second stereo program stays out of the downmix and can be selected")
    void secondStereo() {
        var configuration = new Configuration().defaults();
        configuration.surround = 3;
        configuration.dematrixed(L);
        var lines = lines(151, LINES, configuration);
        var layouts = new ArrayList<ChannelLayout>();
        var main = decode(stream(configuration, lines), Mp3DecoderOptions.defaults(), layouts);
        assertEquals(3, layouts.getFirst().channels());
        var p = planes(lines, configuration, (granule, ch, group) -> true);
        var a = 1 / ALPHA_0;
        assertPlane("L", combine(a, p.get(0), -BETA, p.get(C), 0, p.get(C)), main.get(speaker(L)));
        var second = decode(stream(configuration, lines),
                Mp3DecoderOptions.builder().program(Mp2Program.SECOND_STEREO).build(), new ArrayList<>());
        assertPlane("L2", p.get(LS), second.get(Speaker.FRONT_LEFT));
        assertPlane("R2", p.get(RS), second.get(Speaker.FRONT_RIGHT));
    }

    @Test
    @DisplayName("a frame whose extension fails its CRC plays its base channels in the multichannel layout")
    void damaged() {
        var configuration = new Configuration().defaults();
        var lines = lines(161, LINES, configuration);
        var packets = new ArrayList<>(stream(configuration, lines));
        // a bit of the side information of the extension of frame 3, past the base channels' Huffman data
        var damaged = packets.get(3).clone();
        damaged[extensionHeader(damaged) + 5] ^= 0x10;
        packets.set(3, damaged);
        var layouts = new ArrayList<ChannelLayout>();
        var output = decode(packets, Mp3DecoderOptions.defaults(), layouts);
        assertEquals(1, layouts.size(), "the layout changed: " + layouts);
        var base = plainPair(lines, 0, 1, configuration, (granule, ch, group) -> true);
        var plain = planes(lines, configuration, (granule, ch, group) -> true);
        var left = output.get(speaker(L));
        var surround = output.get(speaker(RS));
        assertEquals(FRAMES * 1152, left.length);
        assertEquals(FRAMES * 1152, surround.length);
        // after the damaged frame's first samples, whose filterbank history is the frame before, it plays Lo
        for (var i = 4 * 1152 - 64; i < 4 * 1152; i++) {
            assertEquals(base[0][i], left[i], EPSILON, "Lo at sample " + i);
        }
        // the last frame decodes again once the extension's reservoir has refilled; its second half has no history left
        for (var i = 6 * 1152 - 64; i < 6 * 1152; i++) {
            assertEquals(plain.get(RS)[i], surround[i], EPSILON, "RS at sample " + i);
            assertEquals(plain.get(L)[i], left[i], EPSILON, "L at sample " + i);
        }
    }

    /** Returns the index of the extension header's first whole byte in a frame without reservoir. */
    private static int extensionHeader(byte[] frame) {
        var bs = new BitReader();
        bs.init(frame, 4, 32);
        bs.read(20);
        var bits = 0;
        for (var granule = 0; granule < 4; granule++) {
            bits += bs.read(12);
            bs.read(47);
        }
        return 36 + (bits + 7) / 8;
    }

    @ParameterizedTest(name = "half rate {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("multilingual channels decode as plain channels with the same lines, selected together or alone")
    void multilingual(boolean halfRate) {
        var configuration = new Configuration().defaults();
        configuration.surround = 0;
        configuration.multilingual = 3;
        configuration.multilingualHalfRate = halfRate;
        var random = new SplittableRandom(201);
        var extra = new int[FRAMES][2][3][];
        for (var frame : extra) {
            for (var granule : frame) {
                for (var ch = 0; ch < 3; ch++) {
                    granule[ch] = Layer3MultichannelStreams.randomLines(random, 40);
                }
            }
        }
        configuration.multilingualLines = extra;
        var lines = lines(202, 40, configuration);
        var packets = stream(configuration, lines);
        var main = decode(packets, Mp3DecoderOptions.defaults(), new ArrayList<>());
        assertEquals(FRAMES * 1152, main.get(speaker(C)).length, "the main program");

        float[][] expected;
        if (halfRate) {
            var pairs = new int[FRAMES][2][];
            var third = new int[FRAMES][2][];
            for (var frame = 0; frame < FRAMES; frame++) {
                pairs[frame] = new int[][]{extra[frame][0][0], extra[frame][0][1]};
                third[frame] = new int[][]{extra[frame][0][2], extra[frame][0][2]};
            }
            var off = Mp3DecoderOptions.builder().multichannel(false).build();
            var first = decode(Layer3MultichannelStreams.plainHalfRate(pairs, configuration.globalGain), off,
                    new ArrayList<>());
            var last = decode(Layer3MultichannelStreams.plainHalfRate(third, configuration.globalGain), off,
                    new ArrayList<>());
            expected = new float[][]{first.get(Speaker.FRONT_LEFT), first.get(Speaker.FRONT_RIGHT),
                    last.get(Speaker.FRONT_LEFT)};
        } else {
            var mirror = new int[FRAMES][2][7][];
            for (var frame = 0; frame < FRAMES; frame++) {
                for (var granule = 0; granule < 2; granule++) {
                    for (var ch = 0; ch < 3; ch++) {
                        mirror[frame][granule][ch] = extra[frame][granule][ch];
                    }
                }
            }
            var first = plainPair(mirror, 0, 1, configuration, (granule, ch, group) -> true);
            var last = plainPair(mirror, 2, 2, configuration, (granule, ch, group) -> true);
            expected = new float[][]{first[0], first[1], last[0]};
        }
        var all = new ArrayList<float[]>();
        var rates = new java.util.HashSet<Integer>();
        var decoder = new Mp3Decoder(track(), Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL).build());
        var lists = new AudioPipeline.FloatList[]{new AudioPipeline.FloatList(), new AudioPipeline.FloatList(),
                new AudioPipeline.FloatList()};
        for (var i = 0; i < packets.size(); i++) {
            decoder.decode(new Packet(1, packets.get(i), i * 1152L, i * 1152L, 1152, true), buffer -> {
                assertEquals(3, buffer.channels());
                rates.add(buffer.sampleRate());
                for (var c = 0; c < 3; c++) {
                    for (var s = 0; s < buffer.frames(); s++) {
                        lists[c].add(buffer.plane(c)[s]);
                    }
                }
            });
        }
        decoder.flush(buffer -> {
            for (var c = 0; c < 3; c++) {
                for (var s = 0; s < buffer.frames(); s++) {
                    lists[c].add(buffer.plane(c)[s]);
                }
            }
        });
        assertEquals(java.util.Set.of(halfRate ? 24000 : 48000), rates);
        for (var c = 0; c < 3; c++) {
            assertPlane("multilingual channel " + c, expected[c], lists[c].toArray());
        }
        var alone = decode(packets, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                .multilingualChannel(2).build(), new ArrayList<>());
        assertPlane("multilingual channel 2 alone", expected[1], alone.get(Speaker.FRONT_CENTER));
    }

    @Test
    @DisplayName("an ordinary stereo stream with ancillary data decodes frame by frame, as with the extension off")
    void plainStream() {
        var random = new SplittableRandom(171);
        var configuration = new Configuration();
        var lines = new int[32][2][2][];
        for (var frame : lines) {
            for (var granule : frame) {
                granule[0] = Layer3MultichannelStreams.randomLines(random, LINES);
                granule[1] = Layer3MultichannelStreams.randomLines(random, LINES);
            }
        }
        var packets = new ArrayList<byte[]>();
        for (var packet : Layer3MultichannelStreams.plain(lines, configuration)) {
            // the unused bytes of a frame are its ancillary data: noise here
            var noisy = packet.clone();
            var end = 960;
            for (var i = end - 200; i < end; i++) {
                noisy[i] = (byte) random.nextInt(256);
            }
            packets.add(noisy);
        }
        var decoder = new Mp3Decoder(track());
        for (var i = 0; i < packets.size(); i++) {
            var count = new int[1];
            decoder.decode(new Packet(1, packets.get(i), i * 1152L, i * 1152L, 1152, true), buffer -> {
                count[0]++;
                assertEquals(ChannelLayout.STEREO, buffer.layout());
                assertEquals(1152, buffer.frames());
            });
            assertEquals(1, count[0], "packet " + i + " did not decode at once");
        }
        var on = decode(packets, Mp3DecoderOptions.defaults(), new ArrayList<>());
        var off = decode(packets, Mp3DecoderOptions.builder().multichannel(false).build(), new ArrayList<>());
        org.junit.jupiter.api.Assertions.assertArrayEquals(off.get(Speaker.FRONT_LEFT), on.get(Speaker.FRONT_LEFT));
        org.junit.jupiter.api.Assertions.assertArrayEquals(off.get(Speaker.FRONT_RIGHT), on.get(Speaker.FRONT_RIGHT));
    }

    @Test
    @DisplayName("damaged and truncated extensions only ever fail as media errors")
    void robustness() {
        var random = new SplittableRandom(181);
        var configuration = new Configuration().defaults();
        configuration.lfe = true;
        configuration.extensionFrame = true;
        var lines = lines(182, 200, configuration);
        var base = new int[FRAMES][2][2][];
        var lfe = new int[FRAMES][2][6];
        for (var frame = 0; frame < FRAMES; frame++) {
            for (var granule = 0; granule < 2; granule++) {
                base[frame][granule][0] = lines[frame][granule][0];
                base[frame][granule][1] = lines[frame][granule][1];
            }
        }
        var clean = Layer3MultichannelStreams.build(configuration, base, lines, lfe);
        for (var round = 0; round < 400; round++) {
            var options = Mp3DecoderOptions.builder().crcCheck(round % 2 == 0).build();
            var decoder = new Mp3Decoder(track(), options);
            for (var i = 0; i < clean.size(); i++) {
                var bytes = clean.get(i).clone();
                for (var flips = random.nextInt(1 + round % 12); flips > 0; flips--) {
                    bytes[4 + random.nextInt(bytes.length - 4)] ^= (byte) (1 << random.nextInt(8));
                }
                if (round % 5 == 0) {
                    bytes = java.util.Arrays.copyOf(bytes, 40 + random.nextInt(bytes.length - 40));
                }
                var data = bytes;
                try {
                    decoder.decode(new Packet(1, data, i * 1152L, i * 1152L, 1152, true), buffer -> {
                        for (var c = 0; c < buffer.channels(); c++) {
                            for (var s = 0; s < buffer.frames(); s++) {
                                assertTrue(Float.isFinite(buffer.plane(c)[s]), "non-finite sample");
                            }
                        }
                    });
                } catch (MediaException expected) {
                    // a media error is the allowed failure
                }
            }
            decoder.flush(buffer -> {
            });
        }
    }
}
