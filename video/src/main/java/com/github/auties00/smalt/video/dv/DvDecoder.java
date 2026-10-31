package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dv.internal.DvFrameDecoder;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes DV video: the 25 Mb/s DV of IEC 61834-2 (525/60 4:1:1 and 625/50 4:2:0), DVCPRO and DVCPRO50 (SMPTE
 * ST 314, 4:1:1 at 25 Mb/s and 4:2:2 at 50 Mb/s) and DVCPRO HD (SMPTE ST 370, 1080/60i, 1080/50i, 720/60p and
 * 720/50p), every {@link DvProfile}.
 *
 * <p>A packet holds one DIF frame; its format is read from the frame's header block and VAUX source pack, so one
 * decoder follows a stream whose format changes. The pictures are
 * {@link com.github.auties00.smalt.image.YuvPicture}s of the coded size, 8 bits, limited range: BT.601 for the SD
 * formats and BT.709 for DVCPRO HD, 4:2:0 for 625/50 DV and 4:2:2 otherwise, 4:1:1 chroma repeated to 4:2:2,
 * each coded chroma sample filling two output samples side by side. Every frame is a key frame carrying the
 * packet's timestamps and its field order as side data: bottom field first for the SD formats, top field first
 * for 1080-line DVCPRO HD, progressive for 720-line DVCPRO HD. A packet holding the two video frames of a
 * four-channel 720-line DIF frame yields both, the second half a frame duration later.
 *
 * <p>The video segments of a frame are independent; a segment whose data ends early keeps the coefficients read,
 * as SMPTE ST 314 clause 5.6 prescribes, and the error status of a compressed macroblock is not acted on. A packet
 * that is not a DIF frame, or is shorter than its format's frame, raises {@link MediaException.Malformed}.
 *
 * <p>Instances are not thread-safe. The segments of a frame are decoded on the module's shared helper threads
 * unless the options ask for one thread; the pictures do not depend on the threading.
 *
 * @implNote This implementation computes the inverse DCTs in single precision, so samples may differ by one code
 * value from those of a fixed-point decoder.
 */
public final class DvDecoder implements VideoDecoder {
    private final VideoTrack track;
    private final DvFrameDecoder frames;

    /**
     * Constructs a decoder for frames that come with no track, with the {@link VideoDecoderOptions#defaults()}.
     */
    public DvDecoder() {
        this(VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for frames that come with no track.
     *
     * <p>The options' thread count and limits apply.
     *
     * @param options the decoding options
     */
    public DvDecoder(VideoDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        this.track = null;
        this.frames = new DvFrameDecoder(options.limits(), options.threads());
    }

    /**
     * Constructs a decoder for a DV track with the {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#DV}
     * @throws IllegalArgumentException if the track is not a DV track
     */
    public DvDecoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a DV track.
     *
     * <p>The options' thread count and limits apply.
     *
     * @param track the track; its codec must be {@link Codec#DV}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a DV track
     */
    public DvDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.DV) {
            throw new IllegalArgumentException("not a DV track: " + track.codec());
        }
        this.track = track;
        this.frames = new DvFrameDecoder(options.limits(), options.threads());
    }

    /**
     * {@return the track being decoded, or {@code null} for a decoder of frames without a track}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Decodes one packet holding one DIF frame.
     *
     * @param packet the next packet
     * @param output receives the frame, or the two frames of a four-channel 720-line packet; their pictures are
     * valid only during the call
     * @throws MediaException.Malformed if the packet is not a DIF frame or is truncated
     * @throws MediaException.LimitExceeded if the picture exceeds the limits of the options
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var duration = packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION;
        try (var _ = ParallelBands.operation()) {
            var picture = decodeChecked(data, 0, data.length);
            var profile = frames.profile();
            var side = FrameSideData.builder().fieldOrder(fieldOrder(profile)).build();
            var size = profile.frameSize();
            if (profile.progressive() && data.length >= 2 * size
                    && DifProfile.detect(data, size, data.length - size) == profile) {
                var half = duration == Track.UNKNOWN_DURATION ? Track.UNKNOWN_DURATION : duration / 2;
                output.accept(new VideoFrame(picture, packet.pts(), half, true, side));
                var second = decodeChecked(data, size, data.length - size, 1);
                var pts = half == Track.UNKNOWN_DURATION ? packet.pts() : packet.pts() + half;
                output.accept(new VideoFrame(second, pts, half, true, side));
                return;
            }
            output.accept(new VideoFrame(picture, packet.pts(), duration, true, side));
        }
    }

    /**
     * Decodes one DIF frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the picture, whose planes the next decoding call of this decoder overwrites
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the range does not hold a DIF frame or is truncated
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation()) {
            return decodeChecked(data, offset, length);
        }
    }

    /**
     * Does nothing: every frame is handed out by the {@link #decode(Packet, Consumer)} call that receives it.
     *
     * @param output not called
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Decodes a frame, reporting an unexpected failure on corrupt data as malformed input.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes available
     * @return the picture
     */
    private Picture decodeChecked(byte[] data, int offset, int length) {
        return decodeChecked(data, offset, length, 0);
    }

    /**
     * Decodes a frame into one of the decoder's two pictures, reporting an unexpected failure on corrupt data as
     * malformed input.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes available
     * @param slot the picture to decode into, 0 or 1
     * @return the picture
     */
    private Picture decodeChecked(byte[] data, int offset, int length, int slot) {
        try {
            return frames.decode(data, offset, length, slot);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid DV frame", exception);
        }
    }

    /**
     * Returns the field order of the frames of a format.
     *
     * @param profile the format
     * @return bottom first for SD, top first for 1080-line DVCPRO HD, progressive for 720-line
     */
    private static FieldOrder fieldOrder(DifProfile profile) {
        if (profile.progressive()) {
            return FieldOrder.PROGRESSIVE;
        }
        return profile.highDefinition() ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST;
    }
}
