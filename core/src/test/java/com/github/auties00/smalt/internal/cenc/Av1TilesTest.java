package com.github.auties00.smalt.internal.cenc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the tile data AV1 samples report on hand-made still pictures (reduced still picture header, AV1
 * specification sections 5.5, 5.9 and 5.11), whose tile offsets the test knows; the real streams are checked
 * against Shaka Packager by CencVideoGoldenTest.
 */
@DisplayName("AV1 tiles")
class Av1TilesTest {
    private static final int OBU_SEQUENCE_HEADER = 1;
    private static final int OBU_FRAME_HEADER = 3;
    private static final int OBU_TILE_GROUP = 4;
    private static final int OBU_FRAME = 6;
    private static final int OBU_METADATA = 5;

    /**
     * A sample being built, with the tiles it holds as offset and size pairs.
     */
    private static final class Sample {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<int[]> tiles = new ArrayList<>();

        void obu(int type, byte[] payload) {
            out.write(type << 3 | 0b010);
            var size = payload.length;
            do {
                var b = size & 0x7F;
                size >>>= 7;
                out.write(size != 0 ? b | 0x80 : b);
            } while (size != 0);
            out.writeBytes(payload);
        }

        /**
         * Writes an OBU whose payload holds tiles at the given payload offsets.
         */
        void obu(int type, byte[] payload, List<int[]> payloadTiles) {
            obu(type, payload);
            var start = out.size() - payload.length;
            for (var tile : payloadTiles) {
                tiles.add(new int[]{start + tile[0], tile[1]});
            }
        }

        int[] expected() {
            var result = new int[1 + 2 * tiles.size()];
            result[0] = tiles.size();
            for (var index = 0; index < tiles.size(); index++) {
                result[1 + 2 * index] = tiles.get(index)[0];
                result[2 + 2 * index] = tiles.get(index)[1];
            }
            return result;
        }
    }

    private static byte[] sequenceHeader(int width, int height) {
        return new HeaderBits().bits(0, 3).flag(true).flag(true).bits(8, 5).bits(7, 4).bits(7, 4)
                .bits(width - 1, 8).bits(height - 1, 8).flag(false).flag(true).flag(true)
                .flag(false).flag(false).flag(false)
                .flag(false).flag(false).flag(false).flag(false).bits(0, 2).flag(false)
                .flag(false).trailing().toByteArray();
    }

    /**
     * Writes the uncompressed header of a still picture: the tile info the caller writes, then the quantizer, the
     * segmentation and, when the picture is not lossless, the loop filter and transform mode fields.
     */
    private static HeaderBits header(int baseQ, java.util.function.Consumer<HeaderBits> tileInfo) {
        var bits = new HeaderBits().flag(false).flag(false).flag(false);
        tileInfo.accept(bits);
        bits.bits(baseQ, 8).flag(false).flag(false).flag(false).flag(false);
        bits.flag(false);
        if (baseQ > 0) {
            bits.flag(false);
            // the second plane levels follow a nonzero first level
            bits.bits(20, 6).bits(0, 6).bits(5, 6).bits(5, 6).bits(3, 3).flag(false);
            bits.flag(true);
        }
        return bits.flag(true);
    }

    /**
     * Writes the tile group after a byte-aligned header: the tile start and end when asked, then each tile, its
     * size in two bytes before every tile but the last; returns the tiles' offsets in the payload.
     */
    private static List<int[]> tileGroup(HeaderBits bits, int tilesInFrame, int first, int last, int[] sizes,
                                         boolean startAndEnd) {
        if (tilesInFrame > 1) {
            bits.flag(startAndEnd);
            if (startAndEnd) {
                bits.bits(first, 2).bits(last, 2);
            }
        }
        bits.align();
        var tiles = new ArrayList<int[]>();
        for (var tile = 0; tile < sizes.length; tile++) {
            if (tile < sizes.length - 1) {
                bits.bits(sizes[tile] - 1 & 0xFF, 8).bits(sizes[tile] - 1 >>> 8, 8);
            }
            tiles.add(new int[]{bits.bitLength() / 8, sizes[tile]});
            var data = new byte[sizes[tile]];
            Arrays.fill(data, (byte) (0x30 + tile));
            bits.bytes(data);
        }
        return tiles;
    }

    private static void uniformFourTiles(HeaderBits bits) {
        bits.flag(true).flag(true).flag(true).bits(0, 2).bits(1, 2);
    }

    @ParameterizedTest(name = "base_q_idx {0}")
    @ValueSource(ints = {0, 1, 100, 255})
    @DisplayName("finds the tile of a one-tile still picture, lossless or not")
    void oneTile(int baseQ) {
        var sample = new Sample();
        sample.obu(OBU_SEQUENCE_HEADER, sequenceHeader(64, 64));
        var bits = header(baseQ, tileInfo -> tileInfo.flag(true)).align();
        var tiles = tileGroup(bits, 1, 0, 0, new int[]{37}, false);
        sample.obu(OBU_FRAME, bits.toByteArray(), tiles);
        var data = sample.out.toByteArray();
        assertArrayEquals(sample.expected(), Arrays.copyOf(new Av1Tiles().find(data, 0, data.length), 3));
    }

