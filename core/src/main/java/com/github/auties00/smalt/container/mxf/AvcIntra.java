package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.track.Rational;

import java.util.HexFormat;

/**
 * The AVC-Intra classes of SMPTE RP 2027: their coding labels, coded frame sizes, and the sequence and picture
 * parameter sets a headerless stream (one that carries none in band) is decoded with.
 *
 * <p>RP 2027 fixes the profile, raster, scan, level, frame rate and the coded frame size of each class, and lets a
 * stream leave its parameter sets out; it does not state them. The sets here are those AVC-Intra encoders write for
 * each class and raster, with the level of RP 2027 Tables 5.1 to 5.3 and the timing of Table 5.4. An access unit of
 * RP 2027 starts with an access unit delimiter, its parameter sets (when present) padded to 512 bytes, and a region
 * of SEI messages padded to 18 (1080 lines) or 10 (720 lines) blocks of 512 bytes, then the coded picture, padded to
 * the coded frame size.
 */
final class AvcIntra {
    /**
     * The sequence and picture parameter sets of the six rasters (1080/59.94i, 1080/50i, 1080/29.97p, 1080/25p,
     * 720/59.94p, 720/50p) of class 50, 100 and 200, without start codes.
     */
    private static final String[][][] SETS = {
            {
                    {"676e1028a6d42032330c7118886210191986388c44302102564e6e61873e734d980c03069c0b73e6c0b518630d39e05b02"
                            + "d4c6191a798c323424f0168113f7870800001f480007530420", "68ee311211"},
                    {"676e1028a6d42032330c7118886210191986388c44302102564e6e61873e734d980c03069c0b73e6c0b518630d39e05b02"
                            + "d4c6191a798c323424f0168113f787080000030008000003019420", "68ee311211"},
                    {"676e1028a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c05a0227e5c38400000"
                            + "fa40003a9821", "68ee311211"},
                    {"676e1028a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c05a0227e5c38400000"
                            + "03004000000ca1", "68ee311211"},
                    {"676e1020a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c0f016ec38400000fa4"
                            + "0007530210", "68ee311211"},
                    {"676e1020a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c0f016ec38400000300"
                            + "04000003019210", "68ee311211"}
            },
            {
                    {"677a1029b6d420223319c6632321011198ce33191821033a46656a6524ade91232141a2634ada441822301502b1a246948"
                            + "30402e111208c68c0441284c34f01e0113f2e022000007d20001d4c108", "68ce3348d0"},
                    {"677a1029b6d420223319c6632321011198ce33191821033a46656a6524ade91232141a2634ada441822301502b1a246948"
                            + "30402e111208c68c0441284c34f01e0113f2e0220000030002000003006508", "68ce3348d0"},
                    {"677a1029b6d420223319c6632321011198ce331918210256b93d7d7e4fe33f11f19e08b88c5443c0780227e2701100000"
                            + "303e90000ea6084", "68ce3348d0"},
                    {"677a1029b6d420223319c6632321011198ce331918210256b93d7d7e4fe33f11f19e08b88c5443c0780227e2701100000"
                            + "30001000003003284", "68ce3348d0"},
                    {"677a1029b6d4202a331dc762a1084054663b8ec542021025642c89e885e4214b90830695d106469720c8d7430811c21e4c"
                            + "910f014016ec04400000fa4000753021", "68ce311211"},
                    {"677a1029b6d4202a331dc762a1084054663b8ec542021025642c89e885e4214b90830695d106469720c8d7430811c21e4c"
                            + "910f014016ec0440000003004000001921", "68ce311211"}
            }
    };

    /**
     * The coded frame sizes of RP 2027 in bytes, by class (50, 100, 200), raster (1080, 720) and frame rate family
     * (59.94 Hz, 50 Hz).
     */
    private static final int[][][] FRAME_SIZES = {
            {{223_232, 271_360}, {111_104, 135_168}},
            {{462_848, 559_104}, {230_912, 279_040}},
            {{943_104, 1_134_592}, {471_040, 566_784}}
    };

    /**
     * The last bytes of the coding labels of the six rasters, in the order of {@link #SETS}.
     */
    private static final int[] RASTER_CODES = {1, 2, 3, 4, 8, 9};

    /**
     * The level of class 200 (4.1, {@code level_idc} 41), which RP 2027 Table 5.3 gives every raster with a coding
     * label.
     */
    private static final int CLASS_200_LEVEL = 41;

    private AvcIntra() {
        throw new AssertionError();
    }

    /**
     * Returns the class of a picture coding label.
     *
     * @param label the 16-byte picture coding label
     * @return 50, 100 or 200 for the RP 2027 constrained AVC-Intra labels, or {@code 0}
     */
    static int classOf(byte[] label) {
        if (label == null || label.length != 16
                || !MxfLabels.startsWith(label, MxfLabels.bytes("060e2b3404010100040102020132"))) {
            return 0;
        }
        return switch (label[14]) {
            case 0x21 -> 50;
            case 0x31 -> 100;
            case 0x32 -> 200;
            default -> 0;
        };
    }

