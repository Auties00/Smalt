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
 * rate the level is chosen for
 * @param bitrate the target bitrate of rate control in bits per second, which the level's bitrate
 * covers, or 0 when rate control does not bound the bitrate
 * @param decodesPerFrame the most frames the encoder decodes per shown frame over a stretch of
 * frames, at least 1, which scales the decoded sample rate and header rate the level is chosen for
 * @param displayDelay the initial display delay of the display model, 1 to 10 frames: the frame
 * buffers the encoder's references fill before the first frame is shown
 */
public record StreamTiming(Av1TimingInfo mode, Rational timeBase, Rational frameRate, long bitrate,
                           double decodesPerFrame, int displayDelay) {
    /**
     * The timing of a stream that signals none.
     */
    public static final StreamTiming NONE = new StreamTiming(Av1TimingInfo.NONE, new Rational(1, 90_000),
            new Rational(30, 1), 0, 1, StreamTiming.DISPLAY_DELAY_FRAMES);

    /**
     * Checks the decode ratio and the display delay.
     *
     * @throws IllegalArgumentException if {@code decodesPerFrame} is below 1 or not finite, or
     * {@code displayDelay} is outside 1 to 10
     */
    public StreamTiming {
        if (!(decodesPerFrame >= 1) || Double.isInfinite(decodesPerFrame)) {
            throw new IllegalArgumentException("decodes per frame " + decodesPerFrame);
        }
        if (displayDelay < 1 || displayDelay > 10) {
            throw new IllegalArgumentException("display delay " + displayDelay);
        }
    }

    /**
     * The initial display delay of the good-quality loop, in frames: the frames the buffer pool
     * holds when the first frame is shown, which leaves time to decode the frames a pyramid codes
     * ahead of their display.
     */
    public static final int DISPLAY_DELAY_FRAMES = 8;

    /**
     * The length of the decoder and encoder buffer delays, in bits
     * ({@code encoder_decoder_buffer_delay_length}), enough for a second.
     */
    private static final int BUFFER_DELAY_BITS = 17;

    /**
     * The sum of the decoder and encoder buffer delays, in 1/90000 seconds: the size of the level's
     * smoothing buffer divided by its bitrate, one second, so the smoothing buffer never overflows.
     */
    private static final int BUFFER_DELAYS = 90_000;

    /**
     * The longest decoder buffer delay, in 1/90000 seconds, which leaves an encoder buffer delay for
     * the frame groups before a key frame to arrive in.
     */
    private static final int MAX_DECODER_DELAY = 70_000;

    /**
     * The decoder buffer delay of a stream without a defined level, in 1/90000 seconds.
     */
    private static final int UNLEVELED_DECODER_DELAY = 45_000;

    /**
     * The decoding clock ticks of a frame interval the decoding tick aims at, which lets the
     * removal times follow the decoding times of frames shorter than an interval.
     */
    private static final int TICKS_PER_FRAME = 16;

    /**
     * The length of the buffer removal times, in bits.
     */
    static final int REMOVAL_TIME_BITS = 32;

    /**
     * The length of the presentation times, in bits.
     */
    static final int PRESENTATION_TIME_BITS = 32;

    /**
     * Sets the timing fields of a sequence header: the timing information, the decoder model and
     * the display model of the first operating point. With timing, the level of every operating
     * point becomes the lowest that admits the largest picture size at the frame rate and covers
     * the bitrate.
     *
     * @param seq the sequence header, with its profile, largest size and operating points set
     * @throws IllegalArgumentException if a tick does not fit the header's 32-bit fields
     */
    void apply(SequenceHeader seq) {
        seq.timingInfoPresent = mode != Av1TimingInfo.NONE;
        seq.decoderModelInfoPresent = mode == Av1TimingInfo.DECODER_MODEL;
        seq.displayModelInfoPresent = mode != Av1TimingInfo.NONE;
        if (mode == Av1TimingInfo.NONE) {
            return;
        }
        var fps = (double) frameRate.numerator() / frameRate.denominator();
        var level = LevelLimits.level(seq.maxWidth, seq.maxHeight, fps, fps * decodesPerFrame, bitrate,
                seq.profile);
        for (var i = 0; i < seq.numOperatingPoints; i++) {
            seq.majorLevel[i] = 2 + (level >> 2);
            seq.minorLevel[i] = level & 3;
            seq.tier[i] = 0;
        }
        if (mode == Av1TimingInfo.CONSTANT_FRAME_RATE) {
            seq.numUnitsInTick = checked(frameRate.denominator());
            seq.timeScale = checked(frameRate.numerator());
            seq.equalPictureInterval = true;
            seq.numTicksPerPicture = 1;
        } else {
            // the time scale grows with the display tick when a frame interval has too few units
            // for the decoding tick to divide it
            var units = (double) timeBase.denominator() * frameRate.denominator()
                    / ((double) timeBase.numerator() * frameRate.numerator());
            var scale = units >= TICKS_PER_FRAME ? 1
                    : Math.max(1, Math.min((long) Math.ceil(TICKS_PER_FRAME / units),
                    0xFFFFFFFFL / Math.max(timeBase.numerator(), timeBase.denominator())));
            seq.numUnitsInTick = checked(timeBase.numerator() * scale);
            seq.timeScale = checked(timeBase.denominator() * scale);
            seq.equalPictureInterval = false;
            seq.numUnitsInDecodingTick = checked(Math.max(1,
                    (long) Math.floor(units * seq.numUnitsInTick / TICKS_PER_FRAME)));
            seq.encoderDecoderBufferDelayLength = BUFFER_DELAY_BITS;
            seq.bufferRemovalDelayLength = REMOVAL_TIME_BITS;
            seq.framePresentationDelayLength = PRESENTATION_TIME_BITS;
            seq.decoderModelParamPresent[0] = true;
            var decoderDelay = decoderDelay(seq, level, fps);
            seq.decoderBufferDelay[0] = decoderDelay;
            seq.encoderBufferDelay[0] = BUFFER_DELAYS - decoderDelay;
            seq.lowDelayMode[0] = false;
        }
        seq.displayModelParamPresent[0] = true;
        seq.initialDisplayDelay[0] = displayDelay;
    }

    /**
     * Returns the decoder buffer delay of a stream: the time the largest frame the level's
     * compression ratio admits takes to arrive at the level's bitrate, so that a key frame starting
     * the decoding arrives before its removal, at most {@value #MAX_DECODER_DELAY}.
     *
     * @param seq the sequence header
     * @param level the {@code seq_level_idx}
     * @param fps the frame rate
     * @return the delay, in 1/90000 seconds
     */
    private static long decoderDelay(SequenceHeader seq, int level, double fps) {
        var limits = LevelLimits.of(level);
        if (limits == null) {
            return UNLEVELED_DECODER_DELAY;
        }
        var seconds = limits.largestFrameBits(seq.maxWidth, seq.maxHeight, fps, seq.profile)
                / limits.bitrate(seq.profile);
        return Math.clamp((long) Math.ceil(seconds * 90_000), 1, MAX_DECODER_DELAY);
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
