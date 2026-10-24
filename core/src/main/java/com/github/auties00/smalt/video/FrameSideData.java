package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.Orientation;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the display information a decoder attaches to one {@link VideoFrame}: how the frame's
 * fields are scanned and repeated, whether it packs the two views of a stereoscopic pair, how
 * to orient it for display, which layer of a multi-layer stream it shows, and the closed captions it carries.
 *
 * <p>Decoders fill the entries from the messages the stream carries (for H.264 and HEVC, the
 * picture timing, frame packing arrangement, display orientation and caption SEI messages, the layers an
 * HEVC video parameter set describes and the views of an H.264 multiview stream) and leave the
 * others absent; a frame the stream says nothing about carries {@link #NONE}. An encoder that can carry an entry
 * in its bitstream writes it.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FrameSideData {
    /**
     * The side data of a frame that carries none.
     */
    public static final FrameSideData NONE = new FrameSideData(null, 0, null, null, null, null);

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
     * Constructs side data from validated components.
     *
     * @param fieldOrder the scan, or {@code null}
     * @param repeatedFields the extra field periods, from 0 to 4
     * @param framePacking the packing, or {@code null}
     * @param orientation the orientation, or {@code null}
     * @param layer the layer, or {@code null}
     * @param closedCaptions the caption data, owned by the instance, or {@code null}
     */
    private FrameSideData(FieldOrder fieldOrder, int repeatedFields, FramePacking framePacking,
                          Orientation orientation, VideoLayer layer, byte[] closedCaptions) {
        this.fieldOrder = fieldOrder;
        this.repeatedFields = repeatedFields;
        this.framePacking = framePacking;
        this.orientation = orientation;
        this.layer = layer;
        this.closedCaptions = closedCaptions;
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
     * Returns whether this side data carries nothing.
     *
     * @return whether every entry is absent or zero
     */
    public boolean isEmpty() {
        return fieldOrder == null && repeatedFields == 0 && framePacking == null && orientation == null
                && layer == null && closedCaptions == null;
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
                && Arrays.equals(closedCaptions, that.closedCaptions);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(fieldOrder, repeatedFields, framePacking, orientation, layer) * 31
                + Arrays.hashCode(closedCaptions);
    }

    @Override
    public String toString() {
        return "FrameSideData[fieldOrder=" + fieldOrder + ", repeatedFields=" + repeatedFields
                + ", framePacking=" + framePacking + ", orientation=" + orientation + ", layer=" + layer
                + ", closedCaptions=" + (closedCaptions == null ? "none" : closedCaptions.length / 3 + " constructs")
                + ']';
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
         * Builds the side data, {@link FrameSideData#NONE} when every entry is absent.
         *
         * @return the side data
         */
        public FrameSideData build() {
            if (fieldOrder == null && repeatedFields == 0 && framePacking == null && orientation == null
                    && layer == null && closedCaptions == null) {
                return NONE;
            }
            return new FrameSideData(fieldOrder, repeatedFields, framePacking, orientation, layer, closedCaptions);
        }
    }
}
