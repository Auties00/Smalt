package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.pcm.DopStreams.Section;
import com.github.auties00.smalt.internal.dsd.DsdOverPcmDecoder;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the DSD over PCM decoder follows streams that switch between DoP and PCM, as a DoP 1.1 receiver does:
 * each section of the output must equal, bit for bit, the decode of that section alone (a DoP section through the
 * native DSD decoder, a PCM section scaled unchanged), whatever the packetization, the sample size and the output
 * precision. DoP runs shorter than 32 frames and frames whose markers are corrupt must come out as PCM.
 */
@DisplayName("DSD over PCM switching")
class DsdOverPcmSwitchingTest {
    private static final int RATE = 176400;

    private static final int LOW = 0x05;

    private static final int HIGH = 0xFA;

    /**
     * A span of output at one sample rate, one array per channel.
     */
    record Run(int rate, double[][] values) {
    }

    /**
     * How a stream is cut into packets.
     */
    enum Cut {
        /** One packet per section. */
        SECTIONS,
        /** Packets of 1 to 300 frames, ignoring the sections. */
        RANDOM,
        /** One frame per packet. */
        SINGLE,
        /** The whole stream in one packet. */
        WHOLE
    }

    static Codec codec(int bits) {
        return bits == 24 ? Codec.PCM_S24LE : Codec.PCM_S32BE;
    }

    static AudioTrack track(int bits, int rate, int channels) {
        return AudioTrack.builder().codec(codec(bits)).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                .layout(ChannelLayout.defaultFor(channels)).bitsPerSample(bits).build();
    }

    static int[] cut(List<Section> sections, Cut cut, long seed) {
        var total = sections.stream().mapToInt(Section::frames).sum();
        return switch (cut) {
            case SECTIONS -> sections.stream().mapToInt(Section::frames).toArray();
            case WHOLE -> new int[]{total};
            case SINGLE -> {
                var sizes = new int[total];
                Arrays.fill(sizes, 1);
                yield sizes;
            }
            case RANDOM -> {
                var random = new Random(seed);
                var sizes = new ArrayList<Integer>();
                for (var left = total; left > 0; ) {
                    var size = Math.min(left, 1 + random.nextInt(300));
                    sizes.add(size);
                    left -= size;
                }
                yield sizes.stream().mapToInt(Integer::intValue).toArray();
            }
        };
    }

    /**
     * Adds a block to the runs, extending the last run when the block has its rate.
     */
    static void append(List<Run> runs, int rate, int channels, int frames, java.util.function.IntFunction<double[]> plane) {
        Run last = runs.isEmpty() ? null : runs.getLast();
        if (last == null || last.rate() != rate) {
            last = new Run(rate, new double[channels][0]);
            runs.add(last);
        }
        for (var c = 0; c < channels; c++) {
            var old = last.values()[c];
            var grown = Arrays.copyOf(old, old.length + frames);
            System.arraycopy(plane.apply(c), 0, grown, old.length, frames);
            last.values()[c] = grown;
        }
    }

    static Consumer<AudioBuffer> floatSink(List<Run> runs) {
        return block -> append(runs, block.sampleRate(), block.channels(), block.frames(), c -> {
            var values = new double[block.frames()];
            for (var i = 0; i < values.length; i++) {
                values[i] = block.plane(c)[i];
            }
            return values;
        });
    }

    static Consumer<DoubleAudioBuffer> doubleSink(List<Run> runs) {
        return block -> append(runs, block.sampleRate(), block.channels(), block.frames(), block::plane);
    }

    /**
     * Decodes a stream of sections through the DSD over PCM decoder.
     */
    static List<Run> decode(AudioDecoder decoder, List<Section> sections, int bits, int[] packets, boolean doubles) {
        var samples = DopStreams.join(sections);
        var runs = new ArrayList<Run>();
        var floats = floatSink(runs);
        var precise = doubleSink(runs);
        var at = 0;
        for (var size : packets) {
            var data = DopStreams.encode(samples, at, size, bits, bits == 32);
            var packet = new Packet(0, data, at, at, size, true);
            if (doubles) {
                decoder.decodeDoubles(packet, precise);
            } else {
                decoder.decode(packet, floats);
            }
            at += size;
        }
        if (doubles) {
            decoder.flushDoubles(precise);
        } else {
            decoder.flush(floats);
        }
        return runs;
    }

