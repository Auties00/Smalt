package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.JpegPlanes;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.jpeg.internal.LosslessOutput;
import com.github.auties00.smalt.image.jpeg.internal.Upsampler;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.limits.LimitChecks;


/**
 * Decodes the JPEG data of TIFF strips and tiles into interleaved 8-bit or 12-bit samples.
 *
 * <p>New-style JPEG (compression 7, TIFF Technical Note 2) stores each strip or tile as an
 * abbreviated JPEG stream whose tables may live in the {@code JPEGTables} tag; the two are joined
 * into one interchange stream before decoding. Old-style JPEG (compression 6) is decoded either
 * from the complete stream its {@code JPEGInterchangeFormat} tag points to, or from a stream
 * rebuilt from its table tags and the strip or tile data. Each component is brought to full
 * resolution with libjpeg's fancy upsampling, and YCbCr is converted to RGB with libjpeg's tables
 * when asked, which is what libtiff does when it reads JPEG data as RGB. A 12-bit stream is
 * upsampled and converted at 12 bits, with libjpeg's 12-bit tables, and its samples are packed as
 * TIFF packs 12-bit samples: two in three bytes, most significant bits first, each row starting on
 * a byte boundary. The samples of a lossless JPEG, JPEG-LS or lossless hierarchical stream, which a JPEG strip or
 * tile may hold at any precision from 2 to 16 bits, are kept as decoded and packed as TIFF packs samples of that
 * depth: 16-bit samples in the file's byte order, others most significant bits first, each row starting on a byte
 * boundary.
 *
 * @implNote This implementation reuses Smalt's JPEG decoder ({@link JpegReader}), whose planes
 * are bit-exact with libjpeg-turbo's accurate integer IDCT, so the samples match libtiff linked
 * against libjpeg-turbo. The rebuilt old-style stream follows libtiff's tif_ojpeg.c: one
 * quantization table and one DC and one AC Huffman table per component, the luma sampling taken
 * from {@code YCbCrSubSampling}, and a baseline frame header.
 */
public final class TiffJpeg {
    /**
     * Prevents instantiation.
     */
    private TiffJpeg() {
        throw new AssertionError();
    }

    /**
     * Holds decoded samples.
     *
     * @param samples the interleaved samples, {@link #rowBytes()} per row
     * @param width the decoded width
     * @param height the decoded height
     * @param components the number of samples per pixel
     * @param bits the number of bits per sample: 8 or 12 for a DCT stream, from 2 to 16 for a lossless one;
     * 16-bit samples are in the file's byte order and others below 8 or between 8 and 16 bits packed most
     * significant bits first
     */
    public record Samples(byte[] samples, int width, int height, int components, int bits) {
        /**
         * {@return the number of bytes of one row of samples}
         */
        public int rowBytes() {
            return (int) (((long) width * components * bits + 7) / 8);
        }
    }

    /**
     * Joins a {@code JPEGTables} stream and an abbreviated strip or tile stream.
     *
     * @param tables the tables stream ({@code SOI}, tables, {@code EOI}), or {@code null}
     * @param data the strip or tile stream
     * @param length the number of bytes of {@code data}
     * @return the interchange stream
     */
    public static byte[] join(byte[] tables, byte[] data, int length) {
        if (tables == null || tables.length < 4) {
            return length == data.length ? data : java.util.Arrays.copyOf(data, length);
        }
        var tablesLength = tables.length;
        if ((tables[tablesLength - 2] & 0xFF) == 0xFF && (tables[tablesLength - 1] & 0xFF) == 0xD9) {
            tablesLength -= 2;
        }
        var skip = length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 ? 2 : 0;
        var joined = new byte[tablesLength + length - skip];
        System.arraycopy(tables, 0, joined, 0, tablesLength);
        System.arraycopy(data, skip, joined, tablesLength, length - skip);
        return joined;
    }

