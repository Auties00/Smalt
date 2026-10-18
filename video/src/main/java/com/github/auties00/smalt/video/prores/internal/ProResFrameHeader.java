package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.Arrays;

/**
 * Holds the frame header of a ProRes frame (SMPTE RDD 36 clauses 5.1.1 and 6.1.1) and reads and
 * writes it.
 *
 * <p>A frame starts with its 32-bit size and the identifier {@code icpf}, followed by the frame
 * header, whose first field is its own size; the first picture follows the header.
 *
 * @param frameSize the size of the frame in bytes, from the start of the size field
 * @param headerSize the size of the frame header in bytes
 * @param version the bitstream version, 0 or 1
 * @param encoder the encoder identifier, a four-character code
 * @param width the frame width in luma samples
 * @param height the frame height in luma samples
 * @param chromaFormat {@code 2} for 4:2:2, {@code 3} for 4:4:4
 * @param interlaceMode {@code 0} progressive, {@code 1} top field first, {@code 2} bottom field
 * first
 * @param aspectRatio the aspect ratio information code
 * @param frameRate the frame rate code
 * @param primaries the color primaries code (ITU-T H.273)
 * @param transfer the transfer characteristic code (ITU-T H.273)
 * @param matrix the matrix coefficients code (ITU-T H.273)
 * @param alphaType {@code 0} no alpha, {@code 1} 8-bit alpha, {@code 2} 16-bit alpha
 * @param lumaMatrix the 64 luma quantization weights in raster order
 * @param chromaMatrix the 64 chroma quantization weights in raster order
 */
