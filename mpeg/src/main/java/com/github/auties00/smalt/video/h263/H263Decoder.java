package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.internal.DecoderCore;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes an ITU-T H.263 video track: the {@code s263} streams of 3GP and MP4 files and the
 * H.263 streams of Matroska and AVI files; and a Sorenson Spark ({@link Codec#FLV1}) track, the Sorenson H.263 of
 * Flash Video.
 *
 * <p>The decoder implements the baseline syntax and every optional coding mode of ITU-T H.263
 * (2005): the extended picture type with custom picture formats and clock frequencies, Continuous
 * Presence Multipoint (Annex C), Unrestricted Motion Vectors (Annex D), Syntax-based Arithmetic
 * Coding (Annex E), Advanced Prediction with four vectors per macroblock and overlapped block
 * motion compensation (Annex F), PB-frames (Annex G), Advanced INTRA Coding (Annex I), the in-loop
 * Deblocking Filter (Annex J), the Slice Structured mode with arbitrary slice ordering and
 * rectangular slices (Annex K), Improved PB-frames (Annex M), Reference Picture Selection (Annex
 * N), temporal, SNR and spatial scalability with B-, EI- and EP-pictures (Annex O), Reference
 * Picture Resampling with implicit and explicit warping (Annex P), Reduced-Resolution Update
 * (Annex Q), Independent Segment Decoding (Annex R), Alternative INTER VLC (Annex S), Modified
 * Quantization (Annex T), Enhanced Reference Picture Selection with multiple reference
 * pictures, memory management and two-picture backward prediction (Annex U) and the Data
 * Partitioned Slice mode (Annex V). Output samples follow the decoding process exactly, apart from
 * the inverse DCT, which the Recommendation leaves free within the IEEE 1180 accuracy (Annex A).
 *
 * <p>Packets are coded pictures starting with a picture start code; a packet holding several
 * pictures is split at their start codes, and raw {@code .263} streams are split into packets by
 * {@link com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream}. Frames reach the consumer in
 * display order as 4:2:0 {@link com.github.auties00.smalt.image.YuvPicture}s, each once the next
 * I-, P-, EI- or EP-picture of its stream is decoded (B-pictures and the B-part of a PB-frame
 * display before the picture that follows them in the stream) or when the decoder is flushed. They
 * carry the timestamps of their packets (one that does not follow the previous frame's is moved a
 * frame duration, at least one tick, after it), or continue from the previous frame by the temporal
 * reference when packets carry none; from the first picture that needs one of Annexes C, E, G, M,
 * N, O, P, Q, R, U or V, or rectangular slices, the timestamps continue from that picture's packet by
 * the temporal references, since a packet carries both pictures of a PB-frame and the pictures of
 * the layers of an instant share a temporal reference. A frame is a keyframe when it is an I- or
 * EI-picture.
 *
 * <p>A Sorenson Spark packet is one picture: its picture header (version 0 or 1, a standard or custom size,
 * an I-, P- or disposable P-picture type) is followed by baseline macroblocks without groups of blocks, whose
 * motion vectors keep the baseline range and may point outside the picture, with four vectors per macroblock
 * allowed and predicted without overlapped block motion compensation, and, in version 1, coefficient escapes of
 * 7 or 11-bit levels. A disposable P-picture is handed out at once and never predicted from. The deblocking flag of
 * the header only suggests a post filter, which the decoder does not apply, so the output is the reconstruction the
 * encoder predicts from. Corrupt macroblock data conceals the rest of the picture. Frames are keyframes when they
 * are I-pictures and carry the timestamps of their packets.
 *
 * <p>Of the pictures of the layers of a scalable stream that share an instant, the frame is the
 * picture of the highest layer decoded. Once a stream has shown an enhancement layer or a second
 * sub-bitstream of Continuous Presence Multipoint, every frame carries a
 * {@link com.github.auties00.smalt.video.VideoLayer} in its side data, whose identifier is the
 * enhancement layer number minus 1 and whose view identifier is the sub-bitstream number; the
 * sub-bitstreams are independent streams, each with its own frames in display order.
 *
 * <p>Corrupt data in a group of blocks or slice conceals the rest of it from the previous picture
 * and raises {@link MediaException.Malformed} after the damaged frame was handed out; in a data
 * partitioned slice, corrupt coefficient data keeps the macroblock types and motion vectors of its
 * partitions, so the INTER macroblocks from there on are predicted without their residual and
 * only the INTRA ones are concealed.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation shares the macroblock decoding of {@link
 *           com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder}, of which H.263 baseline is the
 *           short video header; its inverse DCT is the accurate integer transform of the
 *           Independent JPEG Group's software (ported from libjpeg-turbo's {@code jidctint.c}).
 *           At the first picture that needs a mode the shared engine lacks, a scalar decoder
 *           written for the optional modes takes over the stream with its reference pictures and
 *           decodes it from there on.
 */
public final class H263Decoder implements VideoDecoder {
    /**
     * The shared decoding machinery.
     */
    private final DecoderCore core;

    /**
     * The decoder options.
     */
    private final VideoDecoderOptions options;

    /**
     * Constructs a decoder for a track under {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track to decode
     * @throws IllegalArgumentException if the track is not an H.263 or Sorenson Spark track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration uses a coding tool the decoder lacks
     */
    public H263Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * <p>With {@link VideoDecoderOptions#threads()} {@code 0} the macroblock rows of each picture are
     * reconstructed on the shared pool of helper threads, with {@code 1} on the calling thread; the
     * output never depends on the thread count. {@link VideoDecoderOptions#highBitDepth()} is
     * ignored, because every picture of the codec has 8-bit samples.
     *
     * @param track the track to decode
     * @param options the decoder settings
     * @throws IllegalArgumentException if the track is not an H.263 or Sorenson Spark track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration uses a coding tool the decoder lacks
     */
    public H263Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.H263 && track.codec() != Codec.FLV1) {
            throw new IllegalArgumentException(track.codec() + " is not an H.263 or Sorenson Spark track");
        }
        this.core = new DecoderCore(track, options.limits(), options.threads(), true, false);
    }

    /**
     * Returns the options of this decoder.
     *
     * @return the options
     */
    public VideoDecoderOptions options() {
        return options;
    }

    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        core.decode(packet, output);
    }

    @Override
    public void flush(Consumer<VideoFrame> output) {
        core.flush(output);
    }

    @Override
    public void close() {
        core.close();
    }
}
