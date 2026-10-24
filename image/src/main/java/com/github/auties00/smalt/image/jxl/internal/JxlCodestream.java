/* Ports jxl-oxide 0.12.6 (crates/jxl-oxide and crates/jxl-render: frame loading and compositing). Copyright
   Wonwoo Choi and the jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlContainer;
import com.github.auties00.smalt.image.jxl.internal.color.IccStream;
import com.github.auties00.smalt.image.jxl.internal.frame.DecodedFrame;
import com.github.auties00.smalt.image.jxl.internal.frame.FrameDecoder;
import com.github.auties00.smalt.image.jxl.internal.frame.Sections;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.header.Toc;
import com.github.auties00.smalt.image.jxl.internal.render.Blending;
import com.github.auties00.smalt.image.jxl.internal.render.FrameImage;
import com.github.auties00.smalt.image.jxl.internal.render.FrameRenderer;
import com.github.auties00.smalt.image.jxl.internal.render.SpotColors;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the frames of a JPEG XL codestream one after another and composites the displayed ones
 * (ISO/IEC 18181-1 section F): it keeps the four reference slots, the LF frames and the frame
 * counts that noise synthesis is seeded with.
 *
 * <p>{@link #nextKeyframe()} returns the canvas after each displayed frame, in the output color
 * space, until the last frame. {@link #decodePreview()} decodes the preview frame and
 * {@link #decodeReduced(int)} the first displayed frame from fewer passes, both without disturbing the frame
 * sequence. With {@link #setRenderSpotColors(boolean)} the displayed canvases have their spot colors
 * composited, as {@link SpotColors} describes. Instances are not thread-safe.
 */
public final class JxlCodestream {
    /**
     * The parsed container.
     */
    private final JxlContainer container;

    /**
     * The image header.
     */
    private final ImageHeader header;

    /**
     * The embedded ICC profile, or {@code null}.
     */
    private final byte[] icc;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The byte offset, from the start of the codestream, of the preview frame, or -1 when there is none.
     */
    private final long previewFrame;

    /**
     * The byte offset, from the start of the codestream, of the first frame.
     */
    private final long firstFrame;

    /**
     * The byte offset of the next frame.
     */
    private long nextFrame;

    /**
     * The reference slots.
     */
    private final FrameImage[] references = new FrameImage[4];

    /**
     * The LF frames by LF level minus one.
     */
    private final FrameImage[] lfFrames = new FrameImage[4];

    /**
     * The number of frames decoded so far, the preview frame excluded.
     */
    private int frameIndex;

    /**
     * The number of keyframes returned so far.
     */
    private int keyframes;

    /**
     * The index of the last keyframe, or -1.
     */
    private int lastKeyframe = -1;

    /**
     * Whether the last frame has been decoded.
     */
    private boolean finished;

    /**
     * Whether displayed canvases have their spot colors composited.
     */
    private boolean renderSpotColors;

    /**
     * Holds a displayed frame.
     *
     * @param canvas the canvas after the frame, the size of the image, in the output color space
     * @param duration the display duration in animation ticks
     */
    public record Keyframe(FrameImage canvas, int duration) {
    }

    /**
     * Holds the first displayed frame decoded at a reduced size.
     *
     * @param canvas the canvas in the output color space, {@code ceil(width / factor)} by
     * {@code ceil(height / factor)} samples
     * @param factor the reduction, 2, 4 or 8, or 1 when the frame could not be decoded from fewer passes and
     * the canvas is the full-size one
     */
    public record Reduced(FrameImage canvas, int factor) {
    }

    /**
     * Describes where a frame starts and the decoder state before it.
     *
     * @param offset the byte offset of the frame from the start of the codestream
     * @param frameIndex the number of frames before it, the preview frame excluded
     * @param keyframes the number of displayed frames before it
     * @param lastKeyframe the index of the last displayed frame before it, or -1
     * @param header the frame header
     */
    public record FrameStart(long offset, int frameIndex, int keyframes, int lastKeyframe, FrameHeader header) {
    }

