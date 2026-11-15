package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers termination frames that end in a partial subsubframe ({@code PSC}, ETSI TS 102 114 clause 5.5.1). The
 * synthesis, the inverse ADPCM and the LFE interpolation are causal, so a stream that ends in a termination frame
 * decodes to the start of the same stream whose last frame is a normal frame with one more subsubframe holding the
 * partial samples first and silent LFE samples: Smalt must decode the two to the same samples, and ffmpeg, which
 * decodes the normal frame, is the oracle of the rest. Every coding tool of the core, the XXCH channels, X96 and XBR
 * run through it.
 */
final class DtsTerminationFrameDifferentialTest {
    /**
     * Two implementations of the same integer dequantization and float synthesis differ by float rounding only.
     */
    private static final Tolerance FLOAT = Tolerance.snr(90);

    /**
     * Smalt's X96 decoding and 64-band synthesis against ffmpeg's: about 80 dB apart on the normal frames of these
     * streams, the termination frame aside.
     */
    private static final Tolerance X96 = Tolerance.snr(75);

    /**
     * The core and its XBR residual each dequantized and summed in single precision, two roundings per sample.
     */
    private static final Tolerance XBR = Tolerance.snr(85);

    /**
     * The number of normal frames before the last frame.
     */
    private static final int LEADING = 3;

    static Stream<Arguments> tools() {
        var cases = Stream.concat(DtsSyntheticDifferentialTest.cases().filter(test -> test.subsubframes() > 1),
                Stream.of(new DtsSyntheticDifferentialTest.Case("adpcm prediction over two subsubframes", 9, 2, true,
                        false, false, false, 2, 2, DtsSyntheticDifferentialTest.Tool.ADPCM)));
        return cases.map(test -> Arguments.of(test, 1 + Math.floorMod(test.name().hashCode(), 7)));
    }

    @ParameterizedTest(name = "{0}, {1} partial samples")
    @MethodSource("tools")
    @DisplayName("a termination frame decodes as the start of the frame with its partial subsubframe made whole")
    void tools(DtsSyntheticDifferentialTest.Case test, int partial) throws IOException {
        var random = new SplittableRandom(test.name().hashCode() * 31L + partial);
        var leading = new ArrayList<CoreStreamWriter.Frame>();
        for (var f = 0; f < LEADING; f++) {
            leading.add(DtsSyntheticDifferentialTest.frame(test, random));
        }
        var whole = DtsSyntheticDifferentialTest.frame(test, random);
        var pair = terminate(whole, partial);
        assertEquivalent(test.name(), units(leading, pair[0]), units(leading, pair[1]), partial, FLOAT);
    }

    @ParameterizedTest(name = "{0} partial samples")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    @DisplayName("every partial sample count decodes, Huffman, block and plain coded, with prediction and LFE")
    void counts(int partial) throws IOException {
        for (var tool : List.of(DtsSyntheticDifferentialTest.Tool.HUFFMAN, DtsSyntheticDifferentialTest.Tool.BLOCK,
                DtsSyntheticDifferentialTest.Tool.PLAIN, DtsSyntheticDifferentialTest.Tool.ADPCM)) {
            var test = new DtsSyntheticDifferentialTest.Case(tool + " " + partial, 9, 1 + partial % 2, partial % 2 == 0,
                    partial % 3 == 0, false, false, 1 + partial % 3 + 1, partial % 3 == 2 ? 1 : 1 + partial % 2, tool);
            try {
                tools(test, partial);
            } catch (MediaException failure) {
                throw new AssertionError(test.name(), failure);
            }
        }
    }

