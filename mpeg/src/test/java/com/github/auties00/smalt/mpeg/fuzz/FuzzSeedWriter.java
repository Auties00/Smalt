package com.github.auties00.smalt.mpeg.fuzz;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.Arrays;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.tone;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the mpeg fuzz targets (tools/fuzz/README.md): short streams encoded by ffmpeg and
 * converted to the packet format through Smalt's demuxers, raw elementary streams and H.264 parameter sets made by
 * ffmpeg, and HEIC stills built with the test kit's
 * {@link HeifBuilder} around x265 pictures. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true")
class FuzzSeedWriter {
    @Test
    @DisplayName("writes the seeds of the video decoder targets")
    void videos() {
        seed("h264", "valid-baseline-mp4.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264",
                "-preset", "ultrafast", "-profile:v", "baseline"));
        seed("h264", "valid-high-bframes-cabac.bin", Codec.H264, ffmpeg("mp4", video(48, 32, 4), "-c:v", "libx264",
                "-preset", "medium", "-bf", "2", "-refs", "2", "-x264-params", "weightb=1:b-pyramid=normal"));
        seed("h264", "valid-annexb-interlaced.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v", "libx264",
                "-preset", "ultrafast", "-flags", "+ildct+ilme", "-x264-params", "tff=1"));
        seed("h264", "valid-high10-8x8.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 2), "-c:v", "libx264",
                "-preset", "fast", "-pix_fmt", "yuv420p10le", "-x264-params", "8x8dct=1"));
        seed("hevc", "valid-main-mp4.bin", Codec.HEVC, ffmpeg("mp4", video(64, 64, 3), "-c:v", "libx265",
                "-preset", "ultrafast", "-x265-params", "log-level=0:bframes=1"));
        seed("hevc", "valid-main10-annexb.bin", Codec.HEVC, ffmpeg("ts", video(64, 32, 2), "-c:v", "libx265",
                "-preset", "fast", "-pix_fmt", "yuv420p10le", "-x265-params", "log-level=0:sao=1:amp=1"));
        seed("mpeg4", "valid-asp-avi.bin", Codec.MPEG4_PART2, ffmpeg("avi", video(32, 32, 3), "-c:v", "mpeg4",
                "-q:v", "10", "-bf", "1"));
        seed("mpeg4", "valid-qpel-mp4.bin", Codec.MPEG4_PART2, ffmpeg("mp4", video(32, 32, 3), "-c:v", "mpeg4",
                "-q:v", "10", "-flags", "+qpel+mv4"));
        seed("h263", "valid-sqcif.bin", Codec.H263, ffmpeg("3gp", video(128, 96, 3), "-c:v", "h263", "-q:v", "20"));
        seed("h263", "valid-plus-annexes.bin", Codec.H263, ffmpeg("avi", video(176, 144, 2), "-c:v", "h263p",
                "-q:v", "20", "-flags", "+aic+loop", "-umv", "1", "-aiv", "1"));
    }

    @Test
    @DisplayName("writes the seeds of the audio decoder targets")
    void audios() {
        seed("aac", "valid-lc-m4a.bin", Codec.AAC, ffmpeg("m4a", tone(8000, 0.3), "-c:a", "aac", "-b:a", "16k"));
        seed("aac", "valid-lc-stereo-adts.bin", Codec.AAC, ffmpeg("aac", tone(44100, 0.1), "-ac", "2",
                "-c:a", "aac", "-b:a", "64k"));
        seed("aac", "valid-stereo-tools.bin", Codec.AAC, ffmpeg("m4a", tone(16000, 0.2), "-ac", "2", "-c:a",
                "aac", "-b:a", "24k", "-aac_pns", "1", "-aac_is", "1", "-aac_ms", "1", "-aac_tns", "1"));
        seed("amrNb", "valid-nb.bin", Codec.AMR_NB, ffmpeg("amr", tone(8000, 0.2), "-c:a", "libopencore_amrnb",
                "-ar", "8000", "-b:a", "12.2k"));
        seed("amrWb", "valid-wb.bin", Codec.AMR_WB, ffmpeg("amr", tone(16000, 0.2), "-c:a", "libvo_amrwbenc",
                "-ar", "16000"));
        seed("ac3", "valid-stereo.bin", Codec.AC3, ffmpeg("ac3", tone(48000, 0.1), "-ac", "2", "-c:a", "ac3",
                "-b:a", "96k"));
        seed("ac3", "valid-surround.bin", Codec.AC3, ffmpeg("ac3", tone(48000, 0.06), "-ac", "6", "-c:a", "ac3",
                "-b:a", "192k"));
        seed("eac3", "valid-stereo.bin", Codec.EAC3, ffmpeg("eac3", tone(48000, 0.1), "-ac", "2", "-c:a", "eac3",
                "-b:a", "96k"));
    }

    @Test
    @DisplayName("writes the seeds of the HEIC target")
    void heic() {
        write(MpegFuzzTest.class, "heic", "valid-still.heic", still(ffmpeg("mp4", video(64, 64, 1), "-frames:v",
                "1", "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=0")));
        write(MpegFuzzTest.class, "heic", "valid-10bit.heic", still(ffmpeg("mp4", video(32, 32, 1), "-frames:v",
                "1", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-x265-params",
                "log-level=0")));
    }

    @Test
    @DisplayName("writes the seeds of the elementary stream and H.264 parameter set targets")
    void streams() {
        // the m4v extension selects ffmpeg's ipod muxer, so the raw stream muxer is named
        copy(MpegFuzzTest.class, "elementaryStream", "valid-mpeg4.m4v", ffmpeg("m4v", video(32, 32, 3),
                "-c:v", "mpeg4", "-q:v", "10", "-bf", "1", "-f", "m4v"));
        copy(MpegFuzzTest.class, "elementaryStream", "valid-h263.h263", ffmpeg("h263", video(128, 96, 3),
                "-c:v", "h263", "-q:v", "20"));
        var mp4 = ffmpeg("mp4", video(48, 32, 1), "-c:v", "libx264", "-preset", "ultrafast", "-vf", "crop=46:30");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-cropped.bin", firstTrack(mp4).codecConfig().orElseThrow());
        var interlaced = ffmpeg("mp4", video(32, 64, 2), "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv422p10le", "-flags", "+ildct+ilme", "-x264-params", "tff=1");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-high422-interlaced.bin",
                firstTrack(interlaced).codecConfig().orElseThrow());
        var annexB = ffmpeg("h264", video(32, 32, 1), "-c:v", "libx264", "-preset", "ultrafast", "-r", "25");
        write(MpegFuzzTest.class, "h264Info", "valid-annexb.bin", Fixtures.read(annexB));
        var config = firstTrack(mp4).codecConfig().orElseThrow();
        // avcC: 5 header bytes, the SPS count byte, a 16-bit length, then the first SPS NAL unit
        var spsLength = (config[6] & 0xFF) << 8 | config[7] & 0xFF;
        write(MpegFuzzTest.class, "h264Info", "valid-sps.bin", Arrays.copyOfRange(config, 8, 8 + spsLength));
    }

    private static VideoTrack firstTrack(Path file) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            return (VideoTrack) demuxer.tracks().getFirst();
        }
    }

    private static byte[] still(Path mp4) {
        try (var source = MediaSource.of(mp4); var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packet = demuxer.read();
            var builder = new HeifBuilder().brands("heic", "heic", "mif1");
            var id = builder.item("hvc1", packet.data(),
                    HeifBuilder.plain(HeifBuilder.ispe(track.width(), track.height())),
                    HeifBuilder.essential(HeifBuilder.box("hvcC", track.codecConfig().orElseThrow())));
            return builder.primary(id).build();
        }
    }

    private static void seed(String method, String name, Codec codec, Path file) {
        write(MpegFuzzTest.class, method, name, FuzzSeeds.packets(file, codec, 0, 6));
    }
}
