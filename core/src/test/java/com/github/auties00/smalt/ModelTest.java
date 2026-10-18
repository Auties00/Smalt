package com.github.auties00.smalt;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the shared value types: limits, rationals, orientations, format detection, pictures and
 * audio buffers.
 */
class ModelTest {
    @Nested
    @DisplayName("Rational")
    class RationalRescale {
        @ParameterizedTest(name = "{0} in {1}/{2} -> {5} in {3}/{4}")
        @CsvSource({
                "90000, 1, 90000, 1, 1000, 1000",
                "1, 1, 3, 1, 1000, 333",
                "2, 1, 3, 1, 1000, 667",
                "-2, 1, 3, 1, 1000, -667",
                "1024, 1, 48000, 1, 1000000, 21333",
                "9223372036854775, 1, 1000, 1, 1000000, 9223372036854775000"
        })
        @DisplayName("rescales exactly with round-half-away-from-zero")
        void rescale(long value, long fromNum, long fromDen, long toNum, long toDen, long expected) {
            assertEquals(expected, new Rational(fromNum, fromDen).rescale(value, new Rational(toNum, toDen)));
        }

        @Test
        @DisplayName("falls back to arbitrary precision when only the intermediate product overflows")
        void overflow() {
            // 2^40 * 1_000_000_007 exceeds 2^62, but the quotient by 3 * 1_000_000_009 fits easily
            var result = new Rational(1_000_000_007L, 3).rescale(1L << 40, new Rational(1_000_000_009L, 1));
            var expected = new java.math.BigDecimal(1L << 40)
                    .multiply(java.math.BigDecimal.valueOf(1_000_000_007L))
                    .divide(java.math.BigDecimal.valueOf(3L * 1_000_000_009L), 0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("throws ArithmeticException when the result does not fit in a long")
        void unrepresentable() {
            assertThrows(ArithmeticException.class,
                    () -> new Rational(1_000_000_007L, 1).rescale(1L << 40, new Rational(1, 1_000_000_009L)));
        }

        @Test
        @DisplayName("rejects non-positive fractions")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> new Rational(0, 1));
            assertThrows(IllegalArgumentException.class, () -> new Rational(1, -1));
        }
    }

    @Nested
    @DisplayName("Orientation")
    class Orientations {
        @ParameterizedTest(name = "{0} degrees mirror={1} -> {2}")
        @CsvSource({
                "0, false, NORMAL", "90, false, ROTATE_90", "180, false, ROTATE_180", "270, false, ROTATE_270",
                "-90, false, ROTATE_270", "450, false, ROTATE_90",
                "0, true, FLIP_HORIZONTAL", "90, true, TRANSVERSE", "180, true, FLIP_VERTICAL", "270, true, TRANSPOSE"
        })
        @DisplayName("maps rotations and mirrors to EXIF orientations")
        void ofRotation(int degrees, boolean mirror, Orientation expected) {
            assertEquals(expected, Orientation.ofRotation(degrees, mirror));
        }

        @Test
        @DisplayName("maps EXIF codes and treats out-of-range codes as normal")
        void ofExif() {
            for (var orientation : Orientation.values()) {
                assertEquals(orientation, Orientation.ofExif(orientation.exifCode()));
            }
            assertEquals(Orientation.NORMAL, Orientation.ofExif(0));
            assertEquals(Orientation.NORMAL, Orientation.ofExif(9));
        }
    }

    @Nested
    @DisplayName("ImageFormat")
    class Detection {
        private ImageFormat detect(byte[] header) {
            return ImageFormat.detect(header, header.length).orElse(null);
        }

