package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the decoder with libopus as run by ffmpeg ({@code -c:a libopus}, float output) on
 * Ogg Opus files that ffmpeg's libopus encoder produced across applications, bitrates, frame
 * durations, rate-control modes and channel layouts, including 5.1 with mapping family 1. Each
 * channel must pass {@code opus_compare} against libopus' decode, the RFC 6716 conformance
 * criterion, and reach 60 dB of SNR, because both decoders run the same float reconstruction.
 */
class OpusDecoderDifferentialTest {
    /** The rule each decoded channel meets against libopus' decode of it. */
    private static final Tolerance TOLERANCE = Tolerance.opusCompare(1, 48000).and(Tolerance.snr(60));

    /** A decoded stream: interleaved samples, the channel count and the rate. */
    record Decoded(float[] samples, int channels, int sampleRate) {
    }

    /** Returns the lavfi source for a channel count: distinct tones plus noise per channel. */
    static List<String> source(int channels, double seconds, String kind) {
        var expressions = new ArrayList<String>();
        for (var c = 0; c < channels; c++) {
            var f = 220 * (c + 1) + 37 * c;
            expressions.add(switch (kind) {
                case "speechy" -> "0.3*sin(2*PI*(" + f + "+80*sin(2*PI*3*t))*t)*(0.5+0.5*sin(2*PI*2.3*t))+0.05*(random(" + c + ")-0.5)";
                default -> "0.25*sin(2*PI*" + f + "*t)+0.15*sin(2*PI*" + (f * 7 + 13) + "*t)+0.1*(random(" + c + ")-0.5)";
            });
        }
        return List.of("-f", "lavfi", "-i", "aevalsrc=" + String.join("|", expressions) + ":s=48000:d=" + seconds);
    }

    /** Decodes an Ogg Opus file with the decoder under test, trimming to the end granule. */
    static Decoded decodeOurs(Path file, int outputRate) {
        var stream = OggOpusIO.read(Fixtures.read(file));
        var head = OpusHead.parse(stream.head(), MediaLimits.DEFAULT);
        var track = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000))
                .codecConfig(stream.head())
                .sampleRate(48000)
                .build();
        var channels = head.channels();
        var samples = new OpusVectors.FloatSink();
        try (var decoder = new OpusDecoder(track, OpusDecoderOptions.defaults().toBuilder().sampleRate(outputRate)
                .build())) {
            for (var data : stream.packets()) {
                decoder.decode(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), buffer -> append(samples, buffer));
            }
            decoder.flush(buffer -> append(samples, buffer));
        }
        var total = (int) ((stream.finalGranule() - head.preSkip()) * outputRate / 48000);
        var length = Math.min(samples.size(), total * channels);
        return new Decoded(java.util.Arrays.copyOf(samples.data(), length), channels, outputRate);
    }

    static void append(OpusVectors.FloatSink sink, AudioBuffer buffer) {
        var channels = buffer.channels();
        var interleaved = new float[buffer.frames() * channels];
        for (var c = 0; c < channels; c++) {
            var plane = buffer.plane(c);
            for (var i = 0; i < buffer.frames(); i++) {
                interleaved[i * channels + c] = plane[i];
            }
        }
        sink.append(interleaved, 0, interleaved.length);
    }

    /** Decodes with libopus through ffmpeg, float output, pre-skip and end trimming applied. */
    static float[] decodeLibopus(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-request_sample_fmt", "flt",
                "-c:a", "libopus", "-i", file.toString(), "-f", "f32le", "-"));
        var out = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    /** Returns one channel of interleaved samples. */
    static float[] channel(float[] interleaved, int channels, int channel) {
        var out = new float[interleaved.length / channels];
        for (var i = 0; i < out.length; i++) {
            out[i] = interleaved[i * channels + channel];
        }
        return out;
    }

    /** Returns the SNR of one interleaved channel. */
    static double channelSnr(float[] expected, float[] actual, int channels, int channel) {
        var frames = Math.min(expected.length, actual.length) / channels;
        double signal = 0;
        double noise = 0;
        for (var i = 0; i < frames; i++) {
            var e = expected[i * channels + channel];
            var d = (double) e - actual[i * channels + channel];
            signal += (double) e * e;
            noise += d * d;
        }
        return noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
    }

    @ParameterizedTest(name = "{0} ch, {1}, {2} bps, {3} ms, vbr {4}")
    @CsvSource({
            "1,voip,6000,20,on,speechy",
            "1,voip,12000,60,on,speechy",
            "1,voip,16000,20,on,speechy",
            "1,voip,24000,40,on,speechy",
            "1,audio,16000,10,on,tones",
            "1,audio,8000,100,on,tones",
            "1,lowdelay,32000,20,on,tones",
            "1,audio,40000,120,constrained,speechy",
            "2,audio,20000,20,on,speechy",
            "2,audio,32000,120,on,tones",
            "2,audio,64000,20,off,tones",
            "2,audio,96000,80,constrained,tones",
            "2,audio,128000,2.5,on,tones",
            "2,audio,256000,5,on,tones",
            "2,audio,510000,20,on,tones",
            "2,lowdelay,96000,5,on,tones",
            "2,voip,40000,60,on,speechy",
            "6,audio,256000,20,on,tones",
            "6,audio,96000,10,off,tones"
    })
    @DisplayName("matches libopus's float decode by opus_compare and 60 dB SNR on ffmpeg-encoded Ogg Opus")
    void matchesLibopus(int channels, String application, int bitrate, String duration, String vbr, String kind) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var options = new ArrayList<>(List.of("-ac", Integer.toString(channels), "-c:a", "libopus",
                "-application", application, "-b:a", Integer.toString(bitrate), "-frame_duration", duration, "-vbr", vbr));
        if (channels > 2) {
            options.addAll(List.of("-mapping_family", "1"));
        }
        var file = Fixtures.ffmpeg("opus", source(channels, 4, kind), options);
        var reference = decodeLibopus(file);
        var ours = decodeOurs(file, 48000);
        assertEquals(reference.length, ours.samples().length, "decoded length");
        var minimum = Double.POSITIVE_INFINITY;
        for (var c = 0; c < channels; c++) {
            minimum = Math.min(minimum, channelSnr(reference, ours.samples(), channels, c));
            TOLERANCE.assertPcm("channel " + c, channel(reference, channels, c), channel(ours.samples(), channels, c));
        }
        System.err.printf("[opus-decode-diff] %d ch %s %d bps %s ms vbr %s: min SNR %.1f dB, %s%n", channels, application,
                bitrate, duration, vbr, minimum, java.util.Arrays.equals(reference, ours.samples()) ? "bit-exact" : "not bit-exact");
    }
}
