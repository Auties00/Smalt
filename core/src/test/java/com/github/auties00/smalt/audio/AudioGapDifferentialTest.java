package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the silence the stream decoder fills timestamp gaps with against ffmpeg's
 * {@code aresample=async=1} filter, which fills every gap above 100 ms with silence, on Matroska files of
 * 16-bit PCM that Smalt's muxer writes with gaps between their packets: the output must match sample for
 * sample, so both the number of samples and the place and length of every silent span agree.
 */
@DisplayName("audio gaps against ffmpeg aresample=async=1")
class AudioGapDifferentialTest {
    private static final Tolerance EXACT = Tolerance.exact();

    private static final int RATE = 8000;

    /** Packets of 100 ms each, given as their start in milliseconds, every sample a function of its index. */
    static byte[] matroska(int channels, long... starts) {
        var layout = ChannelLayout.defaultFor(channels);
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.MILLISECONDS)
                .sampleRate(RATE).layout(layout).bitsPerSample(16).build();
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            muxer.addTrack(track);
            var frames = RATE / 10;
            var sample = 1;
            for (var start : starts) {
                var data = ByteBuffer.allocate(frames * 2 * channels).order(ByteOrder.LITTLE_ENDIAN);
                for (var index = 0; index < frames; index++) {
                    for (var channel = 0; channel < channels; channel++) {
                        data.putShort((short) ((sample * 37 + channel * 1000) % 30_000 + 1));
                    }
                    sample++;
                }
                muxer.writePacket(new Packet(1, data.array(), start, start, 100, true));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static short[] smalt(byte[] data) {
        var out = new ArrayList<Short>();
        try (var reader = AudioReader.open(MediaSource.of(data),
                AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build())) {
            IntegerAudioBuffer block;
            while ((block = reader.readIntegers()) != null) {
                for (var index = 0; index < block.frames(); index++) {
                    for (var channel = 0; channel < block.channels(); channel++) {
                        out.add((short) block.plane(channel)[index]);
                    }
                }
            }
        }
        var samples = new short[out.size()];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = out.get(index);
        }
        return samples;
    }

    static short[] ffmpeg(byte[] data, String name) throws IOException {
        var file = Scratch.directory("audio-gaps").resolve(name + ".mkv");
        Files.write(file, data);
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(),
                "-af", "aresample=async=1", "-f", "s16le", "-acodec", "pcm_s16le", "-"));
        var samples = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,100,200,450,550,1550,1650", "0,100,1200", "0,100,200,300,700,800,1050,1150,1250",
            "0,100,200,300,400,500,600"})
    @DisplayName("fills gaps above 100 ms as ffmpeg does")
    void matches(String starts) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var values = java.util.Arrays.stream(starts.split(",")).mapToLong(Long::parseLong).toArray();
        for (var channels : new int[]{1, 2}) {
            var data = matroska(channels, values);
            var expected = ffmpeg(data, "gaps-" + channels + "-" + starts.replace(',', '_'));
            var actual = smalt(data);
            var last = values[values.length - 1];
            assertEquals((last + 100) * RATE / 1000 * channels, expected.length, "ffmpeg's sample count");
            EXACT.assertPcm(starts + " with " + channels + " channels", expected, actual);
        }
    }
}