    static List<Run> decode(List<Section> sections, int bits, int[] packets, boolean doubles,
                            AudioDecoderOptions options) {
        var channels = sections.getFirst().samples().length;
        try (var decoder = AudioDecoders.create(track(bits, RATE, channels), options)) {
            assertInstanceOf(DsdOverPcmDecoder.class, decoder);
            return decode(decoder, sections, bits, packets, doubles);
        }
    }

    /**
     * Returns the expected output: each DoP section of 32 frames or more decoded alone by the native DSD decoder, and
     * every other section scaled as PCM.
     */
    static List<Run> expected(List<Section> sections, int bits, int rate, boolean doubles,
                              AudioDecoderOptions options) {
        var runs = new ArrayList<Run>();
        for (var section : sections) {
            var channels = section.samples().length;
            if (section.isDop() && section.frames() >= DsdOverPcmDecoder.DSD_RUN) {
                var track = DsdDecoderTest.track(Codec.DSD_MSBF, rate * 16, channels);
                var bytes = section.dsd()[0].length;
                var packet = new Packet(0, DsdDecoderTest.pack(Codec.DSD_MSBF, section.dsd(), 0, bytes), 0, 0,
                        bytes * 8L, true);
                try (var decoder = new DsdDecoder(track, options)) {
                    if (doubles) {
                        decoder.decodeDoubles(packet, doubleSink(runs));
                        decoder.flushDoubles(doubleSink(runs));
                    } else {
                        decoder.decode(packet, floatSink(runs));
                        decoder.flush(floatSink(runs));
                    }
                }
            } else {
                var frames = section.frames();
                append(runs, rate, channels, frames, c -> {
                    var values = new double[frames];
                    for (var i = 0; i < frames; i++) {
                        var sample = section.samples()[c][i];
                        values[i] = doubles ? sample * Math.scalb(1.0, 1 - bits) : sample * Math.scalb(1f, 1 - bits);
                    }
                    return values;
                });
            }
        }
        return runs;
    }

    static void assertRuns(List<Run> expected, List<Run> actual) {
        assertEquals(expected.size(), actual.size(), "number of runs at one rate");
        for (var r = 0; r < expected.size(); r++) {
            assertEquals(expected.get(r).rate(), actual.get(r).rate(), "rate of run " + r);
            for (var c = 0; c < expected.get(r).values().length; c++) {
                assertArrayEquals(expected.get(r).values()[c], actual.get(r).values()[c],
                        "run " + r + ", channel " + c);
            }
        }
    }

    static AudioDecoderOptions options() {
        return AudioDecoderOptions.builder().dsdOverPcm(true).build();
    }

    static List<Section> schedule(int which, int bits) {
        return switch (which) {
            case 1 -> List.of(DopStreams.pcm(2, 500, bits, 1), DopStreams.dop(2, 1200, bits, LOW, 2),
                    DopStreams.pcm(2, 37, bits, 3), DopStreams.dop(2, 32, bits, HIGH, 4),
                    DopStreams.pcm(2, 300, bits, 5), DopStreams.dop(2, 999, bits, HIGH, 6));
            default -> List.of(DopStreams.dop(2, 640, bits, LOW, 7), DopStreams.pcm(2, 1, bits, 8),
                    DopStreams.dop(2, 2000, bits, HIGH, 9), DopStreams.pcm(2, 64, bits, 10),
                    DopStreams.dop(2, 33, bits, LOW, 11));
        };
    }

