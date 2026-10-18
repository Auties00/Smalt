package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the public contract of {@link VorbisDecoder}: construction errors, the priming packet,
 * per-packet sample counts across block size transitions, ignored packets, recovery after a
 * rejected packet, flushing, and the WAVE channel order checked by tone frequency.
 */
class VorbisDecoderTest {
    private static Packet packet(byte[] data) {
        return new Packet(0, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true);
    }

    /** Builds an audio packet selecting a mode, with the given window flags and random payload. */
    private static byte[] audioPacket(Random random, int mode, int modeBits, boolean longBlock, int flags, int payload) {
        var writer = new LsbBitWriter();
        writer.write(0, 1);
        writer.write(mode, modeBits);
        if (longBlock) {
            writer.write(flags, 2);
        }
        for (var i = 0; i < payload; i++) {
            writer.write(random.nextInt(256), 8);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /** Decodes every packet, returning the samples per packet (-1 when nothing was emitted). */
    private static List<float[][]> decodeAll(VorbisDecoder decoder, List<byte[]> packets) {
        var result = new ArrayList<float[][]>();
        for (var data : packets) {
            var holder = new float[1][][];
            decoder.decode(packet(data), buffer -> {
                var copy = new float[buffer.channels()][buffer.frames()];
                for (var c = 0; c < copy.length; c++) {
                    System.arraycopy(buffer.plane(c), 0, copy[c], 0, buffer.frames());
                }
                holder[0] = copy;
            });
            result.add(holder[0]);
        }
        return result;
    }

    @Nested
    @DisplayName("construction")
    class Construction {
        @Test
        @DisplayName("rejects other codecs and tracks without header packets")
        void arguments() {
            var opus = AudioTrack.builder().codec(Codec.OPUS).timeBase(new Rational(1, 48000))
                    .codecConfig(new byte[19])
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new VorbisDecoder(opus));
            var empty = AudioTrack.builder().codec(Codec.VORBIS).timeBase(new Rational(1, 48000)).build();
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(empty));
            assertThrows(NullPointerException.class, () -> new VorbisDecoder(null));
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 12})
        @DisplayName("reports the WAVE-ordered default layout, unknown speakers beyond eight")
        void layout(int channels) {
            var stream = VorbisStreamBuilder.minimal(channels);
            stream.sampleRate = 22050;
            var decoder = new VorbisDecoder(stream.track());
            assertEquals(ChannelLayout.defaultFor(channels), decoder.layout());
            assertEquals(22050, decoder.sampleRate());
            if (channels > 8) {
                assertEquals(Collections.nCopies(channels, Speaker.UNKNOWN), decoder.layout().speakers());
            }
        }
    }

    @Nested
    @DisplayName("packet sequencing")
    class Sequencing {
        @Test
        @DisplayName("primes on the first packet and then returns previous/4 + current/4 samples")
        void sampleCounts() {
            var stream = VorbisStreamBuilder.minimal(2);
            var decoder = new VorbisDecoder(stream.track());
            var random = new Random(3);
            boolean[] longBlocks = {true, true, false, false, true, false, true, true, false};
            var packets = new ArrayList<byte[]>();
            for (var longBlock : longBlocks) {
                packets.add(audioPacket(random, longBlock ? 1 : 0, 1, longBlock, 3, 40));
            }
            var decoded = decodeAll(decoder, packets);
            assertNull(decoded.getFirst(), "the first packet primes the decoder");
            for (var i = 1; i < longBlocks.length; i++) {
                var expected = stream.blocksize(longBlocks[i - 1]) / 4 + stream.blocksize(longBlocks[i]) / 4;
                assertEquals(expected, decoded.get(i)[0].length, "packet " + i);
            }
        }

        @Test
        @DisplayName("ignores empty and header-type packets without disturbing the lapping")
        void ignored() {
            var stream = VorbisStreamBuilder.minimal(2);
            var random = new Random(5);
            var packets = new ArrayList<byte[]>();
            for (var i = 0; i < 6; i++) {
                packets.add(audioPacket(random, i % 2, 1, i % 2 == 1, 3, 60));
            }
            var clean = decodeAll(new VorbisDecoder(stream.track()), packets);
            var noisy = new ArrayList<>(packets);
            noisy.add(2, new byte[0]);
            noisy.add(4, VorbisStreamBuilder.comment());
            var withIgnored = decodeAll(new VorbisDecoder(stream.track()), noisy);
            assertNull(withIgnored.get(2));
            assertNull(withIgnored.get(4));
            withIgnored.remove(4);
            withIgnored.remove(2);
            assertSamePackets(clean, withIgnored);
        }

        @Test
        @DisplayName("rejects a packet with an undeclared mode and stays usable, as if it were absent")
        void recovery() {
            var stream = VorbisStreamBuilder.minimal(1);
            stream.modes.add(new VorbisStreamBuilder.ModeSpec(false, 0));
            var random = new Random(9);
            var packets = new ArrayList<byte[]>();
            for (var i = 0; i < 6; i++) {
                packets.add(audioPacket(random, i % 3, 2, i % 3 == 1, 3, 50));
            }
            var clean = decodeAll(new VorbisDecoder(stream.track()), packets);
            var decoder = new VorbisDecoder(stream.track());
            var bad = audioPacket(random, 3, 2, false, 0, 10);
            var recovered = new ArrayList<float[][]>();
            for (var i = 0; i < packets.size(); i++) {
                if (i == 3) {
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(bad), buffer -> fail()));
                }
                recovered.addAll(decodeAll(decoder, List.of(packets.get(i))));
            }
            assertSamePackets(clean, recovered);
        }

        @Test
        @DisplayName("rejects a packet that ends before its window flags")
        void truncatedHeader() {
            var stream = VorbisStreamBuilder.minimal(1);
            stream.modes.clear();
            for (var i = 0; i < 64; i++) {
                stream.modes.add(new VorbisStreamBuilder.ModeSpec(true, 0));
            }
            var decoder = new VorbisDecoder(stream.track());
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[]{0x02}), buffer -> fail()));
        }

        @Test
        @DisplayName("flush emits nothing and makes the next packet prime again")
        void flush() {
            var stream = VorbisStreamBuilder.minimal(2);
            var random = new Random(11);
            var packets = new ArrayList<byte[]>();
            for (var i = 0; i < 4; i++) {
                packets.add(audioPacket(random, 1, 1, true, 3, 30));
            }
            var decoder = new VorbisDecoder(stream.track());
            var first = decodeAll(decoder, packets);
            decoder.flush(buffer -> fail("flush must not emit"));
            var second = decodeAll(decoder, packets);
            assertSamePackets(first, second);
            assertNull(second.getFirst());
        }

        @Test
        @DisplayName("decodes packets truncated at every byte without failing")
        void truncatedPayload() {
            var stream = VorbisStreamBuilder.minimal(2);
            var random = new Random(13);
            var full = audioPacket(random, 1, 1, true, 3, 200);
            var decoder = new VorbisDecoder(stream.track());
            decoder.decode(packet(full), buffer -> fail("first packet primes"));
            for (var length = 1; length <= full.length; length++) {
                var counted = new int[1];
                decoder.decode(packet(java.util.Arrays.copyOf(full, length)), buffer -> counted[0] = buffer.frames());
                assertEquals(1024, counted[0], "prefix " + length);
            }
        }

        static void assertSamePackets(List<float[][]> expected, List<float[][]> actual) {
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                if (expected.get(i) == null) {
                    assertNull(actual.get(i), "packet " + i);
                    continue;
                }
                for (var c = 0; c < expected.get(i).length; c++) {
                    assertArrayEquals(expected.get(i)[c], actual.get(i)[c], "packet " + i + " channel " + c);
                }
            }
        }
    }

    @Test
    @DisplayName("accepts the configuration exactly as WebM stores it in CodecPrivate")
    void webmCodecPrivate() throws IOException {
        var ogg = Fixtures.ffmpeg("ogg", VorbisDifferentialTest.music(2, 44100, 1), List.of("-c:a", "libvorbis", "-q:a", "3"));
        var webm = Files.readAllBytes(Fixtures.ffmpeg("webm", List.of("-i", ogg.toString()), List.of("-c:a", "copy")));
        var link = OggVorbis.read(Files.readAllBytes(ogg)).getFirst();
        // Minimal EBML scan: the CodecPrivate element (ID 0x63A2), its vint size, then a laced header set.
        byte[] codecPrivate = null;
        for (var i = 0; i + 3 < webm.length && codecPrivate == null; i++) {
            if ((webm[i] & 0xFF) != 0x63 || (webm[i + 1] & 0xFF) != 0xA2 || webm[i + 2] == 0) {
                continue;
            }
            var first = webm[i + 2] & 0xFF;
            var width = Integer.numberOfLeadingZeros(first) - 23;
            long size = first & (0xFF >> width);
            for (var k = 1; k < width; k++) {
                size = size << 8 | (webm[i + 2 + k] & 0xFF);
            }
            var start = i + 2 + width;
            if (start + size <= webm.length && webm[start] == 2) {
                codecPrivate = Arrays.copyOfRange(webm, start, start + (int) size);
            }
        }
        assertNotNull(codecPrivate, "CodecPrivate not found");
        var packets = VorbisSetup.splitXiphLacing(codecPrivate);
        assertArrayEquals(link.headers()[0], packets[0], "identification header");
        assertArrayEquals(link.headers()[2], packets[2], "setup header");
        var fromWebm = decodeAll(new VorbisDecoder(VorbisStreamBuilder.track(codecPrivate)), link.audio());
        var fromOgg = decodeAll(new VorbisDecoder(link.track()), link.audio());
        Sequencing.assertSamePackets(fromOgg, fromWebm);
    }

    @Nested
    @DisplayName("channel order")
    class ChannelOrder {
        /** ffmpeg layout names; aevalsrc generates channel k of each in WAVE order. */
        private static final String[] LAYOUTS = {null, "mono", "stereo", "3.0", "quad", "5.0", "5.1", "6.1", "7.1"};

        private static double power(float[] samples, double frequency, int rate) {
            var coefficient = 2 * Math.cos(2 * Math.PI * frequency / rate);
            double s1 = 0;
            double s2 = 0;
            for (var sample : samples) {
                var s0 = sample + coefficient * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            return s1 * s1 + s2 * s2 - coefficient * s1 * s2;
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {3, 4, 5, 6, 7, 8})
        @DisplayName("maps the Vorbis channel order to WAVE order so each plane carries its own tone")
        void tones(int channels) throws IOException {
            var expressions = new ArrayList<String>();
            for (var c = 0; c < channels; c++) {
                expressions.add("0.5*sin(2*PI*" + (400 + 300 * c) + "*t)");
            }
            var file = Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i", "aevalsrc=exprs='"
                    + String.join("|", expressions) + "':c=" + LAYOUTS[channels] + ":s=48000:d=1"),
                    List.of("-c:a", "libvorbis", "-q:a", "6"));
            var decoded = OggVorbis.decode(OggVorbis.read(Files.readAllBytes(file)).getFirst());
            assertEquals(channels, decoded.channels());
            for (var plane = 0; plane < channels; plane++) {
                var best = -1;
                var bestPower = -1.0;
                for (var tone = 0; tone < channels; tone++) {
                    var power = power(decoded.planes()[plane], 400 + 300 * tone, 48000);
                    if (power > bestPower) {
                        bestPower = power;
                        best = tone;
                    }
                }
                assertEquals(plane, best, "plane " + plane + " of " + channels);
            }
        }
    }

    @Nested
    @DisplayName("codec-independent options")
    class IndependentOptions {
        @Test
        @DisplayName("apply their limits and ignore the preferred sample rate")
        void limits() {
            var stereo = VorbisStreamBuilder.minimal(2).track();
            var decoder = new VorbisDecoder(stereo, AudioDecoderOptions.builder().preferredSampleRate(8000).build());
            assertEquals(2, decoder.layout().channels());
            assertEquals(new VorbisDecoder(stereo).sampleRate(), decoder.sampleRate());
            assertThrows(MediaException.LimitExceeded.class, () -> new VorbisDecoder(stereo,
                    AudioDecoderOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build()));
            assertThrows(NullPointerException.class, () -> new VorbisDecoder(stereo, (AudioDecoderOptions) null));
        }
    }
}
