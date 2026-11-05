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
 * <p>The decoder follows a stream that switches between DoP and PCM, as the receiver of the DoP 1.1 standard does: it
 * switches to DSD after {@value #DSD_RUN} consecutive frames that carry the alternating markers {@code 0x05} and
 * {@code 0xFA} in the top byte of every channel's sample, starting the DSD at the first of them, and back to PCM at the
 * first frame whose marker is missing in a channel or breaks the alternation. Each DSD section is decimated as a
 * stream of its own, its filter tail included, so the output of a section equals the output of decoding it alone,
 * and the PCM between sections is output unchanged. Frames held while a run of markers is shorter than
 * {@value #DSD_RUN} are output as PCM when the run breaks or the stream ends. A change of sample rate, sample size or
 * channel count between blocks ends a DSD section or a run.
 *
 * <p>Each output block carries the rate of its section: the PCM rate for PCM, and for DSD the DSD rate (16 times the
 * PCM rate) divided by the decimation ratio that {@link AudioDecoderOptions#preferredSampleRate()} chooses.
 * {@link #sampleRate()} and {@link #layout()} report the format of the last block output, and the PCM format before
 * the first.
 *
 * <p>Instances are not thread-safe.
 */
public final class DsdOverPcmDecoder implements AudioDecoder {
    /**
     * The number of consecutive frames carrying the markers after which a PCM stream switches to DSD, as DoP 1.1
     * recommends.
     */
    public static final int DSD_RUN = 32;

    private static final int MARKER_LOW = 0x05;

    private static final int MARKER_HIGH = 0xFA;

    private final AudioDecoder inner;

    private final OptionalInt preferred;

    private final Consumer<IntegerAudioBuffer> blocks = this::process;

    private Consumer<AudioBuffer> floatOutput;

    private Consumer<DoubleAudioBuffer> doubleOutput;

    /**
     * Whether the current call outputs doubles rather than floats.
     */
    private boolean doubleMode;

    /**
     * Whether the frames being read belong to a DSD section.
     */
    private boolean inDsd;

    /**
     * Whether the last frame decoded was carried as DSD.
     */
    private boolean lastDsd;

    /**
     * Whether {@link #flush} ended the stream, so the next block starts a new one.
     */
    private boolean ended;

    /**
     * The marker the next frame of a DSD section or of a held run must carry.
     */
    private int expected;

    /**
     * The format of the DSD section being read or of the held run.
     */
    private int sectionRate;

    private int sectionBits;

    private ChannelLayout sectionLayout;

    /**
     * The frames of a run of markers shorter than {@link #DSD_RUN}, one array of {@code DSD_RUN} samples per channel.
     */
    private int[][] held = new int[0][];

    private int heldFrames;

    private DsdDecimator decimator;

    private int dsdRate;

    private ChannelLayout dsdLayout;

    private int outputRate;

    private ChannelLayout layout;

    private byte[][] lanes = new byte[0][];

    private AudioBuffer pcmFloats;

    private AudioBuffer dsdFloats;

    private DoubleAudioBuffer pcmDoubles;

    private DoubleAudioBuffer dsdDoubles;

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
     * {@return whether the last frame decoded was carried as DSD}
     *
     * <p>A frame that starts a run of markers counts as PCM until the run reaches {@value #DSD_RUN} frames.
     */
    public boolean isDsd() {
        return lastDsd;
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
        floatOutput = Objects.requireNonNull(output, "output");
        doubleMode = false;
        try {
            inner.decodeIntegers(packet, blocks);
        } finally {
            floatOutput = null;
        }
    }

    @Override
    public void flush(Consumer<AudioBuffer> output) {
        floatOutput = Objects.requireNonNull(output, "output");
        doubleMode = false;
        try {
            inner.flushIntegers(blocks);
            end();
        } finally {
            floatOutput = null;
        }
    }

    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        doubleOutput = Objects.requireNonNull(output, "output");
        doubleMode = true;
        try {
            inner.decodeIntegers(packet, blocks);
        } finally {
            doubleOutput = null;
        }
    }

    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        doubleOutput = Objects.requireNonNull(output, "output");
        doubleMode = true;
        try {
            inner.flushIntegers(blocks);
            end();
        } finally {
            doubleOutput = null;
        }
    }

    @Override
    public void close() {
        inner.close();
    }

    /**
     * Returns whether every frame of a block carries the alternating DoP markers in every channel.
     *
     * @param block a block of at least two frames
     * @return {@code true} for DSD over PCM
     */
    public static boolean carriesDsd(IntegerAudioBuffer block) {
        var bits = block.bitsPerSample();
        if ((bits != 24 && bits != 32) || block.frames() == 0) {
            return false;
        }
        var planes = block.planes();
        var channels = block.channels();
        var shift = bits - 8;
        var expected = marker(planes, channels, shift, 0);
        if (expected < 0) {
            return false;
        }
        for (var i = 1; i < block.frames(); i++) {
            expected ^= 0xFF;
            if (marker(planes, channels, shift, i) != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the marker a frame carries in every channel.
     *
     * @param shift the position of the top byte of a sample
     * @return {@code 0x05} or {@code 0xFA}, or -1 when a channel carries no marker or the channels disagree
     */
    private static int marker(int[][] planes, int channels, int shift, int frame) {
        var marker = (planes[0][frame] >>> shift) & 0xFF;
        if (marker != MARKER_LOW && marker != MARKER_HIGH) {
            return -1;
        }
        for (var c = 1; c < channels; c++) {
            if (((planes[c][frame] >>> shift) & 0xFF) != marker) {
                return -1;
            }
        }
        return marker;
    }

    /**
     * Splits a block into its DSD and PCM sections and outputs them.
     */
    private void process(IntegerAudioBuffer block) {
        if (ended) {
            ended = false;
            inDsd = false;
            heldFrames = 0;
        }
        var frames = block.frames();
        if (frames == 0) {
            return;
        }
        var rate = block.sampleRate();
        var bits = block.bitsPerSample();
        var blockLayout = block.layout();
        var sameFormat = rate == sectionRate && bits == sectionBits && blockLayout.equals(sectionLayout);
        if (inDsd && !sameFormat) {
            endDsd();
        } else if (heldFrames > 0 && !sameFormat) {
            releaseHeld();
        }
        var planes = block.planes();
        var channels = block.channels();
        var carries = bits == 24 || bits == 32;
        var shift = bits - 8;
        var i = 0;
        while (i < frames) {
            if (inDsd) {
                var end = i;
                while (end < frames && marker(planes, channels, shift, end) == expected) {
                    expected ^= 0xFF;
                    end++;
                }
                if (end > i) {
                    feed(planes, i, end - i, bits);
                    drain();
                }
                if (end < frames) {
                    endDsd();
                }
                i = end;
                continue;
            }
            i = scanPcm(block, planes, i, carries, shift);
        }
    }

    /**
     * Reads frames from {@code from} in PCM mode until a run of markers reaches {@link #DSD_RUN} frames, which starts
     * a DSD section, or the block ends, holding the frames of an unfinished run at its end.
     *
     * <p>The PCM read is output as one block, cut only where a DSD section starts, so isolated frames whose top byte
     * happens to be a marker do not split the output.
     *
     * @return the index of the first frame not read
     */
    private int scanPcm(IntegerAudioBuffer block, int[][] planes, int from, boolean carries, int shift) {
        var frames = block.frames();
        var channels = block.channels();
        var run = 0;
        var i = from;
        while (i < frames) {
            var marker = carries ? marker(planes, channels, shift, i) : -1;
            if (marker < 0 || (run == 0 && heldFrames == 0) || marker != expected) {
                if (heldFrames > 0) {
                    releaseHeld();
                }
                run = 0;
                if (marker < 0) {
                    i++;
                    continue;
                }
                startRun(block);
            }
            run++;
            i++;
            expected = marker ^ 0xFF;
            if (heldFrames + run == DSD_RUN) {
                var start = i - run;
                if (start > from) {
                    pcm(planes, from, start - from, block.bitsPerSample(), block.sampleRate(), block.layout());
                }
                startDsd(planes, start, run);
                return i;
            }
        }
        var start = frames - run;
        if (start > from) {
            pcm(planes, from, start - from, block.bitsPerSample(), block.sampleRate(), block.layout());
        }
        if (run > 0) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(planes[c], start, held[c], heldFrames, run);
            }
            heldFrames += run;
        }
        lastDsd = false;
        return frames;
    }

    /**
     * Starts holding a run of markers in the format of {@code block}.
     */
    private void startRun(IntegerAudioBuffer block) {
        sectionRate = block.sampleRate();
        sectionBits = block.bitsPerSample();
        sectionLayout = block.layout();
        var channels = block.channels();
        if (held.length != channels) {
            held = new int[channels][DSD_RUN];
            lanes = new byte[channels][];
        }
    }

    /**
     * Outputs the held run as PCM.
     */
    private void releaseHeld() {
        var count = heldFrames;
        heldFrames = 0;
        pcm(held, 0, count, sectionBits, sectionRate, sectionLayout);
    }

    /**
     * Starts a DSD section with the held run followed by {@code count} frames of {@code planes} from {@code from}, on
     * a decimator restarted for it.
     */
    private void startDsd(int[][] planes, int from, int count) {
        var channels = sectionLayout.channels();
        var rate = sectionRate * 16;
        var ratio = DsdDecimator.ratioFor(rate, preferred);
        if (decimator == null || decimator.channels() != channels || decimator.ratio() != ratio) {
            decimator = new DsdDecimator(channels, ratio);
        } else {
            decimator.reset();
        }
        dsdRate = Math.max(rate / ratio, 1);
        dsdLayout = sectionLayout;
        inDsd = true;
        if (heldFrames > 0) {
            feed(held, 0, heldFrames, sectionBits);
            heldFrames = 0;
        }
        feed(planes, from, count, sectionBits);
        drain();
    }

    /**
     * Ends the DSD section: outputs the samples of its last bytes.
     */
    private void endDsd() {
        inDsd = false;
        decimator.flush();
        drain();
    }

    /**
     * Ends the stream: outputs the held run as PCM and ends a DSD section.
     */
    private void end() {
        if (ended) {
            return;
        }
        if (heldFrames > 0) {
            releaseHeld();
        }
        if (inDsd) {
            endDsd();
        }
        ended = true;
    }

    /**
     * Feeds the two DSD bytes below the marker of {@code count} frames from {@code from} to the decimator.
     */
    private void feed(int[][] planes, int from, int count, int bits) {
        var shift = bits - 24;
        var bytes = count * 2;
        decimator.prepare(bytes);
        for (var c = 0; c < decimator.channels(); c++) {
            if (lanes[c] == null || lanes[c].length < bytes) {
                lanes[c] = new byte[Math.max(bytes, 8192)];
            }
            var lane = lanes[c];
            var source = planes[c];
            for (var i = 0; i < count; i++) {
                var word = source[from + i] >> shift;
                lane[2 * i] = (byte) (word >> 8);
                lane[2 * i + 1] = (byte) word;
            }
            decimator.feed(c, lane, 0, 1, bytes, false);
        }
        decimator.commit();
    }

    /**
     * Outputs every sample the decimator has completed.
     */
    private void drain() {
        lastDsd = true;
        var count = decimator.available();
        if (count == 0) {
            return;
        }
        outputRate = dsdRate;
        layout = dsdLayout;
        var channels = decimator.channels();
        if (doubleMode) {
            if (dsdDoubles == null || dsdDoubles.capacity() < count || dsdDoubles.channels() != channels
                    || dsdDoubles.sampleRate() != dsdRate || !dsdDoubles.layout().equals(dsdLayout)) {
                dsdDoubles = new DoubleAudioBuffer(dsdLayout, dsdRate, Math.max(count, 4096));
            }
            dsdDoubles.setFrames(decimator.drain(dsdDoubles.planes(), 0));
            doubleOutput.accept(dsdDoubles);
        } else {
            if (dsdFloats == null || dsdFloats.capacity() < count || dsdFloats.channels() != channels
                    || dsdFloats.sampleRate() != dsdRate || !dsdFloats.layout().equals(dsdLayout)) {
                dsdFloats = new AudioBuffer(dsdLayout, dsdRate, Math.max(count, 4096));
            }
            dsdFloats.setFrames(decimator.drain(dsdFloats.planes(), 0));
            floatOutput.accept(dsdFloats);
        }
    }

    /**
     * Outputs {@code count} frames from {@code from} as PCM scaled to the unit range.
     */
    private void pcm(int[][] planes, int from, int count, int bits, int rate, ChannelLayout format) {
        lastDsd = false;
        outputRate = rate;
        layout = format;
        var channels = format.channels();
        if (doubleMode) {
            if (pcmDoubles == null || pcmDoubles.capacity() < count || pcmDoubles.sampleRate() != rate
                    || !pcmDoubles.layout().equals(format)) {
                pcmDoubles = new DoubleAudioBuffer(format, rate, Math.max(count, 1024));
            }
            var scale = Math.scalb(1.0, 1 - bits);
            for (var c = 0; c < channels; c++) {
                var source = planes[c];
                var target = pcmDoubles.plane(c);
                for (var i = 0; i < count; i++) {
                    target[i] = source[from + i] * scale;
                }
            }
            pcmDoubles.setFrames(count);
            doubleOutput.accept(pcmDoubles);
        } else {
            if (pcmFloats == null || pcmFloats.capacity() < count || pcmFloats.sampleRate() != rate
                    || !pcmFloats.layout().equals(format)) {
                pcmFloats = new AudioBuffer(format, rate, Math.max(count, 1024));
            }
            var scale = Math.scalb(1f, 1 - bits);
            for (var c = 0; c < channels; c++) {
                var source = planes[c];
                var target = pcmFloats.plane(c);
                for (var i = 0; i < count; i++) {
                    target[i] = source[from + i] * scale;
                }
            }
            pcmFloats.setFrames(count);
            floatOutput.accept(pcmFloats);
        }
    }
}
