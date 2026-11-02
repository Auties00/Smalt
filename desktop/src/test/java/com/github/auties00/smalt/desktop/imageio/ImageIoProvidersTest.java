package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The codec artifacts on this test class path are smalt-image, smalt-audio, smalt-mpeg and
// smalt-video: they decode WebP, PNG, HEIC and AVIF and encode WebP, AVIF and HEIC.
@DisplayName("ImageIO provider discovery")
class ImageIoProvidersTest {
    static <T> List<T> list(Iterator<T> iterator) {
        var result = new ArrayList<T>();
        iterator.forEachRemaining(result::add);
        return result;
    }

    @Test
    @DisplayName("the WebP reader is found by format name, suffix and MIME type")
    void webpReader() {
        for (var readers : List.of(ImageIO.getImageReadersByFormatName("webp"), ImageIO.getImageReadersBySuffix("webp"),
                ImageIO.getImageReadersByMIMEType("image/webp"))) {
            var found = list(readers);
            assertEquals(1, found.size());
            assertInstanceOf(SmaltImageReader.class, found.getFirst());
        }
    }

    @Test
    @DisplayName("the APNG reader is found by name, and still PNGs keep the JDK reader")
    void apngReader() {
        assertInstanceOf(SmaltImageReader.class, list(ImageIO.getImageReadersByFormatName("apng")).getFirst());
        var png = list(ImageIO.getImageReadersByFormatName("png"));
        assertFalse(png.isEmpty());
        assertTrue(png.stream().noneMatch(SmaltImageReader.class::isInstance));
    }

    @Test
    @DisplayName("the reader serves only formats the JDK lacks, plus APNG")
    void readerFormats() {
        var provider = new SmaltImageReaderSpi();
        assertEquals(List.of(ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.HEIC, ImageFormat.AVIF, ImageFormat.ICO,
                ImageFormat.ANI, ImageFormat.JP2, ImageFormat.JXL, ImageFormat.DNG, ImageFormat.RAW), provider.formats());
        var names = Arrays.asList(provider.getFormatNames());
        assertTrue(names.contains("webp"));
        assertTrue(names.contains("avif"));
        assertTrue(names.contains("heic"));
        assertTrue(names.contains("apng"));
        assertTrue(names.contains("ico"));
        assertTrue(names.contains("ani"));
        assertTrue(Arrays.asList(provider.getFileSuffixes()).contains("ani"));
        assertTrue(names.contains("jpeg2000"));
        assertTrue(names.contains("jp2"));
        assertTrue(Arrays.asList(provider.getFileSuffixes()).containsAll(List.of("jph", "jhc")));
        assertTrue(Arrays.asList(provider.getMIMETypes()).containsAll(List.of("image/jph", "image/jphc")));
        assertTrue(names.contains("dng"));
        assertTrue(names.contains("raw"));
        assertTrue(Arrays.asList(provider.getFileSuffixes()).contains("nef"));
        assertFalse(names.contains("tiff"));
        assertFalse(names.contains("png"));
        assertFalse(names.contains("jpeg"));
        assertTrue(Arrays.asList(ImageIO.getReaderFormatNames()).contains("webp"));
    }

    @Test
    @DisplayName("the WebP, AVIF, JPEG XL and HEIC writers are registered")
    void writers() {
        var webp = list(ImageIO.getImageWritersByFormatName("webp"));
        assertEquals(1, webp.size());
        assertInstanceOf(SmaltImageWriter.class, webp.getFirst());
        assertInstanceOf(SmaltImageWriter.class, list(ImageIO.getImageWritersBySuffix("webp")).getFirst());
        var avif = list(ImageIO.getImageWritersByFormatName("avif"));
        assertEquals(1, avif.size());
        assertInstanceOf(SmaltImageWriter.class, avif.getFirst());
        var jxl = list(ImageIO.getImageWritersByFormatName("jxl"));
        assertEquals(1, jxl.size());
        assertInstanceOf(SmaltImageWriter.class, jxl.getFirst());
        var heic = list(ImageIO.getImageWritersByFormatName("heic"));
        assertEquals(1, heic.size());
        assertInstanceOf(SmaltImageWriter.class, heic.getFirst());
        assertInstanceOf(SmaltImageWriter.class, list(ImageIO.getImageWritersByMIMEType("image/heic")).getFirst());
        var registered = list(IIORegistry.getDefaultInstance().getServiceProviders(ImageWriterSpi.class, false));
        assertTrue(registered.stream().anyMatch(WebpImageWriterSpi.class::isInstance));
        assertTrue(registered.stream().anyMatch(AvifImageWriterSpi.class::isInstance));
        assertTrue(registered.stream().anyMatch(JxlImageWriterSpi.class::isInstance));
        assertTrue(registered.stream().anyMatch(HeicImageWriterSpi.class::isInstance));
    }

    @Test
    @DisplayName("the writer names the reader")
    void pairing() {
        ImageWriter writer = list(ImageIO.getImageWritersByFormatName("webp")).getFirst();
        ImageReader reader = ImageIO.getImageReader(writer);
        assertInstanceOf(SmaltImageReader.class, reader);
    }
}
