package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.internal.zstd.ZstdEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ZSTD and HTJ2K chunk codecs of OpenEXR 3.5 below the file layer: the framings this file builds itself
 * (wire versions 1 and 2, one and two segments, the uncompressed fallback) against the samples they were built from,
 * and rejection of damaged chunks taken from the reference library's files.
 */
@DisplayName("OpenEXR 3.5 chunk codecs")
class ExrCodecs35Test {
    private static final long MAGIC = 8248453963162350458L;

    private static MediaLimits limits() {
        return ImageDecoderOptions.defaults().limits();
    }

    /** {@return a chunk of two half channels and a float one, 5 columns and 3 lines, the half ones every other line} */
    private static ExrBlock block() {
        return new ExrBlock(List.of(new ExrHeader.Channel("A", 1, false, 1, 2), new ExrHeader.Channel("B", 1, false, 1,
                1), new ExrHeader.Channel("Z", 2, false, 1, 1)), 0, 4, 5, 3);
    }

    /** {@return the uncompressed chunk of {@link #block()}: random samples in the packed layout} */
    private static byte[] packed(ExrBlock block, long seed) {
        var bytes = new byte[(int) block.bytes()];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /**
     * Frames a packed chunk as OpenEXR's encoder does: the half samples of every line ahead of the 32-bit ones,
     * optionally delta coded along each channel line, in byte planes behind their u64 lengths, in a zstd frame.
     */
    private static byte[] frame(ExrBlock block, byte[] raw, int version, boolean delta, boolean join) {
        var halves = new ByteArrayOutputStream();
        var words = new ByteArrayOutputStream();
        var position = 0;
        for (var row = 0; row < block.height(); row++) {
            for (var channel = 0; channel < block.channels().size(); channel++) {
                if (!block.samples(channel, block.y() + row)) {
                    continue;
                }
                var size = block.channels().get(channel).bytes();
                var count = block.channelWidth(channel);
                var values = java.util.Arrays.copyOfRange(raw, position, position + size * count);
                position += size * count;
                if (delta) {
                    // from the end, so each difference takes the original previous sample
                    for (var index = count - 1; index > 0; index--) {
                        var carry = 0;
                        for (var b = 0; b < size; b++) {
                            var difference = (values[index * size + b] & 0xFF) - (values[(index - 1) * size + b]
                                    & 0xFF) - carry;
                            carry = difference < 0 ? 1 : 0;
                            values[index * size + b] = (byte) difference;
                        }
                    }
                }
                (size == 2 ? halves : words).writeBytes(values);
            }
        }
        var inner = new ByteArrayOutputStream();
        if (!join && halves.size() > 0 && words.size() > 0) {
            segment(inner, halves.toByteArray(), 2);
            segment(inner, words.toByteArray(), 4);
        } else {
            var all = new ByteArrayOutputStream();
            all.writeBytes(halves.toByteArray());
            all.writeBytes(words.toByteArray());
            segment(inner, all.toByteArray(), join || halves.size() > 0 ? 2 : 4);
        }
        var payload = inner.toByteArray();
        var compressed = ZstdEncoder.compress(payload, 0, payload.length, 3);
        return ByteBuffer.allocate(24 + compressed.length).order(ByteOrder.LITTLE_ENDIAN).putLong(MAGIC)
                .putInt(version).putInt(delta ? 1 : 0).putLong(compressed.length).put(compressed).array();
    }

    private static void segment(ByteArrayOutputStream out, byte[] samples, int element) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(samples.length).array());
        var count = samples.length / element;
        for (var plane = 0; plane < element; plane++) {
            for (var index = 0; index < count; index++) {
                out.write(samples[index * element + plane]);
            }
        }
    }

    @Nested
    @DisplayName("ZSTD")
    class Zstd {
        @Test
        @DisplayName("undoes wire version 1 (sort and byte planes) and version 2 (with delta coding)")
        void versions() {
            var block = block();
            for (var seed = 0; seed < 8; seed++) {
                var raw = packed(block, seed);
                for (var delta : new boolean[]{false, true}) {
                    var chunk = frame(block, raw, delta ? 2 : 1, delta, false);
                    assertArrayEquals(raw, ExrCodecs.decompress(13, chunk, 0, chunk.length, block, limits()),
                            "seed " + seed + " delta " + delta);
                }
            }
        }

        @Test
        @DisplayName("reads a chunk of 16-bit samples only as one segment")
        void halvesOnly() {
            var block = new ExrBlock(List.of(new ExrHeader.Channel("Y", 1, false, 1, 1)), 0, 0, 9, 2);
            var raw = packed(block, 3);
            var chunk = frame(block, raw, 2, true, false);
            assertArrayEquals(raw, ExrCodecs.decompress(13, chunk, 0, chunk.length, block, limits()));
        }

        @Test
        @DisplayName("copies a chunk stored uncompressed and rejects one of another size")
        void stored() {
            var block = block();
            var raw = packed(block, 5);
            raw[0] = 1;
            assertArrayEquals(raw, ExrZstd.flat(raw, 0, raw.length, block, raw.length));
            assertThrows(MediaException.Malformed.class, () -> ExrZstd.flat(raw, 0, raw.length - 1, block,
                    raw.length));
        }

        @Test
        @DisplayName("rejects unknown versions and flags, frames of another size, and segments of another length")
        void malformed() {
            var block = block();
            var raw = packed(block, 7);
            var good = frame(block, raw, 2, true, false);
            var version = good.clone();
            version[8] = 3;
            var flags = good.clone();
            flags[12] = 2;
            var v1Delta = frame(block, raw, 1, true, false);
            var size = good.clone();
            size[16]++;
            var joined = frame(block, raw, 2, true, true);
            var truncated = java.util.Arrays.copyOf(good, good.length - 3);
            for (var chunk : List.of(version, flags, v1Delta, size, joined)) {
                assertThrows(MediaException.Malformed.class, () -> ExrZstd.flat(chunk, 0, chunk.length, block,
                        raw.length));
            }
            assertThrows(MediaException.Malformed.class, () -> ExrZstd.flat(truncated, 0, truncated.length, block,
                    raw.length));
        }

        @Test
        @DisplayName("decodes deep sample count tables and samples")
        void deep() {
            var counts = ByteBuffer.allocate(4 * 6).order(ByteOrder.LITTLE_ENDIAN);
            for (var value : new int[]{1, 3, 3, 0, 2, 5}) {
                counts.putInt(value);
            }
            var table = counts.array();
            var tableBlock = new ExrBlock(List.of(new ExrHeader.Channel("", 0, false, 1, 1)), 0, 0, 3, 2);
            var framed = frame(tableBlock, table, 1, false, false);
            assertArrayEquals(table, ExrZstd.sampleCounts(framed, 0, framed.length, 3, 2));
        }
    }

    @Nested
    @DisplayName("HTJ2K")
    class Htj2k {
        private static byte[] vector(String name) {
            return ExrSamplesTest.resource(name + ".exr");
        }

        @Test
        @DisplayName("pads lossy chunks to a length of 1 modulo 2^levels")
        void padding() {
            assertEquals(65, ExrHt.paddedLength(40, 5));
            assertEquals(33, ExrHt.paddedLength(23, 5));
            assertEquals(33, ExrHt.paddedLength(33, 5));
            assertEquals(1, ExrHt.paddedLength(1, 3));
            assertEquals(9, ExrHt.paddedLength(2, 3));
        }

        @Test
        @DisplayName("undoes the transfer function of legacy LJ2K samples")
        void legacy() {
            assertEquals(0, ExrHt.legacyHalf(0));
            // the largest code maps back to the largest half
            assertEquals(Float.floatToFloat16(65504f), (short) ExrHt.legacyHalf(32767));
            assertEquals((short) Float.floatToFloat16(-65504f), (short) ExrHt.legacyHalf(-40000));
            assertEquals(1f, Float.intBitsToFloat(ExrHt.legacyFloat(Math.round(51961246.180338))), 1e-6f);
            assertEquals(Float.MAX_VALUE, Float.intBitsToFloat(ExrHt.legacyFloat(Integer.MAX_VALUE)), 1e33f);
            assertEquals(0.5, ExrHt.toLinear(StrictMath.pow(0.5, 1 / 2.2f)), 1e-6);
            assertEquals(-Math.exp(2.2f), ExrHt.toLinear(-2), 1e-9);
        }

        @ParameterizedTest
        @ValueSource(ints = {8, 9, 12, 13})
        @DisplayName("rejects chunks whose header is damaged")
        void header(int index) {
            var data = vector("scanline-htj2k32");
            var file = ExrFile.read(data, limits());
            var at = file.chunk(0, 0);
            var damaged = data.clone();
            // the magic, the payload length and the first channel map entry
            damaged[at + index] ^= (byte) (index == 13 ? 0x01 : 0x40);
            assertThrows(MediaException.class, () -> ExrSamples.read(ExrFile.read(damaged, limits()), 0, 0, 0,
                    limits()));
        }

        @Test
        @DisplayName("rejects an HL magic outside LJ2K and a channel named twice")
        void map() {
            var data = vector("scanline-htj2k256");
            var file = ExrFile.read(data, limits());
            var at = file.chunk(0, 0) + 8;
            var magic = data.clone();
            magic[at + 1] = 0x4C;
            assertThrows(MediaException.Malformed.class, () -> ExrSamples.read(ExrFile.read(magic, limits()), 0, 0,
                    0, limits()));
            var twice = data.clone();
            twice[at + 9] = twice[at + 11];
            assertThrows(MediaException.Malformed.class, () -> ExrSamples.read(ExrFile.read(twice, limits()), 0, 0,
                    0, limits()));
        }
    }
}
