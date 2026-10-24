package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link H264Decoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class H264DecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final H264DecoderOptions DEFAULTS = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final H264DecoderOptions REALTIME = new Builder().threads(1).realtime(true).build();

    private final MediaLimits limits;

    private final int threads;

    private final boolean realtime;

    private final boolean highBitDepth;

    private final boolean filmGrain;

    /**
     * Whether alpha auxiliary pictures are attached to the frames; see {@link #alpha()}.
     */
    private final boolean alpha;

    /**
     * Whether every view of a multiview stream is output; see {@link #allViews()}.
     */
    private final boolean allViews;

    /**
     * The {@code view_id}s of the views output; see {@link #outputViews()}.
     */
    private final List<Integer> outputViews;

    /**
     * Whether the depth views of a multiview stream with depth are output; see {@link #depth()}.
     */
    private final boolean depth;

    /**
     * Whether a frame-compatible stereo stream is output at enhanced resolution; see
     * {@link #enhancedResolution()}.
     */
    private final boolean enhancedResolution;

    /**
     * The {@code DQId} of the target layer, or -1 for the highest layer; see
     * {@link #targetLayer()}.
     */
    private final int targetLayer;

    /**
     * The highest {@code temporal_id} decoded, or -1 for every temporal layer; see
     * {@link #maxTemporalId()}.
     */
    private final int maxTemporalId;

    private H264DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.realtime = builder.realtime;
        this.highBitDepth = builder.highBitDepth;
        this.filmGrain = builder.filmGrain;
        this.alpha = builder.alpha;
        this.allViews = builder.allViews;
        this.outputViews = builder.allViews ? List.of() : builder.outputViews;
        this.depth = builder.depth;
        this.enhancedResolution = builder.enhancedResolution;
        this.targetLayer = builder.targetLayer;
        this.maxTemporalId = builder.maxTemporalId;
    }

    /**
     * Returns the settings for files: one picture at a time on the calling thread under
     * {@link MediaLimits#defaults()}, with the reordering the stream signals.
     *
     * @return the options
     */
    public static H264DecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings for real-time streams, such as the video of a call: low-delay mode (see
     * {@link #realtime()}) on the calling thread under {@link MediaLimits#defaults()}.
     *
     * @return the options
     */
    public static H264DecoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the most pictures decoded at once: 0 or 1 to decode one picture at a time on the
     * calling thread, or a larger count to decode up to that many pictures concurrently on the shared
     * helper threads; 0 by default}
     *
     * <p>The output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the decoder works in low-delay mode; {@code false} by default}
     *
     * <p>In low-delay mode every packet must hold one whole access unit, whose picture is finished
     * and output before the decode call returns (at most one frame per packet, none when the
     * access unit carried no picture or only part of one that could not start). Baseline profile
     * sequences never reorder pictures, so their pictures are output at once even when the
     * sequence parameter set signals no reordering limit; other profiles keep the reordering the
     * sequence signals (none for a {@code bitstream_restriction} with
     * {@code max_num_reorder_frames} 0 or picture order count type 2). Without a configuration
     * record on the track, packets are Annex B access units carrying their parameter sets in
     * band. A low-delay decoder decodes on the calling thread, so {@link #threads()} is at most 1.
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return whether the pictures of streams deeper than 8 bits are handed out at their own depth
     * as {@link com.github.auties00.smalt.image.Yuv16Picture Yuv16Picture}s rather than rounded to
     * 8-bit {@link com.github.auties00.smalt.image.YuvPicture YuvPicture}s; {@code false} by default}
     *
     * <p>The pictures of 8-bit streams are always {@code YuvPicture}s.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether the decoder adds the film grain that film grain characteristics SEI
     * messages describe to the frames; {@code true} by default}
     *
     * <p>When set, the grain of the frequency filtering model with additive blending is rendered
     * as {@link H264Decoder} describes; when clear, frames hold the decoded samples as they are.
     */
    public boolean filmGrain() {
        return filmGrain;
    }

    /**
     * {@return whether the alpha of auxiliary coded pictures is attached to the frames; {@code true}
     * by default}
     *
     * <p>A stream whose sequence parameter set extension declares alpha auxiliary pictures
     * ({@code aux_format_idc} 1 or 2 with distinct opaque and transparent values, ITU-T H.264
     * 7.4.2.1.2) codes one monochrome auxiliary picture per access unit. When set, those pictures
     * are decoded and each becomes the straight alpha plane of the frame of its access unit, scaled
     * from the range between {@code alpha_transparent_value} and {@code alpha_opaque_value} to the
     * full range of the picture's depth after the {@code alpha_incr_flag} increment; the color of a
     * stream that codes it premultiplied by the alpha ({@code aux_format_idc} 2) is divided back.
     * A frame whose access unit carried no auxiliary picture is opaque, and so is a frame whose
     * auxiliary picture cannot be decoded, which is then reported damaged. When clear, or for
     * auxiliary pictures of other uses, auxiliary pictures are skipped.
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * {@return whether every view of a multiview stream is output; {@code false} by default}
     *
     * <p>A multiview stream (MVC, the Stereo High and Multiview High profiles of ITU-T H.264
     * Annex G) codes several views of a scene, one view component of each per access unit; its
     * base view decodes on its own, the other views predict from the base view and from each
     * other. When set, every view is decoded and output; otherwise the views
     * {@link #outputViews()} names are. The frames of an access unit are output together, in the
     * view order of the stream (base view first), each naming its view in
     * {@link com.github.auties00.smalt.video.FrameSideData#layer()} whenever more than the base
     * view is output.
     */
    public boolean allViews() {
        return allViews;
    }

    /**
     * {@return the {@code view_id}s of the views of a multiview stream that are output, in
     * ascending order, or an empty list for the base view alone; empty by default}
     *
     * <p>The decoder decodes the named views and the views they predict from, and outputs the
     * named views only (the target output views of ITU-T H.264 G.8). Views the stream does not
     * code are ignored, and the base view is output only when named or when the list is empty.
     * Streams with a single view ignore this setting, and the list is empty when
     * {@link #allViews()} is set.
     */
    public List<Integer> outputViews() {
        return outputViews;
    }

    /**
     * {@return whether the depth views of a multiview stream with depth are output; {@code false} by
     * default}
     *
     * <p>A multiview stream with depth (MVCD, the Multiview Depth High and MFC Depth High profiles of
     * ITU-T H.264 Annex H) codes, next to the texture of its views, a depth map of some of them: a
     * monochrome picture per access unit, of the texture's size or smaller, whose depth views predict
     * from each other the way texture views do. When set, the depth views of the views that are output
     * ({@link #allViews()}, {@link #outputViews()}, the base view by default) are decoded with the depth
     * views they predict from and output as monochrome frames that name their view in
     * {@link com.github.auties00.smalt.video.FrameSideData#layer()}, with the layer type
     * {@link com.github.auties00.smalt.video.VideoLayer.Type#DEPTH DEPTH}. A depth frame is output once
     * its own picture buffer releases it, independently of the texture frames of its access unit, and
     * carries the timestamp of the packet its first slice arrived in. When clear, depth views are skipped.
     * Streams without depth views ignore this setting. The texture and depth views of 3D-AVC streams
     * (Annex I), which predict from each other, raise
     * {@link com.github.auties00.smalt.MediaException.Unsupported} once, as {@code "h264 3d-avc"}, and
     * their base view is decoded.
     */
    public boolean depth() {
        return depth;
    }

    /**
     * {@return whether a frame-compatible stereo stream is output at enhanced resolution; {@code false}
     * by default}
     *
     * <p>A frame-compatible stereo stream (the MFC High and MFC Depth High profiles of ITU-T H.264
     * Annexes G and H) packs the left and right views of a stereo pair into each picture of its base
     * view, side by side or top and bottom, at half resolution, and codes in the pictures of its
     * enhancement view, packed the other way, what restores the full resolution. The enhancement view
     * predicts from the base view converted to its packing (ITU-T H.264 G.8.4.1). Without this setting
     * the stream is a multiview stream of two views, whose pictures are output packed as decoded. When
     * set, both views are decoded and each access unit outputs its left view and then its right view,
     * reconstructed at the full size of the coded picture as ITU-T H.264 G.8.6 describes: the frame of
     * the left view names the base view in {@link com.github.auties00.smalt.video.FrameSideData#layer()}
     * and the frame of the right view the enhancement view, and neither carries the frame packing of the
     * stream. An access unit whose enhancement view component is missing outputs its base view picture
     * as decoded. Streams that are not frame-compatible ignore this setting.
     */
    public boolean enhancedResolution() {
        return enhancedResolution;
    }

    /**
     * {@return the layer of a scalable stream that is decoded and output, as its {@code DQId}
     * ({@code dependency_id * 16 + quality_id}), or empty for the highest layer of each access
     * unit; 0, the base layer, by default}
     *
     * <p>A scalable stream (SVC, the Scalable Baseline, Scalable High and Scalable High Intra
     * profiles of ITU-T H.264 Annex F) codes layers of increasing resolution ({@code dependency_id})
     * and quality ({@code quality_id}) over a base layer that decodes on its own; a layer predicts
     * from the layers below it. The decoder decodes the target layer with the layers it predicts
     * from and outputs the target layer alone, at its own resolution. A target above the layers of
     * an access unit selects the highest layer of that unit below the target. Frames decoded at a
     * layer above the base layer name their layer, by its {@code DQId}, in
     * {@link com.github.auties00.smalt.video.FrameSideData#layer()}. Streams without scalable
     * layers ignore this setting.
     *
     * <p>A target above the base layer takes effect at the first IDR picture after a subset
     * sequence parameter set with an SVC extension; the pictures before it are decoded at the
     * base layer. Layers are decoded as frames, as macroblock-adaptive frames and as field
     * pictures, in any combination across the layers. Scalable streams with samples other than
     * 4:2:0 at 8 bits raise {@link com.github.auties00.smalt.MediaException.Unsupported} once, as
     * {@code "h264 svc format"}, and their base layer is decoded.
     */
    public OptionalInt targetLayer() {
        return targetLayer < 0 ? OptionalInt.empty() : OptionalInt.of(targetLayer);
    }

    /**
     * {@return the highest {@code temporal_id} decoded, from 0 to 7, or empty for every temporal
     * layer; empty by default}
     *
     * <p>Scalable and multiview streams mark the pictures of their temporal layers with a
     * {@code temporal_id} in the NAL unit header extension (ITU-T H.264 F.7.4.1.1 and G.7.4.1.1);
     * pictures of a higher temporal layer are skipped, which lowers the frame rate. Pictures of
     * streams without the extension all belong to temporal layer 0.
     */
    public OptionalInt maxTemporalId() {
        return maxTemporalId < 0 ? OptionalInt.empty() : OptionalInt.of(maxTemporalId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof H264DecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && realtime == that.realtime && highBitDepth == that.highBitDepth && filmGrain == that.filmGrain
                && alpha == that.alpha && allViews == that.allViews && outputViews.equals(that.outputViews)
                && depth == that.depth && enhancedResolution == that.enhancedResolution
                && targetLayer == that.targetLayer && maxTemporalId == that.maxTemporalId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, realtime, highBitDepth, filmGrain, alpha, allViews, outputViews,
                depth, enhancedResolution, targetLayer, maxTemporalId);
    }

    @Override
    public String toString() {
        return "H264DecoderOptions[limits=" + limits + ", threads=" + threads + ", realtime=" + realtime
                + ", highBitDepth=" + highBitDepth + ", filmGrain=" + filmGrain + ", alpha=" + alpha
                + ", allViews=" + allViews + ", outputViews=" + outputViews + ", depth=" + depth
                + ", enhancedResolution=" + enhancedResolution + ", targetLayer=" + targetLayer()
                + ", maxTemporalId=" + maxTemporalId() + "]";
    }

    /**
     * A builder of {@link H264DecoderOptions}, starting from {@link H264DecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values and how they combine.
     * A builder may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private int threads;

        private boolean realtime;

        private boolean highBitDepth;

        private boolean filmGrain;

        /**
         * The setting of {@link H264DecoderOptions#alpha()}.
         */
        private boolean alpha;

        /**
         * The setting of {@link H264DecoderOptions#allViews()}.
         */
        private boolean allViews;

        /**
         * The setting of {@link H264DecoderOptions#outputViews()}.
         */
        private List<Integer> outputViews;

        /**
         * The setting of {@link H264DecoderOptions#depth()}.
         */
        private boolean depth;

        /**
         * The setting of {@link H264DecoderOptions#enhancedResolution()}.
         */
        private boolean enhancedResolution;

        /**
         * The setting of {@link H264DecoderOptions#targetLayer()}: a {@code DQId}, -1 for the
         * highest layer, or {@link Integer#MIN_VALUE} after an identifier out of range.
         */
        private int targetLayer;

        /**
         * The setting of {@link H264DecoderOptions#maxTemporalId()}: a {@code temporal_id}, -1 for
         * every temporal layer, or {@link Integer#MIN_VALUE} after an identifier out of range.
         */
        private int maxTemporalId;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.filmGrain = true;
            this.alpha = true;
            this.outputViews = List.of();
            this.maxTemporalId = -1;
        }

        private Builder(H264DecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.realtime = options.realtime;
            this.highBitDepth = options.highBitDepth;
            this.filmGrain = options.filmGrain;
            this.alpha = options.alpha;
            this.allViews = options.allViews;
            this.outputViews = options.outputViews;
            this.depth = options.depth;
            this.enhancedResolution = options.enhancedResolution;
            this.targetLayer = options.targetLayer;
            this.maxTemporalId = options.maxTemporalId;
        }

        /**
         * Sets the resource bounds; see {@link H264DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the most pictures decoded at once; see {@link H264DecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether the decoder works in low-delay mode; see {@link H264DecoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets whether pictures deeper than 8 bits keep their depth; see
         * {@link H264DecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether the decoder adds film grain to the frames; see
         * {@link H264DecoderOptions#filmGrain()}.
         *
         * @param filmGrain the value
         * @return this builder
         */
        public Builder filmGrain(boolean filmGrain) {
            this.filmGrain = filmGrain;
            return this;
        }

        /**
         * Sets whether the alpha of auxiliary coded pictures is attached to the frames; see
         * {@link H264DecoderOptions#alpha()}.
         *
         * @param alpha the value
         * @return this builder
         */
        public Builder alpha(boolean alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Outputs every view of a multiview stream; see {@link H264DecoderOptions#allViews()}.
         *
         * @return this builder
         */
        public Builder allViews() {
            this.allViews = true;
            this.outputViews = List.of();
            return this;
        }

        /**
         * Outputs the base view of a multiview stream alone, the default; see
         * {@link H264DecoderOptions#outputViews()}.
         *
         * @return this builder
         */
        public Builder baseView() {
            this.allViews = false;
            this.outputViews = List.of();
            return this;
        }

        /**
         * Sets the views of a multiview stream that are output; see
         * {@link H264DecoderOptions#outputViews()}.
         *
         * @param viewIds the {@code view_id}s, from 0 to 1023, in any order; none for the base view
         * alone
         * @return this builder
         */
        public Builder outputViews(int... viewIds) {
            var ids = viewIds.clone();
            Arrays.sort(ids);
            this.outputViews = Arrays.stream(ids).distinct().boxed().toList();
            this.allViews = false;
            return this;
        }

        /**
         * Sets whether the depth views of a multiview stream with depth are output; see
         * {@link H264DecoderOptions#depth()}.
         *
         * @param depth the value
         * @return this builder
         */
        public Builder depth(boolean depth) {
            this.depth = depth;
            return this;
        }

        /**
         * Sets whether a frame-compatible stereo stream is output at enhanced resolution; see
         * {@link H264DecoderOptions#enhancedResolution()}.
         *
         * @param enhancedResolution the value
         * @return this builder
         */
        public Builder enhancedResolution(boolean enhancedResolution) {
            this.enhancedResolution = enhancedResolution;
            return this;
        }

        /**
         * Sets the layer of a scalable stream that is decoded and output; see
         * {@link H264DecoderOptions#targetLayer()}.
         *
         * @param dependencyId the {@code dependency_id}, from 0 to 7
         * @param qualityId the {@code quality_id}, from 0 to 15
         * @return this builder
         */
        public Builder targetLayer(int dependencyId, int qualityId) {
            this.targetLayer = dependencyId < 0 || dependencyId > 7 || qualityId < 0 || qualityId > 15
                    ? Integer.MIN_VALUE : dependencyId << 4 | qualityId;
            return this;
        }

        /**
         * Decodes the highest layer of each access unit of a scalable stream; see
         * {@link H264DecoderOptions#targetLayer()}.
         *
         * @return this builder
         */
        public Builder highestLayer() {
            this.targetLayer = -1;
            return this;
        }

        /**
         * Sets the highest temporal layer decoded; see {@link H264DecoderOptions#maxTemporalId()}.
         *
         * @param temporalId the {@code temporal_id}, from 0 to 7
         * @return this builder
         */
        public Builder maxTemporalId(int temporalId) {
            this.maxTemporalId = temporalId < 0 || temporalId > 7 ? Integer.MIN_VALUE : temporalId;
            return this;
        }

        /**
         * Decodes every temporal layer, the default; see {@link H264DecoderOptions#maxTemporalId()}.
         *
         * @return this builder
         */
        public Builder allTemporalLayers() {
            this.maxTemporalId = -1;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative, or above one in low-delay
         * mode, if a view identifier is outside 0 to 1023, if the target layer's dependency or
         * quality identifier is out of range, or if the highest temporal identifier is outside 0
         * to 7
         */
        public H264DecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            if (realtime && threads > 1) {
                throw new IllegalArgumentException("thread count " + threads + " is above 1 in low-delay mode");
            }
            for (var id : outputViews) {
                if (id < 0 || id > 1023) {
                    throw new IllegalArgumentException("view_id " + id + " outside 0 to 1023");
                }
            }
            if (targetLayer == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("target layer outside dependency_id 0 to 7 and quality_id 0 to 15");
            }
            if (maxTemporalId == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("temporal_id outside 0 to 7");
            }
            return new H264DecoderOptions(this);
        }
    }
}
