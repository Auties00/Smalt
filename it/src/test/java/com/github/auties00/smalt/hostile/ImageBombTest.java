package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.auties00.smalt.hostile.Hostile.LIMITS;
import static com.github.auties00.smalt.hostile.Hostile.assertRejected;
import static com.github.auties00.smalt.hostile.Hostile.be16;
import static com.github.auties00.smalt.hostile.Hostile.be32;
import static com.github.auties00.smalt.hostile.Hostile.indexOf;
import static com.github.auties00.smalt.hostile.Hostile.le16;
import static com.github.auties00.smalt.hostile.Hostile.le32;

/**
 * Decompression bombs for every image decoder: a small valid file whose header is rewritten to declare a picture
 * that exceeds {@link Hostile#LIMITS} by side, by area, or by the size of its pixel buffer, which every entry point
 * (decode, probe-then-decode, animation, pages) must reject with {@link MediaException.LimitExceeded} before it
 * allocates for the declared size. Frame-count bombs (an animation with more frames than the limit, an APNG whose
 * {@code acTL} declares two billion frames) must stop at the limit.
 */
@DisplayName("Image decompression bombs")
class ImageBombTest {
    /** Wider than the 4096 side limit, with a small area. */
    private static final int[] SIDE = {5008, 16};

    /** Within the side limit, 9 megapixels against the 4 megapixel limit. */
    private static final int[] AREA = {3008, 3008};

    /** The side of the real files that exceed the buffer limit: within the side and area limits. */
    private static final int LARGE = 1104;

    @FunctionalInterface
    interface Patch {
        byte[] apply(byte[] file, int width, int height);
    }

