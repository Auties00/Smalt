package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.intra.internal.IntraBands;
import com.github.auties00.smalt.video.intra.internal.IntraInput;
import com.github.auties00.smalt.video.intra.internal.IntraPlanes;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;

import java.util.Arrays;

/**
 * Encodes pictures into ProRes frames (SMPTE RDD 36): prepares the planes, encodes the slices of
 * every picture, in parallel bands of slice rows when the frame is large enough, and assembles the
 * frame header, the picture headers, the slice tables and the slices.
 *
 * <p>Instances are not thread-safe.
 */
public final class ProResFrameEncoder {
    /**
     * The encoder identifier the frame headers carry.
     */
    private static final int ENCODER_IDENTIFIER = 0x736D6C74;

    /**
     * The size of a picture header.
     */
    private static final int PICTURE_HEADER_SIZE = 8;

    /**
     * The quantization index a slice starts searching from in the first frame.
     */
    private static final int FIRST_INDEX = 8;

    /**
     * The frame rates of the frame rate codes 1 to 11 (RDD 36 Table 4).
     */
    private static final Rational[] FRAME_RATES = {
            new Rational(24000, 1001), new Rational(24, 1), new Rational(25, 1), new Rational(30000, 1001),
            new Rational(30, 1), new Rational(50, 1), new Rational(60000, 1001), new Rational(60, 1),
            new Rational(100, 1), new Rational(120000, 1001), new Rational(120, 1)
    };

    /**
     * The frame width.
     */
    private final int width;

    /**
     * The frame height.
     */
    private final int height;

    /**
     * The encoding options.
     */
    private final ProResEncoderOptions options;

    /**
     * The frame width in macroblocks.
     */
    private final int widthInMb;

    /**
     * The number of macroblocks of every slice of a row.
     */
    private final int[] sliceMbs;

    /**
     * The number of pictures of a frame: 1, or 2 for interlaced frames.
     */
    private final int pictureCount;

    /**
     * The height of every picture in luma rows.
     */
    private final int[] pictureHeights;

    /**
     * The bytes a frame's slice headers and color components should take.
     */
    private final long frameBudget;

    /**
     * The quantization index every slice used in the previous frame, by picture and slice.
     */
    private final int[][] lastIndices;

    /**
     * The planes of the previous frame, whose arrays the next frame reuses.
     */
    private IntraPlanes recycled;

    /**
     * The slice encoder of every parallel band, created on first use.
     */
    private ProResSliceEncoder[] sliceEncoders = new ProResSliceEncoder[1];

    /**
     * Constructs a frame encoder.
     *
     * @param width the frame width, positive
     * @param height the frame height, positive
     * @param options the encoding options
     */
    public ProResFrameEncoder(int width, int height, ProResEncoderOptions options) {
        this.width = width;
        this.height = height;
        this.options = options;
        this.widthInMb = (width + 15) >>> 4;
        this.pictureCount = options.fieldOrder().isInterlaced() ? 2 : 1;
        this.pictureHeights = new int[pictureCount];
        var topFirst = options.fieldOrder() != FieldOrder.BOTTOM_FIRST;
        for (var p = 0; p < pictureCount; p++) {
            pictureHeights[p] = pictureCount == 1 ? height : (topFirst == (p == 0)) ? (height + 1) / 2 : height / 2;
        }
        var log2 = Integer.numberOfTrailingZeros(options.sliceSize());
        var counts = new int[4];
        var remaining = widthInMb;
        var slices = 0;
        for (var size = log2; size >= 0; size--) {
            counts[size] = remaining >>> size;
            remaining -= counts[size] << size;
            slices += counts[size];
        }
        this.sliceMbs = new int[slices];
        var j = 0;
        for (var size = 3; size >= 0; size--) {
            for (var k = 0; k < counts[size]; k++) {
                sliceMbs[j++] = 1 << size;
            }
        }
        var macroblocks = 0L;
        this.lastIndices = new int[pictureCount][];
        for (var p = 0; p < pictureCount; p++) {
            var heightInMb = (pictureHeights[p] + 15) >>> 4;
            macroblocks += (long) heightInMb * widthInMb;
            lastIndices[p] = new int[heightInMb * slices];
            Arrays.fill(lastIndices[p], FIRST_INDEX);
        }
        var frameRate = options.frameRate().toDouble();
        var bitrate = options.bitrate().isPresent() ? options.bitrate().getAsLong()
                : (long) (options.profile().bitsPerMacroblock() * macroblocks * frameRate);
        this.frameBudget = Math.max(macroblocks * 8, (long) (bitrate / frameRate / 8));
    }

