package com.github.auties00.smalt.image.hap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.hap.internal.HapSections;
import com.github.auties00.smalt.image.hap.internal.HapTextures;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Vidvox HAP video ({@link Codec#HAP}) in every texture format of the HAP specification, as found in
 * QuickTime, AVI and Matroska files.
 *
 * <p>A frame's sections declare its texture format and its second-stage compression (none, Snappy, or chunks of
 * either, which are expanded in parallel); the picture size is the track's, since frames do not carry it. The
 * textures are decoded as their compression formats define them, cropped from their 4 by 4 blocks to the picture:
 * <ul>
 *   <li>HAP (RGB DXT1) and HAP Q (scaled YCoCg DXT5) to an opaque {@link RgbaPicture};</li>
 *   <li>HAP Alpha (RGBA DXT5), HAP R (BPTC) and HAP Q Alpha (scaled YCoCg DXT5 with an RGTC1 alpha texture) to an
 *   {@code RgbaPicture} with alpha;</li>
 *   <li>HAP Alpha Only (RGTC1) to a full-range {@link YuvPicture} of {@link ChromaFormat#MONOCHROME} chroma format
 *   holding the alpha as luma;</li>
 *   <li>HAP HDR (BC6U or BC6S) to a {@link ChannelFloatPicture} of {@link PixelLayout#RGB} layout holding the linear
 *   half-float values.</li>
 * </ul>
 * <p>Scaled YCoCg texels convert as {@code scale = (b >> 3) + 1}, {@code Co = (r - 128) / scale},
 * {@code Cg = (g - 128) / scale} (divisions truncating toward zero) and {@code R = Y + Co - Cg},
 * {@code G = Y + Cg}, {@code B = Y - Co - Cg}, clamped to 0 to 255, where {@code r}, {@code g}, {@code b} and the luma
 * {@code Y} are the decoded DXT5 color and alpha. The output does not depend on the threading.
 *
 * <p>Every packet yields one key frame carrying the packet's timestamps; {@link #flush(Consumer)} has nothing to drain.
 * A corrupt frame raises {@link MediaException.Malformed} and does not affect the next one.
 *
 * <p>Instances are not thread-safe.
 */
public final class HapDecoder implements VideoDecoder {
    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    /**
     * The limits every frame is decoded under.
     */
    private final MediaLimits limits;

    /**
     * The section parser, reused from frame to frame.
     */
    private final HapSections sections = new HapSections();

    /**
     * The texture buffers and block decoders, reused from frame to frame.
     */
    private final HapTextures textures = new HapTextures();

    /**
     * The operation the calls of this decoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Creates a decoder for frames that come with no track, under the {@link VideoDecoderOptions#defaults()}; only
     * {@link #decodeFrame(byte[], int, int, int, int)} can be used.
     */
    public HapDecoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for frames that come with no track; only {@link #decodeFrame(byte[], int, int, int, int)} can
     * be used.
     *
     * @param options the limits checked against every frame before allocating; the thread count and the bit depth
     * setting are ignored
     */
    public HapDecoder(VideoDecoderOptions options) {
        this.track = null;
        this.limits = Objects.requireNonNull(options, "options").limits();
    }

    /**
     * Creates a decoder for a HAP track.
     *
     * @param track the track description; its codec must be {@link Codec#HAP} and it must declare its picture size
     * @param options the limits checked against every frame before allocating; the thread count and the bit depth
     * setting are ignored
     * @throws IllegalArgumentException if the track is not a HAP track
     * @throws MediaException.Malformed if the track does not declare its picture size
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public HapDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.HAP) {
            throw new IllegalArgumentException("not a hap track: " + track.codec());
        }
        if (track.width() <= 0 || track.height() <= 0) {
            throw new MediaException.Malformed("a HAP track needs its picture size");
        }
        LimitChecks.checkPicture(options.limits(), track.width(), track.height());
        this.track = track;
        this.limits = options.limits();
    }

    /**
     * Returns the track being decoded.
     *
     * @return the track description, or {@code null} for a decoder of frames without a track
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Decodes one packet holding one HAP frame of the track's picture size.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps
     * @throws IllegalStateException if the decoder has no track
     * @throws MediaException.Malformed if the frame's sections are invalid or its textures do not match the picture
     * size
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        if (track == null) {
            throw new IllegalStateException("a decoder without a track decodes frames of a given size only");
        }
        var data = packet.data();
        var picture = decodeFrame(data, 0, data.length, track.width(), track.height());
        output.accept(new VideoFrame(picture, packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, true));
    }

    /**
     * Decodes one HAP frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @param width the picture width, positive
     * @param height the picture height, positive
     * @return the decoded picture, as the class description states; it shares no state with the input or the
     * decoder
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame's sections are invalid or its textures do not match the picture
     * size
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length, int width, int height) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(limits, width, height);
        try (var _ = ParallelBands.operation(operation)) {
            return Guards.malformed("HAP frame", () -> decodeSections(data, offset, length, width, height));
        }
    }

    /**
     * Parses and decodes a frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @param width the picture width
     * @param height the picture height
     * @return the picture
     */
    private Picture decodeSections(byte[] data, int offset, int length, int width, int height) {
        sections.parse(data, offset, length);
        var first = sections.texture(0);
        if (sections.count() == 2) {
            var second = sections.texture(1);
            var color = first.format == HapSections.YCOCG_DXT5 ? first : second;
            var alpha = color == first ? second : first;
            if (color.format != HapSections.YCOCG_DXT5 || alpha.format != HapSections.ALPHA_RGTC1) {
                throw new MediaException.Malformed("a HAP frame of two images combines texture formats other than "
                        + "scaled YCoCg and alpha");
            }
            var colorData = textures.expand(data, color, 0, width, height, limits);
            var alphaData = textures.expand(data, alpha, 1, width, height, limits);
            var picture = RgbaPicture.allocate(width, height, true);
            textures.decodeColor(HapSections.YCOCG_DXT5, colorData, alphaData, picture.pixels(), width, height);
            return picture;
        }
        var texture = textures.expand(data, first, 0, width, height, limits);
        return switch (first.format) {
            case HapSections.ALPHA_RGTC1 -> {
                LimitChecks.checkAllocation(limits, (long) width * height);
                var plane = new byte[width * height];
                textures.decodeSingle(texture, plane, width, height);
                yield YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME)
                        .color(ColorInfo.JPEG).luma(plane, width).build();
            }
            case HapSections.RGB_BPTC_UNSIGNED, HapSections.RGB_BPTC_SIGNED -> {
                var picture = ChannelFloatPicture.allocate(width, height, PixelLayout.RGB);
                textures.decodeFloat(texture, first.format == HapSections.RGB_BPTC_SIGNED, picture.pixels(), width,
                        height);
                yield picture;
            }
            default -> {
                var picture = RgbaPicture.allocate(width, height, first.format != HapSections.RGB_DXT1
                        && first.format != HapSections.YCOCG_DXT5);
                textures.decodeColor(first.format, texture, null, picture.pixels(), width, height);
                yield picture;
            }
        };
    }

    /**
     * Does nothing: HAP frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
