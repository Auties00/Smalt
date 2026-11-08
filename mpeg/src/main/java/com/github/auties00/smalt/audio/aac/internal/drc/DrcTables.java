/* Ports the tables of the ISO/IEC 23003-4 reference software (uniDrcTables.c). Copyright Apple Inc., Fraunhofer
   IIS and ISO/IEC 2014; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003-4).
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

/**
 * Holds the constant tables of the MPEG-D DRC decoder.
 */
final class DrcTables {
    /**
     * The delta gain codes of the regular, fading and constant gain coding profiles, by code size: the code sizes in
     * bits.
     */
    static final int[] DELTA_GAIN_SIZE = {
            2, 2, 3, 3, 4, 5, 5, 5,
            6, 6, 6, 7, 7, 7, 7, 8,
            9, 9, 9, 9, 10, 10, 10, 11,
            11,
    };

    /**
     * The delta gain codes of the regular, fading and constant gain coding profiles, by code size: the codes.
     */
    static final int[] DELTA_GAIN_CODE = {
            3, 2, 1, 2, 0, 2, 15, 14,
            25, 24, 6, 15, 52, 54, 55, 29,
            57, 213, 215, 212, 112, 428, 429, 226,
            227,
    };

    /**
     * The delta gain codes of the regular, fading and constant gain coding profiles, by code size: the delta gains in
     * dB.
     */
    static final float[] DELTA_GAIN_VALUE = {
            -0.125f, 0.125f, -0.250f, 0.000f, -2.000f, -0.500f, -0.375f, 1.000f,
            -0.625f, 0.250f, 0.375f, -1.000f, -0.875f, -0.750f, 0.500f, 0.625f,
            -1.875f, -1.125f, 0.750f, 0.875f, -1.500f, -1.375f, -1.250f, -1.750f,
            -1.625f,
    };

    /**
     * The delta gain codes of the clipping gain coding profile, by code size: the code sizes in bits.
     */
    static final int[] DELTA_GAIN_CLIPPING_SIZE = {
            3, 4, 4, 4, 4, 4, 4, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 6, 6, 6, 6, 6, 6, 7,
            7, 7, 7, 7, 7, 8, 8, 8,
            8, 8, 8, 8, 9, 9, 9, 9,
            9, 9, 9, 9, 10, 10, 10, 11,
            11,
    };

    /**
     * The delta gain codes of the clipping gain coding profile, by code size: the codes.
     */
    static final int[] DELTA_GAIN_CLIPPING_CODE = {
            7, 12, 9, 5, 3, 1, 11, 17,
            14, 5, 4, 8, 0, 13, 15, 16,
            27, 43, 40, 2, 18, 24, 41, 106,
            84, 104, 38, 6, 50, 210, 171, 170,
            79, 78, 215, 14, 429, 428, 422, 205,
            206, 423, 31, 204, 60, 414, 415, 122,
            123,
    };

    /**
     * The delta gain codes of the clipping gain coding profile, by code size: the delta gains in dB.
     */
    static final float[] DELTA_GAIN_CLIPPING_VALUE = {
            -0.125f, -0.625f, -0.500f, -0.375f, -0.250f, 0.000f, 0.125f, -0.875f,
            -0.750f, 0.250f, 0.375f, 0.500f, 0.625f, 0.750f, 0.875f, 1.000f,
            1.125f, -1.250f, -1.125f, -1.000f, 1.250f, 1.375f, 1.500f, -4.000f,
            -1.750f, -1.625f, -1.500f, -1.375f, 1.625f, -2.250f, -2.125f, -2.000f,
            -1.875f, 1.750f, 1.875f, 2.000f, -3.625f, -3.375f, -3.250f, -3.125f,
            -2.750f, -2.625f, -2.500f, -2.375f, -3.500f, -3.000f, -2.875f, -3.875f,
            -3.750f,
    };

    /**
     * The slope codes, by code size: the code sizes in bits.
     */
    static final int[] SLOPE_SIZE = {
            1, 2, 4, 5, 5, 5, 5, 6,
            6, 7, 7, 7, 8, 9, 9,
    };

    /**
     * The slope codes, by code size: the codes.
     */
    static final int[] SLOPE_CODE = {
            1, 0, 7, 10, 9, 13, 11, 24,
            17, 50, 32, 51, 66, 135, 134,
    };

    /**
     * The slope codes, by code size: the slope indices.
     */
    static final int[] SLOPE_INDEX = {
            7, 6, 8, 3, 4, 5, 9, 0,
            10, 2, 13, 14, 1, 11, 12,
    };

