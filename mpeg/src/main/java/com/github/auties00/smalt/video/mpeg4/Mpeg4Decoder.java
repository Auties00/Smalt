package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.internal.DecoderCore;
import com.github.auties00.smalt.video.mpeg4.internal.ScalableCore;

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
 * <p>The other tools of the Core, Main, N-bit, Scalable, Advanced Real Time Simple, Advanced Coding Efficiency and
 * Advanced Core profiles decode exactly like the reference decoder of ISO/IEC 14496-5, inverse DCT included: binary
 * and grayscale shape (with multiple auxiliary components), shape only layers, the shape-adaptive DCT, static
 * sprites (basic and low latency, 0 to 4 warping points), N-bit samples from 4 to 12 bits, NEWPRED, reduced
 * resolution VOPs, and spatial and temporal scalability (see
 * {@link #Mpeg4Decoder(VideoTrack, VideoTrack, VideoDecoderOptions)}); the sprite brightness change, which that
 * decoder lacks, scales the luminance of the warped sprite as 7.8.7 specifies. Pictures of arbitrarily shaped layers
 * carry the shape as their alpha plane, the binary shape scaled by the VOP's constant alpha or the first grayscale
 * alpha plane; outside the shape their luminance is 0 and their chrominance mid-gray (everywhere for a shape only
 * layer). Their display starts at the origin of the VOP coordinates and takes the size of the track, or, when the
 * track has none, the extent of the first VOP with a bounding rectangle (its right and bottom edges, rounded up to
 * even).
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
 * in 4:2:0; with {@link VideoDecoderOptions#highBitDepth()}, N-bit layers of more than 8 bits give
 * {@link com.github.auties00.smalt.image.Yuv16Picture}s of their depth, which are otherwise reduced to 8 bits as
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(com.github.auties00.smalt.image.Picture)} reduces
 * them, and layers of fewer than 8 bits have their samples shifted up to 8 bits. Each frame takes the smallest
 * timestamp among the packets fed and not yet assigned,
 * which gives frames their presentation times whether a container stores presentation times
 * (MP4, Matroska) or one timestamp per stored frame in decoding order (AVI); a timestamp that does
 * not follow the previous frame's (two VOPs timed alike) is replaced by the previous one plus the
 * frame duration, at least one tick, so presentation times increase. The color
 * description comes from the visual object's {@code video_signal_type()}, with BT.601 or BT.709
 * by picture size for components left unspecified. A frame is a keyframe when it is an I-VOP.
 *
 * <p>Unsupported but valid streams raise {@link MediaException.Unsupported} naming the feature:
 * {@code "mpeg-4 chroma format"}, {@code "mpeg-4 studio profile"}, {@code "mpeg-4 fine granularity scalability"},
 * {@code "mpeg-4 temporal scalability with background composition over an arbitrarily shaped base layer"}, {@code
 * "mpeg-4 scalability of sprite layers"} and {@code "mpeg-4 scalability"} for a single track that carries an
 * enhancement layer.
 * Corrupt data inside a video packet of a rectangular layer conceals the rest of the packet from the previous
 * picture and raises {@link MediaException.Malformed} after the damaged frame was handed out; decoding continues
 * with the next packet. Corrupt data in the other layers raises {@link MediaException.Malformed} for the VOP. P- and
 * B-VOPs whose reference pictures were not decoded, as after a seek to a non-intra VOP, produce no frame.
 *
 * <p>With {@link VideoDecoderOptions#threads()} {@code 0} the macroblock rows of each picture are
 * reconstructed concurrently on the module's shared pool once it is parsed. With more than one
 * thread, up to that many pictures are decoded at once on the shared pool, each parsing while the
 * pictures it predicts from finish and reconstructing in parallel rows, so a picture reaches the
 * consumer during the {@link #decode(Packet, Consumer)} or {@link #flush(Consumer)} call after it
 * finished, which may follow the call that received its packet, and a corrupt picture is reported
 * by that call; {@link #close()} waits for the pictures still being decoded. Streams with the short
 * video header, H.263, and the layers that need the tools of the second paragraph decode one picture at a time on
 * the calling thread. The output never depends on the thread count.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation's inverse DCT is the accurate integer transform of the
 *           Independent JPEG Group's software (ported from libjpeg-turbo's {@code jidctint.c}),
 *           applied rows first; FFmpeg decodes the same streams to the same samples when told to
 *           use that transform ({@code -idct int}). The layers that need the tools of the second paragraph are
 *           decoded by a port of the ISO/IEC 14496-5 reference decoder, with its floating-point inverse DCT.
 */
public final class Mpeg4Decoder implements VideoDecoder {
    /**
     * The shared decoding machinery of a single track, or {@code null} for a scalable object.
     */
    private final DecoderCore core;

    /**
     * The decoding machinery of a scalable object, or {@code null} for a single track.
     */
    private final ScalableCore scalable;

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
     * says, and {@link VideoDecoderOptions#highBitDepth()} selects the output of N-bit layers of more than 8 bits.
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
        this.core = new DecoderCore(track, options.limits(), options.threads(), false, options.highBitDepth());
        this.scalable = null;
    }

    /**
     * Constructs a decoder for the two layers of a scalable video object (ISO/IEC 14496-2 7.9), whose base layer and
     * enhancement layer of spatial or temporal scalability are separate elementary streams, as MPEG-4 Systems
     * carries them.
     *
     * <p>Each track holds the headers of its layer in its {@link VideoTrack#codecConfig()} or its first packets,
     * and its VOPs in its packets; {@link #decode(Packet, Consumer)} takes the packets of both tracks, told apart by
     * {@link Packet#trackId()}, in any interleaving that keeps each layer in order. The kind of scalability comes from
     * the streams: spatial when the first VOPs of both layers have the same time, temporal otherwise. The frames
     * are the enhanced pictures in display order: the enhancement layer's for spatial scalability (at the
     * enhancement layer's size), those of both layers for temporal scalability. An enhancement layer that enhances a
     * part of its reference layer ({@code enhancement_type} 1) is composed over it: for temporal scalability its VOPs
     * come out as opaque pictures over the background of the base layer VOPs around them (ISO/IEC 14496-2 7.9.2.2),
     * for spatial scalability with {@code background_composition} over the upsampled reference layer VOP, with the
     * composed shape as alpha. Frames come out once enough VOPs of
     * both layers are fed, and the rest at {@link #flush(Consumer)}; they take the time of their VOP in the base
     * track's time base. The size of the base track, when it has one, is the display of an arbitrarily shaped base
     * layer, and the size of the enhancement track that of an arbitrarily shaped enhancement layer of spatial
     * scalability (the base display scaled by the layer's sampling factors when it has none). The decoding is
     * exactly that of the reference decoder of ISO/IEC 14496-5, which looks ahead in both layers; like it, the
     * layers are decoded from their first VOPs, so they should start at I-VOPs. When no VOP of the enhancement layer
     * was fed by the first {@link #flush(Consumer)}, that flush hands out the pictures of the base layer alone, and
     * enhancement packets fed later are ignored.
     *
     * @param base the base layer track
     * @param enhancement the enhancement layer track
     * @param options the decoder settings; {@link VideoDecoderOptions#threads()} is ignored
     * @throws IllegalArgumentException if either track is not an MPEG-4 Part 2 track, or both have the same id
     * @throws MediaException.Malformed if a track's configuration is corrupt
     * @throws MediaException.Unsupported if a configuration uses a coding tool the decoder lacks
     */
    public Mpeg4Decoder(VideoTrack base, VideoTrack enhancement, VideoDecoderOptions options) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(enhancement, "enhancement");
        this.options = Objects.requireNonNull(options, "options");
        for (var track : new VideoTrack[]{base, enhancement}) {
            if (track.codec() != Codec.MPEG4_PART2) {
                throw new IllegalArgumentException(track.codec() + " is not an MPEG-4 Part 2 track");
            }
        }
        if (base.id() == enhancement.id()) {
            throw new IllegalArgumentException("the layers have the same track id " + base.id());
        }
        this.core = null;
        this.scalable = new ScalableCore(base, enhancement, options.limits(), options.highBitDepth());
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
        if (core != null) {
            core.decode(packet, output);
        } else {
            scalable.decode(packet, output);
        }
    }

    @Override
    public void flush(Consumer<VideoFrame> output) {
        if (core != null) {
            core.flush(output);
        } else {
            scalable.flush(output);
        }
    }

    @Override
    public void close() {
        if (core != null) {
            core.close();
        } else {
            scalable.close();
        }
    }
}