    /**
     * Rebuilds an old-style JPEG strip or tile as an interchange stream.
     *
     * @param data the entropy-coded data
     * @param length the number of bytes of {@code data}
     * @param width the width of the strip or tile
     * @param height the height of the strip or tile
     * @param components the number of components
     * @param quantization the quantization tables, 64 bytes each in zig-zag order, one per
     * component
     * @param dc the DC Huffman tables (16 counts then the values), one per component
     * @param ac the AC Huffman tables, one per component
     * @param hSampling the luma horizontal sampling factor
     * @param vSampling the luma vertical sampling factor
     * @param restartInterval the restart interval in MCUs, or {@code 0}
     * @return the interchange stream
     * @throws MediaException.Malformed if a table is truncated
     */
    public static byte[] rebuild(byte[] data, int length, int width, int height, int components, byte[][] quantization,
                                 byte[][] dc, byte[][] ac, int hSampling, int vSampling, int restartInterval) {
        var out = new ByteWriter(length + 1024);
        marker(out, 0xD8);
        for (var c = 0; c < components; c++) {
            if (quantization[c].length < 64) {
                throw new MediaException.Malformed("truncated old-style JPEG quantization table");
            }
            marker(out, 0xDB);
            out.u16be(2 + 65);
            out.u8(c);
            out.bytes(quantization[c], 0, 64);
        }
        for (var c = 0; c < components; c++) {
            huffman(out, 0x00 | c, dc[c]);
            huffman(out, 0x10 | c, ac[c]);
        }
        if (restartInterval > 0) {
            marker(out, 0xDD);
            out.u16be(4);
            out.u16be(restartInterval);
        }
        marker(out, 0xC0);
        out.u16be(8 + 3 * components);
        out.u8(8);
        out.u16be(height);
        out.u16be(width);
        out.u8(components);
        for (var c = 0; c < components; c++) {
            out.u8(c + 1);
            out.u8(c == 0 ? (hSampling << 4 | vSampling) : 0x11);
            out.u8(c);
        }
        marker(out, 0xDA);
        out.u16be(6 + 2 * components);
        out.u8(components);
        for (var c = 0; c < components; c++) {
            out.u8(c + 1);
            out.u8(c << 4 | c);
        }
        out.u8(0);
        out.u8(63);
        out.u8(0);
        var skip = length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 ? 2 : 0;
        out.bytes(data, skip, length - skip);
        marker(out, 0xD9);
        return out.toByteArray();
    }

