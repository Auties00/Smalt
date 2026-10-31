package com.github.auties00.smalt.audio.speex;

/**
 * A decoder of one Speex layer: the narrowband decoder, or a sub-band decoder over the layer below it.
 */
sealed interface SpeexFrameDecoder permits SpeexNarrowbandDecoder, SpeexSubbandDecoder {
    /**
     * {@return the samples of a frame}
     */
    int frameSize();

    /**
     * Decodes one frame into {@code out}, or conceals a lost frame when {@code bits} is {@code null}.
     *
     * @param bits the packet, positioned at the frame, or {@code null} for a lost frame
     * @param out receives {@link #frameSize()} samples
     * @param offset the index of the first sample
     * @return 0 for a decoded frame, -1 at the end of the packet's frames, -2 for a corrupt frame
     */
    int decode(SpeexBits bits, short[] out, int offset);

    /**
     * Marks the layer as the low band of a sub-band decoder.
     */
    void setWideband();

    /**
     * Attaches the stereo decoder the in-band stereo requests update.
     *
     * @param stereo the stereo decoder
     */
    void setStereo(SpeexStereoDecoder stereo);

    /**
     * Makes the decoder copy the innovation of each frame into {@code target}, as the layer above reads it.
     *
     * @param target the array
     * @param offset the index of the first innovation sample
     */
    void setInnovationSave(short[] target, int offset);

    /**
     * {@return the gain of the synthesis filter at half the sampling rate of each subframe of the last frame}
     */
    int[] piGain();

    /**
     * Stores the root mean square of the excitation of each subframe of the last frame.
     *
     * @param target receives one value per subframe
     */
    void excitationRms(short[] target);

    /**
     * {@return whether the stream is in discontinuous transmission}
     */
    boolean dtx();
}
