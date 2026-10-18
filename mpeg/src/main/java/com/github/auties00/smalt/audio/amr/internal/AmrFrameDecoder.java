package com.github.auties00.smalt.audio.amr.internal;

import com.github.auties00.smalt.audio.amr.internal.nb.NbDecoder;
import com.github.auties00.smalt.audio.amr.internal.wb.WbDecoder;

/**
 * Decodes the RFC 4867 storage-format frames of one AMR codec into 16-bit samples, bit-exactly with the 3GPP
 * fixed-point reference decoder of that codec, and applies its decoder homing procedure.
 *
 * <p>Every input decodes without an exception: a frame that is damaged, cut short or of an unusable type is
 * concealed. Instances are not thread-safe.
 */
public sealed interface AmrFrameDecoder permits NbDecoder, WbDecoder {
    /**
     * Returns a new decoder in its home state.
     *
     * @param wideband {@code true} for AMR-WB, {@code false} for AMR-NB
     * @return the decoder
     */
    static AmrFrameDecoder create(boolean wideband) {
        return wideband ? new WbDecoder() : new NbDecoder();
    }

    /**
     * Decodes one storage-format frame into one frame of samples: 160 for AMR-NB, 320 for AMR-WB.
     *
     * @param frameType the 4-bit frame type of the header byte
     * @param quality the quality bit of the header byte; {@code false} marks a damaged frame
     * @param payload the array holding the bytes that follow the header byte
     * @param offset the index of the first payload byte
     * @param length the number of payload bytes available, fewer than the frame type needs for a frame cut short
     * @param out receives the samples
     * @param outOffset the index of the first sample written
     */
    void decode(int frameType, boolean quality, byte[] payload, int offset, int length, short[] out, int outOffset);

    /**
     * Resets the decoder to its home state.
     */
    void reset();
}