        @Test
        @DisplayName("recognizes every supported magic number")
        void magic() {
            assertEquals(ImageFormat.JPEG, detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
            assertEquals(ImageFormat.PNG, detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
            assertEquals(ImageFormat.GIF, detect("GIF89a".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(ImageFormat.GIF, detect("GIF87a".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(ImageFormat.WEBP, detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(ImageFormat.BMP, detect("BM\0\0\0\0\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(ImageFormat.TIFF, detect(new byte[]{'I', 'I', 42, 0}));
            assertEquals(ImageFormat.TIFF, detect(new byte[]{'M', 'M', 0, 42}));
            assertEquals(ImageFormat.TIFF, detect(new byte[]{'I', 'I', 43, 0}));
            assertEquals(ImageFormat.TIFF, detect(new byte[]{'M', 'M', 0, 43}));
            assertEquals(ImageFormat.ICO, detect(icon(1, 1, 22)));
            assertEquals(ImageFormat.ICO, detect(icon(2, 3, 54)));
            assertEquals(ImageFormat.JP2, detect(new byte[]{(byte) 0xFF, 0x4F, (byte) 0xFF, 0x51}));
            assertEquals(ImageFormat.JP2, detect(new byte[]{0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A}));
            assertNull(detect(new byte[]{0, 0, 0, 12, 'j', 'P', ' ', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0B}));
        }

        @Test
        @DisplayName("tells icons from other files that share their first bytes")
        void iconLookalikes() {
            assertNull(detect(icon(1, 0, 22)), "no entries");
            assertNull(detect(icon(1, 2, 22)), "data inside the directory");
            var targa = icon(2, 1, 22);
            targa[9] = 1;
            assertNull(detect(targa), "non-zero reserved byte, as in a Targa header");
            assertNull(detect(java.util.Arrays.copyOf(icon(1, 1, 22), 21)), "too short to hold an entry");
        }

        private byte[] icon(int type, int count, int offset) {
            var header = new byte[22];
            header[2] = (byte) type;
            header[4] = (byte) count;
            header[6] = 16;
            header[7] = 16;
            header[14] = 40;
            header[18] = (byte) offset;
            return header;
        }

        @Test
        @DisplayName("recognizes HEIC and AVIF from ftyp brands, including compatible brands")
        void heif() {
            assertEquals(ImageFormat.HEIC, detect(ftyp("heic", "mif1", "heic")));
            assertEquals(ImageFormat.HEIC, detect(ftyp("mif1", "mif1", "heic")));
            assertEquals(ImageFormat.AVIF, detect(ftyp("avif", "mif1", "miaf")));
            assertEquals(ImageFormat.AVIF, detect(ftyp("mif1", "avif", "heic")));
            assertNull(detect(ftyp("isom", "iso2", "mp41")), "an MP4 video is not an image");
        }

        @Test
        @DisplayName("returns null for unknown or too-short input")
        void unknown() {
            assertNull(detect(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}));
            assertNull(detect(new byte[0]));
            assertTrue(ImageFormat.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 2).isEmpty());
        }

        private byte[] ftyp(String major, String... compatible) {
            var size = 16 + 4 * compatible.length;
            var box = new byte[size];
            box[3] = (byte) size;
            System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, box, 4, 4);
            System.arraycopy(major.getBytes(StandardCharsets.US_ASCII), 0, box, 8, 4);
            for (var i = 0; i < compatible.length; i++) {
                System.arraycopy(compatible[i].getBytes(StandardCharsets.US_ASCII), 0, box, 16 + 4 * i, 4);
            }
            return box;
        }
    }

    @Nested
    @DisplayName("Pictures")
    class Pictures {
        @Test
        @DisplayName("sizes chroma planes with round-up for odd dimensions")
        void chromaSizes() {
            var picture = YuvPicture.allocate(7, 5, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            assertEquals(4, picture.chromaWidth());
            assertEquals(3, picture.chromaHeight());
            assertEquals(12, picture.u().length);
            assertEquals((byte) 255, picture.alpha()[34]);
            assertEquals((byte) 128, picture.v()[11]);
            var mono = YuvPicture.allocate(3, 3, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            assertNull(mono.u());
            assertEquals(0, mono.uvStride());
            assertFalse(mono.hasAlpha());
        }

        @Test
        @DisplayName("rejects planes that are too short or strides that are too small")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> RgbaPicture.of(2, 2, 7, new byte[16], true));
            assertThrows(IllegalArgumentException.class, () -> RgbaPicture.of(2, 2, 8, new byte[15], true));
            assertThrows(IllegalArgumentException.class, () -> YuvPicture.builder().size(4, 4)
                    .chromaFormat(ChromaFormat.YUV420)
                    .color(ColorInfo.JPEG)
                    .luma(new byte[16], 4)
                    .chroma(new byte[3], new byte[4], 2)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> YuvPicture.builder().size(4, 4)
                    .chromaFormat(ChromaFormat.MONOCHROME)
                    .color(ColorInfo.JPEG)
                    .luma(new byte[16], 4)
                    .chroma(new byte[4], new byte[4], 2)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> RgbaPicture.allocate(0, 2, true));
        }
    }

    @Nested
    @DisplayName("Audio")
    class Audio {
        @Test
        @DisplayName("maps channel counts to WAVE layouts")
        void defaultLayouts() {
            assertEquals(ChannelLayout.MONO, ChannelLayout.defaultFor(1));
            assertEquals(ChannelLayout.STEREO, ChannelLayout.defaultFor(2));
            assertEquals(Speaker.LOW_FREQUENCY, ChannelLayout.defaultFor(6).speakers().get(3));
            assertEquals(Speaker.UNKNOWN, ChannelLayout.defaultFor(12).speakers().get(11));
            assertThrows(IllegalArgumentException.class, () -> ChannelLayout.defaultFor(0));
        }

        @Test
        @DisplayName("bounds the valid frame count by the capacity")
        void buffer() {
            var buffer = new AudioBuffer(ChannelLayout.STEREO, 48_000, 960);
            buffer.setFrames(480);
            assertEquals(10_000, buffer.durationMicros());
            assertThrows(IllegalArgumentException.class, () -> buffer.setFrames(961));
            assertThrows(IllegalArgumentException.class,
                    () -> new AudioBuffer(ChannelLayout.STEREO, 48_000, new float[][]{new float[4], new float[5]}, 0));
        }
    }

    @Nested
    @DisplayName("MediaLimits")
    class Limits {
        @Test
        @DisplayName("rejects oversized pictures, frame counts, channels and allocations before allocation")
        void checks() {
            var limits = MediaLimits.builder().maxDimension(100).maxPixels(5_000).maxFrames(10).maxChannels(2)
                    .maxBytes(1_000).build();
            LimitChecks.checkPicture(limits, 100, 50);
            assertThrows(MediaException.LimitExceeded.class, () -> LimitChecks.checkPicture(limits, 101, 1));
            assertThrows(MediaException.LimitExceeded.class, () -> LimitChecks.checkPicture(limits, 100, 51));
            assertThrows(MediaException.Malformed.class, () -> LimitChecks.checkPicture(limits, 0, 1));
            assertThrows(MediaException.LimitExceeded.class, () -> LimitChecks.checkFrames(limits, 11));
            assertThrows(MediaException.LimitExceeded.class, () -> LimitChecks.checkChannels(limits, 3));
            assertThrows(MediaException.LimitExceeded.class, () -> LimitChecks.checkAllocation(limits, 1_001));
            assertThrows(MediaException.Malformed.class, () -> LimitChecks.checkAllocation(limits, -1));
        }

        @Test
        @DisplayName("sets every bound through the builder and validates it")
        void builder() {
            var limits = MediaLimits.builder().maxDimension(10).maxPixels(20).maxFrames(30).maxChannels(4)
                    .maxBytes(50).build();
            assertEquals(4, limits.maxChannels());
            assertEquals(50, limits.maxBytes());
            assertEquals(MediaLimits.DEFAULT.maxPixels(), MediaLimits.builder().maxDimension(10).build().maxPixels());
            assertThrows(IllegalArgumentException.class, () -> MediaLimits.builder().maxFrames(0).build());
        }
    }

    @Nested
    @DisplayName("ColorInfo")
    class Colors {
        @Test
        @DisplayName("defaults to unspecified siting and no HDR metadata")
        void defaults() {
            var info = new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED, ColorPrimaries.BT709,
                    TransferCharacteristics.BT709);
            assertEquals(ColorInfo.BT709_LIMITED, info);
            assertEquals(ChromaSiting.UNSPECIFIED, info.chromaSiting());
            assertTrue(info.masteringDisplay().isEmpty());
            assertTrue(info.contentLightLevel().isEmpty());
        }

        @Test
        @DisplayName("keeps every other component through each wither")
        void withers() {
            var display = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 1000, 0.0001);
            var light = new ContentLightLevel(1000, 400);
            var info = ColorInfo.BT709_LIMITED.withChromaSiting(ChromaSiting.TOP_LEFT).withMasteringDisplay(display)
                    .withContentLightLevel(light).withPrimaries(ColorPrimaries.BT2020)
                    .withTransfer(TransferCharacteristics.PQ).withMatrix(MatrixCoefficients.BT2020_NCL)
                    .withRange(ColorRange.FULL);
            assertEquals(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.FULL, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ, ChromaSiting.TOP_LEFT, Optional.of(display), Optional.of(light)), info);
            assertTrue(info.withMasteringDisplay(null).masteringDisplay().isEmpty());
            assertTrue(info.withContentLightLevel(null).contentLightLevel().isEmpty());
        }

        @ParameterizedTest(name = "chroma_sample_loc_type {0} -> {1}")
        @CsvSource({"0, LEFT", "1, CENTER", "2, TOP_LEFT", "3, TOP", "4, BOTTOM_LEFT", "5, BOTTOM", "6, UNSPECIFIED",
                "-1, UNSPECIFIED"})
        @DisplayName("maps H.264 and H.265 chroma sample locations")
        void siting(int code, ChromaSiting expected) {
            assertEquals(expected, ChromaSiting.of(code));
            if (expected != ChromaSiting.UNSPECIFIED) {
                assertEquals(code, expected.chromaSampleLocType().orElseThrow());
            }
        }

        @Test
        @DisplayName("rejects invalid HDR metadata")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> new Chromaticity(-0.1, 0.3));
            assertThrows(IllegalArgumentException.class, () -> new Chromaticity(Double.NaN, 0.3));
            assertThrows(IllegalArgumentException.class, () -> new ContentLightLevel(70_000, 0));
            var white = new Chromaticity(0.3127, 0.329);
            assertThrows(IllegalArgumentException.class, () -> new MasteringDisplay(white, white, white, white, -1, 0));
        }
    }