    @ParameterizedTest(name = "block {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an X96 extension decodes its partial subsubframe like the core")
    void x96(boolean block) throws IOException {
        var random = new SplittableRandom(block ? 11 : 12);
        var leading = new ArrayList<CoreStreamWriter.Frame>();
        for (var f = 0; f < LEADING; f++) {
            leading.add(withX96(DtsFixedCoreDifferentialTest.plainFrame(random, 80, true, 6), 2, 0, block,
                    x96Codes(random)));
        }
        var codes = x96Codes(random);
        var whole = withX96(DtsFixedCoreDifferentialTest.plainFrame(random, 80, true, 6), 2, 0, block, codes);
        var pair = terminate(whole, 5);
        var cut = withX96(pair[0], 1, 5, block, codes);
        for (var frames : List.of(units(leading, cut), units(leading, pair[1]))) {
            assertEquals(List.of(96000, 96000, 96000, 96000), DtsUnits.decode(frames).stream()
                    .map(AudioBuffer::sampleRate).toList(), "every frame decodes its X96 extension");
        }
        assertEquivalent("X96 " + block, units(leading, cut), units(leading, pair[1]), 5, X96);
    }

    @ParameterizedTest(name = "{0} partial samples, downmix {1}")
    @CsvSource({"2, false", "6, false", "2, true", "6, true"})
    @DisplayName("an XXCH channel set decodes its partial subsubframe like the core and undoes its downmix")
    void xxch(int partial, boolean downmix) throws IOException {
        var random = new SplittableRandom(partial);
        var leading = new ArrayList<CoreStreamWriter.Frame>();
        for (var f = 0; f < LEADING; f++) {
            leading.add(xxchFrame(random, null, 0, downmix));
        }
        var whole = xxchFrame(random, null, 0, downmix);
        var pair = terminate(whole, partial);
        var cut = pair[0];
        cut.extensionData = xxchFrame(random, xxchSet, partial, downmix).extensionData;
        // ffmpeg undoes these synthetic downmixes otherwise than Smalt, a difference of normal frames that the
        // termination frame does not concern, so a downmixed set compares Smalt's two streams only
        assertEquivalent("XXCH " + partial + " " + downmix, units(leading, cut), units(leading, pair[1]), partial,
                downmix ? null : FLOAT);
    }

