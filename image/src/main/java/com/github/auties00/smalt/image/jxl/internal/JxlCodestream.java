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
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes the frames of a JPEG XL codestream one after another and composites the displayed ones
 * (ISO/IEC 18181-1 section F): it keeps the four reference slots, the LF frames and the frame
 * counts that noise synthesis is seeded with.
 *
 * <p>{@link #nextKeyframe()} returns the canvas after each displayed frame, in the output color
 * space, until the last frame. Instances are not thread-safe.
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
     * The number of frames decoded so far.
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
     * Holds a displayed frame.
     *
     * @param canvas the canvas after the frame, the size of the image, in the output color space
     * @param duration the display duration in animation ticks
     */
    public record Keyframe(FrameImage canvas, int duration) {
    }

    /**
     * Creates a decoder.
     *
     * @param container the parsed file
     * @param header the image header
     * @param icc the ICC profile, or {@code null}
     * @param firstFrame the offset of the first frame after the preview
     * @param limits the resource bounds
     */
    private JxlCodestream(JxlContainer container, ImageHeader header, byte[] icc, long firstFrame, MediaLimits limits) {
        this.container = container;
        this.header = header;
        this.icc = icc;
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
        var container = JxlContainer.parse(file, limits);
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
        if (header.previewWidth() > 0) {
            var preview = FrameHeader.read(br, header, true);
            var toc = Toc.read(br, preview, offset);
            offset = toc.end();
        }
        return new JxlCodestream(container, header, icc, offset, limits);
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
     * Rewinds to the first frame, discarding the references.
     */
    public void rewind() {
        nextFrame = firstFrame;
        frameIndex = 0;
        keyframes = 0;
        lastKeyframe = -1;
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
     * Skips the next frame without decoding it.
     *
     * @return the skipped frame's header, or {@code null} after the last frame
     */
    public FrameHeader skipFrame() {
        if (finished) {
            return null;
        }
        var br = readerAt(nextFrame);
        var frame = FrameHeader.read(br, header, false);
        var toc = Toc.read(br, frame, nextFrame);
        nextFrame = toc.end();
        frameIndex++;
        if (frame.isLast) {
            finished = true;
        }
        return frame;
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
            float[][] lfPlanes = null;
            var lfWidth = 0;
            var lfHeight = 0;
            if (frame.hasFlag(FrameHeader.FLAG_USE_LF_FRAME)) {
                var lf = lfFrames[frame.lfLevel];
                if (lf == null || lf.colorChannels != 3) {
                    throw new MediaException.Malformed("frame uses a missing LF frame");
                }
                lfPlanes = new float[][]{lf.planes[0], lf.planes[1], lf.planes[2]};
                lfWidth = lf.width;
                lfHeight = lf.height;
            }
            var decoded = FrameDecoder.decode(sections, header, frame, lfPlanes, lfWidth, lfHeight, limits);
            var keyframe = frame.isKeyframe();
            long visible = keyframes + (keyframe ? 1 : 0);
            long invisible = keyframe ? 0 : (lastKeyframe < 0 ? 1L + frameIndex : (long) frameIndex - lastKeyframe);
            var context = new FrameRenderer.Context(references.clone(), visible, invisible);
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
            if (frame.canReference()) {
                FrameImage saved;
                if (frame.saveBeforeCt || canvas == null) {
                    saved = rendered;
                } else {
                    saved = canvas;
                }
                references[frame.saveAsReference] = saved;
            }
            if (keyframe && canvas != null) {
                keyframes++;
                lastKeyframe = index;
                var output = canvas.colorTransformed ? canvas : FrameRenderer.transformColor(canvas.copy(), header, frame);
                return new Keyframe(output, frame.duration);
            }
        }
        return null;
    }
}