    record Bomb(String name, Supplier<byte[]> base, Patch patch, boolean decodable) {
        Bomb(String name, Supplier<byte[]> base, Patch patch) {
            this(name, base, patch, true);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> bombs() {
        var bombs = List.of(
                new Bomb("PNG", () -> encode(ImageFormat.PNG, true), ImageBombTest::png),
                new Bomb("JPEG", () -> encode(ImageFormat.JPEG, false), ImageBombTest::jpeg),
                new Bomb("GIF", () -> encode(ImageFormat.GIF, false), ImageBombTest::gif),
                new Bomb("WebP lossless", () -> webp(true), ImageBombTest::webp),
                new Bomb("WebP lossy", () -> webp(false), ImageBombTest::webp),
                new Bomb("BMP", () -> bmp(16, 16, true), (file, width, height) -> bmp(width, height, false)),
                new Bomb("TIFF", () -> encode(ImageFormat.TIFF, false), ImageBombTest::tiff),
                new Bomb("TIFF Zstandard", () -> tiffZstd(16), ImageBombTest::tiff),
                new Bomb("ICO", () -> encode(ImageFormat.ICO, true), ImageBombTest::ico),
                new Bomb("ANI", () -> encode(ImageFormat.ANI, true), ImageBombTest::ani),
                new Bomb("JPEG 2000", () -> encode(ImageFormat.JP2, false), ImageBombTest::jp2),
                new Bomb("JPEG 2000 HTJ2K", ImageBombTest::highThroughput, ImageBombTest::jp2),
                new Bomb("AVIF", () -> avif(16, 16), (file, width, height) -> avif(width, height), false),
                new Bomb("HEIC", () -> heic(16, 16), (file, width, height) -> heic(width, height), false),
                new Bomb("JPEG XL", () -> jxl(16, 16), (file, width, height) -> jxl(width, height), false),
                new Bomb("DNG", () -> dng(16, 16), (file, width, height) -> dng(width, height)));
        var arguments = new ArrayList<Arguments>();
        for (var bomb : bombs) {
            arguments.add(Arguments.of(bomb, "side", SIDE));
            arguments.add(Arguments.of(bomb, "area", AREA));
        }
        return arguments.stream();
    }

    record Large(String name, Supplier<byte[]> file, int side) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Large> large() {
        return Stream.of(
                new Large("PNG", () -> encodeFlat(ImageFormat.PNG, LARGE, ImageEncoderOptions.builder().build()), LARGE),
                new Large("JPEG", () -> encodeFlat(ImageFormat.JPEG, LARGE, ImageEncoderOptions.builder().build()), LARGE),
                new Large("GIF", () -> encodeFlat(ImageFormat.GIF, LARGE, ImageEncoderOptions.builder().build()), LARGE),
                new Large("WebP lossless", () -> encodeFlat(ImageFormat.WEBP, LARGE,
                        ImageEncoderOptions.builder().lossless(true).build()), LARGE),
                new Large("WebP lossy", () -> encodeFlat(ImageFormat.WEBP, LARGE,
                        ImageEncoderOptions.builder().lossless(false).build()), LARGE),
                new Large("BMP", () -> bmp(LARGE, LARGE, true), LARGE),
                new Large("TIFF", () -> encodeFlat(ImageFormat.TIFF, LARGE, ImageEncoderOptions.builder().build()), LARGE),
                new Large("TIFF Zstandard", () -> tiffZstd(LARGE), LARGE),
                new Large("ICO", () -> encodeFlat(ImageFormat.ICO, 256, ImageEncoderOptions.builder().build()), 256),
                new Large("ANI", () -> encodeFlat(ImageFormat.ANI, 256, ImageEncoderOptions.builder().build()), 256),
                new Large("JPEG 2000", () -> encodeFlat(ImageFormat.JP2, LARGE, ImageEncoderOptions.builder().build()),
                        LARGE),
                new Large("AVIF", () -> encodeFlat(ImageFormat.AVIF, LARGE, ImageEncoderOptions.builder().build()), LARGE),
                new Large("JPEG XL", ImageBombTest::cjxl, LARGE),
                new Large("DNG", () -> dng(LARGE, LARGE), LARGE));
    }

    @Nested
    @DisplayName("a real picture whose buffer exceeds the buffer limit")
    class Buffers {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.hostile.ImageBombTest#large")
        @DisplayName("is rejected by decode before allocating its pixel buffer, and decodes once the limit allows it")
        void decode(Large large) {
            var data = large.file().get();
            var pixels = (long) large.side() * large.side();
            var tight = LIMITS.toBuilder().maxBytes(pixels / 2).build();
            assertRejected(large + " buffer", MediaException.LimitExceeded.class,
                    () -> Images.decode(MediaSource.of(data), ImageDecoderOptions.builder().limits(tight).build()));
            var roomy = LIMITS.toBuilder().maxBytes(64L << 20).build();
            var decoded = Images.decode(MediaSource.of(data), ImageDecoderOptions.builder().limits(roomy).build());
            if (decoded.picture().width() != large.side() || decoded.picture().height() != large.side()) {
                throw new AssertionError(large + " decoded " + decoded.picture().width() + "x"
                        + decoded.picture().height());
            }
        }
    }

    @Nested
    @DisplayName("declaring a picture over the limits")
    class Dimensions {
        @ParameterizedTest(name = "{0} over the {1} limit")
        @MethodSource("com.github.auties00.smalt.hostile.ImageBombTest#bombs")
        @DisplayName("is rejected by decode before allocating the declared picture")
        void decode(Bomb bomb, String limit, int[] size) {
            var data = patched(bomb, size);
            var options = ImageDecoderOptions.builder().limits(LIMITS).build();
            assertRejected(bomb + " " + limit, MediaException.LimitExceeded.class,
                    () -> Images.decode(MediaSource.of(data), options));
        }

        @ParameterizedTest(name = "{0} over the {1} limit")
        @MethodSource("com.github.auties00.smalt.hostile.ImageBombTest#bombs")
        @DisplayName("is rejected by the page reader before allocating the declared picture")
        void pages(Bomb bomb, String limit, int[] size) {
            var data = patched(bomb, size);
            var options = ImageDecoderOptions.builder().limits(LIMITS).build();
            assertRejected(bomb + " " + limit, MediaException.LimitExceeded.class, () -> {
                try (var pages = Images.openPages(MediaSource.of(data), options)) {
                    for (var index = 0; index < pages.pageCount(); index++) {
                        pages.read(index);
                    }
                }
            });
        }

        @ParameterizedTest(name = "{0} over the {1} limit")
        @MethodSource("com.github.auties00.smalt.hostile.ImageBombTest#bombs")
        @DisplayName("is rejected by a reduced-size decode, which still has to decode the declared picture")
        void reduced(Bomb bomb, String limit, int[] size) {
            var data = patched(bomb, size);
            var options = ImageDecoderOptions.builder().limits(LIMITS).minSize(64, 64).highBitDepth(true).build();
            assertRejected(bomb + " " + limit, MediaException.LimitExceeded.class,
                    () -> Images.decode(MediaSource.of(data), options));
        }

        private static byte[] patched(Bomb bomb, int[] size) {
            var base = bomb.base().get();
            if (bomb.decodable()) {
                var accepted = Images.decode(MediaSource.of(base), ImageDecoderOptions.builder().limits(LIMITS).build());
                if (accepted.picture().width() != 16) {
                    throw new AssertionError(bomb + " base file did not decode");
                }
            }
            return bomb.patch().apply(base, size[0], size[1]);
        }
    }

    @Nested
    @DisplayName("an animation")
    class Frames {
        static Stream<ImageFormat> animated() {
            return Stream.of(ImageFormat.GIF, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.AVIF, ImageFormat.ANI);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("animated")
        @DisplayName("with more frames than the limit stops with LimitExceeded at the limit")
        void overLimit(ImageFormat format) {
            var data = animation(format, LIMITS.maxFrames() + 8);
            var options = ImageDecoderOptions.builder().limits(LIMITS).build();
            var read = new int[1];
            assertRejected(format + " animation", MediaException.LimitExceeded.class, () -> {
                try (var animation = Images.openAnimation(MediaSource.of(data), options)) {
                    while (animation.read() != null) {
                        read[0]++;
                    }
                }
            });
            if (read[0] > LIMITS.maxFrames()) {
                throw new AssertionError(format + " returned " + read[0] + " frames past the limit");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("animated")
        @DisplayName("with exactly as many frames as the limit is read whole")
        void atLimit(ImageFormat format) {
            var data = animation(format, LIMITS.maxFrames());
            var options = ImageDecoderOptions.builder().limits(LIMITS).build();
            var read = 0;
            try (var animation = Images.openAnimation(MediaSource.of(data), options)) {
                while (animation.read() != null) {
                    read++;
                }
            }
            if (read != LIMITS.maxFrames()) {
                throw new AssertionError(format + " returned " + read + " of " + LIMITS.maxFrames() + " frames");
            }
        }

        @org.junit.jupiter.api.Test
        @DisplayName("an APNG whose acTL declares two billion frames is rejected without allocating for them")
        void declaredFrames() {
            var data = animation(ImageFormat.PNG, 2);
            var acTl = indexOf(data, "acTL", 0);
            be32(data, acTl + 4, 0x7FFF_FFFFL);
            be32(data, acTl + 12, Hostile.crc32(data, acTl, 12));
            var options = ImageDecoderOptions.builder().limits(LIMITS).build();
            assertRejected("APNG acTL", MediaException.class, () -> {
                try (var animation = Images.openAnimation(MediaSource.of(data), options)) {
                    animation.frameCount();
                    while (animation.read() != null) {
                        // drains
                    }
                }
            });
        }
    }

    static byte[] encode(ImageFormat format, boolean alpha) {
        var picture = RgbaPicture.allocate(16, 16, alpha);
        var pixels = picture.pixels();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = (byte) (index * 37 + (index >> 6) * 11);
        }
        if (!alpha) {
            for (var index = 3; index < pixels.length; index += 4) {
                pixels[index] = (byte) 0xFF;
            }
        }
        return Images.encode(picture, format);
    }

    static byte[] encodeFlat(ImageFormat format, int side, ImageEncoderOptions options) {
        var picture = RgbaPicture.allocate(side, side, false);
        var pixels = picture.pixels();
        for (var row = 0; row < side; row++) {
            for (var column = 0; column < side; column++) {
                var at = (row * side + column) * 4;
                pixels[at] = (byte) (row >> 4);
                pixels[at + 1] = (byte) (column >> 4);
                pixels[at + 2] = (byte) 0x80;
                pixels[at + 3] = (byte) 0xFF;
            }
        }
        return Images.encode(picture, format, options);
    }

    static byte[] tiffZstd(int side) {
        var picture = RgbaPicture.allocate(side, side, false);
        var pixels = picture.pixels();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = (index & 3) == 3 ? (byte) 0xFF : (byte) (index / 4 / side * 7 + index % (4 * side) / 12);
        }
        return TiffEncoder.encode(picture, TiffEncoderOptions.zstd());
    }

    static byte[] cjxl() {
        var picture = Images.decode(MediaSource.of(encodeFlat(ImageFormat.PNG, LARGE,
                ImageEncoderOptions.builder().build()))).picture();
        return com.github.auties00.smalt.property.OracleEncoders.jxl(picture, "large");
    }

    static byte[] webp(boolean lossless) {
        var picture = RgbaPicture.allocate(16, 16, false);
        var pixels = picture.pixels();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = (byte) (index % 4 == 3 ? 0xFF : index * 13);
        }
        return Images.encode(picture, ImageFormat.WEBP, ImageEncoderOptions.builder().lossless(lossless).build());
    }

    static byte[] animation(ImageFormat format, int frames) {
        var sink = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(format, 8, 8, sink)) {
            for (var frame = 0; frame < frames; frame++) {
                var picture = RgbaPicture.allocate(8, 8, false);
                var pixels = picture.pixels();
                for (var index = 0; index < pixels.length; index++) {
                    pixels[index] = (byte) (index % 4 == 3 ? 0xFF : frame * 29 + index);
                }
                writer.write(picture, 40_000);
            }
            writer.finish();
        }
        return sink.toByteArray();
    }

