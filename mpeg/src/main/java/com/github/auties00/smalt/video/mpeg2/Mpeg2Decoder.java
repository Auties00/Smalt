package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg2.internal.Mpeg2DecoderEngine;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes an MPEG-1 video (ISO/IEC 11172-2) or MPEG-2 video (ITU-T H.262, ISO/IEC 13818-2) track: the
 * {@link Codec#MPEG1_VIDEO} and {@link Codec#MPEG2_VIDEO} streams of MPEG transport streams, MP4, QuickTime,
 * Matroska and AVI files.
 *
 * <p>The decoder implements every non-scalable coding tool of both standards, the Simple, Main, High and 4:2:2
 * profiles of MPEG-2 at every level: I-, P- and B-pictures and the D-pictures of MPEG-1; frame pictures and field
 * pictures; frame, field, 16x8 and dual-prime motion compensation; frame and field DCT; 4:2:0, 4:2:2 and 4:4:4
 * chroma; intra DC precisions of 8 to 11 bits; the default and downloaded quantizer matrices of the sequence header
 * and of the quant matrix extension, chroma matrices included; the linear and non-linear quantizer scales; the
 * zigzag and alternate scans; both intra coefficient tables; MPEG-1 whole sample vectors; skipped macroblocks; and
 * concealment motion vectors. A stream whose track says {@link Codec#MPEG1_VIDEO} but carries MPEG-2 headers, or the
 * other way round, decodes as its headers say. Output samples follow the decoding process of the standards exactly,
 * apart from the inverse DCT, whose implementation they leave free within the IEEE 1180 accuracy.
 *
 * <p>Packets carry whole pictures as the elementary stream codes them, with the sequence, group of pictures and
 * extension headers before them; a packet may carry several pictures, and the two field pictures of a frame may come
 * in one packet or in two. {@link VideoTrack#codecConfig()} may hold a sequence header with its extensions (the
 * {@code DecoderSpecificInfo} of {@code esds}, Matroska's {@code CodecPrivate}); headers carried in packets are
 * honored, and every sequence header resets the quantizer matrices as the standards define. Pictures before the
 * first sequence header are skipped, as are slices before the first picture header of a packet.
 *
 * <p>Frames reach the consumer in display order as {@link YuvPicture}s of the
 * sequence's chroma format and of its {@code horizontal_size} by {@code vertical_size}. The two field pictures of a
 * frame are woven into it; a field whose second field never comes is completed by repeating each of its rows. The
 * color description comes from the sequence display extension, with BT.601 for pictures up to 1024 by 576 and
 * BT.709 for larger ones where it leaves components unspecified or is absent, in limited range, and with the chroma
 * siting of the standard (centered for MPEG-1, co-sited to the left for MPEG-2). A frame is a keyframe when its
 * first picture is an I- or D-picture.
 *
 * <p>Each frame takes the smallest timestamp among the packets fed and not yet assigned, counting only the packets
 * that start a new frame (so a packet holding just the second field of a frame adds none); this gives frames their
 * presentation times whether the container stores presentation times (transport streams, MP4, Matroska) or one
 * timestamp per stored frame in decoding order (AVI). A frame without such a timestamp, or whose timestamp does not
 * follow the previous frame's, takes the previous one plus the previous frame's duration (the nominal frame duration
 * when that is unknown), at least one tick. The duration is the frame period of the sequence's
 * {@code frame_rate_code} (or the track's frame rate) times the field periods the frame is shown for, halved. For
 * MPEG-2 each frame's {@link FrameSideData} tells its scan and repeated fields:
 * {@link com.github.auties00.smalt.video.FieldOrder#PROGRESSIVE} for the frames of a progressive sequence,
 * {@code repeat_first_field} counting 2 or, with {@code top_field_first}, 4 repeated fields; in an interlaced
 * sequence {@code PROGRESSIVE} for a progressive frame shown as two fields, otherwise the field order of
 * {@code top_field_first} (of the first field picture's parity for field pictures), with 1 repeated field for a
 * frame whose {@code repeat_first_field} is set. The closed captions of the ATSC user data of a frame's pictures are
 * attached to it, for MPEG-1 too.
 *
 * <p>Unsupported but valid streams raise {@link MediaException.Unsupported} with the feature
 * {@code "mpeg-2 scalability"}: the scalable extensions (data partitioning, SNR, spatial and temporal scalability).
 * Corrupt slices, macroblocks no slice covers and predictions from a missing reference frame are concealed: a
 * concealed macroblock is predicted from the forward reference frame (the previous reference frame for an
 * I-picture) with the vector of the macroblock above it, its forward or concealment motion vector, or with a zero
 * vector, and is mid-gray without a reference. The call that decoded such a picture raises
 * {@link MediaException.Malformed} after the frames due were handed out; decoding continues with the next packet. A
 * corrupt header raises it too, and the picture it introduces is skipped. P-pictures without a reference frame, as
 * after a seek, B-pictures whose forward reference frame is missing (unless they lead a closed group of pictures)
 * and the leading B-pictures of a group of pictures with {@code broken_link} produce no frame.
 *
 * <p>With {@link VideoDecoderOptions#threads()} other than 1 the macroblock rows of each MPEG-2 picture are decoded
 * concurrently on the module's shared pool, at most that many at once (any number for 0). The output never depends
 * on the thread count.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation's inverse DCT is the accurate integer transform of the Independent JPEG Group's
 * software (ported from libjpeg-turbo's {@code jidctint.c}), applied rows first, which FFmpeg selects with
 * {@code -idct int}. An MPEG-2 slice ends at the end of its macroblock row, as ISO/IEC 13818-2 requires;
 * MPEG-1 slices may span rows and are decoded in stream order.
 */
public final class Mpeg2Decoder implements VideoDecoder {
    /**
     * The engine.
     */
    private final Mpeg2DecoderEngine engine;

    /**
     * The decoder options.
     */
    private final VideoDecoderOptions options;

    /**
     * The operation the calls of the decoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The consumer of the call in progress, or {@code null} between calls and once it failed.
     */
    private ConsumerFailure<VideoFrame> output;

    /**
     * Whether the decoder was closed.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a track under {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track to decode
     * @throws IllegalArgumentException if the track is neither an MPEG-1 nor an MPEG-2 video track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration declares a scalable sequence
     * @throws MediaException.LimitExceeded if the configuration's picture size exceeds the default limits
     */
    public Mpeg2Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * <p>The thread count of {@link VideoDecoderOptions#threads()} works as the class description says; the
     * streams are 8-bit, so {@link VideoDecoderOptions#highBitDepth()} changes nothing.
     *
     * @param track the track to decode
     * @param options the decoder settings
     * @throws IllegalArgumentException if the track is neither an MPEG-1 nor an MPEG-2 video track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration declares a scalable sequence
     * @throws MediaException.LimitExceeded if the configuration's picture size exceeds the limits
     */
    public Mpeg2Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.MPEG1_VIDEO && track.codec() != Codec.MPEG2_VIDEO) {
            throw new IllegalArgumentException(track.codec() + " is not an MPEG-1 or MPEG-2 video track");
        }
        var threads = options.threads() == 0 ? ParallelBands.THREADS : options.threads();
        this.engine = new Mpeg2DecoderEngine(options.limits(), threads, track.timeBase(),
                track.frameRate().orElse(null), this::emit);
        var config = track.codecConfig().orElse(null);
        if (config != null && config.length > 0) {
            try {
                engine.configure(config);
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("corrupt MPEG video decoder configuration", exception);
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
     * @throws MediaException.Malformed if a header of the packet is corrupt or a picture it completes was concealed,
     * after the frames due were handed out
     * @throws MediaException.Unsupported if a sequence header of the packet declares a scalable extension
     * @throws MediaException.LimitExceeded if a sequence header's picture size exceeds the limits
     * @throws IllegalStateException if the decoder is closed
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                engine.decode(packet.data(), packet.pts());
            } catch (RuntimeException exception) {
                throw handoff.translate("MPEG video packet", exception);
            } finally {
                this.output = null;
            }
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
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                engine.flush();
            } catch (RuntimeException exception) {
                throw handoff.translate("MPEG video stream", exception);
            } finally {
                this.output = null;
            }
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
     * Rejects calls after {@link #close()}.
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
     * @param picture the picture, valid until the call returns
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration, or {@link Packet#UNKNOWN_DURATION}
     * @param keyframe whether the frame comes from an I- or D-picture
     * @param sideData the display information of the frame
     */
    private void emit(YuvPicture picture, long pts, long duration, boolean keyframe,
                      FrameSideData sideData) {
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
