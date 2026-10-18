/* Ports opencore-amr 0.1.6 (gsm_amr_typedefs.h). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * Holds the saturation flag that the fixed-point operations of the AMR-NB codec raise when a
 * result saturates.
 *
 * <p>The reference passes a {@code Flag *pOverflow} pointer through most functions; the basic
 * operations set it to 1 on saturation and never clear it, while a few callers clear it and test
 * it afterwards (the decoder's synthesis overflow check, for example). This class is that pointer:
 * a codec instance owns one {@code Flag}, passes it wherever the reference passes
 * {@code pOverflow}, writes {@code value = true} where the reference writes {@code *pOverflow = 1},
 * {@code value = false} where it writes {@code *pOverflow = 0}, and tests {@code value} where it
 * tests {@code *pOverflow}. Functions whose reference implementation takes the pointer but never
 * touches it do not take a {@code Flag} in this port.
 */
final class Flag {
    /**
     * Whether a saturation happened since the flag was last cleared.
     */
    boolean value;

    /**
     * Creates a cleared flag.
     */
    Flag() {
    }
}
