package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.HeicReference.Planes;
import com.github.auties00.smalt.image.heic.internal.StillDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HEIF files with AVC-coded items and image sequences (ISO/IEC 23008-12 Annex E), built
 * box by box around x264 and Smalt-encoded payloads, against ffmpeg's decode of the same AVC
 * streams; and the AVC base layer of Nokia's multilayer conformance file.
 */
@DisplayName("HEIF with AVC images")
class HeicAvcTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    /** An AVC stream: its avcC record, its access units, its sync samples, size and pixel format. */
    record Avc(byte[] config, List<byte[]> samples, int[] sync, int width, int height, String pixelFormat) {
        ChromaFormat format() {
            return pixelFormat.startsWith("gray") ? ChromaFormat.MONOCHROME
                    : pixelFormat.startsWith("yuv422") ? ChromaFormat.YUV422
                    : pixelFormat.startsWith("yuv444") ? ChromaFormat.YUV444 : ChromaFormat.YUV420;
        }

        int depth() {
            return pixelFormat.contains("10") ? 10 : 8;
        }

        /** The ffmpeg decode of the first {@code frames} access units, last picture. */
        Planes reference(int frames) {
            return HeicReference.decode("h264", HeicReference.annexBAvc(config, samples.subList(0, frames)), frames,
                    width, height, format(), depth());
        }

        static Avc of(byte[] mp4, String pixelFormat) {
            var data = new ArrayList<byte[]>();
            var sync = new ArrayList<Integer>();
            try (var demuxer = new Mp4Demuxer(MediaSource.of(mp4))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.keyframe()) {
                        sync.add(data.size());
                    }
                    data.add(packet.data());
                }
                return new Avc(track.codecConfig().orElseThrow(), data, sync.stream().mapToInt(Integer::intValue).toArray(),
                        track.width(), track.height(), pixelFormat);
            }
        }
    }

    /** Encodes pictures of ffmpeg's test pattern with x264, without B pictures. */
    static Avc x264(String filter, int width, int height, String pixelFormat, int frames, String params) {
        var path = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=256x256:rate=10"),
                List.of("-frames:v", Integer.toString(frames), "-vf", filter + ",format=" + pixelFormat, "-c:v",
                        "libx264", "-preset", "veryfast", "-x264-params", "log-level=error:bframes=0" + params));
        var avc = Avc.of(Fixtures.read(path), pixelFormat);
        assertEquals(width, avc.width());
        assertEquals(height, avc.height());
        return avc;
    }

    static Avc tile(int x, int y, String pixelFormat) {
        return x264("crop=64:64:" + x + ":" + y, 64, 64, pixelFormat, 1, "");
    }

    static HeifBuilder avci() {
        return new HeifBuilder().brands("avci", "avci", "mif1", "miaf");
    }

    static int still(HeifBuilder builder, Avc avc, int sample, HeifBuilder.Property... extra) {
        var properties = new ArrayList<>(List.of(plain(HeifBuilder.ispe(avc.width(), avc.height())),
                essential(HeifBuilder.box("avcC", avc.config()))));
        properties.addAll(List.of(extra));
        return builder.item("avc1", avc.samples().get(sample), properties.toArray(HeifBuilder.Property[]::new));
    }

    static Planes planes(Picture picture) {
        return Planes.of(picture);
    }

    @Nested
    @DisplayName("still images")
    class Stills {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yuv420p", "yuv422p", "yuv444p", "gray", "yuv420p10le", "yuv422p10le", "yuv444p10le"})
        @DisplayName("an x264 item decodes to ffmpeg's picture at its depth and in its chroma format")
        void x264(String pixelFormat) {
            var avc = HeicAvcTest.x264("crop=96:80:16:8", 96, 80, pixelFormat, 1, "");
            var builder = avci();
            builder.primary(still(builder, avc, 0));
            var data = builder.build();
            assertEquals(ImageFormat.HEIC, ImageFormat.detect(data, data.length).orElseThrow());
            var expected = avc.reference(1);
            var deep = HeicDecoder.decode(MediaSource.of(data), WIDE).picture();
            HeicStructureTest.assertPlanes(expected, planes(deep));
            var narrow = HeicDecoder.decode(MediaSource.of(data)).picture();
            if (deep instanceof Yuv16Picture wide) {
                HeicStructureTest.assertPlanes(planes(ImageOps.toEightBit(wide)), planes(narrow));
            }
            var info = HeicDecoder.probe(MediaSource.of(data));
            assertEquals(96, info.width());
            assertEquals(80, info.height());
            assertFalse(info.hasAlpha());
        }

        @Test
        @DisplayName("a Smalt-encoded item of an odd size, cropped by the frame cropping of its stream")
        void smalt() {
            var width = 70;
            var height = 46;
            var options = H264EncoderOptions.builder().profile(H264Profile.HIGH).rateControl(H264RateControl.CONSTANT_QUALITY)
                    .quality(26).build();
            var packets = new ArrayList<Packet>();
            byte[] config;
            try (var encoder = new H264Encoder(width, height, options)) {
                encoder.encode(new VideoFrame(gradient(width, height, 0), 0, 1, false), packets::add);
                encoder.flush(packets::add);
                config = encoder.codecConfig().orElseThrow();
            }
            var avc = new Avc(config, List.of(packets.getFirst().data()), new int[]{0}, width, height, "yuv420p");
            var builder = avci();
            builder.primary(still(builder, avc, 0));
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build())).picture();
            HeicStructureTest.assertPlanes(avc.reference(1), planes(decoded));
        }

        @Test
        @DisplayName("the color of an nclx property replaces the color of the stream")
        void color() {
            var avc = tile(0, 0, "yuv420p");
            var builder = avci();
            builder.primary(still(builder, avc, 0, plain(HeifBuilder.nclx(9, 16, 9, false))));
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(9, picture.color().primaries().code());
            assertEquals(16, picture.color().transfer().code());
            assertEquals(9, picture.color().matrix().code());
        }

        @Test
        @DisplayName("an item predicted from another decodes after it")
        void predicted() {
            var avc = HeicAvcTest.x264("crop=64:64:32:32", 64, 64, "yuv420p", 2, ":keyint=10:min-keyint=10:scenecut=0");
            var builder = avci();
            var base = still(builder, avc, 0);
            builder.hidden(base);
            var predicted = still(builder, avc, 1, essential(HeifBuilder.fullBox("rref", 0, 0,
                    new byte[]{1, 'p', 'r', 'e', 'd'})));
            builder.reference("pred", predicted, base).primary(predicted);
            var decoded = HeicDecoder.decode(MediaSource.of(builder.build())).picture();
            HeicStructureTest.assertPlanes(avc.reference(2), planes(decoded));
        }

        @Test
        @DisplayName("the AVC base layer of Nokia's multilayer file decodes to ffmpeg's picture of its data")
        void nokia() throws Exception {
            var data = Files.readAllBytes(HeicVectors.nokia("multilayer004.heic"));
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var primary = file.primaryItem();
            assertEquals("avc1", primary.type());
            var expected = HeicReference.item(source, file, primary, 1024, 512, ChromaFormat.YUV420, 8);
            // the decoder shows the layered enhancement the alternatives group prefers; the base item on its own
            var decoded = StillDecoder.decode(source, file, primary, MediaLimits.DEFAULT, false).picture();
            HeicStructureTest.assertPlanes(expected, planes(decoded));
        }
    }

    @Nested
    @DisplayName("derived images and alpha")
    class Derived {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yuv420p", "yuv420p10le"})
        @DisplayName("a grid of AVC tiles decoded in parallel")
        void grid(String pixelFormat) {
            var tiles = List.of(tile(0, 0, pixelFormat), tile(64, 0, pixelFormat), tile(0, 64, pixelFormat),
                    tile(64, 64, pixelFormat));
            var builder = avci();
            var ids = new int[4];
            for (var i = 0; i < 4; i++) {
                ids[i] = still(builder, tiles.get(i), 0);
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 120, 100), plain(HeifBuilder.ispe(120, 100)));
            builder.reference("dimg", grid, ids).primary(grid);
            var data = builder.build();
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var depth = pixelFormat.contains("10") ? 10 : 8;
            var expected = HeicReference.grid(source, file, file.primaryItem(), ChromaFormat.YUV420, depth);
            HeicStructureTest.assertPlanes(expected, planes(HeicDecoder.decode(source, WIDE).picture()));
        }

        @Test
        @DisplayName("an AVC alpha item gives the alpha plane, premultiplied color is divided back")
        void alpha() {
            var color = tile(0, 0, "yuv420p");
            var alpha = HeicAvcTest.x264("crop=64:64:0:0,geq=lum='X*3+Y'", 64, 64, "gray", 1, ":fullrange=on");
            var builder = avci();
            var image = still(builder, color, 0);
            var aux = still(builder, alpha, 0, essential(HeifBuilder.auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha")));
            builder.reference("auxl", aux, image).hidden(aux).primary(image);
            var decoded = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            var expected = color.reference(1).withAlpha(alpha.reference(1).planes()[0]);
            HeicStructureTest.assertPlanes(expected, planes(decoded));
            assertTrue(HeicDecoder.probe(MediaSource.of(builder.build())).hasAlpha());
            builder.reference("prem", image, aux);
            assertInstanceOf(RgbaPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
        }
    }

    @Nested
    @DisplayName("image sequences")
    class Sequences {
        @Test
        @DisplayName("an x264 sequence with B pictures plays every frame ffmpeg decodes, in display order")
        void reordered() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10"),
                    List.of("-frames:v", "12", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "medium",
                            "-x264-params", "log-level=error:bframes=3:b-pyramid=normal:keyint=6:min-keyint=6",
                            "-brand", "msf1"));
            var source = MediaSource.of(Fixtures.read(path));
            var color = ((YuvPicture) HeicDecoder.decode(source).picture()).color();
            var actual = new ArrayList<RgbaPicture>();
            try (var reader = HeicDecoder.openAnimation(source)) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    actual.add((RgbaPicture) frame.picture());
                }
            }
            var expected = HeicReference.decodeFile(path, 64, 48, ChromaFormat.YUV420, 8, true);
            assertEquals(12, actual.size());
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                var want = ImageOps.toRgba(yuv(expected.get(i), color));
                assertArrayEquals(want.pixels(), actual.get(i).pixels(), "frame " + i);
            }
            AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(source), 11);
        }

        @Test
        @DisplayName("a color track with an AVC alpha track, read and sought")
        void alpha() {
            var color = HeicAvcTest.x264("crop=64:48:0:0", 64, 48, "yuv420p", 9, ":keyint=3:min-keyint=3:scenecut=0");
            var alpha = HeicAvcTest.x264("crop=64:48:0:0,geq=lum='X*3+Y'", 64, 48, "gray", 9,
                    ":keyint=3:min-keyint=3:scenecut=0:fullrange=on");
            var builder = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8", "mif1", "avcs");
            var colorTrack = builder.track("avc1", 64, 48, 100, color.samples(), HeifBuilder.box("avcC", color.config()));
            builder.syncSamples(colorTrack, color.sync());
            var alphaTrack = builder.track("avc1", 64, 48, 100, alpha.samples(), HeifBuilder.box("avcC", alpha.config()),
                    HeifSequenceBuilder.auxi("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"));
            builder.reference("auxl", alphaTrack, colorTrack).syncSamples(alphaTrack, alpha.sync());
            var data = builder.build();
            var info = HeicDecoder.probe(MediaSource.of(data));
            assertTrue(info.animated());
            assertTrue(info.hasAlpha());
            assertEquals(9, info.frameCount());
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(data))) {
                for (var i = 0; i < 9; i++) {
                    var frame = assertInstanceOf(RgbaPicture.class, reader.read().picture());
                    var expected = alpha.reference(i + 1).planes()[0];
                    for (var p = 0; p < expected.length; p++) {
                        assertEquals(expected[p], frame.pixels()[(p / 64) * frame.stride() + 4 * (p % 64) + 3] & 0xFF,
                                "alpha of frame " + i + " at " + p);
                    }
                }
            }
            AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(data)), 12);
        }

        @Test
        @DisplayName("a sequence whose only track has another codec is unsupported, not malformed")
        void otherCodec() {
            var builder = new HeifSequenceBuilder("jpgs", 1000, "jpgs", "msf1", "iso8");
            builder.track("mjp1", 16, 16, 100, List.of(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}));
            var data = builder.build();
            assertEquals(ImageFormat.HEIC, ImageFormat.detect(data, data.length).orElseThrow());
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.probe(MediaSource.of(data)));
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.decode(MediaSource.of(data)));
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        @Test
        @DisplayName("an item without its avcC property, or with a corrupt one, is malformed")
        void configuration() {
            var avc = tile(0, 0, "yuv420p");
            var missing = avci();
            missing.primary(missing.item("avc1", avc.samples().getFirst(), plain(HeifBuilder.ispe(64, 64))));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(missing.build())));
            var corrupt = avci();
            corrupt.primary(corrupt.item("avc1", avc.samples().getFirst(), plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.box("avcC", new byte[]{1, 2}))));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(corrupt.build())));
        }

        @Test
        @DisplayName("raise only MediaException for truncated and mutated items, in bounded time")
        void mutations() {
            var avc = tile(0, 0, "yuv420p");
            var builder = avci();
            builder.primary(still(builder, avc, 0));
            var data = builder.build();
            var random = new Random(31);
            var limits = MediaLimits.builder().maxPixels(1 << 20).maxBytes(16L << 20).build();
            var options = ImageDecoderOptions.builder().limits(limits).build();
            assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
                for (var round = 0; round < 200; round++) {
                    var mutated = data.clone();
                    if (round % 4 == 0) {
                        mutated = java.util.Arrays.copyOf(mutated, random.nextInt(mutated.length));
                    } else {
                        for (var k = 0; k < 1 + random.nextInt(8); k++) {
                            mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                        }
                    }
                    try {
                        HeicDecoder.decode(MediaSource.of(mutated), options);
                    } catch (MediaException expected) {
                        // any MediaException is a valid outcome
                    }
                }
            });
        }
    }

    private static YuvPicture gradient(int width, int height, int frame) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (16 + (x * 3 + y * 2 + frame * 5) % 220);
            }
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            for (var x = 0; x < picture.chromaWidth(); x++) {
                picture.u()[y * picture.uvStride() + x] = (byte) (64 + x * 4);
                picture.v()[y * picture.uvStride() + x] = (byte) (192 - y * 4);
            }
        }
        return picture;
    }

    private static Picture yuv(Planes planes, ColorInfo color) {
        var builder = YuvPicture.builder().size(planes.width(), planes.height()).chromaFormat(planes.format()).color(color)
                .luma(pack(planes.planes()[0]), planes.width());
        if (planes.planes()[1] != null) {
            builder.chroma(pack(planes.planes()[1]), pack(planes.planes()[2]), planes.chromaWidth());
        }
        if (planes.planes()[3] != null) {
            builder.alpha(pack(planes.planes()[3]), planes.width());
        }
        return builder.build();
    }

    private static byte[] pack(int[] samples) {
        var out = new byte[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = (byte) samples[i];
        }
        return out;
    }
}
