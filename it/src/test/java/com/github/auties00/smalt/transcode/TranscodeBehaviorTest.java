package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the job controls (progress, cancellation, failures before writing) and the picture and
 * sample operations of the transcode API, checked against ffmpeg.
 */
class TranscodeBehaviorTest {
    static Path clip() {
        return Fixtures.testVideo("mp4", 320, 240, 3, List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-shortest"));
    }

    static TranscodeResult transcode(Path input, Path output, TranscodeOptions options) {
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            return Transcoder.transcode(in, out, options);
        }
    }

    @Nested
    @DisplayName("job controls")
    class Controls {
        @Test
        @DisplayName("writes a 10 s 30 fps A/V transcode to AV1 in WebM in decoding order despite the encoder's lookahead")
        void delayedVideoOrder(@TempDir Path directory) {
            var input = Fixtures.testVideo("webm", 160, 120, 10, List.of("-c:v", "libvpx-vp9", "-deadline", "realtime",
                    "-cpu-used", "8", "-c:a", "libopus", "-shortest"));
            var output = directory.resolve("av1.webm");
            transcode(input, output, TranscodeOptions.of(OutputContainer.WEBM)
                    .toBuilder().video(VideoTranscodeOptions.encode(Codec.AV1)).audio(AudioTranscodeOptions.copy())
                            .build());
            Oracle.assertDecodesCleanly(output);
            try (var source = MediaSource.of(output);
                 var demuxer = new com.github.auties00.smalt.container.matroska.MatroskaDemuxer(source)) {
                var timeBases = new java.util.HashMap<Integer, Rational>();
                demuxer.tracks().forEach(track -> timeBases.put(track.id(), track.timeBase()));
                var last = 0L;
                var count = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    var time = packet.dts() != com.github.auties00.smalt.track.Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
                    var micros = timeBases.get(packet.trackId()).toMicros(time);
                    // blocks store millisecond ticks and Opus blocks carry the pre-skip, so times read back
                    // may step back by up to one tick without any block being out of order
                    assertTrue(micros >= last - 1000, "block at " + micros + " us after one at " + last + " us");
                    last = Math.max(last, micros);
                    count++;
                }
                assertTrue(count > 700, "blocks " + count);
            }
        }

        @Test
        @DisplayName("reports progress that never decreases and ends at the output duration")
        void progress(@TempDir Path directory) {
            var reports = new ArrayList<TranscodeProgress>();
            var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder().listener(reports::add).build();
            var result = transcode(clip(), directory.resolve("out.webm"), options);
            assertTrue(reports.size() > 5, "reports " + reports.size());
            for (var index = 1; index < reports.size(); index++) {
                assertTrue(reports.get(index).positionMicros() >= reports.get(index - 1).positionMicros(), "monotonic at " + index);
            }
            var last = reports.getLast();
            assertEquals(result.durationMicros(), last.positionMicros());
            assertEquals(1.0, last.fraction().orElseThrow(), 1e-9);
            assertEquals(3_000_000, reports.getFirst().durationMicros(), 50_000, "expected duration from the input");
        }

        @Test
        @DisplayName("reports progress on the remux path too")
        void remuxProgress(@TempDir Path directory) {
            var reports = new ArrayList<TranscodeProgress>();
            var result = transcode(clip(), directory.resolve("out.mp4"), TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().listener(reports::add).build());
            assertTrue(result.remuxed());
            assertFalse(reports.isEmpty());
            assertEquals(result.durationMicros(), reports.getLast().positionMicros());
        }

        @Test
        @DisplayName("stops with a cancellation exception when the job is canceled mid-run")
        void cancel(@TempDir Path directory) {
            var output = MediaSink.memory();
            var job = new TranscodeJob[1];
            var reports = new ArrayList<TranscodeProgress>();
            var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder().listener(progress -> {
                reports.add(progress);
                if (progress.positionMicros() > 500_000) {
                    job[0].cancel();
                }
            }).build();
            try (var in = MediaSource.of(clip())) {
                job[0] = Transcoder.job(in, output, options);
                assertThrows(CancellationException.class, job[0]::run);
            }
            assertTrue(job[0].isCanceled());
            assertTrue(reports.getLast().positionMicros() < 1_500_000, "stopped early");
            assertThrows(IllegalStateException.class, job[0]::run, "a job runs once");
        }