    @Nested
    @DisplayName("Packet")
    class Packets {
        @Test
        @DisplayName("maps the keyframe constructor to the KEY flag without side data")
        void keyframe() {
            var key = new Packet(1, new byte[3], 0, 0, 10, true);
            assertTrue(key.keyframe());
            assertEquals(Set.of(PacketFlag.KEY), key.flags());
            assertSame(PacketSideData.NONE, key.sideData());
            var delta = new Packet(1, new byte[3], 0, 0, 10, false);
            assertFalse(delta.keyframe());
            assertTrue(delta.flags().isEmpty());
        }

        @Test
        @DisplayName("copies the flags into an unmodifiable set")
        void flags() {
            var flags = EnumSet.of(PacketFlag.KEY, PacketFlag.DISCARDABLE);
            var packet = new Packet(1, new byte[1], 0, 0, 0, flags, PacketSideData.NONE);
            flags.clear();
            assertEquals(Set.of(PacketFlag.KEY, PacketFlag.DISCARDABLE), packet.flags());
            assertThrows(UnsupportedOperationException.class, () -> packet.flags().add(PacketFlag.CORRUPT));
        }

        @Test
        @DisplayName("keeps flags and side data through every wither")
        void withers() {
            var side = PacketSideData.builder().discard(0, 5).hdr10Plus(new byte[]{1}).build();
            var packet = new Packet(1, new byte[2], 3, 3, 9, Set.of(PacketFlag.KEY, PacketFlag.DISCARD), side)
                    .withTrackId(2).withTiming(4, 2, 8).withData(new byte[4]);
            assertEquals(2, packet.trackId());
            assertEquals(4, packet.pts());
            assertEquals(2, packet.dts());
            assertEquals(8, packet.duration());
            assertEquals(4, packet.size());
            assertEquals(Set.of(PacketFlag.KEY, PacketFlag.DISCARD), packet.flags());
            assertSame(side, packet.sideData());
            assertFalse(packet.withFlags(Set.of()).keyframe());
        }

