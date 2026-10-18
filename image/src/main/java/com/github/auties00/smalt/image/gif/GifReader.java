package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;

/**
 * Parses the block structure of a GIF87a or GIF89a datastream.
 *
 * <p>{@link #readHeader()} reads the signature, the logical screen descriptor and the global
 * color table; each {@link #nextFrame(GifFrame, boolean)} then reads blocks up to and including
 * the next image. Damaged files are read as far as they go:
 * <ul>
 *   <li>the datastream ends at the trailer, at the end of the input (a missing trailer is
 *       fine), or at any byte that introduces no known block;</li>
 *   <li>an input that ends inside an image's data sub-blocks yields that image with the data
 *       read so far, flagged {@link GifFrame#truncated}, and then ends; one that ends earlier in
 *       an image, or inside an extension, just ends;</li>
 *   <li>comment, plain text and unknown application extensions are skipped; a graphic control
 *       extension applies to the next image (the last one wins when several precede it); the
 *       loop count comes from the first {@code NETSCAPE2.0} or {@code ANIMEXTS1.0}
 *       extension.</li>
 * </ul>
 */
final class GifReader {
    /**
     * The GIF block introducer of an image descriptor.
     */
    private static final int IMAGE = 0x2C;

    /**
     * The GIF block introducer of an extension.
     */
    private static final int EXTENSION = 0x21;

    /**
     * The label of the graphic control extension.
     */
    private static final int GRAPHIC_CONTROL = 0xF9;

    /**
     * The label of the application extension.
     */
    private static final int APPLICATION = 0xFF;

    /**
     * The buffered cursor over the input.
     */
    private final SourceReader in;

    /**
     * The limits bounding the frame data buffer.
     */
    private final MediaLimits limits;

    /**
     * Scratch space for fixed-size fields and sub-blocks.
     */
    private final byte[] scratch;

    /**
     * The global color table as packed little-endian RGBA pixels.
     */
    private final int[] globalColors;

    /**
     * The number of global color table entries, {@code 0} when there is none.
     */
    private int globalCount;

    /**
     * The logical screen width.
     */
    private int screenWidth;

    /**
     * The logical screen height.
     */
    private int screenHeight;

    /**
     * The loop count of the first looping application extension, or {@code -1}.
     */
    private int loopCount;

    /**
     * Whether a graphic control extension is waiting for its image.
     */
    private boolean controlPending;

    /**
     * The transparent index of the pending graphic control, or {@code -1}.
     */
    private int pendingTransparent;

    /**
     * The disposal method of the pending graphic control.
     */
    private int pendingDisposal;

    /**
     * The delay of the pending graphic control.
     */
    private int pendingDelay;

    /**
     * Whether the datastream has ended.
     */
    private boolean ended;

    /**
     * Constructs a reader positioned at the start of the datastream.
     *
     * @param source the input
     * @param limits the limits bounding frame data buffers
     */
    GifReader(MediaSource source, MediaLimits limits) {
        this.in = new SourceReader(source);
        this.limits = limits;
        this.scratch = new byte[768];
        this.globalColors = new int[256];
        this.loopCount = -1;
        this.pendingTransparent = -1;
    }

    /**
     * Returns the logical screen width.
     *
     * @return the width as stored, possibly zero
     */
    int screenWidth() {
        return screenWidth;
    }

    /**
     * Returns the logical screen height.
     *
     * @return the height as stored, possibly zero
     */
    int screenHeight() {
        return screenHeight;
    }

    /**
     * Returns the loop count read so far.
     *
     * @return the count stored by the first looping application extension, or {@code -1} when
     * none has been read
     */
    int loopCount() {
        return loopCount;
    }

