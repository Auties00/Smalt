/* Ports random1() of common_m4a.c of the ISO/IEC 14496-3 reference software (MPEG-4 Audio). Copyright University
 * of Hannover; ISO/IEC 14496-3 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

/**
 * The random number generator of the HVXC decoder ({@code random1}): a linear congruential generator whose values
 * are uniform over {@code [0, 2^31 - 1]}, seeded with 1.
 *
 * <p>One generator serves every random choice of a decoder, so the sequence of draws, and with it the output,
 * depends only on the stream and the options. Instances are not thread-safe.
 */
final class HvxcRandom {
    /**
     * The largest value, {@code RND_MAX}.
     */
    static final int MAX = 0x7fffffff;

    /**
     * The state; only its low 31 bits matter.
     */
    private int state = 1;

    /**
     * {@return the next value, from 0 to {@link #MAX}}
     */
    int next() {
        state = state * 1103515245 + 12345;
        return state & MAX;
    }
}
