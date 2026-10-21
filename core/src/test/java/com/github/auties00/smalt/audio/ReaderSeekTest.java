package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.UnsizedSource;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the seek contract on the containers that implement it (WAV) and on {@link AudioReader},
 * whose seek is sample-accurate, with a hand-built 8 kHz mono WAV whose samples count up, so every
 * sample identifies its own index.
 */
@DisplayName("Seeking")
class ReaderSeekTest {
    private static final int RATE = 8000;

    private static final int FRAMES = 8000;

    /** Builds a 16-bit mono WAV whose sample {@code i} is {@code i - 4000}. */
    static byte[] wav() {
        var data = ByteBuffer.allocate(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < FRAMES; i++) {
            data.putShort((short) (i - FRAMES / 2));
        }
        var file = ByteBuffer.allocate(44 + FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);
        file.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + FRAMES * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        file.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        file.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(FRAMES * 2).put(data.array());
        return file.array();
    }

    @Nested
    @DisplayName("a WAV demuxer")
    class Wav {
        @Test
        @DisplayName("restarts at the sample frame displayed at the requested time")
        void seeks() {
            try (var demuxer = new WavDemuxer(MediaSource.of(wav()))) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(250_000);
                var packet = demuxer.read();
                assertEquals(2000, packet.pts());
                assertEquals(2000 - FRAMES / 2, (short) ((packet.data()[0] & 0xFF) | (packet.data()[1] << 8)));
                demuxer.seek(-5);
                assertEquals(0, demuxer.read().pts());
                demuxer.seek(2_000_000);
                assertNull(demuxer.read());
                demuxer.seek(999_999);
                assertEquals(7999, demuxer.read().pts());
            }
        }
    }

    @Nested
    @DisplayName("an audio reader")
    class Reader {
        @ParameterizedTest(name = "at {0} us")
        @ValueSource(longs = {0, 125, 312_500, 500_000, 999_875})
        @DisplayName("returns exactly the samples from the requested time on")
        void sampleAccurate(long micros) {
            var whole = readAll(wav(), -1);
            var tail = readAll(wav(), micros);
            var start = (int) (micros * RATE / 1_000_000);
            assertArrayEquals(Arrays.copyOfRange(whole, start, whole.length), tail);
        }

        @Test
        @DisplayName("ends after a seek past the end")
        void pastEnd() {
            try (var reader = AudioReader.open(MediaSource.of(wav()))) {
                assertTrue(reader.isSeekable());
                reader.seek(5_000_000);
                assertNull(reader.read());
            }
        }

        @Test
        @DisplayName("seeks uncompressed audio in Matroska sample-accurately")
        void matroska() {
            var file = Fixtures.tone("mkv", 440, RATE, 1, 0.5, List.of("-c:a", "pcm_s16le"));
            var bytes = Fixtures.read(file);
            float[] whole;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                whole = drain(reader);
            }
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                assertTrue(reader.isSeekable());
                reader.seek(250_000);
                assertArrayEquals(Arrays.copyOfRange(whole, 2000, whole.length), drain(reader));
            }
        }

        @Test
        @DisplayName("rejects seeking a container that cannot seek")
        void unsupported() {
            var file = Fixtures.tone("aac", 440, 48_000, 1, 0.2, List.of("-c:a", "aac", "-f", "adts"));
            try (var demuxer = Containers.openDemuxer(new UnsizedSource(Fixtures.read(file), 4096))) {
                assertFalse(demuxer.isSeekable());
                assertThrows(MediaException.Unsupported.class, () -> demuxer.seek(0));
                assertTrue(demuxer.read() != null);
            }
        }

        @Test
        @DisplayName("refuses to read after a failed seek until a seek succeeds")
        void failedSeek() {
            // 50 s of 16-bit samples equal to their index, so the demuxer's read-ahead no longer
            // holds the start of the data once the reader is past 30 s
            var frames = RATE * 50;
            var data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < frames; i++) {
                data.putShort((short) i);
            }
            var file = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
            file.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2)
                    .put("WAVE".getBytes(StandardCharsets.US_ASCII));
            file.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                    .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
            file.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2).put(data.array());
            var source = new FailingSource(file.array());
            try (var reader = AudioReader.open(source)) {
                var read = 0L;
                while (read < RATE * 30L) {
                    read += reader.read().frames();
                }
                source.failing = true;
                assertThrows(MediaException.Io.class, () -> reader.seek(312_500));
                source.failing = false;
                assertThrows(IllegalStateException.class, reader::read);
                reader.seek(312_500);
                assertEquals(2500 / 32768f, reader.read().planes()[0][0]);
            }
        }

        private static float[] drain(AudioReader reader) {
            var samples = new float[RATE];
            var count = 0;
            for (var block = reader.read(); block != null; block = reader.read()) {
                System.arraycopy(block.planes()[0], 0, samples, count, block.frames());
                count += block.frames();
            }
            return Arrays.copyOf(samples, count);
        }

        private static float[] readAll(byte[] data, long seek) {
            try (var reader = AudioReader.open(MediaSource.of(data),
                    AudioReaderOptions.builder().blockFrames(333).build())) {
                if (seek >= 0) {
                    reader.seek(seek);
                }
                var samples = new float[FRAMES];
                var count = 0;
                for (var block = reader.read(); block != null; block = reader.read()) {
                    System.arraycopy(block.planes()[0], 0, samples, count, block.frames());
                    count += block.frames();
                }
                return Arrays.copyOf(samples, count);
            }
        }
    }

    /** A byte-array source whose reads fail while {@link #failing} is set. */
    private static final class FailingSource implements MediaSource {
        private final byte[] data;
        private boolean failing;

        FailingSource(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (failing) {
                throw new MediaException.Io("read failed", new java.io.IOException("injected"));
            }
            if (position >= data.length) {
                return length == 0 ? 0 : -1;
            }
            var count = (int) Math.min(length, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            return count;
        }

        @Override
        public void close() {
        }
    }

    @Nested
    @DisplayName("a stream decoder timing")
    class StreamTiming {
        @Test
        @DisplayName("is exact for WAV and restarts a decoder at the requested sample")
        void restarts() {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(wav()))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var timing = AudioStreamDecoder.measure(demuxer, track, AudioStreamDecoderOptions.defaults());
                assertTrue(timing.isExact());
                assertEquals(20_000, timing.prerollMicros());
                assertEquals(292_500, timing.seekMicros(312_500));
                demuxer.seek(timing.seekMicros(312_500));
                try (var decoder = AudioStreamDecoder.create(track)) {
                    decoder.startAt(312_500, timing);
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        decoder.decode(packet);
                    }
                    decoder.finish();
                    var planes = new float[1][FRAMES];
                    var count = decoder.read(planes, 0, FRAMES);
                    var whole = Reader.readAll(wav(), -1);
                    assertArrayEquals(Arrays.copyOfRange(whole, 2500, whole.length), Arrays.copyOf(planes[0], count));
                }
            }
        }

        @Test
        @DisplayName("rejects a track of another demuxer, a decoder of another track and a decoder that started")
        void misuse() {
            var other = Fixtures.tone("mkv", 440, RATE, 1, 0.5, List.of("-c:a", "pcm_s16le"));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(wav()));
                 var otherDemuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(other)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var otherTrack = (AudioTrack) otherDemuxer.tracks().getFirst();
                assertThrows(IllegalArgumentException.class,
                        () -> AudioStreamDecoder.measure(demuxer, otherTrack, AudioStreamDecoderOptions.defaults()));
                var otherTiming = AudioStreamDecoder.measure(otherDemuxer, otherTrack, AudioStreamDecoderOptions.defaults());
                var timing = AudioStreamDecoder.measure(demuxer, track, AudioStreamDecoderOptions.defaults());
                try (var decoder = AudioStreamDecoder.create(track)) {
                    assertThrows(IllegalArgumentException.class, () -> decoder.startAt(0, otherTiming));
                    demuxer.seek(0);
                    decoder.decode(demuxer.read());
                    assertThrows(IllegalStateException.class, () -> decoder.startAt(0, timing));
                }
            }
        }
    }
}
