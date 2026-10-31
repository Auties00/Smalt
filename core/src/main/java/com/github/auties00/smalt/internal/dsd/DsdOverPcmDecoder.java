package com.github.auties00.smalt.internal.dsd;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Decodes the DSD that integer PCM carries over PCM (DoP 1.1), as {@link AudioDecoderOptions#dsdOverPcm()}
 * describes, passing PCM that carries none through unchanged.
 *
 * <p>The decision is made on the first block of at least two frames: DSD when every frame carries the alternating
 * markers {@code 0x05} and {@code 0xFA} in the top byte of every channel's 24-bit sample, PCM otherwise. Before the
 * first block, {@link #sampleRate()} reports the PCM rate.
 *
 * <p>Instances are not thread-safe.
 */
public final class DsdOverPcmDecoder implements AudioDecoder {
    private static final int UNDECIDED = 0;

    private static final int PCM = 1;

    private static final int DSD = 2;

    private final AudioDecoder inner;

    private final OptionalInt preferred;

    private int state = UNDECIDED;

    private DsdDecimator decimator;

    private int outputRate;

    private ChannelLayout layout;

    private byte[][] lanes = new byte[0][];

    private AudioBuffer floats;

    private DoubleAudioBuffer doubles;

    /**
     * Constructs the decoder over a decoder of integer PCM.
     *
     * @param inner the decoder of the PCM, which must have integer output; closed by {@link #close()}
     * @param options the options, of which the preferred sample rate chooses the decimation ratio
     */
    public DsdOverPcmDecoder(AudioDecoder inner, AudioDecoderOptions options) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.preferred = options.preferredSampleRate();
        this.layout = inner.layout();
        this.outputRate = inner.sampleRate();
    }

    /**
     * {@return whether the stream was found to carry DSD}
     */
    public boolean isDsd() {
        return state == DSD;
    }

    @Override
    public int sampleRate() {
        return outputRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        inner.decodeIntegers(packet, block -> floats(block, output));
    }

    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        inner.flushIntegers(block -> floats(block, output));
        if (state == DSD) {
            decimator.flush();
            drainFloats(output);
        }
    }

    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        inner.decodeIntegers(packet, block -> doubles(block, output));
    }

    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        inner.flushIntegers(block -> doubles(block, output));
        if (state == DSD) {
            decimator.flush();
            drainDoubles(output);
        }
    }

    @Override
    public void close() {
        inner.close();
    }

    private void floats(IntegerAudioBuffer block, Consumer<AudioBuffer> output) {
        if (!route(block)) {
            return;
        }
        if (state == DSD) {
            feed(block);
            drainFloats(output);
            return;
        }
        var frames = block.frames();
        if (floats == null || floats.capacity() < frames || floats.channels() != block.channels()
                || floats.sampleRate() != block.sampleRate()) {
            floats = new AudioBuffer(block.layout(), block.sampleRate(), Math.max(frames, 1));
        }
        var scale = Math.scalb(1f, 1 - block.bitsPerSample());
        for (var c = 0; c < block.channels(); c++) {
            var source = block.plane(c);
            var target = floats.plane(c);
            for (var i = 0; i < frames; i++) {
                target[i] = source[i] * scale;
            }
        }
        floats.setFrames(frames);
        output.accept(floats);
    }

    private void doubles(IntegerAudioBuffer block, Consumer<DoubleAudioBuffer> output) {
        if (!route(block)) {
            return;
        }
        if (state == DSD) {
            feed(block);
            drainDoubles(output);
            return;
        }
        var frames = block.frames();
        if (doubles == null || doubles.capacity() < frames || doubles.channels() != block.channels()
                || doubles.sampleRate() != block.sampleRate()) {
            doubles = new DoubleAudioBuffer(block.layout(), block.sampleRate(), Math.max(frames, 1));
        }
        var scale = Math.scalb(1.0, 1 - block.bitsPerSample());
        for (var c = 0; c < block.channels(); c++) {
            var source = block.plane(c);
            var target = doubles.plane(c);
            for (var i = 0; i < frames; i++) {
                target[i] = source[i] * scale;
            }
        }
        doubles.setFrames(frames);
        output.accept(doubles);
    }

    /**
     * Decides the stream's kind on its first block of two frames or more.
     *
     * @return {@code false} when the block is held back undecided (it has fewer than two frames)
     */
    private boolean route(IntegerAudioBuffer block) {
        if (state != UNDECIDED) {
            // TODO: follow a stream that switches between DoP and PCM mid-way, as DoP 1.1 players do
            return true;
        }
        if (block.frames() < 2) {
            if (block.frames() == 0) {
                return false;
            }
            state = PCM;
            return true;
        }
        state = carriesDsd(block) ? DSD : PCM;
        if (state == DSD) {
            var dsdRate = block.sampleRate() * 16;
            var ratio = DsdDecimator.ratioFor(dsdRate, preferred);
            decimator = new DsdDecimator(block.channels(), ratio);
            outputRate = Math.max(dsdRate / ratio, 1);
            layout = block.layout();
            lanes = new byte[block.channels()][];
        } else {
            outputRate = block.sampleRate();
            layout = block.layout();
        }
        return true;
    }

    /**
     * Returns whether every frame of a block carries the alternating DoP markers in every channel.
     *
     * @param block a block of at least two frames
     * @return {@code true} for DSD over PCM
     */
    public static boolean carriesDsd(IntegerAudioBuffer block) {
        var bits = block.bitsPerSample();
        if (bits != 24 && bits != 32) {
            return false;
        }
        var shift = bits - 8;
        var expected = (block.plane(0)[0] >>> shift) & 0xFF;
        if (expected != 0x05 && expected != 0xFA) {
            return false;
        }
        for (var i = 0; i < block.frames(); i++) {
            for (var c = 0; c < block.channels(); c++) {
                if (((block.plane(c)[i] >>> shift) & 0xFF) != expected) {
                    return false;
                }
            }
            expected ^= 0xFF;
        }
        return true;
    }

    private void feed(IntegerAudioBuffer block) {
        var frames = block.frames();
        var shift = block.bitsPerSample() - 24;
        var bytes = frames * 2;
        decimator.prepare(bytes);
        for (var c = 0; c < block.channels() && c < lanes.length; c++) {
            if (lanes[c] == null || lanes[c].length < bytes) {
                lanes[c] = new byte[Math.max(bytes, 8192)];
            }
            var lane = lanes[c];
            var source = block.plane(c);
            for (var i = 0; i < frames; i++) {
                var word = source[i] >> shift;
                lane[2 * i] = (byte) (word >> 8);
                lane[2 * i + 1] = (byte) word;
            }
            decimator.feed(c, lane, 0, 1, bytes, false);
        }
        decimator.commit();
    }

    private void drainFloats(Consumer<AudioBuffer> output) {
        var count = decimator.available();
        if (count == 0) {
            return;
        }
        if (floats == null || floats.capacity() < count || floats.channels() != decimator.channels()
                || floats.sampleRate() != outputRate) {
            floats = new AudioBuffer(layout, outputRate, Math.max(count, 4096));
        }
        floats.setFrames(decimator.drain(floats.planes(), 0));
        output.accept(floats);
    }

    private void drainDoubles(Consumer<DoubleAudioBuffer> output) {
        var count = decimator.available();
        if (count == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < count || doubles.channels() != decimator.channels()
                || doubles.sampleRate() != outputRate) {
            doubles = new DoubleAudioBuffer(layout, outputRate, Math.max(count, 4096));
        }
        doubles.setFrames(decimator.drain(doubles.planes(), 0));
        output.accept(doubles);
    }
}
