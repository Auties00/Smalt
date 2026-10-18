package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;

/**
 * Holds one parsed {@code TrackEntry} and maps its {@code CodecID} to a {@link Track}, per the
 * Matroska codec specification (RFC 9559 and the Matroska codec mappings registry).
 *
 * <p>Codec configurations follow {@link Track#codecConfig()}: {@code CodecPrivate} is passed
 * through for H.264 ({@code avcC}), HEVC ({@code hvcC}), AV1 ({@code av1C}), MPEG-4 Part 2, AAC,
 * Opus ({@code OpusHead}), Vorbis (Xiph-laced headers) and FLAC ({@code fLaC} and metadata
 * blocks); legacy {@code A_AAC/MPEG2/...} and {@code A_AAC/MPEG4/...} identifiers, and an
 * {@code A_AAC} track without {@code CodecPrivate}, get an {@code AudioSpecificConfig} synthesized
 * from the object type in the identifier and the declared rates and channels. VP8 and VP9 carry no
 * configuration (their bitstreams are self-describing). Tracks stored through the
 * {@code V_MS/VFW/FOURCC} and {@code A_MS/ACM} compatibility identifiers are mapped from their
 * {@code BITMAPINFOHEADER} and {@code WAVEFORMATEX} structures.
 */
final class MatroskaTrackEntry {
    /**
     * The nanosecond time base in which Matroska expresses {@code DefaultDuration},
     * {@code CodecDelay}, {@code DiscardPadding} and the {@code DURATION} tag.
     */
    static final Rational NANOSECONDS = new Rational(1, 1_000_000_000);

    /**
     * The {@code TrackType} of a video track.
     */
    private static final int TYPE_VIDEO = 1;

    /**
     * The {@code TrackType} of an audio track.
     */
    private static final int TYPE_AUDIO = 2;

    /**
     * The track number blocks refer to.
     */
    long number;

    /**
     * The track's unique identifier, which tags refer to.
     */
    long uid;

    /**
     * The {@code TrackType}.
     */
    long type;

    /**
     * The {@code CodecID}.
     */
    String codecId = "";

    /**
     * The {@code CodecPrivate}, or {@code null}.
     */
    byte[] codecPrivate;

    /**
     * The {@code DefaultDuration} in nanoseconds, or {@code 0}.
     */
    long defaultDuration;

    /**
     * The {@code CodecDelay} in nanoseconds.
     */
    long codecDelay;

    /**
     * The {@code Name} of the track, or {@code null}.
     */
    String name;

    /**
     * The {@code Language}, {@code eng} unless declared.
     */
    String language = "eng";

    /**
     * The {@code LanguageBCP47}, which overrides {@link #language}, or {@code null}.
     */
    String languageBcp47;

    /**
     * Whether the track is usable ({@code FlagEnabled}, set unless declared otherwise).
     */
    boolean enabled = true;

    /**
     * The ISO 639-2 bibliographic codes of the languages whose terminology code differs, keyed by
     * the terminology code.
     */
    private static final Map<String, String> BIBLIOGRAPHIC = Map.ofEntries(Map.entry("sqi", "alb"),
            Map.entry("hye", "arm"), Map.entry("eus", "baq"), Map.entry("mya", "bur"), Map.entry("zho", "chi"),
            Map.entry("ces", "cze"), Map.entry("nld", "dut"), Map.entry("fra", "fre"), Map.entry("kat", "geo"),
            Map.entry("deu", "ger"), Map.entry("ell", "gre"), Map.entry("isl", "ice"), Map.entry("mkd", "mac"),
            Map.entry("mri", "mao"), Map.entry("msa", "may"), Map.entry("fas", "per"), Map.entry("ron", "rum"),
            Map.entry("slk", "slo"), Map.entry("bod", "tib"), Map.entry("cym", "wel"));

    /**
     * The {@code PixelWidth}.
     */
    long pixelWidth;

    /**
     * The {@code PixelHeight}.
     */
    long pixelHeight;

    /**
     * The color description of the {@code Color} element, or {@code null}.
     */
    ColorInfo color;

