package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the coding style of one tile-component: the {@code SPcod} parameters of a {@code COD}
 * segment or the {@code SPcoc} parameters of a {@code COC} segment (ISO/IEC 15444-1 A.6.1 and
 * A.6.2).
 *
 * <p>Instances are immutable once parsed.
 */
public final class ComponentCoding {
    /**
     * The code-block style flag selecting selective arithmetic coding bypass.
     */
    public static final int BYPASS = 0x01;

    /**
     * The code-block style flag resetting the context probabilities after each coding pass.
     */
    public static final int RESET = 0x02;

    /**
     * The code-block style flag terminating the arithmetic coder after each coding pass.
     */
    public static final int TERMINATE_ALL = 0x04;

    /**
     * The code-block style flag selecting vertically causal context formation.
     */
    public static final int VERTICALLY_CAUSAL = 0x08;

    /**
     * The code-block style flag selecting predictable termination.
     */
    public static final int PREDICTABLE_TERMINATION = 0x10;

    /**
     * The code-block style flag adding segmentation symbols after each cleanup pass.
     */
    public static final int SEGMENTATION_SYMBOLS = 0x20;

    /**
     * The code-block style flag selecting the high-throughput block coder of ISO/IEC 15444-15.
     */
    public static final int HIGH_THROUGHPUT = 0x40;

    /**
     * The code-block style flags allowing high-throughput and legacy code-blocks in one tile-component
     * (ISO/IEC 15444-15 Table A.4).
     */
    public static final int MIXED = 0xC0;

    /**
     * The largest number of resolution levels: 32 decomposition levels plus the lowest
     * resolution.
     */
    public static final int MAX_RESOLUTIONS = 33;

    /**
     * The number of resolution levels, one more than the number of decomposition levels; 0 while a decomposition
     * style index stands for the level count.
     */
    private final int resolutions;

    /**
     * The base-2 logarithm of the nominal code-block width.
     */
    private final int codeBlockWidth;

    /**
     * The base-2 logarithm of the nominal code-block height.
     */
    private final int codeBlockHeight;

    /**
     * The code-block style flags.
     */
    private final int codeBlockStyle;

    /**
     * The wavelet transformation: 0 for the irreversible 9-7, 1 for the reversible 5-3, or the index of an
     * {@code ATK} kernel (ISO/IEC 15444-2 Table A.10).
     */
    private final int transform;

    /**
     * The index of the {@code DFS} (main header) or {@code ADS} (tile-part header) segment of an arbitrary
     * decomposition (ISO/IEC 15444-2 Table A.9), or 0 for the dyadic decomposition.
     */
    private final int decompositionStyle;

    /**
     * The base-2 logarithm of the precinct width of each resolution level, or {@code null} for the maximal
     * precincts.
     */
    private final int[] precinctWidths;

    /**
     * The base-2 logarithm of the precinct height of each resolution level, or {@code null}.
     */
    private final int[] precinctHeights;

    /**
     * Constructs a coding style of the dyadic decomposition with a wavelet of ISO/IEC 15444-1.
     *
     * @param resolutions     the number of resolution levels
     * @param codeBlockWidth  the base-2 logarithm of the code-block width
     * @param codeBlockHeight the base-2 logarithm of the code-block height
     * @param codeBlockStyle  the code-block style flags
     * @param reversible      whether the 5-3 wavelet is used
     * @param precinctWidths  the precinct width exponents, one per resolution
     * @param precinctHeights the precinct height exponents, one per resolution
     */
    public ComponentCoding(int resolutions, int codeBlockWidth, int codeBlockHeight, int codeBlockStyle,
                           boolean reversible, int[] precinctWidths, int[] precinctHeights) {
        this(resolutions, codeBlockWidth, codeBlockHeight, codeBlockStyle, reversible ? 1 : 0, 0, precinctWidths,
                precinctHeights);
    }

    /**
     * Constructs a coding style.
     *
     * @param resolutions        the number of resolution levels, or 0 when {@code decompositionStyle} gives it
     * @param codeBlockWidth     the base-2 logarithm of the code-block width
     * @param codeBlockHeight    the base-2 logarithm of the code-block height
     * @param codeBlockStyle     the code-block style flags
     * @param transform          the wavelet transformation: 0, 1 or an {@code ATK} index
     * @param decompositionStyle the {@code DFS} or {@code ADS} index, or 0
     * @param precinctWidths     the precinct width exponents, one per resolution (as many as the segment holds while
     *                           the resolution count is unknown), or {@code null} for the maximal precincts
     * @param precinctHeights    the precinct height exponents, or {@code null}
     */
    ComponentCoding(int resolutions, int codeBlockWidth, int codeBlockHeight, int codeBlockStyle, int transform,
                    int decompositionStyle, int[] precinctWidths, int[] precinctHeights) {
        this.resolutions = resolutions;
        this.codeBlockWidth = codeBlockWidth;
        this.codeBlockHeight = codeBlockHeight;
        this.codeBlockStyle = codeBlockStyle;
        this.transform = transform;
        this.decompositionStyle = decompositionStyle;
        this.precinctWidths = precinctWidths;
        this.precinctHeights = precinctHeights;
    }

