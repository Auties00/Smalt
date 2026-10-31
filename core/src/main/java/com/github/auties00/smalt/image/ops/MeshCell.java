package com.github.auties00.smalt.image.ops;

import java.util.Objects;

/**
 * Describes one cell of a mesh warp: a rectangle of the result filled from a quadrilateral of the source, as
 * {@link ImageOps#quad} fills a whole result.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param target the rectangle of the result the cell covers; it may extend beyond the result, whose part outside
 * is ignored
 * @param source the quadrilateral of the source picture whose corners map to the corners of {@code target}
 * @see ImageOps#mesh
 */
public record MeshCell(Region target, Quad source) {
    /**
     * Validates the cell.
     */
    public MeshCell {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
    }
}
