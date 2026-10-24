package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.Orientation;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the display information a decoder attaches to one {@link VideoFrame}: how the frame's
 * fields are scanned and repeated, whether it packs the two views of a stereoscopic pair, how
 * to orient it for display, and which layer of a multi-layer stream it shows.
 *
 * <p>Decoders fill the entries from the messages the stream carries (for H.264 and HEVC, the
 * picture timing, frame packing arrangement and display orientation SEI messages, and for HEVC
 * the layers its video parameter set describes) and leave the others absent; a frame the stream
 * says nothing about carries {@link #NONE}.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FrameSideData {
    /**
     * The side data of a frame that carries none.
     */
    public static final FrameSideData NONE = new FrameSideData(null, 0, null, null, null);

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
     * Constructs side data from validated components.
     *
     * @param fieldOrder the scan, or {@code null}
     * @param repeatedFields the extra field periods, from 0 to 4
     * @param framePacking the packing, or {@code null}
     * @param orientation the orientation, or {@code null}
     * @param layer the layer, or {@code null}
     */
    private FrameSideData(FieldOrder fieldOrder, int repeatedFields, FramePacking framePacking,
                          Orientation orientation, VideoLayer layer) {
        this.fieldOrder = fieldOrder;
        this.repeatedFields = repeatedFields;
        this.framePacking = framePacking;
        this.orientation = orientation;
        this.layer = layer;
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
     * <p>An HEVC decoder sets it on every frame when it outputs more than the base layer of a
     * stream with several layers, and leaves it absent otherwise.
     *
     * @return the layer, or empty when the frame comes from a single layer stream or only its base
     * layer is output
     */
    public Optional<VideoLayer> layer() {
        return Optional.ofNullable(layer);
    }

    /**
     * Returns whether this side data carries nothing.
     *
     * @return whether every entry is absent or zero
     */
    public boolean isEmpty() {
        return fieldOrder == null && repeatedFields == 0 && framePacking == null && orientation == null
                && layer == null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrameSideData that && fieldOrder == that.fieldOrder
                && repeatedFields == that.repeatedFields && Objects.equals(framePacking, that.framePacking)
                && orientation == that.orientation && Objects.equals(layer, that.layer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldOrder, repeatedFields, framePacking, orientation, layer);
    }

    @Override
    public String toString() {
        return "FrameSideData[fieldOrder=" + fieldOrder + ", repeatedFields=" + repeatedFields
                + ", framePacking=" + framePacking + ", orientation=" + orientation + ", layer=" + layer + ']';
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

        private VideoLayer layer;

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
         * Builds the side data, {@link FrameSideData#NONE} when every entry is absent.
         *
         * @return the side data
         */
        public FrameSideData build() {
            if (fieldOrder == null && repeatedFields == 0 && framePacking == null && orientation == null
                    && layer == null) {
                return NONE;
            }
            return new FrameSideData(fieldOrder, repeatedFields, framePacking, orientation, layer);
        }
    }
}
