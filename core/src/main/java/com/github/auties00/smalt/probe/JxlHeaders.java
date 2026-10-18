package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.bits.LsbBitReader;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;

/**
 * Describes a JPEG XL file from its image header (ISO/IEC 18181-1 annex A), without decoding
 * frames.
 *
 * <p>The codestream is found directly (signature {@code FF 0A}) or in the {@code jxlc} box, or the
 * leading {@code jxlp} boxes, of an ISO-BMFF container. The size header and the image metadata
 * give the size, the orientation, the bit depth, alpha (an extra channel of type alpha) and
 * whether the image animates; the frames of an animation are not counted (that takes reading
 * every frame header), so their number is reported as unknown.
 */
final class JxlHeaders {
    /**
     * The number of leading codestream bytes read: far more than any image header needs.
     */
    private static final int HEADER_BYTES = 64 * 1024;

    /**
     * The largest number of container boxes examined.
     */
    private static final int MAX_BOXES = 1 << 12;

    /**
     * The width-to-height ratios a size header can signal, as numerator and denominator pairs;
     * ratio 0 means an explicit width.
     */
    private static final int[][] RATIOS = {{1, 1}, {1, 1}, {12, 10}, {4, 3}, {3, 2}, {16, 9}, {5, 4}, {2, 1}};

    /**
     * The extra channel type of alpha.
     */
    private static final int TYPE_ALPHA = 0;

    /**
     * The extra channel type of a spot color.
     */
    private static final int TYPE_SPOT_COLOR = 2;

    /**
     * The extra channel type of a color filter array channel.
     */
    private static final int TYPE_CFA = 5;

    /**
     * The box type of a whole codestream.
     */
    private static final int JXLC = FourCc.of("jxlc");

    /**
     * The box type of a partial codestream.
     */
    private static final int JXLP = FourCc.of("jxlp");

    /**
     * The reader of the leading codestream bytes.
     */
    private final LsbBitReader stream;

    /**
     * Constructs a reader over leading codestream bytes.
     *
     * @param data the codestream bytes, from the signature on
     */
    private JxlHeaders(byte[] data) {
        this.stream = new LsbBitReader(data);
    }

    /**
     * Describes a JPEG XL file.
     *
     * @param source the input
     * @return the header
     * @throws MediaException.Malformed if the headers are corrupt or truncated
     * @throws MediaException.Io if reading the source fails
     */
    static ImageHeaders.Header read(MediaSource source) {
        var reader = new JxlHeaders(codestream(source));
        if (reader.bits(16) != 0x0AFF) {
            throw new MediaException.Malformed("missing JPEG XL codestream signature");
        }
        return reader.imageHeader();
    }

    /**
     * Collects the leading bytes of the codestream, from a bare codestream or a container.
     *
     * @param source the input
     * @return up to {@link #HEADER_BYTES} codestream bytes
     * @throws MediaException.Malformed if the container is invalid or has no codestream
     */
    private static byte[] codestream(MediaSource source) {
        var reader = new SourceReader(source, 4096);
        var size = reader.size();
        if (reader.u8() == 0xFF) {
            reader.seek(0);
            return reader.bytes(Math.min(reader.remaining(), HEADER_BYTES), HEADER_BYTES);
        }
        reader.seek(0);
        var out = new ByteArrayOutputStream();
        for (var boxes = 0; boxes < MAX_BOXES && out.size() < HEADER_BYTES && reader.remaining() >= 8; boxes++) {
            var start = reader.position();
            var boxSize = reader.u32be();
            var type = reader.fourCc();
            var header = 8L;
            if (boxSize == 1) {
                boxSize = reader.u64be();
                header = 16;
            }
            var end = boxSize == 0 ? size : start + boxSize;
            if (boxSize != 0 && (boxSize < header || end > size || end < start)) {
                throw new MediaException.Malformed("invalid JPEG XL container box");
            }
            var body = end - start - header;
            if (type == JXLC) {
                out.writeBytes(reader.bytes(Math.min(body, HEADER_BYTES), HEADER_BYTES));
                break;
            }
            if (type == JXLP) {
                if (body < 4) {
                    throw new MediaException.Malformed("truncated JPEG XL partial codestream box");
                }
                reader.u32be();
                out.writeBytes(reader.bytes(Math.min(body - 4, HEADER_BYTES - out.size()), HEADER_BYTES));
            }
            if (boxSize == 0) {
                break;
            }
            reader.seek(end);
        }
        if (out.size() == 0) {
            throw new MediaException.Malformed("JPEG XL container without a codestream");
        }
        return out.toByteArray();
    }

