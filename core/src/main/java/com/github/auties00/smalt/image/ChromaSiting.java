package com.github.auties00.smalt.image;

import java.util.OptionalInt;

/**
 * Enumerates the positions of subsampled chroma samples relative to the luma samples they cover.
 *
 * <p>The names give the position of each chroma sample within the block of luma samples it
 * covers: {@link #LEFT} sits between the two left luma samples of a 2x2 block (vertically
 * centered, horizontally co-sited), {@link #CENTER} in the middle of the block, {@link #TOP_LEFT}
 * on the top-left luma sample, and so on. The constants map one to one to the
 * {@code chroma_sample_loc_type} values 0 to 5 of the H.264 and H.265 VUI. The siting only matters
 * for subsampled layouts ({@link ChromaFormat#YUV420} and, horizontally, {@link ChromaFormat#YUV422});
 * {@link #UNSPECIFIED} leaves the choice to the consumer, which conventionally assumes
 * {@link #LEFT} for video and {@link #CENTER} for JPEG.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum ChromaSiting {
    /**
     * The position is not signaled.
     */
    UNSPECIFIED(-1),

    /**
     * Co-sited horizontally with the left luma column, centered vertically between two rows
     * ({@code chroma_sample_loc_type} 0, MPEG-2 and most video; AV1
     * {@code CSP_VERTICAL}).
     */
    LEFT(0),

    /**
     * Centered horizontally and vertically between the luma samples it covers
     * ({@code chroma_sample_loc_type} 1, JPEG and MPEG-1).
     */
    CENTER(1),

    /**
     * Co-sited with the top-left luma sample ({@code chroma_sample_loc_type} 2, BT.2020 and
     * BT.2100 HDR video; AV1 {@code CSP_COLOCATED}).
     */
    TOP_LEFT(2),

    /**
     * Centered horizontally, co-sited with the top luma row ({@code chroma_sample_loc_type} 3).
     */
    TOP(3),

    /**
     * Co-sited with the left luma column and the bottom luma row ({@code chroma_sample_loc_type} 4).
     */
    BOTTOM_LEFT(4),

    /**
     * Centered horizontally, co-sited with the bottom luma row ({@code chroma_sample_loc_type} 5).
     */
    BOTTOM(5);

    /**
     * The {@code chroma_sample_loc_type} value, or {@code -1} for {@link #UNSPECIFIED}.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the {@code chroma_sample_loc_type} value, or {@code -1}
     */
    ChromaSiting(int code) {
        this.code = code;
    }

    /**
     * Returns the H.264 and H.265 {@code chroma_sample_loc_type} value of this siting.
     *
     * @return the value, 0 to 5, or empty for {@link #UNSPECIFIED}
     */
    public OptionalInt chromaSampleLocType() {
        return code < 0 ? OptionalInt.empty() : OptionalInt.of(code);
    }

    /**
     * Returns the siting of an H.264 or H.265 {@code chroma_sample_loc_type} value.
     *
     * @param chromaSampleLocType the signaled value
     * @return the siting, or {@link #UNSPECIFIED} when the value is not 0 to 5
     */
    public static ChromaSiting of(int chromaSampleLocType) {
        return switch (chromaSampleLocType) {
            case 0 -> LEFT;
            case 1 -> CENTER;
            case 2 -> TOP_LEFT;
            case 3 -> TOP;
            case 4 -> BOTTOM_LEFT;
            case 5 -> BOTTOM;
            default -> UNSPECIFIED;
        };
    }
}