        @Test
        @DisplayName("stops a whole-file remux when the job is canceled")
        void cancelRemux() {
            var job = new TranscodeJob[1];
            var options = TranscodeOptions.of(OutputContainer.MP4).toBuilder().listener(progress -> job[0].cancel())
                    .build();
            try (var in = MediaSource.of(clip())) {
                job[0] = Transcoder.job(in, MediaSink.memory(), options);
                assertThrows(CancellationException.class, job[0]::run);
            }
        }

        @Test
        @DisplayName("stops when the running thread is interrupted, keeping the interrupt status")
        void interrupt() {
            var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder()
                    .listener(progress -> Thread.currentThread().interrupt()).build();
            try (var in = MediaSource.of(clip())) {
                assertThrows(CancellationException.class, () -> Transcoder.transcode(in, MediaSink.memory(), options));
                assertTrue(Thread.interrupted(), "interrupt status kept (and cleared here)");
            }
        }

        @Test
        @DisplayName("a job canceled before it runs reads nothing")
        void canceledBefore() {
            var output = MediaSink.memory();
            try (var in = MediaSource.of(clip())) {
                var job = Transcoder.job(in, output, TranscodeOptions.defaults());
                job.cancel();
                assertThrows(CancellationException.class, job::run);
            }
            assertEquals(0, output.toByteArray().length);
        }

