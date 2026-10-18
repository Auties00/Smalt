package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.internal.IntraDecoderCore;
import com.github.auties00.smalt.video.prores.internal.ProResFrameDecoder;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Apple ProRes video, as found in QuickTime ({@code apco}, {@code apcs}, {@code apcn},
 * {@code apch}, {@code ap4h} and {@code ap4x} sample entries) and Matroska ({@code V_PRORES})
 * files, following SMPTE RDD 36.
 *
 * <p>Every flavor decodes: 422 Proxy, 422 LT, 422, 422 HQ, 4444 and 4444 XQ, at any frame size,
 * progressive or interlaced (the two field pictures are woven into one frame), with or without an
 * 8 or 16-bit alpha channel. ProRes frames are independent, so every packet yields one key frame
 * carrying the packet's timestamps and {@link #flush(Consumer)} has nothing to drain unless
 * {@link ProResDecoderOptions#threads()} asks for several frames at once. By default
 * ({@link ProResDecoderOptions#defaults()}) 4:2:2 frames decode to 10-bit and 4:4:4 frames to
 * 12-bit {@link com.github.auties00.smalt.image.Yuv16Picture}s in limited range, with the color
 * description of the frame header; with {@link ProResDecoderOptions#highBitDepth()} off
 * they decode to 8-bit {@link com.github.auties00.smalt.image.YuvPicture}s. The alpha channel
 * becomes the picture's alpha plane, at the picture's depth. The planes are padded to whole
 * macroblocks, so their strides may exceed the width.
 *
 * <p>Instances are not thread-safe. Large frames are decoded in parallel slice rows on the
 * module's shared helper threads unless {@link ProResDecoderOptions#threads()} is {@code 1}. With
 * more than one thread, up to that many frames are decoded at once, and a frame reaches the
 * consumer during the {@link #decode(Packet, Consumer)} or {@link #flush(Consumer)} call after it
 * finished, which may follow the call that received its packet, in decoding order; a corrupt frame
 * is reported by that call, and {@link #close()} waits for the frames still being decoded. The
 * packets' arrays must not change until their frames are handed out.
 * {@link #decodeFrame(byte[], int, int)} always returns its frame at once. The pictures do not
 * depend on the threading. A corrupt frame raises {@link MediaException.Malformed} and does not
 * affect the next one.
 *
 * @implNote This implementation follows RDD 36 directly; the inverse transform is the
 * single-precision orthonormal DCT, which meets the accuracy qualification of RDD 36 Annex A. The
 * specification does not fix the inverse transform bit for bit, so the samples may differ from
 * those of another conforming decoder by one code value.
 */
public final class ProResDecoder implements VideoDecoder {
    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    private final ProResDecoderOptions options;

    /**
     * The calls of this decoder.
     */
    private final IntraDecoderCore core;

    /**
     * Constructs a decoder for frames that come with no track, under the
     * {@link ProResDecoderOptions#defaults()}.
     */
    public ProResDecoder() {
        this(ProResDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for frames that come with no track.
     *
     * @param options the decoding options
     */
    public ProResDecoder(ProResDecoderOptions options) {
        this.track = null;
        this.options = Objects.requireNonNull(options, "options");
        this.core = core(options);
    }

    /**
     * Constructs a decoder for a ProRes track under the {@link ProResDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#PRORES}
     * @throws IllegalArgumentException if the track is not a ProRes track
     */
    public ProResDecoder(VideoTrack track) {
        this(track, ProResDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a ProRes track.
     *
     * @param track the track description; its codec must be {@link Codec#PRORES}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a ProRes track
     */
    public ProResDecoder(VideoTrack track, ProResDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.PRORES) {
            throw new IllegalArgumentException("not a ProRes track: " + track.codec());
        }
        this.track = track;
        this.options = options;
        this.core = core(options);
    }

    /**
     * Creates the decoding core for a set of options.
     *
     * @param options the decoding options
     * @return the core
     */
    private static IntraDecoderCore core(ProResDecoderOptions options) {
        return new IntraDecoderCore("ProRes", options.threads(),
                threads -> new ProResFrameDecoder(options.limits(), threads, options.highBitDepth()));
    }

    /**
     * {@return the track being decoded, or {@code null} for a decoder of frames without a track}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the decoding options}
     */
    public ProResDecoderOptions options() {
        return options;
    }

    /**
     * Decodes one packet holding one ProRes frame.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps, valid only
     * during the call
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if the frame uses a bitstream version newer than 1
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        core.decode(packet, output);
    }

    /**
     * Decodes one ProRes frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's size field
     * @param length the number of bytes of the frame
     * @return the decoded picture, whose planes the next call of this decoder may overwrite
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if the frame uses a bitstream version newer than 1
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        return core.decodeFrame(data, offset, length);
    }

    /**
     * Hands out the frames still being decoded concurrently; there are none unless the options ask
     * for several threads.
     *
     * @param output receives the frames, valid only during the call
     * @throws MediaException.Malformed if a frame is corrupt
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        core.flush(output);
    }

    /**
     * Waits for the frames still being decoded concurrently and drops them.
     */
    @Override
    public void close() {
        core.close();
    }
}
