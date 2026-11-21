package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.Av1FilmGrain;
import com.github.auties00.smalt.video.av1.internal.FilmGrainData;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.Arrays;

/**
 * Converts film grain parameters to the decoder's model and writes them in a frame header
 * ({@code film_grain_params()}, AV1 specification section 5.9.30).
 */
public final class FilmGrains {
    /**
     * The increment of the random seed from one shown or showable frame to the next.
     */
    private static final int SEED_STEP = 3381;

    /**
     * The seed that replaces a seed of zero.
     */
    private static final int SEED_RESTART = 7391;

    /**
     * Prevents instantiation.
     */
    private FilmGrains() {
        throw new AssertionError();
    }

    /**
     * Returns the seed of the frame after a frame with a seed ({@code random_seed += 3381}).
     *
     * @param seed the seed of a frame
     * @return the seed of the next shown or showable frame
     */
    public static int nextSeed(int seed) {
        var next = (seed + SEED_STEP) & 0xFFFF;
        return next == 0 ? SEED_RESTART : next;
    }

    /**
     * Converts parameters to the decoder's model as a decoder parses them from a stream of a
     * sequence: the fields the syntax does not code for the sequence are zero.
     *
     * @param grain the parameters
     * @param seq the sequence header
     * @return the model, or {@code null} when the parameters add no grain to the sequence's planes
     * @throws IllegalArgumentException if the parameters cannot be coded in the sequence: in 4:2:0
     * one chroma plane has points and the other none, or there are chroma points but no luma
     * points
     */
    public static FilmGrainData toData(Av1FilmGrain grain, SequenceHeader seq) {
        var d = new FilmGrainData();
        d.seed = grain.randomSeed();
        var luma = grain.lumaPoints();
        d.numYPoints = luma.size();
        for (var i = 0; i < luma.size(); i++) {
            d.yPoints[i][0] = luma.get(i).value();
            d.yPoints[i][1] = luma.get(i).scaling();
        }
        var subsampled = seq.ssHor == 1 && seq.ssVer == 1;
        if (!seq.monochrome) {
            d.chromaScalingFromLuma = grain.chromaScalingFromLuma();
            if (!d.chromaScalingFromLuma && !(subsampled && d.numYPoints == 0)) {
                var cb = grain.cbPoints();
                var cr = grain.crPoints();
                for (var pl = 0; pl < 2; pl++) {
                    var points = pl == 0 ? cb : cr;
                    d.numUvPoints[pl] = points.size();
                    for (var i = 0; i < points.size(); i++) {
                        d.uvPoints[pl][i][0] = points.get(i).value();
                        d.uvPoints[pl][i][1] = points.get(i).scaling();
                    }
                }
            } else if (!d.chromaScalingFromLuma && (!grain.cbPoints().isEmpty() || !grain.crPoints().isEmpty())) {
                throw new IllegalArgumentException("4:2:0 film grain with chroma points needs luma points");
            }
        }
        if (subsampled && (d.numUvPoints[0] != 0) != (d.numUvPoints[1] != 0)) {
            throw new IllegalArgumentException("4:2:0 film grain needs points for both chroma planes or neither");
        }
        d.scalingShift = grain.scalingShift();
        d.arCoeffLag = grain.arCoefficientLag();
        var n = 2 * d.arCoeffLag * (d.arCoeffLag + 1);
        if (d.numYPoints != 0) {
            System.arraycopy(grain.lumaArCoefficients(), 0, d.arCoeffsY, 0, n);
        }
        for (var pl = 0; pl < 2; pl++) {
            if (d.numUvPoints[pl] != 0 || d.chromaScalingFromLuma) {
                var coefficients = pl == 0 ? grain.cbArCoefficients() : grain.crArCoefficients();
                System.arraycopy(coefficients, 0, d.arCoeffsUv[pl], 0, n + (d.numYPoints != 0 ? 1 : 0));
            }
        }
        d.arCoeffShift = grain.arCoefficientShift();
        d.grainScaleShift = grain.grainScaleShift();
        if (d.numUvPoints[0] != 0) {
            d.uvMult[0] = grain.cbMultiplier() - 128;
            d.uvLumaMult[0] = grain.cbLumaMultiplier() - 128;
            d.uvOffset[0] = grain.cbOffset() - 256;
        }
        if (d.numUvPoints[1] != 0) {
            d.uvMult[1] = grain.crMultiplier() - 128;
            d.uvLumaMult[1] = grain.crLumaMultiplier() - 128;
            d.uvOffset[1] = grain.crOffset() - 256;
        }
        d.overlapFlag = grain.overlap();
        d.clipToRestrictedRange = grain.clipToRestrictedRange();
        var adds = d.numYPoints != 0 || d.numUvPoints[0] != 0 || d.numUvPoints[1] != 0 || d.chromaScalingFromLuma;
        return adds ? d : null;
    }