    @Test
    @DisplayName("finds four uniformly spaced tiles behind their two-byte sizes, after metadata")
    void fourTiles() {
        var sample = new Sample();
        sample.obu(OBU_SEQUENCE_HEADER, sequenceHeader(128, 128));
        sample.obu(OBU_METADATA, new byte[]{1, 2, 3, (byte) 0x80});
        var bits = header(90, Av1TilesTest::uniformFourTiles).align();
        var tiles = tileGroup(bits, 4, 0, 3, new int[]{300, 17, 1, 250}, false);
        sample.obu(OBU_FRAME, bits.toByteArray(), tiles);
        var data = sample.out.toByteArray();
        assertArrayEquals(sample.expected(), Arrays.copyOf(new Av1Tiles().find(data, 0, data.length), 9));
        // the same sample inside a larger array
        var shifted = new byte[data.length + 11];
        System.arraycopy(data, 0, shifted, 5, data.length);
        assertArrayEquals(sample.expected(), Arrays.copyOf(new Av1Tiles().find(shifted, 5, data.length), 9));
    }

    @Test
    @DisplayName("finds the tiles of two columns of explicit widths")
    void explicitSpacing() {
        var sample = new Sample();
        sample.obu(OBU_SEQUENCE_HEADER, sequenceHeader(128, 128));
        // two columns one superblock wide, one row two superblocks high
        var bits = header(40, tileInfo -> tileInfo.flag(false).bits(0, 1).bits(1, 1).bits(0, 1).bits(1, 2)).align();
        var tiles = tileGroup(bits, 2, 0, 1, new int[]{64, 80}, false);
        sample.obu(OBU_FRAME, bits.toByteArray(), tiles);
        var data = sample.out.toByteArray();
        assertArrayEquals(sample.expected(), Arrays.copyOf(new Av1Tiles().find(data, 0, data.length), 5));
    }

    @Test
    @DisplayName("finds the tiles of a frame header followed by two tile groups")
    void tileGroups() {
        var sample = new Sample();
        sample.obu(OBU_SEQUENCE_HEADER, sequenceHeader(128, 128));
        sample.obu(OBU_FRAME_HEADER, header(90, Av1TilesTest::uniformFourTiles).trailing().toByteArray());
        var first = new HeaderBits();
        var firstTiles = tileGroup(first, 4, 0, 1, new int[]{40, 41}, true);
        sample.obu(OBU_TILE_GROUP, first.toByteArray(), firstTiles);
        var second = new HeaderBits();
        var secondTiles = tileGroup(second, 4, 2, 3, new int[]{42, 43}, true);
        sample.obu(OBU_TILE_GROUP, second.toByteArray(), secondTiles);
        var data = sample.out.toByteArray();
        var parser = new Av1Tiles();
        assertArrayEquals(sample.expected(), Arrays.copyOf(parser.find(data, 0, data.length), 9));
        // the next temporal unit is read with the same sequence header
        assertArrayEquals(sample.expected(), Arrays.copyOf(parser.find(data, 0, data.length), 9));
    }

    @Test
    @DisplayName("reports no tiles for a frame before any sequence header, or damaged OBUs, and recovers")
    void unreadable() {
        var sequence = new Sample();
        sequence.obu(OBU_SEQUENCE_HEADER, sequenceHeader(64, 64));
        var frame = new Sample();
        var bits = header(100, tileInfo -> tileInfo.flag(true)).align();
        var tiles = tileGroup(bits, 1, 0, 0, new int[]{20}, false);
        frame.obu(OBU_FRAME, bits.toByteArray(), tiles);
        var frameData = frame.out.toByteArray();
        var parser = new Av1Tiles();
        assertNull(parser.find(frameData, 0, frameData.length));
        var full = new ByteArrayOutputStream();
        full.writeBytes(sequence.out.toByteArray());
        full.writeBytes(frameData);
        var data = full.toByteArray();
        var forbidden = data.clone();
        forbidden[0] |= (byte) 0x80;
        assertNull(parser.find(forbidden, 0, forbidden.length));
        // an OBU size running past the sample
        assertNull(parser.find(data, 0, data.length - 1));
        var expected = parser.find(data, 0, data.length);
        assertArrayEquals(new int[]{1, data.length - 20, 20}, Arrays.copyOf(expected, 3));
        // a configuration record carries the sequence header for samples without one
        var configured = new Av1Tiles();
        var record = new byte[4 + sequence.out.size()];
        record[0] = (byte) 0x81;
        System.arraycopy(sequence.out.toByteArray(), 0, record, 4, sequence.out.size());
        configured.configure(record);
        assertArrayEquals(new int[]{1, frameData.length - 20, 20}, Arrays.copyOf(configured.find(frameData, 0,
                frameData.length), 3));
    }
}