        @Test
        @DisplayName("builds side data with typed and generic entries compared by content")
        void sideData() {
            assertSame(PacketSideData.NONE, PacketSideData.builder().build());
            assertTrue(PacketSideData.NONE.isEmpty());
            var side = PacketSideData.builder()
                    .discard(10, 20)
                    .newCodecConfig(new byte[]{1, 2})
                    .hdr10Plus(new byte[]{3})
                    .extra("matroska:BlockAdditional:1", new byte[]{4})
                    .build();
            assertEquals(10, side.discardStart());
            assertEquals(20, side.discardEnd());
            assertArrayEquals(new byte[]{1, 2}, side.newCodecConfig().orElseThrow());
            assertArrayEquals(new byte[]{3}, side.hdr10Plus().orElseThrow());
            assertArrayEquals(new byte[]{4}, side.extra("matroska:BlockAdditional:1").orElseThrow());
            assertTrue(side.extra("other").isEmpty());
            var same = side.toBuilder().newCodecConfig(new byte[]{1, 2}).build();
            assertEquals(side, same);
            assertEquals(side.hashCode(), same.hashCode());
            assertNotEquals(side, side.toBuilder().discard(0, 20).build());
            assertTrue(side.toBuilder().extra("matroska:BlockAdditional:1", null).build().extra().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> side.extra().put("x", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> PacketSideData.builder().discard(-1, 0));
        }
    }

