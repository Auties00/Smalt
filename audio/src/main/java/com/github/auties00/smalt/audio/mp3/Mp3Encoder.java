package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float samples into MPEG audio Layer III (MP3) frames.
 *
 * <p>Each packet is one complete frame, header included, so writing the packets back to back
 * gives an {@code .mp3} elementary stream. Frames at 32, 44.1 and 48 kHz are MPEG-1 and hold 1152
 * samples per channel; at 16, 22.05 and 24 kHz they are MPEG-2 and at 8, 11.025 and 12 kHz
 * MPEG-2.5, holding 576. MP3 needs no out-of-band configuration, so {@link #codecConfig()} is
 * empty. Packet timestamps count samples from the first input sample; a decoder outputs
 * {@link #delay()} samples before the first input sample, which the container signals (an MP4 edit
 * list, for example).
 *
 * <p>Coding tools: the polyphase filterbank and hybrid MDCT of the standard with long, start,
 * short and stop blocks switched by an attack detector, a psychoacoustic model with tonality,
 * spreading, the threshold in quiet and pre-echo control, per-frame mid/side stereo in joint
 * stereo mode, a noise-targeting quantizer with scalefactors, {@code scalefac_scale},
 * preemphasis, subblock gains and scalefactor sharing between granules, Huffman tables and
 * region boundaries chosen for the fewest bits, and the bit reservoir. Intensity stereo and mixed
 * blocks are not used. Frames are bit-exact functions of the input and options: encoding the
 * same samples twice produces the same bytes.
 *
 * <p>The encoder buffers input of any length and emits frames as soon as no later frame can
 * still place reservoir data in them; {@link #flush(Consumer)} pads the input with silence until
 * every input sample is covered and emits the remaining frames. Input samples outside
 * {@code [-1, 1]} are clipped; NaN samples are coded as silence.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation is written from ISO/IEC 11172-3 and ISO/IEC 13818-3 (the analysis
 * window of Table C.1, the Huffman tables of Table B.7, the scalefactor bands of Table B.8 and
 * 13818-3 Table B.2) and from published psychoacoustic literature; no other encoder's source code
 * was used.
 */
public final class Mp3Encoder implements AudioEncoder {
    /**
     * The number of samples between the first input sample and the first sample a decoder
     * outputs for it: one granule of MDCT overlap plus the 481-sample delay of the polyphase
     * analysis and synthesis filterbanks.
     */
    static final int DELAY = 576 + 481;

    /**
     * The options in use: those the encoder was created with, with the bitrate of the last
     * {@link #setTargetBitrate(long)}.
     */
    private Mp3EncoderOptions options;

    /**
     * The frame encoder.
     */
    private final Mp3FrameEncoder engine;

    /**
     * The number of samples per channel of one frame.
     */
    private final int frameSize;

    /**
     * The input granule being filled, one plane per channel.
     */
    private final float[][] granule;

    /**
     * The number of samples per channel in {@link #granule}.
     */
    private int filled;

    /**
     * The number of input samples per channel received.
     */
    private long received;

    /**
     * The number of frames produced by the engine.
     */
    private long frames;

    /**
     * The number of packets emitted.
     */
    private long emitted;

    /**
     * The frames produced but not yet emitted.
     */
    private final List<byte[]> ready = new ArrayList<>();

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Constructs an encoder for 44.1 kHz stereo input at 128 kbit/s constant bitrate, the settings
     * of {@link Mp3EncoderOptions#defaults()}.
     */
    public Mp3Encoder() {
        this(Mp3EncoderOptions.defaults());
    }

    /**
     * Creates an encoder.
     *
     * @param options the settings
     * @throws NullPointerException if {@code options} is {@code null}
     */
    public Mp3Encoder(Mp3EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.engine = new Mp3FrameEncoder(options, Mp3EncoderKernel.create(), frame -> {
            ready.add(frame);
            frames++;
        });
        this.frameSize = engine.frameSize();
        this.granule = new float[options.layout().channels()][576];
    }

    /**
     * Returns the options in use: those the encoder was created with, with the bitrate of the last
     * {@link #setTargetBitrate(long)} when one was set.
     *
     * @return the options
     */
    public Mp3EncoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * <p>In constant bitrate mode the frames take the highest bitrate of the stream's MPEG version not above the
     * target, or its lowest when the target is below every one. In average bitrate mode the target, limited to the
     * version's range of bitrates, becomes the bitrate the noise level is steered toward, from the next coded frame
     * on by the bitrate of the last quarter second rather than by the average since the start. An automatic bandwidth
     * follows the new bitrate, and {@link #options()} reports it.
     *
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive
     * @throws IllegalStateException if the encoder runs in {@link Mp3BitrateMode#VBR} mode, which has no target
     * bitrate
     */
    @Override
    public void setTargetBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("invalid bitrate " + bitsPerSecond);
        }
        if (options.bitrateMode() == Mp3BitrateMode.VBR) {
            throw new IllegalStateException("a variable bitrate encoder has no target bitrate");
        }
        var table = Mp3EncoderTables.BITRATES[Mp3EncoderTables.sampleRateIndex(options.sampleRate()) < 3 ? 0 : 1];
        int bitrate;
        if (options.bitrateMode() == Mp3BitrateMode.CBR) {
            var index = 1;
            for (var i = 1; i < table.length; i++) {
                if (table[i] * 1000L <= bitsPerSecond) {
                    index = i;
                }
            }
            bitrate = table[index] * 1000;
        } else {
            bitrate = (int) Math.clamp(bitsPerSecond, table[1] * 1000L, table[table.length - 1] * 1000L);
        }
        options = options.toBuilder().bitrate(bitrate).build();
        engine.retarget(options);
    }

    @Override
    public Codec codec() {
        return Codec.MP3;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * {@inheritDoc}
     *
     * @return 1152 for MPEG-1 rates, 576 for MPEG-2 and MPEG-2.5 rates
     */
    @Override
    public int frameSize() {
        return frameSize;
    }

    /**
     * {@inheritDoc}
     *
     * @return 1057: one granule of MDCT overlap (576 samples) plus the delay of the polyphase
     * analysis and synthesis filterbanks (481 samples)
     */
    @Override
    public int delay() {
        return DELAY;
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: MP3 frames carry their configuration in their headers
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout().speakers()
                    + " does not match the encoder's " + options.sampleRate() + " Hz " + options.layout().speakers());
        }
        var planes = input.planes();
        var count = input.frames();
        received += count;
        var offset = 0;
        while (offset < count) {
            var take = Math.min(count - offset, 576 - filled);
            for (var ch = 0; ch < granule.length; ch++) {
                var source = planes[ch];
                var target = granule[ch];
                for (var i = 0; i < take; i++) {
                    var x = source[offset + i];
                    target[filled + i] = x != x ? 0 : Math.clamp(x, -1.0f, 1.0f);
                }
            }
            filled += take;
            offset += take;
            if (filled == 576) {
                engine.push(granule, 0);
                filled = 0;
            }
        }
        drain(output);
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
        var needed = (received + DELAY + frameSize - 1) / frameSize;
        while (true) {
            var produced = frames + pendingFrames();
            if (produced >= needed && engine.atFrameBoundary() && filled == 0) {
                break;
            }
            for (var ch = 0; ch < granule.length; ch++) {
                java.util.Arrays.fill(granule[ch], filled, 576, 0);
            }
            engine.push(granule, 0);
            filled = 0;
        }
        engine.finish();
        drain(output);
    }

    /**
     * Returns the number of frames the engine has written but not handed out.
     *
     * @return the count
     */
    private long pendingFrames() {
        return engine.writtenFrames() - frames;
    }

    /**
     * Emits the frames the engine handed out.
     *
     * @param output receives the packets
     */
    private void drain(Consumer<Packet> output) {
        for (var frame : ready) {
            var timestamp = emitted * frameSize;
            output.accept(new Packet(0, frame, timestamp, timestamp, frameSize, true));
            emitted++;
        }
        ready.clear();
    }
}
