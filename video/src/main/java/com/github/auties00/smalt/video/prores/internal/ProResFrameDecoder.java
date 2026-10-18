package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.intra.internal.IntraBands;
import com.github.auties00.smalt.video.intra.internal.IntraBitReader;
import com.github.auties00.smalt.video.intra.internal.IntraFramePipeline;

/**
 * Decodes ProRes frames (SMPTE RDD 36) into pictures: parses the frame and picture headers and the
 * slice tables, then decodes the slices of every picture, in parallel bands of slice rows when the
 * frame is large enough, with one {@link ProResSliceDecoder} per band.
 *
 * <p>Pictures of 4:2:2 frames come out at 10 bits and pictures of 4:4:4 frames at 12 bits, the
 * depths Apple's decoders deliver, or at 8 bits when high bit depth is off; the samples are
 * computed directly at the output depth from the reconstructed values, as RDD 36 clause 7.5.1
 * specifies, not reduced from a deeper result. An interlaced frame's two field pictures are woven
 * into one frame. The output planes are recycled from frame to frame while the frame layout stays
 * the same. Frames may come with their frame size and {@code icpf} identifier, as QuickTime stores
 * them, or without, as Matroska stores them.
 *
 * <p>Instances are not thread-safe.
 */
public final class ProResFrameDecoder implements IntraFramePipeline.FrameDecoder {
    /**
     * The resource bounds every frame is checked against.
     */
    private final MediaLimits limits;

    /**
     * The thread count: {@code 1} for the calling thread only, otherwise the shared pool.
     */
    private final int threads;

    /**
     * Whether the pictures keep 10 or 12 bits rather than 8.
     */
    private final boolean highBitDepth;

    /**
     * The slice decoder of every parallel band, created on first use.
     */
    private ProResSliceDecoder[] sliceDecoders = new ProResSliceDecoder[1];

    /**
     * The output planes of the previous frame, recycled while the layout stays the same, or
     * {@code null}.
     */
    private Planes planes;

    /**
     * Constructs a frame decoder.
     *
     * @param limits the resource bounds
     * @param threads {@code 1} for the calling thread only, otherwise the shared pool with at
     * most this many threads ({@code 0} for no cap)
     * @param highBitDepth whether the pictures keep their 10 or 12 bits
     */
    public ProResFrameDecoder(MediaLimits limits, int threads, boolean highBitDepth) {
        this.limits = limits;
        this.threads = threads;
        this.highBitDepth = highBitDepth;
    }

