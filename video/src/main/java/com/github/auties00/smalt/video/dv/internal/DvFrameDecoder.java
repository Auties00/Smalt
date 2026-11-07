package com.github.auties00.smalt.video.dv.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.intra.internal.IntraBands;
import com.github.auties00.smalt.video.intra.internal.IntraFramePipeline;

/**
 * Decodes one DV frame, the DIF blocks of one video frame, into a picture.
 *
 * <p>The profile is read from the frame's header block and VAUX source pack. 4:1:1 frames decode to 4:2:2
 * pictures, each chroma sample repeated in two columns; 4:2:0 frames to 4:2:0 pictures and the others to 4:2:2,
 * BT.601 limited range for the SD profiles and BT.709 for DVCPRO HD. The video segments of a frame are decoded in
 * parallel bands unless the decoder runs on one thread; the picture does not depend on the threading.
 *
 * <p>Instances are not thread-safe.
 */
public final class DvFrameDecoder implements IntraFramePipeline.FrameDecoder {
    private final MediaLimits limits;
    private final int threads;
    private DifProfile profile;
    private float[] dequant;
    private Worker[] workers = new Worker[0];
    private final YuvPicture[] pictures = new YuvPicture[2];

    /**
     * Constructs a frame decoder.
     *
     * @param limits the resource bounds the pictures must fit
     * @param threads the thread count: {@code 1} for the calling thread, otherwise the shared pool with at most
     * this many threads ({@code 0} for no cap)
     */
    public DvFrameDecoder(MediaLimits limits, int threads) {
        this.limits = limits;
        this.threads = threads;
    }

    /**
     * {@return the profile of the last frame decoded, or {@code null} before the first}
     */
    public DifProfile profile() {
        return profile;
    }

    @Override
    public YuvPicture decode(byte[] data, int offset, int length) {
        return decode(data, offset, length, 0);
    }

    /**
     * Decodes a frame into one of the two pictures this decoder keeps, so that the two video frames of one packet
     * can be held at once.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes available from {@code offset}
     * @param slot the picture to decode into, 0 or 1; the next decode into the same slot overwrites it
     * @return the picture
     */
    public YuvPicture decode(byte[] data, int offset, int length, int slot) {
        var detected = DifProfile.detect(data, offset, length);
        if (detected == null) {
            throw new MediaException.Malformed("not a DV frame");
        }
        if (length < detected.frameSize()) {
            throw new MediaException.Malformed("truncated DV frame: " + length + " of " + detected.frameSize()
                    + " bytes");
        }
        if (detected != profile) {
            profile = detected;
            dequant = dequantTable(detected);
            workers = new Worker[0];
            pictures[0] = null;
            pictures[1] = null;
        }
        var picture = pictures[slot];
        if (picture == null) {
            LimitChecks.checkPicture(limits, detected.width(), detected.height());
            LimitChecks.checkAllocation(limits, 2L * detected.width() * detected.height());
            var format = detected.sampling() == DifProfile.Sampling.S420 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
            var color = detected.highDefinition() ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
            picture = YuvPicture.allocate(detected.width(), detected.height(), format, false, color);
            pictures[slot] = picture;
        }
        var secondHalf = detected.progressive() && (data[offset + 1] & 0x04) == 0;
        var layout = DvLayout.of(detected, secondHalf);
        var segments = layout.segments();
        var bands = IntraBands.count(threads, segments, (long) detected.width() * detected.height());
        if (workers.length < bands) {
            var grown = new Worker[bands];
            System.arraycopy(workers, 0, grown, 0, workers.length);
            for (var i = workers.length; i < bands; i++) {
                grown[i] = new Worker(detected);
            }
            workers = grown;
        }
        var target = new Planes(picture.y(), picture.u(), picture.v(), picture.yStride(), picture.uvStride());
        var lookup = layout;
        var table = dequant;
        var source = detected;
        ParallelBands.run(bands, band -> {
            var worker = workers[band];
            var first = ParallelBands.start(band, bands, segments);
            var last = ParallelBands.start(band + 1, bands, segments);
            for (var segment = first; segment < last; segment++) {
                worker.segment(data, offset, lookup, source, table, segment, target);
            }
        });
        return picture;
    }

