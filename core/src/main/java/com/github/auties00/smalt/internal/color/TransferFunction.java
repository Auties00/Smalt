package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.TransferCharacteristics;

/**
 * The transfer characteristics function of ITU-T H.273 Table 3 and its inverse, in double precision.
 *
 * <p>{@link #encode} maps a linear value {@code Lc} (or {@code Lo}), 1 at nominal peak white, to the signal
 * {@code V}; {@link #decode} maps a signal back. For a transfer defined by an electro-optical function (PQ,
 * SMPTE ST 428-1, the assumed display gammas) {@code encode} is the inverse of that function. The constants are
 * those of H.273: the BT.709 family uses {@code alpha = 1.099296826809442} and {@code beta = 0.018053968510807},
 * SMPTE ST 240 the {@code alpha} and {@code beta} that join its segments with continuous value and slope, sRGB
 * the IEC 61966-2-1 values {@code 1.055} and {@code 0.0031308}, and a linear value of 1 is 10000 candelas per
 * square meter for PQ and 48 for SMPTE ST 428-1.
 *
 * <p>Negative values follow the negative segments H.273 defines for IEC 61966-2-4, BT.1361 extended color gamut and
 * sRGB with non-identity matrices; every other transfer is extended to negative values as an odd function, so
 * {@code encode(-x) == -encode(x)}. Values above 1 follow the segment that ends at 1, except that a PQ signal is
 * decoded as at most 1. Below the start of their logarithmic segment, LOG100 and LOG316 encode to 0, and decode
 * maps a signal of 0 to 0 and every positive signal through the logarithmic segment.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation uses the IEC 61966-2-1 sRGB constants rather than the slope-continuous ones the
 * general rule of H.273 section 8.2 would give, so its sRGB values match the rest of the library's sRGB curves.
 */
public final class TransferFunction {
    /**
     * The {@code alpha} of the BT.709 family.
     */
    private static final double BT709_ALPHA = 1.099296826809442;

    /**
     * The {@code beta} of the BT.709 family.
     */
    private static final double BT709_BETA = 0.018053968510807;

    /**
     * The {@code alpha} of SMPTE ST 240, solved for continuity of value and slope with the slope-4 segment.
     */
    private static final double SMPTE240M_ALPHA = 1.111572195921731;

    /**
     * The {@code beta} of SMPTE ST 240.
     */
    private static final double SMPTE240M_BETA = 0.022821585529445;

    /**
     * The PQ constant {@code c1}.
     */
    private static final double PQ_C1 = 107.0 / 128.0;

    /**
     * The PQ constant {@code c2}.
     */
    private static final double PQ_C2 = 2413.0 / 128.0;

    /**
     * The PQ constant {@code c3}.
     */
    private static final double PQ_C3 = 2392.0 / 128.0;

    /**
     * The PQ exponent {@code m}.
     */
    private static final double PQ_M = 2523.0 / 32.0;

    /**
     * The PQ exponent {@code n}.
     */
    private static final double PQ_N = 1305.0 / 8192.0;

    /**
     * The HLG constant {@code a}.
     */
    private static final double HLG_A = 0.17883277;

    /**
     * The HLG constant {@code b}.
     */
    private static final double HLG_B = 0.28466892;

    /**
     * The HLG constant {@code c}.
     */
    private static final double HLG_C = 0.55991073;

    /**
     * The ratio of the SMPTE ST 428-1 code-value peak to its reference white, {@code 52.37 / 48}.
     */
    private static final double DCI_SCALE = 52.37 / 48.0;

    /**
     * The function of every transfer, indexed by ordinal.
     */
    private static final TransferFunction[] BUILT = new TransferFunction[TransferCharacteristics.values().length];

    static {
        for (var transfer : TransferCharacteristics.values()) {
            BUILT[transfer.ordinal()] = new TransferFunction(transfer);
        }
    }

    /**
     * The transfer this function implements, never {@link TransferCharacteristics#UNSPECIFIED}.
     */
    private final TransferCharacteristics transfer;

    /**
     * Constructs a function.
     *
     * @param transfer the transfer, {@link TransferCharacteristics#UNSPECIFIED} meaning BT.709
     */
    private TransferFunction(TransferCharacteristics transfer) {
        this.transfer = transfer == TransferCharacteristics.UNSPECIFIED ? TransferCharacteristics.BT709 : transfer;
    }

    /**
     * Returns the function of a transfer.
     *
     * @param transfer the transfer characteristics; {@link TransferCharacteristics#UNSPECIFIED} gives the BT.709
     * function
     * @return the function
     */
    public static TransferFunction of(TransferCharacteristics transfer) {
        return BUILT[transfer.ordinal()];
    }

