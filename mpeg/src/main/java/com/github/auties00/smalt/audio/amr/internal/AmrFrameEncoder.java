package com.github.auties00.smalt.audio.amr.internal;

import com.github.auties00.smalt.audio.amr.internal.nb.NbEncoder;
import com.github.auties00.smalt.audio.amr.internal.wbenc.WbEncoder;

/**
 * Encodes 16-bit samples into the RFC 4867 storage-format frames of one AMR codec, bit-exactly with the 3GPP
 * fixed-point reference encoder of that codec, and applies its encoder homing procedure.
 *
 * <p>Instances are not thread-safe.
 */
public sealed interface AmrFrameEncoder permits NbEncoder, WbEncoder {
    /**
     * Returns a new encoder in its home state.
     *
     * @param wideband {@code true} for AMR-WB, {@code false} for AMR-NB
     * @param dtx whether voice activity detection and discontinuous transmission are on
     * @param vad2 whether an AMR-NB encoder detects voice activity with VAD option 2 instead of option 1
     * @return the encoder
     */
    static AmrFrameEncoder create(boolean wideband, boolean dtx, boolean vad2) {
        return wideband ? new WbEncoder(dtx) : new NbEncoder(dtx, vad2);
    }

    /**
     * Encodes one frame of samples, 160 for AMR-NB and 320 for AMR-WB, into one storage-format frame, header byte
     * included.
     *
     * <p>With discontinuous transmission on, an inactive frame comes out as a 6-byte comfort noise (SID) frame or a
     * 1-byte no-data frame.
     *
     * @param mode the codec mode, which is the frame type of a speech frame: 0 to 7 for AMR-NB, 0 to 8 for AMR-WB
     * @param pcm the samples
     * @param pcmOffset the index of the first sample
     * @param out receives the frame, with room for 32 bytes (AMR-NB) or 61 bytes (AMR-WB)
     * @param outOffset the index of the first byte written
     * @return the number of bytes written
     * @throws IllegalArgumentException if {@code mode} is out of range
     */
    int encode(int mode, short[] pcm, int pcmOffset, byte[] out, int outOffset);

    /**
     * Resets the encoder to its home state.
     */
    void reset();
}
