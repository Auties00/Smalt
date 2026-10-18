package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Buffers planar float PCM into the frames a {@link FrameEncoder} codes and turns each coded
 * frame into a packet, for the public AC-3 and E-AC-3 encoders.
 *
 * <p>The input channels are reordered into the coded order of A/52 table 5.8 and clipped to
 * {@code [-1, 1]} (not-a-number samples become silence). Packets carry consecutive timestamps
 * starting at zero; {@link #flush(Consumer)} pads the input with silence until the frames cover
 * every input sample plus the {@link #DELAY} of the filter bank. Instances are not thread-safe.
 *
 * <p>An E-AC-3 program beyond 5.1 (7.1 or 7.0, see {@link ChannelMap#extended(ChannelLayout)}) is
 * coded as ETSI TS 102 366 clause E.2.8.2 describes it: independent substream 0 carries a 5.1 (or
 * 5.0) downmix whose surround channels mix each side channel with the back channel of its side at
 * -3 dB, and a dependent substream with the custom channel map {@link ChannelMap#EXTENSION_MAP}
 * carries the side and back pairs, which replace the downmixed surrounds in a decoder that renders
 * more than 5.1 channels. Every packet holds the independent frame followed by the dependent one,
 * and the bit rate is divided between them in proportion to their full-bandwidth channels, five
 * to four.
 */
public final class EncoderSession {
    /**
     * The decoder delay in samples: the overlap of the filter bank.
     */
    public static final int DELAY = 256;

    /**
     * The gain of each channel mixed into a surround channel of the 5.1 downmix of a program beyond
     * 5.1: -3 dB, which keeps the power of uncorrelated side and back channels.
     */
    private static final float SURROUND_MIX = (float) Math.sqrt(0.5);

    /**
     * The sampling rate of the input.
     */
    private final int sampleRate;

    /**
     * The layout of the input.
     */
    private final ChannelLayout layout;

    /**
     * The samples per channel of one frame.
     */
    private final int frameSize;

    /**
     * The frame encoder of the independent substream.
     */
    private final FrameEncoder engine;

    /**
     * The frame encoder of the dependent substream, or {@code null} for a program of up to 5.1
     * channels.
     */
    private final FrameEncoder extension;

    /**
     * The input channel of each coded channel of the independent substream.
     */
    private final int[] inputChannel;

    /**
     * The input channel mixed into each coded channel of the independent substream at
     * {@link #SURROUND_MIX} together with {@link #inputChannel}, or {@code -1}.
     */
    private final int[] mixedChannel;

    /**
     * The input channel of each coded channel of the dependent substream.
     */
    private final int[] extensionChannel;

    /**
     * The buffered input of each coded channel of the independent substream.
     */
    private final float[][] pending;

    /**
     * The buffered input of each coded channel of the dependent substream.
     */
    private final float[][] extensionPending;

    /**
     * The number of buffered samples per channel.
     */
    private int buffered;

    /**
     * The number of input samples received.
     */
    private long received;

    /**
     * The number of samples encoded into frames.
     */
    private long encoded;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Creates a session, which for an E-AC-3 program beyond 5.1 codes a dependent substream too.
     *
     * @param sampleRate the sampling rate
     * @param layout a layout {@link ChannelMap#codable(ChannelLayout)} returns, or for E-AC-3 one
     * {@link ChannelMap#codableEnhanced(ChannelLayout)} returns
     * @param bitrate the bit rate in bits per second, of both substreams together
     * @param dialogueLevel the dialogue level in dBFS, from -31 to -1
     * @param blockSwitching whether short transforms are used on transients
     * @param coupling whether channel coupling may be used
     * @param enhanced whether to write E-AC-3 frames
     * @param blocks the blocks per frame
     * @param tools the E-AC-3 tools of the independent substream, {@link EncoderTools#NONE} for AC-3;
     * the dependent substream uses them with its channel map
     * @param fastSearch whether frames of more than one block take the faster search of
     * {@link FrameEncoder} too
     */
    public EncoderSession(int sampleRate, ChannelLayout layout, int bitrate, int dialogueLevel, boolean blockSwitching,
                          boolean coupling, boolean enhanced, int blocks, EncoderTools tools, boolean fastSearch) {
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.frameSize = 256 * blocks;
        var lfe = layout.speakers().contains(Speaker.LOW_FREQUENCY);
        if (enhanced && ChannelMap.extended(layout)) {
            var core = coreBitrate(bitrate);
            var coded = 5 + (lfe ? 1 : 0);
            this.inputChannel = new int[coded];
            this.mixedChannel = new int[coded];
            var front = new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT,
                    Speaker.SIDE_RIGHT};
            for (var ch = 0; ch < 5; ch++) {
                inputChannel[ch] = layout.indexOf(front[ch]);
                mixedChannel[ch] = -1;
            }
            mixedChannel[3] = layout.indexOf(Speaker.BACK_LEFT);
            mixedChannel[4] = layout.indexOf(Speaker.BACK_RIGHT);
            if (lfe) {
                inputChannel[5] = layout.indexOf(Speaker.LOW_FREQUENCY);
                mixedChannel[5] = -1;
            }
            this.extensionChannel = new int[]{layout.indexOf(Speaker.SIDE_LEFT), layout.indexOf(Speaker.SIDE_RIGHT),
                    layout.indexOf(Speaker.BACK_LEFT), layout.indexOf(Speaker.BACK_RIGHT)};
            this.pending = new float[coded][frameSize];
            this.extensionPending = new float[4][frameSize];
            this.engine = new FrameEncoder(7, lfe, sampleRate, core, dialogueLevel, blockSwitching, coupling, true,
                    blocks, tools.withChannelMap(-1), fastSearch, Ac3Kernels.INSTANCE);
            this.extension = new FrameEncoder(6, false, sampleRate, bitrate - core, dialogueLevel, blockSwitching,
                    coupling, true, blocks, tools.withChannelMap(ChannelMap.EXTENSION_MAP), fastSearch,
                    Ac3Kernels.INSTANCE);
            return;
        }
        var order = ChannelMap.codedSpeakers(layout);
        var coded = order.length + (lfe ? 1 : 0);
        this.inputChannel = new int[coded];
        this.mixedChannel = new int[coded];
        Arrays.fill(mixedChannel, -1);
        for (var ch = 0; ch < order.length; ch++) {
            inputChannel[ch] = layout.indexOf(order[ch]);
        }
        if (lfe) {
            inputChannel[coded - 1] = layout.indexOf(Speaker.LOW_FREQUENCY);
        }
        this.extensionChannel = null;
        this.pending = new float[coded][frameSize];
        this.extensionPending = null;
        this.engine = new FrameEncoder(ChannelMap.codingMode(layout), lfe, sampleRate, bitrate, dialogueLevel,
                blockSwitching, coupling, enhanced, blocks, tools, fastSearch, Ac3Kernels.INSTANCE);
        this.extension = null;
    }

    /**
     * Returns the share of the bit rate of a program beyond 5.1 that its independent substream
     * receives: five ninths, the share of its five full-bandwidth channels among the nine the two
     * substreams code.
     *
     * @param bitrate the bit rate of the program
     * @return the bit rate of the independent substream
     */
    public static int coreBitrate(int bitrate) {
        return (int) (bitrate * 5L / 9);
    }

    /**
     * Returns the samples per channel of one frame.
     *
     * @return {@code 256} times the blocks per frame
     */
    public int frameSize() {
        return frameSize;
    }

    /**
     * Buffers samples and emits a packet for every frame they complete.
     *
     * @param input the samples, in the session's rate and layout
     * @param output receives the packets
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the input's rate or layout differs from the session's
     * @throws IllegalStateException if the session was flushed
     */
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != sampleRate || !input.layout().equals(layout)) {
            throw new IllegalArgumentException("expected " + sampleRate + " Hz " + layout.speakers()
                    + ", got " + input.sampleRate() + " Hz " + input.layout().speakers());
        }
        var frames = input.frames();
        var position = 0;
        while (position < frames) {
            var count = Math.min(frameSize - buffered, frames - position);
            for (var ch = 0; ch < pending.length; ch++) {
                var source = input.plane(inputChannel[ch]);
                var target = pending[ch];
                if (mixedChannel[ch] < 0) {
                    for (var i = 0; i < count; i++) {
                        target[buffered + i] = clip(source[position + i]);
                    }
                } else {
                    var other = input.plane(mixedChannel[ch]);
                    for (var i = 0; i < count; i++) {
                        target[buffered + i] = clip(SURROUND_MIX * (clip(source[position + i])
                                + clip(other[position + i])));
                    }
                }
            }
            if (extension != null) {
                for (var ch = 0; ch < extensionPending.length; ch++) {
                    var source = input.plane(extensionChannel[ch]);
                    var target = extensionPending[ch];
                    for (var i = 0; i < count; i++) {
                        target[buffered + i] = clip(source[position + i]);
                    }
                }
            }
            buffered += count;
            position += count;
            received += count;
            if (buffered == frameSize) {
                emit(output);
            }
        }
    }

    /**
     * Clips a sample to {@code [-1, 1]}, turning not-a-number into silence.
     *
     * @param value the sample
     * @return the clipped sample
     */
    private static float clip(float value) {
        return value > 1 ? 1 : value < -1 ? -1 : value == value ? value : 0;
    }

    /**
     * Encodes the buffered frame.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        var frame = engine.encode(pending, 0);
        if (extension != null) {
            var dependent = extension.encode(extensionPending, 0);
            var unit = Arrays.copyOf(frame, frame.length + dependent.length);
            System.arraycopy(dependent, 0, unit, frame.length, dependent.length);
            frame = unit;
        }
        output.accept(new Packet(0, frame, encoded, encoded, frameSize, true));
        encoded += frameSize;
        buffered = 0;
    }

    /**
     * Pads the input with silence until the frames cover every input sample plus the delay and
     * emits the remaining frames; a second call emits nothing.
     *
     * @param output receives the packets
     * @throws NullPointerException if {@code output} is {@code null}
     */
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        while (encoded + buffered < received + DELAY || buffered > 0) {
            for (var ch = 0; ch < pending.length; ch++) {
                Arrays.fill(pending[ch], buffered, frameSize, 0);
            }
            if (extension != null) {
                for (var ch = 0; ch < extensionPending.length; ch++) {
                    Arrays.fill(extensionPending[ch], buffered, frameSize, 0);
                }
            }
            buffered = frameSize;
            emit(output);
        }
    }
}
