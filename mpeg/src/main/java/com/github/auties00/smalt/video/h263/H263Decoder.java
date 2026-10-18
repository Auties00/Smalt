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
 * H.263 streams of Matroska and AVI files.
 *
 * <p>The decoder implements the baseline syntax and the optional modes of the H.263 profiles 0
 * to 3 and the 3GPP profiles: the extended picture type with custom picture formats and clock
 * frequencies, Unrestricted Motion Vectors (Annex D), Advanced Prediction with four vectors per
 * macroblock and overlapped block motion compensation (Annex F), Advanced INTRA Coding (Annex
 * I), the in-loop Deblocking Filter (Annex J), the Slice Structured mode without rectangular
 * slices (Annex K), Alternative INTER VLC (Annex S) and Modified Quantization (Annex T). Output
 * samples follow the decoding process exactly, apart from the inverse DCT, which the
 * Recommendation leaves free within the IEEE 1180 accuracy (Annex A).
 *
 * <p>Packets are coded pictures starting with a picture start code; a packet holding several
 * pictures is split at their start codes, and raw {@code .263} streams are split into packets by
 * {@link com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream}. Frames reach the consumer in
 * decoding order, which is the display order of the supported picture types, as 4:2:0
 * {@link com.github.auties00.smalt.image.YuvPicture}s with the timestamps of their packets, or
 * continuing from the previous frame by the temporal reference when packets carry none. A frame
 * is a keyframe when it is an I-picture.
 *
 * <p>Unsupported but valid streams raise {@link MediaException.Unsupported} naming the mode:
 * syntax-based arithmetic coding, PB-frames, Improved PB-frames, B, EI and EP pictures,
 * Continuous Presence Multipoint, reference picture selection, independent segment decoding,
 * reference picture resampling, reduced-resolution update and rectangular slices. Corrupt data
 * in a group of blocks or slice conceals the rest of it from the previous picture and raises
 * {@link MediaException.Malformed} after the damaged frame was handed out.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation shares the macroblock decoding of {@link
 *           com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder}, of which H.263 baseline is the
 *           short video header; its inverse DCT is the accurate integer transform of the
 *           Independent JPEG Group's software (ported from libjpeg-turbo's {@code jidctint.c}).
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
     * @throws IllegalArgumentException if the track is not an H.263 track
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
     * @throws IllegalArgumentException if the track is not an H.263 track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration uses a coding tool the decoder lacks
     */
    public H263Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.H263) {
            throw new IllegalArgumentException(track.codec() + " is not an H.263 track");
        }
        this.core = new DecoderCore(track, options.limits(), options.threads(), true);
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
