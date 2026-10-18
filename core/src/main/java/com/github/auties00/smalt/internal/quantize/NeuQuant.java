/* Ports NeuQuant (neuquant.c, as carried by gif.js src/NeuQuant.js). Copyright Anthony Dekker; permissive notice
 * reproduced in THIRD-PARTY-NOTICES. Modified for Smalt. */
package com.github.auties00.smalt.internal.quantize;

/**
 * Reduces a list of colors to a palette with the NeuQuant neural-net quantization algorithm.
 *
 * <p>A one-dimensional self-organizing map of {@code size} neurons, initialized along the gray
 * axis, is trained on a pseudo-random sample of the colors: each sample moves the neuron that
 * wins a biased competition (which favors rarely chosen neurons) and its neighbors towards it,
 * with a learning rate and a neighborhood radius that shrink over 100 cycles. The trained neurons
 * are the palette. The sampling factor trades quality for speed: 1 trains on every color, 10 on
 * every tenth, 30 on every thirtieth.
 *
 * <p>The training is deterministic: the same colors, size and factor give the same palette.
 *
 * @implNote This implementation ports {@code initnet}, {@code contest}, {@code altersingle}, {@code alterneigh},
 * {@code learn} and {@code unbiasnet} of Anthony Dekker's {@code neuquant.c} with its fixed-point arithmetic. The
 * network size is a parameter, sample positions count pixels rather than bytes, and the learning-rate interval is at
 * least one sample, where the original divides by zero below 100 sampled pixels; the original's green-sorted index
 * ({@code inxbuild}, {@code inxsearch}) is not ported.
 */
public final class NeuQuant {
    /**
     * The number of learning cycles.
     */
    private static final int CYCLES = 100;

    /**
     * The fixed-point shift of the color values in the network.
     */
    private static final int NET_BIAS_SHIFT = 4;

    /**
     * The fixed-point shift of the frequencies and biases.
     */
    private static final int INT_BIAS_SHIFT = 16;

    /**
     * One in {@link #INT_BIAS_SHIFT} fixed point.
     */
    private static final int INT_BIAS = 1 << INT_BIAS_SHIFT;

    /**
     * The shift of the bias gain gamma, 1024.
     */
    private static final int GAMMA_SHIFT = 10;

    /**
     * The shift of the frequency decay beta, 1/1024.
     */
    private static final int BETA_SHIFT = 10;

    /**
     * The frequency decay beta in fixed point.
     */
    private static final int BETA = INT_BIAS >> BETA_SHIFT;

    /**
     * The product of beta and gamma in fixed point.
     */
    private static final int BETA_GAMMA = INT_BIAS << (GAMMA_SHIFT - BETA_SHIFT);

    /**
     * The fixed-point shift of the neighborhood radius.
     */
    private static final int RADIUS_BIAS_SHIFT = 6;

    /**
     * The factor by which the radius decreases each cycle, as a divisor.
     */
    private static final int RADIUS_DECREASE = 30;

    /**
     * The fixed-point shift of the learning rate alpha.
     */
    private static final int ALPHA_BIAS_SHIFT = 10;

    /**
     * The initial learning rate, 1.0 in fixed point.
     */
    private static final int INIT_ALPHA = 1 << ALPHA_BIAS_SHIFT;

    /**
     * The fixed-point shift of the neighborhood weights.
     */
    private static final int RAD_BIAS_SHIFT = 8;

    /**
     * One neighborhood weight unit.
     */
    private static final int RAD_BIAS = 1 << RAD_BIAS_SHIFT;

    /**
     * The divisor of the neighborhood updates: alpha and the weight units combined.
     */
    private static final int ALPHA_RAD_BIAS = 1 << (ALPHA_BIAS_SHIFT + RAD_BIAS_SHIFT);

    /**
     * The first of four primes near 500 used as sampling steps; an image whose length all four
     * divide is assumed not to exist.
     */
    private static final int PRIME_1 = 499;

    /**
     * The second sampling prime.
     */
    private static final int PRIME_2 = 491;

