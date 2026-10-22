package com.github.auties00.smalt.video.fuzz;

import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.args;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the video fuzz targets (tools/fuzz/README.md): a few frames encoded by ffmpeg and
 * converted to the packet format through Smalt's demuxers, and still AVIF images. Runs only with
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

    private static void seed(String method, String name, Codec codec, int options, Path file) {
        write(VideoFuzzTest.class, method, name, FuzzSeeds.packets(file, codec, options, 4));
    }
}
