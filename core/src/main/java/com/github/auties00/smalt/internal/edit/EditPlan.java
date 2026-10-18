package com.github.auties00.smalt.internal.edit;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes a rewritten file as a sequence of pieces: literal bytes built in memory, ranges copied
 * from the original file, and ranges copied through a transformation.
 *
 * <p>A plan describes the whole output, so it can always be streamed to a new destination. When
 * every copied range lands at the offset it already occupies in the original, the output differs
 * from the original only where literal bytes are written and in its length: such a plan is
 * {@linkplain #isInPlace() in place} and can be applied to the original file by writing the
 * literal pieces and truncating or extending the file, without moving any of its data.
 *
 * <p>Instances are immutable once built and thread-safe.
 */
public final class EditPlan {
    /**
     * The pieces in output order.
     */
    private final List<Piece> pieces;

    /**
     * The total length of the output in bytes.
     */
    private final long length;

    /**
     * Creates a plan.
     *
     * @param pieces the pieces in output order
     * @param length the total length of the output
     */
    private EditPlan(List<Piece> pieces, long length) {
        this.pieces = List.copyOf(pieces);
        this.length = length;
    }

    /**
     * Returns a new, empty builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the pieces in output order.
     *
     * @return the pieces; unmodifiable
     */
    public List<Piece> pieces() {
        return pieces;
    }

    /**
     * Returns the total length of the output.
     *
     * @return the length in bytes
     */
    public long length() {
        return length;
    }

    /**
     * Returns whether the plan can be applied to the original file by writing only its literal
     * pieces and adjusting the file length.
     *
     * @return {@code true} when every copied range lands at its original offset and no piece is
     * transformed
     */
    public boolean isInPlace() {
        var position = 0L;
        for (var piece : pieces) {
            switch (piece) {
                case Copy copy -> {
                    if (copy.start() != position) {
                        return false;
                    }
                }
                case Bytes _ -> {
                }
                case Transform _ -> {
                    return false;
                }
            }
            position += piece.length();
        }
        return true;
    }

    /**
     * Returns whether the plan reproduces the original file exactly.
     *
     * @param originalLength the length of the original file
     * @return {@code true} when the plan is a single identity copy of the whole original, or
     * nothing for an empty original
     */
    public boolean isIdentity(long originalLength) {
        if (length != originalLength) {
            return false;
        }
        for (var piece : pieces) {
            if (!(piece instanceof Copy)) {
                return false;
            }
        }
        return isInPlace();
    }

    /**
     * Returns whether the output of an in-place plan equals the original byte for byte.
     *
     * <p>Only the literal pieces are compared with the bytes they would overwrite, so the check
     * costs as much as reading the metadata.
     *
     * @param source the original
     * @return {@code true} when writing the plan would change nothing
     * @throws MediaException.Io if reading fails
     */
    public boolean reproduces(MediaSource source) {
        if (length != source.size() || !isInPlace()) {
            return false;
        }
        var position = 0L;
        for (var piece : pieces) {
            if (piece instanceof Bytes(var data)) {
                var original = new byte[data.length];
                source.readFully(position, original, 0, original.length);
                if (!java.util.Arrays.equals(original, data)) {
                    return false;
                }
            }
            position += piece.length();
        }
        return true;
    }

    /**
     * Writes the whole output to a sink, reading copied ranges from the original.
     *
     * @param source the original file
     * @param sink the destination, written from its current position
     * @throws MediaException.Malformed if a copied range runs past the end of the original
     * @throws MediaException.Io if reading or writing fails
     */
    public void writeTo(MediaSource source, MediaSink sink) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sink, "sink");
        var buffer = new byte[COPY_BUFFER];
        for (var piece : pieces) {
            piece.writeTo(source, sink, buffer);
        }
    }

    /**
     * The size of the buffer used to copy ranges, in bytes.
     */
    static final int COPY_BUFFER = 1 << 16;

    /**
     * One part of a plan's output.
     */
    public sealed interface Piece permits Bytes, Copy, Transform {
        /**
         * Returns the number of bytes this piece contributes to the output.
         *
         * @return the length in bytes
         */
        long length();

        /**
         * Writes this piece to a sink.
         *
         * @param source the original file
         * @param sink the destination
         * @param buffer a scratch buffer the piece may use
         * @throws MediaException.Malformed if the piece reads past the end of the original
         * @throws MediaException.Io if reading or writing fails
         */
        void writeTo(MediaSource source, MediaSink sink, byte[] buffer);
    }

    /**
     * Literal bytes built in memory.
     *
     * @param data the bytes; not copied, the plan owns them
     */
    public record Bytes(byte[] data) implements Piece {
        /**
         * Validates the piece.
         *
         * @throws NullPointerException if {@code data} is {@code null}
         */
        public Bytes {
            Objects.requireNonNull(data, "data");
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public void writeTo(MediaSource source, MediaSink sink, byte[] buffer) {
            sink.write(data, 0, data.length);
        }
    }

    /**
     * A range copied unchanged from the original.
     *
     * @param start the offset of the range in the original
     * @param length the length of the range
     */
    public record Copy(long start, long length) implements Piece {
        /**
         * Validates the range.
         *
         * @throws IllegalArgumentException if {@code start} or {@code length} is negative
         */
        public Copy {
            if (start < 0 || length < 0) {
                throw new IllegalArgumentException("invalid range " + start + "+" + length);
            }
        }

        @Override
        public void writeTo(MediaSource source, MediaSink sink, byte[] buffer) {
            copy(source, sink, start, length, buffer);
        }
    }

    /**
     * A range of the original rewritten by a transformation while it is copied, such as Ogg pages
     * whose sequence numbers shift or MP4 fragments whose absolute offsets move.
     */
    public abstract static non-sealed class Transform implements Piece {
        /**
         * Creates a transformation.
         */
        protected Transform() {
        }
    }

    /**
     * Copies a range of a source to a sink.
     *
     * @param source the bytes to copy from
     * @param sink the destination
     * @param start the offset of the first byte
     * @param length the number of bytes
     * @param buffer the scratch buffer
     * @throws MediaException.Malformed if the source ends before the range does
     * @throws MediaException.Io if reading or writing fails
     */
    public static void copy(MediaSource source, MediaSink sink, long start, long length, byte[] buffer) {
        var done = 0L;
        while (done < length) {
            var chunk = (int) Math.min(buffer.length, length - done);
            var read = source.read(start + done, buffer, 0, chunk);
            if (read <= 0) {
                throw new MediaException.Malformed("the input ends at offset " + (start + done)
                        + " inside a range that must be copied");
            }
            sink.write(buffer, 0, read);
            done += read;
        }
    }

    /**
     * Assembles a plan, merging adjacent copies of contiguous ranges.
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
         * Creates an empty builder.
         */
        private Builder() {
        }

        /**
         * Appends literal bytes; an empty array is ignored.
         *
         * @param data the bytes; not copied
         * @return this builder
         */
        public Builder bytes(byte[] data) {
            if (data.length > 0) {
                pieces.add(new Bytes(data));
                length += data.length;
            }
            return this;
        }

        /**
         * Appends a range copied from the original; an empty range is ignored.
         *
         * @param start the offset of the range in the original
         * @param count the length of the range
         * @return this builder
         */
        public Builder copy(long start, long count) {
            if (count <= 0) {
                return this;
            }
            if (!pieces.isEmpty() && pieces.getLast() instanceof Copy last && last.start() + last.length() == start) {
                pieces.set(pieces.size() - 1, new Copy(last.start(), last.length() + count));
            } else {
                pieces.add(new Copy(start, count));
            }
            length += count;
            return this;
        }

        /**
         * Appends a transformed range.
         *
         * @param transform the transformation
         * @return this builder
         */
        public Builder transform(Transform transform) {
            Objects.requireNonNull(transform, "transform");
            if (transform.length() > 0) {
                pieces.add(transform);
                length += transform.length();
            }
            return this;
        }

        /**
         * Returns the length of the output assembled so far.
         *
         * @return the length in bytes
         */
        public long length() {
            return length;
        }

        /**
         * Builds the plan.
         *
         * @return the plan
         */
        public EditPlan build() {
            return new EditPlan(pieces, length);
        }
    }
}
