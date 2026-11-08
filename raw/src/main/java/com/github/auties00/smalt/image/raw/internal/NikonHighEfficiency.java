/* Ports SVT-JPEG-XS 0.9.0 (Packing.c, Dequant.c, Idwt.c: the JPEG XS bit-plane count coding, the uniform
   dequantization and the 5/3 synthesis). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.IntConsumer;

/**
 * Decodes the sensor data of Nikon's high-efficiency NEF files (HE and HE*, TIFF compression 34713 without the
 * compression data of Nikon's other modes): a codestream in the syntax of JPEG XS (ISO/IEC 21122-1) coding the color
 * filter array as four component planes of half its width and height.
 *
 * <p>The codestream starts with the {@code SOC} marker, a capabilities marker whose payload starts with
 * {@code CONTACT_INTOPIX_}, a picture header of 39 bytes (the 26 bytes of JPEG XS followed by 13 bytes of its own),
 * a component table of three bytes per component (bit depth, sampling factors, then the horizontal and vertical
 * decomposition levels of the component) and a weights table, then the slices. The picture header gives the size of
 * the color filter array; each component holds one photosite of every 2x2 block. Components 0, 1 and 3 have five
 * horizontal and one vertical wavelet level; component 2 has none.
 *
 * <p>A precinct covers two component lines. Its packets come line by line, component by component: on the first
 * line the bands of the low vertical half of each component (the deepest low band, then the horizontal high bands
 * from the deepest level up, then the high horizontal and low vertical band of the first level) or the first line
 * of an undecomposed component, on the second line the two high vertical bands of the first level or the second line
 * of an undecomposed component. The weights table lists one gain and priority per band in the order the bands first
 * appear in the packets. Every packet header is seven bytes long, and the significance flags of a packet are not
 * counted in its lengths. Two bits per band in the precinct header select vertical prediction of the bit-plane counts
 * and significance flags (the samples set both for every band); vertical prediction in the first precinct row of a
 * slice predicts from zero. The other entropy coding follows JPEG XS: unary bit-plane counts, raw four-bit groups,
 * signs after the data, uniform dequantization.
 *
 * <p>Reconstruction keeps four fractional bits: the dequantized coefficients are scaled by 16 before the inverse
 * 5/3 wavelet, which runs in integer arithmetic as JPEG XS specifies it. A lifting transform then turns the
 * components into the photosites of the mosaic, still in the square-root domain: component 0 sits on the green
 * photosite to the right of the top-left one, component 1 on the top-left photosite, component 2 on the green below
 * it and component 3 diagonally. Last, a square law maps each value to the linear 14-bit photosite.
 *
 * <p>The 13 bytes the picture header adds to those of JPEG XS carry the square law, as fields of a big-endian bit
 * string: the black level (11 bits from bit 29), the gain of the codes above the pivot (18 bits from bit 62, 16 of
 * them fractional) and the pivot (16 bits from bit 80). A reconstructed value {@code v} is clamped to a 16-bit code
 * {@code u = v + 32768} and taken relative to the pivot, {@code d = u - pivot}; the photosite is
 * {@snippet :
 * long y = d >= 0 ? square((d * gain + (1 << 15)) >> 16) : -square(d);
 * int photosite = clamp(black + ((y + (5 << 15)) >> 18), 0, (1 << 14) - 1);
 * }
 * The other bits of the 13 bytes select the form of the law; the decoder accepts only the values every sample file
 * carries.
 *
 * @implNote This implementation was worked out clean-room from CC0 sample files of the Z 9, Z 8, Z f, Z 5II, Z 50II
 * and Z 6III against the photosites Adobe DNG Converter 18.0 stores for them; no specification of the format is
 * published. The square law was measured with synthetic files: the codestream of a sample file replaced by one
 * whose reconstructed values cover every 16-bit code, then each bit of the 13 header bytes flipped in turn, which
 * showed the three fields and the bits that change the form of the law or make the converter refuse the file. The
 * decoder reproduces every photosite the converter stores for the samples and for the synthetic files. The meaning
 * of the capability flags is not known; the decoder checks the parameters it relies on and refuses other values.
 */
final class NikonHighEfficiency {
    /**
     * The start of codestream marker.
     */
    private static final int SOC = 0xFF10;

    /**
     * The end of codestream marker.
     */
    private static final int EOC = 0xFF11;

    /**
     * The picture header marker.
     */
    private static final int PIH = 0xFF12;

    /**
     * The component table marker.
     */
    private static final int CDT = 0xFF13;

    /**
     * The weights table marker.
     */
    private static final int WGT = 0xFF14;

    /**
     * The slice header marker.
     */
    private static final int SLH = 0xFF20;

    /**
     * The capabilities marker.
     */
    private static final int CAP = 0xFF50;

    /**
     * The start of the capabilities payload.
     */
    private static final byte[] VENDOR = "CONTACT_INTOPIX_".getBytes(StandardCharsets.US_ASCII);

    /**
     * The length of the picture header segment.
     */
    private static final int PICTURE_HEADER_LENGTH = 39;

    /**
     * The number of components.
     */
    private static final int COMPONENTS = 4;

    /**
     * The bit depth of every component.
     */
    private static final int PRECISION = 14;

    /**
     * The nominal bit precision of the wavelet coefficients ({@code Bw}).
     */
    private static final int WAVELET_PRECISION = 18;

    /**
     * The fractional bits of the reconstruction ({@code Fq}).
     */
    private static final int FRACTION = 4;

    /**
     * The bits of a raw bit-plane count ({@code Br}).
     */
    private static final int RAW_BITS = 4;

    /**
     * The length of a packet header.
     */
    private static final int PACKET_HEADER = 7;

    /**
     * The largest bit-plane count a code group may have.
     */
    private static final int MAX_BITPLANES = 20;

    /**
     * The longest unary code of a bit-plane count: a prediction residual codes at most twice the largest count.
     */
    private static final int MAX_UNARY = 2 * MAX_BITPLANES + 1;

    /**
     * The bit of a band's coding mode that predicts the bit-plane counts vertically.
     */
    private static final int VERTICAL = 1;

    /**
     * The bit of a band's coding mode that codes significance flags.
     */
    private static final int SIGNIFICANCE = 2;

    /**
     * The offset of the bytes the picture header adds, from the start of its payload.
     */
    private static final int LAW_OFFSET = 24;