    /**
     * The third sampling prime.
     */
    private static final int PRIME_3 = 487;

    /**
     * The fourth sampling prime; images of fewer pixels are sampled in full.
     */
    private static final int PRIME_4 = 503;

    /**
     * Prevents instantiation.
     */
    private NeuQuant() {
        throw new AssertionError();
    }

    /**
     * Trains a palette on colors.
     *
     * @param colors the colors, packed RGB (red in the low byte); the upper byte is ignored
     * @param count the number of colors, positive
     * @param size the number of palette entries wanted, from 1 to 256
     * @param sampleFactor the sampling factor, from 1 (every color) to 30
     * @return the palette entries, packed RGB, {@code size} of them; entries may repeat
     */
    public static int[] quantize(int[] colors, int count, int size, int sampleFactor) {
        var network = new int[size * 3];
        var bias = new int[size];
        var freq = new int[size];
        for (var i = 0; i < size; i++) {
            var value = (i << (NET_BIAS_SHIFT + 8)) / size;
            network[i * 3] = value;
            network[i * 3 + 1] = value;
            network[i * 3 + 2] = value;
            freq[i] = INT_BIAS / size;
        }
        learn(colors, count, size, count < PRIME_4 ? 1 : sampleFactor, network, bias, freq);
        var palette = new int[size];
        for (var i = 0; i < size; i++) {
            var red = clamp(network[i * 3] >> NET_BIAS_SHIFT);
            var green = clamp(network[i * 3 + 1] >> NET_BIAS_SHIFT);
            var blue = clamp(network[i * 3 + 2] >> NET_BIAS_SHIFT);
            palette[i] = red | green << 8 | blue << 16;
        }
        return palette;
    }

    /**
     * Runs the main learning loop.
     *
     * @param colors the colors, packed RGB
     * @param count the number of colors
     * @param size the number of neurons
     * @param sampleFactor the sampling factor
     * @param network the neurons, three biased channels each
     * @param bias the bias of each neuron
     * @param freq the frequency of each neuron
     */
    private static void learn(int[] colors, int count, int size, int sampleFactor, int[] network, int[] bias,
                              int[] freq) {
        var alphaDecrease = 30 + (sampleFactor - 1) / 3;
        var samples = count / sampleFactor;
        var delta = Math.max(1, samples / CYCLES);
        var alpha = INIT_ALPHA;
        var radius = (size >> 3) << RADIUS_BIAS_SHIFT;
        var radiusPower = new int[Math.max(1, size >> 3)];
        var rad = radius >> RADIUS_BIAS_SHIFT;
        if (rad <= 1) {
            rad = 0;
        }
        powers(radiusPower, rad, alpha);
        int step;
        if (count < PRIME_4) {
            step = 1;
        } else if (count % PRIME_1 != 0) {
            step = PRIME_1;
        } else if (count % PRIME_2 != 0) {
            step = PRIME_2;
        } else if (count % PRIME_3 != 0) {
            step = PRIME_3;
        } else {
            step = PRIME_4;
        }
        var position = 0;
        for (var i = 1; i <= samples; i++) {
            var color = colors[position];
            var red = (color & 0xFF) << NET_BIAS_SHIFT;
            var green = ((color >>> 8) & 0xFF) << NET_BIAS_SHIFT;
            var blue = ((color >>> 16) & 0xFF) << NET_BIAS_SHIFT;
            var winner = contest(network, bias, freq, size, red, green, blue);
            var n = winner * 3;
            network[n] -= alpha * (network[n] - red) / INIT_ALPHA;
            network[n + 1] -= alpha * (network[n + 1] - green) / INIT_ALPHA;
            network[n + 2] -= alpha * (network[n + 2] - blue) / INIT_ALPHA;
            if (rad != 0) {
                neighbors(network, size, radiusPower, rad, winner, red, green, blue);
            }
            position += step;
            if (position >= count) {
                position -= count;
            }
            if (i % delta == 0) {
                alpha -= alpha / alphaDecrease;
                radius -= radius / RADIUS_DECREASE;
                rad = radius >> RADIUS_BIAS_SHIFT;
                if (rad <= 1) {
                    rad = 0;
                }
                powers(radiusPower, rad, alpha);
            }
        }
    }