    /**
     * Decodes one frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame and anything that follows it
     * @return the picture, whose planes the next call may overwrite
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if the frame uses a newer bitstream version
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public Picture decode(byte[] data, int offset, int length) {
        if (data.length >= IntraBitReader.MAX_BYTES) {
            throw new MediaException.LimitExceeded("ProRes frame of " + data.length + " bytes is too large");
        }
        if (length >= 8 && (data[offset + 4] != 'i' || data[offset + 5] != 'c' || data[offset + 6] != 'p'
                || data[offset + 7] != 'f')) {
            var framed = new byte[length + 8];
            ByteArrays.put32be(framed, 0, framed.length);
            ByteArrays.put32be(framed, 4, ProResFrameHeader.IDENTIFIER);
            System.arraycopy(data, offset, framed, 8, length);
            return decodeFramed(framed, 0, framed.length);
        }
        return decodeFramed(data, offset, length);
    }

    /**
     * Decodes one frame that starts with its frame size and {@code icpf} identifier.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's size field
     * @param length the number of bytes of the frame and anything that follows it
     * @return the picture
     */
    private Picture decodeFramed(byte[] data, int offset, int length) {
        var header = ProResFrameHeader.parse(data, offset, length);
        var width = header.width();
        var height = header.height();
        LimitChecks.checkPicture(limits, width, height);
        var widthInMb = (width + 15) >>> 4;
        var pictureCount = header.pictureCount();
        var chroma444 = header.chromaFormat() == 3;
        var depth = highBitDepth ? (chroma444 ? 12 : 10) : 8;
        var tallest = (header.pictureHeight(0) + 15) >>> 4;
        var rows = tallest * 16 * pictureCount;
        var target = planes(widthInMb, rows, chroma444, header.alphaType() != 0, depth);
        var frameEnd = offset + header.frameSize();
        var pictureStart = offset + 8 + header.headerSize();
        var layouts = new PictureLayout[pictureCount];
        var totalRows = 0;
        for (var p = 0; p < pictureCount; p++) {
            var layout = PictureLayout.parse(data, pictureStart, frameEnd, widthInMb, header.pictureHeight(p));
            layouts[p] = layout;
            totalRows += layout.heightInMb;
            pictureStart += layout.pictureSize;
        }
        var targets = new ProResTarget[pictureCount];
        for (var p = 0; p < pictureCount; p++) {
            var rowOffset = header.isTopField(p) ? 0 : 1;
            var rowStep = pictureCount == 1 ? 1 : 2;
            targets[p] = target.target(rowOffset, rowStep, header.pictureHeight(p), depth);
        }
        var scan = pictureCount == 1 ? ProResTables.PROGRESSIVE_SCAN : ProResTables.INTERLACED_SCAN;
        var bands = IntraBands.count(threads, totalRows, (long) width * height);
        if (sliceDecoders.length < bands) {
            sliceDecoders = java.util.Arrays.copyOf(sliceDecoders, bands);
        }
        var units = totalRows;
        var decoders = sliceDecoders;
        ParallelBands.run(bands, band -> {
            var decoder = decoders[band];
            if (decoder == null) {
                decoder = new ProResSliceDecoder();
                decoders[band] = decoder;
            }
            var first = ParallelBands.start(band, bands, units);
            var last = ParallelBands.start(band + 1, bands, units);
            for (var unit = first; unit < last; unit++) {
                var p = 0;
                var row = unit;
                while (row >= layouts[p].heightInMb) {
                    row -= layouts[p].heightInMb;
                    p++;
                }
                layouts[p].decodeRow(data, header, scan, targets[p], decoder, row);
            }
        });
        return target.picture(width, height, chroma444, depth, color(header));
    }

    /**
     * Returns the output planes for a frame layout, recycling those of the previous frame when the
     * layout is the same.
     *
     * @param widthInMb the frame width in macroblocks
     * @param rows the number of plane rows, a multiple of 16 covering every picture
     * @param chroma444 whether the chroma planes have full width
     * @param alpha whether an alpha plane is needed
     * @param depth the output bit depth
     * @return the planes
     */
    private Planes planes(int widthInMb, int rows, boolean chroma444, boolean alpha, int depth) {
        var current = planes;
        if (current == null || current.widthInMb != widthInMb || current.rows != rows
                || current.chroma444 != chroma444 || current.alpha != alpha || current.wide != (depth > 8)) {
            current = new Planes(widthInMb, rows, chroma444, alpha, depth > 8, limits);
            planes = current;
        }
        return current;
    }

    /**
     * Returns the color description a frame header declares.
     *
     * @param header the frame header
     * @return the description, in limited range
     */
    private static ColorInfo color(ProResFrameHeader header) {
        return new ColorInfo(MatrixCoefficients.of(header.matrix() == 0 ? 2 : header.matrix()), ColorRange.LIMITED,
                ColorPrimaries.of(header.primaries() == 0 ? 2 : header.primaries()),
                TransferCharacteristics.of(header.transfer() == 0 ? 2 : header.transfer()));
    }

    /**
     * Holds the output planes of a frame, sized to whole macroblocks.
     */
    private static final class Planes {
        /**
         * The frame width in macroblocks.
         */
        private final int widthInMb;

        /**
         * The number of plane rows.
         */
        private final int rows;

        /**
         * Whether the chroma planes have full width.
         */
        private final boolean chroma444;

        /**
         * Whether there is an alpha plane.
         */
        private final boolean alpha;

        /**
         * Whether the planes hold 16-bit samples.
         */
        private final boolean wide;

        /**
         * The 16-bit planes (luma, Cb, Cr, alpha), or {@code null}.
         */
        private final short[] y16, u16, v16, a16;

