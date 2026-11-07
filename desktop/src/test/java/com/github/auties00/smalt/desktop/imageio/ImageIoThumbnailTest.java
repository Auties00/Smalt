package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the thumbnails of the Smalt reader: the thumbnail of an EXIF block (JPEG or uncompressed RGB, in either
 * byte order) embedded in a lossless WebP, the thumbnail items of an AVIF file assembled box by box from
 * Smalt-encoded stills, and damaged EXIF blocks, which give no thumbnail and never fail.
 */
@DisplayName("ImageIO thumbnails")
class ImageIoThumbnailTest {
    /**
     * One directory entry of a TIFF structure: a SHORT, LONG or RATIONAL field (numerator and denominator pairs),
     * inline or out of line.
     */
    record Entry(int tag, int type, long[] values) {
    }

    /** Builds a TIFF structure of IFD0 and IFD1, then {@code data}, which the IFD1 entries locate at {@code DATA}. */
    static byte[] exif(boolean bigEndian, List<Entry> ifd0, List<Entry> ifd1, byte[] data) {
        var order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        var buffer = ByteBuffer.allocate(4096 + data.length).order(order);
        buffer.put(bigEndian ? (byte) 'M' : (byte) 'I').put(bigEndian ? (byte) 'M' : (byte) 'I').putShort((short) 42)
                .putInt(8);
        var extra = new ByteArrayOutputStream();
        var ifd0Size = 2 + 12 * ifd0.size() + 4;
        var ifd1Size = 2 + 12 * ifd1.size() + 4;
        var extraStart = 8 + ifd0Size + ifd1Size;
        writeDirectory(buffer, ifd0, 8 + ifd0Size, extra, extraStart, order);
        writeDirectory(buffer, ifd1, 0, extra, extraStart, order);
        buffer.put(extra.toByteArray());
        buffer.put(data);
        var result = new byte[buffer.position()];
        buffer.flip().get(result);
        return result;
    }

    static final int DATA = 1024;

    /** The resolution entries every thumbnail directory carries: 72 by 72 pixels per inch, as RATIONAL fields. */
    static final List<Entry> RESOLUTION = List.of(new Entry(0x011A, 5, new long[]{72, 1}),
            new Entry(0x011B, 5, new long[]{72, 1}), new Entry(0x0128, 3, new long[]{2}));

    /** Returns directory entries with the resolution entries, sorted by tag as TIFF requires. */
    static List<Entry> withResolution(List<Entry> entries) {
        var all = new java.util.ArrayList<>(entries);
        all.addAll(RESOLUTION);
        all.sort(java.util.Comparator.comparingInt(Entry::tag));
        return all;
    }

    private static void writeDirectory(ByteBuffer buffer, List<Entry> entries, int next, ByteArrayOutputStream extra,
                                       int extraStart, ByteOrder order) {
        buffer.putShort((short) entries.size());
        for (var entry : entries) {
            var count = entry.type() == 5 ? entry.values().length / 2 : entry.values().length;
            buffer.putShort((short) entry.tag()).putShort((short) entry.type()).putInt(count);
            var size = entry.type() == 3 ? 2 : 4;
            var packed = ByteBuffer.allocate(Math.max(4, size * entry.values().length)).order(order);
            for (var value : entry.values()) {
                if (size == 2) {
                    packed.putShort((short) value);
                } else {
                    packed.putInt((int) value);
                }
            }
            if (size * entry.values().length <= 4) {
                buffer.put(packed.array(), 0, 4);
            } else {
                buffer.putInt(extraStart + extra.size());
                extra.writeBytes(packed.array());
            }
        }
        buffer.putInt(next);
    }

