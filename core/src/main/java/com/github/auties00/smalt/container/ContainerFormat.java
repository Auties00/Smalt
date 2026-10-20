package com.github.auties00.smalt.container;

/**
 * Enumerates the container formats this module can recognize or write.
 *
 * <p>Every constant is recognized by {@link Containers#detect(com.github.auties00.smalt.io.MediaSource)} except the
 * elementary video streams {@link #H264}, {@link #HEVC} and {@link #AV1}, which are only written.
 *
 * <p>Constants may be added in minor releases as new formats are recognized, so a {@code switch}
 * over this type needs a {@code default} branch.
 */
public enum ContainerFormat {
    /**
     * ISO base media file format: MP4, MOV, M4A, M4V, 3GP and 3G2.
     */
    MP4,

    /**
     * Matroska and its WebM profile.
     */
    MATROSKA,

    /**
     * Ogg, carrying Opus, Vorbis, FLAC or Speex.
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
     * RIFF WAVE and its RF64/BW64 extensions.
     */
    WAV,

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
     * IVF, the container of the VP8, VP9 and AV1 reference tools.
     */
    IVF,

    /**
     * RIFF AVI.
     */
    AVI,

    /**
     * MPEG-2 transport stream (TS, M2TS, MTS).
     */
    MPEG_TS,

    /**
     * A raw Dolby Digital (AC-3, {@code .ac3}) or Dolby Digital Plus (E-AC-3, {@code .ec3})
     * elementary stream of sync frames.
     */
    AC3,

    /**
     * A SubRip ({@code .srt}) text subtitle file.
     */
    SUBRIP,

    /**
     * A WebVTT ({@code .vtt}) text subtitle file.
     */
    WEBVTT,

    /**
     * An H.264 elementary stream in the Annex B byte stream format ({@code .h264}): start-code prefixed NAL
     * units, with the parameter sets in front of every key frame. Written only.
     */
    H264,

    /**
     * An HEVC elementary stream in the Annex B byte stream format ({@code .h265}): start-code prefixed NAL
     * units, with the parameter sets in front of every key frame. Written only.
     */
    HEVC,

    /**
     * An AV1 elementary stream in the low-overhead bitstream format of the AV1 specification, section 5
     * ({@code .obu}): temporal units of OBUs with size fields, each starting with a temporal delimiter, with
     * the sequence header in front of every key frame. Written only.
     */
    AV1
}
