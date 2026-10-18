package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.testkit.Fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The ffmpeg-generated ISO base media files the MP4 tests share.
 */
final class Mp4Fixtures {
    private Mp4Fixtures() {
        throw new AssertionError();
    }

    /** A named fixture generator. */
    record Case(String name, Supplier<Path> file) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<String> video(int width, int height, double seconds, int rate) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=" + rate + ":duration=" + seconds);
    }

    static List<String> audio(int sampleRate, double seconds) {
        return List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + sampleRate + ":duration=" + seconds);
    }

    static List<String> both(int width, int height, double seconds, int sampleRate) {
        var inputs = new ArrayList<>(video(width, height, seconds, 30));
        inputs.addAll(audio(sampleRate, seconds));
        return inputs;
    }

    static Path h264Aac(boolean bFrames, List<String> extra, String extension) {
        var options = new ArrayList<>(List.of("-c:v", "libx264", "-preset", "veryfast", "-g", "15"));
        options.addAll(bFrames ? List.of("-bf", "3", "-x264-params", "b-pyramid=normal") : List.of("-bf", "0"));
        options.addAll(List.of("-c:a", "aac", "-b:a", "96k"));
        options.addAll(extra);
        return Fixtures.ffmpeg(extension, both(160, 120, 2, 48000), options);
    }

    /** Remuxes a fixture with extra output options (stream copy). */
    static Path copy(Path input, String extension, List<String> inputOptions, List<String> outputOptions) {
        var inputs = new ArrayList<>(inputOptions);
        inputs.addAll(List.of("-i", input.toString()));
        var outputs = new ArrayList<>(List.of("-map", "0", "-c", "copy"));
        outputs.addAll(outputOptions);
        return Fixtures.ffmpeg(extension, inputs, outputs);
    }

    static List<Case> demuxCases() {
        var cases = new ArrayList<Case>();
        cases.add(new Case("h264 b-pyramid + aac", () -> h264Aac(true, List.of(), "mp4")));
        cases.add(new Case("h264 no b-frames + aac", () -> h264Aac(false, List.of(), "mp4")));
        cases.add(new Case("h264 faststart", () -> h264Aac(true, List.of("-movflags", "+faststart"), "mp4")));
        cases.add(new Case("h264 negative cts", () -> h264Aac(true, List.of("-movflags", "+negative_cts_offsets"), "mp4")));
        cases.add(new Case("hevc hvc1 + aac", () -> Fixtures.ffmpeg("mp4", both(160, 120, 1.5, 44100),
                List.of("-c:v", "libx265", "-x265-params", "log-level=error:bframes=4", "-tag:v", "hvc1", "-c:a", "aac"))));
        cases.add(new Case("hevc hev1", () -> Fixtures.ffmpeg("mp4", video(128, 96, 1, 25),
                List.of("-c:v", "libx265", "-x265-params", "log-level=error"))));
        cases.add(new Case("vp9 + opus in mp4", () -> Fixtures.ffmpeg("mp4", both(96, 64, 1, 48000),
                List.of("-c:v", "libvpx-vp9", "-b:v", "200k", "-deadline", "realtime", "-c:a", "libopus"))));
        cases.add(new Case("av1 in mp4", () -> Fixtures.ffmpeg("mp4", video(64, 64, 0.5, 30),
                List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-b:v", "100k"))));
        cases.add(new Case("mpeg4 part 2 + mp3", () -> Fixtures.ffmpeg("mp4", both(160, 120, 1, 44100),
                List.of("-c:v", "mpeg4", "-c:a", "libmp3lame"))));
        cases.add(new Case("mp3 audio only", () -> Fixtures.ffmpeg("mp4", audio(44100, 1.5), List.of("-c:a", "libmp3lame"))));
        cases.add(new Case("mp3 mpeg-2 rate", () -> Fixtures.ffmpeg("mp4", audio(22050, 1), List.of("-c:a", "libmp3lame"))));
        cases.add(new Case("mp2 in mp4", () -> Fixtures.ffmpeg("mp4", audio(48000, 1), List.of("-c:a", "mp2"))));
        cases.add(new Case("opus in mp4", () -> Fixtures.ffmpeg("mp4", audio(48000, 1.2), List.of("-c:a", "libopus", "-ac", "2"))));
        cases.add(new Case("flac in mp4", () -> Fixtures.ffmpeg("mp4", audio(44100, 1.2), List.of("-c:a", "flac"))));
        cases.add(new Case("alac in m4a", () -> Fixtures.ffmpeg("m4a", audio(44100, 1.2), List.of("-c:a", "alac"))));
        cases.add(new Case("aac m4a", () -> Fixtures.ffmpeg("m4a", audio(44100, 1.2), List.of("-c:a", "aac", "-ac", "2"))));
        cases.add(new Case("aac 5.1", () -> Fixtures.ffmpeg("mp4", audio(48000, 0.6), List.of("-c:a", "aac", "-ac", "6"))));
        cases.add(new Case("ac3 in mp4", () -> Fixtures.ffmpeg("mp4", audio(48000, 0.6), List.of("-c:a", "ac3"))));
        for (var pcm : List.of("pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_f32be", "pcm_f32le",
                "pcm_f64le", "pcm_u8", "pcm_s8", "pcm_alaw", "pcm_mulaw")) {
            cases.add(new Case(pcm + " in mov", () -> Fixtures.ffmpeg("mov", audio(22050, 0.7), List.of("-ac", "2", "-c:a", pcm))));
        }
        cases.add(new Case("pcm_s16le in mp4 (ipcm)", () -> Fixtures.ffmpeg("mp4", audio(16000, 0.5), List.of("-c:a", "pcm_s16le"))));
        cases.add(new Case("h263 + amr-nb 3gp", () -> Fixtures.ffmpeg("3gp", both(176, 144, 1, 8000),
                List.of("-c:v", "h263", "-c:a", "libopencore_amrnb", "-ar", "8000", "-ac", "1", "-b:a", "12.2k"))));
        cases.add(new Case("mjpeg in mov", () -> Fixtures.ffmpeg("mov", video(96, 64, 0.5, 10), List.of("-c:v", "mjpeg"))));
        cases.add(new Case("prores in mov", () -> Fixtures.ffmpeg("mov", video(96, 64, 0.3, 10), List.of("-c:v", "prores_ks"))));
        cases.add(new Case("h264 + aac in mov", () -> h264Aac(true, List.of(), "mov")));
        cases.add(new Case("fragmented empty moov", () -> copy(h264Aac(true, List.of(), "mp4"), "mp4", List.of(),
                List.of("-movflags", "frag_keyframe+empty_moov+default_base_moof"))));
        cases.add(new Case("fragmented with moov samples", () -> copy(h264Aac(true, List.of(), "mp4"), "mp4", List.of(),
                List.of("-movflags", "frag_keyframe"))));
        cases.add(new Case("fragmented separate moof per track", () -> copy(h264Aac(true, List.of(), "mp4"), "mp4", List.of(),
                List.of("-movflags", "frag_keyframe+empty_moov+separate_moof+omit_tfhd_offset"))));
        cases.add(new Case("fragmented by duration", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of(),
                List.of("-movflags", "frag_keyframe+empty_moov", "-frag_duration", "300000"))));
        cases.add(new Case("rotation 90", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of("-display_rotation", "90"), List.of())));
        cases.add(new Case("rotation 180", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of("-display_rotation", "180"), List.of())));
        cases.add(new Case("rotation 270", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of("-display_rotation", "270"), List.of())));
        cases.add(new Case("hflip", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of("-display_hflip"), List.of())));
        cases.add(new Case("rotation 90 + hflip", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4",
                List.of("-display_rotation", "90", "-display_hflip"), List.of())));
        cases.add(new Case("edit list from -ss copy", () -> copy(h264Aac(true, List.of(), "mp4"), "mp4", List.of("-ss", "0.7"), List.of())));
        cases.add(new Case("metadata tags", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of(),
                List.of("-metadata", "title=Title è", "-metadata", "artist=Someone", "-metadata", "comment=hi"))));
        cases.add(new Case("mdta metadata", () -> copy(h264Aac(false, List.of(), "mp4"), "mp4", List.of(),
                List.of("-metadata", "title=Keyed", "-metadata", "custom_key=custom value", "-movflags", "use_metadata_tags"))));
        cases.add(new Case("two audio tracks + mov_text", () -> multiTrack()));
        return cases;
    }

    static Path multiTrack() {
        return Fixtures.ffmpeg("mp4", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=1",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                        "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=1",
                        "-i", com.github.auties00.smalt.container.TestFiles.subtitles().toString()),
                List.of("-map", "0", "-map", "1", "-map", "2", "-map", "3", "-c:v", "libx264", "-c:a", "aac",
                        "-c:s", "mov_text"));
    }
}
