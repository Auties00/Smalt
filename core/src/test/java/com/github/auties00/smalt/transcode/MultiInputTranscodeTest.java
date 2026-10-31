package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.transcode.TranscodeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes several inputs into one output with this module's codecs: concatenations of PCM and uncompressed video,
 * copied and encoded, compared with the sequential decodes of their files, and combinations of streams from several
 * files, compared with each file's own streams. The audio and video concatenations and combinations in one file are
 * covered with real codecs by the {@code it} module.
 */
@DisplayName("Transcodes of several inputs")
class MultiInputTranscodeTest {
    static final int RATE = 48_000;
    static final Rational FPS = new Rational(10, 1);

    static float[] concat(float[] a, float[] b) {
        var result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    static byte[] video(int first, int frames) {
        return y4m(raw(0, 32, 16, FPS, frames, index -> gradient(32, 16, first + index)));
    }

    static TranscodeResult concatenate(List<byte[]> files, MediaSink sink, TranscodeOptions options) {
        var sources = new ArrayList<MediaSource>();
        for (var file : files) {
            sources.add(MediaSource.of(file));
        }
        return Transcoder.concat(sources, sink, options);
    }

    @Nested
    @DisplayName("concatenation")
    class Concatenation {
        @Test
        @DisplayName("copies PCM files into one whose samples are theirs end to end")
        void pcmCopy() {
            var a = sine(440, RATE, RATE + 123);
            var b = sine(1_000, RATE, RATE / 2);
            var sink = MediaSink.memory();
            var result = concatenate(List.of(wav(RATE, new float[][]{a}), wav(RATE, new float[][]{b})), sink,
                    TranscodeOptions.of(OutputContainer.WAV));
            assertTrue(result.streams().getFirst().copied());
            assertArrayEquals(concat(a, b), audio(sink.toByteArray())[0]);
            assertEquals(Math.round((a.length + b.length) * 1e6 / RATE), result.durationMicros(), 1);
        }

        @Test
        @DisplayName("encodes three files, the middle one at another rate, decoding each with its own decoder")
        void differentRates() {
            var a = sine(440, RATE, RATE);
            var b = sine(1_000, 44_100, 44_100);
            var c = sine(300, RATE, RATE / 2);
            var files = List.of(wav(RATE, new float[][]{a}), wav(44_100, new float[][]{b}), wav(RATE, new float[][]{c}));
            var sink = MediaSink.memory();
            var result = concatenate(files, sink, TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                    .audio(AudioTranscodeOptions.builder().codec(Codec.PCM_F32LE).build()).build());
            assertFalse(result.streams().getFirst().copied());
            var output = audio(sink.toByteArray())[0];
            assertEquals(RATE, sampleRate(sink.toByteArray()));
            assertEquals(2 * RATE + RATE / 2, output.length, 2);
            Tolerance.maxAbs(1e-6).assertPcm("first file", a, Arrays.copyOf(output, RATE));
            Tolerance.maxAbs(1e-6).assertPcm("third file", c, Arrays.copyOfRange(output, 2 * RATE, 2 * RATE + RATE / 2));
            var resampled = Arrays.copyOfRange(output, RATE + 2_000, 2 * RATE - 2_000);
            var peak = 0.0;
            var best = 0;
            for (var frequency = 900; frequency <= 1_100; frequency++) {
                var coefficient = 2 * Math.cos(2 * Math.PI * frequency / RATE);
                double previous = 0, before = 0;
                for (var sample : resampled) {
                    var current = sample + coefficient * previous - before;
                    before = previous;
                    previous = current;
                }
                var power = previous * previous + before * before - coefficient * previous * before;
                if (power > peak) {
                    peak = power;
                    best = frequency;
                }
            }
            assertEquals(1_000, best, 1, "the second file is resampled to the first file's rate");
            assertThrows(MediaException.Unsupported.class, () -> concatenate(files, MediaSink.memory(),
                    TranscodeOptions.remux(OutputContainer.WAV)));
        }

        @Test
        @DisplayName("copies uncompressed video with continuous timestamps, every frame kept")
        void videoCopy() {
            var sink = MediaSink.memory();
            var result = concatenate(List.of(video(0, 10), video(10, 7)), sink, TranscodeOptions.remux(OutputContainer.Y4M));
            assertTrue(result.remuxed());
            var frames = TranscodeFixtures.video(sink.toByteArray());
            assertEquals(17, frames.size());
            for (var index = 0; index < 17; index++) {
                assertEquals(100_000L * index, frames.get(index).micros(), "frame " + index);
                assertArrayEquals(gradient(32, 16, index).y(), frames.get(index).picture().y(), "frame " + index);
            }
            assertEquals(1_700_000, result.durationMicros(), 1);
        }

        @Test
        @DisplayName("copies no video whose files differ in dimensions, encoding it instead")
        void videoDimensions() {
            var small = y4m(raw(0, 16, 16, FPS, 5, index -> gradient(16, 16, index)));
            var files = List.of(video(0, 10), small);
            var failure = assertThrows(MediaException.Unsupported.class, () -> concatenate(files, MediaSink.memory(),
                    TranscodeOptions.remux(OutputContainer.Y4M)));
            assertTrue(failure.getMessage().contains("dimensions"), failure.getMessage());
            var sink = MediaSink.memory();
            var result = concatenate(files, sink, TranscodeOptions.of(OutputContainer.Y4M).toBuilder()
                    .video(VideoTranscodeOptions.encode(Codec.RAW_VIDEO)).build());
            assertFalse(result.streams().getFirst().copied());
            var frames = TranscodeFixtures.video(sink.toByteArray());
            assertEquals(15, frames.size());
            for (var frame : frames) {
                assertEquals(32, frame.picture().width());
                assertEquals(16, frame.picture().height());
            }
        }

        @Test
        @DisplayName("encodes a range across the join, with a new decoder for the second file")
        void videoRange() {
            var sink = MediaSink.memory();
            concatenate(List.of(video(0, 10), video(10, 10)), sink, TranscodeOptions.of(OutputContainer.Y4M).toBuilder()
                    .video(VideoTranscodeOptions.encode(Codec.RAW_VIDEO)).range(500_000, 1_500_000).build());
            var frames = TranscodeFixtures.video(sink.toByteArray());
            assertEquals(10, frames.size());
            for (var index = 0; index < 10; index++) {
                assertEquals(100_000L * index, frames.get(index).micros());
                assertArrayEquals(gradient(32, 16, index + 5).y(), frames.get(index).picture().y(), "frame " + index);
            }
        }

        @Test
        @DisplayName("rejects files whose tracks do not match, and an empty list")
        void mismatch() {
            var audioOnly = wav(RATE, new float[][]{sine(440, RATE, RATE)});
            assertThrows(IllegalArgumentException.class, () -> concatenate(List.of(video(0, 10), audioOnly),
                    MediaSink.memory(), TranscodeOptions.of(OutputContainer.Y4M)));
            assertThrows(IllegalArgumentException.class, () -> TranscodeInput.concat(List.of()));
            assertFalse(TranscodeInput.of(MediaSource.of(audioOnly)).isConcatenation());
        }
    }

    @Nested
    @DisplayName("combining inputs")
    class Combining {
        static final String SUBTITLES = "1\n00:00:00,200 --> 00:00:00,800\nHello\n\n2\n00:00:01,000 --> 00:00:01,500\n"
                + "World\n";

        @Test
        @DisplayName("takes the audio of one file and the subtitles of another")
        void audioAndSubtitles() {
            var samples = sine(440, RATE, 2 * RATE);
            var sound = wav(RATE, new float[][]{samples});
            var sink = MediaSink.memory();
            var result = Transcoder.transcode(List.of(TranscodeInput.of(MediaSource.of(sound)),
                            TranscodeInput.of(MediaSource.of(srt(SUBTITLES)))), sink,
                    TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                            .audio(AudioTranscodeOptions.builder().codec(Codec.PCM_S16LE).build()).build());
            assertEquals(2, result.streams().size());
            assertEquals(List.of(0, 1), result.streams().stream().map(TranscodedStream::sourceInput).toList());
            assertEquals(TrackKind.AUDIO, result.streams().get(0).track().kind());
            var output = sink.toByteArray();
            var decoded = audio(output)[0];
            assertEquals(samples.length, decoded.length);
            Tolerance.maxAbs(1.0 / 32_768).assertPcm("audio", samples, decoded);
            var text = demux(output).stream().filter(stream -> stream.track().codec().isSubtitle()).findFirst()
                    .orElseThrow();
            assertEquals(2, text.packets().size());
            assertEquals("Hello", new String(text.packets().get(0).data(), StandardCharsets.UTF_8).trim());
            assertEquals("World", new String(text.packets().get(1).data(), StandardCharsets.UTF_8).trim());
            assertEquals(200_000, text.track().timeBase().toMicros(text.packets().get(0).pts()));
            assertEquals(1_000_000, text.track().timeBase().toMicros(text.packets().get(1).pts()));
        }

        @Test
        @DisplayName("selects streams by input, and every audio track of every input")
        void selection() {
            var first = sine(440, RATE, RATE);
            var other = sine(1_000, RATE, RATE);
            var inputs = List.of(TranscodeInput.of(MediaSource.of(wav(RATE, new float[][]{first}))),
                    TranscodeInput.of(MediaSource.of(wav(RATE, new float[][]{other}))));
            var sink = MediaSink.memory();
            var result = Transcoder.transcode(inputs, sink, TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                    .audio(AudioTranscodeOptions.builder().input(1).build()).build());
            assertEquals(1, result.streams().getFirst().sourceInput());
            assertArrayEquals(other, audio(sink.toByteArray())[0]);
            var defaults = MediaSink.memory();
            Transcoder.transcode(inputs, defaults, TranscodeOptions.of(OutputContainer.WAV));
            assertArrayEquals(first, audio(defaults.toByteArray())[0], "without an input, the first one that has audio");
            var every = Transcoder.transcode(inputs, MediaSink.memory(), TranscodeOptions.of(OutputContainer.MATROSKA)
                    .toBuilder().audio(AudioTranscodeOptions.builder().allTracks().build()).build());
            assertEquals(List.of(0, 1), every.streams().stream().map(TranscodedStream::sourceInput).toList());
            var id = demux(wav(RATE, new float[][]{other})).getFirst().track().id();
            var byId = Transcoder.transcode(inputs, MediaSink.memory(), TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.builder().input(1).trackId(id).build()).build());
            assertEquals(1, byId.streams().getFirst().sourceInput());
            assertEquals(id, byId.streams().getFirst().sourceTrackId());
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(inputs, MediaSink.memory(),
                    TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                            .audio(AudioTranscodeOptions.builder().input(2).build()).build()));
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(inputs, MediaSink.memory(),
                    TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                            .audio(AudioTranscodeOptions.builder().input(1).trackId(id + 99).build()).build()));
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(List.of(inputs.getFirst()),
                    MediaSink.memory(), TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                            .audio(AudioTranscodeOptions.builder().input(1).build()).build()));
        }

        @Test
        @DisplayName("combines a concatenation with another input")
        void withConcatenation() {
            var a = sine(440, RATE, RATE / 2);
            var b = sine(660, RATE, RATE / 3);
            var concatenated = TranscodeInput.concat(List.of(MediaSource.of(wav(RATE, new float[][]{a})),
                    MediaSource.of(wav(RATE, new float[][]{b}))));
            var subtitles = TranscodeInput.of(MediaSource.of(srt(SUBTITLES)));
            var sink = MediaSink.memory();
            var result = Transcoder.transcode(List.of(subtitles, concatenated), sink,
                    TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                            .audio(AudioTranscodeOptions.builder().codec(Codec.PCM_F32LE).build()).build());
            assertEquals(List.of(1, 0), result.streams().stream().map(TranscodedStream::sourceInput).toList());
            assertArrayEquals(concat(a, b), audio(sink.toByteArray())[0]);
        }
    }
}