    /**
     * Creates a decoder.
     *
     * @param container the parsed file
     * @param header the image header
     * @param icc the ICC profile, or {@code null}
     * @param previewFrame the offset of the preview frame, or -1
     * @param firstFrame the offset of the first frame after the preview
     * @param limits the resource bounds
     */
    private JxlCodestream(JxlContainer container, ImageHeader header, byte[] icc, long previewFrame, long firstFrame,
                          MediaLimits limits) {
        this.container = container;
        this.header = header;
        this.icc = icc;
        this.previewFrame = previewFrame;
        this.firstFrame = firstFrame;
        this.nextFrame = firstFrame;
        this.limits = limits;
    }

    /**
     * Opens a file: parses the container, the image header and the ICC profile, and skips the
     * preview frame.
     *
     * @param file the whole file
     * @param limits the resource bounds
     * @return the decoder, positioned at the first frame
     * @throws MediaException.Malformed if the headers are invalid
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static JxlCodestream open(byte[] file, MediaLimits limits) {
        return open(JxlContainer.parse(file, limits), limits);
    }

    /**
     * Opens a parsed file or a bare codestream: parses the image header and the ICC profile, and skips the
     * preview frame.
     *
     * @param container the parsed file
     * @param limits the resource bounds
     * @return the decoder, positioned at the first frame
     * @throws MediaException.Malformed if the headers are invalid
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static JxlCodestream open(JxlContainer container, MediaLimits limits) {
        var br = new JxlBitReader(container.codestream(), container.codestreamOffset(), container.codestreamLength());
        var header = ImageHeader.read(br);
        LimitChecks.checkPicture(limits, header.width(), header.height());
        LimitChecks.checkChannels(limits, header.extraChannels().size() + 3);
        if (header.animation() == null) {
            LimitChecks.checkFrames(limits, 1);
        }
        byte[] icc = null;
        if (header.colorEncoding().wantIcc()) {
            icc = IccStream.read(br, limits.maxBytes());
        }
        br.zeroPadToByte();
        var offset = br.bitPosition() / 8;
        var preview = -1L;
        if (header.previewWidth() > 0) {
            preview = offset;
            var frame = FrameHeader.read(br, header, true);
            // The reader counts from the start of the codestream, so the TOC offsets need no base.
            var toc = Toc.read(br, frame, 0);
            offset = toc.end();
        }
        return new JxlCodestream(container, header, icc, preview, offset, limits);
    }

    /**
     * Returns the image header.
     *
     * @return the header
     */
    public ImageHeader header() {
        return header;
    }

    /**
     * Returns the embedded ICC profile.
     *
     * @return the profile, or {@code null} when the color space is enumerated
     */
    public byte[] icc() {
        return icc;
    }

    /**
     * {@return the parsed file the codestream comes from}
     */
    public JxlContainer container() {
        return container;
    }

    /**
     * {@return the byte offset of the first frame after the preview, from the start of the codestream}
     */
    public long firstFrame() {
        return firstFrame;
    }

    /**
     * {@return whether the codestream has a preview frame}
     */
    public boolean hasPreview() {
        return previewFrame >= 0;
    }

    /**
     * Sets whether displayed canvases have their spot colors composited; they do not by default.
     *
     * @param render whether {@link #nextKeyframe()}, {@link #decodePreview()} and {@link #decodeReduced(int)}
     * composite the spot color channels into the color channels
     */
    public void setRenderSpotColors(boolean render) {
        this.renderSpotColors = render;
    }

    /**
     * Rewinds to the first frame, discarding the references.
     */
    public void rewind() {
        restart(new FrameStart(firstFrame, 0, 0, -1, null));
    }

    /**
     * Positions the decoder at the start of a frame that does not depend on the frames before it, discarding
     * the references.
     *
     * @param start where the frame starts and the frame counts before it
     */
    public void restart(FrameStart start) {
        nextFrame = start.offset();
        frameIndex = start.frameIndex();
        keyframes = start.keyframes();
        lastKeyframe = start.lastKeyframe();
        finished = false;
        Arrays.fill(references, null);
        Arrays.fill(lfFrames, null);
    }

    /**
     * Returns whether the last frame has been decoded.
     *
     * @return whether no frame is left
     */
    public boolean finished() {
        return finished;
    }

    /**
     * {@return where the next frame starts and the frame counts before it}
     */
    public FrameStart position() {
        return new FrameStart(nextFrame, frameIndex, keyframes, lastKeyframe, null);
    }

