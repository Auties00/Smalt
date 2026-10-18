package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Test helpers for the Vorbis encoder: deterministic signals (computed with {@link StrictMath}
 * so they are bit-identical on every platform, which the libvorbis golden hashes rely on),
 * encoding in irregular chunks and Ogg file assembly.
 */
final class VorbisEncoding {
    private VorbisEncoding() {
    }

    /** The synthetic signal shapes. */
    enum Shape {
        MUSIC, TRANSIENTS, NOISE, SILENCE, SINE, FULL_SCALE, IMPULSE
    }

    /** Generates a deterministic planar signal in [-1, 1]. */
    static float[][] signal(Shape shape, int channels, int rate, int length, long seed) {
        var random = new SplittableRandom(seed);
        var out = new float[channels][length];
        for (var c = 0; c < channels; c++) {
            var lowpass = 0.0;
            for (var i = 0; i < length; i++) {
                var t = (double) i / rate;
                double value = switch (shape) {
                    case MUSIC -> {
                        lowpass = 0.9 * lowpass + 0.1 * (random.nextDouble() - 0.5);
                        var vibrato = 1 + 0.003 * StrictMath.sin(2 * Math.PI * 5 * t);
                        yield 0.25 * StrictMath.sin(2 * Math.PI * (220 + 110 * c) * vibrato * t)
                                + 0.12 * StrictMath.sin(2 * Math.PI * 1320 * t + c)
                                + 0.06 * StrictMath.sin(2 * Math.PI * Math.min(5300, rate * 0.3) * t)
                                + 0.5 * lowpass;
                    }
                    case TRANSIENTS -> {
                        var phase = t % 0.37;
                        var burst = phase < 0.15 ? 0.6 * (random.nextDouble() * 2 - 1) * StrictMath.exp(-phase * 60) : 0;
                        yield 0.05 * StrictMath.sin(2 * Math.PI * 440 * t) + (c == 0 ? burst : 0.9 * burst);
                    }
                    case NOISE -> random.nextDouble() * 2 - 1;
                    case SILENCE -> 0;
                    case SINE -> 0.5 * StrictMath.sin(2 * Math.PI * (500 + 250 * c) * t);
                    case FULL_SCALE -> StrictMath.sin(2 * Math.PI * 100 * t) >= 0 ? 1 : -1;
                    case IMPULSE -> i == length / 3 ? 0.9 : 0;
                };
                out[c][i] = (float) value;
            }
        }
        return out;
    }

    /** Encodes planar samples in chunks of growing irregular sizes and flushes. */
    static List<Packet> encode(VorbisEncoderOptions options, float[][] samples) {
        return encode(new VorbisEncoder(options), samples, 1);
    }

    /** Encodes planar samples with an encoder, starting with chunks of {@code chunk} samples. */
    static List<Packet> encode(VorbisEncoder encoder, float[][] samples, int chunk) {
        var packets = new ArrayList<Packet>();
        var length = samples[0].length;
        var position = 0;
        while (position < length) {
            var count = Math.min(length - position, chunk);
            var planes = new float[samples.length][count];
            for (var c = 0; c < samples.length; c++) {
                System.arraycopy(samples[c], position, planes[c], 0, count);
            }
            encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), planes, count), packets::add);
            position += count;
            chunk = Math.min(65536, chunk * 3 + 7);
        }
        encoder.flush(packets::add);
        return packets;
    }

    /** Builds the track of an encoder's packets. */
    static AudioTrack track(VorbisEncoder encoder, long samples) {
        return AudioTrack.builder().codec(com.github.auties00.smalt.track.Codec.VORBIS)
                .timeBase(Rational.timeBaseOf(encoder.sampleRate())).sampleRate(encoder.sampleRate())
                .layout(encoder.layout()).codecConfig(encoder.codecConfig().orElseThrow())
                .duration(samples).build();
    }

    /** Writes an Ogg Vorbis file. */
    static byte[] ogg(VorbisEncoder encoder, List<Packet> packets, long samples) {
        var sink = MediaSink.memory();
        try (var muxer = new OggMuxer(sink)) {
            muxer.addTrack(track(encoder, samples));
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns the SHA-256 of the identification and setup headers followed by every audio packet. */
    static String digest(byte[] codecConfig, List<Packet> packets) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var headers = XiphLacing.unlace(codecConfig);
            digest.update(headers[0]);
            digest.update(headers[2]);
            for (var packet : packets) {
                digest.update(packet.data());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /** Interleaves planar samples. */
    static float[] interleave(float[][] planes) {
        var channels = planes.length;
        var out = new float[planes[0].length * channels];
        for (var i = 0; i < planes[0].length; i++) {
            for (var c = 0; c < channels; c++) {
                out[i * channels + c] = planes[c][i];
            }
        }
        return out;
    }

    /** Returns the SNR in dB of decoded planes against the input, over the input length. */
    static double snr(float[][] input, float[][] decoded) {
        var signal = 0.0;
        var error = 0.0;
        for (var c = 0; c < input.length; c++) {
            for (var i = 0; i < input[c].length; i++) {
                double x = input[c][i];
                double y = i < decoded[c].length ? decoded[c][i] : 0;
                signal += x * x;
                error += (x - y) * (x - y);
            }
        }
        return 10 * Math.log10(signal / Math.max(error, 1e-30));
    }

    /** Returns the layout of a channel count. */
    static ChannelLayout layout(int channels) {
        return ChannelLayout.defaultFor(channels);
    }
}
