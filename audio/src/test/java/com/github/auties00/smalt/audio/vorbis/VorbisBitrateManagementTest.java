package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the bitrate managed modes of {@link VorbisEncoder}: bit-exactness against libvorbis 1.3.7
 * (golden digests made by {@code tools/testdata/vorbis-oracle}), the reservoir bounds on every
 * run of consecutive packets, the average, the identification header, and the decoding of padded
 * and truncated packets.
 */
@DisplayName("Vorbis bitrate management")
class VorbisBitrateManagementTest {
    /**
     * Builds options from a space-separated list of settings: q (quality), nom, avg, min, max (bit/s),
     * res (bits), bias and damp.
     */
    static VorbisEncoderOptions options(int rate, int channels, String spec) {
        var builder = VorbisEncoderOptions.builder().format(rate, VorbisEncoding.layout(channels));
        for (var setting : spec.trim().split(" +")) {
            var parts = setting.split("=", 2);
            switch (parts[0]) {
                case "q" -> builder.quality(Float.parseFloat(parts[1]));
                case "nom" -> builder.nominalBitrate(Integer.parseInt(parts[1]));
                case "avg" -> builder.averageBitrate(Integer.parseInt(parts[1]));
                case "min" -> builder.minimumBitrate(Integer.parseInt(parts[1]));
                case "max" -> builder.maximumBitrate(Integer.parseInt(parts[1]));
                case "res" -> builder.reservoirBits(Integer.parseInt(parts[1]));
                case "bias" -> builder.reservoirBias(Double.parseDouble(parts[1]));
                case "damp" -> builder.averageDamping(Double.parseDouble(parts[1]));
                default -> throw new IllegalArgumentException(setting);
            }
        }
        return builder.build();
    }

