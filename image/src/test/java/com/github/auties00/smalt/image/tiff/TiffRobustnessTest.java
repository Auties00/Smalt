package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feeds the decoder damaged and hostile files: deterministic mutations of files of every codec
 * and layout, truncation at every structural boundary, limits, loops, and absurd tag values.
 * Every outcome must be a decoded picture or a {@link MediaException}, within a time bound.
 */
class TiffRobustnessTest {
    private static List<byte[]> seeds() {
        var seeds = new ArrayList<byte[]>();
        var rgb = TiffEncoderTest.picture(37, 23, true, false, 1);
        seeds.add(TiffEncoder.encode(rgb, TiffEncoderOptions.defaults()));
        seeds.add(TiffEncoder.encode(rgb, TiffEncoderOptions.deflate().toBuilder().tiles(16, 16).bigEndian(true)
                .build()));
        seeds.add(TiffEncoder.encode(rgb, TiffEncoderOptions.uncompressed().toBuilder().bigTiff(true).strips(4)
                .build()));
        seeds.add(TiffEncoder.encode(TiffEncoderTest.picture16(19, 11, false, 2),
                TiffEncoderOptions.builder().compression(TiffCompression.PACKBITS).build()));
        if (LibTiff.find("tiffcp").isPresent()) {
            var base = TiffEncoder.encode(TiffEncoderTest.picture(37, 23, false, false, 8), TiffEncoderOptions.uncompressed());
            for (var options : List.of("-c jpeg -r 16", "-c g4", "-c g3:2d", "-c lzw -p separate", "-c zip:2 -t -w 16 -l 16 -p separate")) {
                var source = options.contains("g") && !options.contains("jpeg")
                        ? TiffFiles.of(40, 20, 1, 1).photometric(0).data(TiffFiles.pack(TiffFiles.pattern(40, 20, 1, 1, 5), 40, 20, 1, 1, true)).bytes()
                        : base;
                seeds.add(LibTiff.tiffcp(source, List.of(options.split(" "))));
            }
        }
        seeds.add(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/jpeg12/226x149-ycbcr420-strips32.tif"));
        seeds.add(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/jpeg12/226x149-gray-strips16-tables.tif"));
        var pics = LibTiff.pics();
        for (var name : List.of("ycbcr-cat.tif", "zackthecat.tif", "smallliz.tif", "text.tif", "jello.tif", "caspian.tif")) {
            seeds.add(pics.get(name));
        }
        return seeds;
    }

    @Test
    @DisplayName("mutated files decode or raise MediaException, quickly")
    void mutations() {
        var seeds = seeds();
        var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds() * 3), () ->
                RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(150), 0x7171F, source -> {
                    try (var pages = TiffDecoder.openPages(source, ImageDecoderOptions.defaults()
                            .toBuilder().limits(MediaLimits.builder().maxBytes(64 << 20).maxPixels(4 << 20).build())
                                    .build())) {
                        for (var i = 0; i < Math.min(3, pages.pageCount()); i++) {
                            pages.page(i);
                            pages.probe(i);
                            pages.read(i);
                        }
                    }
                }));
        assertTrue(stats.decoded() > 0 && stats.rejected() > 0, stats.toString());
    }

    @Test
    @DisplayName("truncation at every byte of a small file raises only MediaException")
    void truncation() {
        var file = TiffEncoder.encode(TiffEncoderTest.picture(9, 7, true, false, 3), TiffEncoderOptions.defaults());
        for (var length = 0; length < file.length; length++) {
            var cut = java.util.Arrays.copyOf(file, length);
            try {
                TiffDecoder.decode(MediaSource.of(cut));
            } catch (MediaException expected) {
                // rejected
            }
        }
    }

    @Test
    @DisplayName("limits are checked before allocating")
    void limits() {
        var file = TiffEncoder.encode(TiffEncoderTest.picture(64, 64, false, false, 4), TiffEncoderOptions.defaults());
        var tight = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(1000).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> TiffDecoder.decode(MediaSource.of(file), tight));
        var pages = TiffEncoder.encode(List.of(TiffEncoderTest.picture(2, 2, false, false, 1), TiffEncoderTest.picture(2, 2, false, false, 2),
                TiffEncoderTest.picture(2, 2, false, false, 3)), TiffEncoderOptions.defaults());
        var fewFrames = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxFrames(2).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> TiffDecoder.openPages(MediaSource.of(pages), fewFrames));
        var huge = TiffFiles.of(1, 1, 1, 8).data(new byte[1]).bytes();
        var rejected = assertThrows(MediaException.class, () -> TiffDecoder.decode(MediaSource.of(withWidth(huge, 0x7FFFFFFF))));
        assertInstanceOf(MediaException.LimitExceeded.class, rejected);
    }

    private static byte[] withWidth(byte[] file, long width) {
        var copy = file.clone();
        var ifd = (copy[4] & 0xFF) | (copy[5] & 0xFF) << 8;
        var at = ifd + 2 + 8;
        for (var i = 0; i < 4; i++) {
            copy[at + i] = (byte) (width >>> (8 * i));
        }
        return copy;
    }

    @Test
    @DisplayName("rejects non-TIFF input and broken headers as malformed")
    void headers() {
        for (var bad : List.of(new byte[0], new byte[]{'I', 'I'}, "II*\0\0\0\0\0".getBytes(), "MM\0*\u0000\u0000\u0000\u0008".getBytes(),
                "XX*\0\u0008\0\0\0".getBytes(), "II+\0\u0004\0\0\0\u0010\0\0\0\0\0\0\0".getBytes())) {
            assertThrows(MediaException.class, () -> TiffDecoder.decode(MediaSource.of(bad)));
        }
        var bigWrongOffsetSize = "II+\0\u0004\0\0\0\u0010\0\0\0\0\0\0\0".getBytes();
        assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.openPages(MediaSource.of(bigWrongOffsetSize)));
    }

    @Test
    @DisplayName("missing strips leave transparent pixels and report alpha")
    void missingStrips() {
        var file = TiffEncoder.encode(TiffEncoderTest.picture(8, 8, false, false, 6), TiffEncoderOptions.uncompressed()
                .toBuilder().strips(4).build());
        // zero the second strip offset: StripOffsets is LONG[2], stored out of line
        try (var pages = TiffDecoder.openPages(MediaSource.of(file))) {
            assertFalse(pages.read(0).picture().hasAlpha());
        }
        var damaged = file.clone();
        var ifd = (damaged[4] & 0xFF) | (damaged[5] & 0xFF) << 8;
        var count = (damaged[ifd] & 0xFF) | (damaged[ifd + 1] & 0xFF) << 8;
        for (var i = 0; i < count; i++) {
            var at = ifd + 2 + i * 12;
            var tag = (damaged[at] & 0xFF) | (damaged[at + 1] & 0xFF) << 8;
            if (tag == 273) {
                var pointer = (damaged[at + 8] & 0xFF) | (damaged[at + 9] & 0xFF) << 8;
                for (var b = 0; b < 4; b++) {
                    damaged[pointer + 4 + b] = 0;
                }
            }
        }
        var picture = (com.github.auties00.smalt.image.RgbaPicture) TiffDecoder.decode(MediaSource.of(damaged)).picture();
        assertTrue(picture.hasAlpha());
        assertEquals(0, picture.pixels()[7 * 8 * 4 + 3]);
        assertEquals((byte) 0xFF, picture.pixels()[3]);
    }

    @Test
    @DisplayName("unsupported compressions and photometrics name the feature")
    void unsupported() {
        var base = TiffFiles.of(4, 4, 3, 8).data(new byte[48]);
        var lzma = base.tag(259, 34925).bytes();
        var failure = assertThrows(MediaException.Unsupported.class, () -> TiffDecoder.decode(MediaSource.of(lzma)));
        assertEquals("tiff lzma compression", failure.format());
        var cfa = TiffFiles.of(4, 4, 1, 8).photometric(32803).data(new byte[16]).bytes();
        assertEquals("tiff raw", assertThrows(MediaException.Unsupported.class,
                () -> TiffDecoder.decode(MediaSource.of(cfa))).format());
        // the page description is still available
        try (var pages = TiffDecoder.openPages(MediaSource.of(cfa))) {
            assertEquals(32803, pages.page(0).photometric().code());
        }
        assertEquals("tiff", assertThrows(MediaException.Unsupported.class,
                () -> Images.decode(MediaSource.of(TiffFiles.of(2, 2, 70, 8).data(new byte[280]).bytes()))).format());
    }
}