    /**
     * Builds the dequantization factors of a profile: for each DCT mode or component, class, QNO and raster
     * position, the factor that turns a level into a coefficient of the transform.
     *
     * @param profile the DV format
     * @return the factors, indexed {@code ((select * 4 + class) * 16 + qno) * 64 + position}, {@code select} the
     * DCT mode for SD profiles and 0 for luma, 1 for chroma for DVCPRO HD
     */
    private static float[] dequantTable(DifProfile profile) {
        var table = new float[2 * 4 * 16 * 64];
        for (var select = 0; select < 2; select++) {
            for (var classNumber = 0; classNumber < 4; classNumber++) {
                for (var qno = 0; qno < 16; qno++) {
                    var base = ((select * 4 + classNumber) * 16 + qno) * 64;
                    for (var position = 1; position < 64; position++) {
                        double factor;
                        if (profile.highDefinition()) {
                            var matrix = DvTables.hdMatrix(profile.progressive(), select == 1);
                            factor = DvTables.hdStep(qno, classNumber) * matrix[position] / 32.0;
                        } else {
                            var area = select == 0 ? DvTables.AREA_88[position] : DvTables.AREA_248[position];
                            var weight = select == 0 ? DvTables.WEIGHT_88[position] : DvTables.WEIGHT_248[position];
                            factor = DvTables.sdStep(qno, classNumber, area) * (classNumber == 3 ? 2 : 1) / weight;
                        }
                        table[base + position] = (float) factor;
                    }
                    table[base] = 4;
                }
            }
        }
        return table;
    }

    /**
     * The planes being written.
     *
     * @param y the luma plane
     * @param u the Cb plane
     * @param v the Cr plane
     * @param lumaStride the luma row stride
     * @param chromaStride the chroma row stride
     */
    private record Planes(byte[] y, byte[] u, byte[] v, int lumaStride, int chromaStride) {
    }

    /**
     * Decodes video segments on one thread.
     */
    private static final class Worker {
        private final DvSegmentDecoder segments;
        private final int[] offsets = new int[5];
        private final float[] block = new float[64];
        private final float[] scratch = new float[64];

        /**
         * Constructs the state of a thread.
         *
         * @param profile the profile of the frames
         */
        Worker(DifProfile profile) {
            this.segments = new DvSegmentDecoder(profile.highDefinition(),
                    !profile.highDefinition() && profile.sampling() == DifProfile.Sampling.S422);
        }

        /**
         * Decodes one segment into the planes.
         *
         * @param data the array holding the frame
         * @param offset the index of the frame's first byte
         * @param layout the layout of the profile
         * @param profile the DV format
         * @param dequant the dequantization factors of the profile
         * @param segment the index of the video segment in the layout
         * @param planes the planes of the picture being decoded
         */
        void segment(byte[] data, int offset, DvLayout layout, DifProfile profile, float[] dequant, int segment,
                     Planes planes) {
            for (var q = 0; q < 5; q++) {
                offsets[q] = offset + layout.offset(segment * 5 + q);
            }
            segments.decode(data, offsets);
            var blocks = segments.blocks;
            var hd = profile.highDefinition();
            for (var q = 0; q < 5; q++) {
                var entry = segment * 5 + q;
                var kind = layout.kind(entry);
                var x = layout.x(entry);
                var y = layout.y(entry);
                var fieldMode = hd && kind == DvLayout.KIND_HD && segments.mode[q * blocks] == 1;
                for (var b = 0; b < blocks; b++) {
                    if (segments.reserved(b)) {
                        continue;
                    }
                    var index = q * blocks + b;
                    var select = hd ? (b >= 4 ? 1 : 0) : segments.mode[index];
                    var table = ((select * 4 + segments.classes[index]) * 16 + segments.qno[q]) * 64;
                    var levels = segments.levels;
                    var base = index * 64;
                    block[0] = segments.dc[index] * dequant[table];
                    for (var position = 1; position < 64; position++) {
                        block[position] = levels[base + position] * dequant[table + position];
                    }
                    DvTransform.inverse(block, 0, !hd && segments.mode[index] == 1, scratch);
                    place(kind, b, x, y, fieldMode, planes);
                }
            }
        }

