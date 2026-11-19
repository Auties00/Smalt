package com.github.auties00.smalt.audio.celp.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

/**
 * Reads the frames of the layers of a CELP audio object into a {@link CelpFrame} (ISO/IEC 14496-3 subclause 3.3):
 * the base layer in the syntax of object type 8 or, for object type 24, in the order of its error sensitivity
 * categories, and the bitrate and bandwidth scalable enhancement layers.
 *
 * <p>Instances are not thread-safe.
 */
final class CelpFrameReader {
    /**
     * The number of error sensitivity categories of an ER CELP base layer frame.
     */
    static final int CATEGORIES = 5;

    /**
     * The structure of the base layer.
     */
    private final CelpLayout layout;

    /**
     * Whether the base layer carries {@code interpolation_flag} and {@code LPC_Present}.
     */
    private final boolean fineRateControl;

    /**
     * Whether the base layer carries {@code TX_flag}.
     */
    private final boolean silenceCompression;

    /**
     * The width of {@code shape_bws_positions}.
     */
    private final int bandwidthPositionBits;

    /**
     * The width of {@code shape_bws_signs}.
     */
    private final int bandwidthSignBits;

    /**
     * Creates a reader.
     *
     * @param base the configuration of the base layer
     * @param layout the structure of the base layer
     * @param bandwidthConfiguration {@code BWS_configuration} of the bandwidth scalable layer, or -1 without one
     */
    CelpFrameReader(CelpConfig base, CelpLayout layout, int bandwidthConfiguration) {
        this.layout = layout;
        this.fineRateControl = base.fineRateControl();
        this.silenceCompression = base.silenceCompression();
        this.bandwidthPositionBits = bandwidthConfiguration < 0 ? 0
                : CelpLayout.bandwidthPositionBits(bandwidthConfiguration);
        this.bandwidthSignBits = bandwidthConfiguration < 0 ? 0 : CelpLayout.bandwidthPulses(bandwidthConfiguration);
    }

    /**
     * Reads a {@code CelpBaseFrame} of object type 8 (Table 3.13).
     *
     * @param in the access unit
     * @param frame the fields, cleared first
     */
    void readBase(MsbBitReader in, CelpFrame frame) {
        frame.clear();
        if (fineRateControl) {
            frame.interpolation = in.readFlag();
            frame.lpcPresent = in.readFlag();
        }
        if (frame.lpcPresent) {
            if (layout.wideband) {
                frame.lpc[0] = in.read(5);
                frame.lpc[1] = in.read(5);
                frame.lpc[2] = in.read(7);
                frame.lpc[3] = in.read(7);
                frame.lpc[4] = in.read(1);
                frame.lpc[5] = in.read(4);
                frame.lpc[6] = in.read(4);
                frame.lpc[7] = in.read(7);
                frame.lpc[8] = in.read(5);
                frame.lpc[9] = in.read(1);
            } else {
                frame.lpc[0] = in.read(4);
                frame.lpc[1] = in.read(4);
                frame.lpc[2] = in.read(7);
                frame.lpc[3] = in.read(6);
                frame.lpc[4] = in.read(1);
            }
        }
        if (layout.rpe) {
            for (var s = 0; s < layout.subframes; s++) {
                frame.delay[s] = in.read(8);
                frame.positions[s] = in.read(layout.positionBits);
                frame.gain[s] = in.read(6);
                frame.fixedGain[s] = in.read(s == 0 ? 5 : 3);
            }
        } else {
            frame.signalMode = in.read(2);
            frame.rms = in.read(6);
            for (var s = 0; s < layout.subframes; s++) {
                frame.delay[s] = in.read(layout.delayBits);
                frame.positions[s] = in.readLong(layout.positionBits);
                frame.signs[s] = in.read(layout.signBits);
                frame.gain[s] = in.read(layout.gainBits);
            }
        }
    }

    /**
     * Reads the {@code TX_flag} that starts an {@code ER_SC_CelpBaseFrame} with silence compression (Table 3.26).
     *
     * @param in the access unit of category 0
     * @param frame the fields, cleared first
     */
    void readVoiceActivity(MsbBitReader in, CelpFrame frame) {
        frame.clear();
        if (silenceCompression) {
            frame.txFlag = in.read(2);
        }
    }

