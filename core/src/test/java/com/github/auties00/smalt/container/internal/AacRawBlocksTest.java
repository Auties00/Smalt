package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the raw data block walker against blocks written by {@link AacBlockGenerator} for every object type, sampling
 * frequency index and frame length, every codeword of every spectrum codebook, truncation and invalid syntax. Streams
 * of real encoders are covered by the WAVE demuxer's tests.
 */
@DisplayName("AacRawBlocks")
class AacRawBlocksTest {
    static Stream<Arguments> configurations() {
        var arguments = new ArrayList<Arguments>();
        for (var objectType = 1; objectType <= 4; objectType++) {
            for (var samplingIndex = 0; samplingIndex <= 12; samplingIndex++) {
                for (var frameLength : new int[]{1024, 960}) {
                    arguments.add(Arguments.of(objectType, samplingIndex, frameLength));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "object type {0}, sampling index {1}, {2} samples")
    @MethodSource("configurations")
    @DisplayName("measures random blocks of every element and coding tool, with garbage after them")
    void randomBlocks(int objectType, int samplingIndex, int frameLength) {
        var generator = new AacBlockGenerator(objectType, samplingIndex, frameLength, 31L * objectType + samplingIndex + frameLength);
        var walker = new AacRawBlocks(objectType, samplingIndex, frameLength);
        var random = new SplittableRandom(samplingIndex);
        for (var trial = 0; trial < 40; trial++) {
            var block = generator.block(8);
            var padded = new byte[block.length + 3 + 64];
            random.nextBytes(padded);
            System.arraycopy(block, 0, padded, 3, block.length);
            assertEquals(block.length, walker.measure(padded, 3, padded.length - 3), "trial " + trial);
            assertEquals(block.length, walker.measure(padded, 3, block.length), "trial " + trial + " exactly");
        }
    }

    @Test
    @DisplayName("measures a stream of concatenated blocks block by block")
    void stream() {
        var generator = new AacBlockGenerator(AacRawBlocks.AOT_LC, 3, 1024, 7);
        var walker = new AacRawBlocks(AacRawBlocks.AOT_LC, 3, 1024);
        var lengths = new ArrayList<Integer>();
        var stream = new java.io.ByteArrayOutputStream();
        for (var index = 0; index < 300; index++) {
            var block = generator.block(6);
            lengths.add(block.length);
            stream.writeBytes(block);
        }
        var bytes = stream.toByteArray();
        var offset = 0;
        for (var length : lengths) {
            assertEquals(length, walker.measure(bytes, offset, bytes.length - offset));
            offset += length;
        }
    }

    @ParameterizedTest(name = "codebook {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    @DisplayName("walks every codeword of every spectrum codebook")
    void codebooks(int codebook) {
        for (var samplingIndex : new int[]{3, 11}) {
            var block = new AacBlockGenerator(AacRawBlocks.AOT_LC, samplingIndex, 1024, codebook).sweep(codebook);
            assertEquals(block.length, new AacRawBlocks(AacRawBlocks.AOT_LC, samplingIndex, 1024)
                    .measure(block, 0, block.length));
        }
    }

    @Test
    @DisplayName("reports every proper prefix of a block as truncated")
    void prefixes() {
        var generator = new AacBlockGenerator(AacRawBlocks.AOT_MAIN, 4, 1024, 99);
        var walker = new AacRawBlocks(AacRawBlocks.AOT_MAIN, 4, 1024);
        for (var trial = 0; trial < 20; trial++) {
            var block = generator.block(5);
            for (var length = 0; length < block.length; length++) {
                assertEquals(-1, walker.measure(block, 0, length), "trial " + trial + ", " + length + " of "
                        + block.length + " bytes");
            }
        }
    }

    static byte[] bits(Consumer<MsbBitWriter> body) {
        var writer = new MsbBitWriter(64);
        body.accept(writer);
        writer.write(7, 3);
        writer.alignWithZeros();
        var bytes = writer.toByteArray();
        return Arrays.copyOf(bytes, bytes.length + 16);
    }

    /** Writes an SCE header with a global gain, then an {@code ics_info} of a long window. */
    static void longChannel(MsbBitWriter writer, int maxBands, boolean predictor) {
        writer.write(0, 3);
        writer.write(0, 4);
        writer.write(100, 8);
        writer.write(0, 4);
        writer.write(maxBands, 6);
        writer.write(predictor ? 1 : 0, 1);
    }

    @Test
    @DisplayName("rejects invalid syntax")
    void malformed() {
        var lc = new AacRawBlocks(AacRawBlocks.AOT_LC, 3, 1024);
        var reserved = bits(writer -> {
            writer.write(0, 3);
            writer.write(0, 4);
            writer.write(0, 8);
            writer.write(1, 1);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(reserved, 0, reserved.length), "reserved bit");
        var bands = bits(writer -> longChannel(writer, 50, false));
        assertThrows(MediaException.Malformed.class, () -> lc.measure(bands, 0, bands.length), "max_sfb 50 of 49");
        var prediction = bits(writer -> longChannel(writer, 10, true));
        assertThrows(MediaException.Malformed.class, () -> lc.measure(prediction, 0, prediction.length),
                "prediction in Low Complexity");
        var codebook = bits(writer -> {
            longChannel(writer, 10, false);
            writer.write(12, 4);
            writer.write(10, 5);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(codebook, 0, codebook.length), "codebook 12");
        var overflow = bits(writer -> {
            longChannel(writer, 10, false);
            writer.write(1, 4);
            writer.write(11, 5);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(overflow, 0, overflow.length), "section overflow");
        var empty = bits(writer -> {
            longChannel(writer, 10, false);
            writer.write(1, 4);
            writer.write(0, 5);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(empty, 0, empty.length), "empty section");
        var mask = bits(writer -> {
            writer.write(1, 3);
            writer.write(0, 4);
            writer.write(1, 1);
            writer.write(0, 4);
            writer.write(0, 6);
            writer.write(0, 1);
            writer.write(3, 2);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(mask, 0, mask.length), "M/S mask mode 3");
        var pulses = bits(writer -> {
            writer.write(0, 3);
            writer.write(0, 4);
            writer.write(0, 8);
            writer.write(0, 1);
            writer.write(2, 2);
            writer.write(0, 1);
            writer.write(0, 4);
            writer.write(0, 7);
            writer.write(1, 1);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(pulses, 0, pulses.length), "pulses in short windows");
        var escape = bits(writer -> {
            longChannel(writer, 1, false);
            writer.write(11, 4);
            writer.write(1, 5);
            writer.write(0, 1);
            writer.write(0, 3);
            // the codeword of (16, 0) in codebook 11, its sign bit, then an escape prefix of nine ones
            writer.write(AacHuffmanTables.CODES_11[16 * 17], AacHuffmanTables.LENGTHS_11[16 * 17]);
            writer.write(0, 1);
            writer.write(0x1FF, 9);
            writer.write(0, 1);
        });
        assertThrows(MediaException.Malformed.class, () -> lc.measure(escape, 0, escape.length), "escape of 14 bits");
        assertThrows(MediaException.Unsupported.class, () -> new AacRawBlocks(23, 3, 512));
        assertThrows(MediaException.Malformed.class, () -> new AacRawBlocks(2, 13, 1024));
    }

    @Test
    @DisplayName("measures the shortest blocks and reports zeros as no block")
    void edges() {
        var walker = new AacRawBlocks(AacRawBlocks.AOT_LC, 3, 1024);
        assertEquals(1, walker.measure(new byte[]{(byte) 0xE0}, 0, 1));
        assertEquals(-1, walker.measure(new byte[0], 0, 0));
        assertEquals(-1, walker.measure(new byte[4096], 0, 4096));
        var fill = new byte[]{(byte) 0xDE, 0x7F, (byte) 0xFF};
        // a fill element of 15 + 127 - 1 bytes runs past the three bytes
        assertEquals(-1, walker.measure(fill, 0, fill.length));
        assertTrue(AacRawBlocks.supports(4));
        assertEquals(3, AacRawBlocks.samplingIndexFor(48000));
        assertEquals(4, AacRawBlocks.samplingIndexFor(44100));
        assertEquals(11, AacRawBlocks.samplingIndexFor(7350));
        assertEquals(0, AacRawBlocks.samplingIndexFor(192000));
    }
}