    /**
     * The number of bytes the picture header adds.
     */
    private static final int LAW_LENGTH = 13;

    /**
     * The bits of the added picture header bytes that select the form of the square law.
     */
    private static final byte[] LAW_FORM_MASK = HexFormat.of().parseHex("07f8c7f8003000000000000087");

    /**
     * The values of the {@link #LAW_FORM_MASK} bits that every sample file carries.
     */
    private static final byte[] LAW_FORM = HexFormat.of().parseHex("00884080001000000000000007");

    /**
     * The offset of the 16-bit code of a reconstructed value.
     */
    private static final int CODE_OFFSET = 1 << 15;

    /**
     * The largest 16-bit code.
     */
    private static final int MAX_CODE = (1 << 16) - 1;

    /**
     * The fractional bits of the square law's gain.
     */
    private static final int GAIN_BITS = 16;

    /**
     * The fractional bits of the square law's squares, relative to photosite levels.
     */
    private static final int SQUARE_BITS = 18;

    /**
     * The rounding term of the square law's squares, five eighths of a photosite level.
     */
    private static final long SQUARE_ROUNDING = 5L << 15;

    /**
     * The largest output value.
     */
    private static final int MAX_OUTPUT = (1 << PRECISION) - 1;

    /**
     * Prevents instantiation.
     */
    private NikonHighEfficiency() {
        throw new AssertionError();
    }

    /**
     * The parameters of a codestream's main header the decoder uses.
     *
     * @param width the width of the color filter array
     * @param height the height of the color filter array
     * @param length the length of the codestream in bytes
     * @param sliceRows the number of precinct rows of a slice
     * @param levels the horizontal decomposition levels of each component, 0 for an undecomposed one
     * @param gains the gain of each band, in weights table order
     * @param priorities the priority of each band, in weights table order
     * @param headerLength the length of the main header, the offset of the first slice
     * @param black the square law's black level, the photosite value of the pivot
     * @param gain the square law's gain of the codes above the pivot, with 16 fractional bits
     * @param pivot the 16-bit code the square law maps to the black level
     */
    record Header(int width, int height, long length, int sliceRows, int[] levels, int[] gains, int[] priorities,
                  int headerLength, int black, int gain, int pivot) {
        /**
         * {@return the width of a component}
         */
        int componentWidth() {
            return width / 2;
        }

        /**
         * {@return the height of a component}
         */
        int componentHeight() {
            return height / 2;
        }

        /**
         * {@return the number of precinct rows}
         */
        int precinctRows() {
            return componentHeight() / 2;
        }

        /**
         * {@return the number of slices}
         */
        int slices() {
            return (precinctRows() + sliceRows - 1) / sliceRows;
        }

        /**
         * {@return whether the data rate is that of HE*, about five bits per photosite, rather than the three of HE}
         */
        boolean star() {
            return length * 8 > 4L * width * height;
        }
    }

    /**
     * One band of a component.
     *
     * @param component the index of the component the band belongs to
     * @param x the left of the band in the component plane
     * @param y the top of the band in the component plane
     * @param width the width of the band
     * @param lines the band lines of a precinct
     * @param weight the band's index in the weights table
     */
    private record Band(int component, int x, int y, int width, int lines, int weight) {
        /**
         * {@return the number of code groups of a band line}
         */
        int groups() {
            return (width + 3) >>> 2;
        }
    }

    /**
     * One band line of a packet.
     *
     * @param band the band the line belongs to
     * @param line the line within the precinct
     */
    private record Part(Band band, int line) {
    }

    /**
     * Returns whether data starts as a high-efficiency codestream: the {@code SOC} marker, then the capabilities
     * marker.
     *
     * @param head the first bytes of the strip
     * @return whether the strip is a high-efficiency codestream
     */
    static boolean matches(byte[] head) {
        return head.length >= 4 && u16(head, 0) == SOC && u16(head, 2) == CAP;
    }