    /**
     * Reads the SID information of a non-active ER CELP frame, {@code SID_LSP_VQ_ESC0} and {@code SID_Frame_ESC0}
     * of {@code TX_flag} 2 or {@code SID_Frame_ESC0} of {@code TX_flag} 3 (Tables 3.45, 3.46, 3.48 and 3.49), with
     * the LPC indices stored where the active frame keeps them (Table 3.105).
     *
     * @param in the access unit of category 0, after {@code TX_flag}
     * @param frame the fields
     */
    void readSid(MsbBitReader in, CelpFrame frame) {
        if (frame.txFlag == 2) {
            frame.lpc[0] = in.read(layout.wideband ? 5 : 4);
            frame.lpc[1] = in.read(layout.wideband ? 5 : 4);
            frame.lpc[2] = in.read(7);
            if (layout.wideband) {
                frame.lpc[3] = in.read(7);
                frame.lpc[5] = in.read(4);
                frame.lpc[6] = in.read(4);
            }
        }
        if (frame.txFlag >= 2) {
            frame.rms = in.read(6);
        }
    }

    /**
     * Reads one error sensitivity category of an {@code ER_CelpBaseFrame} (Tables 3.28 to 3.42).
     *
     * @param category the category, from 0 to 4
     * @param in the access unit holding the category
     * @param frame the fields; category 0 must be read first
     */
    void readCategory(int category, MsbBitReader in, CelpFrame frame) {
        if (layout.rpe) {
            rpeCategory(category, in, frame);
        } else if (layout.wideband) {
            mpeWidebandCategory(category, in, frame);
        } else {
            mpeNarrowbandCategory(category, in, frame);
        }
    }