    /**
     * Skips the next frame without decoding it.
     *
     * @return the skipped frame's header, or {@code null} after the last frame
     */
    public FrameHeader skipFrame() {
        var start = skipFrameStart();
        return start == null ? null : start.header();
    }

    /**
     * Skips the next frame without decoding it, reporting where it started.
     *
     * @return where the skipped frame started, with its header, or {@code null} after the last frame
     */
    public FrameStart skipFrameStart() {
        if (finished) {
            return null;
        }
        var start = nextFrame;
        var br = readerAt(nextFrame);
        var frame = FrameHeader.read(br, header, false);
        var toc = Toc.read(br, frame, nextFrame);
        var result = new FrameStart(start, frameIndex, keyframes, lastKeyframe, frame);
        nextFrame = toc.end();
        if (frame.isKeyframe()) {
            keyframes++;
            lastKeyframe = frameIndex;
        }
        frameIndex++;
        if (frame.isLast) {
            finished = true;
        }
        return result;
    }

    /**
     * Creates a reader positioned at a codestream offset.
     *
     * @param offset the byte offset from the start of the codestream
     * @return the reader
     * @throws MediaException.Malformed if the offset is past the codestream
     */
    private JxlBitReader readerAt(long offset) {
        var length = container.codestreamLength();
        if (offset > length) {
            throw new MediaException.Malformed("truncated JPEG XL codestream");
        }
        return new JxlBitReader(container.codestream(), container.codestreamOffset() + (int) offset,
                length - (int) offset);
    }

    /**
     * Decodes the first frame up to its quantized coefficients, for JPEG reconstruction.
     *
     * @return the decoded frame, whose VarDCT data keeps its coefficients
     * @throws MediaException.Malformed if the frame is invalid or uses an LF frame
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public DecodedFrame decodeFirstFrameCoefficients() {
        var br = readerAt(firstFrame);
        var frame = FrameHeader.read(br, header, false);
        var toc = Toc.read(br, frame, firstFrame);
        if (frame.hasFlag(FrameHeader.FLAG_USE_LF_FRAME)) {
            throw new MediaException.Malformed("a recompressed JPEG frame cannot use an LF frame");
        }
        var sections = new Sections(container.codestream(), container.codestreamOffset(), container.codestreamLength(),
                toc, frame);
        return FrameDecoder.decode(sections, header, frame, null, 0, 0, limits, true);
    }

    /**
     * Decodes the preview frame, without moving the frame sequence.
     *
     * @return the preview canvas, the size of the preview, in the output color space, with a duration of zero
     * @throws IllegalStateException if the codestream has no preview frame
     * @throws MediaException.Malformed if the preview frame is invalid, blended or cropped
     * @throws MediaException.LimitExceeded if the preview exceeds the limits
     */
    public Keyframe decodePreview() {
        if (previewFrame < 0) {
            throw new IllegalStateException("the codestream has no preview frame");
        }
        var br = readerAt(previewFrame);
        var frame = FrameHeader.read(br, header, true);
        var toc = Toc.read(br, frame, previewFrame);
        if (!frame.isNormalFrame() || frame.haveCrop || !frame.resetsCanvas
                || frame.hasFlag(FrameHeader.FLAG_USE_LF_FRAME) || frame.hasFlag(FrameHeader.FLAG_PATCHES)) {
            throw new MediaException.Malformed("a JPEG XL preview frame must replace a whole canvas on its own");
        }
        for (var info : frame.ecBlending) {
            if (info.mode() != FrameHeader.BLEND_REPLACE) {
                throw new MediaException.Malformed("a JPEG XL preview frame must replace a whole canvas on its own");
            }
        }
        var sections = new Sections(container.codestream(), container.codestreamOffset(), container.codestreamLength(),
                toc, frame);
        var decoded = FrameDecoder.decode(sections, header, frame, null, 0, 0, limits);
        var spotsLinear = renderSpotColors && SpotColors.present(header) && SpotColors.linear(header, frame);
        // The preview is counted as an invisible frame: its noise is seeded with no visible frame before it.
        var context = new FrameRenderer.Context(new FrameImage[4], 0, 1, spotsLinear);
        var rendered = FrameRenderer.render(decoded, header, context, limits);
        return new Keyframe(display(rendered, frame, spotsLinear), 0);
    }

