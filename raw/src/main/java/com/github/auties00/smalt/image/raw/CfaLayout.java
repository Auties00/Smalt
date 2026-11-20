package com.github.auties00.smalt.image.raw;

/**
 * The spatial arrangement of the photosites of a color filter array: the rectangular grid of most
 * sensors, or one of the staggered layouts of DNG's {@code CFALayout} tag, in which every other row
 * or column sits half a photosite away from the grid (Fujifilm SuperCCD sensors, for example).
 *
 * <p>Rows and columns are counted from 1, as DNG counts them, from the top-left photosite of the
 * image the layout describes: the "even" rows and columns are the second, fourth and so on. The
 * {@link CfaPattern} still gives the color of each stored photosite; the layout says where on the
 * sensor it lies.
 */
public enum CfaLayout {
    /**
     * Rectangular (or square) layout, {@code CFALayout} 1.
     */
    RECTANGULAR(1),

    /**
     * Staggered layout A, {@code CFALayout} 2: even columns are offset down by half a row.
     */
    STAGGERED_A(2),

    /**
     * Staggered layout B, {@code CFALayout} 3: even columns are offset up by half a row.
     */
    STAGGERED_B(3),

    /**
     * Staggered layout C, {@code CFALayout} 4: even rows are offset right by half a column.
     */
    STAGGERED_C(4),

    /**
     * Staggered layout D, {@code CFALayout} 5: even rows are offset left by half a column.
     */
    STAGGERED_D(5),

    /**
     * Staggered layout E, {@code CFALayout} 6: even rows are offset up by half a row, even columns
     * left by half a column.
     */
    STAGGERED_E(6),

    /**
     * Staggered layout F, {@code CFALayout} 7: even rows are offset up by half a row, even columns
     * right by half a column.
     */
    STAGGERED_F(7),

    /**
     * Staggered layout G, {@code CFALayout} 8: even rows are offset down by half a row, even columns
     * left by half a column.
     */
    STAGGERED_G(8),

    /**
     * Staggered layout H, {@code CFALayout} 9: even rows are offset down by half a row, even columns
     * right by half a column.
     */
    STAGGERED_H(9);

    private final int code;

    CfaLayout(int code) {
        this.code = code;
    }

    /**
     * {@return the DNG {@code CFALayout} value, from 1 to 9}
     */
    public int code() {
        return code;
    }

    /**
     * Returns the layout of a DNG {@code CFALayout} value.
     *
     * @param code the value, from 1 to 9
     * @return the layout
     * @throws IllegalArgumentException if the value is not a defined layout
     */
    public static CfaLayout of(int code) {
        if (code < 1 || code > 9) {
            throw new IllegalArgumentException("undefined CFA layout " + code);
        }
        return values()[code - 1];
    }

    /**
     * {@return whether some photosites are offset by half a row or column}
     */
    public boolean isStaggered() {
        return this != RECTANGULAR;
    }

    /**
     * Returns the layout of the image that starts at a given photosite of this one, as cropping
     * shifts it: the same layout up to a translation of the whole grid, with the roles of the even
     * and odd rows (or columns) exchanged when the crop removes an odd number of them.
     *
     * @param top the first row kept
     * @param left the first column kept
     * @return the shifted layout
     * @throws IllegalArgumentException if a coordinate is negative
     */
    public CfaLayout shifted(int top, int left) {
        if (top < 0 || left < 0) {
            throw new IllegalArgumentException("negative shift " + top + ", " + left);
        }
        var layout = this;
        if ((left & 1) != 0) {
            layout = switch (layout) {
                case STAGGERED_A -> STAGGERED_B;
                case STAGGERED_B -> STAGGERED_A;
                case STAGGERED_E -> STAGGERED_F;
                case STAGGERED_F -> STAGGERED_E;
                case STAGGERED_G -> STAGGERED_H;
                case STAGGERED_H -> STAGGERED_G;
                default -> layout;
            };
        }
        if ((top & 1) != 0) {
            layout = switch (layout) {
                case STAGGERED_C -> STAGGERED_D;
                case STAGGERED_D -> STAGGERED_C;
                case STAGGERED_E -> STAGGERED_G;
                case STAGGERED_G -> STAGGERED_E;
                case STAGGERED_F -> STAGGERED_H;
                case STAGGERED_H -> STAGGERED_F;
                default -> layout;
            };
        }
        return layout;
    }

    /**
     * Returns the vertical offset of a photosite from its grid position.
     *
     * @param row the row, from 0
     * @param column the column, from 0
     * @return the offset in rows: -0.5, 0 or 0.5, positive downward
     */
    public double rowOffset(int row, int column) {
        return switch (this) {
            case STAGGERED_A -> (column & 1) != 0 ? 0.5 : 0;
            case STAGGERED_B -> (column & 1) != 0 ? -0.5 : 0;
            case STAGGERED_E, STAGGERED_F -> (row & 1) != 0 ? -0.5 : 0;
            case STAGGERED_G, STAGGERED_H -> (row & 1) != 0 ? 0.5 : 0;
            default -> 0;
        };
    }

    /**
     * Returns the horizontal offset of a photosite from its grid position.
     *
     * @param row the row, from 0
     * @param column the column, from 0
     * @return the offset in columns: -0.5, 0 or 0.5, positive to the right
     */
    public double columnOffset(int row, int column) {
        return switch (this) {
            case STAGGERED_C -> (row & 1) != 0 ? 0.5 : 0;
            case STAGGERED_D -> (row & 1) != 0 ? -0.5 : 0;
            case STAGGERED_E, STAGGERED_G -> (column & 1) != 0 ? -0.5 : 0;
            case STAGGERED_F, STAGGERED_H -> (column & 1) != 0 ? 0.5 : 0;
            default -> 0;
        };
    }
}
