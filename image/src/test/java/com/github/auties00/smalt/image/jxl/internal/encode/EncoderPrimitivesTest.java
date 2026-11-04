package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.brotli.BrotliDecoder;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;
import com.github.auties00.smalt.image.jxl.internal.header.BitDepth;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.header.UpsamplingWeights;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the encoder's bit writer, entropy coder, header writers and Brotli writer against the decoder's readers
 * of the same structures.
 */
class EncoderPrimitivesTest {
    @Nested
    @DisplayName("the bit writer")
    class Bits {
        @Test
        @DisplayName("writes fields the reader reads back")
        void fields() {
            var w = new JxlBitWriter(4);
            var random = new Random(1);
            var values = new long[2000];
            for (var i = 0; i < values.length; i++) {
                values[i] = random.nextLong();
            }
            for (var i = 0; i < values.length; i++) {
                switch (i % 5) {
                    case 0 -> w.write(13, values[i] & 0x1FFF);
                    case 1 -> w.writeU32((int) (values[i] & 0x3FFFFFFF), 0, 8, 256, 11, 2304, 14, 18688, 30);
                    case 2 -> w.writeU64(values[i] >>> (i % 64));
                    case 3 -> w.writeF16((float) ((values[i] % 1000) / 7.0));
                    default -> w.writeEnum((int) (values[i] & 63));
                }
            }
            var data = w.toByteArray();
            var r = new JxlBitReader(data, 0, data.length);
            for (var i = 0; i < values.length; i++) {
                switch (i % 5) {
                    case 0 -> assertEquals(values[i] & 0x1FFF, r.readBits(13));
                    case 1 -> assertEquals((int) (values[i] & 0x3FFFFFFF),
                            r.readU32(0, 8, 256, 11, 2304, 14, 18688, 30));
                    case 2 -> assertEquals(values[i] >>> (i % 64), r.readU64());
                    case 3 -> assertEquals(Float.float16ToFloat(Float.floatToFloat16((float) ((values[i] % 1000) / 7.0))),
                            r.readF16());
                    default -> assertEquals((int) (values[i] & 63), r.readEnum());
                }
            }
        }