        /**
         * The 8-bit planes (luma, Cb, Cr, alpha), or {@code null}.
         */
        private final byte[] y8, u8, v8, a8;

        /**
         * Allocates the planes.
         *
         * @param widthInMb the frame width in macroblocks
         * @param rows the number of plane rows
         * @param chroma444 whether the chroma planes have full width
         * @param alpha whether there is an alpha plane
         * @param wide whether the planes hold 16-bit samples
         * @param limits the bounds the allocation is checked against
         */
        private Planes(int widthInMb, int rows, boolean chroma444, boolean alpha, boolean wide, MediaLimits limits) {
            this.widthInMb = widthInMb;
            this.rows = rows;
            this.chroma444 = chroma444;
            this.alpha = alpha;
            this.wide = wide;
            var lumaSize = (long) widthInMb * 16 * rows;
            var chromaSize = chroma444 ? lumaSize : lumaSize / 2;
            var planes = (lumaSize + 2 * chromaSize + (alpha ? lumaSize : 0)) * (wide ? 2 : 1);
            LimitChecks.checkAllocation(limits, planes);
            if (lumaSize > Integer.MAX_VALUE - 16) {
                throw new MediaException.LimitExceeded("ProRes frame is too large");
            }
            if (wide) {
                y16 = new short[(int) lumaSize];
                u16 = new short[(int) chromaSize];
                v16 = new short[(int) chromaSize];
                a16 = alpha ? new short[(int) lumaSize] : null;
                y8 = u8 = v8 = a8 = null;
            } else {
                y8 = new byte[(int) lumaSize];
                u8 = new byte[(int) chromaSize];
                v8 = new byte[(int) chromaSize];
                a8 = alpha ? new byte[(int) lumaSize] : null;
                y16 = u16 = v16 = a16 = null;
            }
        }

        /**
         * Returns the target of one picture of the frame.
         *
         * @param rowOffset the frame row of the picture's first row
         * @param rowStep the distance in frame rows between picture rows
         * @param pictureHeight the picture height in luma rows
         * @param depth the output bit depth
         * @return the target
         */
        private ProResTarget target(int rowOffset, int rowStep, int pictureHeight, int depth) {
            var scale = (float) (1 << depth) / 512f;
            var offset = 256f * scale + 0.5f;
            var limit = (1 << depth) - 0.5f;
            var stride = widthInMb * 16;
            return new ProResTarget(wide, y16, u16, v16, a16, y8, u8, v8, a8, stride,
                    chroma444 ? stride : stride / 2, rowOffset, rowStep, pictureHeight, scale, offset, limit, depth);
        }

        /**
         * Wraps the planes into the frame's picture.
         *
         * @param width the frame width
         * @param height the frame height
         * @param chroma444 whether the frame is 4:4:4
         * @param depth the output bit depth
         * @param color the color description
         * @return the picture
         */
        private Picture picture(int width, int height, boolean chroma444, int depth, ColorInfo color) {
            var format = chroma444 ? ChromaFormat.YUV444 : ChromaFormat.YUV422;
            var stride = widthInMb * 16;
            var chromaStride = chroma444 ? stride : stride / 2;
            if (wide) {
                return Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(depth).color(color)
                        .luma(y16, stride).chroma(u16, v16, chromaStride).alpha(a16, stride).build();
            }
            return YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                    .luma(y8, stride).chroma(u8, v8, chromaStride).alpha(a8, stride).build();
        }
    }

    /**
     * Describes the slices of one picture: its macroblock grid, the slice layout of every
     * macroblock row and where every slice's data lies.
     */
    private static final class PictureLayout {
        /**
         * The picture height in macroblocks.
         */
        private final int heightInMb;

        /**
         * The size of the picture in bytes, including its header.
         */
        private final int pictureSize;

        /**
         * The number of macroblocks of every slice of a row, left to right.
         */
        private final int[] sliceMbs;

        /**
         * The index of every slice's first byte, row by row.
         */
        private final int[] sliceStarts;

        /**
         * The coded size of every slice, row by row.
         */
        private final int[] sliceSizes;