    /**
     * Reads one category of the MPE narrowband syntax (Tables 3.28 to 3.32).
     */
    private void mpeNarrowbandCategory(int category, MsbBitReader in, CelpFrame f) {
        var lpc = f.lpcPresent;
        var n = layout.subframes;
        switch (category) {
            case 0 -> {
                readRateControl(in, f);
                f.rms |= in.read(2) << 4;
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(1) << 7;
                }
            }
            case 1 -> {
                if (lpc) {
                    f.lpc[0] |= in.read(2);
                    f.lpc[1] |= in.read(1);
                }
                f.signalMode = in.read(2);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2) << 5;
                }
            }
            case 2 -> {
                if (lpc) {
                    f.lpc[2] |= in.read(1) << 6;
                    f.lpc[2] |= in.read(1);
                    f.lpc[4] = in.read(1);
                }
                f.rms |= in.read(1) << 3;
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2) << 3;
                    f.gain[s] |= in.read(2);
                }
            }
            case 3 -> {
                if (lpc) {
                    f.lpc[0] |= in.read(2) << 2;
                    f.lpc[1] |= in.read(2) << 1;
                    f.lpc[2] |= in.read(5) << 1;
                }
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(3);
                    f.signs[s] = in.read(layout.signBits);
                    f.gain[s] |= in.read(1) << 2;
                }
            }
            default -> {
                if (lpc) {
                    f.lpc[1] |= in.read(1) << 3;
                    f.lpc[3] = in.read(6);
                }
                f.rms |= in.read(3);
                for (var s = 0; s < n; s++) {
                    f.positions[s] = in.readLong(layout.positionBits);
                    f.gain[s] |= in.read(3) << 3;
                }
            }
        }
    }

    /**
     * Reads one category of the MPE wideband syntax (Tables 3.33 to 3.37).
     */
    private void mpeWidebandCategory(int category, MsbBitReader in, CelpFrame f) {
        var n = layout.subframes;
        switch (category) {
            case 0 -> {
                readRateControl(in, f);
                widebandLpc(0, in, f);
                f.rms |= in.read(2) << 4;
            }
            case 1 -> {
                widebandLpc(1, in, f);
                f.signalMode = in.read(2);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(3) << 6;
                }
            }
            case 2 -> {
                widebandLpc(2, in, f);
                f.rms |= in.read(1) << 3;
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2) << 4;
                    f.gain[s] |= in.read(2);
                }
            }
            case 3 -> {
                widebandLpc(3, in, f);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2) << 2;
                    f.signs[s] = in.read(layout.signBits);
                    f.gain[s] |= in.read(1) << 2;
                }
            }
            default -> {
                widebandLpc(4, in, f);
                f.rms |= in.read(3);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2);
                    f.positions[s] = in.readLong(layout.positionBits);
                    f.gain[s] |= in.read(4) << 3;
                }
            }
        }
    }

    /**
     * Reads one category of the RPE wideband syntax (Tables 3.38 to 3.42).
     */
    private void rpeCategory(int category, MsbBitReader in, CelpFrame f) {
        var n = layout.subframes;
        switch (category) {
            case 0 -> {
                readRateControl(in, f);
                widebandLpc(0, in, f);
                for (var s = 0; s < n; s++) {
                    f.gain[s] |= in.read(3) << 3;
                    f.fixedGain[s] |= s == 0 ? in.read(2) << 3 : in.read(1) << 2;
                }
            }
            case 1 -> {
                widebandLpc(1, in, f);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(3) << 5;
                }
            }
            case 2 -> {
                widebandLpc(2, in, f);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2) << 3;
                    f.gain[s] |= in.read(1) << 2;
                    f.fixedGain[s] |= s == 0 ? in.read(1) << 2 : in.read(1) << 1;
                }
            }
            case 3 -> {
                widebandLpc(3, in, f);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(2) << 1;
                }
            }
            default -> {
                widebandLpc(4, in, f);
                for (var s = 0; s < n; s++) {
                    f.delay[s] |= in.read(1);
                    f.positions[s] = in.read(layout.positionBits);
                    f.gain[s] |= in.read(2);
                    f.fixedGain[s] |= s == 0 ? in.read(2) : in.read(1);
                }
            }
        }
    }

    /**
     * Reads {@code interpolation_flag} and {@code LPC_Present} when fine rate control is on.
     */
    private void readRateControl(MsbBitReader in, CelpFrame f) {
        if (fineRateControl) {
            f.interpolation = in.readFlag();
            f.lpcPresent = in.readFlag();
        }
    }

    /**
     * Reads the wideband LPC index bits of one category, shared by the MPE and RPE wideband syntax.
     */
    private static void widebandLpc(int category, MsbBitReader in, CelpFrame f) {
        if (!f.lpcPresent) {
            return;
        }
        var lpc = f.lpc;
        switch (category) {
            case 0 -> {
                lpc[0] = in.read(5);
                lpc[1] |= in.read(2);
                lpc[2] |= in.read(1) << 6;
                lpc[2] |= in.read(5);
                lpc[4] = in.read(1);
                lpc[5] |= in.read(1);
            }
            case 1 -> {
                lpc[1] |= in.read(2) << 2;
                lpc[2] |= in.read(1) << 5;
                lpc[5] |= in.read(1) << 1;
                lpc[6] |= in.read(2);
            }
            case 2 -> {
                lpc[1] |= in.read(1) << 4;
                lpc[3] |= in.read(1) << 6;
                lpc[3] |= in.read(1) << 1;
                lpc[5] |= in.read(1) << 2;
                lpc[6] |= in.read(1) << 3;
                lpc[7] |= in.read(1) << 6;
                lpc[7] |= in.read(1) << 4;
                lpc[7] |= in.read(2);
                lpc[9] = in.read(1);
            }
            case 3 -> {
                lpc[3] |= in.read(3) << 2;
                lpc[3] |= in.read(1);
                lpc[5] |= in.read(1) << 3;
                lpc[6] |= in.read(1) << 2;
                lpc[7] |= in.read(1) << 5;
                lpc[7] |= in.read(2) << 2;
                lpc[8] |= in.read(4) << 1;
            }
            default -> {
                lpc[3] |= in.read(1) << 5;
                lpc[8] |= in.read(1);
            }
        }
    }

    /**
     * Reads a {@code CelpBRSenhFrame} (Table 3.14).
     *
     * @param layer the enhancement layer, from 0
     * @param in the access unit
     * @param frame the fields
     */
    void readBitrateLayer(int layer, MsbBitReader in, CelpFrame frame) {
        for (var s = 0; s < layout.subframes; s++) {
            frame.enhancementPositions[layer][s] = in.read(layout.enhancementPositionBits);
            frame.enhancementSigns[layer][s] = in.read(layout.enhancementSignBits);
            frame.enhancementGain[layer][s] = in.read(4);
        }
    }

    /**
     * Reads a {@code CelpBWSenhFrame} (Tables 3.15 and 3.19).
     *
     * @param in the access unit
     * @param frame the fields
     */
    void readBandwidthLayer(MsbBitReader in, CelpFrame frame) {
        readBandwidthLsp(in, frame, false);
        for (var s = 0; s < layout.bandwidthSubframes; s++) {
            frame.bandwidthDelay[s] = in.read(3);
            frame.bandwidthPositions[s] = in.readLong(bandwidthPositionBits);
            frame.bandwidthSigns[s] = in.read(bandwidthSignBits);
            frame.bandwidthGain[s] = in.read(11);
        }
    }

    /**
     * Reads {@code BandScalable_LSP} (Table 3.19), or {@code SID_BandScalable_LSP} (Table 3.47) of a SID frame.
     *
     * @param in the access unit
     * @param frame the fields
     * @param sid whether to read the SID form, which leaves out the last two indices
     */
    void readBandwidthLsp(MsbBitReader in, CelpFrame frame, boolean sid) {
        frame.lpc[5] = in.read(4);
        frame.lpc[6] = in.read(7);
        frame.lpc[7] = in.read(4);
        frame.lpc[8] = in.read(6);
        if (!sid) {
            frame.lpc[9] = in.read(7);
            frame.lpc[10] = in.read(4);
        }
    }
}
