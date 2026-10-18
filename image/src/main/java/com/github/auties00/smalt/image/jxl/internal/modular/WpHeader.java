/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: WpHeader). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

/**
 * Holds the parameters of the weighted (self-correcting) predictor (ISO/IEC 18181-1 table H.2,
 * {@code WPHeader}).
 *
 * @param p1 the error weight of sub-predictor 1
 * @param p2 the error weight of sub-predictor 2
 * @param p3a the north-west error weight of sub-predictor 3
 * @param p3b the north error weight of sub-predictor 3
 * @param p3c the north-east error weight of sub-predictor 3
 * @param p3d the north-north weight of sub-predictor 3
 * @param p3e the north-west minus west weight of sub-predictor 3
 * @param w0 the maximum weight of sub-predictor 0
 * @param w1 the maximum weight of sub-predictor 1
 * @param w2 the maximum weight of sub-predictor 2
 * @param w3 the maximum weight of sub-predictor 3
 */
public record WpHeader(int p1, int p2, int p3a, int p3b, int p3c, int p3d, int p3e, int w0, int w1, int w2, int w3) {
    /**
     * The default parameters.
     */
    public static final WpHeader DEFAULT = new WpHeader(16, 10, 7, 7, 7, 0, 0, 13, 12, 12, 12);

    /**
     * Reads the parameters.
     *
     * @param br the bitstream
     * @return the parameters
     */
    public static WpHeader read(JxlBitReader br) {
        if (br.readBool()) {
            return DEFAULT;
        }
        return new WpHeader(br.readBits(5), br.readBits(5), br.readBits(5), br.readBits(5), br.readBits(5),
                br.readBits(5), br.readBits(5), br.readBits(4), br.readBits(4), br.readBits(4), br.readBits(4));
    }
}