    /**
     * The slope of each slope index.
     */
    static final float[] SLOPE_VALUE = {
            -3.0518f, -1.2207f, -0.4883f, -0.1953f, -0.0781f, -0.0312f, -0.005f, 0.0f,
            0.005f, 0.0312f, 0.0781f, 0.1953f, 0.4883f, 1.2207f, 3.0518f,
    };

    /**
     * The downmix coefficients in dB.
     */
    static final float[] DOWNMIX_COEFF = {
            0.0f, -0.5f, -1.0f, -1.5f, -2.0f, -2.5f, -3.0f, -3.5f,
            -4.0f, -4.5f, -5.0f, -5.5f, -6.0f, -7.5f, -9.0f, -1000.0f,
    };

    /**
     * The downmix coefficients of the low frequency channel in dB.
     */
    static final float[] DOWNMIX_COEFF_LFE = {
            10.0f, 6.0f, 4.5f, 3.0f, 1.5f, 0.0f, -1.5f, -3.0f,
            -4.5f, -6.0f, -10.0f, -15.0f, -20.0f, -30.0f, -40.0f, -1000.0f,
    };

    /**
     * The level estimation channel weights in dB.
     */
    static final float[] CHANNEL_WEIGHT = {
            10.0f, 6.0f, 4.5f, 3.0f, 1.5f, 0.0f, -1.5f, -3.0f,
            -4.5f, -6.0f, -10.0f, -15.0f, -20.0f, -30.0f, -40.0f, -1000.0f,
    };

    /**
     * The downmix coefficients of the version 1 syntax.
     */
    static final float[] DOWNMIX_COEFF_V1 = {
            10.00f, 6.00f, 4.50f, 3.00f, 1.50f, 0.00f, -0.50f, -1.00f,
            -1.50f, -2.00f, -2.50f, -3.00f, -3.50f, -4.00f, -4.50f, -5.00f,
            -5.50f, -6.00f, -6.50f, -7.00f, -7.50f, -8.00f, -9.00f, -10.00f,
            -11.00f, -12.00f, -15.00f, -20.00f, -25.00f, -30.00f, -40.00f, -100000.0f,
    };

    /**
     * The equalizer spline slopes.
     */
    static final float[] EQ_SLOPE = {
            -32.0f, -24.0f, -18.0f, -12.0f, -7.0f, -4.0f, -2.0f, -1.0f,
            1.0f, 2.0f, 4.0f, 7.0f, 12.0f, 18.0f, 24.0f, 32.0f,
    };

    /**
     * The equalizer spline gain deltas in dB.
     */
    static final float[] EQ_GAIN_DELTA = {
            -22.0f, -16.0f, -13.0f, -11.0f, -9.0f, -7.0f, -6.0f, -5.0f,
            -4.0f, -3.0f, -2.5f, -2.0f, -1.5f, -1.0f, -0.5f, 0.0f,
            0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f,
            6.0f, 7.0f, 9.0f, 11.0f, 13.0f, 16.0f, 22.0f, 32.0f,
    };

