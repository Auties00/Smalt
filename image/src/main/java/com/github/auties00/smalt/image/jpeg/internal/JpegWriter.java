/* Ports libjpeg-turbo (jcparam.c, jccolor.c, jcsample.c, jcdctmgr.c, jchuff.c). Copyright (C) 1991-1998, Thomas
   G. Lane, Guido Vollbeding; Copyright (C) 2009-2024, D. R. Commander and the libjpeg-turbo authors; IJG License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.List;

/**
 * Encodes a picture as a baseline JFIF JPEG.
 *
 * <p>The output is SOI, a JFIF 1.01 APP0 segment with a 1:1 aspect ratio, the
 * {@code APP2 ICC_PROFILE} segments of an embedded profile, one DQT segment per quantization
 * table, SOF0, one DHT segment per Huffman table (luminance DC, luminance AC, chrominance DC,
 * chrominance AC), DRI when restart markers are requested, SOS, the entropy-coded data, and EOI.
 * Components have identifiers 1, 2 and 3 and use table 0 for luminance and table 1 for both
 * chrominance components. Quantizers are limited to 255, so every output is baseline.
 *
 * <p>A planar picture already in the target subsampling with full-range BT.601 samples is coded
 * as is; any other input is turned into full-resolution Y'CbCr rows, then downsampled. Samples
 * outside the picture are filled as libjpeg fills them: rows and columns replicate the last
 * sample, chroma is downsampled from the edge-extended full-resolution rows, and MCU blocks
 * entirely outside a component become dummy blocks with zero AC coefficients and the previous
 * block's DC coefficient. For the same input and settings the output is byte-identical to
 * {@code cjpeg -dct int} with the same subsampling, and it does not depend on the number of
 * threads.
 *
 * @implNote This implementation transforms bands of MCU rows on several threads, keeps the
 * quantized blocks, and entropy-codes them in order afterwards. With the Vector API the blocks are
 * transformed {@link BatchFdctKernel#LANES} at a time; without it each block is transformed alone
 * by {@link ScalarFdctKernel}, and both produce the same coefficients.
 */
public final class JpegWriter {
    /**
     * The JFIF identifier and version 1.01, units 0, density 1:1, no thumbnail.
     */
    private static final byte[] JFIF = {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};

    /**
     * Maps each byte of a natural-order nonzero mask, by its row, to the corresponding bits of the
     * zigzag-order mask.
     */
    private static final long[][] ZIGZAG_MASKS = zigzagMasks();

    /**
     * The largest width or height of a JPEG frame.
     */
    private static final int MAX_DIMENSION = 65535;

    /**
     * The approximate number of operations to transform, quantize and keep one block, which
     * weighs a picture's work against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long BLOCK_WORK = 256;

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The number of components, 1 or 3.
     */
    private final int components;

    /**
     * The horizontal sampling factor of each component.
     */
    private final int[] h;

    /**
     * The vertical sampling factor of each component.
     */
    private final int[] v;

    /**
     * The number of block columns of each component.
     */
    private final int[] blocksWide;

    /**
     * The number of block rows of each component.
     */
    private final int[] blocksHigh;

    /**
     * The width in samples of each component.
     */
    private final int[] componentWidth;

    /**
     * The height in samples of each component.
     */
    private final int[] componentHeight;

    /**
     * The number of MCU columns.
     */
    private final int mcusWide;

    /**
     * The number of MCU rows.
     */
    private final int mcusHigh;

    /**
     * The number of sample rows of each component in one MCU row.
     */
    private final int[] bandRows;

    /**
     * The row stride of each component's band: a whole number of {@link BatchFdctKernel} batches
     * covering every block column of the MCUs, including the dummy blocks at the right edge.
     */
    private final int[] bandStride;

    /**
     * The quantization table of each component's table slot: luminance, then chrominance.
     */
    private final QuantDivisors[] divisors;

    /**
     * The restart interval in MCUs, or zero.
     */
    private final int restartInterval;

    /**
     * The planar source coded as is, or {@code null}.
     */
    private final YuvPicture planar;

    /**
     * The full-resolution row source, or {@code null} when {@link #planar} is used.
     */
    private final YccRows rows;

    /**
     * The output.
     */
    private final JpegBitWriter out;

    /**
     * The {@code APP2 ICC_PROFILE} segments written after the JFIF segment, possibly none.
     */
    private final List<byte[]> iccSegments;

    /**
     * The payload of the {@code APP0} segment after SOI.
     */
    private final byte[] app0;

    /**
     * Whether the DHT segments are written.
     */
    private final boolean huffmanTables;

    /**
     * The most threads one call uses, or 0 for the shared pool's default share.
     */
    private final int threads;

    /**
     * Whether the blocks are transformed a batch at a time by the Vector API kernel, rather than one
     * at a time by {@link #BLOCK_FDCT}; both give the same coefficients.
     *
     * <p>Batches pay off with the Vector API kernel only, so this is {@link Simd#ENABLED}.
     */
    private final boolean batched;

