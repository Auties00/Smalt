package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaDocType;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Enumerates the files a transcode writes: a container format together with the profile of it
 * that decides which codecs it carries.
 *
 * <p>Several constants share a {@link ContainerFormat}: {@link #MP4}, {@link #M4A},
 * {@link #FRAGMENTED_MP4} and {@link #MOV} are all ISO base media files, {@link #WEBM} is the
 * WebM profile of {@link #MATROSKA}, {@link #OGG_FLAC} the FLAC profile of {@link #OGG}, and
 * {@link #AC3} and {@link #EAC3} the two Dolby elementary streams. Each constant knows the codecs
 * its muxer accepts ({@link #supports(Codec)}), the codecs a transcode picks when the caller names
 * none ({@link #videoCodecs()}, {@link #audioCodecs()}), and whether the file stores a display
 * orientation and edit lists, which decide how a transcode rotates video and trims copied streams.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum OutputContainer {
    /**
     * A fast-start MP4 file: the {@code moov} precedes the media data, so the file plays while it
     * downloads. The media data is spooled (in memory, then in a temporary file) until the end.
     */
    MP4(ContainerFormat.MP4, "mp4", "video/mp4", List.of(Codec.H264, Codec.VP9, Codec.AV1), List.of(Codec.AAC, Codec.OPUS)),

    /**
     * A fast-start QuickTime movie with the {@code qt  } brand, the file of the professional intra
     * codecs: it carries ProRes, DNxHD and every PCM format (A-law and mu-law included) besides the
     * codecs of {@link #MP4}, and a transcode picks ProRes 422 HQ when the caller names no video
     * codec.
     */
    MOV(ContainerFormat.MP4, "mov", "video/quicktime", List.of(Codec.PRORES, Codec.H264), List.of(Codec.AAC)),

    /**
     * An audio-only fast-start MP4 file with the {@code M4A} brand.
     */
    M4A(ContainerFormat.MP4, "m4a", "audio/mp4", List.of(), List.of(Codec.AAC, Codec.OPUS)),

    /**
     * A fragmented MP4 file, written as it is produced, so a streaming sink works and a truncated
     * file stays playable up to its last complete fragment.
     */
    FRAGMENTED_MP4(ContainerFormat.MP4, "mp4", "video/mp4", List.of(Codec.H264, Codec.VP9, Codec.AV1),
            List.of(Codec.AAC, Codec.OPUS)),

    /**
     * A Matroska file.
     */
    MATROSKA(ContainerFormat.MATROSKA, "mkv", "video/x-matroska", List.of(Codec.VP9, Codec.H264, Codec.VP8, Codec.AV1),
            List.of(Codec.OPUS, Codec.AAC, Codec.FLAC, Codec.PCM_S16LE)),

    /**
     * A WebM file: Matroska restricted to VP8, VP9, AV1, Opus and Vorbis.
     */
    WEBM(ContainerFormat.MATROSKA, "webm", "video/webm", List.of(Codec.VP9, Codec.VP8, Codec.AV1), List.of(Codec.OPUS, Codec.VORBIS)),

    /**
     * An Ogg Opus, Ogg Vorbis or Ogg FLAC file; a transcode picks Opus when the caller names no
     * audio codec.
     */
    OGG(ContainerFormat.OGG, "opus", "audio/ogg", List.of(), List.of(Codec.OPUS, Codec.VORBIS)),

    /**
     * An Ogg FLAC file: FLAC streams in Ogg pages, following the FLAC-to-Ogg mapping. It carries
     * FLAC only, so a transcode copies a FLAC stream and encodes any other audio to FLAC.
     */
    OGG_FLAC(ContainerFormat.OGG, "oga", "audio/ogg", List.of(), List.of(Codec.FLAC)),

    /**
     * A RIFF WAVE file, switching to RF64 when it outgrows 4 GiB.
     *
     * <p>It carries every PCM format and the coded audio
     * {@link com.github.auties00.smalt.container.wav.WavMuxer} writes (MPEG audio, AC-3, E-AC-3, AAC,
     * FLAC, Opus, AMR, DTS and the ADPCM and telephony codecs), but a transcode copies coded audio into
     * it only when the options ask to copy or name the stream's codec, or when no decoder of the codec is
     * installed: in {@link StreamMode#AUTO} without a codec it decodes coded audio to PCM, the samples
     * readers of WAVE files expect.
     */
    WAV(ContainerFormat.WAV, "wav", "audio/wav", List.of(), List.of(Codec.PCM_S16LE)),

    /**
     * An AIFF or AIFF-C file.
     */
    AIFF(ContainerFormat.AIFF, "aiff", "audio/aiff", List.of(), List.of(Codec.PCM_S16BE)),

    /**
     * An AAC elementary stream in ADTS framing.
     */
    ADTS(ContainerFormat.ADTS, "aac", "audio/aac", List.of(), List.of(Codec.AAC)),

    /**
     * An IVF file, the container of the VP8, VP9 and AV1 reference tools.
     */
    IVF(ContainerFormat.IVF, "ivf", "video/x-ivf", List.of(Codec.VP9, Codec.VP8, Codec.AV1), List.of()),

    /**
     * An H.264 elementary stream in the Annex B byte stream format, holding one video stream and no
     * timing: every frame follows the previous one in decoding order, and players assume a frame
     * rate.
     */
    H264(ContainerFormat.H264, "h264", "video/h264", List.of(Codec.H264), List.of()),

    /**
     * An HEVC elementary stream in the Annex B byte stream format, holding one video stream and no
     * timing, like {@link #H264}. No HEVC encoder exists, so a transcode only copies an HEVC stream
     * into it.
     */
    HEVC(ContainerFormat.HEVC, "h265", "video/h265", List.of(Codec.HEVC), List.of()),

    /**
     * An AV1 elementary stream in the low-overhead bitstream format ({@code .obu}), holding one
     * video stream and no timing, like {@link #H264}.
     */
    AV1(ContainerFormat.AV1, "obu", "video/av1", List.of(Codec.AV1), List.of()),

    /**
     * A YUV4MPEG2 file of uncompressed frames, holding one video stream: a transcode decodes the input and writes
     * 8-bit 4:2:0 frames, and copies a {@link Codec#RAW_VIDEO} stream only when its frames are in a format
     * YUV4MPEG2 carries.
     */
    Y4M(ContainerFormat.Y4M, "y4m", "video/x-yuv4mpeg", List.of(Codec.RAW_VIDEO), List.of()),

    /**
     * An MPEG audio elementary stream (MP3 or MP2).
     */
    MP3(ContainerFormat.MPEG_AUDIO, "mp3", "audio/mpeg", List.of(), List.of(Codec.MP3)),

    /**
     * A native FLAC stream.
     */
    FLAC(ContainerFormat.FLAC, "flac", "audio/flac", List.of(), List.of(Codec.FLAC)),

    /**
     * An AMR-NB or AMR-WB storage file.
     */
    AMR(ContainerFormat.AMR, "amr", "audio/amr", List.of(), List.of(Codec.AMR_NB, Codec.AMR_WB)),

    /**
     * A Dolby Digital (AC-3) elementary stream of sync frames ({@code .ac3}). It carries AC-3 only,
     * so a transcode encodes E-AC-3 and any other audio to AC-3.
     */
    AC3(ContainerFormat.AC3, "ac3", "audio/ac3", List.of(), List.of(Codec.AC3)),

    /**
     * A Dolby Digital Plus (E-AC-3) elementary stream of sync frames ({@code .ec3}). It carries
     * E-AC-3 only.
     */
    EAC3(ContainerFormat.AC3, "ec3", "audio/eac3", List.of(), List.of(Codec.EAC3)),

    /**
     * An MPEG-2 transport stream of 188-byte packets, written as it is produced, so a streaming
     * sink works; it carries H.264, HEVC, AV1, MPEG-4 Part 2, AAC, MP3, MP2, AC-3, E-AC-3, Opus and 16 and
     * 24-bit PCM.
     */
    MPEG_TS(ContainerFormat.MPEG_TS, "ts", "video/mp2t", List.of(Codec.H264, Codec.HEVC),
            List.of(Codec.AAC, Codec.MP3, Codec.OPUS)),

    /**
     * An AVI file with the OpenDML extensions, for players that expect the Video for Windows
     * format; it needs a seekable sink and carries video at a constant frame rate.
     */
    AVI(ContainerFormat.AVI, "avi", "video/x-msvideo", List.of(Codec.H264),
            List.of(Codec.AAC, Codec.MP3, Codec.PCM_S16LE)),

    /**
     * A SubRip ({@code .srt}) subtitle file, holding one subtitle stream.
     */
    SRT(ContainerFormat.SUBRIP, "srt", "application/x-subrip", List.of(), List.of()),

    /**
     * A WebVTT ({@code .vtt}) subtitle file, holding one subtitle stream.
     */
    WEBVTT(ContainerFormat.WEBVTT, "vtt", "text/vtt", List.of(), List.of()),

    /**
     * An Advanced SubStation Alpha ({@code .ass}, v4.00+) script, holding one subtitle stream; an SSA script
     * is converted to ASS.
     */
    ASS(ContainerFormat.ASS, "ass", "text/x-ssa", List.of(), List.of()),

    /**
     * A SubStation Alpha ({@code .ssa}, v4.00) script, holding one subtitle stream; an ASS script is
     * converted to SSA.
     */
    SSA(ContainerFormat.ASS, "ssa", "text/x-ssa", List.of(), List.of()),

    /**
     * A TTML document ({@code .ttml}) in the IMSC 1.0.1 text profile, holding one subtitle stream.
     */
    TTML(ContainerFormat.TTML, "ttml", "application/ttml+xml", List.of(), List.of()),

    /**
     * A SAMI ({@code .smi}) subtitle file, holding one subtitle stream per language.
     */
    SAMI(ContainerFormat.SAMI, "smi", "application/x-sami", List.of(), List.of()),

    /**
     * An LRC ({@code .lrc}) lyrics file, holding one subtitle stream.
     */
    LRC(ContainerFormat.LRC, "lrc", "application/x-lrc", List.of(), List.of()),

    /**
     * A MicroDVD ({@code .sub}) subtitle file at 24000/1001 frames per second, holding one subtitle stream.
     */
    MICRODVD(ContainerFormat.MICRODVD, "sub", "text/x-microdvd", List.of(), List.of()),

    /**
     * A Blu-ray presentation graphic stream file ({@code .sup}), holding one PGS bitmap subtitle stream.
     */
    SUP(ContainerFormat.SUP, "sup", "application/x-pgs", List.of(), List.of());

    /**
     * The container format written.
     */
    private final ContainerFormat format;

    /**
     * The conventional file extension, without the dot.
     */
    private final String extension;

    /**
     * The media type of the file.
     */
    private final String mimeType;

    /**
     * The video codecs a transcode tries, in order, when the caller names none.
     */
    private final List<Codec> videoCodecs;

    /**
     * The audio codecs a transcode tries, in order, when the caller names none.
     */
    private final List<Codec> audioCodecs;

    /**
     * Constructs a constant.
     *
     * @param format      the container format written
     * @param extension   the conventional file extension
     * @param mimeType    the media type
     * @param videoCodecs the preferred video codecs
     * @param audioCodecs the preferred audio codecs
     */
    OutputContainer(ContainerFormat format, String extension, String mimeType, List<Codec> videoCodecs,
                    List<Codec> audioCodecs) {
        this.format = format;
        this.extension = extension;
        this.mimeType = mimeType;
        this.videoCodecs = videoCodecs;
        this.audioCodecs = audioCodecs;
    }

    /**
     * Returns the container format written.
     *
     * @return the format
     */
    public ContainerFormat format() {
        return format;
    }

    /**
     * Returns the conventional file extension.
     *
     * @return the extension without the leading dot, for example {@code "webm"}
     */
    public String extension() {
        return extension;
    }

    /**
     * Returns the media type of the file.
     *
     * @return the type, for example {@code "video/webm"}
     */
    public String mimeType() {
        return mimeType;
    }

    /**
     * Returns the video codecs a transcode tries, in order, when the caller names no codec.
     *
     * <p>The first codec an encoder is available for is used.
     *
     * @return the codecs, empty for an audio-only container; unmodifiable
     */
    public List<Codec> videoCodecs() {
        return videoCodecs;
    }

    /**
     * Returns the audio codecs a transcode tries, in order, when the caller names no codec.
     *
     * <p>The first codec an encoder is available for is used.
     *
     * @return the codecs, empty for a video-only container; unmodifiable
     */
    public List<Codec> audioCodecs() {
        return audioCodecs;
    }

    /**
     * Returns the text subtitle codecs a transcode converts subtitles to, in order of preference,
     * when the file cannot carry a stream's own codec and the caller names none.
     *
     * <p>MP4 and QuickTime files carry 3GPP timed text and WebVTT, Matroska files SubRip, ASS and WebVTT, WebM
     * files and WebVTT files WebVTT, SubRip, SAMI and MicroDVD files SubRip, SSA and ASS files ASS, TTML files
     * TTML and LRC files WebVTT; the other files carry no subtitles.
     *
     * @return the codecs, empty when the file carries no subtitles; unmodifiable
     */
    public List<Codec> subtitleCodecs() {
        return switch (this) {
            case MP4, MOV -> List.of(Codec.MOV_TEXT, Codec.WEBVTT);
            case MATROSKA -> List.of(Codec.SUBRIP, Codec.ASS, Codec.WEBVTT);
            case WEBM, WEBVTT, LRC -> List.of(Codec.WEBVTT);
            case SRT, SAMI, MICRODVD -> List.of(Codec.SUBRIP);
            case ASS, SSA -> List.of(Codec.ASS);
            case TTML -> List.of(Codec.TTML);
            default -> List.of();
        };
    }

    /**
     * Returns whether the file can carry a stream of a codec.
     *
     * <p>The answer follows the muxer of the container ({@link Containers#isMuxable(ContainerFormat,
     * Codec)}) and the brand or profile it writes: {@link #M4A} carries the compressed audio of {@link #MP4},
     * {@link #MOV} the codecs of {@link Mp4Muxer#supports} for the QuickTime brand,
     * {@link #WEBM} the WebM codecs only, {@link #OGG_FLAC} FLAC only, {@link #AC3} AC-3 only and
     * {@link #EAC3} E-AC-3 only.
     *
     * @param codec the codec of the stream
     * @return {@code true} when the file can carry the codec
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public boolean supports(Codec codec) {
        Objects.requireNonNull(codec, "codec");
        return switch (this) {
            case M4A -> Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, codec);
            case MP4 -> codec.isSubtitle() ? subtitleCodecs().contains(codec) : Containers.isMuxable(format, codec);
            case MOV -> codec.isSubtitle() ? subtitleCodecs().contains(codec)
                    : Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, codec);
            case FRAGMENTED_MP4 -> FragmentedMp4Muxer.supports(codec);
            case MATROSKA -> MatroskaMuxer.supports(MatroskaDocType.MATROSKA, codec);
            case WEBM -> MatroskaMuxer.supports(MatroskaDocType.WEBM, codec);
            case OGG_FLAC -> codec == Codec.FLAC;
            case AC3 -> codec == Codec.AC3;
            case EAC3 -> codec == Codec.EAC3;
            default -> Containers.isMuxable(format, codec);
        };
    }

    /**
     * Returns whether a transcode in {@link StreamMode#AUTO} copies a stream of a codec the file can carry without the
     * options naming the codec.
     *
     * @param codec the codec of the stream
     * @return {@code false} for coded audio in {@link #WAV} that an installed decoder decodes to PCM, {@code true}
     * otherwise
     */
    boolean copiesByDefault(Codec codec) {
        return this != WAV || codec.isPcm() || !AudioDecoders.isSupported(codec);
    }

    /**
     * Returns whether the file stores a display orientation for video, so that a transcode can
     * keep the coded pixels and record the rotation instead of turning the pixels upright.
     *
     * @return {@code true} for the MP4 family
     */
    public boolean storesOrientation() {
        return format == ContainerFormat.MP4;
    }

    /**
     * Returns whether the file stores a sample aspect ratio for video, so that a transcode can keep
     * non-square samples as stored instead of resampling them to square ones.
     *
     * @return {@code true} for the MP4 family, Matroska, WebM, AVI and YUV4MPEG2
     */
    public boolean storesSampleAspectRatio() {
        return format == ContainerFormat.MP4 || format == ContainerFormat.MATROSKA || format == ContainerFormat.AVI
                || format == ContainerFormat.Y4M;
    }

    /**
     * Returns whether the file stores transparent video as a second coded stream, the alpha frames a
     * track that {@linkplain com.github.auties00.smalt.track.VideoTrack#hasAlpha() carries alpha}
     * attaches to its packets, as transparent VP8 and VP9 video in WebM.
     *
     * @return {@code true} for Matroska and WebM
     */
    public boolean storesAlpha() {
        return format == ContainerFormat.MATROSKA;
    }

    /**
     * Returns whether the file stores edit lists, so that a copied stream that starts before the
     * requested start (at a key frame, or with decoder pre-roll) is still presented from exactly
     * that start.
     *
     * @return {@code true} for the MP4 family
     */
    public boolean hasEditLists() {
        return format == ContainerFormat.MP4;
    }

    /**
     * Returns whether the file stores tags, the tags of its tracks, attached pictures or chapters, so that a
     * transcode carries them into it ({@link TranscodeOptions#tagMode()}).
     *
     * <p>What each file stores is what its muxer writes
     * ({@link Muxer#setTags(com.github.auties00.smalt.metadata.MediaTags) Muxer.setTags}): everything in the MP4
     * family, Matroska, WebM, Ogg, FLAC, MP3, AC-3, E-AC-3, AMR, ADTS, WAV and AIFF files; the tags and the track
     * names in an AVI file; the track names and some audio dispositions in a transport stream; and the title,
     * artist and album in an LRC file.
     *
     * @return {@code false} for IVF, YUV4MPEG2, the video elementary streams and the subtitle files other than LRC
     */
    public boolean supportsTags() {
        return switch (this) {
            case IVF, H264, HEVC, AV1, Y4M, SRT, WEBVTT, ASS, SSA, TTML, SAMI, MICRODVD -> false;
            default -> true;
        };
    }

    /**
     * Returns whether the file can hold more than one audio track.
     *
     * @return {@code true} for the MP4 family, Matroska, WebM, Ogg and MPEG transport streams
     */
    boolean carriesSeveralTracks() {
        return format == ContainerFormat.MP4 || format == ContainerFormat.MATROSKA || format == ContainerFormat.OGG
                || format == ContainerFormat.MPEG_TS;
    }

    /**
     * Returns whether the file needs a codec's out-of-band configuration (such as {@code avcC} or
     * an {@code AudioSpecificConfig}) to carry a copied stream.
     *
     * @param codec the codec of the stream
     * @return {@code true} when a track of {@code codec} without a configuration cannot be written
     */
    boolean needsConfig(Codec codec) {
        return switch (format) {
            case MP4 -> switch (codec) {
                case H264, HEVC, AAC, OPUS, ALAC, FLAC -> true;
                default -> false;
            };
            case MATROSKA -> switch (codec) {
                case H264, HEVC, AAC, FLAC, VORBIS, ALAC -> true;
                default -> false;
            };
            case MPEG_TS -> switch (codec) {
                case H264, HEVC, MPEG4_PART2, AAC, OPUS -> true;
                default -> false;
            };
            case OGG -> codec == Codec.VORBIS || codec == Codec.FLAC;
            case WAV -> codec == Codec.AAC || codec == Codec.FLAC;
            case H264 -> codec == Codec.H264;
            case HEVC -> codec == Codec.HEVC;
            case Y4M -> codec == Codec.RAW_VIDEO;
            default -> false;
        };
    }

    /**
     * Returns the constant whose conventional extension matches a file name or extension.
     *
     * <p>{@code mp4} maps to {@link #MP4}, {@code mov} and {@code qt} to {@link #MOV}, {@code m4a} to
     * {@link #M4A}, {@code mkv} and
     * {@code mka} to {@link #MATROSKA}, {@code webm} to {@link #WEBM}, {@code ogg}, {@code oga}
     * and {@code opus} to {@link #OGG}, {@code wav} to {@link #WAV}, {@code aif}, {@code aiff} and
     * {@code aifc} to {@link #AIFF}, {@code aac} and {@code adts} to {@link #ADTS}, and
     * {@code ivf}, {@code mp3}, {@code flac}, {@code amr}, {@code ac3} and {@code avi} to their constants,
     * {@code ec3} and {@code eac3} to {@link #EAC3}, {@code h264} and {@code 264} to {@link #H264},
     * {@code h265}, {@code 265} and {@code hevc} to {@link #HEVC}, {@code obu} to {@link #AV1}, {@code y4m} to
     * {@link #Y4M}, {@code ts} to {@link #MPEG_TS}, {@code srt} to {@link #SRT}, {@code vtt} to
     * {@link #WEBVTT}, {@code ass} and {@code ssa} to {@link #ASS} and {@link #SSA}, {@code ttml},
     * {@code dfxp} and {@code xml} to {@link #TTML}, {@code smi} and {@code sami} to {@link #SAMI},
     * {@code lrc} to {@link #LRC} and {@code sub} to {@link #MICRODVD}; no extension
     * maps to {@link #OGG_FLAC}, whose {@code oga} maps to {@link #OGG}. Matching ignores case.
     *
     * @param fileName a file name such as {@code "clip.webm"}, or a bare extension
     * @return the constant, or empty when the extension is not recognized
     * @throws NullPointerException if {@code fileName} is {@code null}
     */
    public static Optional<OutputContainer> forFileName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        var dot = fileName.lastIndexOf('.');
        var extension = (dot >= 0 ? fileName.substring(dot + 1) : fileName).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(switch (extension) {
            case "mp4", "m4v" -> MP4;
            case "mov", "qt" -> MOV;
            case "m4a" -> M4A;
            case "mkv", "mka" -> MATROSKA;
            case "webm" -> WEBM;
            case "ogg", "oga", "opus" -> OGG;
            case "wav" -> WAV;
            case "aif", "aiff", "aifc" -> AIFF;
            case "aac", "adts" -> ADTS;
            case "ivf" -> IVF;
            case "y4m" -> Y4M;
            case "mp3" -> MP3;
            case "flac" -> FLAC;
            case "amr" -> AMR;
            case "ac3" -> AC3;
            case "ec3", "eac3" -> EAC3;
            case "h264", "264" -> H264;
            case "h265", "265", "hevc" -> HEVC;
            case "obu" -> AV1;
            case "ts" -> MPEG_TS;
            case "avi" -> AVI;
            case "srt" -> SRT;
            case "vtt" -> WEBVTT;
            case "ass" -> ASS;
            case "ssa" -> SSA;
            case "ttml", "dfxp", "xml" -> TTML;
            case "smi", "sami" -> SAMI;
            case "lrc" -> LRC;
            case "sub" -> MICRODVD;
            default -> null;
        });
    }

    /**
     * Creates the muxer that writes this file.
     *
     * @param sink the output, written from its position
     * @return the muxer, which the caller finishes and closes
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the sink cannot hold the
     *                                                               file
     */
    Muxer createMuxer(MediaSink sink) {
        return Containers.openMuxer(this, sink);
    }
}
