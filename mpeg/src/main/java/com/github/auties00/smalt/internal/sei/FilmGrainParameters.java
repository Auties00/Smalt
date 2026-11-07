package com.github.auties00.smalt.internal.sei;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * A film grain characteristics SEI message (ITU-T H.264 D.1.21, H.265 D.2.13), whose two syntaxes
 * differ only in how the message persists.
 *
 * <p>Instances are immutable once parsed.
 */
public final class FilmGrainParameters implements SeiPersistence.Message {
    /**
     * Whether the message cancels the persistence of the previous one.
     */
    final boolean cancel;

    /**
     * {@code film_grain_model_id}: 0 for frequency filtering, 1 for auto-regression.
     */
    final int modelId;

    /**
     * Whether the message carries its own color description.
     */
    final boolean separateColour;

    /**
     * The bit depth of the luma model, when {@link #separateColour} is set.
     */
    final int bitDepthLuma;

    /**
     * The bit depth of the chroma models, when {@link #separateColour} is set.
     */
    final int bitDepthChroma;

    /**
     * {@code film_grain_full_range_flag}, when {@link #separateColour} is set.
     */
    final boolean fullRange;

    /**
     * {@code film_grain_colour_primaries}, when {@link #separateColour} is set.
     */
    final int colourPrimaries;

    /**
     * {@code film_grain_transfer_characteristics}, when {@link #separateColour} is set.
     */
    final int transferCharacteristics;

    /**
     * {@code film_grain_matrix_coefficients}, when {@link #separateColour} is set.
     */
    final int matrixCoefficients;

    /**
     * {@code blending_mode_id}: 0 for additive, 1 for multiplicative.
     */
    final int blendingMode;

    /**
     * {@code log2_scale_factor}, from 0 to 15.
     */
    final int log2ScaleFactor;

    /**
     * The intensity interval bounds of each component, {@code [c][2 * i]} the lower and
     * {@code [c][2 * i + 1]} the upper bound of interval {@code i}, or {@code null} for a
     * component without a model.
     */
    final int[][] bounds;

    /**
     * The model values of each component, {@code [c][6 * i + j]} for value {@code j} of interval
     * {@code i}, with the values the message leaves out set to their inferred defaults, or
     * {@code null} for a component without a model.
     */
    final int[][] values;

    /**
     * The number of model values each component codes, from 1 to 6, or 0 without a model.
     */
    final int[] valueCount;

    /**
     * Whether the message applies to later pictures in output order too.
     */
    private final boolean persists;

    /**
     * Constructs a parsed message.
     */
    private FilmGrainParameters(boolean cancel, int modelId, boolean separateColour, int bitDepthLuma,
                                int bitDepthChroma, boolean fullRange, int colourPrimaries,
                                int transferCharacteristics, int matrixCoefficients, int blendingMode,
                                int log2ScaleFactor, int[][] bounds, int[][] values, int[] valueCount,
                                boolean persists) {
        this.cancel = cancel;
        this.modelId = modelId;
        this.separateColour = separateColour;
        this.bitDepthLuma = bitDepthLuma;
        this.bitDepthChroma = bitDepthChroma;
        this.fullRange = fullRange;
        this.colourPrimaries = colourPrimaries;
        this.transferCharacteristics = transferCharacteristics;
        this.matrixCoefficients = matrixCoefficients;
        this.blendingMode = blendingMode;
        this.log2ScaleFactor = log2ScaleFactor;
        this.bounds = bounds;
        this.values = values;
        this.valueCount = valueCount;
        this.persists = persists;
    }

