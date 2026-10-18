package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the additions to the preview package: the {@link Waveform#measure} and {@code Path}
 * entry points, {@link WaveformMeter#accept(float[][], int, int, int)}, the
 * {@link Thumbnails} {@code Path} and options-less overloads, and the builder resets of
 * {@link ThumbnailOptions} and {@link WaveformOptions}.
 */
@DisplayName("Preview additions")
class PreviewApiTest {
    /** A one-second 8 kHz stereo WAVE file whose channels hold different ramps. */
    static byte[] wav() {
        var frames = 8000;
        var data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var frame = 0; frame < frames; frame++) {
            data.putShort((short) (Math.sin(frame * 0.01) * 20000));
            data.putShort((short) ((frame % 400) * 50 - 10000));
        }
        var track = AudioTrack.builder()
                .id(0)
                .codec(Codec.PCM_S16LE)
                .timeBase(Rational.timeBaseOf(8000))
                .duration(frames)
                .sampleRate(8000)
                .layout(ChannelLayout.STEREO)
                .bitsPerSample(16)
                .build();
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink)) {
            muxer.addTrack(track);
            muxer.writePacket(new Packet(0, data.array(), 0, 0, frames, true));
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static Path file(Path directory, byte[] data) throws IOException {
        return Files.write(directory.resolve("tone.wav"), data);
    }

    static void assertSameWaveform(Waveform expected, Waveform actual) {
        assertEquals(expected.frames(), actual.frames());
        assertEquals(expected.sampleRate(), actual.sampleRate());
        assertArrayEquals(expected.minimums(), actual.minimums());
        assertArrayEquals(expected.maximums(), actual.maximums());
        for (var measure : Waveform.Measure.values()) {
            assertArrayEquals(expected.values(measure), actual.values(measure));
        }
    }

    @Nested
    @DisplayName("Waveform.measure")
    class Measure {
        @Test
        @DisplayName("equals a WaveformMeter fed the decoded source")
        void source() {
            var data = wav();
            assertSameWaveform(Waveform.measure(MediaSource.of(data), WaveformOptions.defaults()),
                    Waveform.measure(MediaSource.of(data)));
            var options = WaveformOptions.builder().buckets(17).build();
            var builder = new WaveformMeter(options);
            try (var reader = AudioReader.open(MediaSource.of(data), AudioReaderOptions.builder()
                    .layout(ChannelLayout.MONO).build())) {
                for (var block = reader.read(); block != null; block = reader.read()) {
                    builder.accept(block);
                }
            }
            assertSameWaveform(builder.finish(), Waveform.measure(MediaSource.of(data), options));
        }

        @Test
        @DisplayName("opens and closes a file on disk")
        void path(@TempDir Path directory) throws IOException {
            var data = wav();
            var file = file(directory, data);
            assertSameWaveform(Waveform.measure(MediaSource.of(data)), Waveform.measure(file));
            var options = WaveformOptions.builder().buckets(9).sampleRate(4000).build();
            assertSameWaveform(Waveform.measure(MediaSource.of(data), options), Waveform.measure(file, options));
            Files.delete(file);
        }
    }

    @Nested
    @DisplayName("WaveformMeter.accept with a sampling rate")
    class AcceptWithRate {
        @Test
        @DisplayName("records the rate and measures as one whole block does")
        void records() {
            var planes = WaveformTest.signal(5000, 7);
            var options = WaveformOptions.builder().buckets(10).build();
            var plain = new WaveformMeter(options);
            plain.accept(planes, 0, 5000, 8000);
            var rated = new WaveformMeter(options);
            rated.accept(planes, 0, 2500, 22_050);
            rated.accept(planes, 2500, 2500, 22_050);
            var waveform = rated.finish();
            assertEquals(22_050, waveform.sampleRate());
            assertArrayEquals(plain.finish().maximums(), waveform.maximums());
        }

        @Test
        @DisplayName("rejects a rate that is not positive")
        void rejects() {
            var builder = new WaveformMeter(WaveformOptions.defaults());
            assertThrows(IllegalArgumentException.class, () -> builder.accept(new float[1][4], 0, 4, 0));
        }
    }

    @Nested
    @DisplayName("Thumbnails overloads")
    class ThumbnailOverloads {
        @Test
        @DisplayName("evenly without options rejects a non-positive count")
        void evenlyCount(@TempDir Path directory) {
            assertThrows(IllegalArgumentException.class, () -> Thumbnails.evenly(MediaSource.of(wav()), 0));
            assertThrows(IllegalArgumentException.class, () -> Thumbnails.evenly(directory.resolve("missing.mp4"), 0));
        }

        @Test
        @DisplayName("Path overloads fail as the source overloads do on input without pictures")
        void audioOnly(@TempDir Path directory) throws IOException {
            var data = wav();
            var file = file(directory, data);
            assertThrows(MediaException.Unsupported.class, () -> Thumbnails.at(MediaSource.of(data), 0));
            assertThrows(MediaException.Unsupported.class, () -> Thumbnails.at(file, 0));
            assertThrows(MediaException.Unsupported.class, () -> Thumbnails.at(file, 0, ThumbnailOptions.defaults()));
            assertThrows(MediaException.Unsupported.class, () -> Thumbnails.evenly(MediaSource.of(data), 3));
            assertThrows(MediaException.Unsupported.class, () -> Thumbnails.evenly(file, 3));
            assertThrows(MediaException.Unsupported.class, () -> Thumbnails.evenly(file, 3, ThumbnailOptions.defaults()));
            Files.delete(file);
        }
    }

    @Nested
    @DisplayName("options builder resets")
    class Resets {
        @Test
        @DisplayName("ThumbnailOptions.Builder.fullSize removes the box")
        void fullSize() {
            var options = ThumbnailOptions.builder().maxSize(100, 50).fullSize().build();
            assertEquals(0, options.maxWidth());
            assertEquals(0, options.maxHeight());
            assertEquals(ThumbnailOptions.fullSize(), options);
        }

        @Test
        @DisplayName("WaveformOptions.Builder.sourceSampleRate decodes at the source rate")
        void sourceSampleRate() {
            var options = WaveformOptions.builder().sampleRate(8000).sourceSampleRate().build();
            assertEquals(OptionalInt.empty(), options.sampleRate());
            assertEquals(WaveformOptions.defaults().sampleRate(), options.sampleRate());
        }
    }
}
