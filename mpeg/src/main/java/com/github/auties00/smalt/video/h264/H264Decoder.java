package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.sei.FilmGrainSynthesizer;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.internal.AuxiliaryAlpha;
import com.github.auties00.smalt.video.h264.internal.H264DecoderEngine;
import com.github.auties00.smalt.video.h264.internal.H264Sps;
import com.github.auties00.smalt.video.h264.internal.H264SpsExtension;
import com.github.auties00.smalt.video.h264.internal.MfcProcessing;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes an ITU-T H.264 / MPEG-4 AVC video track.
 *
 * <p>The decoder implements the Baseline (flexible macroblock ordering and arbitrary slice order
 * included), Extended, Main, High, High 10, High 4:2:2 and High 4:4:4 Predictive profiles (with
 * High 10 Intra, High 4:2:2 Intra, High 4:4:4 Intra and CAVLC 4:4:4 Intra), for progressive and
 * interlaced 4:2:0, 4:2:2, 4:4:4 and 4:0:0 (monochrome) video of 8 to 14 bits, with independent
 * luma and chroma bit depths: CAVLC and CABAC, I, P and B slices, the SP and SI slices of the
 * Extended profile (switching SP slices and SI macroblocks included), slice data partitioning
 * (partitions A, B and C, which may arrive in separate packets and interleave across the slices of
 * a picture), redundant coded pictures (which are skipped), all partition sizes, I_PCM macroblocks,
 * lossless macroblocks ({@code qpprime_y_zero_transform_bypass_flag}), weighted prediction, spatial
 * and temporal direct prediction, the 8x8 transform, scaling matrices, long-term references, every
 * memory management control operation, the deblocking filter, field pictures and
 * macroblock-adaptive frame/field (MBAFF) frames. 4:4:4 streams may code their three color planes
 * together or separately ({@code separate_colour_plane_flag}). Output is bit-exact with the
 * normative decoding process.
 *
 * <p>Streams with more than one layer decode as follows. The alpha auxiliary coded pictures of a
 * stream whose sequence parameter set extension declares alpha become the alpha plane of their
 * frames (see {@link H264DecoderOptions#alpha()}). A multiview stream (the Multiview High and Stereo
 * High profiles of Annex G: inter-view prediction, prefix NAL units, subset sequence parameter sets
 * and the inter-view reference list modifications) outputs its base view by default and any set of
 * its views on request (see {@link H264DecoderOptions#allViews()} and
 * {@link H264DecoderOptions#outputViews()}); a frame-compatible stereo stream (MFC High) also
 * reconstructs its views at full resolution (see {@link H264DecoderOptions#enhancedResolution()}),
 * and a multiview stream with depth (Multiview Depth High and MFC Depth High of Annex H) also
 * outputs its depth views (see {@link H264DecoderOptions#depth()}). A scalable stream (the Scalable
 * Baseline, Scalable High and Scalable High Intra profiles of Annex F: coarse-grain and medium-grain
 * quality layers, dyadic and extended spatial layers with cropping and chroma phase shifts,
 * inter-layer intra, motion and residual prediction, key pictures with reference base pictures,
 * progressive and interlaced layers with field pictures and macroblock-adaptive frames, CAVLC and
 * CABAC) outputs its base layer by default and any of its layers on request (see
 * {@link H264DecoderOptions#targetLayer()}); its 4:2:0 8-bit layers are decoded bit-exactly, on the
 * calling thread. Both kinds of stream can drop their higher temporal layers (see
 * {@link H264DecoderOptions#maxTemporalId()}). Multiview and scalable tracks of MP4 files decode
 * through their {@code mvcC} and {@code svcC} configurations and aggregator NAL units.
 *
 * <p>Interlaced content is output as frames: the two fields of a frame, coded as one frame
 * picture or as two field pictures, are woven into one frame, which carries the timestamp of the
 * packet in which its first field arrived and, when the two fields arrived in separate packets of
 * known durations, the sum of those durations. A field whose frame never receives its other field
 * is output as a frame whose missing field is mid-gray ({@code 1 << (d - 1)} at bit depth
 * {@code d}).
 *
 * <p>Packet format follows the track: when {@link VideoTrack#codecConfig()} holds an
 * {@code AVCDecoderConfigurationRecord}, packets are NAL units prefixed by their big-endian
 * length (of the record's size) and the record's parameter sets are loaded at construction;
 * otherwise packets are Annex B byte streams with start codes. Parameter sets carried in
 * packets are honored in both cases. A packet may hold any number of complete NAL units.
 *
 * <p>Frames reach the consumer in presentation order as {@link YuvPicture}s cropped by the
 * sequence's frame cropping, in {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422},
 * {@link ChromaFormat#YUV444} or {@link ChromaFormat#MONOCHROME}. The planes of a 4:4:4 stream
 * coded as G, B and R (with {@code matrix_coefficients} 0) come out in that order, as Y, Cb and Cr;
 * separately coded color planes all have the luma bit depth.
 * Samples of streams deeper than 8 bits are rounded to 8 bits,
 * {@code min((v + (1 << (d - 9))) >> (d - 8), 255)} for bit depth {@code d}, unless
 * {@link H264DecoderOptions#highBitDepth()} is set, in which case they are handed out as
 * {@link Yuv16Picture}s of the stream's bit depth: the luma depth for monochrome streams, the
 * larger of the luma and chroma depths otherwise. When luma and chroma have different depths, the
 * samples of the shallower component are scaled to that depth by a left shift; a component of 8
 * bits in a deeper stream keeps its 8-bit samples when rounded.
 * Each frame carries the timestamp of the packet in which its picture's first slice arrived.
 * When that packet had no timestamp and the frame duration is known (from the packet, the
 * track's frame rate or the stream's VUI timing), the timestamp is derived from the previous
 * frame's timestamp and the picture order count difference; otherwise it stays
 * {@link Packet#NO_TIMESTAMP}. The color description comes from the VUI; components the VUI
 * leaves unspecified default to BT.709 for pictures wider than 1024 or taller than 576 samples
 * and to BT.601 (SMPTE 170M) otherwise, with limited range unless {@code video_full_range_flag}
 * is set; the chroma siting is the VUI's {@code chroma_sample_loc_type_top_field} when signaled,
 * the transfer function the one an alternative transfer characteristics SEI message of the coded
 * video sequence prefers when present, and the mastering display and content light level come from
 * the last SEI messages that carried them. A frame is a keyframe when its picture is an IDR picture
 * or an I picture carrying a recovery point.
 *
 * <p>With {@link H264DecoderOptions#filmGrain()} set, the film grain a film grain characteristics
 * SEI message describes (the frequency filtering model with additive blending) is added to the
 * frames it applies to, before cropping; the samples are bit-exact with SMPTE RDD 5 for 8-bit 4:2:0
 * frames, and other formats get the same grain extended to them. The display information of the
 * picture timing ({@code pic_struct}), frame packing arrangement and display orientation SEI
 * messages is reported in {@link VideoFrame#sideData()}; a frame woven from two field pictures
 * reports the parity of the field shown first. Messages that persist (a repetition period above 0)
 * apply to the frames output after them until a cancel, a later message of the same type or an IDR
 * picture. A duration derived from the track's frame rate or the VUI timing is extended by the
 * field periods {@code pic_struct} repeats: one and a half frame periods for the three-field
 * structures, two for frame doubling and three for frame tripling.
 *
 * <p>Streams this decoder does not handle raise {@link MediaException.Unsupported} naming the
 * feature: {@code "h264 cabac data partitioning"} for slice data partitions of a CABAC picture
 * parameter set, which no profile allows; {@code "h264 3d-avc"} for the texture and depth views
 * beyond the base view of 3D-AVC streams (Enhanced Multiview Depth High, Annex I);
 * {@code "h264 mvcd"} for the non-base view components of a multiview stream that refer to a
 * sequence parameter set the decoder cannot use for them; and {@code "h264 svc format"} for
 * scalable layers with samples other than 4:2:0 at 8 bits. Each of the last three is raised once,
 * and the base view or base layer keeps decoding. When a slice data partition B or C is
 * lost, the macroblocks whose residual or I_PCM samples it held are reconstructed without them
 * (I_PCM macroblocks as mid-gray) and the picture is damaged; a partition B or C whose partition
 * A is missing damages the picture as a lost slice does. The slice data of a slice coded as
 * partitions is decoded once its partition C arrives or its picture ends, so its failures are
 * reported by the {@link #decode(Packet, Consumer)} or {@link #flush(Consumer)} call during which
 * that happens. A corrupt slice raises {@link MediaException.Malformed} for its packet after the
 * rest of the packet was processed; the missing area is concealed from the previous frame and
 * decoding continues with the next packet. A stream that starts on a non-IDR picture is decoded
 * from its first I picture or recovery point. An exception the frame consumer throws reaches the
 * caller unchanged.
 *
 * <p>With {@link H264DecoderOptions#threads()} above one, up to that many pictures are decoded
 * concurrently on the module's shared worker threads, each picture waiting only for the rows of
 * its reference pictures that its motion vectors reach; the calling thread decodes a picture
 * itself when no worker is free to. The output is identical to single-threaded decoding, but
 * frames and slice data failures are reported by the {@link #decode(Packet, Consumer)} or
 * {@link #flush(Consumer)} call during which the picture finished decoding, which may follow the
 * call that received its packet. {@link #close()} waits for the pictures still being decoded.
 *
 * <p>For streams that cross a lossy network, {@link #decode(Packet, FrameConsumer)} and
 * {@link #decodeIncomplete(Packet, FrameConsumer)} report the integrity of every frame instead of
 * failing: a frame is <em>damaged</em> when its samples may differ from the encoder's
 * reconstruction, because its packet was declared incomplete, a slice failed to decode,
 * macroblocks had to be concealed, a partition predicts from a damaged or missing picture
 * (including the stand-ins for pictures lost entirely, which show up as gaps in
 * {@code frame_num}), its reference list modification names a picture that does not exist, its
 * B slice lists leave out a picture lost entirely, or it is made of fields and data was lost
 * among or next to them, which may have carried one of its fields or changed which fields pair
 * up.
 * Damage propagates only through the references partitions actually use, so the first picture
 * that predicts only from intact pictures, such as an IDR picture or a picture predicting from a
 * long-term reference kept for recovery, is intact again. {@link #keyframeNeeded()} tells the
 * caller when to ask the sender for a key frame: from any damage, dropped slice or stream failure
 * until a later picture is output intact. A receiver that shows only intact frames and freezes on
 * the last one otherwise never displays a corrupt picture.
 *
 * <p>A decoder with {@link H264DecoderOptions#forRealtime()} works in low-delay mode on the
 * calling thread: every packet must hold one whole access unit, whose picture is finished and
 * output before the decode call returns (at most one frame per packet, none when the access unit
 * carried no picture or only part of one that could not start). The first field of a frame coded
 * as two field pictures is output with the second one, or, when no second field follows, once
 * the next picture starts. Baseline profile sequences never reorder pictures, so their pictures
 * are output at once even when the sequence parameter set signals no reordering limit; other
 * profiles keep the reordering the sequence signals (none for a {@code bitstream_restriction}
 * with {@code max_num_reorder_frames} 0 or picture order count type 2).
 *
 * <p>Instances are not thread-safe.
 */
public final class H264Decoder implements VideoDecoder {
    /**
     * A receiver of decoded frames together with their integrity.
     */
    @FunctionalInterface
    public interface FrameConsumer {
        /**
         * Handles one frame; the frame's picture is reused once this method returns.
         *
         * @param frame the decoded frame, valid until this method returns
         * @param damaged whether the frame's samples may differ from what the encoder
         * reconstructed, so a receiver should keep showing the previous intact frame instead
         */
        void accept(VideoFrame frame, boolean damaged);
    }

    /**
     * The NAL unit type of the aggregators of scalable and multiview tracks of MP4 files (ISO/IEC 14496-15).
     */
    private static final int AGGREGATOR = 30;

    private final H264DecoderOptions options;

    private final H264DecoderEngine engine;

    /**
     * The NAL unit length prefix size of packets, or 0 for Annex B packets.
     */
    private final int lengthSize;

    private final Rational timeBase;

    /**
     * The frame duration derived from the track frame rate, in time base units, or 0.
     */
    private final long trackFrameDuration;

    /**
     * The consumer of the call in progress, when it takes frames only.
     */
    private ConsumerFailure<VideoFrame> output;

    /**
     * The consumer of the call in progress, when it takes frames with their integrity.
     */
    private FrameConsumer checkedOutput;

    /**
     * The exception {@link #checkedOutput} threw during the call in progress, which is rethrown
     * rather than taken for stream damage.
     */
    private RuntimeException consumerFailure;

    /**
     * The reusable output picture.
     */
    private YuvPicture picture;

    /**
     * The reusable output picture of streams deeper than 8 bits with
     * {@link H264DecoderOptions#highBitDepth()}.
     */
    private Yuv16Picture widePicture;

    /**
     * The reusable output picture of depth frames.
     */
    private YuvPicture depthPicture;

    /**
     * The reusable output picture of depth frames deeper than 8 bits with
     * {@link H264DecoderOptions#highBitDepth()}.
     */
    private Yuv16Picture wideDepthPicture;

    /**
     * The base view frame of a frame-compatible stereo stream waiting for the enhancement view frame of
     * its access unit, with {@link H264DecoderOptions#enhancedResolution()}, or {@code null}.
     */
    private HeldBase heldBase;

    /**
     * The picture {@link #heldBase} holds, reused.
     */
    private YuvPicture heldBasePicture;

    /**
     * The reusable pictures of the left and right views reconstructed at enhanced resolution.
     */
    private YuvPicture leftPicture;

    private YuvPicture rightPicture;

    /**
     * The buffers of the enhanced resolution reconstruction.
     */
    private final MfcProcessing.Workspace mfcWorkspace = new MfcProcessing.Workspace();

    /**
     * A base view frame of a frame-compatible stereo stream held until the enhancement view frame of its
     * access unit arrives.
     *
     * @param key the pairing key of the access unit
     * @param frame the frame, whose picture is {@link #heldBasePicture}
     * @param damaged whether the frame may differ from the encoder's reconstruction
     */
    private record HeldBase(long key, VideoFrame frame, boolean damaged) {
    }

    /**
     * The timestamp of the last frame output, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * The picture order count of the last frame output.
     */
    private int lastPoc;

    /**
     * The smallest positive picture order count step observed between consecutive frames.
     */
    private int pocStep = Integer.MAX_VALUE;

    /**
     * The operation the calls of this decoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The film grain renderer, created with the first frame that carries grain.
     */
    private FilmGrainSynthesizer grainSynthesizer;

    /**
     * The recycled 8-bit planes of frames with film grain, by component.
     */
    private final byte[][] grainPlanes = new byte[3][];

    /**
     * The recycled 16-bit planes of frames with film grain, by component.
     */
    private final short[][] grainWidePlanes = new short[3][];

    /**
     * The most frames of a stream with alpha kept waiting for their alpha, and the most alpha
     * planes kept waiting for their frame. A frame still waiting when this many frames follow it
     * lost its auxiliary picture (one whose slices were damaged, for instance) and is output
     * opaque and damaged.
     */
    private static final int MAX_PENDING_ALPHAS = 16;

    /**
     * The frames output by the engine that wait for the alpha of their access unit, in output
     * order, with the frames output after them.
     */
    private final ArrayDeque<PendingFrame> pendingFrames = new ArrayDeque<>();

    /**
     * Copies of the auxiliary frames output before their primary frame.
     */
    private final ArrayDeque<PendingAlpha> pendingAlphas = new ArrayDeque<>();

    /**
     * The pictures of delivered frames of {@link #pendingFrames}, for reuse.
     */
    private final ArrayDeque<Picture> sparePictures = new ArrayDeque<>();

    /**
     * A frame waiting in {@link #pendingFrames}.
     */
    private static final class PendingFrame {
        /**
         * The pairing key of the frame.
         */
        final long key;

        /**
         * The frame, whose picture belongs to this entry.
         */
        final VideoFrame frame;

        /**
         * The extension describing the frame's alpha, or {@code null} for a frame without alpha.
         */
        final H264SpsExtension extension;

        /**
         * Whether the frame may differ from the encoder's reconstruction.
         */
        boolean damaged;

        /**
         * Whether the frame is complete: its alpha was attached, or it has none.
         */
        boolean resolved;

        PendingFrame(long key, VideoFrame frame, H264SpsExtension extension, boolean damaged) {
            this.key = key;
            this.frame = frame;
            this.extension = extension;
            this.damaged = damaged;
        }
    }

    /**
     * An auxiliary frame output before its primary frame.
     *
     * @param key the pairing key
     * @param frame a copy of the auxiliary frame
     * @param damaged whether the auxiliary frame may differ from the encoder's reconstruction
     */
    private record PendingAlpha(long key, H264DecoderEngine.OutputFrame frame, boolean damaged) {
    }

    private boolean closed;

    /**
     * Constructs a decoder for a track with {@link H264DecoderOptions#defaults()}.
     *
     * @param track the track to decode
     * @throws IllegalArgumentException if the track is not an H.264 track
     * @throws MediaException.Malformed if the track's configuration record is corrupt
     */
    public H264Decoder(VideoTrack track) {
        this(track, H264DecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * @param track the track to decode; in low-delay mode without a configuration record, packets
     * are Annex B access units carrying their parameter sets in band
     * @param options the limits, thread count and low-delay mode of the decoder
     * @throws IllegalArgumentException if the track is not an H.264 track
     * @throws MediaException.Malformed if the track's configuration record is corrupt
     */
    public H264Decoder(VideoTrack track, H264DecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.H264) {
            throw new IllegalArgumentException(track.codec() + " is not an H.264 track");
        }
        this.engine = new H264DecoderEngine(options.limits(), this::emit, Math.max(1, options.threads()));
        if (options.alpha()) {
            engine.decodeAuxiliary(this::emitAlpha, this::noAlpha);
        }
        engine.setLowDelay(options.realtime());
        var viewIds = options.outputViews().stream().mapToInt(Integer::intValue).toArray();
        engine.setEnhancedResolution(options.enhancedResolution());
        engine.setViews(options.allViews(), viewIds);
        if (options.depth()) {
            engine.decodeDepth(this::emitDepth, options.allViews(), viewIds);
        }
        engine.setMaxTemporalId(options.maxTemporalId().orElse(7));
        engine.setTargetLayer(options.targetLayer().orElse(127));
        this.timeBase = track.timeBase();
        this.trackFrameDuration = track.frameRate()
                .map(rate -> Math.max(0, rate.invert().rescale(1, timeBase)))
                .orElse(0L);
        var config = track.codecConfig().orElse(null);
        if (config != null && NalRecords.looksLikeAvc(config)) {
            try {
                var record = NalRecords.parseAvcStrict(config);
                this.lengthSize = record.lengthSize();
                engine.beginPacket(Packet.NO_TIMESTAMP, 0);
                for (var set : record.parameterSets()) {
                    engine.decodeNal(set, 0, set.length);
                }
                engine.endPacket();
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("corrupt AVCDecoderConfigurationRecord", exception);
            }
        } else {
            this.lengthSize = 0;
            if (config != null && NalUnits.looksLikeAnnexB(config)) {
                engine.beginPacket(Packet.NO_TIMESTAMP, 0);
                NalUnits.forEachAnnexB(config, engine::decodeNal);
                engine.endPacket();
            }
        }
    }

    /**
     * {@return the options this decoder was constructed with}
     */
    public H264DecoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * <p>An exception {@code output} throws reaches the caller unchanged.
     *
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     * @throws MediaException.LimitExceeded if the coded picture exceeds the decoder's limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.<VideoFrame>wrap(output);
            this.output = handoff;
            try {
                engine.beginPacket(packet.pts(), packet.duration());
                feed(packet.data());
                engine.endPacket();
            } catch (RuntimeException exception) {
                throw handoff.translate("H.264 packet", exception);
            } finally {
                this.output = null;
            }
        }
    }

    /**
     * Decodes one packet into a {@link FrameConsumer}, damaging the frames of a packet that arrived
     * only in part.
     *
     * @param packet the packet, or what arrived of it
     * @param incomplete whether the transport lost part of the packet's data
     * @param output receives the frames that became ready, with their integrity
     */
    private void decodeChecked(Packet packet, boolean incomplete, FrameConsumer output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            ensureOpen();
            this.checkedOutput = output;
            try {
                engine.beginPacket(packet.pts(), packet.duration(), incomplete);
                feed(packet.data());
                engine.endPacket();
            } catch (RuntimeException exception) {
                if (exception instanceof MediaException.Malformed && exception != consumerFailure) {
                    engine.noteDamage();
                } else {
                    abandon(exception);
                }
            } finally {
                this.checkedOutput = null;
                this.consumerFailure = null;
            }
        }
    }

    /**
     * Abandons the packet after a failure that is not stream damage and rethrows what the caller
     * must see: the consumer's exception, whether it ended the packet or was thrown while the
     * abandoned picture was output, otherwise a {@link MediaException}. Any other failure only
     * damages the stream.
     *
     * @param exception the failure that ended the packet
     */
    private void abandon(RuntimeException exception) {
        var failure = consumerFailure;
        engine.abandonPacket();
        if (failure == null) {
            failure = consumerFailure;
            if (failure != null && exception instanceof MediaException) {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (exception instanceof MediaException) {
            throw exception;
        }
    }

    /**
     * Decodes one packet that arrived whole and reports the integrity of every frame that became
     * ready, for streams that cross a lossy network.
     *
     * <p>Corrupt or missing data does not fail the call: it damages the affected frames, which the
     * consumer receives flagged as damaged, and makes {@link #keyframeNeeded()} {@code true}. A
     * frame with the {@code damaged} flag clear is exactly the picture the encoder reconstructed.
     * A packet the transport received only in part goes to
     * {@link #decodeIncomplete(Packet, FrameConsumer)} instead. An exception {@code output} throws
     * reaches the caller unchanged.
     *
     * @param packet the packet; in low-delay mode one whole access unit
     * @param output receives the frames that became ready, with their integrity
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Unsupported if the stream uses an unsupported coding tool
     * @throws MediaException.LimitExceeded if the pictures exceed the decoder's limits
     */
    public void decode(Packet packet, FrameConsumer output) {
        decodeChecked(packet, false, output);
    }

    /**
     * Decodes what arrived of a packet the transport received only in part, such as an RTP
     * access unit with a missing packet, and reports the integrity of every frame that became
     * ready.
     *
     * <p>Whatever picture the packet carries is reported damaged even when the remaining data
     * happens to decode without an error, and the long-term references decoded before it no
     * longer count as intact for prediction (see {@link #reportLoss()}). Otherwise the call
     * behaves as {@link #decode(Packet, FrameConsumer)}.
     *
     * @param packet the data that arrived of the packet
     * @param output receives the frames that became ready, with their integrity
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Unsupported if the stream uses an unsupported coding tool
     * @throws MediaException.LimitExceeded if the pictures exceed the decoder's limits
     */
    public void decodeIncomplete(Packet packet, FrameConsumer output) {
        decodeChecked(packet, true, output);
    }

    /**
     * Returns whether the decoder needs a key frame to output intact frames again: {@code true}
     * from the first damaged frame, dropped slice or stream failure (such as a stream joined after
     * its IDR picture) until a frame decoded after it is output intact.
     *
     * @apiNote A real-time receiver asks the sender for a key frame (an RTCP picture loss
     * indication) while this is {@code true}, at a rate its round trip time allows.
     * @return whether a key frame is needed
     */
    public boolean keyframeNeeded() {
        return engine.keyframeNeeded();
    }

    /**
     * Tells the decoder that data of the stream was lost before reaching it, such as an access
     * unit whose packets all went missing, so {@link #keyframeNeeded()} becomes {@code true} at
     * once rather than when the next access unit reveals the gap.
     *
     * <p>The signal clears like any other damage: as soon as a frame decoded after this call is
     * output intact, which is the case when the lost data was not needed by later pictures. The
     * long-term references decoded so far no longer count as intact for prediction, because the
     * lost data may have changed which pictures their indices name; this matters for senders that
     * allow gaps in {@code frame_num}, where the gap a lost picture leaves does not prove a loss on
     * its own.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    public void reportLoss() {
        ensureOpen();
        engine.noteLoss();
    }

    /**
     * Emits every frame the decoder still holds after the last packet, with its integrity, for
     * streams decoded with {@link #decode(Packet, FrameConsumer)} or
     * {@link #decodeIncomplete(Packet, FrameConsumer)}.
     *
     * <p>As with that method, corrupt or missing data damages frames instead of failing the call,
     * and an exception {@code output} throws reaches the caller unchanged. A low-delay decoder
     * holds no frames, so this emits nothing.
     *
     * @param output receives the remaining frames, with their integrity
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Unsupported if the stream uses an unsupported coding tool
     * @throws MediaException.LimitExceeded if the pictures exceed the decoder's limits
     */
    public void flush(FrameConsumer output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(output, "output");
            ensureOpen();
            this.checkedOutput = output;
            try {
                try {
                    engine.flush();
                } finally {
                    drainAllPending();
                }
            } catch (RuntimeException exception) {
                if (exception == consumerFailure || (exception instanceof MediaException
                        && !(exception instanceof MediaException.Malformed))) {
                    throw exception;
                }
                engine.noteDamage();
            } finally {
                this.checkedOutput = null;
                this.consumerFailure = null;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>An exception {@code output} throws reaches the caller unchanged.
     *
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if the pictures still held are corrupt
     * @throws MediaException.Unsupported if the stream uses an unsupported coding tool
     * @throws MediaException.LimitExceeded if the pictures exceed the decoder's limits
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.<VideoFrame>wrap(output);
            this.output = handoff;
            try {
                try {
                    engine.flush();
                } finally {
                    drainAllPending();
                }
            } catch (RuntimeException exception) {
                throw handoff.translate("H.264 stream", exception);
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
            pendingFrames.clear();
            pendingAlphas.clear();
            heldBase = null;
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
     * Feeds the NAL units of a packet to the engine, in the packet format of the track or as an
     * Annex B byte stream when the packet is one.
     *
     * @param data the packet payload
     * @throws MediaException.Malformed if a length prefix is cut off or its unit runs past the end
     * of the packet
     */
    private void feed(byte[] data) {
        if (lengthSize == 0 || (NalUnits.looksLikeAnnexB(data) && !NalUnits.isLengthPrefixed(data, lengthSize))) {
            NalUnits.forEachAnnexB(data, engine::decodeNal);
            return;
        }
        var end = NalUnits.forEachLengthPrefixed(data, lengthSize, this::feedUnit);
        if (end < data.length) {
            if (end + lengthSize > data.length) {
                throw new MediaException.Malformed("truncated NAL unit length prefix");
            }
            throw new MediaException.Malformed("NAL unit length " + NalUnits.readLength(data, end, lengthSize)
                    + " exceeds the packet");
        }
    }

    /**
     * Feeds one NAL unit of a length-prefixed packet to the engine, or the units an aggregator holds
     * (NAL unit type 30, ISO/IEC 14496-15): a four-byte NAL unit header followed by length-prefixed NAL
     * units. An aggregator inside an aggregator, which ISO/IEC 14496-15 does not allow, is skipped.
     *
     * @param data the packet payload
     * @param offset the index of the unit's header byte
     * @param length the unit length in bytes
     */
    private void feedUnit(byte[] data, int offset, int length) {
        if (length > 4 && (data[offset] & 0x1F) == AGGREGATOR) {
            var position = offset + 4;
            var end = offset + length;
            while (position + lengthSize <= end) {
                var size = NalUnits.readLength(data, position, lengthSize);
                position += lengthSize;
                if (size > end - position) {
                    throw new MediaException.Malformed("NAL unit length " + size + " exceeds its aggregator");
                }
                if (size > 0 && (data[position] & 0x1F) != AGGREGATOR) {
                    engine.decodeNal(data, position, (int) size);
                }
                position += (int) size;
            }
            return;
        }
        engine.decodeNal(data, offset, length);
    }

    /**
     * Converts a decoded frame into a {@link VideoFrame} and hands it to the consumer.
     *
     * @param frame the engine's frame
     */
    private void emit(H264DecoderEngine.OutputFrame frame) {
        emit(frame, false);
    }

    /**
     * Converts a decoded depth frame into a {@link VideoFrame} and hands it to the consumer.
     *
     * @param frame the depth engine's frame
     */
    private void emitDepth(H264DecoderEngine.OutputFrame frame) {
        emit(frame, true);
    }

    /**
     * Converts a decoded frame into a {@link VideoFrame} and hands it to the consumer, or holds it until
     * what completes it arrives.
     *
     * @param frame the engine's frame
     * @param depthView whether the frame is a depth view component
     */
    private void emit(H264DecoderEngine.OutputFrame frame, boolean depthView) {
        if (frame.filmGrain() != null && options.filmGrain()) {
            frame = withFilmGrain(frame);
        }
        var sps = frame.sps();
        var width = sps.croppedWidth();
        var height = sps.croppedHeight();
        var monochrome = !frame.hasChroma();
        var format = switch (sps.chromaFormatIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
        var color = frame.color();
        var luma = frame.lumaOrigin() + sps.cropTop * frame.lumaStride() + sps.cropLeft;
        var chromaTop = sps.cropTop >> format.shiftY();
        var chroma = frame.chromaOrigin() + chromaTop * frame.chromaStride() + (sps.cropLeft >> format.shiftX());
        var held = frame.auxiliary() != null || !pendingFrames.isEmpty();
        Picture result;
        if (frame.wide() && options.highBitDepth()) {
            var depth = monochrome ? frame.bitDepthLuma() : Math.max(frame.bitDepthLuma(), frame.bitDepthChroma());
            var target = held ? heldWidePicture(width, height, format, depth, color)
                    : depthView ? wideDepthPicture : widePicture;
            if (target == null || target.width() != width || target.height() != height
                    || target.chromaFormat() != format || target.bitDepth() != depth || !target.color().equals(color)) {
                target = Yuv16Picture.allocate(width, height, format, depth, false, color);
                if (depthView) {
                    wideDepthPicture = target;
                } else {
                    widePicture = target;
                }
            }
            copyShifted(frame.luma16(), luma, frame.lumaStride(), target.y(), target.yStride(), width, height,
                    depth - frame.bitDepthLuma());
            if (!monochrome) {
                var shift = depth - frame.bitDepthChroma();
                copyShifted(frame.cb16(), chroma, frame.chromaStride(), target.u(), target.uvStride(),
                        target.chromaWidth(), target.chromaHeight(), shift);
                copyShifted(frame.cr16(), chroma, frame.chromaStride(), target.v(), target.uvStride(),
                        target.chromaWidth(), target.chromaHeight(), shift);
            }
            result = target;
        } else {
            var target = held ? heldPicture(width, height, format, color) : depthView ? depthPicture : picture;
            if (target == null || target.width() != width || target.height() != height
                    || target.chromaFormat() != format || !target.color().equals(color)) {
                target = YuvPicture.allocate(width, height, format, false, color);
                if (depthView) {
                    depthPicture = target;
                } else {
                    picture = target;
                }
            }
            if (frame.wide()) {
                var narrow = Narrow.select();
                narrow.narrow(frame.luma16(), luma, frame.lumaStride(), target.y(), 0, target.yStride(), width,
                        height, frame.bitDepthLuma() - 8);
                if (!monochrome) {
                    var shift = frame.bitDepthChroma() - 8;
                    narrow.narrow(frame.cb16(), chroma, frame.chromaStride(), target.u(), 0, target.uvStride(),
                            target.chromaWidth(), target.chromaHeight(), shift);
                    narrow.narrow(frame.cr16(), chroma, frame.chromaStride(), target.v(), 0, target.uvStride(),
                            target.chromaWidth(), target.chromaHeight(), shift);
                }
            } else {
                Planes.copy(frame.luma(), luma, frame.lumaStride(), target.y(), 0, target.yStride(), width, height);
                if (!monochrome) {
                    Planes.copy(frame.cb(), chroma, frame.chromaStride(), target.u(), 0, target.uvStride(),
                            target.chromaWidth(), target.chromaHeight());
                    Planes.copy(frame.cr(), chroma, frame.chromaStride(), target.v(), 0, target.uvStride(),
                            target.chromaWidth(), target.chromaHeight());
                }
            }
            result = target;
        }
        if (depthView && frame.depthLut() != null && result instanceof YuvPicture depthMap) {
            var lut = frame.depthLut();
            var samples = depthMap.y();
            for (var y = 0; y < height; y++) {
                var row = y * depthMap.yStride();
                for (var x = 0; x < width; x++) {
                    samples[row + x] = (byte) lut[samples[row + x] & 0xFF];
                }
            }
        }
        var nominal = frame.duration() > 0 ? frame.duration() : frameDuration(sps);
        var pts = frame.pts();
        if (pts == Packet.NO_TIMESTAMP && nominal > 0) {
            if (lastPts == Packet.NO_TIMESTAMP) {
                pts = 0;
            } else {
                var step = pocStep == Integer.MAX_VALUE ? 2 : pocStep;
                var frames = Math.max(1, (frame.poc() - lastPoc) / step);
                pts = lastPts + frames * nominal;
            }
        }
        // the picture timing pic_struct shows the frame for extra field periods
        var duration = frame.duration() > 0 ? nominal : nominal * (2 + frame.sideData().repeatedFields()) / 2;
        var pocDelta = frame.poc() - lastPoc;
        if (pocDelta > 0 && lastPts != Packet.NO_TIMESTAMP) {
            pocStep = Math.min(pocStep, pocDelta);
        }
        lastPoc = frame.poc();
        if (pts != Packet.NO_TIMESTAMP) {
            lastPts = pts;
        }
        var decoded = new VideoFrame(result, pts, duration > 0 ? duration : Track.UNKNOWN_DURATION, frame.keyframe(),
                frame.sideData());
        if (!held && frame.frameCompatible() != null && options.enhancedResolution()
                && result instanceof YuvPicture yuv && format == ChromaFormat.YUV420) {
            emitFrameCompatible(frame, decoded, yuv);
            return;
        }
        if (!held) {
            deliver(decoded, frame.damaged());
            return;
        }
        var pending = new PendingFrame(frame.pairKey(), decoded, frame.auxiliary(), frame.damaged());
        pendingFrames.addLast(pending);
        if (frame.auxiliary() == null) {
            pending.resolved = true;
        } else {
            for (var iterator = pendingAlphas.iterator(); iterator.hasNext(); ) {
                var alpha = iterator.next();
                if (alpha.key() == pending.key) {
                    iterator.remove();
                    applyAlpha(pending, alpha.frame(), alpha.damaged());
                    break;
                }
            }
        }
        if (pendingFrames.size() > MAX_PENDING_ALPHAS) {
            var oldest = pendingFrames.peekFirst();
            if (!oldest.resolved) {
                makeOpaque(oldest.frame.picture());
                oldest.damaged = true;
                oldest.resolved = true;
            }
        }
        drainPending();
    }

    /**
     * Handles a frame of a frame-compatible stereo stream output at enhanced resolution: holds a copy of
     * the base view frame until the enhancement view frame of its access unit arrives, then hands the
     * left and right views reconstructed from the two to the consumer. A base view frame that no
     * enhancement view frame follows is handed out as decoded.
     *
     * @param frame the engine's frame
     * @param decoded the converted frame
     * @param picture the converted frame's picture, reused by the next frame
     */
    private void emitFrameCompatible(H264DecoderEngine.OutputFrame frame, VideoFrame decoded, YuvPicture picture) {
        if (frame.voIdx() == 0) {
            releaseHeldBase();
            var copy = heldBasePicture;
            if (copy == null || copy.width() != picture.width() || copy.height() != picture.height()
                    || !copy.color().equals(picture.color())) {
                copy = YuvPicture.allocate(picture.width(), picture.height(), ChromaFormat.YUV420, false,
                        picture.color());
                heldBasePicture = copy;
            }
            Planes.copy(picture.y(), 0, picture.yStride(), copy.y(), 0, copy.yStride(), picture.width(),
                    picture.height());
            Planes.copy(picture.u(), 0, picture.uvStride(), copy.u(), 0, copy.uvStride(), picture.chromaWidth(),
                    picture.chromaHeight());
            Planes.copy(picture.v(), 0, picture.uvStride(), copy.v(), 0, copy.uvStride(), picture.chromaWidth(),
                    picture.chromaHeight());
            heldBase = new HeldBase(frame.pairKey(), new VideoFrame(copy, decoded.pts(), decoded.duration(),
                    decoded.keyframe(), decoded.sideData()), frame.damaged());
            return;
        }
        var base = heldBase;
        if (base == null || base.key() != frame.pairKey() || frame.voIdx() != 1
                || picture.width() != base.frame().picture().width()
                || picture.height() != base.frame().picture().height()) {
            releaseHeldBase();
            deliver(decoded, frame.damaged());
            return;
        }
        heldBase = null;
        var basePicture = (YuvPicture) base.frame().picture();
        var left = leftPicture;
        if (left == null || left.width() != picture.width() || left.height() != picture.height()
                || !left.color().equals(basePicture.color())) {
            left = YuvPicture.allocate(picture.width(), picture.height(), ChromaFormat.YUV420, false,
                    basePicture.color());
            leftPicture = left;
            rightPicture = YuvPicture.allocate(picture.width(), picture.height(), ChromaFormat.YUV420, false,
                    basePicture.color());
        }
        var right = rightPicture;
        MfcProcessing.reconstruct(basePicture, picture, left, right, frame.frameCompatible(), mfcWorkspace);
        var damaged = base.damaged() || frame.damaged();
        var baseFrame = base.frame();
        deliver(new VideoFrame(left, baseFrame.pts(), baseFrame.duration(), baseFrame.keyframe(),
                baseFrame.sideData().toBuilder().noFramePacking().build()), damaged);
        deliver(new VideoFrame(right, baseFrame.pts(), baseFrame.duration(), baseFrame.keyframe(),
                decoded.sideData().toBuilder().noFramePacking().build()), damaged);
    }

    /**
     * Hands the held base view frame of a frame-compatible stereo stream to the consumer as decoded, when
     * one is held.
     */
    private void releaseHeldBase() {
        var base = heldBase;
        if (base != null) {
            heldBase = null;
            deliver(base.frame(), base.damaged());
        }
    }

    /**
     * Hands a frame to the consumer of the call in progress.
     *
     * @param frame the decoded frame
     * @param damaged whether the frame may differ from the encoder's reconstruction
     */
    private void deliver(VideoFrame frame, boolean damaged) {
        if (checkedOutput != null) {
            try {
                checkedOutput.accept(frame, damaged);
            } catch (RuntimeException failure) {
                consumerFailure = failure;
                throw failure;
            }
        } else {
            output.accept(frame);
        }
    }

    /**
     * Hands the leading frames of {@link #pendingFrames} that no longer wait for their alpha to
     * the consumer, returning their pictures to the spare pictures.
     */
    private void drainPending() {
        while (!pendingFrames.isEmpty() && pendingFrames.peekFirst().resolved) {
            var pending = pendingFrames.pollFirst();
            sparePictures.addLast(pending.frame.picture());
            deliver(pending.frame, pending.damaged);
        }
    }

    /**
     * Hands every frame of {@link #pendingFrames} to the consumer, opaque when its alpha never
     * arrived, and forgets the alpha planes still waiting for their frame.
     */
    private void drainAllPending() {
        for (var pending : pendingFrames) {
            if (!pending.resolved) {
                makeOpaque(pending.frame.picture());
                pending.resolved = true;
            }
        }
        pendingAlphas.clear();
        drainPending();
        releaseHeldBase();
    }

    /**
     * Receives a frame of the auxiliary engine: attaches its samples as the alpha of the primary
     * frame of the same access unit, or keeps a copy until that frame is output.
     *
     * @param frame the auxiliary engine's frame
     */
    private void emitAlpha(H264DecoderEngine.OutputFrame frame) {
        for (var pending : pendingFrames) {
            if (pending.key == frame.pairKey() && !pending.resolved) {
                applyAlpha(pending, frame, frame.damaged());
                drainPending();
                return;
            }
        }
        if (pendingAlphas.size() >= MAX_PENDING_ALPHAS) {
            pendingAlphas.pollFirst();
        }
        pendingAlphas.addLast(new PendingAlpha(frame.pairKey(), copyOf(frame), frame.damaged()));
    }

    /**
     * Learns that the access unit of a primary frame carried no auxiliary picture, so that the frame
     * is output opaque.
     *
     * @param key the pairing key of the primary frame
     */
    private void noAlpha(long key) {
        for (var pending : pendingFrames) {
            if (pending.key == key && !pending.resolved) {
                makeOpaque(pending.frame.picture());
                pending.resolved = true;
                drainPending();
                return;
            }
        }
    }

    /**
     * Writes the alpha of an auxiliary frame into the picture of a waiting primary frame.
     *
     * @param pending the primary frame
     * @param alpha the auxiliary frame, whose samples are read before this method returns
     * @param damaged whether the auxiliary frame may differ from the encoder's reconstruction
     */
    private void applyAlpha(PendingFrame pending, H264DecoderEngine.OutputFrame alpha, boolean damaged) {
        AuxiliaryAlpha.apply(pending.extension, alpha, pending.frame.picture());
        pending.damaged |= damaged;
        pending.resolved = true;
    }

    /**
     * Returns a copy of the samples of an auxiliary frame, whose planes the engine reuses once the
     * frame is handed out.
     *
     * @param frame the auxiliary frame
     * @return a frame holding copies of the luma plane
     */
    private static H264DecoderEngine.OutputFrame copyOf(H264DecoderEngine.OutputFrame frame) {
        return new H264DecoderEngine.OutputFrame(frame.luma() != null ? frame.luma().clone() : null, null, null,
                frame.luma16() != null ? frame.luma16().clone() : null, null, null, frame.bitDepthLuma(),
                frame.bitDepthChroma(), frame.lumaStride(), frame.chromaStride(), frame.lumaOrigin(),
                frame.chromaOrigin(), frame.sps(), frame.color(), frame.pts(), frame.duration(), frame.keyframe(),
                frame.poc(), frame.damaged(), null, 0, frame.sideData(), frame.picStruct(), frame.pairKey(), null,
                frame.voIdx(), null, null);
    }

    /**
     * Fills the alpha plane of a held picture with the opaque value.
     *
     * @param picture the picture, which has an alpha plane when its frame waited for alpha
     */
    private static void makeOpaque(Picture picture) {
        if (picture instanceof YuvPicture yuv && yuv.hasAlpha()) {
            Arrays.fill(yuv.alpha(), (byte) 255);
        } else if (picture instanceof Yuv16Picture wide && wide.hasAlpha()) {
            Arrays.fill(wide.alpha(), (short) ((1 << wide.bitDepth()) - 1));
        }
    }

    /**
     * Returns a spare 8-bit picture with an alpha plane for a frame that waits in
     * {@link #pendingFrames}, reusing one of the same format when there is one.
     *
     * @param width the picture width in samples
     * @param height the picture height in samples
     * @param format the chroma format
     * @param color the color description
     * @return the picture
     */
    private YuvPicture heldPicture(int width, int height, ChromaFormat format, ColorInfo color) {
        for (var iterator = sparePictures.iterator(); iterator.hasNext(); ) {
            if (iterator.next() instanceof YuvPicture spare && spare.width() == width && spare.height() == height
                    && spare.chromaFormat() == format && spare.color().equals(color)) {
                iterator.remove();
                return spare;
            }
        }
        trimSpares();
        return YuvPicture.allocate(width, height, format, true, color);
    }

    /**
     * Returns a spare 16-bit picture with an alpha plane for a frame that waits in
     * {@link #pendingFrames}; see {@link #heldPicture}.
     *
     * @param width the picture width in samples
     * @param height the picture height in samples
     * @param format the chroma format
     * @param depth the bit depth
     * @param color the color description
     * @return the picture
     */
    private Yuv16Picture heldWidePicture(int width, int height, ChromaFormat format, int depth, ColorInfo color) {
        for (var iterator = sparePictures.iterator(); iterator.hasNext(); ) {
            if (iterator.next() instanceof Yuv16Picture spare && spare.width() == width && spare.height() == height
                    && spare.chromaFormat() == format && spare.bitDepth() == depth && spare.color().equals(color)) {
                iterator.remove();
                return spare;
            }
        }
        trimSpares();
        return Yuv16Picture.allocate(width, height, format, depth, true, color);
    }

    /**
     * Drops the oldest spare pictures beyond the few a stream with alpha keeps in use.
     */
    private void trimSpares() {
        while (sparePictures.size() >= MAX_PENDING_ALPHAS) {
            sparePictures.pollFirst();
        }
    }

    /**
     * Returns a frame whose planes hold the decoded samples of a frame plus the film grain its
     * film grain characteristics message describes, rendered on the whole decoded frame before
     * cropping, or the frame itself when the message is not one the renderer supports.
     *
     * @param frame the engine's frame, whose film grain message is not {@code null}
     * @return the frame with grain, whose planes are recycled for the next frame with grain
     */
    private H264DecoderEngine.OutputFrame withFilmGrain(H264DecoderEngine.OutputFrame frame) {
        var sps = frame.sps();
        var params = frame.filmGrain();
        if (!params.renderable(frame.bitDepthLuma(), frame.bitDepthChroma(), sps.videoFullRange,
                sps.colorDescriptionPresent, sps.colorPrimaries, sps.transferCharacteristics, sps.matrixCoefficients)) {
            return frame;
        }
        if (grainSynthesizer == null) {
            grainSynthesizer = new FilmGrainSynthesizer();
        }
        var width = sps.codedWidth();
        var height = sps.codedHeight();
        var format = sps.chromaFormatIdc;
        var chromaWidth = format == 3 ? width : width >> 1;
        var chromaHeight = format == 1 ? height >> 1 : height;
        var components = frame.hasChroma() ? 3 : 1;
        var threads = Math.max(1, options.threads());
        var offset = frame.grainOffset();
        byte[][] planes8 = null;
        short[][] planes16 = null;
        for (var c = 0; c < components; c++) {
            var w = c == 0 ? width : chromaWidth;
            var h = c == 0 ? height : chromaHeight;
            var stride = c == 0 ? frame.lumaStride() : frame.chromaStride();
            var origin = c == 0 ? frame.lumaOrigin() : frame.chromaOrigin();
            if (frame.wide()) {
                planes16 = grainWidePlanes;
                if (planes16[c] == null || planes16[c].length < w * h) {
                    planes16[c] = new short[w * h];
                }
                var src = c == 0 ? frame.luma16() : c == 1 ? frame.cb16() : frame.cr16();
                var depth = c == 0 ? frame.bitDepthLuma() : frame.bitDepthChroma();
                grainSynthesizer.apply(params, offset, c, format, depth, src, origin, stride, planes16[c], 0, w, w, h,
                        threads);
            } else {
                planes8 = grainPlanes;
                if (planes8[c] == null || planes8[c].length < w * h) {
                    planes8[c] = new byte[w * h];
                }
                var src = c == 0 ? frame.luma() : c == 1 ? frame.cb() : frame.cr();
                grainSynthesizer.apply(params, offset, c, format, src, origin, stride, planes8[c], 0, w, w, h, threads);
            }
        }
        var chroma = components == 3;
        return new H264DecoderEngine.OutputFrame(planes8 != null ? planes8[0] : null,
                planes8 != null && chroma ? planes8[1] : null, planes8 != null && chroma ? planes8[2] : null,
                planes16 != null ? planes16[0] : null, planes16 != null && chroma ? planes16[1] : null,
                planes16 != null && chroma ? planes16[2] : null, frame.bitDepthLuma(), frame.bitDepthChroma(), width,
                chromaWidth, 0, 0, sps, frame.color(), frame.pts(), frame.duration(), frame.keyframe(), frame.poc(),
                frame.damaged(), params, offset, frame.sideData(), frame.picStruct(), frame.pairKey(),
                frame.auxiliary(), frame.voIdx(), frame.frameCompatible(), frame.depthLut());
    }

    /**
     * Copies a region of a 16-bit plane to the top-left corner of an output plane, shifting every
     * sample left.
     *
     * @param src the source plane
     * @param srcOffset the index of the region's top-left sample
     * @param srcStride the source row stride
     * @param dst the output plane
     * @param dstStride the output row stride
     * @param width the region width
     * @param height the region height
     * @param shift the left shift, from 0 to 6
     */
    private static void copyShifted(short[] src, int srcOffset, int srcStride, short[] dst, int dstStride, int width,
                                    int height, int shift) {
        if (shift == 0) {
            Planes.copy(src, srcOffset, srcStride, dst, 0, dstStride, width, height);
            return;
        }
        for (var y = 0; y < height; y++) {
            var from = srcOffset + y * srcStride;
            var to = y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[to + x] = (short) (src[from + x] << shift);
            }
        }
    }

    /**
     * Returns the nominal frame duration in track time base units: from the track's frame rate,
     * or from the stream's VUI timing information, or 0 when neither is known.
     *
     * @param sps the sequence parameter set of the frame
     * @return the duration, or 0
     */
    private long frameDuration(H264Sps sps) {
        if (trackFrameDuration > 0) {
            return trackFrameDuration;
        }
        if (sps.timingInfoPresent && sps.numUnitsInTick > 0 && sps.timeScale > 0) {
            try {
                return Math.max(0, new Rational(2 * sps.numUnitsInTick, sps.timeScale).rescale(1, timeBase));
            } catch (ArithmeticException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
