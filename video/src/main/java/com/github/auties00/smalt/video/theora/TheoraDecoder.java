package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.theora.internal.TheoraFrameDecoder;
import com.github.auties00.smalt.video.theora.internal.TheoraSetup;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Theora video (the Theora I specification, bitstream versions 3.0 to 3.2), as Ogg ({@code .ogv}) and
 * Matroska ({@code V_THEORA}) files carry it.
 *
 * <p>The track's configuration holds the three headers, Xiph-laced: the identification header gives the coded
 * frame and picture sizes and the pixel format, the setup header the loop filter limits, the quantization matrices
 * and the 80 Huffman codes; the comment header is not read. Every coding tool decodes: intra and inter frames, the
 * eight macro block modes with the golden and previous reference frames, half- and quarter-pixel motion vectors
 * reaching outside the frame, up to three quality indices per frame, DC prediction and the loop filter. The output
 * is bit-exact with libtheora 1.2.0 with its post-processing off (its default).
 *
 * <p>The pictures are {@link YuvPicture}s of the picture region, 4:2:0, 4:2:2 or 4:4:4 as the stream codes them,
 * 8 bits, with the track's color description, or BT.601 limited range when it has none (Theora's color spaces
 * both use the BT.601 matrix).
 *
 * <p>Each data packet yields one frame carrying the packet's timestamps, marked as a key frame when it is an intra
 * frame. An empty packet, and an inter frame that codes no block, repeat the previous picture; header packets in
 * the data are skipped. An inter frame before the first key frame predicts from mid-gray reference frames, as
 * libtheora does. A packet that is not a data packet raises {@link MediaException.Malformed}; a damaged or truncated
 * frame decodes as libtheora decodes it, the bits past its end reading as zeros.
 *
 * <p>Instances are not thread-safe.
 */
public final class TheoraDecoder implements VideoDecoder {
    private final VideoTrack track;
    private final TheoraSetup setup;
    private final TheoraFrameDecoder frames;

    /**
     * Constructs a decoder for a Theora track with the {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#THEORA}
     * @throws IllegalArgumentException if the track is not a Theora track
     * @throws MediaException.Malformed if the track's configuration is missing or holds invalid headers
     * @throws MediaException.Unsupported if the stream is of a newer bitstream version
     * @throws MediaException.LimitExceeded if the frame size exceeds the limits
     */
    public TheoraDecoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a Theora track.
     *
     * <p>The options' limits apply.
     *
     * @param track the track; its codec must be {@link Codec#THEORA}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a Theora track
     * @throws MediaException.Malformed if the track's configuration is missing or holds invalid headers
     * @throws MediaException.Unsupported if the stream is of a newer bitstream version
     * @throws MediaException.LimitExceeded if the frame size exceeds the limits
     */
    public TheoraDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.THEORA) {
            throw new IllegalArgumentException("not a Theora track: " + track.codec());
        }
        this.track = track;
        var config = track.codecConfig()
                .orElseThrow(() -> new MediaException.Malformed("Theora track without its headers"));
        byte[][] headers;
        try {
            headers = XiphLacing.unlace(config);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Theora header lacing", exception);
        }
        if (headers.length != 3) {
            throw new MediaException.Malformed("Theora configuration without three headers");
        }
        this.setup = new TheoraSetup(headers[0], headers[2]);
        this.frames = new TheoraFrameDecoder(setup, track.color().orElse(ColorInfo.BT601_LIMITED),
                options.limits());
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the width of the coded frame, a multiple of 16 holding the picture}
     */
    public int frameWidth() {
        return setup.frameWidth;
    }

    /**
     * {@return the height of the coded frame, a multiple of 16 holding the picture}
     */
    public int frameHeight() {
        return setup.frameHeight;
    }

    /**
     * Decodes one packet holding one Theora frame.
     *
     * @param packet the next packet
     * @param output receives the frame, carrying the packet's timestamps, unless the packet is a header packet;
     *               its picture is valid only during the call
     * @throws MediaException.Unsupported if a key frame sets the reserved header bits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        if (data.length > 0 && (data[0] & 0x80) != 0) {
            return;
        }
        var picture = frames.decode(data, 0, data.length);
        output.accept(new VideoFrame(picture, packet.pts(), packet.duration(), frames.keyframe()));
    }

    /**
     * Decodes one Theora data packet held in part of an array.
     *
     * @param data the array holding the packet
     * @param offset the index of the packet's first byte
     * @param length the number of bytes of the packet
     * @return the picture, which the next call of this decoder may overwrite
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the packet is not a data packet
     * @throws MediaException.Unsupported if a key frame sets the reserved header bits
     */
    public YuvPicture decodeFrame(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        return frames.decode(data, offset, length);
    }

    /**
     * Does nothing: every frame is output by the call that decodes it.
     *
     * @param output unused
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}
