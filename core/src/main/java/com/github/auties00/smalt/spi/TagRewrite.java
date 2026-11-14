package com.github.auties00.smalt.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Describes the file a tag edit writes, as a sequence of literal bytes and ranges copied from the original file:
 * the plan a {@link ContainerProvider} returns from
 * {@link ContainerProvider#planTags ContainerProvider.planTags}.
 *
 * <p>A plan describes the whole output, so it can always be streamed to a new file. When every copied range lands
 * at the offset it already occupies in the original, the output differs from the original only where literal
 * bytes are written and in its length: such a plan is {@linkplain #isInPlace() in place} and can be applied by
 * writing the literal pieces over the original and truncating or extending it, without moving its data.
 *
 * <p>Instances are immutable and thread-safe, provided the arrays of their {@link Bytes} pieces are not modified;
 * create them with {@link #builder()} or {@link #unchanged(long)}.
 */
public final class TagRewrite {
    /**
     * The pieces in output order.
     */
    private final List<Piece> pieces;

    /**
     * The length of the output in bytes.
     */
    private final long length;

    /**
     * Constructs a plan.
     *
     * @param pieces the pieces in output order, owned by the plan
     * @param length the length of the output
     */
    private TagRewrite(List<Piece> pieces, long length) {
        this.pieces = List.copyOf(pieces);
        this.length = length;
    }

    /**
     * Returns a builder of an empty plan.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the plan that reproduces an original file unchanged.
     *
     * @param length the length of the original, in bytes
     * @return a plan of a single copy of the whole original, or of nothing when {@code length} is zero
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static TagRewrite unchanged(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("negative length " + length);
        }
        return builder().copy(0, length).build();
    }

    /**
     * {@return the pieces of the output, in output order; adjacent copies of contiguous ranges are merged}
     */
    public List<Piece> pieces() {
        return pieces;
    }

    /**
     * {@return the length of the output in bytes}
     */
    public long length() {
        return length;
    }

    /**
     * Returns whether the plan can be applied to the original by writing only its literal pieces and adjusting
     * the length of the file.
     *
     * @return {@code true} when every copied range lands at the offset it occupies in the original
     */
    public boolean isInPlace() {
        var position = 0L;
        for (var piece : pieces) {
            if (piece instanceof Copy copy && copy.start() != position) {
                return false;
            }
            position += piece.length();
        }
        return true;
    }

    /**
     * A part of the output of a {@link TagRewrite}.
     *
     * <p>This sealed type may gain subtypes in later releases; a {@code switch} over it needs a {@code default}
     * branch.
     */
    public sealed interface Piece permits Bytes, Copy {
        /**
         * {@return the number of bytes the piece writes}
         */
        long length();
    }

    /**
     * Literal bytes written to the output.
     *
     * @param data the bytes, not empty; the caller must not modify the array
     */
    public record Bytes(byte[] data) implements Piece {
        /**
         * Validates the piece.
         *
         * @throws IllegalArgumentException if {@code data} is empty
         */
        public Bytes {
            Objects.requireNonNull(data, "data");
            if (data.length == 0) {
                throw new IllegalArgumentException("empty literal piece");
            }
        }

        @Override
        public long length() {
            return data.length;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Pieces are equal when they hold the same bytes.
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes(var bytes) && Arrays.equals(data, bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "Bytes[length=" + data.length + "]";
        }
    }

    /**
     * A range copied unchanged from the original file.
     *
     * @param start the offset of the range in the original, at least zero
     * @param length the length of the range, at least one
     */
    public record Copy(long start, long length) implements Piece {
        /**
         * Validates the range.
         *
         * @throws IllegalArgumentException if {@code start} is negative, {@code length} is not positive, or the
         * range ends past {@link Long#MAX_VALUE}
         */
        public Copy {
            if (start < 0 || length <= 0 || start > Long.MAX_VALUE - length) {
                throw new IllegalArgumentException("invalid range " + start + "+" + length);
            }
        }
    }

    /**
     * Assembles a {@link TagRewrite}, merging adjacent copies of contiguous ranges. Arguments are validated as
     * each piece is added.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The pieces added so far.
         */
        private final List<Piece> pieces = new ArrayList<>();

        /**
         * The length of the output so far.
         */
        private long length;

        /**
         * Constructs an empty builder.
         */
        private Builder() {
        }

        /**
         * Appends literal bytes; an empty array adds nothing.
         *
         * @param data the bytes, copied
         * @return this builder
         * @throws IllegalArgumentException if the output would grow past {@link Long#MAX_VALUE} bytes
         */
        public Builder bytes(byte[] data) {
            Objects.requireNonNull(data, "data");
            if (data.length > 0) {
                grow(data.length);
                pieces.add(new Bytes(data.clone()));
            }
            return this;
        }

        /**
         * Appends a range copied from the original; an empty range adds nothing.
         *
         * @param start the offset of the range in the original, at least zero
         * @param count the length of the range, at least zero
         * @return this builder
         * @throws IllegalArgumentException if {@code start} or {@code count} is negative, the range ends past
         * {@link Long#MAX_VALUE}, or the output would grow past it
         */
        public Builder copy(long start, long count) {
            if (start < 0 || count < 0 || start > Long.MAX_VALUE - count) {
                throw new IllegalArgumentException("invalid range " + start + "+" + count);
            }
            if (count == 0) {
                return this;
            }
            grow(count);
            if (!pieces.isEmpty() && pieces.getLast() instanceof Copy last && last.start() + last.length() == start) {
                pieces.set(pieces.size() - 1, new Copy(last.start(), last.length() + count));
            } else {
                pieces.add(new Copy(start, count));
            }
            return this;
        }

        /**
         * Adds bytes to the output length.
         *
         * @param count the number of bytes
         * @throws IllegalArgumentException if the length would pass {@link Long#MAX_VALUE}
         */
        private void grow(long count) {
            if (length > Long.MAX_VALUE - count) {
                throw new IllegalArgumentException("the output is longer than " + Long.MAX_VALUE + " bytes");
            }
            length += count;
        }

        /**
         * Builds the plan.
         *
         * @return the plan
         */
        public TagRewrite build() {
            return new TagRewrite(pieces, length);
        }
    }
}
