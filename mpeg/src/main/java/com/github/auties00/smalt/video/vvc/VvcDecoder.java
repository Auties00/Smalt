package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentColorVolume;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
import com.github.auties00.smalt.internal.sei.FilmGrainSynthesizer;
import com.github.auties00.smalt.internal.sei.H274Sei;
import com.github.auties00.smalt.internal.sei.SeiPersistence;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.OmnidirectionalVideo;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vvc.internal.VvcDecoderEngine;
import com.github.auties00.smalt.video.vvc.internal.VvcPicture;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A decoder of ITU-T H.266 / MPEG-I VVC video tracks.
 *
 * <p>The decoder implements the Main 10 and Main 10 Still Picture profiles (4:0:0 and 4:2:0 at 8 to 10 bits) with
 * every coding tool of version 1 of the specification: the quadtree with nested multi-type tree partitioning and the
 * dual intra trees, the 67 intra modes with wide angles, multiple reference lines, intra sub-partitions,
 * matrix-based and cross-component linear model prediction and position dependent combination, the merge, AMVP,
 * history-based, temporal and pairwise candidates, merge with motion vector differences, symmetric MVD, adaptive
 * motion vector resolution with the half-sample filter, affine motion with prediction refinement by optical flow,
 * subblock-based temporal motion prediction, geometric partitioning, combined inter and intra prediction,
 * bi-prediction with CU weights, explicit weighted prediction, decoder side motion vector refinement,
 * bi-directional optical flow, intra block copy, reference picture resampling and wraparound, the transforms with
 * multiple transform selection, the low-frequency non-separable transform, subblock transform, transform skip and
 * block differential PCM, dependent quantization, sign data hiding, joint chroma residuals, scaling lists, luma
 * mapping with chroma scaling, the deblocking filter with luma adaptive offsets, sample adaptive offset, the adaptive
 * loop filter and its cross-component part, and the picture partitioning into slices, tiles, subpictures and
 * wavefront rows. Output is bit-exact with the normative decoding process.
 *
 * <p>Packet format follows the track: when {@link VideoTrack#codecConfig()} holds a
 * {@code VvcDecoderConfigurationRecord} ({@code vvcC}), packets are NAL units prefixed by their big-endian length
 * and the record's parameter sets are loaded at construction; otherwise packets are Annex B byte streams with start
 * codes, which may split access units arbitrarily. Parameter sets carried in packets are honored in both cases. Only
 * the base layer ({@code nuh_layer_id} 0) is decoded; NAL units of other layers and of reserved types are ignored.
 *
 * <p>Frames reach the consumer in output order as {@link YuvPicture}s cropped by the picture parameter set's
 * conformance window, in {@link ChromaFormat#YUV420} or {@link ChromaFormat#MONOCHROME}. Samples of 10-bit streams
 * are rounded to 8 bits, {@code min((v + 2) >> 2, 255)}, unless {@link VvcDecoderOptions#highBitDepth()} is set, in
 * which case they are handed out as 10-bit {@link Yuv16Picture}s. Pictures of one stream may change size with
 * reference picture resampling. Each frame carries the timestamp of the packet in which its picture header arrived;
 * when that packet had no timestamp and a frame duration is known (from the packet or the track's frame rate), the
 * timestamp continues from the end of the previous frame. The color description comes from the VUI; components it
 * leaves unspecified default to BT.709 for pictures wider than 1024 or taller than 576 samples and to BT.601
 * otherwise. The preferred transfer characteristics of an alternative transfer characteristics SEI message replace
 * the VUI's for the rest of its coded layer video sequence, and the last mastering display colour volume and content
 * light level information SEI messages give the color description its {@link ColorInfo#masteringDisplay()} and
 * {@link ColorInfo#contentLightLevel()}. A frame is a keyframe when its picture is an IRAP picture or a GDR picture
 * whose recovery point is the picture itself.
 *
 * <p>The display SEI messages are reported in {@link VideoFrame#sideData()}: the frame packing arrangement and the
 * display orientation (with the persistence their flags give, until a new coded layer video sequence), the ATSC
 * A/53 caption data of user data registered SEI messages as {@link FrameSideData#closedCaptions()}, and, for a frame
 * picture, the scan and repetition of a frame-field information SEI message: a frame displayed field by field is
 * {@link FieldOrder#TOP_FIRST} or {@link FieldOrder#BOTTOM_FIRST}, with one {@linkplain FrameSideData#repeatedFields()
 * repeated field} for three display periods, and a frame displayed whole is {@link FieldOrder#PROGRESSIVE}, with two
 * repeated fields when doubled and four when tripled. With {@link VvcDecoderOptions#filmGrain()} set, the grain a film
 * grain characteristics SEI message describes (the frequency filtering model with additive blending, persisting as
 * the message says) is added to the whole decoded picture before cropping, by the bit-exact process of SMPTE RDD 5
 * seeded with the picture order count; messages of another model, of multiplicative blending or of a color
 * description that differs from the stream's leave the samples unchanged.
 *
 * <p>The fields of a field coded sequence ({@code sps_field_seq_flag}) are woven into frames twice as tall as a
 * field: in output order, two consecutive fields of opposite parity form a frame, the top field in its even rows.
 * The parity is the one a frame-field information SEI message gives; without one, fields alternate, the first
 * field of each frame taken as the top field. A field left without a pair is output as a frame whose other field is
 * mid-gray. A woven frame carries the timestamp of its first field and, when both durations are known, their sum,
 * and reports its first field's parity as its {@link FieldOrder}, the display messages of its first field and the
 * caption data of both.
 *
 * <p>Decoding starts at the first IRAP or GDR picture; the RASL pictures of a CRA picture that starts the stream
 * are skipped, and the pictures a GDR picture that starts the stream recovers are decoded but not output until its
 * recovery point. References the stream lacks are mid-gray stand-ins. A stream of the Main 10 4:4:4 profiles, deeper
 * than 10 bits or using the range extension tools raises {@link MediaException.Unsupported}. A corrupt slice raises
 * {@link MediaException.Malformed} once its picture is finished; the rest of the picture is still decoded and
 * decoding continues with the next picture. An exception thrown by the frame consumer reaches the caller unchanged.
 *
 * <p>Unless {@link VvcDecoderOptions#threads()} is 1, the substreams of a slice (its tiles and, with wavefront
 * parallel processing, the CTU rows of each tile, each row trailing the row above by one CTU) are decoded
 * concurrently, and the in-loop filters of each picture (the inverse luma mapping, the deblocking filter, SAO and ALF)
 * run in bands of CTU rows on the module's helper threads, as do the film grain rendering and the conversion to 8-bit
 * samples; the output does not depend on the thread count. Instances are not thread-safe.
 */
public final class VvcDecoder implements VideoDecoder {
    /**
     * The parity of a top field, whose rows are the even rows of its frame.
     */
    private static final int TOP = 0;

    /**
     * The parity of a bottom field, whose rows are the odd rows of its frame.
     */
    private static final int BOTTOM = 1;

    /**
     * The decoding process, which hands its pictures to {@link #emit(VvcPicture)} in output order.
     */
    private final VvcDecoderEngine engine;

    /**
     * The options this decoder was constructed with.
     */
    private final VvcDecoderOptions options;

    /**
     * The NAL unit length prefix size of packets, or 0 for Annex B packets.
     */
    private final int lengthSize;

    /**
     * The time base of the track's timestamps.
     */
    private final Rational timeBase;

    /**
     * The frame duration derived from the track frame rate, in time base units, or 0.
     */
    private final long trackFrameDuration;

    /**
     * The operation the parallel bands of a call run under.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The consumer of the call in progress, or {@code null} between calls.
     */
    private ConsumerFailure<VideoFrame> output;

    /**
     * Whether {@link #close()} was called.
     */
    private boolean closed;

    /**
     * The recycled output picture of frame pictures, 8-bit or 16-bit.
     */
    private Picture picture;

    /**
     * The recycled frame a field is woven into.
     */
    private Picture woven;

    /**
     * Whether {@link #woven} holds a first field waiting for its pair.
     */
    private boolean holding;

    /**
     * The parity of the held first field, {@link #TOP} or {@link #BOTTOM}.
     */
    private int heldParity;

    /**
     * The timestamp of the held first field, or {@link Packet#NO_TIMESTAMP}.
     */
    private long heldPts;

    /**
     * The duration of the held first field, or 0 when unknown.
     */
    private long heldDuration;

    /**
     * Whether the held first field is a key frame.
     */
    private boolean heldKeyframe;

    /**
     * The timestamp of the last frame output, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * The duration of the last frame output, or 0 when unknown.
     */
    private long lastDuration;

    /**
     * The side data of the held first field: its display messages and caption data.
     */
    private FrameSideData heldSideData = FrameSideData.NONE;

    /**
     * The film grain characteristics message in effect.
     */
    private final SeiPersistence<FilmGrainParameters> grainPersistence = new SeiPersistence<>();

    /**
     * The frame packing arrangement message in effect.
     */
    private final SeiPersistence<DisplaySei.FramePackingMessage> packingPersistence = new SeiPersistence<>();

    /**
     * The display orientation message in effect.
     */
    private final SeiPersistence<DisplaySei.OrientationMessage> orientationPersistence = new SeiPersistence<>();

    /**
     * The content colour volume message in effect.
     */
    private final SeiPersistence<H274Sei.Message<ContentColorVolume>> volumePersistence = new SeiPersistence<>();

    /**
     * The sample aspect ratio information message in effect.
     */
    private final SeiPersistence<H274Sei.Message<Rational>> aspectPersistence = new SeiPersistence<>();

    /**
     * The equirectangular projection message in effect.
     */
    private final SeiPersistence<H274Sei.Message<OmnidirectionalVideo.Projection>> equirectangularPersistence =
            new SeiPersistence<>();

    /**
     * The generalized cubemap projection message in effect.
     */
    private final SeiPersistence<H274Sei.Message<OmnidirectionalVideo.Projection>> cubemapPersistence =
            new SeiPersistence<>();

    /**
     * The sphere rotation message in effect.
     */
    private final SeiPersistence<H274Sei.Message<OmnidirectionalVideo.SphereRotation>> rotationPersistence =
            new SeiPersistence<>();

    /**
     * The region-wise packing message in effect.
     */
    private final SeiPersistence<H274Sei.Message<OmnidirectionalVideo.RegionWisePacking>> regionPersistence =
            new SeiPersistence<>();

    /**
     * The omnidirectional viewport message in effect.
     */
    private final SeiPersistence<H274Sei.Message<OmnidirectionalVideo.Viewports>> viewportPersistence =
            new SeiPersistence<>();

    /**
     * The renderer of film grain, created with the first picture that needs it.
     */
    private FilmGrainSynthesizer grainSynthesizer;

    /**
     * The recycled sample planes of a picture with film grain, laid out as the engine's.
     */
    private short[][] grained = new short[3][];

    /**
     * Constructs a decoder with {@link VvcDecoderOptions#defaults()}.
     *
     * @param track the VVC track
     * @throws IllegalArgumentException if the track is not a VVC track
     * @throws MediaException.Malformed if the track's configuration record is corrupt
     */
    public VvcDecoder(VideoTrack track) {
        this(track, VvcDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder.
     *
     * @param track the VVC track
     * @param options the decoder options
     * @throws IllegalArgumentException if the track is not a VVC track
     * @throws MediaException.Malformed if the track's configuration record is corrupt
     */
    public VvcDecoder(VideoTrack track, VvcDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.VVC) {
            throw new IllegalArgumentException(track.codec() + " is not a VVC track");
        }
        this.options = options;
        this.engine = new VvcDecoderEngine(options.limits(), options.hashVerification(), options.threads(),
                this::emit);
        this.timeBase = track.timeBase();
        this.trackFrameDuration = track.frameRate()
                .map(rate -> Math.max(0, rate.invert().rescale(1, timeBase)))
                .orElse(0L);
        this.lengthSize = loadConfiguration(track.codecConfig().orElse(null));
    }

    /**
     * {@return the options this decoder was constructed with}
     */
    public VvcDecoderOptions options() {
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
            engine.beginPacket(Packet.NO_TIMESTAMP, 0);
            if (NalUnits.looksLikeAnnexB(config)) {
                NalUnits.forEachAnnexB(config, engine::decodeNal);
                return 0;
            }
            var record = NalRecords.parseVvcStrict(config);
            for (var unit : record.parameterSets()) {
                engine.decodeNal(unit, 0, unit.length);
            }
            return record.lengthSize();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt VvcDecoderConfigurationRecord", exception);
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
                } else {
                    NalUnits.forEachAnnexB(data, engine::decodeNal);
                }
            } catch (RuntimeException exception) {
                throw handoff.translate("VVC packet", exception);
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
                }
            } catch (RuntimeException exception) {
                throw handoff.translate("VVC stream", exception);
            } finally {
                this.output = null;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        picture = null;
        woven = null;
        holding = false;
        grained = new short[3][];
        grainSynthesizer = null;
    }

    /**
     * Rejects a call on a closed decoder.
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
     * Converts a decoded picture into a {@link VideoFrame} and hands it to the consumer, or, for a field of a
     * sequence of fields, weaves it into the frame of its pair.
     *
     * @param pic the engine's picture, valid until this method returns
     */
    private void emit(VvcPicture pic) {
        var sps = pic.sps;
        var grain = grainPersistence.resolve(pic.newSequence, pic.filmGrain);
        var packing = packingPersistence.resolve(pic.newSequence, pic.framePacking);
        var orientation = orientationPersistence.resolve(pic.newSequence, pic.orientation);
        var viewing = viewingSideData(pic, packing);
        var planes = grain != null && options.filmGrain() ? withFilmGrain(pic, grain) : pic.planes;
        var subWidth = pic.chromaFormat == 1 ? 2 : 1;
        var subHeight = pic.chromaFormat == 1 ? 2 : 1;
        var left = pic.confLeft * subWidth;
        var top = pic.confTop * subHeight;
        var width = pic.width - left - pic.confRight * subWidth;
        var height = pic.height - top - pic.confBottom * subHeight;
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("vvc conformance window leaves no picture");
        }
        var format = pic.chromaFormat == 0 ? ChromaFormat.MONOCHROME : ChromaFormat.YUV420;
        var color = colorInfo(pic, width, height);
        var wide = options.highBitDepth() && pic.bitDepth > 8;
        var duration = pic.duration > 0 ? pic.duration : trackFrameDuration;
        if (sps.fieldSeq) {
            emitField(pic, planes, left, top, width, height, format, color, wide, duration, packing, orientation,
                    viewing);
            return;
        }
        releaseHeldField();
        if (!matches(picture, width, height, format, wide ? pic.bitDepth : 0, color)) {
            picture = wide ? Yuv16Picture.allocate(width, height, format, pic.bitDepth, false, color)
                    : YuvPicture.allocate(width, height, format, false, color);
        }
        write(pic, planes, picture, left, top, width, height, 0, 1);
        var pts = resolvePts(pic.pts, duration);
        var sideData = withCaptions(withViewing(DisplaySei.sideData(pic.fieldOrder, pic.repeatedFields, packing,
                orientation), viewing), pic.captions);
        output.accept(new VideoFrame(picture, pts, duration > 0 ? duration : Track.UNKNOWN_DURATION, pic.keyframe,
                sideData));
    }

    /**
     * Resolves the persistence of the Rec. ITU-T H.274 viewing messages of a picture being output (the content
     * colour volume, sample aspect ratio information and omnidirectional messages) and returns side data holding
     * them, with the ambient viewing environment of the picture's coded layer video sequence.
     *
     * @param pic the engine's picture
     * @param packing the frame packing arrangement message that applies to the picture, or {@code null}
     * @return side data holding only those entries
     */
    private FrameSideData viewingSideData(VvcPicture pic, DisplaySei.FramePackingMessage packing) {
        var volume = volumePersistence.resolve(pic.newSequence, pic.contentColorVolume);
        var aspect = aspectPersistence.resolve(pic.newSequence, pic.sampleAspectRatio);
        var equirectangular = equirectangularPersistence.resolve(pic.newSequence, pic.equirectangular);
        var cubemap = cubemapPersistence.resolve(pic.newSequence, pic.cubemap);
        var rotation = rotationPersistence.resolve(pic.newSequence, pic.sphereRotation);
        var region = regionPersistence.resolve(pic.newSequence, pic.regionWisePacking);
        var viewports = viewportPersistence.resolve(pic.newSequence, pic.viewports);
        var omnidirectional = H274Sei.omnidirectional(equirectangular != null ? equirectangular : cubemap, rotation,
                region, viewports, packing == null ? null : packing.packing());
        var builder = FrameSideData.builder();
        if (pic.ambientViewing != null) {
            builder.ambientViewing(pic.ambientViewing);
        }
        if (volume != null && volume.value() != null) {
            builder.contentColorVolume(volume.value());
        }
        if (aspect != null && aspect.value() != null) {
            builder.sampleAspectRatio(aspect.value());
        }
        if (omnidirectional != null) {
            builder.omnidirectional(omnidirectional);
        }
        return builder.build();
    }

    /**
     * Adds the viewing entries of {@link #viewingSideData} to side data.
     *
     * @param sideData the side data
     * @param viewing the viewing side data
     * @return the side data with the viewing entries
     */
    private static FrameSideData withViewing(FrameSideData sideData, FrameSideData viewing) {
        if (viewing.isEmpty()) {
            return sideData;
        }
        var builder = sideData.toBuilder();
        viewing.ambientViewing().ifPresent(builder::ambientViewing);
        viewing.contentColorVolume().ifPresent(builder::contentColorVolume);
        viewing.sampleAspectRatio().ifPresent(builder::sampleAspectRatio);
        viewing.omnidirectional().ifPresent(builder::omnidirectional);
        return builder.build();
    }

    /**
     * Adds caption data to side data, after the caption data it already holds.
     *
     * @param sideData the side data
     * @param captions the {@code cc_data} constructs to add, or {@code null}
     * @return the side data with the caption data
     */
    private static FrameSideData withCaptions(FrameSideData sideData, byte[] captions) {
        if (captions == null || captions.length == 0) {
            return sideData;
        }
        var held = sideData.closedCaptions().orElse(null);
        var whole = held == null ? captions : Arrays.copyOf(held, held.length + captions.length);
        if (held != null) {
            System.arraycopy(captions, 0, whole, held.length, captions.length);
        }
        return sideData.toBuilder().closedCaptions(whole).build();
    }

    /**
     * Returns the samples of a picture with the film grain a film grain characteristics message describes, rendered
     * on the whole decoded picture before any cropping, or its decoded samples when the message is not one the
     * renderer supports.
     *
     * @param pic the engine's picture
     * @param grain the film grain characteristics message that applies to the picture
     * @return the sample planes, recycled for the next picture
     */
    private short[][] withFilmGrain(VvcPicture pic, FilmGrainParameters grain) {
        var vui = pic.sps.vui;
        if (!grain.renderable(pic.bitDepth, pic.bitDepth, vui.fullRange, vui.colourDescriptionPresent,
                vui.colourPrimaries, vui.transferCharacteristics, vui.matrixCoefficients)) {
            return pic.planes;
        }
        if (grainSynthesizer == null) {
            grainSynthesizer = new FilmGrainSynthesizer();
        }
        var threads = options.threads() == 0 ? ParallelBands.THREADS : options.threads();
        if (grained.length != pic.planes.length) {
            grained = new short[pic.planes.length][];
        }
        for (var c = 0; c < pic.planes.length; c++) {
            var src = pic.planes[c];
            if (grained[c] == null || grained[c].length < src.length) {
                grained[c] = new short[src.length];
            }
            var w = pic.planeWidth[c];
            var h = pic.planeHeight[c];
            grainSynthesizer.apply(grain, pic.poc, c, pic.chromaFormat, pic.bitDepth, src, 0, w, grained[c], 0, w, w,
                    h, threads);
        }
        return grained;
    }

    /**
     * Weaves a field into the frame of its pair and outputs the frame once both fields are in it; a field of the
     * parity already held, or of another size, format or color, first releases the held field alone.
     *
     * @param pic the engine's picture of the field
     * @param planes the samples of the field, the engine's or a copy with film grain
     * @param left the first kept column, in luma samples
     * @param top the first kept row of the field, in luma samples
     * @param width the cropped width
     * @param height the cropped height of the field
     * @param format the chroma format of the output
     * @param color the color description of the output
     * @param wide whether the output keeps the samples' depth
     * @param duration the duration of the field, or 0 when unknown
     * @param packing the frame packing arrangement message that applies to the field, or {@code null}
     * @param orientation the display orientation message that applies to the field, or {@code null}
     * @param viewing the viewing side data of the field, from {@link #viewingSideData}
     */
    private void emitField(VvcPicture pic, short[][] planes, int left, int top, int width, int height,
                           ChromaFormat format, ColorInfo color, boolean wide, long duration,
                           DisplaySei.FramePackingMessage packing, DisplaySei.OrientationMessage orientation,
                           FrameSideData viewing) {
        var parity = pic.fieldParity >= 0 ? pic.fieldParity : holding ? 1 - heldParity : TOP;
        if (holding && (parity == heldParity || woven.width() != width || woven.height() != 2 * height
                || !matches(woven, width, 2 * height, format, wide ? pic.bitDepth : 0, color))) {
            releaseHeldField();
        }
        if (!holding) {
            if (!matches(woven, width, 2 * height, format, wide ? pic.bitDepth : 0, color)) {
                woven = wide ? Yuv16Picture.allocate(width, 2 * height, format, pic.bitDepth, false, color)
                        : YuvPicture.allocate(width, 2 * height, format, false, color);
            }
            write(pic, planes, woven, left, top, width, height, parity, 2);
            holding = true;
            heldParity = parity;
            heldPts = pic.pts;
            heldDuration = duration;
            heldKeyframe = pic.keyframe;
            heldSideData = withCaptions(withViewing(DisplaySei.sideData(parity == TOP ? FieldOrder.TOP_FIRST
                    : FieldOrder.BOTTOM_FIRST, 0, packing, orientation), viewing), pic.captions);
            return;
        }
        write(pic, planes, woven, left, top, width, height, parity, 2);
        heldSideData = withCaptions(heldSideData, pic.captions);
        var total = heldDuration > 0 && duration > 0 ? heldDuration + duration : 0;
        emitWoven(total);
    }

    /**
     * Outputs a held first field, its other field mid-gray, when a field is held.
     */
    private void releaseHeldField() {
        if (!holding) {
            return;
        }
        fillField(woven, 1 - heldParity);
        emitWoven(heldDuration > 0 ? 2 * heldDuration : 0);
    }

    /**
     * Outputs the woven frame with the timing of its first field and that field's parity as its field order.
     *
     * @param duration the duration of the frame, or 0 when unknown
     */
    private void emitWoven(long duration) {
        holding = false;
        var pts = resolvePts(heldPts, duration);
        var sideData = heldSideData;
        heldSideData = FrameSideData.NONE;
        output.accept(new VideoFrame(woven, pts, duration > 0 ? duration : Track.UNKNOWN_DURATION, heldKeyframe,
                sideData));
    }

    /**
     * Writes the cropped samples of a picture into every {@code step}-th row of a target from row {@code first},
     * rounded to 8 bits for an 8-bit target.
     *
     * @param pic the engine's picture
     * @param planes the samples to write, the engine's or a copy with film grain, laid out as the engine's
     * @param target the output picture
     * @param left the first kept column, in luma samples, a multiple of the chroma subsampling
     * @param top the first kept row, in luma samples, a multiple of the chroma subsampling
     * @param width the cropped width
     * @param height the cropped height
     * @param first the output row receiving the first kept row, 0 or 1
     * @param step the output rows per picture row: 1, or 2 to write the rows of a field
     */
    private void write(VvcPicture pic, short[][] planes, Picture target, int left, int top, int width, int height,
                       int first, int step) {
        var parallel = options.threads() != 1;
        for (var c = 0; c < pic.planes.length; c++) {
            var shiftX = c == 0 ? 0 : pic.shiftX;
            var shiftY = c == 0 ? 0 : pic.shiftY;
            var src = planes[c];
            var srcStride = pic.planeWidth[c];
            var srcOffset = (top >> shiftY) * srcStride + (left >> shiftX);
            var w = (width + (1 << shiftX) - 1) >> shiftX;
            var h = (height + (1 << shiftY) - 1) >> shiftY;
            if (target instanceof Yuv16Picture wide) {
                var dst = c == 0 ? wide.y() : c == 1 ? wide.u() : wide.v();
                var dstStride = c == 0 ? wide.yStride() : wide.uvStride();
                for (var y = 0; y < h; y++) {
                    System.arraycopy(src, srcOffset + y * srcStride, dst, (first + y * step) * dstStride, w);
                }
            } else {
                var narrow = (YuvPicture) target;
                var dst = c == 0 ? narrow.y() : c == 1 ? narrow.u() : narrow.v();
                var dstStride = c == 0 ? narrow.yStride() : narrow.uvStride();
                Narrow.plane(src, srcOffset, srcStride, dst, first * dstStride, dstStride * step, w, h,
                        pic.bitDepth - 8, parallel);
            }
        }
    }

    /**
     * Fills the rows of a field of a frame with mid-gray.
     *
     * @param target the frame
     * @param parity the parity of the field, {@link #TOP} or {@link #BOTTOM}
     */
    private static void fillField(Picture target, int parity) {
        if (target instanceof Yuv16Picture wide) {
            var gray = (short) (1 << (wide.bitDepth() - 1));
            fillRows(wide.y(), wide.yStride(), wide.height(), parity, gray);
            if (wide.u() != null) {
                var h = (wide.height() + 1) >> 1;
                fillRows(wide.u(), wide.uvStride(), h, parity, gray);
                fillRows(wide.v(), wide.uvStride(), h, parity, gray);
            }
        } else if (target instanceof YuvPicture narrow) {
            fillRows(narrow.y(), narrow.yStride(), narrow.height(), parity, (byte) 128);
            if (narrow.u() != null) {
                var h = (narrow.height() + 1) >> 1;
                fillRows(narrow.u(), narrow.uvStride(), h, parity, (byte) 128);
                fillRows(narrow.v(), narrow.uvStride(), h, parity, (byte) 128);
            }
        }
    }

    /**
     * Fills every second row of a 16-bit plane, from row {@code first}, with one value.
     *
     * @param plane the samples
     * @param stride the row stride, in samples
     * @param height the number of rows of the plane
     * @param first the first row filled, 0 or 1
     * @param value the sample value
     */
    private static void fillRows(short[] plane, int stride, int height, int first, short value) {
        for (var y = first; y < height; y += 2) {
            Arrays.fill(plane, y * stride, (y + 1) * stride, value);
        }
    }

    /**
     * Fills every second row of an 8-bit plane, from row {@code first}, with one value.
     *
     * @param plane the samples
     * @param stride the row stride, in samples
     * @param height the number of rows of the plane
     * @param first the first row filled, 0 or 1
     * @param value the sample value
     */
    private static void fillRows(byte[] plane, int stride, int height, int first, byte value) {
        for (var y = first; y < height; y += 2) {
            Arrays.fill(plane, y * stride, (y + 1) * stride, value);
        }
    }

    /**
     * Returns whether a recycled picture can hold a frame.
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
     * Derives the color description of a picture from the VUI of its sequence parameter set, the components it
     * leaves unspecified taken from BT.709 above 1024x576 and from BT.601 otherwise, with the transfer
     * characteristics an alternative transfer characteristics SEI message prefers and the mastering display and
     * content light level of the SEI messages in effect.
     *
     * @param pic the picture
     * @param width the cropped width
     * @param height the cropped height
     * @return the color description
     */
    private static ColorInfo colorInfo(VvcPicture pic, int width, int height) {
        var vui = pic.sps.vui;
        var fallback = width > 1024 || height > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var matrix = fallback.matrix();
        var primaries = fallback.primaries();
        var transfer = fallback.transfer();
        var signaledMatrix = MatrixCoefficients.of(vui.matrixCoefficients);
        if (signaledMatrix != MatrixCoefficients.UNSPECIFIED) {
            matrix = signaledMatrix;
        }
        var signaledPrimaries = ColorPrimaries.of(vui.colourPrimaries);
        if (signaledPrimaries != ColorPrimaries.UNSPECIFIED) {
            primaries = signaledPrimaries;
        }
        var signaledTransfer = TransferCharacteristics.of(vui.transferCharacteristics);
        if (signaledTransfer != TransferCharacteristics.UNSPECIFIED) {
            transfer = signaledTransfer;
        }
        if (pic.preferredTransfer >= 0) {
            var preferred = TransferCharacteristics.of(pic.preferredTransfer);
            if (preferred != TransferCharacteristics.UNSPECIFIED) {
                transfer = preferred;
            }
        }
        var info = new ColorInfo(matrix, vui.fullRange ? ColorRange.FULL : ColorRange.LIMITED, primaries, transfer);
        if (vui.chromaSampleLocType >= 0) {
            info = info.withChromaSiting(ChromaSiting.of(vui.chromaSampleLocType));
        }
        if (pic.masteringDisplay != null) {
            info = info.withMasteringDisplay(pic.masteringDisplay);
        }
        if (pic.contentLightLevel != null) {
            info = info.withContentLightLevel(pic.contentLightLevel);
        }
        return info;
    }

    /**
     * Resolves the timestamp of a frame: its own when known, otherwise the end of the previous frame when a
     * duration is known.
     *
     * @param pts the timestamp of the frame's packet, or {@link Packet#NO_TIMESTAMP}
     * @param duration the duration of the frame, or 0 when unknown
     * @return the timestamp, or {@link Packet#NO_TIMESTAMP} when it cannot be resolved
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
}
