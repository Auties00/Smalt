package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the nine places a rectangle can be anchored to inside or outside another one: the
 * corners, the middles of the edges and the center.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum Anchor {
    /**
     * The top-left corner.
     */
    TOP_LEFT(0, 0),

    /**
     * The middle of the top edge.
     */
    TOP(1, 0),

    /**
     * The top-right corner.
     */
    TOP_RIGHT(2, 0),

    /**
     * The middle of the left edge.
     */
    LEFT(0, 1),

    /**
     * The center.
     */
    CENTER(1, 1),

    /**
     * The middle of the right edge.
     */
    RIGHT(2, 1),

    /**
     * The bottom-left corner.
     */
    BOTTOM_LEFT(0, 2),

    /**
     * The middle of the bottom edge.
     */
    BOTTOM(1, 2),

    /**
     * The bottom-right corner.
     */
    BOTTOM_RIGHT(2, 2);

    /**
     * The horizontal position: {@code 0} left, {@code 1} center, {@code 2} right.
     */
    private final int column;

    /**
     * The vertical position: {@code 0} top, {@code 1} middle, {@code 2} bottom.
     */
    private final int row;

    /**
     * Creates an anchor.
     *
     * @param column the horizontal position: {@code 0} left, {@code 1} center, {@code 2} right
     * @param row    the vertical position: {@code 0} top, {@code 1} middle, {@code 2} bottom
     */
    Anchor(int column, int row) {
        this.column = column;
        this.row = row;
    }

    /**
     * Returns the column of the left edge of a rectangle of width {@code inner} anchored inside
     * one of width {@code outer}, keeping {@code margin} pixels from the anchored edge.
     *
     * <p>A centered rectangle ignores the margin and rounds its offset down.
     *
     * @param outer  the width of the enclosing rectangle
     * @param inner  the width of the anchored rectangle
     * @param margin the distance from the anchored edge, in pixels
     * @return the offset, negative when the anchored rectangle is wider than the margin allows
     */
    int x(int outer, int inner, int margin) {
        return offset(column, outer, inner, margin);
    }

    /**
     * Returns the row of the top edge of a rectangle of height {@code inner} anchored inside one
     * of height {@code outer}, keeping {@code margin} pixels from the anchored edge.
     *
     * <p>A centered rectangle ignores the margin and rounds its offset down.
     *
     * @param outer  the height of the enclosing rectangle
     * @param inner  the height of the anchored rectangle
     * @param margin the distance from the anchored edge, in pixels
     * @return the offset, negative when the anchored rectangle is taller than the margin allows
     */
    int y(int outer, int inner, int margin) {
        return offset(row, outer, inner, margin);
    }

    /**
     * Returns the offset of an anchored span along one axis.
     *
     * @param position the anchored position: {@code 0} start, {@code 1} center, {@code 2} end
     * @param outer    the length of the enclosing span
     * @param inner    the length of the anchored span
     * @param margin   the distance from the anchored edge
     * @return the offset
     */
    private static int offset(int position, int outer, int inner, int margin) {
        return switch (position) {
            case 0 -> margin;
            case 1 -> (int) Math.floorDiv((long) outer - inner, 2);
            default -> (int) ((long) outer - inner - margin);
        };
    }
}
