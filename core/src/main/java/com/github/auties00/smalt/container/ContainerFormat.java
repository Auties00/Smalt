package com.github.auties00.smalt.container;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Enumerates the container formats Smalt recognizes or writes, in core or through a container provider.
 *
 * <p>Every constant is recognized by {@link Containers#detect(com.github.auties00.smalt.io.MediaSource)} except
 * {@link #PCM}, raw samples that carry no header to recognize them by. {@link #OTHER} stands for every container a
 * {@link com.github.auties00.smalt.spi.ContainerProvider} adds that this enum does not name.
 *
 * <p>Constants may be added in minor releases as new formats are recognized, so a {@code switch}
 * over this type needs a {@code default} branch.
 */
public enum ContainerFormat {
    /**
     * ISO base media file format: MP4, MOV, M4A, M4V, 3GP, 3G2 and Motion JPEG 2000 (MJ2).
     */
    MP4,

    /**
     * Matroska and its WebM profile.
     */
    MATROSKA,

    /**
     * Ogg, carrying Opus, Vorbis, FLAC, Speex or Theora.
     */
    OGG,

    /**
     * An MPEG audio elementary stream (MP3, MP2), optionally with ID3 tags.
     */
    MPEG_AUDIO,

    /**
     * An AAC elementary stream in ADTS framing.
     */
    ADTS,

    /**
     * An AAC elementary stream in LOAS framing ({@code .loas}, {@code .latm}): the {@code AudioSyncStream} of
     * ISO/IEC 14496-3 clause 1.7, LATM {@code AudioMuxElement}s after the 11-bit sync word {@code 0x2B7}.
     */
    LOAS,

    /**
     * An AAC elementary stream in the Audio Data Interchange Format ({@code .adif}, {@code .aac}): raw data blocks
     * after an ADIF header, magic {@code ADIF}.
     */
    ADIF,

    /**
     * RIFF WAVE and its RF64/BW64 extensions.
     */
    WAV,

    /**
     * Sony Wave64 ({@code .w64}): RIFF WAVE with GUID chunk identifiers and 64-bit sizes, magic {@code riff}
     * followed by its GUID.
     */
    W64,

    /**
     * A native FLAC stream.
     */
    FLAC,

    /**
     * An AMR-NB or AMR-WB storage file ({@code #!AMR} magic).
     */
    AMR,

    /**
     * AIFF and AIFF-C.
     */
    AIFF,

    /**
     * Apple Core Audio Format ({@code .caf}), magic {@code caff}.
     */
    CAF,

    /**
     * Sun/NeXT audio ({@code .au}, {@code .snd}), magic {@code .snd}.
     */
    AU,

    /**
     * IVF, the container of the VP8, VP9 and AV1 reference tools.
     */
    IVF,

    /**
     * RIFF AVI.
     */
    AVI,

    /**
     * Flash Video ({@code .flv}), magic {@code FLV}, with the Enhanced RTMP extensions (FourCC codecs, multitrack).
     */
    FLV,

    /**
     * MPEG-2 transport stream (TS, M2TS, MTS).
     */
    MPEG_TS,

    /**
     * MPEG program stream (ISO/IEC 13818-1) and MPEG-1 system stream (ISO/IEC 11172-1): {@code .mpg},
     * {@code .mpeg}, {@code .m2p}, {@code .ps} and the {@code .vob} files of DVD-Video, magic {@code 00 00 01 BA}.
     */
    MPEG_PS,

    /**
     * Material Exchange Format ({@code .mxf}, SMPTE ST 377-1), OP1a, OP-Atom and the other operational patterns of
     * internal essence: a header partition pack key {@code 06 0E 2B 34 02 05 01 01 0D 01 02 01 01 02}, after a
     * run-in of up to 64 KiB.
     */
    MXF,

    /**
     * A raw Dolby Digital (AC-3, {@code .ac3}) or Dolby Digital Plus (E-AC-3, {@code .ec3})
     * elementary stream of sync frames.
     */
    AC3,

    /**
     * A WavPack file ({@code .wv}): a sequence of blocks, magic {@code wvpk}.
     */
    WAVPACK,

    /**
     * A Monkey's Audio file ({@code .ape}): a header and seek table followed by frames, magic {@code "MAC "} (or
     * {@code "MACF"} for floating-point samples).
     */
    APE,

    /**
     * A Musepack file ({@code .mpc}): stream version 7 (magic {@code "MP+"}) or the packets of stream version 8
     * (magic {@code "MPCK"}).
     */
    MUSEPACK,

    /**
     * A Sony DSD Stream File ({@code .dsf}) of uncompressed DSD audio, magic {@code "DSD "}.
     */
    DSF,

    /**
     * A Philips DSD Interchange File Format file ({@code .dff}) of uncompressed or DST-coded DSD audio, magic
     * {@code "FRM8"} with the form type {@code "DSD "}.
     */
    DSDIFF,

    /**
     * A raw DTS elementary stream ({@code .dts}) or a DTS-HD file ({@code .dtshd}, magic {@code DTSHDHDR}) of DTS
     * and DTS-HD frames.
     */
    DTS,

    /**
     * A SubRip ({@code .srt}) text subtitle file.
     */
    SUBRIP,

    /**
     * A WebVTT ({@code .vtt}) text subtitle file.
     */
    WEBVTT,

    /**
     * A SubStation Alpha ({@code .ssa}, v4.00) or Advanced SubStation Alpha ({@code .ass}, v4.00+) script,
     * starting with {@code [Script Info]}.
     */
    ASS,

    /**
     * A TTML document ({@code .ttml}, {@code .dfxp}, {@code .xml}): W3C TTML, DFXP and the IMSC profiles, an
     * XML document whose root element is {@code tt}.
     */
    TTML,

    /**
     * A SAMI ({@code .smi}) subtitle file, starting with a {@code SAMI} tag.
     */
    SAMI,

    /**
     * An LRC ({@code .lrc}) lyrics file of {@code [mm:ss.xx]} time tags.
     */
    LRC,

    /**
     * A MicroDVD ({@code .sub}) subtitle file of {@code {start}{end}} frame-numbered cues.
     */
    MICRODVD,

    /**
     * A Blu-ray presentation graphic stream file ({@code .sup}): {@code "PG"}-headed PGS segments.
     */
    SUP,

    /**
     * VobSub subtitles: the MPEG program stream of DVD subpictures of a {@code .sub} file, with the {@code .idx}
     * index that names its palette and languages. Recognized from either file; a demuxer opened from a source reads
     * the program stream alone, and {@link com.github.auties00.smalt.container.subtitle.VobSubDemuxer} and
     * {@link com.github.auties00.smalt.container.subtitle.VobSubMuxer} read and write the pair.
     */
    VOBSUB,

    /**
     * An H.264 elementary stream in the Annex B byte stream format ({@code .h264}, {@code .264}): start-code
     * prefixed NAL units.
     */
    H264,

    /**
     * An HEVC elementary stream in the Annex B byte stream format ({@code .h265}, {@code .265}): start-code
     * prefixed NAL units.
     */
    HEVC,

    /**
     * A VVC elementary stream in the Annex B byte stream format ({@code .h266}, {@code .266}, {@code .vvc}):
     * start-code prefixed NAL units.
     */
    VVC,

    /**
     * An AV1 elementary stream in the low-overhead bitstream format of the AV1 specification, section 5
     * ({@code .obu}): temporal units of OBUs with size fields, each starting with a temporal delimiter.
     */
    AV1,

    /**
     * An AV1 elementary stream in the length-delimited bitstream format of the AV1 specification, Annex B
     * ({@code .obu}): temporal units and frame units prefixed with their sizes.
     */
    AV1_ANNEX_B,

    /**
     * An MPEG-4 Part 2 video elementary stream ({@code .m4v}, {@code .cmp}): start-code prefixed visual object
     * sequence, video object layer and video object plane headers.
     */
    MPEG4_VIDEO,

    /**
     * An H.263 video elementary stream ({@code .263}): pictures after picture start codes.
     */
    H263,

    /**
     * A YUV4MPEG2 file ({@code .y4m}): uncompressed video frames after a text header, magic
     * {@code YUV4MPEG2}.
     */
    Y4M,

    /**
     * An HTTP Live Streaming playlist ({@code .m3u8}, RFC 8216), master or media, with the segments it names: text
     * starting with {@code #EXTM3U}.
     */
    HLS,

    /**
     * An MPEG-DASH media presentation description ({@code .mpd}, ISO/IEC 23009-1), with the segments it addresses:
     * XML whose root element is {@code MPD}.
     */
    DASH,

    /**
     * Raw PCM samples without a header ({@code .pcm}, {@code .raw}), whose sample format, rate and channel count
     * the reader must know. Never recognized.
     */
    PCM,

    /**
     * A container this enum does not name, read or written by a {@link com.github.auties00.smalt.spi.ContainerProvider}
     * outside Smalt; the provider's name, which {@link Demuxer#formatName()} and
     * {@link com.github.auties00.smalt.probe.MediaInfo#containerName()} report, identifies it.
     */
    OTHER;

    /**
     * Returns the short name of the format: the constant's name in lowercase with hyphens for underscores, for
     * example {@code "mp4"}, {@code "mpeg-ts"} or {@code "av1-annex-b"}.
     *
     * <p>The short names identify the formats in failures and in the name-based lookups of {@link Containers},
     * which also accept the names of the containers that providers add.
     *
     * @return the short name; {@code "other"} for {@link #OTHER}, which names no single container
     */
    public String shortName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Returns the format of a short name.
     *
     * @param shortName the short name, as {@link #shortName()} spells it
     * @return the format, or empty when no constant has that short name (as for the name of a container a
     * {@link com.github.auties00.smalt.spi.ContainerProvider} adds)
     */
    public static Optional<ContainerFormat> ofShortName(String shortName) {
        Objects.requireNonNull(shortName, "shortName");
        for (var format : values()) {
            if (format.shortName().equals(shortName)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