    static byte[] png(byte[] file, int width, int height) {
        var data = file.clone();
        pngIhdr(data, 8, width, height);
        return data;
    }

    private static void pngIhdr(byte[] data, int chunk, int width, int height) {
        be32(data, chunk + 8, width);
        be32(data, chunk + 12, height);
        be32(data, chunk + 21, Hostile.crc32(data, chunk + 4, 17));
    }

    static byte[] jpeg(byte[] file, int width, int height) {
        var data = file.clone();
        var offset = 2;
        while (offset + 4 <= data.length) {
            var marker = data[offset + 1] & 0xFF;
            var length = be16(data, offset + 2);
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                be16(data, offset + 5, height);
                be16(data, offset + 7, width);
                return data;
            }
            offset += 2 + length;
        }
        throw new AssertionError("no SOF marker");
    }

    static byte[] gif(byte[] file, int width, int height) {
        var data = file.clone();
        le16(data, 6, width);
        le16(data, 8, height);
        var offset = 13;
        if ((data[10] & 0x80) != 0) {
            offset += 3 << ((data[10] & 7) + 1);
        }
        while (offset < data.length) {
            if ((data[offset] & 0xFF) == 0x21) {
                offset += 2;
                while ((data[offset] & 0xFF) != 0) {
                    offset += (data[offset] & 0xFF) + 1;
                }
                offset++;
            } else if ((data[offset] & 0xFF) == 0x2C) {
                le16(data, offset + 5, width);
                le16(data, offset + 7, height);
                return data;
            } else {
                break;
            }
        }
        throw new AssertionError("no image descriptor");
    }

