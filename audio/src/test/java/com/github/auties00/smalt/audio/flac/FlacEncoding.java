package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Test helpers for the FLAC encoder: deterministic signals, encoding into packets, decoding them
 * back to integers with {@link FlacDecoder}, the FLAC MD5 layout and native file assembly.
 */
final class FlacEncoding {
    private FlacEncoding() {
    }

    /** The packets and final configuration of one encode. */
    record Encoded(List<Packet> packets, byte[] initialConfig, byte[] config) {
        long bytes() {
            return packets.stream().mapToLong(Packet::size).sum();
        }
    }

    /** The synthetic signal shapes. */
    enum Shape {
        MUSIC, NOISE, SILENCE, SQUARE, WASTED, CONSTANT, IMPULSES, EXTREMES, RAMP
    }

    /** Generates a deterministic planar signal of the given sample size. */
    static int[][] signal(Shape shape, int channels, int bits, int length, long seed) {
        var random = new SplittableRandom(seed);
        var max = bits == 32 ? Integer.MAX_VALUE : (1 << (bits - 1)) - 1;
        var min = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
        var out = new int[channels][length];
        for (var c = 0; c < channels; c++) {
            var phase = random.nextDouble() * 6;
            var lowpass = 0.0;
            for (var i = 0; i < length; i++) {
                double value = switch (shape) {
                    case MUSIC -> {
                        lowpass = 0.95 * lowpass + 0.05 * (random.nextDouble() - 0.5);
                        yield 0.45 * Math.sin(phase + i * (0.013 + 0.004 * c)) + 0.25 * Math.sin(i * 0.071 + c)
                                + 0.6 * lowpass + 0.002 * (random.nextDouble() - 0.5);
                    }
                    case NOISE -> random.nextDouble() * 2 - 1;
                    case SILENCE -> 0;
                    case SQUARE -> Math.sin(i * 0.02 + c) >= 0 ? 0.999 : -0.999;
                    case WASTED, CONSTANT, IMPULSES, EXTREMES, RAMP -> 0;
                };
                long sample = switch (shape) {
                    case WASTED -> ((long) (0.7 * Math.sin(phase + i * 0.01) * max)) & ~0xFFL;
                    case CONSTANT -> max / 3 - c;
                    case IMPULSES -> i % 997 == 0 ? (c % 2 == 0 ? max : min) : 0;
                    case EXTREMES -> random.nextBoolean() ? max : min;
                    case RAMP -> (min + (long) i * 7919L * (c + 1)) % ((long) max - min + 1) + min;
                    default -> Math.round(value * max);
                };
                out[c][i] = (int) Math.max(min, Math.min(max, sample));
            }
        }
        return out;
    }

    /** Encodes planar integers in chunks of irregular sizes and flushes. */
    static Encoded encode(FlacEncoderOptions options, int[][] samples) {
        var encoder = new FlacEncoder(options);
        var initial = encoder.codecConfig().orElseThrow();
        var packets = new ArrayList<Packet>();
        var length = samples[0].length;
        var position = 0;
        var chunk = 1;
        while (position < length) {
            var count = Math.min(length - position, chunk);
            encoder.encode(samples, position, count, packets::add);
            position += count;
            chunk = chunk * 3 + 7;
        }
        encoder.flush(packets::add);
        return new Encoded(packets, initial, encoder.codecConfig().orElseThrow());
    }

    /** Builds the track an encoder's configuration describes. */
    static AudioTrack track(byte[] config, int rate) {
        return AudioTrack.builder().codec(Codec.FLAC).timeBase(Rational.timeBaseOf(rate)).codecConfig(config)
                .sampleRate(rate)
                .build();
    }

    /** Decodes packets back to planar integers through {@link FlacDecoder}'s integer output. */
    static int[][] decode(Encoded encoded, int rate, int channels) {
        var decoder = new FlacDecoder(track(encoded.config(), rate));
        var total = 0;
        for (var packet : encoded.packets()) {
            total += (int) packet.duration();
        }
        var out = new int[channels][total];
        var position = new int[1];
        for (var packet : encoded.packets()) {
            decoder.decodeIntegers(packet, block -> {
                for (var c = 0; c < channels; c++) {
                    System.arraycopy(block.plane(c), 0, out[c], position[0], block.frames());
                }
                position[0] += block.frames();
            });
        }
        return out;
    }

    /** Computes the FLAC MD5 signature of planar samples. */
    static byte[] md5(int[][] samples, int bits) throws Exception {
        var digest = MessageDigest.getInstance("MD5");
        var bytes = (bits + 7) / 8;
        var length = samples[0].length;
        var buffer = new byte[samples.length * bytes];
        for (var i = 0; i < length; i++) {
            var position = 0;
            for (var channel : samples) {
                for (var b = 0; b < bytes; b++) {
                    buffer[position++] = (byte) (channel[i] >> (8 * b));
                }
            }
            digest.update(buffer);
        }
        return digest.digest();
    }

    /** Writes a native FLAC file through {@link RawMuxer}, storing the final configuration. */
    static byte[] nativeFile(Encoded encoded, int rate) {
        var sink = MediaSink.memory();
        try (var muxer = new RawMuxer(sink)) {
            muxer.addTrack(track(encoded.initialConfig(), rate));
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet);
            }
            muxer.updateCodecConfig(0, encoded.config());
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns the offset of the {@code STREAMINFO} body inside a configuration. */
    static int streamInfo() {
        return 8;
    }

    /** Reads a big-endian unsigned value from a configuration. */
    static long read(byte[] data, int offset, int bytes) {
        var value = 0L;
        for (var i = 0; i < bytes; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }
}
