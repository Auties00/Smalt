package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the lossless JPEG variant of Hasselblad's 3FR sensor data: a stream whose start of scan names predictor
 * 8, which ITU-T T.81 leaves undefined.
 *
 * <p>The frame holds one 16-bit component. The entropy-coded data is read as 32-bit little-endian words, each most
 * significant bit first, with no byte stuffing. The photosites of a row are coded in pairs: the two Huffman-coded
 * difference categories come first, then the two differences' extra bits, a category {@code n} below 16 taking
 * {@code n} bits as in T.81. Category 16 is the difference 32768, as in T.81, but is followed by 16 bits that
 * carry no information. Each
 * photosite is the difference added to the photosite two columns to its left, and to 32768 for the first two of
 * each row; values wrap to 16 bits.
 *
 * @implNote This implementation's layout was worked out from CC0 sample files, against the photosites Adobe DNG
 * Converter, run as a black box, stores for them: the converter scales its photosites by a gain per color and
 * position, which leaves the photosites themselves proportional to these within one unit.
 */
final class HasselbladJpeg {
    /**
     * The predictor value the start of scan names.
     */
    static final int PREDICTOR = 8;

    /**
     * The value added to the photosites of a signed stream, which becomes their black level.
     */
    static final int SIGNED_OFFSET = 1024;

    /**
     * The frame width in photosites.
     */
    final int width;

    /**
     * The frame height in photosites.
     */
    final int height;

    /**
     * The Huffman table of the difference categories.
     */
    private final MsbBits.Table table;

    /**
     * The index of the first byte of the entropy-coded data.
     */
    private final int scan;

    /**
     * Parses the markers up to the start of scan.
     *
     * @param data the bytes holding the stream
     * @param start the index of the stream's start of image marker
     * @param end the index after the stream
     * @throws MediaException.Malformed if the markers are corrupt or a table is missing
     * @throws MediaException.Unsupported if the frame is not a one-component, 16-bit predictor-8 frame
     */
    HasselbladJpeg(byte[] data, int start, int end) {
        if (end - start < 4 || (data[start] & 0xFF) != 0xFF || (data[start + 1] & 0xFF) != 0xD8) {
            throw new MediaException.Malformed("the Hasselblad sensor data does not start with a JPEG marker");
        }
        var position = start + 2;
        var frameWidth = -1;
        var frameHeight = -1;
        MsbBits.Table huffman = null;
        while (true) {
            while (position + 1 < end && (data[position] & 0xFF) == 0xFF && (data[position + 1] & 0xFF) == 0xFF) {
                position++;
            }
            if (position + 4 > end || (data[position] & 0xFF) != 0xFF) {
                throw new MediaException.Malformed("the Hasselblad JPEG stream ends before its scan");
            }
            var marker = data[position + 1] & 0xFF;
            var length = (data[position + 2] & 0xFF) << 8 | data[position + 3] & 0xFF;
            var body = position + 4;
            var next = position + 2 + length;
            if (length < 2 || next > end) {
                throw new MediaException.Malformed("a Hasselblad JPEG segment of " + length + " bytes");
            }
            switch (marker) {
                case 0xC3 -> {
                    if (length < 8 + 3) {
                        throw new MediaException.Malformed("a short Hasselblad JPEG frame header");
                    }
                    var precision = data[body] & 0xFF;
                    frameHeight = (data[body + 1] & 0xFF) << 8 | data[body + 2] & 0xFF;
                    frameWidth = (data[body + 3] & 0xFF) << 8 | data[body + 4] & 0xFF;
                    var components = data[body + 5] & 0xFF;
                    if (precision != 16 || components != 1) {
                        throw new MediaException.Unsupported("raw", "a Hasselblad JPEG frame of " + components
                                + " components of " + precision + " bits is not supported");
                    }
                    if (frameWidth == 0 || frameHeight == 0 || frameWidth % 2 != 0) {
                        throw new MediaException.Malformed("a Hasselblad JPEG frame of " + frameWidth + "x"
                                + frameHeight);
                    }
                }
                case 0xC4 -> {
                    var at = body;
                    while (at < next) {
                        if (at + 17 > next) {
                            throw new MediaException.Malformed("a short Hasselblad Huffman table");
                        }
                        var spec = new int[16];
                        var total = 0;
                        for (var k = 0; k < 16; k++) {
                            spec[k] = data[at + 1 + k] & 0xFF;
                            total += spec[k];
                        }
                        if (at + 17 + total > next) {
                            throw new MediaException.Malformed("a short Hasselblad Huffman table");
                        }
                        var full = new int[16 + total];
                        System.arraycopy(spec, 0, full, 0, 16);
                        for (var k = 0; k < total; k++) {
                            var category = data[at + 17 + k] & 0xFF;
                            if (category > 16) {
                                throw new MediaException.Malformed("Hasselblad difference category " + category);
                            }
                            full[16 + k] = category;
                        }
                        if ((data[at] & 0x0F) == 0) {
                            huffman = new MsbBits.Table(full);
                        }
                        at += 17 + total;
                    }
                }
                case 0xDA -> {
                    var components = data[body] & 0xFF;
                    var selection = body + 1 + 2 * components;
                    if (components != 1 || selection >= next) {
                        throw new MediaException.Malformed("a Hasselblad JPEG scan of " + components + " components");
                    }
                    if ((data[selection] & 0xFF) != PREDICTOR) {
                        throw new MediaException.Unsupported("raw", "Hasselblad JPEG predictor "
                                + (data[selection] & 0xFF) + " is not supported");
                    }
                    if (frameWidth < 0 || huffman == null) {
                        throw new MediaException.Malformed("a Hasselblad JPEG scan without its frame or table");
                    }
                    width = frameWidth;
                    height = frameHeight;
                    table = huffman;
                    scan = next;
                    return;
                }
                case 0xD9 -> throw new MediaException.Malformed("the Hasselblad JPEG stream ends before its scan");
                default -> {
                }
            }
            position = next;
        }
    }

