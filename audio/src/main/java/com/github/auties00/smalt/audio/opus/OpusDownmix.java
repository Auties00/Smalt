/* Ports opusfile 0.12 (src/opusfile.c, the OP_STEREO_DOWNMIX matrices). Copyright Xiph.Org Foundation and
   contributors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus;

/**
 * The matrices that mix the coded channels of an Opus stream down to mono or stereo, indexed
 * {@code [output channel][coded channel]}.
 *
 * <ul>
 * <li>Families 0 and 1 (Vorbis order): the stereo matrices of RFC 7845 section 5.1.1.5 for three to eight
 * channels, with the coefficients rounded to three or four decimals; one channel is copied to both outputs
 * and two are kept.</li>
 * <li>Families 2 and 3 (ambisonics, RFC 8486 section 4): left and right are {@code (W + Y) / 2} and
 * {@code (W - Y) / 2}, or with a non-diegetic pair {@code (W + Y) / 4 + Ls / 2} and {@code (W - Y) / 4 + Rs / 2};
 * a count that is not an ambisonic one is mixed as family 255.</li>
 * <li>Family 255 and the reserved families, whose channels have no position: the first two channels are kept
 * and the others dropped; one channel is copied to both outputs.</li>
 * </ul>
 * A mono output is the average of the two stereo rows, except that ambisonics without a non-diegetic pair
 * output {@code W} itself, the omnidirectional signal, and a single channel is kept as it is.
 *
 * @implNote This implementation takes the stereo matrices of families 0 and 1 from opusfile, so that the
 * downmix is bit-identical to its {@code op_read_float_stereo}, except the right row of the quadraphonic
 * matrix, whose rear-right coefficient opusfile 0.12 misprints as 0.336; this one uses 0.366 as the left row
 * and RFC 7845 do.
 */
final class OpusDownmix {
    /**
     * The stereo matrices of three to eight channels in the Vorbis order, indexed {@code [channels - 3][channel]}
     * and holding the left and right coefficients ({@code OP_STEREO_DOWNMIX}).
     */
    private static final float[][][] STEREO = {
            {{0.5858F, 0.0F}, {0.4142F, 0.4142F}, {0.0F, 0.5858F}},
            {{0.4226F, 0.0F}, {0.0F, 0.4226F}, {0.366F, 0.2114F}, {0.2114F, 0.366F}},
            {{0.651F, 0.0F}, {0.46F, 0.46F}, {0.0F, 0.651F}, {0.5636F, 0.3254F}, {0.3254F, 0.5636F}},
            {{0.529F, 0.0F}, {0.3741F, 0.3741F}, {0.0F, 0.529F}, {0.4582F, 0.2645F}, {0.2645F, 0.4582F},
                    {0.3741F, 0.3741F}},
            {{0.4553F, 0.0F}, {0.322F, 0.322F}, {0.0F, 0.4553F}, {0.3943F, 0.2277F}, {0.2277F, 0.3943F},
                    {0.2788F, 0.2788F}, {0.322F, 0.322F}},
            {{0.3886F, 0.0F}, {0.2748F, 0.2748F}, {0.0F, 0.3886F}, {0.3366F, 0.1943F}, {0.1943F, 0.3366F},
                    {0.3366F, 0.1943F}, {0.1943F, 0.3366F}, {0.2748F, 0.2748F}}
    };

    private OpusDownmix() {
    }

    /**
     * Returns the matrix that mixes the coded channels of a stream to a channel count.
     *
     * @param family the channel mapping family
     * @param channels the number of coded (output) channels the stream declares, at least 1
     * @param target the number of output channels, 1 or 2
     * @return the coefficients, indexed {@code [target channel][coded channel]}
     */
    static float[][] matrix(int family, int channels, int target) {
        var stereo = stereo(family, channels);
        if (target == 2) {
            return stereo;
        }
        var mono = new float[1][channels];
        if (channels == 1) {
            mono[0][0] = 1;
            return mono;
        }
        var acn = ambisonicChannels(family, channels);
        if (acn == channels) {
            mono[0][0] = 1;
            return mono;
        }
        for (var c = 0; c < channels; c++) {
            mono[0][c] = 0.5f * (stereo[0][c] + stereo[1][c]);
        }
        return mono;
    }

    /**
     * Returns the stereo matrix of a stream.
     *
     * @param family the channel mapping family
     * @param channels the number of coded channels
     * @return the coefficients, indexed {@code [output channel][coded channel]}
     */
    private static float[][] stereo(int family, int channels) {
        var matrix = new float[2][channels];
        if (channels == 1) {
            matrix[0][0] = 1;
            matrix[1][0] = 1;
            return matrix;
        }
        if ((family == 0 || family == 1) && channels >= 3 && channels <= 8) {
            var table = STEREO[channels - 3];
            for (var c = 0; c < channels; c++) {
                matrix[0][c] = table[c][0];
                matrix[1][c] = table[c][1];
            }
            return matrix;
        }
        var acn = ambisonicChannels(family, channels);
        if (acn > 0) {
            var nonDiegetic = acn != channels;
            var weight = nonDiegetic ? 0.25f : 0.5f;
            matrix[0][0] = weight;
            matrix[1][0] = weight;
            if (acn >= 4) {
                matrix[0][1] = weight;
                matrix[1][1] = -weight;
            }
            if (nonDiegetic) {
                matrix[0][acn] = 0.5f;
                matrix[1][acn + 1] = 0.5f;
            }
            return matrix;
        }
        matrix[0][0] = 1;
        matrix[1][1] = 1;
        return matrix;
    }

    /**
     * Returns the number of ambisonic channels of an ambisonic stream.
     *
     * @param family the channel mapping family
     * @param channels the number of coded channels
     * @return {@code (order + 1)^2} when the family is 2 or 3 and the count is that square, optionally plus two,
     * otherwise 0
     */
    private static int ambisonicChannels(int family, int channels) {
        if (family != 2 && family != 3) {
            return 0;
        }
        var root = (int) Math.sqrt(channels);
        var acn = root * root;
        return channels - acn == 0 || channels - acn == 2 ? acn : 0;
    }
}
