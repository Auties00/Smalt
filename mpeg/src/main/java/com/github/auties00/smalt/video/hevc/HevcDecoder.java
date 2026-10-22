package com.github.auties00.smalt.video.hevc;

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
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
import com.github.auties00.smalt.internal.sei.FilmGrainSynthesizer;
import com.github.auties00.smalt.internal.sei.SeiPersistence;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A decoder of ITU-T H.265 / MPEG-H HEVC video tracks.
 *
 * <p>The decoder implements the Main, Main 10 and Main Still Picture profiles with every coding tool
 * of version 1 of the specification: intra prediction in the 35 modes with neighbor filtering,
 * inter prediction with merge and advanced motion vector prediction including temporal candidates,
 * asymmetric partitions, weighted prediction, the 4x4 to 32x32 transforms with the 4x4 DST,
 * transform skip, scaling lists, sign data hiding, PCM, transquant bypass, the deblocking filter
 * and sample adaptive offset, tiles, wavefront parallel processing entry points, dependent slice
 * segments, long-term reference pictures and the output order decoded picture buffer. It also
 * implements the range extensions: the 4:0:0, 4:2:2 and 4:4:4 chroma formats, bit depths up to 16,
 * and the range extension coding tools (cross-component prediction, implicit and explicit residual
 * DPCM, transform skip rotation, contexts and blocks up to 32x32, extended precision processing,
 * persistent Rice adaptation, CABAC bypass alignment, chroma QP offset lists, SAO offset scaling,
 * disabled intra smoothing and high precision weighted prediction offsets), 4:4:4 pictures coded as
 * three separate color planes ({@code separate_colour_plane_flag}, each plane decoded as a monochrome
 * picture at the luma bit depth) and the screen content coding extensions: palette mode with
 * predictor initializers in the sequence and picture parameter sets, intra block copy through
 * references to the current picture, the adaptive color transform with its QP offsets, adaptive
 * motion vector resolution and disabled intra boundary filtering. Output is bit-exact with the
 * normative decoding process.
 *
 * <p>Packet format follows the track: when {@link VideoTrack#codecConfig()} holds an
 * {@code HEVCDecoderConfigurationRecord} ({@code hvcC}), packets are NAL units prefixed by their
 * big-endian length and the record's parameter sets are loaded at construction; otherwise packets
 * are Annex B byte streams with start codes, which may split access units arbitrarily. Parameter
 * sets carried in packets are honored in both cases. NAL units of reserved types and of layers
 * other than the base layer are ignored.
 *
 * <p>Frames reach the consumer in output order as {@link YuvPicture}s cropped by the sequence's
 * conformance window (and by the VUI's default display window inside it when
 * {@link HevcDecoderOptions#defaultDisplayWindow()} is set), in the stream's chroma format:
 * {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422}, {@link ChromaFormat#YUV444} or
 * {@link ChromaFormat#MONOCHROME}.
 * Samples of streams deeper than 8 bits (up to 16 bits, the range extension profiles) are rounded
 * to 8 bits, {@code min((v + (1 << (d - 9))) >> (d - 8), 255)} for bit depth {@code d}, unless
 * {@link HevcDecoderOptions#highBitDepth()} is set, in which case they are handed out as
 * {@link Yuv16Picture}s of the stream's bit depth: the luma depth for monochrome streams, the
 * larger of the luma and chroma depths otherwise. When luma and chroma have different depths, the
 * samples of the shallower component are scaled to that depth by a left shift.
 * Each frame carries the timestamp of the packet in which its picture's first slice arrived. When
 * that packet had no timestamp and a frame duration is known (from the packet, the track's frame
 * rate or the stream's VUI timing), the timestamp continues from the end of the previous frame. A
 * duration taken from the track or the VUI is scaled by the picture timing SEI message's
 * {@code pic_struct}: one and a half times for the three-field structures (5 and 6), twice for frame
 * doubling (7) and three times for frame tripling (8). The color
 * description comes from the VUI; components the VUI leaves unspecified default to BT.709 for
 * pictures wider than 1024 or taller than 576 samples and to BT.601 otherwise. The chroma siting
 * is the VUI's {@code chroma_sample_loc_type_top_field} when signaled, the transfer function the
 * one an alternative transfer characteristics SEI message prefers when present, and the mastering
 * display and content light level come from the last SEI messages that carried them. A frame is a
 * keyframe when its picture is an IRAP picture or an I picture carrying a recovery point SEI message.
 *
 * <p>With {@link HevcDecoderOptions#filmGrain()} set, the film grain a film grain characteristics
 * SEI message describes (the frequency filtering model with additive blending) is added to the
 * pictures it applies to, before cropping, seeded by their picture order count; the samples follow
 * SMPTE RDD 5 for 8-bit 4:2:0 pictures, and other formats get the same grain extended to them.
 * Decoded picture hashes are checked on the samples without grain. The display information of the
 * picture timing ({@code pic_struct}), frame packing arrangement and display orientation SEI
 * messages is reported in {@link VideoFrame#sideData()}; a frame woven from two fields carries the
 * messages of its first field and reports that field's parity. Messages with the persistence flag
 * set apply to the pictures output after them until a cancel, a later message of the same type or
 * the start of a coded video sequence.
 *
 * <p>The fields of a field coded sequence ({@code field_seq_flag}), and the pictures that their
 * picture timing SEI message's {@code pic_struct} marks as fields (1, 2 and 9 to 12), are woven into
 * frames twice as tall as a field: in output order, two consecutive fields of opposite parity form a frame, the top
 * field in its even rows, unless the picture timing {@code pic_struct} of the first asks to pair it
 * with its previous field (9 or 10) or that of the second with its next field (11 or 12). The parity
 * is the {@code pic_struct}'s (top for 1, 9 and 11, bottom for 2, 10 and 12); without one, fields
 * alternate, the first field of each frame taken as the top field. A field left without a pair is
 * output as a frame whose other field is mid-gray ({@code 1 << (d - 1)} at bit depth {@code d}).
 * A woven frame carries the timestamp of its first field and, when both durations are known, the
 * sum of the durations of its fields; the conformance and display windows crop each field.
 *
 * <p>Decoding starts at the first IRAP picture or at the first picture carrying a recovery point SEI
 * message, whichever comes first; the RASL pictures of a CRA picture that starts the stream are
 * skipped. After a start at a recovery point, the references the stream lacks are mid-gray
 * stand-ins, and the pictures before the recovery point in output order are decoded but not output
 * (nor their picture hashes checked), until an IRAP picture starts a new coded video sequence;
 * the hashes of the later pictures are checked only when the message promises an exact match. A
 * recovery point message that is truncated, or whose {@code recovery_poc_cnt} lies outside
 * {@code -MaxPicOrderCntLsb / 2} to {@code MaxPicOrderCntLsb / 2 - 1}, is ignored.
 * A base layer whose parameter sets carry the 3D-HEVC extension raises
 * {@link MediaException.Unsupported} naming the feature {@code "hevc 3D extension"}.
 * Every profile of version 1, of the range extensions and of the screen content coding extensions
 * is decoded: Main, Main 10, Main Still Picture, Main 12, Main 4:2:2 10 and 12, Main 4:4:4 (8, 10,
 * 12 and 16 Intra, with their Intra and Still Picture variants), Monochrome (8, 12 and 16), High
 * Throughput 4:4:4 16 Intra, Screen-Extended Main, Main 10, Main 4:4:4 and Main 4:4:4 10, and
 * Screen-Extended High Throughput 4:4:4, 4:4:4 10 and 4:4:4 14, with every coding tool of those
 * extensions. A picture coded with separate color planes that lacks the slices of a plane raises
 * {@link MediaException.Malformed}, its missing plane mid-gray. A corrupt slice raises
 * {@link MediaException.Malformed} once its picture is finished; the rest of the picture is still
 * decoded and decoding continues with the next picture. With several threads
 * ({@link HevcDecoderOptions#threads()}), a picture is decoded while later ones are parsed, so the
 * failure of a corrupt picture is raised by the call that outputs it or by {@link #flush(Consumer)}.
 * An exception thrown by the frame consumer reaches the caller unchanged.
 *
 * <p>With several threads, pictures, the wavefront rows of a slice segment and the color planes of
 * a picture coded with separate color planes are decoded
 * concurrently on the shared pool that {@link com.github.auties00.smalt.MediaRuntime MediaRuntime}
 * describes. The output never depends on the thread count.
 *
 * <p>Instances are not thread-safe.
 */
public final class HevcDecoder implements VideoDecoder {
    /**
     * The decoding engine.
     */
    private final HevcDecoderEngine engine;

    /**
     * The decoder options.
     */
    private final HevcDecoderOptions options;

    /**
     * The NAL unit length prefix size of packets, or 0 for Annex B packets.
     */
    private final int lengthSize;

    /**
     * The track time base.
     */
    private final Rational timeBase;

    /**
     * The frame duration derived from the track frame rate, in time base units, or 0.
     */
    private final long trackFrameDuration;

    /**
     * The consumer of the call in progress, or {@code null} between calls.
     */
    private ConsumerFailure<VideoFrame> output;

    /**
     * The recycled 8-bit output picture.
     */
    private YuvPicture picture;

    /**
     * The recycled high bit depth output picture.
     */
    private Yuv16Picture widePicture;

    /**
     * The timestamp of the last frame or field output, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * The duration of the last frame or field output, or 0 when unknown.
     */
    private long lastDuration;

    /**
     * The parity of a top field, whose rows are the even rows of its frame.
     */
    private static final int TOP = 0;

    /**
     * The parity of a bottom field, whose rows are the odd rows of its frame.
     */
    private static final int BOTTOM = 1;

    /**
     * The woven frame holding the field that waits for its pair, or {@code null} when no field
     * waits.
     */
    private Picture held;

    /**
     * The parity of the waiting field, {@link #TOP} or {@link #BOTTOM}.
     */
    private int heldParity;

    /**
     * The timestamp of the waiting field.
     */
    private long heldPts;

    /**
     * The duration of the waiting field, or 0 when unknown.
     */
    private long heldDuration;

    /**
     * Whether the waiting field is a keyframe.
     */
    private boolean heldKeyframe;

    /**
     * The display information of the frame of the waiting field.
     */
    private FrameSideData heldSideData = FrameSideData.NONE;

    /**
     * The decoded samples of the picture being output.
     */
    private final Source decoded = new Source();

    /**
     * The samples of the picture being output with its film grain.
     */
    private final Source grained = new Source();

    /**
     * The film grain renderer, created with the first picture that carries grain.
     */
    private FilmGrainSynthesizer grainSynthesizer;

    /**
     * Which film grain characteristics message applies to each picture, in output order.
     */
    private final SeiPersistence<FilmGrainParameters> grainPersistence = new SeiPersistence<>();

    /**
     * Which frame packing arrangement message applies to each picture, in output order.
     */
    private final SeiPersistence<DisplaySei.FramePackingMessage> packingPersistence = new SeiPersistence<>();

    /**
     * Which display orientation message applies to each picture, in output order.
     */
    private final SeiPersistence<DisplaySei.OrientationMessage> orientationPersistence = new SeiPersistence<>();

    /**
     * The recycled 8-bit woven frame.
     */
    private YuvPicture wovenPicture;

    /**
     * The recycled high bit depth woven frame.
     */
    private Yuv16Picture wovenWidePicture;

    /**
     * The left edge of the window {@link #window(HevcSps)} computed last, in luma samples.
     */
    private int windowLeft;

    /**
     * The top edge of the window {@link #window(HevcSps)} computed last, in luma samples.
     */
    private int windowTop;

    /**
     * The width of the window {@link #window(HevcSps)} computed last.
     */
    private int windowWidth;

    /**
     * The height of the window {@link #window(HevcSps)} computed last.
     */
    private int windowHeight;

    /**
     * The operation the calls of this decoder form, whose computing time decides how long a call
     * keeps its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Whether the decoder was closed.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a track with the {@linkplain HevcDecoderOptions#defaults() default}
     * options.
     *
     * @param track the track to decode
     * @throws IllegalArgumentException if the track is not an HEVC track
     * @throws MediaException.Malformed if the track's configuration record is corrupt
     */
    public HevcDecoder(VideoTrack track) {
        this(track, HevcDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * @param track the track to decode
     * @param options the decoder settings
     * @throws IllegalArgumentException if the track is not an HEVC track
     * @throws MediaException.Malformed if the track's configuration record is corrupt
     */
    public HevcDecoder(VideoTrack track, HevcDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.HEVC) {
            throw new IllegalArgumentException(track.codec() + " is not an HEVC track");
        }
        this.options = options;
        // TODO: decode the tiles of a picture concurrently too; pictures and wavefront rows already
        // overlap when options.threads() allows it
        this.engine = new HevcDecoderEngine(options.limits(), options.hashVerification(),
                options.threads() == 0 ? ParallelBands.THREADS : options.threads(), this::emit);
        this.timeBase = track.timeBase();
        this.trackFrameDuration = track.frameRate()
                .map(rate -> Math.max(0, rate.invert().rescale(1, timeBase)))
                .orElse(0L);
        this.lengthSize = loadConfiguration(track.codecConfig().orElse(null));
    }

    /**
     * {@return the options this decoder was constructed with}
     */
    public HevcDecoderOptions options() {
        return options;
    }

    /**
     * Loads the parameter sets of a codec configuration.
     *
     * @param config the codec configuration, or {@code null}
     * @return the NAL unit length prefix size of the packets, or 0 for Annex B packets
     * @throws MediaException.Malformed if the configuration record is corrupt
     */
    private int loadConfiguration(byte[] config) {
        if (config == null || config.length == 0) {
            return 0;
        }
        try {
            if (NalRecords.looksLikeHevc(config)) {
                var record = NalRecords.parseHevcStrict(config);
                engine.beginPacket(Packet.NO_TIMESTAMP, 0);
                for (var unit : record.parameterSets()) {
                    engine.decodeNal(unit, 0, unit.length);
                }
                return record.lengthSize();
            }
            if (NalUnits.looksLikeAnnexB(config)) {
                engine.beginPacket(Packet.NO_TIMESTAMP, 0);
                NalUnits.forEachAnnexB(config, engine::decodeNal);
            }
            return 0;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt HEVCDecoderConfigurationRecord", exception);
        }
    }

    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                engine.beginPacket(packet.pts(), packet.duration());
                var data = packet.data();
                if (lengthSize > 0
                        && !(NalUnits.looksLikeAnnexB(data) && !NalUnits.isLengthPrefixed(data, lengthSize))) {
                    feedLengthPrefixed(data);
                    engine.endAccessUnit();
                } else {
                    NalUnits.forEachAnnexB(data, engine::decodeNal);
                }
            } catch (RuntimeException exception) {
                throw handoff.translate("HEVC packet", exception);
            } finally {
                this.output = null;
            }
        }
    }

    @Override
    public void flush(Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                try {
                    engine.flush();
                } finally {
                    releaseHeldField();
                    grainPersistence.reset();
                    packingPersistence.reset();
                    orientationPersistence.reset();
                }
            } catch (RuntimeException exception) {
                throw handoff.translate("HEVC stream", exception);
            } finally {
                this.output = null;
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        held = null;
        try (var _ = ParallelBands.operation(operation)) {
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
     * Feeds the NAL units of a length-prefixed packet to the engine.
     *
     * @param data the packet payload
     * @throws MediaException.Malformed if a length prefix runs past the end of the packet
     */
    private void feedLengthPrefixed(byte[] data) {
        var broken = NalUnits.forEachLengthPrefixed(data, lengthSize, engine::decodeNal);
        if (broken < data.length) {
            if (broken + lengthSize > data.length) {
                throw new MediaException.Malformed("truncated NAL unit length prefix");
            }
            throw new MediaException.Malformed("NAL unit length " + NalUnits.readLength(data, broken, lengthSize)
                    + " exceeds the packet");
        }
    }

    /**
     * Converts a decoded picture into a {@link VideoFrame} and hands it to the consumer, or, for a
     * field of a sequence of fields, weaves it into the frame of its pair.
     *
     * <p>When the conformance window crops nothing on the left or top, a frame picture whose
     * samples are already in the requested sample size and depth (8-bit planes, or 16-bit planes of
     * one bit depth with {@link HevcDecoderOptions#highBitDepth()}) is handed out as a view of the
     * engine's planes, with the coded width as its row stride; the engine writes those planes again
     * only after the consumer returns. Other pictures are copied into a recycled picture, narrowed
     * when deeper than 8 bits or with the shallower component shifted up when the depths differ.
     *
     * @param frame the engine's picture
     */
    private void emit(HevcFrame frame) {
        var sps = frame.sps;
        window(sps);
        var keyframe = frame.irap || frame.recoveryPoint && frame.intraOnly;
        var grain = grainPersistence.resolve(frame.newSequence, frame.filmGrain);
        var packing = packingPersistence.resolve(frame.newSequence, frame.framePacking);
        var orientation = orientationPersistence.resolve(frame.newSequence, frame.orientation);
        var source = grain != null && options.filmGrain() ? withFilmGrain(frame, grain) : decoded.of(frame);
        if (sps.fieldSeq || isField(frame.picStruct)) {
            emitField(frame, source, keyframe, packing, orientation);
            return;
        }
        releaseHeldField();
        var sideData = DisplaySei.sideData(DisplaySei.fieldOrder(frame.picStruct),
                DisplaySei.repeatedFields(frame.picStruct), packing, orientation);
        var left = windowLeft;
        var top = windowTop;
        var width = windowWidth;
        var height = windowHeight;
        var format = chromaFormat(sps);
        var color = frame.color;
        var depth = sps.pictureBitDepth();
        Picture result;
        var uncropped = left == 0 && top == 0;
        if (uncropped && source.eightBit()) {
            var builder = YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                    .luma(source.y8, source.width);
            if (source.cb8 != null) {
                builder.chroma(source.cb8, source.cr8, source.chromaWidth);
            }
            result = builder.build();
        } else if (uncropped && depth > 8 && options.highBitDepth() && !sps.mixedBitDepth()) {
            var builder = Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(depth).color(color)
                    .luma(source.y, source.width);
            if (source.cb != null) {
                builder.chroma(source.cb, source.cr, source.chromaWidth);
            }
            result = builder.build();
        } else if (depth > 8 && options.highBitDepth()) {
            var target = widePicture;
            if (!matches(target, width, height, format, depth, color)) {
                target = Yuv16Picture.allocate(width, height, format, depth, false, color);
                widePicture = target;
            }
            copyWide(source, sps, target, left, top, width, height, 0, 1);
            result = target;
        } else {
            var target = picture;
            if (!matches(target, width, height, format, 0, color)) {
                target = YuvPicture.allocate(width, height, format, false, color);
                picture = target;
            }
            copyNarrow(source, sps, target, left, top, width, height, 0, 1);
            result = target;
        }
        var duration = frame.duration > 0 ? frame.duration : repeated(frameDuration(sps), frame.picStruct);
        var pts = resolvePts(frame.pts, duration);
        output.accept(new VideoFrame(result, pts, duration > 0 ? duration : Track.UNKNOWN_DURATION, keyframe,
                sideData));
    }

    /**
     * Returns the samples of a picture with the film grain a film grain characteristics message
     * describes, rendered on the whole decoded picture before any cropping, or its decoded samples
     * when the message is not one the renderer supports.
     *
     * @param frame the engine's picture
     * @param grain the film grain characteristics message that applies to the picture
     * @return the samples, recycled for the next picture
     */
    private Source withFilmGrain(HevcFrame frame, FilmGrainParameters grain) {
        var sps = frame.sps;
        var described = sps.colorPrimaries != 2 || sps.transferCharacteristics != 2 || sps.matrixCoefficients != 2;
        if (!grain.renderable(sps.bitDepthLuma, sps.bitDepthChroma, sps.fullRange, described, sps.colorPrimaries,
                sps.transferCharacteristics, sps.matrixCoefficients)) {
            return decoded.of(frame);
        }
        if (grainSynthesizer == null) {
            grainSynthesizer = new FilmGrainSynthesizer();
        }
        var target = grained.fit(frame);
        var threads = options.threads() == 0 ? ParallelBands.THREADS : options.threads();
        var poc = frame.poc;
        var format = sps.chromaFormatIdc;
        var components = frame.chromaWidth > 0 ? 3 : 1;
        for (var c = 0; c < components; c++) {
            var w = c == 0 ? frame.width : frame.chromaWidth;
            var h = c == 0 ? frame.height : frame.chromaHeight;
            if (frame.eightBit()) {
                var src = c == 0 ? frame.y8 : c == 1 ? frame.cb8 : frame.cr8;
                var dst = c == 0 ? target.y8 : c == 1 ? target.cb8 : target.cr8;
                grainSynthesizer.apply(grain, poc, c, format, src, 0, w, dst, 0, w, w, h, threads);
            } else {
                var src = c == 0 ? frame.y : c == 1 ? frame.cb : frame.cr;
                var dst = c == 0 ? target.y : c == 1 ? target.cb : target.cr;
                var depth = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
                grainSynthesizer.apply(grain, poc, c, format, depth, src, 0, w, dst, 0, w, w, h, threads);
            }
        }
        return target;
    }

    /**
     * The sample planes of a picture being output: the engine's or a copy with film grain, laid
     * out as the engine lays them out.
     */
    private static final class Source {
        byte[] y8;

        byte[] cb8;

        byte[] cr8;

        short[] y;

        short[] cb;

        short[] cr;

        /**
         * The luma row stride, the coded width.
         */
        int width;

        /**
         * The chroma row stride, or 0 without chroma.
         */
        int chromaWidth;

        /**
         * Points these planes at a picture's.
         *
         * @param frame the picture
         * @return these planes
         */
        Source of(HevcFrame frame) {
            y8 = frame.y8;
            cb8 = frame.cb8;
            cr8 = frame.cr8;
            y = frame.y;
            cb = frame.cb;
            cr = frame.cr;
            width = frame.width;
            chromaWidth = frame.chromaWidth;
            return this;
        }

        /**
         * Makes these planes the size and sample size of a picture's, keeping the arrays that fit.
         *
         * @param frame the picture
         * @return these planes
         */
        Source fit(HevcFrame frame) {
            var lumaSize = frame.width * frame.height;
            var chromaSize = frame.chromaWidth * frame.chromaHeight;
            var chroma = frame.chromaWidth > 0;
            if (frame.eightBit()) {
                y8 = sized(y8, lumaSize);
                cb8 = chroma ? sized(cb8, chromaSize) : null;
                cr8 = chroma ? sized(cr8, chromaSize) : null;
                y = null;
                cb = null;
                cr = null;
            } else {
                y = sized(y, lumaSize);
                cb = chroma ? sized(cb, chromaSize) : null;
                cr = chroma ? sized(cr, chromaSize) : null;
                y8 = null;
                cb8 = null;
                cr8 = null;
            }
            width = frame.width;
            chromaWidth = frame.chromaWidth;
            return this;
        }

        /**
         * {@return whether the samples are in the 8-bit planes}
         */
        boolean eightBit() {
            return y8 != null;
        }

        /**
         * Returns an 8-bit plane of at least a size, reusing a recycled one.
         *
         * @param plane the recycled plane, or {@code null}
         * @param size the samples needed
         * @return the plane
         */
        private static byte[] sized(byte[] plane, int size) {
            return plane != null && plane.length >= size ? plane : new byte[size];
        }

        /**
         * Returns a 16-bit plane of at least a size, reusing a recycled one.
         *
         * @param plane the recycled plane, or {@code null}
         * @param size the samples needed
         * @return the plane
         */
        private static short[] sized(short[] plane, int size) {
            return plane != null && plane.length >= size ? plane : new short[size];
        }
    }

    /**
     * Weaves a field into a frame: the field waits for the next field in output order when the two
     * pair up, and a field without a pair is output alone, its missing field mid-gray.
     *
     * <p>The parity of a field is the {@code pic_struct} of its picture timing SEI message: top for
     * 1, 9 and 11, bottom for 2, 10 and 12. Without one, the field takes the parity opposite to
     * the waiting field, or top when no field waits. Two consecutive fields pair up when their
     * parities differ, their sequences describe frames of the same size, format and color, and
     * neither the waiting field is to pair with its previous field ({@code pic_struct} 9 or 10) nor
     * the new one with its next field (11 or 12).
     *
     * <p>The woven frame carries the frame packing and display orientation of its first field, and
     * the parity of that field as its scan.
     *
     * @param frame the engine's picture of the field
     * @param source the samples of the field
     * @param keyframe whether the field is a keyframe, which makes its frame one when it comes
     * first
     * @param packing the frame packing arrangement message that applies to the field, or
     * {@code null}
     * @param orientation the display orientation message that applies to the field, or
     * {@code null}
     */
    private void emitField(HevcFrame frame, Source source, boolean keyframe, DisplaySei.FramePackingMessage packing,
                           DisplaySei.OrientationMessage orientation) {
        var sps = frame.sps;
        var picStruct = frame.picStruct;
        var parity = switch (picStruct) {
            case 1, 9, 11 -> TOP;
            case 2, 10, 12 -> BOTTOM;
            default -> held != null ? 1 - heldParity : TOP;
        };
        var pairedWithPrevious = picStruct == 9 || picStruct == 10;
        var pairedWithNext = picStruct == 11 || picStruct == 12;
        var duration = frame.duration > 0 ? frame.duration : frameDuration(sps);
        var pts = resolvePts(frame.pts, duration);
        var width = windowWidth;
        var height = windowHeight * 2;
        var format = chromaFormat(sps);
        var depth = options.highBitDepth() && sps.pictureBitDepth() > 8 ? sps.pictureBitDepth() : 0;
        var color = frame.color;
        if (held != null) {
            if (!pairedWithNext && parity != heldParity && matches(held, width, height, format, depth, color)) {
                writeField(frame, source, held, parity);
                var frameDuration = heldDuration > 0 && duration > 0 ? heldDuration + duration : Track.UNKNOWN_DURATION;
                var result = held;
                held = null;
                output.accept(new VideoFrame(result, heldPts, frameDuration, heldKeyframe, heldSideData));
                return;
            }
            releaseHeldField();
        }
        Picture target;
        if (depth > 0) {
            if (!matches(wovenWidePicture, width, height, format, depth, color)) {
                wovenWidePicture = Yuv16Picture.allocate(width, height, format, depth, false, color);
            }
            target = wovenWidePicture;
        } else {
            if (!matches(wovenPicture, width, height, format, 0, color)) {
                wovenPicture = YuvPicture.allocate(width, height, format, false, color);
            }
            target = wovenPicture;
        }
        writeField(frame, source, target, parity);
        var sideData = DisplaySei.sideData(parity == TOP ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST, 0, packing,
                orientation);
        if (pairedWithPrevious) {
            fillField(target, 1 - parity);
            output.accept(new VideoFrame(target, pts, duration > 0 ? duration : Track.UNKNOWN_DURATION, keyframe,
                    sideData));
            return;
        }
        held = target;
        heldParity = parity;
        heldPts = pts;
        heldDuration = duration;
        heldKeyframe = keyframe;
        heldSideData = sideData;
    }

    /**
     * Returns whether a {@code pic_struct} marks its picture as a field: 1, 2 and 9 to 12.
     *
     * @param picStruct the {@code pic_struct}, or -1
     * @return {@code true} for a field
     */
    private static boolean isField(int picStruct) {
        return picStruct == 1 || picStruct == 2 || picStruct >= 9 && picStruct <= 12;
    }

    /**
     * Outputs the field waiting for its pair, if any, as a frame whose other field is mid-gray.
     */
    private void releaseHeldField() {
        var frame = held;
        if (frame == null) {
            return;
        }
        held = null;
        fillField(frame, 1 - heldParity);
        output.accept(new VideoFrame(frame, heldPts, heldDuration > 0 ? heldDuration : Track.UNKNOWN_DURATION,
                heldKeyframe, heldSideData));
    }

    /**
     * Copies the cropped samples of a field into the rows of one parity of a woven frame.
     *
     * @param frame the decoded field
     * @param source the samples of the field
     * @param target the woven frame, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param parity {@link #TOP} for the even rows, {@link #BOTTOM} for the odd rows
     */
    private void writeField(HevcFrame frame, Source source, Picture target, int parity) {
        var sps = frame.sps;
        window(sps);
        if (target instanceof Yuv16Picture wide) {
            copyWide(source, sps, wide, windowLeft, windowTop, windowWidth, windowHeight, parity, 2);
        } else {
            copyNarrow(source, sps, (YuvPicture) target, windowLeft, windowTop, windowWidth, windowHeight, parity, 2);
        }
    }

    /**
     * Fills the rows of one parity of a woven frame with mid-gray: {@code 1 << (d - 1)} at bit
     * depth {@code d}.
     *
     * @param target the woven frame, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param parity {@link #TOP} for the even rows, {@link #BOTTOM} for the odd rows
     */
    private static void fillField(Picture target, int parity) {
        if (target instanceof Yuv16Picture wide) {
            var gray = (short) (1 << (wide.bitDepth() - 1));
            fillRows(wide.y(), wide.yStride(), wide.width(), wide.height(), parity, gray);
            if (wide.u() != null) {
                fillRows(wide.u(), wide.uvStride(), wide.chromaWidth(), wide.chromaHeight(), parity, gray);
                fillRows(wide.v(), wide.uvStride(), wide.chromaWidth(), wide.chromaHeight(), parity, gray);
            }
            return;
        }
        var narrow = (YuvPicture) target;
        fillRows(narrow.y(), narrow.yStride(), narrow.width(), narrow.height(), parity, (byte) 128);
        if (narrow.u() != null) {
            fillRows(narrow.u(), narrow.uvStride(), narrow.chromaWidth(), narrow.chromaHeight(), parity, (byte) 128);
            fillRows(narrow.v(), narrow.uvStride(), narrow.chromaWidth(), narrow.chromaHeight(), parity, (byte) 128);
        }
    }

    /**
     * Fills every second row of an 8-bit plane, starting at a given row.
     *
     * @param plane the samples to fill
     * @param stride the row stride
     * @param width the row width
     * @param height the number of rows
     * @param first the first row to fill, 0 or 1
     * @param value the sample value
     */
    private static void fillRows(byte[] plane, int stride, int width, int height, int first, byte value) {
        for (var row = first; row < height; row += 2) {
            Arrays.fill(plane, row * stride, row * stride + width, value);
        }
    }

    /**
     * Fills every second row of a 16-bit plane, starting at a given row.
     *
     * @param plane the samples to fill
     * @param stride the row stride
     * @param width the row width
     * @param height the number of rows
     * @param first the first row to fill, 0 or 1
     * @param value the sample value
     */
    private static void fillRows(short[] plane, int stride, int width, int height, int first, short value) {
        for (var row = first; row < height; row += 2) {
            Arrays.fill(plane, row * stride, row * stride + width, value);
        }
    }

    /**
     * Returns whether a recycled picture has the given size, format, sample depth and color.
     *
     * @param picture the recycled picture, or {@code null}
     * @param width the required width
     * @param height the required height
     * @param format the chroma format
     * @param depth the bit depth of a {@link Yuv16Picture}, or 0 for a {@link YuvPicture}
     * @param color the color description
     * @return {@code true} when the picture can be reused
     */
    private static boolean matches(Picture picture, int width, int height, ChromaFormat format, int depth,
                                   ColorInfo color) {
        if (picture == null || picture.width() != width || picture.height() != height) {
            return false;
        }
        return switch (picture) {
            case Yuv16Picture wide -> depth > 0 && wide.bitDepth() == depth && wide.chromaFormat() == format
                    && wide.color().equals(color);
            case YuvPicture narrow -> depth == 0 && narrow.chromaFormat() == format && narrow.color().equals(color);
            default -> false;
        };
    }

    /**
     * Computes the window of a picture that frames show: the conformance window, narrowed to the
     * VUI's default display window when {@link HevcDecoderOptions#defaultDisplayWindow()} is set and
     * the display window fits inside it.
     *
     * @param sps the sequence parameter set of the picture
     */
    private void window(HevcSps sps) {
        var left = sps.cropLeft;
        var top = sps.cropTop;
        var width = sps.width - sps.cropLeft - sps.cropRight;
        var height = sps.height - sps.cropTop - sps.cropBottom;
        if (options.defaultDisplayWindow() && sps.displayLeft + sps.displayRight < width
                && sps.displayTop + sps.displayBottom < height) {
            left += sps.displayLeft;
            top += sps.displayTop;
            width -= sps.displayLeft + sps.displayRight;
            height -= sps.displayTop + sps.displayBottom;
        }
        windowLeft = left;
        windowTop = top;
        windowWidth = width;
        windowHeight = height;
    }

    /**
     * Returns the chroma format of the pictures of a sequence.
     *
     * @param sps the sequence parameter set
     * @return the chroma format
     */
    private static ChromaFormat chromaFormat(HevcSps sps) {
        return switch (sps.chromaFormatIdc) {
            case 0 -> ChromaFormat.MONOCHROME;
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
    }

    /**
     * Returns the timestamp of the next frame or field: its packet's, or, when the packet had none
     * and the duration is known, the end of the previous frame or field (0 for the first), where a
     * previous one of unknown duration lasts as long as this one.
     *
     * @param pts the timestamp of the picture's packet, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration of the picture, or 0 when unknown
     * @return the timestamp, or {@link Packet#NO_TIMESTAMP}
     */
    private long resolvePts(long pts, long duration) {
        if (pts == Packet.NO_TIMESTAMP && duration > 0) {
            pts = lastPts == Packet.NO_TIMESTAMP ? 0 : lastPts + (lastDuration > 0 ? lastDuration : duration);
        }
        if (pts != Packet.NO_TIMESTAMP) {
            lastPts = pts;
            lastDuration = duration;
        }
        return pts;
    }

    /**
     * Returns the display duration of a frame picture from the nominal picture duration and its
     * {@code pic_struct} (ITU-T H.265 Table D.2): one and a half periods for the three-field
     * structures 5 and 6, two for frame doubling (7), three for frame tripling (8), one otherwise.
     *
     * @param duration the nominal duration, or 0 when unknown
     * @param picStruct the {@code pic_struct}, or -1
     * @return the duration, or 0 when unknown
     */
    private static long repeated(long duration, int picStruct) {
        return switch (picStruct) {
            case 5, 6 -> duration * 3 / 2;
            case 7 -> duration * 2;
            case 8 -> duration * 3;
            default -> duration;
        };
    }

    /**
     * Copies the cropped samples of a picture into rows of an 8-bit picture, rounding deeper
     * samples to nearest.
     *
     * @param frame the samples of the picture
     * @param sps its sequence parameter set
     * @param target the output picture
     * @param left the first kept column, in luma samples, a multiple of the chroma subsampling
     * @param top the first kept row, in luma samples, a multiple of the chroma subsampling
     * @param width the cropped width
     * @param height the cropped height
     * @param firstRow the output row receiving the first kept row, 0 or 1
     * @param rowStep the output rows per picture row: 1, or 2 to write the rows of a field
     */
    private static void copyNarrow(Source frame, HevcSps sps, YuvPicture target, int left, int top, int width,
                                   int height, int firstRow, int rowStep) {
        var yOffset = firstRow * target.yStride();
        var yStride = target.yStride() * rowStep;
        var uvOffset = firstRow * target.uvStride();
        var uvStride = target.uvStride() * rowStep;
        var cw = target.chromaWidth();
        var ch = (height + (1 << sps.chromaShiftY) - 1) >> sps.chromaShiftY;
        var chromaOffset = (top >> sps.chromaShiftY) * frame.chromaWidth + (left >> sps.chromaShiftX);
        if (frame.eightBit()) {
            Planes.copy(frame.y8, top * frame.width + left, frame.width, target.y(), yOffset, yStride, width, height);
            if (frame.cb8 != null) {
                Planes.copy(frame.cb8, chromaOffset, frame.chromaWidth, target.u(), uvOffset, uvStride, cw, ch);
                Planes.copy(frame.cr8, chromaOffset, frame.chromaWidth, target.v(), uvOffset, uvStride, cw, ch);
            }
            return;
        }
        var narrow = Narrow.select();
        narrow.narrow(frame.y, top * frame.width + left, frame.width, target.y(), yOffset, yStride, width,
                height, sps.bitDepthLuma - 8);
        if (frame.cb != null) {
            narrow.narrow(frame.cb, chromaOffset, frame.chromaWidth, target.u(), uvOffset, uvStride, cw, ch,
                    sps.bitDepthChroma - 8);
            narrow.narrow(frame.cr, chromaOffset, frame.chromaWidth, target.v(), uvOffset, uvStride, cw, ch,
                    sps.bitDepthChroma - 8);
        }
    }

    /**
     * Copies the cropped samples of a picture into rows of a high bit depth picture of the
     * picture's bit depth, shifting the samples of a shallower component up to it.
     *
     * @param frame the samples of the picture
     * @param sps its sequence parameter set
     * @param target the output picture
     * @param left the first kept column, in luma samples, a multiple of the chroma subsampling
     * @param top the first kept row, in luma samples, a multiple of the chroma subsampling
     * @param width the cropped width
     * @param height the cropped height
     * @param firstRow the output row receiving the first kept row, 0 or 1
     * @param rowStep the output rows per picture row: 1, or 2 to write the rows of a field
     */
    private static void copyWide(Source frame, HevcSps sps, Yuv16Picture target, int left, int top, int width,
                                 int height, int firstRow, int rowStep) {
        var depth = target.bitDepth();
        copyShifted(frame.y, top * frame.width + left, frame.width, target.y(), firstRow * target.yStride(),
                target.yStride() * rowStep, width, height, depth - sps.bitDepthLuma);
        if (frame.cb != null) {
            var offset = (top >> sps.chromaShiftY) * frame.chromaWidth + (left >> sps.chromaShiftX);
            var cw = target.chromaWidth();
            var ch = (height + (1 << sps.chromaShiftY) - 1) >> sps.chromaShiftY;
            var shift = depth - sps.bitDepthChroma;
            var uvOffset = firstRow * target.uvStride();
            var uvStride = target.uvStride() * rowStep;
            copyShifted(frame.cb, offset, frame.chromaWidth, target.u(), uvOffset, uvStride, cw, ch, shift);
            copyShifted(frame.cr, offset, frame.chromaWidth, target.v(), uvOffset, uvStride, cw, ch, shift);
        }
    }

    /**
     * Copies a region of a 16-bit plane into an output plane, shifting every sample left.
     *
     * @param src the source plane
     * @param srcOffset the index of the region's top-left sample
     * @param srcStride the source row stride
     * @param dst the output plane
     * @param dstOffset the index of the first output sample
     * @param dstStride the output row stride
     * @param width the region width
     * @param height the region height
     * @param shift the left shift, from 0 to 8
     */
    private static void copyShifted(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                    int dstStride, int width, int height, int shift) {
        if (shift == 0) {
            Planes.copy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            return;
        }
        for (var y = 0; y < height; y++) {
            var from = srcOffset + y * srcStride;
            var to = dstOffset + y * dstStride;
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
    private long frameDuration(HevcSps sps) {
        if (trackFrameDuration > 0) {
            return trackFrameDuration;
        }
        if (sps.timingInfoPresent && sps.numUnitsInTick > 0 && sps.timeScale > 0) {
            try {
                return Math.max(0, new Rational(sps.numUnitsInTick, sps.timeScale).rescale(1, timeBase));
            } catch (ArithmeticException | IllegalArgumentException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