    /**
     * Returns whether a stream's start of scan names predictor 8.
     *
     * @param data the bytes holding the stream
     * @param start the index of the stream's start of image marker
     * @param end the index after the stream
     * @return whether the stream is in this variant
     */
    static boolean matches(byte[] data, int start, int end) {
        var position = start + 2;
        if (end - start < 4 || (data[start] & 0xFF) != 0xFF || (data[start + 1] & 0xFF) != 0xD8) {
            return false;
        }
        while (position + 4 <= end) {
            if ((data[position] & 0xFF) != 0xFF) {
                return false;
            }
            var marker = data[position + 1] & 0xFF;
            if (marker == 0xFF) {
                position++;
                continue;
            }
            var length = (data[position + 2] & 0xFF) << 8 | data[position + 3] & 0xFF;
            if (marker == 0xDA) {
                var selection = position + 5 + 2 * (data[position + 4] & 0xFF);
                return selection < end && (data[selection] & 0xFF) == PREDICTOR;
            }
            if (length < 2) {
                return false;
            }
            position += 2 + length;
        }
        return false;
    }

    /**
     * Decodes the photosites into a plane, keeping those inside it.
     *
     * <p>A stream that ends early decodes its remaining photosites from zero bits. The photosites of a signed
     * stream, whose values are 16-bit two's complement numbers with black at 0, are stored plus
     * {@link #SIGNED_OFFSET}, those that would fall below 0 at 0.
     *
     * @param data the bytes holding the stream
     * @param end the index after the stream
     * @param signed whether the photosites are signed
     * @param out the plane's samples
     * @param planeWidth the plane's width
     * @param planeHeight the plane's height
     */
    void decode(byte[] data, int end, boolean signed, short[] out, int planeWidth, int planeHeight) {
        var words = swapWords(data, scan, end);
        var bits = new MsbBits(words, 0, words.length);
        var row = new int[width];
        var rows = Math.min(height, planeHeight);
        var columns = Math.min(width, planeWidth);
        for (var y = 0; y < rows; y++) {
            for (var x = 0; x < width; x += 2) {
                var first = bits.decode(table);
                var second = bits.decode(table);
                var a = difference(bits, first);
                var b = difference(bits, second);
                row[x] = ((x == 0 ? 32768 : row[x - 2]) + a) & 0xFFFF;
                row[x + 1] = ((x == 0 ? 32768 : row[x - 1]) + b) & 0xFFFF;
            }
            var target = y * planeWidth;
            if (signed) {
                for (var x = 0; x < columns; x++) {
                    out[target + x] = (short) Math.max(0, (short) row[x] + SIGNED_OFFSET);
                }
            } else {
                for (var x = 0; x < columns; x++) {
                    out[target + x] = (short) row[x];
                }
            }
        }
    }

    /**
     * Reads the extra bits of a difference.
     *
     * @param bits the reader
     * @param category the difference category, from 0 to 16
     * @return the difference
     */
    private static int difference(MsbBits bits, int category) {
        if (category == 0) {
            return 0;
        }
        if (category == 16) {
            bits.read(16);
            return 32768;
        }
        var value = bits.read(category);
        return value >= 1 << (category - 1) ? value : value - (1 << category) + 1;
    }

    /**
     * Copies the entropy-coded data with the bytes of each 32-bit word reversed, so that a most-significant-first
     * byte reader reads the little-endian words; a trailing partial word is padded with zero bytes.
     *
     * @param data the bytes holding the stream
     * @param start the index of the first entropy-coded byte
     * @param end the index after the stream
     * @return the reordered copy
     */
    private static byte[] swapWords(byte[] data, int start, int end) {
        var length = Math.max(0, end - start);
        var words = new byte[(length + 3) & ~3];
        System.arraycopy(data, start, words, 0, length);
        for (var at = 0; at < words.length; at += 4) {
            var b0 = words[at];
            var b1 = words[at + 1];
            words[at] = words[at + 3];
            words[at + 1] = words[at + 2];
            words[at + 2] = b1;
            words[at + 3] = b0;
        }
        return words;
    }
}
