package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
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
import com.github.auties00.smalt.video.h264.internal.H264DecoderEngine;
import com.github.auties00.smalt.video.h264.internal.H264Sps;

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
 * parameter set, which no profile allows, and {@code "h264 mvc"} for inter-view reference list
 * modifications. NAL units of the scalable and multiview extensions and auxiliary pictures are
 * skipped, so the base layer and primary pictures decode. When a slice data partition B or C is
 * lost, the macroblocks whose residual or I_PCM samples it held are reconstructed without them (I_PCM
 * macroblocks as mid-gray) and the picture is damaged; a partition B or C whose partition A is
 * missing damages the picture as a lost slice does. The slice data of a slice coded as partitions
 * is decoded once its partition C arrives or its picture ends, so its failures are reported by
 * the {@link #decode(Packet, Consumer)} or {@link #flush(Consumer)} call during which that
 * happens. A corrupt slice raises
 * {@link MediaException.Malformed} for its packet after the rest of the packet was processed; the
 * missing area is concealed from the previous frame
 * and decoding continues with the next packet. A stream that starts on a non-IDR picture is
 * decoded from its first I picture or recovery point. An exception the frame consumer throws
 * reaches the caller unchanged.
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
        engine.setLowDelay(options.realtime());
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
                engine.flush();
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
                engine.flush();
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
        var end = NalUnits.forEachLengthPrefixed(data, lengthSize, engine::decodeNal);
        if (end < data.length) {
            if (end + lengthSize > data.length) {
                throw new MediaException.Malformed("truncated NAL unit length prefix");
            }
            throw new MediaException.Malformed("NAL unit length " + NalUnits.readLength(data, end, lengthSize)
                    + " exceeds the packet");
        }
    }

    /**
     * Converts a decoded frame into a {@link VideoFrame} and hands it to the consumer.
     *
     * @param frame the engine's frame
     */
    private void emit(H264DecoderEngine.OutputFrame frame) {
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
        Picture result;
        if (frame.wide() && options.highBitDepth()) {
            var depth = monochrome ? frame.bitDepthLuma() : Math.max(frame.bitDepthLuma(), frame.bitDepthChroma());
            var target = widePicture;
            if (target == null || target.width() != width || target.height() != height
                    || target.chromaFormat() != format || target.bitDepth() != depth || !target.color().equals(color)) {
                target = Yuv16Picture.allocate(width, height, format, depth, false, color);
                widePicture = target;
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
            var target = picture;
            if (target == null || target.width() != width || target.height() != height
                    || target.chromaFormat() != format || !target.color().equals(color)) {
                target = YuvPicture.allocate(width, height, format, false, color);
                picture = target;
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
        if (checkedOutput != null) {
            try {
                checkedOutput.accept(decoded, frame.damaged());
            } catch (RuntimeException failure) {
                consumerFailure = failure;
                throw failure;
            }
        } else {
            output.accept(decoded);
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
                frame.damaged(), params, offset, frame.sideData(), frame.picStruct());
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
