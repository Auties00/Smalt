package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.track.Codec;

import java.util.Objects;

/**
 * Names a codec mode of AMR-NB (3GPP TS 26.071, eight modes from 4.75 to 12.2 kbit/s) or AMR-WB
 * (3GPP TS 26.171, nine modes from 6.60 to 23.85 kbit/s).
 *
 * <p>A mode fixes the bitrate and the number of speech bits of every 20 millisecond frame. Its
 * {@link #frameType()} is the 4-bit frame type of the RFC 4867 storage format and of the MP4
 * {@code damr} mode set; the comfort noise (SID) and no-data frames that discontinuous transmission
 * produces are frame types, not modes.
 */
public enum AmrMode {
    /**
     * AMR-NB at 4.75 kbit/s (MR475).
     */
    NB_4_75(false, 0, 4750, 95),

    /**
     * AMR-NB at 5.15 kbit/s (MR515).
     */
    NB_5_15(false, 1, 5150, 103),

    /**
     * AMR-NB at 5.90 kbit/s (MR59).
     */
    NB_5_90(false, 2, 5900, 118),

    /**
     * AMR-NB at 6.70 kbit/s (MR67), the PDC-EFR mode.
     */
    NB_6_70(false, 3, 6700, 134),

    /**
     * AMR-NB at 7.40 kbit/s (MR74), the TDMA-EFR mode.
     */
    NB_7_40(false, 4, 7400, 148),

    /**
     * AMR-NB at 7.95 kbit/s (MR795).
     */
    NB_7_95(false, 5, 7950, 159),

    /**
     * AMR-NB at 10.2 kbit/s (MR102).
     */
    NB_10_2(false, 6, 10200, 204),

    /**
     * AMR-NB at 12.2 kbit/s (MR122), the GSM-EFR mode.
     */
    NB_12_2(false, 7, 12200, 244),

    /**
     * AMR-WB at 6.60 kbit/s.
     */
    WB_6_60(true, 0, 6600, 132),

    /**
     * AMR-WB at 8.85 kbit/s.
     */
    WB_8_85(true, 1, 8850, 177),

    /**
     * AMR-WB at 12.65 kbit/s.
     */
    WB_12_65(true, 2, 12650, 253),

    /**
     * AMR-WB at 14.25 kbit/s.
     */
    WB_14_25(true, 3, 14250, 285),

    /**
     * AMR-WB at 15.85 kbit/s.
     */
    WB_15_85(true, 4, 15850, 317),

    /**
     * AMR-WB at 18.25 kbit/s.
     */
    WB_18_25(true, 5, 18250, 365),

    /**
     * AMR-WB at 19.85 kbit/s.
     */
    WB_19_85(true, 6, 19850, 397),

    /**
     * AMR-WB at 23.05 kbit/s.
     */
    WB_23_05(true, 7, 23050, 461),

    /**
     * AMR-WB at 23.85 kbit/s.
     */
    WB_23_85(true, 8, 23850, 477);

    /**
     * The constants in declaration order, shared to avoid copying {@link #values()}.
     */
    private static final AmrMode[] VALUES = values();

    /**
     * Whether the mode belongs to AMR-WB.
     */
    private final boolean wideband;

    /**
     * The frame type of the storage format.
     */
    private final int frameType;

    /**
     * The bitrate in bits per second.
     */
    private final int bitrate;

    /**
     * The number of speech bits of one frame.
     */
    private final int bits;

    /**
     * Constructs a constant.
     *
     * @param wideband whether the mode belongs to AMR-WB
     * @param frameType the storage-format frame type
     * @param bitrate the bitrate in bits per second
     * @param bits the speech bits per frame
     */
    AmrMode(boolean wideband, int frameType, int bitrate, int bits) {
        this.wideband = wideband;
        this.frameType = frameType;
        this.bitrate = bitrate;
        this.bits = bits;
    }

    /**
     * {@return {@code true} for an AMR-WB mode, {@code false} for an AMR-NB mode}
     */
    public boolean isWideband() {
        return wideband;
    }

    /**
     * {@return the codec this mode belongs to, {@link Codec#AMR_WB} or {@link Codec#AMR_NB}}
     */
    public Codec codec() {
        return wideband ? Codec.AMR_WB : Codec.AMR_NB;
    }

    /**
     * {@return the sampling rate of the codec this mode belongs to, 16000 for AMR-WB and 8000 for AMR-NB}
     */
    public int sampleRate() {
        return wideband ? 16_000 : 8_000;
    }

    /**
     * {@return the 4-bit frame type that marks a speech frame of this mode in the RFC 4867 storage format, 0 to 7 for
     * AMR-NB and 0 to 8 for AMR-WB}
     */
    public int frameType() {
        return frameType;
    }

    /**
     * {@return the bitrate of this mode, in bits per second}
     */
    public int bitrate() {
        return bitrate;
    }

    /**
     * {@return the number of speech bits of one 20 millisecond frame of this mode}
     */
    public int bitsPerFrame() {
        return bits;
    }

    /**
     * {@return the size in bytes of one storage-format frame of this mode: the header byte followed by the speech bits
     * padded to whole bytes}
     */
    public int frameBytes() {
        return 1 + (bits + 7) / 8;
    }

    /**
     * Returns the mode of a codec whose bitrate is closest to a target.
     *
     * <p>A target halfway between two modes selects the lower one, so that the bitrate is not
     * exceeded on a tie.
     *
     * @param codec {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @param bitrate the target in bits per second
     * @return the closest mode
     * @throws IllegalArgumentException if {@code codec} is not an AMR codec
     */
    public static AmrMode closest(Codec codec, int bitrate) {
        var wide = isWideband(codec);
        AmrMode best = null;
        var distance = Long.MAX_VALUE;
        for (var mode : VALUES) {
            if (mode.wideband != wide) {
                continue;
            }
            var difference = Math.abs((long) mode.bitrate - bitrate);
            if (difference < distance) {
                best = mode;
                distance = difference;
            }
        }
        return best;
    }

    /**
     * Returns the mode of a codec with the highest bitrate.
     *
     * @param codec {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @return {@link #NB_12_2} or {@link #WB_23_85}
     * @throws IllegalArgumentException if {@code codec} is not an AMR codec
     */
    public static AmrMode highest(Codec codec) {
        return isWideband(codec) ? WB_23_85 : NB_12_2;
    }

    /**
     * Returns the mode of a codec with a storage-format frame type.
     *
     * @param codec {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @param frameType the 4-bit frame type of a speech frame
     * @return the mode
     * @throws IllegalArgumentException if {@code codec} is not an AMR codec or {@code frameType} is not a speech
     * frame type of it
     */
    public static AmrMode ofFrameType(Codec codec, int frameType) {
        var wide = isWideband(codec);
        var count = wide ? 9 : 8;
        if (frameType < 0 || frameType >= count) {
            throw new IllegalArgumentException("frame type " + frameType + " is not a speech mode of " + codec.displayName());
        }
        return VALUES[(wide ? 8 : 0) + frameType];
    }

    /**
     * Tells AMR-WB from AMR-NB.
     *
     * @param codec {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     * @return {@code true} for {@link Codec#AMR_WB}
     * @throws IllegalArgumentException if {@code codec} is not an AMR codec
     */
    private static boolean isWideband(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case AMR_WB -> true;
            case AMR_NB -> false;
            default -> throw new IllegalArgumentException(codec.displayName() + " is not an AMR codec");
        };
    }
}
