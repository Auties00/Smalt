package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.AmbientViewingEnvironment;
import com.github.auties00.smalt.image.ContentColorVolume;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the display information a decoder attaches to one {@link VideoFrame}: how the frame's
 * fields are scanned and repeated, whether it packs the two views of a stereoscopic pair, how
 * to orient it for display, which layer of a multi-layer stream it shows, the closed captions it carries, the
 * viewing environment and color volume its content was made for, the shape of its samples, and how an
 * omnidirectional frame maps onto a sphere.
 *
 * <p>Decoders fill the entries from the messages the stream carries (for H.264 and HEVC, the
 * picture timing, frame packing arrangement, display orientation and caption SEI messages, the layers an
 * HEVC video parameter set describes and the views of an H.264 multiview stream; for VVC, the Rec. ITU-T H.274
 * messages too) and leave the
 * others absent; a frame the stream says nothing about carries {@link #NONE}. An encoder that can carry an entry
 * in its bitstream writes it.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FrameSideData {
    /**
     * The side data of a frame that carries none.
     */
    public static final FrameSideData NONE = new FrameSideData(null, 0, null, null, null, null, null, null, null,
            null);

    /**
     * The scan of the frame, or {@code null}.
     */
    private final FieldOrder fieldOrder;

    /**
     * The extra field periods the frame is displayed for.
     */
    private final int repeatedFields;

    /**
     * The stereoscopic packing, or {@code null}.
     */
    private final FramePacking framePacking;

    /**
     * The display orientation, or {@code null}.
     */
    private final Orientation orientation;

    /**
     * The layer of the frame, or {@code null}.
     */
    private final VideoLayer layer;

    /**
     * The caption data of the frame, or {@code null}.
     */
    private final byte[] closedCaptions;

    /**
     * The ambient viewing environment, or {@code null}.
     */
    private final AmbientViewingEnvironment ambientViewing;

    /**
     * The content color volume, or {@code null}.
     */
    private final ContentColorVolume contentColorVolume;

    /**
     * The sample aspect ratio, or {@code null}.
     */
    private final Rational sampleAspectRatio;

    /**
     * The omnidirectional video description, or {@code null}.
     */
    private final OmnidirectionalVideo omnidirectional;

    /**
     * Constructs side data from validated components.
     *
     * @param fieldOrder the scan, or {@code null}
     * @param repeatedFields the extra field periods, from 0 to 4
     * @param framePacking the packing, or {@code null}
     * @param orientation the orientation, or {@code null}
     * @param layer the layer, or {@code null}
     * @param closedCaptions the caption data, owned by the instance, or {@code null}
     * @param ambientViewing the ambient viewing environment, or {@code null}
     * @param contentColorVolume the content color volume, or {@code null}
     * @param sampleAspectRatio the sample aspect ratio, or {@code null}
     * @param omnidirectional the omnidirectional video description, or {@code null}
     */
    private FrameSideData(FieldOrder fieldOrder, int repeatedFields, FramePacking framePacking,
                          Orientation orientation, VideoLayer layer, byte[] closedCaptions,
                          AmbientViewingEnvironment ambientViewing, ContentColorVolume contentColorVolume,
                          Rational sampleAspectRatio, OmnidirectionalVideo omnidirectional) {
        this.fieldOrder = fieldOrder;
        this.repeatedFields = repeatedFields;
        this.framePacking = framePacking;
        this.orientation = orientation;
        this.layer = layer;
        this.closedCaptions = closedCaptions;
        this.ambientViewing = ambientViewing;
        this.contentColorVolume = contentColorVolume;
        this.sampleAspectRatio = sampleAspectRatio;
        this.omnidirectional = omnidirectional;
    }

    /**
     * Returns a builder of side data, initially empty.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the entries of this side data.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.fieldOrder = fieldOrder;
        builder.repeatedFields = repeatedFields;
        builder.framePacking = framePacking;
        builder.orientation = orientation;
        builder.layer = layer;
        builder.closedCaptions = closedCaptions;
        builder.ambientViewing = ambientViewing;
        builder.contentColorVolume = contentColorVolume;
        builder.sampleAspectRatio = sampleAspectRatio;
        builder.omnidirectional = omnidirectional;
        return builder;
    }

    /**
     * Returns how the rows of the frame are scanned in time, as the stream signals it.
     *
     * <p>For H.264 and HEVC this comes from the {@code pic_struct} of the picture timing SEI
     * message: {@link FieldOrder#TOP_FIRST} for a frame shown top field first,
     * {@link FieldOrder#BOTTOM_FIRST} for one shown bottom field first, and
     * {@link FieldOrder#PROGRESSIVE} for a frame shown as a whole, doubled or tripled included. A
     * frame the decoder wove from two field pictures reports the parity of the field that comes
     * first.
     *
     * @return the scan, or empty when the stream does not signal it
     */
    public Optional<FieldOrder> fieldOrder() {
        return Optional.ofNullable(fieldOrder);
    }

    /**
     * Returns the number of field periods the frame is displayed for beyond its own two: 1 when
     * its first field is shown again after the second, 2 when the frame is shown twice and 4 when
     * it is shown three times.
     *
     * <p>A decoder that derives a frame's duration from the nominal frame rate already includes
     * these periods in {@link VideoFrame#duration()}.
     *
     * @return the extra field periods, from 0 to 4
     */
    public int repeatedFields() {
        return repeatedFields;
    }

    /**
     * Returns how the frame packs the two views of a stereoscopic pair.
     *
     * @return the packing, or empty when the stream does not signal one
     */
    public Optional<FramePacking> framePacking() {
        return Optional.ofNullable(framePacking);
    }

    /**
     * Returns the transform a viewer applies to the frame to display it upright.
     *
     * <p>For H.264 and HEVC this comes from the display orientation SEI message, whose flips
     * apply before its counterclockwise rotation; a rotation that is not a multiple of 90 degrees
     * is rounded to the nearest one.
     *
     * @return the orientation, or empty when the stream does not signal one
     */
    public Optional<Orientation> orientation() {
        return Optional.ofNullable(orientation);
    }

    /**
     * Returns the layer of a multi-layer stream that the frame shows.
     *
     * <p>The HEVC and H.264 decoders set it on every frame when they output more than the base
     * layer or base view of a stream with several layers or views, and leave it absent otherwise.
     *
     * @return the layer, or empty when the frame comes from a single layer stream or only its base
     * layer is output
     */
    public Optional<VideoLayer> layer() {
        return Optional.ofNullable(layer);
    }

    /**
     * Returns the closed captions the frame carries, as ATSC A/53 {@code cc_data} constructs.
     *
     * <p>The bytes are the constructs of the {@link com.github.auties00.smalt.track.Codec#CEA_708} packet form,
     * three per construct, in the order the stream carries them: for H.264 and HEVC, those of the ATSC caption SEI
     * messages of the picture's access unit, attached to the frame in output order. The H.264 encoder writes them
     * back as such a message.
     *
     * @return a copy of the caption data, or empty when the frame carries none
     */
    public Optional<byte[]> closedCaptions() {
        return closedCaptions == null ? Optional.empty() : Optional.of(closedCaptions.clone());
    }

    /**
     * Returns the nominal ambient viewing environment the content was made for.
     *
     * <p>For VVC this comes from the ambient viewing environment SEI message, which applies to every picture of its
     * coded layer video sequence.
     *
     * @return the environment, or empty when the stream does not signal one
     */
    public Optional<AmbientViewingEnvironment> ambientViewing() {
        return Optional.ofNullable(ambientViewing);
    }

    /**
     * Returns the color volume the content of the frame is expected to occupy.
     *
     * <p>For VVC this comes from the content colour volume SEI message, with the persistence its flags give.
     *
     * @return the color volume, or empty when the stream does not signal one
     */
    public Optional<ContentColorVolume> contentColorVolume() {
        return Optional.ofNullable(contentColorVolume);
    }

    /**
     * Returns the sample aspect ratio of the frame when the stream signals it for the frame, overriding the one of
     * the track.
     *
     * <p>For VVC this comes from the sample aspect ratio information SEI message, with the persistence its flags
     * give; the ratio of a message whose {@code sari_aspect_ratio_idc} is reserved, or whose width or height is 0,
     * is left out.
     *
     * @return the width of a sample divided by its height, or empty when the stream does not signal it for the frame
     */
    public Optional<Rational> sampleAspectRatio() {
        return Optional.ofNullable(sampleAspectRatio);
    }

    /**
     * Returns how the frame maps onto a sphere when it is a picture of omnidirectional (360-degree) video.
     *
     * <p>For VVC this comes from the equirectangular projection, generalized cubemap projection, sphere rotation,
     * region-wise packing and omnidirectional viewport SEI messages, each with the persistence its flags give.
     *
     * @return the description, or empty when the stream signals none of those messages for the frame
     */
    public Optional<OmnidirectionalVideo> omnidirectional() {
        return Optional.ofNullable(omnidirectional);
    }

    /**
     * Returns whether this side data carries nothing.
     *
     * @return whether every entry is absent or zero
     */
    public boolean isEmpty() {
        return fieldOrder == null && repeatedFields == 0 && framePacking == null && orientation == null
                && layer == null && closedCaptions == null && ambientViewing == null && contentColorVolume == null
                && sampleAspectRatio == null && omnidirectional == null;
    }

    /**
     * Compares side data by every entry, the caption data by content.
     *
     * @param other the object to compare
     * @return {@code true} when every entry is equal
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof FrameSideData that && fieldOrder == that.fieldOrder
                && repeatedFields == that.repeatedFields && Objects.equals(framePacking, that.framePacking)
                && orientation == that.orientation && Objects.equals(layer, that.layer)
                && Arrays.equals(closedCaptions, that.closedCaptions)
                && Objects.equals(ambientViewing, that.ambientViewing)
                && Objects.equals(contentColorVolume, that.contentColorVolume)
                && Objects.equals(sampleAspectRatio, that.sampleAspectRatio)
                && Objects.equals(omnidirectional, that.omnidirectional);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(fieldOrder, repeatedFields, framePacking, orientation, layer, ambientViewing,
                contentColorVolume, sampleAspectRatio, omnidirectional) * 31 + Arrays.hashCode(closedCaptions);
    }

    @Override
    public String toString() {
        return "FrameSideData[fieldOrder=" + fieldOrder + ", repeatedFields=" + repeatedFields
                + ", framePacking=" + framePacking + ", orientation=" + orientation + ", layer=" + layer
                + ", closedCaptions=" + (closedCaptions == null ? "none" : closedCaptions.length / 3 + " constructs")
                + ", ambientViewing=" + ambientViewing + ", contentColorVolume=" + contentColorVolume
                + ", sampleAspectRatio=" + sampleAspectRatio + ", omnidirectional=" + omnidirectional + ']';
    }

    /**
     * Builds {@link FrameSideData} instances.
     *
     * <p>Values are validated by the setters. Instances are not thread-safe; confine a builder to
     * one thread.
     */
    public static final class Builder {
        private FieldOrder fieldOrder;

        private int repeatedFields;

        private FramePacking framePacking;

        private Orientation orientation;

        /**
         * The layer the frame was decoded from, or {@code null}.
         */
        private VideoLayer layer;

        /**
         * The caption data, or {@code null}.
         */
        private byte[] closedCaptions;

        private AmbientViewingEnvironment ambientViewing;

        private ContentColorVolume contentColorVolume;

        private Rational sampleAspectRatio;

        private OmnidirectionalVideo omnidirectional;

        /**
         * Constructs an empty builder.
         */
        private Builder() {
        }

        /**
         * Sets the scan of the frame; see {@link FrameSideData#fieldOrder()}.
         *
         * @param fieldOrder the value
         * @return this builder
         */
        public Builder fieldOrder(FieldOrder fieldOrder) {
            this.fieldOrder = Objects.requireNonNull(fieldOrder, "fieldOrder");
            return this;
        }

        /**
         * Removes the scan of the frame; see {@link FrameSideData#fieldOrder()}.
         *
         * @return this builder
         */
        public Builder noFieldOrder() {
            this.fieldOrder = null;
            return this;
        }

        /**
         * Sets the extra field periods; see {@link FrameSideData#repeatedFields()}.
         *
         * @param repeatedFields the value
         * @return this builder
         * @throws IllegalArgumentException if {@code repeatedFields} is not from 0 to 4
         */
        public Builder repeatedFields(int repeatedFields) {
            if (repeatedFields < 0 || repeatedFields > 4) {
                throw new IllegalArgumentException("repeated fields outside 0 to 4: " + repeatedFields);
            }
            this.repeatedFields = repeatedFields;
            return this;
        }

        /**
         * Sets the stereoscopic packing; see {@link FrameSideData#framePacking()}.
         *
         * @param framePacking the value
         * @return this builder
         */
        public Builder framePacking(FramePacking framePacking) {
            this.framePacking = Objects.requireNonNull(framePacking, "framePacking");
            return this;
        }

        /**
         * Removes the stereoscopic packing; see {@link FrameSideData#framePacking()}.
         *
         * @return this builder
         */
        public Builder noFramePacking() {
            this.framePacking = null;
            return this;
        }

        /**
         * Sets the display orientation; see {@link FrameSideData#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Removes the display orientation; see {@link FrameSideData#orientation()}.
         *
         * @return this builder
         */
        public Builder noOrientation() {
            this.orientation = null;
            return this;
        }

        /**
         * Sets the layer of the frame; see {@link FrameSideData#layer()}.
         *
         * @param layer the value
         * @return this builder
         */
        public Builder layer(VideoLayer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            return this;
        }

        /**
         * Removes the layer of the frame; see {@link FrameSideData#layer()}.
         *
         * @return this builder
         */
        public Builder noLayer() {
            this.layer = null;
            return this;
        }

        /**
         * Sets the closed captions of the frame; see {@link FrameSideData#closedCaptions()}.
         *
         * @param closedCaptions the value, copied
         * @return this builder
         * @throws IllegalArgumentException if the length of {@code closedCaptions} is not a positive multiple of 3
         */
        public Builder closedCaptions(byte[] closedCaptions) {
            if (closedCaptions.length == 0 || closedCaptions.length % 3 != 0) {
                throw new IllegalArgumentException("caption data of " + closedCaptions.length
                        + " bytes is not a whole number of cc_data constructs");
            }
            this.closedCaptions = closedCaptions.clone();
            return this;
        }

        /**
         * Removes the closed captions of the frame; see {@link FrameSideData#closedCaptions()}.
         *
         * @return this builder
         */
        public Builder noClosedCaptions() {
            this.closedCaptions = null;
            return this;
        }

        /**
         * Sets the ambient viewing environment; see {@link FrameSideData#ambientViewing()}.
         *
         * @param ambientViewing the value
         * @return this builder
         */
        public Builder ambientViewing(AmbientViewingEnvironment ambientViewing) {
            this.ambientViewing = Objects.requireNonNull(ambientViewing, "ambientViewing");
            return this;
        }

        /**
         * Removes the ambient viewing environment; see {@link FrameSideData#ambientViewing()}.
         *
         * @return this builder
         */
        public Builder noAmbientViewing() {
            this.ambientViewing = null;
            return this;
        }

        /**
         * Sets the content color volume; see {@link FrameSideData#contentColorVolume()}.
         *
         * @param contentColorVolume the value
         * @return this builder
         */
        public Builder contentColorVolume(ContentColorVolume contentColorVolume) {
            this.contentColorVolume = Objects.requireNonNull(contentColorVolume, "contentColorVolume");
            return this;
        }

        /**
         * Removes the content color volume; see {@link FrameSideData#contentColorVolume()}.
         *
         * @return this builder
         */
        public Builder noContentColorVolume() {
            this.contentColorVolume = null;
            return this;
        }

        /**
         * Sets the sample aspect ratio of the frame; see {@link FrameSideData#sampleAspectRatio()}.
         *
         * @param sampleAspectRatio the value
         * @return this builder
         */
        public Builder sampleAspectRatio(Rational sampleAspectRatio) {
            this.sampleAspectRatio = Objects.requireNonNull(sampleAspectRatio, "sampleAspectRatio");
            return this;
        }

        /**
         * Removes the sample aspect ratio of the frame; see {@link FrameSideData#sampleAspectRatio()}.
         *
         * @return this builder
         */
        public Builder noSampleAspectRatio() {
            this.sampleAspectRatio = null;
            return this;
        }

        /**
         * Sets the omnidirectional video description; see {@link FrameSideData#omnidirectional()}.
         *
         * @param omnidirectional the value
         * @return this builder
         */
        public Builder omnidirectional(OmnidirectionalVideo omnidirectional) {
            this.omnidirectional = Objects.requireNonNull(omnidirectional, "omnidirectional");
            return this;
        }

        /**
         * Removes the omnidirectional video description; see {@link FrameSideData#omnidirectional()}.
         *
         * @return this builder
         */
        public Builder noOmnidirectional() {
            this.omnidirectional = null;
            return this;
        }

        /**
         * Builds the side data, {@link FrameSideData#NONE} when every entry is absent.
         *
         * @return the side data
         */
        public FrameSideData build() {
            if (fieldOrder == null && repeatedFields == 0 && framePacking == null && orientation == null
                    && layer == null && closedCaptions == null && ambientViewing == null
                    && contentColorVolume == null && sampleAspectRatio == null && omnidirectional == null) {
                return NONE;
            }
            return new FrameSideData(fieldOrder, repeatedFields, framePacking, orientation, layer, closedCaptions,
                    ambientViewing, contentColorVolume, sampleAspectRatio, omnidirectional);
        }
    }
}