    /**
     * Reads the signature, the logical screen descriptor and the global color table.
     *
     * @throws MediaException.Malformed if the signature is wrong or the header is truncated
     */
    void readHeader() {
        if (!read(scratch, 13)) {
            throw new MediaException.Malformed("input is too short to be a GIF datastream");
        }
        if (scratch[0] != 'G' || scratch[1] != 'I' || scratch[2] != 'F' || scratch[3] != '8'
                || (scratch[4] != '7' && scratch[4] != '9') || scratch[5] != 'a') {
            throw new MediaException.Malformed("missing GIF signature");
        }
        screenWidth = ByteArrays.u16le(scratch, 6);
        screenHeight = ByteArrays.u16le(scratch, 8);
        var packed = scratch[10] & 0xFF;
        if ((packed & 0x80) != 0) {
            globalCount = 2 << (packed & 7);
            if (!readColors(globalColors, globalCount)) {
                throw new MediaException.Malformed("GIF global color table is truncated");
            }
        }
    }

    /**
     * Reads blocks up to and including the next image.
     *
     * @param frame the frame to fill
     * @param withData whether the data sub-blocks are stored; when {@code false} they are skipped
     * @return {@code true} when an image was read, {@code false} at the end of the datastream
     * @throws MediaException.LimitExceeded if the image data exceeds the allocation limit
     */
    boolean nextFrame(GifFrame frame, boolean withData) {
        while (!ended) {
            var introducer = read();
            switch (introducer) {
                case IMAGE -> {
                    if (readImage(frame, withData)) {
                        return true;
                    }
                    ended = true;
                }
                case EXTENSION -> {
                    if (!readExtension()) {
                        ended = true;
                    }
                }
                default -> ended = true;
            }
        }
        return false;
    }

    /**
     * Reads an image descriptor, its local color table and its data.
     *
     * @param frame the frame to fill
     * @param withData whether the data sub-blocks are stored
     * @return {@code false} when the input ends before the image data starts
     */
    private boolean readImage(GifFrame frame, boolean withData) {
        if (!read(scratch, 9)) {
            return false;
        }
        frame.left = ByteArrays.u16le(scratch, 0);
        frame.top = ByteArrays.u16le(scratch, 2);
        frame.width = ByteArrays.u16le(scratch, 4);
        frame.height = ByteArrays.u16le(scratch, 6);
        var packed = scratch[8] & 0xFF;
        frame.interlaced = (packed & 0x40) != 0;
        if ((packed & 0x80) != 0) {
            frame.colorCount = 2 << (packed & 7);
            if (!readColors(frame.colors, frame.colorCount)) {
                return false;
            }
        } else {
            frame.colorCount = globalCount;
            System.arraycopy(globalColors, 0, frame.colors, 0, globalCount);
        }
        frame.transparentIndex = controlPending ? pendingTransparent : -1;
        frame.disposal = controlPending ? pendingDisposal : GifFrame.DISPOSE_NONE;
        frame.delay = controlPending ? pendingDelay : 0;
        controlPending = false;
        var codeSize = read();
        if (codeSize < 0) {
            return false;
        }
        frame.minimumCodeSize = codeSize;
        frame.dataLength = 0;
        frame.truncated = false;
        while (true) {
            var size = read();
            if (size < 0) {
                frame.truncated = true;
                ended = true;
                return true;
            }
            if (size == 0) {
                return true;
            }
            if (withData) {
                ensureData(frame, size);
            }
            var target = withData ? frame.data : scratch;
            var offset = withData ? frame.dataLength : 0;
            var got = readUpTo(target, offset, size);
            if (withData) {
                frame.dataLength += got;
            }
            if (got < size) {
                frame.truncated = true;
                ended = true;
                return true;
            }
        }
    }

    /**
     * Grows the data buffer of a frame to hold {@code extra} more bytes.
     *
     * @param frame the frame whose compressed data grows
     * @param extra the number of bytes about to be appended
     * @throws MediaException.LimitExceeded if the buffer would exceed the allocation limit
     */
    private void ensureData(GifFrame frame, int extra) {
        var needed = (long) frame.dataLength + extra;
        if (needed > frame.data.length) {
            var capacity = Math.max(needed, frame.data.length * 2L);
            LimitChecks.checkAllocation(limits, capacity);
            frame.data = Arrays.copyOf(frame.data, (int) capacity);
        }
    }

