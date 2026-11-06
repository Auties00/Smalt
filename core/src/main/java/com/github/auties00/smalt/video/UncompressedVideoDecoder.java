package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.heif.HeifComponentPictures;
import com.github.auties00.smalt.internal.heif.HeifUncompressed;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes the packets of a {@link Codec#UNCOMPRESSED_23001_17} track (ISO/IEC 23001-17 uncompressed video), one
 * frame each, into pictures.
 *
 * <p>The track's codec configuration gives the layout and the track its width and height. The layouts read are
 * those of the standard's uncompressed images: every interleaving; 4:4:4, 4:2:2, 4:2:0 and 4:1:1 sampling (row
 * interleaving of 4:2:0 and multi-Y interleaving of 4:2:0 excepted); unsigned components of 1 to 16 bits packed bit
 * after bit, aligned to whole bytes or in blocks of up to 8 bytes, in either byte order; pixel, row and tile padding;
 * equal tiles; the version 1 profiles {@code rgb3}, {@code rgba} and {@code abgr}; and frames compressed whole with
 * {@code defl} or {@code zlib}. Other layouts raise {@link MediaException.Unsupported} when the decoder is
 * created.
 *
 * <p>Monochrome and Y'CbCr components decode to a {@link YuvPicture} at up to 8 bits and to a {@link Yuv16Picture}
 * of the depth of the deepest color component otherwise, with the track's color description or, when it has none,
 * BT.601 limited range, taking the chroma siting of a {@code cloc} box when that description leaves it unspecified;
 * red, green and blue components decode to a 4:4:4 picture of the identity matrix in full range, green in the luma
 * plane and blue and red in the chroma planes; a 4:1:1 frame decodes to 4:4:4 by repeating each chroma sample; an alpha component becomes the alpha
 * plane. A component shallower than the picture is rescaled to its depth, {@code (v * max + m / 2) / m} for a
 * sample {@code v} of largest value {@code m}. Padding components and the components of other types are skipped.
 * Bytes past the frame are ignored; the output depends only on the packet. The picture is reused from frame to
 * frame. Instances are not thread-safe.
 */
public final class UncompressedVideoDecoder implements VideoDecoder {
    /**
     * The format name of messages.
     */
    private static final String NAME = "uncv";

    private final HeifUncompressed frames;

    private final MediaLimits limits;

    /**
     * The picture the frames are decoded into, reused from frame to frame.
     */
    private final Picture picture;

    /**
     * Constructs a decoder for a track.
     *
     * @param track the track, of {@link Codec#UNCOMPRESSED_23001_17} with its size and configuration
     * @param options the limits
     * @throws IllegalArgumentException if the track is not an uncompressed video track
     * @throws MediaException.Malformed if the track has no size, or its configuration is missing, truncated or
     * inconsistent
     * @throws MediaException.Unsupported if the layout uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public UncompressedVideoDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.UNCOMPRESSED_23001_17) {
            throw new IllegalArgumentException("not an uncompressed video track: " + track.codec().displayName());
        }
        var config = track.codecConfig().orElseThrow(
                () -> new MediaException.Malformed("an uncompressed video track needs its codec configuration"));
        if (track.width() <= 0 || track.height() <= 0) {
            throw new MediaException.Malformed("an uncompressed video track needs its picture size");
        }
        this.limits = options.limits();
        this.frames = HeifUncompressed.forTrack(NAME, config, track.width(), track.height(), limits);
        var color = track.color().orElse(ColorInfo.BT601_LIMITED);
        var location = frames.chromaLocation();
        if (color.chromaSiting() == ChromaSiting.UNSPECIFIED && location >= 0 && location <= 5) {
            color = color.withChromaSiting(ChromaSiting.of(location));
        }
        this.picture = HeifComponentPictures.allocate(NAME, frames.components(), color, limits);
    }

    /**
     * Constructs a decoder for a track with {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track, of {@link Codec#UNCOMPRESSED_23001_17} with its size and configuration
     * @throws IllegalArgumentException if the track is not an uncompressed video track
     * @throws MediaException.Malformed if the track has no size, or its configuration is missing, truncated or
     * inconsistent
     * @throws MediaException.Unsupported if the layout uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public UncompressedVideoDecoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet is shorter than its frame, or its compression is corrupt
     * @throws MediaException.LimitExceeded if a compressed frame expands beyond the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var components = frames.decodeFrame(packet.data(), limits);
        HeifComponentPictures.fill(NAME, components, picture);
        output.accept(new VideoFrame(picture, packet.pts(), packet.duration() >= 0 ? packet.duration()
                : Track.UNKNOWN_DURATION, packet.keyframe()));
    }

    /**
     * Holds no frames; does nothing.
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Releases the decompressor of compressed frames; a later compressed frame takes a new one.
     */
    @Override
    public void close() {
        frames.close();
    }
}