    /**
     * {@return the transfer this function implements; {@link TransferCharacteristics#BT709} for an unspecified one}
     */
    public TransferCharacteristics transfer() {
        return transfer;
    }

    /**
     * Maps a linear value to the signal.
     *
     * @param linear the linear value, 1 at nominal peak white
     * @return the signal; NaN for NaN
     */
    public double encode(double linear) {
        if (linear >= 0) {
            return forward(linear);
        }
        return switch (transfer) {
            case BT1361 -> -forward(-4 * linear) / 4;
            default -> -forward(-linear);
        };
    }

    /**
     * Maps a signal to the linear value.
     *
     * @param signal the encoded value, 1 at nominal peak white for most transfers
     * @return the linear value, 1 at nominal peak white; NaN for NaN
     */
    public double decode(double signal) {
        if (signal >= 0) {
            return inverse(signal);
        }
        return switch (transfer) {
            case BT1361 -> -inverse(-4 * signal) / 4;
            default -> -inverse(-signal);
        };
    }

    /**
     * Applies the function to a non-negative linear value.
     *
     * @param l the linear value, at least 0
     * @return the signal
     */
    private double forward(double l) {
        return switch (transfer) {
            case BT709, UNSPECIFIED, SMPTE170M, BT2020_10, BT2020_12, IEC61966_2_4, BT1361 ->
                    l < BT709_BETA ? 4.5 * l : BT709_ALPHA * StrictMath.pow(l, 0.45) - (BT709_ALPHA - 1);
            case GAMMA22 -> StrictMath.pow(l, 1 / 2.2);
            case GAMMA28 -> StrictMath.pow(l, 1 / 2.8);
            case SMPTE240M -> l < SMPTE240M_BETA ? 4.0 * l : SMPTE240M_ALPHA * StrictMath.pow(l, 0.45) - (SMPTE240M_ALPHA - 1);
            case LINEAR -> l;
            case LOG100 -> l < 0.01 ? 0 : 1 + StrictMath.log10(l) / 2;
            case LOG316 -> l < Math.sqrt(10) / 1000 ? 0 : 1 + StrictMath.log10(l) / 2.5;
            case SRGB -> l < 0.0031308 ? 12.92 * l : 1.055 * StrictMath.pow(l, 1 / 2.4) - 0.055;
            case PQ -> {
                var p = StrictMath.pow(l, PQ_N);
                yield StrictMath.pow((PQ_C1 + PQ_C2 * p) / (1 + PQ_C3 * p), PQ_M);
            }
            case SMPTE428 -> StrictMath.pow(l / DCI_SCALE, 1 / 2.6);
            case HLG -> l <= 1.0 / 12 ? Math.sqrt(3 * l) : HLG_A * StrictMath.log(12 * l - HLG_B) + HLG_C;
        };
    }

    /**
     * Inverts the function for a non-negative signal.
     *
     * @param v the signal, at least 0
     * @return the linear value
     */
    private double inverse(double v) {
        return switch (transfer) {
            case BT709, UNSPECIFIED, SMPTE170M, BT2020_10, BT2020_12, IEC61966_2_4, BT1361 ->
                    v < 4.5 * BT709_BETA ? v / 4.5 : StrictMath.pow((v + BT709_ALPHA - 1) / BT709_ALPHA, 1 / 0.45);
            case GAMMA22 -> StrictMath.pow(v, 2.2);
            case GAMMA28 -> StrictMath.pow(v, 2.8);
            case SMPTE240M -> v < 4.0 * SMPTE240M_BETA ? v / 4.0
                    : StrictMath.pow((v + SMPTE240M_ALPHA - 1) / SMPTE240M_ALPHA, 1 / 0.45);
            case LINEAR -> v;
            case LOG100 -> v > 0 ? StrictMath.pow(10, 2 * (v - 1)) : 0;
            case LOG316 -> v > 0 ? StrictMath.pow(10, 2.5 * (v - 1)) : 0;
            case SRGB -> v <= 0.04045 ? v / 12.92 : StrictMath.pow((v + 0.055) / 1.055, 2.4);
            case PQ -> {
                var p = StrictMath.pow(Math.min(v, 1), 1 / PQ_M);
                yield StrictMath.pow(Math.max(p - PQ_C1, 0) / (PQ_C2 - PQ_C3 * p), 1 / PQ_N);
            }
            case SMPTE428 -> DCI_SCALE * StrictMath.pow(v, 2.6);
            case HLG -> v <= 0.5 ? v * v / 3 : (StrictMath.exp((v - HLG_C) / HLG_A) + HLG_B) / 12;
        };
    }
}