    /**
     * Encodes one picture into a frame.
     *
     * @param picture the picture, of the encoder's size
     * @return the frame
     */
    public byte[] encode(Picture picture) {
        var profile = options.profile();
        var chroma444 = profile.supportsAlpha();
        var alphaType = chroma444 && options.alpha() && picture.hasAlpha() ? (options.alphaBits() == 16 ? 2 : 1) : 0;
        var depth = profile.bitDepth();
        var tallest = (pictureHeights[0] + 15) >>> 4;
        var paddedHeight = pictureCount == 1 ? tallest * 16 : tallest * 32;
        var paddedWidth = widthInMb * 16;
        LimitChecks.checkAllocation(options.limits(), (long) paddedWidth * paddedHeight * 2 * (alphaType != 0 ? 4 : 3));
        var deferred = pictureCount == 1 && alphaType == 0
                ? IntraInput.yuvDeferred(picture, profile.chromaFormat(), depth, paddedWidth, paddedHeight, recycled)
                : null;
        var planes = deferred != null ? deferred.planes()
                : IntraInput.yuv(picture, profile.chromaFormat(), depth, options.color(),
                alphaType == 0 ? 0 : options.alphaBits(), paddedWidth, paddedHeight, options.threads(), recycled);
        recycled = planes;
        var contexts = new ProResPictureContext[pictureCount];
        var topFirst = options.fieldOrder() != FieldOrder.BOTTOM_FIRST;
        var scan = pictureCount == 1 ? ProResTables.PROGRESSIVE_SCAN : ProResTables.INTERLACED_SCAN;
        var luma = profile.lumaMatrix();
        var chroma = profile.chromaMatrix();
        var totalRows = 0;
        var heights = new int[pictureCount];
        for (var p = 0; p < pictureCount; p++) {
            var top = pictureCount == 1 || topFirst == (p == 0);
            contexts[p] = new ProResPictureContext(chroma444, alphaType, pictureHeights[p], top ? 0 : 1,
                    pictureCount == 1 ? 1 : 2, scan, luma, chroma);
            heights[p] = (pictureHeights[p] + 15) >>> 4;
            totalRows += heights[p];
        }
        var macroblocks = (long) totalRows * widthInMb;
        var perRow = sliceMbs.length;
        var slices = new byte[pictureCount][][];
        for (var p = 0; p < pictureCount; p++) {
            slices[p] = new byte[heights[p] * perRow][];
        }
        var fixed = options.quantizationIndex().orElse(0);
        var threads = options.threads();
        var bands = IntraBands.count(threads, totalRows, (long) width * height);
        if (sliceEncoders.length < bands) {
            sliceEncoders = Arrays.copyOf(sliceEncoders, bands);
        }
        var encoders = sliceEncoders;
        var units = totalRows;
        ParallelBands.run(bands, band -> {
            var encoder = encoders[band];
            if (encoder == null) {
                encoder = new ProResSliceEncoder();
                encoders[band] = encoder;
            }
            for (var unit = ParallelBands.start(band, bands, units); unit < ParallelBands.start(band + 1, bands, units); unit++) {
                var p = 0;
                var row = unit;
                while (row >= heights[p]) {
                    row -= heights[p];
                    p++;
                }
                if (deferred != null) {
                    deferred.fill(row);
                }
                var mbX = 0;
                for (var j = 0; j < perRow; j++) {
                    var mbs = sliceMbs[j];
                    var budget = (int) Math.min(Integer.MAX_VALUE, frameBudget * mbs / macroblocks);
                    var index = row * perRow + j;
                    var slice = encoder.encode(planes, contexts[p], row, mbX, mbs, budget, lastIndices[p][index], fixed);
                    slices[p][index] = slice.data();
                    lastIndices[p][index] = slice.index();
                    mbX += mbs;
                }
                ParallelBands.checkpoint();
            }
        });
        return assemble(slices, planes.color(), alphaType, chroma444, luma, chroma, topFirst);
    }