    /**
     * Returns the number of leading passes that render the first displayed frame well enough for a reduced size,
     * when that frame can be decoded from fewer than all of its passes.
     *
     * <p>The first displayed frame qualifies when only LF frames and reference-only frames come before it and it
     * replaces the whole canvas; it qualifies for a reduction by 8 without any pass (its LF image or LF channels),
     * and, when it has progressive passes, for the reductions its passes describe. Whether the modular channels are
     * complete after those passes is decided by {@link #decodeReduced(int)}.
     *
     * @param factor the reduction, 2, 4 or 8
     * @return the number of passes, from 0 (the LF image only), or -1 when the frame cannot be decoded at that
     * reduction from fewer passes
     * @throws MediaException.Malformed if a frame header is invalid
     * @throws MediaException.LimitExceeded if more frames than the limits allow come before it
     */
    public int reductionPasses(int factor) {
        var frame = firstDisplayedFrame();
        if (frame == null) {
            return -1;
        }
        for (var passes = 0; passes < frame.numPasses; passes++) {
            if (downsamplingAfter(frame, passes) <= factor) {
                return passes;
            }
        }
        return -1;
    }

    /**
     * Returns the header of the first displayed frame when it qualifies for a reduced-size decode.
     *
     * @return the header, or {@code null} when the frames before it or the frame itself do not qualify
     * @throws MediaException.LimitExceeded if more frames than the limits allow come before it
     */
    private FrameHeader firstDisplayedFrame() {
        var saved = position();
        var savedFinished = finished;
        try {
            nextFrame = firstFrame;
            frameIndex = 0;
            keyframes = 0;
            lastKeyframe = -1;
            finished = false;
            var scanned = 0L;
            while (!finished) {
                if (++scanned > frameBound()) {
                    throw new MediaException.LimitExceeded("too many JPEG XL frames");
                }
                var frame = skipFrame();
                if (frame.isNormalFrame()) {
                    return frame.isKeyframe() && qualifiesForReduction(frame) ? frame : null;
                }
                if (frame.frameType != FrameHeader.LF_FRAME && frame.frameType != FrameHeader.REFERENCE_ONLY) {
                    return null;
                }
            }
            return null;
        } finally {
            nextFrame = saved.offset();
            frameIndex = saved.frameIndex();
            keyframes = saved.keyframes();
            lastKeyframe = saved.lastKeyframe();
            finished = savedFinished;
        }
    }

    /**
     * {@return the largest number of frames a scan for the first displayed frame reads, as the frame counts of
     * the decoder bound them}
     */
    private long frameBound() {
        return limits.maxFrames() * 4L + 16;
    }

