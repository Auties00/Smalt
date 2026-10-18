package com.github.auties00.smalt.video.mpeg4;

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
 * Decodes an MPEG-4 Part 2 Visual (ISO/IEC 14496-2) video track: DivX, Xvid and the
 * {@code mp4v} streams of MP4, Matroska and AVI files.
 *
 * <p>The decoder implements the rectangular video object layers of the Simple and Advanced
 * Simple profiles and the matching tools of the other profiles: I-, P-, B- and S(GMC)-VOPs, half
 * and quarter sample motion compensation, four motion vectors per macroblock, unrestricted motion
 * vectors, direct mode, global motion compensation with 0 to 3 warping points, interlaced
 * coding (field DCT, field prediction and interlaced direct mode), the H.263 and MPEG
 * quantization methods with downloaded matrices, intra DC and AC prediction, video packets with
 * resynchronization markers, data partitioning and reversible VLCs, overlapped block motion
 * compensation, and the short video header (H.263 baseline). Output samples follow the decoding
 * process of the standard exactly, apart from the inverse DCT, whose implementation the standard
 * leaves free within the IEEE 1180 accuracy (Annex A).
 *
 * <p>Packets are the coded VOPs of the track; {@link VideoTrack#codecConfig()} may hold the
 * visual object sequence, visual object and video object layer headers (the
 * {@code DecoderSpecificInfo} of {@code esds}, Matroska's {@code CodecPrivate}, the extra data
 * of an AVI {@code BITMAPINFOHEADER}), and headers carried in packets are honored. A packet may
 * carry several VOPs, as the packed bitstreams of DivX and Xvid do (a P-VOP and the B-VOP that
 * precedes it in display order, followed by a packet with a not-coded VOP); such placeholders and
 * other not-coded VOPs produce no frame. Raw elementary streams ({@code .m4v}) are split into
 * packets by {@link Mpeg4ElementaryStream}.
 *
 * <p>Frames reach the consumer in display order as {@link com.github.auties00.smalt.image.YuvPicture}s
 * in 4:2:0. Each frame takes the smallest timestamp among the packets fed and not yet assigned,
 * which gives frames their presentation times whether a container stores presentation times
 * (MP4, Matroska) or one timestamp per stored frame in decoding order (AVI). The color
 * description comes from the visual object's {@code video_signal_type()}, with BT.601 or BT.709
 * by picture size for components left unspecified. A frame is a keyframe when it is an I-VOP.
 *
 * <p>Unsupported but valid streams raise {@link MediaException.Unsupported} naming the feature:
 * {@code "mpeg-4 shape coding"}, {@code "mpeg-4 static sprites"}, {@code "mpeg-4 scalability"},
 * {@code "mpeg-4 n-bit video"}, {@code "mpeg-4 newpred"}, {@code "mpeg-4 reduced resolution
 * vop"}, {@code "mpeg-4 chroma format"} and {@code "mpeg-4 sprite brightness change"}. Corrupt
 * data inside a video packet conceals the rest of the packet from the previous picture and raises
 * {@link MediaException.Malformed} after the damaged frame was handed out; decoding continues
 * with the next packet. P- and B-VOPs whose reference pictures were not decoded, as after a seek
 * to a non-intra VOP, produce no frame.
 *
 * <p>With {@link VideoDecoderOptions#threads()} {@code 0} the macroblock rows of each picture are
 * reconstructed concurrently on the module's shared pool once it is parsed. With more than one
 * thread, up to that many pictures are decoded at once on the shared pool, each parsing while the
 * pictures it predicts from finish and reconstructing in parallel rows, so a picture reaches the
 * consumer during the {@link #decode(Packet, Consumer)} or {@link #flush(Consumer)} call after it
 * finished, which may follow the call that received its packet, and a corrupt picture is reported
 * by that call; {@link #close()} waits for the pictures still being decoded. Streams with the short
 * video header, and H.263, decode one picture at a time. The output never depends on the thread
 * count.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation's inverse DCT is the accurate integer transform of the
 *           Independent JPEG Group's software (ported from libjpeg-turbo's {@code jidctint.c}),
 *           applied rows first; FFmpeg decodes the same streams to the same samples when told to
 *           use that transform ({@code -idct int}).
 */
public final class Mpeg4Decoder implements VideoDecoder {
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
     * @throws IllegalArgumentException if the track is not an MPEG-4 Part 2 track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration uses a coding tool the decoder lacks
     */
    public Mpeg4Decoder(VideoTrack track) {
        this(track, VideoDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * <p>The thread count of {@link VideoDecoderOptions#threads()} works as the class description
     * says. {@link VideoDecoderOptions#highBitDepth()} is ignored, because every picture of the
     * codec has 8-bit samples.
     *
     * @param track the track to decode
     * @param options the decoder settings
     * @throws IllegalArgumentException if the track is not an MPEG-4 Part 2 track
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration uses a coding tool the decoder lacks
     */
    public Mpeg4Decoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.MPEG4_PART2) {
            throw new IllegalArgumentException(track.codec() + " is not an MPEG-4 Part 2 track");
        }
        this.core = new DecoderCore(track, options.limits(), options.threads(), false);
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
