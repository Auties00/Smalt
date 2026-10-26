package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.List;
import java.util.Objects;

/**
 * Converts planar float audio between two {@link ChannelLayout}s with a fixed coefficient matrix.
 *
 * <p>Output channel {@code o} is {@code sum(matrix[o][i] * input[i])}. The matrix is built once
 * from the two layouts by these rules, which follow the ITU-R BS.775 downmix equations:
 * <ul>
 *   <li>equal layouts give the identity;</li>
 *   <li>an input speaker that the output also has goes there with gain {@code 1};</li>
 *   <li>a mono input (one channel feeding {@link Speaker#FRONT_CENTER} or
 *       {@link Speaker#UNKNOWN}) is copied at gain {@code 1} to the output center, or else to both
 *       front speakers (mono to stereo duplicates the signal), or else to output channel 0;</li>
 *   <li>a mono output is the average of the stereo downmix, so stereo to mono is
 *       {@code 0.5 * L + 0.5 * R} and 5.1 to mono is {@code 0.5 L + 0.5 R + 0.707 C + 0.354 BL
 *       + 0.354 BR}, the BS.775 mono equation scaled by -3 dB so a correlated stereo signal
 *       cannot clip;</li>
 *   <li>a missing center goes to front left and right at -3 dB ({@code sqrt(1/2)}); missing front
 *       left or right go to the center at -3 dB; front left and right of center go to front left
 *       and right at gain {@code 1};</li>
 *   <li>a missing back surround is replaced by the side surround of the same side, and a missing
 *       side surround by the back one, at gain {@code 1} when the input lacks that counterpart
 *       (a pure relabeling such as 5.1 back to 5.1 side) and at -3 dB when the input has both
 *       (7.1 to 5.1 folds the second pair into the first); with neither available a surround goes
 *       to the front speaker of its side at -3 dB;</li>
 *   <li>a missing back center is split at -3 dB each into the back pair when the output has
 *       it, else into the side pair when the output has that, else into back left and right,
 *       which then fold into the front speakers at -3 dB (a total of -6 dB per side);</li>
 *   <li>the front wide pair goes to front left and right at gain {@code 1}, and the surround
 *       direct pair to the side surrounds, at gain {@code 1} when the input lacks side surrounds
 *       and at -3 dB when it has them;</li>
 *   <li>the low-frequency channel is dropped unless the output has one (or a low-frequency mix level
 *       is set, see below); the second low-frequency
 *       channel goes to it, at gain {@code 1} when the input lacks the first and at -3 dB when it
 *       has it;</li>
 *   <li>top speakers fold into the speaker below them at -3 dB, and bottom speakers into the
 *       speaker above them at -3 dB;</li>
 *   <li>{@link Speaker#UNKNOWN} channels are mapped by index, input channel {@code i} to output
 *       channel {@code i}; when every speaker of either layout is unknown, all channels are mapped
 *       by index.</li>
 * </ul>
 *
 * <p>The -3 dB gains of the rules above are fixed, except three that {@link ChannelMixerOptions} sets:
 * the center folded into front left and right ({@link ChannelMixerOptions#centerMixLevel()}), the last
 * fold of a surround into the front speaker of its side ({@link ChannelMixerOptions#surroundMixLevel()})
 * and the low-frequency channel, dropped by default ({@link ChannelMixerOptions#lfeMixLevel()}). With
 * those levels at {@code clev} and {@code slev}, 5.1 to stereo is the ATSC A/52 Lo/Ro downmix
 * {@code L + clev C + slev BL} and {@code R + clev C + slev BR}. Under
 * {@link ChannelMixerOptions.StereoDownmix#LT_RT} a stereo output is the A/52 Lt/Rt downmix instead,
 * with its own levels {@link ChannelMixerOptions#ltrtCenterMixLevel()} ({@code ltrtclev}) and
 * {@link ChannelMixerOptions#ltrtSurroundMixLevel()} ({@code ltrtslev}), both -3 dB by default: the
 * input is first folded by the rules into front left, right and center, a back pair and a back
 * center, the surround signal {@code S} is their sum {@code BL + BR + BC}, and then
 * {@snippet :
 * Lt = L + ltrtclev * C - ltrtslev * S
 * Rt = R + ltrtclev * C + ltrtslev * S
 * }
 * A caller's matrix ({@link ChannelMixerOptions#matrix()}) replaces the rules altogether.
 *
 * <p>By default the matrix is not normalized: a downmix such as 5.1 to stereo sums up to
 * {@code 1 + 0.707 + 0.707} into each output and can exceed full scale, so the caller clips (or
 * encoders clip, see {@link com.github.auties00.smalt.audio.AudioEncoder}). With
 * normalization every output row whose absolute coefficients sum to more than one is divided by
 * that sum, which rules out clipping at the cost of a quieter mix.
 *
 * <p>A mixer is immutable apart from scratch arrays used during {@link #process(float[][],
 * float[][], int)}, so an instance must not be used by two threads at once.
 */