    /**
     * Reads the size header and the image metadata.
     *
     * @return the header
     */
    private ImageHeaders.Header imageHeader() {
        var result = new ImageHeaders.Header();
        var size = size();
        result.width = size[0];
        result.height = size[1];
        result.bitDepth = 8;
        if (bool()) {
            return result;
        }
        if (bool()) {
            result.orientation = Orientation.ofExif(1 + (int) bits(3));
            if (bool()) {
                size();
            }
            if (bool()) {
                preview();
            }
            if (bool()) {
                u32(0, 100, 0, 1000, 10, 1, 30, 1);
                u32(0, 1, 0, 1001, 8, 1, 10, 1);
                var loops = u32(0, 0, 3, 0, 16, 0, 32, 0);
                bool();
                result.animated = true;
                result.frames = -1;
                result.loops = loops > Integer.MAX_VALUE ? 0 : (int) loops;
            }
        }
        result.bitDepth = bitDepth();
        bool();
        var extraChannels = u32(0, 0, 0, 1, 4, 2, 12, 1);
        for (var index = 0L; index < extraChannels && !result.alpha; index++) {
            result.alpha = extraChannelIsAlpha();
        }
        return result;
    }

    /**
     * Reads a size header.
     *
     * @return the width and the height
     * @throws MediaException.Malformed if the width does not fit an {@code int}
     */
    private int[] size() {
        var small = bool();
        var height = small ? 8 * (1 + bits(5)) : u32(9, 1, 13, 1, 18, 1, 30, 1);
        var ratio = (int) bits(3);
        long width;
        if (ratio != 0) {
            width = height * RATIOS[ratio][0] / RATIOS[ratio][1];
        } else {
            width = small ? 8 * (1 + bits(5)) : u32(9, 1, 13, 1, 18, 1, 30, 1);
        }
        if (width > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("JPEG XL image too wide");
        }
        return new int[]{(int) width, (int) height};
    }

    /**
     * Skips a preview header.
     */
    private void preview() {
        var div8 = bool();
        if (div8) {
            u32(0, 16, 0, 32, 5, 1, 9, 33);
        } else {
            u32(6, 1, 8, 65, 10, 321, 12, 1345);
        }
        if (bits(3) == 0) {
            if (div8) {
                u32(0, 16, 0, 32, 5, 1, 9, 33);
            } else {
                u32(6, 1, 8, 65, 10, 321, 12, 1345);
            }
        }
    }

    /**
     * Reads a bit depth.
     *
     * @return the bits per sample
     */
    private int bitDepth() {
        if (bool()) {
            var bits = (int) u32(0, 32, 0, 16, 0, 24, 6, 1);
            bits(4);
            return bits;
        }
        return (int) u32(0, 8, 0, 10, 0, 12, 6, 1);
    }

    /**
     * Reads one extra channel description.
     *
     * @return whether the channel is alpha
     */
    private boolean extraChannelIsAlpha() {
        if (bool()) {
            return true;
        }
        var type = (int) u32(0, 0, 0, 1, 4, 2, 6, 18);
        bitDepth();
        u32(0, 0, 0, 3, 0, 4, 3, 1);
        skip(u32(0, 0, 4, 0, 5, 16, 10, 48) * 8);
        if (type == TYPE_ALPHA) {
            bool();
        } else if (type == TYPE_SPOT_COLOR) {
            skip(4 * 16);
        } else if (type == TYPE_CFA) {
            u32(0, 1, 2, 0, 4, 3, 8, 19);
        }
        return type == TYPE_ALPHA;
    }

    /**
     * Reads a {@code U32} field: a 2-bit selector picks one of four distributions, each a number
     * of bits (zero for a constant) plus an offset.
     *
     * @param bits0 the bit count of distribution 0
     * @param offset0 the offset of distribution 0
     * @param bits1 the bit count of distribution 1
     * @param offset1 the offset of distribution 1
     * @param bits2 the bit count of distribution 2
     * @param offset2 the offset of distribution 2
     * @param bits3 the bit count of distribution 3
     * @param offset3 the offset of distribution 3
     * @return the value
     * @throws MediaException.Malformed if the header bytes end first
     */
    private long u32(int bits0, int offset0, int bits1, int offset1, int bits2, int offset2, int bits3, int offset3) {
        return switch ((int) bits(2)) {
            case 0 -> bits(bits0) + offset0;
            case 1 -> bits(bits1) + offset1;
            case 2 -> bits(bits2) + offset2;
            default -> bits(bits3) + offset3;
        };
    }

    /**
     * {@return whether the next bit is set}
     *
     * @throws MediaException.Malformed if the header bytes end first
     */
    private boolean bool() {
        return bits(1) != 0;
    }

    /**
     * Skips bits.
     *
     * @param count the number of bits to skip
     * @throws MediaException.Malformed if {@code count} is negative or the header bytes end first
     */
    private void skip(long count) {
        if (count < 0 || count > stream.bitsLeft()) {
            throw new MediaException.Malformed("truncated JPEG XL image header");
        }
        stream.skip(count);
    }

    /**
     * Reads bits, least significant first.
     *
     * @param count the number of bits, from 0 to 32
     * @return the unsigned value
     * @throws MediaException.Malformed if the header bytes end first
     */
    private long bits(int count) {
        if (count > stream.bitsLeft()) {
            throw new MediaException.Malformed("truncated JPEG XL image header");
        }
        return Integer.toUnsignedLong(stream.read(count));
    }
}