        @Test
        @DisplayName("fails before writing when a stream cannot be copied, a track is missing or a codec is wrong")
        void failures() {
            try (var in = MediaSource.of(clip())) {
                var output = MediaSink.memory();
                var copy = assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(in, output,
                        TranscodeOptions.remux(OutputContainer.WEBM)));
                assertTrue(copy.getMessage().contains("cannot copy"), copy.getMessage());
                assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(in, output,
                        TranscodeOptions.builder().video(VideoTranscodeOptions.builder().trackId(42).build()).build()));
                assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(in, output,
                        TranscodeOptions.builder().audio(AudioTranscodeOptions.builder().trackId(1).build()).build()));
                assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(in, output,
                        TranscodeOptions.of(OutputContainer.WEBM).toBuilder()
                                .video(VideoTranscodeOptions.encode(Codec.H264)).build()));
                assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(in, output,
                        TranscodeOptions.of(OutputContainer.WAV).toBuilder().video(VideoTranscodeOptions.copy())
                                .build()));
                assertEquals(0, output.toByteArray().length, "nothing written");
            }
        }

        @Test
        @DisplayName("writes the container named by the output file's extension")
        void pathOverload(@TempDir Path directory) {
            var output = directory.resolve("clip.webm");
            var result = Transcoder.transcode(clip(), output);
            assertEquals(OutputContainer.WEBM, result.container());
            Oracle.assertDecodesCleanly(output);
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(clip(), directory.resolve("clip.xyz")));
        }
    }

    @Nested
    @DisplayName("picture operations")
    class Pictures {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"MP4, 90, 240, 180", "WEBM, 0, 180, 240"})
        @DisplayName("keeps a rotation as metadata where the container stores it and turns the pixels upright elsewhere")
        void rotation(OutputContainer container, int rotation, int width, int height, @TempDir Path directory) {
            var rotated = Fixtures.ffmpeg("mp4", List.of("-display_rotation", "90", "-i", clip().toString()), List.of("-c", "copy"));
            var output = directory.resolve("out." + container.extension());
            transcode(rotated, output, TranscodeOptions.of(container)
                    .toBuilder().video(VideoTranscodeOptions.encode(container.videoCodecs().getFirst()).toBuilder()
                            .maxSize(0, 240).build()).build());
            var video = MediaProbe.of(output).video();
            assertEquals(width, video.width());
            assertEquals(height, video.height());
            assertEquals(rotation, Math.abs(video.rotation()));
            // ffmpeg turns both upright when decoding, so the displayed pictures must match
            var expected = Oracle.decodeVideo(Fixtures.ffmpeg("mp4", List.of("-i", rotated.toString()),
                    List.of("-vf", "scale=180:240", "-c:v", "libx264", "-qp", "0", "-an")), "gray", 1);
            var actual = Oracle.decodeVideo(output, "gray", 1);
            assertEquals(expected.length, actual.length);
            assertTrue(meanAbsoluteDifference(expected, actual) < 12, "upright picture");
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @CsvSource({"FIT, 200, 200, 200, 150", "FILL, 200, 200, 200, 200", "EXACT, 100, 50, 100, 50", "FIT, 0, 99, 132, 98"})
        @DisplayName("fits the picture to a box with even dimensions")
        void scale(ScaleMode mode, int boxWidth, int boxHeight, int width, int height, @TempDir Path directory) {
            var output = directory.resolve("out.mp4");
            transcode(clip(), output, TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().video(VideoTranscodeOptions.builder().size(boxWidth, boxHeight, mode)
                            .filter(ResizeFilter.AREA).build()).build());
            var video = MediaProbe.of(output).video();
            assertEquals(width, video.width());
            assertEquals(height, video.height());
            assertEquals(90, video.decoded());
        }

        @Test
        @DisplayName("crops in displayed coordinates")
        void crop(@TempDir Path directory) {
            var output = directory.resolve("out.mp4");
            transcode(clip(), output, TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().video(VideoTranscodeOptions.builder().crop(new Region(40, 20, 160, 120)).build())
                            .build());
            var video = MediaProbe.of(output).video();
            assertEquals(160, video.width());
            assertEquals(120, video.height());
            var expected = Oracle.decodeVideo(Fixtures.ffmpeg("mp4", List.of("-i", clip().toString()),
                    List.of("-vf", "crop=160:120:40:20", "-c:v", "libx264", "-qp", "0", "-an")), "gray", 1);
            assertTrue(meanAbsoluteDifference(expected, Oracle.decodeVideo(output, "gray", 1)) < 6, "cropped picture");
        }

        @ParameterizedTest(name = "{0} fps")
        @CsvSource({"15, 45", "10, 30", "60, 180", "24, 72"})
        @DisplayName("converts the frame rate by dropping and repeating frames")
        void frameRate(int rate, int frames, @TempDir Path directory) {
            var output = directory.resolve("out.mp4");
            transcode(clip(), output, TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().video(VideoTranscodeOptions.builder().frameRate(new Rational(rate, 1)).build())
                            .build());
            var probe = MediaProbe.of(output);
            assertEquals(frames, probe.video().decoded());
            assertEquals(3.0, probe.audio().audioSeconds(), 0.05);
        }

        @Test
        @DisplayName("caps the frame rate only when the source exceeds it")
        void maxFrameRate(@TempDir Path directory) {
            var capped = directory.resolve("capped.mp4");
            transcode(clip(), capped, TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().video(VideoTranscodeOptions.builder().maxFrameRate(new Rational(20, 1)).build())
                            .build());
            assertEquals(60, MediaProbe.of(capped).video().decoded());
            var kept = directory.resolve("kept.mp4");
            var result = transcode(clip(), kept, TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().video(VideoTranscodeOptions.builder().maxFrameRate(new Rational(60, 1)).build())
                            .build());
            assertTrue(result.remuxed(), "a source under the cap is copied");
        }
    }

    @Nested
    @DisplayName("WAV outputs")
    class WavOutputs {
        @Test
        @DisplayName("decode coded audio to PCM unless the options copy it or name its codec")
        void codedAudio(@TempDir Path directory) {
            var input = Fixtures.tone("mp3", 440, 44_100, 2, 1.0, List.of("-c:a", "libmp3lame", "-b:a", "128k"));
            var decoded = directory.resolve("decoded.wav");
            assertFalse(transcode(input, decoded, TranscodeOptions.of(OutputContainer.WAV)).streams().getFirst().copied());
            assertEquals("pcm_s16le", MediaProbe.of(decoded).audio().codec());
            var copied = directory.resolve("copied.wav");
            var result = transcode(input, copied, TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                    .audio(AudioTranscodeOptions.copy()).build());
            assertTrue(result.streams().getFirst().copied());
            assertEquals(Codec.MP3, result.streams().getFirst().track().codec());
            assertEquals("mp3", MediaProbe.of(copied).audio().codec());
            Oracle.assertDecodesCleanly(copied);
            var named = directory.resolve("named.wav");
            transcode(input, named, TranscodeOptions.of(OutputContainer.WAV).toBuilder()
                    .audio(AudioTranscodeOptions.builder().codec(Codec.MP3).build()).build());
            assertEquals("mp3", MediaProbe.of(named).audio().codec());
        }

        @Test
        @DisplayName("copy coded audio no installed decoder decodes, and refuse to re-encode it")
        void undecodableAudio(@TempDir Path directory) {
            var input = Fixtures.tone("wav", 440, 8000, 1, 1.0, List.of("-c:a", "adpcm_ima_wav"));
            var copied = directory.resolve("copied.wav");
            var result = transcode(input, copied, TranscodeOptions.of(OutputContainer.WAV));
            assertTrue(result.streams().getFirst().copied());
            assertEquals(Codec.ADPCM_IMA_WAV, result.streams().getFirst().track().codec());
            assertEquals("adpcm_ima_wav", MediaProbe.of(copied).audio().codec());
            assertArrayEquals(Oracle.decodeAudio(input), Oracle.decodeAudio(copied), "the same samples");
            assertThrows(MediaException.Unsupported.class, () -> transcode(input, directory.resolve("encoded.mp4"),
                    TranscodeOptions.of(OutputContainer.MP4)));
        }
    }

    @Nested
    @DisplayName("sample operations")
    class Samples {
        @Test
        @DisplayName("applies gain, downmixes and resamples")
        void process(@TempDir Path directory) {
            var input = Fixtures.tone("wav", 1000, 44_100, 2, 2.0, List.of("-c:a", "pcm_s16le"));
            var output = directory.resolve("out.wav");
            var processing = AudioTranscodeOptions.builder().layout(ChannelLayout.MONO).sampleRate(16_000).build();
            transcode(input, output, TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(processing.toBuilder()
                    .gainDb(-6.0206).build()).build());
            var reference = directory.resolve("reference.wav");
            transcode(input, reference, TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(processing).build());
            var audio = MediaProbe.of(output).audio();
            assertEquals(1, audio.channels());
            assertEquals(16_000, audio.sampleRate());
            assertEquals(32_000, audio.decoded(), 1);
            assertEquals(peak(MediaProbe.monoSamples(reference)) / 2, peak(MediaProbe.monoSamples(output)), 1e-3, "half the amplitude");
        }

        @Test
        @DisplayName("writes every audio track when asked")
        void allTracks(@TempDir Path directory) {
            var video = clip();
            var tones = Fixtures.tone("wav", 660, 48_000, 1, 3.0, List.of("-c:a", "pcm_s16le"));
            var input = Fixtures.ffmpeg("mkv", List.of("-i", video.toString(), "-i", tones.toString()),
                    List.of("-map", "0:v", "-map", "0:a", "-map", "1:a", "-c:v", "copy", "-c:a", "aac"));
            var output = directory.resolve("out.mkv");
            var result = transcode(input, output, TranscodeOptions.of(OutputContainer.MATROSKA)
                    .toBuilder().audio(AudioTranscodeOptions.builder().allTracks().build()).build());
            assertEquals(3, result.streams().size());
            var probe = MediaProbe.of(output);
            assertEquals(2, probe.of("audio").size());
            Oracle.assertDecodesCleanly(output);
        }

        @Test
        @DisplayName("resamples to a rate Opus codes when the source rate is not one")
        void opusRate(@TempDir Path directory) {
            var input = Fixtures.tone("wav", 440, 44_100, 2, 1.0, List.of("-c:a", "pcm_s16le"));
            var output = directory.resolve("out.opus");
            var result = transcode(input, output, TranscodeOptions.of(OutputContainer.OGG));
            var track = (com.github.auties00.smalt.track.AudioTrack) result.streams().getFirst().track();
            assertEquals(48_000, track.sampleRate());
            assertEquals(48_000, MediaProbe.of(output).audio().decoded(), 1);
        }
    }

    static double meanAbsoluteDifference(byte[] expected, byte[] actual) {
        var sum = 0L;
        for (var index = 0; index < expected.length; index++) {
            sum += Math.abs((expected[index] & 0xFF) - (actual[index] & 0xFF));
        }
        return (double) sum / expected.length;
    }

    static double peak(float[] samples) {
        var peak = 0.0;
        for (var index = samples.length / 4; index < samples.length * 3 / 4; index++) {
            peak = Math.max(peak, Math.abs(samples[index]));
        }
        return peak;
    }
}
