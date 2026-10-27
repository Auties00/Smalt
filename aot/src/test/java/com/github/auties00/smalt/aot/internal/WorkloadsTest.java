package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingMode;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creates every workload of every family and runs two rounds of each, so that a codec API change
 * that breaks a workload fails here rather than as a skipped workload in a user's training run.
 */
class WorkloadsTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingFamily.class)
    @DisplayName("every workload of a family is created under its factory's name and runs its rounds")
    void runs(TrainingFamily family) throws Exception {
        var factories = Workloads.of(family);
        assertFalse(factories.isEmpty());
        for (var factory : factories) {
            var workload = factory.create();
            assertEquals(factory.name(), workload.name());
            workload.round();
            workload.round();
        }
    }

    @Test
    @DisplayName("the real-time workloads cover every artifact with real-time codecs and no other")
    void realtimeModes() {
        var realtime = EnumSet.of(TrainingFamily.IMAGE, TrainingFamily.AUDIO, TrainingFamily.VIDEO, TrainingFamily.MPEG,
                TrainingFamily.CAPTURE);
        for (var family : TrainingFamily.values()) {
            var hasRealtime = Workloads.of(family).stream().anyMatch(factory -> factory.mode() == TrainingMode.REALTIME);
            assertEquals(realtime.contains(family), hasRealtime, family.name());
        }
    }

    @Test
    @DisplayName("the synthetic TIFF variants decode, the masked page with alpha")
    void tiffVariants() {
        var files = SyntheticImages.tiffVariants(SyntheticImages.rgba(SyntheticVideo.natural(32, 16, 1).getFirst(),
                true));
        assertEquals(7, files.size());
        for (var file : files) {
            var picture = TiffDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(32, picture.width());
            assertEquals(16, picture.height());
        }
        var masked = (RgbaPicture) TiffDecoder.decode(MediaSource.of(files.getLast())).picture();
        assertTrue(masked.hasAlpha());
        assertEquals(0, masked.pixels()[3]);
    }

    @Test
    @DisplayName("the synthetic multi-scan and arithmetic-coded lossless JPEG files decode to their samples")
    void losslessScans() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(48, 20, 1).getFirst(), false);
        var wide = ImageDecoderOptions.builder().highBitDepth(true).build();
        for (var precision : new int[]{8, 12, 16}) {
            var samples = SyntheticImages.losslessSamples(picture, 3, precision);
            for (var arithmetic : new boolean[]{false, true}) {
                for (var scans : List.of(new int[][]{{0, 1, 2}}, new int[][]{{2}, {0, 1}})) {
                    var jpeg = SyntheticImages.losslessScansJpeg(samples, 48, 20, 3, precision, precision % 7 + 1,
                            arithmetic, scans);
                    var decoded = JpegDecoder.decode(MediaSource.of(jpeg), wide).picture();
                    for (var i = 0; i < 48 * 20; i++) {
                        for (var c = 0; c < 3; c++) {
                            var x = i % 48;
                            var y = i / 48;
                            var actual = switch (decoded) {
                                case YuvPicture yuv -> (c == 0 ? yuv.y()[y * yuv.yStride() + x]
                                        : (c == 1 ? yuv.u() : yuv.v())[y * yuv.uvStride() + x]) & 0xFF;
                                case Yuv16Picture yuv -> (c == 0 ? yuv.y()[y * yuv.yStride() + x]
                                        : (c == 1 ? yuv.u() : yuv.v())[y * yuv.uvStride() + x]) & 0xFFFF;
                                default -> throw new AssertionError(decoded);
                            };
                            assertEquals(samples[i * 3 + c], actual, precision + " bits, arithmetic " + arithmetic);
                        }
                    }
                }
            }
        }
        var gray = SyntheticImages.losslessSamples(picture, 1, 8);
        var five = new int[48 * 20 * 5];
        for (var i = 0; i < five.length; i++) {
            five[i] = gray[i / 5] ^ (i % 5) * 37 & 0xFF;
        }
        var jpeg = SyntheticImages.losslessScansJpeg(five, 48, 20, 5, 8, 6, true, new int[][]{{0, 1, 2, 3}, {4}});
        var channels = (Channel8Picture) JpegDecoder.decode(MediaSource.of(jpeg),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture();
        for (var i = 0; i < five.length; i++) {
            assertEquals(five[i], channels.pixels()[i] & 0xFF);
        }
    }

    @Test
    @DisplayName("workload names are unique across families")
    void uniqueNames() {
        var names = new HashSet<String>();
        for (var family : TrainingFamily.values()) {
            for (var factory : Workloads.of(family)) {
                assertTrue(names.add(factory.name()), factory.name());
            }
        }
    }

    @Test
    @DisplayName("synthetic video is deterministic and has a flat band and black frames")
    void syntheticVideo() {
        var first = SyntheticVideo.natural(64, 32, 6);
        var second = SyntheticVideo.natural(64, 32, 6);
        for (var t = 0; t < first.size(); t++) {
            assertArrayEquals(first.get(t).y(), second.get(t).y());
        }
        assertEquals((byte) 128, first.getFirst().y()[0]);
        var black = first.get(5);
        assertEquals((byte) 16, black.y()[black.yStride() * 31 + 63]);
        for (var kind = 0; kind < SyntheticVideo.CONTENT_CLASSES; kind++) {
            List<YuvPicture> clip = SyntheticVideo.content(kind, 64, 32, 3);
            assertEquals(3, clip.size());
        }
    }

    @Test
    @DisplayName("synthetic speech and music are deterministic and normalized")
    void syntheticAudio() {
        var speech = SyntheticAudio.speech(16000, 1);
        assertArrayEquals(speech, SyntheticAudio.speech(16000, 1));
        assertEquals(16000, speech.length);
        var peak = 0f;
        for (var sample : speech) {
            peak = Math.max(peak, Math.abs(sample));
        }
        assertEquals(0.5f, peak, 1e-6f);
        var music = SyntheticAudio.music(8000, 1);
        assertEquals(2, music.length);
        assertEquals(8000, music[1].length);
    }

    @Test
    @DisplayName("synthetic MRW, CRW, CR3 and X3F files are decodable files of their formats")
    void syntheticVendorRaw() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(64, 48, 1).getFirst(), false);
        var files = List.of(SyntheticVendorRaw.mrw(picture, true), SyntheticVendorRaw.mrw(picture, false),
                SyntheticVendorRaw.crw(picture), SyntheticVendorRaw.x3f(picture, false),
                SyntheticVendorRaw.x3f(picture, true), SyntheticCr3.cr3(picture, 0, 0x100),
                SyntheticCr3.cr3(picture, 3, 0x100), SyntheticCr3.cr3(picture, 3, 0x200));
        var formats = List.of(RawFormat.MRW, RawFormat.MRW, RawFormat.CRW, RawFormat.X3F, RawFormat.X3F, RawFormat.CR3,
                RawFormat.CR3, RawFormat.CR3);
        // the image areas: MRW 8 photosites smaller, CRW without its masked columns, X3F its active area, CR3 its
        // crop 8 photosites in from every edge
        var sizes = new int[][]{{56, 40}, {56, 40}, {56, 44}, {60, 44}, {60, 44}, {48, 32}, {48, 32}, {48, 32}};
        for (var index = 0; index < files.size(); index++) {
            var info = RawDecoder.info(MediaSource.of(files.get(index)));
            assertEquals(formats.get(index), info.format());
            assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
            assertEquals(sizes[index][0], info.width(), formats.get(index).name());
            assertEquals(sizes[index][1], info.height(), formats.get(index).name());
            var raw = RawDecoder.decodeRaw(MediaSource.of(files.get(index)));
            assertEquals(info.width(), raw.width());
        }
        assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(files.get(0))).samples(),
                RawDecoder.decodeRaw(MediaSource.of(files.get(1))).samples());
    }

    @Test
    @DisplayName("synthetic RAF files, compressed and of bare SuperCCD words, are decodable files of their format")
    void syntheticRaf() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(800, 48, 1).getFirst(), false);
        for (var variant = 0; variant < 4; variant++) {
            var file = SyntheticRaf.compressed(picture, (variant & 1) != 0, variant >= 2);
            var info = RawDecoder.info(MediaSource.of(file));
            assertEquals(RawFormat.RAF, info.format());
            assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
            assertEquals(800, info.width());
            assertEquals(48, info.height());
            assertEquals(800 * 48, RawDecoder.decodeRaw(MediaSource.of(file)).samples().length);
        }
        var superCcd = SyntheticRaf.superCcd(SyntheticImages.rgba(SyntheticVideo.natural(64, 48, 1).getFirst(), false));
        var info = RawDecoder.info(MediaSource.of(superCcd));
        assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
        assertTrue(info.cfaLayout().isStaggered());
        assertEquals(com.github.auties00.smalt.image.Orientation.TRANSPOSE, info.orientation());
        // the low-sensitivity photodiodes of the SuperCCD SR file are the second sample of each photosite
        var sensor = RawDecoder.decodeRaw(MediaSource.of(superCcd));
        assertEquals(2, sensor.channels());
        assertEquals(sensor.width() * sensor.height() * 2, sensor.samples().length);
        assertTrue(RawDecoder.decode(MediaSource.of(superCcd)).picture().width() > 0);
    }
}