    /**
     * Assembles a frame from its coded slices.
     *
     * @param slices the coded slices, by picture, row by row
     * @param color the color description of the samples
     * @param alphaType the alpha channel type: 0 for none, 1 for 8 bits, 2 for 16 bits
     * @param chroma444 whether the frame is 4:4:4
     * @param luma the luma quantization matrix
     * @param chroma the chroma quantization matrix
     * @param topFirst whether the top field comes first
     * @return the frame
     */
    private byte[] assemble(byte[][][] slices, ColorInfo color, int alphaType, boolean chroma444,
                            int[] luma, int[] chroma, boolean topFirst) {
        var frameHeaderSize = ProResFrameHeader.BASE_HEADER_SIZE + 128;
        var pictureSizes = new int[pictureCount];
        long total = 8 + frameHeaderSize;
        for (var p = 0; p < pictureCount; p++) {
            long size = PICTURE_HEADER_SIZE + 2L * slices[p].length;
            for (var slice : slices[p]) {
                if (slice.length > 0xFFFF) {
                    throw new IllegalStateException("ProRes slice of " + slice.length + " bytes");
                }
                size += slice.length;
            }
            pictureSizes[p] = Math.toIntExact(size);
            total += size;
        }
        var out = new byte[Math.toIntExact(total)];
        var interlaceMode = pictureCount == 1 ? 0 : topFirst ? 1 : 2;
        var header = new ProResFrameHeader((int) total, frameHeaderSize, chroma444 || alphaType != 0 ? 1 : 0,
                ENCODER_IDENTIFIER, width, height, chroma444 ? 3 : 2, interlaceMode, 0, frameRateCode(),
                code(color.primaries().code()), code(color.transfer().code()), code(color.matrix().code()), alphaType,
                luma, chroma);
        var at = header.write(out, 0);
        var log2 = Integer.numberOfTrailingZeros(options.sliceSize());
        for (var p = 0; p < pictureCount; p++) {
            var count = slices[p].length;
            out[at] = (byte) (PICTURE_HEADER_SIZE << 3);
            ByteArrays.put32be(out, at + 1, pictureSizes[p]);
            ByteArrays.put16be(out, at + 5, count <= 0xFFFF ? count : 0);
            out[at + 7] = (byte) (log2 << 4);
            var table = at + PICTURE_HEADER_SIZE;
            var data = table + 2 * count;
            for (var i = 0; i < count; i++) {
                var slice = slices[p][i];
                ByteArrays.put16be(out, table + 2 * i, slice.length);
                System.arraycopy(slice, 0, out, data, slice.length);
                data += slice.length;
            }
            at = data;
        }
        return out;
    }

    /**
     * Returns the frame rate code of the configured frame rate.
     *
     * @return the code of RDD 36 Table 4, or {@code 0} for a rate without one
     */
    private int frameRateCode() {
        var rate = options.frameRate();
        for (var i = 0; i < FRAME_RATES.length; i++) {
            if ((long) rate.numerator() * FRAME_RATES[i].denominator()
                    == (long) FRAME_RATES[i].numerator() * rate.denominator()) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Returns the frame header code of an H.273 code point; the codes ProRes defines agree with
     * H.273.
     *
     * @param code the H.273 code point
     * @return the code, or {@code 2} (unspecified) for a value that does not fit a byte
     */
    private static int code(int code) {
        return code < 0 || code > 255 ? 2 : code;
    }
}