    /**
     * The radii of the equalizer filter zeros and poles.
     */
    static final float[] ZERO_POLE_RADIUS = {
            0.00000000E+00f, 7.57409621E-11f, 7.47451079E-09f, 7.37623509E-08f, 3.37872933E-07f, 1.05439995E-06f, 2.61370951E-06f, 5.55702854E-06f,
            1.05878771E-05f, 1.85806475E-05f, 3.05868707E-05f, 4.78395414E-05f, 7.17558214E-05f, 1.03938342E-04f, 1.46175269E-04f, 2.00439375E-04f,
            2.68886099E-04f, 3.53850890E-04f, 4.57845890E-04f, 5.83555840E-04f, 7.33833469E-04f, 9.11694835E-04f, 1.12031354E-03f, 1.36301492E-03f,
            1.64327072E-03f, 1.96469179E-03f, 2.33102194E-03f, 2.74613220E-03f, 3.21401190E-03f, 3.73876374E-03f, 4.32459544E-03f, 4.97581391E-03f,
            5.69681637E-03f, 6.49208482E-03f, 7.36617809E-03f, 8.32372531E-03f, 9.36941616E-03f, 1.05079999E-02f, 1.17442720E-02f, 1.30830696E-02f,
            1.45292655E-02f, 1.60877611E-02f, 1.77634824E-02f, 1.95613634E-02f, 2.14863531E-02f, 2.35434026E-02f, 2.57374570E-02f, 2.80734543E-02f,
            3.05563174E-02f, 3.31909470E-02f, 3.59822176E-02f, 3.89349759E-02f, 4.20540236E-02f, 4.53441292E-02f, 4.88100089E-02f, 5.24563305E-02f,
            5.62877022E-02f, 6.03086725E-02f, 6.45237267E-02f, 6.89372867E-02f, 7.35536888E-02f, 7.83772022E-02f, 8.34120139E-02f, 8.86622295E-02f,
            9.41318572E-02f, 9.98248383E-02f, 1.05744988E-01f, 1.11896060E-01f, 1.18281692E-01f, 1.24905407E-01f, 1.31770656E-01f, 1.38880774E-01f,
            1.46238968E-01f, 1.53848350E-01f, 1.61711931E-01f, 1.69832602E-01f, 1.78213134E-01f, 1.86856180E-01f, 1.95764288E-01f, 2.04939872E-01f,
            2.14385241E-01f, 2.24102572E-01f, 2.34093949E-01f, 2.44361281E-01f, 2.54906416E-01f, 2.65731007E-01f, 2.76836663E-01f, 2.88224846E-01f,
            2.99896836E-01f, 3.11853856E-01f, 3.24096978E-01f, 3.36627185E-01f, 3.49445283E-01f, 3.62551987E-01f, 3.75947863E-01f, 3.89633417E-01f,
            4.03608948E-01f, 4.17874694E-01f, 4.32430804E-01f, 4.47277188E-01f, 4.62413728E-01f, 4.77840215E-01f, 4.93556231E-01f, 5.09561300E-01f,
            5.25854886E-01f, 5.42436182E-01f, 5.59304416E-01f, 5.76458573E-01f, 5.93897760E-01f, 6.11620665E-01f, 6.29626155E-01f, 6.47912800E-01f,
            6.66479111E-01f, 6.85323536E-01f, 7.04444408E-01f, 7.23839939E-01f, 7.43508339E-01f, 7.63447523E-01f, 7.83655465E-01f, 8.04130018E-01f,
            8.24868977E-01f, 8.45869958E-01f, 8.67130578E-01f, 8.88648331E-01f, 9.10420537E-01f, 9.32444632E-01f, 9.54717815E-01f, 9.77237225E-01f,
    };

    /**
     * The angles of the equalizer filter zeros and poles, as fractions of pi.
     */
    static final float[] ZERO_POLE_ANGLE = {
            0.00000000E+00f, 6.90533966E-04f, 7.31595252E-04f, 7.75098170E-04f, 8.21187906E-04f, 8.70018279E-04f, 9.21752258E-04f, 9.76562500E-04f,
            1.03463193E-03f, 1.09615434E-03f, 1.16133507E-03f, 1.23039165E-03f, 1.30355455E-03f, 1.38106793E-03f, 1.46319050E-03f, 1.55019634E-03f,
            1.64237581E-03f, 1.74003656E-03f, 1.84350452E-03f, 1.95312500E-03f, 2.06926386E-03f, 2.19230869E-03f, 2.32267015E-03f, 2.46078330E-03f,
            2.60710909E-03f, 2.76213586E-03f, 2.92638101E-03f, 3.10039268E-03f, 3.28475162E-03f, 3.48007312E-03f, 3.68700903E-03f, 3.90625000E-03f,
            4.13852771E-03f, 4.38461738E-03f, 4.64534029E-03f, 4.92156660E-03f, 5.21421818E-03f, 5.52427173E-03f, 5.85276202E-03f, 6.20078536E-03f,
            6.56950324E-03f, 6.96014624E-03f, 7.37401807E-03f, 7.81250000E-03f, 8.27705542E-03f, 8.76923475E-03f, 9.29068059E-03f, 9.84313320E-03f,
            1.04284364E-02f, 1.10485435E-02f, 1.17055240E-02f, 1.24015707E-02f, 1.31390065E-02f, 1.39202925E-02f, 1.47480361E-02f, 1.56250000E-02f,
            1.65541108E-02f, 1.75384695E-02f, 1.85813612E-02f, 1.96862664E-02f, 2.08568727E-02f, 2.20970869E-02f, 2.34110481E-02f, 2.48031414E-02f,
            2.62780130E-02f, 2.78405849E-02f, 2.94960723E-02f, 3.12500000E-02f, 3.31082217E-02f, 3.50769390E-02f, 3.71627223E-02f, 3.93725328E-02f,
            4.17137454E-02f, 4.41941738E-02f, 4.68220962E-02f, 4.96062829E-02f, 5.25560260E-02f, 5.56811699E-02f, 5.89921445E-02f, 6.25000000E-02f,
            6.62164434E-02f, 7.01538780E-02f, 7.43254447E-02f, 7.87450656E-02f, 8.34274909E-02f, 8.83883476E-02f, 9.36441923E-02f, 9.92125657E-02f,
            1.05112052E-01f, 1.11362340E-01f, 1.17984289E-01f, 1.25000000E-01f, 1.32432887E-01f, 1.40307756E-01f, 1.48650889E-01f, 1.57490131E-01f,
            1.66854982E-01f, 1.76776695E-01f, 1.87288385E-01f, 1.98425131E-01f, 2.10224104E-01f, 2.22724680E-01f, 2.35968578E-01f, 2.50000000E-01f,
            2.64865774E-01f, 2.80615512E-01f, 2.97301779E-01f, 3.14980262E-01f, 3.33709964E-01f, 3.53553391E-01f, 3.74576769E-01f, 3.96850263E-01f,
            4.20448208E-01f, 4.45449359E-01f, 4.71937156E-01f, 5.00000000E-01f, 5.29731547E-01f, 5.61231024E-01f, 5.94603558E-01f, 6.29960525E-01f,
            6.67419927E-01f, 7.07106781E-01f, 7.49153538E-01f, 7.93700526E-01f, 8.40896415E-01f, 8.90898718E-01f, 9.43874313E-01f, 1.00000000E+00f,
    };

