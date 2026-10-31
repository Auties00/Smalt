package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes test patterns in every {@link DvProfile} and checks the frames against ffmpeg: its decoder reads them as
 * Smalt's does, ffprobe reports the format their header and VAUX sections name, and their quality is close to that of
 * ffmpeg's own dvvideo encoder on the same pictures. Also checks the packet structure, the independence of the output
 * from the thread count and the choice of the DCT mode.
 */
@DisplayName("DV encoder")
class DvEncoderTest {
    private static final int FRAMES = 3;

    /** Two decoders of one stream may round the non-normative inverse DCT differently. */
    private static final Tolerance CONFORMANCE = DvDifferentialTest.TOLERANCE;

    private static final Map<DvProfile, List<Picture>> SOURCES = new HashMap<>();

    private static synchronized List<Picture> source(DvProfile profile) {
        return SOURCES.computeIfAbsent(profile, key -> DvTesting.sourceFrames(key, FRAMES));
    }

    private static Path write(String name, byte[] data) {
        try {
            var file = Scratch.directory("dv").resolve(name + ".dv");
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] decode(List<Packet> packets, DvProfile profile, String pixelFormat) {
        var out = new ByteArrayOutputStream();
        var decoder = new DvDecoder();
        for (var packet : packets) {
            decoder.decode(packet, frame -> out.writeBytes(DvTesting.raw(frame.picture(), pixelFormat)));
        }
        return out.toByteArray();
    }

    private static byte[] decodedLuma(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        var decoder = new DvDecoder();
        for (var packet : packets) {
            decoder.decode(packet, frame -> out.writeBytes(DvTesting.luma(frame.picture())));
        }
        return out.toByteArray();
    }

    private static byte[] sourceLuma(List<Picture> pictures) {
        var out = new ByteArrayOutputStream();
        for (var picture : pictures) {
            out.writeBytes(DvTesting.luma(picture));
        }
        return out.toByteArray();
    }

    /** Encodes the source pictures with ffmpeg's dvvideo encoder and returns its raw DIF stream. */
    private static byte[] ffmpegEncode(DvProfile profile, List<Picture> pictures) {
        var raw = new ByteArrayOutputStream();
        var pixelFormat = profile == DvProfile.DV_625 ? "yuv420p" : "yuv422p";
        for (var picture : pictures) {
            raw.writeBytes(DvTesting.raw(picture, pixelFormat));
        }
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), raw.toByteArray(), List.of("-v", "error",
                "-f", "rawvideo", "-pix_fmt", pixelFormat, "-s", DvTesting.size(profile), "-r", DvTesting.rate(profile),
                "-i", "-", "-c:v", "dvvideo", "-pix_fmt", DvTesting.pixelFormat(profile), "-f", "dv", "-"));
    }

    @ParameterizedTest
    @EnumSource(DvProfile.class)
    @DisplayName("writes frames that ffmpeg decodes as Smalt does, at a quality close to ffmpeg's encoder")
    void ffmpegDecodes(DvProfile profile) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var pictures = source(profile);
        var packets = DvTesting.encode(DvEncoderOptions.of(profile), pictures);
        assertEquals(FRAMES, packets.size());
        var file = write(profile.name(), DvTesting.concatenate(packets));
        var pixelFormat = DvTesting.pixelFormat(profile);
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        var actual = decode(packets, profile, pixelFormat);
        assertEquals(expected.length, actual.length, "ffmpeg decoded a different number of frames");
        CONFORMANCE.check(profile.name(), IntraTesting.difference(expected, actual, false, 255));

        var original = sourceLuma(pictures);
        var ours = IntraTesting.psnr(original, decodedLuma(packets), false, 255);
        var reference = IntraTesting.psnr(original, decodedLuma(DvTesting.split(ffmpegEncode(profile, pictures),
                profile)), false, 255);
        System.out.printf("%s: luma PSNR %.2f dB, ffmpeg dvvideo %.2f dB%n", profile, ours, reference);
        assertTrue(ours >= 30, profile + " luma PSNR " + ours);
        assertTrue(ours >= reference - 2, profile + " luma PSNR " + ours + " against ffmpeg's " + reference);
    }

    @ParameterizedTest
    @EnumSource(value = DvProfile.class, names = {"DV_525", "DV_625", "DVCPRO50_625", "DVCPRO_HD_1080I60",
            "DVCPRO_HD_720P50"})
    @DisplayName("writes noisy pictures, whose blocks overflow into the free space, as ffmpeg decodes them")
    void noisy(DvProfile profile) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var pictures = DvTesting.sourceFrames(profile, 2, true);
        var packets = DvTesting.encode(DvEncoderOptions.of(profile), pictures);
        var file = write(profile.name() + "-noisy", DvTesting.concatenate(packets));
        var pixelFormat = DvTesting.pixelFormat(profile);
        var expected = IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        var actual = decode(packets, profile, pixelFormat);
        assertEquals(expected.length, actual.length);
        CONFORMANCE.check(profile.name() + " noisy", IntraTesting.difference(expected, actual, false, 255));
        var original = sourceLuma(pictures);
        var ours = IntraTesting.psnr(original, decodedLuma(packets), false, 255);
        var reference = IntraTesting.psnr(original, decodedLuma(DvTesting.split(ffmpegEncode(profile, pictures),
                profile)), false, 255);
        System.out.printf("%s noisy: luma PSNR %.2f dB, ffmpeg dvvideo %.2f dB%n", profile, ours, reference);
        assertTrue(ours >= reference - 2, profile + " luma PSNR " + ours + " against ffmpeg's " + reference);
    }

    @ParameterizedTest
    @EnumSource(DvProfile.class)
    @DisplayName("names the format, frame rate, aspect ratio and time code ffprobe reports")
    void ffprobeReportsFormat(DvProfile profile) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var pictures = source(profile);
        for (var wide : List.of(false, true)) {
            var options = DvEncoderOptions.of(profile).toBuilder().wide(wide).build();
            var file = write(profile.name() + (wide ? "-wide" : "-narrow"),
                    DvTesting.concatenate(DvTesting.encode(options, pictures)));
            var text = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                    "-show_entries", "stream=codec_name,width,height,pix_fmt,r_frame_rate,display_aspect_ratio"
                            + ":format_tags=timecode", "-of", "default=nw=1", file.toString())));
            var lines = Arrays.asList(text.replace("\r", "").split("\n"));
            assertTrue(lines.contains("codec_name=dvvideo"), text);
            assertTrue(lines.contains("width=" + profile.width()), text);
            assertTrue(lines.contains("height=" + profile.height()), text);
            assertTrue(lines.contains("pix_fmt=" + DvTesting.pixelFormat(profile)), text);
            assertTrue(lines.contains("r_frame_rate=" + DvTesting.rate(profile)), text);
            var dar = wide || profile.width() > 720 ? "16:9" : "4:3";
            assertTrue(lines.contains("display_aspect_ratio=" + dar), text);
            // ffmpeg reads the time code from subcode sync 0, where IEC 61834 puts it; SMPTE ST 314 leaves it reserved
            if (profile == DvProfile.DV_525 || profile == DvProfile.DV_625) {
                var separator = profile.frameRate().denominator() == 1001 ? ";" : ":";
                assertTrue(lines.contains("TAG:timecode=00:00:00" + separator + "00"), text);
            }
        }
    }

    @Test
    @DisplayName("emits one key frame of the profile's size per picture, timestamps in frames")
    void packets() {
        var pictures = source(DvProfile.DV_625);
        var packets = DvTesting.encode(DvEncoderOptions.of(DvProfile.DV_625), pictures);
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            assertEquals(DvProfile.DV_625.frameSize(), packet.data().length);
            assertTrue(packet.keyframe());
            assertEquals(i, packet.pts());
        }
        try (var encoder = new DvEncoder(DvEncoderOptions.of(DvProfile.DV_625))) {
            assertEquals(Codec.DV, encoder.codec());
            assertEquals(new Rational(1, 25), encoder.timeBase());
            assertTrue(encoder.codecConfig().isEmpty());
            var wrong = YuvPicture.allocate(720, 480, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new VideoFrame(wrong, 0, 1, true),
                    packet -> { }));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DvProfile.class, names = {"DV_525", "DVCPRO50_625", "DVCPRO_HD_1080I50"})
    @DisplayName("writes the same frames whatever the thread count")
    void threadInvariance(DvProfile profile) {
        var pictures = source(profile);
        var single = DvTesting.encode(DvEncoderOptions.of(profile).toBuilder().threads(1).build(), pictures);
        var pooled = DvTesting.encode(DvEncoderOptions.of(profile).toBuilder().threads(4).build(), pictures);
        for (var i = 0; i < single.size(); i++) {
            assertArrayEquals(single.get(i).data(), pooled.get(i).data(), "frame " + i);
        }
        var serial = new DvDecoder(com.github.auties00.smalt.video.VideoDecoderOptions.builder().threads(1).build());
        var parallel = new DvDecoder();
        for (var packet : single) {
            var a = new ByteArrayOutputStream();
            var b = new ByteArrayOutputStream();
            serial.decode(packet, frame -> a.writeBytes(DvTesting.raw(frame.picture(), "yuv422p")));
            parallel.decode(packet, frame -> b.writeBytes(DvTesting.raw(frame.picture(), "yuv422p")));
            assertArrayEquals(a.toByteArray(), b.toByteArray());
        }
    }

    @Test
    @DisplayName("places the even and odd 720-line frames in DIF channels 0-1 and 2-3")
    void progressiveChannels() {
        var profile = DvProfile.DVCPRO_HD_720P60;
        var packets = DvTesting.encode(DvEncoderOptions.of(profile), source(profile));
        for (var i = 0; i < packets.size(); i++) {
            var data = packets.get(i).data();
            var second = i % 2 == 1;
            // FSP is 1 in channels 0 and 1, 0 in channels 2 and 3 (SMPTE ST 370 clause 3.3.2)
            assertEquals(second ? 0 : 1, (data[1] >>> 2) & 1, "FSP of frame " + i);
            assertEquals(1, (data[profile.frameSize() / 2 + 1] >>> 3) & 1, "FSC of the second channel");
        }
        var joined = new byte[2 * profile.frameSize()];
        System.arraycopy(packets.get(0).data(), 0, joined, 0, profile.frameSize());
        System.arraycopy(packets.get(1).data(), 0, joined, profile.frameSize(), profile.frameSize());
        var frames = new ArrayList<VideoFrame>();
        new DvDecoder().decode(new Packet(0, joined, 0, 0, 2, true), frames::add);
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).pts());
        assertEquals(1, frames.get(1).pts());
    }

    /** Returns interlaced pictures whose two fields differ strongly: alternate lines bright and dark. */
    private static List<Picture> combed(DvProfile profile) {
        var chroma = profile == DvProfile.DV_625 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
        var color = profile.width() > 720 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var picture = YuvPicture.allocate(profile.width(), profile.height(), chroma, false, color);
        for (var y = 0; y < profile.height(); y++) {
            var value = (byte) (y % 2 == 0 ? 200 : 40);
            Arrays.fill(picture.y(), y * picture.yStride(), y * picture.yStride() + profile.width(), value);
        }
        Arrays.fill(picture.u(), (byte) 128);
        Arrays.fill(picture.v(), (byte) 128);
        return List.of(picture);
    }

    /** Counts the SD blocks coded in the 2-4-8 mode, from the DCT mode bit of their DC words. */
    private static int fieldBlocks(byte[] frame, DvProfile profile) {
        int[] areas = {4, 18, 32, 46, 60, 70};
        var count = 0;
        for (var sequence = 0; sequence < frame.length / 12000; sequence++) {
            for (var video = 0; video < 135; video++) {
                var block = sequence * 12000 + (6 + (video / 15) * 16 + 1 + video % 15) * 80;
                for (var a = 0; a < areas.length; a++) {
                    if (profile.name().startsWith("DVCPRO50") && (a == 1 || a == 3)) {
                        continue;
                    }
                    count += (frame[block + areas[a] + 1] >>> 6) & 1;
                }
            }
        }
        return count;
    }

    @ParameterizedTest
    @EnumSource(value = DvProfile.class, names = {"DV_525", "DV_625", "DVCPRO50_525"})
    @DisplayName("codes blocks whose fields differ in the 2-4-8 mode only when adaptive DCT is on")
    void adaptiveDct(DvProfile profile) {
        var pictures = combed(profile);
        var adaptive = DvTesting.encode(DvEncoderOptions.of(profile), pictures).getFirst().data();
        var frameOnly = DvTesting.encode(DvEncoderOptions.of(profile).toBuilder().adaptiveDct(false).build(),
                pictures).getFirst().data();
        assertTrue(fieldBlocks(adaptive, profile) > 0, "no 2-4-8 block with adaptive DCT");
        assertEquals(0, fieldBlocks(frameOnly, profile));
        var original = sourceLuma(pictures);
        var withFields = IntraTesting.psnr(original, decodedLuma(List.of(new Packet(0, adaptive, 0, 0, 1, true))),
                false, 255);
        var withFrames = IntraTesting.psnr(original, decodedLuma(List.of(new Packet(0, frameOnly, 0, 0, 1, true))),
                false, 255);
        assertTrue(withFields >= 30 && withFrames >= 30, withFields + " / " + withFrames);
    }

    @Test
    @DisplayName("is created by VideoEncoders for the DV picture sizes and rejects the others")
    void registry() {
        assertTrue(VideoEncoders.isSupported(Codec.DV));
        var options = VideoEncoderOptions.builder().size(960, 720).frameRate(new Rational(50, 1))
                .bitrate(100_000_000).build();
        try (var encoder = VideoEncoders.create(Codec.DV, options)) {
            assertEquals(Codec.DV, encoder.codec());
            assertEquals(960, encoder.width());
            assertEquals(((DvEncoder) encoder).options().profile(), DvProfile.DVCPRO_HD_720P50);
        }
        var wrong = VideoEncoderOptions.builder().size(640, 480).frameRate(new Rational(25, 1))
                .bitrate(25_000_000).build();
        assertThrows(IllegalArgumentException.class, () -> VideoEncoders.create(Codec.DV, wrong));
        assertFalse(DvEncoderOptions.defaults().wide());
    }
}
