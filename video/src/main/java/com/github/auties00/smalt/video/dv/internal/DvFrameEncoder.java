package com.github.auties00.smalt.video.dv.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.header.DifFrames;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.intra.internal.IntraBands;

/**
 * Encodes pictures into DV frames of one profile.
 *
 * <p>A picture is converted to 8-bit limited-range Y'CbCr with the BT.601 matrix (SD) or the BT.709 matrix
 * (DVCPRO HD) in the chroma layout of the profile, 4:1:1 chroma averaging two 4:2:2 samples. The frame carries the
 * header, subcode, VAUX and empty audio sections of {@link DifFrames#writeSkeleton}; its video segments are encoded
 * in parallel bands unless the encoder runs on one thread, and the frame does not depend on the threading.
 *
 * <p>Instances are not thread-safe.
 */
public final class DvFrameEncoder {
    private final DifProfile profile;
    private final boolean wide;
    private final boolean adaptive;
    private final int threads;
    private DvSegmentEncoder[] workers = new DvSegmentEncoder[0];
    private byte[] cb411;
    private byte[] cr411;

    /**
     * Constructs an encoder.
     *
     * @param profile the profile of the frames
     * @param wide whether the pictures display at 16:9
     * @param adaptive whether blocks whose fields differ may use the 2-4-8 or field DCT mode
     * @param threads the thread count: {@code 1} for the calling thread, otherwise the shared pool with at most
     * this many threads ({@code 0} for no cap)
     */
    public DvFrameEncoder(DifProfile profile, boolean wide, boolean adaptive, int threads) {
        this.profile = profile;
        this.wide = wide;
        this.adaptive = adaptive;
        this.threads = threads;
    }

    /**
     * Encodes a picture into a frame.
     *
     * @param picture the picture, of the profile's size
     * @param frame the index of the frame in the stream, which gives its time code and, for a 720-line profile,
     * whether it is the first or the second video frame of its DIF frame
     * @return the frame, {@link DifProfile#frameSize()} bytes
     */
    public byte[] encode(Picture picture, long frame) {
        var format = profile.sampling() == DifProfile.Sampling.S420 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
        var color = profile.highDefinition() ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var yuv = ImageOps.toYuv(picture, format, color);
        DvSource source;
        if (profile.sampling() == DifProfile.Sampling.S411) {
            var width = profile.width() / 4;
            var height = profile.height();
            if (cb411 == null) {
                cb411 = new byte[width * height];
                cr411 = new byte[width * height];
            }
            quarter(yuv.u(), yuv.uvStride(), cb411, width, height);
            quarter(yuv.v(), yuv.uvStride(), cr411, width, height);
            source = new DvSource(yuv.y(), yuv.yStride(), cb411, cr411, width);
        } else {
            source = new DvSource(yuv.y(), yuv.yStride(), yuv.u(), yuv.v(), yuv.uvStride());
        }
        var data = new byte[profile.frameSize()];
        var secondHalf = profile.progressive() && frame % 2 == 1;
        DifFrames.writeSkeleton(data, 0, profile, wide, secondHalf, frame);
        var layout = DvLayout.of(profile, secondHalf);
        var segments = layout.segments();
        var bands = IntraBands.count(threads, segments, (long) profile.width() * profile.height());
        if (workers.length < bands) {
            var grown = new DvSegmentEncoder[bands];
            System.arraycopy(workers, 0, grown, 0, workers.length);
            for (var i = workers.length; i < bands; i++) {
                grown[i] = new DvSegmentEncoder(profile, adaptive);
            }
            workers = grown;
        }
        var encoders = workers;
        ParallelBands.run(bands, band -> {
            var encoder = encoders[band];
            var first = ParallelBands.start(band, bands, segments);
            var last = ParallelBands.start(band + 1, bands, segments);
            for (var segment = first; segment < last; segment++) {
                encoder.encode(data, 0, layout, segment, source);
            }
        });
        return data;
    }

    /**
     * Halves the width of a 4:2:2 chroma plane into a 4:1:1 one, each sample the rounded mean of two.
     *
     * @param source the 4:2:2 plane
     * @param stride its row stride
     * @param target the 4:1:1 plane, {@code width} samples per row
     * @param width the width of the 4:1:1 plane
     * @param height the number of rows
     */
    private static void quarter(byte[] source, int stride, byte[] target, int width, int height) {
        for (var row = 0; row < height; row++) {
            var in = row * stride;
            var out = row * width;
            for (var column = 0; column < width; column++) {
                var sum = (source[in + 2 * column] & 0xFF) + (source[in + 2 * column + 1] & 0xFF);
                target[out + column] = (byte) ((sum + 1) >> 1);
            }
        }
    }
}