        /**
         * Constructs a layout.
         *
         * @param heightInMb the picture height in macroblocks
         * @param pictureSize the size of the picture in bytes
         * @param sliceMbs the macroblocks of every slice of a row
         * @param sliceStarts the index of every slice's first byte
         * @param sliceSizes the coded size of every slice
         */
        private PictureLayout(int heightInMb, int pictureSize, int[] sliceMbs, int[] sliceStarts, int[] sliceSizes) {
            this.heightInMb = heightInMb;
            this.pictureSize = pictureSize;
            this.sliceMbs = sliceMbs;
            this.sliceStarts = sliceStarts;
            this.sliceSizes = sliceSizes;
        }

        /**
         * Parses the picture header and slice table of a picture (RDD 36 clauses 5.2 and 6.2).
         *
         * @param data the frame
         * @param start the index of the picture's first byte
         * @param frameEnd the index one past the frame
         * @param widthInMb the frame width in macroblocks
         * @param pictureHeight the picture height in luma rows
         * @return the layout
         * @throws MediaException.Malformed if the header or slice table is invalid or overruns the
         * frame
         */
        private static PictureLayout parse(byte[] data, int start, int frameEnd, int widthInMb, int pictureHeight) {
            if (start + 8 > frameEnd) {
                throw new MediaException.Malformed("ProRes picture header overruns the frame");
            }
            var headerSize = (data[start] & 0xFF) >>> 3;
            var pictureSize = ByteArrays.i32be(data, start + 1);
            var log2SliceSize = (data[start + 7] >>> 4) & 3;
            if (headerSize < 8 || pictureSize < headerSize || pictureSize > frameEnd - start) {
                throw new MediaException.Malformed("invalid ProRes picture header");
            }
            var heightInMb = (pictureHeight + 15) >>> 4;
            var counts = new int[4];
            var slicesPerRow = 0;
            var remaining = widthInMb;
            for (var size = 3; size >= 0; size--) {
                if (size > log2SliceSize) {
                    continue;
                }
                counts[size] = remaining >>> size;
                remaining -= counts[size] << size;
                slicesPerRow += counts[size];
            }
            var sliceMbs = new int[slicesPerRow];
            var j = 0;
            for (var size = 3; size >= 0; size--) {
                for (var k = 0; k < counts[size]; k++) {
                    sliceMbs[j++] = 1 << size;
                }
            }
            var slices = (long) slicesPerRow * heightInMb;
            var pictureEnd = start + pictureSize;
            var tableStart = start + headerSize;
            if (tableStart + 2 * slices > pictureEnd) {
                throw new MediaException.Malformed("ProRes slice table overruns the picture");
            }
            var count = (int) slices;
            var starts = new int[count];
            var sizes = new int[count];
            var cursor = tableStart + 2 * count;
            for (var i = 0; i < count; i++) {
                var size = ByteArrays.u16be(data, tableStart + 2 * i);
                if (cursor + size > pictureEnd) {
                    throw new MediaException.Malformed("ProRes slice " + i + " overruns the picture");
                }
                starts[i] = cursor;
                sizes[i] = size;
                cursor += size;
            }
            return new PictureLayout(heightInMb, pictureSize, sliceMbs, starts, sizes);
        }

        /**
         * Decodes the slices of one macroblock row.
         *
         * @param data the frame
         * @param header the frame header
         * @param scan the raster position of every scan index
         * @param target where the samples go
         * @param decoder the slice decoder of the calling band
         * @param row the macroblock row
         */
        private void decodeRow(byte[] data, ProResFrameHeader header, int[] scan, ProResTarget target,
                               ProResSliceDecoder decoder, int row) {
            var perRow = sliceMbs.length;
            var mbX = 0;
            for (var j = 0; j < perRow; j++) {
                var index = row * perRow + j;
                decoder.decode(data, sliceStarts[index], sliceSizes[index], header, scan, target, row, mbX, sliceMbs[j]);
                mbX += sliceMbs[j];
            }
            ParallelBands.checkpoint();
        }
    }
}
