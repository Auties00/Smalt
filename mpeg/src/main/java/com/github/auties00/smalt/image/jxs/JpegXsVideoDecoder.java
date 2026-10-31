package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.jxs.internal.JxsFile;
import com.github.auties00.smalt.image.jxs.internal.JxsFrameDecoder;
import com.github.auties00.smalt.image.jxs.internal.JxsHeader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Decodes JPEG XS video (ISO/IEC 21122), as carried in MPEG-2 transport streams (Rec. ITU-T H.222.0 Annex W), MP4
 * ({@code jxsm}) and MXF (SMPTE ST 2124), where every frame is an independent codestream or a pair of field
 * codestreams.
 *
 * <p>A packet holds a JPEG XS codestream or a JXS file (ISO/IEC 21122-3), optionally followed by a second codestream:
 * the two fields of an interlaced frame, the earlier one first. Each codestream decodes as {@link JpegXsDecoder}
 * decodes it, to the picture {@link JpegXsDecoder} describes: samples deeper than 8 bits are kept in a 16-bit
 * picture when {@link VideoDecoderOptions#highBitDepth()} is set. The color description of a Y'CbCr picture is that of
 * the JXS file, else the track's {@link VideoTrack#color()}, else {@link ColorInfo#BT709_LIMITED}.
 *
 * <p>The two fields of a frame are woven into one picture whose rows 0, 2, 4 and so on come from the top field,
 * which is the first field unless the track's {@linkplain VideoTrack#interlacing() scan} is
 * {@link Interlacing#BOTTOM_FIELD_FIRST} or {@link Interlacing#BOTTOM_FIELD_FIRST_SEPARATED}. The second field is
 * ignored, and the first returned alone, when it cannot be decoded or does not pair with the first: both fields must
 * have the same width, components, precisions and sampling, the top field as high as the bottom one or one row
 * higher, and neither may be a Bayer mosaic.
 *
 * <p>A codestream of temporal differential coding (the TDC profiles) predicts its coefficients from those of the
 * previous frame, kept apart for the first and the second field of a packet, and from zero for the first frame or
 * after a change of picture size or sampling; frames of every other profile share no state. Every packet yields one
 * frame with the packet's timestamps, a key frame unless its codestream uses temporal differential coding, and
 * {@link #flush(Consumer)} has nothing to drain. A corrupt frame raises {@link MediaException.Malformed}; the next
 * frame of a temporal differential sequence then predicts from what the corrupt one left. Instances are not
 * thread-safe; frames are decoded partly on the shared pool that {@link com.github.auties00.smalt.MediaRuntime}
 * describes, and the pictures do not depend on the threading.
 */
public final class JpegXsVideoDecoder implements VideoDecoder {
    /**
     * The input, named in failure messages.
     */
    private static final String WHAT = "JPEG XS frame";

    /**
     * The track being decoded.
     */
    private final VideoTrack track;

    /**
     * The decoding options.
     */
    private final VideoDecoderOptions options;

    /**
     * Whether the track's scan puts the bottom field first.
     */
    private final boolean bottomFirst;

    /**
     * The operation the calls of this decoder form, whose computing time decides how long a call keeps its carrier
     * thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The frame buffer of temporal differential coding of the first codestream of each packet.
     */
    private final JxsFrameDecoder.FrameBuffer firstBuffer = new JxsFrameDecoder.FrameBuffer();

    /**
     * The frame buffer of temporal differential coding of the second field codestream of each packet.
     */
    private final JxsFrameDecoder.FrameBuffer secondBuffer = new JxsFrameDecoder.FrameBuffer();

    /**
     * The coefficient planes the first codestream of each packet decodes into.
     */
    private final JxsFrameDecoder.Workspace firstPlanes = new JxsFrameDecoder.Workspace();

    /**
     * The coefficient planes the second field codestream of each packet decodes into.
     */
    private final JxsFrameDecoder.Workspace secondPlanes = new JxsFrameDecoder.Workspace();

    /**
     * Whether the last frame decoded uses temporal differential coding.
     */
    private boolean temporal;

    /**
     * Creates a decoder for a JPEG XS track under the {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#JPEG_XS}
     * @throws IllegalArgumentException if the track is not a JPEG XS track
     */
    public JpegXsVideoDecoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for a JPEG XS track.
     *
     * @param track the track description; its codec must be {@link Codec#JPEG_XS}
     * @param options the limits checked against every frame before allocating, and whether samples of more than
     * 8 bits keep their depth; the thread count is ignored
     * @throws IllegalArgumentException if the track is not a JPEG XS track
     */
    public JpegXsVideoDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.JPEG_XS) {
            throw new IllegalArgumentException("not a JPEG XS track: " + track.codec());
        }
        this.track = track;
        this.options = options;
        var scan = track.interlacing().orElse(null);
        this.bottomFirst = scan == Interlacing.BOTTOM_FIELD_FIRST || scan == Interlacing.BOTTOM_FIELD_FIRST_SEPARATED;
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Decodes one packet holding one JPEG XS frame.
     *
     * @param packet the next packet
     * @param output receives the frame, carrying the packet's timestamps
     * @throws MediaException.Malformed if the packet holds no valid codestream
     * @throws MediaException.Unsupported if the components of the codestream form no picture
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var picture = decodeFrame(data);
        output.accept(new VideoFrame(picture, packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, !temporal));
    }

    /**
     * Decodes the codestreams of one frame.
     *
     * @param data the frame
     * @return the picture
     */
    private Picture decodeFrame(byte[] data) {
        try (var _ = ParallelBands.operation(operation)) {
            return Guards.malformed(WHAT, () -> {
                var limits = options.limits();
                var file = JxsFile.parse(data, data.length);
                var color = file.color() != null ? file.color() : track.color().orElse(null);
                var first = JxsFrameDecoder.decode(data, file.codestreamStart(), file.codestreamEnd(), limits,
                        firstBuffer, firstPlanes);
                temporal = first.header().temporal;
                var image = JpegXsDecoder.image(first, color, file.icc());
                var next = first.end();
                if (next + 4 <= file.codestreamEnd() && JxsHeader.isCodestream(data, next, file.codestreamEnd())) {
                    var second = secondField(data, next, file.codestreamEnd(), color, file.icc());
                    if (second != null) {
                        var woven = bottomFirst ? weave(second, image) : weave(image, second);
                        if (woven != null) {
                            image = woven;
                        }
                    }
                }
                return JpegXsDecoder.picture(image, options.highBitDepth());
            });
        }
    }

    /**
     * Decodes the second field of a frame, or gives {@code null} when it is damaged or uses a feature the decoder
     * lacks, so that the frame decodes from its first field alone.
     *
     * @param data the frame
     * @param start the position of the field's {@code SOC} marker
     * @param end the end of the frame's codestreams (exclusive)
     * @param color the color description of the frame, or {@code null}
     * @param icc the ICC profile of the frame, or {@code null}
     * @return the field, or {@code null}
     */
    private JpegXsImage secondField(byte[] data, int start, int end, ColorInfo color, byte[] icc) {
        try {
            return Guards.malformed(WHAT, () -> JpegXsDecoder.image(
                    JxsFrameDecoder.decode(data, start, end, options.limits(), secondBuffer, secondPlanes), color,
                    icc));
        } catch (MediaException.Malformed | MediaException.Unsupported ignored) {
            return null;
        }
    }

    /**
     * Weaves two fields into one frame, or gives {@code null} when they do not pair.
     *
     * @param top the top field
     * @param bottom the bottom field
     * @return the frame, or {@code null}
     */
    private JpegXsImage weave(JpegXsImage top, JpegXsImage bottom) {
        var count = top.components().size();
        if (top.cfaPattern().isPresent() || bottom.cfaPattern().isPresent() || top.width() != bottom.width()
                || count != bottom.components().size() || top.height() < bottom.height()
                || top.height() > bottom.height() + 1) {
            return null;
        }
        var height = top.height() + bottom.height();
        var limits = options.limits();
        var components = new ArrayList<JpegXsImage.Component>(count);
        for (var c = 0; c < count; c++) {
            var a = top.components().get(c);
            var b = bottom.components().get(c);
            if (a.width() != b.width() || a.precision() != b.precision() || a.sx() != b.sx() || a.sy() != b.sy()
                    || a.height() < b.height() || a.height() > b.height() + 1) {
                return null;
            }
            var width = a.width();
            var rows = a.height() + b.height();
            LimitChecks.checkAllocation(limits, (long) width * rows * Short.BYTES);
            var samples = new short[width * rows];
            for (var y = 0; y < rows; y++) {
                var field = (y & 1) == 0 ? a : b;
                System.arraycopy(field.samples(), (y >> 1) * width, samples, y * width, width);
            }
            components.add(new JpegXsImage.Component(width, rows, a.precision(), a.sx(), a.sy(), 0, 0, samples));
        }
        return new JpegXsImage(top.width(), height, components, top.colorTransform(), Optional.empty(),
                top.profileCode(), top.level(), top.color(), top.iccProfile());
    }

    /**
     * Does nothing: JPEG XS frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
