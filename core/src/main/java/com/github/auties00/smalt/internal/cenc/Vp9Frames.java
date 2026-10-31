/* Ports Shaka Packager v3.9.3 (packager/media/codecs/vp9_parser.cc, the superframe index and the uncompressed header
 * syntax read to find where the compressed data of each frame starts). Copyright 2015 Google LLC; BSD 3-Clause
 * License. Modified for Smalt. */
package com.github.auties00.smalt.internal.cenc;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Finds the frames of VP9 samples and the uncompressed header of each, which Common Encryption keeps clear (the VP
 * Codec ISO Media File Format Binding, section "Common Encryption"): the compressed header and the tile data of a
 * frame are protected, the superframe index is clear.
 *
 * <p>The uncompressed header (VP9 bitstream specification section 6.2) is read with the frame sizes of the eight
 * reference slots, which the frames read before update through their {@code refresh_frame_flags}, so an inter frame
 * that takes its size from a reference ({@code found_ref}) is read with the right tile layout. A frame whose header
 * cannot be read (a damaged header, or an inter frame naming a reference slot no frame filled) reports its whole
 * size as header, and the caller leaves it clear. Instances keep the reference state of one stream and are not
 * thread-safe.
 */
public final class Vp9Frames {
    private static final int SYNC_CODE = 0x498342;
    private static final int COLOR_SPACE_RGB = 7;
    private static final int[] SEGMENT_FEATURE_BITS = {8, 6, 2, 0};
    private static final boolean[] SEGMENT_FEATURE_SIGNED = {true, true, false, false};

