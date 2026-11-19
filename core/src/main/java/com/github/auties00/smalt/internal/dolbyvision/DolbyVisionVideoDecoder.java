package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Decodes a Dolby Vision stream without a backward compatible base layer: decodes the base layer with another decoder
 * and reconstructs each picture from the RPU of its access unit, to BT.2020 RGB coded with the SMPTE ST 2084 inverse
 * EOTF.
 *
 * <p>An RPU belongs to the frames of its packet's timestamp. A packet without a valid RPU takes the mapping and the
 * display management data of the one before it in decoding order, as does an RPU whose mapping regresses the luma
 * component, which ETSI GS CCM 001 forbids; that packet still decodes, then fails with
 * {@link MediaException.Malformed}. A frame output before the first valid RPU, which only happens at the start of a
 * stream, is dropped and the packet that held it fails the same way, so the base layer decoder keeps its state.
 *
 * <p>An enhancement layer found in band (HEVC NAL units of type 63) fails its packet with
 * {@link MediaException.Unsupported}, since the pictures would be wrong without it.
 *
 * <p>A track that declares no Dolby Vision configuration starts as plain video, and reconstruction starts at the
 * first key frame from its first valid RPU on when that RPU is of profile 5 and the stream has a single layer. Any
 * other first RPU ends the check.
 *
 * @implNote This implementation holds the mappings of the pictures the base layer decoder has not output yet; a
 * decoder holds a bounded number, and {@link #PENDING} bounds them in case one drops pictures.
 */
public final class DolbyVisionVideoDecoder implements VideoDecoder {
    /**
     * The largest number of pictures whose mappings are held. A base layer decoder holds at most 16 pictures back.
     */
    private static final int PENDING = 64;

    /**
     * The identifiers of mappings and of display management data ({@code vdr_rpu_id},
     * {@code affected_dm_metadata_id}) range up to 15.
     */
    private static final int IDENTIFIERS = 16;

    /**
     * Creates the base layer decoders.
     */
    private final Function<VideoDecoderOptions, VideoDecoder> factory;

    /**
     * The options the caller gave.
     */
    private final VideoDecoderOptions options;

    /**
     * Finds the RPU of each packet.
     */
    private final DolbyVisionRpuReader reader;

    /**
     * The integer stages.
     */
    private final DolbyVisionComposer composer = new DolbyVisionComposer();

    /**
     * The color stages.
     */
    private final DolbyVisionColor color = new DolbyVisionColor();

    /**
     * The mappings by {@code vdr_rpu_id}.
     */
    private final DolbyVisionRpu.Mapping[] mappings = new DolbyVisionRpu.Mapping[IDENTIFIERS];

    /**
     * The conversion matrices by display management identifier.
     */
    private final DolbyVisionColor.Matrices[] matrices = new DolbyVisionColor.Matrices[IDENTIFIERS];

    /**
     * The timestamps of the packets whose pictures have not been output, in insertion order.
     */
    private final long[] pendingPts = new long[PENDING];

    /**
     * What reconstructs each picture of {@link #pendingPts}.
     */
    private final Work[] pendingWork = new Work[PENDING];

    /**
     * The number of entries of {@link #pendingPts}.
     */
    private int pendingCount;

    /**
     * What applies to the next packet without a valid RPU, or {@code null} before the first valid one.
     */
    private Work active;

    /**
     * The decoder of the base layer pictures, or of the plain video while not reconstructing.
     */
    private VideoDecoder decoder;

    /**
     * Whether the frames of {@link #decoder} are reconstructed.
     */
    private boolean reconstructing;

    /**
     * Whether the track declares nothing and its RPUs are still checked.
     */
    private boolean checking;

    /**
     * Whether a first RPU of profile 5 waits for a key frame.
     */
    private boolean switchPending;

    /**
     * Whether no packet has been decoded.
     */
    private boolean first = true;

    /**
     * The consumer of the call in progress.
     */
    private Consumer<VideoFrame> output;

    /**
     * Reconstructs the frames of the base layer decoder.
     */
    private final Consumer<VideoFrame> reconstructor = this::reconstruct;

    /**
     * The reconstructed luma plane.
     */
    private short[] y = new short[0];

    /**
     * The reconstructed first chroma plane.
     */
    private short[] cb = new short[0];

    /**
     * The reconstructed second chroma plane.
     */
    private short[] cr = new short[0];

    /**
     * The output picture, recycled for every frame.
     */
    private Picture picture;

    /**
     * The color description of {@link #picture}.
     */
    private ColorInfo pictureColor;

    /**
     * The color description of the base layer that {@link #pictureColor} was derived from.
     */
    private ColorInfo baseColor;

    /**
     * Whether the RPU of the packet in progress has a mapping the composer cannot apply.
     */
    private boolean rejected;

    /**
     * What reconstructs the pictures of one packet.
     *
     * @param mapping the curves
     * @param depth the reconstructed bit depth
     * @param matrices the conversion matrices
     */
    private record Work(DolbyVisionRpu.Mapping mapping, int depth, DolbyVisionColor.Matrices matrices) {
    }

    /**
     * Constructs a decoder of a stream.
     *
     * @param codec {@link Codec#HEVC} or {@link Codec#AV1}
     * @param config the track's codec configuration, or {@code null}
     * @param declared whether the track declares a configuration without a backward compatible base layer; when
     * {@code false}, the decoder is plain video until an RPU says otherwise
     * @param options the caller's options
     * @param factory creates a base layer decoder for options
     */
    public DolbyVisionVideoDecoder(Codec codec, byte[] config, boolean declared, VideoDecoderOptions options,
                                   Function<VideoDecoderOptions, VideoDecoder> factory) {
        this.options = options;
        this.factory = factory;
        this.reader = new DolbyVisionRpuReader(codec, config);
        this.reconstructing = declared;
        this.checking = !declared;
        this.decoder = factory.apply(declared ? baseLayerOptions() : options.toBuilder().dolbyVisionBaseLayer(true).build());
    }

    /**
     * {@return the options of a decoder whose pictures are reconstructed, which needs the base layer's full depth}
     */
    private VideoDecoderOptions baseLayerOptions() {
        return options.toBuilder().highBitDepth(true).dolbyVisionBaseLayer(true).build();
    }

    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        if (checking) {
            check(packet, output);
        }
        first = false;
        if (!reconstructing) {
            decoder.decode(packet, output);
            return;
        }
        var missing = !enqueue(packet);
        this.output = output;
        try {
            decoder.decode(packet, reconstructor);
        } finally {
            this.output = null;
        }
        if (missing) {
            throw new MediaException.Malformed("Dolby Vision access unit without an RPU at the start of the stream");
        }
        if (rejected) {
            throw new MediaException.Malformed("Dolby Vision regression mapping of the luma component");
        }
    }

    @Override
    public void flush(Consumer<VideoFrame> output) {
        if (!reconstructing) {
            decoder.flush(output);
            return;
        }
        this.output = output;
        try {
            decoder.flush(reconstructor);
        } finally {
            this.output = null;
            for (var index = 0; index < pendingCount; index++) {
                pendingWork[index] = null;
            }
            pendingCount = 0;
        }
    }

    @Override
    public void close() {
        decoder.close();
    }

    /**
     * Checks a packet of a track that declares nothing, and starts reconstructing once its RPUs ask for it.
     *
     * @param packet the next packet in decoding order
     * @param output the consumer of the frames of the replaced decoder
     */
    private void check(Packet packet, Consumer<VideoFrame> output) {
        var rpu = reader.read(packet.data());
        if (rpu != null) {
            if (!switchPending) {
                switchPending = rpu.profile() == 5 && !reader.layered();
                checking = switchPending;
            }
            if (switchPending) {
                update(rpu);
            }
        }
        if (switchPending && (first || packet.keyframe())) {
            decoder.flush(output);
            decoder.close();
            decoder = factory.apply(baseLayerOptions());
            reconstructing = true;
            checking = false;
            switchPending = false;
        }
    }

    /**
     * Records what reconstructs the pictures of a packet.
     *
     * @param packet the next packet in decoding order
     * @return {@code false} when neither the packet nor one before it has an RPU that applies
     */
    private boolean enqueue(Packet packet) {
        var rpu = reader.read(packet.data());
        if (reader.enhancementLayer()) {
            // TODO: compose an enhancement layer (ETSI GS CCM 001, clause 5.4.3) when a stream without a backward
            // compatible base layer has one; the single-track streams of the current profiles do not
            throw new MediaException.Unsupported("dolby vision reconstruction",
                    "a Dolby Vision stream without a backward compatible base layer carries an enhancement layer");
        }
        rejected = false;
        if (rpu != null) {
            update(rpu);
        }
        if (active == null) {
            return false;
        }
        if (pendingCount == PENDING) {
            System.arraycopy(pendingPts, 1, pendingPts, 0, PENDING - 1);
            System.arraycopy(pendingWork, 1, pendingWork, 0, PENDING - 1);
            pendingCount--;
        }
        pendingPts[pendingCount] = packet.pts();
        pendingWork[pendingCount] = active;
        pendingCount++;
        return true;
    }

    /**
     * Applies an RPU to the state of the stream.
     *
     * @param rpu the RPU
     */
    private void update(DolbyVisionRpu rpu) {
        var mapping = rpu.mapping();
        if (mapping != null) {
            for (var piece : mapping.curves()[0].pieces()) {
                if (piece.mmr()) {
                    rejected = true;
                    return;
                }
            }
            if (mapping.rpuId() >= 0 && mapping.rpuId() < IDENTIFIERS) {
                mappings[mapping.rpuId()] = mapping;
            }
        } else if (rpu.previousRpuId() >= 0 && rpu.previousRpuId() < IDENTIFIERS) {
            mapping = mappings[rpu.previousRpuId()];
        }
        if (mapping == null) {
            if (active == null) {
                return;
            }
            mapping = active.mapping();
        }
        var current = active != null ? active.matrices() : DolbyVisionColor.Matrices.DEFAULTS;
        var management = rpu.displayManagement();
        if (management != null) {
            if (!management.compressed()) {
                var parsed = DolbyVisionColor.Matrices.of(management);
                current = parsed;
                if (management.affectedId() >= 0 && management.affectedId() < IDENTIFIERS) {
                    matrices[management.affectedId()] = parsed;
                }
            }
            if (management.currentId() >= 0 && management.currentId() < IDENTIFIERS
                    && matrices[management.currentId()] != null) {
                current = matrices[management.currentId()];
            }
        }
        active = new Work(mapping, rpu.vdrBitDepth(), current);
    }

    /**
     * Reconstructs one frame and hands it to the consumer of the call in progress.
     *
     * @param frame the base layer frame
     */
    private void reconstruct(VideoFrame frame) {
        if (!(frame.picture() instanceof Yuv16Picture base) || base.bitDepth() != DolbyVisionComposer.BASE_DEPTH
                || base.chromaFormat() != ChromaFormat.YUV420) {
            throw new MediaException.Unsupported("dolby vision reconstruction",
                    "the base layer of a Dolby Vision stream must be 10-bit 4:2:0");
        }
        var work = takeWork(frame.pts());
        if (work == null) {
            return;
        }
        var width = base.width();
        var height = base.height();
        var chromaSize = base.chromaWidth() * base.chromaHeight();
        if (y.length != width * height || cb.length != chromaSize) {
            // the reconstructed planes, the upsampled chroma and a 4:4:4 picture of 16-bit samples
            LimitChecks.checkAllocation(options.limits(), width * (long) height * 2 * 4 + chromaSize * 2L * 2);
            y = new short[width * height];
            cb = new short[chromaSize];
            cr = new short[chromaSize];
        }
        composer.prepare(work.mapping(), work.depth());
        composer.compose(base, y, cb, cr, options.threads());
        var target = target(base, work.depth());
        color.convert(y, cb, cr, work.depth(), work.matrices(), target, options.threads());
        output.accept(new VideoFrame(target, frame.pts(), frame.duration(), frame.keyframe(), frame.sideData()));
    }

    /**
     * Takes the entry of the frame's timestamp, or else the latest entry before it, and removes every earlier entry,
     * since frames arrive in presentation order.
     *
     * @param pts the frame's timestamp
     * @return what reconstructs the frame, or {@code null} when no valid RPU has been read
     */
    private Work takeWork(long pts) {
        var best = -1;
        for (var index = 0; index < pendingCount; index++) {
            var candidate = pendingPts[index];
            if (candidate == pts) {
                best = index;
                break;
            }
            if (candidate < pts && (best < 0 || candidate >= pendingPts[best])) {
                best = index;
            }
        }
        if (best < 0) {
            // a later frame of a packet whose entry the first one took, or a frame older than every entry
            return active;
        }
        var work = pendingWork[best];
        var kept = 0;
        for (var index = 0; index < pendingCount; index++) {
            if (index != best && pendingPts[index] >= pts) {
                pendingPts[kept] = pendingPts[index];
                pendingWork[kept] = pendingWork[index];
                kept++;
            }
        }
        for (var index = kept; index < pendingCount; index++) {
            pendingWork[index] = null;
        }
        pendingCount = kept;
        return work;
    }

    /**
     * {@return the recycled output picture of a frame}
     *
     * @param base the base layer picture
     * @param depth the reconstructed bit depth
     */
    private Picture target(Yuv16Picture base, int depth) {
        var color = base.color();
        var description = pictureColor;
        if (!color.equals(baseColor)) {
            description = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ);
            if (color.masteringDisplay().isPresent()) {
                description = description.withMasteringDisplay(color.masteringDisplay().get());
            }
            if (color.contentLightLevel().isPresent()) {
                description = description.withContentLightLevel(color.contentLightLevel().get());
            }
            baseColor = color;
        }
        var outputDepth = options.highBitDepth() ? Math.max(depth, DolbyVisionComposer.BASE_DEPTH) : 8;
        if (picture != null && picture.width() == base.width() && picture.height() == base.height()
                && picture.bitDepth() == outputDepth && description == pictureColor) {
            return picture;
        }
        pictureColor = description;
        picture = options.highBitDepth()
                ? Yuv16Picture.allocate(base.width(), base.height(), ChromaFormat.YUV444, outputDepth, false, description)
                : YuvPicture.allocate(base.width(), base.height(), ChromaFormat.YUV444, false, description);
        return picture;
    }
}
