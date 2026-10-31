package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.y4m.Y4mMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoEncoder;
import com.github.auties00.smalt.video.RawVideoFormat;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes audio/video files built with the lossless FFV1 encoder and PCM through the operations that join or split
 * streams: concatenations of A/V clips (copied, and encoded with the audio padded at the joins), the combination of a
 * video, an audio and a subtitle file, renditions of one input, the silence trim moving every stream, and a speed
 * change; outputs are compared with the inputs' own pictures and samples, and ffprobe and ffmpeg read them as black
 * boxes.
 */
@DisplayName("Audio/video transcodes of several inputs and outputs")
class AvEffectsTranscodeTest {
    static final int RATE = 48_000;
    static final Rational FPS = new Rational(10, 1);
    static final int WIDTH = 32;
    static final int HEIGHT = 16;

    record Stream(Track track, List<Packet> packets) {
    }

    static YuvPicture picture(int index) {
        var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var bar = x >= (3 * index) % WIDTH && x < (3 * index) % WIDTH + 4;
                picture.y()[y * picture.yStride() + x] = (byte) (bar ? 220 : 40 + y + index % 64);
            }
        }
        Arrays.fill(picture.u(), (byte) (100 + index % 50));
        Arrays.fill(picture.v(), (byte) (160 - index % 50));
        return picture;
    }

    static float[] sine(double frequency, int frames) {
        var samples = new float[frames];
        for (var index = 0; index < frames; index++) {
            samples[index] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * index / RATE));
        }
        return samples;
    }

    static Stream ffv1(int id, int first, int frames) {
        var encoder = VideoEncoders.create(Codec.FFV1, VideoEncoderOptions.of(WIDTH, HEIGHT, FPS, 1_000_000).toBuilder()
                .color(ColorInfo.BT709_LIMITED).build());
        var packets = new ArrayList<Packet>();
        try (encoder) {
            for (var index = 0; index < frames; index++) {
                var pts = FPS.invert().rescale(index, encoder.timeBase());
                encoder.encode(new VideoFrame(picture(first + index), pts, FPS.invert().rescale(1, encoder.timeBase()),
                        false), packet -> packets.add(packet.withTrackId(id)));
            }
            encoder.flush(packet -> packets.add(packet.withTrackId(id)));
            var track = VideoTrack.builder().id(id).codec(Codec.FFV1).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElse(null)).width(WIDTH).height(HEIGHT).frameRate(FPS)
                    .color(ColorInfo.BT709_LIMITED).build();
            return new Stream(track, packets);
        }
    }

    static Stream raw(int id, int first, int frames) {
        var encoder = new RawVideoEncoder(RawVideoFormat.of(RawPixelFormat.YUV420P), WIDTH, HEIGHT, FPS,
                ColorInfo.BT709_LIMITED);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < frames; index++) {
            encoder.encode(new VideoFrame(picture(first + index), index, 1, true), packet -> packets.add(packet.withTrackId(id)));
        }
        var track = VideoTrack.builder().id(id).codec(Codec.RAW_VIDEO).timeBase(encoder.timeBase())
                .codecConfig(encoder.codecConfig().orElse(null)).width(WIDTH).height(HEIGHT).frameRate(FPS)
                .color(ColorInfo.BT709_LIMITED).build();
        return new Stream(track, packets);
    }

    static Stream pcm(int id, Codec codec, float[] samples) {
        var layout = ChannelLayout.MONO;
        var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, RATE, layout).toBuilder().framesPerPacket(1024).build());
        var packets = new ArrayList<Packet>();
        encoder.encode(new AudioBuffer(layout, RATE, new float[][]{samples}, samples.length),
                packet -> packets.add(packet.withTrackId(id)));
        encoder.flush(packet -> packets.add(packet.withTrackId(id)));
        var track = AudioTrack.builder().id(id).codec(codec).timeBase(Rational.timeBaseOf(RATE)).duration(samples.length)
                .sampleRate(RATE).layout(layout).bitsPerSample(encoder.bitsPerSample()).skipSamples(0).build();
        return new Stream(track, packets);
    }

    static byte[] mux(Function<MediaSink, Muxer> factory, Stream... streams) {
        record Timed(long micros, int order, Packet packet) {
        }
        var timed = new ArrayList<Timed>();
        for (var stream : streams) {
            for (var packet : stream.packets()) {
                var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
                timed.add(new Timed(stream.track().timeBase().toMicros(time), timed.size(), packet));
            }
        }
        timed.sort(Comparator.comparingLong(Timed::micros).thenComparingInt(Timed::order));
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            for (var stream : streams) {
                muxer.addTrack(stream.track());
            }
            for (var entry : timed) {
                muxer.writePacket(entry.packet());
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static byte[] clip(int first, int frames, float[] audio) {
        return mux(MatroskaMuxer::new, ffv1(1, first, frames), pcm(2, Codec.PCM_S16LE, audio));
    }

    static float[] samples(byte[] file) {
        var collected = new ArrayList<float[]>();
        var total = 0;
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            for (var block = reader.read(); block != null; block = reader.read()) {
                collected.add(Arrays.copyOf(block.plane(0), block.frames()));
                total += block.frames();
            }
        }
        var result = new float[total];
        var offset = 0;
        for (var block : collected) {
            System.arraycopy(block, 0, result, offset, block.length);
            offset += block.length;
        }
        return result;
    }

    record Frame(long micros, byte[] luma) {
    }

    static List<Frame> frames(byte[] file) {
        var frames = new ArrayList<Frame>();
        try (var reader = VideoReader.open(MediaSource.of(file))) {
            var timeBase = reader.track().timeBase();
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                var picture = (YuvPicture) frame.picture();
                var luma = new byte[WIDTH * HEIGHT];
                for (var y = 0; y < HEIGHT; y++) {
                    System.arraycopy(picture.y(), y * picture.yStride(), luma, y * WIDTH, WIDTH);
                }
                frames.add(new Frame(timeBase.toMicros(frame.pts()), luma));
            }
        }
        return frames;
    }

    static float[] concat(float[]... parts) {
        var total = 0;
        for (var part : parts) {
            total += part.length;
        }
        var result = new float[total];
        var offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    static void assertFrames(List<Frame> frames, int first, int count, long stepMicros) {
        assertEquals(count, frames.size());
        for (var index = 0; index < count; index++) {
            assertEquals(stepMicros * index, frames.get(index).micros(), 1_000, "time of frame " + index);
            assertArrayEquals(picture(first + index).y(), frames.get(index).luma(), "pixels of frame " + index);
        }
    }

    static TranscodeOptions.Builder encode() {
        return TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder().video(VideoTranscodeOptions.encode(Codec.FFV1))
                .audio(AudioTranscodeOptions.encode(Codec.PCM_S16LE));
    }

    static Path save(String name, byte[] data) throws IOException {
        var path = Scratch.directory("av-effects").resolve(name);
        Files.write(path, data);
        return path;
    }

    @Nested
    @DisplayName("concatenation")
    class Concatenation {
        @Test
        @DisplayName("copies A/V clips with continuous timestamps, which ffprobe reads, every packet kept")
        void copy() throws IOException {
            var first = clip(0, 10, sine(440, RATE));
            var second = clip(10, 10, sine(880, RATE));
            var sink = MediaSink.memory();
            var result = Transcoder.concat(List.of(MediaSource.of(first), MediaSource.of(second)), sink,
                    TranscodeOptions.remux(OutputContainer.MATROSKA));
            assertTrue(result.remuxed());
            var output = sink.toByteArray();
            assertFrames(frames(output), 0, 20, 100_000);
            assertArrayEquals(concat(samples(first), samples(second)), samples(output));
            Oracle.assume(Oracle.Tool.FFPROBE);
            var path = save("concat-copy.mkv", output);
            var times = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", path.toString())),
                    StandardCharsets.UTF_8).trim().split("\\R");
            assertEquals(20, times.length);
            for (var index = 0; index < times.length; index++) {
                assertEquals(0.1 * index, Double.parseDouble(times[index]), 0.002);
            }
            Oracle.assertDecodesCleanly(path);
        }

        @Test
        @DisplayName("pads a clip's short audio with silence so the next clip's audio starts with its video")
        void padding() {
            var first = clip(0, 10, sine(440, RATE - 4_800));
            var second = clip(10, 10, sine(880, RATE));
            var sink = MediaSink.memory();
            Transcoder.concat(List.of(MediaSource.of(first), MediaSource.of(second)), sink, encode().build());
            var output = sink.toByteArray();
            assertArrayEquals(concat(samples(first), new float[4_800], samples(second)), samples(output));
            assertFrames(frames(output), 0, 20, 100_000);
        }

        @Test
        @DisplayName("joins clips of different codecs by decoding each with its own decoder")
        void codecs() {
            var first = clip(0, 10, sine(440, RATE));
            var second = mux(MatroskaMuxer::new, ffv1(1, 10, 10), pcm(2, Codec.PCM_F32LE, sine(880, RATE)));
            var third = clip(20, 5, sine(220, RATE / 2));
            var sink = MediaSink.memory();
            var result = Transcoder.concat(List.of(MediaSource.of(first), MediaSource.of(second), MediaSource.of(third)),
                    sink, TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                            .video(VideoTranscodeOptions.encode(Codec.FFV1))
                            .audio(AudioTranscodeOptions.builder().codec(Codec.PCM_S16LE).build()).build());
            assertFalse(result.streams().get(1).copied(), "the files' audio codecs differ, so the audio is encoded");
            var output = sink.toByteArray();
            assertFrames(frames(output), 0, 25, 100_000);
            assertEquals(2 * RATE + RATE / 2, samples(output).length, 1);
        }

        @Test
        @DisplayName("speeds a concatenation up, the frames and the samples keeping their order")
        void speed() {
            var first = clip(0, 10, sine(440, RATE));
            var second = clip(10, 10, sine(440, RATE));
            var sink = MediaSink.memory();
            var result = Transcoder.concat(List.of(MediaSource.of(first), MediaSource.of(second)), sink, encode()
                    .speed(2).build());
            assertEquals(1_000_000, result.durationMicros(), 1_000);
            var output = sink.toByteArray();
            assertFrames(frames(output), 0, 20, 50_000);
            assertEquals(RATE, samples(output).length);
        }
    }

    @Nested
    @DisplayName("combining inputs")
    class Combining {
        @Test
        @DisplayName("muxes the video of one file, the audio of another and the subtitles of a third")
        void threeFiles() throws IOException {
            var video = mux(Y4mMuxer::new, raw(0, 0, 20));
            var audio = sine(440, 2 * RATE);
            var sound = mux(WavMuxer::new, pcm(0, Codec.PCM_S16LE, audio));
            var text = "1\n00:00:00,200 --> 00:00:00,800\nHello\n\n2\n00:00:01,000 --> 00:00:01,500\nWorld\n";
            var sink = MediaSink.memory();
            var result = Transcoder.transcode(List.of(TranscodeInput.of(MediaSource.of(video)),
                            TranscodeInput.of(MediaSource.of(sound)),
                            TranscodeInput.of(MediaSource.of(text.getBytes(StandardCharsets.UTF_8)))), sink,
                    TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder().video(VideoTranscodeOptions.encode(Codec.FFV1))
                            .build());
            assertEquals(List.of(0, 1, 2), result.streams().stream().map(TranscodedStream::sourceInput).toList());
            var output = sink.toByteArray();
            assertFrames(frames(output), 0, 20, 100_000);
            assertArrayEquals(samples(sound), samples(output));
            Oracle.assume(Oracle.Tool.FFPROBE);
            var path = save("combined.mkv", output);
            var probe = Oracle.probe(path);
            assertTrue(probe.contains("\"codec_type\": \"video\""), probe);
            assertTrue(probe.contains("\"codec_type\": \"audio\""), probe);
            assertTrue(probe.contains("\"codec_type\": \"subtitle\""), probe);
            Oracle.assertDecodesCleanly(path);
        }

        @Test
        @DisplayName("replaces the audio of an A/V clip with that of another file")
        void replaceAudio() {
            var av = clip(0, 10, sine(440, RATE));
            var other = sine(660, RATE);
            var sound = mux(WavMuxer::new, pcm(0, Codec.PCM_S16LE, other));
            var sink = MediaSink.memory();
            var result = Transcoder.transcode(List.of(TranscodeInput.of(MediaSource.of(av)),
                    TranscodeInput.of(MediaSource.of(sound))), sink, TranscodeOptions.remux(OutputContainer.MATROSKA)
                    .toBuilder().audio(AudioTranscodeOptions.copy().toBuilder().input(1).build()).build());
            assertEquals(List.of(0, 1), result.streams().stream().map(TranscodedStream::sourceInput).toList());
            assertTrue(result.remuxed());
            var output = sink.toByteArray();
            assertFrames(frames(output), 0, 10, 100_000);
            assertArrayEquals(samples(sound), samples(output));
        }
    }

    @Nested
    @DisplayName("renditions")
    class Renditions {
        @Test
        @DisplayName("writes each rendition of an A/V input as its own transcode would, reading the input once")
        void ladder() {
            var input = clip(0, 20, sine(440, 2 * RATE));
            var full = encode().build();
            var half = encode().video(VideoTranscodeOptions.encode(Codec.FFV1).toBuilder().maxSize(16, 8).build()).build();
            var copy = TranscodeOptions.remux(OutputContainer.MATROSKA);
            var options = List.of(full, half, copy);
            var sinks = new ArrayList<com.github.auties00.smalt.io.ByteArrayMediaSink>();
            var outputs = new ArrayList<TranscodeOutput>();
            for (var option : options) {
                var sink = MediaSink.memory();
                sinks.add(sink);
                outputs.add(new TranscodeOutput(sink, option));
            }
            Transcoder.transcode(MediaSource.of(new ByteArrayInputStream(input), input.length), outputs);
            for (var index = 0; index < options.size(); index++) {
                var alone = MediaSink.memory();
                Transcoder.transcode(MediaSource.of(input), alone, options.get(index));
                assertArrayEquals(alone.toByteArray(), sinks.get(index).toByteArray(), "rendition " + index);
            }
            try (var reader = VideoReader.open(MediaSource.of(sinks.get(1).toByteArray()))) {
                assertEquals(16, reader.track().width());
            }
        }
    }

    @Nested
    @DisplayName("silence trimming with video")
    class SilenceWithVideo {
        @Test
        @DisplayName("moves the video's range with the audio's leading and trailing silence")
        void ends() {
            var audio = new float[2 * RATE];
            var tone = sine(440, RATE);
            for (var index = 0; index < RATE; index++) {
                audio[RATE / 2 + index] = (float) (0.5 * Math.cos(2 * Math.PI * 440 * index / RATE));
            }
            var input = clip(0, 20, audio);
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(input), sink, encode().silenceTrim(SilenceTrim.defaults()).build());
            var output = sink.toByteArray();
            assertFrames(frames(output), 5, 10, 100_000);
            assertArrayEquals(Arrays.copyOfRange(samples(input), RATE / 2, RATE / 2 + RATE), samples(output));
            assertEquals(tone.length, RATE);
        }

        @Test
        @DisplayName("refuses to shorten pauses when the output keeps video")
        void pauses() {
            var input = clip(0, 20, sine(440, 2 * RATE));
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(MediaSource.of(input),
                    MediaSink.memory(), encode().silenceTrim(SilenceTrim.shorteningPauses(100_000)).build()));
            var audioOnly = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(input), audioOnly, encode().noVideo()
                    .silenceTrim(SilenceTrim.shorteningPauses(100_000)).build());
            assertEquals(2 * RATE, samples(audioOnly.toByteArray()).length);
        }
    }

    @Test
    @DisplayName("fades both streams of an A/V file at the end of its range")
    void fades() {
        var level = new float[2 * RATE];
        Arrays.fill(level, 0.5f);
        var input = clip(0, 20, level);
        var sink = MediaSink.memory();
        var fades = List.of(Fade.out(500_000));
        Transcoder.transcode(MediaSource.of(input), sink, encode()
                .video(VideoTranscodeOptions.encode(Codec.FFV1).toBuilder().fades(fades).build())
                .audio(AudioTranscodeOptions.encode(Codec.PCM_S16LE).toBuilder().fades(fades).build())
                .range(0, 1_500_000).build());
        var output = sink.toByteArray();
        var audio = samples(output);
        assertEquals(RATE + RATE / 2, audio.length);
        assertEquals(0.5f, audio[RATE / 2], 1e-4f);
        assertEquals(0.25f, audio[RATE + RATE / 4], 2e-3f);
        assertEquals(0f, audio[audio.length - 1], 1e-3f);
        var frames = frames(output);
        assertEquals(15, frames.size());
        assertArrayEquals(picture(5).y(), frames.get(5).luma(), "before the fade");
        var last = frames.get(14).luma();
        var original = picture(14).y();
        for (var position = 0; position < last.length; position++) {
            var expected = 16 + ((original[position] & 0xFF) - 16) * 0.2;
            assertEquals(expected, last[position] & 0xFF, 0.51, "the last frame keeps a fifth of its contrast");
        }
        assertEquals(Containers.detect(MediaSource.of(output)).orElseThrow(), com.github.auties00.smalt.container
                .ContainerFormat.MATROSKA);
    }
}