    /**
     * Returns whether two models hold the same parameters, the seeds aside.
     *
     * @param a one model
     * @param b the other model
     * @return {@code true} when every field but the seed is equal
     */
    static boolean sameParameters(FilmGrainData a, FilmGrainData b) {
        if (a.numYPoints != b.numYPoints || a.chromaScalingFromLuma != b.chromaScalingFromLuma
                || a.scalingShift != b.scalingShift || a.arCoeffLag != b.arCoeffLag
                || a.arCoeffShift != b.arCoeffShift || a.grainScaleShift != b.grainScaleShift
                || a.overlapFlag != b.overlapFlag || a.clipToRestrictedRange != b.clipToRestrictedRange
                || !Arrays.equals(a.arCoeffsY, b.arCoeffsY) || !Arrays.equals(a.numUvPoints, b.numUvPoints)
                || !Arrays.equals(a.uvMult, b.uvMult) || !Arrays.equals(a.uvLumaMult, b.uvLumaMult)
                || !Arrays.equals(a.uvOffset, b.uvOffset)) {
            return false;
        }
        for (var i = 0; i < a.yPoints.length; i++) {
            if (!Arrays.equals(a.yPoints[i], b.yPoints[i])) {
                return false;
            }
        }
        for (var pl = 0; pl < 2; pl++) {
            if (!Arrays.equals(a.arCoeffsUv[pl], b.arCoeffsUv[pl])) {
                return false;
            }
            for (var i = 0; i < a.uvPoints[pl].length; i++) {
                if (!Arrays.equals(a.uvPoints[pl][i], b.uvPoints[pl][i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the reference slot an inter frame can load its film grain parameters from instead of
     * coding them ({@code film_grain_params_ref_idx}): a slot one of the frame's references points
     * to whose frame signals the same parameters.
     *
     * @param hdr the frame header, with its references and film grain set
     * @param refs the frame header of each reference slot, entries may be {@code null}
     * @return the slot, or -1 when the parameters must be coded
     */
    static int referenceSlot(FrameHeader hdr, FrameHeader[] refs) {
        if (hdr.frameType != FrameHeader.INTER || refs == null) {
            return -1;
        }
        for (var i = 0; i < 7; i++) {
            var slot = hdr.refidx[i];
            var ref = refs[slot];
            if (ref != null && ref.filmGrainPresent && sameParameters(ref.filmGrain, hdr.filmGrain)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Writes the film grain parameters of a shown or showable frame of a sequence that signals
     * film grain.
     *
     * @param w the writer
     * @param seq the sequence header
     * @param hdr the frame header: whether grain applies, the parameters and, for an inter frame,
     * whether they are coded ({@link FrameHeader#filmGrainUpdate}) or loaded from a reference
     * @param refs the frame header of each reference slot, for the slot a frame loads from
     */
    static void write(HeaderWriter w, SequenceHeader seq, FrameHeader hdr, FrameHeader[] refs) {
        w.flag(hdr.filmGrainPresent);
        if (!hdr.filmGrainPresent) {
            return;
        }
        var d = hdr.filmGrain;
        w.bits(d.seed, 16);
        if (hdr.frameType == FrameHeader.INTER) {
            w.flag(hdr.filmGrainUpdate);
            if (!hdr.filmGrainUpdate) {
                w.bits(referenceSlot(hdr, refs), 3);
                return;
            }
        }
        w.bits(d.numYPoints, 4);
        for (var i = 0; i < d.numYPoints; i++) {
            w.bits(d.yPoints[i][0], 8);
            w.bits(d.yPoints[i][1], 8);
        }
        if (!seq.monochrome) {
            w.flag(d.chromaScalingFromLuma);
        }
        if (!(seq.monochrome || d.chromaScalingFromLuma || (seq.ssVer == 1 && seq.ssHor == 1 && d.numYPoints == 0))) {
            for (var pl = 0; pl < 2; pl++) {
                w.bits(d.numUvPoints[pl], 4);
                for (var i = 0; i < d.numUvPoints[pl]; i++) {
                    w.bits(d.uvPoints[pl][i][0], 8);
                    w.bits(d.uvPoints[pl][i][1], 8);
                }
            }
        }
        w.bits(d.scalingShift - 8, 2);
        w.bits(d.arCoeffLag, 2);
        var numYPos = 2 * d.arCoeffLag * (d.arCoeffLag + 1);
        if (d.numYPoints != 0) {
            for (var i = 0; i < numYPos; i++) {
                w.bits(d.arCoeffsY[i] + 128, 8);
            }
        }
        for (var pl = 0; pl < 2; pl++) {
            if (d.numUvPoints[pl] != 0 || d.chromaScalingFromLuma) {
                var numUvPos = numYPos + (d.numYPoints != 0 ? 1 : 0);
                for (var i = 0; i < numUvPos; i++) {
                    w.bits(d.arCoeffsUv[pl][i] + 128, 8);
                }
            }
        }
        w.bits(d.arCoeffShift - 6, 2);
        w.bits(d.grainScaleShift, 2);
        for (var pl = 0; pl < 2; pl++) {
            if (d.numUvPoints[pl] != 0) {
                w.bits(d.uvMult[pl] + 128, 8);
                w.bits(d.uvLumaMult[pl] + 128, 8);
                w.bits(d.uvOffset[pl] + 256, 9);
            }
        }
        w.flag(d.overlapFlag);
        w.flag(d.clipToRestrictedRange);
    }
}
