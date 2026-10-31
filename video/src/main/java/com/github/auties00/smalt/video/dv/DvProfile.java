package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.track.Rational;

/**
 * Enumerates the DV formats {@link DvEncoder} writes and {@link DvDecoder} reads.
 *
 * <p>The SD formats code 720 samples per line at 13.5 MHz, interlaced with the bottom field first; the DVCPRO HD
 * formats code horizontally subsampled 1080-line interlaced (top field first) or 720-line progressive pictures,
 * displayed at 16:9. A frame of each format has a fixed size, which {@link #frameSize()} gives.
 */
public enum DvProfile {
    /**
     * DV at 25 Mb/s, 525/60 (720 by 480 at 30000/1001 frames per second), 4:1:1, as IEC 61834-2 defines it for
     * MiniDV and DVCAM; QuickTime type {@code dvc }.
     */
    DV_525(DifProfile.DV_525),

    /**
     * DVCPRO at 25 Mb/s, 525/60, 4:1:1 (SMPTE ST 314); the same coding as {@link #DV_525} with the SMPTE application
     * ID; QuickTime type {@code dvpn}.
     */
    DVCPRO_525(DifProfile.DVCPRO_525),

    /**
     * DV at 25 Mb/s, 625/50 (720 by 576 at 25 frames per second), 4:2:0, as IEC 61834-2 defines it for MiniDV and
     * DVCAM; QuickTime type {@code dvcp}.
     */
    DV_625(DifProfile.DV_625),

    /**
     * DVCPRO at 25 Mb/s, 625/50, 4:1:1 (SMPTE ST 314); QuickTime type {@code dvpp}.
     */
    DVCPRO_625(DifProfile.DVCPRO_625),

    /**
     * DVCPRO50 at 50 Mb/s, 525/60, 4:2:2 (SMPTE ST 314); QuickTime type {@code dv5n}.
     */
    DVCPRO50_525(DifProfile.DVCPRO50_525),

    /**
     * DVCPRO50 at 50 Mb/s, 625/50, 4:2:2 (SMPTE ST 314); QuickTime type {@code dv5p}.
     */
    DVCPRO50_625(DifProfile.DVCPRO50_625),

    /**
     * DVCPRO HD at 100 Mb/s, 1080/60i: 1280 by 1080 coded samples at 30000/1001 frames per second, 4:2:2 (SMPTE
     * ST 370); QuickTime type {@code dvh6}.
     */
    DVCPRO_HD_1080I60(DifProfile.DVCPRO_HD_1080I60),

    /**
     * DVCPRO HD at 100 Mb/s, 1080/50i: 1440 by 1080 coded samples at 25 frames per second, 4:2:2 (SMPTE ST 370);
     * QuickTime type {@code dvh5}.
     */
    DVCPRO_HD_1080I50(DifProfile.DVCPRO_HD_1080I50),

    /**
     * DVCPRO HD at 100 Mb/s, 720/60p: 960 by 720 coded samples at 60000/1001 frames per second, 4:2:2 (SMPTE
     * ST 370); QuickTime type {@code dvhp}.
     */
    DVCPRO_HD_720P60(DifProfile.DVCPRO_HD_720P60),

    /**
     * DVCPRO HD at 100 Mb/s, 720/50p: 960 by 720 coded samples at 50 frames per second, 4:2:2 (SMPTE ST 370);
     * QuickTime type {@code dvhq}.
     */
    DVCPRO_HD_720P50(DifProfile.DVCPRO_HD_720P50);

    private final DifProfile dif;

    DvProfile(DifProfile dif) {
        this.dif = dif;
    }

    /**
     * {@return the number of coded luma samples per line}
     */
    public int width() {
        return dif.width();
    }

    /**
     * {@return the number of coded lines}
     */
    public int height() {
        return dif.height();
    }

    /**
     * {@return the frame rate}
     */
    public Rational frameRate() {
        return dif.frameRate();
    }

    /**
     * {@return the size of a frame in bytes}
     */
    public int frameSize() {
        return dif.frameSize();
    }

    /**
     * {@return whether the frames are interlaced; {@code false} only for the 720-line formats}
     */
    public boolean interlaced() {
        return !dif.progressive();
    }

    /**
     * Returns the sample aspect ratio of the coded picture.
     *
     * @param wide whether the picture displays at 16:9; ignored by the DVCPRO HD formats, which always do
     * @return the ratio of the width of a sample to its height
     */
    public Rational sampleAspectRatio(boolean wide) {
        return dif.sampleAspectRatio(wide);
    }

    /**
     * {@return the DIF description of this format}
     */
    DifProfile dif() {
        return dif;
    }

    /**
     * Returns the format of a DIF description.
     *
     * @param dif the description
     * @return the format
     */
    static DvProfile of(DifProfile dif) {
        return valueOf(dif.name());
    }
}
