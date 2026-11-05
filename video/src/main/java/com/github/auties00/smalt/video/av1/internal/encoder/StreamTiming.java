package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.av1.Av1TimingInfo;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Describes the timing information an inter encoder's sequence header signals.
 *
 * @param mode what is signaled
 * @param timeBase the time base of the frames' timestamps, whose unit is the display clock tick of
 * the decoder model
 * @param frameRate the nominal frame rate, the picture interval of a constant frame rate and the
 * decoding clock tick of the decoder model
 */
public record StreamTiming(Av1TimingInfo mode, Rational timeBase, Rational frameRate) {
    /**
     * The timing of a stream that signals none.
     */
    public static final StreamTiming NONE = new StreamTiming(Av1TimingInfo.NONE, new Rational(1, 90_000),
            new Rational(30, 1));

    /**
     * The frames a shown frame's presentation follows the removal of the last key frame by, beyond
     * its distance in display order, and the initial display delay of the display model.
     */
    static final int DISPLAY_DELAY_FRAMES = 8;

    /**
     * The length of the decoder and encoder buffer delays, in bits
     * ({@code encoder_decoder_buffer_delay_length}).
     */
    private static final int BUFFER_DELAY_BITS = 16;

    /**
     * The decoder and encoder buffer delays, in 1/90000 seconds: half a second.
     */
    private static final int BUFFER_DELAY = 90_000 >> 1;

    /**
     * The length of the buffer removal times, in bits.
     */
    static final int REMOVAL_TIME_BITS = 16;

    /**
     * The length of the presentation times, in bits.
     */
    static final int PRESENTATION_TIME_BITS = 32;

    /**
     * Sets the timing fields of a sequence header: the timing information, the decoder model and
     * the display model of the first operating point.
     *
     * @param seq the sequence header
     * @throws IllegalArgumentException if a tick does not fit the header's 32-bit fields
     */
    void apply(SequenceHeader seq) {
        seq.timingInfoPresent = mode != Av1TimingInfo.NONE;
        seq.decoderModelInfoPresent = mode == Av1TimingInfo.DECODER_MODEL;
        seq.displayModelInfoPresent = mode != Av1TimingInfo.NONE;
        switch (mode) {
            case NONE -> {
                return;
            }
            case CONSTANT_FRAME_RATE -> {
                seq.numUnitsInTick = checked(frameRate.denominator());
                seq.timeScale = checked(frameRate.numerator());
                seq.equalPictureInterval = true;
                seq.numTicksPerPicture = 1;
            }
            case DECODER_MODEL -> {
                seq.numUnitsInTick = checked(timeBase.numerator());
                seq.timeScale = checked(timeBase.denominator());
                seq.equalPictureInterval = false;
                seq.encoderDecoderBufferDelayLength = BUFFER_DELAY_BITS;
                seq.numUnitsInDecodingTick = checked(Math.max(1, Math.round(
                        (double) timeBase.denominator() * frameRate.denominator() / frameRate.numerator())));
                seq.bufferRemovalDelayLength = REMOVAL_TIME_BITS;
                seq.framePresentationDelayLength = PRESENTATION_TIME_BITS;
                seq.decoderModelParamPresent[0] = true;
                seq.decoderBufferDelay[0] = BUFFER_DELAY;
                seq.encoderBufferDelay[0] = BUFFER_DELAY;
                seq.lowDelayMode[0] = false;
            }
        }
        seq.displayModelParamPresent[0] = true;
        seq.initialDisplayDelay[0] = DISPLAY_DELAY_FRAMES;
    }

    /**
     * Returns the frame interval of the nominal frame rate, in time base units.
     *
     * @return the interval, at least 1
     */
    long frameTicks() {
        return Math.max(1, Math.round((double) timeBase.denominator() * frameRate.denominator()
                / ((double) timeBase.numerator() * frameRate.numerator())));
    }

    /**
     * Returns a tick count after checking that it fits an unsigned 32-bit field.
     *
     * @param value the count
     * @return the count
     * @throws IllegalArgumentException if it does not fit
     */
    private static long checked(long value) {
        if (value <= 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("timing value " + value + " does not fit the sequence header");
        }
        return value;
    }
}