    /**
     * Parses a film grain characteristics message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @param hevc whether the message follows the H.265 syntax, which ends with
     * {@code film_grain_characteristics_persistence_flag} instead of the H.264
     * {@code film_grain_characteristics_repetition_period}
     * @return the message, or {@code null} when it is truncated or out of range, or carries a
     * reserved {@code film_grain_model_id} or {@code blending_mode_id}, which the semantics make
     * decoders ignore
     */
    public static FilmGrainParameters parse(H264BitReader reader, boolean hevc) {
        try {
            if (reader.readFlag()) {
                return reader.overrun() ? null : new FilmGrainParameters(true, 0, false, 8, 8, false, 2, 2, 2, 0, 0,
                        new int[3][], new int[3][], new int[3], false);
            }
            var modelId = reader.readBits(2);
            var separateColour = reader.readFlag();
            var bitDepthLuma = 0;
            var bitDepthChroma = 0;
            var fullRange = false;
            var primaries = 2;
            var transfer = 2;
            var matrix = 2;
            if (separateColour) {
                bitDepthLuma = reader.readBits(3) + 8;
                bitDepthChroma = reader.readBits(3) + 8;
                fullRange = reader.readFlag();
                primaries = reader.readBits(8);
                transfer = reader.readBits(8);
                matrix = reader.readBits(8);
            }
            var blendingMode = reader.readBits(2);
            var log2ScaleFactor = reader.readBits(4);
            var present = new boolean[3];
            for (var c = 0; c < 3; c++) {
                present[c] = reader.readFlag();
            }
            var bounds = new int[3][];
            var values = new int[3][];
            var valueCount = new int[3];
            for (var c = 0; c < 3; c++) {
                if (!present[c]) {
                    continue;
                }
                var intervals = reader.readBits(8) + 1;
                var count = reader.readBits(3) + 1;
                if (count > 6) {
                    return null;
                }
                var componentBounds = new int[2 * intervals];
                var componentValues = new int[6 * intervals];
                for (var i = 0; i < intervals; i++) {
                    componentBounds[2 * i] = reader.readBits(8);
                    componentBounds[2 * i + 1] = reader.readBits(8);
                    for (var j = 0; j < count; j++) {
                        componentValues[6 * i + j] = reader.readSe();
                    }
                    inferDefaults(componentValues, 6 * i, count, modelId);
                }
                bounds[c] = componentBounds;
                values[c] = componentValues;
                valueCount[c] = count;
                if (reader.overrun()) {
                    return null;
                }
            }
            boolean persists;
            if (hevc) {
                persists = reader.readFlag();
            } else {
                persists = reader.readUe() != 0;
            }
            // the semantics make decoders ignore the reserved model and blending mode ids, 2 and 3
            if (reader.overrun() || modelId > 1 || blendingMode > 1) {
                return null;
            }
            return new FilmGrainParameters(false, modelId, separateColour, bitDepthLuma, bitDepthChroma, fullRange,
                    primaries, transfer, matrix, blendingMode, log2ScaleFactor, bounds, values, valueCount,
                    persists);
        } catch (MediaException ignored) {
            // an invalid Exp-Golomb code makes the message unusable, not the stream
            return null;
        }
    }

    /**
     * Sets the model values a message leaves out to the values the semantics infer: the second
     * defaults to 8 for frequency filtering and 0 otherwise, the third to the second for frequency
     * filtering and 0 otherwise, the fifth to the model id, the others to 0.
     *
     * @param values the model values of the component
     * @param offset the index of the interval's first value
     * @param count the number of values the message codes
     * @param modelId the {@code film_grain_model_id}
     */
    private static void inferDefaults(int[] values, int offset, int count, int modelId) {
        if (count < 2) {
            values[offset + 1] = modelId == 0 ? 8 : 0;
        }
        if (count < 3) {
            values[offset + 2] = modelId == 0 ? values[offset + 1] : 0;
        }
        if (count < 5) {
            values[offset + 4] = modelId;
        }
    }

    @Override
    public boolean cancel() {
        return cancel;
    }

    @Override
    public boolean persists() {
        return persists;
    }

    /**
     * Returns whether the film grain synthesis can render this message on pictures of the given
     * bit depths: a frequency filtering model with additive blending, whose color description,
     * when it carries one, has the pictures' bit depths and their sequence's color description.
     *
     * @param bitDepthLuma the luma bit depth of the pictures
     * @param bitDepthChroma the chroma bit depth of the pictures
     * @param videoFullRange the {@code video_full_range_flag} of the sequence
     * @param colourDescription whether the sequence signals its color description
     * @param primaries the sequence's {@code colour_primaries}
     * @param transfer the sequence's {@code transfer_characteristics}
     * @param matrix the sequence's {@code matrix_coefficients}
     * @return {@code true} when {@link FilmGrainSynthesizer} renders the message
     */
    public boolean renderable(int bitDepthLuma, int bitDepthChroma, boolean videoFullRange, boolean colourDescription,
                              int primaries, int transfer, int matrix) {
        if (cancel || modelId != 0 || blendingMode != 0) {
            return false;
        }
        if (!separateColour) {
            return true;
        }
        return this.bitDepthLuma == bitDepthLuma && this.bitDepthChroma == bitDepthChroma
                && fullRange == videoFullRange && (!colourDescription || colourPrimaries == primaries
                && transferCharacteristics == transfer && matrixCoefficients == matrix);
    }

    /**
     * {@return whether the message models grain on a component}
     *
     * @param component the component, 0 for luma, 1 for Cb and 2 for Cr
     */
    public boolean models(int component) {
        return values[component] != null;
    }
}
