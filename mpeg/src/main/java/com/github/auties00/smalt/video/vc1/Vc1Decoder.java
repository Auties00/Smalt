package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vc1.internal.Vc1DecoderEngine;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes a VC-1 (SMPTE ST 421) track: the {@link Codec#VC1} streams of Windows Media (WMV3, WVC1), Matroska, AVI,
 * MP4 and MPEG transport stream files, Blu-ray discs included.
 *
 * <p>The decoder implements the simple, main and advanced profiles at every level: I, BI, P, B and skipped pictures;
 * 1-MV and mixed-MV (4-MV) P pictures with hybrid motion vector prediction; quarter sample bicubic and half sample
 * bilinear motion compensation; intensity compensation; direct, interpolated, forward and backward B macroblocks; all
 * bitplane coding modes; DC and AC prediction with the coefficient scaling of the advanced profile; the uniform and
 * non-uniform quantizers with HALFQP and every macroblock quantizer profile; the 8x8, 8x4, 4x8 and 4x4 transforms;
 * overlap smoothing, conditional overlap and the in-loop deblocking filter; slices; sync markers; range reduction and
 * range mapping; and multi-resolution coding. The advanced profile's interlace coding is complete too: interlace field
 * pictures (two fields of any type pair, one and two reference fields, the second field predicted from the first,
 * per-field intensity compensation, field B pictures with their four reference fields) and interlace frame pictures
 * (frame and field transform macroblocks, 1-MV, 2 field MV, 4 frame MV and 4 field MV macroblocks, field motion
 * compensation, field-based loop filtering), mixed freely with progressive pictures. Output samples follow the decoding
 * process of the standard exactly, the inverse transform included.
 *
 * <p>A simple or main profile track needs its STRUCT_C (SMPTE ST 421 Annex J) in {@link VideoTrack#codecConfig()}, as
 * the 4 bytes themselves or inside the 36-byte sequence layer of Annex L, and its picture size in the track; each
 * packet is one frame, and a packet of at most one byte is a skipped frame. An advanced profile track may carry its
 * sequence header and entry point in the configuration; packets hold the bitstream data units of one or more
 * pictures with their start codes, headers included. A packet whose data does not start with a start code is taken
 * as a frame unit without its start code. Pictures before the first sequence header and entry point are skipped.
 *
 * <p>Frames reach the consumer in display order as 4:2:0 {@link YuvPicture}s of the coded picture size (the entry
 * point's {@code CODED_WIDTH} and {@code CODED_HEIGHT} in the advanced profile, the track size otherwise), in limited
 * range, with the color description of the sequence header's display extension and the defaults of SMPTE ST 421
 * section 6.1.14.5 (BT.709 primaries and transfer, BT.601 matrix) where it is absent. Range reduction and range
 * mapping are applied to the output only. A multi-resolution frame coded at half width or height is up-sampled to the
 * full size. A frame is a keyframe when it is an I picture. Advanced profile frames carry their field order and
 * repeated fields in {@link FrameSideData}.
 *
 * <p>Each frame takes the smallest timestamp among the packets fed and not yet assigned; a frame without such a
 * timestamp, or whose timestamp does not follow the previous frame's, takes the previous one plus the frame duration
 * (from the sequence header's frame rate, or the track's), at least one tick.
 *
 * <p>Interlaced frames are output as frames whose two fields are interleaved, the top field on the even rows; the
 * field order is in {@link FrameSideData}. A sequence header declaring a color-difference format other than 4:2:0,
 * a STRUCT_C of the Windows Media Video 9 complex profile (profile 2, which SMPTE ST 421 reserves), and a STRUCT_C
 * whose Reserved6 bit is 0 (written by pre-release Windows Media Video 9 encoders, which SMPTE ST 421 Annex J
 * forbids) raise {@link MediaException.Unsupported}. A corrupt picture raises {@link MediaException.Malformed}
 * after the frames due were handed out, and its frame is still output with the macroblocks decoded until the error;
 * P pictures without a reference frame, and B pictures without both anchors or after a broken link, produce no
 * frame.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation up-samples multi-resolution frames with the bilinear filter of weights 1/4 and 3/4,
 * which SMPTE ST 421 Annex B leaves to the implementation. It buffers the motion vectors of main profile P pictures
 * for direct mode without the pull-back of Figure 68, and predicts the backward motion vectors of the second field of
 * a B field picture with the scaling of Table 113 at the backward reference frame distance (the text says only "as
 * in P field pictures"), as the encoders of the reference streams do: both reconstruct those streams exactly.
 */
public final class Vc1Decoder implements VideoDecoder {
    /**
     * The decoding engine.
     */
    private final Vc1DecoderEngine engine;

    /**
     * The options of this decoder.
     */
    private final VideoDecoderOptions options;

    /**
     * The consumer of the call in progress, or {@code null} outside {@link #decode} and {@link #flush}.
     */
    private ConsumerFailure<VideoFrame> output;

    /**
     * Whether {@link #close()} was called.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a track under {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track to decode
     * @throws IllegalArgumentException if the track is not a VC-1 track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration declares a color-difference format other than 4:2:0,
     * the Windows Media Video 9 complex profile, or the bitstream of a pre-release Windows Media Video 9 encoder
     * @throws MediaException.LimitExceeded if the picture size exceeds the default limits
     */
    public Vc1Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * <p>The streams are 8-bit, so {@link VideoDecoderOptions#highBitDepth()} changes nothing, and the decoder runs
     * on the calling thread whatever {@link VideoDecoderOptions#threads()} says.
     *
     * @param track the track to decode
     * @param options the decoder settings
     * @throws IllegalArgumentException if the track is not a VC-1 track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration declares a color-difference format other than 4:2:0,
     * the Windows Media Video 9 complex profile, or the bitstream of a pre-release Windows Media Video 9 encoder
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public Vc1Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.VC1) {
            throw new IllegalArgumentException(track.codec() + " is not a VC-1 track");
        }
        this.engine = new Vc1DecoderEngine(options.limits(), track.timeBase(), track.frameRate().orElse(null),
                track.width(), track.height(), this::emit);
        var config = track.codecConfig().orElse(null);
        if (config != null && config.length > 0) {
            try {
                engine.configure(config);
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("corrupt VC-1 decoder configuration", exception);
            }
        }
    }

    /**
     * Returns the options of this decoder.
     *
     * @return the options
     */
    public VideoDecoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet is corrupt, after the frames due were handed out
     * @throws MediaException.Unsupported if a sequence header declares a color-difference format other than 4:2:0
     * @throws MediaException.LimitExceeded if a sequence header's picture size exceeds the limits
     * @throws IllegalStateException if the decoder is closed
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        ensureOpen();
        var handoff = ConsumerFailure.wrap(output);
        this.output = handoff;
        try {
            engine.decode(packet.data(), packet.pts());
        } catch (RuntimeException exception) {
            throw handoff.translate("VC-1 packet", exception);
        } finally {
            this.output = null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The reference frames are dropped, so that the next packet decodes as after a seek.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
        ensureOpen();
        var handoff = ConsumerFailure.wrap(output);
        this.output = handoff;
        try {
            engine.flush();
        } catch (RuntimeException exception) {
            throw handoff.translate("VC-1 stream", exception);
        } finally {
            this.output = null;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            engine.close();
        }
    }

    /**
     * Checks that the decoder is open.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("decoder is closed");
        }
    }

    /**
     * Hands a frame of the engine to the consumer of the call in progress.
     *
     * @param picture the decoded picture
     * @param pts the presentation timestamp
     * @param duration the duration in time base ticks, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the frame is an I picture
     * @param sideData the field order and repeated fields
     */
    private void emit(YuvPicture picture, long pts, long duration, boolean keyframe, FrameSideData sideData) {
        var consumer = output;
        if (consumer != null) {
            try {
                consumer.accept(new VideoFrame(picture, pts, duration, keyframe, sideData));
            } catch (RuntimeException exception) {
                output = null;
                throw exception;
            }
        }
    }
}