    @ParameterizedTest(name = "{0} partial samples")
    @ValueSource(ints = {3, 4})
    @DisplayName("an XBR extension decodes its partial subsubframe like the core")
    void xbr(int partial) throws IOException {
        var random = new SplittableRandom(40 + partial);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < LEADING; f++) {
            var frame = DtsFixedCoreDifferentialTest.plainFrame(random, 60, true, 6);
            units.add(xbrUnit(frame, xbrResidual(random), 2, 0));
        }
        var whole = DtsFixedCoreDifferentialTest.plainFrame(random, 60, true, 6);
        var residual = xbrResidual(random);
        var pair = terminate(whole, partial);
        var full = new ArrayList<>(units);
        full.add(xbrUnit(pair[1], residual, 2, 0));
        var cut = new ArrayList<>(units);
        cut.add(xbrUnit(pair[0], residual, 1, partial));
        assertEquivalent("XBR " + partial, cut, full, partial, XBR);
    }

    @Test
    @DisplayName("two termination frames in a row each output their own length")
    void consecutive() {
        var random = new SplittableRandom(5);
        var test = new DtsSyntheticDifferentialTest.Case("consecutive", 9, 2, true, false, false, false, 3, 1,
                DtsSyntheticDifferentialTest.Tool.HUFFMAN);
        var units = new ArrayList<byte[]>();
        units.add(CoreStreamWriter.write(DtsSyntheticDifferentialTest.frame(test, random)));
        units.add(CoreStreamWriter.write(terminate(DtsSyntheticDifferentialTest.frame(test, random), 3)[0]));
        units.add(CoreStreamWriter.write(terminate(DtsSyntheticDifferentialTest.frame(test, random), 7)[0]));
        var buffers = DtsUnits.decode(units);
        assertEquals(List.of(768, 608, 736), buffers.stream().map(AudioBuffer::frames).toList());
    }

    @Test
    @DisplayName("a partial subsubframe in a normal frame is malformed")
    void partialInNormalFrame() {
        var random = new SplittableRandom(6);
        var frame = terminate(DtsFixedCoreDifferentialTest.plainFrame(random, 80, true, 6), 4)[0];
        frame.termination = false;
        var unit = CoreStreamWriter.write(frame);
        assertThrows(MediaException.Malformed.class, () -> DtsUnits.decode(List.of(unit)));
    }

    @Test
    @DisplayName("a partial subsubframe before the last subframe is malformed")
    void partialBeforeLastSubframe() {
        var test = new DtsSyntheticDifferentialTest.Case("inner", 9, 2, true, false, false, false, 3, 2,
                DtsSyntheticDifferentialTest.Tool.HUFFMAN);
        var frame = DtsSyntheticDifferentialTest.frame(test, new SplittableRandom(10));
        frame.termination = true;
        frame.deficit = 0;
        frame.subframes.set(0, frame.subframes.getFirst().resized(2, 5));
        var unit = CoreStreamWriter.write(frame);
        var failure = assertThrows(MediaException.Malformed.class, () -> DtsUnits.decode(List.of(unit)));
        assertTrue(failure.getMessage().contains("before the last subframe"), failure.getMessage());
    }

    @Test
    @DisplayName("a subframe of more than 32 subband samples is malformed")
    void longSubframe() {
        var test = new DtsSyntheticDifferentialTest.Case("long", 9, 0, true, false, false, false, 4, 1,
                DtsSyntheticDifferentialTest.Tool.HUFFMAN);
        var frame = DtsSyntheticDifferentialTest.frame(test, new SplittableRandom(7));
        frame.termination = true;
        frame.subframes.set(0, frame.subframes.getFirst().resized(4, 2));
        var unit = CoreStreamWriter.write(frame);
        assertThrows(MediaException.Malformed.class, () -> DtsUnits.decode(List.of(unit)));
    }

    @Test
    @DisplayName("a termination frame cut at every byte or damaged anywhere fails only with media exceptions")
    void damaged() {
        var random = new SplittableRandom(8);
        var test = new DtsSyntheticDifferentialTest.Case("damaged", 9, 2, true, false, false, false, 3, 2,
                DtsSyntheticDifferentialTest.Tool.ADPCM);
        var unit = CoreStreamWriter.write(terminate(DtsSyntheticDifferentialTest.frame(test, random), 5)[0]);
        for (var length = 0; length < unit.length; length++) {
            decodeQuietly(Arrays.copyOf(unit, length));
        }
        for (var round = 0; round < 400; round++) {
            var damaged = unit.clone();
            for (var flip = 0; flip < 4; flip++) {
                damaged[16 + random.nextInt(damaged.length - 16)] ^= (byte) (1 << random.nextInt(8));
            }
            decodeQuietly(damaged);
        }
    }

    /**
     * Decodes units, naming the unit that fails.
     */
    private static List<AudioBuffer> decode(String name, List<byte[]> units) {
        var decoder = DtsUnits.decoder();
        var buffers = new ArrayList<AudioBuffer>();
        for (var i = 0; i < units.size(); i++) {
            try {
                decoder.decode(new Packet(0, units.get(i), 0, 0, 0, true), buffer -> buffers.add(buffer.copy()));
            } catch (MediaException failure) {
                throw new AssertionError(name + " unit " + i, failure);
            }
        }
        return buffers;
    }

    /**
     * Decodes a unit, accepting a media exception.
     */
    private static void decodeQuietly(byte[] unit) {
        try {
            DtsUnits.decoder().decode(new Packet(0, unit, 0, 0, 0, true), buffer -> {
            });
        } catch (MediaException expected) {
            // a damaged frame may be rejected, but only so
        }
    }

    /**
     * Makes the termination frame of a frame and its whole counterpart: the last subframe loses its last subsubframe
     * but for {@code partial} samples, and the whole frame keeps it with silent LFE samples, and with no transient when
     * the termination frame would code none.
     *
     * @return the termination frame and the normal frame
     */
    static CoreStreamWriter.Frame[] terminate(CoreStreamWriter.Frame whole, int partial) {
        var last = whole.subframes.getLast();
        var count = last.subsubframes;
        if (count - 1 < 2) {
            for (var row : last.transientMode) {
                Arrays.fill(row, 0);
            }
        }
        if (whole.lfe > 0) {
            Arrays.fill(last.lfe, 2 * whole.lfe * (count - 1), 2 * whole.lfe * count, 0);
        }
        var cut = whole.copy();
        cut.termination = true;
        cut.deficit = 0;
        cut.subframes.set(cut.subframes.size() - 1, last.resized(count - 1, partial));
        return new CoreStreamWriter.Frame[]{cut, whole};
    }

    private static List<byte[]> units(List<CoreStreamWriter.Frame> leading, CoreStreamWriter.Frame last) {
        var units = new ArrayList<byte[]>();
        for (var frame : leading) {
            units.add(CoreStreamWriter.write(frame));
        }
        units.add(CoreStreamWriter.write(last));
        return units;
    }

    /**
     * Asserts that the stream ending in a termination frame decodes to the start of the stream ending in its whole
     * frame, and to ffmpeg's decoding of the whole one.
     */
    private static void assertEquivalent(String name, List<byte[]> cut, List<byte[]> whole, int partial,
                                         Tolerance oracle) throws IOException {
        var cutBuffers = decode(name + " cut", cut);
        var wholeBuffers = decode(name + " whole", whole);
        var shortfall = (8 - partial) * 32 * (wholeBuffers.getLast().sampleRate() / 48000);
        assertEquals(wholeBuffers.getLast().frames() - shortfall, cutBuffers.getLast().frames(), name + " length");
        var decoded = DtsUnits.interleave(cutBuffers);
        var expected = DtsUnits.interleave(wholeBuffers);
        assertArrayEquals(Arrays.copyOf(expected, decoded.length), decoded, name);
        if (oracle == null) {
            return;
        }
        Oracle.assume(Oracle.Tool.FFMPEG);
        var reference = DtsUnits.ffmpegFloat(name + "-whole", whole);
        assertEquals(expected.length, reference.length, name + " ffmpeg sample count");
        oracle.assertPcm(name, Arrays.copyOf(reference, decoded.length), decoded);
        // ffmpeg drops a termination frame with a partial subsubframe and decodes the frames before it
        var leading = DtsUnits.ffmpegFloat(name + "-cut", cut);
        var last = cutBuffers.getLast();
        assertEquals(decoded.length - last.frames() * last.channels(), leading.length, name + " ffmpeg drops the frame");
        oracle.assertPcm(name + " before the termination frame", leading, Arrays.copyOf(decoded, leading.length));
    }

    /**
     * The XXCH set of the last frame {@link #xxchFrame} built.
     */
    private static XxchStreamWriter.Set xxchSet;

    /**
     * Builds a 5.1 core frame with a two channel XXCH set downmixed into its side surrounds, or, given the set of such
     * a frame, the XXCH extension of its termination frame.
     */
    private static CoreStreamWriter.Frame xxchFrame(SplittableRandom random, XxchStreamWriter.Set whole, int partial,
                                                    boolean downmix) {
        if (whole != null) {
            var set = new XxchStreamWriter.Set(whole.channels);
            set.layoutMask = whole.layoutMask;
            set.downmixCoefficients = downmix;
            set.downmixEmbedded = downmix;
            set.scaleCode = whole.scaleCode;
            System.arraycopy(whole.mapMasks, 0, set.mapMasks, 0, 8);
            for (var ch = 0; ch < 8; ch++) {
                System.arraycopy(whole.coefficientCodes[ch], 0, set.coefficientCodes[ch], 0, 32);
            }
            set.subframes.add(whole.subframes.getFirst().resized(1, partial));
            var frame = new CoreStreamWriter.Frame(new CoreStreamWriter.Channels(5));
            frame.extensionData = XxchStreamWriter.write(List.of(set), 11, 0x627, false);
            return frame;
        }
        var frame = DtsFixedCoreDifferentialTest.plainFrame(random, 80, true, 6);
        var channels = new CoreStreamWriter.Channels(2);
        var set = new XxchStreamWriter.Set(channels);
        set.layoutMask = 0x180;
        set.downmixCoefficients = downmix;
        set.downmixEmbedded = downmix;
        set.scaleCode = 55;
        var subframe = new CoreStreamWriter.Subframe(2);
        for (var ch = 0; ch < 2; ch++) {
            channels.subbands[ch] = 4;
            channels.vqStart[ch] = 4;
            for (var n = 0; n < 4; n++) {
                subframe.allocation[ch][n] = 15;
                subframe.scaleIndex[ch][n][0] = 80;
                for (var m = 0; m < 16; m++) {
                    subframe.codes[ch][n][m] = random.nextInt(-1500, 1500);
                }
            }
            set.mapMasks[ch] = 1 << 9 + ch;
            set.coefficientCodes[ch][9 + ch] = random.nextInt(30, 61);
        }
        set.subframes.add(subframe);
        xxchSet = set;
        frame.extension = true;
        frame.extensionType = 6;
        frame.extensionData = XxchStreamWriter.write(List.of(set), 11, 0x627, false);
        return frame;
    }

    /**
     * {@return codes of the three-level quantizer for the X96 subbands of a frame of one subframe}
     */
    private static int[][][][] x96Codes(SplittableRandom random) {
        var codes = new int[1][5][6][32];
        for (var ch = 0; ch < 5; ch++) {
            for (var n = 0; n < 6; n++) {
                for (var m = 0; m < 32; m++) {
                    codes[0][ch][n][m] = random.nextInt(-1, 2);
                }
            }
        }
        return codes;
    }

    /**
     * {@return a frame with an X96 extension of coded subbands 27 to 32}
     */
    private static CoreStreamWriter.Frame withX96(CoreStreamWriter.Frame frame, int subsubframes, int partial,
                                                  boolean block, int[][][][] codes) {
        frame.extension = true;
        frame.extensionType = 2;
        frame.extensionData = X96StreamWriter.coded(5, new int[]{subsubframes}, new int[]{partial}, false, 40, block,
                codes);
        return frame;
    }

    /**
     * {@return random XBR allocations and codes of five channels over eight subbands, in the order of
     * {@link XbrStreamWriter#write}: the allocations, then the codes}
     */
    private static Object[] xbrResidual(SplittableRandom random) {
        var allocation = new int[1][5][8];
        var codes = new int[1][5][8][32];
        for (var ch = 0; ch < 5; ch++) {
            for (var n = 0; n < 8; n++) {
                var abits = n % 3 == 0 ? 0 : 1 + random.nextInt(12);
                allocation[0][ch][n] = abits;
                var levels = abits <= 7 ? new int[]{0, 3, 5, 7, 9, 13, 17, 25}[abits] : 1 << (abits - 3);
                for (var m = 0; m < 32; m++) {
                    codes[0][ch][n][m] = abits == 0 ? 0 : random.nextInt(-(levels / 2), (levels + 1) / 2);
                }
            }
        }
        return new Object[]{allocation, codes};
    }

    /**
     * {@return an access unit of a core frame and an extension substream frame holding an XBR component}
     */
    private static byte[] xbrUnit(CoreStreamWriter.Frame frame, Object[] residual, int subsubframes, int partial) {
        var xbr = XbrStreamWriter.write(IntStream.generate(() -> 8).limit(5).toArray(), new int[]{subsubframes},
                new int[]{partial}, false, (int[][][]) residual[0], 30, (int[][][][]) residual[1]);
        var out = new ByteArrayOutputStream();
        out.writeBytes(CoreStreamWriter.write(frame));
        out.writeBytes(XllStreamWriter.substream(null, 6, 0xF, 12, XllStreamWriter.Sync.NONE,
                List.of(new XllStreamWriter.Extension(0x20, 14, xbr))));
        return out.toByteArray();
    }
}
