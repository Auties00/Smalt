package com.github.auties00.smalt.video.fuzz;

import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.args;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the video fuzz targets (tools/fuzz/README.md): a few frames encoded by ffmpeg and
 * converted to the packet format through Smalt's demuxers, still AVIF images, and the seeds of the AV1 Annex B and
 * tile list targets, the temporal units of the streams of the AV1 tests. Runs only with
 * {@code -Dsmalt.fuzz.writeSeeds=true}. The AVIF seeds of derived images and sample transforms,
 * {@code valid-derived.avif} and {@code valid-sato.avif}, are written with the behavior samples by
 * {@code tools/testdata/behavior/make-avif-samples.py}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class FuzzSeedWriter {
    @Test
    @DisplayName("writes the seeds of the video decoder targets")
    void seeds() {
        seed("vp9", "valid-profile0.bin", Codec.VP9, 0, ffmpeg("ivf", video(32, 32, 3), "-c:v", "libvpx-vp9",
                "-deadline", "realtime", "-b:v", "50k"));
        seed("vp9", "valid-profile2-10bit.bin", Codec.VP9, 1, ffmpeg("ivf", video(32, 32, 2), "-c:v", "libvpx-vp9",
                "-deadline", "realtime", "-b:v", "50k", "-pix_fmt", "yuv420p10le"));
        seed("vp9", "valid-profile1-444-tiles.bin", Codec.VP9, 0, ffmpeg("ivf", video(320, 16, 2), "-c:v",
                "libvpx-vp9", "-deadline", "realtime", "-b:v", "50k", "-pix_fmt", "yuv444p", "-tile-columns", "1"));
        seed("av1", "valid-8bit.bin", Codec.AV1, 0, ffmpeg("ivf", video(32, 32, 3), "-c:v", "libaom-av1",
                "-cpu-used", "8", "-b:v", "50k"));
        seed("av1", "valid-10bit-444.bin", Codec.AV1, 1, ffmpeg("ivf", video(32, 32, 2), "-c:v", "libaom-av1",
                "-cpu-used", "8", "-b:v", "50k", "-pix_fmt", "yuv444p10le"));
        seed("av1", "valid-mp4.bin", Codec.AV1, 0, ffmpeg("mp4", video(32, 32, 2), "-c:v", "libaom-av1",
                "-cpu-used", "8", "-b:v", "50k", "-enable-cdef", "0"));
        seed("proRes", "valid-422-proxy.bin", Codec.PRORES, 0, ffmpeg("mov", video(32, 16, 2), "-c:v", "prores_ks",
                "-profile:v", "0", "-q:v", "31"));
        seed("proRes", "valid-4444-alpha.bin", Codec.PRORES, 1, ffmpeg("mov", video(32, 16, 1), "-c:v", "prores_ks",
                "-profile:v", "4", "-pix_fmt", "yuva444p10le", "-q:v", "31"));
        seed("dnxhd", "valid-dnxhr-lb.bin", Codec.DNXHD, 0, ffmpeg("mov", video(256, 120, 1), "-c:v", "dnxhd",
                "-profile:v", "dnxhr_lb", "-pix_fmt", "yuv422p"));
        copy(VideoFuzzTest.class, "avif", "valid-still.avif", ffmpeg("avif", video(32, 32, 1), "-frames:v", "1",
                "-c:v", "libaom-av1", "-cpu-used", "8", "-still-picture", "1"));
        copy(VideoFuzzTest.class, "avif", "valid-10bit-444.avif", ffmpeg("avif", video(16, 16, 1), "-frames:v",
                "1", "-c:v", "libaom-av1", "-cpu-used", "8", "-still-picture", "1", "-pix_fmt", "yuv444p10le"));
        copy(VideoFuzzTest.class, "avif", "valid-sequence.avifs", ffmpeg("avif", args(video(16, 16, 3)),
                "-c:v", "libaom-av1", "-cpu-used", "8"));
    }

    @Test
    @DisplayName("writes the DNxHD alpha seeds: premultiplied in-band alpha and Avid's out-of-band alpha, which Smalt writes")
    void dnxhdAlpha() {
        var picture = com.github.auties00.smalt.image.Yuv16Picture.allocate(32, 16,
                com.github.auties00.smalt.image.ChromaFormat.YUV444, 10, true,
                com.github.auties00.smalt.image.ColorInfo.BT709_LIMITED);
        for (var i = 0; i < 32 * 16; i++) {
            picture.y()[i] = (short) (64 + i);
            picture.u()[i] = (short) (512 + (i % 32) * 8);
            picture.v()[i] = (short) (512 - (i / 32) * 8);
            picture.alpha()[i] = (short) (i % 32 < 4 ? 0 : (i % 32) * 33);
        }
        var o = com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions.of(
                com.github.auties00.smalt.video.dnxhd.DnxhdProfile.DNXHR_444).toBuilder().alpha(true);
        dnxhdSeed("valid-dnxhr-444-premultiplied-lossless.bin", o.losslessAlpha(true).premultipliedAlpha(true).build(),
                picture);
        var hq = com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions.of(
                com.github.auties00.smalt.video.dnxhd.DnxhdProfile.DNXHR_HQX).toBuilder().alpha(true);
        dnxhdSeed("valid-dnxhr-hqx-avid-uncompressed.bin", hq.alphaStorage(
                com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions.AlphaStorage.AVID_UNCOMPRESSED).build(),
                picture);
        dnxhdSeed("valid-dnxhr-hqx-avid-coded.bin", hq.alphaStorage(
                com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions.AlphaStorage.AVID_CODED).build(), picture);
    }

    private static void dnxhdSeed(String name, com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions options,
                                  com.github.auties00.smalt.image.Picture picture) {
        var encoder = new com.github.auties00.smalt.video.dnxhd.DnxhdEncoder(32, 16, options);
        var packet = encoder.encodeFrame(new com.github.auties00.smalt.video.VideoFrame(picture, 0, 3600, true));
        var track = com.github.auties00.smalt.track.VideoTrack.builder().id(1).codec(Codec.DNXHD)
                .timeBase(encoder.timeBase()).codecConfig(encoder.codecConfig().orElseThrow()).width(32).height(16)
                .build();
        write(VideoFuzzTest.class, "dnxhd", name, FuzzSeeds.packets(track, 0,
                java.util.List.of(new com.github.auties00.smalt.testkit.fuzz.FuzzPackets.Part(packet.data(), true))));
    }

    @Test
    @DisplayName("writes the seeds of the AV1 Annex B and tile list targets from the AV1 test streams")
    void av1Seeds() {
        var annexB = "/com/github/auties00/smalt/video/av1/annexb/";
        ivfSeed("av1AnnexB", "valid-8bit-tiles.bin", 0, annexB + "annexb-8bit-tiles.ivf");
        ivfSeed("av1AnnexB", "valid-10bit-444.bin", 1, annexB + "annexb-10bit-444.ivf");
        var raw = resource(annexB + "annexb-raw.obu");
        var units = new ArrayList<FuzzPackets.Part>();
        for (var position = 0; position < raw.length; ) {
            long value = 0;
            var start = position;
            for (var i = 0; ; i++) {
                var b = raw[position + i] & 0xFF;
                value |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    start = position + i + 1;
                    break;
                }
            }
            var end = Math.toIntExact(start + value);
            units.add(new FuzzPackets.Part(Arrays.copyOfRange(raw, position, end), units.isEmpty()));
            position = end;
        }
        write(VideoFuzzTest.class, "av1AnnexB", "valid-raw-all-layers.bin", FuzzPackets.video(8, 0, 0, null, units));
        var lightField = "/com/github/auties00/smalt/video/av1/lightfield/";
        ivfSeed("av1TileList", "valid-testsrc2.bin", 4 << 4, lightField + "lightfield-testsrc2.ivf");
        ivfSeed("av1TileList", "valid-mandelbrot.bin", 3 << 4, lightField + "lightfield-mandelbrot.ivf");
    }

    /** Writes a seed of the frames of an IVF resource, as they are, with the size of its header. */
    private static void ivfSeed(String method, String name, int options, String resource) {
        var data = resource(resource);
        var width = (data[12] & 0xFF) | (data[13] & 0xFF) << 8;
        var height = (data[14] & 0xFF) | (data[15] & 0xFF) << 8;
        var parts = new ArrayList<FuzzPackets.Part>();
        for (var position = (data[6] & 0xFF) | (data[7] & 0xFF) << 8; position + 12 <= data.length; ) {
            var size = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF) << 16
                    | (data[position + 3] & 0xFF) << 24;
            position += 12;
            parts.add(new FuzzPackets.Part(Arrays.copyOfRange(data, position, position + size), parts.isEmpty()));
            position += size;
        }
        write(VideoFuzzTest.class, method, name, FuzzPackets.video(options, width, height, null, parts));
    }

    @Test
    @DisplayName("writes the seeds of the AV1 film grain table and first pass statistics targets")
    void av1EncoderSeeds() {
        write(VideoFuzzTest.class, "av1FilmGrainTable", "valid-table.txt",
                GRAIN_TABLE.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        write(VideoFuzzTest.class, "av1FilmGrainTable", "valid-constant-luma.txt", ("filmgrn1\n"
                + "E 0 9223372036854775807 1 1234 1\n"
                + "\tp 0 6 1 9 0 1 128 192 256 128 192 256\n"
                + "\tsY 3  0 32 128 48 255 40\n"
                + "\tsCb 0\n"
                + "\tsCr 0\n"
                + "\tcY\n"
                + "\tcCb 0\n"
                + "\tcCr 0\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        write(VideoFuzzTest.class, "av1FirstPassStatistics", "valid-16x16-scene-cut.bin", statistics(16, 16, 6));
        write(VideoFuzzTest.class, "av1FirstPassStatistics", "valid-32x24-one-frame.bin", statistics(32, 24, 1));
    }

    /** A libaom-style table of three entries: parameters, no grain, and the first parameters again. */
    private static final String GRAIN_TABLE = "filmgrn1\n"
            + "E 0 10000000 1 7391 1\n"
            + "\tp 3 7 0 11 0 1 128 192 256 128 192 256\n"
            + "\tsY 6  0 20 40 28 94 30 144 28 196 26 255 24\n"
            + "\tsCb 2 0 18 255 16\n"
            + "\tsCr 2 0 15 255 14\n"
            + "\tcY 1 -2 3 -4 5 -6 7 -8 9 -10 11 -12 13 -14 15 -16 17 -18 19 -20 21 -22 23 -24\n"
            + "\tcCb 0 1 0 -1 0 1 0 -1 0 1 0 -1 0 1 0 -1 0 1 0 -1 0 1 0 -1 33\n"
            + "\tcCr 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 -7\n"
            + "E 10000000 20000000 0 0 0\n"
            + "E 20000000 30000000 1 10 0\n";

    /** Returns serialized first pass statistics of plausible measurements, the fourth frame a scene cut. */
    private static byte[] statistics(int width, int height, int frames) {
        var out = java.nio.ByteBuffer.allocate(24 + frames * 12 * 8);
        out.putLong(0x534D414C54465031L).putInt(1).putInt(width).putInt(height).putInt(frames);
        for (var i = 0; i < frames; i++) {
            var cut = i == 3;
            double[] values = {cut ? 900 : 120 + 10 * i, cut ? 850 : 30 + 2 * i, cut ? 860 : 40, cut ? 0.05 : 0.9,
                    cut ? 0.0 : 0.4, 0.1, 0.25, 0.0, 0.5, 1.5, -0.25, 1.0};
            for (var v : values) {
                out.putDouble(v);
            }
        }
        return out.array();
    }

    private static byte[] resource(String name) {
        try (var input = FuzzSeedWriter.class.getResourceAsStream(name)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void seed(String method, String name, Codec codec, int options, Path file) {
        write(VideoFuzzTest.class, method, name, FuzzSeeds.packets(file, codec, options, 4));
    }

    @Test
    @DisplayName("writes the seeds of the AVIF gain map target: AVIF files with a tmap item that Smalt writes")
    void gainMaps() {
        var base = com.github.auties00.smalt.image.RgbaPicture.allocate(24, 16, false);
        var bright = com.github.auties00.smalt.image.Rgba16Picture.allocate(24, 16, false);
        for (var i = 0; i < 24 * 16; i++) {
            for (var c = 0; c < 3; c++) {
                base.pixels()[i * 4 + c] = (byte) (i * (c + 3));
                bright.pixels()[i * 4 + c] = (short) (i * (c + 3) * 150);
            }
            base.pixels()[i * 4 + 3] = (byte) 0xFF;
            bright.pixels()[i * 4 + 3] = (short) 0xFFFF;
        }
        var pq = com.github.auties00.smalt.image.color.ColorProfile.of(new com.github.auties00.smalt.image.ColorInfo(
                com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY,
                com.github.auties00.smalt.image.ColorRange.FULL, com.github.auties00.smalt.image.ColorPrimaries.BT709,
                com.github.auties00.smalt.image.TransferCharacteristics.PQ));
        var sdr = new com.github.auties00.smalt.image.DecodedImage(base,
                com.github.auties00.smalt.image.ImageFormat.AVIF, com.github.auties00.smalt.image.Orientation.NORMAL,
                24, 16, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty());
        var alternate = new com.github.auties00.smalt.image.DecodedImage(bright,
                com.github.auties00.smalt.image.ImageFormat.AVIF, com.github.auties00.smalt.image.Orientation.NORMAL,
                24, 16, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(pq));
        for (var multichannel : new boolean[]{false, true}) {
            var gainMap = com.github.auties00.smalt.image.gainmap.GainMaps.compute(sdr, alternate,
                    com.github.auties00.smalt.image.gainmap.GainMapComputeOptions.builder().multichannel(multichannel)
                            .build());
            com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write(VideoFuzzTest.class, "avifGainMap",
                    "valid-tmap-" + (multichannel ? "multichannel" : "gray") + ".avif",
                    com.github.auties00.smalt.image.avif.AvifEncoder.encode(base, gainMap));
        }
    }
}