        @Test
        @DisplayName("appends unaligned writers bit for bit")
        void append() {
            var a = new JxlBitWriter(4);
            a.write(3, 5);
            var b = new JxlBitWriter(4);
            for (var i = 0; i < 100; i++) {
                b.write(7, i);
            }
            a.append(b);
            a.write(5, 17);
            var data = a.toByteArray();
            var r = new JxlBitReader(data, 0, data.length);
            assertEquals(5, r.readBits(3));
            for (var i = 0; i < 100; i++) {
                assertEquals(i, r.readBits(7));
            }
            assertEquals(17, r.readBits(5));
        }
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of(1, 50, 4, false, EntropyEncoder.Params.BEST),
                Arguments.of(1, 5000, 40, false, EntropyEncoder.Params.BEST),
                Arguments.of(1, 5000, 1 << 20, false, EntropyEncoder.Params.BEST),
                Arguments.of(7, 20000, 300, false, EntropyEncoder.Params.BEST),
                Arguments.of(40, 20000, 64, false, EntropyEncoder.Params.forEffort(5, true)),
                Arguments.of(300, 30000, 16, false, EntropyEncoder.Params.forEffort(3, false)),
                Arguments.of(12, 20000, 1000, true, EntropyEncoder.Params.BEST),
                Arguments.of(3, 4000, 1, false, EntropyEncoder.Params.BEST),
                Arguments.of(5, 0, 1, false, EntropyEncoder.Params.BEST),
                Arguments.of(6, 20000, 64, false, EntropyEncoder.Params.forEffort(1, true)),
                Arguments.of(6, 20000, 64, true, EntropyEncoder.Params.forEffort(1, true)),
                Arguments.of(1, 3000, 300, false, RUNS));
    }

    static final EntropyEncoder.Params RUNS = new EntropyEncoder.Params(2, 4, 2, 128, false, true);

    @ParameterizedTest(name = "{0} contexts, {1} tokens below {2}, prefix {3}")
    @MethodSource("streams")
    @DisplayName("the entropy coder writes streams the entropy decoder reads back")
    void entropy(int contexts, int count, int range, boolean prefix, EntropyEncoder.Params params) {
        var random = new Random(contexts * 31L + count);
        var first = new Tokens();
        var second = new Tokens();
        for (var i = 0; i < count; i++) {
            var ctx = random.nextInt(contexts);
            var skew = ctx % 3 == 0 ? 1 : 8;
            first.add(ctx, (int) Math.min(range - 1L, (long) (Math.abs(random.nextGaussian()) * range / skew)));
            second.add(ctx, random.nextInt(range));
        }
        var p = prefix ? new EntropyEncoder.Params(params.clustering(), params.uintMethod(),
                params.histogramStrategy(), params.maxHistograms(), true, params.runs()) : params;
        roundTrip(List.of(first, second), contexts, p);
    }

    /**
     * Codes the streams with one encoder and reads them back with a decoder of distance multiplier 77, as a
     * modular channel of that width does; returns the size of the coded streams in bytes.
     */
    static int roundTrip(List<Tokens> streams, int contexts, EntropyEncoder.Params params) {
        var code = EntropyEncoder.build(streams, contexts, params);
        var w = new JxlBitWriter();
        code.writeHeader(w);
        for (var tokens : streams) {
            code.writeTokens(tokens, w);
        }
        var data = w.toByteArray();
        var r = new JxlBitReader(data, 0, data.length);
        var decoder = EntropyDecoder.read(r, contexts);
        for (var tokens : streams) {
            var d = decoder.copy();
            d.begin(r);
            for (var i = 0; i < tokens.size(); i++) {
                assertEquals(tokens.value(i), d.readUintClustered(r, d.cluster(tokens.context(i)), 77),
                        "token " + i);
            }
            d.checkFinalState();
        }
        return data.length;
    }

    @ParameterizedTest(name = "prefix {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the entropy coder codes runs as LZ77 copies the entropy decoder expands")
    void runs(boolean prefix) {
        var random = new Random(9);
        var streams = new ArrayList<Tokens>();
        for (var s = 0; s < 3; s++) {
            var tokens = new Tokens();
            var value = 0;
            for (var i = 0; i < 30000; i++) {
                // Runs of 1 to 2000 equal values, the first token of a stream included, and some isolated values.
                if (random.nextInt(i % 7 == 0 ? 3 : 400) == 0) {
                    value = random.nextInt(40);
                }
                tokens.add(i % 5, value);
            }
            streams.add(tokens);
        }
        var plain = new EntropyEncoder.Params(1, 2, 1, 128, prefix, false);
        var runs = new EntropyEncoder.Params(1, 2, 1, 128, prefix, true);
        var without = roundTrip(streams, 5, plain);
        var with = roundTrip(streams, 5, runs);
        assertTrue(with * 4 < without, with + " bytes with LZ77 runs against " + without + " without");
    }

    @Test
    @DisplayName("the image header writer writes headers the reader reads back")
    void imageHeader() {
        var extra = List.of(
                new ExtraChannelInfo(ExtraChannelInfo.TYPE_ALPHA, BitDepth.DEFAULT, 0, "", false, null, 0),
                new ExtraChannelInfo(ExtraChannelInfo.TYPE_DEPTH, new BitDepth(false, 16, 0), 0, "depth", false,
                        null, 0),
                new ExtraChannelInfo(ExtraChannelInfo.TYPE_SPOT_COLOR, new BitDepth(true, 32, 8), 0, "spot", false,
                        new float[]{0.5f, 0.25f, 1.0f, 0.75f}, 0));
        var color = new ColorEncoding(false, ColorEncoding.RGB, ColorEncoding.WHITE_D65, null,
                ColorEncoding.PRIMARIES_P3, null, ColorEncoding.TF_PQ, 0, 0);
        for (var size : new int[][]{{1, 1}, {8, 8}, {256, 192}, {257, 3}, {4000, 3000}, {1 << 20, 7}}) {
            var header = new ImageHeader(size[0], size[1], 6, 0, 0, 0, 0,
                    new ImageHeader.Animation(1000, 1, 3, false), new BitDepth(false, 10, 0), false, extra, true,
                    color, new ImageHeader.ToneMapping(10000, 0, false, 0), ImageHeader.OpsinInverse.DEFAULT,
                    UpsamplingWeights.UP2, UpsamplingWeights.UP4, UpsamplingWeights.UP8);
            var w = new JxlBitWriter();
            HeaderWriter.writeImageHeader(w, header);
            var data = w.toByteArray();
            var read = ImageHeader.read(new JxlBitReader(data, 0, data.length));
            assertEquals(size[0], read.width());
            assertEquals(size[1], read.height());
            assertEquals(6, read.orientation());
            assertEquals(header.animation(), read.animation());
            assertEquals(header.bitDepth(), read.bitDepth());
            assertEquals(3, read.extraChannels().size());
            assertEquals("depth", read.extraChannels().get(1).name());
            assertArrayEquals(new float[]{0.5f, 0.25f, 1.0f, 0.75f}, read.extraChannels().get(2).spotColor());
            assertEquals(ColorEncoding.TF_PQ, read.colorEncoding().transfer());
            assertEquals(ColorEncoding.PRIMARIES_P3, read.colorEncoding().primaries());
            assertEquals(10000, read.toneMapping().intensityTarget());
            assertTrue(read.xybEncoded());
        }
    }

    @ParameterizedTest(name = "crop {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the frame header writer writes headers the reader reads back")
    void frameHeader(boolean crop) {
        var image = new ImageHeader(300, 200, 1, 0, 0, 0, 0, new ImageHeader.Animation(1000, 1, 0, false),
                BitDepth.DEFAULT, true, List.of(new ExtraChannelInfo(ExtraChannelInfo.TYPE_ALPHA, BitDepth.DEFAULT,
                0, "", false, null, 0)), false, ColorEncoding.SRGB, ImageHeader.ToneMapping.DEFAULT,
                ImageHeader.OpsinInverse.DEFAULT, UpsamplingWeights.UP2, UpsamplingWeights.UP4, UpsamplingWeights.UP8);
        var f = new HeaderWriter.Frame();
        f.modular = true;
        f.groupSizeShift = 2;
        f.duration = 1234;
        f.isLast = false;
        f.saveAsReference = 1;
        f.gaborish = false;
        f.epfIterations = 0;
        f.name = "frame";
        if (crop) {
            f.haveCrop = true;
            f.x0 = 17;
            f.y0 = 3;
            f.width = 50;
            f.height = 60;
            f.blendSource = 1;
        }
        var w = new JxlBitWriter();
        HeaderWriter.writeFrameHeader(w, f, image);
        var data = w.toByteArray();
        var read = FrameHeader.read(new JxlBitReader(data, 0, data.length), image, false);
        assertEquals(FrameHeader.MODULAR, read.encoding);
        assertEquals(2, read.groupSizeShift);
        assertEquals(1234, read.duration);
        assertFalse(read.isLast);
        assertEquals(1, read.saveAsReference);
        assertNull(read.gaborWeights);
        assertNull(read.epf);
        assertEquals("frame", read.name);
        assertEquals(crop ? 50 : 300, read.width);
        assertEquals(crop ? 17 : 0, read.x0);
        assertEquals(crop ? 1 : 0, read.blending.source());
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 1000, 70000, 1 << 21})
    @DisplayName("the Brotli writer writes streams the Brotli decoder inflates")
    void brotli(int size) {
        var data = new byte[size];
        new Random(size).nextBytes(data);
        var stream = BrotliWriter.compress(data);
        assertArrayEquals(data, BrotliDecoder.decompress(stream, 0, stream.length, MediaLimits.defaults().maxBytes()));
    }

    @Test
    @DisplayName("the ICC writer writes profiles the ICC reader reads back")
    void icc() {
        for (var profile : List.of(com.github.auties00.smalt.image.color.ColorProfile.displayP3().toByteArray(),
                com.github.auties00.smalt.image.color.ColorProfile.adobeRgb().toByteArray(), new byte[100])) {
            var w = new JxlBitWriter();
            IccWriter.write(w, profile);
            var data = w.toByteArray();
            assertArrayEquals(profile, com.github.auties00.smalt.image.jxl.internal.color.IccStream.read(
                    new JxlBitReader(data, 0, data.length), 1 << 20));
        }
    }
}
