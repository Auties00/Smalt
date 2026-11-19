package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ICC profile application extension of GIF ({@code ICCRGBG1 012}, ICC.1 Annex B) and the
 * XMP extension on the decode path: hand-built files read by the decoder and by {@link ImageMetadata},
 * profiles written by the still and animation encoders and by the metadata editor, located by an
 * independent block walk, and black-box checks against ImageMagick, exiftool and ffmpeg.
 */
class GifColorProfileTest {
    private static final int[] PALETTE = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};

    @TempDir
    Path directory;

    static byte[] indices(int width, int height) {
        var indices = new byte[width * height];
        for (var i = 0; i < indices.length; i++) {
            indices[i] = (byte) (i * 7 / 3 % PALETTE.length);
        }
        return indices;
    }

    static GifFiles image(GifFiles files) {
        return files.image(0, 0, 6, 5, null, false, indices(6, 5), 2, false);
    }

    /** A byte string that is not a valid profile, long enough to need several sub-blocks. */
    static byte[] filler(int length) {
        var bytes = new byte[length];
        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    static RgbaPicture gradient(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                pixels[at] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[at + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                pixels[at + 2] = (byte) ((x + y) * 9);
                pixels[at + 3] = (byte) (alpha && (x + y) % 5 == 0 ? 0 : 255);
            }
        }
        return picture;
    }

    /** One block of a datastream, found by walking it independently of the code under test. */
    record Block(int introducer, int label, String identifier, int start, byte[] data) {
    }

    /** Walks the blocks after the global color table, concatenating each extension's sub-block data. */
    static List<Block> blocks(byte[] gif) {
        var packed = gif[10] & 0xFF;
        var position = 13 + ((packed & 0x80) != 0 ? 3 * (2 << (packed & 7)) : 0);
        var list = new ArrayList<Block>();
        while (position < gif.length && (gif[position] & 0xFF) != 0x3B) {
            var start = position;
            var introducer = gif[position] & 0xFF;
            if (introducer == 0x21) {
                var label = gif[position + 1] & 0xFF;
                position += 2;
                String identifier = null;
                if (label == 0xFF) {
                    identifier = new String(gif, position + 1, gif[position] & 0xFF, StandardCharsets.ISO_8859_1);
                    position += 1 + (gif[position] & 0xFF);
                }
                var data = new java.io.ByteArrayOutputStream();
                while ((gif[position] & 0xFF) != 0) {
                    data.write(gif, position + 1, gif[position] & 0xFF);
                    position += 1 + (gif[position] & 0xFF);
                }
                position++;
                list.add(new Block(0x21, label, identifier, start, data.toByteArray()));
            } else {
                var local = gif[position + 9] & 0xFF;
                position += 10 + ((local & 0x80) != 0 ? 3 * (2 << (local & 7)) : 0) + 1;
                while ((gif[position] & 0xFF) != 0) {
                    position += 1 + (gif[position] & 0xFF);
                }
                position++;
                list.add(new Block(0x2C, 0x2C, null, start, new byte[0]));
            }
        }
        return list;
    }

    static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static List<Block> iccBlocks(byte[] gif) {
        return blocks(gif).stream().filter(block -> "ICCRGBG1012".equals(block.identifier())).toList();
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("the decoder returns the profile of an extension before the first image and parses it")
        void decoder() {
            var profile = ColorProfile.displayP3().toByteArray();
            assertTrue(profile.length > 255, "the profile spans several sub-blocks");
            var gif = image(GifFiles.create(6, 5, PALETTE).loop("NETSCAPE2.0", 0).icc(profile)).trailer().bytes();
            var decoded = GifDecoder.decode(MediaSource.of(gif));
            assertArrayEquals(profile, decoded.iccProfile().orElseThrow());
            assertEquals(ColorProfile.displayP3(), decoded.colorProfile().orElseThrow());
            var plain = GifDecoder.decode(MediaSource.of(image(GifFiles.create(6, 5, PALETTE)).trailer().bytes()));
            assertArrayEquals(RasterTesting.tight(plain.picture()), RasterTesting.tight(decoded.picture()));
            assertTrue(plain.iccProfile().isEmpty());
            assertTrue(plain.colorProfile().isEmpty());
        }

        @Test
        @DisplayName("reads the Compact-ICC Display P3 profile of the assembled sample, whose pixels are those of its source")
        void sample() throws IOException {
            var samples = Path.of("src", "test", "resources", "samples", "gif");
            var gif = Files.readAllBytes(samples.resolve("icc.gif"));
            var profile = GifDecoder.decode(MediaSource.of(gif)).iccProfile().orElseThrow();
            assertEquals(480, profile.length);
            assertEquals("cb51de38e482ee974c0c76b9689e16aad04bad16e226fed2f30c842d15ff3a3d", sha256(profile));
            assertArrayEquals(profile, ImageMetadata.read(gif).iccProfile().orElseThrow());
            assertEquals("sP3", ImageMetadata.read(gif).colorProfile().orElseThrow().description().orElseThrow());
            var source = Files.readAllBytes(samples.resolve("still.gif"));
            assertArrayEquals(RasterTesting.tight(GifDecoder.decode(MediaSource.of(source)).picture()),
                    RasterTesting.tight(GifDecoder.decode(MediaSource.of(gif)).picture()));
        }

        @Test
        @DisplayName("bytes that are not a profile are returned as stored, without a color profile")
        void invalid() {
            var bytes = filler(1000);
            var gif = image(GifFiles.create(6, 5, PALETTE).icc(bytes)).trailer().bytes();
            var decoded = GifDecoder.decode(MediaSource.of(gif));
            assertArrayEquals(bytes, decoded.iccProfile().orElseThrow());
            assertTrue(decoded.colorProfile().isEmpty());
            assertArrayEquals(bytes, ImageMetadata.read(gif).iccProfile().orElseThrow());
            assertTrue(ImageMetadata.read(gif).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("a profile of another color space than RGB is returned as stored but does not describe the pixels")
        void gray() {
            var profile = ColorProfile.grayGamma22().toByteArray();
            var gif = image(GifFiles.create(6, 5, PALETTE).icc(profile)).trailer().bytes();
            var decoded = GifDecoder.decode(MediaSource.of(gif));
            assertArrayEquals(profile, decoded.iccProfile().orElseThrow());
            assertTrue(decoded.colorProfile().isEmpty());
            var converted = GifDecoder.decode(MediaSource.of(gif), ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertArrayEquals(RasterTesting.tight(decoded.picture()), RasterTesting.tight(converted.picture()));
            assertArrayEquals(profile, ImageMetadata.read(gif).iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("the first extension wins, and the decoder ignores one after the first image")
        void placement() {
            var first = ColorProfile.displayP3().toByteArray();
            var second = ColorProfile.adobeRgb().toByteArray();
            var two = image(GifFiles.create(6, 5, PALETTE).icc(first).icc(second)).trailer().bytes();
            assertArrayEquals(first, GifDecoder.decode(MediaSource.of(two)).iccProfile().orElseThrow());
            assertArrayEquals(first, ImageMetadata.read(two).iccProfile().orElseThrow());
            var late = image(image(GifFiles.create(6, 5, PALETTE)).icc(second)).trailer().bytes();
            assertTrue(GifDecoder.decode(MediaSource.of(late)).iccProfile().isEmpty());
            assertArrayEquals(second, ImageMetadata.read(late).iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("an application extension with another identifier or authentication code is skipped")
        void otherIdentifiers() {
            var profile = ColorProfile.displayP3().toByteArray();
            var gif = image(GifFiles.create(6, 5, PALETTE).application("ICCRGBG1011", profile)
                    .application("ICCRGBG2012", profile)).trailer().bytes();
            assertTrue(GifDecoder.decode(MediaSource.of(gif)).iccProfile().isEmpty());
            assertTrue(ImageMetadata.read(gif).iccProfile().isEmpty());
        }

        @Test
        @DisplayName("a datastream that ends inside the extension keeps no profile")
        void truncated() {
            var profile = ColorProfile.displayP3().toByteArray();
            var gif = GifFiles.create(6, 5, PALETTE).icc(profile).bytes();
            for (var cut = 14; cut < gif.length; cut += 37) {
                var data = Arrays.copyOf(gif, cut);
                assertThrows(MediaException.Malformed.class, () -> GifDecoder.decode(MediaSource.of(data)));
            }
        }

        @Test
        @DisplayName("convertToSrgb converts the pixels through the profile")
        void convert() {
            var profile = ColorProfile.displayP3().toByteArray();
            var muted = new int[]{0x806040, 0x408060, 0x604080, 0xC0A080};
            var gif = GifFiles.create(6, 5, muted).icc(profile).image(0, 0, 6, 5, null, false, indices(6, 5), 2, false)
                    .trailer().bytes();
            var decoded = GifDecoder.decode(MediaSource.of(gif));
            var converted = GifDecoder.decode(MediaSource.of(gif), ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            assertTrue(converted.iccProfile().isEmpty());
            var expected = ImageOps.toSrgb(decoded);
            assertArrayEquals(RasterTesting.tight(expected.picture()), RasterTesting.tight(converted.picture()));
            assertFalse(Arrays.equals(RasterTesting.tight(decoded.picture()), RasterTesting.tight(converted.picture())),
                    "Display P3 colors inside the sRGB gamut change their sRGB values");
            var plain = image(GifFiles.create(6, 5, PALETTE)).trailer().bytes();
            var untouched = GifDecoder.decode(MediaSource.of(plain), ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertArrayEquals(RasterTesting.tight(GifDecoder.decode(MediaSource.of(plain)).picture()),
                    RasterTesting.tight(untouched.picture()));
        }

        @Test
        @DisplayName("the decoder returns the XMP packet without the magic trailer")
        void xmp() {
            var packet = XmpPacket.empty().with(XmpPacket.DC, "title", "Gif title");
            var bytes = packet.toBytes();
            var gif = image(GifFiles.create(6, 5, PALETTE).xmp(bytes)).trailer().bytes();
            var decoded = GifDecoder.decode(MediaSource.of(gif));
            var text = new String(decoded.xmp().orElseThrow(), StandardCharsets.UTF_8);
            assertTrue(text.endsWith("?>"), text);
            assertTrue(new String(bytes, StandardCharsets.UTF_8).startsWith(text), text);
            assertEquals(packet, XmpPacket.parse(decoded.xmp().orElseThrow()));
            var edited = ImageMetadataEditor.apply(image(GifFiles.create(6, 5, PALETTE)).trailer().bytes(),
                    ImageMetadataEdit.keepAll().toBuilder().xmp(packet).build());
            assertEquals(packet, XmpPacket.parse(GifDecoder.decode(MediaSource.of(edited)).xmp().orElseThrow()));
            assertTrue(GifDecoder.decode(MediaSource.of(image(GifFiles.create(6, 5, PALETTE)).trailer().bytes()))
                    .xmp().isEmpty());
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @Test
        @DisplayName("the still encoder writes the profile right after the global color table")
        void still() {
            var profile = ColorProfile.displayP3();
            var picture = gradient(20, 12, false);
            var gif = GifEncoder.encode(picture, GifEncoderOptions.builder().colorProfile(profile).build());
            var blocks = blocks(gif);
            assertEquals("ICCRGBG1012", blocks.getFirst().identifier());
            assertArrayEquals(profile.toByteArray(), blocks.getFirst().data());
            assertEquals(1, iccBlocks(gif).size());
            var decoded = GifDecoder.decode(MediaSource.of(gif));
            assertEquals(profile, decoded.colorProfile().orElseThrow());
            var plain = GifEncoder.encode(picture, GifEncoderOptions.defaults());
            assertTrue(iccBlocks(plain).isEmpty());
            assertArrayEquals(RasterTesting.tight(GifDecoder.decode(MediaSource.of(plain)).picture()),
                    RasterTesting.tight(decoded.picture()));
            assertEquals(plain.length + iccExtensionSize(profile.toByteArray().length), gif.length);
        }

        @Test
        @DisplayName("the animation encoder writes the profile once, after the looping extension")
        void animation() {
            var profile = ColorProfile.adobeRgb();
            var encoder = new GifAnimationEncoder(16, 10, GifEncoderOptions.builder().colorProfile(profile).build());
            encoder.addFrame(gradient(16, 10, false), 5);
            encoder.addFrame(gradient(16, 10, true), 7);
            var gif = encoder.finish();
            var blocks = blocks(gif);
            assertEquals("NETSCAPE2.0", blocks.get(0).identifier());
            assertEquals("ICCRGBG1012", blocks.get(1).identifier());
            assertArrayEquals(profile.toByteArray(), blocks.get(1).data());
            assertEquals(1, iccBlocks(gif).size());
            assertEquals(profile, ImageMetadata.read(gif).colorProfile().orElseThrow());
            try (var reader = GifDecoder.openAnimation(MediaSource.of(gif))) {
                assertEquals(2, reader.frameCount());
            }
        }

        @Test
        @DisplayName("the options carry the profile through builders, equality and its removal")
        void options() {
            var options = GifEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build();
            assertEquals(ColorProfile.displayP3(), options.colorProfile().orElseThrow());
            assertEquals(options, options.toBuilder().build());
            assertNotEquals(options, GifEncoderOptions.defaults());
            assertTrue(options.toBuilder().noColorProfile().build().colorProfile().isEmpty());
            assertEquals(GifEncoderOptions.defaults(), options.toBuilder().noColorProfile().build());
            assertTrue(GifEncoderOptions.defaults().colorProfile().isEmpty());
            assertThrows(NullPointerException.class, () -> GifEncoderOptions.builder().colorProfile(null));
            assertThrows(MediaException.Unsupported.class,
                    () -> GifEncoderOptions.builder().colorProfile(ColorProfile.grayGamma22()).build());
        }
    }

    static long iccExtensionSize(int profileLength) {
        return 3 + 11 + profileLength + (profileLength + 254) / 255 + 1;
    }

    @Nested
    @DisplayName("editing")
    class Editing {
        @Test
        @DisplayName("adds, replaces and removes the profile, keeping the image blocks")
        void edit() {
            var source = Fixtures.read(Fixtures.testImage("gif", 32, 24, List.of()));
            var data = imageBlocks(source);
            var added = ImageMetadataEditor.apply(source, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.displayP3()).build());
            assertEquals(ColorProfile.displayP3(), ImageMetadata.read(added).colorProfile().orElseThrow());
            assertEquals(ColorProfile.displayP3(), GifDecoder.decode(MediaSource.of(added)).colorProfile().orElseThrow());
            assertArrayEquals(data, imageBlocks(added));
            var replaced = ImageMetadataEditor.apply(added, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.rec2020()).build());
            assertEquals(1, iccBlocks(replaced).size());
            assertArrayEquals(ColorProfile.rec2020().toByteArray(), ImageMetadata.read(replaced).iccProfile().orElseThrow());
            assertArrayEquals(data, imageBlocks(replaced));
            var kept = ImageMetadataEditor.apply(replaced, ImageMetadataEdit.keepAll().toBuilder()
                    .comments(List.of("kept")).build());
            assertArrayEquals(ColorProfile.rec2020().toByteArray(), ImageMetadata.read(kept).iccProfile().orElseThrow());
            var removed = ImageMetadataEditor.apply(kept, ImageMetadataEdit.keepAll().toBuilder().removeIccProfile().build());
            assertTrue(iccBlocks(removed).isEmpty());
            assertTrue(ImageMetadata.read(removed).iccProfile().isEmpty());
            assertEquals(List.of("kept"), ImageMetadata.read(removed).comments());
            assertArrayEquals(data, imageBlocks(removed));
            var stripped = ImageMetadata.read(ImageMetadataEditor.stripAll(ImageMetadataEditor.apply(added,
                    ImageMetadataEdit.keepAll().toBuilder().comments(List.of("dropped")).build())));
            assertTrue(stripped.comments().isEmpty());
            assertEquals(ColorProfile.displayP3(), stripped.colorProfile().orElseThrow(), "stripAll keeps the profile");
        }

        @Test
        @DisplayName("places the new profile before the first graphic control extension or image")
        void position() {
            var source = image(GifFiles.create(6, 5, PALETTE).loop("NETSCAPE2.0", 0).control(0, 4, -1)).trailer().bytes();
            var edited = ImageMetadataEditor.apply(source, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.displayP3()).build());
            var blocks = blocks(edited);
            assertEquals("NETSCAPE2.0", blocks.get(0).identifier());
            assertEquals("ICCRGBG1012", blocks.get(1).identifier());
            assertEquals(0xF9, blocks.get(2).label());
            assertArrayEquals(RasterTesting.tight(GifDecoder.decode(MediaSource.of(source)).picture()),
                    RasterTesting.tight(GifDecoder.decode(MediaSource.of(edited)).picture()));
        }

        @Test
        @DisplayName("rejects a profile of another color space than RGB")
        void gray() {
            var source = image(GifFiles.create(6, 5, PALETTE)).trailer().bytes();
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(source,
                    ImageMetadataEdit.keepAll().toBuilder().colorProfile(ColorProfile.grayGamma22()).build()));
        }
    }

    /** Returns the image descriptor blocks and everything between them and the trailer, in order. */
    static byte[] imageBlocks(byte[] gif) {
        var out = new java.io.ByteArrayOutputStream();
        var blocks = blocks(gif);
        for (var i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).introducer() == 0x2C) {
                var end = i + 1 < blocks.size() ? blocks.get(i + 1).start() : gif.length - 1;
                out.write(gif, blocks.get(i).start(), end - blocks.get(i).start());
            }
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("black-box tools")
    class Oracles {
        /** Returns the ImageMagick command: {@code magick}, or {@code convert} where it is not the Windows disk tool. */
        static String imageMagick() {
            if (Oracle.find("magick").isPresent()) {
                return "magick";
            }
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    && Oracle.find("convert").isPresent()) {
                return "convert";
            }
            Oracle.assume("magick");
            return "magick";
        }

        static byte[] displayP3() {
            return Files.isRegularFile(Conformance.directory().resolve("compact-icc-DisplayP3-v4.icc"))
                    ? read(Conformance.fetchListed("compact-icc-DisplayP3-v4.icc"))
                    : ColorProfile.displayP3().toByteArray();
        }

        static byte[] read(Path file) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }

        @Test
        @DisplayName("reads and edits a Display P3 GIF written by ImageMagick, which reads the edits back")
        void imageMagickWritten() throws IOException {
            var magick = imageMagick();
            var profile = displayP3();
            var icc = Files.write(directory.resolve("p3.icc"), profile);
            var gif = directory.resolve("p3.gif");
            Oracle.run(magick, Duration.ofMinutes(1), new byte[0], List.of("-size", "24x16", "gradient:red-blue",
                    "-profile", icc.toString(), gif.toString()));
            var written = Files.readAllBytes(gif);
            assertEquals(1, iccBlocks(written).size(), "ImageMagick writes an ICCRGBG1 extension");
            assertArrayEquals(profile, ImageMetadata.read(written).iccProfile().orElseThrow());
            var decoded = GifDecoder.decode(MediaSource.of(written));
            assertArrayEquals(profile, decoded.iccProfile().orElseThrow());
            assertTrue(decoded.colorProfile().isPresent());
            var edited = directory.resolve("srgb.gif");
            var srgb = ColorProfile.srgb().toByteArray();
            Files.write(edited, ImageMetadataEditor.apply(written, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.srgb()).build()));
            var extracted = directory.resolve("extracted.icc");
            Oracle.run(magick, Duration.ofMinutes(1), new byte[0], List.of(edited.toString(), extracted.toString()));
            assertArrayEquals(srgb, Files.readAllBytes(extracted));
            assertArrayEquals(imageBlocks(written), imageBlocks(Files.readAllBytes(edited)));
        }

        @Test
        @DisplayName("ImageMagick extracts the profile Smalt's encoder embeds")
        void imageMagickReads() throws IOException {
            var magick = imageMagick();
            var profile = ColorProfile.parse(displayP3());
            var gif = Files.write(directory.resolve("smalt.gif"),
                    GifEncoder.encode(gradient(24, 16, true), GifEncoderOptions.builder().colorProfile(profile).build()));
            var extracted = directory.resolve("smalt.icc");
            Oracle.run(magick, Duration.ofMinutes(1), new byte[0], List.of(gif.toString(), extracted.toString()));
            assertArrayEquals(profile.toByteArray(), Files.readAllBytes(extracted));
        }

        @Test
        @DisplayName("exiftool reads the profile of encoded and edited files and sees none after removal")
        void exiftool() throws IOException {
            Oracle.assume(Oracle.Tool.EXIFTOOL);
            var profile = ColorProfile.displayP3();
            var encoded = GifEncoder.encode(gradient(24, 16, false), GifEncoderOptions.builder().colorProfile(profile).build());
            assertArrayEquals(profile.toByteArray(), exiftoolProfile(encoded, "encoded.gif"));
            var source = Fixtures.read(Fixtures.testImage("gif", 32, 24, List.of()));
            var edited = ImageMetadataEditor.apply(source, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.adobeRgb()).build());
            assertArrayEquals(ColorProfile.adobeRgb().toByteArray(), exiftoolProfile(edited, "edited.gif"));
            var removed = ImageMetadataEditor.apply(edited, ImageMetadataEdit.keepAll().toBuilder().removeIccProfile().build());
            assertEquals(0, exiftoolProfile(removed, "removed.gif").length);
        }

        byte[] exiftoolProfile(byte[] gif, String name) throws IOException {
            var file = Files.write(directory.resolve(name), gif);
            return Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1), List.of("-b", "-ICC_Profile", file.toString()));
        }

        @Test
        @DisplayName("ffmpeg decodes a GIF carrying the extension to the pixels Smalt decodes")
        void ffmpeg() throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var picture = gradient(24, 16, false);
            var gif = GifEncoder.encode(picture, GifEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build());
            var file = Files.write(directory.resolve("ffmpeg.gif"), gif);
            var frames = Oracle.decodeVideo(file, "rgba", 1);
            assertArrayEquals(RasterTesting.tight(GifDecoder.decode(MediaSource.of(gif)).picture()), frames);
        }
    }
}