    /**
     * Decodes an interchange stream into interleaved samples.
     *
     * @param stream the interchange stream
     * @param offset the index of the stream's {@code SOI}
     * @param length the length of the stream
     * @param limits the bounds of the decoded samples
     * @param ycbcrToRgb whether three components are YCbCr to convert to RGB
     * @param tiffTables the TIFF YCbCr tables to convert with, taking each pixel's chroma from the
     * sample covering it as libtiff does for old-style JPEG, or {@code null} to
     * upsample and convert as libjpeg does
     * @param little whether the file is little-endian, for the 16-bit samples of a lossless stream
     * @return the samples
     * @throws MediaException.Malformed if the stream is invalid
     * @throws MediaException.Unsupported if the stream uses a coding the JPEG decoder lacks, or is a lossless
     * stream of subsampled components
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static Samples decode(byte[] stream, int offset, int length, MediaLimits limits, boolean ycbcrToRgb,
                                 YCbCrTables tiffTables, boolean little) {
        var reader = new JpegReader(stream, offset, length, limits);
        var frame = reader.readHeaders();
        reader.checkSupported();
        if (reader.lossless()) {
            return decodeLossless(reader, limits, ycbcrToRgb, little);
        }
        if (frame.precision() == 12) {
            return decodeTwelveBit(reader.decodeWidePlanes(0), limits, ycbcrToRgb);
        }
        var planes = reader.decodePlanes(0);
        var width = planes.width();
        var height = planes.height();
        var components = frame.components().size();
        LimitChecks.checkAllocation(limits, (long) width * height * components);
        if (ycbcrToRgb && tiffTables != null && components == 3) {
            var samples = new byte[width * height * 3];
            var info = frame.components();
            var sx1 = frame.hMax() / Math.max(1, info.get(1).horizontalSampling());
            var sy1 = frame.vMax() / Math.max(1, info.get(1).verticalSampling());
            var sx2 = frame.hMax() / Math.max(1, info.get(2).horizontalSampling());
            var sy2 = frame.vMax() / Math.max(1, info.get(2).verticalSampling());
            var y = planes.plane(0);
            var cb = planes.plane(1);
            var cr = planes.plane(2);
            var ys = planes.stride(0);
            var cbs = planes.stride(1);
            var crs = planes.stride(2);
            for (var row = 0; row < height; row++) {
                var out = row * width * 3;
                for (var x = 0; x < width; x++) {
                    var rgb = tiffTables.toRgb(y[row * ys + x] & 0xFF, cb[(row / sy1) * cbs + x / sx1] & 0xFF,
                            cr[(row / sy2) * crs + x / sx2] & 0xFF);
                    samples[out] = (byte) (rgb >>> 16);
                    samples[out + 1] = (byte) (rgb >>> 8);
                    samples[out + 2] = (byte) rgb;
                    out += 3;
                }
            }
            return new Samples(samples, width, height, 3, 8);
        }
        var full = new byte[components][];
        var strides = new int[components];
        for (var c = 0; c < components; c++) {
            var info = frame.components().get(c);
            if (info.horizontalSampling() == frame.hMax() && info.verticalSampling() == frame.vMax()) {
                full[c] = planes.plane(c);
                strides[c] = planes.stride(c);
            } else {
                full[c] = Upsampler.upsample(planes.plane(c), planes.stride(c), planes.componentWidth(c),
                        planes.componentHeight(c), info.horizontalSampling(), info.verticalSampling(), frame.hMax(),
                        frame.vMax(), width, height, true);
                strides[c] = width;
            }
        }
        var samples = new byte[width * height * components];
        if (ycbcrToRgb && components == 3) {
            var y = full[0];
            var cb = full[1];
            var cr = full[2];
            for (var row = 0; row < height; row++) {
                var out = row * width * 3;
                var yRow = row * strides[0];
                var cbRow = row * strides[1];
                var crRow = row * strides[2];
                for (var x = 0; x < width; x++) {
                    var rgb = JpegOutput.yccToRgb(y[yRow + x] & 0xFF, cb[cbRow + x] & 0xFF, cr[crRow + x] & 0xFF);
                    samples[out] = (byte) (rgb >>> 16);
                    samples[out + 1] = (byte) (rgb >>> 8);
                    samples[out + 2] = (byte) rgb;
                    out += 3;
                }
            }
            return new Samples(samples, width, height, 3, 8);
        }
        for (var c = 0; c < components; c++) {
            var plane = full[c];
            var stride = strides[c];
            for (var row = 0; row < height; row++) {
                var out = row * width * components + c;
                var in = row * stride;
                for (var x = 0; x < width; x++) {
                    samples[out] = plane[in + x];
                    out += components;
                }
            }
        }
        return new Samples(samples, width, height, components, 8);
    }

    /**
     * Decodes the samples of a lossless stream, components interleaved, at their coded precision.
     *
     * @param reader the reader, its headers read
     * @param limits the bounds of the decoded samples
     * @param ycbcrToRgb whether three 8-bit components are YCbCr to convert to RGB
     * @param little whether 16-bit samples are written little-endian
     * @return the samples
     * @throws MediaException.Unsupported if a component is subsampled, or YCbCr samples of other than 8 bits are to
     * be converted
     */
    private static Samples decodeLossless(JpegReader reader, MediaLimits limits, boolean ycbcrToRgb, boolean little) {
        var samples = LosslessOutput.open(reader);
        if (!samples.fullResolution()) {
            throw new MediaException.Unsupported("tiff jpeg lossless subsampled",
                    "lossless JPEG strips and tiles of subsampled components are not supported");
        }
        var frame = reader.frame();
        var width = frame.width();
        var height = frame.height();
        var components = frame.components().size();
        var bits = frame.precision();
        var convert = ycbcrToRgb && components == 3;
        if (convert && bits != 8) {
            throw new MediaException.Unsupported("tiff jpeg lossless ycbcr",
                    "lossless JPEG strips and tiles of " + bits + "-bit YCbCr samples are not supported");
        }
        var rowBytes = (int) (((long) width * components * bits + 7) / 8);
        LimitChecks.checkAllocation(limits, (long) rowBytes * height);
        var packed = new byte[rowBytes * height];
        var max = (1 << bits) - 1;
        samples.decode((row, values) -> {
            var at = row * rowBytes;
            var count = width * components;
            if (convert) {
                for (var x = 0; x < width; x++) {
                    var rgb = JpegOutput.yccToRgb(values[3 * x] & 0xFF, values[3 * x + 1] & 0xFF, values[3 * x + 2] & 0xFF);
                    packed[at + 3 * x] = (byte) (rgb >>> 16);
                    packed[at + 3 * x + 1] = (byte) (rgb >>> 8);
                    packed[at + 3 * x + 2] = (byte) rgb;
                }
            } else if (bits == 8) {
                for (var i = 0; i < count; i++) {
                    packed[at + i] = (byte) values[i];
                }
            } else if (bits == 16) {
                for (var i = 0; i < count; i++) {
                    var value = values[i] & max;
                    packed[at + 2 * i] = (byte) (little ? value : value >>> 8);
                    packed[at + 2 * i + 1] = (byte) (little ? value >>> 8 : value);
                }
            } else {
                var bit = 0L;
                for (var i = 0; i < count; i++) {
                    var value = values[i] & max;
                    for (var b = bits - 1; b >= 0; b--, bit++) {
                        if (((value >>> b) & 1) != 0) {
                            packed[at + (int) (bit >>> 3)] |= (byte) (0x80 >>> (bit & 7));
                        }
                    }
                }
            }
        });
        return new Samples(packed, width, height, components, bits);
    }

