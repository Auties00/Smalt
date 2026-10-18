package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

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
     * The largest number of resolution levels: 32 decomposition levels plus the lowest
     * resolution.
     */
    public static final int MAX_RESOLUTIONS = 33;

    /**
     * The number of resolution levels, one more than the number of decomposition levels.
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
     * Whether the reversible 5-3 wavelet is used, rather than the irreversible 9-7 one.
     */
    private final boolean reversible;

    /**
     * The base-2 logarithm of the precinct width of each resolution level.
     */
    private final int[] precinctWidths;

    /**
     * The base-2 logarithm of the precinct height of each resolution level.
     */
    private final int[] precinctHeights;

    /**
     * Constructs a coding style.
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
        this.resolutions = resolutions;
        this.codeBlockWidth = codeBlockWidth;
        this.codeBlockHeight = codeBlockHeight;
        this.codeBlockStyle = codeBlockStyle;
        this.reversible = reversible;
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
     * @return the coding style
     * @throws MediaException.Malformed   if a field is out of range or the segment is too short
     * @throws MediaException.Unsupported if the high-throughput block coder is selected
     */
    static ComponentCoding parse(byte[] data, int offset, int end, boolean userPrecincts) {
        if (end - offset < 5) {
            throw new MediaException.Malformed("truncated JPEG 2000 coding style");
        }
        var resolutions = (data[offset] & 0xFF) + 1;
        if (resolutions > MAX_RESOLUTIONS) {
            throw new MediaException.Malformed("invalid JPEG 2000 decomposition level count " + (resolutions - 1));
        }
        var width = (data[offset + 1] & 0xFF) + 2;
        var height = (data[offset + 2] & 0xFF) + 2;
        if (width > 10 || height > 10 || width + height > 12) {
            throw new MediaException.Malformed("invalid JPEG 2000 code-block size 2^" + width + "x2^" + height);
        }
        var style = data[offset + 3] & 0xFF;
        if ((style & HIGH_THROUGHPUT) != 0) {
            // TODO: decode the high-throughput block coder of ISO/IEC 15444-15 (OpenJPEG 2.5 ht_dec.c)
            throw new MediaException.Unsupported("JPEG 2000", "the high-throughput block coder (HTJ2K)");
        }
        var transform = data[offset + 4] & 0xFF;
        if (transform > 1) {
            throw new MediaException.Unsupported("JPEG 2000", "wavelet transform " + transform);
        }
        var widths = new int[resolutions];
        var heights = new int[resolutions];
        if (userPrecincts) {
            if (end - offset < 5 + resolutions) {
                throw new MediaException.Malformed("truncated JPEG 2000 precinct sizes");
            }
            for (var i = 0; i < resolutions; i++) {
                var value = data[offset + 5 + i] & 0xFF;
                if (i != 0 && ((value & 0xF) == 0 || (value >> 4) == 0)) {
                    throw new MediaException.Malformed("invalid JPEG 2000 precinct size at resolution " + i);
                }
                widths[i] = value & 0xF;
                heights[i] = value >> 4;
            }
        } else {
            Arrays.fill(widths, 15);
            Arrays.fill(heights, 15);
        }
        return new ComponentCoding(resolutions, width, height, style, transform == 1, widths, heights);
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
     *         {@link #VERTICALLY_CAUSAL}, {@link #PREDICTABLE_TERMINATION} and
     *         {@link #SEGMENTATION_SYMBOLS}
     */
    public int codeBlockStyle() {
        return codeBlockStyle;
    }

    /**
     * Returns whether the reversible 5-3 wavelet is used.
     *
     * @return {@code true} for the 5-3 wavelet, {@code false} for the 9-7 one
     */
    public boolean reversible() {
        return reversible;
    }

    /**
     * Returns the base-2 logarithm of the precinct width of a resolution level.
     *
     * @param resolution the resolution level
     * @return the exponent, 15 when the segment declares no precinct sizes
     */
    public int precinctWidth(int resolution) {
        return precinctWidths[resolution];
    }

    /**
     * Returns the base-2 logarithm of the precinct height of a resolution level.
     *
     * @param resolution the resolution level
     * @return the exponent, 15 when the segment declares no precinct sizes
     */
    public int precinctHeight(int resolution) {
        return precinctHeights[resolution];
    }
}