    /**
     * Parses the {@code SPcod}/{@code SPcoc} fields of a segment.
     *
     * @param data           the segment
     * @param offset         the index of the first field (the number of decomposition levels)
     * @param end            the end of the segment
     * @param userPrecincts  whether the style byte declares precinct sizes ({@code Scod} or
     *                       {@code Scoc} bit 0)
     * @param extended       whether the codestream uses the extensions of ISO/IEC 15444-2, which allow
     *                       decomposition style indices and {@code ATK} kernels
     * @return the coding style
     * @throws MediaException.Malformed if a field is out of range or the segment is too short
     */
    static ComponentCoding parse(byte[] data, int offset, int end, boolean userPrecincts, boolean extended) {
        if (end - offset < 5) {
            throw new MediaException.Malformed("truncated JPEG 2000 coding style");
        }
        var levels = data[offset] & 0xFF;
        var style = 0;
        var resolutions = levels + 1;
        if (extended && levels > 0x80) {
            style = levels & 0x7F;
            resolutions = 0;
        } else if (resolutions > MAX_RESOLUTIONS) {
            throw new MediaException.Malformed("invalid JPEG 2000 decomposition level count " + levels);
        }
        var width = (data[offset + 1] & 0xFF) + 2;
        var height = (data[offset + 2] & 0xFF) + 2;
        if (width > 10 || height > 10 || width + height > 12) {
            throw new MediaException.Malformed("invalid JPEG 2000 code-block size 2^" + width + "x2^" + height);
        }
        var blockStyle = data[offset + 3] & 0xFF;
        var transform = data[offset + 4] & 0xFF;
        if (transform > 1 && !extended) {
            throw new MediaException.Malformed("invalid JPEG 2000 wavelet transform " + transform);
        }
        int[] widths = null;
        int[] heights = null;
        if (userPrecincts) {
            var count = resolutions == 0 ? end - offset - 5 : resolutions;
            if (end - offset < 5 + count) {
                throw new MediaException.Malformed("truncated JPEG 2000 precinct sizes");
            }
            widths = new int[count];
            heights = new int[count];
            for (var i = 0; i < count; i++) {
                var value = data[offset + 5 + i] & 0xFF;
                if (i != 0 && ((value & 0xF) == 0 || (value >> 4) == 0)) {
                    throw new MediaException.Malformed("invalid JPEG 2000 precinct size at resolution " + i);
                }
                widths[i] = value & 0xF;
                heights[i] = value >> 4;
            }
        }
        return new ComponentCoding(resolutions, width, height, blockStyle, transform, style, widths, heights);
    }

    /**
     * Returns this coding style with the number of decomposition levels a decomposition style gives it.
     *
     * @param levels the number of decomposition levels
     * @return the coding style
     * @throws MediaException.Malformed if the level count is out of range or the precinct sizes do not cover every
     * resolution
     */
    ComponentCoding withLevels(int levels) {
        if (levels + 1 > MAX_RESOLUTIONS) {
            throw new MediaException.Malformed("invalid JPEG 2000 decomposition level count " + levels);
        }
        if (precinctWidths != null && precinctWidths.length < levels + 1) {
            throw new MediaException.Malformed("truncated JPEG 2000 precinct sizes");
        }
        return new ComponentCoding(levels + 1, codeBlockWidth, codeBlockHeight, codeBlockStyle, transform,
                decompositionStyle, precinctWidths, precinctHeights);
    }

    /**
     * Returns the number of bytes the fields occupy in a segment.
     *
     * @param userPrecincts whether precinct sizes are present
     * @return the field length
     */
    int length(boolean userPrecincts) {
        return 5 + (userPrecincts ? resolutions : 0);
    }

    /**
     * Returns the number of resolution levels.
     *
     * @return the resolution count, from 1 to {@link #MAX_RESOLUTIONS}
     */
    public int resolutions() {
        return resolutions;
    }

    /**
     * Returns the base-2 logarithm of the nominal code-block width.
     *
     * @return the exponent, from 2 to 10
     */
    public int codeBlockWidth() {
        return codeBlockWidth;
    }

    /**
     * Returns the base-2 logarithm of the nominal code-block height.
     *
     * @return the exponent, from 2 to 10
     */
    public int codeBlockHeight() {
        return codeBlockHeight;
    }

    /**
     * Returns the code-block style flags.
     *
     * @return the flags, a combination of {@link #BYPASS}, {@link #RESET}, {@link #TERMINATE_ALL},
     *         {@link #VERTICALLY_CAUSAL}, {@link #PREDICTABLE_TERMINATION}, {@link #SEGMENTATION_SYMBOLS},
     *         {@link #HIGH_THROUGHPUT} and {@link #MIXED}
     */
    public int codeBlockStyle() {
        return codeBlockStyle;
    }

    /**
     * Returns whether the reversible 5-3 wavelet of ISO/IEC 15444-1 is used.
     *
     * @return {@code true} for the 5-3 wavelet, {@code false} for the 9-7 one or an {@code ATK} kernel
     */
    public boolean reversible() {
        return transform == 1;
    }

    /**
     * Returns the wavelet transformation.
     *
     * @return 0 for the irreversible 9-7, 1 for the reversible 5-3, or the index of an {@code ATK} kernel
     */
    int transform() {
        return transform;
    }

    /**
     * Returns the decomposition style index.
     *
     * @return the {@code DFS} or {@code ADS} index of an arbitrary decomposition, or 0 for the dyadic one
     */
    int decompositionStyle() {
        return decompositionStyle;
    }

    /**
     * Returns the base-2 logarithm of the precinct width of a resolution level.
     *
     * @param resolution the resolution level
     * @return the exponent, 15 when the segment declares no precinct sizes
     */
    public int precinctWidth(int resolution) {
        return precinctWidths == null ? 15 : precinctWidths[resolution];
    }

    /**
     * Returns the base-2 logarithm of the precinct height of a resolution level.
     *
     * @param resolution the resolution level
     * @return the exponent, 15 when the segment declares no precinct sizes
     */
    public int precinctHeight(int resolution) {
        return precinctHeights == null ? 15 : precinctHeights[resolution];
    }
}
