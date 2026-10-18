package com.github.auties00.smalt.video.hevc.internal;

/**
 * Holds the HEVC NAL unit types (ITU-T H.265 Table 7-1) and the predicates over them.
 */
public final class HevcNal {
    /**
     * Coded slice segment of a trailing picture that is not a sub-layer reference.
     */
    public static final int TRAIL_N = 0;

    /**
     * Coded slice segment of a trailing picture that is a sub-layer reference.
     */
    public static final int TRAIL_R = 1;

    /**
     * Coded slice segment of a temporal sub-layer access picture that is not a sub-layer reference.
     */
    public static final int TSA_N = 2;

    /**
     * Coded slice segment of a temporal sub-layer access picture that is a sub-layer reference.
     */
    public static final int TSA_R = 3;

    /**
     * Coded slice segment of a stepwise temporal sub-layer access picture that is not a sub-layer
     * reference.
     */
    public static final int STSA_N = 4;

    /**
     * Coded slice segment of a stepwise temporal sub-layer access picture that is a sub-layer
     * reference.
     */
    public static final int STSA_R = 5;

    /**
     * Coded slice segment of a random access decodable leading picture that is not a sub-layer
     * reference.
     */
    public static final int RADL_N = 6;

    /**
     * Coded slice segment of a random access decodable leading picture that is a sub-layer
     * reference.
     */
    public static final int RADL_R = 7;

    /**
     * Coded slice segment of a random access skipped leading picture that is not a sub-layer
     * reference.
     */
    public static final int RASL_N = 8;

    /**
     * Coded slice segment of a random access skipped leading picture that is a sub-layer
     * reference.
     */
    public static final int RASL_R = 9;

    /**
     * The first reserved non-IRAP VCL NAL unit type, {@code RSV_VCL_N10}.
     */
    public static final int RSV_VCL_N10 = 10;

    /**
     * The largest reserved non-IRAP sub-layer non-reference VCL NAL unit type, {@code RSV_VCL_N14}.
     */
    public static final int RSV_VCL_N14 = 14;

    /**
     * The last reserved non-IRAP VCL NAL unit type, {@code RSV_VCL_R15}.
     */
    public static final int RSV_VCL_R15 = 15;

    /**
     * Coded slice segment of a broken link access picture with leading pictures.
     */
    public static final int BLA_W_LP = 16;

    /**
     * Coded slice segment of a broken link access picture with decodable leading pictures only.
     */
    public static final int BLA_W_RADL = 17;

    /**
     * Coded slice segment of a broken link access picture without leading pictures.
     */
    public static final int BLA_N_LP = 18;

    /**
     * Coded slice segment of an instantaneous decoding refresh picture with decodable leading
     * pictures.
     */
    public static final int IDR_W_RADL = 19;

    /**
     * Coded slice segment of an instantaneous decoding refresh picture without leading pictures.
     */
    public static final int IDR_N_LP = 20;

    /**
     * Coded slice segment of a clean random access picture.
     */
    public static final int CRA_NUT = 21;

    /**
     * The first reserved IRAP VCL NAL unit type, {@code RSV_IRAP_VCL22}.
     */
    public static final int RSV_IRAP_VCL22 = 22;

    /**
     * The largest reserved IRAP VCL NAL unit type, {@code RSV_IRAP_VCL23}.
     */
    public static final int RSV_IRAP_VCL23 = 23;

    /**
     * The first reserved non-IRAP VCL NAL unit type of the upper range, {@code RSV_VCL24}.
     */
    public static final int RSV_VCL24 = 24;

    /**
     * The last reserved VCL NAL unit type, {@code RSV_VCL31}.
     */
    public static final int RSV_VCL31 = 31;

    /**
     * Video parameter set.
     */
    public static final int VPS = 32;

    /**
     * Sequence parameter set.
     */
    public static final int SPS = 33;

    /**
     * Picture parameter set.
     */
    public static final int PPS = 34;

    /**
     * Access unit delimiter.
     */
    public static final int AUD = 35;

    /**
     * End of sequence.
     */
    public static final int EOS = 36;

    /**
     * End of bitstream.
     */
    public static final int EOB = 37;

    /**
     * Filler data.
     */
    public static final int FD = 38;

    /**
     * Prefix supplemental enhancement information.
     */
    public static final int PREFIX_SEI = 39;

    /**
     * Suffix supplemental enhancement information.
     */
    public static final int SUFFIX_SEI = 40;

