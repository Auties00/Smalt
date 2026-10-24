package com.github.auties00.smalt.track;

import java.util.OptionalInt;

/**
 * Enumerates the coding formats an elementary stream can use.
 *
 * <p>Demuxers identify every track they recognize, including tracks whose codec this module
 * cannot decode, so callers can report precisely what an input contains. Each constant names the
 * {@link TrackKind} of stream it codes and a short lowercase name used in diagnostics.
 *
 * <p>Constants may be added in minor releases as new formats are recognized, so a {@code switch}
 * over this type needs a {@code default} branch.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum Codec {
    /**
     * ITU-T H.264 / MPEG-4 AVC.
     */
    H264(TrackKind.VIDEO, "h264"),

    /**
     * ITU-T H.265 / MPEG-H HEVC.
     */
    HEVC(TrackKind.VIDEO, "hevc"),

    /**
     * VP8.
     */
    VP8(TrackKind.VIDEO, "vp8"),

    /**
     * VP9.
     */
    VP9(TrackKind.VIDEO, "vp9"),

    /**
     * AOMedia AV1.
     */
    AV1(TrackKind.VIDEO, "av1"),

    /**
     * MPEG-4 Part 2 Visual (DivX, Xvid).
     */
    MPEG4_PART2(TrackKind.VIDEO, "mpeg4"),

    /**
     * ITU-T H.263.
     */
    H263(TrackKind.VIDEO, "h263"),

    /**
     * Motion JPEG: every frame is an independent JPEG.
     */
    MJPEG(TrackKind.VIDEO, "mjpeg"),

    /**
     * Motion JPEG 2000 (ISO/IEC 15444-3): every frame is an independent JPEG 2000 image.
     *
     * <p>A packet is one frame in one of three forms: a JP2 file (a frame that starts with the JPEG 2000 signature
     * box), a raw codestream (one that starts with the {@code SOC} and {@code SIZ} markers {@code FF 4F FF 51}), or
     * the sequence of boxes a Motion JPEG 2000 sample holds, one Contiguous Codestream box ({@code jp2c}) for a
     * progressive frame and two, the earlier field first, for an interlaced one, possibly with a JP2 Header box
     * ({@code jp2h}) that describes them. The configuration, when the track has one, is the JP2 Header box (header
     * included) of the {@code mjp2} sample entry: it declares the color space and the channels of the frames whose
     * packet carries no JP2 header of its own.
     */
    JPEG2000(TrackKind.VIDEO, "jpeg2000"),

    /**
     * Apple ProRes.
     */
    PRORES(TrackKind.VIDEO, "prores"),

    /**
     * Avid DNxHD and DNxHR, the VC-3 format of SMPTE ST 2019-1.
     *
     * <p>A packet is one frame: its coding units (two for field-encoded interlaced frames),
     * possibly followed by the alpha Avid's QuickTime encoders store after the video and the size
     * of the video part in four big-endian bytes. The configuration is the QuickTime sample entry
     * type in ASCII ({@code AVdn} or {@code AVdh}), optionally followed by the extension atoms of
     * Avid's sample descriptions ({@code ACLR}, {@code APRG}, {@code ARES}, {@code AALP} and
     * {@code ADHR}, sizes and types included), which QuickTime and Matroska muxers write into the
     * sample description; an {@code AALP} atom declares the alpha stored after the video.
     */
    DNXHD(TrackKind.VIDEO, "dnxhd"),

    /**
     * Xiph.Org Theora.
     *
     * <p>A packet is one frame; an empty packet repeats the previous frame, and a frame whose frame type bit (bit 6
     * of its first byte) is clear is a key frame. The configuration is the three Theora headers (identification,
     * comment and setup), Xiph-laced as for {@link #VORBIS}.
     */
    THEORA(TrackKind.VIDEO, "theora"),

    /**
     * An animated GIF treated as a video stream.
     */
    GIF(TrackKind.VIDEO, "gif"),

    /**
     * A video codec the demuxer could not identify.
     */
    UNKNOWN_VIDEO(TrackKind.VIDEO, "unknown video"),

    /**
     * MPEG-4 Advanced Audio Coding (any object type; the codec configuration tells which).
     */
    AAC(TrackKind.AUDIO, "aac"),

    /**
     * MPEG-1/2/2.5 Audio Layer III.
     */
    MP3(TrackKind.AUDIO, "mp3"),

    /**
     * MPEG-1/2 Audio Layer I or II.
     */
    MP2(TrackKind.AUDIO, "mp2"),

    /**
     * Opus.
     */
    OPUS(TrackKind.AUDIO, "opus"),

    /**
     * Vorbis.
     */
    VORBIS(TrackKind.AUDIO, "vorbis"),

    /**
     * Free Lossless Audio Codec.
     */
    FLAC(TrackKind.AUDIO, "flac"),

    /**
     * Apple Lossless Audio Codec.
     */
    ALAC(TrackKind.AUDIO, "alac"),

    /**
     * Adaptive Multi-Rate narrowband speech.
     */
    AMR_NB(TrackKind.AUDIO, "amr-nb"),

    /**
     * Adaptive Multi-Rate wideband speech.
     */
    AMR_WB(TrackKind.AUDIO, "amr-wb"),

    /**
     * Xiph.Org Speex speech.
     *
     * <p>A packet holds the number of frames the configuration declares. The configuration is the 80-byte
     * {@code "Speex   "} identification header, which gives the sampling rate, channel count, frame size and frames
     * per packet.
     */
    SPEEX(TrackKind.AUDIO, "speex"),

    /**
     * Dolby Digital (AC-3).
     */
    AC3(TrackKind.AUDIO, "ac3"),

    /**
     * Dolby Digital Plus (E-AC-3).
     */
    EAC3(TrackKind.AUDIO, "eac3"),

    /**
     * DTS Coherent Acoustics (ETSI TS 102 114), including DTS-HD frames, whose extension substream follows the core
     * frame.
     *
     * <p>A packet is one frame in the 16-bit big-endian form, which starts with the sync word {@code 0x7FFE8001}.
     * The track has no configuration.
     */
    DTS(TrackKind.AUDIO, "dts"),

    /**
     * Dolby TrueHD.
     *
     * <p>A packet is one access unit; the units that open with a major sync are the key frames. The track has no
     * configuration.
     */
    TRUEHD(TrackKind.AUDIO, "truehd"),

    /**
     * Uncompressed signed 16-bit little-endian PCM.
     */
    PCM_S16LE(TrackKind.AUDIO, "pcm_s16le"),

    /**
     * Uncompressed signed 16-bit big-endian PCM.
     */
    PCM_S16BE(TrackKind.AUDIO, "pcm_s16be"),

    /**
     * Uncompressed signed 24-bit little-endian PCM, packed in three bytes.
     */
    PCM_S24LE(TrackKind.AUDIO, "pcm_s24le"),

    /**
     * Uncompressed signed 24-bit big-endian PCM, packed in three bytes.
     */
    PCM_S24BE(TrackKind.AUDIO, "pcm_s24be"),

    /**
     * Uncompressed signed 32-bit little-endian PCM.
     */
    PCM_S32LE(TrackKind.AUDIO, "pcm_s32le"),

    /**
     * Uncompressed signed 32-bit big-endian PCM.
     */
    PCM_S32BE(TrackKind.AUDIO, "pcm_s32be"),

    /**
     * Uncompressed signed 64-bit little-endian PCM.
     */
    PCM_S64LE(TrackKind.AUDIO, "pcm_s64le"),

    /**
     * Uncompressed signed 64-bit big-endian PCM.
     */
    PCM_S64BE(TrackKind.AUDIO, "pcm_s64be"),

    /**
     * Uncompressed 32-bit little-endian IEEE float PCM.
     */
    PCM_F32LE(TrackKind.AUDIO, "pcm_f32le"),

    /**
     * Uncompressed 32-bit big-endian IEEE float PCM.
     */
    PCM_F32BE(TrackKind.AUDIO, "pcm_f32be"),

    /**
     * Uncompressed 64-bit little-endian IEEE float PCM.
     */
    PCM_F64LE(TrackKind.AUDIO, "pcm_f64le"),

    /**
     * Uncompressed 64-bit big-endian IEEE float PCM.
     */
    PCM_F64BE(TrackKind.AUDIO, "pcm_f64be"),

    /**
     * Uncompressed unsigned 8-bit PCM.
     */
    PCM_U8(TrackKind.AUDIO, "pcm_u8"),

    /**
     * Uncompressed signed 8-bit PCM.
     */
    PCM_S8(TrackKind.AUDIO, "pcm_s8"),

    /**
     * ITU-T G.711 A-law.
     */
    PCM_ALAW(TrackKind.AUDIO, "pcm_alaw"),

    /**
     * ITU-T G.711 mu-law.
     */
    PCM_MULAW(TrackKind.AUDIO, "pcm_mulaw"),

    /**
     * An audio codec the demuxer could not identify.
     */
    UNKNOWN_AUDIO(TrackKind.AUDIO, "unknown audio"),

    /**
     * SubRip text subtitles: the Matroska {@code S_TEXT/UTF8} codec and the cues of an SRT file.
     *
     * <p>A packet holds the text of one cue in UTF-8, without the cue number and timing lines, its
     * SubRip markup ({@code <i>}, {@code <b>}, {@code <u>}, {@code <font>}, {@code {\an8}}) kept as
     * text and its lines separated by line feeds; the packet's presentation timestamp and duration
     * are the cue's start and length. Packets may overlap. The track has no configuration.
     */
    SUBRIP(TrackKind.DATA, "subrip"),

    /**
     * WebVTT text subtitles: the cues of a WebVTT file, the Matroska {@code S_TEXT/WEBVTT} and
     * {@code D_WEBVTT} codecs, and the ISO/IEC 14496-30 {@code wvtt} MP4 sample entry.
     *
     * <p>A packet holds the payload of one cue in UTF-8, its WebVTT markup and character
     * references kept as text and its lines separated by line feeds; the cue identifier and the
     * cue settings travel in the packet's {@link PacketSideData#extra()} under
     * {@code "webvtt:identifier"} and {@code "webvtt:settings"} when present. The packet's
     * presentation timestamp and duration are the cue's start and length, and packets may
     * overlap. The configuration is the WebVTT header in UTF-8: the {@code WEBVTT} line and the
     * blocks that precede the first cue ({@code STYLE}, {@code REGION}, {@code NOTE}).
     */
    WEBVTT(TrackKind.DATA, "webvtt"),

    /**
     * 3GPP timed text (3GPP TS 26.245), the {@code tx3g} subtitles of MP4 and MOV files, and the QuickTime
     * {@code text} media the MP4 demuxer converts to it.
     *
     * <p>A packet is one text sample: a 16-bit big-endian text length, the text in UTF-8, then
     * the text modifier boxes ({@code styl}, {@code hlit}, {@code tbox} and the others); a
     * sample with no text clears the screen. Samples follow each other without gaps or overlaps.
     * The configuration is the body of the {@code tx3g} sample entry after its eight leading
     * bytes: the display flags, justification, background color, default text box, default
     * style and the {@code ftab} font table.
     */
    MOV_TEXT(TrackKind.DATA, "mov_text"),

    /**
     * Advanced SubStation Alpha (and SubStation Alpha) subtitles, the Matroska {@code S_TEXT/ASS}
     * and {@code S_TEXT/SSA} codecs and the events of {@code .ass} and {@code .ssa} files.
     *
     * <p>A packet holds one event in UTF-8 in the Matroska block form: the fields
     * {@code ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text} separated by
     * commas, its override tags kept as text, the layer 0 for an SSA event. The configuration is the script
     * header in UTF-8: every line of the script but its {@code Dialogue} lines, so the {@code [Script Info]} and
     * styles sections, the {@code [Events]} format line and any other section; its {@code ScriptType} and styles
     * section tell an SSA (v4.00) script from an ASS (v4.00+) one.
     */
    ASS(TrackKind.DATA, "ass"),

    /**
     * Timed Text Markup Language (W3C TTML, including its IMSC profiles), the ISO/IEC 14496-30
     * {@code stpp} subtitles of MP4 files.
     *
     * <p>A packet is one TTML document in XML, as an MP4 sample stores it, covering the packet's
     * presentation timestamp and duration; its time expressions count from the start of the
     * track, not of the packet. Packets follow each other without overlapping. The configuration
     * is the body of the {@code stpp} sample entry after its eight leading bytes: the
     * {@code namespace}, {@code schema_location} and {@code auxiliary_mime_types} fields as
     * null-terminated UTF-8 strings (the first a space-separated list of the XML namespaces the
     * documents use, the others possibly empty), then any child boxes of the entry, such as
     * {@code btrt}. A track without a configuration uses the TTML namespace alone:
     * {@snippet :
     * http://www.w3.org/ns/ttml\0\0\0
     * }
     * The packets are documents rather than cues, so this is not one of the text formats of
     * {@link #isSubtitle()}; the {@code subtitle} package decodes their cues.
     */
    TTML(TrackKind.DATA, "ttml"),

    /**
     * A data, subtitle, or metadata stream.
     */
    DATA(TrackKind.DATA, "data");

    /**
     * The kind of stream this codec codes.
     */
    private final TrackKind kind;

    /**
     * The short lowercase name used in diagnostics.
     */
    private final String displayName;

    /**
     * Constructs a constant.
     *
     * @param kind        the kind of stream
     * @param displayName the short diagnostic name
     */
    Codec(TrackKind kind, String displayName) {
        this.kind = kind;
        this.displayName = displayName;
    }

    /**
     * Returns the kind of stream this codec codes.
     *
     * @return the stream kind
     */
    public TrackKind kind() {
        return kind;
    }

    /**
     * Returns the short lowercase name used in diagnostics.
     *
     * @return the display name, for example {@code "h264"}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns whether this constant is one of the uncompressed PCM formats.
     *
     * @return {@code true} for the {@code PCM_*} constants
     */
    public boolean isPcm() {
        return name().startsWith("PCM_");
    }

    /**
     * Returns the size of one coded sample of one channel of a PCM format.
     *
     * <p>A-law and mu-law samples are 8 bits wide; 24-bit samples are packed in three bytes.
     *
     * @return the bits per sample, 8, 16, 24, 32 or 64, or empty when this constant is not a
     *         {@linkplain #isPcm() PCM format}
     */
    public OptionalInt pcmBitsPerSample() {
        return switch (this) {
            case PCM_U8, PCM_S8, PCM_ALAW, PCM_MULAW -> OptionalInt.of(8);
            case PCM_S16LE, PCM_S16BE -> OptionalInt.of(16);
            case PCM_S24LE, PCM_S24BE -> OptionalInt.of(24);
            case PCM_S32LE, PCM_S32BE, PCM_F32LE, PCM_F32BE -> OptionalInt.of(32);
            case PCM_S64LE, PCM_S64BE, PCM_F64LE, PCM_F64BE -> OptionalInt.of(64);
            default -> OptionalInt.empty();
        };
    }

    /**
     * Returns whether this constant is one of the text subtitle formats.
     *
     * @return {@code true} for {@link #SUBRIP}, {@link #WEBVTT}, {@link #MOV_TEXT} and {@link #ASS}
     */
    public boolean isSubtitle() {
        return this == SUBRIP || this == WEBVTT || this == MOV_TEXT || this == ASS;
    }
}