    static byte[] webp(byte[] file, int width, int height) {
        var data = file.clone();
        var offset = 12;
        while (offset + 8 <= data.length) {
            var type = new String(data, offset, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            var size = (int) le32(data, offset + 4);
            var body = offset + 8;
            switch (type) {
                case "VP8X" -> {
                    le24(data, body + 4, width - 1);
                    le24(data, body + 7, height - 1);
                }
                case "VP8L" -> {
                    var bits = le32(data, body + 1);
                    bits = bits & ~0x0FFF_FFFFL | (width - 1) & 0x3FFF | (long) ((height - 1) & 0x3FFF) << 14;
                    le32(data, body + 1, bits);
                }
                case "VP8 " -> {
                    le16(data, body + 6, width & 0x3FFF | le16(data, body + 6) & 0xC000);
                    le16(data, body + 8, height & 0x3FFF | le16(data, body + 8) & 0xC000);
                }
                default -> {
                }
            }
            offset = body + size + (size & 1);
        }
        return data;
    }

    private static void le24(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
    }

    static byte[] bmp(int width, int height, boolean full) {
        var rowBytes = (width * 3 + 3) & ~3;
        var pixelBytes = full ? (long) rowBytes * height : 16L * rowBytes;
        var data = new byte[54 + (int) pixelBytes];
        data[0] = 'B';
        data[1] = 'M';
        le32(data, 2, data.length);
        le32(data, 10, 54);
        le32(data, 14, 40);
        le32(data, 18, width);
        le32(data, 22, height);
        le16(data, 26, 1);
        le16(data, 28, 24);
        le32(data, 34, (long) rowBytes * height);
        return data;
    }

    static byte[] tiff(byte[] file, int width, int height) {
        var data = file.clone();
        var little = data[0] == 'I';
        var ifd = (int) (little ? le32(data, 4) : be32(data, 4));
        var count = little ? le16(data, ifd) : be16(data, ifd);
        for (var index = 0; index < count; index++) {
            var entry = ifd + 2 + 12 * index;
            var tag = little ? le16(data, entry) : be16(data, entry);
            var type = little ? le16(data, entry + 2) : be16(data, entry + 2);
            if (tag == 256 || tag == 257) {
                var value = tag == 256 ? width : height;
                if (type == 3) {
                    if (little) {
                        le16(data, entry + 8, value);
                    } else {
                        be16(data, entry + 8, value);
                    }
                } else if (little) {
                    le32(data, entry + 8, value);
                } else {
                    be32(data, entry + 8, value);
                }
            }
        }
        return data;
    }

    static byte[] ico(byte[] file, int width, int height) {
        var data = file.clone();
        var png = indexOf(data, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, 6);
        if (png >= 0) {
            pngIhdr(data, png + 8, width, height);
            return data;
        }
        var dib = (int) le32(data, 18);
        le32(data, dib + 4, width);
        le32(data, dib + 8, 2L * height);
        return data;
    }

    /** Patches the first frame of an animated cursor, an icon file inside the {@code fram} list. */
    static byte[] ani(byte[] file, int width, int height) {
        var start = indexOf(file, "icon", 12) + 8;
        var size = (int) le32(file, start - 4);
        var icon = ico(java.util.Arrays.copyOfRange(file, start, start + size), width, height);
        var data = file.clone();
        System.arraycopy(icon, 0, data, start, size);
        return data;
    }

    /** Returns a 16x16 JPH file, coded with the high-throughput block coder. */
    static byte[] highThroughput() {
        var picture = RgbaPicture.allocate(16, 16, false);
        var pixels = picture.pixels();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = (byte) (index % 4 == 3 ? 0xFF : index * 37 + (index >> 6) * 11);
        }
        return Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).build());
    }

    static byte[] jp2(byte[] file, int width, int height) {
        var data = file.clone();
        var ihdr = indexOf(data, "ihdr", 0);
        if (ihdr >= 0) {
            be32(data, ihdr + 4, height);
            be32(data, ihdr + 8, width);
        }
        var siz = indexOf(data, new byte[]{(byte) 0xFF, 0x51}, 0);
        be32(data, siz + 6, width);
        be32(data, siz + 10, height);
        be32(data, siz + 14, 0);
        be32(data, siz + 18, 0);
        be32(data, siz + 22, width);
        be32(data, siz + 26, height);
        return data;
    }

    static byte[] heic(int width, int height) {
        var stream = ForgedHeaders.hevc(width, height);
        var builder = new HeifBuilder().brands("heic", "mif1", "heic");
        var item = builder.item("hvc1", stream.packet(),
                HeifBuilder.essential(HeifBuilder.box("hvcC", stream.config())),
                HeifBuilder.plain(HeifBuilder.ispe(width, height)));
        return builder.primary(item).build();
    }

    static byte[] avif(int width, int height) {
        var temporalUnit = ForgedHeaders.av1TemporalUnit(width, height);
        var data = java.util.Arrays.copyOfRange(temporalUnit, 2, temporalUnit.length);
        var builder = new HeifBuilder().brands("avif", "mif1", "avif");
        var item = builder.item("av01", data,
                HeifBuilder.essential(HeifBuilder.av1C(ForgedHeaders.av1Config(width, height))),
                HeifBuilder.plain(HeifBuilder.ispe(width, height)));
        return builder.primary(item).build();
    }

    static byte[] jxl(int width, int height) {
        var bits = new Hostile.LsbBits();
        bits.u(8, 0xFF).u(8, 0x0A);
        bits.u(1, 0);
        jxlU32(bits, height - 1);
        bits.u(3, 0);
        jxlU32(bits, width - 1);
        bits.u(1, 1);
        bits.pad();
        bits.u(1, 1);
        var groups = (long) Math.ceilDiv(width, 256) * Math.ceilDiv(height, 256);
        var dcGroups = (long) Math.ceilDiv(width, 2048) * Math.ceilDiv(height, 2048);
        var entries = groups == 1 ? 1 : 2 + dcGroups + groups;
        bits.u(1, 0);
        bits.pad();
        for (var entry = 0; entry < entries; entry++) {
            bits.u(2, 0).u(10, 0);
        }
        bits.pad();
        return bits.bytes();
    }

    private static void jxlU32(Hostile.LsbBits bits, int value) {
        if (value < 1 << 9) {
            bits.u(2, 0).u(9, value);
        } else if (value < 1 << 13) {
            bits.u(2, 1).u(13, value);
        } else if (value < 1 << 18) {
            bits.u(2, 2).u(18, value);
        } else {
            bits.u(2, 3).u(30, value);
        }
    }

    static byte[] dng(int width, int height) {
        var out = new ByteArrayOutputStream();
        var entries = new long[][]{
                {254, 4, 1, 0},
                {256, 4, 1, width},
                {257, 4, 1, height},
                {258, 3, 1, 16},
                {259, 3, 1, 1},
                {262, 3, 1, 32803},
                {273, 4, 1, 0},
                {277, 3, 1, 1},
                {278, 4, 1, height},
                {279, 4, 1, 2L * width * height},
                {33421, 3, 2, 2 | 2L << 16},
                {33422, 1, 4, 0x02010100L},
                {50706, 1, 4, 0x00000401L},
                {50708, 2, 4, 0x00474E44L}};
        var ifd = new byte[2 + entries.length * 12 + 4];
        le16(ifd, 0, entries.length);
        var dataOffset = 8 + ifd.length;
        for (var index = 0; index < entries.length; index++) {
            var entry = entries[index];
            var at = 2 + index * 12;
            le16(ifd, at, (int) entry[0]);
            le16(ifd, at + 2, (int) entry[1]);
            le32(ifd, at + 4, entry[2]);
            var value = entry[0] == 273 ? dataOffset : entry[3];
            if (entry[1] == 3 && entry[2] == 1) {
                le16(ifd, at + 8, (int) value);
            } else {
                le32(ifd, at + 8, value);
            }
        }
        var header = new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0};
        out.writeBytes(header);
        out.writeBytes(ifd);
        var strip = new byte[2 * width * height];
        for (var index = 0; index < strip.length; index += 2) {
            strip[index] = (byte) index;
            strip[index + 1] = (byte) (index >> 8 & 0x0F);
        }
        out.writeBytes(strip);
        return out.toByteArray();
    }
}
