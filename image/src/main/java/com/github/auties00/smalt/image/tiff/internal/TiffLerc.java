/* Ports libtiff 4.7.1 (tif_lerc.c, the mask, alpha and byte-order conventions of LERCPreDecode).
 * Copyright 2018 Even Rouault; libtiff license. Modified for Smalt. */
package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.internal.lerc.LercDecoder;
import com.github.auties00.smalt.image.internal.zlib.ZlibInflater;
import com.github.auties00.smalt.image.internal.zstd.ZstdDecoder;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.zip.DataFormatException;

/**
 * Decodes the LERC strips and tiles of TIFF compression 34887 into stored samples.
 *
 * <p>Each strip or tile is one LERC blob, compressed again with Deflate or Zstandard when the second
 * value of the {@code LercParameters} tag (50674) says so (0 none, 1 Deflate, 2 Zstandard; without
 * the tag the wrapping is recognized from the data). The blob has as many columns and rows as the
 * strip or tile, the data type of the samples, and either one value per sample, or, for interleaved
 * 8-bit samples whose last extra sample is unassociated alpha, one value fewer, the alpha then coming
 * from the blob's mask (255 where valid); interleaved floating-point samples may instead be one band
 * per sample. Floating-point samples outside the mask are NaN, integer samples outside it zero.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation writes the decoded values little-endian whatever the file's byte
 * order, which is what libtiff, the reference writer, stores: it hands LERC the samples in the file's
 * byte order as if they were native little-endian values.
 */
final class TiffLerc {
    /** The decoder of the blobs. */
    private final LercDecoder lerc = new LercDecoder();

    /** The decompressor of Zstandard-wrapped blobs, created on first use. */
    private ZstdDecoder zstd;

    /** The decompressor of Deflate-wrapped blobs, created on first use. */
    private ZlibInflater inflater;

    /** The unwrapped blob. */
    private byte[] blob = new byte[0];

    /** The decoded values and masks. */
    private double[] values = new double[0];
    private byte[] valid = new byte[0];

    /**
     * Returns the LERC data type of TIFF samples.
     *
     * @param bits the bits per sample
     * @param format the sample format: 1 unsigned, 2 signed, 3 floating point
     * @return the data type, or -1 when LERC has none
     */
    static int dataType(int bits, int format) {
        return switch (format) {
            case 1 -> bits == 8 ? LercDecoder.BYTE : bits == 16 ? LercDecoder.USHORT : bits == 32 ? LercDecoder.UINT : -1;
            case 2 -> bits == 8 ? LercDecoder.CHAR : bits == 16 ? LercDecoder.SHORT : bits == 32 ? LercDecoder.INT : -1;
            case 3 -> bits == 32 ? LercDecoder.FLOAT : bits == 64 ? LercDecoder.DOUBLE : -1;
            default -> -1;
        };
    }