    /**
     * Computes the neighborhood weights for a radius and learning rate.
     *
     * @param radiusPower the weights, one per distance from the winner
     * @param rad the radius in neurons
     * @param alpha the learning rate
     */
    private static void powers(int[] radiusPower, int rad, int alpha) {
        for (var i = 0; i < rad; i++) {
            radiusPower[i] = alpha * (((rad * rad - i * i) * RAD_BIAS) / (rad * rad));
        }
    }

    /**
     * Finds the winning neuron for a color and updates every neuron's frequency and bias.
     *
     * <p>The closest neuron by Manhattan distance has its frequency raised and its bias lowered;
     * the neuron returned is the one whose distance minus its bias is the smallest.
     *
     * @param network the neurons
     * @param bias the bias of each neuron
     * @param freq the frequency of each neuron
     * @param size the number of neurons
     * @param red the biased red channel
     * @param green the biased green channel
     * @param blue the biased blue channel
     * @return the index of the winning neuron
     */
    private static int contest(int[] network, int[] bias, int[] freq, int size, int red, int green, int blue) {
        var bestDistance = Integer.MAX_VALUE;
        var bestBiasDistance = Integer.MAX_VALUE;
        var bestPosition = 0;
        var bestBiasPosition = 0;
        for (var i = 0; i < size; i++) {
            var n = i * 3;
            var distance = Math.abs(network[n] - red) + Math.abs(network[n + 1] - green)
                    + Math.abs(network[n + 2] - blue);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPosition = i;
            }
            var biasDistance = distance - (bias[i] >> (INT_BIAS_SHIFT - NET_BIAS_SHIFT));
            if (biasDistance < bestBiasDistance) {
                bestBiasDistance = biasDistance;
                bestBiasPosition = i;
            }
            var betaFreq = freq[i] >> BETA_SHIFT;
            freq[i] -= betaFreq;
            bias[i] += betaFreq << GAMMA_SHIFT;
        }
        freq[bestPosition] += BETA;
        bias[bestPosition] -= BETA_GAMMA;
        return bestBiasPosition;
    }

    /**
     * Moves the neurons within a radius of the winner towards a color, by weights that fall off
     * with the distance from the winner.
     *
     * @param network the neurons
     * @param size the number of neurons
     * @param radiusPower the weights, one per distance from the winner
     * @param rad the radius in neurons
     * @param winner the index of the winning neuron
     * @param red the biased red channel
     * @param green the biased green channel
     * @param blue the biased blue channel
     */
    private static void neighbors(int[] network, int size, int[] radiusPower, int rad, int winner, int red,
                                   int green, int blue) {
        var low = Math.max(-1, winner - rad);
        var high = Math.min(size, winner + rad);
        var j = winner + 1;
        var k = winner - 1;
        var m = 1;
        while (j < high || k > low) {
            var a = radiusPower[m++];
            if (j < high) {
                var p = j * 3;
                network[p] -= a * (network[p] - red) / ALPHA_RAD_BIAS;
                network[p + 1] -= a * (network[p + 1] - green) / ALPHA_RAD_BIAS;
                network[p + 2] -= a * (network[p + 2] - blue) / ALPHA_RAD_BIAS;
                j++;
            }
            if (k > low) {
                var p = k * 3;
                network[p] -= a * (network[p] - red) / ALPHA_RAD_BIAS;
                network[p + 1] -= a * (network[p + 1] - green) / ALPHA_RAD_BIAS;
                network[p + 2] -= a * (network[p + 2] - blue) / ALPHA_RAD_BIAS;
                k--;
            }
        }
    }

    /**
     * Clamps a channel to 0..255.
     *
     * @param value the channel
     * @return the clamped channel
     */
    private static int clamp(int value) {
        return Math.clamp(value, 0, 255);
    }
}