        /**
         * Writes the samples of a reconstructed block into its place.
         *
         * @param kind the macroblock kind
         * @param b the block index
         * @param x the left edge of the macroblock
         * @param y the top edge of the macroblock
         * @param fieldMode whether a DVCPRO HD macroblock is in the field mode
         * @param planes the planes of the picture being decoded
         */
        private void place(int kind, int b, int x, int y, boolean fieldMode, Planes planes) {
            var lumaStride = planes.lumaStride();
            var chromaStride = planes.chromaStride();
            switch (kind) {
                case DvLayout.KIND_411 -> {
                    if (b < 4) {
                        store(planes.y(), lumaStride, x + 8 * b, y, 1, 0, 8);
                    } else {
                        store411(b == 4 ? planes.v() : planes.u(), chromaStride, x / 2, y, 0, 8);
                    }
                }
                case DvLayout.KIND_411_RIGHT -> {
                    if (b < 4) {
                        store(planes.y(), lumaStride, x + 8 * (b & 1), y + 8 * (b >> 1), 1, 0, 8);
                    } else {
                        var plane = b == 4 ? planes.v() : planes.u();
                        store411(plane, chromaStride, x / 2, y, 0, 4);
                        store411(plane, chromaStride, x / 2, y + 8, 4, 4);
                    }
                }
                case DvLayout.KIND_420 -> {
                    if (b < 4) {
                        store(planes.y(), lumaStride, x + 8 * (b & 1), y + 8 * (b >> 1), 1, 0, 8);
                    } else {
                        store(b == 4 ? planes.v() : planes.u(), chromaStride, x / 2, y / 2, 1, 0, 8);
                    }
                }
                case DvLayout.KIND_422 -> {
                    switch (b) {
                        case 0 -> store(planes.y(), lumaStride, x, y, 1, 0, 8);
                        case 2 -> store(planes.y(), lumaStride, x + 8, y, 1, 0, 8);
                        case 4 -> store(planes.v(), chromaStride, x / 2, y, 1, 0, 8);
                        default -> store(planes.u(), chromaStride, x / 2, y, 1, 0, 8);
                    }
                }
                case DvLayout.KIND_HD -> {
                    byte[] plane;
                    int stride;
                    int left;
                    boolean bottom;
                    if (b < 4) {
                        plane = planes.y();
                        stride = lumaStride;
                        left = x + 8 * (b & 1);
                        bottom = b >= 2;
                    } else {
                        plane = b < 6 ? planes.v() : planes.u();
                        stride = chromaStride;
                        left = x / 2;
                        bottom = (b & 1) == 1;
                    }
                    if (fieldMode) {
                        store(plane, stride, left, y + (bottom ? 1 : 0), 2, 0, 8);
                    } else {
                        store(plane, stride, left, y + (bottom ? 8 : 0), 1, 0, 8);
                    }
                }
                default -> {
                    if (b < 4) {
                        store(planes.y(), lumaStride, x + 8 * b, y, 1, 0, 8);
                    } else {
                        var plane = b < 6 ? planes.v() : planes.u();
                        store(plane, chromaStride, x / 2 + 8 * (b & 1), y, 1, 0, 8);
                    }
                }
            }
        }

        /**
         * Stores columns of the reconstructed block, one sample per column.
         *
         * @param plane the samples of one component
         * @param stride its row stride
         * @param x the first column
         * @param y the line of the block's first row
         * @param lineStep the distance between the lines of two rows, 1 or 2
         * @param firstColumn the first column of the block to store
         * @param columns the number of columns to store
         */
        private void store(byte[] plane, int stride, int x, int y, int lineStep, int firstColumn, int columns) {
            for (var row = 0; row < 8; row++) {
                var line = (y + row * lineStep) * stride + x;
                for (var column = 0; column < columns; column++) {
                    plane[line + column] = (byte) clamp(block[row * 8 + firstColumn + column]);
                }
            }
        }

        /**
         * Stores columns of a reconstructed 4:1:1 chroma block, each sample in two columns of the 4:2:2 plane.
         *
         * @param plane the samples of one component
         * @param stride its row stride
         * @param x the first column of the 4:2:2 plane
         * @param y the line of the block's first row
         * @param firstColumn the first column of the block to store
         * @param columns the number of columns to store
         */
        private void store411(byte[] plane, int stride, int x, int y, int firstColumn, int columns) {
            for (var row = 0; row < 8; row++) {
                var line = (y + row) * stride + x;
                for (var column = 0; column < columns; column++) {
                    var value = (byte) clamp(block[row * 8 + firstColumn + column]);
                    plane[line + 2 * column] = value;
                    plane[line + 2 * column + 1] = value;
                }
            }
        }

        /**
         * Converts a reconstructed value to a sample: adds the level shift, rounds and clamps to 8 bits.
         *
         * @param value the reconstructed value
         * @return the sample, from 0 to 255
         */
        private static int clamp(float value) {
            var sample = (int) Math.floor(value + 128.5f);
            return sample < 0 ? 0 : Math.min(sample, 255);
        }
    }
}
