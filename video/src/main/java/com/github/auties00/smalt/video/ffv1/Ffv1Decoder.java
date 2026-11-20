package com.github.auties00.smalt.video.ffv1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.ffv1.internal.Ffv1FrameDecoder;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Decodes FFV1 video (RFC 9043), versions 0, 1 and 3, as Matroska ({@code V_FFV1}), AVI ({@code FFV1}) and MP4
 * files carry it.
 *
 * <p>Every coding tool of RFC 9043 decodes: the Golomb Rice coder with its run mode, the range coder with the
 * default or a custom state transition table and coded initial states, any number of quantization table sets,
 * slices (version 3) with or without CRCs, frames that continue the context states of the previous frame, and
 * every bit depth from 1 to 16. The frame size is the track's; versions 0 and 1 carry their parameters in key
 * frames, version 3 in the track's configuration record.
 *
 * <p>The pictures are:
 * <ul>
 *   <li>Y'CbCr: a {@link com.github.auties00.smalt.image.YuvPicture}, or with
 *       {@link VideoDecoderOptions#highBitDepth()} a {@link com.github.auties00.smalt.image.Yuv16Picture} of the
 *       stream's depth when it is deeper than 8 bits, with an alpha plane when the stream has a transparency
 *       plane, 4:2:0, 4:2:2, 4:4:0, 4:4:4 or monochrome as the stream codes it. Chroma subsampled by 4 (4:1:1 and
 *       4:1:0) is repeated to 4:2:2 or 4:2:0, each coded chroma sample filling two output samples side by side.
 *       Shallower samples are scaled to 8 bits; the color description is the track's, or BT.601 limited range
 *       when it has none.</li>
 *   <li>RGB (the JPEG 2000 reversible color transform): an {@link com.github.auties00.smalt.image.RgbaPicture},
 *       or with {@code highBitDepth} an {@link com.github.auties00.smalt.image.Rgba16Picture} when the stream is
 *       deeper than 8 bits, each sample {@code v} of depth {@code d} scaled to {@code round(v * 65535 / (2^d -
 *       1))}, opaque without a transparency plane.</li>
 * </ul>
 * Samples deeper than 8 bits are otherwise reduced as {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit
 * ImageOps.toEightBit} reduces them. At the stream's own depth the samples are exactly those the encoder coded:
 * FFV1 is lossless.
 *
 * <p>Each packet yields one frame carrying the packet's timestamps, marked as a key frame when the frame resets
 * the context states; the field order a version 3 frame declares is its side data, and its sample aspect ratio is
 * {@link #sampleAspectRatio()}. A
 * version 3 frame decodes its slices in parallel on the module's shared helper threads unless the options ask
 * for one thread; the pictures do not depend on the threading. A corrupt frame, a frame failing its slice CRCs and
 * a frame that continues the states of a frame that failed raise {@link MediaException.Malformed}; the next key
 * frame decodes normally.
 *
 * <p>Instances are not thread-safe.
 */
public final class Ffv1Decoder implements VideoDecoder {
    private final VideoTrack track;

    private final Ffv1FrameDecoder frames;

    /**
     * Constructs a decoder for an FFV1 track with the {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#FFV1}
     * @throws IllegalArgumentException if the track is not an FFV1 track
     * @throws MediaException.Malformed if the track has no frame size or an invalid configuration record
     * @throws MediaException.Unsupported if the configuration record describes an unsupported stream
     * @throws MediaException.LimitExceeded if the frame size exceeds the limits
     */
    public Ffv1Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an FFV1 track.
     *
     * <p>The options' limits, thread count and {@link VideoDecoderOptions#highBitDepth()} apply.
     *
     * @param track the track; its codec must be {@link Codec#FFV1}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not an FFV1 track
     * @throws MediaException.Malformed if the track has no frame size or an invalid configuration record
     * @throws MediaException.Unsupported if the configuration record describes an unsupported stream
     * @throws MediaException.LimitExceeded if the frame size exceeds the limits
     */
    public Ffv1Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.FFV1) {
            throw new IllegalArgumentException("not an FFV1 track: " + track.codec());
        }
        this.track = track;
        var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        this.frames = new Ffv1FrameDecoder(track.width(), track.height(), config, options.highBitDepth(),
                options.threads(), track.color().orElse(ColorInfo.BT601_LIMITED), options.limits());
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Decodes one packet holding one FFV1 frame.
     *
     * @param packet the next packet
     * @param output receives the frame, carrying the packet's timestamps; its picture is valid only during the call
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if a version 0 or 1 key frame describes an unsupported stream
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        Picture picture;
        try (var ignored = ParallelBands.operation()) {
            picture = frames.decode(packet.data(), 0, packet.data().length);
        }
        var order = frames.fieldOrder();
        var side = order == null ? FrameSideData.NONE : FrameSideData.builder().fieldOrder(order).build();
        output.accept(new VideoFrame(picture, packet.pts(), packet.duration(), frames.keyframe(), side));
    }

    /**
     * Decodes one FFV1 frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the picture, which the next call of this decoder may overwrite
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if a version 0 or 1 key frame describes an unsupported stream
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var ignored = ParallelBands.operation()) {
            return frames.decode(data, offset, length);
        }
    }

    /**
     * {@return the sample aspect ratio the last decoded frame declares, or empty when it declares none}
     *
     * <p>Only version 3 frames declare one, in their slice headers.
     */
    public Optional<Rational> sampleAspectRatio() {
        return Optional.ofNullable(frames.sampleAspect());
    }

    /**
     * {@return the field order the last decoded frame declares, or empty when it declares none}
     */
    public Optional<FieldOrder> fieldOrder() {
        return Optional.ofNullable(frames.fieldOrder());
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