    /**
     * Turns the planes of a 12-bit stream into packed interleaved 12-bit samples.
     *
     * @param planes the {@linkplain JpegPlanes#wide() wide} planes
     * @param limits the bounds of the decoded samples
     * @param ycbcrToRgb whether three components are YCbCr to convert to RGB
     * @return the samples
     * @throws MediaException.LimitExceeded if the samples exceed the limits
     */
    private static Samples decodeTwelveBit(JpegPlanes planes, MediaLimits limits, boolean ycbcrToRgb) {
        var frame = planes.frame();
        var width = planes.width();
        var height = planes.height();
        var components = frame.components().size();
        LimitChecks.checkAllocation(limits, (long) width * height * components * 2);
        var full = new short[components][];
        var strides = new int[components];
        for (var c = 0; c < components; c++) {
            var info = frame.components().get(c);
            if (info.horizontalSampling() == frame.hMax() && info.verticalSampling() == frame.vMax()) {
                full[c] = planes.widePlane(c);
                strides[c] = planes.stride(c);
            } else {
                full[c] = JpegOutput.fullResolutionWide(planes, c);
                strides[c] = width;
            }
        }
        var convert = ycbcrToRgb && components == 3;
        var rowBytes = (int) (((long) width * components * 12 + 7) / 8);
        var samples = new byte[rowBytes * height];
        var pixel = new int[components];
        for (var row = 0; row < height; row++) {
            var out = row * rowBytes;
            var odd = false;
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < components; c++) {
                    pixel[c] = full[c][row * strides[c] + x];
                }
                if (convert) {
                    var rgb = JpegOutput.yccToRgb12(pixel[0], pixel[1], pixel[2]);
                    pixel[0] = (int) (rgb >>> 24);
                    pixel[1] = (int) ((rgb >>> 12) & 0xFFF);
                    pixel[2] = (int) (rgb & 0xFFF);
                }
                for (var c = 0; c < components; c++) {
                    var value = pixel[c];
                    if (odd) {
                        samples[out] |= (byte) (value >>> 8);
                        samples[out + 1] = (byte) value;
                        out += 2;
                    } else {
                        samples[out] = (byte) (value >>> 4);
                        samples[out + 1] = (byte) (value << 4);
                        out++;
                    }
                    odd = !odd;
                }
            }
        }
        return new Samples(samples, width, height, components, 12);
    }

    /**
     * Writes a Huffman table segment.
     *
     * @param out the stream
     * @param id the table class and destination byte
     * @param table the 16 code counts followed by the values
     * @throws MediaException.Malformed if the table is truncated
     */
    private static void huffman(ByteWriter out, int id, byte[] table) {
        if (table.length < 16) {
            throw new MediaException.Malformed("truncated old-style JPEG Huffman table");
        }
        var values = 0;
        for (var i = 0; i < 16; i++) {
            values += table[i] & 0xFF;
        }
        if (table.length < 16 + values) {
            throw new MediaException.Malformed("truncated old-style JPEG Huffman table");
        }
        marker(out, 0xC4);
        out.u16be(2 + 1 + 16 + values);
        out.u8(id);
        out.bytes(table, 0, 16 + values);
    }

    /**
     * Writes a marker.
     *
     * @param out the stream
     * @param code the marker code after {@code 0xFF}
     */
    private static void marker(ByteWriter out, int code) {
        out.u8(0xFF);
        out.u8(code);
    }
}