    /**
     * Parses the main header of a codestream.
     *
     * @param data the start of the codestream, at least up to the first slice header
     * @return the header
     * @throws MediaException.Malformed if the header is truncated or inconsistent
     * @throws MediaException.Unsupported if it uses parameters no sample file has shown
     */
    static Header header(byte[] data) {
        var position = 0;
        if (data.length < 4 || u16(data, 0) != SOC) {
            throw new MediaException.Malformed("the NEF high-efficiency data has no start of codestream marker");
        }
        position += 2;
        if (u16(data, position) != CAP) {
            throw new MediaException.Malformed("the NEF high-efficiency data has no capabilities marker");
        }
        var capLength = segment(data, position);
        if (capLength < 2 || position + 2 + capLength > data.length) {
            throw new MediaException.Malformed("the NEF high-efficiency capabilities marker is truncated");
        }
        if (capLength - 2 < VENDOR.length
                || !Arrays.equals(data, position + 4, position + 4 + VENDOR.length, VENDOR, 0, VENDOR.length)) {
            throw new MediaException.Unsupported("raw", "the NEF high-efficiency capabilities are not intoPIX's");
        }
        position += 2 + capLength;
        if (position + 4 > data.length || u16(data, position) != PIH) {
            throw new MediaException.Malformed("the NEF high-efficiency data has no picture header");
        }
        if (segment(data, position) != PICTURE_HEADER_LENGTH) {
            throw new MediaException.Unsupported("raw", "the NEF high-efficiency picture header has "
                    + segment(data, position) + " bytes");
        }
        if (position + 2 + PICTURE_HEADER_LENGTH > data.length) {
            throw new MediaException.Malformed("the NEF high-efficiency picture header is truncated");
        }
        var p = position + 4;
        var length = u32(data, p);
        var width = u16(data, p + 8);
        var height = u16(data, p + 10);
        var precinctWidth = u16(data, p + 12);
        var sliceRows = u16(data, p + 14);
        var components = data[p + 16] & 0xFF;
        var groupSize = data[p + 17] & 0xFF;
        var significanceGroup = data[p + 18] & 0xFF;
        var waveletPrecision = data[p + 19] & 0xFF;
        var fractionRaw = data[p + 20] & 0xFF;
        var coding = data[p + 21] & 0xFF;
        var flags = data[p + 23] & 0xFF;
        if (width < 16 || height < 8 || (width & 1) != 0 || (height & 3) != 0) {
            throw new MediaException.Unsupported("raw", "the NEF high-efficiency picture is " + width + "x" + height);
        }
        if (components != COMPONENTS || groupSize != 4 || significanceGroup != 8 || precinctWidth != 0
                || sliceRows < 1 || waveletPrecision != WAVELET_PRECISION || fractionRaw != (FRACTION << 4 | RAW_BITS)
                || coding != 0x10 || (flags & 0x3F) != 0x14) {
            throw new MediaException.Unsupported("raw", "the NEF high-efficiency picture header has parameters no "
                    + "sample file has shown");
        }
        for (var i = 0; i < LAW_LENGTH; i++) {
            if ((data[p + LAW_OFFSET + i] & LAW_FORM_MASK[i]) != LAW_FORM[i]) {
                throw new MediaException.Unsupported("raw", "the NEF high-efficiency picture header has a square "
                        + "law no sample file has shown");
            }
        }
        var black = bits(data, p + LAW_OFFSET, 29, 11);
        var gain = bits(data, p + LAW_OFFSET, 62, 18);
        var pivot = bits(data, p + LAW_OFFSET, 80, 16);
        position += 2 + PICTURE_HEADER_LENGTH;
        int[] levels = null;
        int[] gains = null;
        int[] priorities = null;
        while (true) {
            if (position + 4 > data.length) {
                throw new MediaException.Malformed("the NEF high-efficiency main header is truncated");
            }
            var marker = u16(data, position);
            if (marker == SLH) {
                break;
            }
            var segmentLength = segment(data, position);
            var body = position + 4;
            if (body + segmentLength - 2 > data.length || segmentLength < 2) {
                throw new MediaException.Malformed("the NEF high-efficiency main header is truncated");
            }
            switch (marker) {
                case CDT -> {
                    if (levels != null || segmentLength != 2 + 3 * COMPONENTS) {
                        throw new MediaException.Malformed("invalid NEF high-efficiency component table");
                    }
                    levels = new int[COMPONENTS];
                    for (var c = 0; c < COMPONENTS; c++) {
                        var precision = data[body + 3 * c] & 0xFF;
                        var sampling = data[body + 3 * c + 1] & 0xFF;
                        var decomposition = data[body + 3 * c + 2] & 0xFF;
                        var horizontal = decomposition >>> 4;
                        var vertical = decomposition & 15;
                        var decomposed = horizontal >= 1 && horizontal <= 8 && vertical == 1;
                        var plain = horizontal == 0 && vertical == 0;
                        if (precision != PRECISION || sampling != 0x11 || !(decomposed || plain)) {
                            throw new MediaException.Unsupported("raw", "NEF high-efficiency component " + c
                                    + " has parameters no sample file has shown");
                        }
                        levels[c] = horizontal;
                    }
                }
                case WGT -> {
                    if (gains != null || ((segmentLength - 2) & 1) != 0) {
                        throw new MediaException.Malformed("invalid NEF high-efficiency weights table");
                    }
                    var count = (segmentLength - 2) / 2;
                    gains = new int[count];
                    priorities = new int[count];
                    for (var b = 0; b < count; b++) {
                        gains[b] = data[body + 2 * b] & 0xFF;
                        priorities[b] = data[body + 2 * b + 1] & 0xFF;
                    }
                }
                default -> throw new MediaException.Unsupported("raw", "the NEF high-efficiency main header has "
                        + "marker 0x" + Integer.toHexString(marker));
            }
            position = body + segmentLength - 2;
        }
        if (levels == null || gains == null) {
            throw new MediaException.Malformed("the NEF high-efficiency main header lacks its component or weights "
                    + "table");
        }
        var header = new Header(width, height, length, sliceRows, levels, gains, priorities, position, black, gain,
                pivot);
        var expected = 0;
        for (var c = 0; c < COMPONENTS; c++) {
            if (levels[c] > 0 && header.componentWidth() >> levels[c] == 0) {
                throw new MediaException.Malformed("the NEF high-efficiency picture is too narrow for its wavelet");
            }
            expected += levels[c] == 0 ? 1 : levels[c] + 3;
        }
        if (gains.length != expected) {
            throw new MediaException.Malformed("the NEF high-efficiency weights table lists " + gains.length
                    + " bands, the picture has " + expected);
        }
        return header;
    }

    /**
     * Decodes a codestream into a sensor plane of the size its header gives.
     *
     * @param data the codestream
     * @param header the parsed main header
     * @param plane the plane to fill
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the codestream is invalid or truncated
     * @throws MediaException.LimitExceeded if decoding needs more memory than the limits allow
     */
    static void decode(byte[] data, Header header, SensorPlane plane, MediaLimits limits) {
        if (plane.width != header.width() || plane.height != header.height() || plane.integers == null) {
            throw new MediaException.Malformed("the NEF high-efficiency picture does not match its directory");
        }
        var planes = coefficients(data, header, limits);
        var width = header.componentWidth();
        rowsOf(header.componentHeight(), width, y -> {
            for (var values : planes) {
                for (int i = y * width, end = i + width; i < end; i++) {
                    values[i] <<= FRACTION;
                }
            }
        });
        reconstruct(planes, header);
        output(planes, header, plane);
    }

