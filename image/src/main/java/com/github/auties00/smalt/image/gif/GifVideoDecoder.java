package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.codec.IntermediateFrames;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
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
 * Decodes GIF video ({@link Codec#GIF}), as found in QuickTime files ({@code gif }) and Matroska, where the packets
 * hold the blocks of a GIF data stream one frame at a time.
 *
 * <p>A packet that starts with the GIF header (signature, logical screen descriptor and global color table) starts
 * the picture afresh: the canvas, as large as its logical screen, is cleared to transparent. A packet without one
 * continues the stream under the last header seen, or, before any, under a header of the track's picture size
 * without a global color table. Every image of a packet is composited onto the canvas as {@link GifDecoder}
 * composites the frames of an animation (disposal methods, transparent index, interlacing and clipping), and the
 * packet decodes to a copy of the canvas, an {@link RgbaPicture} with alpha; a packet without an image repeats the
 * canvas. The frame is a key frame when the packet starts with a header.
 *
 * <p>A damaged image raises {@link MediaException.Malformed} after drawing what it decoded, and the next packet
 * continues from that canvas. {@link #flush(Consumer)} has nothing to drain.
 *
 * <p>Instances are not thread-safe.
 */
public final class GifVideoDecoder implements VideoDecoder {
    /**
     * The length of the signature and logical screen descriptor.
     */
    private static final int SCREEN_LENGTH = 13;

    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    /**
     * The limits every frame is decoded under.
     */
    private final MediaLimits limits;

    /**
     * The image being decoded, reused from packet to packet.
     */
    private final GifFrame frame = new GifFrame();

    /**
     * The header of the stream: signature, logical screen descriptor and global color table, or {@code null}
     * before the first one.
     */
    private byte[] header;

    /**
     * The canvas, or {@code null} before the first frame.
     */
    private GifCanvas canvas;

    /**
     * The buffer a packet without a header is copied into after the stream's header, reused from packet to packet.
     */
    private byte[] scratch = new byte[0];

    /**
     * Creates a decoder for frames that come with no track, under the {@link VideoDecoderOptions#defaults()}.
     */
    public GifVideoDecoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for frames that come with no track.
     *
     * @param options the limits checked against every frame before allocating; the thread count and the bit depth
     * setting are ignored
     */
    public GifVideoDecoder(VideoDecoderOptions options) {
        this.track = null;
        this.limits = Objects.requireNonNull(options, "options").limits();
    }

    /**
     * Creates a decoder for a GIF video track.
     *
     * @param track the track description; its codec must be {@link Codec#GIF}
     * @param options the limits checked against every frame before allocating; the thread count and the bit depth
     * setting are ignored
     * @throws IllegalArgumentException if the track is not a GIF video track
     */
    public GifVideoDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        if (track.codec() != Codec.GIF) {
            throw new IllegalArgumentException("not a gif track: " + track.codec());
        }
        this.track = track;
        this.limits = Objects.requireNonNull(options, "options").limits();
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
     * Decodes one packet.
     *
     * @param packet the next packet
     * @param output receives the frame, carrying the packet's timestamps, a key frame when the packet starts with a
     * header
     * @throws MediaException.Malformed if the packet is not part of a GIF data stream, or a packet without a header
     * comes first in a stream whose picture size is unknown
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var picture = decodeFrame(data, 0, data.length);
        output.accept(new VideoFrame(picture, packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION,
                IntermediateFrames.isGifKey(data, 0, data.length)));
    }

    /**
     * Decodes the blocks of one frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return a copy of the canvas after the frame is drawn
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is not part of a GIF data stream, or a frame without a header
     * comes first in a stream whose picture size is unknown
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public RgbaPicture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        return Guards.malformed("GIF frame", () -> {
            MediaSource source;
            if (IntermediateFrames.isGifKey(data, offset, length)) {
                if (length < SCREEN_LENGTH) {
                    throw new MediaException.Malformed("the GIF header is truncated");
                }
                var packed = data[offset + 10] & 0xFF;
                var headerLength = SCREEN_LENGTH + ((packed & 0x80) != 0 ? 3 * (2 << (packed & 7)) : 0);
                if (length < headerLength) {
                    throw new MediaException.Malformed("the GIF global color table is truncated");
                }
                header = new byte[headerLength];
                System.arraycopy(data, offset, header, 0, headerLength);
                canvas = null;
                source = MediaSource.of(data, offset, length);
            } else {
                if (header == null) {
                    header = syntheticHeader();
                }
                var total = header.length + length;
                if (scratch.length < total) {
                    scratch = new byte[Math.max(total, scratch.length + (scratch.length >> 1))];
                }
                System.arraycopy(header, 0, scratch, 0, header.length);
                System.arraycopy(data, offset, scratch, header.length, length);
                source = MediaSource.of(scratch, 0, total);
            }
            var reader = new GifReader(source, limits);
            reader.readHeader();
            if (canvas == null) {
                var width = reader.screenWidth();
                var height = reader.screenHeight();
                if (width <= 0 || height <= 0) {
                    throw new MediaException.Malformed("the GIF logical screen is empty");
                }
                LimitChecks.checkPicture(limits, width, height);
                canvas = new GifCanvas(width, height, true, limits);
            }
            while (reader.nextFrame(frame, true)) {
                canvas.draw(frame);
            }
            var picture = canvas.picture();
            return RgbaPicture.of(picture.width(), picture.height(), picture.stride(), picture.pixels().clone(), true);
        });
    }

    /**
     * Returns a GIF header of the track's picture size without a global color table.
     *
     * @return the header
     * @throws MediaException.Malformed if the picture size is unknown
     */
    private byte[] syntheticHeader() {
        var width = track == null ? 0 : track.width();
        var height = track == null ? 0 : track.height();
        if (width <= 0 || height <= 0 || width > 0xFFFF || height > 0xFFFF) {
            throw new MediaException.Malformed("the first GIF frame has no header and the picture size is unknown");
        }
        return new byte[]{'G', 'I', 'F', '8', '9', 'a', (byte) width, (byte) (width >>> 8), (byte) height,
                (byte) (height >>> 8), 0, 0, 0};
    }

    /**
     * Does nothing: GIF frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