    /**
     * The pole radii of the low frequency shape filters.
     */
    static final float[] SHAPE_RADIUS_LF = {
            0.988f, 0.98f, 0.96f, 0.94f,
    };

    /**
     * The pole radii of the high frequency shape filters.
     */
    static final float[] SHAPE_RADIUS_HF = {
            0.45f, 0.40f, 0.35f, 0.30f, 0.30f,
    };

    /**
     * The normalized cutoff frequencies of the high frequency shape filters.
     */
    static final float[] SHAPE_CUTOFF_HF = {
            0.15f, 0.20f, 0.25f, 0.35f, 0.45f,
    };

    /**
     * The y1 bounds of the low frequency shape filters, by corner frequency and strength.
     */
    static final float[][] SHAPE_Y1_BOUND_LF = {
            {-0.994f, -0.996f, -1.0f},
            {-0.99f, -0.995f, -0.999f},
            {-0.98f, -0.989f, -0.996f},
            {-0.97f, -0.983f, -0.994f}
    };

    /**
     * The y1 bounds of the high frequency shape filters, by corner frequency and strength.
     */
    static final float[][] SHAPE_Y1_BOUND_HF = {
            {0.15f, 0.75f, 1.05f},
            {0.43f, 0.87f, 1.07f},
            {0.60f, 0.92f, 1.07f},
            {0.80f, 1.00f, 1.06f},
            {0.90f, 1.04f, 1.073f}
    };

    /**
     * The gain offsets of the low frequency shape filters, by corner frequency and strength.
     */
    static final float[][] SHAPE_GAIN_OFFSET_LF = {
            {3.0f, 2.0f, 1.2f},
            {3.0f, 2.0f, 1.5f},
            {3.0f, 2.0f, 2.0f},
            {3.0f, 2.0f, 2.0f}
    };

    /**
     * The gain offsets of the high frequency shape filters, by corner frequency and strength.
     */
    static final float[][] SHAPE_GAIN_OFFSET_HF = {
            {4.5f, 6.0f, 3.5f},
            {3.7f, 4.0f, 2.7f},
            {3.0f, 3.5f, 2.0f},
            {2.0f, 2.5f, 1.5f},
            {1.5f, 2.0f, 1.31f}
    };

    /**
     * The input to output ratio and the low and high exponents of the CICP sigmoid characteristics 1 to 6.
     */
    static final float[][] CICP_SIGMOID = {
            {0.0f, 9.0f, 12.0f},
            {0.2f, 9.0f, 12.0f},
            {0.4f, 9.0f, 12.0f},
            {0.6f, 9.0f, 12.0f},
            {0.8f, 6.0f, 8.0f},
            {1.0f, 5.0f, 6.0f}
    };

    /**
     * Prevents instantiation.
     */
    private DrcTables() {
        throw new AssertionError();
    }
}