    /**
     * Decodes the wavelet coefficients of a codestream: one plane per component, of half the width and height of the
     * color filter array, each band at its place in the plane (the deepest low band at the top left, the high bands
     * of each horizontal level to its right, the high vertical bands below the low ones).
     *
     * @param data the codestream
     * @param header the parsed main header
     * @param limits the resource bounds
     * @return the dequantized coefficients, without the fractional bits of the reconstruction
     * @throws MediaException.Malformed if the codestream is invalid or truncated
     * @throws MediaException.LimitExceeded if decoding needs more memory than the limits allow
     */
    static int[][] coefficients(byte[] data, Header header, MediaLimits limits) {
        var cw = header.componentWidth();
        var ch = header.componentHeight();
        LimitChecks.checkAllocation(limits, 4L * header.width() * header.height());
        var planes = new int[COMPONENTS][cw * ch];
        var packets = packets(header);
        var slices = header.slices();
        var starts = locate(data, header, slices);
        var bands = ParallelBands.bands(slices, (long) header.width() * header.height());
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, slices);
            var last = ParallelBands.start(band + 1, bands, slices);
            var decoder = new SliceDecoder(data, header, packets, planes);
            for (var slice = first; slice < last; slice++) {
                decoder.decode(slice, starts[slice]);
            }
        });
        return planes;
    }

    /**
     * Reconstructs the photosite values in the square-root domain from coefficients carrying the fractional bits:
     * the inverse wavelet of every decomposed component, then the inverse lifting of the components.
     *
     * @param planes the coefficients of each component, replaced by the values of its photosites
     * @param header the main header
     */
    static void reconstruct(int[][] planes, Header header) {
        var cw = header.componentWidth();
        var ch = header.componentHeight();
        for (var c = 0; c < COMPONENTS; c++) {
            if (header.levels()[c] > 0) {
                synthesize(planes[c], cw, ch, header.levels()[c]);
            }
        }
        lift(planes, cw, ch);
    }

    /**
     * Lists the band lines of each packet of a precinct and assigns each band its weights table index, in the order
     * the bands first appear.
     *
     * @param header the main header
     * @return the band lines of each packet
     */
    private static Part[][] packets(Header header) {
        var cw = header.componentWidth();
        var ch = header.componentHeight();
        var lowHeight = (ch + 1) / 2;
        var lists = new ArrayList<Part[]>();
        var weight = 0;
        var plain = new Band[COMPONENTS];
        var high = new Band[COMPONENTS][];
        for (var line = 0; line < 2; line++) {
            for (var c = 0; c < COMPONENTS; c++) {
                var levels = header.levels()[c];
                if (levels == 0) {
                    if (line == 0) {
                        plain[c] = new Band(c, 0, 0, cw, 2, weight++);
                    }
                    lists.add(new Part[]{new Part(plain[c], line)});
                    continue;
                }
                var half = (cw + 1) / 2;
                if (line == 0) {
                    var parts = new Part[levels + 1];
                    var low = ceilShift(cw, levels);
                    parts[0] = new Part(new Band(c, 0, 0, low, 1, weight++), 0);
                    for (var depth = levels; depth >= 2; depth--) {
                        var x = ceilShift(cw, depth);
                        var width = ceilShift(cw, depth - 1) - x;
                        parts[levels - depth + 1] = new Part(new Band(c, x, 0, width, 1, weight++), 0);
                    }
                    parts[levels] = new Part(new Band(c, half, 0, cw - half, 1, weight++), 0);
                    lists.add(parts);
                    high[c] = new Band[]{new Band(c, 0, lowHeight, half, 1, -1),
                            new Band(c, half, lowHeight, cw - half, 1, -1)};
                } else {
                    var lh = high[c][0];
                    var hh = high[c][1];
                    lists.add(new Part[]{new Part(new Band(c, lh.x(), lh.y(), lh.width(), 1, weight++), 0),
                            new Part(new Band(c, hh.x(), hh.y(), hh.width(), 1, weight++), 0)});
                }
            }
        }
        return lists.toArray(Part[][]::new);
    }

    /**
     * Finds the start of every slice by reading the precinct lengths.
     *
     * @param data the codestream
     * @param header the main header
     * @param slices the number of slices
     * @return the position of each slice header
     * @throws MediaException.Malformed if a slice header is missing or a precinct runs past the end
     */
    private static int[] locate(byte[] data, Header header, int slices) {
        var starts = new int[slices];
        var position = header.headerLength();
        var headerBytes = precinctHeaderBytes(header);
        for (var s = 0; s < slices; s++) {
            if (position + 6 > data.length || u16(data, position) != SLH || u16(data, position + 2) != 4
                    || u16(data, position + 4) != s) {
                throw new MediaException.Malformed("NEF high-efficiency slice " + s + " has no valid slice header");
            }
            starts[s] = position;
            position += 6;
            var rows = Math.min(header.sliceRows(), header.precinctRows() - s * header.sliceRows());
            for (var r = 0; r < rows; r++) {
                if (position + headerBytes > data.length) {
                    throw new MediaException.Malformed("NEF high-efficiency slice " + s + " is truncated");
                }
                var length = u24(data, position);
                position += headerBytes;
                if (length > data.length - position) {
                    throw new MediaException.Malformed("a NEF high-efficiency precinct runs past the end of the data");
                }
                position += length;
            }
        }
        if (position + 2 > data.length || u16(data, position) != EOC) {
            throw new MediaException.Malformed("the NEF high-efficiency data does not end after its slices");
        }
        return starts;
    }

    /**
     * {@return the length of a precinct header: its length, quantization and refinement, then two bits per band}
     */
    private static int precinctHeaderBytes(Header header) {
        return 5 + (2 * header.gains().length + 7) / 8;
    }

    /**
     * Decodes the slices of a codestream into wavelet coefficients. Instances are not thread-safe; each slice is
     * coded independently.
     */
    private static final class SliceDecoder {
        /**
         * The codestream.
         */
        private final byte[] data;

        /**
         * The main header.
         */
        private final Header header;

        /**
         * The band lines of each packet.
         */
        private final Part[][] packets;

        /**
         * The coefficient planes.
         */
        private final int[][] planes;

        /**
         * The bit-plane counts of the previous band line of each band and line, indexed by weight and line.
         */
        private final byte[][][] above;

        /**
         * The truncation of the previous band line of each band and line.
         */
        private final int[][] aboveTruncation;

        /**
         * The bit-plane counts of the current band lines.
         */
        private final byte[][][] current;

        /**
         * The truncation of the current band lines.
         */
        private final int[][] currentTruncation;

        /**
         * The coding mode of each band of the current precinct.
         */
        private final int[] modes;

        /**
         * The significance flags of a band line.
         */
        private final byte[] flags;

        /**
         * The number of nonzero coefficients past the band width of each band line of the current packet.
         */
        private final int[] paddings;

        /**
         * The bit reader.
         */
        private final Bits bits;

        /**
         * Constructs a decoder.
         *
         * @param data the codestream
         * @param header the main header
         * @param packets the band lines of each packet
         * @param planes the coefficient planes to fill
         */
        SliceDecoder(byte[] data, Header header, Part[][] packets, int[][] planes) {
            this.data = data;
            this.header = header;
            this.packets = packets;
            this.planes = planes;
            var count = header.gains().length;
            this.above = new byte[count][2][];
            this.current = new byte[count][2][];
            this.aboveTruncation = new int[count][2];
            this.currentTruncation = new int[count][2];
            var maxGroups = 1;
            for (var packet : packets) {
                for (var part : packet) {
                    var groups = part.band().groups();
                    maxGroups = Math.max(maxGroups, groups);
                    var w = part.band().weight();
                    above[w][part.line()] = new byte[groups];
                    current[w][part.line()] = new byte[groups];
                }
            }
            this.modes = new int[count];
            this.flags = new byte[(maxGroups + 7) / 8];
            var parts = 1;
            for (var packet : packets) {
                parts = Math.max(parts, packet.length);
            }
            this.paddings = new int[parts];
            this.bits = new Bits(data);
        }

        /**
         * Decodes one slice.
         *
         * @param slice the slice index
         * @param position the position of its slice header
         */
        void decode(int slice, int position) {
            for (var lines : above) {
                for (var line : lines) {
                    if (line != null) {
                        Arrays.fill(line, (byte) 0);
                    }
                }
            }
            for (var truncations : aboveTruncation) {
                Arrays.fill(truncations, 0);
            }
            var cursor = position + 6;
            var first = slice * header.sliceRows();
            var rows = Math.min(header.sliceRows(), header.precinctRows() - first);
            for (var r = 0; r < rows; r++) {
                cursor = precinct(first + r, cursor);
            }
        }

        /**
         * Decodes one precinct.
         *
         * @param row the precinct row
         * @param position the position of its header
         * @return the position after it
         */
        private int precinct(int row, int position) {
            var length = u24(data, position);
            var quantization = data[position + 3] & 0xFF;
            var refinement = data[position + 4] & 0xFF;
            bits.seek(position + 5);
            for (var b = 0; b < modes.length; b++) {
                modes[b] = bits.read(2);
            }
            var bodyStart = position + precinctHeaderBytes(header);
            var bodyEnd = bodyStart + length;
            var cursor = bodyStart;
            for (var packet : packets) {
                if (cursor + PACKET_HEADER > bodyEnd) {
                    throw new MediaException.Malformed("a NEF high-efficiency packet header runs past its precinct");
                }
                var fields = 0L;
                for (var i = 0; i < PACKET_HEADER; i++) {
                    fields = fields << 8 | data[cursor + i] & 0xFF;
                }
                var raw = fields >>> 55 != 0;
                var dataLength = (int) (fields >>> 35) & 0xFFFFF;
                var countLength = (int) (fields >>> 15) & 0xFFFFF;
                var signLength = (int) fields & 0x7FFF;
                cursor += PACKET_HEADER;
                if ((long) dataLength + countLength + signLength > bodyEnd - cursor) {
                    throw new MediaException.Malformed("a NEF high-efficiency packet runs past its precinct");
                }
                bits.seek(cursor);
                int countStart;
                if (raw) {
                    countStart = cursor;
                    for (var part : packet) {
                        var counts = current[part.band().weight()][part.line()];
                        for (var i = 0; i < counts.length; i++) {
                            counts[i] = (byte) bits.read(RAW_BITS);
                        }
                        currentTruncation[part.band().weight()][part.line()] = truncation(part.band(), quantization,
                                refinement);
                    }
                    bits.align();
                    countEnd(countStart, countLength, bodyEnd);
                } else {
                    var flagStart = bits.position();
                    var flagBits = 0L;
                    for (var part : packet) {
                        if ((modes[part.band().weight()] & SIGNIFICANCE) != 0) {
                            flagBits += (part.band().groups() + 7) / 8;
                        }
                    }
                    countStart = (int) ((flagStart + flagBits + 7) >>> 3);
                    if (countStart > bodyEnd) {
                        throw new MediaException.Malformed("NEF high-efficiency significance flags run past their "
                                + "precinct");
                    }
                    var flagCursor = flagStart;
                    var countCursor = (long) countStart << 3;
                    for (var part : packet) {
                        var band = part.band();
                        var significance = (modes[band.weight()] & SIGNIFICANCE) != 0;
                        var groups = band.groups();
                        if (significance) {
                            bits.seekBits(flagCursor);
                            for (var i = 0; i < (groups + 7) / 8; i++) {
                                flags[i] = (byte) bits.read(1);
                            }
                            flagCursor = bits.position();
                        }
                        bits.seekBits(countCursor);
                        counts(part, significance, quantization, refinement);
                        countCursor = bits.position();
                    }
                    bits.align();
                    countEnd(countStart, countLength, bodyEnd);
                }
                var dataStart = countStart + countLength;
                if ((long) dataStart + dataLength + signLength > bodyEnd) {
                    throw new MediaException.Malformed("a NEF high-efficiency packet runs past its precinct");
                }
                bits.seek(dataStart);
                for (var k = 0; k < packet.length; k++) {
                    paddings[k] = coefficients(packet[k], row);
                }
                bits.align();
                if (bits.position() > ((long) dataStart + dataLength) << 3) {
                    throw new MediaException.Malformed("a NEF high-efficiency data sub-packet is longer than declared");
                }
                var signStart = dataStart + dataLength;
                bits.seek(signStart);
                for (var k = 0; k < packet.length; k++) {
                    signs(packet[k], row, paddings[k]);
                }
                bits.align();
                if (bits.position() > ((long) signStart + signLength) << 3) {
                    throw new MediaException.Malformed("a NEF high-efficiency sign sub-packet is longer than declared");
                }
                cursor = signStart + signLength;
            }
            for (var w = 0; w < above.length; w++) {
                for (var line = 0; line < 2; line++) {
                    var swap = above[w][line];
                    above[w][line] = current[w][line];
                    current[w][line] = swap;
                    aboveTruncation[w][line] = currentTruncation[w][line];
                }
            }
            return bodyEnd;
        }

        /**
         * Checks that a bit-plane count sub-packet ended within its declared length.
         *
         * @param start the position of the sub-packet
         * @param declared its declared length in bytes
         * @param bodyEnd the end of the precinct
         * @throws MediaException.Malformed if the counts run past the declared length or the precinct
         */
        private void countEnd(int start, int declared, int bodyEnd) {
            if (bits.position() > ((long) start + declared) << 3 || start + declared > bodyEnd) {
                throw new MediaException.Malformed("a NEF high-efficiency bit-plane count sub-packet is longer than "
                        + "declared");
            }
        }

        /**
         * {@return the truncation position of a band in a precinct of quantization {@code quantization} and
         * refinement {@code refinement}}
         *
         * @param band the band whose truncation to compute
         * @param quantization the precinct's quantization
         * @param refinement the precinct's refinement
         */
        private int truncation(Band band, int quantization, int refinement) {
            var gain = header.gains()[band.weight()];
            var priority = header.priorities()[band.weight()];
            var pump = priority < refinement ? 1 : 0;
            if (quantization < gain + pump) {
                return 0;
            }
            return Math.min(quantization - gain - pump, 15);
        }

        /**
         * Reads the bit-plane counts of a band line, predicted vertically from the band line above when the band's
         * mode says so: the previous line of the same band in the precinct, or the last line of the band in the
         * precinct above, zero in the first precinct row of a slice.
         *
         * @param part the band line
         * @param significance whether the band codes significance flags, read into {@code flags}
         * @param quantization the precinct's quantization
         * @param refinement the precinct's refinement
         * @throws MediaException.Malformed if a count is out of range or its code too long
         */
        private void counts(Part part, boolean significance, int quantization, int refinement) {
            var band = part.band();
            var w = band.weight();
            var line = part.line();
            var counts = current[w][line];
            var gtli = truncation(band, quantization, refinement);
            currentTruncation[w][line] = gtli;
            var groups = counts.length;
            if ((modes[w] & VERTICAL) == 0) {
                for (var i = 0; i < groups; i++) {
                    if (significance && flags[i >>> 3] != 0) {
                        counts[i] = (byte) gtli;
                        continue;
                    }
                    counts[i] = (byte) checked(gtli + unary());
                }
                return;
            }
            byte[] top;
            int topTruncation;
            if (band.lines() == 2 && line == 1) {
                top = current[w][0];
                topTruncation = currentTruncation[w][0];
            } else {
                var source = band.lines() == 2 ? 1 : 0;
                top = above[w][source];
                topTruncation = aboveTruncation[w][source];
            }
            var t = Math.max(topTruncation, gtli);
            for (var i = 0; i < groups; i++) {
                var predicted = Math.max(top[i], t);
                if (significance && flags[i >>> 3] != 0) {
                    counts[i] = (byte) predicted;
                    continue;
                }
                var x = unary();
                var threshold = Math.max(predicted - gtli, 0);
                int delta;
                if (x > 2 * threshold) {
                    delta = x - threshold;
                } else if (x > 0) {
                    delta = (x & 1) != 0 ? -((x + 1) >> 1) : x >> 1;
                } else {
                    delta = 0;
                }
                counts[i] = (byte) checked(predicted + delta);
            }
        }

        /**
         * {@return the next unary code}
         *
         * @throws MediaException.Malformed if the code is too long
         */
        private int unary() {
            var x = bits.unary(MAX_UNARY);
            if (x < 0) {
                throw new MediaException.Malformed("a NEF high-efficiency bit-plane count code is too long");
            }
            return x;
        }

        /**
         * {@return a bit-plane count, checked}
         *
         * @param count the decoded bit-plane count
         * @throws MediaException.Malformed if the count is negative or more than {@link #MAX_BITPLANES}
         */
        private static int checked(int count) {
            if (count < 0 || count > MAX_BITPLANES) {
                throw new MediaException.Malformed("a NEF high-efficiency code group has " + count + " bit-planes");
            }
            return count;
        }

        /**
         * Reads the magnitudes of a band line and writes them, dequantized, into its component plane.
         *
         * @param part the band line
         * @param row the precinct row
         * @return the number of nonzero coefficients past the band width in its last code group
         */
        private int coefficients(Part part, int row) {
            var band = part.band();
            var w = band.weight();
            var counts = current[w][part.line()];
            var gtli = currentTruncation[w][part.line()];
            var plane = planes[band.component()];
            var base = (band.y() + row * band.lines() + part.line()) * header.componentWidth() + band.x();
            var width = band.width();
            var padding = 0;
            for (var group = 0; group < counts.length; group++) {
                var count = group * 4 + 4 <= width ? 4 : width - group * 4;
                var target = base + group * 4;
                var gcli = counts[group];
                var planesCoded = gcli - gtli;
                if (planesCoded <= 0) {
                    Arrays.fill(plane, target, target + count, 0);
                    continue;
                }
                var m0 = 0;
                var m1 = 0;
                var m2 = 0;
                var m3 = 0;
                for (var k = 0; k < planesCoded; k++) {
                    var nibble = bits.read(4);
                    m0 = m0 << 1 | nibble >>> 3;
                    m1 = m1 << 1 | (nibble >>> 2) & 1;
                    m2 = m2 << 1 | (nibble >>> 1) & 1;
                    m3 = m3 << 1 | nibble & 1;
                }
                plane[target] = dequantize(m0, gcli, gtli);
                if (count > 1) {
                    plane[target + 1] = dequantize(m1, gcli, gtli);
                } else if (m1 != 0) {
                    padding++;
                }
                if (count > 2) {
                    plane[target + 2] = dequantize(m2, gcli, gtli);
                } else if (m2 != 0) {
                    padding++;
                }
                if (count > 3) {
                    plane[target + 3] = dequantize(m3, gcli, gtli);
                } else if (m3 != 0) {
                    padding++;
                }
            }
            return padding;
        }

        /**
         * Reconstructs a magnitude from its coded bit-planes with the uniform quantizer of JPEG XS.
         *
         * @param magnitude the coded bit-planes of the magnitude
         * @param gcli the bit-plane count of its code group
         * @param gtli the truncation position of its band
         * @return the reconstructed magnitude
         */
        private static int dequantize(int magnitude, int gcli, int gtli) {
            var value = magnitude << gtli;
            if (gtli == 0 || magnitude == 0) {
                return value;
            }
            var scale = gcli - gtli + 1;
            var result = 0;
            for (var v = value; v > 0; v >>>= scale) {
                result += v;
            }
            return result;
        }

        /**
         * Reads the signs of the nonzero coefficients of a band line, and skips those of the {@code padding} nonzero
         * coefficients past the band width.
         *
         * @param part the band line
         * @param row the precinct row
         * @param padding the number of nonzero coefficients past the band width
         */
        private void signs(Part part, int row, int padding) {
            var band = part.band();
            var plane = planes[band.component()];
            var base = (band.y() + row * band.lines() + part.line()) * header.componentWidth() + band.x();
            for (var i = 0; i < band.width(); i++) {
                var v = plane[base + i];
                if (v != 0 && bits.read(1) != 0) {
                    plane[base + i] = -v;
                }
            }
            bits.skip(padding);
        }
    }

    /**
     * Applies the inverse 5/3 wavelet to a component: the horizontal levels of the low vertical half from the deepest
     * one, then the first horizontal and vertical level over the whole plane.
     *
     * @param plane the coefficients, replaced by the samples
     * @param width the component width
     * @param height the component height
     * @param levels the horizontal levels
     */
    private static void synthesize(int[] plane, int width, int height, int levels) {
        var lowHeight = (height + 1) / 2;
        for (var depth = levels; depth >= 2; depth--) {
            rows(plane, width, lowHeight, ceilShift(width, depth - 1), ceilShift(width, depth));
        }
        rows(plane, width, height, width, (width + 1) / 2);
        columns(plane, width, height, lowHeight);
    }

    /**
     * Synthesizes the first {@code length} samples of the first {@code count} rows from their low and high halves.
     *
     * @param plane the component plane
     * @param stride the plane width
     * @param count the number of rows
     * @param length the number of samples of each row
     * @param low the number of low samples, which come first
     */
    private static void rows(int[] plane, int stride, int count, int length, int low) {
        if (length < 2) {
            return;
        }
        var bands = ParallelBands.bands(count, (long) count * length);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, count);
            var to = ParallelBands.start(band + 1, bands, count);
            var scratch = new int[length];
            for (var r = from; r < to; r++) {
                System.arraycopy(plane, r * stride, scratch, 0, length);
                synthesize(scratch, 0, low, 1, plane, r * stride, 1, length);
            }
        });
    }

    /**
     * Synthesizes every column from its low and high vertical halves.
     *
     * @param plane the component plane
     * @param width the plane width
     * @param height the plane height
     * @param low the number of low rows, which come first
     */
    private static void columns(int[] plane, int width, int height, int low) {
        if (height < 2) {
            return;
        }
        var strip = 64;
        var strips = (width + strip - 1) / strip;
        var bands = ParallelBands.bands(strips, (long) width * height);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, strips);
            var to = ParallelBands.start(band + 1, bands, strips);
            var scratch = new int[height * strip];
            for (var s = from; s < to; s++) {
                var x0 = s * strip;
                var span = Math.min(strip, width - x0);
                for (var r = 0; r < height; r++) {
                    System.arraycopy(plane, r * width + x0, scratch, r * strip, span);
                }
                for (var i = 0; i < span; i++) {
                    synthesize(scratch, i, low * strip + i, strip, plane, x0 + i, width, height);
                }
            }
        });
    }

    /**
     * Applies the inverse 5/3 lifting to {@code n} samples whose low half starts at {@code lowStart} and high half at
     * {@code highStart} of {@code in}, both stepping by {@code step}, writing them interleaved into {@code out}.
     *
     * @param in the coefficients
     * @param lowStart the index of the first low coefficient
     * @param highStart the index of the first high coefficient
     * @param step the distance between coefficients
     * @param out the samples
     * @param outStart the index of the first sample
     * @param outStep the distance between samples
     * @param n the number of samples
     */
    private static void synthesize(int[] in, int lowStart, int highStart, int step, int[] out, int outStart,
                                   int outStep, int n) {
        var nl = (n + 1) >>> 1;
        var nh = n >>> 1;
        for (var i = 0; i < nl; i++) {
            var previous = in[highStart + Math.max(i - 1, 0) * step];
            var current = in[highStart + Math.min(i, nh - 1) * step];
            out[outStart + 2 * i * outStep] = in[lowStart + i * step] - ((previous + current + 2) >> 2);
        }
        for (var i = 0; i < nh; i++) {
            var left = out[outStart + 2 * i * outStep];
            var right = 2 * i + 2 < n ? out[outStart + (2 * i + 2) * outStep] : left;
            out[outStart + (2 * i + 1) * outStep] = in[highStart + i * step] + ((left + right) >> 1);
        }
    }

    /**
     * Turns the four components into the mosaic's photosites, in place: component 0 (on the photosite right of the
     * top-left one of each 2x2 block) loses an eighth of its four diagonal neighbors of component 2, component 2
     * gains a quarter of its four diagonal neighbors of component 0, both lose an eighth of their four horizontal and
     * vertical neighbors, and components 1 and 3 gain a quarter of theirs. Neighbors past the edge are mirrored.
     *
     * @param planes the components
     * @param width the component width
     * @param height the component height
     */
    private static void lift(int[][] planes, int width, int height) {
        var y0 = planes[0];
        var c1 = planes[1];
        var d2 = planes[2];
        var c3 = planes[3];
        rowsOf(height, width, y -> {
            var up = Math.max(y - 1, 0) * width;
            var at = y * width;
            for (var x = 0; x < width; x++) {
                var right = Math.min(x + 1, width - 1);
                y0[at + x] -= (d2[up + x] + d2[up + right] + d2[at + x] + d2[at + right]) >> 3;
            }
        });
        rowsOf(height, width, y -> {
            var down = Math.min(y + 1, height - 1) * width;
            var at = y * width;
            for (var x = 0; x < width; x++) {
                var left = Math.max(x - 1, 0);
                d2[at + x] += (y0[at + left] + y0[at + x] + y0[down + left] + y0[down + x]) >> 2;
            }
        });
        rowsOf(height, width, y -> {
            var up = Math.max(y - 1, 0) * width;
            var down = Math.min(y + 1, height - 1) * width;
            var at = y * width;
            for (var x = 0; x < width; x++) {
                var right = Math.min(x + 1, width - 1);
                var left = Math.max(x - 1, 0);
                y0[at + x] -= (c1[at + x] + c1[at + right] + c3[up + x] + c3[at + x]) >> 3;
                d2[at + x] -= (c3[at + left] + c3[at + x] + c1[at + x] + c1[down + x]) >> 3;
            }
        });
        rowsOf(height, width, y -> {
            var up = Math.max(y - 1, 0) * width;
            var down = Math.min(y + 1, height - 1) * width;
            var at = y * width;
            for (var x = 0; x < width; x++) {
                var right = Math.min(x + 1, width - 1);
                var left = Math.max(x - 1, 0);
                c1[at + x] += (y0[at + left] + y0[at + x] + d2[up + x] + d2[at + x]) >> 2;
                c3[at + x] += (d2[at + x] + d2[at + right] + y0[at + x] + y0[down + x]) >> 2;
            }
        });
    }

    /**
     * Writes the photosites: each reconstructed value through the square law.
     *
     * @param planes the reconstructed components
     * @param header the main header, which gives the square law
     * @param plane the sensor plane
     */
    private static void output(int[][] planes, Header header, SensorPlane plane) {
        var width = header.componentWidth();
        var height = header.componentHeight();
        var black = header.black();
        var gain = header.gain();
        var pivot = header.pivot();
        var out = plane.integers;
        var stride = plane.width;
        rowsOf(height, width, y -> {
            var top = 2 * y * stride;
            var bottom = top + stride;
            var at = y * width;
            for (var x = 0; x < width; x++) {
                out[top + 2 * x + 1] = (short) linear(planes[0][at + x], black, gain, pivot);
                out[top + 2 * x] = (short) linear(planes[1][at + x], black, gain, pivot);
                out[bottom + 2 * x] = (short) linear(planes[2][at + x], black, gain, pivot);
                out[bottom + 2 * x + 1] = (short) linear(planes[3][at + x], black, gain, pivot);
            }
        });
    }

    /**
     * Maps a reconstructed value to its linear photosite value through the square law.
     *
     * @param value the reconstructed value
     * @param black the square law's black level
     * @param gain the square law's gain, with 16 fractional bits
     * @param pivot the 16-bit code the square law maps to the black level
     * @return the photosite value, from 0 to {@code 2^14 - 1}
     */
    static int linear(int value, int black, int gain, int pivot) {
        long d = Math.clamp((long) value + CODE_OFFSET, 0, MAX_CODE) - pivot;
        long y;
        if (d >= 0) {
            var q = (d * gain + (1L << (GAIN_BITS - 1))) >> GAIN_BITS;
            y = q * q;
        } else {
            y = -d * d;
        }
        return (int) Math.clamp(black + ((y + SQUARE_ROUNDING) >> SQUARE_BITS), 0, MAX_OUTPUT);
    }

    /**
     * Reads a field of a big-endian bit string.
     *
     * @param data the bytes
     * @param offset the offset of the bit string
     * @param first the index of the field's first bit, 0 for the most significant bit of the first byte
     * @param count the number of bits, at most 31
     * @return the field's value
     */
    private static int bits(byte[] data, int offset, int first, int count) {
        var value = 0;
        for (var i = first; i < first + count; i++) {
            value = value << 1 | data[offset + (i >>> 3)] >>> (7 - (i & 7)) & 1;
        }
        return value;
    }

    /**
     * Runs {@code body} for every row of {@code height}, in parallel bands.
     *
     * @param height the number of rows
     * @param width the row width, which weighs the work
     * @param body the work of one row
     */
    private static void rowsOf(int height, int width, IntConsumer body) {
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, height);
            var to = ParallelBands.start(band + 1, bands, height);
            for (var y = from; y < to; y++) {
                body.accept(y);
            }
        });
    }

    /**
     * {@return {@code value} divided by {@code 2^shift}, rounded up}
     */
    private static int ceilShift(int value, int shift) {
        return (value + (1 << shift) - 1) >> shift;
    }

    /**
     * {@return the length field of the marker segment at {@code position}}
     */
    private static int segment(byte[] data, int position) {
        if (position + 4 > data.length) {
            throw new MediaException.Malformed("a NEF high-efficiency marker segment is truncated");
        }
        return u16(data, position + 2);
    }

    /**
     * {@return the big-endian 16-bit value at {@code position}}
     */
    private static int u16(byte[] data, int position) {
        return (data[position] & 0xFF) << 8 | data[position + 1] & 0xFF;
    }

    /**
     * {@return the big-endian 24-bit value at {@code position}}
     */
    private static int u24(byte[] data, int position) {
        return (data[position] & 0xFF) << 16 | (data[position + 1] & 0xFF) << 8 | data[position + 2] & 0xFF;
    }

    /**
     * {@return the big-endian 32-bit value at {@code position}, unsigned}
     */
    private static long u32(byte[] data, int position) {
        return (long) u16(data, position) << 16 | u16(data, position + 2);
    }

    /**
     * Reads bits most significant first, with random access; past the end of the data it reads zero bits.
     */
    private static final class Bits {
        /**
         * The data.
         */
        private final byte[] data;

        /**
         * The position of the next bit.
         */
        private long position;

        /**
         * Constructs a reader at the start of {@code data}.
         *
         * @param data the bytes to read
         */
        Bits(byte[] data) {
            this.data = data;
        }

        /**
         * Moves to a byte position.
         *
         * @param bytePosition the byte position
         */
        void seek(int bytePosition) {
            position = (long) bytePosition << 3;
        }

        /**
         * Moves to a bit position.
         *
         * @param bitPosition the bit position
         */
        void seekBits(long bitPosition) {
            position = bitPosition;
        }

        /**
         * {@return the position of the next bit}
         */
        long position() {
            return position;
        }

        /**
         * Moves to the next byte boundary, unless at one.
         */
        void align() {
            position = (position + 7) & ~7L;
        }

        /**
         * Skips bits.
         *
         * @param count the number of bits
         */
        void skip(int count) {
            position += count;
        }

        /**
         * {@return the next bit}
         */
        private int bit() {
            var index = position >>> 3;
            var value = index < data.length ? (data[(int) index] >> (7 - (int) (position & 7))) & 1 : 0;
            position++;
            return value;
        }

        /**
         * {@return the next {@code count} bits, most significant first}
         *
         * @param count the number of bits, at most 31
         */
        int read(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = value << 1 | bit();
            }
            return value;
        }

        /**
         * {@return the number of one bits before the next zero bit, or -1 if more than {@code max} come first}
         *
         * @param max the longest code
         */
        int unary(int max) {
            var count = 0;
            while (bit() != 0) {
                if (++count > max) {
                    return -1;
                }
            }
            return count;
        }
    }
}
