package com.github.auties00.smalt.audio.speex;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the Speex vectors of {@code src/test/resources/com/github/auties00/smalt/audio/speex} (ffmpeg's libspeex
 * encodes of every mode, quality, bit rate control and packing; see
 * {@code tools/testdata/speex-oracle/make-vectors.py}) and compares every sample with the MD5 of the fixed-point
 * libspeex 1.2.1 decode recorded in {@code expected.tsv}, also with every third packet lost and concealed.
 */
@DisplayName("Speex decoder")
class SpeexDecoderTest {
    static byte[] resource(String name) {
        try (var in = SpeexDecoderTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Vector(String name, boolean lossy, String md5) {
        @Override
        public String toString() {
            return name + (lossy ? " with every third packet lost" : "");
        }
    }

    static Stream<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        for (var line : new String(resource("expected.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            var lossy = fields[0].endsWith("#lossy");
            vectors.add(new Vector(lossy ? fields[0].substring(0, fields[0].length() - 6) : fields[0], lossy,
                    fields[1]));
        }
        return vectors.stream();
    }

    record Speex(AudioTrack track, List<Packet> packets) {
    }

    static Speex demux(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            return new Speex(track, packets);
        }
    }

    static Consumer<IntegerAudioBuffer> writer(ByteArrayOutputStream out) {
        return block -> {
            for (var i = 0; i < block.frames(); i++) {
                for (var c = 0; c < block.channels(); c++) {
                    var value = block.plane(c)[i];
                    out.write(value);
                    out.write(value >> 8);
                }
            }
        };
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static int framesPerPacket(AudioTrack track) {
        var header = track.codecConfig().orElseThrow();
        return Math.max(1, (header[64] & 0xFF) | (header[65] & 0xFF) << 8);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes every vector sample for sample as the fixed-point reference decoder does")
    void reference(Vector vector) {
        var stream = demux(resource(vector.name() + ".spx"));
        assertEquals(Codec.SPEEX, stream.track().codec());
        var decoder = AudioDecoders.create(stream.track());
        var speex = assertInstanceOf(SpeexDecoder.class, decoder);
        var out = new ByteArrayOutputStream();
        var frames = framesPerPacket(stream.track());
        for (var i = 0; i < stream.packets().size(); i++) {
            if (vector.lossy() && i % 3 == 2) {
                assertEquals(frames * speex.frameSize(), speex.concealIntegers(frames * speex.frameSize(),
                        writer(out)));
            } else {
                decoder.decodeIntegers(stream.packets().get(i), writer(out));
            }
        }
        assertEquals(vector.md5(), md5(out.toByteArray()), vector.toString());
    }

    @Test
    @DisplayName("decodes headerless (FLV) wideband packets as the Ogg stream's")
    void headerless() {
        var stream = demux(resource("wb-q5.spx"));
        var flv = AudioTrack.builder().id(1).codec(Codec.SPEEX).timeBase(Rational.timeBaseOf(16000))
                .sampleRate(16000).layout(ChannelLayout.MONO).build();
        var expected = new ByteArrayOutputStream();
        var withHeader = new SpeexDecoder(stream.track());
        var actual = new ByteArrayOutputStream();
        var headerless = new SpeexDecoder(flv);
        assertEquals(320, headerless.frameSize());
        for (var packet : stream.packets()) {
            withHeader.decodeIntegers(packet, writer(expected));
            headerless.decodeIntegers(packet, writer(actual));
        }
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
    }

    @Test
    @DisplayName("describes the stream from its header")
    void description() {
        var stereo = new SpeexDecoder(demux(resource("uwb-stereo.spx")).track());
        assertEquals(ChannelLayout.STEREO, stereo.layout());
        assertEquals(32000, stereo.sampleRate());
        assertEquals(640, stereo.frameSize());
        var narrow = new SpeexDecoder(demux(resource("nb-3frames.spx")).track());
        assertEquals(8000, narrow.sampleRate());
        assertEquals(160, narrow.frameSize());
        assertTrue(narrow.supportsConcealment());
    }

    @Test
    @DisplayName("scales the integer samples to floats by 2^-15")
    void floats() {
        var stream = demux(resource("wb-q8.spx"));
        var integers = new ArrayList<Integer>();
        var floats = new ArrayList<Float>();
        var a = new SpeexDecoder(stream.track());
        var b = new SpeexDecoder(stream.track());
        for (var packet : stream.packets()) {
            a.decodeIntegers(packet, block -> {
                for (var i = 0; i < block.frames(); i++) {
                    integers.add(block.plane(0)[i]);
                }
            });
            b.decode(packet, block -> {
                for (var i = 0; i < block.frames(); i++) {
                    floats.add(block.plane(0)[i]);
                }
            });
        }
        assertEquals(integers.size(), floats.size());
        for (var i = 0; i < integers.size(); i++) {
            assertEquals(integers.get(i) * 0x1p-15f, floats.get(i));
        }
    }

    @Test
    @DisplayName("conceals silence before the first packet")
    void concealBeforeFirst() {
        var decoder = new SpeexDecoder(demux(resource("nb-q6.spx")).track());
        var out = new ByteArrayOutputStream();
        assertEquals(480, decoder.concealIntegers(500, writer(out)));
        for (var b : out.toByteArray()) {
            assertEquals(0, b);
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"nb-q8", "wb-q10", "uwb-stereo", "nb-vad-dtx", "wb-vbr"})
        @DisplayName("decodes corrupt packets or raises MediaException.Malformed, and decodes the next packet")
        void corrupt(String name) {
            var stream = demux(resource(name + ".spx"));
            var random = new Random(name.hashCode());
            for (var trial = 0; trial < 300; trial++) {
                var decoder = new SpeexDecoder(stream.track());
                var packet = stream.packets().get(random.nextInt(stream.packets().size()));
                var data = packet.data().clone();
                if (random.nextBoolean()) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                } else {
                    data = java.util.Arrays.copyOf(data, random.nextInt(data.length + 1));
                }
                try {
                    decoder.decodeIntegers(packet.withData(data), block -> { });
                } catch (MediaException.Malformed expected) {
                    // the clean failure
                }
                decoder.decodeIntegers(stream.packets().getFirst(), block -> { });
            }
        }

        @Test
        @DisplayName("rejects a header that is not a Speex header")
        void header() {
            var track = AudioTrack.builder().id(1).codec(Codec.SPEEX).timeBase(Rational.timeBaseOf(8000))
                    .sampleRate(8000).codecConfig(new byte[80]).build();
            assertThrows(MediaException.Malformed.class, () -> new SpeexDecoder(track));
        }
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Differential {
        /** ffmpeg's libspeex decoder is the floating-point build; the fixed-point decode differs within this SNR. */
        static final Tolerance FLOATING_POINT = Tolerance.snr(18);

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"8000 4", "8000 9", "16000 2", "16000 8", "32000 6"})
        @DisplayName("decodes ffmpeg's libspeex encodes close to its libspeex decode")
        void libspeex(String settings) {
            Oracle.assumeEncoder("libspeex");
            var parts = settings.split(" ");
            var file = Fixtures.ffmpeg("spx", List.of("-f", "lavfi", "-i",
                    "aevalsrc=0.5*sin(2*PI*220*t)*(0.6+0.4*sin(2*PI*2*t))+0.2*sin(2*PI*1100*t):d=1"),
                    List.of("-ar", parts[0], "-ac", "1", "-c:a", "libspeex", "-cbr_quality", parts[1], "-f", "ogg"));
            var stream = demux(Fixtures.read(file));
            var decoder = new SpeexDecoder(stream.track());
            var ours = new ArrayList<Short>();
            for (var packet : stream.packets()) {
                decoder.decodeIntegers(packet, block -> {
                    for (var i = 0; i < block.frames(); i++) {
                        ours.add((short) block.plane(0)[i]);
                    }
                });
            }
            var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-c:a",
                    "libspeex", "-i", file.toString(), "-f", "s16le", "-"));
            var reference = new short[raw.length / 2];
            for (var i = 0; i < reference.length; i++) {
                reference[i] = (short) ((raw[2 * i] & 0xFF) | raw[2 * i + 1] << 8);
            }
            var mine = new short[ours.size()];
            for (var i = 0; i < mine.length; i++) {
                mine[i] = ours.get(i);
            }
            // ffmpeg trims the start and end of the stream by its granule positions: align on the best lag
            var lag = 0;
            var best = Double.MAX_VALUE;
            for (var candidate = 0; candidate <= 1000 && candidate < mine.length; candidate++) {
                var length = Math.min(mine.length - candidate, reference.length);
                var error = 0.0;
                for (var i = 0; i < length; i++) {
                    double d = mine[candidate + i] - reference[i];
                    error += d * d;
                }
                error /= Math.max(length, 1);
                if (error < best) {
                    best = error;
                    lag = candidate;
                }
            }
            var length = Math.min(mine.length - lag, reference.length);
            var a = java.util.Arrays.copyOfRange(mine, lag, lag + length);
            var b = java.util.Arrays.copyOf(reference, length);
            var difference = Tolerance.Difference.ofPcm(b, a);
            System.out.printf("speex %s: %s%n", settings, difference);
            FLOATING_POINT.check(settings, difference);
        }
    }
}