    /** The identification header of an encoder. */
    static ByteBuffer identification(VorbisEncoder encoder) {
        return ByteBuffer.wrap(XiphLacing.unlace(encoder.codecConfig().orElseThrow())[0]).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Returns, for each packet, its size in bits minus the bits a bitrate allows its block: the
     * bitrate's bits of a short block's half, rounded to a whole bit, times the short blocks a long
     * block spans.
     */
    static long[] excess(VorbisEncoder encoder, List<Packet> packets, int bitrate) {
        var header = identification(encoder);
        var rate = header.getInt(12);
        var sizes = header.get(28);
        var shortBlock = 1 << (sizes & 15);
        var longBlock = 1 << ((sizes >> 4) & 15);
        var perShort = (long) Math.rint((double) bitrate * (shortBlock / 2) / rate);
        var out = new long[packets.size()];
        for (var i = 0; i < out.length; i++) {
            var data = packets.get(i).data();
            var isLong = shortBlock != longBlock && ((data[0] >> 1) & 1) != 0;
            out[i] = data.length * 8L - (isLong ? perShort * (longBlock / shortBlock) : perShort);
        }
        return out;
    }

    /** Returns the largest sum of {@code values} over a run of consecutive elements, or 0. */
    static long largestRun(long[] values) {
        long best = 0;
        long current = 0;
        for (var value : values) {
            current = Math.max(0, current + value);
            best = Math.max(best, current);
        }
        return best;
    }

    /** Returns the largest sum of {@code values} over a prefix, or 0. */
    static long largestPrefix(long[] values) {
        long best = 0;
        long sum = 0;
        for (var value : values) {
            sum += value;
            best = Math.max(best, sum);
        }
        return best;
    }

    static long[] negate(long[] values) {
        var out = new long[values.length];
        for (var i = 0; i < out.length; i++) {
            out[i] = -values[i];
        }
        return out;
    }

    static float[][] signal(String shape, int channels, int rate, int length, long seed) {
        return VorbisEncoding.signal(VorbisEncoding.Shape.valueOf(shape), channels, rate, length, seed);
    }

    @Nested
    @DisplayName("libvorbis equivalence")
    class Golden {
        // Digests of the identification header, setup header and audio packets libvorbis 1.3.7 (built with
        // an IEEE round-half-even rint) produces from the same samples fed in 1024-sample chunks; the
        // libvorbis calls of each setting are in tools/testdata/vorbis-oracle/goldens.py.
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "average, MUSIC, 2, 44100, 132300, 1, avg=128000, "
                        + "c2fdec5dad27b2be4fc5db42766d3eb1837ecf8a4100bcf12de1d520c9ddfc0d",
                "average with limits, TRANSIENTS, 2, 48000, 96000, 2, avg=96000 min=88000 max=104000, "
                        + "616912d8cdf959752513b5d0c3ca81d1f06d189475157c05e91b1fea3a1a7e33",
                "limits at a nominal bitrate, TRANSIENTS, 2, 48000, 96000, 2, nom=96000 min=64000 max=128000, "
                        + "d0d7e36884742aa46e489e2e3a4b0ea7dd3639fe170810290e2a89edba912200",
                "maximum at a quality, MUSIC, 1, 22050, 66150, 11, q=4 max=40000, "
                        + "bcff684a22c6b73732df304592b9e8c1e7dcb395074b5f97b55e1d5f533e05b0",
                "truncating maximum, MUSIC, 2, 44100, 132300, 1, q=4 max=48000 res=8000, "
                        + "4f34f7775b62e5142e4584e0fce9806b7d0b3cac260bfdd5d791a673daf54671",
                "padding minimum, MUSIC, 2, 44100, 132300, 1, q=0 min=200000 res=8000, "
                        + "1ad4bef4904c872533c0364f86859b288ecf7c45e120b02e7e4c08c8ddc35790",
                "reservoir settings, MUSIC, 1, 32000, 64000, 7, avg=48000 res=20000 bias=0.5 damp=0.5, "
                        + "4f91e501c2caa5de872e790d01ed9cdd25d6361584b5179d8b6bd6d4dd459ca9",
                "5.1 average, MUSIC, 6, 48000, 48000, 8, avg=256000, "
                        + "68b2532c8816dc66c1cfec17d7f8ffcf552a9b9c8f29b835dcd013276fb08ba5",
                "8 kHz narrow limits, NOISE, 1, 8000, 24000, 9, avg=16000 min=12000 max=20000, "
                        + "bdc7fa2842d65ff7c4272ebee1e1768b54c6111526b05ae76136b941a18090ae",
                "96 kHz limits at a quality, MUSIC, 2, 96000, 96000, 5, q=3 min=64000 max=160000, "
                        + "5d9116cf728793ea8e98563aa9e7e89d5b7bab2a86ec883575d46b728b004109",
                "silence with a minimum, SILENCE, 2, 44100, 44100, 10, avg=64000 min=32000, "
                        + "3109974a0ca0741466749168511bc6fd321b0a46e38c79955352b112a5c2dfa7"
        })
        @DisplayName("produces libvorbis's managed packets byte for byte")
        void matchesLibvorbis(String name, String shape, int channels, int rate, int length, long seed, String spec,
                              String digest) {
            var encoder = new VorbisEncoder(options(rate, channels, spec));
            var packets = VorbisEncoding.encode(encoder, signal(shape, channels, rate, length, seed), 1);
            assertEquals(digest, VorbisEncoding.digest(encoder.codecConfig().orElseThrow(), packets));
        }
    }

    @Nested
    @DisplayName("reservoir bounds")
    class Bounds {
        @ParameterizedTest(name = "{0} {5}")
        @CsvSource({
                "MUSIC, 2, 44100, 441000, 21, q=6 max=96000 res=48000",
                "MUSIC, 2, 44100, 441000, 22, q=4 max=48000 res=8000",
                "TRANSIENTS, 2, 48000, 480000, 23, avg=128000 max=144000 res=64000",
                "NOISE, 1, 22050, 220500, 24, q=8 max=64000 res=32000 bias=0.9",
                "NOISE, 1, 16000, 160000, 25, nom=32000 min=20000 max=26000 res=16000",
                "NOISE, 2, 32000, 320000, 26, q=2 max=56000 res=20000 bias=0",
        })
        @DisplayName("no run of packets exceeds the maximum by more than the reservoir")
        void maximum(String shape, int channels, int rate, int length, long seed, String spec) {
            var options = options(rate, channels, spec);
            var input = signal(shape, channels, rate, length, seed);
            var encoder = new VorbisEncoder(options);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            var maximum = options.maximumBitrate().orElseThrow();
            var reservoir = options.reservoirBits().orElseThrow();
            var excess = excess(encoder, packets, maximum);
            var run = largestRun(excess);
            assertTrue(run <= reservoir, "a run exceeds the maximum by " + run + " bits, reservoir " + reservoir);
            var initialFill = (long) (reservoir * options.reservoirBias());
            assertTrue(largestPrefix(excess) <= reservoir - initialFill, "a prefix exceeds the starting headroom");
            // the bound binds: without management the same signal goes over it
            var unmanaged = new VorbisEncoder(options.toBuilder().noMaximumBitrate().noMinimumBitrate().build());
            if (!unmanaged.options().isBitrateManaged()) {
                var free = largestRun(excess(unmanaged, VorbisEncoding.encode(unmanaged, input, 1), maximum));
                assertTrue(free > reservoir, "the unmanaged stream already stays within the maximum: " + free);
            }
        }

        @ParameterizedTest(name = "{0} {5}")
        @CsvSource({
                "MUSIC, 2, 44100, 441000, 31, q=0 min=128000 res=32000",
                "SINE, 1, 22050, 220500, 32, q=-1 min=48000 res=16000 bias=0.9",
                "TRANSIENTS, 2, 48000, 480000, 33, avg=96000 min=88000 res=48000",
                "SILENCE, 2, 44100, 220500, 34, q=3 min=32000 res=8000",
                "TRANSIENTS, 1, 16000, 160000, 35, nom=32000 min=24000 max=40000 res=16000",
        })
        @DisplayName("no run of packets falls short of the minimum by more than the reservoir")
        void minimum(String shape, int channels, int rate, int length, long seed, String spec) {
            var options = options(rate, channels, spec);
            var input = signal(shape, channels, rate, length, seed);
            var encoder = new VorbisEncoder(options);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            var minimum = options.minimumBitrate().orElseThrow();
            var reservoir = options.reservoirBits().orElseThrow();
            var shortfall = negate(excess(encoder, packets, minimum));
            var run = largestRun(shortfall);
            assertTrue(run <= reservoir, "a run falls short of the minimum by " + run + " bits, reservoir " + reservoir);
            var initialFill = (long) (reservoir * options.reservoirBias());
            assertTrue(largestPrefix(shortfall) <= initialFill, "a prefix falls short of the starting fill");
            var unmanaged = new VorbisEncoder(options.toBuilder().noMaximumBitrate().noMinimumBitrate().build());
            if (!unmanaged.options().isBitrateManaged()) {
                var free = largestRun(negate(excess(unmanaged, VorbisEncoding.encode(unmanaged, input, 1), minimum)));
                assertTrue(free > reservoir, "the unmanaged stream already stays above the minimum: " + free);
            }
        }

        @Test
        @DisplayName("the automatic reservoir is two seconds at the average bitrate")
        void automaticReservoir() {
            var options = VorbisEncoderOptions.ofAverageBitrate(96_000).toBuilder().maximumBitrate(100_000).build();
            var input = signal("TRANSIENTS", 2, 44100, 441000, 36);
            var encoder = new VorbisEncoder(options);
            var excess = excess(encoder, VorbisEncoding.encode(encoder, input, 1), 100_000);
            var reservoir = 2L * 96_000;
            assertTrue(largestRun(excess) <= reservoir);
            assertTrue(largestPrefix(excess) <= reservoir - (long) (reservoir * 0.1));
        }

        @ParameterizedTest(name = "{0} kbit/s")
        @CsvSource({"64", "96", "160"})
        @DisplayName("an average bitrate holds over a long stream, whatever the content's own rate")
        void average(int kbps) {
            var seconds = 12;
            var input = signal("MUSIC", 2, 44100, 44100 * seconds, 37);
            var bits = VorbisEncoding.encode(VorbisEncoderOptions.ofAverageBitrate(kbps * 1000), input).stream()
                    .mapToLong(Packet::size).sum() * 8;
            var actual = bits / (double) seconds / 1000;
            assertEquals(kbps, actual, kbps * 0.05, actual + " kbit/s for an average of " + kbps);
        }
    }

    @Nested
    @DisplayName("identification header")
    class Header {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "q=3, 0, 0",
                "avg=128000, 0, 0",
                "avg=128000 min=96000 max=160000, 160000, 96000",
                "nom=128000 max=192000, 192000, 0",
                "q=5 min=64000, 0, 64000"
        })
        @DisplayName("carries the maximum, nominal and minimum bitrates")
        void bitrates(String spec, int upper, int lower) {
            var options = options(44100, 2, spec);
            var header = identification(new VorbisEncoder(options));
            assertEquals(upper, header.getInt(16));
            assertEquals(lower, header.getInt(24));
            var nominal = header.getInt(20);
            if (options.averageBitrate().isPresent()) {
                assertEquals(options.averageBitrate().getAsInt(), nominal);
            } else {
                // the typical bitrate of the setting, as an unmanaged stream at the same setting writes
                var unmanaged = options.toBuilder().noMaximumBitrate().noMinimumBitrate().build();
                assertEquals(identification(new VorbisEncoder(unmanaged)).getInt(20), nominal);
            }
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"q=4 max=48000 res=8000", "q=0 min=200000 res=8000", "avg=96000 min=80000 max=112000",
                "q=-1 max=32000 res=2000"})
        @DisplayName("truncated and padded packets decode to the exact length with Smalt and ffmpeg alike")
        void decodes(String spec) {
            var options = options(44100, 2, spec);
            var length = 44100 * 3 + 55;
            var input = signal("MUSIC", 2, 44100, length, 41);
            var encoder = new VorbisEncoder(options);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            var file = VorbisEncoding.ogg(encoder, packets, length);
            var decoded = VorbisEncoderTest.decodeOgg(file);
            assertEquals(length, decoded.frames());
            assertEquals(0, decoded.rejected());
            for (var sample : decoded.interleaved()) {
                assertTrue(Float.isFinite(sample));
            }
            assertTrue(VorbisEncoding.snr(input, decoded.planes()) > 3);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = VorbisEncoderTest.save("managed", ".ogg", file);
                Oracle.assertDecodesCleanly(path);
                assertEquals(length * 2, Oracle.decodeAudio(path).length);
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"q=0 max=1000 res=2000", "q=0 max=100 res=2000"})
        @DisplayName("a maximum below one byte per short block keeps every packet decodable and the stream small")
        void starvedMaximum(String spec) {
            var options = options(44100, 2, spec);
            var length = 44100 * 4 + 17;
            var input = signal("TRANSIENTS", 2, 44100, length, 45);
            var encoder = new VorbisEncoder(options);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            for (var packet : packets) {
                assertTrue(packet.size() > 0, "an empty packet at " + packet.pts());
            }
            var file = VorbisEncoding.ogg(encoder, packets, length);
            var decoded = VorbisEncoderTest.decodeOgg(file);
            assertEquals(length, decoded.frames());
            assertEquals(0, decoded.rejected());
            // the bound binds: an unmanaged stream at the same quality is far larger, and the managed packets
            // hold one byte per short block and three at most per long block (24 bits at 1 kbit/s)
            var managedBytes = packets.stream().mapToLong(Packet::size).sum();
            var unmanagedBytes = VorbisEncoding.encode(VorbisEncoderOptions.ofQuality(0), input).stream()
                    .mapToLong(Packet::size).sum();
            assertTrue(managedBytes * 20 < unmanagedBytes, managedBytes + " bytes managed, " + unmanagedBytes
                    + " unmanaged");
            assertTrue(managedBytes <= 3L * packets.size() + 2000 / 8, managedBytes + " bytes in "
                    + packets.size() + " packets");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = VorbisEncoderTest.save("starved", ".ogg", file);
                Oracle.assertDecodesCleanly(path);
                assertEquals(length * 2, Oracle.decodeAudio(path).length);
            }
        }

        @Test
        @DisplayName("a tight maximum truncates packets, a high minimum pads them with zero bytes")
        void truncatesAndPads() {
            var input = signal("MUSIC", 2, 44100, 44100 * 3, 42);
            var free = VorbisEncoding.encode(VorbisEncoderOptions.ofQuality(4), input);
            var tight = VorbisEncoding.encode(options(44100, 2, "q=4 max=24000 res=2000"), input);
            assertEquals(free.size(), tight.size());
            var tightBytes = tight.stream().mapToLong(Packet::size).sum();
            assertTrue(tightBytes * 8 <= 24000L * 3 + 2000 + 24000, tightBytes + " bytes under a 24 kbit/s maximum");
            var padded = VorbisEncoding.encode(options(44100, 2, "q=-1 min=400000 res=2000"), input);
            var zeroTails = padded.stream().filter(packet -> packet.data()[packet.size() - 1] == 0).count();
            assertTrue(zeroTails > padded.size() / 2, zeroTails + " of " + padded.size() + " packets end padded");
        }
    }

    @Nested
    @DisplayName("encoder contract")
    class Contract {
        @Test
        @DisplayName("produces the same managed packets whatever the input chunking")
        void deterministic() {
            var options = options(44100, 2, "avg=112000 min=64000 max=160000");
            var input = signal("TRANSIENTS", 2, 44100, 44100 * 2, 43);
            var reference = VorbisEncoding.encode(new VorbisEncoder(options), input, 44100 * 2);
            for (var chunk : new int[]{1, 777, 4096}) {
                var packets = VorbisEncoding.encode(new VorbisEncoder(options), input, chunk);
                assertEquals(reference.size(), packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    assertArrayEquals(reference.get(i).data(), packets.get(i).data());
                    assertEquals(reference.get(i).pts(), packets.get(i).pts());
                }
            }
        }

        @Test
        @DisplayName("limits that never bind keep the timing and the quality of the unmanaged stream")
        void looseLimits() {
            var length = 44100 * 2;
            var input = signal("MUSIC", 2, 44100, length, 44);
            var unmanagedEncoder = new VorbisEncoder(VorbisEncoderOptions.ofQuality(5));
            var managedEncoder = new VorbisEncoder(options(44100, 2, "q=5 min=1000 max=2000000"));
            assertTrue(managedEncoder.options().isBitrateManaged());
            assertFalse(unmanagedEncoder.options().isBitrateManaged());
            var unmanaged = VorbisEncoding.encode(unmanagedEncoder, input, 1);
            var managed = VorbisEncoding.encode(managedEncoder, input, 1);
            assertEquals(2_000_000, identification(managedEncoder).getInt(16));
            assertEquals(1000, identification(managedEncoder).getInt(24));
            assertEquals(unmanaged.size(), managed.size());
            for (var i = 0; i < managed.size(); i++) {
                assertEquals(unmanaged.get(i).pts(), managed.get(i).pts());
                assertEquals(unmanaged.get(i).duration(), managed.get(i).duration());
            }
            var unmanagedSnr = VorbisEncoding.snr(input, VorbisEncoderTest.decodeOgg(
                    VorbisEncoding.ogg(unmanagedEncoder, unmanaged, length)).planes());
            var managedSnr = VorbisEncoding.snr(input, VorbisEncoderTest.decodeOgg(
                    VorbisEncoding.ogg(managedEncoder, managed, length)).planes());
            assertEquals(unmanagedSnr, managedSnr, 1, "SNR " + managedSnr + " managed, " + unmanagedSnr + " unmanaged");
        }
    }
}
