package com.github.auties00.smalt.aot;

/**
 * A group of Smalt's own training workloads: the codecs and kernels of one Smalt artifact.
 *
 * <p>Every family trains every encoder and decoder of its artifact whose input Smalt can produce,
 * in the coding modes ({@link TrainingMode}), thread counts and options that select different
 * kernels (bitrates, speeds, profiles, bit depths), together with the format's probe and metadata
 * paths. Every family synthesizes its inputs (video, speech, music, pictures) or produces them with
 * Smalt's own encoders, so training needs no file. The content covers the properties that steer
 * the codecs' decisions: smooth natural content, high-frequency texture, motion large enough to
 * reach the frame borders and the motion search range, scene cuts and fades, flat areas and black
 * frames whose blocks quantize to zero, transparency, and silence between phrases of speech.
 */
public enum TrainingFamily {
    /**
     * The codecs of {@code smalt-image}: JPEG (full and reduced-size decoding, encoding at several
     * qualities and chroma formats, lossless transforms, Motion-JPEG), PNG and APNG (every row
     * filter, 8 and 16-bit), GIF (every quantizer and dithering, still and animated), WebP (lossy,
     * lossless, alpha, animated, on one and on several threads), TIFF (LZW, Deflate with the
     * predictor, PackBits, strips and tiles, 8 and 16-bit), JPEG 2000 (lossless and lossy, reduced
     * resolution), ICO and ANI, BMP (every pixel format, run-length coding, 64-bit, wide-mask and CMYK
     * pixels), image metadata reading and editing, and VP8: the real-time encoder at one and two
     * threads, its decoder, and a real-time session that creates an encoder and a decoder every round
     * next to one that runs across every round.
     */
    IMAGE,

    /**
     * The codecs of {@code smalt-audio}: Opus (speech and music files, and real-time coding at
     * 10 and 20 ms frames, wideband and fullband, with forward error correction, discontinuous
     * transmission and loss concealment), Vorbis at several qualities, FLAC at 16 and 24 bits, ALAC
     * stereo (searched and fast) and 24-bit 5.1, MP3 at constant and variable bitrate and in real time,
     * the decoders of all five, IMA ADPCM (WAVE and {@code ima4} layouts) and Microsoft ADPCM files, G.722, G.726
     * and GSM 06.10 in real time (encoders and decoders), the Speex decoder with loss concealment, the WavPack
     * decoder (lossless, floating-point, DSD and hybrid with correction data), the ATRAC9 decoder (stereo,
     * 5.1 and high-rate files), and the voice
     * processor (echo cancellation, noise suppression) in 10 ms frames over several echo paths and
     * talk patterns.
     */
    AUDIO,

    /**
     * The codecs of {@code smalt-video}: the real-time VP9 and AV1 encoders on one and two threads
     * and at two bitrates, their decoders, a real-time AV1 session, the good-quality and intra-only
     * AV1 encoders (8 and 10-bit), multi-threaded and high-bit-depth decoding, AVIF (lossy, lossless,
     * 10-bit 4:4:4, alpha), the ProRes and DNxHR intra codecs, the Theora encoder and decoder (inter frames
     * included), and the FFV1 encoder and decoder (versions 1 and 3, 8 and 10 bits, one and two threads).
     */
    VIDEO,

    /**
     * The codecs of {@code smalt-mpeg}: the H.264 encoder in real time and for files at two speeds
     * and profiles, its decoder on one and two threads and in real time, a real-time H.264 session,
     * AAC encoding and LC, HE-AAC and HE-AAC v2 decoding, AC-3 and E-AC-3 encoding (file and
     * real-time frames) and decoding, and AMR-NB and AMR-WB in every mode.
     */
    MPEG,

    /**
     * The conversion kernels of {@code smalt-capture}, which turn the pixels the operating systems'
     * cameras and screens deliver into 4:2:0 pictures, on synthetic frames of every platform's
     * layouts; no camera or screen is needed.
     */
    CAPTURE,

    /**
     * The camera raw decoder and the DNG encoder of {@code smalt-raw}, on synthetic DNG, Minolta MRW, Canon CRW,
     * Canon CR3, Sigma X3F and Fujifilm RAF files: Menon and bilinear demosaicing, the color rendering to 8-bit sRGB
     * and to 16-bit linear Rec. 2020, linear raw data, the vendor codings, the extraction of the sensor
     * values, the file description, and the writing and conversion of DNG files, uncompressed and lossless JPEG.
     */
    RAW,

    /**
     * The transcoder, containers and processing of {@code smalt-core}: file transcoding through
     * {@link com.github.auties00.smalt.transcode.Transcoder} (an MP4 with H.264 video and AAC audio
     * to WebM with VP9 and Opus, and to a smaller MP4), remuxing to fragmented MP4, Matroska,
     * MPEG-TS and WAV, probing, the picture operations (conversion, resizing with every filter,
     * orientation, blurs, sharpening, compositing, rotation, quantization), color management,
     * resampling and loudness measurement.
     */
    TRANSCODE
}
