package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import com.github.auties00.smalt.testkit.validate.IsoBmff;
import com.github.auties00.smalt.testkit.validate.Validators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;

import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.image.heic.HeicSamples.fast;
import static com.github.auties00.smalt.image.heic.HeicSamples.parse;
import static com.github.auties00.smalt.image.heic.HeicSamples.rgba;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEIC image sequence writer: animations play back through {@link HeicDecoder} with every frame, its
 * timestamp and duration, the alpha track and the loop count, and the file has the {@code msf1} layout (a primary
 * image item for still readers, an {@code hvc1} color track, an {@code auxv} alpha track, sync samples, composition
 * offsets and the edit list).
 */
@DisplayName("HEIC image sequence encoder")
class HeicSequenceEncoderTest {
    /** Writes an animation of {@code durations.length} frames; frames from {@code alphaFrom} on are transparent. */
    static byte[] animation(int width, int height, HeicEncoderOptions options, long[] durations, int alphaFrom) {
        var sink = MediaSink.memory();
        try (var writer = HeicEncoder.openAnimationWriter(width, height, options, sink)) {
            assertEquals(width, writer.width());
            assertEquals(height, writer.height());
            for (var i = 0; i < durations.length; i++) {
                writer.write(rgba(width, height, i, alphaFrom >= 0 && i >= alphaFrom), durations[i]);
            }
            writer.finish();
        }
        return sink.toByteArray();
    }

    static long[] constant(int frames, long duration) {
        var durations = new long[frames];
        java.util.Arrays.fill(durations, duration);
        return durations;
    }