    /**
     * Reads an extension block.
     *
     * @return {@code false} when the input ends inside the extension
     */
    private boolean readExtension() {
        var label = read();
        if (label < 0) {
            return false;
        }
        if (label == GRAPHIC_CONTROL) {
            var size = read();
            if (size < 0 || !read(scratch, size)) {
                return false;
            }
            if (size >= 4) {
                var packed = scratch[0] & 0xFF;
                controlPending = true;
                pendingTransparent = (packed & 1) != 0 ? scratch[3] & 0xFF : -1;
                pendingDelay = ByteArrays.u16le(scratch, 1);
                pendingDisposal = switch ((packed >>> 2) & 7) {
                    case 2 -> GifFrame.DISPOSE_BACKGROUND;
                    case 3, 4 -> GifFrame.DISPOSE_PREVIOUS;
                    default -> GifFrame.DISPOSE_NONE;
                };
            }
            return size == 0 || skipSubBlocks();
        }
        if (label == APPLICATION) {
            var size = read();
            if (size < 0 || !read(scratch, size)) {
                return false;
            }
            if (size == 0) {
                return true;
            }
            var looping = size == 11 && (identifier("NETSCAPE2.0") || identifier("ANIMEXTS1.0"));
            while (true) {
                var block = read();
                if (block < 0 || !read(scratch, block)) {
                    return false;
                }
                if (block == 0) {
                    return true;
                }
                if (looping && block >= 3 && scratch[0] == 1 && loopCount < 0) {
                    loopCount = ByteArrays.u16le(scratch, 1);
                }
            }
        }
        return skipSubBlocks();
    }

    /**
     * Returns whether the first 11 scratch bytes spell an application identifier.
     *
     * @param identifier the identifier and authentication code
     * @return whether they match
     */
    private boolean identifier(String identifier) {
        for (var i = 0; i < 11; i++) {
            if (scratch[i] != identifier.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Skips data sub-blocks up to and including the terminator.
     *
     * @return {@code false} when the input ends first
     */
    private boolean skipSubBlocks() {
        while (true) {
            var size = read();
            if (size < 0) {
                return false;
            }
            if (size == 0) {
                return true;
            }
            if (readUpTo(scratch, 0, size) < size) {
                return false;
            }
        }
    }

    /**
     * Reads a color table.
     *
     * @param colors the destination, packed little-endian RGBA with alpha 255
     * @param count the number of entries
     * @return {@code false} when the input ends first
     */
    private boolean readColors(int[] colors, int count) {
        if (!read(scratch, count * 3)) {
            return false;
        }
        for (var i = 0; i < count; i++) {
            colors[i] = (scratch[i * 3] & 0xFF) | (scratch[i * 3 + 1] & 0xFF) << 8 | (scratch[i * 3 + 2] & 0xFF) << 16
                    | 0xFF00_0000;
        }
        return true;
    }

    /**
     * Reads one byte.
     *
     * @return the byte, or {@code -1} at the end of the input
     */
    private int read() {
        return in.atEnd() ? -1 : in.u8();
    }

    /**
     * Reads exactly {@code length} bytes into the start of a buffer.
     *
     * @param target the destination
     * @param length the number of bytes
     * @return {@code false} when the input ends first
     */
    private boolean read(byte[] target, int length) {
        return readUpTo(target, 0, length) == length;
    }

    /**
     * Reads up to {@code length} bytes, stopping early only at the end of the input.
     *
     * @param target the destination
     * @param offset the index of the first byte written
     * @param length the number of bytes wanted
     * @return the number of bytes read
     */
    private int readUpTo(byte[] target, int offset, int length) {
        var available = in.remaining();
        if (available >= length && available != Long.MAX_VALUE) {
            in.readFully(target, offset, length);
            return length;
        }
        var count = 0;
        while (count < length && !in.atEnd()) {
            target[offset + count++] = (byte) in.u8();
        }
        return count;
    }
}