    /**
     * Returns the raster of a picture coding label or, for a label without one, of the picture.
     *
     * @param label the 16-byte picture coding label
     * @param height the frame height
     * @param interlaced whether the frames are interlaced
     * @param editRate the frame rate
     * @return the index of the raster in the order 1080/59.94i, 1080/50i, 1080/29.97p, 1080/25p, 720/59.94p,
     * 720/50p, or {@code -1}
     */
    static int rasterOf(byte[] label, int height, boolean interlaced, Rational editRate) {
        var code = label[15] & 0xFF;
        for (var index = 0; index < RASTER_CODES.length; index++) {
            if (RASTER_CODES[index] == code) {
                return index;
            }
        }
        if (editRate == null) {
            return -1;
        }
        var fifty = isFiftyHertz(editRate);
        var sixty = isNtsc(editRate);
        if (!fifty && !sixty) {
            return -1;
        }
        if (height >= 1000) {
            return (interlaced ? 0 : 2) + (fifty ? 1 : 0);
        }
        return height >= 700 ? 4 + (fifty ? 1 : 0) : -1;
    }

    /**
     * Returns the parameter sets a headerless stream of a class and raster is decoded with.
     *
     * @param cls 50, 100 or 200
     * @param raster the raster index of {@link #rasterOf}
     * @return the sequence and picture parameter sets, whole NAL units without start codes
     */
    static byte[][] parameterSets(int cls, int raster) {
        var sets = SETS[cls == 50 ? 0 : 1][raster];
        var sps = HexFormat.of().parseHex(sets[0]);
        if (cls == 200) {
            sps[3] = (byte) CLASS_200_LEVEL;
        }
        return new byte[][]{sps, HexFormat.of().parseHex(sets[1])};
    }

    /**
     * Returns the coding label of a class and raster.
     *
     * @param cls 50, 100 or 200
     * @param raster the raster index of {@link #rasterOf}
     * @return the label
     */
    static byte[] codingLabel(int cls, int raster) {
        var label = MxfLabels.bytes("060e2b340401010a0401020201320000");
        label[14] = (byte) (cls == 50 ? 0x21 : cls == 100 ? 0x31 : 0x32);
        label[15] = (byte) RASTER_CODES[raster];
        if (cls == 200) {
            label[7] = 0x0D;
        }
        return label;
    }

    /**
     * {@return the coded frame size of a class and raster, the access unit size without its 512-byte header and SEI
     * blocks}
     *
     * @param cls 50, 100 or 200
     * @param raster the raster index of {@link #rasterOf}
     */
    static int codedFrameSize(int cls, int raster) {
        var classIndex = cls == 50 ? 0 : cls == 100 ? 1 : 2;
        return FRAME_SIZES[classIndex][raster >= 4 ? 1 : 0][raster % 2];
    }

    /**
     * {@return the number of 512-byte blocks before the coded picture of a stream without parameter sets: 18 for
     * 1080 lines, 10 for 720}
     *
     * @param raster the raster index of {@link #rasterOf}
     */
    static int seiBlocks(int raster) {
        return raster >= 4 ? 10 : 18;
    }

    /**
     * {@return the expected picture size of a raster for a class: width and height}
     *
     * @param cls 50, 100 or 200
     * @param raster the raster index of {@link #rasterOf}
     */
    static int[] pictureSize(int cls, int raster) {
        var hd = raster < 4;
        return cls == 50 ? new int[]{hd ? 1440 : 960, hd ? 1080 : 720} : new int[]{hd ? 1920 : 1280, hd ? 1080 : 720};
    }

    /**
     * {@return whether the raster index names an interlaced raster}
     *
     * @param raster the raster index of {@link #rasterOf}
     */
    static boolean isInterlaced(int raster) {
        return raster < 2;
    }

    /**
     * {@return whether a frame rate is 25 or 50 Hz}
     *
     * @param rate the frame rate
     */
    static boolean isFiftyHertz(Rational rate) {
        var numerator = rate.numerator();
        var denominator = rate.denominator();
        return numerator == 25L * denominator || numerator == 50L * denominator;
    }

    /**
     * {@return whether a frame rate is 23.98, 29.97 or 59.94 Hz}
     *
     * @param rate the frame rate
     */
    static boolean isNtsc(Rational rate) {
        var numerator = rate.numerator() * 1001L;
        var denominator = rate.denominator();
        return numerator == 24_000L * denominator || numerator == 30_000L * denominator
                || numerator == 60_000L * denominator;
    }
}
