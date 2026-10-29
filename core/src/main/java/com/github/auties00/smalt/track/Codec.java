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
     * ISO/IEC 11172-2 MPEG-1 video, {@code stream_type 0x01} of MPEG transport streams and the Matroska
     * {@code V_MPEG1} codec.
     *
     * <p>A packet is one picture as the elementary stream codes it, from its start codes on. The closed captions it
     * carries in ATSC user data are read by {@link com.github.auties00.smalt.subtitle.ClosedCaptions}.
     */
    MPEG1_VIDEO(TrackKind.VIDEO, "mpeg1video"),

    /**
     * ITU-T H.262 / ISO/IEC 13818-2 MPEG-2 video, {@code stream_type 0x02} of MPEG transport streams and the
     * Matroska {@code V_MPEG2} codec.
     *
     * <p>A packet is one picture as the elementary stream codes it, from its start codes on (the transport stream
     * demuxer passes one PES packet per packet). The closed captions it carries in ATSC user data are read by
     * {@link com.github.auties00.smalt.subtitle.ClosedCaptions}.
     */
    MPEG2_VIDEO(TrackKind.VIDEO, "mpeg2video"),

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
     * FFV1, the lossless intra-frame video codec of RFC 9043.
     *
     * <p>A packet is one frame. Versions 0 and 1 carry no configuration; version 3 and later carry the
     * configuration record (Matroska {@code CodecPrivate}, the MP4 {@code glbl} box, the bytes of an AVI
     * {@code strf} chunk after the bitmap header), which holds the coding parameters of every frame.
     */
    FFV1(TrackKind.VIDEO, "ffv1"),

    /**
     * An animated GIF treated as a video stream.
     */
    GIF(TrackKind.VIDEO, "gif"),

    /**
     * Uncompressed video, one frame per packet, laid out as the
     * {@link com.github.auties00.smalt.video.RawVideoFormat} of the track's codec configuration describes
     * (MP4 {@code raw }, {@code 2vuy}, {@code v210} and the other uncompressed sample entries, Matroska
     * {@code V_UNCOMPRESSED}, AVI {@code DIB} and FourCC frames, YUV4MPEG2).
     */
    RAW_VIDEO(TrackKind.VIDEO, "rawvideo"),

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
     * per packet. A track without one carries Speex as FLV does: mono, of the mode of its sampling rate, each packet
     * holding as many frames as it codes.
     */
    SPEEX(TrackKind.AUDIO, "speex"),

    /**
     * WavPack lossless, hybrid lossy, floating-point and DSD audio.
     *
     * <p>A packet holds one frame of every channel: either the complete WavPack blocks of a {@code .wv} file, each
     * starting with its 32-byte {@code "wvpk"} header (with any blocks without audio around them), or the blocks as
     * Matroska ({@code A_WAVPACK4}) stores them,
     * without the header: the block's sample count, then the flags and checksum of each block (and its size when
     * the frame has several blocks) before its metadata. The configuration is the stream version in two
     * little-endian bytes when the packets have no headers, and is absent otherwise. The correction data of a hybrid
     * stream travels as the packet's side data: the complete correction blocks under {@code "wavpack:correction"},
     * or the Matroska {@code BlockAdditional} of {@code BlockAddID} 1.
     */
    WAVPACK(TrackKind.AUDIO, "wavpack"),

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
     * IMA ADPCM (the DVI ADPCM of the IMA Digital Audio Focus recommendation) in the block layout of Microsoft's
     * WAVE format {@code 0x0011}, 4 bits per sample.
     *
     * <p>A packet is one block, which decodes on its own: for each channel a four-byte header (the first sample as a
     * signed 16-bit little-endian integer, the step index from 0 to 88 and a zero byte), then the codes of the other
     * samples in groups of four bytes, eight samples of one channel each, one group of every channel in turn, the low
     * nibble of each byte first. A block of {@code B} bytes and {@code C} channels, {@code B - 4C} a multiple of
     * {@code 4C}, codes {@code 1 + 2 * (B - 4C) / C} samples per channel. The packets of a stream have one size,
     * except the last, which may be shorter. The track has no configuration.
     */
    ADPCM_IMA_WAV(TrackKind.AUDIO, "adpcm_ima_wav"),

    /**
     * Microsoft ADPCM, WAVE format {@code 0x0002}, 4 bits per sample.
     *
     * <p>A packet is one block, which decodes on its own: the predictor index of each channel (one byte), the initial
     * quantization delta of each channel, the second sample of each channel and the first sample of each channel
     * (signed 16-bit little-endian integers), then the codes of the other samples, one sample of every channel in
     * turn, the high nibble of each byte first. A block of {@code B} bytes and {@code C} channels codes
     * {@code 2 + 2 * (B - 7C) / C} samples per channel. The packets of a stream have one size, except the last,
     * which may be shorter. The configuration, when present, is what an {@code ADPCMWAVEFORMAT} stores after its
     * {@code cbSize} field: {@code wSamplesPerBlock}, {@code wNumCoef} and {@code wNumCoef} pairs of predictor
     * coefficients, as 16-bit little-endian integers; without one, the predictors are the seven standard pairs.
     */
    ADPCM_MS(TrackKind.AUDIO, "adpcm_ms"),

    /**
     * ETSI GSM 06.10 full-rate speech in Microsoft's packing, WAVE format {@code 0x0031}: one channel, usually at
     * 8 kHz.
     *
     * <p>A packet holds one or more 65-byte blocks, each two 20 ms frames of 260 bits (320 samples per block)
     * packed from the least significant bit of each byte, the second frame starting in the middle of the 33rd byte.
     * The track has no configuration.
     */
    GSM_MS(TrackKind.AUDIO, "gsm_ms"),

    /**
     * ITU-T G.722 sub-band ADPCM: one channel, at 16 kHz (also where RTP declares a clock rate of 8 kHz).
     *
     * <p>A packet is any number of bytes, each coding two samples: the 2-bit code of the higher sub-band in its top
     * bits and the 6-bit code of the lower sub-band below them (the 64 kbit/s mode; the 56 and 48 kbit/s modes leave
     * the lowest one or two bits to auxiliary data). The track has no configuration.
     */
    ADPCM_G722(TrackKind.AUDIO, "adpcm_g722"),

    /**
     * ITU-T G.726 ADPCM: one channel, usually at 8 kHz, with codes of 2, 3, 4 or 5 bits (16, 24, 32 and 40 kbit/s
     * at 8 kHz), the track's {@link AudioTrack#bitsPerSample() bitsPerSample}.
     *
     * <p>A packet holds the codes of consecutive samples packed from the most significant bit of each byte (the
     * packing of ITU-T I.366.2 and of WAVE format {@code 0x0045}). Every packet but the last ends on a byte boundary
     * after a whole number of codes: any number of bytes for 2 and 4-bit codes, a multiple of 3 or 5 bytes (8 codes)
     * for 3 and 5-bit codes. The track has no configuration.
     */
    ADPCM_G726(TrackKind.AUDIO, "adpcm_g726"),

    /**
     * IMA ADPCM in Apple's QuickTime layout ({@code ima4}), 4 bits per sample, the AIFF-C and QuickTime
     * {@code ima4} compression type.
     *
     * <p>A packet holds one or more frames of 34 bytes per channel, the channels of a frame one after the other:
     * a two-byte big-endian header (the predictor's top nine bits and the 7-bit step index), then the codes of 64
     * samples, the low nibble of each byte first. A frame continues the predictor of the channel's previous frame
     * when its header matches that predictor's top nine bits and step index, and restarts from the header
     * otherwise. The track has no configuration.
     */
    ADPCM_IMA_QT(TrackKind.AUDIO, "adpcm_ima_qt"),

    /**
     * ETSI GSM 06.10 full-rate speech in its 33-byte frames: one channel, usually at 8 kHz, the AIFF-C
     * {@code GSM } and QuickTime {@code agsm} compression types and the RTP payload of RFC 3551.
     *
     * <p>A packet holds one or more frames of 160 samples, each the four-bit signature {@code 0xD} followed by the
     * 260 bits of the frame's parameters from the most significant bit of each byte. The track has no
     * configuration.
     */
    GSM(TrackKind.AUDIO, "gsm"),

    // TODO: MACE decoding: US patent 5,083,310 gives MACE's structure (the 3-2-3 bit packing and the predictor and
    // adaptation constants) but not its 128-entry step tables, adaptation table, quantizer levels or filters, and no
    // other public or permissively licensed description of them exists, so a decoder cannot be written bit-exact
    /**
     * Apple's Macintosh Audio Compression/Expansion at 3:1, the AIFF-C and QuickTime {@code MAC3} compression
     * type: two bytes for every six 8-bit samples of a channel.
     *
     * <p>A packet holds whole frames of two bytes per channel, six samples of every channel. The
     * containers read and write the stream, but no Smalt artifact decodes it.
     */
    MACE3(TrackKind.AUDIO, "mace3"),

    /**
     * Apple's Macintosh Audio Compression/Expansion at 6:1, the AIFF-C and QuickTime {@code MAC6} compression
     * type: one byte for every six 8-bit samples of a channel.
     *
     * <p>A packet holds whole frames of one byte per channel, six samples of every channel. The
     * containers read and write the stream, but no Smalt artifact decodes it.
     */
    MACE6(TrackKind.AUDIO, "mace6"),

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
     * Blu-ray Presentation Graphic Stream bitmap subtitles (HDMV PGS): {@code .sup} files, the Matroska
     * {@code S_HDMV/PGS} codec and {@code stream_type 0x90} of MPEG transport streams.
     *
     * <p>A packet holds one or more segments, each a segment type byte, a 16-bit big-endian length and the segment
     * body: the segments of a {@code .sup} file without their {@code "PG"} marker and timestamps. A display set (a
     * presentation composition segment, then the window, palette and object definition segments it uses, then an
     * end segment) comes in one packet or spread over consecutive packets; the presentation timestamp of the packet
     * holding the presentation composition segment is the time the display set is shown. The track has no
     * configuration. {@link com.github.auties00.smalt.subtitle.BitmapSubtitleDecoder} decodes the pictures.
     */
    PGS(TrackKind.DATA, "hdmv_pgs_subtitle"),

    /**
     * DVB bitmap subtitles (ETSI EN 300 743): {@code stream_type 0x06} with a subtitling descriptor in MPEG transport
     * streams and the Matroska {@code S_DVBSUB} codec.
     *
     * <p>A packet holds the subtitling segments of one PES packet, each starting with the sync byte {@code 0x0F},
     * without the {@code data_identifier}, {@code subtitle_stream_id} and {@code end_of_PES_data_field_marker} of
     * the PES data field. Its presentation timestamp is the time the display set it holds is shown. The
     * configuration, when the track has one, is the body of the subtitling descriptor, eight bytes per subtitle
     * service:
     * {@snippet :
     * ISO_639_language_code (3 bytes), subtitling_type (1), composition_page_id (2), ancillary_page_id (2)
     * }
     * The first service is the one decoded; a track without a configuration decodes the first page it meets.
     */
    DVB_SUBTITLE(TrackKind.DATA, "dvb_subtitle"),

    /**
     * DVD subpicture bitmap subtitles (VobSub): the {@code .idx} and {@code .sub} file pair, the Matroska
     * {@code S_VOBSUB} codec and the MP4 {@code mp4s} sample entry with object type {@code 0xE0}.
     *
     * <p>A packet is one subpicture unit: its 16-bit big-endian size, the run-length coded pixels and the display
     * control sequences, whose delays count from the packet's presentation timestamp. The configuration, when the
     * track has one, is the VobSub index header in UTF-8, as Matroska stores it: the lines of an {@code .idx} file
     * before its first stream, among them {@code size: 720x480} and the 16 colors of
     * {@code palette: 000000, ...}. A track without a configuration shows its pictures in a 720 by 576 frame (480
     * for a 29.97 Hz source) with a gray palette.
     */
    DVD_SUBTITLE(TrackKind.DATA, "dvd_subtitle"),

    /**
     * DVB teletext (ETSI EN 300 706, carried in MPEG transport streams as ETSI EN 300 472 describes):
     * {@code stream_type 0x06} with a teletext descriptor.
     *
     * <p>A packet is the PES data field of one PES packet: the {@code data_identifier} byte, then the data units,
     * each a {@code data_unit_id}, a {@code data_unit_length} and, for teletext data units, the 44 bytes of one
     * teletext packet as transmitted (least significant bit first). The configuration, when the track has one, is
     * the body of the teletext descriptor, five bytes per page:
     * {@snippet :
     * ISO_639_language_code (3 bytes), teletext_type (5 bits) and magazine_number (3 bits), page_number (BCD byte)
     * }
     * {@link com.github.auties00.smalt.subtitle.SubtitleDecoder} decodes the text of one page.
     */
    DVB_TELETEXT(TrackKind.DATA, "dvb_teletext"),

    /**
     * CEA-608 (line 21) closed captions, the {@code c608} closed caption tracks of QuickTime files.
     *
     * <p>A packet holds the caption data of one video frame as ATSC A/53 {@code cc_data} constructs, three bytes
     * each:
     * {@snippet :
     * marker_bits (5 bits, 11111), cc_valid (1 bit), cc_type (2 bits), cc_data_1 (8 bits), cc_data_2 (8 bits)
     * }
     * with {@code cc_type} 0 for the byte pairs of field 1 (channels CC1, CC2 and text channels T1, T2) and 1 for
     * those of field 2 (CC3, CC4, T3, T4), the data bytes with their odd parity bits. The track has no
     * configuration. {@link com.github.auties00.smalt.subtitle.SubtitleDecoder} decodes the text of one channel.
     */
    CEA_608(TrackKind.DATA, "eia_608"),

    /**
     * CEA-708 digital television closed captions, the {@code c708} closed caption tracks of QuickTime files, and
     * the closed captions carried inside H.264, HEVC and MPEG-2 video
     * ({@link com.github.auties00.smalt.subtitle.ClosedCaptions}).
     *
     * <p>A packet holds the caption data of one video frame as the {@code cc_data} constructs of {@link #CEA_608},
     * of every {@code cc_type}: 2 and 3 for the bytes of the DTVCC caption channel packets (3 starting a packet)
     * and 0 and 1 for the CEA-608 compatibility byte pairs. The track has no configuration.
     * {@link com.github.auties00.smalt.subtitle.SubtitleDecoder} decodes the text of one caption service or of one
     * CEA-608 channel.
     */
    CEA_708(TrackKind.DATA, "cea_708"),

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

    /**
     * Returns whether this constant is one of the bitmap subtitle formats, whose cues are pictures.
     *
     * @return {@code true} for {@link #PGS}, {@link #DVB_SUBTITLE} and {@link #DVD_SUBTITLE}
     */
    public boolean isBitmapSubtitle() {
        return this == PGS || this == DVB_SUBTITLE || this == DVD_SUBTITLE;
    }

    /**
     * Returns whether this constant is one of the caption formats: streams of text that a stateful decoder
     * builds into cues, a page or a screen at a time.
     *
     * @return {@code true} for {@link #DVB_TELETEXT}, {@link #CEA_608} and {@link #CEA_708}
     */
    public boolean isCaption() {
        return this == DVB_TELETEXT || this == CEA_608 || this == CEA_708;
    }
}