    /**
     * The forward DCT of one block, used when the Vector API is unavailable.
     */
    private static final ScalarFdctKernel BLOCK_FDCT = new ScalarFdctKernel();

    /**
     * The batched forward DCT kernel, or {@code null} when the blocks are transformed one at a
     * time.
     */
    private final BatchFdctKernel fdct;

    /**
     * Prepares an encoder.
     *
     * @param picture the picture to encode; the alpha channel is ignored
     * @param quality the quality, from 1 to 100
     * @param format the target subsampling
     * @param restartInterval the restart interval in MCUs, or zero
     * @param iccProfile the ICC profile to embed, or {@code null}
     * @param app0 the payload of the {@code APP0} segment after SOI
     * @param huffmanTables whether the DHT segments are written
     * @param threads the most threads one call uses, or 0 for the shared pool's default share
     * @throws IllegalArgumentException if the picture exceeds 65535 pixels per side
     * @throws MediaException.LimitExceeded if the profile needs more than 255 segments
     */
    private JpegWriter(Picture picture, int quality, ChromaFormat format, int restartInterval, byte[] iccProfile,
                       byte[] app0, boolean huffmanTables, int threads) {
        this.width = picture.width();
        this.height = picture.height();
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " exceeds the JPEG limit of "
                    + MAX_DIMENSION + " pixels per side");
        }
        this.restartInterval = restartInterval;
        this.app0 = app0;
        this.huffmanTables = huffmanTables;
        this.threads = threads;
        this.iccSegments = iccProfile == null ? List.of() : JpegIcc.segments(iccProfile);
        this.batched = Simd.ENABLED;
        this.fdct = batched ? VectorKernels.batchFdct() : null;
        this.components = format.hasChroma() ? 3 : 1;
        this.h = new int[components];
        this.v = new int[components];
        h[0] = 1 << format.shiftX();
        v[0] = 1 << format.shiftY();
        for (var c = 1; c < components; c++) {
            h[c] = 1;
            v[c] = 1;
        }
        var hMax = h[0];
        var vMax = v[0];
        this.componentWidth = new int[components];
        this.componentHeight = new int[components];
        this.blocksWide = new int[components];
        this.blocksHigh = new int[components];
        this.bandRows = new int[components];
        this.bandStride = new int[components];
        for (var c = 0; c < components; c++) {
            componentWidth[c] = JpegFrame.ceilDiv((long) width * h[c], hMax);
            componentHeight[c] = JpegFrame.ceilDiv((long) height * v[c], vMax);
            blocksWide[c] = JpegFrame.ceilDiv(componentWidth[c], 8);
            blocksHigh[c] = JpegFrame.ceilDiv(componentHeight[c], 8);
            bandRows[c] = components == 1 ? 8 : v[c] * 8;
        }
        if (components == 1) {
            mcusWide = blocksWide[0];
            mcusHigh = blocksHigh[0];
        } else {
            mcusWide = JpegFrame.ceilDiv(width, hMax * 8);
            mcusHigh = JpegFrame.ceilDiv(height, vMax * 8);
        }
        for (var c = 0; c < components; c++) {
            var mcuBlocks = components == 1 ? mcusWide : mcusWide * h[c];
            var batches = JpegFrame.ceilDiv(Math.max(mcuBlocks, blocksWide[c]), BatchFdctKernel.LANES);
            bandStride[c] = batched ? batches * BatchFdctKernel.LANES * 8 : blocksWide[c] * 8;
        }
        this.divisors = new QuantDivisors[components == 1 ? 1 : 2];
        divisors[0] = new QuantDivisors(scaledTable(JpegTables.STD_LUMINANCE_QUANT, quality));
        if (components == 3) {
            divisors[1] = new QuantDivisors(scaledTable(JpegTables.STD_CHROMINANCE_QUANT, quality));
        }
        if (picture instanceof YuvPicture yuv && yuv.chromaFormat() == format && yuv.color().range() == ColorRange.FULL
                && YccRows.FromOtherYuv.isBt601(yuv.color().matrix())) {
            this.planar = yuv;
            this.rows = null;
        } else {
            this.planar = null;
            this.rows = switch (picture) {
                case RgbaPicture rgba -> new YccRows.FromRgba(rgba, ColorKernel.INSTANCE);
                case YuvPicture yuv when yuv.color().range() == ColorRange.FULL
                        && YccRows.FromOtherYuv.isBt601(yuv.color().matrix()) -> new YccRows.FromYuv(yuv);
                case YuvPicture yuv when !YccRows.FromOtherYuv.converts(yuv.color().matrix()) ->
                        new YccRows.FromRgba(ImageOps.toRgba(yuv), ColorKernel.INSTANCE);
                case YuvPicture yuv -> new YccRows.FromOtherYuv(yuv, ColorKernel.INSTANCE);
                case Rgba16Picture high -> new YccRows.FromRgba(ImageOps.toEightBit(high), ColorKernel.INSTANCE);
                case Yuv16Picture high when !YccRows.FromOtherYuv.converts(high.color().matrix()) ->
                        new YccRows.FromRgba(ImageOps.toRgba(high), ColorKernel.INSTANCE);
                case Yuv16Picture high -> new YccRows.FromOtherYuv(ImageOps.toEightBit(high), ColorKernel.INSTANCE);
                case ChannelPicture channels -> new YccRows.FromRgba(ImageOps.toRgba(channels), ColorKernel.INSTANCE);
            };
        }
        this.out = new JpegBitWriter((int) Math.min(Integer.MAX_VALUE - 16, (long) width * height / 2 + 1024));
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture; the alpha channel is ignored
     * @param quality the quality, from 1 to 100, scaled as libjpeg scales it
     * @param format the target subsampling
     * @param optimize whether to build optimal Huffman tables in a first pass
     * @param restartInterval the restart interval in MCUs, or zero
     * @param iccProfile the ICC profile written as {@code APP2 ICC_PROFILE} segments after the JFIF
     * segment, or {@code null} for none
     * @return the JPEG file
     * @throws IllegalArgumentException if the picture exceeds 65535 pixels per side
     * @throws MediaException.LimitExceeded if the profile needs more than 255 segments
     */
    public static byte[] encode(Picture picture, int quality, ChromaFormat format, boolean optimize, int restartInterval,
                                byte[] iccProfile) {
        return new JpegWriter(picture, quality, format, restartInterval, iccProfile, JFIF, true, 0).encode(optimize);
    }

    /**
     * Encodes a picture as one Motion-JPEG image: the output of
     * {@link #encode(Picture, int, ChromaFormat, boolean, int, byte[])} with another {@code APP0}
     * payload in place of the JFIF one, optionally without its DHT segments, and with a cap on the
     * threads.
     *
     * @param picture the picture; the alpha channel is ignored
     * @param quality the quality, from 1 to 100, scaled as libjpeg scales it
     * @param format the target subsampling
     * @param optimize whether to build optimal Huffman tables in a first pass; {@code false} when
     * {@code huffmanTables} is {@code false}
     * @param restartInterval the restart interval in MCUs, or zero
     * @param app0 the payload of the {@code APP0} segment after SOI, or {@code null} for the JFIF one
     * @param huffmanTables whether the DHT segments are written; without them the stream relies on
     * the tables of T.81 Annex K
     * @param threads the most threads the call uses, or 0 for the shared pool's default share; the
     * output does not depend on it
     * @return the JPEG stream
     * @throws IllegalArgumentException if the picture exceeds 65535 pixels per side
     */
    public static byte[] encodeMotion(Picture picture, int quality, ChromaFormat format, boolean optimize,
                                      int restartInterval, byte[] app0, boolean huffmanTables, int threads) {
        return new JpegWriter(picture, quality, format, restartInterval, null, app0 == null ? JFIF : app0,
                huffmanTables, threads).encode(optimize);
    }

    /**
     * Returns a quantization table scaled for a quality, as {@code jpeg_set_quality} does with
     * baseline limiting.
     *
     * @param base the example table of T.81 Annex K, natural order
     * @param quality the quality, from 1 to 100
     * @return the scaled table, each value from 1 to 255
     */
    public static int[] scaledTable(int[] base, int quality) {
        var q = Math.clamp(quality, 1, 100);
        var scale = q < 50 ? 5000 / q : 200 - q * 2;
        var table = new int[64];
        for (var i = 0; i < 64; i++) {
            var value = ((long) base[i] * scale + 50) / 100;
            table[i] = (int) Math.clamp(value, 1, 255);
        }
        return table;
    }

    /**
     * Encodes the picture.
     *
     * <p>The first pass transforms and quantizes every block, keeping its nonzero coefficients
     * and counting its AC symbols; bands of MCU rows are transformed on several threads, since no
     * block depends on another. A sequential pass then turns the kept DC coefficients into
     * differences and counts their symbols, the Huffman tables are chosen (optimal ones, or those
     * of T.81 Annex K), and the last pass codes the kept blocks in order. Without restart markers
     * each band is coded on its own thread into raw bits, and the bands' bits are then appended in
     * order with the byte stuffing, which yields exactly the bits of coding the blocks in one
     * pass.
     *
     * @param optimize whether to build optimal Huffman tables
     * @return the JPEG file
     */
    private byte[] encode(boolean optimize) {
        var perMcu = blocksPerMcu();
        var bands = ParallelBands.bands(mcusHigh, (long) mcusWide * mcusHigh * perMcu * BLOCK_WORK);
        var bandCount = threads > 0 ? Math.min(bands, threads) : bands;
        var stores = new BlockStore[bandCount];
        ParallelBands.run(bandCount, band -> stores[band] = new BandEncoder().transform(
                ParallelBands.start(band, bandCount, mcusHigh), ParallelBands.start(band + 1, bandCount, mcusHigh)));
        var tables = components == 1 ? 1 : 2;
        var dcCounts = new long[tables][256];
        var acCounts = new long[tables][256];
        for (var store : stores) {
            for (var t = 0; t < tables; t++) {
                for (var symbol = 0; symbol < 256; symbol++) {
                    acCounts[t][symbol] += store.acCounts[t][symbol];
                }
            }
        }
        resolveDc(stores, dcCounts);
        var dc = new HuffmanEncodingTable[tables];
        var ac = new HuffmanEncodingTable[tables];
        if (optimize) {
            for (var t = 0; t < tables; t++) {
                dc[t] = new HuffmanEncodingTable(HuffmanEncodingTable.optimal(dcCounts[t]));
                ac[t] = new HuffmanEncodingTable(HuffmanEncodingTable.optimal(acCounts[t]));
            }
        } else {
            dc[0] = new HuffmanEncodingTable(HuffmanSpec.STD_DC_LUMINANCE);
            ac[0] = new HuffmanEncodingTable(HuffmanSpec.STD_AC_LUMINANCE);
            if (tables == 2) {
                dc[1] = new HuffmanEncodingTable(HuffmanSpec.STD_DC_CHROMINANCE);
                ac[1] = new HuffmanEncodingTable(HuffmanSpec.STD_AC_CHROMINANCE);
            }
        }
        writeHeaders(dc, ac);
        if (restartInterval == 0 && stores.length > 1) {
            var raws = new JpegBitWriter[stores.length];
            ParallelBands.run(stores.length, band -> raws[band] = code(stores[band], dc, ac));
            for (var raw : raws) {
                out.appendRaw(raw);
            }
        } else {
            replay(stores, dc, ac);
        }
        out.flushBits();
        out.writeMarker(0xD9);
        return out.toByteArray();
    }

    /**
     * Returns the number of blocks in one MCU.
     *
     * @return the block count
     */
    private int blocksPerMcu() {
        if (components == 1) {
            return 1;
        }
        var blocks = 0;
        for (var c = 0; c < components; c++) {
            blocks += h[c] * v[c];
        }
        return blocks;
    }

    /**
     * Returns the component of each block of an MCU, in coding order.
     *
     * @return the component indexes
     */
    private int[] mcuComponents() {
        var result = new int[blocksPerMcu()];
        if (components == 1) {
            return result;
        }
        var b = 0;
        for (var c = 0; c < components; c++) {
            for (var i = 0; i < h[c] * v[c]; i++) {
                result[b++] = c;
            }
        }
        return result;
    }

    /**
     * Replaces the kept DC coefficient of every block by its difference from the previous block
     * of the same component, in coding order with the predictions reset at every restart
     * boundary, and counts the DC symbols.
     *
     * @param stores the kept blocks of every band, in band order
     * @param dcCounts the DC symbol counts of each table slot, filled by this method
     */
    private void resolveDc(BlockStore[] stores, long[][] dcCounts) {
        var componentOf = mcuComponents();
        var perMcu = componentOf.length;
        var lastDc = new int[3];
        var mcu = 0L;
        for (var store : stores) {
            var values = store.values;
            var masks = store.masks;
            var position = 0;
            for (var block = 0; block < store.blocks; ) {
                if (restartInterval != 0 && mcu != 0 && mcu % restartInterval == 0) {
                    Arrays.fill(lastDc, 0);
                }
                for (var i = 0; i < perMcu; i++) {
                    var component = componentOf[i];
                    int value = values[position];
                    var diff = value - lastDc[component];
                    lastDc[component] = value;
                    values[position] = (short) diff;
                    dcCounts[component == 0 ? 0 : 1][32 - Integer.numberOfLeadingZeros(diff < 0 ? -diff : diff)]++;
                    position += 1 + Long.bitCount(masks[block++]);
                }
                mcu++;
            }
        }
    }

    /**
     * Codes the kept blocks of every band in order, inserting the restart markers.
     *
     * @param stores the kept blocks of every band, in band order, with DC differences
     * @param dc the DC tables by slot
     * @param ac the AC tables by slot
     */
    private void replay(BlockStore[] stores, HuffmanEncodingTable[] dc, HuffmanEncodingTable[] ac) {
        var componentOf = mcuComponents();
        var perMcu = componentOf.length;
        var mcu = 0L;
        for (var store : stores) {
            var values = store.values;
            var masks = store.masks;
            var position = 0;
            for (var block = 0; block < store.blocks; ) {
                if (restartInterval != 0 && mcu != 0 && mcu % restartInterval == 0) {
                    out.flushBits();
                    out.writeMarker(0xD0 + (int) ((mcu / restartInterval - 1) & 7));
                }
                for (var i = 0; i < perMcu; i++) {
                    var table = componentOf[i] == 0 ? 0 : 1;
                    position = out.writeStoredBlock(dc[table], ac[table], masks[block++], values, position);
                }
                mcu++;
            }
        }
    }

    /**
     * Codes the kept blocks of one band, which holds whole MCUs, into raw entropy-coded bits.
     *
     * @param store the kept blocks of the band, with DC differences
     * @param dc the DC tables by slot
     * @param ac the AC tables by slot
     * @return a writer without stuffing holding the band's bits
     */
    private JpegBitWriter code(BlockStore store, HuffmanEncodingTable[] dc, HuffmanEncodingTable[] ac) {
        var componentOf = mcuComponents();
        var perMcu = componentOf.length;
        var raw = new JpegBitWriter(Math.max(1024, store.count), false);
        var values = store.values;
        var masks = store.masks;
        var position = 0;
        for (var block = 0; block < store.blocks; block++) {
            var table = componentOf[block % perMcu] == 0 ? 0 : 1;
            position = raw.writeStoredBlock(dc[table], ac[table], masks[block], values, position);
        }
        return raw;
    }

    /**
     * Converts a natural-order nonzero mask to zigzag order.
     *
     * @param natural the natural-order mask
     * @return the zigzag-order mask
     */
    private static long zigzagMask(long natural) {
        var masks = ZIGZAG_MASKS;
        return masks[0][(int) natural & 0xFF]
                | masks[1][(int) (natural >>> 8) & 0xFF]
                | masks[2][(int) (natural >>> 16) & 0xFF]
                | masks[3][(int) (natural >>> 24) & 0xFF]
                | masks[4][(int) (natural >>> 32) & 0xFF]
                | masks[5][(int) (natural >>> 40) & 0xFF]
                | masks[6][(int) (natural >>> 48) & 0xFF]
                | masks[7][(int) (natural >>> 56) & 0xFF];
    }

    /**
     * Builds {@link #ZIGZAG_MASKS}.
     *
     * @return the tables, indexed by natural row and mask byte
     */
    private static long[][] zigzagMasks() {
        var inverse = new int[64];
        for (var k = 0; k < 64; k++) {
            inverse[JpegTables.ZIGZAG[k]] = k;
        }
        var tables = new long[8][256];
        for (var row = 0; row < 8; row++) {
            for (var bits = 0; bits < 256; bits++) {
                var mask = 0L;
                for (var column = 0; column < 8; column++) {
                    if ((bits & (1 << column)) != 0) {
                        mask |= 1L << inverse[row * 8 + column];
                    }
                }
                tables[row][bits] = mask;
            }
        }
        return tables;
    }

    /**
     * Downsamples one chroma row with libjpeg's box filter and alternating rounding bias.
     *
     * @param rows the two most recent full-resolution rows
     * @param slot the index in {@code rows} of the most recent row
     * @param band the destination band
     * @param offset the index of the destination row
     * @param count the number of output samples
     * @param factorX the horizontal factor, 1 or 2
     * @param factorY the vertical factor, 1 or 2
     */
    private static void downsample(byte[][] rows, int slot, byte[] band, int offset, int count, int factorX, int factorY) {
        var current = rows[slot];
        if (factorX == 1) {
            System.arraycopy(current, 0, band, offset, count);
            return;
        }
        if (factorY == 1) {
            var bias = 0;
            for (var i = 0; i < count; i++) {
                band[offset + i] = (byte) (((current[2 * i] & 0xFF) + (current[2 * i + 1] & 0xFF) + bias) >> 1);
                bias ^= 1;
            }
            return;
        }
        var above = rows[slot ^ 1];
        var bias = 1;
        for (var i = 0; i < count; i++) {
            band[offset + i] = (byte) (((above[2 * i] & 0xFF) + (above[2 * i + 1] & 0xFF)
                    + (current[2 * i] & 0xFF) + (current[2 * i + 1] & 0xFF) + bias) >> 2);
            bias ^= 3;
        }
    }

    /**
     * Writes every marker segment up to and including SOS.
     *
     * @param dc the DC tables by slot
     * @param ac the AC tables by slot
     */
    private void writeHeaders(HuffmanEncodingTable[] dc, HuffmanEncodingTable[] ac) {
        out.writeMarker(0xD8);
        out.writeMarker(0xE0);
        out.writeShort(2 + app0.length);
        for (var b : app0) {
            out.writeByte(b);
        }
        for (var segment : iccSegments) {
            out.writeBytes(segment);
        }
        for (var t = 0; t < divisors.length; t++) {
            out.writeDqt(t, divisors[t].table, false);
        }
        out.writeMarker(0xC0);
        out.writeShort(8 + 3 * components);
        out.writeByte(8);
        out.writeShort(height);
        out.writeShort(width);
        out.writeByte(components);
        for (var c = 0; c < components; c++) {
            out.writeByte(c + 1);
            out.writeByte((h[c] << 4) | v[c]);
            out.writeByte(c == 0 ? 0 : 1);
        }
        if (huffmanTables) {
            for (var t = 0; t < dc.length; t++) {
                out.writeDht(t, dc[t].spec);
                out.writeDht(0x10 | t, ac[t].spec);
            }
        }
        if (restartInterval != 0) {
            out.writeDri(restartInterval);
        }
        out.writeMarker(0xDA);
        out.writeShort(6 + 2 * components);
        out.writeByte(components);
        for (var c = 0; c < components; c++) {
            out.writeByte(c + 1);
            out.writeByte(c == 0 ? 0x00 : 0x11);
        }
        out.writeByte(0);
        out.writeByte(63);
        out.writeByte(0);
    }

    /**
     * Copies a plane region into a tightly packed array.
     *
     * @param plane the source samples
     * @param stride the plane stride
     * @param width the region width
     * @param height the region height
     * @return the packed samples
     */
    static byte[] tight(byte[] plane, int stride, int width, int height) {
        var result = new byte[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, result, y * width, width);
        }
        return result;
    }

    /**
     * Transforms the MCU rows of one band: owns the band's sample buffers and scratch rows, so
     * several bands can be transformed at once.
     */
    private final class BandEncoder {
        /**
         * The samples of each component for the current MCU row.
         */
        private final byte[][] bands;

        /**
         * The quantized coefficients of the current chunk of MCUs: for each component, one
         * interleaved {@link BatchFdctKernel} batch per block row of the MCU and group of eight
         * block columns; {@code null} when the blocks are transformed one at a time.
         */
        private final int[][][] coefficients;

        /**
         * The natural-order nonzero masks of the blocks of {@link #coefficients}, eight per batch,
         * or {@code null} when the blocks are transformed one at a time.
         */
        private final long[][][] masks;

        /**
         * The scratch array of {@link BatchFdctKernel#transform}, or {@code null} when the blocks
         * are transformed one at a time.
         */
        private final byte[] scratch;

        /**
         * The quantized coefficients of one block in natural order when the blocks are transformed
         * one at a time, or {@code null}.
         */
        private final int[] block;

        /**
         * The full-resolution row source of this band, or {@code null} when the planar source is
         * used.
         */
        private final YccRows source;

        /**
         * The scratch luma row of {@link #fillConvertedBands}.
         */
        private byte[] lumaRow;

        /**
         * The two most recent scratch Cb rows of {@link #fillConvertedBands}.
         */
        private byte[][] cbRows;

        /**
         * The two most recent scratch Cr rows of {@link #fillConvertedBands}.
         */
        private byte[][] crRows;

        /**
         * Allocates the buffers of one band.
         */
        BandEncoder() {
            this.bands = new byte[components][];
            for (var c = 0; c < components; c++) {
                bands[c] = new byte[bandRows[c] * bandStride[c]];
            }
            if (batched) {
                this.coefficients = new int[components][][];
                this.masks = new long[components][][];
                for (var c = 0; c < components; c++) {
                    var batches = components == 1 ? 1 : v[c] * h[c];
                    coefficients[c] = new int[batches][BatchFdctKernel.BATCH_LENGTH];
                    masks[c] = new long[batches][BatchFdctKernel.LANES];
                }
                this.scratch = new byte[BatchFdctKernel.SCRATCH_LENGTH];
                this.block = null;
            } else {
                this.coefficients = null;
                this.masks = null;
                this.scratch = null;
                this.block = new int[64];
            }
            this.source = rows == null ? null : rows.copy();
        }

        /**
         * Transforms and quantizes every block of the MCU rows {@code [first, last)}.
         *
         * <p>Each MCU row is processed in chunks of {@link BatchFdctKernel#LANES} MCUs: the blocks
         * of a chunk are transformed a batch at a time per component, then kept in MCU order.
         *
         * @param first the first MCU row
         * @param last the MCU row after the last one
         * @return the kept blocks, with absolute DC coefficients
         */
        BlockStore transform(int first, int last) {
            var store = new BlockStore((long) (last - first) * mcusWide * blocksPerMcu());
            for (var mcuY = first; mcuY < last; mcuY++) {
                fillBands(mcuY);
                if (!batched) {
                    transformBlocks(store, mcuY);
                    continue;
                }
                for (var chunk = 0; chunk < mcusWide; chunk += BatchFdctKernel.LANES) {
                    transformChunk(chunk);
                    keepChunk(store, mcuY, chunk, Math.min(BatchFdctKernel.LANES, mcusWide - chunk));
                }
            }
            return store;
        }

        /**
         * Transforms, quantizes and keeps the blocks of the current MCU row one at a time, in coding
         * order, the dummy blocks at the right and bottom edges as the previous block's DC
         * coefficient alone.
         *
         * @param store the destination
         * @param mcuY the MCU row
         */
        private void transformBlocks(BlockStore store, int mcuY) {
            var block = this.block;
            for (var mcuX = 0; mcuX < mcusWide; mcuX++) {
                for (var c = 0; c < components; c++) {
                    var table = c == 0 ? 0 : 1;
                    var rowsInMcu = components == 1 ? 1 : v[c];
                    var columnsInMcu = components == 1 ? 1 : h[c];
                    var band = bands[c];
                    var stride = bandStride[c];
                    var quantizer = divisors[table];
                    var previousDc = 0;
                    for (var by = 0; by < rowsInMcu; by++) {
                        for (var bx = 0; bx < columnsInMcu; bx++) {
                            var column = mcuX * columnsInMcu + bx;
                            var row = components == 1 ? mcuY : mcuY * v[c] + by;
                            if (column < blocksWide[c] && row < blocksHigh[c]) {
                                var natural = BLOCK_FDCT.transform(band, by * 8 * stride + column * 8, stride,
                                        quantizer, block);
                                previousDc = block[0];
                                store.block(table, zigzagMask(natural) & ~1L, block, 0, 1);
                            } else {
                                store.dcBlock(table, previousDc);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Transforms and quantizes the blocks of a chunk of MCUs of the current MCU row.
         *
         * @param chunk the first MCU column of the chunk, a multiple of
         * {@link BatchFdctKernel#LANES}
         */
        private void transformChunk(int chunk) {
            for (var c = 0; c < components; c++) {
                var rowsInMcu = components == 1 ? 1 : v[c];
                var columnsInMcu = components == 1 ? 1 : h[c];
                var band = bands[c];
                var stride = bandStride[c];
                var quantizer = divisors[c == 0 ? 0 : 1];
                for (var by = 0; by < rowsInMcu; by++) {
                    for (var group = 0; group < columnsInMcu; group++) {
                        var column = chunk * columnsInMcu + group * BatchFdctKernel.LANES;
                        if (column >= blocksWide[c]) {
                            continue;
                        }
                        var batch = by * columnsInMcu + group;
                        fdct.transform(band, by * 8 * stride + column * 8, stride, quantizer, coefficients[c][batch],
                                masks[c][batch], scratch);
                    }
                }
            }
        }

        /**
         * Keeps the blocks of a chunk of MCUs in coding order, the dummy blocks at the right and
         * bottom edges as the previous block's DC coefficient alone.
         *
         * @param store the destination
         * @param mcuY the MCU row
         * @param chunk the first MCU column of the chunk
         * @param count the number of MCUs of the chunk
         */
        private void keepChunk(BlockStore store, int mcuY, int chunk, int count) {
            for (var m = 0; m < count; m++) {
                var mcuX = chunk + m;
                for (var c = 0; c < components; c++) {
                    var table = c == 0 ? 0 : 1;
                    var rowsInMcu = components == 1 ? 1 : v[c];
                    var columnsInMcu = components == 1 ? 1 : h[c];
                    var previousDc = 0;
                    for (var by = 0; by < rowsInMcu; by++) {
                        for (var bx = 0; bx < columnsInMcu; bx++) {
                            var column = mcuX * columnsInMcu + bx;
                            var row = components == 1 ? mcuY : mcuY * v[c] + by;
                            if (column < blocksWide[c] && row < blocksHigh[c]) {
                                var local = m * columnsInMcu + bx;
                                var batch = by * columnsInMcu + (local >> 3);
                                var lane = local & (BatchFdctKernel.LANES - 1);
                                var batchCoefficients = coefficients[c][batch];
                                previousDc = batchCoefficients[lane];
                                store.block(table, zigzagMask(masks[c][batch][lane]) & ~1L, batchCoefficients, lane,
                                        BatchFdctKernel.LANES);
                            } else {
                                store.dcBlock(table, previousDc);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Fills the band of every component for one MCU row.
         *
         * @param mcuY the MCU row
         */
        private void fillBands(int mcuY) {
            if (planar != null) {
                fillPlanarBands(mcuY);
            } else {
                fillConvertedBands(mcuY);
            }
        }

        /**
         * Fills the bands from a planar picture already in the target subsampling.
         *
         * @param mcuY the MCU row
         */
        private void fillPlanarBands(int mcuY) {
            for (var c = 0; c < components; c++) {
                byte[] plane;
                int stride;
                if (c == 0) {
                    plane = planar.y();
                    stride = planar.yStride();
                } else {
                    plane = c == 1 ? planar.u() : planar.v();
                    stride = planar.uvStride();
                }
                var band = bands[c];
                var bandWidth = bandStride[c];
                var sampleWidth = componentWidth[c];
                for (var r = 0; r < bandRows[c]; r++) {
                    var row = Math.min(mcuY * bandRows[c] + r, componentHeight[c] - 1);
                    var target = r * bandWidth;
                    System.arraycopy(plane, row * stride, band, target, sampleWidth);
                    Arrays.fill(band, target + sampleWidth, target + bandWidth, band[target + sampleWidth - 1]);
                }
            }
        }

        /**
         * Fills the bands from full-resolution Y'CbCr rows, downsampling the chroma.
         *
         * @param mcuY the MCU row
         */
        private void fillConvertedBands(int mcuY) {
            var vMax = v[0];
            var groupRows = components == 1 ? 8 : vMax * 8;
            var first = mcuY * groupRows;
            var fullWidth = bandStride[0];
            if (components == 3) {
                fullWidth = Math.max(fullWidth, bandStride[1] * h[0]);
            }
            if (lumaRow == null || lumaRow.length < fullWidth) {
                lumaRow = new byte[fullWidth];
                cbRows = new byte[2][fullWidth];
                crRows = new byte[2][fullWidth];
            }
            var converted = -1;
            for (var r = 0; r < groupRows; r++) {
                var row = Math.min(first + r, height - 1);
                var slot = r & 1;
                if (components == 1) {
                    if (row != converted) {
                        source.fill(row, lumaRow, null, null);
                        Arrays.fill(lumaRow, width, fullWidth, lumaRow[width - 1]);
                        converted = row;
                    }
                } else {
                    if (row != converted) {
                        source.fill(row, lumaRow, cbRows[slot], crRows[slot]);
                        Arrays.fill(lumaRow, width, fullWidth, lumaRow[width - 1]);
                        Arrays.fill(cbRows[slot], width, fullWidth, cbRows[slot][width - 1]);
                        Arrays.fill(crRows[slot], width, fullWidth, crRows[slot][width - 1]);
                        converted = row;
                    } else {
                        System.arraycopy(cbRows[(r - 1) & 1], 0, cbRows[slot], 0, fullWidth);
                        System.arraycopy(crRows[(r - 1) & 1], 0, crRows[slot], 0, fullWidth);
                    }
                }
                System.arraycopy(lumaRow, 0, bands[0], r * bandStride[0], bandStride[0]);
                if (components == 3 && (vMax == 1 || slot == 1)) {
                    var chromaRow = vMax == 1 ? r : r >> 1;
                    downsample(cbRows, slot, bands[1], chromaRow * bandStride[1], bandStride[1], h[0], vMax);
                    downsample(crRows, slot, bands[2], chromaRow * bandStride[2], bandStride[2], h[0], vMax);
                }
            }
            if (components == 3) {
                for (var c = 1; c < 3; c++) {
                    var firstChroma = mcuY * bandRows[c];
                    var lastReal = componentHeight[c] - 1 - firstChroma;
                    for (var r = Math.max(lastReal + 1, 0); r < bandRows[c]; r++) {
                        if (lastReal >= 0) {
                            System.arraycopy(bands[c], lastReal * bandStride[c], bands[c], r * bandStride[c], bandStride[c]);
                        }
                    }
                }
            }
        }
    }

    /**
     * Keeps the transformed blocks of a band and counts their AC Huffman symbols.
     *
     * <p>Each block is kept as its zigzag nonzero mask plus its DC coefficient and nonzero AC
     * values, in coding order; {@link #resolveDc} later replaces the DC coefficients by the
     * differences the entropy coder needs.
     */
    private static final class BlockStore {
        /**
         * The AC symbol counts of each table slot.
         */
        final long[][] acCounts = {new long[256], new long[256]};

        /**
         * The nonzero AC mask of each kept block.
         */
        private long[] masks;

        /**
         * The DC coefficient (or difference) and the nonzero AC values of each kept block, in
         * coding order.
         */
        private short[] values;

        /**
         * The number of kept blocks.
         */
        private int blocks;

        /**
         * The number of kept values.
         */
        private int count;

        /**
         * Constructs a store sized for a band.
         *
         * @param expectedBlocks the number of blocks the band has
         */
        BlockStore(long expectedBlocks) {
            if (expectedBlocks > Integer.MAX_VALUE - 16) {
                throw new IllegalArgumentException("picture has too many blocks for optimized coding");
            }
            masks = new long[(int) expectedBlocks];
            values = new short[(int) Math.min(Integer.MAX_VALUE - 16, expectedBlocks * 16)];
        }

        /**
         * Keeps one block and counts its AC symbols.
         *
         * @param table the table slot, 0 for luminance and 1 for chrominance
         * @param mask the zigzag-order mask of the nonzero AC coefficients (bit 0 clear)
         * @param coefficients the quantized coefficients, in natural order
         * @param first the index in {@code coefficients} of the block's DC coefficient
         * @param step the distance in {@code coefficients} between consecutive coefficients of the
         * block: {@link BatchFdctKernel#LANES} in an interleaved batch, 1 for a block of its own
         */
        void block(int table, long mask, int[] coefficients, int first, int step) {
            reserve(1 + Long.bitCount(mask));
            masks[blocks++] = mask;
            values[count++] = (short) coefficients[first];
            var counts = acCounts[table];
            var previous = 0;
            while (mask != 0) {
                var k = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                var run = k - previous - 1;
                previous = k;
                while (run > 15) {
                    counts[0xF0]++;
                    run -= 16;
                }
                var coefficient = coefficients[first + JpegTables.ZIGZAG[k] * step];
                values[count++] = (short) coefficient;
                counts[(run << 4) | (32 - Integer.numberOfLeadingZeros(coefficient < 0 ? -coefficient : coefficient))]++;
            }
            if (previous != 63) {
                counts[0]++;
            }
        }

        /**
         * Keeps a block with no AC coefficients, as the dummy blocks at the picture's edges are
         * coded.
         *
         * @param table the table slot, 0 for luminance and 1 for chrominance
         * @param dc the DC coefficient
         */
        void dcBlock(int table, int dc) {
            reserve(1);
            masks[blocks++] = 0;
            values[count++] = (short) dc;
            acCounts[table][0]++;
        }

        /**
         * Grows the arrays so that one more block keeping {@code kept} values fits.
         *
         * @param kept the number of values the block keeps
         */
        private void reserve(int kept) {
            var needed = (long) count + kept;
            if (needed > values.length) {
                var grown = Math.max(needed, Math.min((long) values.length * 2, Integer.MAX_VALUE - 16));
                values = Arrays.copyOf(values, (int) grown);
            }
            if (blocks == masks.length) {
                masks = Arrays.copyOf(masks, blocks * 2 + 16);
            }
        }
    }
}
