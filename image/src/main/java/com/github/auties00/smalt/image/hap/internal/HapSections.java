package com.github.auties00.smalt.image.hap.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Parses the sections of a HAP frame (the Vidvox HAP video specification) into the textures they hold.
 *
 * <p>Instances are reused from frame to frame and are not thread-safe. Every offset is checked against the frame:
 * a section that does not fit raises {@link MediaException.Malformed}.
 */
public final class HapSections {
    /**
     * The texture format code of RGB DXT1 (BC1).
     */
    public static final int RGB_DXT1 = 0x0B;

    /**
     * The texture format code of RGBA DXT5 (BC3).
     */
    public static final int RGBA_DXT5 = 0x0E;

    /**
     * The texture format code of scaled YCoCg DXT5.
     */
    public static final int YCOCG_DXT5 = 0x0F;

    /**
     * The texture format code of alpha RGTC1 (BC4).
     */
    public static final int ALPHA_RGTC1 = 0x01;

    /**
     * The texture format code of RGBA BPTC (BC7).
     */
    public static final int RGBA_BPTC = 0x0C;

    /**
     * The texture format code of RGB unsigned float BPTC (BC6U).
     */
    public static final int RGB_BPTC_UNSIGNED = 0x02;

    /**
     * The texture format code of RGB signed float BPTC (BC6S).
     */
    public static final int RGB_BPTC_SIGNED = 0x03;

    /**
     * The second-stage compressor code of uncompressed data.
     */
    public static final int NONE = 0x0A;

    /**
     * The second-stage compressor code of Snappy.
     */
    public static final int SNAPPY = 0x0B;

    /**
     * The second-stage compressor code that defers to the decode instructions.
     */
    public static final int COMPLEX = 0x0C;

    /**
     * The section type of a multiple-image section.
     */
    public static final int MULTIPLE_IMAGES = 0x0D;

    /**
     * The section type of the decode instructions container.
     */
    private static final int INSTRUCTIONS = 0x01;

    /**
     * The section type of the chunk second-stage compressor table.
     */
    private static final int COMPRESSOR_TABLE = 0x02;

    /**
     * The section type of the chunk size table.
     */
    private static final int SIZE_TABLE = 0x03;

    /**
     * The section type of the chunk offset table.
     */
    private static final int OFFSET_TABLE = 0x04;

    /**
     * The textures of the frame, at most two.
     */
    private final Texture[] textures = {new Texture(), new Texture()};

    /**
     * The number of textures of the frame.
     */
    private int count;

    /**
     * The fields of the section header last read: its type, the start of its data and its end.
     */
    private int type;

    private int start;

    private int end;

    /**
     * Constructs a parser.
     */
    public HapSections() {
    }

    /**
     * One texture of a frame: its format and where its data is.
     */
    public static final class Texture {
        /**
         * The texture format code.
         */
        public int format;

        /**
         * The second-stage compressor code: {@link #NONE}, {@link #SNAPPY} or {@link #COMPLEX}.
         */
        public int compressor;

        /**
         * The start of the texture's data in the frame: the section data, or for {@link #COMPLEX} the frame data
         * after the decode instructions.
         */
        public int dataStart;

        /**
         * The end of the texture's data in the frame (exclusive).
         */
        public int dataEnd;

        /**
         * The number of chunks, 1 unless the decode instructions split the data.
         */
        public int chunks;

        /**
         * The compressor of each chunk.
         */
        public int[] chunkCompressors = new int[1];

        /**
         * The start of each chunk in the frame.
         */
        public int[] chunkStarts = new int[1];

        /**
         * The size of each chunk.
         */
        public int[] chunkSizes = new int[1];

        Texture() {
        }

        /**
         * Grows the chunk tables to hold a number of chunks.
         *
         * @param count the number of chunks
         */
        void ensure(int count) {
            if (chunkSizes.length < count) {
                chunkCompressors = new int[count];
                chunkStarts = new int[count];
                chunkSizes = new int[count];
            }
            chunks = count;
        }
    }

    /**
     * {@return the number of textures the last parsed frame holds, 1 or 2}
     */
    public int count() {
        return count;
    }

    /**
     * {@return a texture of the last parsed frame}
     *
     * @param index the texture, from 0 to {@link #count()} minus one
     */
    public Texture texture(int index) {
        return textures[index];
    }

    /**
     * Parses a frame.
     *
     * @param data the array holding the frame
     * @param offset the index of its first byte
     * @param length the number of bytes of the frame
     * @throws MediaException.Malformed if a section does not fit, the top-level section is of an unknown type, a
     * multiple-image section holds no texture or more than two, or the decode instructions are inconsistent
     */
    public void parse(byte[] data, int offset, int length) {
        var frameEnd = offset + length;
        header(data, offset, frameEnd);
        count = 0;
        if (type == MULTIPLE_IMAGES) {
            var at = start;
            var sectionEnd = end;
            while (at < sectionEnd) {
                header(data, at, sectionEnd);
                at = end;
                if (count == 2) {
                    throw new MediaException.Malformed("a HAP multiple-image section holds more than two images");
                }
                texture(data, textures[count++]);
            }
            if (count == 0) {
                throw new MediaException.Malformed("a HAP multiple-image section holds no image");
            }
        } else {
            texture(data, textures[count++]);
        }
    }