    static Stream<Arguments> combinations() {
        var out = new ArrayList<Arguments>();
        for (var which : new int[]{1, 2}) {
            for (var bits : new int[]{24, 32}) {
                for (var doubles : new boolean[]{false, true}) {
                    for (var cut : Cut.values()) {
                        out.add(Arguments.of(which, bits, doubles, cut));
                    }
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "schedule {0}, {1}-bit, doubles {2}, packets {3}")
    @MethodSource("combinations")
    @DisplayName("decodes each section of an alternating stream as if decoded alone")
    void sections(int which, int bits, boolean doubles, Cut cut) {
        var sections = schedule(which, bits);
        var actual = decode(sections, bits, cut(sections, cut, which * 31L + bits), doubles, options());
        assertRuns(expected(sections, bits, RATE, doubles, options()), actual);
    }

    @ParameterizedTest(name = "preferred rate {0}")
    @ValueSource(ints = {352800, 88200, 44100})
    @DisplayName("outputs each section at its own rate when the decimation changes the DSD rate")
    void rates(int preferred) {
        var options = AudioDecoderOptions.builder().dsdOverPcm(true).preferredSampleRate(preferred).build();
        var sections = schedule(1, 24);
        var actual = decode(sections, 24, cut(sections, Cut.RANDOM, 5), true, options);
        var expected = expected(sections, 24, RATE, true, options);
        assertRuns(expected, actual);
        assertEquals(sections.size(), actual.size(), "one run per section");
        assertEquals(RATE, actual.getFirst().rate());
        assertEquals(preferred, actual.get(1).rate());
    }

    @Nested
    @DisplayName("PCM fallback")
    class Fallback {
        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {24, 32})
        @DisplayName("outputs DoP runs shorter than 32 frames as PCM, at the end of the stream too")
        void shortRuns(int bits) {
            var sections = List.of(DopStreams.pcm(2, 100, bits, 1), DopStreams.dop(2, 31, bits, LOW, 2),
                    DopStreams.pcm(2, 50, bits, 3), DopStreams.dop(2, 1, bits, HIGH, 4),
                    DopStreams.pcm(2, 10, bits, 5), DopStreams.dop(2, 20, bits, LOW, 6));
            for (var cut : Cut.values()) {
                var actual = decode(sections, bits, cut(sections, cut, 7), false, options());
                assertRuns(expected(sections, bits, RATE, false, options()), actual);
                assertEquals(1, actual.size());
                assertEquals(212, actual.getFirst().values()[0].length);
            }
        }

        @ParameterizedTest(name = "channel {0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("ends a DSD section at a frame whose marker is missing in one channel and outputs it as PCM")
        void missingMarker(int channel) {
            var dop = DopStreams.dop(2, 1000, 24, LOW, 3);
            dop.samples()[channel][400] = 0x123456;
            var stream = List.of(DopStreams.pcm(2, 64, 24, 1), dop, DopStreams.pcm(2, 64, 24, 2));
            var model = List.of(stream.getFirst(), dop.slice(0, 400), dop.pcmSlice(400, 401), dop.slice(401, 1000),
                    stream.getLast());
            for (var doubles : new boolean[]{false, true}) {
                var actual = decode(stream, 24, cut(stream, Cut.RANDOM, channel), doubles, options());
                assertRuns(expected(model, 24, RATE, doubles, options()), actual);
            }
        }

        @Test
        @DisplayName("ends a DSD section where the markers stop alternating and starts the next there")
        void brokenAlternation() {
            var first = DopStreams.dop(2, 600, 32, LOW, 4);
            var second = DopStreams.dop(2, 700, 32, first.lastMarker(32), 5);
            var stream = List.of(first, second);
            for (var cut : Cut.values()) {
                assertRuns(expected(stream, 32, RATE, true, options()),
                        decode(stream, 32, cut(stream, cut, 9), true, options()));
            }
        }

        @Test
        @DisplayName("outputs as PCM the frames of a run broken before it reaches 32 frames")
        void corruptRun() {
            var dop = DopStreams.dop(2, 600, 24, HIGH, 8);
            dop.samples()[1][20] = -5;
            var stream = List.of(DopStreams.pcm(2, 100, 24, 7), dop);
            var model = List.of(stream.getFirst(), dop.pcmSlice(0, 21), dop.slice(21, 600));
            assertRuns(expected(model, 24, RATE, false, options()),
                    decode(stream, 24, cut(stream, Cut.SINGLE, 0), false, options()));
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {24, 32})
        @DisplayName("outputs a packet of PCM with stray marker frames and short runs as one block")
        void strayMarkers(int bits) {
            var pcm = DopStreams.pcm(1, 1000, bits, 12);
            var samples = pcm.samples()[0];
            var low = bits - 24;
            for (var i = 0; i < 1000; i += 7) {
                samples[i] = (LOW << 16 | (samples[i] & 0xFFFF)) << 8 >> 8 << low;
            }
            var marker = LOW;
            for (var i = 0; i < 31; i++) {
                samples[300 + i] = (marker << 16 | (samples[300 + i] & 0xFFFF)) << 8 >> 8 << low;
                if (i < 20) {
                    samples[100 + i] = (marker << 16 | (samples[100 + i] & 0xFFFF)) << 8 >> 8 << low;
                }
                marker ^= 0xFF;
            }
            samples[999] = 0x123 << low;
            var stream = List.of(pcm);
            try (var decoder = AudioDecoders.create(track(bits, RATE, 1), options())) {
                var blocks = new int[1];
                var runs = new ArrayList<Run>();
                var sink = floatSink(runs);
                decoder.decode(new Packet(0, DopStreams.encode(pcm.samples(), 0, 1000, bits, bits == 32), 0, 0, 1000,
                        true), block -> {
                    blocks[0]++;
                    sink.accept(block);
                });
                decoder.flush(sink);
                assertEquals(1, blocks[0], "output blocks");
                assertRuns(expected(stream, bits, RATE, false, options()), runs);
            }
            for (var cut : Cut.values()) {
                assertRuns(expected(stream, bits, RATE, true, options()),
                        decode(stream, bits, cut(stream, cut, bits), true, options()));
            }
        }

        @Test
        @DisplayName("starts the DSD at the first frame of a run that spans packets")
        void runAcrossPackets() {
            var dop = DopStreams.dop(2, 400, 24, HIGH, 21);
            var stream = List.of(DopStreams.pcm(2, 90, 24, 20), dop, DopStreams.pcm(2, 10, 24, 22));
            var packets = new int[]{100, 5, 1, 7, 300, 87};
            assertRuns(expected(stream, 24, RATE, false, options()),
                    decode(stream, 24, packets, false, options()));
        }

        @Test
        @DisplayName("treats markers that differ between channels as PCM")
        void disagreeingChannels() {
            var dop = DopStreams.dop(2, 200, 24, LOW, 3);
            for (var i = 0; i < 200; i++) {
                dop.samples()[1][i] = (dop.samples()[1][i] ^ 0xFF0000) << 8 >> 8;
            }
            var stream = List.of(dop);
            var actual = decode(stream, 24, cut(stream, Cut.WHOLE, 0), false, options());
            assertRuns(expected(List.of(dop.pcmSlice(0, 200)), 24, RATE, false, options()), actual);
        }
    }

    @Nested
    @DisplayName("State")
    class State {
        @Test
        @DisplayName("reports the kind of the last frame decoded")
        void isDsd() {
            var pcm = DopStreams.pcm(2, 100, 24, 1);
            var dop = DopStreams.dop(2, 100, 24, LOW, 2);
            var stream = List.of(pcm, dop, pcm);
            var samples = DopStreams.join(stream);
            try (var decoder = (DsdOverPcmDecoder) AudioDecoders.create(track(24, RATE, 2), options())) {
                Consumer<AudioBuffer> ignore = block -> { };
                var states = new boolean[3];
                for (var k = 0; k < 3; k++) {
                    var data = DopStreams.encode(samples, k * 100, 100, 24, false);
                    decoder.decode(new Packet(0, data, 0, 0, 100, true), ignore);
                    states[k] = decoder.isDsd();
                }
                assertArrayEquals(new boolean[]{false, true, false}, states);
            }
        }

        @Test
        @DisplayName("starts a new stream after a flush")
        void restart() {
            var sections = List.of(DopStreams.pcm(2, 50, 24, 1), DopStreams.dop(2, 300, 24, LOW, 2),
                    DopStreams.dop(2, 10, 24, HIGH, 3));
            try (var decoder = AudioDecoders.create(track(24, RATE, 2), options())) {
                var first = decode(decoder, sections, 24, cut(sections, Cut.RANDOM, 1), true);
                var second = decode(decoder, sections, 24, cut(sections, Cut.RANDOM, 2), true);
                assertRuns(first, second);
                assertRuns(expected(List.of(sections.get(0), sections.get(1), sections.get(2).pcmSlice(0, 10)), 24,
                        RATE, true, options()), first);
            }
        }

        @Test
        @DisplayName("ends a section or a run when the block format changes")
        void formatChange() {
            var a = DopStreams.dop(2, 100, 24, LOW, 1);
            var b = DopStreams.dop(2, 100, 24, a.lastMarker(24) ^ 0xFF, 2);
            var c = DopStreams.dop(2, 20, 24, LOW, 3);
            var d = DopStreams.dop(2, 80, 24, c.lastMarker(24) ^ 0xFF, 4);
            var blocks = new ArrayDeque<IntegerAudioBuffer>();
            blocks.add(block(a, 24, RATE));
            blocks.add(block(b, 24, 88200));
            blocks.add(block(c, 24, RATE));
            blocks.add(block(d, 32, RATE));
            var runs = new ArrayList<Run>();
            try (var decoder = new DsdOverPcmDecoder(new Blocks(blocks), options())) {
                for (var k = 0; k < 4; k++) {
                    decoder.decodeDoubles(new Packet(0, new byte[1], 0, 0, 1, true), doubleSink(runs));
                }
                decoder.flushDoubles(doubleSink(runs));
            }
            var expected = new ArrayList<Run>();
            expected.addAll(expected(List.of(a), 24, RATE, true, options()));
            expected.addAll(expected(List.of(b), 24, 88200, true, options()));
            var tail = new ArrayList<Run>();
            tail.addAll(expected(List.of(c.pcmSlice(0, 20)), 24, RATE, true, options()));
            var shifted = new int[2][];
            for (var ch = 0; ch < 2; ch++) {
                shifted[ch] = Arrays.stream(d.samples()[ch]).map(v -> v << 8).toArray();
            }
            tail.addAll(expected(List.of(new Section(shifted, d.dsd())), 32, RATE, true, options()));
            assertEquals(4, expected.size() + tail.size());
            expected.add(new Run(RATE, concat(tail.get(0).values(), tail.get(1).values())));
            assertRuns(expected, runs);
        }

        static double[][] concat(double[][] first, double[][] second) {
            var out = new double[first.length][];
            for (var c = 0; c < first.length; c++) {
                out[c] = Arrays.copyOf(first[c], first[c].length + second[c].length);
                System.arraycopy(second[c], 0, out[c], first[c].length, second[c].length);
            }
            return out;
        }

        static IntegerAudioBuffer block(Section section, int bits, int rate) {
            var channels = section.samples().length;
            var planes = new int[channels][];
            for (var c = 0; c < channels; c++) {
                planes[c] = bits == 32 ? Arrays.stream(section.samples()[c]).map(v -> v << 8).toArray()
                        : section.samples()[c].clone();
            }
            return new IntegerAudioBuffer(ChannelLayout.defaultFor(channels), rate, bits, planes, section.frames());
        }

        /**
         * An integer decoder that outputs one prepared block per packet.
         */
        record Blocks(ArrayDeque<IntegerAudioBuffer> queue) implements AudioDecoder {
            @Override
            public int sampleRate() {
                return RATE;
            }

            @Override
            public ChannelLayout layout() {
                return ChannelLayout.STEREO;
            }

            @Override
            public void decode(Packet packet, Consumer<AudioBuffer> output) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void flush(Consumer<AudioBuffer> output) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean supportsIntegerOutput() {
                return true;
            }

            @Override
            public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
                output.accept(queue.removeFirst());
            }

            @Override
            public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
            }
        }
    }

    @Nested
    @DisplayName("Properties")
    class Properties {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
        @DisplayName("matches the section model on random alternating streams")
        void random(long seed) {
            var random = new Random(seed);
            var channels = 1 + random.nextInt(6);
            var bits = random.nextBoolean() ? 24 : 32;
            var stream = new ArrayList<Section>();
            var model = new ArrayList<Section>();
            var dop = random.nextBoolean();
            for (var s = 0; s < 8; s++) {
                if (dop) {
                    var frames = 1 + random.nextInt(random.nextBoolean() ? 40 : 400);
                    var first = random.nextBoolean() ? LOW : HIGH;
                    var previous = stream.isEmpty() ? null : stream.getLast();
                    if (previous != null && previous.isDop()) {
                        first = previous.lastMarker(bits);
                    }
                    var section = DopStreams.dop(channels, frames, bits, first, random.nextLong());
                    stream.add(section);
                    model.add(section);
                    dop = random.nextInt(4) != 0;
                } else {
                    var section = DopStreams.pcm(channels, 1 + random.nextInt(200), bits, random.nextLong());
                    stream.add(section);
                    model.add(section);
                    dop = true;
                }
            }
            var doubles = random.nextBoolean();
            var cut = Cut.values()[random.nextInt(Cut.values().length)];
            var actual = decode(stream, bits, cut(stream, cut, seed), doubles, options());
            assertRuns(expected(model, bits, RATE, doubles, options()), actual);
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("outputs one sample per input frame at the default ratio, whatever the marker damage")
        void frameCount(long seed) {
            var random = new Random(seed);
            var frames = 20000;
            var dop = DopStreams.dop(2, frames, 24, LOW, seed);
            for (var i = 0; i < frames; i++) {
                if (random.nextInt(100) < 3) {
                    dop.samples()[random.nextInt(2)][i] = random.nextInt() >> 8;
                }
            }
            var stream = List.of(dop);
            var actual = decode(stream, 24, cut(stream, Cut.RANDOM, seed), false, options());
            assertEquals(1, actual.size());
            assertEquals(frames, actual.getFirst().values()[0].length);
            assertFalse(Arrays.stream(actual.getFirst().values()[0]).anyMatch(v -> !Double.isFinite(v)));
            assertTrue(actual.getFirst().values()[1].length == frames);
        }
    }
}
