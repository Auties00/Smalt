package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public behavior of {@link HevcDecoder}: registration, packet formats (Annex B and
 * length-prefixed with an {@code hvcC} record), MP4 and Matroska tracks, single-picture decoding for
 * HEIF items, high bit depth output, timestamps, color metadata, unsupported features and the
 * options type.
 */
@DisplayName("HEVC decoder")
class HevcDecoderTest {
    private static Path x265(String extension, String size, double seconds, String pixelFormat, String params,
                             List<String> extra) {
        var output = new ArrayList<>(List.of("-pix_fmt", pixelFormat, "-c:v", "libx265", "-x265-params",
                "log-level=error" + (params.isEmpty() ? "" : ":" + params)));
        output.addAll(extra);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i",
                "testsrc2=size=" + size + ":rate=25:duration=" + seconds), output);
    }

    private static byte[] decodeAll(byte[] stream, HevcDecoderOptions options, List<VideoFrame> frames) {
        var raw = new ByteArrayOutputStream();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            java.util.function.Consumer<VideoFrame> sink = frame -> {
                raw.writeBytes(HevcTestStreams.raw(frame.picture()));
                if (frames != null) {
                    frames.add(frame);
                }
            };
            for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return raw.toByteArray();
    }

    @Nested
    @DisplayName("registration")
    class Registration {
        @Test
        @DisplayName("is found by the video decoder registry")
        void registry() {
            assertTrue(VideoDecoders.isSupported(Codec.HEVC));
            try (var decoder = VideoDecoders.create(HevcTestStreams.track())) {
                assertInstanceOf(HevcDecoder.class, decoder);
            }
        }

        @Test
        @DisplayName("rejects tracks of other codecs")
        void otherCodec() {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
            assertThrows(IllegalArgumentException.class, () -> new HevcDecoder(track));
        }

        @Test
        @DisplayName("hands out 16-bit frames when the registry options ask for high bit depth")
        void registryHighBitDepth() {
            var stream = Fixtures.read(x265("hevc", "64x48", 0.12, "yuv420p10le", "", List.of("-f", "hevc")));
            var options = VideoDecoderOptions.builder().highBitDepth(true).build();
            var pictures = new ArrayList<Object>();
            try (var decoder = VideoDecoders.create(HevcTestStreams.track(), options)) {
                for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                    decoder.decode(HevcTestStreams.packet(nal), frame -> pictures.add(frame.picture().getClass()));
                }
                decoder.flush(frame -> pictures.add(frame.picture().getClass()));
            }
            assertFalse(pictures.isEmpty());
            assertTrue(pictures.stream().allMatch(type -> type == Yuv16Picture.class));
        }
    }

    @Nested
    @DisplayName("packet formats and containers")
    class Containers1 {
        @Test
        @DisplayName("decodes length-prefixed access units with an hvcC record like Annex B")
        void lengthPrefixed() {
            var stream = Fixtures.read(x265("hevc", "96x64", 0.4, "yuv420p", "bframes=3", List.of("-f", "hevc")));
            var expected = decodeAll(stream, HevcDecoderOptions.defaults(), null);
            var nals = HevcTestStreams.nalUnits(stream);
            var parameterSets = nals.stream().filter(nal -> HevcTestStreams.type(nal) >= 32 && HevcTestStreams.type(nal) <= 34).toList();
            var config = HevcTestStreams.hvcC(parameterSets);
            var raw = new ByteArrayOutputStream();
            try (var decoder = new HevcDecoder(HevcTestStreams.track(config))) {
                for (var unit : HevcTestStreams.accessUnits(nals)) {
                    var vcl = unit.stream().filter(nal -> HevcTestStreams.type(nal) < 32 || HevcTestStreams.type(nal) > 34).toList();
                    decoder.decode(HevcTestStreams.packet(HevcTestStreams.lengthPrefixed(vcl)),
                            frame -> raw.writeBytes(HevcTestStreams.raw(frame.picture())));
                }
                decoder.flush(frame -> raw.writeBytes(HevcTestStreams.raw(frame.picture())));
            }
            assertArrayEquals(expected, raw.toByteArray());
        }

        @Test
        @DisplayName("decodes an MP4 hvc1 track through the container and video reader like ffmpeg")
        void mp4() throws Exception {
            var file = x265("mp4", "128x96", 0.6, "yuv420p", "bframes=4", List.of("-tag:v", "hvc1"));
            var expected = Oracle.decodeVideo(file, "yuv420p", 0);
            var raw = new ByteArrayOutputStream();
            try (var reader = VideoReader.open(MediaSource.of(file))) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    raw.writeBytes(HevcTestStreams.raw(frame.picture()));
                }
            }
            assertArrayEquals(expected, raw.toByteArray());
        }

        @Test
        @DisplayName("decodes a Matroska V_MPEGH/ISO/HEVC track like ffmpeg")
        void matroska() throws Exception {
            var file = x265("mkv", "112x80", 0.6, "yuv420p", "bframes=2", List.of());
            var expected = Oracle.decodeVideo(file, "yuv420p", 0);
            var raw = new ByteArrayOutputStream();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.HEVC, track.codec());
                try (var decoder = VideoDecoders.create(track)) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        decoder.decode(packet, frame -> raw.writeBytes(HevcTestStreams.raw(frame.picture())));
                    }
                    decoder.flush(frame -> raw.writeBytes(HevcTestStreams.raw(frame.picture())));
                }
            }
            assertArrayEquals(expected, raw.toByteArray());
        }

        @Test
        @DisplayName("carries packet timestamps and marks IRAP pictures as keyframes")
        void timestamps() throws Exception {
            var file = x265("mp4", "64x48", 1.2, "yuv420p", "bframes=3:keyint=10:min-keyint=10", List.of("-tag:v", "hvc1"));
            var pts = new ArrayList<Long>();
            var keys = new ArrayList<Boolean>();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                try (var decoder = new HevcDecoder(track)) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        decoder.decode(packet, frame -> {
                            pts.add(frame.pts());
                            keys.add(frame.keyframe());
                        });
                    }
                    decoder.flush(frame -> {
                        pts.add(frame.pts());
                        keys.add(frame.keyframe());
                    });
                }
            }
            assertEquals(30, pts.size());
            for (var i = 1; i < pts.size(); i++) {
                assertTrue(pts.get(i) > pts.get(i - 1), "presentation order at " + i);
            }
            assertTrue(keys.getFirst());
            assertTrue(keys.get(10));
            assertFalse(keys.get(1));
        }
    }

    @Nested
    @DisplayName("single pictures")
    class Stills {
        private static List<VideoFrame> decodeStill(byte[] configuration, byte[] data, HevcDecoderOptions options) {
            var track = configuration.length == 0 ? HevcTestStreams.track() : HevcTestStreams.track(configuration);
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new HevcDecoder(track, options.toBuilder().threads(1).build())) {
                decoder.decode(new Packet(1, data, 0, 0, 0, true), frames::add);
                decoder.flush(frames::add);
            }
            return frames;
        }

        @Test
        @DisplayName("decodes one intra picture from an hvcC record and length-prefixed data")
        void intraPicture() {
            var stream = Fixtures.read(x265("hevc", "120x80", 0.04, "yuv420p", "keyint=1", List.of("-frames:v", "1", "-f", "hevc")));
            var nals = HevcTestStreams.nalUnits(stream);
            var config = HevcTestStreams.hvcC(nals.stream().filter(nal -> HevcTestStreams.type(nal) >= 32 && HevcTestStreams.type(nal) <= 34).toList());
            var data = HevcTestStreams.lengthPrefixed(nals.stream().filter(nal -> HevcTestStreams.type(nal) < 32).toList());
            var frames = decodeStill(config, data, HevcDecoderOptions.defaults());
            assertEquals(1, frames.size());
            var picture = assertInstanceOf(YuvPicture.class, frames.getFirst().picture());
            assertEquals(120, picture.width());
            assertEquals(80, picture.height());
            assertArrayEquals(decodeAll(stream, HevcDecoderOptions.defaults(), null), HevcTestStreams.raw(picture));
        }

        @Test
        @DisplayName("decodes a 10-bit still at its depth on request")
        void decodePictureHighBitDepth() {
            var stream = Fixtures.read(x265("hevc", "64x64", 0.04, "yuv420p10le", "keyint=1", List.of("-frames:v", "1", "-f", "hevc")));
            var frames = decodeStill(new byte[0], stream, HevcDecoderOptions.builder().highBitDepth(true).build());
            var wide = assertInstanceOf(Yuv16Picture.class, frames.getFirst().picture());
            assertEquals(10, wide.bitDepth());
        }

        @Test
        @DisplayName("outputs nothing for data without a picture")
        void noPicture() {
            assertTrue(decodeStill(new byte[0], new byte[]{0, 0, 1, 0x40, 1, 0x0c}, HevcDecoderOptions.defaults()).isEmpty());
        }
    }

    @Nested
    @DisplayName("bit depth and color")
    class Color {
        @Test
        @DisplayName("rounds 10-bit samples to 8 bits exactly like ImageOps.toEightBit")
        void eightBitRounding() {
            var stream = Fixtures.read(x265("hevc", "96x48", 0.2, "yuv420p10le", "", List.of("-f", "hevc")));
            var wide = new ArrayList<VideoFrame>();
            var narrow = new ArrayList<VideoFrame>();
            var wideRaw = new ArrayList<byte[]>();
            try (var decoder = new HevcDecoder(HevcTestStreams.track(), HevcDecoderOptions.builder().highBitDepth(true).build())) {
                for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                    decoder.decode(HevcTestStreams.packet(nal), frame -> wideRaw.add(HevcTestStreams.raw(ImageOps.toEightBit((Yuv16Picture) frame.picture()))));
                }
                decoder.flush(frame -> wideRaw.add(HevcTestStreams.raw(ImageOps.toEightBit((Yuv16Picture) frame.picture()))));
            }
            var narrowRaw = decodeAll(stream, HevcDecoderOptions.defaults(), narrow);
            var joined = new ByteArrayOutputStream();
            wideRaw.forEach(joined::writeBytes);
            assertArrayEquals(joined.toByteArray(), narrowRaw);
            assertTrue(narrow.stream().allMatch(frame -> frame.picture() instanceof YuvPicture));
        }

        @Test
        @DisplayName("reports the VUI color description and the HDR SEI metadata")
        void hdrMetadata() {
            var stream = Fixtures.read(x265("hevc", "64x48", 0.12, "yuv420p10le",
                    "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=full"
                            + ":master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)"
                            + ":max-cll=1000,400",
                    List.of("-f", "hevc")));
            var frames = new ArrayList<VideoFrame>();
            decodeAll(stream, HevcDecoderOptions.defaults(), frames);
            var color = ((com.github.auties00.smalt.image.YuvPicture) frames.getFirst().picture()).color();
            assertEquals(ColorPrimaries.BT2020, color.primaries());
            assertEquals(TransferCharacteristics.PQ, color.transfer());
            assertEquals(MatrixCoefficients.BT2020_NCL, color.matrix());
            assertEquals(ColorRange.FULL, color.range());
            var display = color.masteringDisplay().orElseThrow();
            assertEquals(0.68, display.red().x(), 1e-9);
            assertEquals(0.265, display.green().x(), 1e-9);
            assertEquals(1000.0, display.maxLuminance(), 1e-9);
            assertEquals(0.0001, display.minLuminance(), 1e-12);
            var light = color.contentLightLevel().orElseThrow();
            assertEquals(1000, light.maxContentLightLevel());
            assertEquals(400, light.maxFrameAverageLightLevel());
        }

        @Test
        @DisplayName("defaults unspecified color to BT.601 limited range for small pictures")
        void defaults() {
            var stream = Fixtures.read(x265("hevc", "64x48", 0.08, "yuv420p", "", List.of("-f", "hevc")));
            var frames = new ArrayList<VideoFrame>();
            decodeAll(stream, HevcDecoderOptions.defaults(), frames);
            var color = ((com.github.auties00.smalt.image.YuvPicture) frames.getFirst().picture()).color();
            assertEquals(ColorRange.LIMITED, color.range());
            assertEquals(MatrixCoefficients.BT601, color.matrix());
        }

        @Test
        @DisplayName("decodes monochrome video")
        void monochrome() {
            var file = x265("hevc", "64x48", 0.2, "gray", "", List.of("-f", "hevc"));
            var frames = new ArrayList<VideoFrame>();
            var raw = decodeAll(Fixtures.read(file), HevcDecoderOptions.defaults(), frames);
            assertEquals(ChromaFormat.MONOCHROME, ((YuvPicture) frames.getFirst().picture()).chromaFormat());
            assertArrayEquals(Oracle.decodeVideo(file, "gray", 0), raw);
        }
    }

    @Nested
    @DisplayName("range extension formats")
    class RangeExtensions {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"yuv422p, YUV422", "yuv444p, YUV444"})
        @DisplayName("hands out 8-bit pictures in the stream's chroma format")
        void chromaFormats(String pixelFormat, ChromaFormat format) {
            var file = x265("hevc", "64x48", 0.2, pixelFormat, "", List.of("-f", "hevc"));
            var frames = new ArrayList<VideoFrame>();
            var raw = decodeAll(Fixtures.read(file), HevcDecoderOptions.defaults(), frames);
            assertEquals(format, ((YuvPicture) frames.getFirst().picture()).chromaFormat());
            assertArrayEquals(Oracle.decodeVideo(file, pixelFormat, 0), raw);
        }

        @Test
        @DisplayName("rounds 10-bit 4:2:2 samples to 8 bits unless high bit depth is requested")
        void deep422() {
            var file = x265("hevc", "64x48", 0.2, "yuv422p10le", "", List.of("-f", "hevc"));
            var narrow = new ArrayList<VideoFrame>();
            decodeAll(Fixtures.read(file), HevcDecoderOptions.defaults(), narrow);
            var picture = (YuvPicture) narrow.getFirst().picture();
            assertEquals(ChromaFormat.YUV422, picture.chromaFormat());
            assertEquals(8, picture.bitDepth());
            var wide = new ArrayList<VideoFrame>();
            var raw = decodeAll(Fixtures.read(file), HevcDecoderOptions.builder().highBitDepth(true).build(), wide);
            var deep = (Yuv16Picture) wide.getFirst().picture();
            assertEquals(ChromaFormat.YUV422, deep.chromaFormat());
            assertEquals(10, deep.bitDepth());
            assertArrayEquals(Oracle.decodeVideo(file, "yuv422p10le", 0), raw);
        }
    }

    @Nested
    @DisplayName("limits and lifecycle")
    class Lifecycle {
        @Test
        @DisplayName("rejects pictures larger than the limits before allocating them")
        void limits() {
            var stream = Fixtures.read(x265("hevc", "128x96", 0.04, "yuv420p", "", List.of("-f", "hevc")));
            var options = HevcDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(64 * 64).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> decodeAll(stream, options, null));
        }

        @Test
        @DisplayName("refuses calls after close")
        void closed() {
            var decoder = new HevcDecoder(HevcTestStreams.track());
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.decode(HevcTestStreams.packet(new byte[0]), frame -> {
            }));
        }

        @Test
        @DisplayName("passes an exception of the frame consumer through unchanged")
        void consumerFailure() {
            var stream = Fixtures.read(x265("hevc", "128x96", 0.2, "yuv420p", "", List.of("-f", "hevc")));
            var failure = new IllegalStateException("consumer");
            var thrown = assertThrows(IllegalStateException.class, () -> {
                try (var decoder = new HevcDecoder(HevcTestStreams.track())) {
                    for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                        decoder.decode(HevcTestStreams.packet(nal), frame -> {
                            throw failure;
                        });
                    }
                    decoder.flush(frame -> {
                        throw failure;
                    });
                }
            });
            assertSame(failure, thrown);
        }

        @Test
        @DisplayName("ignores NAL units of the reserved VCL types")
        void reservedVclTypes() {
            var stream = Fixtures.read(x265("hevc", "128x96", 0.2, "yuv420p", "", List.of("-f", "hevc")));
            var expected = decodeAll(stream, HevcDecoderOptions.defaults(), null);
            var padded = new ByteArrayOutputStream();
            for (var nal : HevcTestStreams.nalUnits(stream)) {
                padded.writeBytes(new byte[]{0, 0, 0, 1});
                padded.writeBytes(nal);
                if (HevcTestStreams.type(nal) < 32) {
                    for (var type : new int[]{10, 22, 24, 31}) {
                        padded.writeBytes(new byte[]{0, 0, 0, 1, (byte) (type << 1), 1, (byte) 0xAF, 0x12, (byte) 0x80});
                    }
                }
            }
            assertArrayEquals(expected, decodeAll(padded.toByteArray(), HevcDecoderOptions.defaults(), null));
        }

        @Test
        @DisplayName("ignores empty packets and stray bytes")
        void empty() {
            try (var decoder = new HevcDecoder(HevcTestStreams.track())) {
                decoder.decode(new Packet(1, new byte[0], 0, 0, 0, false), frame -> {
                });
                decoder.decode(new Packet(1, new byte[]{1, 2, 3}, 0, 0, 0, false), frame -> {
                });
                decoder.flush(frame -> {
                });
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("follow the options convention")
        void convention() {
            var defaults = HevcDecoderOptions.defaults();
            assertEquals(MediaLimits.DEFAULT, defaults.limits());
            assertEquals(0, defaults.threads());
            assertFalse(defaults.highBitDepth());
            assertFalse(defaults.hashVerification());
            var changed = defaults.toBuilder().threads(3).highBitDepth(true).hashVerification(true).build();
            assertEquals(3, changed.threads());
            assertTrue(changed.highBitDepth());
            assertTrue(changed.hashVerification());
            assertNotEquals(defaults, changed);
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed, HevcDecoderOptions.builder().threads(3).highBitDepth(true).hashVerification(true).build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().threads(-1).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().limits(null).build());
        }
    }
}