    /**
     * The display orientation of the {@code Projection} roll.
     */
    Orientation orientation = Orientation.NORMAL;

    /**
     * The {@code SamplingFrequency} in hertz.
     */
    double samplingFrequency = 8000;

    /**
     * The {@code OutputSamplingFrequency} in hertz, or {@code 0} when absent.
     */
    double outputSamplingFrequency;

    /**
     * The {@code Channels}.
     */
    long channels = 1;

    /**
     * The {@code BitDepth}, or {@code 0}.
     */
    long bitDepth;

    /**
     * The content encodings, in the order they must be undone.
     */
    List<ContentEncoding> encodings = List.of();

    /**
     * The track duration from a {@code DURATION} tag, in nanoseconds, or {@code -1}.
     */
    long taggedDuration = -1;

    /**
     * The mapped codec, set by {@link #toTrack(Rational, double, MediaLimits)}.
     */
    Codec codec = Codec.DATA;

    /**
     * The time base of the track's packets, set by {@link #toTrack(Rational, double, MediaLimits)}:
     * the sample period of an audio track (48 kHz for Opus), so that durations and
     * {@code DiscardPadding} trims are sample-exact, and the segment time base otherwise.
     */
    Rational timeBase;

    /**
     * Parses a {@code TrackEntry} element.
     *
     * @param entry the element data
     * @param limits the limits bounding the codec private data after decompression
     * @return the entry
     * @throws MediaException.Malformed if a child element is corrupt
     * @throws MediaException.Unsupported if the track is encrypted or uses an unsupported compression
     */
    static MatroskaTrackEntry parse(EbmlCursor entry, MediaLimits limits) {
        var track = new MatroskaTrackEntry();
        while (entry.hasMore()) {
            var element = entry.next();
            var body = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.TRACK_NUMBER -> track.number = body.unsigned();
                case EbmlIds.TRACK_UID -> track.uid = body.unsigned();
                case EbmlIds.TRACK_TYPE -> track.type = body.unsigned();
                case EbmlIds.CODEC_ID -> track.codecId = body.string();
                case EbmlIds.CODEC_PRIVATE -> track.codecPrivate = body.bytes();
                case EbmlIds.DEFAULT_DURATION -> track.defaultDuration = body.unsigned();
                case EbmlIds.CODEC_DELAY -> track.codecDelay = body.unsigned();
                case EbmlIds.LANGUAGE -> track.language = body.string();
                case EbmlIds.LANGUAGE_BCP47 -> track.languageBcp47 = body.string();
                case EbmlIds.FLAG_ENABLED -> track.enabled = body.unsigned() != 0;
                case EbmlIds.NAME -> track.name = body.string();
                case EbmlIds.VIDEO -> track.parseVideo(body);
                case EbmlIds.AUDIO -> track.parseAudio(body);
                case EbmlIds.CONTENT_ENCODINGS -> track.parseEncodings(body);
                default -> {
                }
            }
        }
        if (track.codecPrivate != null) {
            for (var encoding : track.encodings) {
                if (encoding.appliesToPrivate() && encoding.algorithm() == ContentEncoding.ZLIB) {
                    track.codecPrivate = encoding.decode(track.codecPrivate, 0, track.codecPrivate.length, limits);
                }
            }
        }
        return track;
    }

    /**
     * Parses a {@code Video} element.
     *
     * @param video the element data
     */
    private void parseVideo(EbmlCursor video) {
        // TODO: read DisplayWidth and DisplayHeight (the display aspect ratio) once VideoTrack can carry it
        while (video.hasMore()) {
            var element = video.next();
            var body = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.PIXEL_WIDTH -> pixelWidth = body.unsigned();
                case EbmlIds.PIXEL_HEIGHT -> pixelHeight = body.unsigned();
                case EbmlIds.COLOR -> color = parseColor(body);
                case EbmlIds.PROJECTION -> {
                    while (body.hasMore()) {
                        var child = body.next();
                        if (child.id() == EbmlIds.PROJECTION_POSE_ROLL) {
                            var roll = child.payload().floating();
                            if (Double.isFinite(roll)) {
                                // the roll is counter-clockwise, the orientation clockwise
                                orientation = Orientation.ofRotation((int) Math.round(-roll), false);
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * Parses a {@code Color} element.
     *
     * <p>Besides the color description, the chroma siting ({@code ChromaSitingHorz} and
     * {@code ChromaSitingVert}), the content light level ({@code MaxCLL} and {@code MaxFALL}) and
     * the mastering display ({@code MasteringMetadata}) are read; a light level or mastering
     * display whose values are out of range is ignored.
     *
     * @param color the element data
     * @return the color description
     */
    private static ColorInfo parseColor(EbmlCursor color) {
        var matrix = 2L;
        var range = 0L;
        var transfer = 2L;
        var primaries = 2L;
        var sitingHorizontal = 0L;
        var sitingVertical = 0L;
        var maxCll = -1L;
        var maxFall = -1L;
        MasteringDisplay mastering = null;
        while (color.hasMore()) {
            var element = color.next();
            var body = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.MATRIX_COEFFICIENTS -> matrix = body.unsigned();
                case EbmlIds.RANGE -> range = body.unsigned();
                case EbmlIds.TRANSFER_CHARACTERISTICS -> transfer = body.unsigned();
                case EbmlIds.PRIMARIES -> primaries = body.unsigned();
                case EbmlIds.CHROMA_SITING_HORZ -> sitingHorizontal = body.unsigned();
                case EbmlIds.CHROMA_SITING_VERT -> sitingVertical = body.unsigned();
                case EbmlIds.MAX_CLL -> maxCll = body.unsigned();
                case EbmlIds.MAX_FALL -> maxFall = body.unsigned();
                case EbmlIds.MASTERING_METADATA -> mastering = parseMastering(body);
                default -> {
                }
            }
        }
        var info = new ColorInfo(MatrixCoefficients.of((int) Math.min(matrix, 255)), range == 2 ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of((int) Math.min(primaries, 255)), TransferCharacteristics.of((int) Math.min(transfer, 255)))
                .withChromaSiting(siting(sitingHorizontal, sitingVertical))
                .withMasteringDisplay(mastering);
        if (maxCll >= 0 && maxCll <= 0xFFFF && maxFall >= 0 && maxFall <= 0xFFFF) {
            info = info.withContentLightLevel(new ContentLightLevel((int) maxCll, (int) maxFall));
        }
        return info;
    }

    /**
     * Returns the chroma siting of the {@code ChromaSitingHorz} and {@code ChromaSitingVert}
     * values, each 0 for unspecified, 1 for co-sited with the left or top luma sample, and 2 for
     * half-way.
     *
     * @param horizontal the horizontal siting
     * @param vertical the vertical siting
     * @return the siting, {@link ChromaSiting#UNSPECIFIED} when either is unspecified or unknown
     */
    private static ChromaSiting siting(long horizontal, long vertical) {
        if (horizontal == 1 && vertical == 1) {
            return ChromaSiting.TOP_LEFT;
        }
        if (horizontal == 1 && vertical == 2) {
            return ChromaSiting.LEFT;
        }
        if (horizontal == 2 && vertical == 1) {
            return ChromaSiting.TOP;
        }
        if (horizontal == 2 && vertical == 2) {
            return ChromaSiting.CENTER;
        }
        return ChromaSiting.UNSPECIFIED;
    }

    /**
     * Parses a {@code MasteringMetadata} element.
     *
     * @param metadata the element data
     * @return the mastering display, or {@code null} when an element is missing, malformed or out of range
     */
    private static MasteringDisplay parseMastering(EbmlCursor metadata) {
        var values = new double[10];
        Arrays.fill(values, Double.NaN);
        while (metadata.hasMore()) {
            var element = metadata.next();
            var index = switch ((int) element.id()) {
                case EbmlIds.PRIMARY_R_CHROMATICITY_X -> 0;
                case EbmlIds.PRIMARY_R_CHROMATICITY_Y -> 1;
                case EbmlIds.PRIMARY_G_CHROMATICITY_X -> 2;
                case EbmlIds.PRIMARY_G_CHROMATICITY_Y -> 3;
                case EbmlIds.PRIMARY_B_CHROMATICITY_X -> 4;
                case EbmlIds.PRIMARY_B_CHROMATICITY_Y -> 5;
                case EbmlIds.WHITE_POINT_CHROMATICITY_X -> 6;
                case EbmlIds.WHITE_POINT_CHROMATICITY_Y -> 7;
                case EbmlIds.LUMINANCE_MAX -> 8;
                case EbmlIds.LUMINANCE_MIN -> 9;
                default -> -1;
            };
            if (index >= 0) {
                try {
                    values[index] = element.payload().floating();
                } catch (MediaException.Malformed exception) {
                    return null;
                }
            }
        }
        try {
            return new MasteringDisplay(new Chromaticity(values[0], values[1]), new Chromaticity(values[2], values[3]),
                    new Chromaticity(values[4], values[5]), new Chromaticity(values[6], values[7]), values[8], values[9]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Parses an {@code Audio} element.
     *
     * @param audio the element data
     */
    private void parseAudio(EbmlCursor audio) {
        while (audio.hasMore()) {
            var element = audio.next();
            var body = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.SAMPLING_FREQUENCY -> samplingFrequency = body.floating();
                case EbmlIds.OUTPUT_SAMPLING_FREQUENCY -> outputSamplingFrequency = body.floating();
                case EbmlIds.CHANNELS -> channels = body.unsigned();
                case EbmlIds.BIT_DEPTH -> bitDepth = body.unsigned();
                default -> {
                }
            }
        }
    }

    /**
     * Parses a {@code ContentEncodings} element.
     *
     * @param encodings the element data
     */
    private void parseEncodings(EbmlCursor encodings) {
        var result = new ArrayList<ContentEncoding>();
        while (encodings.hasMore()) {
            var element = encodings.next();
            if (element.id() == EbmlIds.CONTENT_ENCODING && result.size() < 8) {
                result.add(ContentEncoding.parse(element.payload()));
            }
        }
        result.sort(Comparator.comparingLong(ContentEncoding::order).reversed());
        this.encodings = List.copyOf(result);
    }

    /**
     * {@return whether frames of this track need decoding before use: a content encoding applies to frames}
     */
    boolean encodesFrames() {
        for (var encoding : encodings) {
            if (encoding.appliesToFrames()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Undoes the content encodings of one frame.
     *
     * @param data the stored bytes
     * @param offset the index of the first stored byte
     * @param length the number of stored bytes
     * @param limits the limits bounding decoded sizes
     * @return the frame
     */
    byte[] decodeFrame(byte[] data, int offset, int length, MediaLimits limits) {
        var result = Arrays.copyOfRange(data, offset, offset + length);
        for (var encoding : encodings) {
            if (encoding.appliesToFrames()) {
                result = encoding.decode(result, 0, result.length, limits);
            }
        }
        return result;
    }

    /**
     * {@return the output sampling rate in hertz: the output rate when declared, otherwise the
     * sampling frequency, rounded}
     */
    int sampleRate() {
        var rate = outputSamplingFrequency > 0 ? outputSamplingFrequency : samplingFrequency;
        return rate > 0 && rate < Integer.MAX_VALUE ? (int) Math.round(rate) : 0;
    }

    /**
     * Builds the public description of the track and sets {@link #codec} and {@link #timeBase}.
     *
     * @param segmentBase the segment time base, from the {@code TimestampScale}
     * @param segmentDuration the segment {@code Duration} in segment time base units, or a non-positive value when
     * absent
     * @param limits the limits bounding dimensions and channel counts
     * @return the track
     */
    Track toTrack(Rational segmentBase, double segmentDuration, MediaLimits limits) {
        var id = (int) number;
        var code = languageBcp47 != null ? bibliographicCode(languageBcp47) : language;
        var lang = code.isEmpty() || code.equals("und") ? Optional.<String>empty() : Optional.of(code);
        timeBase = segmentBase;
        if (type == TYPE_VIDEO || codecId.startsWith("V_")) {
            byte[] config = codecPrivate;
            codec = switch (codecId) {
                case "V_MPEG4/ISO/AVC" -> Codec.H264;
                case "V_MPEGH/ISO/HEVC" -> Codec.HEVC;
                case "V_VP8" -> Codec.VP8;
                case "V_VP9" -> Codec.VP9;
                case "V_AV1" -> Codec.AV1;
                case "V_MPEG4/ISO/SP", "V_MPEG4/ISO/ASP", "V_MPEG4/ISO/AP" -> Codec.MPEG4_PART2;
                case "V_MJPEG" -> Codec.MJPEG;
                case "V_PRORES" -> Codec.PRORES;
                case "V_MS/VFW/FOURCC" -> videoFromBitmapInfo();
                case "V_QUICKTIME" -> videoFromImageDescription();
                default -> Codec.UNKNOWN_VIDEO;
            };
            if (codec == Codec.VP8 || codec == Codec.VP9) {
                config = null;
            } else if (codecId.equals("V_QUICKTIME")) {
                config = codec == Codec.PRORES || codec == Codec.DNXHD ? Arrays.copyOfRange(codecPrivate, 4, 8) : null;
            } else if (codecId.equals("V_MS/VFW/FOURCC")) {
                config = codec == Codec.DNXHD ? Arrays.copyOfRange(codecPrivate, 16, 20)
                        : codecPrivate != null && codecPrivate.length > 40 ? Arrays.copyOfRange(codecPrivate, 40, codecPrivate.length) : null;
            }
            var width = (int) Math.min(pixelWidth, Integer.MAX_VALUE);
            var height = (int) Math.min(pixelHeight, Integer.MAX_VALUE);
            if (width > 0 && height > 0) {
                LimitChecks.checkPicture(limits, width, height);
            }
            var frameRate = defaultDuration > 0 ? Optional.of(new Rational(1_000_000_000L, defaultDuration).reduce()) : Optional.<Rational>empty();
            return VideoTrack.builder().id(id).codec(codec).timeBase(timeBase)
                    .duration(duration(segmentBase, segmentDuration))
                    .codecConfig(config)
                    .language(lang.orElse(null))
                    .width(width)
                    .height(height)
                    .frameRate(frameRate.orElse(null))
                    .color(color)
                    .orientation(orientation)
                    .build();
        }
        if (type == TYPE_AUDIO || codecId.startsWith("A_")) {
            var config = codecPrivate;
            var bits = (int) Math.min(bitDepth, 64);
            codec = switch (codecId) {
                case "A_AAC" -> Codec.AAC;
                case "A_OPUS" -> Codec.OPUS;
                case "A_VORBIS" -> Codec.VORBIS;
                case "A_FLAC" -> Codec.FLAC;
                case "A_MPEG/L3" -> Codec.MP3;
                case "A_MPEG/L2", "A_MPEG/L1" -> Codec.MP2;
                case "A_PCM/INT/LIT" -> integerPcm(bits, true);
                case "A_PCM/INT/BIG" -> integerPcm(bits, false);
                case "A_PCM/FLOAT/IEEE" -> bits == 64 ? Codec.PCM_F64LE : bits == 32 ? Codec.PCM_F32LE : Codec.UNKNOWN_AUDIO;
                case "A_AC3", "A_AC3/BSID9", "A_AC3/BSID10" -> Codec.AC3;
                case "A_EAC3" -> Codec.EAC3;
                case "A_ALAC" -> Codec.ALAC;
                case "A_AMR/NB" -> Codec.AMR_NB;
                case "A_AMR/WB" -> Codec.AMR_WB;
                case "A_MS/ACM" -> audioFromWaveFormat();
                default -> codecId.startsWith("A_AAC/") ? Codec.AAC : Codec.UNKNOWN_AUDIO;
            };
            if (codec == Codec.AAC && (config == null || config.length < 2 || codecId.startsWith("A_AAC/"))) {
                config = synthesizeAudioSpecificConfig();
            } else if (codec == Codec.ALAC && config != null) {
                config = AudioConfigs.alacConfig(config);
            } else if (codecId.equals("A_MS/ACM")) {
                var extra = codec == Codec.AAC ? WaveFormat.parse(codecPrivate, codecPrivate.length, false).extra() : null;
                config = extra != null && extra.length > 0 ? extra : null;
                if (codec == Codec.AAC && config == null) {
                    config = synthesizeAudioSpecificConfig();
                }
            }
            if (bits == 0) {
                bits = codec.pcmBitsPerSample().orElse(0);
            }
            var amr = codec == Codec.AMR_NB || codec == Codec.AMR_WB;
            var channelCount = amr ? 1 : (int) Math.min(channels, Integer.MAX_VALUE);
            LimitChecks.checkChannels(limits, channelCount);
            var sampleRate = codec == Codec.AMR_WB ? 16_000 : codec == Codec.AMR_NB ? 8_000 : sampleRate();
            var skip = codec == Codec.OPUS || sampleRate == 0 ? 0 : NANOSECONDS.rescale(codecDelay,
                    Rational.timeBaseOf(sampleRate));
            var packetRate = codec == Codec.OPUS ? 48_000 : sampleRate;
            if (packetRate > 0) {
                timeBase = Rational.timeBaseOf(packetRate);
            }
            return AudioTrack.builder().id(id).codec(codec).timeBase(timeBase)
                    .duration(duration(segmentBase, segmentDuration))
                    .codecConfig(config)
                    .language(lang.orElse(null))
                    .sampleRate(sampleRate)
                    .layout(ChannelLayout.defaultFor(channelCount))
                    .bitsPerSample(codec.isPcm() ? bits : 0)
                    .skipSamples(Math.max(skip, 0))
                    .build();
        }
        codec = switch (codecId) {
            case "S_TEXT/UTF8", "S_TEXT/ASCII" -> Codec.SUBRIP;
            case "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> Codec.ASS;
            case "S_TEXT/WEBVTT" -> Codec.WEBVTT;
            default -> codecId.startsWith("D_WEBVTT") ? Codec.WEBVTT : Codec.DATA;
        };
        return DataTrack.builder().id(id).codec(codec).timeBase(timeBase).duration(duration(segmentBase, segmentDuration))
                .codecConfig(codecPrivate)
                .language(lang.orElse(null))
                .handler(codecId)
                .build();
    }

    /**
     * Returns the duration of the track in {@link #timeBase} units: the {@code DURATION} tag when
     * present, otherwise the segment {@code Duration}.
     *
     * @param segmentBase the segment time base
     * @param segmentDuration the segment {@code Duration} in segment time base units, or a non-positive value when
     * absent
     * @return the duration, or {@code -1} when neither is declared
     */
    private long duration(Rational segmentBase, double segmentDuration) {
        if (taggedDuration >= 0) {
            return NANOSECONDS.rescale(taggedDuration, timeBase);
        }
        if (segmentDuration <= 0) {
            return -1;
        }
        return Math.round(segmentDuration * segmentBase.numerator() * timeBase.denominator()
                / ((double) segmentBase.denominator() * timeBase.numerator()));
    }

    /**
     * Returns whether blocks of this track may be stored out of presentation order, so that their
     * decoding timestamps cannot be taken from the block timestamps.
     *
     * @return {@code true} for H.264, HEVC, MPEG-4 Part 2 and unidentified video
     */
    boolean reorders() {
        return codec == Codec.H264 || codec == Codec.HEVC || codec == Codec.MPEG4_PART2 || codec == Codec.UNKNOWN_VIDEO;
    }

    /**
     * Returns the integer PCM codec for a sample size.
     *
     * @param bits the sample size
     * @param littleEndian whether samples are little-endian
     * @return the codec
     */
    private static Codec integerPcm(int bits, boolean littleEndian) {
        return switch (bits) {
            case 8 -> Codec.PCM_U8;
            case 16 -> littleEndian ? Codec.PCM_S16LE : Codec.PCM_S16BE;
            case 24 -> littleEndian ? Codec.PCM_S24LE : Codec.PCM_S24BE;
            case 32 -> littleEndian ? Codec.PCM_S32LE : Codec.PCM_S32BE;
            default -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Maps the compression code of a {@code BITMAPINFOHEADER} codec private.
     *
     * @return the codec
     */
    private Codec videoFromBitmapInfo() {
        if (codecPrivate == null || codecPrivate.length < 40) {
            return Codec.UNKNOWN_VIDEO;
        }
        var fourCc = new String(codecPrivate, 16, 4, java.nio.charset.StandardCharsets.ISO_8859_1).toUpperCase(java.util.Locale.ROOT);
        return switch (fourCc) {
            case "H264", "X264", "AVC1", "DAVC" -> Codec.H264;
            case "HEVC", "H265", "HVC1" -> Codec.HEVC;
            case "XVID", "DIVX", "DX50", "FMP4", "MP4V" -> Codec.MPEG4_PART2;
            case "MJPG" -> Codec.MJPEG;
            case "H263", "S263" -> Codec.H263;
            case "VP80" -> Codec.VP8;
            case "VP90" -> Codec.VP9;
            case "AV01" -> Codec.AV1;
            case "AVDN", "AVDH" -> Codec.DNXHD;
            default -> Codec.UNKNOWN_VIDEO;
        };
    }

    /**
     * Maps the sample entry type of a QuickTime image description codec private
     * ({@code V_QUICKTIME}): ProRes and DNxHD.
     *
     * @return the codec
     */
    private Codec videoFromImageDescription() {
        if (codecPrivate == null || codecPrivate.length < 8) {
            return Codec.UNKNOWN_VIDEO;
        }
        return switch (new String(codecPrivate, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1)) {
            case "apco", "apcs", "apcn", "apch", "ap4h", "ap4x" -> Codec.PRORES;
            case "AVdn", "AVdh" -> Codec.DNXHD;
            default -> Codec.UNKNOWN_VIDEO;
        };
    }

    /**
     * Maps the format tag of a {@code WAVEFORMATEX} codec private.
     *
     * @return the codec
     */
    private Codec audioFromWaveFormat() {
        if (codecPrivate == null || codecPrivate.length < 16) {
            return Codec.UNKNOWN_AUDIO;
        }
        var format = WaveFormat.parse(codecPrivate, codecPrivate.length, false);
        return WaveFormat.codec(format.tag(), format.bitsPerSample());
    }

    /**
     * Builds an {@code AudioSpecificConfig} from the codec identifier and the declared audio
     * parameters, with explicit backward-compatible SBR signaling for the {@code /SBR} identifiers
     * or an output rate above the sampling rate.
     *
     * @return the configuration bytes, or {@code null} when the declared rate or channel count cannot be expressed
     */
    private byte[] synthesizeAudioSpecificConfig() {
        var objectType = 2;
        if (codecId.contains("/MAIN")) {
            objectType = 1;
        } else if (codecId.contains("/SSR")) {
            objectType = 3;
        } else if (codecId.contains("/LTP")) {
            objectType = 4;
        }
        var coreRate = (int) Math.round(samplingFrequency);
        var outputRate = outputSamplingFrequency > 0 ? (int) Math.round(outputSamplingFrequency) : coreRate;
        if (codecId.endsWith("/SBR") && outputRate == coreRate) {
            outputRate = coreRate * 2;
        }
        var channelCount = (int) Math.min(channels, 8);
        try {
            return AudioConfigs.audioSpecificConfig(objectType, coreRate, channelCount == 8 ? 7 : Math.min(channelCount, 7),
                    outputRate > coreRate ? outputRate : 0, false);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Converts a BCP 47 language tag to the ISO 639-2 bibliographic code {@link Track#language()}
     * reports.
     *
     * @param tag the tag, such as {@code fr-CA}
     * @return the three-letter code, or {@code und} when the tag names no language with one
     */
    private static String bibliographicCode(String tag) {
        String code;
        try {
            code = Locale.forLanguageTag(tag).getISO3Language();
        } catch (MissingResourceException exception) {
            return "und";
        }
        return code.isEmpty() ? "und" : BIBLIOGRAPHIC.getOrDefault(code, code);
    }
}
