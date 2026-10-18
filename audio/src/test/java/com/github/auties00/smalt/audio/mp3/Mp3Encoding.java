package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Test helpers for the MP3 encoder: deterministic signals, encoding in irregular chunks, raw
 * file assembly and decoding back with {@link Mp3Decoder}.
 */
final class Mp3Encoding {
    private Mp3Encoding() {
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
                        var vibrato = 1 + 0.003 * Math.sin(2 * Math.PI * 5 * t);
                        yield 0.25 * Math.sin(2 * Math.PI * (220 + 110 * c) * vibrato * t)
                                + 0.12 * Math.sin(2 * Math.PI * 1320 * t + c)
                                + 0.06 * Math.sin(2 * Math.PI * Math.min(5300, rate * 0.3) * t)
                                + 0.5 * lowpass;
                    }
                    case TRANSIENTS -> {
                        var phase = t % 0.37;
                        var burst = phase < 0.15 ? 0.6 * (random.nextDouble() * 2 - 1) * Math.exp(-phase * 60) : 0;
                        yield 0.05 * Math.sin(2 * Math.PI * 440 * t) + (c == 0 ? burst : 0.9 * burst);
                    }
                    case NOISE -> random.nextDouble() * 2 - 1;
                    case SILENCE -> 0;
                    case SINE -> 0.5 * Math.sin(2 * Math.PI * 1000 * t + c);
                    case FULL_SCALE -> Math.sin(2 * Math.PI * 100 * t) >= 0 ? 1 : -1;
                    case IMPULSE -> i == length / 3 ? 0.9 : 0;
                };
                out[c][i] = (float) value;
            }
        }
        return out;
    }

    /** Encodes planar samples in chunks of growing irregular sizes and flushes. */
    static List<Packet> encode(Mp3EncoderOptions options, float[][] samples) {
        return encode(new Mp3Encoder(options), samples, 1);
    }

    /** Encodes planar samples with an encoder, starting with chunks of {@code chunk} samples. */
    static List<Packet> encode(Mp3Encoder encoder, float[][] samples, int chunk) {
        var packets = new ArrayList<Packet>();
        var length = samples[0].length;
        var layout = encoder.layout();
        var position = 0;
        while (position < length) {
            var count = Math.min(length - position, chunk);
            var planes = new float[samples.length][count];
            for (var c = 0; c < samples.length; c++) {
                System.arraycopy(samples[c], position, planes[c], 0, count);
            }
            encoder.encode(new AudioBuffer(layout, encoder.sampleRate(), planes, count), packets::add);
            position += count;
            chunk = Math.min(65536, chunk * 3 + 7);
        }
        encoder.flush(packets::add);
        return packets;
    }

    /** Concatenates the frames, as an elementary stream without a tag frame. */
    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    /** Builds the track of an encoder's packets. */
    static AudioTrack track(Mp3EncoderOptions options, long samples) {
        return AudioTrack.builder().codec(Codec.MP3).timeBase(Rational.timeBaseOf(options.sampleRate()))
                .sampleRate(options.sampleRate()).layout(options.layout()).skipSamples(Mp3Encoder.DELAY)
                .duration(samples).build();
    }

    /** Writes an {@code .mp3} file through {@link RawMuxer}, with its Xing tag frame. */
    static byte[] file(Mp3EncoderOptions options, List<Packet> packets, long samples) {
        var sink = MediaSink.memory();
        try (var muxer = new RawMuxer(sink)) {
            muxer.addTrack(track(options, samples));
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Decodes an elementary stream with Smalt's demuxer and decoder, untrimmed. */
    static AudioPipeline.Decoded decode(byte[] stream) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(stream))) {
            return AudioPipeline.decode(demuxer, Mp3Decoder::new);
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

    /** Returns the SNR in dB of decoded interleaved samples against the input, after a lag. */
    static double snr(float[][] input, float[] decoded, int lag) {
        var channels = input.length;
        var signal = 0.0;
        var error = 0.0;
        for (var i = 0; i < input[0].length; i++) {
            for (var c = 0; c < channels; c++) {
                var index = (i + lag) * channels + c;
                double y = index < decoded.length ? decoded[index] : 0;
                double x = input[c][i];
                signal += x * x;
                error += (x - y) * (x - y);
            }
        }
        return 10 * Math.log10(signal / Math.max(error, 1e-30));
    }

    /** Returns the layout of a channel count. */
    static ChannelLayout layout(int channels) {
        return channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
    }
}