    static List<AnimationFrame> play(byte[] file) {
        var frames = new ArrayList<AnimationFrame>();
        try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                frames.add(frame);
            }
        }
        return frames;
    }

    static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFFL) << 16 | (data[offset + 2] & 0xFFL) << 8
                | data[offset + 3] & 0xFFL;
    }

    static long readU64(byte[] data, int offset) {
        return readU32(data, offset) << 32 | readU32(data, offset + 4);
    }

    static void writeU32(byte[] data, int offset, long value) {
        for (var i = 0; i < 4; i++) {
            data[offset + i] = (byte) (value >>> (24 - 8 * i));
        }
    }

    static IsoBmff.Box stbl(byte[] file, int track) {
        var moov = IsoBmff.top(HeicSamples.boxes(file), "moov").orElseThrow();
        return moov.all("trak").get(track).find("mdia", "minf", "stbl").orElseThrow();
    }

    /** Asserts that the frames are the source frames, in order, with their timestamps and durations. */
    static void assertFrames(List<AnimationFrame> frames, int width, int height, long[] durations, int alphaFrom,
                             double minimumPsnr) {
        assertEquals(durations.length, frames.size());
        long time = 0;
        for (var i = 0; i < durations.length; i++) {
            var frame = frames.get(i);
            assertEquals(time, frame.timestampMicros(), "timestamp of frame " + i);
            assertEquals(durations[i], frame.durationMicros(), "duration of frame " + i);
            var picture = (RgbaPicture) frame.picture();
            assertEquals(width, picture.width());
            assertEquals(height, picture.height());
            var expected = rgba(width, height, i, alphaFrom >= 0 && i >= alphaFrom);
            var actual = ImageOps.toRgba(picture);
            if (alphaFrom < 0 || i < alphaFrom) {
                for (var p = 3; p < actual.pixels().length; p += 4) {
                    assertEquals(-1, actual.pixels()[p], "frame " + i + " is opaque");
                }
            }
            var psnr = Quality.psnr(expected, RgbaPicture.of(width, height, actual.stride(), actual.pixels(),
                    expected.hasAlpha()));
            assertTrue(psnr > minimumPsnr, "frame " + i + " PSNR " + psnr);
            time += durations[i];
        }
    }

    @Nested
    @DisplayName("playback")
    class Playback {
        @Test
        @DisplayName("predicted frames play back in display order with their timestamps and durations")
        void predicted() {
            var durations = constant(9, 40_000);
            var file = animation(64, 48, fast().build(), durations, -1);
            assertFrames(play(file), 64, 48, durations, -1, 33);
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
                assertEquals(9, reader.frameCount());
                assertEquals(0, reader.loopCount());
            }
        }

        @Test
        @DisplayName("independent frames of varying durations play back with their durations")
        void intra() {
            long[] durations = {10_000, 33_333, 50_000, 1, 70_001};
            var file = animation(48, 32, fast().interFrames(false).build(), durations, -1);
            assertFrames(play(file), 48, 32, durations, -1, 33);
        }

        @Test
        @DisplayName("predicted frames of varying durations keep their timestamps")
        void predictedVarying() {
            long[] durations = {50_000, 60_000, 70_000, 50_000, 60_000, 70_000, 50_000};
            var frames = play(animation(48, 32, fast().build(), durations, -1));
            assertEquals(durations.length, frames.size());
            long time = 0;
            for (var i = 0; i < durations.length; i++) {
                assertEquals(time, frames.get(i).timestampMicros(), "timestamp of frame " + i);
                time += durations[i];
            }
        }

        @Test
        @DisplayName("alpha that appears mid-animation plays from that frame, the earlier frames opaque")
        void lateAlpha() {
            var durations = constant(6, 50_000);
            var file = animation(48, 32, fast().build(), durations, 2);
            assertFrames(play(file), 48, 32, durations, 2, 30);
            var movie = HeifMovie.parse(MediaSource.of(file), parse(file), MediaLimits.DEFAULT);
            assertEquals(2, movie.tracks().size());
            var alpha = movie.tracks().get(1);
            assertEquals("auxv", alpha.handler());
            assertTrue(alpha.references("auxl", movie.tracks().getFirst().id()));
            assertEquals(6, alpha.sampleCount());
            assertEquals(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN,
                    alpha.property(HeifProperty.AuxiliaryType.class).urn());
        }

        @ParameterizedTest(name = "inter frames {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("premultiplied color carries a prem track reference and plays back as straight color")
        void premultiplied(boolean interFrames) {
            var durations = constant(3, 40_000);
            var file = animation(48, 32, fast().premultipliedAlpha(true).interFrames(interFrames).build(), durations,
                    0);
            var movie = HeifMovie.parse(MediaSource.of(file), parse(file), MediaLimits.DEFAULT);
            var color = movie.tracks().getFirst();
            assertTrue(color.references("prem", movie.tracks().get(1).id()));
            var still = parse(file).primaryItem();
            assertTrue(still.references("prem", parse(file).auxiliariesOf(still.id()).getFirst().id()));
            var frames = play(file);
            for (var i = 0; i < frames.size(); i++) {
                // the premultiplied coding loses precision where alpha is small, so compare the more opaque half
                var expected = ImageOps.crop(rgba(48, 32, i, true), 24, 0, 24, 32);
                var actual = ImageOps.crop(ImageOps.toRgba(frames.get(i).picture()), 24, 0, 24, 32);
                var psnr = Quality.psnr(expected, actual);
                assertTrue(psnr > 30, "frame " + i + " PSNR " + psnr);
            }
            assertFalse(movie.tracks().get(1).references("prem", color.id()), "the alpha track is not premultiplied");
        }

        @ParameterizedTest(name = "{0} plays")
        @ValueSource(ints = {0, 1, 3})
        @DisplayName("the loop count is kept in the repeating edit list and the track lasts every play")
        void loops(int loops) {
            var file = animation(32, 32, fast().loopCount(loops).build(), constant(3, 100_000), -1);
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
                assertEquals(loops, reader.loopCount());
            }
            var tkhd = IsoBmff.top(HeicSamples.boxes(file), "moov").orElseThrow().find("trak", "tkhd")
                    .orElseThrow();
            var durationAt = (int) tkhd.payload() + 20;
            assertEquals(loops == 0 ? 0xFFFFFFFFL : 300_000L * loops, readU32(file, durationAt),
                    "the track duration, indefinite for an endless loop");
            Validators.assertValid("heic-loops-" + loops + ".heic", file);
            if (loops == 3) {
                var broken = file.clone();
                writeU32(broken, durationAt, 350_000L * 3);
                assertThrows(AssertionFailedError.class, () -> Validators.assertValid("heic-loops-broken.heic",
                        broken), "a track duration that is not a whole number of plays");
            }
        }

        @Test
        @DisplayName("plays whose total overflows 32 bits keep the loop count in 64-bit movie and track headers")
        void longLoops() {
            // two frames of 25 minutes each, played three times: 3e9 us per play, 9e9 in all
            var file = animation(16, 16, fast().interFrames(false).loopCount(3).build(),
                    constant(2, 1_500_000_000L), -1);
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
                assertEquals(3, reader.loopCount());
            }
            var moov = IsoBmff.top(HeicSamples.boxes(file), "moov").orElseThrow();
            var mvhd = moov.find("mvhd").orElseThrow();
            assertEquals(1, file[(int) mvhd.payload()], "mvhd version");
            assertEquals(3 * 3_000_000_000L, readU64(file, (int) mvhd.payload() + 24));
            var tkhd = moov.find("trak", "tkhd").orElseThrow();
            assertEquals(1, file[(int) tkhd.payload()], "tkhd version");
            assertEquals(3 * 3_000_000_000L, readU64(file, (int) tkhd.payload() + 28));
            Validators.assertValid("heic-long-loops.heic", file);
            var frames = play(file);
            assertEquals(2, frames.size());
            assertEquals(1_500_000_000L, frames.get(1).timestampMicros());
        }

        @ParameterizedTest(name = "inter frames {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a transparent animation seeks with its alpha before any read, after a partial read and at the end")
        void seekAlpha(boolean interFrames) {
            var durations = constant(6, 40_000);
            var file = animation(48, 32, fast().interFrames(interFrames).build(), durations, 0);
            AnimationSeeks.assertMatchesSequential(() -> HeicDecoder.openAnimation(MediaSource.of(file)), 7);
            var expected = play(file);
            for (var consumed : new int[]{0, 2, durations.length}) {
                try (var reader = HeicDecoder.openAnimation(MediaSource.of(file))) {
                    for (var i = 0; i < consumed; i++) {
                        assertNotNull(reader.read(), "frame " + i);
                    }
                    for (var target : new int[]{1, 4, 0, 5}) {
                        reader.seek(40_000L * target);
                        var frame = reader.read();
                        assertNotNull(frame, "frame " + target + " after " + consumed + " reads");
                        assertEquals(expected.get(target).timestampMicros(), frame.timestampMicros());
                        assertArrayEquals((byte[]) AnimationSeeks.pixels(expected.get(target).picture()),
                                (byte[]) AnimationSeeks.pixels(frame.picture()), "frame " + target + " after "
                                        + consumed + " reads, alpha included");
                    }
                }
            }
        }

        @Test
        @DisplayName("an odd canvas is cropped back by the clean aperture of the sample entry and of the primary item")
        void oddCanvas() {
            var durations = constant(4, 40_000);
            var file = animation(33, 19, fast().build(), durations, 1);
            assertFrames(play(file), 33, 19, durations, 1, 30);
            var track = HeifMovie.parse(MediaSource.of(file), parse(file), MediaLimits.DEFAULT).tracks().getFirst();
            assertNotNull(track.property(HeifProperty.CleanAperture.class));
            assertEquals(33, track.width());
            var still = HeicDecoder.decode(MediaSource.of(file)).picture();
            assertEquals(33, still.width());
            assertEquals(19, still.height());
        }

        @Test
        @DisplayName("the primary image of a sequence is its first frame")
        void primary() {
            var file = animation(48, 32, fast().build(), constant(3, 40_000), 0);
            var still = ImageOps.toRgba(HeicDecoder.decode(MediaSource.of(file)).picture());
            var first = ImageOps.toRgba(play(file).getFirst().picture());
            assertArrayEquals(first.pixels(), still.pixels());
            // a HEIF file with a primary image shows it to a still reader, which the probe describes
            assertEquals(1, HeicDecoder.probe(MediaSource.of(file)).frameCount());
        }

        @Test
        @DisplayName("a 10-bit sequence carries the hevx and heix brands")
        void tenBit() {
            var file = animation(32, 32, fast().bitDepth(10).build(), constant(3, 40_000), -1);
            var heif = parse(file);
            assertEquals("heix", heif.majorBrand());
            assertTrue(heif.brands().containsAll(List.of("msf1", "hevx", "heix", "mif1", "miaf", "iso8")));
            assertEquals(10, HeicSamples.bitDepth(heif.primaryItem().property(HeifProperty.HevcConfiguration.class)
                    .record()));
            assertFrames(play(file), 32, 32, constant(3, 40_000), -1, 33);
        }

        @Test
        @DisplayName("Images writes HEIC animations with the requested loop count")
        void registry() {
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.HEIC, 32, 24, ImageEncoderOptions.builder()
                    .loopCount(2).build(), sink)) {
                for (var i = 0; i < 3; i++) {
                    writer.write(rgba(32, 24, i, false), 100_000);
                }
                writer.finish();
            }
            var file = sink.toByteArray();
            assertEquals(ImageFormat.HEIC, Images.detect(MediaSource.of(file)).orElseThrow());
            try (var reader = Images.openAnimation(MediaSource.of(file))) {
                assertEquals(2, reader.loopCount());
                assertEquals(3, reader.frameCount());
            }
        }
    }

    @Nested
    @DisplayName("file layout")
    class Layout {
        @Test
        @DisplayName("the major brand is heic, as for a still image, and the compatible brands add the sequence ones")
        void brands() {
            var file = parse(animation(32, 32, fast().build(), constant(2, 40_000), -1));
            assertEquals("heic", file.majorBrand());
            assertEquals(List.of("heic", "heic", "mif1", "miaf", "msf1", "hevc", "iso8"), file.brands());
            assertTrue(file.hasMovie());
            assertEquals("hvc1", file.primaryItem().type());
        }

        @Test
        @DisplayName("predicted frames are stored with composition offsets and a sync sample table")
        void reordered() {
            var file = animation(48, 32, fast().build(), constant(9, 40_000), -1);
            var stbl = stbl(file, 0);
            assertTrue(stbl.child("ctts").isPresent(), "B pictures reorder the samples");
            assertTrue(stbl.child("stss").isPresent(), "not every sample is a sync sample");
            var track = HeifMovie.parse(MediaSource.of(file), parse(file), MediaLimits.DEFAULT).tracks().getFirst();
            assertEquals("pict", track.handler());
            assertEquals("hvc1", track.sampleEntry());
            assertEquals(9, track.sampleCount());
            assertNotNull(track.property(HeifProperty.HevcConfiguration.class));
            assertNotNull(track.property(HeifProperty.ColorNclx.class));
        }

        @Test
        @DisplayName("the color sample entry carries the color boxes of the primary item, HDR metadata and ICC included")
        void colorBoxes() {
            var mastering = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.170, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.3290), 1000, 0.005);
            var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ).withMasteringDisplay(mastering)
                    .withContentLightLevel(new ContentLightLevel(800, 400));
            var profile = ColorProfile.displayP3();
            var file = animation(32, 32, fast().bitDepth(10).color(color).colorProfile(profile).build(),
                    constant(3, 40_000), 1);
            var heif = parse(file);
            var tracks = HeifMovie.parse(MediaSource.of(file), heif, MediaLimits.DEFAULT).tracks();
            var track = tracks.getFirst();
            var nclx = track.property(HeifProperty.ColorNclx.class);
            assertEquals(MatrixCoefficients.BT2020_NCL.code(), nclx.matrix());
            assertEquals(TransferCharacteristics.PQ.code(), nclx.transfer());
            assertFalse(nclx.fullRange());
            assertArrayEquals(profile.toByteArray(), track.property(HeifProperty.ColorIcc.class).profile());
            var clli = track.property(HeifProperty.ContentLightLevel.class);
            assertEquals(800, clli.maxContentLightLevel());
            assertEquals(400, clli.maxPictureAverageLightLevel());
            assertEquals(10_000_000L, track.property(HeifProperty.MasteringDisplay.class).maxLuminance());
            var primary = heif.primaryItem();
            assertEquals(800, primary.property(HeifProperty.ContentLightLevel.class).maxContentLightLevel());
            var alpha = tracks.get(1);
            assertNull(alpha.property(HeifProperty.ContentLightLevel.class), "the alpha track has no color");
            assertNull(alpha.property(HeifProperty.ColorNclx.class));
        }

        @Test
        @DisplayName("independent frames need neither composition offsets nor a sync sample table")
        void independent() {
            var file = animation(48, 32, fast().interFrames(false).build(), constant(4, 40_000), -1);
            var stbl = stbl(file, 0);
            assertFalse(stbl.child("ctts").isPresent());
            assertFalse(stbl.child("stss").isPresent());
        }
    }

    @Nested
    @DisplayName("writer contract")
    class Contract {
        @Test
        @DisplayName("frames of another size, non-positive durations, empty animations and writes or finishes after finish fail")
        void misuse() {
            var sink = MediaSink.memory();
            try (var writer = HeicEncoder.openAnimationWriter(32, 32, fast().build(), sink)) {
                assertThrows(IllegalArgumentException.class, () -> writer.write(rgba(16, 32, 0, false), 1000));
                assertThrows(IllegalArgumentException.class, () -> writer.write(rgba(32, 32, 0, false), 0));
                assertThrows(IllegalStateException.class, writer::finish);
                writer.write(rgba(32, 32, 0, false), 1000);
                writer.finish();
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalStateException.class, () -> writer.write(rgba(32, 32, 0, false), 1000));
            }
            var finished = sink.toByteArray();
            assertTrue(finished.length > 0);
            assertEquals(1, play(finished).size(), "a second finish writes nothing more");
            var closed = MediaSink.memory();
            var writer = HeicEncoder.openAnimationWriter(32, 32, fast().build(), closed);
            writer.write(rgba(32, 32, 0, false), 1000);
            writer.close();
            assertThrows(IllegalStateException.class, writer::finish);
            assertEquals(0, closed.toByteArray().length, "a closed writer writes nothing");
        }

        @Test
        @DisplayName("an animation longer than the 32-bit track durations store is rejected at the frame that overflows")
        void tooLong() {
            try (var writer = HeicEncoder.openAnimationWriter(16, 16, fast().interFrames(false).build(),
                    MediaSink.memory())) {
                writer.write(rgba(16, 16, 0, false), 0xFFFFFFFEL - 10);
                assertThrows(IllegalArgumentException.class, () -> writer.write(rgba(16, 16, 1, false), 11));
                assertThrows(IllegalArgumentException.class, () -> writer.write(rgba(16, 16, 1, false),
                        Long.MAX_VALUE));
                writer.write(rgba(16, 16, 1, false), 10);
                writer.finish();
            }
        }
    }
}
