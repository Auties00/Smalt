/* Ports the ISO/IEC 23003-3:2019 reference software (ct_sbrdec.c: the SBR_DEC state and initSbrDec).
   Copyright Coding Technologies and ISO/IEC 2002; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the state the enhanced spectral band replication decoders of every element of a USAC stream share: the
 * QMF banks, the buffer layout and the write offset of the most recent channel.
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrContext {
    /**
     * The QMF slot offset of the frame's first slot in the core band buffers.
     */
    static final int READ_OFFSET = 2;

    /**
     * The extra delay, in QMF slots, of the core band buffers of the harmonic transposer.
     */
    static final int TRANSPOSER_DELAY = 32;

    /**
     * The delay of the hybrid filterbank of MPEG Surround, in QMF slots.
     */
    static final int HYBRID_DELAY = 6;

    /**
     * The SBR ratio index: 1 for 4:1, 2 for 8:3 and 3 for 2:1.
     */
    final int ratioIndex;

    /**
     * The core frame length.
     */
    final int coreLength;

    /**
     * The output sampling rate.
     */
    final int outputRate;

    /**
     * The number of QMF slots per frame.
     */
    final int columns;

    /**
     * The number of QMF slots per SBR time slot.
     */
    final int rate;

    /**
     * The write offset of the SBR channels that do not take their core band from MPEG Surround.
     */
    final int sbrOffset;

    /**
     * The write offset of the SBR channels that take their core band from MPEG Surround.
     */
    final int hybridOffset;

    /**
     * The number of history samples of the analysis input buffer.
     */
    final int history;

    /**
     * The QMF banks.
     */
    final EsbrQmf qmf;

    /**
     * The write offset of the most recently processed channel, which a band table reset of any channel uses.
     */
    int writeOffset;

    /**
     * Every SBR channel of the stream.
     */
    final List<EsbrChannel> channels = new ArrayList<>();

    /**
     * Constructs the shared state.
     *
     * @param ratioIndex the SBR ratio index, 1 to 3
     * @param coreLength the core frame length
     * @param outputRate the output sampling rate
     */
    EsbrContext(int ratioIndex, int coreLength, int outputRate) {
        this.ratioIndex = ratioIndex;
        this.coreLength = coreLength;
        this.outputRate = outputRate;
        var bands = switch (ratioIndex) {
            case 1 -> 16;
            case 2 -> 24;
            default -> 32;
        };
        var outLength = switch (ratioIndex) {
            case 1 -> 4 * coreLength;
            case 2 -> 8 * coreLength / 3;
            default -> 2 * coreLength;
        };
        this.columns = outLength / 64;
        this.rate = ratioIndex == 1 ? 4 : 2;
        this.sbrOffset = ratioIndex == 1 ? 2 * 6 + 2 : 6 + 2;
        this.hybridOffset = 6 + 2;
        this.history = 10 * bands - bands;
        this.qmf = new EsbrQmf(bands);
    }

    /**
     * {@return whether the stream uses the 4:1 ratio}
     */
    boolean sbr41() {
        return ratioIndex == 1;
    }
}
