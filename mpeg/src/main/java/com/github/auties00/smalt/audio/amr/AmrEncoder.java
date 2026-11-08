package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.amr.internal.AmrFrameEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes 8 kHz mono samples into AMR-NB or 16 kHz mono samples into AMR-WB, bit-exactly with
 * the 3GPP fixed-point reference encoders (TS 26.073 and TS 26.173).
 *
 * <p>Every packet is one 20 millisecond frame (160 or 320 samples) in the RFC 4867 storage
 * format: a header byte with the frame type and the quality bit set, followed by the speech bits,
 * which is what an {@code .amr} file and the MP4 {@code samr}/{@code sawb} sample entries store.
 * Packet timestamps count samples from the first input sample, and each packet lasts one frame.
 * With discontinuous transmission on, frames of background noise come out as 6-byte comfort
 * noise (SID) frames and 1-byte no-data frames, which keep the timeline intact.
 *
 * <p>The coded frame trails the input by the encoder's look-ahead, so a decoder outputs
 * {@link #delay()} samples before the first input sample; containers that can signal it (an MP4
 * edit list) remove them. The encoder buffers input of any length and emits a packet for every
 * complete frame; {@link #flush(Consumer)} pads the input with silence until the last input sample,
 * delayed, is covered. Samples are converted to 16 bits by rounding and clipping; AMR-NB keeps the
 * 13 most significant bits of each, as the reference encoder does. A frame consisting entirely
 * of the encoder homing pattern resets the encoder after it is coded, as TS 26.073 and TS 26.173
 * require.
 *
 * <p>Instances are not thread-safe.
 */
public final class AmrEncoder implements AudioEncoder {
    /**
     * The scale from the {@code [-1, 1]} range to 16-bit samples.
     */
    private static final float SCALE = 32768f;

    /**
     * The delay of AMR-NB between the input and the decoded output, in samples: the 5 millisecond
     * look-ahead of the linear prediction analysis.
     */
    private static final int NB_DELAY = 40;

    /**
     * The delay of AMR-WB between the input and the decoded output, in samples at 16 kHz.
     *
     * @implNote This implementation's value is measured: the 5 millisecond look-ahead plus the
     * group delays of the 16 to 12.8 kHz decimation in the encoder and the 12.8 to 16 kHz
     * interpolation in the decoder, rounded to the sample that maximizes the correlation of the
     * decoded output with the input.
     */
    private static final int WB_DELAY = 95;

    /**
     * The largest storage-format frame, header included.
     */
    private static final int MAX_FRAME_BYTES = 61;

    /**
     * The options the encoder was created with.
     */
    private final AmrEncoderOptions options;

    /**
     * The frame encoder of the codec.
     */
    private final AmrFrameEncoder coder;

    /**
     * The number of samples of one frame.
     */
    private final int frameSamples;

    /**
     * The input samples waiting for a complete frame.
     */
    private final short[] pending;

    /**
     * The coded frame.
     */
    private final byte[] frame;

    /**
     * The number of valid samples in {@link #pending}.
     */
    private int filled;

    /**
     * The codec mode of the next frame.
     */
    private AmrMode mode;

    /**
     * The number of input samples received.
     */
    private long received;

    /**
     * The number of frames emitted.
     */
    private long frames;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Constructs an encoder with {@link AmrEncoderOptions#defaults()}: AMR-NB at 12.2 kbit/s without discontinuous
     * transmission.
     */
    public AmrEncoder() {
        this(AmrEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param options the codec mode and discontinuous transmission
     */
    public AmrEncoder(AmrEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.mode = options.mode();
        var wide = mode.isWideband();
        this.coder = AmrFrameEncoder.create(wide, options.dtx(), options.vad() == AmrVad.OPTION_2);
        this.frameSamples = wide ? 320 : 160;
        this.pending = new short[frameSamples];
        this.frame = new byte[MAX_FRAME_BYTES];
    }

    /**
     * {@return the options the encoder was created with}
     *
     * <p>{@link #setMode(AmrMode)} does not change them.
     */
    public AmrEncoderOptions options() {
        return options;
    }

    /**
     * {@return the codec mode of the next frame}
     */
    public AmrMode mode() {
        return mode;
    }

    /**
     * Changes the codec mode from the next frame on, as AMR allows on any frame boundary, for
     * example to follow a rate adaptation request.
     *
     * @param mode the new mode, of the same codec as the encoder
     * @throws IllegalArgumentException if {@code mode} belongs to the other codec
     */
    public void setMode(AmrMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode.isWideband() != this.mode.isWideband()) {
            throw new IllegalArgumentException(mode + " does not belong to " + codec().displayName());
        }
        this.mode = mode;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The frames take the mode of the encoder's codec with the highest bitrate not above the target, or its
     * lowest mode when the target is below every one, from the next frame on, as {@link #setMode(AmrMode)} sets
     * it.
     *
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("invalid bitrate " + bitsPerSecond);
        }
        AmrMode chosen = null;
        AmrMode lowest = null;
        for (var candidate : AmrMode.values()) {
            if (candidate.isWideband() != mode.isWideband()) {
                continue;
            }
            if (lowest == null || candidate.bitrate() < lowest.bitrate()) {
                lowest = candidate;
            }
            if (candidate.bitrate() <= bitsPerSecond && (chosen == null || candidate.bitrate() > chosen.bitrate())) {
                chosen = candidate;
            }
        }
        setMode(chosen != null ? chosen : lowest);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Codec#AMR_NB} or {@link Codec#AMR_WB}
     */
    @Override
    public Codec codec() {
        return options.codec();
    }

    /**
     * {@inheritDoc}
     *
     * @return 8000 for AMR-NB, 16000 for AMR-WB
     */
    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link ChannelLayout#MONO}
     */
    @Override
    public ChannelLayout layout() {
        return ChannelLayout.MONO;
    }

    /**
     * {@inheritDoc}
     *
     * @return 160 for AMR-NB, 320 for AMR-WB
     */
    @Override
    public int frameSize() {
        return frameSamples;
    }

    /**
     * {@inheritDoc}
     *
     * @return 40 samples (5 milliseconds) for AMR-NB, 95 samples (about 5.9 milliseconds) for
     * AMR-WB
     */
    @Override
    public int delay() {
        return mode.isWideband() ? WB_DELAY : NB_DELAY;
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: AMR needs no configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the input is not mono at the codec's rate
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != sampleRate() || input.channels() != 1) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout().speakers()
                    + " does not match the encoder's " + sampleRate() + " Hz mono");
        }
        var samples = input.plane(0);
        var count = input.frames();
        received += count;
        for (var i = 0; i < count; i++) {
            pending[filled++] = quantize(samples[i]);
            if (filled == frameSamples) {
                emit(output);
            }
        }
    }

    /**
     * Encodes one frame of 16-bit samples directly, bypassing the float input path.
     *
     * <p>The frame is coded in the current {@link #mode()} and written in the storage format, header byte included.
     * It counts toward the timeline as a frame of buffered input does. This method must not be mixed with buffered
     * input that has not completed a frame.
     *
     * @param samples the samples, {@link #frameSize()} of them from {@code offset}
     * @param offset the index of the first sample
     * @param out receives the frame, with room for {@code mode().frameBytes()} bytes from {@code outOffset}
     * @param outOffset the index of the first byte written
     * @return the number of bytes written: the mode's frame size, or fewer for a comfort noise or no-data frame
     * when discontinuous transmission is on
     * @throws IllegalStateException if buffered input is waiting for a complete frame, or the encoder was flushed
     * @throws IndexOutOfBoundsException if an array is too short
     */
    public int encodeFrame(short[] samples, int offset, byte[] out, int outOffset) {
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (filled != 0) {
            throw new IllegalStateException("buffered input is waiting for a complete frame");
        }
        Objects.checkFromIndexSize(offset, frameSamples, samples.length);
        Objects.checkFromIndexSize(outOffset, mode.frameBytes(), out.length);
        received += frameSamples;
        frames++;
        return code(samples, offset, out, outOffset);
    }

    /**
     * {@inheritDoc}
     *
     * <p>After a flush the encoder accepts no further input.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        if (received == 0) {
            return;
        }
        var needed = (received + delay() + frameSamples - 1) / frameSamples;
        while (frames < needed) {
            Arrays.fill(pending, filled, frameSamples, (short) 0);
            filled = frameSamples;
            emit(output);
        }
    }

    /**
     * Resets the encoder to its initial state, discarding buffered input; the timestamps
     * continue.
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    public void reset() {
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        filled = 0;
        coder.reset();
    }

    /**
     * Codes the buffered frame and hands its packet to {@code output}.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        var size = code(pending, 0, frame, 0);
        filled = 0;
        var timestamp = frames * frameSamples;
        frames++;
        output.accept(new Packet(0, Arrays.copyOf(frame, size), timestamp, timestamp, frameSamples, true));
    }

    /**
     * Codes one frame in the current mode.
     *
     * @param samples the 16-bit input, holding one frame from {@code offset}
     * @param offset the first sample
     * @param out receives the frame
     * @param outOffset the first byte written
     * @return the number of bytes written
     */
    private int code(short[] samples, int offset, byte[] out, int outOffset) {
        return coder.encode(mode.frameType(), samples, offset, out, outOffset);
    }

    /**
     * Converts a sample to 16 bits, rounding and clipping; NaN becomes 0.
     *
     * @param sample the sample in {@code [-1, 1]}
     * @return the 16-bit sample
     */
    private static short quantize(float sample) {
        return (short) Math.clamp(Math.round(sample * SCALE), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