    private final int[] referenceWidths = new int[8];
    private final int[] referenceHeights = new int[8];
    private final int[] references = new int[3];
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);
    private int[] layout = new int[2 * 8 + 1];

    /**
     * Constructs a parser for one stream, with no reference frame known.
     */
    public Vp9Frames() {
        Arrays.fill(referenceWidths, -1);
        Arrays.fill(referenceHeights, -1);
    }

    /**
     * Divides a sample into its frames: the frames of a superframe, as its index lists them, or the sample as one
     * frame.
     *
     * <p>The result holds the frame count {@code n}, then for each frame its size and the size of its uncompressed
     * header (the whole frame for a frame that only shows an earlier one, or that cannot be read), then the size of
     * the superframe index, 0 for a single frame:
     * {@snippet :
     * [n, size0, header0, size1, header1, ..., indexSize]
     * }
     *
     * @param data the array holding the sample
     * @param offset the index of its first byte
     * @param length its size in bytes
     * @return the layout, in an array this parser reuses on the next call
     */
    public int[] divide(byte[] data, int offset, int length) {
        var count = 1;
        var indexSize = 0;
        var marker = length > 0 ? data[offset + length - 1] & 0xFF : 0;
        if ((marker & 0xE0) == 0xC0) {
            var frames = (marker & 7) + 1;
            var size = 2 + frames * (((marker >>> 3) & 3) + 1);
            if (length >= size && (data[offset + length - size] & 0xFF) == marker) {
                count = frames;
                indexSize = size;
            }
        }
        if (layout.length < 2 * count + 2) {
            layout = new int[2 * count + 2];
        }
        if (indexSize > 0) {
            var position = offset + length - indexSize + 1;
            var sizeBytes = ((marker >>> 3) & 3) + 1;
            long total = 0;
            for (var frame = 0; frame < count; frame++) {
                var size = 0L;
                for (var index = 0; index < sizeBytes; index++) {
                    size |= (long) (data[position++] & 0xFF) << (8 * index);
                }
                layout[1 + 2 * frame] = (int) Math.min(size, Integer.MAX_VALUE);
                total += size;
            }
            if (total + indexSize != length) {
                // not a valid index: the bytes are one frame whose last byte happens to look like a marker
                count = 1;
                indexSize = 0;
            }
        }
        if (indexSize == 0) {
            layout[1] = length;
        }
        layout[0] = count;
        var position = offset;
        for (var frame = 0; frame < count; frame++) {
            var size = layout[1 + 2 * frame];
            layout[2 + 2 * frame] = header(data, position, size);
            position += size;
        }
        layout[1 + 2 * count] = indexSize;
        return layout;
    }

    /**
     * Reads the uncompressed header of one frame and updates the reference slots it refreshes.
     *
     * @return the header size in bytes, or the frame size when the frame stays clear
     */
    private int header(byte[] data, int offset, int length) {
        reader.reset(data, offset, length);
        if (length == 0 || reader.read(2) != 2) {
            return length;
        }
        var profile = reader.readBit() | reader.readBit() << 1;
        if (profile == 3 && reader.readFlag()) {
            return length;
        }
        if (reader.readFlag()) {
            // show_existing_frame: the frame is its header
            return length;
        }
        var keyFrame = !reader.readFlag();
        var showFrame = reader.readFlag();
        var errorResilient = reader.readFlag();
        int width;
        int height;
        int refresh;
        if (keyFrame) {
            if (reader.read(24) != SYNC_CODE || !colorConfig(profile)) {
                return length;
            }
            width = reader.read(16) + 1;
            height = reader.read(16) + 1;
            renderSize();
            refresh = 0xFF;
        } else {
            var intraOnly = !showFrame && reader.readFlag();
            if (!errorResilient) {
                reader.skip(2);
            }
            if (intraOnly) {
                if (reader.read(24) != SYNC_CODE || profile > 0 && !colorConfig(profile)) {
                    return length;
                }
                refresh = reader.read(8);
                width = reader.read(16) + 1;
                height = reader.read(16) + 1;
                renderSize();
            } else {
                refresh = reader.read(8);
                for (var index = 0; index < 3; index++) {
                    references[index] = reader.read(3);
                    reader.skip(1);
                }
                width = -1;
                height = -1;
                for (var index = 0; index < 3; index++) {
                    if (reader.readFlag()) {
                        width = referenceWidths[references[index]];
                        height = referenceHeights[references[index]];
                        if (width < 0) {
                            return length;
                        }
                        break;
                    }
                }
                if (width < 0) {
                    width = reader.read(16) + 1;
                    height = reader.read(16) + 1;
                }
                renderSize();
                reader.skip(1);
                if (!reader.readFlag()) {
                    reader.skip(2);
                }
            }
        }
        if (!errorResilient) {
            reader.skip(2);
        }
        reader.skip(2);
        loopFilter();
        quantization();
        segmentation();
        tileInfo(width);
        var compressedHeader = reader.read(16);
        var headerBytes = (int) ((reader.bitPosition() + 7) >>> 3);
        if (reader.overrun() || compressedHeader == 0 || headerBytes + (long) compressedHeader > length) {
            return length;
        }
        for (var slot = 0; slot < 8; slot++) {
            if ((refresh >>> slot & 1) != 0) {
                referenceWidths[slot] = width;
                referenceHeights[slot] = height;
            }
        }
        return headerBytes;
    }

    /**
     * Reads {@code color_config()}; returns {@code false} for a reserved bit set.
     */
    private boolean colorConfig(int profile) {
        if (profile >= 2) {
            reader.skip(1);
        }
        var colorSpace = reader.read(3);
        if (colorSpace != COLOR_SPACE_RGB) {
            reader.skip(1);
            if (profile == 1 || profile == 3) {
                reader.skip(2);
                return !reader.readFlag();
            }
            return true;
        }
        return profile != 1 && profile != 3 || !reader.readFlag();
    }

    private void renderSize() {
        if (reader.readFlag()) {
            reader.skip(32);
        }
    }

    private void loopFilter() {
        reader.skip(6 + 3);
        if (reader.readFlag() && reader.readFlag()) {
            for (var index = 0; index < 4 + 2; index++) {
                if (reader.readFlag()) {
                    reader.skip(6 + 1);
                }
            }
        }
    }

    private void quantization() {
        reader.skip(8);
        for (var index = 0; index < 3; index++) {
            if (reader.readFlag()) {
                reader.skip(4 + 1);
            }
        }
    }

    private void segmentation() {
        if (!reader.readFlag()) {
            return;
        }
        if (reader.readFlag()) {
            for (var index = 0; index < 7; index++) {
                if (reader.readFlag()) {
                    reader.skip(8);
                }
            }
            if (reader.readFlag()) {
                for (var index = 0; index < 3; index++) {
                    if (reader.readFlag()) {
                        reader.skip(8);
                    }
                }
            }
        }
        if (reader.readFlag()) {
            reader.skip(1);
            for (var segment = 0; segment < 8; segment++) {
                for (var feature = 0; feature < 4; feature++) {
                    if (reader.readFlag()) {
                        reader.skip(SEGMENT_FEATURE_BITS[feature] + (SEGMENT_FEATURE_SIGNED[feature] ? 1 : 0));
                    }
                }
            }
        }
    }

    private void tileInfo(int width) {
        var miCols = (width + 7) >>> 3;
        var sb64Cols = (miCols + 7) >>> 3;
        var minLog2 = 0;
        while (64 << minLog2 < sb64Cols) {
            minLog2++;
        }
        var maxLog2 = 1;
        while (sb64Cols >>> maxLog2 >= 4) {
            maxLog2++;
        }
        maxLog2--;
        var log2 = minLog2;
        while (log2 < maxLog2 && reader.readFlag()) {
            log2++;
        }
        if (reader.readFlag()) {
            reader.skip(1);
        }
    }
}
