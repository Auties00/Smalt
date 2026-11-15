package com.github.auties00.smalt.audio.mace;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares {@link MaceDecoder} with QuickTime 7.7.9 for Windows. The fixtures are crafted or QuickTime-encoded streams
 * with the samples QuickTime decoded them to, written by {@code tools/testdata/mace/make-fixtures.py}, whose README
 * records the oracle's provenance; QuickTime itself is not on CI.
 */
@DisplayName("MACE decoder against QuickTime")
class MaceConformanceTest {
    /** QuickTime is the only reference there is, and the decoder equals it sample for sample. */
    private static final Tolerance TOLERANCE = Tolerance.exact();

    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    record Fixture(String name, Codec codec, int channels, int trial) {
        @Override
        public String toString() {
            return name;
        }

        int unit() {
            return (codec == Codec.MACE3 ? 2 : 1) * channels;
        }
    }

    private static byte[] resource(String name) {
        try (var input = MaceConformanceTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<Fixture> fixtures() {
        return new String(resource("fixtures.tsv"), StandardCharsets.US_ASCII).lines()
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.split("\t"))
                .map(row -> new Fixture(row[0], row[1].equals("MAC3") ? Codec.MACE3 : Codec.MACE6,
                        Integer.parseInt(row[2]), Integer.parseInt(row[3])));
    }

    private static AudioTrack track(Codec codec, int channels) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(22050)).sampleRate(22050)
                .layout(ChannelLayout.defaultFor(channels)).build();
    }

    private static Packet packet(byte[] data) {
        return new Packet(1, data, 0, 0, 1, true);
    }

    /** Decodes packets to QuickTime's unsigned 8-bit interleaved form, checking the 16-bit samples repeat their byte. */
    private static byte[] decode(MaceDecoder decoder, List<byte[]> packets) {
        var output = new ByteArrayOutputStream();
        for (var data : packets) {
            decoder.decodeIntegers(packet(data), buffer -> append(buffer, output));
        }
        return output.toByteArray();
    }

    private static void append(IntegerAudioBuffer buffer, ByteArrayOutputStream output) {
        var planes = buffer.planes();
        for (var frame = 0; frame < buffer.frames(); frame++) {
            for (var plane : planes) {
                var sample = plane[frame];
                assertEquals(sample >> 8 & 0xFF, sample & 0xFF, "a 16-bit sample repeats its byte");
                output.write((sample >> 8) + 128);
            }
        }
    }

    /** Decodes a fixture with a fresh decoder per trial, one packet per trial. */
    private static byte[] decodeTrials(Fixture fixture, byte[] data) {
        var output = new ByteArrayOutputStream();
        var trial = fixture.trial() == 0 ? data.length : fixture.trial();
        for (var start = 0; start < data.length; start += trial) {
            var decoder = new MaceDecoder(track(fixture.codec(), fixture.channels()));
            output.writeBytes(decode(decoder, List.of(Arrays.copyOfRange(data, start, start + trial))));
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Nested
    @DisplayName("fixtures written by QuickTime")
    class Fixtures {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.mace.MaceConformanceTest#fixtures")
        @DisplayName("decode to QuickTime's samples")
        void equal(Fixture fixture) {
            var actual = decodeTrials(fixture, resource(fixture.name() + ".mace"));
            if (fixture.name().startsWith("boundaries")) {
                // Every table entry lands a sample on an 8-bit boundary from both sides here, so that an entry off by
                // one changes an output sample
                assertEquals(new String(resource(fixture.name() + ".pcm.sha256"), StandardCharsets.US_ASCII).strip(),
                        sha256(actual));
                return;
            }
            var expected = resource(fixture.name() + ".pcm");
            TOLERANCE.assertWithin(fixture.name(), expected, actual);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mac3", "mac6"})
        @DisplayName("match QuickTime's 16-bit output, which repeats the byte")
        void sixteenBits(String label) {
            var codec = label.equals("mac3") ? Codec.MACE3 : Codec.MACE6;
            var expected = resource("pcm16-" + label + ".pcm16");
            var output = new ByteArrayOutputStream();
            new MaceDecoder(track(codec, 1)).decodeIntegers(packet(resource("pcm16-" + label + ".mace")), buffer -> {
                for (var frame = 0; frame < buffer.frames(); frame++) {
                    var sample = buffer.planes()[0][frame];
                    output.write(sample >> 8);
                    output.write(sample);
                }
            });
            assertArrayEquals(expected, output.toByteArray());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.mace.MaceConformanceTest#fixtures")
        @DisplayName("continue the state across packets of any size")
        void split(Fixture fixture) {
            var data = resource(fixture.name() + ".mace");
            var trial = fixture.trial() == 0 ? data.length : fixture.trial();
            var random = new Random(fixture.name().hashCode());
            var whole = decodeTrials(fixture, data);
            var output = new ByteArrayOutputStream();
            for (var start = 0; start < data.length; start += trial) {
                var decoder = new MaceDecoder(track(fixture.codec(), fixture.channels()));
                var packets = new ArrayList<byte[]>();
                for (var at = start; at < start + trial; ) {
                    var frames = 1 + random.nextInt(40);
                    var end = Math.min(start + trial, at + frames * fixture.unit());
                    packets.add(Arrays.copyOfRange(data, at, end));
                    at = end;
                }
                output.writeBytes(decode(decoder, packets));
            }
            assertArrayEquals(whole, output.toByteArray());
        }
    }

    @Nested
    @DisplayName("files")
    class Files_ {
        /** Reads every track frame through the public reader, to QuickTime's form. */
        private static byte[] read(byte[] file) {
            var output = new ByteArrayOutputStream();
            try (var reader = AudioReader.open(MediaSource.of(file), AudioReaderOptions.builder()
                    .sampleFormat(SampleFormat.INTEGER).build())) {
                while (true) {
                    var buffer = reader.readIntegers();
                    if (buffer == null) {
                        break;
                    }
                    append(buffer, output);
                }
            }
            return output.toByteArray();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"aiff/mace3.aifc", "aiff/mace6.aifc", "mp4/mace3.mov", "mp4/mace6.mov"})
        @DisplayName("read AIFF-C and QuickTime files through their container, as QuickTime decodes the sound data")
        void samples(String sample) throws IOException {
            var name = sample.substring(sample.indexOf('/') + 1, sample.lastIndexOf('.'));
            assertArrayEquals(resource("sample-" + name + ".pcm"), read(Files.readAllBytes(SAMPLES.resolve(sample))));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"aiff/mace3.aifc", "aiff/mace6.aifc", "mp4/mace3.mov", "mp4/mace6.mov"})
        @DisplayName("seek to the samples a decode from the start gives, whose state no restart reproduces")
        void seek(String sample) throws IOException {
            var file = Files.readAllBytes(SAMPLES.resolve(sample));
            var linear = read(file);
            var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
            try (var reader = AudioReader.open(MediaSource.of(file), options)) {
                var channels = reader.layout().channels();
                var target = 400_000L;
                reader.seek(target);
                var output = new ByteArrayOutputStream();
                for (var buffer = reader.readIntegers(); buffer != null; buffer = reader.readIntegers()) {
                    append(buffer, output);
                }
                var frame = target * reader.sampleRate() / 1_000_000;
                var tail = Arrays.copyOfRange(linear, (int) (frame * channels), linear.length);
                assertArrayEquals(tail, output.toByteArray());
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"aiff/mace3.aifc", "aiff/mace6.aifc", "mp4/mace3.mov", "mp4/mace6.mov"})
        @DisplayName("seek back after reading, and past the end, without carrying decoder state over")
        void seekBack(String sample) throws IOException {
            var file = Files.readAllBytes(SAMPLES.resolve(sample));
            var linear = read(file);
            var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
            try (var reader = AudioReader.open(MediaSource.of(file), options)) {
                reader.readIntegers();
                reader.seek(0);
                assertArrayEquals(linear, drain(reader));
                reader.seek(100_000);
                assertTail(linear, drain(reader), 100_000, reader);
                reader.seek(reader.durationMicros() + 1_000_000);
                assertEquals(0, drain(reader).length);
                reader.seek(0);
                assertArrayEquals(linear, drain(reader));
            }
        }

        // mace3.aifc is stereo, 4 bytes a frame; mace6.aifc is mono, 1 byte a frame, and ends in the chunk's pad byte
        @ParameterizedTest(name = "{0}, {1} bytes cut")
        @CsvSource({"mace3, 2, 1, 1", "mace3, 2, 3, 1", "mace3, 2, 4, 1", "mace3, 2, 1001, 251", "mace6, 1, 1, 0",
                "mace6, 1, 2, 1", "mace6, 1, 301, 300"})
        @DisplayName("read a cut AIFF-C file up to its last whole frame")
        void truncated(String name, int channels, int cut, int lostFrames) throws IOException {
            var file = Files.readAllBytes(SAMPLES.resolve("aiff/" + name + ".aifc"));
            var linear = read(file);
            var actual = read(Arrays.copyOf(file, file.length - cut));
            assertEquals(linear.length - lostFrames * 6 * channels, actual.length);
            assertArrayEquals(Arrays.copyOf(linear, actual.length), actual);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"aiff/mace3.aifc", "aiff/mace6.aifc", "mp4/mace3.mov", "mp4/mace6.mov"})
        @DisplayName("declare the duration of what they decode to")
        void duration(String sample) throws IOException {
            assertDuration(Files.readAllBytes(SAMPLES.resolve(sample)));
        }

        /** Checks a file's declared duration against its decoded length, within a frame of six samples. */
        private static void assertDuration(byte[] file) {
            var linear = read(file);
            try (var reader = AudioReader.open(MediaSource.of(file))) {
                var frames = linear.length / reader.layout().channels();
                assertEquals(frames * 1e6 / reader.sampleRate(), reader.durationMicros(),
                        6e6 / reader.sampleRate() + 1);
            }
        }

        /** Reads what is left of a reader, to QuickTime's form. */
        private static byte[] drain(AudioReader reader) {
            var output = new ByteArrayOutputStream();
            for (var buffer = reader.readIntegers(); buffer != null; buffer = reader.readIntegers()) {
                append(buffer, output);
            }
            return output.toByteArray();
        }

        /** Checks that what a seek gave ends the linear decode and starts within a sample of the time asked for. */
        private static void assertTail(byte[] linear, byte[] actual, long micros, AudioReader reader) {
            var channels = reader.layout().channels();
            var skipped = (linear.length - actual.length) / channels;
            assertEquals(micros * reader.sampleRate() / 1e6, skipped, 1.0);
            assertArrayEquals(Arrays.copyOfRange(linear, linear.length - actual.length, linear.length), actual);
        }

        // The sound data of each file went through QuickTime's converter; the digests are of its 8-bit output.
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "mace-Bach3-1.aiff, 257022, 9b7ce9bc65de16765b7872b45a824fc7f88a8775260b29f9ca2558eeb297e783",
                "mace-Bach6-1.aiff, 257028, 36ab7a692b519d98321f8633835e1591846445bfbcf4142043840f38c12b29ba",
                "mace-Boys3-1.aiff, 736254, 5f83e6f97b619c97ca7c320473aac09a48707f319245a70a8559c7b45515644c",
                "mace-Boys6-1.aiff, 695790, fe9dd526e8c596ef493866c4f517937e68463d9bed23ddd1fa78ee32d90f2a9c",
                "mace-mac3audio.mov, 1261260, a6ba5697dea41d950201122b920399e9984f929fc03b32d30c5ce07e0fdf26b3",
                "mace-surge-1-8-MAC3.mov, 578298, fad1a043b6756732f2c43d6945c53482507a0d12bd62c961a87d155da15dafa0",
                "mace-surge-1-8-MAC6.mov, 578298, 80bfde24a939d20408be0b4abfb7c150a38bb77bdaadb8dc8b283d7fa9eeb442",
                "mace-surge-2-8-MAC3.mov, 1156596, 9e4fedb85cb731c51010fd2f84aa678423f067060879652a46ee1a12e277c1a1",
                "mace-surge-2-8-MAC6.mov, 1156596, bd4ec88239619cbbc1cbd1f81105e79141567525a60e20cb296d0c7da931d968"
        })
        @DisplayName("decode real files of public archives to QuickTime's samples")
        void realFiles(String name, int samples, String digest) throws IOException {
            var actual = read(Files.readAllBytes(Conformance.fetchListed(name)));
            assertEquals(samples, actual.length);
            assertEquals(digest, sha256(actual));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mace-Bach3-1.aiff", "mace-Bach6-1.aiff", "mace-surge-1-8-MAC3.mov",
                "mace-surge-2-8-MAC6.mov"})
        @DisplayName("seek in real files to the samples of a decode from the start, and declare their duration")
        void realFileSeek(String name) throws IOException {
            var file = Files.readAllBytes(Conformance.fetchListed(name));
            assertDuration(file);
            var linear = read(file);
            var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
            try (var reader = AudioReader.open(MediaSource.of(file), options)) {
                var middle = reader.durationMicros() / 2;
                reader.seek(middle);
                assertTail(linear, drain(reader), middle, reader);
            }
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("registers with AudioDecoders, and has no encoder")
        void registered(Codec codec) {
            assertTrue(AudioDecoders.isSupported(codec));
            try (var decoder = AudioDecoders.create(track(codec, 2))) {
                assertEquals(22050, decoder.sampleRate());
                assertEquals(ChannelLayout.STEREO, decoder.layout());
                assertTrue(decoder.supportsIntegerOutput());
                assertEquals(16, decoder.bitsPerSample());
            }
            assertFalse(com.github.auties00.smalt.audio.AudioEncoders.isSupported(codec));
        }

        @ParameterizedTest
        @CsvSource({"MACE3, 1, 1", "MACE3, 2, 2", "MACE3, 2, 3", "MACE6, 2, 1", "MACE6, 2, 3"})
        @DisplayName("refuses a packet of part of a frame")
        void partialFrame(Codec codec, int channels, int length) {
            var decoder = new MaceDecoder(track(codec, channels));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[length]), buffer -> {
            }));
        }

        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("gives nothing for an empty packet, and buffers nothing")
        void empty(Codec codec) {
            var decoder = new MaceDecoder(track(codec, 1));
            decoder.decode(packet(new byte[0]), buffer -> {
                throw new AssertionError("no samples");
            });
            decoder.flush(buffer -> {
                throw new AssertionError("no samples");
            });
            decoder.flushIntegers(buffer -> {
                throw new AssertionError("no samples");
            });
            decoder.flushDoubles(buffer -> {
                throw new AssertionError("no samples");
            });
        }

        @Test
        @DisplayName("refuses another codec, a track without a layout or rate, and too many samples")
        void refusals() {
            assertThrows(IllegalArgumentException.class, () -> new MaceDecoder(AudioTrack.builder().id(1)
                    .codec(Codec.GSM).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                    .layout(ChannelLayout.MONO).build()));
            assertThrows(MediaException.Malformed.class, () -> new MaceDecoder(AudioTrack.builder().id(1)
                    .codec(Codec.MACE3).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000).build()));
            assertThrows(MediaException.Malformed.class, () -> new MaceDecoder(AudioTrack.builder().id(1)
                    .codec(Codec.MACE6).timeBase(Rational.timeBaseOf(8000)).layout(ChannelLayout.MONO).build()));
            var options = AudioDecoderOptions.builder()
                    .limits(MediaLimits.builder().maxBytes(1 << 20).build()).build();
            var decoder = new MaceDecoder(track(Codec.MACE6, 1), options);
            assertThrows(MediaException.LimitExceeded.class, () -> decoder.decode(packet(new byte[1 << 20]), buffer -> {
            }));
        }

        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("gives the same samples as floats, doubles and integers, scaled by 2^-15")
        void outputs(Codec codec) {
            var data = new byte[600];
            new Random(5).nextBytes(data);
            var integers = new ArrayList<int[]>();
            new MaceDecoder(track(codec, 1)).decodeIntegers(packet(data), buffer ->
                    integers.add(Arrays.copyOf(buffer.planes()[0], buffer.frames())));
            var floats = new ArrayList<float[]>();
            new MaceDecoder(track(codec, 1)).decode(packet(data), (AudioBuffer buffer) ->
                    floats.add(Arrays.copyOf(buffer.planes()[0], buffer.frames())));
            var doubles = new ArrayList<double[]>();
            new MaceDecoder(track(codec, 1)).decodeDoubles(packet(data), (DoubleAudioBuffer buffer) ->
                    doubles.add(Arrays.copyOf(buffer.planes()[0], buffer.frames())));
            assertEquals(1, integers.size());
            assertEquals(data.length / (codec == Codec.MACE3 ? 2 : 1) * 6, integers.getFirst().length);
            for (var index = 0; index < integers.getFirst().length; index++) {
                assertEquals(integers.getFirst()[index] * 0x1p-15f, floats.getFirst()[index]);
                assertEquals(integers.getFirst()[index] * 0x1p-15, doubles.getFirst()[index]);
            }
        }

        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("keeps its state over a concealed span")
        void conceals(Codec codec) {
            var data = new byte[1200];
            new Random(6).nextBytes(data);
            var half = data.length / 2;
            var plain = new MaceDecoder(track(codec, 1));
            var concealing = new MaceDecoder(track(codec, 1));
            assertTrue(concealing.supportsConcealment());
            var reference = new int[1][];
            plain.decodeIntegers(packet(Arrays.copyOf(data, half)), buffer -> {
            });
            concealing.decode(packet(Arrays.copyOf(data, half)), buffer -> {
            });
            var produced = new int[1];
            assertEquals(120, concealing.conceal(120, buffer -> produced[0] += buffer.frames()));
            assertEquals(120, produced[0]);
            plain.decodeIntegers(packet(Arrays.copyOfRange(data, half, data.length)), buffer ->
                    reference[0] = Arrays.copyOf(buffer.planes()[0], buffer.frames()));
            concealing.decodeIntegers(packet(Arrays.copyOfRange(data, half, data.length)), buffer ->
                    assertArrayEquals(reference[0], Arrays.copyOf(buffer.planes()[0], buffer.frames())));
        }

        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("keeps every sample in range, for bytes of any value and channel count")
        void anyBytes(Codec codec) {
            var random = new Random(9);
            for (var channels : new int[]{1, 2, 3, 6}) {
                var decoder = new MaceDecoder(track(codec, channels));
                for (var round = 0; round < 40; round++) {
                    var data = new byte[(codec == Codec.MACE3 ? 2 : 1) * channels * (1 + random.nextInt(200))];
                    // Runs of one byte drive the state to its limits
                    if (round % 2 == 0) {
                        random.nextBytes(data);
                    } else {
                        Arrays.fill(data, (byte) random.nextInt(256));
                    }
                    decoder.decodeIntegers(packet(data), buffer -> {
                        for (var plane : buffer.planes()) {
                            for (var frame = 0; frame < buffer.frames(); frame++) {
                                assertTrue(plane[frame] >= -32640 && plane[frame] <= 32639, "sample " + plane[frame]);
                            }
                        }
                    });
                }
            }
        }

        @ParameterizedTest(name = "{0}, {1} channels")
        @CsvSource({"MACE3, 2", "MACE3, 3", "MACE3, 6", "MACE6, 2", "MACE6, 3", "MACE6, 6"})
        @DisplayName("decodes each channel as a mono stream, the channels of a frame one after the other")
        void channelsIndependent(Codec codec, int channels) {
            var unit = codec == Codec.MACE3 ? 2 : 1;
            var frames = 400;
            var random = new Random(10);
            var interleaved = new byte[frames * unit * channels];
            random.nextBytes(interleaved);
            var planes = new int[channels][];
            new MaceDecoder(track(codec, channels)).decodeIntegers(packet(interleaved), buffer -> {
                assertEquals(frames * 6, buffer.frames());
                for (var channel = 0; channel < channels; channel++) {
                    planes[channel] = Arrays.copyOf(buffer.planes()[channel], buffer.frames());
                }
            });
            for (var channel = 0; channel < channels; channel++) {
                var mono = new byte[frames * unit];
                for (var frame = 0; frame < frames; frame++) {
                    System.arraycopy(interleaved, (frame * channels + channel) * unit, mono, frame * unit, unit);
                }
                var expected = planes[channel];
                new MaceDecoder(track(codec, 1)).decodeIntegers(packet(mono), buffer ->
                        assertArrayEquals(expected, Arrays.copyOf(buffer.planes()[0], buffer.frames())));
            }
        }

        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("fills a lost span from the decoded signal, not with silence, fading out")
        void concealedSignal(Codec codec) {
            var decoder = new MaceDecoder(track(codec, 1));
            decoder.decode(packet(resource(codec == Codec.MACE3 ? "encoded-mac3.mace" : "encoded-mac6.mace")), buffer -> {
            });
            var span = 22050;
            var samples = new float[span];
            var seen = new int[1];
            decoder.conceal(span, buffer -> {
                System.arraycopy(buffer.planes()[0], 0, samples, seen[0], buffer.frames());
                seen[0] += buffer.frames();
            });
            assertEquals(span, seen[0]);
            var start = 0.0;
            var end = 0.0;
            for (var index = 0; index < 1000; index++) {
                start += samples[index] * samples[index];
                end += samples[span - 1 - index] * samples[span - 1 - index];
            }
            assertTrue(start > 0, "the start of the span repeats the signal");
            assertTrue(end < start, "the span fades");
        }

        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"MACE3", "MACE6"})
        @DisplayName("keeps its state after refusing a packet")
        void stateAfterRefusal(Codec codec) {
            var data = new byte[400];
            new Random(11).nextBytes(data);
            var plain = new MaceDecoder(track(codec, 2));
            var refusing = new MaceDecoder(track(codec, 2));
            var unit = (codec == Codec.MACE3 ? 2 : 1) * 2;
            var half = data.length / 2 / unit * unit;
            plain.decodeIntegers(packet(Arrays.copyOf(data, half)), buffer -> {
            });
            refusing.decodeIntegers(packet(Arrays.copyOf(data, half)), buffer -> {
            });
            assertThrows(MediaException.Malformed.class, () -> refusing.decodeIntegers(packet(new byte[unit + 1]),
                    buffer -> {
                    }));
            var expected = new int[1][];
            var rest = packet(Arrays.copyOfRange(data, half, data.length));
            plain.decodeIntegers(rest, buffer -> expected[0] = Arrays.copyOf(buffer.planes()[0], buffer.frames()));
            refusing.decodeIntegers(rest, buffer ->
                    assertArrayEquals(expected[0], Arrays.copyOf(buffer.planes()[0], buffer.frames())));
        }
    }
}
