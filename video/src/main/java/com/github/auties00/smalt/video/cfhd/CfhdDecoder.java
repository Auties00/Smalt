/* Ports cineform-sdk (Codec/decoder.c DecodeSample, DecodeSampleGroup, DecodeSampleIntraFrame, UpdateCodecState,
 * DecodeSampleLowPassBand and ReconstructWaveletBand, Codec/frame.c ConvertLowpass16sRGB48ToRGB48 and the alpha
 * decompanding of ConvertLowpass16sRGBA64ToRGBA64, Codec/DemoasicFrames.cpp UpdateCFHDDATA for the Bayer phase and
 * encode curve metadata: the CineForm sample layout and reconstruction).
 * Copyright GoPro Inc; Apache License 2.0 or MIT. Modified for Smalt. */
package com.github.auties00.smalt.video.cfhd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.cfhd.internal.CfhdBands;
import com.github.auties00.smalt.video.cfhd.internal.CfhdBayer;
import com.github.auties00.smalt.video.cfhd.internal.CfhdWavelets;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Decodes GoPro CineForm ({@link Codec#CFHD}), the wavelet intermediate codec, as found in QuickTime, AVI and Matroska
 * files.
 *
 * <p>Intra frames (spatial 2/6 wavelets whose finest level is a spatial wavelet for a progressive frame and a frame
 * wavelet between its fields for an interlaced one) and groups of two frames coded together with a temporal wavelet
 * decode: 4:2:2 Y'CbCr (coded as Y', Cr and Cb channels) to a {@link Yuv16Picture} (a {@link YuvPicture} at 8 bits)
 * at the coded precision, and RGB 4:4:4 and RGBA 4:4:4:4 to an {@link Rgba16Picture} whose 16-bit samples repeat the
 * high bits of the coded precision, alpha decompanded. Highpass coefficients of every codeset decode, companded,
 * quantized, difference coded or replaced by peak tables. A group yields its first frame for its first packet and its
 * second frame for the short packet that follows; an intra frame yields one frame and a sequence header or trailer
 * none. Frames carry the packet's timestamps; {@link #flush(Consumer)} has nothing to drain. The Y'CbCrA 4:4:4:4
 * format, which GoPro's decoder does not implement either, raises {@link MediaException.Unsupported}.
 *
 * <p>CineForm RAW samples code a color filter array as four half-size channels (G, R-G, B-G and the difference of
 * the two greens) and decode as {@link CfhdDecoderOptions#bayerOutput()} asks: demosaiced to an {@link Rgba16Picture}
 * (the default) or as the mosaic itself, a 16-bit {@link ChromaFormat#MONOCHROME} {@link Yuv16Picture} of linear
 * photosites whose color description is full range with unspecified primaries and matrix and, when the encoder's
 * curve was undone, the linear transfer (unspecified otherwise). The arrangement of the array comes from the sample's
 * {@code BFMT} metadata and {@link #bayerPhase()} reports it for every frame; the encoder's curve comes from its
 * {@code ECRV} and {@code PCRV} metadata. Both persist from sample to sample until a sample changes them, as in
 * GoPro's decoder. Mosaics equal GoPro's 16-bit Bayer output and demosaiced pictures its default 16-bit RGB output
 * when the sample carries no white balance, color matrix or look; for a source coded with the linear curve, whose
 * photosites GoPro's decoder returns divided by 4, the mosaic keeps them at full scale. Uncompressed CineForm RAW
 * samples, which GoPro's decoder fails to decode, raise {@link MediaException.Unsupported}.
 *
 * <p>Pixels of the other formats are the reconstruction of the coded wavelets without the output-format-dependent
 * rounding offsets of GoPro's decoder, so they may differ from it by a level of the coded precision. A corrupt sample
 * raises {@link MediaException.Malformed}; a group's second packet without its first raises it too.
 *
 * <p>Instances are not thread-safe.
 */
public final class CfhdDecoder implements VideoDecoder {
    private static final int SAMPLE_FRAME = 1;

    private static final int SAMPLE_GROUP = 2;

    private static final int SAMPLE_CHANNEL = 3;

    private static final int SAMPLE_GROUP_TRAILER = 6;

    private static final int SAMPLE_SEQUENCE_HEADER = 7;

    private static final int SAMPLE_SEQUENCE_TRAILER = 8;

    private static final int SAMPLE_INTRA = 9;

    /**
     * The wavelet of each subband of an intra frame.
     */
    private static final int[] INTRA_WAVELET = {2, 2, 2, 2, 1, 1, 1, 0, 0, 0};

    /**
     * The band of each subband of an intra frame.
     */
    private static final int[] INTRA_BAND = {0, 1, 2, 3, 1, 2, 3, 1, 2, 3};

    /**
     * The wavelet of each subband of a group of two frames.
     */
    private static final int[] GROUP_WAVELET = {5, 5, 5, 5, 4, 4, 4, 3, 3, 3, 3, 1, 1, 1, 0, 0, 0};

    /**
     * The band of each subband of a group of two frames.
     */
    private static final int[] GROUP_BAND = {0, 1, 2, 3, 1, 2, 3, 0, 1, 2, 3, 1, 2, 3, 1, 2, 3};

    private static final int FORMAT_YUV_422 = 1;

    private static final int FORMAT_BAYER = 2;

    private static final int FORMAT_RGB_444 = 3;

    private static final int FORMAT_RGBA_4444 = 4;

    private static final int FORMAT_YUVA_4444 = 5;

    /**
     * One wavelet level of a channel: its band size and four bands.
     */
    private static final class Wavelet {
        int width;
        int height;
        final short[][] bands = new short[4][];
        final boolean[] present = new boolean[4];

        short[] band(int index, int samples) {
            if (bands[index] == null || bands[index].length < samples) {
                bands[index] = new short[samples];
            }
            return bands[index];
        }
    }

    private static final int TAG_UNCOMPRESSED = 0x2300;

    private static final int TAG_METADATA = 0x4002;

    /**
     * The metadata tag of the Bayer phase, {@code BFMT} as a little-endian four character code.
     */
    private static final int METADATA_BAYER_FORMAT = fourCc("BFMT");

    /**
     * The metadata tag of the encoder's curve, {@code ECRV}.
     */
    private static final int METADATA_ENCODE_CURVE = fourCc("ECRV");

    /**
     * The metadata tag that marks a source whose curve was applied before encoding, {@code PCRV}.
     */
    private static final int METADATA_ENCODE_PRESET = fourCc("PCRV");

    private static final CfhdBayerPhase[] PHASES = CfhdBayerPhase.values();

    private static final ColorInfo LINEAR_MOSAIC = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL,
            ColorPrimaries.UNSPECIFIED, TransferCharacteristics.LINEAR);

    private static final ColorInfo CODED_MOSAIC = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL,
            ColorPrimaries.UNSPECIFIED, TransferCharacteristics.UNSPECIFIED);

    private final VideoTrack track;

    private final MediaLimits limits;

    private final CfhdBayerOutput bayerOutput;

    /**
     * The thread count of {@link CfhdDecoderOptions#threads()}.
     */
    private final int threads;

    private final CfhdWavelets wavelets = new CfhdWavelets();

    private final CfhdBayer bayer = new CfhdBayer();

    /**
     * The Bayer phase of the {@code BFMT} metadata last seen, 0 to 3, 0 until a sample carries one.
     */
    private int bayerFormat;

    /**
     * The encoder's curve of the {@code ECRV} metadata last seen, 0 (CineForm's log curve) until a sample carries
     * one.
     */
    private int encodeCurve;

    /**
     * The {@code PCRV} metadata value last seen: non-zero when the source's curve was applied before encoding.
     */
    private int encodePreset;

    /**
     * The curve {@link #restore} inverts, or -1 before one is built.
     */
    private int restoreCurve = -1;

    /**
     * The table that linearizes coded photosites under {@link #restoreCurve}.
     */
    private int[] restore;

    /**
     * The arrangement of the last frame, or {@code null} if it was not a Bayer frame.
     */
    private CfhdBayerPhase lastPhase;

    /**
     * The arrangement of the pending second frame of a group, or {@code null}.
     */
    private CfhdBayerPhase pendingPhase;

    /**
     * The levels of each channel.
     */
    private final Wavelet[][] channels = new Wavelet[4][6];

    /**
     * The reconstructed samples of each channel of the two frames of a group, reused from sample to sample.
     */
    private final int[][][] planes = new int[2][4][];

    /**
     * The width of each channel of the sample being reconstructed.
     */
    private final int[] planeWidths = new int[4];

    /**
     * The height of each channel of the sample being reconstructed.
     */
    private final int[] planeHeights = new int[4];

    /**
     * The second frame of the last group, or {@code null}.
     */
    private Picture pending;

    private int sampleType;
    private int transformType;
    private int numChannels = 3;
    private int numWavelets = 3;
    private int frameWidth;
    private int frameHeight;
    private int displayHeight;
    private int precision = 10;
    private int encodedFormat = FORMAT_YUV_422;
    private int colorSpace;
    private final int[] prescale = new int[8];
    private int channel;
    private int lowpassWidth;
    private int lowpassHeight;
    private int pixelOffset;
    private int lowpassQuantization = 1;
    private int pixelDepth = 16;
    private int bandNumber;
    private int bandWidth;
    private int bandHeight;
    private int subband;
    private int encoding = 3;
    private int quantization = 1;
    private int codebook;
    private boolean differenceCoding;
    private int peakLevel;
    private int peakOffset;
    private int peakBase = -1;

    /**
     * Whether the finest level of each frame is a spatial wavelet (a progressive frame) rather than a frame wavelet
     * between the two fields.
     */
    private boolean progressive;

    /**
     * Creates a decoder for a CineForm track that demosaics CineForm RAW frames.
     *
     * @param track the track description; its codec must be {@link Codec#CFHD}
     * @param options the limits checked against every frame before allocating and the thread count of the
     * demosaic; the high bit depth setting does not apply, since frames keep their coded precision
     * @throws IllegalArgumentException if the track is not a CineForm track
     */
    public CfhdDecoder(VideoTrack track, VideoDecoderOptions options) {
        this(track, CfhdDecoderOptions.builder().limits(Objects.requireNonNull(options, "options").limits())
                .threads(options.threads()).build());
    }

    /**
     * Creates a decoder for a CineForm track.
     *
     * @param track the track description; its codec must be {@link Codec#CFHD}
     * @param options the limits checked against every frame before allocating, the thread count and what CineForm
     * RAW frames decode to
     * @throws IllegalArgumentException if the track is not a CineForm track
     */
    public CfhdDecoder(VideoTrack track, CfhdDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.CFHD) {
            throw new IllegalArgumentException("not a cfhd track: " + track.codec());
        }
        this.track = track;
        this.limits = options.limits();
        this.bayerOutput = options.bayerOutput();
        this.threads = options.threads();
        for (var c = 0; c < 4; c++) {
            for (var w = 0; w < 6; w++) {
                channels[c][w] = new Wavelet();
            }
        }
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the color filter array arrangement of the last frame this decoder returned, empty if that frame was
     * not a CineForm RAW frame or no frame was returned yet}
     *
     * <p>The second frame of a group reports the arrangement of its group's sample.
     */
    public Optional<CfhdBayerPhase> bayerPhase() {
        return Optional.ofNullable(lastPhase);
    }

    /**
     * Decodes one packet holding one sample.
     *
     * @param packet the next packet in decoding order
     * @param output receives the frame the sample yields, carrying the packet's timestamps; a sequence header or
     * trailer yields none
     * @throws MediaException.Malformed if the sample is damaged, or is the second packet of a group whose first was
     * not decoded
     * @throws MediaException.Unsupported if the sample is Y'CbCrA 4:4:4:4 coded or uncompressed CineForm RAW, uses a
     * transform other than the spatial and group ones, or is a CineForm RAW frame too small to demosaic
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        var picture = decodeFrame(data, 0, data.length);
        if (picture == null) {
            return;
        }
        output.accept(new VideoFrame(picture, packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, packet.keyframe()));
    }

    /**
     * Decodes one sample held in part of an array.
     *
     * @param data the array holding the sample
     * @param offset the index of the sample's first byte
     * @param length the number of bytes of the sample
     * @return the frame the sample yields, or {@code null} for a sequence header or trailer; it shares no state with
     * the decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the sample is damaged, or is the second packet of a group whose first was
     * not decoded
     * @throws MediaException.Unsupported if the sample is Y'CbCrA 4:4:4:4 coded or uncompressed CineForm RAW, uses a
     * transform other than the spatial and group ones, or is a CineForm RAW frame too small to demosaic
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        try (var _ = ParallelBands.operation()) {
            return parse(data, offset, offset + length);
        } catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new MediaException.Malformed("CineForm sample is damaged", e);
        }
    }

    /**
     * Parses a sample and reconstructs its frame.
     *
     * @param data the array holding the sample
     * @param start the index of its first byte
     * @param end the index after its last byte
     * @return the frame
     */
    private Picture parse(byte[] data, int start, int end) {
        sampleType = 0;
        channel = 0;
        peakLevel = 0;
        peakBase = -1;
        progressive = false;
        for (var c = 0; c < 4; c++) {
            for (var w = 0; w < 6; w++) {
                java.util.Arrays.fill(channels[c][w].present, false);
            }
        }
        var position = start;
        while (position + 4 <= end) {
            int tag = (short) ((data[position] & 0xFF) << 8 | data[position + 1] & 0xFF);
            var value = (data[position + 2] & 0xFF) << 8 | data[position + 3] & 0xFF;
            position += 4;
            var t = Math.abs(tag);
            if (t >= 0x6000) {
                var size = 4L * (value + ((t & 0xFF) << 16));
                if ((t & 0xFF00) == 0x6000) {
                    metadata(data, position, (int) Math.min(end, position + size));
                }
                position = (int) Math.min(end, position + size);
                continue;
            }
            if (t >= 0x4000) {
                peakLevel = 0;
                if (t == TAG_METADATA) {
                    metadata(data, position, Math.min(end, position + 4 * value));
                }
                position += 4 * value;
                continue;
            }
            if (t == TAG_UNCOMPRESSED) {
                // TODO: decode uncompressed samples (packed 12-bit photosites) once a decoder can serve as their
                // reference; GoPro's decoder fails on the ones its encoder writes
                throw new MediaException.Unsupported("CineForm", "uncompressed samples");
            }
            if (t >= 0x2000) {
                continue;
            }
            switch (t) {
                case 1 -> {
                    if (value == SAMPLE_CHANNEL || value == SAMPLE_GROUP_TRAILER) {
                        break;
                    }
                    sampleType = value;
                    if (value == SAMPLE_FRAME) {
                        if (pending == null) {
                            throw new MediaException.Malformed("CineForm frame sample without its group");
                        }
                        var picture = pending;
                        pending = null;
                        lastPhase = pendingPhase;
                        return picture;
                    }
                }
                case 2 -> position += 4 * value;
                case 4 -> {
                    if (value == 0x0F0F) {
                        position = lowpass(data, position, end);
                    }
                }
                case 10 -> transformType = value;
                case 12 -> numChannels = value;
                case 13 -> numWavelets = value;
                case 20 -> frameWidth = value;
                case 21 -> frameHeight = value;
                case 27 -> lowpassWidth = value;
                case 28 -> lowpassHeight = value;
                case 33 -> pixelOffset = value;
                case 34 -> lowpassQuantization = value;
                case 35 -> pixelDepth = value;
                case 48 -> bandNumber = value;
                case 49 -> bandWidth = value;
                case 50 -> bandHeight = value;
                case 51 -> subband = value;
                case 52 -> encoding = value;
                case 53 -> quantization = value;
                case 55 -> position = highpass(data, position, end);
                case 62 -> channel = value;
                case 68 -> progressive = (value & 1) != 0;
                case 70 -> precision = value;
                case 72 -> {
                    codebook = value & 0xF;
                    differenceCoding = (value >> 4 & 1) != 0;
                }
                case 74 -> {
                    peakLevel = value;
                    peakBase += peakOffset;
                }
                case 75 -> {
                    peakOffset = peakOffset & ~0xFFFF | value;
                    peakBase = position;
                    peakLevel = 0;
                }
                case 76 -> {
                    peakOffset = peakOffset & 0xFFFF | value << 16;
                    peakLevel = 0;
                }
                case 83 -> {
                    for (var i = 0; i < prescale.length; i++) {
                        prescale[i] = i < 8 ? value >> (14 - i * 2) & 3 : 0;
                    }
                }
                case 84 -> encodedFormat = value;
                case 85 -> displayHeight = value;
                case 91 -> colorSpace = value;
                default -> {
                }
            }
        }
        if (sampleType == SAMPLE_SEQUENCE_HEADER || sampleType == SAMPLE_SEQUENCE_TRAILER) {
            return null;
        }
        if (sampleType != SAMPLE_INTRA && sampleType != SAMPLE_GROUP) {
            throw new MediaException.Malformed("CineForm sample of type " + sampleType + " holds no frame");
        }
        return reconstruct();
    }

    /**
     * Reads the Bayer phase and the encoder's curve from a chunk of metadata: entries of a little-endian four
     * character code, a 24-bit little-endian size, a type byte and the value, padded to 4 bytes.
     *
     * @param data the sample
     * @param position the index of the chunk's first entry
     * @param end the index after the chunk
     */
    private void metadata(byte[] data, int position, int end) {
        while (position + 8 <= end) {
            var tag = littleEndian(data, position);
            var size = littleEndian(data, position + 4) & 0xFFFFFF;
            var at = position + 8;
            if (size >= 1 && at + size <= end) {
                var value = size >= 4 ? littleEndian(data, at) : data[at] & 0xFF;
                if (tag == METADATA_BAYER_FORMAT) {
                    bayerFormat = value;
                } else if (tag == METADATA_ENCODE_CURVE) {
                    encodeCurve = value;
                } else if (tag == METADATA_ENCODE_PRESET) {
                    encodePreset = value;
                }
            }
            position = (int) Math.min(end, at + ((size + 3L) & ~3L));
        }
    }

    private static int littleEndian(byte[] data, int at) {
        return data[at] & 0xFF | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | (data[at + 3] & 0xFF) << 24;
    }

    private static int fourCc(String code) {
        return code.charAt(0) | code.charAt(1) << 8 | code.charAt(2) << 16 | code.charAt(3) << 24;
    }

    /**
     * Reads the lowpass band of the current channel.
     *
     * @param data the sample
     * @param position the index of the band's first coefficient
     * @param end the index after the sample
     * @return the index after the band, a multiple of 4 bytes past its start
     */
    private int lowpass(byte[] data, int position, int end) {
        checkChannel();
        var wavelet = channels[channel][transformType == 0 ? INTRA_WAVELET[0] : GROUP_WAVELET[0]];
        if (lowpassWidth <= 0 || lowpassHeight <= 0 || pixelDepth < 8 || pixelDepth > 16) {
            throw new MediaException.Malformed("CineForm lowpass band of " + lowpassWidth + "x" + lowpassHeight
                    + " and " + pixelDepth + " bits");
        }
        var samples = lowpassWidth * lowpassHeight;
        LimitChecks.checkAllocation(limits, 2L * samples);
        if (wavelet.present[0] && (wavelet.width != lowpassWidth || wavelet.height != lowpassHeight)) {
            throw new MediaException.Malformed("CineForm lowpass band size differs from its level");
        }
        wavelet.width = lowpassWidth;
        wavelet.height = lowpassHeight;
        var band = wavelet.band(0, samples);
        var bit = (long) position * 8;
        if (position + ((long) samples * pixelDepth + 7) / 8 > end) {
            throw new MediaException.Malformed("CineForm lowpass band is truncated");
        }
        for (var i = 0; i < samples; i++) {
            var raw = 0;
            for (var b = 0; b < pixelDepth; b++) {
                raw = raw << 1 | data[(int) (bit >>> 3)] >>> (7 - (int) (bit & 7)) & 1;
                bit++;
            }
            band[i] = (short) Math.clamp((long) lowpassQuantization * raw + pixelOffset, Short.MIN_VALUE,
                    Short.MAX_VALUE);
        }
        wavelet.present[0] = true;
        var consumed = (int) ((bit + 7) >>> 3) - position;
        return position + (consumed + 3 & ~3);
    }

    /**
     * Reads one highpass band of the current channel.
     *
     * @param data the sample
     * @param position the index of the band's first coded byte
     * @param end the index after the sample
     * @return the index after the band
     */
    private int highpass(byte[] data, int position, int end) {
        checkChannel();
        var table = transformType == 0 ? INTRA_WAVELET : GROUP_WAVELET;
        if (subband == 255) {
            // the empty temporal highpass band, which the inverse of the level above it reconstructs
            return position;
        }
        if (subband <= 0 || subband >= table.length) {
            throw new MediaException.Malformed("CineForm subband " + subband + " out of range");
        }
        var wavelet = channels[channel][table[subband]];
        var index = (transformType == 0 ? INTRA_BAND : GROUP_BAND)[subband];
        if (bandWidth <= 0 || bandHeight <= 0) {
            throw new MediaException.Malformed("CineForm band of " + bandWidth + "x" + bandHeight);
        }
        if (wavelet.present[0] || wavelet.present[1] || wavelet.present[2] || wavelet.present[3]) {
            if (wavelet.width != bandWidth || wavelet.height != bandHeight) {
                throw new MediaException.Malformed("CineForm band sizes of one level differ");
            }
        }
        wavelet.width = bandWidth;
        wavelet.height = bandHeight;
        var samples = bandWidth * bandHeight;
        LimitChecks.checkAllocation(limits, 2L * samples);
        var band = wavelet.band(index, samples);
        int next;
        switch (encoding) {
            case 3 -> {
                if (codebook > 2) {
                    throw new MediaException.Malformed("CineForm band selects codeset " + codebook);
                }
                next = CfhdBands.INSTANCE.decode(data, position, end, codebook, Math.max(1, quantization),
                        peakLevel, peakBase, band, bandWidth, bandHeight);
            }
            case 4 -> {
                if (position + 2L * samples > end) {
                    throw new MediaException.Malformed("CineForm 16-bit band is truncated");
                }
                var scale = Math.max(1, quantization);
                for (var i = 0; i < samples; i++) {
                    band[i] = (short) (scale * (short) ((data[position + 2 * i] & 0xFF) << 8
                            | data[position + 2 * i + 1] & 0xFF));
                }
                next = position + (2 * samples + 3 & ~3);
            }
            default -> throw new MediaException.Unsupported("CineForm", "band encoding " + encoding);
        }
        if (differenceCoding) {
            for (var y = 0; y < bandHeight; y++) {
                for (var x = 1; x < bandWidth; x++) {
                    band[y * bandWidth + x] += band[y * bandWidth + x - 1];
                }
            }
        }
        wavelet.present[index] = true;
        codebook = 0;
        differenceCoding = false;
        peakLevel = 0;
        return next;
    }

    private void checkChannel() {
        if (channel < 0 || channel >= 4 || channel >= Math.max(numChannels, 1)) {
            throw new MediaException.Malformed("CineForm channel " + channel + " out of range");
        }
        if (transformType != 0 && transformType != 2) {
            throw new MediaException.Unsupported("CineForm", "transform type " + transformType);
        }
        if (transformType == 0 && numWavelets != 3 || transformType == 2 && numWavelets != 6) {
            throw new MediaException.Malformed("CineForm transform of " + numWavelets + " wavelets");
        }
    }

    /**
     * Reconstructs the frame (or, for a group, both frames) from the decoded bands.
     *
     * @return the first frame
     */
    private Picture reconstruct() {
        if (encodedFormat == FORMAT_YUVA_4444) {
            // TODO: decode Y'CbCrA 4:4:4:4 samples once an encoder or a reference decoder fixes the order of their
            // channels; GoPro's SDK neither writes nor reads them
            throw new MediaException.Unsupported("CineForm", "Y'CbCrA 4:4:4:4 encoded samples");
        }
        if (encodedFormat < FORMAT_YUV_422 || encodedFormat > FORMAT_YUVA_4444) {
            throw new MediaException.Malformed("CineForm encoded format " + encodedFormat);
        }
        var expected = switch (encodedFormat) {
            case FORMAT_RGBA_4444, FORMAT_BAYER -> 4;
            default -> 3;
        };
        if (numChannels != expected) {
            throw new MediaException.Malformed("CineForm format " + encodedFormat + " with " + numChannels
                    + " channels");
        }
        if (precision != 8 && precision != 10 && precision != 12) {
            throw new MediaException.Malformed("CineForm precision " + precision);
        }
        var max = (1 << precision) - 1;
        var frames = transformType == 2 ? 2 : 1;
        var widths = planeWidths;
        var heights = planeHeights;
        for (var c = 0; c < numChannels; c++) {
            var levels = channels[c];
            if (transformType == 0) {
                requireBands(levels[2], 0, 1, 2, 3);
                requireBands(levels[1], 1, 2, 3);
                requireBands(levels[0], 1, 2, 3);
                spatial(levels[2], levels[1], 2);
                spatial(levels[1], levels[0], 1);
            } else {
                requireBands(levels[5], 0, 1, 2, 3);
                requireBands(levels[4], 1, 2, 3);
                requireBands(levels[1], 1, 2, 3);
                requireBands(levels[0], 1, 2, 3);
                spatial(levels[5], levels[4], 5);
                var temporal = levels[2];
                requireBands(levels[3], 0, 1, 2, 3);
                temporal.width = 2 * levels[4].width;
                temporal.height = 2 * levels[4].height;
                var size = temporal.width * temporal.height;
                LimitChecks.checkAllocation(limits, 4L * size);
                wavelets.spatial(levels[4].bands, levels[4].width, levels[4].height, lowpassScale(4), 0,
                        temporal.band(0, size));
                if (2 * levels[3].width != temporal.width || 2 * levels[3].height != temporal.height) {
                    throw new MediaException.Malformed("CineForm temporal highpass size differs");
                }
                var scale = highpassScale();
                wavelets.spatial(levels[3].bands, levels[3].width, levels[3].height, scale, scale < 0 ? 1 : 0,
                        temporal.band(1, size));
                for (var f = 0; f < 2; f++) {
                    if (levels[f].width != temporal.width || levels[f].height != temporal.height) {
                        throw new MediaException.Malformed("CineForm frame level size differs from the temporal one");
                    }
                }
                CfhdWavelets.temporal(temporal.bands[0], temporal.bands[1], temporal.width, temporal.height,
                        precision, levels[0].band(0, size), levels[1].band(0, size));
            }
            widths[c] = 2 * levels[0].width;
            heights[c] = 2 * levels[0].height;
            for (var f = 0; f < frames; f++) {
                var level = levels[f];
                var out = planeBuffer(f, c, widths[c] * heights[c]);
                if (encodedFormat == FORMAT_BAYER && progressive) {
                    // TODO: match GoPro's decoder on wavelet coefficients of extreme magnitude, as its encoder
                    // writes them for the CineStyle curve; the reconstruction differs from its decode there
                    wavelets.spatialToRow16u(level.bands, level.width, level.height, precision, out);
                } else if (progressive) {
                    wavelets.spatialToSamples(level.bands, level.width, level.height, max, out);
                } else {
                    wavelets.frameToSamples(level.bands, level.width, level.height, max, out);
                    if (encodedFormat == FORMAT_BAYER) {
                        for (var i = 0; i < widths[c] * heights[c]; i++) {
                            out[i] <<= 16 - precision;
                        }
                    }
                }
            }
        }
        var phase = encodedFormat == FORMAT_BAYER ? phase() : null;
        var first = picture(planes[0], widths, heights);
        pending = frames == 2 ? picture(planes[1], widths, heights) : null;
        lastPhase = phase;
        pendingPhase = phase;
        return first;
    }

    /**
     * {@return the arrangement of the Bayer phase last read from metadata}
     *
     * @throws MediaException.Malformed if the phase is not one of the four arrangements
     */
    private CfhdBayerPhase phase() {
        if (bayerFormat < 0 || bayerFormat >= PHASES.length) {
            throw new MediaException.Malformed("CineForm Bayer phase " + bayerFormat);
        }
        return PHASES[bayerFormat];
    }

    /**
     * Returns the reconstruction buffer of one channel of one frame, grown when it is too small for a plane.
     *
     * @param frame the frame of the group, 0 or 1
     * @param channel the index of the channel, from 0 to 3
     * @param size the number of samples of the plane
     * @return the buffer, holding at least {@code size} samples
     * @throws MediaException.LimitExceeded if a grown buffer exceeds the limits
     */
    private int[] planeBuffer(int frame, int channel, int size) {
        var buffer = planes[frame][channel];
        if (buffer == null || buffer.length < size) {
            LimitChecks.checkAllocation(limits, 4L * size);
            buffer = new int[size];
            planes[frame][channel] = buffer;
        }
        return buffer;
    }

    /**
     * Inverts one spatial level into the lowpass band of the next finer one.
     *
     * @param level the level to invert
     * @param finer the next finer level
     * @param index the level's index, which selects its prescale
     */
    private void spatial(Wavelet level, Wavelet finer, int index) {
        if (2 * level.width != finer.width || 2 * level.height != finer.height) {
            throw new MediaException.Malformed("CineForm level sizes do not halve");
        }
        var size = finer.width * finer.height;
        LimitChecks.checkAllocation(limits, 4L * size);
        var scale = lowpassScale(index);
        // the fifth wavelet of a group is its only level at the fourth level of the transform
        var lag = scale < 0 && transformType == 2 && index == 5 ? 1 : 0;
        wavelets.spatial(level.bands, level.width, level.height, scale, lag, finer.band(0, size));
    }

    /**
     * Returns the scaling of the horizontal pass that inverts a spatial level into a lowpass band.
     *
     * <p>A level the encoder did not prescale is halved, one prescaled once is kept and one prescaled more is doubled
     * above 8 bits and kept at 8 bits.
     *
     * @param index the level's index, which selects its prescale
     * @return the scale argument of {@link CfhdWavelets#spatial(short[][], int, int, int, int, short[])}
     */
    private int lowpassScale(int index) {
        return switch (prescale[index]) {
            case 0 -> -1;
            case 1 -> 0;
            default -> precision >= 10 ? 1 : 0;
        };
    }

    /**
     * Returns the scaling of the horizontal pass that inverts the spatial level above the temporal highpass band.
     *
     * <p>The level's prescale applies above 8 bits only: none halves, two doubles and any other keeps.
     *
     * @return the scale argument of {@link CfhdWavelets#spatial(short[][], int, int, int, int, short[])}
     */
    private int highpassScale() {
        var scale = precision >= 10 ? prescale[3] : 0;
        return scale == 0 ? -1 : scale == 2 ? 1 : 0;
    }

    private static void requireBands(Wavelet wavelet, int... bands) {
        for (var band : bands) {
            if (!wavelet.present[band]) {
                throw new MediaException.Malformed("CineForm sample misses band " + band + " of a level");
            }
        }
    }

    /**
     * Builds the picture of one frame from its channels.
     *
     * @param planes the samples of each channel
     * @param widths the width of each channel
     * @param heights the height of each channel
     * @return the picture
     */
    private Picture picture(int[][] planes, int[] widths, int[] heights) {
        var width = widths[0];
        var height = heights[0];
        if (frameWidth > 0 && frameWidth <= width) {
            width = frameWidth;
        }
        var shown = displayHeight > 0 ? displayHeight : frameHeight;
        if (shown > 0 && shown <= height) {
            height = shown;
        }
        var stride = widths[0];
        if (encodedFormat == FORMAT_BAYER) {
            requireSameSize(widths, heights, numChannels);
            return bayerPicture(planes, stride, width, height);
        }
        LimitChecks.checkPicture(limits, width, height);
        switch (encodedFormat) {
            case FORMAT_YUV_422 -> {
                if (widths[1] != (widths[0] + 1) / 2 || widths[2] != widths[1] || heights[1] != heights[0]
                        || heights[2] != heights[0]) {
                    throw new MediaException.Malformed("CineForm 4:2:2 chroma channels do not match the luma");
                }
                var color = (colorSpace & 2) != 0 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
                if (precision == 8) {
                    var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV422, false, color);
                    copy(planes[0], stride, picture.y(), picture.yStride(), width, height);
                    copy(planes[2], widths[1], picture.u(), picture.uvStride(), (width + 1) / 2, height);
                    copy(planes[1], widths[1], picture.v(), picture.uvStride(), (width + 1) / 2, height);
                    return picture;
                }
                var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV422, precision, false, color);
                copy(planes[0], stride, picture.y(), picture.yStride(), width, height);
                copy(planes[2], widths[1], picture.u(), picture.uvStride(), (width + 1) / 2, height);
                copy(planes[1], widths[1], picture.v(), picture.uvStride(), (width + 1) / 2, height);
                return picture;
            }
            default -> {
                requireSameSize(widths, heights, numChannels);
                var alpha = encodedFormat == FORMAT_RGBA_4444;
                var picture = Rgba16Picture.allocate(width, height, alpha);
                var pixels = picture.pixels();
                var shift = 16 - precision;
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var from = y * stride + x;
                        var to = y * picture.stride() + x * 4;
                        pixels[to] = widen(planes[1][from], shift, precision);
                        pixels[to + 1] = widen(planes[0][from], shift, precision);
                        pixels[to + 2] = widen(planes[2][from], shift, precision);
                        pixels[to + 3] = alpha ? widen(decompandAlpha(planes[3][from], precision), shift, precision)
                                : (short) 0xFFFF;
                    }
                }
                return picture;
            }
        }
    }

    /**
     * Builds the picture of one CineForm RAW frame: its mosaic or its demosaiced RGB, twice the channel size.
     *
     * @param planes the 16-bit G, RG, BG and GD channels
     * @param stride the distance between the rows of a channel
     * @param quadsWide the number of quads of a row shown
     * @param quadsHigh the number of quad rows shown
     * @return the picture
     */
    private Picture bayerPicture(int[][] planes, int stride, int quadsWide, int quadsHigh) {
        var width = 2L * quadsWide;
        var height = 2L * quadsHigh;
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new MediaException.LimitExceeded("CineForm frame of " + width + "x" + height);
        }
        LimitChecks.checkPicture(limits, (int) width, (int) height);
        var phase = phase().ordinal();
        if (bayerOutput == CfhdBayerOutput.MOSAIC) {
            var linear = encodePreset == 0 && CfhdBayer.linearizes(encodeCurve);
            if (linear && restoreCurve != encodeCurve) {
                restore = CfhdBayer.restoreTable(encodeCurve);
                restoreCurve = encodeCurve;
            }
            var curveUndone = encodePreset == 0 && (linear || CfhdBayer.linear(encodeCurve));
            var picture = Yuv16Picture.allocate((int) width, (int) height, ChromaFormat.MONOCHROME, 16, false,
                    curveUndone ? LINEAR_MOSAIC : CODED_MOSAIC);
            CfhdBayer.mosaic(planes, stride, quadsWide, quadsHigh, phase, linear ? restore : null, picture.y(),
                    picture.yStride());
            return picture;
        }
        if (quadsWide < 2) {
            throw new MediaException.Unsupported("CineForm", "demosaicing frames narrower than 4 photosites");
        }
        var picture = Rgba16Picture.allocate((int) width, (int) height, false);
        bayer.demosaic(planes, stride, quadsWide, quadsHigh, phase, limits, threads, picture.pixels(),
                picture.stride());
        return picture;
    }

    /**
     * {@return a sample of the coded precision as a 16-bit sample, its high bits repeated into the low ones}
     *
     * @param value the sample
     * @param shift {@code 16 - precision}
     * @param precision the coded precision
     */
    private static short widen(int value, int shift, int precision) {
        return (short) (value << shift | value >>> (precision - shift));
    }

    /**
     * Undoes the companding of a CineForm alpha sample: alpha is coded with an offset of 256 and a gain of about
     * 223/256 at 12 bits.
     *
     * @param value the coded alpha sample at the coded precision
     * @param precision the coded precision
     * @return the alpha sample at the coded precision
     */
    private static int decompandAlpha(int value, int precision) {
        var twelve = precision >= 12 ? value >> (precision - 12) : value << (12 - precision);
        var alpha = Math.clamp((((twelve - 256) << 3) * 9400L) >> 16, 0, 4095);
        return precision >= 12 ? (int) alpha << (precision - 12) : (int) alpha >> (12 - precision);
    }

    private static void requireSameSize(int[] widths, int[] heights, int channels) {
        for (var c = 1; c < channels; c++) {
            if (widths[c] != widths[0] || heights[c] != heights[0]) {
                throw new MediaException.Malformed("CineForm 4:4:4 channels differ in size");
            }
        }
    }

    private static void copy(int[] from, int fromStride, short[] to, int toStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                to[y * toStride + x] = (short) from[y * fromStride + x];
            }
        }
    }

    private static void copy(int[] from, int fromStride, byte[] to, int toStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                to[y * toStride + x] = (byte) from[y * fromStride + x];
            }
        }
    }

    /**
     * Discards the second frame of a group whose short packet never came.
     *
     * @param output receives no frame
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
        pending = null;
    }
}