    /**
     * Decodes one strip or tile.
     *
     * @param raw the stored bytes
     * @param length the number of stored bytes
     * @param wrapping the compression around the blob: 0 none, 1 Deflate, 2 Zstandard, or -1 to
     * recognize it
     * @param chunk receives the samples
     * @param columns the columns of the strip or tile
     * @param rows the rows of the strip or tile
     * @param samples the samples per pixel of the chunk: the samples per pixel, or 1 for a plane
     * @param bits the bits per sample
     * @param format the sample format
     * @param alphaMask whether a mask may stand for the last sample, unassociated alpha
     * @param little whether the file is little-endian
     * @param limits the limits the unwrapped blob and the values must fit
     * @throws MediaException.Malformed if the data is not a LERC blob of the strip or tile
     * @throws MediaException.LimitExceeded if the unwrapped blob exceeds the allocation limit
     */
    void decode(byte[] raw, int length, int wrapping, byte[] chunk, int columns, int rows, int samples, int bits,
                int format, boolean alphaMask, boolean little, MediaLimits limits) {
        var sampleBytes = bits / 8;
        var expected = (long) columns * rows * samples * sampleBytes;
        var data = raw;
        var size = length;
        if (wrapping < 0) {
            wrapping = length >= 6 && raw[0] == 'L' && raw[1] == 'e' && raw[2] == 'r' && raw[3] == 'c' ? 0
                    : length >= 4 && (raw[0] & 0xFF) == 0x28 && (raw[1] & 0xFF) == 0xB5 && (raw[2] & 0xFF) == 0x2F
                    && (raw[3] & 0xFF) == 0xFD ? 2 : 1;
        }
        if (wrapping == 1 || wrapping == 2) {
            var capacity = Math.max(256 + expected + expected / 3, expected + (expected >>> 8) + (128 << 10));
            LimitChecks.checkAllocation(limits, capacity);
            if (blob.length < capacity) {
                blob = new byte[(int) Math.min(capacity, Integer.MAX_VALUE - 16)];
            }
            try {
                if (wrapping == 2) {
                    if (zstd == null) {
                        zstd = new ZstdDecoder();
                    }
                    size = zstd.decompress(raw, 0, length, blob, 0, blob.length);
                } else {
                    size = inflate(raw, length);
                }
            } catch (DataFormatException exception) {
                throw new MediaException.Malformed("invalid " + (wrapping == 2 ? "Zstandard" : "Deflate")
                        + " data around a TIFF LERC blob", exception);
            }
            data = blob;
        } else if (wrapping != 0) {
            throw new MediaException.Malformed("unknown TIFF LERC additional compression " + wrapping);
        }
        try {
            var info = lerc.info(data, 0, size);
            var type = dataType(bits, format);
            if (info.dataType() != type) {
                throw new MediaException.Malformed("TIFF LERC blob of data type " + info.dataType() + " for "
                        + bits + "-bit samples of format " + format);
            }
            if (info.columns() != columns || info.rows() != rows) {
                throw new MediaException.Malformed("TIFF LERC blob of " + info.columns() + "x" + info.rows()
                        + " pixels for a strip or tile of " + columns + "x" + rows);
            }
            var maskAlpha = alphaMask && samples > 1 && type == LercDecoder.BYTE && info.depth() == samples - 1;
            var floating = format == 3;
            var bandPerSample = floating && samples > 1 && info.depth() == 1;
            if (bandPerSample ? info.bands() != samples : info.bands() != 1) {
                throw new MediaException.Malformed("TIFF LERC blob with " + info.bands() + " bands");
            }
            if (!bandPerSample && info.depth() != (maskAlpha ? samples - 1 : samples)) {
                throw new MediaException.Malformed("TIFF LERC blob with " + info.depth() + " values per pixel for "
                        + samples + " samples");
            }
            var pixels = columns * rows;
            var count = (long) info.bands() * pixels * info.depth();
            LimitChecks.checkAllocation(limits, count * 8);
            if (values.length < count) {
                values = new double[(int) count];
            }
            if (valid.length < info.bands() * pixels) {
                valid = new byte[info.bands() * pixels];
            }
            lerc.decode(data, 0, size, info, values, valid);
            var depth = info.depth();
            for (var p = 0; p < pixels; p++) {
                var at = p * samples * sampleBytes;
                for (var s = 0; s < samples; s++, at += sampleBytes) {
                    if (maskAlpha && s == samples - 1) {
                        chunk[at] = (byte) (valid[p] != 0 ? 255 : 0);
                    } else if (bandPerSample) {
                        write(chunk, at, sampleBytes, floating, little, valid[s * pixels + p] != 0,
                                values[s * pixels + p]);
                    } else {
                        write(chunk, at, sampleBytes, floating, little, valid[p] != 0, values[p * depth + s]);
                    }
                }
            }
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("invalid TIFF LERC blob: " + exception.getMessage(), exception);
        }
    }

    /**
     * Writes one sample.
     *
     * @param chunk the samples
     * @param at the sample position
     * @param size the sample size in bytes
     * @param floating whether the sample is floating point
     * @param little whether the file is little-endian, for NaN
     * @param inside whether the pixel is inside the mask
     * @param value the decoded value, ignored outside the mask
     */
    private static void write(byte[] chunk, int at, int size, boolean floating, boolean little, boolean inside,
                              double value) {
        long bits;
        if (floating) {
            if (!inside) {
                var nan = size == 4 ? 0x7FC00000L : 0x7FF8000000000000L;
                for (var i = 0; i < size; i++) {
                    chunk[at + i] = (byte) (nan >>> (8 * (little ? i : size - 1 - i)));
                }
                return;
            }
            bits = size == 4 ? Float.floatToRawIntBits((float) value) : Double.doubleToRawLongBits(value);
        } else {
            bits = (long) value;
        }
        for (var i = 0; i < size; i++) {
            chunk[at + i] = (byte) (bits >>> (8 * i));
        }
    }

    /**
     * Inflates a Deflate-wrapped blob.
     *
     * @param raw the stored bytes
     * @param length the number of stored bytes
     * @return the blob size
     * @throws DataFormatException if the zlib stream is invalid
     */
    private int inflate(byte[] raw, int length) throws DataFormatException {
        if (inflater == null) {
            inflater = new ZlibInflater();
        }
        inflater.reset();
        inflater.setInput(raw, 0, length);
        var produced = 0;
        while (produced < blob.length) {
            var count = inflater.inflate(blob, produced, blob.length - produced);
            if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                break;
            }
            produced += count;
        }
        return produced;
    }
}