    @Nested
    @DisplayName("Tracks")
    class Tracks {
        @Test
        @DisplayName("builds video tracks with unknown defaults and round-trips through toBuilder")
        void video() {
            var track = VideoTrack.builder().codec(Codec.VP9).timeBase(new Rational(1, 30)).build();
            assertEquals(0, track.id());
            assertEquals(-1, track.duration());
            assertTrue(track.codecConfig().isEmpty());
            assertTrue(track.language().isEmpty());
            assertEquals(0, track.width());
            assertEquals(Orientation.NORMAL, track.orientation());
            assertTrue(track.frameRate().isEmpty());
            assertTrue(track.color().isEmpty());
            var full = track.toBuilder().id(3).duration(90).codecConfig(new byte[]{1}).language("ita").width(64)
                    .height(48).orientation(Orientation.ROTATE_90).frameRate(new Rational(30, 1))
                    .color(ColorInfo.BT709_LIMITED).build();
            assertEquals(full, full.toBuilder().codecConfig(new byte[]{1}).build());
            assertEquals(full.hashCode(), full.toBuilder().build().hashCode());
            assertEquals(Optional.of("ita"), full.language());
            assertEquals(Orientation.ROTATE_90, full.orientation());
            assertNotEquals(track, full);
        }

        @Test
        @DisplayName("validates the codec kind and the required properties")
        void validation() {
            assertThrows(IllegalArgumentException.class,
                    () -> VideoTrack.builder().codec(Codec.OPUS).timeBase(Rational.MICROSECONDS).build());
            assertThrows(NullPointerException.class, () -> VideoTrack.builder().codec(Codec.VP9).build());
            assertThrows(IllegalArgumentException.class,
                    () -> VideoTrack.builder().codec(Codec.VP9).timeBase(Rational.MICROSECONDS).width(-1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AudioTrack.builder().codec(Codec.VP9).timeBase(Rational.MICROSECONDS).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.MICROSECONDS).skipSamples(-1).build());
            assertThrows(NullPointerException.class, () -> AudioTrack.builder().timeBase(Rational.MICROSECONDS).build());
            assertThrows(IllegalArgumentException.class,
                    () -> DataTrack.builder().codec(Codec.OPUS).timeBase(Rational.MICROSECONDS).build());
        }

