package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the output of every Smalt audio and video encoder with an independent reference decoder (ffmpeg with an
 * explicitly chosen decoder, libvpx, dav1d, libopus and opencore-amr through it, and the reference {@code flac} tool)
 * and with Smalt, and requires the two decodes to agree within the tolerance declared for the codec: exactly for the
 * codecs whose reconstruction is normative, within an accuracy bound for the others. The muxed file also passes the
 * container validators, and ffmpeg's default decoders read it without an error. This is the encode/decode mismatch
 * check of libaom generalized to every encoder: an encoder whose output a conformant decoder reads differently from
 * Smalt is caught even when Smalt's own round trip looks right.
 */
@DisplayName("Encoder output decoded by the reference decoders")
class ReferenceDecodeTest {
    /**
     * How the output of an encoder is checked.
     *
     * @param container the container the stream is written to, by its muxer
     * @param muxer makes the muxer
     * @param decoder the ffmpeg decoder that serves as the reference
     * @param tolerance the rule the Smalt decode meets against the reference decode
     * @param inputOptions ffmpeg options placed before the decoder, for example to turn off post-processing
     */
    record Reference(String container, Function<MediaSink, Muxer> muxer, String decoder, Tolerance tolerance,
                     List<String> inputOptions) {
    }

    private static Reference of(OutputContainer container, String decoder, Tolerance tolerance, String... options) {
        return new Reference(container.extension(), sink -> Containers.openMuxer(container, sink), decoder, tolerance,
                List.of(options));
    }

    /** Normative reconstruction: every decoder conforming to the standard produces the same samples. */
    private static final Tolerance NORMATIVE = Tolerance.exact();