    /**
     * The first reserved non-VCL NAL unit type, {@code RSV_NVCL41}.
     */
    public static final int RSV_NVCL41 = 41;

    /**
     * The last reserved non-VCL NAL unit type that starts an access unit, {@code RSV_NVCL44}.
     */
    public static final int RSV_NVCL44 = 44;

    /**
     * The first unspecified non-VCL NAL unit type, {@code UNSPEC48}.
     */
    public static final int UNSPEC48 = 48;

    /**
     * The last unspecified non-VCL NAL unit type that starts an access unit, {@code UNSPEC55}.
     */
    public static final int UNSPEC55 = 55;

    /**
     * Prevents instantiation.
     */
    private HevcNal() {
        throw new AssertionError();
    }

    /**
     * Returns whether a NAL unit type is a VCL NAL unit type that carries a coded slice segment of
     * a version 1 picture: 0 to 9 and 16 to 21.
     *
     * @param type the NAL unit type
     * @return {@code true} for a decodable slice segment type
     */
    public static boolean isSlice(int type) {
        return type <= RASL_R || (type >= BLA_W_LP && type <= CRA_NUT);
    }

    /**
     * Returns whether a NAL unit type is a reserved VCL NAL unit type (10 to 15 and 22 to 31),
     * which decoders ignore.
     *
     * @param type the NAL unit type
     * @return {@code true} for a reserved VCL type
     */
    public static boolean isReservedVcl(int type) {
        return type >= RSV_VCL_N10 && type <= RSV_VCL_R15 || type >= RSV_IRAP_VCL22 && type <= RSV_VCL31;
    }

    /**
     * Returns whether a non-VCL NAL unit of a type ends the access unit in progress when it
     * follows the last VCL NAL unit of a picture (7.4.2.4.4): a parameter set, an access unit
     * delimiter, an end of sequence or bitstream, a prefix SEI, or the reserved and unspecified
     * types {@link #RSV_NVCL41} to {@link #RSV_NVCL44} and {@link #UNSPEC48} to {@link #UNSPEC55}.
     *
     * @param type the NAL unit type
     * @return {@code true} when the NAL unit finishes the picture in progress
     */
    public static boolean startsAccessUnit(int type) {
        return type >= VPS && type <= EOB || type == PREFIX_SEI || type >= RSV_NVCL41 && type <= RSV_NVCL44
                || type >= UNSPEC48 && type <= UNSPEC55;
    }

    /**
     * Returns whether a NAL unit type is an intra random access point type (16 to 23).
     *
     * @param type the NAL unit type
     * @return {@code true} for BLA, IDR, CRA and the reserved IRAP types
     */
    public static boolean isIrap(int type) {
        return type >= BLA_W_LP && type <= RSV_IRAP_VCL23;
    }

    /**
     * Returns whether a NAL unit type is an IDR type.
     *
     * @param type the NAL unit type
     * @return {@code true} for {@link #IDR_W_RADL} and {@link #IDR_N_LP}
     */
    public static boolean isIdr(int type) {
        return type == IDR_W_RADL || type == IDR_N_LP;
    }

    /**
     * Returns whether a NAL unit type is a BLA type.
     *
     * @param type the NAL unit type
     * @return {@code true} for the three BLA types
     */
    public static boolean isBla(int type) {
        return type >= BLA_W_LP && type <= BLA_N_LP;
    }

    /**
     * Returns whether a NAL unit type is a RASL type.
     *
     * @param type the NAL unit type
     * @return {@code true} for {@link #RASL_N} and {@link #RASL_R}
     */
    public static boolean isRasl(int type) {
        return type == RASL_N || type == RASL_R;
    }

    /**
     * Returns whether a NAL unit type is a RADL type.
     *
     * @param type the NAL unit type
     * @return {@code true} for {@link #RADL_N} and {@link #RADL_R}
     */
    public static boolean isRadl(int type) {
        return type == RADL_N || type == RADL_R;
    }

    /**
     * Returns whether a VCL NAL unit type marks a sub-layer non-reference picture: an even type
     * up to {@link #RSV_VCL_N14}.
     *
     * @param type the NAL unit type
     * @return {@code true} for a sub-layer non-reference picture
     */
    public static boolean isSubLayerNonReference(int type) {
        return type <= RSV_VCL_N14 && (type & 1) == 0;
    }
}
