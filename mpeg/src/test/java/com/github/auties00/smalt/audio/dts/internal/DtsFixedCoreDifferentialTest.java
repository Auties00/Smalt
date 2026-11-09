package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compares the fixed-point core reconstruction and the lossless extension over it with ffmpeg's lossless output, used
 * as a black box, sample for sample, at the rate of the core and at twice it: zero residuals coded against the core
 * make that output the fixed-point core, so every coding tool of the core runs through its integer path, and random
 * residuals, downmix scales and coefficients over two and three channel sets exercise the reversal of the embedded
 * downmixes.
 *
 * <p>ffmpeg outputs the core alone for the first frame with several channel sets after a frame without the extension,
 * though the frame decodes (a stream's first frames show it: their lossless channels are the source's silence where
 * that output holds the core), so its downmix comparisons start at the second frame and
 * {@link #firstFrame() firstFrame} pins what Smalt does with the first one. ffmpeg also leaves sum/difference decoding
 * out of its lossless output, which {@link #sumDifference() sumDifference} compares with its core output instead.
 */
final class DtsFixedCoreDifferentialTest {
    private static final Tolerance EXACT = Tolerance.exact();
    private static final int FRAME = 512;
    private static final int FRAMES = 6;

    static Stream<DtsSyntheticDifferentialTest.Case> tools() {
        return fiveChannelTools().filter(test -> !test.front() && !test.surround());
    }

    private static Stream<DtsSyntheticDifferentialTest.Case> fiveChannelTools() {
        return DtsSyntheticDifferentialTest.cases().filter(test -> test.amode() == 9 || test.amode() == 8)
                .map(test -> new DtsSyntheticDifferentialTest.Case(test.name(), test.amode(),
                        test.amode() == 9 && test.lfe() == 1 ? 2 : test.lfe(), test.perfect(), test.sync(),
                        test.front(), test.surround(), test.subsubframes(), test.subframes(), test.tool(),
                        test.history(), test.dynamicRange()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tools")
    @DisplayName("zero residuals over the core reproduce the fixed-point core of each coding tool")
    void codingTools(DtsSyntheticDifferentialTest.Case test) throws IOException {
        assertLossless(test.name(), toolUnits(test, 1), 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tools")
    @DisplayName("zero residuals at twice the rate of the core reproduce the 64-band fixed-point core of each tool")
    void codingToolsAtTwiceTheRate(DtsSyntheticDifferentialTest.Case test) throws IOException {
        assertLossless(test.name(), toolUnits(test, 2), 0);
    }

    private static List<byte[]> toolUnits(DtsSyntheticDifferentialTest.Case test, int ratio) {
        var random = new SplittableRandom(test.name().hashCode());
        var channels = test.amode() == 9 ? 5 : 4;
        var total = channels + (test.lfe() > 0 ? 1 : 0);
        var mask = (test.amode() == 9 ? 0x1F : 0x1E) | (test.lfe() > 0 ? 0x20 : 0);
        var speakers = (test.amode() == 9 ? 0x7 : 0x6) | (test.lfe() > 0 ? 0x8 : 0);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var frame = DtsSyntheticDifferentialTest.frame(test, random);
            var samples = frame.blocks() * 32 * ratio;
            var set = new XllStreamWriter.ChannelSet(total, mask);
            set.residuals = new int[total][samples];
            units.add(unit(frame, List.of(set), total, speakers, samples, ratio));
        }
        return units;
    }

    @Test
    @DisplayName("sum/difference decoding reaches the fixed-point core, within float rounding of the core output")
    void sumDifference() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var test = fiveChannelTools().filter(DtsSyntheticDifferentialTest.Case::front).findFirst().orElseThrow();
        var random = new SplittableRandom(test.name().hashCode());
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var frame = DtsSyntheticDifferentialTest.frame(test, random);
            var samples = frame.blocks() * 32;
            var set = new XllStreamWriter.ChannelSet(4, 0x1E);
            set.residuals = new int[4][samples];
            units.add(unit(frame, List.of(set), 4, 0x6, samples, 1));
        }
        // a few hundred least significant bits of the 24, against sums and differences of millions when it is left out
        Tolerance.maxAbs(512 * 256).assertWithin("sum and difference", ffmpeg(units, List.of("-core_only", "1")),
                decode(units).samples);
    }

    /**
     * Reads the components an extension substream frame of a FATE stream carries before its lossless one.
     */
    private static List<XllStreamWriter.Extension> extensions(byte[] data, int offset) {
        var longSizes = (data[offset + 5] & 0x20) != 0;
        var fields = 0L;
        for (var i = 5; i < 10; i++) {
            fields = fields << 8 | data[offset + i] & 0xFF;
        }
        // the 40 bits of the substream index, the flag, the header size and the frame size, each size minus one
        var headerBits = longSizes ? 12 : 8;
        var frameBits = longSizes ? 20 : 16;
        var headerSize = (int) (fields >> 37 - headerBits & (1L << headerBits) - 1) + 1;
        var frameSize = (int) (fields >> 37 - headerBits - frameBits & (1L << frameBits) - 1) + 1;
        var names = new int[][]{{0x20, 14, 0x655E315E}, {0x40, 14, 0x47004A03}, {0x80, 12, 0x1D95F262}};
        var starts = new int[names.length];
        for (var c = 0; c < names.length; c++) {
            starts[c] = -1;
            for (var i = offset + headerSize; i + 4 <= offset + frameSize && starts[c] < 0; i++) {
                if (ByteBuffer.wrap(data, i, 4).getInt() == names[c][2]) {
                    starts[c] = i;
                }
            }
        }
        var found = new ArrayList<XllStreamWriter.Extension>();
        for (var c = 0; c < names.length; c++) {
            if (starts[c] < 0) {
                continue;
            }
            var end = offset + frameSize;
            for (var next = c + 1; next < names.length; next++) {
                if (starts[next] >= 0) {
                    end = starts[next];
                    break;
                }
            }
            found.add(new XllStreamWriter.Extension(names[c][0], names[c][1], Arrays.copyOfRange(data, starts[c], end)));
        }
        return found;
    }

    /**
     * Builds access units of the core frames and core extensions of a FATE stream with zero residuals coded against them.
     */
    private static List<byte[]> extensionUnits(String vector, int channels, int mask, int speakers, int samples,
                                               int rateCode) throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed("dts-" + vector + ".dtshd"));
        var units = new ArrayList<byte[]>();
        var position = 0;
        while (position + 8 <= data.length) {
            if (ByteBuffer.wrap(data, position, 4).getInt() != 0x7FFE8001) {
                position++;
                continue;
            }
            var size = ((data[position + 5] & 0x03) << 12 | (data[position + 6] & 0xFF) << 4
                    | (data[position + 7] & 0xF0) >> 4) + 1;
            var next = position + size;
            if (next + 4 > data.length) {
                break;
            }
            var found = ByteBuffer.wrap(data, next, 4).getInt() == 0x64582025 ? extensions(data, next)
                    : List.<XllStreamWriter.Extension>of();
            var set = new XllStreamWriter.ChannelSet(channels, mask);
            set.rateCode = rateCode;
            set.residuals = new int[channels][samples];
            var out = new ByteArrayOutputStream();
            out.write(data, position, size);
            out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), samples), channels,
                    speakers, rateCode, 0, found));
            units.add(out.toByteArray());
            position = next;
        }
        return units;
    }

    @ParameterizedTest(name = "{0}, {1} channels, {4} samples")
    @CsvSource({"x96_51_24_96_1509, 6, 63, 15, 1024, 13", "x96_51_24_96_1509, 6, 63, 15, 512, 12",
            "x96_xch_61_24_96_3840, 7, 127, 79, 1024, 13", "x96_xxch_71_24_96_3840, 8, 1959, 207, 1024, 13",
            "xbr_51_24_48_3840, 6, 63, 15, 512, 12", "xbr_xch_61_24_48_3840, 7, 127, 79, 512, 12",
            "xbr_xxch_71_24_48_3840, 8, 1959, 207, 512, 12", "xxch_71_24_48_2046, 8, 1959, 207, 512, 12"})
    @DisplayName("the lossless reference leaves X96 out, adds XBR in integers and keeps XCh and XXCH downmixes")
    void coreExtensions(String vector, int channels, int mask, int speakers, int samples, int rateCode)
            throws IOException {
        assertLossless(vector, extensionUnits(vector, channels, mask, speakers, samples, rateCode), 0);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"x96_xch_61_24_96_3840", "xbr_xxch_71_24_48_3840"})
    @DisplayName("damaged core extensions under a lossless component fail only with media exceptions")
    void damagedExtensions(String vector) throws IOException {
        var eight = vector.contains("xxch");
        var units = extensionUnits(vector, eight ? 8 : 7, eight ? 1959 : 127, eight ? 207 : 79,
                vector.contains("96") ? 1024 : 512, vector.contains("96") ? 13 : 12);
        var random = new SplittableRandom(vector.hashCode());
        for (var round = 0; round < 300; round++) {
            var decoder = decoder();
            for (var unit : units.subList(0, 2)) {
                var damaged = unit.clone();
                for (var flip = 0; flip < 8; flip++) {
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                }
                try {
                    decoder.decode(new Packet(0, damaged, 0, 0, 0, true), buffer -> {
                    });
                } catch (MediaException expected) {
                    // a damaged frame may be rejected, but only so
                }
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("random XXCH downmixes stay in the reference of a lossless component, as ffmpeg decodes them")
    void xxchDownmixes(int seed) throws IOException {
        var random = new SplittableRandom(seed);
        var scaleCode = new int[]{61, 55, 49, 40}[seed % 4];
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            units.add(unit(xxchFrame(random, scaleCode), List.of(set(8, 0x7A7, 0, null, random, seed % 2 == 0 ? 1 : 500, 1)),
                    8, 0xCF, FRAME, 1));
        }
        assertLossless("XXCH downmix " + seed, units, 0);
    }

    /**
     * Builds a 5.1 core frame with a two channel XXCH set downmixed into random channels.
     */
    static CoreStreamWriter.Frame xxchFrame(SplittableRandom random, int scaleCode) {
        var frame = plainFrame(random, 80, false, 6);
        var channels = new CoreStreamWriter.Channels(2);
        var set = new XxchStreamWriter.Set(channels);
        set.layoutMask = 0x180;
        set.downmixCoefficients = true;
        set.downmixEmbedded = true;
        set.scaleCode = scaleCode;
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
            // the speakers of the core mask: C, L and R, and the side surrounds of bits 9 and 10
            set.mapMasks[ch] = random.nextInt(1, 32) * 0x41 & 0x607 | 1 << 9 + ch;
            for (var bit = 0; bit < 11; bit++) {
                set.coefficientCodes[ch][bit] = (random.nextBoolean() ? 0x40 : 0) | random.nextInt(30, 61);
            }
        }
        set.subframes.add(subframe);
        frame.extension = true;
        frame.extensionType = 6;
        frame.extensionData = XxchStreamWriter.write(List.of(set), 11, 0x627, false);
        return frame;
    }

    @ParameterizedTest(name = "binary part of {0} bits")
    @ValueSource(ints = {0, 1, 5})
    @DisplayName("Rice codes with unary parts of hundreds of bits decode as ffmpeg decodes them")
    void longRiceCodes(int bits) throws IOException {
        var random = new SplittableRandom(bits);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 3; f++) {
            var set = set(6, 0x3F, f == 0 ? 0x3F : 0, null, random, 3, 1);
            set.riceBits = bits;
            for (var row : set.residuals) {
                row[random.nextInt(FRAME)] = random.nextBoolean() ? 2000 : -2000;
            }
            units.add(unit(plainFrame(random, 80, false, 6), List.of(set), 6, 0xF, FRAME, 1));
        }
        assertLossless("Rice " + bits, units, 0);
    }

    @Test
    @DisplayName("a peak bit rate smoothing buffer outputs the core of a stream with one")
    void peakBufferOverCore() {
        var random = new SplittableRandom(21);
        var units = new ArrayList<byte[]>();
        var cores = new ArrayList<byte[]>();
        for (var f = 0; f < 3; f++) {
            var frame = plainFrame(random, 80, false, 6);
            var set = set(6, 0x3F, 0, null, random, 100, 1);
            var out = new ByteArrayOutputStream();
            out.writeBytes(CoreStreamWriter.write(frame));
            out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), FRAME), 6, 0xF, 12, 3,
                    List.of()));
            units.add(out.toByteArray());
            cores.add(CoreStreamWriter.write(frame));
        }
        assertArrayEquals(decode(cores).samples, decode(units).samples);
    }

    @Test
    @DisplayName("a peak bit rate smoothing buffer is unsupported in a stream without a core")
    void peakBufferAlone() {
        var random = new SplittableRandom(22);
        var set = set(6, 0x3F, 0x3F, null, random, 100, 1);
        var unit = XllStreamWriter.substream(XllStreamWriter.component(List.of(set), FRAME), 6, 0xF, 12, 3, List.of());
        assertThrows(MediaException.Unsupported.class, () -> decode(List.of(unit)));
    }

    @ParameterizedTest(name = "scale index {0}, perfect {1}, ratio {2}")
    // above about 2^24.8 the sum of a block's subband samples, which no real signal reaches, ffmpeg's transform
    // overflows
    @CsvSource({"70, true, 1", "70, false, 1", "100, true, 1", "100, false, 1", "105, true, 1", "105, false, 1",
            "70, true, 2", "100, false, 2", "105, true, 2"})
    @DisplayName("loud blocks take the scaled transform and clip like the fixed-point core")
    void loud(int scale, boolean perfect, int ratio) throws IOException {
        var random = new SplittableRandom(scale);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var frame = plainFrame(random, scale, perfect, 6);
            var set = new XllStreamWriter.ChannelSet(6, 0x3F);
            set.residuals = new int[6][FRAME * ratio];
            units.add(unit(frame, List.of(set), 6, 0xF, FRAME * ratio, ratio));
        }
        assertLossless("loud " + scale + " " + perfect, units, 0);
    }

    @Test
    @DisplayName("a 44.1 kHz core takes the lossless channels at its rate as ffmpeg does")
    void core44100() throws IOException {
        assertLossless("44.1 kHz core", rateUnits(8, 6, 1), 0);
    }

    @Test
    @DisplayName("88.2 kHz channels over a 44.1 kHz core decode as 96 kHz channels over a 48 kHz core")
    void core44100AtTwiceTheRate() {
        // ffmpeg refuses these rates; the fixed-point reconstruction does not depend on the rate
        assertArrayEquals(decode(rateUnits(13, 13, 2)).samples, decode(rateUnits(8, 7, 2)).samples);
    }

    private static List<byte[]> rateUnits(int coreRate, int rateCode, int ratio) {
        var random = new SplittableRandom(441);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var frame = plainFrame(random, 80, false, 6);
            frame.sfreq = coreRate;
            var set = set(6, 0x3F, 0, null, random, 3000, ratio);
            set.rateCode = rateCode;
            var out = new ByteArrayOutputStream();
            out.writeBytes(CoreStreamWriter.write(frame));
            out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), FRAME * ratio), 6, 0xF,
                    rateCode));
            units.add(out.toByteArray());
        }
        return units;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    @DisplayName("residuals over the core and random embedded downmixes decode as ffmpeg decodes them")
    void downmixes(int seed) throws IOException {
        assertDownmixes(seed, 1);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    @DisplayName("residuals at twice the rate of the core and random embedded downmixes decode as ffmpeg decodes them")
    void downmixesAtTwiceTheRate(int seed) throws IOException {
        assertDownmixes(seed, 2);
    }

    private static void assertDownmixes(int seed, int ratio) throws IOException {
        var random = new SplittableRandom(seed);
        var three = seed % 2 == 0;
        var amplitude = seed % 3 == 0 ? 100 : 3000000;
        var scale = seed % 4 == 0 ? 100 : 80;
        var residualType = random.nextInt(64);
        var downmix1 = downmix(random, 6, 1);
        var downmix2 = three ? downmix(random, 7, 2) : null;
        var total = three ? 9 : 7;
        var speakers = three ? 0xCF : 0x4F;
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var sets = new ArrayList<XllStreamWriter.ChannelSet>();
            sets.add(set(6, 0x3F, residualType, null, random, amplitude, ratio));
            sets.add(set(1, 0x40, 1, downmix1, random, amplitude, ratio));
            if (three) {
                sets.add(set(2, 0x180, 3, downmix2, random, amplitude, ratio));
            }
            units.add(unit(plainFrame(random, scale, false, 6), sets, total, speakers, FRAME * ratio, ratio));
        }
        assertLossless("downmix " + seed, units, FRAME * ratio);
    }

    @ParameterizedTest(name = "width {0}, resolution {1}, ratio {2}")
    @CsvSource({"24, 20, 1", "20, 20, 1", "20, 16, 1", "16, 16, 1", "16, 12, 1", "24, 20, 2", "16, 16, 2"})
    @DisplayName("the core is rounded to the resolution of the residuals and widened with them")
    void widths(int width, int resolution, int ratio) throws IOException {
        var random = new SplittableRandom(width * 100L + resolution);
        var downmix = downmix(random, 6, 1);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var first = set(6, 0x3F, 5, null, random, 200, ratio);
            var second = set(1, 0x40, 1, downmix, random, 200, ratio);
            first.width = second.width = width;
            first.resolution = second.resolution = resolution;
            units.add(unit(plainFrame(random, 80, false, 6), List.of(first, second), 7, 0x4F, FRAME * ratio, ratio));
        }
        assertLossless("width " + width + " resolution " + resolution, units, FRAME * ratio);
    }

    @Test
    @DisplayName("channel sets of different resolutions undo their downmixes at the resolution of the target channel")
    void mixedResolutions() {
        var coarse = mixedResolutionUnits(16);
        var fine = mixedResolutionUnits(24);
        assertArrayEquals(decode(fine).samples, decode(coarse).samples);
    }

    /**
     * Builds units whose later channel set holds the same samples at either resolution, its low bits zero at the
     * higher one.
     */
    private static List<byte[]> mixedResolutionUnits(int resolution) {
        var random = new SplittableRandom(44);
        var downmix = downmix(random, 6, 1);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var first = set(6, 0x3F, 0x3F, null, random, 100000, 1);
            var second = set(1, 0x40, 1, downmix, random, 10000, 1);
            second.resolution = resolution;
            if (resolution == 24) {
                for (var n = 0; n < FRAME; n++) {
                    second.residuals[0][n] <<= 8;
                }
            }
            units.add(unit(plainFrame(random, 80, false, 6), List.of(first, second), 7, 0x4F, FRAME, 1));
        }
        return units;
    }

    @Test
    @DisplayName("frames without the extension inside a lossless stream resume the lossless output on the next frame")
    void gaps() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var random = new SplittableRandom(77);
        var units = new ArrayList<byte[]>();
        var plan = new boolean[]{true, true, false, true, true, false, false, true};
        for (var lossless : plan) {
            var frame = plainFrame(random, 90, false, 6);
            if (lossless) {
                units.add(unit(frame, List.of(set(6, 0x3F, 5, null, random, 3000, 1)), 6, 0xF, FRAME, 1));
            } else {
                units.add(CoreStreamWriter.write(frame));
            }
        }
        var reference = ffmpeg(units, List.of());
        var decoded = decode(units);
        assertEquals(reference.length, decoded.samples.length, "sample count");
        for (var f = 0; f < plan.length; f++) {
            var from = f * FRAME * 6;
            var to = from + FRAME * 6;
            var expected = Arrays.copyOfRange(reference, from, to);
            var actual = Arrays.copyOfRange(decoded.samples, from, to);
            if (plan[f]) {
                EXACT.assertWithin("frame " + f, expected, actual);
            } else {
                // ffmpeg decodes these frames in floating point and Smalt too, which differ by float rounding
                Tolerance.maxAbs(4096).assertWithin("frame " + f, expected, actual);
            }
        }
    }

    @Test
    @DisplayName("a frame without the extension inside a 96 kHz lossless stream restarts the fixed-point history")
    void gapAtTwiceTheRate() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var random = new SplittableRandom(78);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 5; f++) {
            var frame = plainFrame(random, 90, false, 6);
            if (f == 2) {
                units.add(CoreStreamWriter.write(frame));
            } else {
                units.add(unit(frame, List.of(set(6, 0x3F, 0, null, random, 3000, 2)), 6, 0xF, FRAME * 2, 2));
            }
        }
        var decoded = decode(units);
        assertEquals((4 * FRAME * 2 + FRAME) * 6, decoded.samples.length, "samples at both rates");
        // ffmpeg resamples the frame at the rate of the core to the rate of the stream, so only the end lines up
        var reference = ffmpeg(units, List.of());
        var tail = 2 * FRAME * 2 * 6;
        EXACT.assertWithin("the two frames after the gap", Arrays.copyOfRange(reference, reference.length - tail,
                reference.length), Arrays.copyOfRange(decoded.samples, decoded.samples.length - tail,
                decoded.samples.length));
    }

    @Test
    @DisplayName("the first frame of several channel sets is decoded, with the downmix of the later set undone")
    void firstFrame() {
        var random = new SplittableRandom(5);
        var units = new ArrayList<byte[]>();
        var first = set(6, 0x3F, 0x3F, null, random, 100000, 1);
        // the column of the first channel: -3 dB scale, coefficient -3 dB; the other columns leave their channels alone
        var codes = new int[12];
        for (var i = 0; i < 12; i += 2) {
            codes[i] = 0x1F1;
            codes[i + 1] = 0x100;
        }
        codes[0] = 0x1D9;
        codes[1] = 0x1D9;
        var second = set(1, 0x40, 1, codes, random, 100000, 1);
        units.add(unit(plainFrame(random, 80, false, 6), List.of(first, second), 7, 0x4F, FRAME, 1));
        var decoded = decode(units);
        var center = new int[FRAME];
        for (var n = 0; n < FRAME; n++) {
            center[n] = first.residuals[0][n] - (int) ((32767L * second.residuals[0][n] + (1 << 14)) >> 15);
        }
        // the center channel is the third output channel
        var actual = new int[FRAME];
        for (var n = 0; n < FRAME; n++) {
            actual[n] = decoded.samples[n * 7 + 2] >> 8;
        }
        assertArrayEquals(center, actual);
    }

    @ParameterizedTest(name = "ratio {0}")
    @ValueSource(ints = {1, 2})
    @DisplayName("a 128 times LFE channel, which has no fixed-point reference, falls back to the core output")
    void lfe128(int ratio) {
        var random = new SplittableRandom(128);
        var units = new ArrayList<byte[]>();
        var cores = new ArrayList<byte[]>();
        for (var f = 0; f < FRAMES; f++) {
            var frame = lfe128Frame(random);
            var set = new XllStreamWriter.ChannelSet(6, 0x3F);
            set.residuals = new int[6][FRAME * ratio];
            units.add(unit(frame, List.of(set), 6, 0xF, FRAME * ratio, ratio));
            cores.add(CoreStreamWriter.write(frame));
        }
        assertArrayEquals(decode(cores).samples, decode(units).samples);
    }

    @Test
    @DisplayName("channels coded alone need no core reconstruction, even over a 128 times LFE channel")
    void aloneOverLfe128() {
        var random = new SplittableRandom(129);
        var units = new ArrayList<byte[]>();
        var expected = new ArrayList<int[][]>();
        for (var f = 0; f < 3; f++) {
            var set = set(6, 0x3F, 0x3F, null, random, 100000, 1);
            expected.add(set.residuals);
            units.add(unit(lfe128Frame(random), List.of(set), 6, 0xF, FRAME, 1));
        }
        var decoded = decode(units);
        assertEquals(6, decoded.channels, "channels");
        // the center channel is the third output channel and the first coded one
        for (var f = 0; f < 3; f++) {
            for (var n = 0; n < FRAME; n++) {
                assertEquals(expected.get(f)[0][n], decoded.samples[(f * FRAME + n) * 6 + 2] >> 8,
                        "frame " + f + " sample " + n);
            }
        }
    }

    private static CoreStreamWriter.Frame lfe128Frame(SplittableRandom random) {
        var frame = plainFrame(random, 60, false, 6);
        frame.lfe = 1;
        for (var subframe : frame.subframes) {
            subframe.lfeScale = 50;
        }
        return frame;
    }

    @Test
    @DisplayName("a damaged component header throws, and the lossless output is exact again two frames later")
    void damagedHeader() {
        var units = cleanUnits(8);
        var clean = decode(units);
        var damaged = new ArrayList<>(units);
        var unit = damaged.get(3).clone();
        // a byte of the frame size field of the XLL header, covered by its header CRC
        unit[unit.length - componentLength(3) + 7] ^= 0x01;
        damaged.set(3, unit);
        var decoder = decoder();
        var buffers = new ArrayList<AudioBuffer>();
        for (var f = 0; f < damaged.size(); f++) {
            var packet = new Packet(0, damaged.get(f), 0, 0, 0, true);
            if (f == 3) {
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(packet, buffer -> buffers.add(buffer.copy())));
            } else {
                decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
            }
        }
        var decoded = interleave(buffers);
        assertEquals(clean.samples.length - FRAME * clean.channels, decoded.samples.length, "one frame lost");
        var from = 5 * FRAME * clean.channels;
        assertArrayEquals(Arrays.copyOfRange(clean.samples, from, clean.samples.length),
                Arrays.copyOfRange(decoded.samples, from - FRAME * clean.channels, decoded.samples.length));
    }

    private static List<byte[]> cleanUnits(int count) {
        var random = new SplittableRandom(99);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < count; f++) {
            units.add(unit(plainFrame(random, 80, false, 6), List.of(set(6, 0x3F, 0, null, random, 1000, 1)), 6, 0xF,
                    FRAME, 1));
        }
        return units;
    }

    /**
     * {@return the size of the XLL component of a unit of {@link #cleanUnits}}
     */
    private static int componentLength(int frame) {
        var random = new SplittableRandom(99);
        for (var f = 0; ; f++) {
            plainFrame(random, 80, false, 6);
            var set = set(6, 0x3F, 0, null, random, 1000, 1);
            if (f == frame) {
                return XllStreamWriter.component(List.of(set), FRAME).length;
            }
        }
    }

    @Test
    @DisplayName("an access unit cut inside its extension substream outputs the core")
    void truncatedSubstream() {
        var units = cleanUnits(2);
        var cut = Arrays.copyOf(units.get(0), units.get(0).length - 40);
        var core = new SplittableRandom(99);
        var coreFrame = CoreStreamWriter.write(plainFrame(core, 80, false, 6));
        assertArrayEquals(decode(List.<byte[]>of(coreFrame)).samples, decode(List.<byte[]>of(cut)).samples);
    }

    @Test
    @DisplayName("a frame whose length is neither the core's nor twice it is malformed")
    void frameLengthMismatch() {
        var random = new SplittableRandom(3);
        var set = new XllStreamWriter.ChannelSet(6, 0x3F);
        set.residuals = new int[6][FRAME / 2];
        var unit = unit(plainFrame(random, 80, false, 6), List.of(set), 6, 0xF, FRAME / 2, 1);
        assertThrows(MediaException.Malformed.class, () -> decode(List.of(unit)));
    }

    @Test
    @DisplayName("a channel coded against a core that has no such speaker is malformed")
    void missingSpeaker() {
        var random = new SplittableRandom(4);
        var set = set(1, 0x40, 0, null, random, 100, 1);
        var unit = unit(plainFrame(random, 80, false, 6), List.of(set), 1, 0x40, FRAME, 1);
        assertThrows(MediaException.Malformed.class, () -> decode(List.of(unit)));
    }

    @ParameterizedTest(name = "code {0}")
    @ValueSource(ints = {0x100, 0x128, 0x1FF})
    @DisplayName("a downmix scale outside the table is malformed")
    void invalidDownmixScale(int code) {
        var random = new SplittableRandom(6);
        var codes = new int[12];
        Arrays.fill(codes, code);
        var first = set(6, 0x3F, 0, null, random, 100, 1);
        var second = set(1, 0x40, 1, codes, random, 100, 1);
        var unit = unit(plainFrame(random, 80, false, 6), List.of(first, second), 7, 0x4F, FRAME, 1);
        assertThrows(MediaException.Malformed.class, () -> decode(List.of(unit)));
    }

    static XllStreamWriter.ChannelSet set(int channels, int mask, int residualType, int[] downmix,
                                          SplittableRandom random, int amplitude, int ratio) {
        var set = new XllStreamWriter.ChannelSet(channels, mask);
        set.residualType = residualType;
        set.downmix = downmix;
        set.primary = downmix == null;
        set.residuals = new int[channels][FRAME * ratio];
        for (var row : set.residuals) {
            for (var n = 0; n < row.length; n++) {
                row[n] = random.nextInt(-amplitude, amplitude);
            }
        }
        return set;
    }

    /**
     * Draws a downmix matrix from positive and negative scales and coefficients.
     */
    private static int[] downmix(SplittableRandom random, int columns, int channels) {
        var scales = new int[]{0x1F1, 0x1D9, 0xD9, 0x165, 0x65, 0x1A1, 0xA0};
        var coefficients = new int[]{0x100, 0, 0x1D9, 0xD9, 0x165, 0x65};
        var codes = new int[columns * (channels + 1)];
        for (var i = 0; i < codes.length; i++) {
            codes[i] = i % (channels + 1) == 0 ? scales[random.nextInt(scales.length)]
                    : coefficients[random.nextInt(coefficients.length)];
        }
        return codes;
    }

    /**
     * Builds a frame of plain coded random samples in the four lowest subbands of every channel.
     */
    static CoreStreamWriter.Frame plainFrame(SplittableRandom random, int scale, boolean perfect, int total) {
        var channels = new CoreStreamWriter.Channels(5);
        var frame = new CoreStreamWriter.Frame(channels);
        frame.lfe = total == 6 ? 2 : 0;
        frame.perfect = perfect;
        var subframe = new CoreStreamWriter.Subframe(2);
        for (var ch = 0; ch < 5; ch++) {
            channels.subbands[ch] = 4;
            channels.vqStart[ch] = 4;
            for (var n = 0; n < 4; n++) {
                subframe.allocation[ch][n] = 15;
                subframe.scaleIndex[ch][n][0] = scale;
                for (var m = 0; m < 16; m++) {
                    subframe.codes[ch][n][m] = random.nextInt(-1500, 1500);
                }
            }
        }
        for (var n = 0; n < 8; n++) {
            subframe.lfe[n] = random.nextInt(-100, 101);
        }
        subframe.lfeScale = 40 + random.nextInt(40);
        frame.subframes.add(subframe);
        return frame;
    }

    static byte[] unit(CoreStreamWriter.Frame frame, List<XllStreamWriter.ChannelSet> sets, int total, int speakers,
                       int samples, int ratio) {
        var rate = ratio == 2 ? 13 : 12;
        for (var set : sets) {
            set.rateCode = rate;
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(CoreStreamWriter.write(frame));
        out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(sets, samples), total, speakers, rate));
        return out.toByteArray();
    }

    /**
     * Asserts that the output equals ffmpeg's, channel for channel, from a sample per channel on.
     */
    private static void assertLossless(String name, List<byte[]> units, int from) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var reference = ffmpeg(units, List.of());
        var decoded = decode(units);
        var channels = decoded.channels;
        assertEquals(reference.length, decoded.samples.length, name + " sample count");
        EXACT.assertWithin(name, Arrays.copyOfRange(reference, from * channels, reference.length),
                Arrays.copyOfRange(decoded.samples, from * channels, decoded.samples.length));
    }

    private record Decoded(int[] samples, int channels) {
    }

    private static DtsDecoder decoder() {
        var track = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        return new DtsDecoder(track, DtsDecoderOptions.defaults());
    }

    /**
     * Decodes access units to 32-bit samples.
     */
    private static Decoded decode(List<byte[]> units) {
        var decoder = decoder();
        var buffers = new ArrayList<AudioBuffer>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> buffers.add(buffer.copy()));
        }
        return interleave(buffers);
    }

    private static Decoded interleave(List<AudioBuffer> buffers) {
        var channels = buffers.getFirst().channels();
        var total = buffers.stream().mapToInt(AudioBuffer::frames).sum();
        var out = new int[total * channels];
        var position = 0;
        for (var buffer : buffers) {
            for (var i = 0; i < buffer.frames(); i++) {
                for (var ch = 0; ch < channels; ch++) {
                    out[position++] = Math.round(buffer.plane(ch)[i] * 8388608.0f) << 8;
                }
            }
        }
        return new Decoded(out, channels);
    }

    private static int[] ffmpeg(List<byte[]> units, List<String> inputArguments) throws IOException {
        var file = Scratch.directory("dts-fixed-core").resolve("stream-" + Thread.currentThread().threadId() + ".dts");
        var stream = new ByteArrayOutputStream();
        units.forEach(stream::writeBytes);
        Files.write(file, stream.toByteArray());
        var arguments = new ArrayList<>(List.of("-cpuflags", "0", "-v", "error", "-f", "dts"));
        arguments.addAll(inputArguments);
        arguments.addAll(List.of("-i", file.toString(), "-f", "s32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var samples = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(samples);
        return samples;
    }
}