public final class ChannelMixer {
    /**
     * The -3 dB gain, {@code sqrt(1/2)}.
     */
    private static final double MINUS_3DB = Math.sqrt(0.5);

    /**
     * The deepest chain of folds followed when routing a speaker the output lacks.
     */
    private static final int MAX_FOLDS = 6;

    /**
     * The layout an input is folded into before the Lt/Rt matrix: FL, FR, FC, BL, BR, BC.
     */
    private static final ChannelLayout LT_RT_FOLD = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER));

    /**
     * The mixing kernel selected for this JVM.
     */
    private static final MixKernel KERNEL = MixKernel.SELECTED;

    /**
     * The input layout.
     */
    private final ChannelLayout input;

    /**
     * The output layout.
     */
    private final ChannelLayout output;

    /**
     * The coefficients, indexed {@code [output][input]}.
     */
    private final float[][] matrix;

    /**
     * Whether the matrix is the identity.
     */
    private final boolean identity;

    /**
     * The input channels with a non-zero coefficient, per output channel.
     */
    private final int[][] terms;

    /**
     * The non-zero coefficients, per output channel, parallel to {@link #terms}.
     */
    private final float[][] gains;

    /**
     * Scratch arrays holding the input planes of each output row during a call.
     */
    private final float[][][] planes;

    /**
     * Creates a mixer without normalization.
     *
     * @param input  the layout of the input planes
     * @param output the layout of the output planes
     * @throws NullPointerException if a layout is {@code null}
     */
    public ChannelMixer(ChannelLayout input, ChannelLayout output) {
        this(input, output, false);
    }

    /**
     * Creates a mixer.
     *
     * @param input     the layout of the input planes
     * @param output    the layout of the output planes
     * @param normalize whether each output row is scaled so its absolute coefficients sum to at
     *                  most one
     * @throws NullPointerException if a layout is {@code null}
     */
    public ChannelMixer(ChannelLayout input, ChannelLayout output, boolean normalize) {
        this(input, output, ChannelMixerOptions.builder().normalize(normalize).build());
    }

    /**
     * Creates a mixer with the given settings.
     *
     * @param input the layout of the input planes
     * @param output the layout of the output planes
     * @param options the mix levels, stereo downmix, normalization or caller's matrix
     * @throws IllegalArgumentException if the options hold a matrix whose dimensions are not
     * {@code output.channels()} rows of {@code input.channels()} coefficients
     */
    public ChannelMixer(ChannelLayout input, ChannelLayout output, ChannelMixerOptions options) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var custom = options.matrixOrNull();
        double[][] coefficients;
        if (custom != null) {
            if (custom.length != output.channels() || custom[0].length != input.channels()) {
                throw new IllegalArgumentException("a " + custom.length + "x" + custom[0].length
                        + " mix matrix cannot mix " + input.channels() + " into " + output.channels() + " channels");
            }
            coefficients = new double[custom.length][];
            for (var row = 0; row < custom.length; row++) {
                coefficients[row] = custom[row].clone();
            }
        } else if (options.stereoDownmix() == ChannelMixerOptions.StereoDownmix.LT_RT
                && output.equals(ChannelLayout.STEREO) && !isMono(input)) {
            coefficients = matrixEncoded(input, options);
        } else {
            coefficients = build(input, output, options);
        }
        if (options.normalize()) {
            for (var row : coefficients) {
                var sum = 0.0;
                for (var value : row) {
                    sum += Math.abs(value);
                }
                if (sum > 1) {
                    for (var i = 0; i < row.length; i++) {
                        row[i] /= sum;
                    }
                }
            }
        }
        var outputs = output.channels();
        var inputs = input.channels();
        this.matrix = new float[outputs][inputs];
        this.terms = new int[outputs][];
        this.gains = new float[outputs][];
        this.planes = new float[outputs][][];
        var isIdentity = outputs == inputs;
        for (var o = 0; o < outputs; o++) {
            var count = 0;
            for (var i = 0; i < inputs; i++) {
                matrix[o][i] = (float) coefficients[o][i];
                if (matrix[o][i] != 0) {
                    count++;
                }
                if (matrix[o][i] != (o == i ? 1.0f : 0.0f)) {
                    isIdentity = false;
                }
            }
            terms[o] = new int[count];
            gains[o] = new float[count];
            planes[o] = new float[count][];
            var index = 0;
            for (var i = 0; i < inputs; i++) {
                if (matrix[o][i] != 0) {
                    terms[o][index] = i;
                    gains[o][index++] = matrix[o][i];
                }
            }
        }
        this.identity = isIdentity;
    }

    /**
     * Builds the unnormalized Lt/Rt matrix of a stereo output by the rules on the class.
     *
     * <p>The low-frequency channels are mixed as in the left-only/right-only downmix.
     *
     * @param input the input layout
     * @param options the mix levels
     * @return the coefficients, indexed {@code [output][input]}
     */
    private static double[][] matrixEncoded(ChannelLayout input, ChannelMixerOptions options) {
        if (allUnknown(input)) {
            return build(input, ChannelLayout.STEREO, options);
        }
        var folded = build(input, LT_RT_FOLD, options);
        var loRo = build(input, ChannelLayout.STEREO, options);
        var center = options.ltrtCenterMixLevel();
        var surround = options.ltrtSurroundMixLevel();
        var result = new double[2][input.channels()];
        for (var i = 0; i < input.channels(); i++) {
            var speaker = input.speakers().get(i);
            if (speaker == Speaker.LOW_FREQUENCY || speaker == Speaker.LOW_FREQUENCY_2) {
                result[0][i] = loRo[0][i];
                result[1][i] = loRo[1][i];
                continue;
            }
            var mono = folded[3][i] + folded[4][i] + folded[5][i];
            result[0][i] = folded[0][i] + center * folded[2][i] - surround * mono;
            result[1][i] = folded[1][i] + center * folded[2][i] + surround * mono;
        }
        return result;
    }

    /**
     * Builds the unnormalized coefficient matrix by the rules on the class.
     *
     * @param input the input layout
     * @param output the output layout
     * @param options the mix levels
     * @return the coefficients, indexed {@code [output][input]}
     */
    private static double[][] build(ChannelLayout input, ChannelLayout output, ChannelMixerOptions options) {
        var outputs = output.channels();
        var inputs = input.channels();
        var result = new double[outputs][inputs];
        if (input.equals(output)) {
            for (var i = 0; i < inputs; i++) {
                result[i][i] = 1;
            }
            return result;
        }
        if (isMono(input)) {
            var center = output.indexOf(Speaker.FRONT_CENTER);
            var left = output.indexOf(Speaker.FRONT_LEFT);
            var right = output.indexOf(Speaker.FRONT_RIGHT);
            if (center >= 0) {
                result[center][0] = 1;
            } else if (left >= 0 && right >= 0) {
                result[left][0] = 1;
                result[right][0] = 1;
            } else {
                result[0][0] = 1;
            }
            return result;
        }
        if (isMono(output)) {
            var stereo = build(input, ChannelLayout.STEREO, options);
            for (var i = 0; i < inputs; i++) {
                result[0][i] = 0.5 * (stereo[0][i] + stereo[1][i]);
            }
            return result;
        }
        if (allUnknown(input) || allUnknown(output)) {
            for (var i = 0; i < Math.min(inputs, outputs); i++) {
                result[i][i] = 1;
            }
            return result;
        }
        for (var i = 0; i < inputs; i++) {
            var speaker = input.speakers().get(i);
            if (speaker == Speaker.UNKNOWN) {
                if (i < outputs) {
                    result[i][i] += 1;
                }
                continue;
            }
            var column = new double[outputs];
            if (!route(speaker, 1.0, input, output, options, column, 0) && i < outputs
                    && output.speakers().get(i) == Speaker.UNKNOWN) {
                column[i] = 1;
            }
            for (var o = 0; o < outputs; o++) {
                result[o][i] += column[o];
            }
        }
        return result;
    }

    /**
     * Routes one input speaker to the output speakers, folding it by the rules on the class
     * until every contribution lands on a speaker the output has.
     *
     * @param speaker the speaker to place
     * @param gain    the gain accumulated so far
     * @param input   the input layout
     * @param output  the output layout
     * @param options the mix levels
     * @param column  accumulates the contribution to each output channel
     * @param depth   the number of folds already applied
     * @return whether any contribution reached the output
     */
    private static boolean route(Speaker speaker, double gain, ChannelLayout input, ChannelLayout output,
                                 ChannelMixerOptions options, double[] column, int depth) {
        var index = output.indexOf(speaker);
        if (index >= 0) {
            column[index] += gain;
            return true;
        }
        if (depth >= MAX_FOLDS) {
            return false;
        }
        var next = depth + 1;
        var folded = gain * MINUS_3DB;
        return switch (speaker) {
            case FRONT_CENTER -> {
                var center = gain * options.centerMixLevel();
                yield route(Speaker.FRONT_LEFT, center, input, output, options, column, next)
                        | route(Speaker.FRONT_RIGHT, center, input, output, options, column, next);
            }
            case FRONT_LEFT, FRONT_RIGHT -> route(Speaker.FRONT_CENTER, folded, input, output, options, column, next);
            case FRONT_LEFT_OF_CENTER -> route(Speaker.FRONT_LEFT, gain, input, output, options, column, next);
            case FRONT_RIGHT_OF_CENTER -> route(Speaker.FRONT_RIGHT, gain, input, output, options, column, next);
            case BACK_LEFT -> surround(Speaker.SIDE_LEFT, Speaker.FRONT_LEFT, gain, input, output, options, column,
                    next);
            case BACK_RIGHT -> surround(Speaker.SIDE_RIGHT, Speaker.FRONT_RIGHT, gain, input, output, options, column,
                    next);
            case SIDE_LEFT -> surround(Speaker.BACK_LEFT, Speaker.FRONT_LEFT, gain, input, output, options, column,
                    next);
            case SIDE_RIGHT -> surround(Speaker.BACK_RIGHT, Speaker.FRONT_RIGHT, gain, input, output, options, column,
                    next);
            case BACK_CENTER -> {
                var sides = output.indexOf(Speaker.BACK_LEFT) < 0 || output.indexOf(Speaker.BACK_RIGHT) < 0;
                var useSides = sides && output.indexOf(Speaker.SIDE_LEFT) >= 0 && output.indexOf(Speaker.SIDE_RIGHT) >= 0;
                yield useSides
                        ? route(Speaker.SIDE_LEFT, folded, input, output, options, column, next)
                        | route(Speaker.SIDE_RIGHT, folded, input, output, options, column, next)
                        : route(Speaker.BACK_LEFT, folded, input, output, options, column, next)
                        | route(Speaker.BACK_RIGHT, folded, input, output, options, column, next);
            }
            case TOP_CENTER, TOP_FRONT_CENTER -> route(Speaker.FRONT_CENTER, folded, input, output, options, column,
                    next);
            case TOP_FRONT_LEFT -> route(Speaker.FRONT_LEFT, folded, input, output, options, column, next);
            case TOP_FRONT_RIGHT -> route(Speaker.FRONT_RIGHT, folded, input, output, options, column, next);
            case TOP_BACK_LEFT -> route(Speaker.BACK_LEFT, folded, input, output, options, column, next);
            case TOP_BACK_RIGHT -> route(Speaker.BACK_RIGHT, folded, input, output, options, column, next);
            case TOP_BACK_CENTER -> route(Speaker.BACK_CENTER, folded, input, output, options, column, next);
            case TOP_SIDE_LEFT -> route(Speaker.SIDE_LEFT, folded, input, output, options, column, next);
            case TOP_SIDE_RIGHT -> route(Speaker.SIDE_RIGHT, folded, input, output, options, column, next);
            case BOTTOM_FRONT_CENTER -> route(Speaker.FRONT_CENTER, folded, input, output, options, column, next);
            case BOTTOM_FRONT_LEFT -> route(Speaker.FRONT_LEFT, folded, input, output, options, column, next);
            case BOTTOM_FRONT_RIGHT -> route(Speaker.FRONT_RIGHT, folded, input, output, options, column, next);
            case WIDE_LEFT -> route(Speaker.FRONT_LEFT, gain, input, output, options, column, next);
            case WIDE_RIGHT -> route(Speaker.FRONT_RIGHT, gain, input, output, options, column, next);
            case SURROUND_DIRECT_LEFT -> route(Speaker.SIDE_LEFT, input.indexOf(Speaker.SIDE_LEFT) >= 0 ? folded : gain,
                    input, output, options, column, next);
            case SURROUND_DIRECT_RIGHT -> route(Speaker.SIDE_RIGHT,
                    input.indexOf(Speaker.SIDE_RIGHT) >= 0 ? folded : gain, input, output, options, column, next);
            case LOW_FREQUENCY_2 -> route(Speaker.LOW_FREQUENCY,
                    input.indexOf(Speaker.LOW_FREQUENCY) >= 0 ? folded : gain, input, output, options, column, next);
            case LOW_FREQUENCY -> lowFrequency(gain * options.lfeMixLevel(), output, column);
            case UNKNOWN -> false;
        };
    }

    /**
     * Routes a surround speaker the output lacks: to its counterpart surround when the output has
     * it, otherwise to the front speaker of its side at -3 dB.
     *
     * @param counterpart the surround on the same side (side for back, back for side)
     * @param front       the front speaker on the same side
     * @param gain        the gain accumulated so far
     * @param input       the input layout
     * @param output      the output layout
     * @param options     the mix levels
     * @param column      accumulates the contribution to each output channel
     * @param depth       the number of folds already applied
     * @return whether any contribution reached the output
     */
    private static boolean surround(Speaker counterpart, Speaker front, double gain, ChannelLayout input,
                                    ChannelLayout output, ChannelMixerOptions options, double[] column, int depth) {
        var index = output.indexOf(counterpart);
        if (index >= 0) {
            column[index] += input.indexOf(counterpart) >= 0 ? gain * MINUS_3DB : gain;
            return true;
        }
        return route(front, gain * options.surroundMixLevel(), input, output, options, column, depth);
    }

    /**
     * Routes the low-frequency channel of an output that lacks one: to the center, or else to front
     * left and right at -3 dB.
     *
     * @param gain the gain accumulated so far times the low-frequency mix level
     * @param output the output layout
     * @param column accumulates the contribution to each output channel
     * @return whether any contribution reached the output
     */
    private static boolean lowFrequency(double gain, ChannelLayout output, double[] column) {
        if (gain == 0) {
            return false;
        }
        var center = output.indexOf(Speaker.FRONT_CENTER);
        if (center >= 0) {
            column[center] += gain;
            return true;
        }
        var left = output.indexOf(Speaker.FRONT_LEFT);
        var right = output.indexOf(Speaker.FRONT_RIGHT);
        if (left < 0 || right < 0) {
            return false;
        }
        column[left] += gain * MINUS_3DB;
        column[right] += gain * MINUS_3DB;
        return true;
    }

    /**
     * Returns whether a layout is a single channel feeding the center or no declared position.
     *
     * @param layout the speakers to test
     * @return {@code true} for mono
     */
    private static boolean isMono(ChannelLayout layout) {
        if (layout.channels() != 1) {
            return false;
        }
        var speaker = layout.speakers().getFirst();
        return speaker == Speaker.FRONT_CENTER || speaker == Speaker.UNKNOWN;
    }

    /**
     * Returns whether no speaker of a layout has a declared position.
     *
     * @param layout the speakers to test
     * @return {@code true} when every speaker is {@link Speaker#UNKNOWN}
     */
    private static boolean allUnknown(ChannelLayout layout) {
        for (var speaker : layout.speakers()) {
            if (speaker != Speaker.UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the input layout.
     *
     * @return the layout of the planes {@link #process(float[][], float[][], int)} reads
     */
    public ChannelLayout input() {
        return input;
    }

    /**
     * Returns the output layout.
     *
     * @return the layout of the planes {@link #process(float[][], float[][], int)} writes
     */
    public ChannelLayout output() {
        return output;
    }

    /**
     * Returns a copy of the coefficient matrix.
     *
     * @return the coefficients, indexed {@code [output channel][input channel]}
     */
    public float[][] matrix() {
        var copy = new float[matrix.length][];
        for (var o = 0; o < matrix.length; o++) {
            copy[o] = matrix[o].clone();
        }
        return copy;
    }

    /**
     * Mixes {@code frames} samples of every input plane into the output planes.
     *
     * <p>Output planes must be distinct from the input planes, except for an identity mixer,
     * which copies each plane and accepts an output plane that is the same array as its input.
     *
     * @param in     the input planes, one per input channel, each holding at least
     *               {@code frames} samples
     * @param out    the output planes, one per output channel, each holding at least
     *               {@code frames} samples
     * @param frames the number of samples per plane; non-negative
     * @throws NullPointerException     if an array is {@code null}
     * @throws IllegalArgumentException if a plane count differs from its layout, a plane is too
     *                                  short, {@code frames} is negative, or an output plane
     *                                  aliases an input plane of a non-identity mixer
     */
    public void process(float[][] in, float[][] out, int frames) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        if (in.length != input.channels() || out.length != output.channels()) {
            throw new IllegalArgumentException("expected " + input.channels() + " input and " + output.channels()
                    + " output planes, got " + in.length + " and " + out.length);
        }
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count " + frames);
        }
        for (var plane : in) {
            if (Objects.requireNonNull(plane, "input plane").length < frames) {
                throw new IllegalArgumentException("input plane shorter than " + frames + " frames");
            }
        }
        for (var plane : out) {
            if (Objects.requireNonNull(plane, "output plane").length < frames) {
                throw new IllegalArgumentException("output plane shorter than " + frames + " frames");
            }
            if (!identity) {
                for (var source : in) {
                    if (source == plane) {
                        throw new IllegalArgumentException("output plane aliases an input plane");
                    }
                }
            }
        }
        for (var o = 0; o < out.length; o++) {
            var target = out[o];
            var rowTerms = terms[o];
            var count = rowTerms.length;
            if (count == 0) {
                java.util.Arrays.fill(target, 0, frames, 0.0f);
            } else if (count == 1 && gains[o][0] == 1.0f) {
                if (in[rowTerms[0]] != target) {
                    System.arraycopy(in[rowTerms[0]], 0, target, 0, frames);
                }
            } else {
                var rowPlanes = planes[o];
                for (var j = 0; j < count; j++) {
                    rowPlanes[j] = in[rowTerms[j]];
                }
                KERNEL.mix(rowPlanes, gains[o], count, target, frames);
                java.util.Arrays.fill(rowPlanes, null);
            }
        }
    }
}
