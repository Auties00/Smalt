package com.github.auties00.smalt.internal.cenc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the frame layout of VP9 samples on hand-made uncompressed headers (VP9 bitstream specification section
 * 6.2), whose sizes the test knows bit by bit; the real streams are checked against Shaka Packager by
 * CencVideoGoldenTest.
 */
@DisplayName("VP9 frames")
class Vp9FramesTest {
    private static final int COMPRESSED_HEADER = 5;

    /**
     * A frame and the size its uncompressed header has.
     */
    private record Frame(byte[] data, int header) {
    }

    /**
     * Returns whether the tile info of a frame reads an {@code increment_tile_cols_log2} bit, which the test always
     * writes as 0.
     */
    private static boolean tileBit(int width) {
        var sb64Cols = ((width + 7) / 8 + 7) / 8;
        var minLog2 = 0;
        while (64 << minLog2 < sb64Cols) {
            minLog2++;
        }
        var maxLog2 = 1;
        while (sb64Cols >> maxLog2 >= 4) {
            maxLog2++;
        }
        return maxLog2 - 1 > minLog2;
    }

    /**
     * Ends a header: the context fields, loop filter, quantizer, segmentation and tile fields, and the compressed
     * header size, then the compressed header and some tile data. The loop filter and quantizer deltas are chosen
     * so that the header ends {@code residue} bits past a byte boundary: with 0, a reader that takes one bit more
     * ends in another byte, with 1 one that takes one bit less.
     */
    private static Frame finish(java.util.function.Supplier<HeaderBits> start, int width, boolean segmentation,
                                int residue) {
        var plain = body(start.get(), width, segmentation, 0, 0).bitLength();
        for (var filterDeltas = 0; filterDeltas <= 6; filterDeltas++) {
            for (var quantizerDeltas = 0; quantizerDeltas <= 3; quantizerDeltas++) {
                if ((plain + 7 * filterDeltas + 5 * quantizerDeltas) % 8 == residue) {
                    var bits = body(start.get(), width, segmentation, filterDeltas, quantizerDeltas);
                    var header = (bits.bitLength() + 7) / 8;
                    bits.bytes(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
                    return new Frame(bits.toByteArray(), header);
                }
            }
        }
        throw new AssertionError();
    }

    private static HeaderBits body(HeaderBits bits, int width, boolean segmentation, int filterDeltas,
                                   int quantizerDeltas) {
        bits.bits(1, 1).bits(1, 1).bits(0, 2);
        bits.bits(10, 6).bits(0, 3).flag(true).flag(true);
        for (var delta = 0; delta < 6; delta++) {
            bits.flag(delta < filterDeltas);
            if (delta < filterDeltas) {
                bits.bits(5, 6).bits(1, 1);
            }
        }
        bits.bits(60, 8);
        for (var delta = 0; delta < 3; delta++) {
            bits.flag(delta < quantizerDeltas);
            if (delta < quantizerDeltas) {
                bits.bits(3, 4).bits(0, 1);
            }
        }
        bits.flag(segmentation);
        if (segmentation) {
            bits.flag(true);
            for (var prob = 0; prob < 7; prob++) {
                bits.flag(prob % 2 == 0);
                if (prob % 2 == 0) {
                    bits.bits(128, 8);
                }
            }
            bits.flag(true);
            for (var prob = 0; prob < 3; prob++) {
                bits.flag(true).bits(64, 8);
            }
            bits.flag(true).bits(1, 1);
            for (var segment = 0; segment < 8; segment++) {
                for (var feature = 0; feature < 4; feature++) {
                    var enabled = (segment + feature) % 3 == 0;
                    bits.flag(enabled);
                    if (enabled) {
                        bits.bits(1, new int[]{8, 6, 2, 0}[feature] + (feature < 2 ? 1 : 0));
                    }
                }
            }
        }
        if (tileBit(width)) {
            bits.flag(false);
        }
        bits.flag(false);
        return bits.bits(COMPRESSED_HEADER, 16);
    }

    private static Frame keyFrame(int width, int height, boolean segmentation) {
        return finish(() -> new HeaderBits().bits(2, 2).bits(0, 2).flag(false).bits(0, 1).flag(true).flag(false)
                .bits(0x498342, 24).bits(1, 3).bits(0, 1).bits(width - 1, 16).bits(height - 1, 16).flag(false),
                width, segmentation, 0);
    }

    private static Frame intraOnly(int width, int height, int refresh) {
        return finish(() -> new HeaderBits().bits(2, 2).bits(0, 2).flag(false).bits(1, 1).flag(false).flag(false)
                .flag(true).bits(0, 2).bits(0x498342, 24).bits(refresh, 8).bits(width - 1, 16).bits(height - 1, 16)
                .flag(false), width, false, 0);
    }

    /**
     * An inter frame whose size comes from reference {@code found} of its three ({@code -1} for an explicit size of
     * {@code width}), and whose tile layout follows {@code tileWidth}.
     */
    private static Frame inter(int[] references, int found, int width, int tileWidth, int residue) {
        return finish(() -> {
            var bits = new HeaderBits().bits(2, 2).bits(0, 2).flag(false).bits(1, 1).flag(true).flag(false)
                    .bits(0, 2).bits(0, 8);
            for (var reference : references) {
                bits.bits(reference, 3).bits(0, 1);
            }
            for (var index = 0; index < 3; index++) {
                if (index == found) {
                    bits.flag(true);
                    break;
                }
                bits.flag(false);
            }
            if (found < 0) {
                bits.bits(width - 1, 16).bits(63, 16);
            }
            return bits.flag(false).flag(true).flag(true);
        }, tileWidth, false, residue);
    }

    private static byte[] superframe(Frame... frames) {
        var out = new ByteArrayOutputStream();
        for (var frame : frames) {
            out.writeBytes(frame.data());
        }
        var marker = 0xC0 | 1 << 3 | frames.length - 1;
        out.write(marker);
        for (var frame : frames) {
            out.write(frame.data().length);
            out.write(frame.data().length >>> 8);
        }
        out.write(marker);
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0} pixels wide")
    @ValueSource(ints = {16, 64, 255, 256, 640, 1920, 4096, 8192})
    @DisplayName("reads the uncompressed header of a key frame to its last bit")
    void keyFrames(int width) {
        for (var segmentation : new boolean[]{false, true}) {
            var frame = keyFrame(width, 64, segmentation);
            var layout = new Vp9Frames().divide(frame.data(), 0, frame.data().length);
            assertArrayEquals(new int[]{1, frame.data().length, frame.header(), 0}, Arrays.copyOf(layout, 4));
        }
    }

    @Test
    @DisplayName("takes the size of an inter frame from the reference it names, not from the last frame")
    void referenceSizes() {
        var parser = new Vp9Frames();
        // the key frame fills every slot at 64 pixels; the hidden intra-only frame puts 640 pixels in slot 1
        var key = keyFrame(64, 64, false);
        parser.divide(key.data(), 0, key.data().length);
        var intra = intraOnly(640, 64, 0b10);
        assertEquals(intra.header(), parser.divide(intra.data(), 0, intra.data().length)[2]);
        // an inter frame sized from slot 0 has one tile column at most, one sized from slot 1 reads a tile bit; each
        // header ends both on and just past a byte boundary, so a tile bit read or missed changes the size
        for (var residue = 0; residue <= 1; residue++) {
            var fromSlot0 = inter(new int[]{0, 1, 2}, 0, 0, 64, residue);
            assertEquals(fromSlot0.header(), parser.divide(fromSlot0.data(), 0, fromSlot0.data().length)[2]);
            var fromSlot1 = inter(new int[]{1, 0, 2}, 0, 0, 640, residue);
            assertEquals(fromSlot1.header(), parser.divide(fromSlot1.data(), 0, fromSlot1.data().length)[2]);
            var explicit = inter(new int[]{0, 0, 0}, -1, 1920, 1920, residue);
            assertEquals(explicit.header(), parser.divide(explicit.data(), 0, explicit.data().length)[2]);
        }
    }

    @Test
    @DisplayName("leaves clear an inter frame naming a reference no frame filled")
    void unknownReference() {
        var frame = inter(new int[]{3, 4, 5}, 1, 0, 64, 0);
        var layout = new Vp9Frames().divide(frame.data(), 0, frame.data().length);
        assertEquals(frame.data().length, layout[2]);
    }

    @Test
    @DisplayName("divides a superframe into its frames, the index clear, and keeps a shown frame clear")
    void superframes() {
        var parser = new Vp9Frames();
        var key = keyFrame(320, 240, false);
        var hidden = intraOnly(320, 240, 0b100);
        var show = new HeaderBits().bits(2, 2).bits(0, 2).flag(true).bits(2, 3).toByteArray();
        var data = superframe(key, hidden, new Frame(show, show.length));
        var layout = parser.divide(data, 0, data.length);
        assertArrayEquals(new int[]{3, key.data().length, key.header(), hidden.data().length, hidden.header(),
                show.length, show.length, 2 + 3 * 2}, Arrays.copyOf(layout, 8));
        // an offset into a larger array reads the same
        var shifted = new byte[data.length + 7];
        System.arraycopy(data, 0, shifted, 3, data.length);
        assertArrayEquals(Arrays.copyOf(layout, 8), Arrays.copyOf(new Vp9Frames().divide(shifted, 3, data.length),
                8));
    }

    @Test
    @DisplayName("reads a sample whose index does not match its frames as one frame")
    void invalidIndex() {
        var key = keyFrame(320, 240, false);
        var data = superframe(key, key);
        // the sizes no longer add up to the sample
        data[data.length - 4] ^= 1;
        var layout = new Vp9Frames().divide(data, 0, data.length);
        assertArrayEquals(new int[]{1, data.length, key.header(), 0}, Arrays.copyOf(layout, 4));
        // the first marker byte differs from the last
        data = superframe(key, key);
        data[data.length - 6] ^= 1;
        layout = new Vp9Frames().divide(data, 0, data.length);
        assertEquals(1, layout[0]);
        assertEquals(0, layout[3]);
    }

    @Test
    @DisplayName("leaves clear damaged, truncated and empty frames")
    void damaged() {
        var key = keyFrame(320, 240, false);
        var parser = new Vp9Frames();
        var marker = key.data().clone();
        marker[0] ^= (byte) 0x80;
        assertEquals(marker.length, parser.divide(marker, 0, marker.length)[2]);
        var sync = key.data().clone();
        sync[2] ^= 0x10;
        assertEquals(sync.length, parser.divide(sync, 0, sync.length)[2]);
        for (var length = 0; length < key.header() + COMPRESSED_HEADER; length++) {
            assertEquals(length, parser.divide(key.data(), 0, length)[2], "truncated to " + length);
        }
    }
}
