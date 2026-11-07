package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the image metadata of the Smalt writers: the EXIF block, ICC profile, XMP packet and orientation of a
 * file read through ImageIO are written again through each writer, as are those of the JDK JPEG and PNG
 * plug-ins' metadata and of writer metadata filled through its native and standard trees.
 */
@DisplayName("ImageIO metadata writing")
class ImageIoMetadataWriteTest {
    /** A TIFF structure with one IFD0 entry: Orientation. */
    static byte[] exif(int orientation) {
        return new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0, 0, 0, 0};
    }

    static final byte[] XMP = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf="
            + "\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " dc:format=\"image/webp\"/></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);

    static BufferedImage pattern(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 7) << 16 | (y * 9) << 8 | (x + y));
            }
        }
        return image;
    }

    static byte[] source() {
        var options = ImageEncoderOptions.lossless().toBuilder().colorProfile(ColorProfile.displayP3()).exif(exif(6))
                .xmp(XMP).build();
        return Images.encode(BufferedImages.toPicture(pattern(40, 30)), ImageFormat.WEBP, options);
    }

    static IIOImage readImage(byte[] file) throws IOException {
        return ImageIoBandsTest.with(file, reader -> new IIOImage(reader.read(0), null, reader.getImageMetadata(0)));
    }

    static byte[] write(String format, IIOImage image) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName(format).next();
        var out = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            writer.write(null, image, null);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    static DecodedImage decode(byte[] file) {
        return Images.decode(MediaSource.of(file));
    }

    static byte[] profileBytes(byte[] icc) {
        return ColorProfile.parse(icc).toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"webp, true", "avif, true", "jxl, true", "smalt-tiff, false", "jpeg2000, false", "apng, false"})
    @DisplayName("a file read through ImageIO is written again with its ICC profile, EXIF block and XMP packet")
    void readAndWrite(String format, boolean exif) throws IOException {
        var source = source();
        var expected = decode(source);
        var written = decode(write(format, readImage(source)));
        assertTrue(written.iccProfile().isPresent(), "ICC profile");
        assertArrayEquals(profileBytes(expected.iccProfile().orElseThrow()), profileBytes(written.iccProfile().get()));
        if (exif) {
            assertArrayEquals(expected.exif().orElseThrow(), written.exif().orElseThrow(), "EXIF");
            assertEquals(Orientation.ofExif(6), written.orientation());
        }
        assertTrue(written.xmp().isPresent(), "XMP");
        assertTrue(new String(written.xmp().get(), StandardCharsets.UTF_8).contains("dc:format"), "XMP content");
    }

    @Test
    @DisplayName("the ICC profile and EXIF block of the JDK JPEG plug-in's metadata are written")
    void jdkJpeg() throws IOException {
        var image = pattern(24, 16);
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
        var name = metadata.getNativeMetadataFormatName();
        var tree = (IIOMetadataNode) metadata.getAsTree(name);
        var sequence = (IIOMetadataNode) tree.getElementsByTagName("markerSequence").item(0);
        var app1 = new IIOMetadataNode("unknown");
        app1.setAttribute("MarkerTag", "225");
        var block = exif(3);
        var payload = new byte[6 + block.length];
        System.arraycopy("Exif".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 4);
        System.arraycopy(block, 0, payload, 6, block.length);
        app1.setUserObject(payload);
        sequence.appendChild(app1);
        var jfif = (IIOMetadataNode) tree.getElementsByTagName("app0JFIF").item(0);
        var icc = new IIOMetadataNode("app2ICC");
        var profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
        icc.setUserObject(profile);
        jfif.appendChild(icc);
        metadata.setFromTree(name, tree);
        var jpeg = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(jpeg)) {
            writer.setOutput(output);
            writer.write(new IIOImage(image, null, metadata));
        } finally {
            writer.dispose();
        }
        var jdkReader = ImageIoDeepDifferentialTest.jdkReader("jpeg");
        IIOImage read;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg.toByteArray()))) {
            jdkReader.setInput(input);
            read = new IIOImage(jdkReader.read(0), null, jdkReader.getImageMetadata(0));
        } finally {
            jdkReader.dispose();
        }
        var written = decode(write("webp", read));
        assertArrayEquals(block, written.exif().orElseThrow());
        assertEquals(Orientation.ofExif(3), written.orientation());
        assertArrayEquals(profileBytes(profile.getData()), profileBytes(written.iccProfile().orElseThrow()));
    }

    @Test
    @DisplayName("the ICC profile and XMP packet of the JDK PNG plug-in's metadata are written")
    void jdkPng() throws IOException {
        var image = pattern(24, 16);
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
        var name = metadata.getNativeMetadataFormatName();
        var tree = (IIOMetadataNode) metadata.getAsTree(name);
        var iccp = new IIOMetadataNode("iCCP");
        iccp.setAttribute("profileName", "p3");
        iccp.setAttribute("compressionMethod", "deflate");
        var p3 = ColorProfile.displayP3().toByteArray();
        iccp.setUserObject(p3);
        tree.appendChild(iccp);
        var itxt = new IIOMetadataNode("iTXt");
        var entry = new IIOMetadataNode("iTXtEntry");
        entry.setAttribute("keyword", "XML:com.adobe.xmp");
        entry.setAttribute("compressionFlag", "FALSE");
        entry.setAttribute("compressionMethod", "0");
        entry.setAttribute("languageTag", "");
        entry.setAttribute("translatedKeyword", "");
        entry.setAttribute("text", new String(XMP, StandardCharsets.UTF_8));
        itxt.appendChild(entry);
        tree.appendChild(itxt);
        metadata.setFromTree(name, tree);
        var png = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(png)) {
            writer.setOutput(output);
            writer.write(new IIOImage(image, null, metadata));
        } finally {
            writer.dispose();
        }
        var jdkReader = ImageIoDeepDifferentialTest.jdkReader("png");
        IIOImage read;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(png.toByteArray()))) {
            jdkReader.setInput(input);
            read = new IIOImage(jdkReader.read(0), null, jdkReader.getImageMetadata(0));
        } finally {
            jdkReader.dispose();
        }
        var written = decode(write("webp", read));
        assertArrayEquals(profileBytes(p3), profileBytes(written.iccProfile().orElseThrow()));
        assertArrayEquals(XMP, written.xmp().orElseThrow());
    }

    @Test
    @DisplayName("writer metadata is filled through its native and standard trees and reset")
    void filled() throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("webp").next();
        try {
            var image = pattern(16, 16);
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
            assertFalse(metadata.isReadOnly());
            var root = new IIOMetadataNode(SmaltImageMetadataFormat.NAME);
            var exif = new IIOMetadataNode("Exif");
            exif.setUserObject(exif(1));
            root.appendChild(exif);
            var icc = new IIOMetadataNode("IccProfile");
            icc.setUserObject(ICC_Profile.getInstance(ColorSpace.CS_sRGB));
            root.appendChild(icc);
            var xmp = new IIOMetadataNode("Xmp");
            xmp.setUserObject(XMP);
            root.appendChild(xmp);
            metadata.mergeTree(SmaltImageMetadataFormat.NAME, root);
            var standard = new IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName);
            var dimension = new IIOMetadataNode("Dimension");
            var orientation = new IIOMetadataNode("ImageOrientation");
            orientation.setAttribute("value", "Rotate90");
            dimension.appendChild(orientation);
            standard.appendChild(dimension);
            metadata.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName, standard);
            var written = decode(write("webp", new IIOImage(image, null, metadata)));
            assertEquals(Orientation.ofExif(8), written.orientation());
            assertTrue(written.iccProfile().isPresent());
            assertArrayEquals(XMP, written.xmp().orElseThrow());
            var tree = (IIOMetadataNode) metadata.getAsTree(SmaltImageMetadataFormat.NAME);
            assertEquals("8", tree.getAttribute("orientation"));
            metadata.reset();
            var reset = decode(write("webp", new IIOImage(image, null, metadata)));
            assertTrue(reset.exif().isEmpty());
            assertTrue(reset.iccProfile().isEmpty());
            assertTrue(reset.xmp().isEmpty());
            var converted = writer.convertImageMetadata(readImage(source()).getMetadata(), null, null);
            assertFalse(converted.isReadOnly());
            assertArrayEquals(exif(6), ((SmaltImageMetadata) converted).exif().orElseThrow());
        } finally {
            writer.dispose();
        }
    }

    @Test
    @DisplayName("read metadata is read-only, invalid trees and profiles are rejected")
    void rejected() throws Exception {
        var read = readImage(source()).getMetadata();
        assertTrue(read.isReadOnly());
        assertThrows(IllegalStateException.class, read::reset);
        assertThrows(IllegalStateException.class,
                () -> read.mergeTree(SmaltImageMetadataFormat.NAME, new IIOMetadataNode(SmaltImageMetadataFormat.NAME)));
        var writer = (ImageWriter) ImageIO.getImageWritersByFormatName("webp").next();
        try {
            IIOMetadata metadata = writer.getDefaultImageMetadata(null, null);
            var root = new IIOMetadataNode(SmaltImageMetadataFormat.NAME);
            root.setAttribute("orientation", "9");
            assertThrows(javax.imageio.metadata.IIOInvalidTreeException.class,
                    () -> metadata.mergeTree(SmaltImageMetadataFormat.NAME, root));
            var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var plain = document.createElement(SmaltImageMetadataFormat.NAME);
            assertThrows(javax.imageio.metadata.IIOInvalidTreeException.class,
                    () -> metadata.mergeTree(SmaltImageMetadataFormat.NAME, plain));
            var bad = new IIOMetadataNode(SmaltImageMetadataFormat.NAME);
            var icc = new IIOMetadataNode("IccProfile");
            icc.setUserObject(Arrays.copyOf(XMP, 200));
            bad.appendChild(icc);
            metadata.mergeTree(SmaltImageMetadataFormat.NAME, bad);
            assertThrows(IIOException.class, () -> write("webp", new IIOImage(pattern(8, 8), null, metadata)));
        } finally {
            writer.dispose();
        }
    }
}
