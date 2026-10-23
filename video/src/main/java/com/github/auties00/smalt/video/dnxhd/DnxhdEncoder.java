package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.internal.Vc3Compression;
import com.github.auties00.smalt.video.dnxhd.internal.Vc3FrameEncoder;
import com.github.auties00.smalt.video.intra.internal.IntraEncoderCore;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into Avid DNxHD and DNxHR frames, the VC-3 format of SMPTE ST 2019-1.
 *
 * <p>The DNxHR families encode any picture size up to 16384x16384 at their compression ID (LB, SQ
 * and HQ at 8 bits 4:2:2 or 4:2:0, HQX at 10 or 12 bits 4:2:2 or 4:2:0, 444 at 10 or 12 bits 4:4:4
 * Y'CbCr or RGB), progressive only. {@link DnxhdProfile#DNXHD} encodes the HD rasters of Table C.1
 * (1920x1080, 1440x1080, 1280x720 and 960x720, progressive or, for the interlaced IDs, two fields,
 * or one frame of adaptive field and frame macroblocks for ID 1260) at 8 or 10 bits, choosing the
 * compression ID from the picture size, scan, bit depth and {@link DnxhdEncoderOptions#bitrate()}
 * unless {@link DnxhdEncoderOptions#compressionId()} names one. With
 * {@link DnxhdEncoderOptions#alpha()} every frame also carries the picture's alpha channel, in band
 * or after the video as {@link DnxhdEncoderOptions#alphaStorage()} chooses. Every frame takes
 * exactly the size of its compression ID, plus the alpha and its four-byte trailer when the alpha
 * is stored out of band, so that all frames of a stream have one size. Pictures of any
 * representation are accepted and converted as needed.
 *
 * <p>Every frame is independent: each {@link #encode(VideoFrame, Consumer)} emits one key-frame
 * packet carrying the frame's timestamp and duration, and {@link #flush(Consumer)} has nothing to
 * drain. {@link #codecConfig()} is the QuickTime sample entry type, {@code AVdn} or {@code AVdh},
 * followed for alpha stored out of band by the {@code AALP} extension of Avid's sample
 * descriptions, which QuickTime muxers write into the sample entry.
 *
 * <p>Instances are not thread-safe. Large frames are encoded in parallel macroblock rows on the
 * module's shared helper threads unless {@link DnxhdEncoderOptions#threads()} is {@code 1}; the
 * output does not depend on the threading.
 *
 * @apiNote DNxHD and DNxHR are Avid technologies that Avid licenses; see the licensing note of the
 * project's format documentation before distributing files made with this encoder.
 */
public final class DnxhdEncoder implements VideoEncoder {
    /**
     * The size of the {@code AALP} atom of an Avid sample description.
     */
    private static final int ALPHA_ATOM_SIZE = 28;

    private final int width;

    private final int height;

    private final DnxhdEncoderOptions options;

    /**
     * The compression ID in use.
     */
    private final Vc3Compression compression;

    /**
     * The sample bit depth in use.
     */
    private final int depth;

    /**
     * The calls of this encoder.
     */
    private final IntraEncoderCore core;

    /**
     * Constructs an encoder for a picture size under the {@link DnxhdEncoderOptions#defaults()}.
     *
     * @param width the picture width
     * @param height the picture height
     * @throws IllegalArgumentException if no compression ID of the family codes the picture
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     */
    public DnxhdEncoder(int width, int height) {
        this(width, height, DnxhdEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder for a picture size.
     *
     * @param width the picture width, from 1 to 16384
     * @param height the picture height, from 1 to 16384
     * @param options the encoding options
     * @throws IllegalArgumentException if a dimension is out of range, or no compression ID of the
     * family codes the picture size, scan, bit depth, color format, chroma layout and alpha
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public DnxhdEncoder(int width, int height, DnxhdEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (width < 1 || height < 1 || width > 16384 || height > 16384) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        this.width = width;
        this.height = height;
        var profile = options.profile();
        this.depth = options.bitDepth().orElse(profile == DnxhdProfile.DNXHD && options.rgb() ? 10 : profile.defaultBitDepth());
        this.compression = resolve(width, height, options, depth);
        if (options.rgb() && !compression.chroma444()) {
            throw new IllegalArgumentException("only the 4:4:4 compression IDs code RGB");
        }
        if (compression.chroma444() && !compression.resolutionIndependent() && !options.rgb()) {
            throw new IllegalArgumentException("compression ID 1256 codes RGB only");
        }
        var chroma = options.chromaFormat().orElse(compression.chroma444() ? ChromaFormat.YUV444 : ChromaFormat.YUV422);
        if ((chroma == ChromaFormat.YUV444) != compression.chroma444()
                || chroma == ChromaFormat.YUV420 && !compression.permits420()) {
            throw new IllegalArgumentException("compression ID " + compression.cid() + " does not code " + chroma);
        }
        if (compression.resolutionIndependent() && (width & 1) != 0 && !compression.chroma444()) {
            throw new IllegalArgumentException("4:2:2 and 4:2:0 DNxHR need an even width, got " + width);
        }
        if (chroma == ChromaFormat.YUV420 && (height & 1) != 0) {
            throw new IllegalArgumentException("4:2:0 DNxHR needs an even height, got " + height);
        }
        if (options.alpha() && options.alphaStorage() == DnxhdEncoderOptions.AlphaStorage.IN_BAND
                && !compression.permitsAlpha()) {
            throw new IllegalArgumentException("compression ID " + compression.cid() + " does not code alpha in band");
        }
        if (options.losslessAlpha() && !compression.permitsLosslessAlpha()) {
            throw new IllegalArgumentException("only DNxHR 444 and compression ID 1256 code lossless alpha");
        }
        var frames = new Vc3FrameEncoder(width, height, compression, depth, options.rgb(),
                options.rgb() && options.alternateColorSpace(), options.color(), chroma, options.alpha(),
                options.losslessAlpha(), options.alphaStorage(), options.premultipliedAlpha(), options.threads(),
                options.limits());
        this.core = new IntraEncoderCore(width, height, options.frameRate(), options.timeBase(), frames::encode);
    }

    /**
     * Finds the compression ID that codes a picture.
     *
     * @param width the picture width
     * @param height the picture height
     * @param options the encoder settings, whose profile, bitrate and compression ID constrain the choice
     * @param depth the bit depth
     * @return the compression ID
     * @throws IllegalArgumentException if no ID fits
     */
    private static Vc3Compression resolve(int width, int height, DnxhdEncoderOptions options, int depth) {
        var profile = options.profile();
        var interlaced = options.fieldOrder().isInterlaced();
        if (profile.compressionId().isPresent()) {
            var compression = Vc3Compression.of(profile.compressionId().getAsInt()).orElseThrow();
            if (interlaced) {
                throw new IllegalArgumentException("DNxHR codes progressive frames only");
            }
            if (!compression.permits(depth)) {
                throw new IllegalArgumentException(profile + " does not code " + depth + "-bit samples");
            }
            return compression;
        }
        if (options.fieldOrder() == FieldOrder.BOTTOM_FIRST) {
            throw new IllegalArgumentException("interlaced DNxHD codes the top field first");
        }
        Vc3Compression best = null;
        var frameRate = options.frameRate().toDouble();
        for (var candidate : Vc3Compression.ALL) {
            if (candidate.resolutionIndependent() || candidate.width() != width
                    || candidate.lines() * candidate.codingUnits() != height || candidate.interlaced() != interlaced
                    || !candidate.permits(depth) || candidate.chroma444() != options.rgb()) {
                continue;
            }
            if (options.compressionId().isPresent()) {
                if (candidate.cid() == options.compressionId().getAsInt()) {
                    return candidate;
                }
                continue;
            }
            if (best == null) {
                best = candidate;
            } else if (options.bitrate().isPresent()) {
                var target = options.bitrate().getAsLong();
                if (Math.abs(candidate.frameSize() * 8.0 * frameRate - target) < Math.abs(best.frameSize() * 8.0 * frameRate - target)) {
                    best = candidate;
                }
            } else if (candidate.frameSize() > best.frameSize()) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no DNxHD compression ID codes " + width + "x" + height + " "
                    + options.fieldOrder() + " at " + depth + " bits" + (options.rgb() ? " RGB" : "")
                    + options.compressionId().stream().mapToObj(id -> " with ID " + id).findFirst().orElse("")
                    + "; use a DNxHR profile for other sizes");
        }
        return best;
    }

    /**
     * {@return the encoding options}
     */
    public DnxhdEncoderOptions options() {
        return options;
    }

    /**
     * {@return the compression ID the encoder codes, from 1235 to 1274}
     */
    public int compressionId() {
        return compression.cid();
    }

    /**
     * {@return the sample bit depth the encoder codes: 8, 10 or 12}
     */
    public int bitDepth() {
        return depth;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#DNXHD}
     */
    @Override
    public Codec codec() {
        return Codec.DNXHD;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Rational timeBase() {
        return options.timeBase();
    }

    /**
     * {@inheritDoc}
     *
     * <p>For alpha stored out of band the type is followed by an {@code AALP} atom, 28 bytes
     * that name the storage of the alpha: {@code AUA8} for 8-bit samples, {@code AUAX} for 8-bit
     * samples in an area sized for 10-bit ones, or the sample entry type for coded alpha.
     * {@snippet :
     * u32 size = 28, 'AALP', 'AALP', '0001', u32 1, fourcc storage, u32 0
     * }
     *
     * @return the profile's sample entry type in ASCII ({@code AVdh} for DNxHR, {@code AVdn} for
     * DNxHD), with the alpha atom for alpha stored out of band
     */
    @Override
    public Optional<byte[]> codecConfig() {
        var fourCc = options.profile().fourCc();
        if (!options.alpha() || options.alphaStorage() == DnxhdEncoderOptions.AlphaStorage.IN_BAND) {
            return Optional.of(fourCc.getBytes(StandardCharsets.US_ASCII));
        }
        var storage = options.alphaStorage() == DnxhdEncoderOptions.AlphaStorage.AVID_CODED ? fourCc
                : depth == 8 ? "AUA8" : "AUAX";
        var config = new byte[4 + ALPHA_ATOM_SIZE];
        ByteArrays.put32be(config, 4, ALPHA_ATOM_SIZE);
        var text = "AALPAALP0001".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(fourCc.getBytes(StandardCharsets.US_ASCII), 0, config, 0, 4);
        System.arraycopy(text, 0, config, 8, text.length);
        ByteArrays.put32be(config, 20, 1);
        System.arraycopy(storage.getBytes(StandardCharsets.US_ASCII), 0, config, 24, 4);
        return Optional.of(config);
    }

    /**
     * Encodes one frame into one key-frame packet.
     *
     * @param frame the next frame in presentation order
     * @param output receives the packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws MediaException.Unsupported if the picture is RGB, the encoder codes Y'CbCr and the
     * matrix of {@link DnxhdEncoderOptions#color()} is the identity matrix
     */
    @Override
    public void encode(VideoFrame frame, Consumer<Packet> output) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        output.accept(encodeFrame(frame));
    }

    /**
     * Encodes one frame and returns its packet.
     *
     * @param frame the picture and its timestamp
     * @return the key-frame packet
     * @throws IllegalArgumentException if the picture size differs from the encoder's or the
     * timestamp does not follow the previous one
     * @throws MediaException.Unsupported if the picture is RGB, the encoder codes Y'CbCr and the
     * matrix of {@link DnxhdEncoderOptions#color()} is the identity matrix
     */
    public Packet encodeFrame(VideoFrame frame) {
        return core.encode(frame);
    }

    /**
     * Does nothing: every DNxHD and DNxHR frame is coded independently, so every packet is a key
     * frame.
     */
    @Override
    public void requestKeyframe() {
    }

    /**
     * Does nothing: DNxHD frames are never held back.
     *
     * @param output receives no packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
    }
}
