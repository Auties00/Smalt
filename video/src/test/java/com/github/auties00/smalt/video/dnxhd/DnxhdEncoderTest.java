package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes test pictures with every DNxHR family and the DNxHD HD rasters, muxes them into
 * QuickTime files and checks them with ffmpeg as an independent decoder: no decoding error, the
 * right sample entry and compression ID, the constant frame size of the compression ID, and a
 * PSNR against the source; Smalt's own decoder must reach at least the same PSNR.
 *
 * <p>4:4:4 streams follow ST 2019-1 clause 7.2.5, which ffmpeg reads the other way round: it
 * returns Smalt's Y'CbCr 4:4:4 streams (color format flag clear) as RGB whose G, B and R planes
 * are the Y', Cb and Cr samples, and Smalt's RGB streams (flag set) as Y'CbCr: the BT.709 Y'CbCr of
 * the default alternate color space macroblocks, or the R, G and B codes of RGB macroblocks. The
 * ffmpeg checks of those streams compare against exactly that relabeling.
 */
@DisplayName("DNxHD encoder")
class DnxhdEncoderTest {
    static final Rational FRAME_RATE = new Rational(25, 1);

    static Path mux(List<Packet> packets, DnxhdEncoder encoder, Path file) {
        try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.DNXHD).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(encoder.width()).height(encoder.height())
                    .frameRate(FRAME_RATE).build());
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(1));
            }
            muxer.finish();
        }
        return file;
    }

    static List<Packet> encode(List<Picture> pictures, DnxhdEncoder encoder) {
        var packets = new ArrayList<Packet>();
        var pts = 0L;
        for (var picture : pictures) {
            encoder.encode(new VideoFrame(picture, pts, 3600, true), packets::add);
            pts += 3600;
        }
        return packets;
    }

    static byte[] raw(List<Picture> pictures) {
        var out = new ByteArrayOutputStream();
        pictures.forEach(picture -> out.writeBytes(IntraTesting.raw(picture, false)));
        return out.toByteArray();
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("DNxHR LB", 640, 360, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB), 1274, 28.0),
                Arguments.of("DNxHR SQ", 640, 360, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_SQ), 1273, 33.0),
                Arguments.of("DNxHR HQ odd size", 642, 362, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ), 1272, 35.0),
                Arguments.of("DNxHR HQX 10-bit", 640, 360, "yuv422p10le", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX), 1271, 35.0),
                Arguments.of("DNxHR HQX 12-bit", 640, 360, "yuv422p12le", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX)
                        .toBuilder()
                        .bitDepth(12)
                        .build(), 1271, 35.0),
                Arguments.of("DNxHR 444 10-bit", 640, 360, "yuv444p10le", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444), 1270, 38.0),
                Arguments.of("DNxHR 444 12-bit", 640, 360, "yuv444p12le", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444)
                        .toBuilder()
                        .bitDepth(12)
                        .build(), 1270, 38.0),
                Arguments.of("DNxHD 1080p 8-bit", 1920, 1080, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                        .toBuilder()
                        .bitrate(120_000_000L)
                        .build(), 1237, 38.0),
                Arguments.of("DNxHD 1080p 10-bit", 1920, 1080, "yuv422p10le", DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                        .toBuilder()
                        .bitDepth(10)
                        .build(), 1235, 40.0),
                Arguments.of("DNxHD 720p", 1280, 720, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHD), 1251, 38.0),
                Arguments.of("DNxHD 1080i", 1920, 1080, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                        .toBuilder()
                        .fieldOrder(FieldOrder.TOP_FIRST)
                        .build(), 1243, 38.0),
                Arguments.of("DNxHD 1080i thin raster", 1440, 1080, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                        .toBuilder()
                        .fieldOrder(FieldOrder.TOP_FIRST).compressionId(1260)
                        .build(), 1260, 36.0),
                Arguments.of("DNxHD 960x720", 960, 720, "yuv422p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHD), 1258, 34.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("produces constant-size files ffmpeg decodes cleanly")
    void encode(String label, int width, int height, String format, DnxhdEncoderOptions options, int cid,
                double minimumPsnr, @TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = IntraTesting.sourceFrames(width, height, format, 2);
        var encoder = new DnxhdEncoder(width, height, options.toBuilder().frameRate(FRAME_RATE).build());
        assertEquals(cid, encoder.compressionId());
        var packets = encode(source, encoder);
        for (var packet : packets) {
            assertEquals(cid, (packet.data()[0x28] & 0xFF) << 24 | (packet.data()[0x29] & 0xFF) << 16
                    | (packet.data()[0x2A] & 0xFF) << 8 | packet.data()[0x2B] & 0xFF);
            assertEquals(packets.getFirst().size(), packet.size(), "constant frame size");
        }
        assertEquals(0, packets.getFirst().data()[0x2C] & 1, "CLF clear: Y'CbCr");
        var file = mux(packets, encoder, directory.resolve(cid + ".mov"));
        Oracle.assertDecodesCleanly(file);
        var probe = Oracle.probe(file);
        assertTrue(probe.contains(cid >= 1270 ? "\"codec_tag_string\": \"AVdh\"" : "\"codec_tag_string\": \"AVdn\""), probe);
        var expected = raw(source);
        var wide = format.endsWith("le");
        var peak = format.contains("12") ? 4095 : format.contains("10") ? 1023 : 255;
        // ffmpeg returns a 4:4:4 stream with a clear color format flag as gbrp, planes Y', Cb, Cr
        var ffmpeg = IntraTesting.ffmpegDecode(file, cid == 1270 ? format.replace("yuv444p", "gbrp") : format, 0);
        var ffmpegPsnr = IntraTesting.psnr(expected, ffmpeg, wide, peak);
        var decoder = new DnxhdDecoder();
        var ours = new ByteArrayOutputStream();
        for (var packet : packets) {
            decoder.decode(packet, frame -> ours.writeBytes(IntraTesting.raw(frame.picture(), false)));
        }
        var smaltPsnr = IntraTesting.psnr(expected, ours.toByteArray(), wide, peak);
        System.out.printf("%s: ffmpeg decode %.2f dB, Smalt decode %.2f dB, %d bytes per frame%n", label, ffmpegPsnr,
                smaltPsnr, packets.getFirst().size());
        assertTrue(ffmpegPsnr >= minimumPsnr, label + " ffmpeg PSNR " + ffmpegPsnr);
        assertTrue(smaltPsnr >= minimumPsnr, label + " Smalt PSNR " + smaltPsnr);
    }

    /**
     * Returns the alternate color space flag of the first macroblock of every scan line of a
     * single-unit frame.
     */
    static List<Integer> firstMacroblockFlags(byte[] data, int rows) {
        var headerSize = (data[0] & 0xFF) << 24 | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | data[3] & 0xFF;
        var flags = new ArrayList<Integer>();
        for (var row = 0; row < rows; row++) {
            var at = 0x170 + 4 * row;
            var offset = (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                    | data[at + 3] & 0xFF;
            var start = headerSize + offset;
            var header = (data[start] & 0xFF) << 4 | (data[start + 1] & 0xFF) >>> 4;
            flags.add(header & 1);
        }
        return flags;
    }

    static Rgba16Picture fullRange(Picture picture) {
        var rgba = (com.github.auties00.smalt.image.RgbaPicture) picture;
        var wide = new short[rgba.pixels().length];
        for (var i = 0; i < wide.length; i++) {
            wide[i] = (short) ((rgba.pixels()[i] & 0xFF) * 257);
        }
        return Rgba16Picture.of(rgba.width(), rgba.height(), rgba.width() * 4, wide, false);
    }

    @Test
    @DisplayName("codes RGB with DNxHR 444 as BT.709 Y'CbCr macroblocks by default: CLF and every ACF set")
    void rgbAsYcbcr(@TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = IntraTesting.sourceFrames(320, 240, "rgba", 1);
        var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().rgb(true).frameRate(FRAME_RATE)
                .build();
        assertTrue(options.alternateColorSpace());
        var encoder = new DnxhdEncoder(320, 240, options);
        var packets = encode(source, encoder);
        var data = packets.getFirst().data();
        assertEquals(1, data[0x2C] & 1, "CLF set: RGB");
        assertEquals(0, (data[0x2C] >>> 1) & 3, "CLV 00b: BT.709");
        assertEquals(java.util.Collections.nCopies(15, 1), firstMacroblockFlags(data, 15), "ACF set");
        var file = mux(packets, encoder, directory.resolve("ycbcr.mov"));
        Oracle.assertDecodesCleanly(file);
        var full = fullRange(source.getFirst());
        var expected = IntraTesting.raw(full, false);
        // ffmpeg returns the Y'CbCr macroblocks unconverted, as 4:4:4 Y'CbCr
        var ffmpeg = DnxhdDifferentialTest.bt709ToRgb(IntraTesting.ffmpegDecode(file, "yuv444p10le", 0), 320, 240);
        var ffmpegPsnr = IntraTesting.psnr(expected, ffmpeg, true, 65535);
        var ours = new ByteArrayOutputStream();
        new DnxhdDecoder().decode(packets.getFirst(), frame -> {
            assertTrue(frame.picture() instanceof Rgba16Picture);
            ours.writeBytes(IntraTesting.raw(frame.picture(), false));
        });
        var smaltPsnr = IntraTesting.psnr(expected, ours.toByteArray(), true, 65535);
        System.out.printf("DNxHR 444 RGB as Y'CbCr: ffmpeg decode converted by BT.709 %.2f dB, Smalt decode %.2f dB%n",
                ffmpegPsnr, smaltPsnr);
        assertTrue(ffmpegPsnr > 40, "ffmpeg PSNR " + ffmpegPsnr);
        assertTrue(smaltPsnr > 40, "Smalt PSNR " + smaltPsnr);
    }

    @Test
    @DisplayName("codes RGB with DNxHR 444 as RGB macroblocks on request: CLF set, ACF clear, channels R, G, B at the BT.709 levels")
    void rgb(@TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = IntraTesting.sourceFrames(320, 240, "rgba", 1);
        var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().rgb(true)
                .alternateColorSpace(false).frameRate(FRAME_RATE).build());
        var packets = encode(source, encoder);
        var data = packets.getFirst().data();
        assertEquals(1, data[0x2C] & 1, "CLF set: RGB");
        assertEquals(0, (data[0x2C] >>> 1) & 3, "CLV 00b: BT.709");
        assertEquals(java.util.Collections.nCopies(15, 0), firstMacroblockFlags(data, 15), "ACF clear");
        var file = mux(packets, encoder, directory.resolve("rgb.mov"));
        // ffmpeg logs "Unsupported: variable ACT flag" for RGB macroblocks under a set flag, but decodes
        Oracle.assertDecodesCleanly(file);
        var full = fullRange(source.getFirst());
        var wide = full.pixels();
        // known divergence: ffmpeg returns the R, G, B codes (64 to 940) as Y', Cb, Cr, unconverted
        var codes = new java.io.ByteArrayOutputStream();
        for (var channel = 0; channel < 3; channel++) {
            for (var i = channel; i < wide.length; i += 4) {
                var code = 64 + (int) Math.round((wide[i] & 0xFFFF) * 876.0 / 65535);
                codes.write(code & 0xFF);
                codes.write(code >>> 8);
            }
        }
        var ffmpeg = IntraTesting.ffmpegDecode(file, "yuv444p10le", 0);
        var ffmpegPsnr = IntraTesting.psnr(codes.toByteArray(), ffmpeg, true, 1023);
        var ours = new ByteArrayOutputStream();
        new DnxhdDecoder().decode(packets.getFirst(), frame -> {
            assertTrue(frame.picture() instanceof Rgba16Picture);
            ours.writeBytes(IntraTesting.raw(frame.picture(), false));
        });
        var smaltPsnr = IntraTesting.psnr(IntraTesting.raw(full, false), ours.toByteArray(), true, 65535);
        System.out.printf("DNxHR 444 RGB: ffmpeg's Y'CbCr planes against the R, G, B codes %.2f dB, Smalt decode %.2f dB%n",
                ffmpegPsnr, smaltPsnr);
        assertTrue(ffmpegPsnr > 40, "ffmpeg PSNR " + ffmpegPsnr);
        assertTrue(smaltPsnr > 40, "Smalt PSNR " + smaltPsnr);
    }

    static Stream<Arguments> chroma420() {
        return Stream.of(
                Arguments.of("DNxHR HQ", "yuv420p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ), 38.0),
                Arguments.of("DNxHR LB", "yuv420p", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB), 30.0),
                Arguments.of("DNxHR HQX 10-bit", "yuv420p10le", DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQX), 38.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chroma420")
    @DisplayName("codes 4:2:0 DNxHR in frames of the 4:2:2 size")
    void yuv420(String label, String format, DnxhdEncoderOptions options, double minimumPsnr) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = IntraTesting.sourceFrames(640, 360, format, 1);
        var packet = encode(source, new DnxhdEncoder(640, 360, options.toBuilder().chromaFormat(ChromaFormat.YUV420)
                .build())).getFirst();
        var reference = encode(source, new DnxhdEncoder(640, 360, options)).getFirst();
        assertEquals(1, (packet.data()[0x2C] >>> 5) & 3, "SSC 01b: 4:2:0");
        assertEquals(reference.size(), packet.size(), "4:2:0 frames take the size of the compression ID");
        var decoded = new DnxhdDecoder().decodeFrame(packet.data(), 0, packet.size());
        assertEquals(ChromaFormat.YUV420, decoded instanceof YuvPicture narrow ? narrow.chromaFormat()
                : ((com.github.auties00.smalt.image.Yuv16Picture) decoded).chromaFormat());
        var wide = format.endsWith("le");
        var psnr = IntraTesting.psnr(raw(source), IntraTesting.raw(decoded, false), wide, wide ? 1023 : 255);
        System.out.printf("%s 4:2:0: Smalt decode %.2f dB%n", label, psnr);
        assertTrue(psnr >= minimumPsnr, label + " PSNR " + psnr);
    }

    @Test
    @DisplayName("codes moving interlaced 1440x1080 with field macroblocks under ID 1260")
    void adaptiveFieldMacroblocks(@TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        // a fast pan whose fields, 1/50 s apart, are woven into each frame, so every edge combs
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=2880x1080:rate=50", "-vf",
                "crop=1440:1080:x='t*900':y=0,tinterlace=mode=interleave_top",
                "-frames:v", "2", "-f", "rawvideo", "-pix_fmt", "yuv422p", "-"));
        var pictures = new ArrayList<Picture>();
        var frameSize = 1440 * 1080 * 2;
        for (var f = 0; f * frameSize < raw.length; f++) {
            var at = f * frameSize;
            pictures.add(YuvPicture.builder().size(1440, 1080).chromaFormat(ChromaFormat.YUV422)
                    .color(ColorInfo.BT709_LIMITED)
                    .luma(java.util.Arrays.copyOfRange(raw, at, at + 1440 * 1080), 1440)
                    .chroma(java.util.Arrays.copyOfRange(raw, at + 1440 * 1080, at + 1440 * 1080 * 3 / 2),
                            java.util.Arrays.copyOfRange(raw, at + 1440 * 1080 * 3 / 2, at + frameSize), 720).build());
        }
        var encoder = new DnxhdEncoder(1440, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                .toBuilder()
                .fieldOrder(FieldOrder.TOP_FIRST).compressionId(1260).frameRate(FRAME_RATE)
                .build());
        var packets = encode(pictures, encoder);
        var data = packets.getFirst().data();
        var fieldRows = 0;
        for (var row = 0; row < 68; row++) {
            var at = 0x280 + ((data[0x170 + 4 * row] & 0xFF) << 24 | (data[0x171 + 4 * row] & 0xFF) << 16
                    | (data[0x172 + 4 * row] & 0xFF) << 8 | data[0x173 + 4 * row] & 0xFF);
            fieldRows += (data[at] & 0x80) != 0 ? 1 : 0;
        }
        assertTrue(fieldRows > 0, "some scan lines start with a field macroblock");
        var file = mux(packets, encoder, directory.resolve("1260.mov"));
        Oracle.assertDecodesCleanly(file);
        var expected = raw(pictures);
        var ffmpegPsnr = IntraTesting.psnr(expected, IntraTesting.ffmpegDecode(file, "yuv422p", 0), false, 255);
        var ours = new ByteArrayOutputStream();
        var decoder = new DnxhdDecoder();
        for (var packet : packets) {
            decoder.decode(packet, frame -> ours.writeBytes(IntraTesting.raw(frame.picture(), false)));
        }
        var smaltPsnr = IntraTesting.psnr(expected, ours.toByteArray(), false, 255);
        System.out.printf("ID 1260 interlaced: %d of 68 scan lines start in field mode, ffmpeg %.2f dB, Smalt %.2f dB%n",
                fieldRows, ffmpegPsnr, smaltPsnr);
        assertTrue(ffmpegPsnr >= 36, "ffmpeg PSNR " + ffmpegPsnr);
        assertTrue(smaltPsnr >= 36, "Smalt PSNR " + smaltPsnr);
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        @Test
        @DisplayName("rejects 4:2:0 and alpha where the compression ID does not permit them")
        void permitted() {
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444)
                    .toBuilder()
                    .chromaFormat(ChromaFormat.YUV420)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                    .toBuilder()
                    .chromaFormat(ChromaFormat.YUV420)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 481, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)
                    .toBuilder()
                    .chromaFormat(ChromaFormat.YUV420)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB)
                    .toBuilder()
                    .alpha(true)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)
                    .toBuilder()
                    .compressionId(1253).alpha(true)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)
                    .toBuilder()
                    .alpha(true).losslessAlpha(true)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.builder().losslessAlpha(true)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> DnxhdEncoderOptions.builder()
                    .chromaFormat(ChromaFormat.MONOCHROME)
                    .build());
        }

        @Test
        @DisplayName("carries the alternate color space choice through builders, on by default")
        void alternateColorSpace() {
            var defaults = DnxhdEncoderOptions.defaults();
            assertTrue(defaults.alternateColorSpace());
            var off = defaults.toBuilder().alternateColorSpace(false).build();
            assertFalse(off.alternateColorSpace());
            assertEquals(off, defaults.toBuilder().alternateColorSpace(false).build());
            assertEquals(defaults, off.toBuilder().alternateColorSpace(true).build());
            assertTrue(off.toString().contains("alternateColorSpace=false"));
        }

        @Test
        @DisplayName("ignores the alternate color space choice for Y'CbCr streams")
        void alternateColorSpaceWithoutRgb() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = IntraTesting.sourceFrames(64, 48, "yuv444p10le", 1);
            var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().threads(1).build();
            var on = encode(source, new DnxhdEncoder(64, 48, options)).getFirst().data();
            var off = encode(source, new DnxhdEncoder(64, 48, options.toBuilder().alternateColorSpace(false).build()))
                    .getFirst().data();
            org.junit.jupiter.api.Assertions.assertArrayEquals(on, off);
        }

        @Test
        @DisplayName("rejects pictures no DNxHD compression ID codes")
        void noCompressionId() {
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD)));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)
                    .toBuilder()
                    .fieldOrder(FieldOrder.TOP_FIRST)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_LB)
                    .toBuilder()
                    .bitDepth(10)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new DnxhdEncoder(640, 480, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)
                    .toBuilder()
                    .rgb(true)
                    .build()));
        }

        @Test
        @DisplayName("picks the DNxHD compression ID closest to the bitrate")
        void bitrate() {
            var low = new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder()
                    .frameRate(FRAME_RATE)
                    .bitrate(36_000_000L).build());
            var high = new DnxhdEncoder(1920, 1080, DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder()
                    .frameRate(FRAME_RATE)
                    .bitrate(185_000_000L).build());
            assertEquals(1253, low.compressionId());
            assertEquals(1238, high.compressionId());
        }
    }
}
