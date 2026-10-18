package com.github.auties00.smalt.video.dnxhd;

import java.util.OptionalInt;

/**
 * Enumerates the DNxHD and DNxHR families an encoder targets.
 *
 * <p>{@link #DNXHD} covers the HD-profile compression IDs of SMPTE ST 2019-1 Table C.1, whose
 * raster (1920x1080, 1440x1080, 1280x720 or 960x720), scan and bit depth are fixed per ID; the
 * encoder picks the ID from the picture size, scan, bit depth and bitrate. The DNxHR constants are
 * the resolution-independent IDs of Table C.2, for any picture size, whose compressed size scales
 * with the picture.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum DnxhdProfile {
    /**
     * DNxHD: one of the HD-profile compression IDs 1235 to 1260, QuickTime type {@code AVdn}.
     */
    DNXHD(0, 8, "AVdn"),

    /**
     * DNxHR LB (low bandwidth, compression ID 1274): 8-bit 4:2:2 (or 4:2:0), about 36 Mb/s at 1080p24.
     */
    DNXHR_LB(1274, 8, "AVdh"),

    /**
     * DNxHR SQ (standard quality, compression ID 1273): 8-bit 4:2:2 (or 4:2:0), about 116 Mb/s at 1080p24.
     */
    DNXHR_SQ(1273, 8, "AVdh"),

    /**
     * DNxHR HQ (high quality, compression ID 1272): 8-bit 4:2:2 (or 4:2:0), about 176 Mb/s at 1080p24.
     */
    DNXHR_HQ(1272, 8, "AVdh"),

    /**
     * DNxHR HQX (compression ID 1271): 10 or 12-bit 4:2:2 (or 4:2:0), about 176 Mb/s at 1080p24.
     */
    DNXHR_HQX(1271, 10, "AVdh"),

    /**
     * DNxHR 444 (compression ID 1270): 10 or 12-bit 4:4:4 Y'CbCr or RGB, about 352 Mb/s at
     * 1080p24.
     */
    DNXHR_444(1270, 10, "AVdh");

    /**
     * The compression ID, or {@code 0} for {@link #DNXHD}.
     */
    private final int compressionId;

    /**
     * The default sample bit depth.
     */
    private final int defaultDepth;

    /**
     * The QuickTime sample entry type.
     */
    private final String fourCc;

    /**
     * Constructs a constant.
     *
     * @param compressionId the compression ID, or {@code 0}
     * @param defaultDepth  the default bit depth
     * @param fourCc        the sample entry type
     */
    DnxhdProfile(int compressionId, int defaultDepth, String fourCc) {
        this.compressionId = compressionId;
        this.defaultDepth = defaultDepth;
        this.fourCc = fourCc;
    }

    /**
     * Returns the compression ID of a DNxHR family.
     *
     * @return the ID, or empty for {@link #DNXHD}, whose ID depends on the picture
     */
    public OptionalInt compressionId() {
        return compressionId == 0 ? OptionalInt.empty() : OptionalInt.of(compressionId);
    }

    /**
     * Returns the bit depth the family encodes at unless asked otherwise.
     *
     * @return 8 or 10
     */
    public int defaultBitDepth() {
        return defaultDepth;
    }

    /**
     * Returns the QuickTime sample entry type, which is also the codec configuration of the track.
     *
     * @return {@code "AVdn"} for DNxHD, {@code "AVdh"} for DNxHR
     */
    public String fourCc() {
        return fourCc;
    }
}