    /** Pads {@code exif} so that its data starts at {@link #DATA}. */
    static byte[] withDataAt(boolean bigEndian, List<Entry> ifd0, List<Entry> ifd1, byte[] data) {
        var head = exif(bigEndian, ifd0, ifd1, new byte[0]);
        var result = new byte[DATA + data.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(data, 0, result, DATA, data.length);
        return result;
    }

    static BufferedImage pattern(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 15) << 16 | (y * 20) << 8 | 0x40);
            }
        }
        return image;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", out));
        return out.toByteArray();
    }

    static byte[] webpWithExif(byte[] exif) {
        var picture = BufferedImages.toPicture(pattern(64, 48));
        return Images.encode(picture, ImageFormat.WEBP, ImageEncoderOptions.lossless().toBuilder().exif(exif).build());
    }

    static void assertSamePixels(RgbaPicture expected, BufferedImage actual) {
        assertEquals(expected.width(), actual.getWidth());
        assertEquals(expected.height(), actual.getHeight());
        for (var y = 0; y < expected.height(); y++) {
            for (var x = 0; x < expected.width(); x++) {
                var at = y * expected.stride() + x * 4;
                var p = expected.pixels();
                var argb = (p[at + 3] & 0xFF) << 24 | (p[at] & 0xFF) << 16 | (p[at + 1] & 0xFF) << 8 | p[at + 2] & 0xFF;
                assertEquals(argb, actual.getRGB(x, y) | (expected.hasAlpha() ? 0 : 0xFF000000), x + "," + y);
            }
        }
    }

    @ParameterizedTest(name = "big-endian {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the JPEG thumbnail of an EXIF block is the image's thumbnail")
    void jpegThumbnail(boolean bigEndian) throws IOException {
        var thumbnail = jpeg(pattern(16, 12));
        var exif = withDataAt(bigEndian, List.of(new Entry(0x0112, 3, new long[]{1})),
                withResolution(List.of(new Entry(0x0103, 3, new long[]{6}), new Entry(0x0201, 4, new long[]{DATA}),
                        new Entry(0x0202, 4, new long[]{thumbnail.length}))), thumbnail);
        var file = webpWithExif(exif);
        ImageIoBandsTest.with(file, reader -> {
            assertTrue(reader.readerSupportsThumbnails());
            assertTrue(reader.hasThumbnails(0));
            assertEquals(1, reader.getNumThumbnails(0));
            assertEquals(16, reader.getThumbnailWidth(0, 0));
            assertEquals(12, reader.getThumbnailHeight(0, 0));
            var expected = ImageOps.toRgba(Images.decode(MediaSource.of(thumbnail)).picture());
            assertSamePixels(expected, reader.readThumbnail(0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.readThumbnail(0, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.getNumThumbnails(1));
            return null;
        });
    }

    @ParameterizedTest(name = "big-endian {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an uncompressed RGB thumbnail of an EXIF block reads with its samples")
    void rgbThumbnail(boolean bigEndian) throws IOException {
        var width = 5;
        var height = 4;
        var samples = new byte[width * height * 3];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (byte) (i * 7);
        }
        // two strips, as a writer may split the samples
        var split = width * 3 * 2;
        var exif = withDataAt(bigEndian, List.of(new Entry(0x0112, 3, new long[]{1})), withResolution(List.of(
                new Entry(0x0100, 4, new long[]{width}), new Entry(0x0101, 3, new long[]{height}),
                new Entry(0x0102, 3, new long[]{8, 8, 8}), new Entry(0x0103, 3, new long[]{1}),
                new Entry(0x0106, 3, new long[]{2}), new Entry(0x0111, 4, new long[]{DATA, DATA + split}),
                new Entry(0x0115, 3, new long[]{3}), new Entry(0x0117, 4, new long[]{split, samples.length - split}))),
                samples);
        ImageIoBandsTest.with(webpWithExif(exif), reader -> {
            assertEquals(1, reader.getNumThumbnails(0));
            var image = reader.readThumbnail(0, 0);
            assertEquals(width, image.getWidth());
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = (y * width + x) * 3;
                    var expected = (samples[at] & 0xFF) << 16 | (samples[at + 1] & 0xFF) << 8 | samples[at + 2] & 0xFF;
                    assertEquals(expected, image.getRGB(x, y) & 0xFFFFFF);
                }
            }
            return null;
        });
    }

    @Test
    @DisplayName("an image without an EXIF thumbnail and a frame of an animation have no thumbnail")
    void none() throws IOException {
        var exif = exif(false, List.of(new Entry(0x0112, 3, new long[]{1})), List.of(), new byte[0]);
        ImageIoBandsTest.with(webpWithExif(exif), reader -> {
            assertEquals(0, reader.getNumThumbnails(0));
            assertFalse(reader.hasThumbnails(0));
            return null;
        });
        var animation = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("webp").next();
        try (var output = ImageIO.createImageOutputStream(animation)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new javax.imageio.IIOImage(pattern(8, 8), null, null), null);
            var second = pattern(8, 8);
            second.setRGB(3, 3, 0xFFFFFF);
            writer.writeToSequence(new javax.imageio.IIOImage(second, null, null), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        ImageIoBandsTest.with(animation.toByteArray(), reader -> {
            assertEquals(0, reader.getNumThumbnails(1));
            return null;
        });
    }

    @Test
    @DisplayName("damaged EXIF blocks give no thumbnail or a valid one, and never fail")
    void damaged() throws IOException {
        var thumbnail = jpeg(pattern(16, 12));
        var exif = withDataAt(false, List.of(new Entry(0x0112, 3, new long[]{1})),
                List.of(new Entry(0x0201, 4, new long[]{DATA}), new Entry(0x0202, 4, new long[]{thumbnail.length})),
                thumbnail);
        var random = new Random(42);
        for (var round = 0; round < 3000; round++) {
            var mutated = exif.clone();
            var edits = 1 + random.nextInt(8);
            for (var i = 0; i < edits; i++) {
                mutated[random.nextInt(Math.min(mutated.length, 200))] = (byte) random.nextInt(256);
            }
            if (round % 7 == 0) {
                mutated = java.util.Arrays.copyOf(mutated, random.nextInt(mutated.length));
            }
            var found = ExifThumbnails.find(mutated);
            found.ifPresent(value -> assertTrue(value.width() > 0 && value.height() > 0));
        }
        var outOfRange = withDataAt(false, List.of(), List.of(new Entry(0x0201, 4, new long[]{DATA}),
                new Entry(0x0202, 4, new long[]{1 << 20})), thumbnail);
        assertTrue(ExifThumbnails.find(outOfRange).isEmpty());
        var notJpeg = withDataAt(false, List.of(), List.of(new Entry(0x0201, 4, new long[]{DATA}),
                new Entry(0x0202, 4, new long[]{100})), new byte[100]);
        assertTrue(ExifThumbnails.find(notJpeg).isEmpty());
        ImageIoBandsTest.with(webpWithExif(outOfRange), reader -> {
            assertEquals(0, reader.getNumThumbnails(0));
            return null;
        });
    }

    /** Returns the av01 payload and configuration of the primary item of a Smalt-encoded AVIF. */
    static Object[] av01(RgbaPicture picture) {
        var file = Images.encode(picture, ImageFormat.AVIF, ImageEncoderOptions.ofQuality(90));
        var source = MediaSource.of(file);
        var heif = HeifFile.parse(source, MediaLimits.DEFAULT);
        var item = heif.primaryItem();
        return new Object[]{heif.readItem(source, item, MediaLimits.DEFAULT),
                item.property(HeifProperty.Av1Configuration.class).record()};
    }

    @Test
    @DisplayName("the thumbnail items of an AVIF still are its thumbnails, decoded as alone")
    void avifItems() throws IOException {
        var full = av01(BufferedImages.toPicture(pattern(128, 96)));
        var small = av01(BufferedImages.toPicture(pattern(32, 24)));
        var builder = new HeifBuilder().brands("avif", "mif1", "miaf");
        var primary = builder.item("av01", (byte[]) full[0], plain(HeifBuilder.ispe(128, 96)),
                essential(HeifBuilder.av1C((byte[]) full[1])));
        var thumb = builder.item("av01", (byte[]) small[0], plain(HeifBuilder.ispe(32, 24)),
                essential(HeifBuilder.av1C((byte[]) small[1])));
        builder.reference("thmb", thumb, primary).primary(primary);
        var file = builder.build();
        var alone = new HeifBuilder().brands("avif", "mif1", "miaf");
        alone.primary(alone.item("av01", (byte[]) small[0], plain(HeifBuilder.ispe(32, 24)),
                essential(HeifBuilder.av1C((byte[]) small[1]))));
        var expected = ImageOps.toRgba(Images.decode(MediaSource.of(alone.build())).picture());
        ImageIoBandsTest.with(file, reader -> {
            assertEquals(1, reader.getNumThumbnails(0));
            assertEquals(32, reader.getThumbnailWidth(0, 0));
            assertEquals(24, reader.getThumbnailHeight(0, 0));
            assertSamePixels(expected, reader.readThumbnail(0, 0));
            assertEquals(128, reader.read(0).getWidth());
            return null;
        });
    }

}