        @Test
        @DisplayName("builds audio and data tracks and round-trips them through toBuilder")
        void audioAndData() {
            var audio = AudioTrack.builder().id(2).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).duration(960)
                    .codecConfig(new byte[19]).sampleRate(48000).layout(ChannelLayout.STEREO).skipSamples(312).build();
            assertEquals(Optional.of(ChannelLayout.STEREO), audio.layout());
            assertEquals(312, audio.skipSamples());
            assertEquals(0, audio.bitsPerSample());
            assertEquals(audio, audio.toBuilder().build());
            assertNotEquals(audio, audio.toBuilder().layout(null).build());
            var data = DataTrack.builder().timeBase(Rational.MICROSECONDS).handler("meta").build();
            assertEquals(Codec.DATA, data.codec());
            assertEquals(Optional.of("meta"), data.handler());
            assertEquals(data, data.toBuilder().build());
        }
    }

    @Nested
    @DisplayName("High bit depth pictures")
    class HighBitDepth {
        @Test
        @DisplayName("fills a planar picture with black, neutral chroma and opaque alpha at its depth")
        void allocate() {
            var picture = Yuv16Picture.allocate(5, 3, ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
            assertEquals(10, picture.bitDepth());
            assertEquals(3, picture.chromaWidth());
            assertEquals(2, picture.chromaHeight());
            assertEquals(0, picture.y()[14]);
            assertEquals(512, picture.u()[5]);
            assertEquals(1023, picture.alpha()[14]);
            assertTrue(picture.hasAlpha());
            assertEquals(8, YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false, ColorInfo.JPEG).bitDepth());
            assertEquals(16, Rgba16Picture.allocate(2, 2, false).bitDepth());
        }

        @Test
        @DisplayName("wraps planes through the builder and shares them through toBuilder")
        void builder() {
            var y = new short[16];
            var u = new short[4];
            var v = new short[4];
            var picture = Yuv16Picture.builder().size(4, 4).chromaFormat(ChromaFormat.YUV420).bitDepth(12)
                    .color(ColorInfo.BT709_LIMITED).luma(y, 4).chroma(u, v, 2).build();
            assertSame(y, picture.y());
            var relabeled = picture.toBuilder().color(ColorInfo.BT601_LIMITED).build();
            assertSame(u, relabeled.u());
            assertEquals(12, relabeled.bitDepth());
            assertEquals(ColorInfo.BT601_LIMITED, relabeled.color());
            var yuv = YuvPicture.allocate(4, 4, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            var opaque = yuv.toBuilder().alpha(null, 0).build();
            assertFalse(opaque.hasAlpha());
            assertSame(yuv.y(), opaque.y());
        }

        @Test
        @DisplayName("rejects unsupported depths and short planes")
        void validation() {
            assertThrows(IllegalArgumentException.class,
                    () -> Yuv16Picture.allocate(2, 2, ChromaFormat.YUV420, 17, false, ColorInfo.JPEG));
            assertThrows(IllegalArgumentException.class,
                    () -> Yuv16Picture.allocate(2, 2, ChromaFormat.YUV420, 7, false, ColorInfo.JPEG));
            assertThrows(IllegalArgumentException.class, () -> Yuv16Picture.builder().size(4, 4)
                    .chromaFormat(ChromaFormat.MONOCHROME).bitDepth(10).color(ColorInfo.JPEG).luma(new short[15], 4)
                            .build());
            assertThrows(IllegalArgumentException.class, () -> Rgba16Picture.of(2, 2, 8, new short[15], true));
            assertThrows(IllegalArgumentException.class, () -> Rgba16Picture.of(2, 2, 7, new short[16], true));
        }
    }
}
