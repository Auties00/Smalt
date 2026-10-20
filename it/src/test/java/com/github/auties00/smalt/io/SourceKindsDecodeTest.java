package com.github.auties00.smalt.io;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Decodes a representative file of every container and image format through every kind of
 * {@link MediaSource} and checks that the decoded frames, samples and pixels are identical to the
 * decode of the same bytes in an array. Containers are decoded with {@link VideoReader} and
 * {@link AudioReader}, each over a fresh source, since a stream source is read once; images with
 * {@link Images#decode(MediaSource)}.
 */
@DisplayName("decoding through every source kind")
class SourceKindsDecodeTest {
    private static final String VIDEO = "testsrc2=size=160x120:rate=25:duration=2";

    private static final String AUDIO = "sine=frequency=330:sample_rate=48000:duration=2";

    /** The channels opened by the channel kind, which its sources do not close. */
    private static final List<java.nio.channels.SeekableByteChannel> CHANNELS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The decoded digest of each fixture through a byte array, computed once. */
    private static final Map<String, List<String>> EXPECTED = new ConcurrentHashMap<>();

    record Fixture(String name, boolean image, Path path) {
        @Override
        public String toString() {
            return name;
        }
    }

    record Kind(String name, Function<Path, MediaSource> open) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Fixture video(String name, String extension, String... options) {
        var output = new ArrayList<>(List.of(options));
        output.add("-shortest");
        return new Fixture(name, false, Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", VIDEO, "-f", "lavfi",
                "-i", AUDIO), output));
    }

    static Fixture audio(String name, String extension, String... options) {
        return new Fixture(name, false, Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", AUDIO), List.of(options)));
    }

    static Fixture image(String name, String extension, String... options) {
        return new Fixture(name, true, Fixtures.testImage(extension, 160, 120, List.of(options)));
    }

    static List<Fixture> fixtures() {
        var fixtures = new ArrayList<>(List.of(
                video("mp4 h264 + aac, fast start", "mp4", "-c:v", "libx264", "-g", "12", "-bf", "2", "-c:a", "aac",
                        "-movflags", "+faststart"),
                video("mp4 h264 + aac, moov after mdat", "mp4", "-c:v", "libx264", "-g", "12", "-c:a", "aac"),
                video("fragmented mp4", "mp4", "-c:v", "libx264", "-g", "12", "-c:a", "aac", "-movflags",
                        "frag_keyframe+empty_moov+default_base_moof"),
                video("matroska h264 + opus", "mkv", "-c:v", "libx264", "-g", "12", "-c:a", "libopus"),
                video("webm vp8 + vorbis", "webm", "-c:v", "libvpx", "-deadline", "realtime", "-g", "12", "-c:a",
                        "libvorbis"),
                video("webm vp9 + opus", "webm", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
                        "-g", "12", "-c:a", "libopus"),
                video("mpeg-ts h264 + aac", "ts", "-c:v", "libx264", "-g", "12", "-c:a", "aac"),
                video("avi h264 + mp3", "avi", "-c:v", "libx264", "-g", "12", "-c:a", "libmp3lame"),
                video("ivf vp8", "ivf", "-c:v", "libvpx", "-deadline", "realtime", "-g", "12", "-an"),
                audio("mp3", "mp3", "-c:a", "libmp3lame", "-b:a", "128k"),
                audio("adts aac", "aac", "-c:a", "aac", "-f", "adts"),
                audio("ogg vorbis", "ogg", "-c:a", "libvorbis"),
                audio("ogg opus", "opus", "-c:a", "libopus"),
                audio("flac", "flac", "-c:a", "flac"),
                audio("wav", "wav", "-c:a", "pcm_s16le"),
                audio("aiff", "aiff", "-c:a", "pcm_s16be"),
                audio("ac3", "ac3", "-c:a", "ac3"),
                audio("amr-nb", "amr", "-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"),
                image("jpeg", "jpg", "-q:v", "3"),
                image("png", "png"),
                image("gif", "gif"),
                image("webp lossy", "webp", "-c:v", "libwebp", "-quality", "80"),
                image("webp lossless", "webp", "-c:v", "libwebp", "-lossless", "1"),
                image("bmp", "bmp"),
                image("tiff", "tiff"),
                image("jpeg 2000", "jp2", "-c:v", "libopenjpeg"),
                image("avif", "avif", "-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8")));
        var jxl = Conformance.directory().resolve("jxl-conformance-sunset_logo.jxl");
        if (Files.isRegularFile(jxl)) {
            fixtures.add(new Fixture("jpeg xl", true, jxl));
        }
        return fixtures;
    }

    static List<Kind> kinds() {
        return List.of(
                new Kind("byte[]", path -> MediaSource.of(read(path))),
                new Kind("heap ByteBuffer", path -> {
                    var data = read(path);
                    var padded = new byte[data.length + 11];
                    System.arraycopy(data, 0, padded, 7, data.length);
                    return MediaSource.of(ByteBuffer.wrap(padded).slice(5, data.length + 4).position(2).limit(data.length + 2));
                }),
                new Kind("direct ByteBuffer", path -> {
                    var data = read(path);
                    return MediaSource.of(ByteBuffer.allocateDirect(data.length + 3).position(3).put(data).position(3));
                }),
                new Kind("heap MemorySegment", path -> {
                    var data = read(path);
                    var padded = new byte[data.length + 9];
                    System.arraycopy(data, 0, padded, 9, data.length);
                    return MediaSource.of(MemorySegment.ofArray(padded).asSlice(9));
                }),
                new Kind("native MemorySegment", path -> {
                    var data = read(path);
                    var segment = Arena.ofAuto().allocate(data.length);
                    MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, 0, data.length);
                    return MediaSource.of(segment);
                }),
                new Kind("InputStream", path -> {
                    var data = read(path);
                    return MediaSource.of(new ByteArrayInputStream(data), data.length);
                }),
                new Kind("Supplier", path -> MediaSource.of(() -> {
                    try {
                        return Files.newInputStream(path);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }, size(path))),
                new Kind("InputStream, 32 KiB budget", path -> {
                    var data = read(path);
                    return MediaSource.of(new ByteArrayInputStream(data), data.length,
                            StreamSourceOptions.builder().memoryBudget(32 << 10).build());
                }),
                new Kind("InputStream, unknown length", path -> MediaSource.of(new ByteArrayInputStream(read(path)), -1)),
                new Kind("Path", MediaSource::of),
                new Kind("channel", path -> {
                    try {
                        var channel = Files.newByteChannel(path);
                        CHANNELS.add(channel);
                        return MediaSource.of(channel);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }));
    }

    static Stream<Arguments> cases() {
        var kinds = kinds();
        return fixtures().stream().flatMap(fixture -> kinds.stream().map(kind -> Arguments.of(fixture, kind)));
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<String> decode(Fixture fixture, Function<Path, MediaSource> open) {
        var digest = new ArrayList<String>();
        if (fixture.image()) {
            try (var source = open.apply(fixture.path())) {
                var image = Images.decode(source);
                digest.add(image.format() + " " + image.orientation() + " " + picture(image.picture()));
            }
            return digest;
        }
        boolean hasVideo;
        boolean hasAudio;
        try (var source = open.apply(fixture.path()); var demuxer = Containers.openDemuxer(source)) {
            hasVideo = demuxer.tracks().stream().anyMatch(track -> track.kind() == TrackKind.VIDEO);
            hasAudio = demuxer.tracks().stream().anyMatch(track -> track.kind() == TrackKind.AUDIO);
        }
        if (hasVideo) {
            try (var source = open.apply(fixture.path()); var reader = VideoReader.open(source)) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    digest.add("video " + frame.pts() + " " + frame.keyframe() + " " + picture(frame.picture()));
                }
            }
        }
        if (hasAudio) {
            try (var source = open.apply(fixture.path()); var reader = AudioReader.open(source)) {
                for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                    var crc = new CRC32C();
                    var bytes = ByteBuffer.allocate(buffer.frames() * 4);
                    for (var plane : buffer.planes()) {
                        bytes.clear();
                        for (var index = 0; index < buffer.frames(); index++) {
                            bytes.putFloat(plane[index]);
                        }
                        crc.update(bytes.array(), 0, bytes.position());
                    }
                    digest.add("audio " + buffer.frames() + " " + Long.toHexString(crc.getValue()));
                }
            }
        }
        return digest;
    }

    /** Digests the visible samples of a picture, row by row, so stride padding is ignored. */
    static String picture(Picture picture) {
        var crc = new CRC32C();
        var width = picture.width();
        var height = picture.height();
        switch (picture) {
            case ChannelPicture channels -> throw new AssertionError("unexpected " + channels);
            case RgbaPicture rgba -> rows(crc, rgba.pixels(), rgba.stride(), width * 4, height);
            case YuvPicture yuv -> {
                rows(crc, yuv.y(), yuv.yStride(), width, height);
                var chromaWidth = yuv.chromaFormat().chromaWidth(width);
                var chromaHeight = yuv.chromaFormat().chromaHeight(height);
                if (yuv.u() != null) {
                    rows(crc, yuv.u(), yuv.uvStride(), chromaWidth, chromaHeight);
                    rows(crc, yuv.v(), yuv.uvStride(), chromaWidth, chromaHeight);
                }
                if (yuv.alpha() != null) {
                    rows(crc, yuv.alpha(), yuv.alphaStride(), width, height);
                }
            }
            case Yuv16Picture yuv -> {
                rows(crc, yuv.y(), yuv.yStride(), width, height);
                if (yuv.u() != null) {
                    rows(crc, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    rows(crc, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
            }
            case Rgba16Picture rgba -> rows(crc, rgba.pixels(), rgba.stride(), width * 4, height);
        }
        return width + "x" + height + " " + picture.getClass().getSimpleName() + " " + Long.toHexString(crc.getValue());
    }

    static void rows(CRC32C crc, byte[] plane, int stride, int width, int height) {
        for (var row = 0; row < height; row++) {
            crc.update(plane, row * stride, width);
        }
    }

    static void rows(CRC32C crc, short[] plane, int stride, int width, int height) {
        var bytes = ByteBuffer.allocate(width * 2);
        for (var row = 0; row < height; row++) {
            bytes.clear();
            for (var column = 0; column < width; column++) {
                bytes.putShort(plane[row * stride + column]);
            }
            crc.update(bytes.array(), 0, width * 2);
        }
    }

    @AfterEach
    void closeChannels() throws IOException {
        for (var channel : CHANNELS) {
            channel.close();
        }
        CHANNELS.clear();
    }

    @ParameterizedTest(name = "{0} through {1}")
    @MethodSource("cases")
    @DisplayName("decodes exactly as from a byte array")
    void decodes(Fixture fixture, Kind kind) {
        Assumptions.assumeTrue(Files.isRegularFile(fixture.path()));
        var expected = EXPECTED.computeIfAbsent(fixture.name(), _ -> decode(fixture, path -> MediaSource.of(read(path))));
        assertFalse(expected.isEmpty(), "the reference decode produced nothing");
        assertEquals(expected, decode(fixture, kind.open()));
    }
}