    /**
     * Reads a section header.
     *
     * @param data the array holding the frame
     * @param at the index of the header
     * @param limit the end of the enclosing section (exclusive)
     */
    private void header(byte[] data, int at, int limit) {
        if (limit - at < 4) {
            throw new MediaException.Malformed("a HAP section header is truncated");
        }
        long size = data[at] & 0xFF | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16;
        type = data[at + 3] & 0xFF;
        var headerLength = 4;
        if (size == 0) {
            if (limit - at < 8) {
                throw new MediaException.Malformed("a HAP section header is truncated");
            }
            size = u32(data, at + 4);
            headerLength = 8;
        }
        if (size > limit - at - headerLength) {
            throw new MediaException.Malformed("a HAP section of " + size + " bytes overruns its frame");
        }
        start = at + headerLength;
        end = (int) (start + size);
    }

    /**
     * Fills a texture from the section just read.
     *
     * @param data the array holding the frame
     * @param texture the texture entry to fill
     */
    private void texture(byte[] data, Texture texture) {
        var format = type & 0x0F;
        var compressor = type >>> 4;
        var known = format == RGB_DXT1 || format == RGBA_DXT5 || format == YCOCG_DXT5 || format == ALPHA_RGTC1
                || format == RGBA_BPTC || format == RGB_BPTC_UNSIGNED || format == RGB_BPTC_SIGNED;
        if (!known || compressor < NONE || compressor > COMPLEX) {
            throw new MediaException.Malformed(String.format("unknown HAP section type 0x%02X", type));
        }
        texture.format = format;
        texture.compressor = compressor;
        var sectionStart = start;
        var sectionEnd = end;
        if (compressor != COMPLEX) {
            texture.dataStart = sectionStart;
            texture.dataEnd = sectionEnd;
            texture.ensure(1);
            texture.chunkCompressors[0] = compressor;
            texture.chunkStarts[0] = sectionStart;
            texture.chunkSizes[0] = sectionEnd - sectionStart;
            return;
        }
        header(data, sectionStart, sectionEnd);
        if (type != INSTRUCTIONS) {
            throw new MediaException.Malformed("a HAP texture lacks its decode instructions");
        }
        var frameData = end;
        var at = start;
        var instructionsEnd = end;
        var compressorsAt = -1;
        var compressorsLength = 0;
        var sizesAt = -1;
        var sizesLength = 0;
        var offsetsAt = -1;
        var offsetsLength = 0;
        while (at < instructionsEnd) {
            header(data, at, instructionsEnd);
            at = end;
            switch (type) {
                case COMPRESSOR_TABLE -> {
                    compressorsAt = start;
                    compressorsLength = end - start;
                }
                case SIZE_TABLE -> {
                    sizesAt = start;
                    sizesLength = end - start;
                }
                case OFFSET_TABLE -> {
                    offsetsAt = start;
                    offsetsLength = end - start;
                }
                default -> {
                }
            }
        }
        if (compressorsAt < 0 || sizesAt < 0) {
            throw new MediaException.Malformed("HAP decode instructions lack the chunk compressor or size table");
        }
        var chunks = compressorsLength;
        if (chunks == 0 || sizesLength != 4 * chunks || offsetsAt >= 0 && offsetsLength != 4 * chunks) {
            throw new MediaException.Malformed("the HAP chunk tables differ in length");
        }
        texture.ensure(chunks);
        texture.dataStart = frameData;
        texture.dataEnd = sectionEnd;
        var running = 0L;
        for (var chunk = 0; chunk < chunks; chunk++) {
            var chunkCompressor = data[compressorsAt + chunk] & 0xFF;
            if (chunkCompressor != NONE && chunkCompressor != SNAPPY) {
                throw new MediaException.Malformed(String.format("unknown HAP chunk compressor 0x%02X",
                        chunkCompressor));
            }
            var size = u32(data, sizesAt + 4 * chunk);
            var chunkOffset = offsetsAt >= 0 ? u32(data, offsetsAt + 4 * chunk) : running;
            running = chunkOffset + size;
            if (running > sectionEnd - frameData) {
                throw new MediaException.Malformed("a HAP chunk overruns its frame");
            }
            texture.chunkCompressors[chunk] = chunkCompressor;
            texture.chunkStarts[chunk] = (int) (frameData + chunkOffset);
            texture.chunkSizes[chunk] = (int) size;
        }
    }

    /**
     * {@return four bytes read little-endian, unsigned}
     *
     * @param data the array
     * @param at the index of the first byte
     */
    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }
}
