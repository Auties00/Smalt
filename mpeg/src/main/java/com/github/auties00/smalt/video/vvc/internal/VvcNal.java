package com.github.auties00.smalt.video.vvc.internal;

/**
 * Holds the NAL unit types of ITU-T H.266 Table 5.
 */
public final class VvcNal {
    /**
     * {@code TRAIL_NUT}: a trailing picture.
     */
    public static final int TRAIL = 0;
    /**
     * {@code STSA_NUT}: a step-wise temporal sublayer access picture.
     */
    public static final int STSA = 1;
    /**
     * {@code RADL_NUT}: a random access decodable leading picture.
     */
    public static final int RADL = 2;
    /**
     * {@code RASL_NUT}: a random access skipped leading picture.
     */
    public static final int RASL = 3;
    /**
     * {@code IDR_W_RADL}: an IDR picture that may have leading pictures.
     */
    public static final int IDR_W_RADL = 7;
    /**
     * {@code IDR_N_LP}: an IDR picture without leading pictures.
     */
    public static final int IDR_N_LP = 8;
    /**
     * {@code CRA_NUT}: a clean random access picture.
     */
    public static final int CRA = 9;
    /**
     * {@code GDR_NUT}: a gradual decoding refresh picture.
     */
    public static final int GDR = 10;
    /**
     * {@code OPI_NUT}: operating point information.
     */
    public static final int OPI = 12;
    /**
     * {@code DCI_NUT}: decoding capability information.
     */
    public static final int DCI = 13;
    /**
     * {@code VPS_NUT}: a video parameter set.
     */
    public static final int VPS = 14;
    /**
     * {@code SPS_NUT}: a sequence parameter set.
     */
    public static final int SPS = 15;
    /**
     * {@code PPS_NUT}: a picture parameter set.
     */
    public static final int PPS = 16;
    /**
     * {@code PREFIX_APS_NUT}: a prefix adaptation parameter set.
     */
    public static final int PREFIX_APS = 17;
    /**
     * {@code SUFFIX_APS_NUT}: a suffix adaptation parameter set.
     */
    public static final int SUFFIX_APS = 18;
    /**
     * {@code PH_NUT}: a picture header.
     */
    public static final int PH = 19;
    /**
     * {@code AUD_NUT}: an access unit delimiter.
     */
    public static final int AUD = 20;
    /**
     * {@code EOS_NUT}: an end of sequence.
     */
    public static final int EOS = 21;
    /**
     * {@code EOB_NUT}: an end of bitstream.
     */
    public static final int EOB = 22;
    /**
     * {@code PREFIX_SEI_NUT}: prefix SEI messages.
     */
    public static final int PREFIX_SEI = 23;
    /**
     * {@code SUFFIX_SEI_NUT}: suffix SEI messages.
     */
    public static final int SUFFIX_SEI = 24;
    /**
     * {@code FD_NUT}: filler data.
     */
    public static final int FD = 25;

    /**
     * Prevents instantiation.
     */
    private VvcNal() {
    }

    /**
     * {@return the type of a NAL unit given its second header byte}
     *
     * @param secondByte the second byte of the NAL unit header
     */
    public static int type(int secondByte) {
        return (secondByte >> 3) & 31;
    }

    /**
     * {@return the layer of a NAL unit given its first header byte}
     *
     * @param firstByte the first byte of the NAL unit header
     */
    public static int layer(int firstByte) {
        return firstByte & 63;
    }

    /**
     * {@return the temporal id of a NAL unit given its second header byte}
     *
     * @param secondByte the second byte of the NAL unit header
     */
    public static int temporalId(int secondByte) {
        return (secondByte & 7) - 1;
    }

    /**
     * {@return whether a type is a coded slice}
     *
     * @param type the NAL unit type
     */
    public static boolean isVcl(int type) {
        return type <= 11;
    }

    /**
     * {@return whether a type is one of the reserved coded slice types, which decoders ignore}
     *
     * @param type the NAL unit type
     */
    public static boolean isReservedVcl(int type) {
        return (type >= 4 && type <= 6) || type == 11;
    }

    /**
     * {@return whether a type is an intra random access point}
     *
     * @param type the NAL unit type
     */
    public static boolean isIrap(int type) {
        return type >= IDR_W_RADL && type <= CRA;
    }

    /**
     * {@return whether a type is an IDR picture}
     *
     * @param type the NAL unit type
     */
    public static boolean isIdr(int type) {
        return type == IDR_W_RADL || type == IDR_N_LP;
    }
}