    /**
     * {@return whether a displayed frame replaces the whole canvas, so that it can be decoded alone at a reduced
     * size}
     *
     * @param frame the frame header
     */
    private static boolean qualifiesForReduction(FrameHeader frame) {
        if (frame.haveCrop || frame.blending.mode() != FrameHeader.BLEND_REPLACE) {
            return false;
        }
        for (var info : frame.ecBlending) {
            if (info.mode() != FrameHeader.BLEND_REPLACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the downsampling a frame's first passes are meant for
     * ({@code GetDownsamplingTargetForCompletedPasses}).
     *
     * @param frame the frame header
     * @param passes the number of leading passes decoded
     * @return 1 when every pass is decoded, else the smallest factor whose last pass is decoded, 8 for none
     */
    static int downsamplingAfter(FrameHeader frame, int passes) {
        if (passes >= frame.numPasses) {
            return 1;
        }
        var target = 8;
        for (var i = 0; i < frame.downsample.length; i++) {
            if (passes > frame.lastPass[i]) {
                target = Math.min(target, frame.downsample[i]);
            }
        }
        return target;
    }

    /**
     * Decodes the first displayed frame from its first passes and reduces it, without moving the frame sequence.
     *
     * <p>A VarDCT frame reduced by 8 without extra channels, patches, splines, noise, upsampling or chroma
     * subsampling is its LF image; otherwise the frame is rendered from its first passes, the later passes
     * left zero, and every plane is averaged over boxes of {@code factor} by {@code factor} samples (fewer at the
     * right and bottom edges), clamped to {@code 0..1} first unless the image has floating-point samples.
     *
     * @param factor the reduction, 2, 4 or 8, for which {@link #reductionPasses(int)} is not -1
     * @return the reduced canvas, or the full-size canvas with a factor of 1 when the modular channels of the
     * frame are not complete after those passes, so that no reduced decode is cheaper than a full one
     * @throws IllegalArgumentException if the factor is not one {@link #reductionPasses(int)} accepts
     * @throws MediaException.Malformed if a frame is invalid
     * @throws MediaException.LimitExceeded if a frame exceeds the limits
     */
    public Reduced decodeReduced(int factor) {
        var passes = reductionPasses(factor);
        if (passes < 0) {
            throw new IllegalArgumentException("the first frame cannot be reduced by " + factor);
        }
        var saved = position();
        var savedFinished = finished;
        var savedReferences = references.clone();
        var savedLf = lfFrames.clone();
        rewind();
        try {
            var decodedFrames = 0L;
            while (!finished) {
                if (++decodedFrames > frameBound()) {
                    throw new MediaException.LimitExceeded("too many JPEG XL frames");
                }
                var br = readerAt(nextFrame);
                var frame = FrameHeader.read(br, header, false);
                var toc = Toc.read(br, frame, nextFrame);
                var sections = new Sections(container.codestream(), container.codestreamOffset(),
                        container.codestreamLength(), toc, frame);
                nextFrame = toc.end();
                finished = frame.isLast;
                var lf = lfPlanes(frame);
                if (!frame.isNormalFrame()) {
                    var decoded = FrameDecoder.decode(sections, header, frame, lf == null ? null : lf.planes, lf == null
                            ? 0 : lf.width, lf == null ? 0 : lf.height, limits);
                    var rendered = FrameRenderer.render(decoded, header,
                            new FrameRenderer.Context(references.clone(), 0, invisibleCount(), false), limits);
                    store(frame, rendered, null);
                    frameIndex++;
                    continue;
                }
                var decoded = FrameDecoder.decode(sections, header, frame, lf == null ? null : lf.planes,
                        lf == null ? 0 : lf.width, lf == null ? 0 : lf.height, limits, false, passes);
                var spotsLinear = renderSpotColors && SpotColors.present(header) && SpotColors.linear(header, frame);
                if (decoded.passes() >= frame.numPasses) {
                    var context = new FrameRenderer.Context(references.clone(), 1, 0, spotsLinear);
                    var rendered = FrameRenderer.render(decoded, header, context, limits);
                    var canvas = Blending.blendFrame(rendered, frame, header, references);
                    return new Reduced(display(canvas, frame, spotsLinear), 1);
                }
                if (passes == 0 && lfOnly(decoded)) {
                    var vd = decoded.varDct();
                    var width = (frame.width + 7) / 8;
                    var height = (frame.height + 7) / 8;
                    var planes = new float[3][];
                    for (var c = 0; c < 3; c++) {
                        planes[c] = crop(vd.lfPlane(c), vd.lfWidth(c), width, height);
                    }
                    var image = new FrameImage(planes, 3, width, height);
                    return new Reduced(display(image, frame, spotsLinear), factor);
                }
                var context = new FrameRenderer.Context(references.clone(), 1, 0, spotsLinear);
                var rendered = FrameRenderer.render(decoded, header, context, limits);
                var canvas = Blending.blendFrame(rendered, frame, header, references);
                var clamp = !header.bitDepth().floatSample();
                return new Reduced(reduce(display(canvas, frame, spotsLinear), factor, clamp), factor);
            }
            throw new MediaException.Malformed("JPEG XL file without a displayed frame");
        } finally {
            nextFrame = saved.offset();
            frameIndex = saved.frameIndex();
            keyframes = saved.keyframes();
            lastKeyframe = saved.lastKeyframe();
            finished = savedFinished;
            System.arraycopy(savedReferences, 0, references, 0, 4);
            System.arraycopy(savedLf, 0, lfFrames, 0, 4);
        }
    }

    /**
     * {@return whether a frame decoded without passes is a VarDCT frame whose LF image alone renders it}
     *
     * @param decoded the decoded frame
     */
    private boolean lfOnly(DecodedFrame decoded) {
        var frame = decoded.header();
        var vd = decoded.varDct();
        if (vd == null || !header.extraChannels().isEmpty() || frame.upsampling != 1) {
            return false;
        }
        if (frame.hasFlag(FrameHeader.FLAG_PATCHES) || frame.hasFlag(FrameHeader.FLAG_SPLINES)
                || frame.hasFlag(FrameHeader.FLAG_NOISE)) {
            return false;
        }
        for (var c = 0; c < 3; c++) {
            if (vd.hshift(c) != 0 || vd.vshift(c) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies the top-left corner of a plane.
     *
     * @param plane the plane, {@code stride} samples per row
     * @param stride the plane width
     * @param width the width to keep, at most {@code stride}
     * @param height the height to keep
     * @return the cropped plane
     */
    private static float[] crop(float[] plane, int stride, int width, int height) {
        var out = new float[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }

    /**
     * Averages every plane of a canvas over boxes of {@code factor} by {@code factor} samples.
     *
     * @param canvas the canvas to reduce, in the output color space
     * @param factor the box size
     * @param clamp whether to clamp the samples to {@code 0..1} before averaging them, as the integer output of
     * the full-size canvas clamps them
     * @return the reduced canvas, {@code ceil(width / factor)} by {@code ceil(height / factor)}
     */
    static FrameImage reduce(FrameImage canvas, int factor, boolean clamp) {
        var width = canvas.width;
        var height = canvas.height;
        var outWidth = (width + factor - 1) / factor;
        var outHeight = (height + factor - 1) / factor;
        var planes = new float[canvas.planes.length][];
        for (var c = 0; c < planes.length; c++) {
            planes[c] = new float[outWidth * outHeight];
        }
        var bands = ParallelBands.bands(outHeight, (long) width * height * planes.length);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, outHeight);
            var to = ParallelBands.start(band + 1, bands, outHeight);
            for (var c = 0; c < planes.length; c++) {
                var source = canvas.planes[c];
                var target = planes[c];
                for (var oy = from; oy < to; oy++) {
                    var y0 = oy * factor;
                    var y1 = Math.min(y0 + factor, height);
                    for (var ox = 0; ox < outWidth; ox++) {
                        var x0 = ox * factor;
                        var x1 = Math.min(x0 + factor, width);
                        var sum = 0.0;
                        for (var y = y0; y < y1; y++) {
                            var row = y * width;
                            for (var x = x0; x < x1; x++) {
                                var v = source[row + x];
                                sum += clamp ? Math.min(Math.max(v, 0.0f), 1.0f) : v;
                            }
                        }
                        target[oy * outWidth + ox] = (float) (sum / ((y1 - y0) * (x1 - x0)));
                    }
                }
            }
        });
        var reduced = new FrameImage(planes, canvas.colorChannels, outWidth, outHeight);
        reduced.colorTransformed = canvas.colorTransformed;
        return reduced;
    }

    /**
     * Decodes frames up to the next displayed one.
     *
     * @return the displayed frame, or {@code null} after the last frame
     * @throws MediaException.Malformed if a frame is invalid
     * @throws MediaException.LimitExceeded if a frame exceeds the limits
     */
    public Keyframe nextKeyframe() {
        while (!finished) {
            var br = readerAt(nextFrame);
            var frame = FrameHeader.read(br, header, false);
            var toc = Toc.read(br, frame, nextFrame);
            var sections = new Sections(container.codestream(), container.codestreamOffset(),
                    container.codestreamLength(), toc, frame);
            nextFrame = toc.end();
            if (frame.isLast) {
                finished = true;
            }
            var lf = lfPlanes(frame);
            var decoded = FrameDecoder.decode(sections, header, frame, lf == null ? null : lf.planes,
                    lf == null ? 0 : lf.width, lf == null ? 0 : lf.height, limits);
            var keyframe = frame.isKeyframe();
            long visible = keyframes + (keyframe ? 1 : 0);
            long invisible = keyframe ? 0 : invisibleCount();
            var spotsLinear = keyframe && renderSpotColors && SpotColors.present(header)
                    && SpotColors.linear(header, frame);
            var context = new FrameRenderer.Context(references.clone(), visible, invisible, spotsLinear);
            var rendered = FrameRenderer.render(decoded, header, context, limits);
            var index = frameIndex++;
            if (frame.lfLevel > 0) {
                lfFrames[frame.lfLevel - 1] = rendered;
                continue;
            }
            FrameImage canvas = null;
            if (frame.isNormalFrame()) {
                canvas = Blending.blendFrame(rendered, frame, header, references);
            }
            store(frame, rendered, canvas);
            if (keyframe && canvas != null) {
                keyframes++;
                lastKeyframe = index;
                return new Keyframe(display(canvas, frame, spotsLinear), frame.duration);
            }
        }
        return null;
    }

    /**
     * {@return the noise seed count of the frames since the last displayed one, this frame included, for a frame
     * that is not displayed}
     */
    private long invisibleCount() {
        if (lastKeyframe >= 0) {
            return (long) frameIndex - lastKeyframe;
        }
        // The preview frame counts as a frame that is not displayed.
        return 1L + frameIndex + (previewFrame >= 0 ? 1 : 0);
    }

    /**
     * Returns the planes of the LF frame a frame uses.
     *
     * @param frame the frame header
     * @return the LF frame, or {@code null} when the frame uses none
     * @throws MediaException.Malformed if the frame uses an LF frame that was not decoded
     */
    private LfFrame lfPlanes(FrameHeader frame) {
        if (!frame.hasFlag(FrameHeader.FLAG_USE_LF_FRAME)) {
            return null;
        }
        var lf = lfFrames[frame.lfLevel];
        if (lf == null || lf.colorChannels != 3) {
            throw new MediaException.Malformed("frame uses a missing LF frame");
        }
        return new LfFrame(new float[][]{lf.planes[0], lf.planes[1], lf.planes[2]}, lf.width, lf.height);
    }

    /**
     * Holds the color planes of an LF frame.
     *
     * @param planes the X, Y and B planes
     * @param width the plane width
     * @param height the plane height
     */
    private record LfFrame(float[][] planes, int width, int height) {
    }

    /**
     * Saves a rendered frame into the LF frames or the reference slots it names.
     *
     * @param frame the frame header
     * @param rendered the rendered frame
     * @param canvas the canvas after blending it, or {@code null} for a frame that is not blended
     */
    private void store(FrameHeader frame, FrameImage rendered, FrameImage canvas) {
        if (frame.lfLevel > 0) {
            lfFrames[frame.lfLevel - 1] = rendered;
            return;
        }
        if (frame.canReference()) {
            references[frame.saveAsReference] = frame.saveBeforeCt || canvas == null ? rendered : canvas;
        }
    }

    /**
     * Prepares a displayed canvas: converts it to the output color space and composites its spot colors.
     *
     * @param canvas the canvas, which may be saved as a reference and is never modified
     * @param frame the header of the frame that completed it
     * @param spotsLinear whether the spot colors were, or are to be, blended in linear light
     * @return the canvas in the output color space
     */
    private FrameImage display(FrameImage canvas, FrameHeader frame, boolean spotsLinear) {
        var output = canvas.colorTransformed ? canvas
                : FrameRenderer.transformColor(canvas.copy(), header, frame, spotsLinear);
        if (renderSpotColors && !spotsLinear && SpotColors.present(header)) {
            if (output == canvas) {
                output = canvas.copy();
            }
            SpotColors.mix(output.planes, output.colorChannels, output.width * output.height, header);
        }
        return output;
    }

    /**
     * Lists where every frame starts, without decoding any, and rewinds.
     *
     * @param maxFrames the largest number of frames to list
     * @return the frames in codestream order
     * @throws MediaException.Malformed if a frame header is invalid
     * @throws MediaException.LimitExceeded if the codestream has more than {@code maxFrames} frames
     */
    public List<FrameStart> scanFrames(long maxFrames) {
        rewind();
        var frames = new ArrayList<FrameStart>();
        while (!finished) {
            if (frames.size() >= maxFrames) {
                throw new MediaException.LimitExceeded("too many JPEG XL frames");
            }
            frames.add(skipFrameStart());
        }
        rewind();
        return frames;
    }
}