    /** The declared reference of each codec Smalt encodes. */
    static final Map<Codec, Reference> REFERENCES = Map.ofEntries(
            entry(Codec.H264, of(OutputContainer.MP4, "h264", NORMATIVE)),
            entry(Codec.VP8, of(OutputContainer.WEBM, "libvpx", NORMATIVE)),
            entry(Codec.VP9, of(OutputContainer.WEBM, "libvpx-vp9", NORMATIVE)),
            entry(Codec.AV1, of(OutputContainer.IVF, "libdav1d", NORMATIVE)),
            // SMPTE RDD 36 leaves the inverse DCT to the decoder within an accuracy bound
            entry(Codec.PRORES, of(OutputContainer.MOV, "prores", Tolerance.offByOne().and(Tolerance.psnr(50)))),
            // SMPTE ST 2019-1 likewise; ffmpeg also reconstructs AC levels a quarter step further from zero
            entry(Codec.DNXHD, new Reference("mov", sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder()
                    .brand(Mp4MuxerOptions.Brand.QUICKTIME).build()), "dnxhd", Tolerance.maxAbs(4)
                    .and(Tolerance.psnr(50)), List.of())),
            // T.81 leaves the inverse DCT's rounding to the decoder; ffmpeg's is not libjpeg's
            entry(Codec.MJPEG, new Reference("mkv", MatroskaMuxer::new, "mjpeg", Tolerance.offByOne()
                    .and(Tolerance.psnr(55)), List.of())),
            // the irreversible 9-7 wavelet is float arithmetic, which ffmpeg's decoder orders differently; sYCC is full
            // range, which ffmpeg's decoder leaves untagged
            entry(Codec.JPEG2000, of(OutputContainer.MOV, "jpeg2000", Tolerance.offByOne().and(Tolerance.psnr(60)),
                    "-color_range", "pc")),
            // the float synthesis of both decoders differs only by rounding
            entry(Codec.AAC, of(OutputContainer.M4A, "aac", Tolerance.snr(110))),
            entry(Codec.MP3, of(OutputContainer.MP3, "mp3float", Tolerance.snr(110)
                    .and(Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL)))),
            entry(Codec.MP2, of(OutputContainer.MP3, "mp2float", Tolerance.snr(110)
                    .and(Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL)))),
            // ffmpeg's Ogg demuxer leaves the end of a stream on one page untrimmed: the common samples compare
            entry(Codec.VORBIS, of(OutputContainer.OGG, "vorbis", Tolerance.snr(110))),
            // the catalog's Opus stream is stereo at 48 kHz; RFC 6716's conformance criterion and the float paths
            entry(Codec.OPUS, of(OutputContainer.OGG, "libopus", Tolerance.opusCompare(2, 48000)
                    .and(Tolerance.snr(70)))),
            // the streams compare with their dither flags cleared, since each decoder draws its own dither, and
            // ffmpeg 7.1 synthesizes the short blocks of every channel after the first wrongly, so the first channel
            // compares; the rest is float rounding
            entry(Codec.AC3, of(OutputContainer.AC3, "ac3", Tolerance.snr(90), "-drc_scale", "0")),
            entry(Codec.EAC3, of(OutputContainer.EAC3, "eac3", Tolerance.snr(90), "-drc_scale", "0")),
            // lossless, and the 3GPP fixed-point decoders are bit-exact by specification
            entry(Codec.FLAC, of(OutputContainer.FLAC, "flac", NORMATIVE)),
            entry(Codec.ALAC, of(OutputContainer.M4A, "alac", NORMATIVE)),
            entry(Codec.AMR_NB, of(OutputContainer.AMR, "libopencore_amrnb", NORMATIVE)),
            entry(Codec.AMR_WB, of(OutputContainer.AMR, "libopencore_amrwb", NORMATIVE)));

    /** The codecs whose comparison covers the first channel only (see their references). */
    private static final EnumSet<Codec> FIRST_CHANNEL = EnumSet.of(Codec.AC3, Codec.EAC3);

    /** Reads little-endian 16-bit words. */
    private static short[] words(byte[] data) {
        var out = new short[data.length / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    /** Returns one channel of interleaved samples. */
    private static float[] channel(float[] interleaved, int channels, int channel) {
        var out = new float[interleaved.length / channels];
        for (var index = 0; index < out.length; index++) {
            out[index] = interleaved[index * channels + channel];
        }
        return out;
    }

    /** The codecs whose encoders produce only samples, without a codec of their own to decode. */
    private static final EnumSet<Codec> PCM = EnumSet.noneOf(Codec.class);

    static {
        for (var codec : Codec.values()) {
            if (codec.isPcm()) {
                PCM.add(codec);
            }
        }
    }

    static Stream<Codec> encoded() {
        return Catalog.smaltCodecs().stream()
                .filter(codec -> codec.kind() == TrackKind.VIDEO || codec.kind() == TrackKind.AUDIO)
                .filter(codec -> !PCM.contains(codec))
                .sorted(Comparator.comparing(Codec::name));
    }

    @Test
    @DisplayName("every Smalt audio and video encoder has a declared reference decoder")
    void declared() {
        var missing = new ArrayList<Codec>();
        for (var codec : Codec.values()) {
            var encodes = VideoEncoders.isSupported(codec) || AudioEncoders.isSupported(codec);
            if (encodes && !codec.isPcm() && !REFERENCES.containsKey(codec)) {
                missing.add(codec);
            }
        }
        assertEquals(List.of(), missing, "declare the reference decoder and tolerance of these encoders");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encoded")
    @DisplayName("decodes to the reference decoder's output within the codec's declared tolerance")
    void matchesReference(Codec codec) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var reference = REFERENCES.get(codec);
        var stream = Catalog.stream(codec);
        var file = write(codec, reference, stream);
        if (codec == Codec.AC3 || codec == Codec.EAC3) {
            try {
                Files.write(file, com.github.auties00.smalt.audio.ac3.internal.DitherClearing.clear(
                        Files.readAllBytes(file)));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        Validators.assertValid(file);
        Oracle.assertFfmpegDecodes(file);
        var what = codec + " in " + reference.container() + " against ffmpeg -c " + reference.decoder();
        if (codec.kind() == TrackKind.VIDEO) {
            var ours = smaltVideo(file);
            var expected = ffmpegVideo(file, reference, ours.pixelFormat());
            assertEquals(expected.length / ours.frameSize(), ours.frames(), what + ": frame count");
            var deep = java.util.regex.Pattern.compile("p(\\d+)le$").matcher(ours.pixelFormat());
            if (deep.find()) {
                // samples of more than 8 bits are little-endian 16-bit words
                var depth = Integer.parseInt(deep.group(1));
                var want = words(expected);
                var got = words(ours.data());
                System.out.println("[reference-decode] " + what + ": " + Tolerance.Difference.of(want, got, depth));
                reference.tolerance().assertFrames(what, want, got, ours.frameSize() / 2, depth);
            } else {
                System.out.println("[reference-decode] " + what + ": " + Tolerance.Difference.of(expected,
                        ours.data()));
                reference.tolerance().assertFrames(what, expected, ours.data(), ours.frameSize());
            }
        } else {
            var ours = smaltAudio(file);
            var expected = ffmpegAudio(file, reference, ours.channels());
            var samples = ours.samples();
            if (codec == Codec.VORBIS && expected.length > samples.length) {
                expected = java.util.Arrays.copyOf(expected, samples.length);
            }
            assertEquals(expected.length, samples.length, what + ": sample count");
            if (FIRST_CHANNEL.contains(codec)) {
                expected = channel(expected, ours.channels(), 0);
                samples = channel(samples, ours.channels(), 0);
                what += ", first channel";
            }
            System.out.println("[reference-decode] " + what + ": " + Tolerance.Difference.ofPcm(expected, samples));
            reference.tolerance().assertPcm(what, expected, samples);
        }
        if (codec == Codec.FLAC && Oracle.find(Oracle.Tool.FLAC).isPresent()) {
            // the reference tool decodes the whole stream and checks the MD5 signature of STREAMINFO
            Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(1), List.of("--test", "--silent",
                    file.toAbsolutePath().toString()));
        }
    }

    private static Path write(Codec codec, Reference reference, Streams.Encoded stream) {
        var directory = Path.of("target", "reference-decode");
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var file = directory.resolve(codec.name().toLowerCase(Locale.ROOT) + "." + reference.container());
        try (var sink = MediaSink.of(file); var muxer = reference.muxer().apply(sink)) {
            muxer.addTrack(stream.track());
            for (Packet packet : stream.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return file;
    }

    /** The frames Smalt decoded, in one raw buffer of the ffmpeg pixel format they correspond to. */
    private record Frames(byte[] data, int frames, int frameSize, String pixelFormat) {
    }

    private static Frames smaltVideo(Path file) {
        var out = new ByteArrayOutputStream();
        var frames = 0;
        var frameSize = 0;
        String format = null;
        try (var source = MediaSource.of(file); var reader = VideoReader.open(source)) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                var raw = raw(frame.picture());
                frameSize = raw.length;
                format = pixelFormat(frame.picture());
                out.writeBytes(raw);
                frames++;
            }
        }
        assertTrue(frames > 0, "Smalt decoded no frame of " + file);
        return new Frames(out.toByteArray(), frames, frameSize, format);
    }

    private static String pixelFormat(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> {
                var name = switch (yuv.chromaFormat()) {
                    case MONOCHROME -> "gray";
                    case YUV420 -> "420p";
                    case YUV422 -> "422p";
                    case YUV444 -> "444p";
                    case YUV440 -> "440p";
                };
                var full = yuv.color().range() == com.github.auties00.smalt.image.ColorRange.FULL;
                yield name.equals("gray") ? name : (yuv.hasAlpha() ? "yuva" : full ? "yuvj" : "yuv") + name;
            }
            case Yuv16Picture yuv -> {
                var name = switch (yuv.chromaFormat()) {
                    case MONOCHROME -> "gray";
                    case YUV420 -> "yuv420p";
                    case YUV422 -> "yuv422p";
                    case YUV444 -> "yuv444p";
                    case YUV440 -> "yuv440p";
                };
                if (yuv.hasAlpha()) {
                    name = name.replace("yuv", "yuva");
                }
                yield name + yuv.bitDepth() + "le";
            }
            default -> throw new AssertionError("no raw layout for " + picture.getClass().getSimpleName());
        };
    }

    private static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case YuvPicture yuv -> {
                plane(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat() != ChromaFormat.MONOCHROME) {
                    plane(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    plane(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
                if (yuv.hasAlpha()) {
                    plane(out, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
            }
            case Yuv16Picture yuv -> {
                plane(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat() != ChromaFormat.MONOCHROME) {
                    plane(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    plane(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
                if (yuv.hasAlpha()) {
                    plane(out, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
            }
            default -> throw new AssertionError("no raw layout for " + picture.getClass().getSimpleName());
        }
        return out.toByteArray();
    }

    private static void plane(ByteArrayOutputStream out, byte[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            out.write(plane, y * stride, width);
        }
    }

    private static void plane(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = plane[y * stride + x];
                out.write(value & 0xFF);
                out.write((value >> 8) & 0xFF);
            }
        }
    }

    private static byte[] ffmpegVideo(Path file, Reference reference, String pixelFormat) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(reference.inputOptions());
        arguments.addAll(List.of("-c:v", reference.decoder(), "-i", file.toAbsolutePath().toString(), "-map", "0:v:0",
                "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }

    /** The interleaved samples Smalt decoded. */
    private record Samples(float[] samples, int channels) {
    }

    private static Samples smaltAudio(Path file) {
        var samples = new ArrayList<float[]>();
        var total = 0;
        int channels;
        try (var source = MediaSource.of(file); var reader = AudioReader.open(source)) {
            channels = reader.layout().channels();
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                var interleaved = new float[buffer.frames() * buffer.channels()];
                for (var channel = 0; channel < buffer.channels(); channel++) {
                    var plane = buffer.plane(channel);
                    for (var index = 0; index < buffer.frames(); index++) {
                        interleaved[index * buffer.channels() + channel] = plane[index];
                    }
                }
                samples.add(interleaved);
                total += interleaved.length;
            }
        }
        var all = new float[total];
        var position = 0;
        for (var chunk : samples) {
            System.arraycopy(chunk, 0, all, position, chunk.length);
            position += chunk.length;
        }
        return new Samples(all, channels);
    }

    private static float[] ffmpegAudio(Path file, Reference reference, int channels) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(reference.inputOptions());
        arguments.addAll(List.of("-c:a", reference.decoder(), "-i", file.toAbsolutePath().toString(), "-map", "0:a:0",
                "-ac", Integer.toString(channels), "-f", "f32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }
}