public record ProResFrameHeader(int frameSize, int headerSize, int version, int encoder, int width, int height,
                                int chromaFormat, int interlaceMode, int aspectRatio, int frameRate, int primaries,
                                int transfer, int matrix, int alphaType, int[] lumaMatrix, int[] chromaMatrix) {
    /**
     * The frame identifier {@code icpf}.
     */
    public static final int IDENTIFIER = 0x69637066;

    /**
     * The size of a frame header without quantization matrices.
     */
    public static final int BASE_HEADER_SIZE = 20;

    /**
     * The quantization weights used when a frame header loads no matrix.
     */
    private static final int[] DEFAULT_MATRIX = filled(4);

    /**
     * Parses the frame header at the start of a frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's size field
     * @param length the number of bytes available from {@code offset}
     * @return the header
     * @throws MediaException.Malformed if the frame is truncated or a field is invalid
     * @throws MediaException.Unsupported if the bitstream version is newer than 1
     */
    public static ProResFrameHeader parse(byte[] data, int offset, int length) {
        if (length < 8 + BASE_HEADER_SIZE) {
            throw new MediaException.Malformed("ProRes frame of " + length + " bytes is truncated");
        }
        var frameSize = ByteArrays.i32be(data, offset);
        if (ByteArrays.i32be(data, offset + 4) != IDENTIFIER) {
            throw new MediaException.Malformed("ProRes frame lacks the icpf identifier");
        }
        if (frameSize < 8 + BASE_HEADER_SIZE || frameSize > length) {
            throw new MediaException.Malformed("ProRes frame size " + Integer.toUnsignedString(frameSize)
                    + " does not fit the " + length + " bytes of the packet");
        }
        var at = offset + 8;
        var headerSize = ByteArrays.u16be(data, at);
        if (headerSize < BASE_HEADER_SIZE || headerSize > frameSize - 8) {
            throw new MediaException.Malformed("invalid ProRes frame header size " + headerSize);
        }
        var version = data[at + 3] & 0xFF;
        if (version > 1) {
            throw new MediaException.Unsupported("prores", "bitstream version " + version);
        }
        var encoder = ByteArrays.i32be(data, at + 4);
        var width = ByteArrays.u16be(data, at + 8);
        var height = ByteArrays.u16be(data, at + 10);
        var flags = data[at + 12] & 0xFF;
        var chromaFormat = flags >>> 6;
        var interlaceMode = (flags >>> 2) & 3;
        var codes = data[at + 13] & 0xFF;
        var primaries = data[at + 14] & 0xFF;
        var transfer = data[at + 15] & 0xFF;
        var matrix = data[at + 16] & 0xFF;
        var alphaType = data[at + 17] & 0x0F;
        var load = data[at + 19] & 0xFF;
        if (width == 0 || height == 0) {
            throw new MediaException.Malformed("invalid ProRes frame size " + width + "x" + height);
        }
        if (chromaFormat != 2 && chromaFormat != 3) {
            throw new MediaException.Malformed("reserved ProRes chroma format " + chromaFormat);
        }
        if (interlaceMode == 3) {
            throw new MediaException.Malformed("reserved ProRes interlace mode");
        }
        if (alphaType > 2) {
            throw new MediaException.Malformed("reserved ProRes alpha channel type " + alphaType);
        }
        var cursor = at + BASE_HEADER_SIZE;
        var end = at + headerSize;
        var luma = DEFAULT_MATRIX;
        if ((load & 2) != 0) {
            luma = readMatrix(data, cursor, end);
            cursor += 64;
        }
        var chroma = luma;
        if ((load & 1) != 0) {
            chroma = readMatrix(data, cursor, end);
        }
        return new ProResFrameHeader(frameSize, headerSize, version, encoder, width, height, chromaFormat,
                interlaceMode, codes >>> 4, codes & 15, primaries, transfer, matrix, alphaType, luma, chroma);
    }

    /**
     * Writes a frame size, identifier and frame header that loads both quantization matrices.
     *
     * @param out the destination
     * @param offset the index of the frame size field
     * @return the index one past the header
     */
    public int write(byte[] out, int offset) {
        ByteArrays.put32be(out, offset, frameSize);
        ByteArrays.put32be(out, offset + 4, IDENTIFIER);
        var at = offset + 8;
        ByteArrays.put16be(out, at, headerSize);
        out[at + 2] = 0;
        out[at + 3] = (byte) version;
        ByteArrays.put32be(out, at + 4, encoder);
        ByteArrays.put16be(out, at + 8, width);
        ByteArrays.put16be(out, at + 10, height);
        out[at + 12] = (byte) (chromaFormat << 6 | interlaceMode << 2);
        out[at + 13] = (byte) (aspectRatio << 4 | frameRate);
        out[at + 14] = (byte) primaries;
        out[at + 15] = (byte) transfer;
        out[at + 16] = (byte) matrix;
        out[at + 17] = (byte) alphaType;
        out[at + 18] = 0;
        out[at + 19] = 3;
        for (var i = 0; i < 64; i++) {
            out[at + BASE_HEADER_SIZE + i] = (byte) lumaMatrix[i];
            out[at + BASE_HEADER_SIZE + 64 + i] = (byte) chromaMatrix[i];
        }
        return at + headerSize;
    }

    /**
     * Returns the number of field pictures of the frame.
     *
     * @return {@code 1} for a progressive frame, {@code 2} for an interlaced one
     */
    public int pictureCount() {
        return interlaceMode == 0 ? 1 : 2;
    }

    /**
     * Returns the height of one picture of the frame (RDD 36 clause 6.2).
     *
     * @param temporalIndex {@code 0} for the first picture, {@code 1} for the second
     * @return the picture height in luma rows
     */
    public int pictureHeight(int temporalIndex) {
        if (interlaceMode == 0) {
            return height;
        }
        var top = (interlaceMode == 1) == (temporalIndex == 0);
        return top ? (height + 1) / 2 : height / 2;
    }

    /**
     * Returns whether a picture of the frame holds the top field.
     *
     * @param temporalIndex {@code 0} for the first picture, {@code 1} for the second
     * @return {@code true} for the top field or a progressive frame
     */
    public boolean isTopField(int temporalIndex) {
        return interlaceMode == 0 || (interlaceMode == 1) == (temporalIndex == 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProResFrameHeader that && frameSize == that.frameSize && headerSize == that.headerSize
                && version == that.version && encoder == that.encoder && width == that.width && height == that.height
                && chromaFormat == that.chromaFormat && interlaceMode == that.interlaceMode
                && aspectRatio == that.aspectRatio && frameRate == that.frameRate && primaries == that.primaries
                && transfer == that.transfer && matrix == that.matrix && alphaType == that.alphaType
                && Arrays.equals(lumaMatrix, that.lumaMatrix) && Arrays.equals(chromaMatrix, that.chromaMatrix);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Integer.hashCode(frameSize) + Arrays.hashCode(lumaMatrix)) + Arrays.hashCode(chromaMatrix);
    }

    @Override
    public String toString() {
        return "ProResFrameHeader[" + width + "x" + height + ", chroma=" + chromaFormat + ", interlace="
                + interlaceMode + ", alpha=" + alphaType + ", size=" + frameSize + "]";
    }

    /**
     * Reads a quantization matrix.
     *
     * @param data the frame
     * @param offset the index of the matrix
     * @param end the index one past the frame header
     * @return the 64 weights
     * @throws MediaException.Malformed if the matrix overruns the header or a weight is below 2
     */
    private static int[] readMatrix(byte[] data, int offset, int end) {
        if (offset + 64 > end) {
            throw new MediaException.Malformed("ProRes quantization matrix overruns the frame header");
        }
        var matrix = new int[64];
        for (var i = 0; i < 64; i++) {
            matrix[i] = data[offset + i] & 0xFF;
            if (matrix[i] < 2) {
                throw new MediaException.Malformed("invalid ProRes quantization weight " + matrix[i]);
            }
        }
        return matrix;
    }

    /**
     * Returns an array of 64 copies of a value.
     *
     * @param value the weight of every position
     * @return the array
     */
    private static int[] filled(int value) {
        var result = new int[64];
        Arrays.fill(result, value);
        return result;
    }
}
