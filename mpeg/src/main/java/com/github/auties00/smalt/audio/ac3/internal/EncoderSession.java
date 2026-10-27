package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.Ac3Metadata;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Buffers planar float PCM into the frames the {@link FrameEncoder}s of a program's substreams
 * code and turns each access unit into a packet, for the public AC-3 and E-AC-3 encoders.
 *
 * <p>The input channels are mixed into the coded channels of each substream as the
 * {@link ProgramPlan} says, in the coded order of A/52 table 5.8, after clipping each to
 * {@code [-1, 1]} (not-a-number samples become silence), and every coded sample is clipped again.
 * An E-AC-3 program beyond 5.1 is coded as an independent substream carrying a 5.1 core, into whose
 * channels the other channels are mixed, and dependent substreams with custom channel maps carrying
 * those channels and the unmixed core channels they were mixed into (ETSI TS 102 366 clause
 * E.2.8.2); every packet holds the independent frame followed by the dependent ones, and the bit
 * rate is divided among the substreams in proportion to their full-bandwidth channels.
 *
 * <p>The dynamic range words are computed once per frame from the full-bandwidth input channels
 * and written into every substream, since decoders take them from the last substream they decode
 * (A/52 Annex E section 3.8.5). Packets carry consecutive timestamps starting at zero;
 * {@link #flush(Consumer)} pads the input with silence until the frames cover every input sample
 * plus the {@link #DELAY} of the filter bank. With transient pre-noise processing, the frame
 * encoders hold each frame until the frames after it are coded, so packets leave that many frames
 * after their input. Instances are not thread-safe.
 */
public final class EncoderSession {
    /**
     * The decoder delay in samples: the overlap of the filter bank.
     */
    public static final int DELAY = 256;

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
     * The substreams of the program, the independent first.
     */
    private final ProgramPlan plan;

    /**
     * The frame encoder of each substream.
     */
    private final FrameEncoder[] engines;

    /**
     * The buffered coded channels of each substream.
     */
    private final float[][][] pending;

    /**
     * The buffered input of each input channel, for the dynamic range analysis.
     */
    private final float[][] input;

    /**
     * The input channels of the full-bandwidth channels, those the dynamic range analysis measures.
     */
    private final int[] measured;

    /**
     * The dynamic range analysis, or {@code null} without dynamic range control.
     */
    private final DynamicRangeAnalysis dynamicRange;

    /**
     * The frame of each substream returned by its encoder for the next packet, scratch.
     */
    private final byte[][] frames;

    /**
     * The number of buffered samples per channel.
     */
    private int buffered;

    /**
     * The number of input samples received.
     */
    private long received;

    /**
     * The number of samples coded into frames.
     */
    private long coded;

    /**
     * The number of samples emitted in packets.
     */
    private long emitted;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Creates a session, which for an E-AC-3 program beyond 5.1 codes dependent substreams too.
     *
     * @param sampleRate the sampling rate
     * @param layout a layout {@link ChannelMap#codable(ChannelLayout)} returns, or for E-AC-3 one
     * {@link ChannelMap#codableEnhanced(ChannelLayout)} returns
     * @param bitrate the bit rate in bits per second, of every substream together
     * @param dialogueLevel the dialogue level in dBFS, from -31 to -1
     * @param blockSwitching whether short transforms are used on transients
     * @param coupling whether channel coupling may be used
     * @param enhanced whether to write E-AC-3 frames
     * @param blocks the blocks per frame
     * @param tools the E-AC-3 tools of the independent substream, {@link EncoderTools#NONE} for AC-3;
     * the dependent substreams use them with their channel maps
     * @param fastSearch whether frames of more than one block take the faster search of
     * {@link FrameEncoder} too
     * @param metadata the metadata the frames carry
     */
    public EncoderSession(int sampleRate, ChannelLayout layout, int bitrate, int dialogueLevel, boolean blockSwitching,
                          boolean coupling, boolean enhanced, int blocks, EncoderTools tools, boolean fastSearch,
                          Ac3Metadata metadata) {
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.frameSize = 256 * blocks;
        this.plan = plan(layout, enhanced);
        var substreams = plan.substreams();
        var rates = plan.bitrates(bitrate);
        var codes = StreamMetadata.of(metadata);
        this.engines = new FrameEncoder[substreams.size()];
        this.pending = new float[substreams.size()][][];
        for (var index = 0; index < engines.length; index++) {
            var substream = substreams.get(index);
            engines[index] = new FrameEncoder(substream.acmod(), substream.lfe(), sampleRate, rates[index],
                    dialogueLevel, blockSwitching, coupling, enhanced, blocks,
                    enhanced ? tools.withSubstream(substream.channelMap(), Math.max(0, index - 1)) : tools, fastSearch,
                    codes, Ac3Kernels.INSTANCE);
            pending[index] = new float[substream.sources().length][frameSize];
        }
        this.frames = new byte[engines.length][];
        var analysis = new DynamicRangeAnalysis(metadata.lineModeProfile(), metadata.rfModeProfile(), dialogueLevel,
                sampleRate, blocks);
        this.dynamicRange = analysis.active() ? analysis : null;
        this.input = dynamicRange != null ? new float[layout.channels()][frameSize] : null;
        this.measured = fullBandwidthInputs(layout);
    }

    /**
     * Returns the plan of a program.
     *
     * @param layout the layout of the program
     * @param enhanced whether the program is coded in E-AC-3
     * @return the plan
     */
    public static ProgramPlan plan(ChannelLayout layout, boolean enhanced) {
        return enhanced ? ProgramPlan.enhanced(layout) : ProgramPlan.single(layout);
    }

    /**
     * Returns the input channels of a layout that are not an LFE channel.
     *
     * @param layout the program layout
     * @return the channel indexes
     */
    private static int[] fullBandwidthInputs(ChannelLayout layout) {
        var speakers = layout.speakers();
        var count = 0;
        for (var speaker : speakers) {
            count += speaker == Speaker.LOW_FREQUENCY || speaker == Speaker.LOW_FREQUENCY_2 ? 0 : 1;
        }
        var channels = new int[count];
        var at = 0;
        for (var ch = 0; ch < speakers.size(); ch++) {
            var speaker = speakers.get(ch);
            if (speaker != Speaker.LOW_FREQUENCY && speaker != Speaker.LOW_FREQUENCY_2) {
                channels[at++] = ch;
            }
        }
        return channels;
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
     * Buffers samples and emits a packet for every access unit the frame encoders return.
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
        var substreams = plan.substreams();
        while (position < frames) {
            var count = Math.min(frameSize - buffered, frames - position);
            for (var index = 0; index < substreams.size(); index++) {
                var substream = substreams.get(index);
                var targets = pending[index];
                for (var ch = 0; ch < targets.length; ch++) {
                    mix(input, substream.sources()[ch], substream.weights()[ch], substream.scales()[ch], targets[ch],
                            position, count);
                }
            }
            if (this.input != null) {
                for (var ch = 0; ch < this.input.length; ch++) {
                    var source = input.plane(ch);
                    var target = this.input[ch];
                    for (var i = 0; i < count; i++) {
                        target[buffered + i] = clip(source[position + i]);
                    }
                }
            }
            buffered += count;
            position += count;
            received += count;
            if (buffered == frameSize) {
                code(output);
            }
        }
    }

    /**
     * Mixes input channels into the buffered samples of a coded channel.
     *
     * @param input the samples received
     * @param sources the input channels
     * @param weights the gain of each input channel
     * @param scale the gain of the mix
     * @param target the buffered samples of the coded channel
     * @param position the first input sample
     * @param count the number of samples
     */
    private void mix(AudioBuffer input, int[] sources, float[] weights, float scale, float[] target, int position,
                     int count) {
        if (sources.length == 1 && weights[0] == 1 && scale == 1) {
            var source = input.plane(sources[0]);
            for (var i = 0; i < count; i++) {
                target[buffered + i] = clip(source[position + i]);
            }
            return;
        }
        for (var i = 0; i < count; i++) {
            var sum = 0.0f;
            for (var k = 0; k < sources.length; k++) {
                sum += weights[k] * clip(input.plane(sources[k])[position + i]);
            }
            target[buffered + i] = clip(scale * sum);
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
     * Codes the buffered frame and emits the access unit the encoders return, if any.
     *
     * @param output receives the packet
     */
    private void code(Consumer<Packet> output) {
        if (dynamicRange != null) {
            dynamicRange.analyze(input, measured, 0);
            for (var engine : engines) {
                engine.setDynamicRange(dynamicRange.words(), dynamicRange.compression());
            }
        }
        for (var index = 0; index < engines.length; index++) {
            frames[index] = engines[index].encode(pending[index], 0);
        }
        coded += frameSize;
        buffered = 0;
        emit(output);
    }

    /**
     * Emits the frames of {@link #frames} as one packet when the encoders returned them.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        if (frames[0] == null) {
            return;
        }
        var length = 0;
        for (var frame : frames) {
            length += frame.length;
        }
        var unit = frames.length == 1 ? frames[0] : new byte[length];
        if (frames.length > 1) {
            var at = 0;
            for (var frame : frames) {
                System.arraycopy(frame, 0, unit, at, frame.length);
                at += frame.length;
            }
        }
        output.accept(new Packet(0, unit, emitted, emitted, frameSize, true));
        emitted += frameSize;
    }

    /**
     * Pads the input with silence until the frames cover every input sample plus the delay, codes
     * the remaining frames and emits every access unit still held; a second call emits nothing.
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
        while (coded + buffered < received + DELAY || buffered > 0) {
            for (var targets : pending) {
                for (var target : targets) {
                    Arrays.fill(target, buffered, frameSize, 0);
                }
            }
            if (input != null) {
                for (var target : input) {
                    Arrays.fill(target, buffered, frameSize, 0);
                }
            }
            buffered = frameSize;
            code(output);
        }
        while (true) {
            for (var index = 0; index < engines.length; index++) {
                frames[index] = engines[index].finish();
            }
            if (frames[0] == null) {
                return;
            }
            emit(output);
        }
    }
}
