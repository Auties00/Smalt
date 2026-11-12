package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.container.dv.DvDemuxer;
import com.github.auties00.smalt.container.dv.DvFormat;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.validate.DvValidator;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.Pulldown;
import com.github.auties00.smalt.transcode.StreamMode;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.TranscodeResult;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.ffv1.Ffv1Encoder;
import com.github.auties00.smalt.video.ffv1.Ffv1EncoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers transcodes into raw DV ({@link OutputContainer#DV}): clips of various sizes, rates, scans and audio layouts
 * are encoded in every DV format, and the output is checked structurally by the test kit's {@link DvValidator}, read
 * back by Smalt (format, raster, rate, display shape, picture placement, field order, audio rate, channels and tone),
 * and probed and decoded by ffmpeg as a black box. The inputs are Matroska files of lossless FFV1 frames and PCM
 * made here, so every expected value follows from how they were made.
 */
@DisplayName("Transcode to raw DV")
class DvTranscodeTest {
    private static final Path DIRECTORY = Scratch.directory("dv-transcode");

    /** The luma of the left half of the test pictures. */
    private static final int LEFT = 200;

    /** The luma of the right half of the test pictures. */
    private static final int RIGHT = 60;

    /**
     * An audio track of a clip: a sine of a frequency per channel, at half of full scale.
     */
    private record Tone(int rate, ChannelLayout layout, double frequency) {
    }

    /**
     * A clip: its file and the duration of its video in microseconds.
     */
    private record Clip(byte[] data, long videoMicros) {
    }

    /**
     * {@return a picture whose left half has luma {@link #LEFT} and right half {@link #RIGHT}, neutral chroma}
     */
    private static YuvPicture halves(int width, int height) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (x < width / 2 ? LEFT : RIGHT);
            }
        }
        return picture;
    }

    /**
     * Makes a Matroska clip of lossless FFV1 4:2:0 video and PCM audio.
     */
    private static Clip clip(int width, int height, Rational rate, int frames, IntFunction<YuvPicture> pictures,
                             Tone... tones) {
        return clip(width, height, rate, null, frames, pictures, tones);
    }

    /**
     * Makes a Matroska clip of lossless FFV1 4:2:0 video whose frames signal a scan, and PCM audio.
     */
    private static Clip clip(int width, int height, Rational rate, FieldOrder scan, int frames,
                             IntFunction<YuvPicture> pictures, Tone... tones) {
        var tracks = new ArrayList<Track>();
        var packets = new ArrayList<Packet>();
        // FFV1 is lossless, so the transcode decodes exactly these pictures
        var settings = Ffv1EncoderOptions.builder().frameRate(rate).threads(1);
        if (scan != null) {
            settings.fieldOrder(scan);
        }
        try (var encoder = new Ffv1Encoder(width, height, settings.build())) {
            for (var index = 0; index < frames; index++) {
                encoder.encode(new VideoFrame(pictures.apply(index), Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION,
                        false), packet -> packets.add(packet.withTrackId(1)));
            }
            encoder.flush(packet -> packets.add(packet.withTrackId(1)));
            tracks.add(VideoTrack.builder().id(1).codec(Codec.FFV1).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElse(null)).width(width).height(height).frameRate(rate)
                    .color(ColorInfo.BT601_LIMITED).build());
        }
        var micros = Math.round(frames * 1e6 * rate.denominator() / rate.numerator());
        for (var t = 0; t < tones.length; t++) {
            var tone = tones[t];
            var samples = (int) (micros * tone.rate() / 1_000_000L);
            var pcm = new PcmEncoder(PcmEncoderOptions.of(Codec.PCM_S16LE, tone.rate(), tone.layout()));
            var planes = new float[tone.layout().channels()][samples];
            for (var channel = 0; channel < planes.length; channel++) {
                for (var n = 0; n < samples; n++) {
                    planes[channel][n] = (float) (0.5 * Math.sin(2 * Math.PI * tone.frequency() * (1 + channel * 0.25)
                            * n / tone.rate()));
                }
            }
            var id = 2 + t;
            pcm.encode(new AudioBuffer(tone.layout(), tone.rate(), planes, samples), packet -> packets.add(
                    packet.withTrackId(id)));
            pcm.flush(packet -> packets.add(packet.withTrackId(id)));
            tracks.add(AudioTrack.builder().id(id).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(tone.rate()))
                    .sampleRate(tone.rate()).layout(tone.layout()).bitsPerSample(16).duration(samples).build());
        }
        record Timed(long micros, int order, Packet packet) {
        }
        var timed = new ArrayList<Timed>();
        for (var packet : packets) {
            var track = tracks.get(packet.trackId() - 1);
            timed.add(new Timed(track.timeBase().toMicros(packet.pts()), timed.size(), packet));
        }
        timed.sort(Comparator.comparingLong(Timed::micros).thenComparingInt(Timed::order));
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            tracks.forEach(muxer::addTrack);
            timed.forEach(entry -> muxer.writePacket(entry.packet()));
            muxer.finish();
        }
        return new Clip(sink.toByteArray(), micros);
    }

    private record Output(byte[] data, Path file, TranscodeResult result) {
    }

    private static Output transcode(String name, byte[] input, TranscodeOptions options) {
        var sink = MediaSink.memory();
        var result = Transcoder.transcode(MediaSource.of(input), sink, options);
        var data = sink.toByteArray();
        var file = DIRECTORY.resolve(name + ".dv");
        try {
            Files.write(file, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new Output(data, file, result);
    }

    private static TranscodeOptions dv(VideoTranscodeOptions video) {
        return TranscodeOptions.builder().container(OutputContainer.DV).video(video).build();
    }

    /**
     * {@return the first decoded picture of a DV stream}
     */
    private static YuvPicture firstPicture(byte[] data) {
        try (var reader = VideoReader.open(MediaSource.of(data))) {
            var frame = reader.read();
            return (YuvPicture) com.github.auties00.smalt.image.ops.ImageOps.toYuv(frame.picture(),
                    ChromaFormat.YUV444, ColorInfo.BT601_LIMITED);
        }
    }

    private static double meanLuma(YuvPicture picture, int x0, int y0, int x1, int y1) {
        var sum = 0L;
        for (var y = y0; y < y1; y++) {
            for (var x = x0; x < x1; x++) {
                sum += picture.y()[y * picture.yStride() + x] & 0xFF;
            }
        }
        return (double) sum / ((long) (x1 - x0) * (y1 - y0));
    }

    /**
     * Checks a DV stream against ffprobe and decodes it fully with ffmpeg.
     */
    private static void assertFfmpegReads(Output output, DvFormat format, boolean wide, int audioStreams) {
        if (Oracle.find(Oracle.Tool.FFPROBE).isEmpty()) {
            return;
        }
        var text = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-show_entries", "stream=codec_type,codec_name,width,height,r_frame_rate,display_aspect_ratio,"
                        + "sample_rate,channels", "-of", "default=nw=1", output.file().toString())));
        var lines = Arrays.asList(text.replace("\r", "").split("\n"));
        var rate = format.frameRate();
        assertTrue(lines.contains("codec_name=dvvideo"), text);
        assertTrue(lines.contains("width=" + format.width()), text);
        assertTrue(lines.contains("height=" + format.height()), text);
        assertTrue(lines.contains("r_frame_rate=" + rate.numerator() + "/" + rate.denominator()), text);
        assertTrue(lines.contains("display_aspect_ratio=" + (wide ? "16:9" : "4:3")), text);
        if (format.interlacing() == com.github.auties00.smalt.track.Interlacing.PROGRESSIVE) {
            // ffmpeg reads the audio of the 720-line formats one frame group at a time; the video is checked
            var frames = Oracle.decodeVideo(output.file(), "yuv422p", 0).length / (2 * format.width() * format.height());
            assertEquals(output.data().length / format.frameSize(), frames, "ffmpeg frames");
            return;
        }
        // ffmpeg lists a stream for every pair the format can carry; the pairs Smalt wrote come first
        assertTrue(lines.stream().filter(line -> line.equals("codec_type=audio")).count() >= audioStreams, text);
        Oracle.assertDecodesCleanly(output.file());
        try (var demuxer = new DvDemuxer(MediaSource.of(output.data()))) {
            var tracks = demuxer.tracks();
            assertEquals(1 + audioStreams, tracks.size());
            var pairs = new java.util.ArrayList<java.io.ByteArrayOutputStream>();
            for (var index = 0; index < audioStreams; index++) {
                pairs.add(new java.io.ByteArrayOutputStream());
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                for (var index = 0; index < audioStreams; index++) {
                    if (tracks.get(1 + index).id() == packet.trackId()) {
                        pairs.get(index).writeBytes(packet.data());
                    }
                }
            }
            for (var index = 0; index < audioStreams; index++) {
                var ours = pairs.get(index).toByteArray();
                var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i",
                        output.file().toString(), "-map", "0:a:" + index, "-f", "s16le", "-"));
                assertTrue(reference.length >= ours.length * 9 / 10, "ffmpeg read " + reference.length + " of "
                        + ours.length + " bytes of pair " + index);
                assertArrayEquals(Arrays.copyOf(ours, reference.length), reference, "ffmpeg pair " + index);
            }
        }
    }

    /**
     * Checks the tone of the first audio pair: 48 kHz stereo at the source frequency and level, as many samples as
     * the frames carry.
     */
    private static void assertTone(byte[] data, int rate, double frequency, int frames, DvFormat format) {
        try (var reader = AudioReader.open(MediaSource.of(data))) {
            assertEquals(rate, reader.sampleRate());
            assertEquals(2, reader.layout().channels());
            var left = new float[0];
            var count = 0;
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                left = Arrays.copyOf(left, count + buffer.frames());
                System.arraycopy(buffer.plane(0), 0, left, count, buffer.frames());
                count += buffer.frames();
            }
            var expected = (double) frames * rate * format.frameRate().denominator() / format.frameRate().numerator();
            // a 720-line stream with an odd frame count carries one audio frame of two video frames in its last frame
            assertEquals(expected, count, 2.0 * rate * format.frameRate().denominator() / format.frameRate().numerator()
                    + 2, "audio samples");
            // the middle of the clip, away from the resampler's edges and the silence padding the last frame
            var from = count / 4;
            var to = count / 2;
            var energy = 0.0;
            var crossings = 0;
            for (var n = from; n < to; n++) {
                energy += left[n] * left[n];
                if (n > from && (left[n - 1] < 0) != (left[n] < 0)) {
                    crossings++;
                }
            }
            var rms = Math.sqrt(energy / (to - from));
            assertEquals(0.5 / Math.sqrt(2), rms, 0.02, "audio level");
            var measured = crossings / 2.0 * rate / (to - from);
            assertEquals(frequency, measured, frequency * 0.02, "audio frequency");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DvFormat.class)
    @DisplayName("encodes a 4:3 clip with 44.1 kHz audio in every format: raster, rate, display and 48 kHz audio")
    void everyFormat(DvFormat format) {
        var sourceRate = format.fiftyHz() ? new Rational(30, 1) : new Rational(25, 1);
        var clip = clip(320, 240, sourceRate, 15, _ -> halves(320, 240),
                new Tone(44100, ChannelLayout.STEREO, 1000));
        var output = transcode("format-" + format, clip.data(), dv(VideoTranscodeOptions.builder().dvFormat(format)
                .build()));
        var summary = Validators.assertValidDv(format.name(), output.data());
        var frames = output.data().length / format.frameSize();
        assertEquals(frames, summary.frames());
        assertEquals(format.frameSize(), summary.frameSize());
        assertEquals(format.fiftyHz(), summary.fiftyHz());
        assertEquals(format.consumer() ? 0 : 1, summary.applicationId());
        assertEquals(format.highDefinition(), summary.wide() || format.highDefinition());
        assertFalse(summary.audio().isEmpty());
        summary.audio().forEach(audio -> assertEquals(48000, audio.sampleRate()));
        // the duration is kept within one frame of the format's rate
        var frameMicros = 1e6 * format.frameRate().denominator() / format.frameRate().numerator();
        assertEquals(clip.videoMicros(), frames * frameMicros, frameMicros, "duration");
        try (var demuxer = new DvDemuxer(MediaSource.of(output.data()))) {
            var video = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(format.width(), video.width());
            assertEquals(format.height(), video.height());
            assertEquals(format.frameRate(), video.frameRate().orElseThrow());
            assertEquals(format.sampleAspectRatio(false), video.sampleAspectRatio().orElseThrow());
            assertEquals(format.interlacing(), video.interlacing().orElseThrow());
        }
        var declared = (VideoTrack) output.result().streams().getFirst().track();
        assertEquals(format.sampleAspectRatio(false), declared.sampleAspectRatio().orElseThrow());
        assertEquals(format.interlacing(), declared.interlacing().orElseThrow());
        var picture = firstPicture(output.data());
        var height = format.height();
        // 4:3 fills a 4:3 display and is pillarboxed between black bars on the 16:9 one of DVCPRO HD
        var covered = format.highDefinition() ? format.width() * 3 / 4 : format.width();
        var left = (format.width() - covered) / 2;
        assertEquals(LEFT, meanLuma(picture, left + covered / 8, height / 4, left + covered * 3 / 8, height * 3 / 4),
                6, "left half");
        assertEquals(RIGHT, meanLuma(picture, left + covered * 5 / 8, height / 4, left + covered * 7 / 8,
                height * 3 / 4), 6, "right half");
        if (left > 0) {
            assertEquals(16, meanLuma(picture, 0, 0, left - 8, height), 3, "left bar");
            assertEquals(16, meanLuma(picture, left + covered + 8, 0, format.width(), height), 3, "right bar");
        }
        assertTone(output.data(), 48000, 1000, frames, format);
        assertFfmpegReads(output, format, format.highDefinition(), 1);
    }

    @ParameterizedTest(name = "{0}x{1} at {2}/{3} -> {4}")
    @CsvSource({
            "1920, 1080, 30000, 1001, DVCPRO_HD_1080I60, true",
            "1440, 600, 25, 1, DV_625, true",
            "1280, 720, 50, 1, DVCPRO_HD_720P50, true",
            "640, 360, 24000, 1001, DV_525, true",
            "352, 288, 25, 1, DV_625, false",
            "200, 400, 30, 1, DV_525, false"})
    @DisplayName("picks the format of the source and fits its shape to the 4:3 or 16:9 display")
    void automatic(int width, int height, long numerator, long denominator, DvFormat expected, boolean wide) {
        var rate = new Rational(numerator, denominator);
        var clip = clip(width, height, rate, 8, _ -> halves(width, height), new Tone(48000, ChannelLayout.STEREO, 750));
        var output = transcode("auto-" + width + "x" + height, clip.data(), TranscodeOptions.of(OutputContainer.DV));
        var summary = Validators.assertValidDv("auto", output.data());
        assertEquals(expected.frameSize(), summary.frameSize());
        assertEquals(expected.fiftyHz(), summary.fiftyHz());
        try (var demuxer = new DvDemuxer(MediaSource.of(output.data()))) {
            var video = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(expected.width(), video.width());
            assertEquals(expected.sampleAspectRatio(wide), video.sampleAspectRatio().orElseThrow());
        }
        // the displayed shape is kept: the picture covers the display's width or height, centered on black
        var display = expected.width() * expected.sampleAspectRatio(wide).toDouble() / expected.height();
        var shape = (double) width / height;
        var picture = firstPicture(output.data());
        if (shape > display + 0.01) {
            var covered = expected.height() * display / shape;
            var bar = (int) ((expected.height() - covered) / 2);
            assertEquals(16, meanLuma(picture, 0, 0, expected.width(), bar - 6), 3, "top bar");
            assertEquals(LEFT, meanLuma(picture, expected.width() / 8, bar + 8, expected.width() * 3 / 8,
                    expected.height() - bar - 8), 6, "content");
        } else if (shape < display - 0.01) {
            var covered = expected.width() * shape / display;
            var bar = (int) ((expected.width() - covered) / 2);
            assertEquals(16, meanLuma(picture, 0, 0, bar - 8, expected.height()), 3, "left bar");
            assertEquals(LEFT, meanLuma(picture, bar + 8, 8, bar + (int) covered / 2 - 8, expected.height() - 8), 6,
                    "content");
        }
        assertTone(output.data(), 48000, 750, output.data().length / expected.frameSize(), expected);
        assertFfmpegReads(output, expected, wide, 1);
    }

    /**
     * {@return a picture whose even rows have luma {@code top} and odd rows {@code bottom}, as the two fields of an
     * interlaced frame}
     */
    private static YuvPicture fields(int width, int height, int top, int bottom) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            Arrays.fill(picture.y(), y * picture.yStride(), y * picture.yStride() + width, (byte) ((y & 1) == 0 ? top
                    : bottom));
        }
        return picture;
    }

    /**
     * {@return the mean luma of the even and of the odd rows of the center of a picture}
     */
    private static double[] fieldMeans(YuvPicture picture) {
        var means = new double[2];
        var counts = new int[2];
        for (var y = picture.height() / 4; y < picture.height() * 3 / 4; y++) {
            for (var x = picture.width() / 4; x < picture.width() * 3 / 4; x++) {
                means[y & 1] += picture.y()[y * picture.yStride() + x] & 0xFF;
                counts[y & 1]++;
            }
        }
        return new double[]{means[0] / counts[0], means[1] / counts[1]};
    }

    @Test
    @DisplayName("scales interlaced frames one field at a time and keeps the earlier field first")
    void interlaced() {
        // top field first 1080i, top field white and bottom field black, into bottom field first 525/60 DV
        var clip = clip(1920, 1080, new Rational(30000, 1001), FieldOrder.TOP_FIRST, 4,
                _ -> fields(1920, 1080, 235, 16));
        var options = TranscodeOptions.builder().container(OutputContainer.DV).noAudio().video(
                VideoTranscodeOptions.builder().dvFormat(DvFormat.DV_525).build()).build();
        var output = transcode("fields-tff-to-bff", clip.data(), options);
        Validators.assertValidDv("fields", output.data());
        var means = fieldMeans(firstPicture(output.data()));
        // the white top field, shown first, moves to the odd rows: the bottom field, which DV 525/60 shows first
        assertTrue(means[1] > 220 && means[0] < 30, Arrays.toString(means));
        // the same frames as progressive ones are scaled as frames, which mixes the rows
        var progressiveClip = clip(1920, 1080, new Rational(30000, 1001), FieldOrder.PROGRESSIVE, 4,
                _ -> fields(1920, 1080, 235, 16));
        var progressive = transcode("fields-progressive", progressiveClip.data(), options);
        var mixed = fieldMeans(firstPicture(progressive.data()));
        assertTrue(Math.abs(mixed[0] - mixed[1]) < 120, Arrays.toString(mixed));
        // bottom field first into top field first 1080/50i: the earlier bottom field moves up to the even rows
        var bff = clip(720, 576, new Rational(25, 1), FieldOrder.BOTTOM_FIRST, 4, _ -> fields(720, 576, 16, 235));
        var up = transcode("fields-bff-to-tff", bff.data(), TranscodeOptions.builder().container(OutputContainer.DV)
                .noAudio().video(VideoTranscodeOptions.builder().dvFormat(DvFormat.DVCPRO_HD_1080I50).build()).build());
        var upMeans = fieldMeans(firstPicture(up.data()));
        assertTrue(upMeans[0] > 220 && upMeans[1] < 30, Arrays.toString(upMeans));
        // matching orders and heights pass the rows unchanged
        var same = transcode("fields-same", bff.data(), TranscodeOptions.builder().container(OutputContainer.DV)
                .noAudio().video(VideoTranscodeOptions.builder().dvFormat(DvFormat.DV_625).build()).build());
        var sameMeans = fieldMeans(firstPicture(same.data()));
        assertTrue(sameMeans[0] < 30 && sameMeans[1] > 220, Arrays.toString(sameMeans));
    }

    @Test
    @DisplayName("telecines 23.976 frames per second into 525/60 DV, five frames for every four")
    void telecine() {
        var clip = clip(720, 480, new Rational(24000, 1001), 24, _ -> halves(720, 480),
                new Tone(48000, ChannelLayout.STEREO, 440));
        var output = transcode("telecine", clip.data(), dv(VideoTranscodeOptions.builder().pulldown(Pulldown.TELECINE)
                .build()));
        var summary = Validators.assertValidDv("telecine", output.data());
        assertEquals(30, summary.frames());
        assertFalse(summary.fiftyHz());
        assertTone(output.data(), 48000, 440, 30, DvFormat.DV_525);
        assertThrows(IllegalArgumentException.class, () -> transcode("telecine-625", clip.data(),
                dv(VideoTranscodeOptions.builder().pulldown(Pulldown.TELECINE).dvFormat(DvFormat.DV_625).build())));
    }

    @Test
    @DisplayName("carries multichannel audio in pairs: 5.1 in DVCPRO HD, four channels at 32 kHz in DV")
    void channels() {
        var surround = clip(1280, 720, new Rational(25, 1), 10, _ -> halves(1280, 720),
                new Tone(48000, ChannelLayout.SURROUND_5_1, 600));
        var hd = transcode("surround-1080", surround.data(), dv(VideoTranscodeOptions.builder()
                .dvFormat(DvFormat.DVCPRO_HD_1080I50).build()));
        var summary = Validators.assertValidDv("surround", hd.data());
        assertEquals(3, summary.audio().stream().mapToInt(DvValidator.Audio::channel).distinct().count());
        assertFfmpegReads(hd, DvFormat.DVCPRO_HD_1080I50, true, 3);
        // the third pair holds the surround channels, at their own frequencies
        try (var demuxer = new DvDemuxer(MediaSource.of(hd.data()))) {
            assertEquals(4, demuxer.tracks().size());
        }
        var quad = transcode("quad-625", surround.data(), TranscodeOptions.builder().container(OutputContainer.DV)
                .video(VideoTranscodeOptions.builder().dvFormat(DvFormat.DV_625).build())
                .audio(AudioTranscodeOptions.builder().maxChannels(4).build()).build());
        var quadSummary = Validators.assertValidDv("quad", quad.data());
        assertEquals(2, quadSummary.audio().size());
        quadSummary.audio().forEach(audio -> {
            assertEquals(32000, audio.sampleRate());
            assertTrue(audio.twelveBit());
        });
        try (var reader = AudioReader.open(MediaSource.of(quad.data()))) {
            assertEquals(32000, reader.sampleRate());
            assertEquals(2, reader.layout().channels());
        }
        assertFfmpegReads(quad, DvFormat.DV_625, true, 2);
        // the default for a consumer format is stereo at 48 kHz
        var stereo = transcode("stereo-625", surround.data(), dv(VideoTranscodeOptions.builder()
                .dvFormat(DvFormat.DV_625).build()));
        var stereoSummary = Validators.assertValidDv("stereo", stereo.data());
        assertEquals(2, stereoSummary.audio().size());
        stereoSummary.audio().forEach(audio -> assertFalse(audio.twelveBit()));
        // two tracks take a pair each, at 32 kHz in 12 bits at 25 Mb/s and at 48 kHz in DVCPRO50
        var two = clip(720, 576, new Rational(25, 1), 10, _ -> halves(720, 576),
                new Tone(44100, ChannelLayout.STEREO, 500), new Tone(48000, ChannelLayout.MONO, 800));
        var allTracks = AudioTranscodeOptions.builder().allTracks().build();
        var consumer = transcode("two-tracks-625", two.data(), TranscodeOptions.builder()
                .container(OutputContainer.DV).audio(allTracks).build());
        try (var demuxer = new DvDemuxer(MediaSource.of(consumer.data()))) {
            assertEquals(3, demuxer.tracks().size());
            assertEquals(32000, ((AudioTrack) demuxer.tracks().get(2)).sampleRate());
        }
        var fifty = transcode("two-tracks-50", two.data(), TranscodeOptions.builder().container(OutputContainer.DV)
                .video(VideoTranscodeOptions.builder().dvFormat(DvFormat.DVCPRO50_625).build()).audio(allTracks)
                .build());
        try (var demuxer = new DvDemuxer(MediaSource.of(fifty.data()))) {
            assertEquals(3, demuxer.tracks().size());
            assertEquals(48000, ((AudioTrack) demuxer.tracks().get(2)).sampleRate());
        }
        assertFfmpegReads(fifty, DvFormat.DVCPRO50_625, false, 2);
    }

    @Test
    @DisplayName("copies a DV stream it can carry and re-encodes it when the options name a format")
    void copy() {
        var clip = clip(720, 576, new Rational(25, 1), 6, _ -> halves(720, 576),
                new Tone(48000, ChannelLayout.STEREO, 1000));
        var first = transcode("copy-source", clip.data(), TranscodeOptions.of(OutputContainer.DV));
        var copied = transcode("copy", first.data(), TranscodeOptions.of(OutputContainer.DV));
        assertTrue(copied.result().streams().getFirst().copied());
        assertEquals(first.data().length, copied.data().length);
        try (var a = new DvDemuxer(MediaSource.of(first.data())); var b = new DvDemuxer(MediaSource.of(copied.data()))) {
            for (var packet = a.read(); packet != null; packet = a.read()) {
                var other = b.read();
                assertArrayEquals(packet.data(), other.data());
            }
        }
        var reencoded = transcode("reencode", first.data(), dv(VideoTranscodeOptions.builder()
                .dvFormat(DvFormat.DVCPRO50_625).build()));
        assertFalse(reencoded.result().streams().getFirst().copied());
        assertEquals(DvFormat.DVCPRO50_625.frameSize(), Validators.assertValidDv("reencode", reencoded.data())
                .frameSize());
        // DVCPRO50 shares the 720 by 576 size of consumer DV but carries two pairs at 48 kHz only: the copy keeps them
        var two = clip(720, 576, new Rational(25, 1), 6, _ -> halves(720, 576),
                new Tone(48000, ChannelLayout.STEREO, 500), new Tone(48000, ChannelLayout.STEREO, 800));
        var allTracks = AudioTranscodeOptions.builder().allTracks().build();
        var fifty = transcode("copy-source-50", two.data(), TranscodeOptions.builder().container(OutputContainer.DV)
                .video(VideoTranscodeOptions.builder().dvFormat(DvFormat.DVCPRO50_625).build()).audio(allTracks)
                .build());
        var fiftyCopy = transcode("copy-50", fifty.data(), TranscodeOptions.builder().container(OutputContainer.DV)
                .audio(allTracks).build());
        assertTrue(fiftyCopy.result().streams().getFirst().copied());
        var fiftySummary = Validators.assertValidDv("copy-50", fiftyCopy.data());
        assertEquals(DvFormat.DVCPRO50_625.frameSize(), fiftySummary.frameSize());
        try (var demuxer = new DvDemuxer(MediaSource.of(fiftyCopy.data()))) {
            assertEquals(3, demuxer.tracks().size());
            for (var track : demuxer.tracks().subList(1, 3)) {
                assertEquals(48000, ((AudioTrack) track).sampleRate());
            }
        }
        // DVCPRO carries 48 kHz audio only, so a copy of it rejects another rate before writing anything
        var dvcpro = transcode("copy-source-dvcpro", clip.data(), dv(VideoTranscodeOptions.builder()
                .dvFormat(DvFormat.DVCPRO_625).build()));
        var rejected = assertThrows(IllegalArgumentException.class, () -> transcode("copy-dvcpro-32k", dvcpro.data(),
                TranscodeOptions.builder().container(OutputContainer.DV).audio(AudioTranscodeOptions.builder()
                        .sampleRate(32000).build()).build()));
        assertTrue(rejected.getMessage().contains(DvFormat.DVCPRO_625 + " carries no audio"), rejected.getMessage());
    }

    @Test
    @DisplayName("rejects audio without video and settings a DV format fixes")
    void rejects() {
        var clip = clip(320, 240, new Rational(25, 1), 3, _ -> halves(320, 240),
                new Tone(48000, ChannelLayout.STEREO, 1000));
        assertThrows(IllegalArgumentException.class, () -> transcode("no-video", clip.data(), TranscodeOptions
                .builder().container(OutputContainer.DV).video(VideoTranscodeOptions.builder().mode(StreamMode.DROP)
                        .build()).build()));
        assertThrows(IllegalArgumentException.class, () -> transcode("size", clip.data(), dv(VideoTranscodeOptions
                .builder().size(640, 480, com.github.auties00.smalt.transcode.ScaleMode.FIT).build())));
        assertThrows(IllegalArgumentException.class, () -> transcode("rate", clip.data(), dv(VideoTranscodeOptions
                .builder().frameRate(new Rational(24, 1)).build())));
        assertThrows(IllegalArgumentException.class, () -> transcode("audio-rate", clip.data(), TranscodeOptions
                .builder().container(OutputContainer.DV).video(VideoTranscodeOptions.builder()
                        .dvFormat(DvFormat.DVCPRO_625).build()).audio(AudioTranscodeOptions.builder().sampleRate(44100)
                        .build()).build()));
    }
}
