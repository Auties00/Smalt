package com.github.auties00.smalt.fuzz;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.args;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.tone;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the core fuzz targets (tools/fuzz/README.md): small files made by ffmpeg from
 * its synthetic sources and by Smalt itself. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class FuzzSeedWriter {
    private static final String SRT = "1\n00:00:00,000 --> 00:00:00,500\n<i>Hello</i> world\n\n"
            + "2\n00:00:00,600 --> 00:00:01,000\n{\\an8}second <b>cue</b>\n";

    private static final String VTT = "WEBVTT\n\nNOTE seed\n\nintro\n00:00.000 --> 00:00.500 align:start line:10%\n"
            + "<v Ann>Hello <c.yellow>there</c></v>\n\n00:00.600 --> 00:01.000\n<b>two</b> <ruby>x<rt>y</rt></ruby>\n";

    private static final String ASS_HEADER = "[Script Info]\nScriptType: v4.00+\nPlayResX: 384\nPlayResY: 288\n\n"
            + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
            + "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
            + "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,16,&Hffffff,&Hffffff,"
            + "&H0,&H0,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,0\n\n[Events]\nFormat: Layer, Start, End, Style, Name, "
            + "MarginL, MarginR, MarginV, Effect, Text\n";

    private static final String XMP = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
            + "xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"seed\" xmp:Rating=\"3\">"
            + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Seed</rdf:li><rdf:li xml:lang=\"it\">Seme</rdf:li>"
            + "</rdf:Alt></dc:title><dc:subject><rdf:Bag><rdf:li>a</rdf:li><rdf:li>b</rdf:li></rdf:Bag></dc:subject>"
            + "<dc:creator><rdf:Seq><rdf:li>Smalt</rdf:li></rdf:Seq></dc:creator>"
            + "<xmp:Thumbnails rdf:parseType=\"Resource\"><xmp:format>JPEG</xmp:format></xmp:Thumbnails>"
            + "</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>";

    @Test
    @DisplayName("copies a seed of every image format to the probe target, whose header readers live in core")
    void imageHeaders() {
        var images = Path.of("..", "image", "src", "test", "resources", "com", "github", "auties00", "smalt", "image",
                "fuzz", "ImageFuzzTestInputs");
        for (var seed : List.of("jpeg/valid-baseline-420.jpg", "png/valid-animated.apng", "gif/valid-animated.gif",
                "webp/valid-animated.webp", "bmp/valid-24bit.bmp", "tiff/valid-deflate.tif", "ico/valid-icon.ico",
                "jp2/valid-jp2.jp2", "jp2/valid-codestream.j2k", "jxl/valid-grayscale.jxl")) {
            var file = images.resolve(seed);
            copy(ProbeFuzzTest.class, "probe", file.getFileName().toString(), file);
        }
        copy(ProbeFuzzTest.class, "probe", "valid-still.avif", Path.of("..", "video", "src", "test", "resources", "com",
                "github", "auties00", "smalt", "video", "fuzz", "VideoFuzzTestInputs", "avif", "valid-still.avif"));
        copy(ProbeFuzzTest.class, "probe", "valid-still.heic", Path.of("..", "mpeg", "src", "test", "resources", "com",
                "github", "auties00", "smalt", "mpeg", "fuzz", "MpegFuzzTestInputs", "heic", "valid-still.heic"));
        var raw = Path.of("..", "raw", "src", "test", "resources", "com", "github", "auties00", "smalt", "image", "raw",
                "fuzz", "RawFuzzTestInputs");
        copy(ProbeFuzzTest.class, "probe", "valid-uncompressed.dng", raw.resolve("dng/valid-uncompressed.dng"));
        copy(ProbeFuzzTest.class, "probe", "valid-reduced-nikon-d70s.nef",
                raw.resolve("nef/valid-reduced-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef"));
    }

    @Test
    @DisplayName("writes the seeds of the demuxer, probe and tag targets")
    void containers() throws IOException {
        var av = args(video(64, 48, 3), tone(8000, 0.3));
        var mp4 = ffmpeg("mp4", av, "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "16k",
                "-ac", "1", "-metadata", "title=seed", "-metadata", "artist=smalt");
        copy(DemuxerFuzzTest.class, "mp4", "valid-h264-aac.mp4", mp4);
        copy(DemuxerFuzzTest.class, "mp4", "valid-fragmented.mp4", ffmpeg("mp4", av, "-c:v", "libx264",
                "-preset", "ultrafast", "-c:a", "aac", "-b:a", "16k", "-ac", "1",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof"));
        copy(DemuxerFuzzTest.class, "mp4", "valid-mjpeg-pcm.mov", ffmpeg("mov", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "mjpeg", "-q:v", "20", "-c:a", "pcm_s16le"));
        copy(DemuxerFuzzTest.class, "mp4", "valid-h263-amr.3gp", ffmpeg("3gp", args(video(128, 96, 2),
                tone(8000, 0.2)), "-c:v", "h263", "-q:v", "20", "-c:a", "libopencore_amrnb", "-ar", "8000"));
        var m4a = ffmpeg("m4a", tone(8000, 0.2), "-c:a", "alac", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "mp4", "valid-alac.m4a", m4a);
        // a Motion JPEG 2000 file (signature box, mjp2 brand, mjp2 sample entry, jp2c samples), the behavior sample
        // an independent writer made (tools/testdata/behavior/make-mj2-sample.py)
        var mj2 = Path.of("src", "test", "resources", "samples", "mp4", "motion.mj2");
        copy(DemuxerFuzzTest.class, "mp4", "valid-motion.mj2", mj2);
        copy(ProbeFuzzTest.class, "probe", "valid-motion.mj2", mj2);

        var srt = Files.writeString(Files.createTempFile("seed", ".srt"), SRT);
        var mkv = ffmpeg("mkv", args(video(32, 32, 2), tone(8000, 0.2), List.of("-i", srt.toString())),
                "-map", "0", "-map", "1", "-map", "2", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-b:v", "50k",
                "-c:a", "libopus", "-b:a", "8k", "-c:s", "srt", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "matroska", "valid-vp9-opus-srt.mkv", mkv);
        copy(DemuxerFuzzTest.class, "matroska", "valid-vp8-vorbis.webm", ffmpeg("webm", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "libvpx", "-deadline", "realtime", "-b:v", "50k", "-c:a", "libvorbis",
                "-ac", "1"));
        copy(DemuxerFuzzTest.class, "matroska", "valid-h264-ac3.mkv", ffmpeg("mkv", args(video(32, 32, 2),
                tone(48000, 0.1)), "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "ac3", "-b:a", "64k"));

        var vorbis = ffmpeg("ogg", tone(8000, 0.3), "-c:a", "libvorbis", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "ogg", "valid-vorbis.ogg", vorbis);
        var opus = ffmpeg("opus", tone(48000, 0.2), "-c:a", "libopus", "-b:a", "8k", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "ogg", "valid-opus.opus", opus);
        copy(DemuxerFuzzTest.class, "ogg", "valid-flac.oga", ffmpeg("oga", tone(8000, 0.1), "-c:a", "flac"));

        var mp3 = ffmpeg("mp3", tone(8000, 0.3), "-c:a", "libmp3lame", "-b:a", "8k", "-id3v2_version", "3",
                "-metadata", "title=seed", "-metadata", "track=1/2");
        copy(DemuxerFuzzTest.class, "mpegAudio", "valid-mp3-id3.mp3", mp3);
        copy(DemuxerFuzzTest.class, "mpegAudio", "valid-mp2.mp2", ffmpeg("mp2", tone(16000, 0.2), "-c:a", "mp2",
                "-b:a", "32k"));

        copy(DemuxerFuzzTest.class, "adts", "valid-aac.aac", ffmpeg("aac", tone(8000, 0.3), "-c:a", "aac",
                "-b:a", "16k"));

        var wav = ffmpeg("wav", tone(8000, 0.05), "-c:a", "pcm_s16le", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "wav", "valid-s16.wav", wav);
        copy(DemuxerFuzzTest.class, "wav", "valid-float-stereo.wav", ffmpeg("wav", tone(8000, 0.05),
                "-c:a", "pcm_f32le", "-ac", "2"));
        copy(DemuxerFuzzTest.class, "wav", "unsupported-ima-adpcm.wav", ffmpeg("wav", tone(8000, 0.1),
                "-c:a", "adpcm_ima_wav"));

        var flac = ffmpeg("flac", tone(8000, 0.1), "-c:a", "flac", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "flac", "valid-flac.flac", flac);

        copy(DemuxerFuzzTest.class, "amr", "valid-nb.amr", ffmpeg("amr", tone(8000, 0.2),
                "-c:a", "libopencore_amrnb", "-ar", "8000"));
        copy(DemuxerFuzzTest.class, "amr", "valid-wb.amr", ffmpeg("amr", tone(16000, 0.2),
                "-c:a", "libvo_amrwbenc", "-ar", "16000"));

        copy(DemuxerFuzzTest.class, "aiff", "valid-s16.aiff", ffmpeg("aiff", tone(8000, 0.05),
                "-c:a", "pcm_s16be"));

        copy(DemuxerFuzzTest.class, "ivf", "valid-vp8.ivf", ffmpeg("ivf", video(32, 32, 2), "-c:v", "libvpx",
                "-deadline", "realtime", "-b:v", "50k"));
        copy(DemuxerFuzzTest.class, "ivf", "valid-av1.ivf", ffmpeg("ivf", video(32, 32, 2), "-c:v", "libaom-av1",
                "-cpu-used", "8", "-b:v", "50k"));

        copy(DemuxerFuzzTest.class, "avi", "valid-mjpeg-pcm.avi", ffmpeg("avi", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "mjpeg", "-q:v", "20", "-c:a", "pcm_u8"));
        copy(DemuxerFuzzTest.class, "avi", "valid-mpeg4-mp3.avi", ffmpeg("avi", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "mpeg4", "-q:v", "20", "-c:a", "libmp3lame", "-b:a", "8k"));

        copy(DemuxerFuzzTest.class, "mpegTs", "valid-h264-aac.ts", ffmpeg("ts", av, "-c:v", "libx264",
                "-preset", "ultrafast", "-c:a", "aac", "-b:a", "16k", "-ac", "1"));
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-hevc-mp2.ts", ffmpeg("ts", args(video(32, 32, 2),
                tone(16000, 0.2)), "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=0",
                "-c:a", "mp2", "-b:a", "32k"));

        copy(DemuxerFuzzTest.class, "ac3", "valid-ac3.ac3", ffmpeg("ac3", tone(48000, 0.1), "-c:a", "ac3",
                "-b:a", "64k"));
        copy(DemuxerFuzzTest.class, "ac3", "valid-eac3.eac3", ffmpeg("eac3", tone(48000, 0.1), "-c:a", "eac3",
                "-b:a", "64k"));

        write(DemuxerFuzzTest.class, "subRip", "valid-cues.srt", SRT.getBytes(StandardCharsets.UTF_8));
        write(DemuxerFuzzTest.class, "webVtt", "valid-cues.vtt", VTT.getBytes(StandardCharsets.UTF_8));

        copy(ProbeFuzzTest.class, "probe", "valid-h264-aac.mp4", mp4);
        copy(ProbeFuzzTest.class, "probe", "valid-vp9-opus-srt.mkv", mkv);
        copy(ProbeFuzzTest.class, "probe", "valid-mp3-id3.mp3", mp3);
        copy(ProbeFuzzTest.class, "probe", "valid-s16.wav", wav);

        copy(TagFuzzTest.class, "tags", "valid-mp3-id3.mp3", mp3);
        copy(TagFuzzTest.class, "tags", "valid-flac.flac", flac);
        copy(TagFuzzTest.class, "tags", "valid-alac.m4a", m4a);
        copy(TagFuzzTest.class, "tags", "valid-vorbis.ogg", vorbis);
        copy(TagFuzzTest.class, "tags", "valid-opus.opus", opus);
        copy(TagFuzzTest.class, "tags", "valid-s16.wav", wav);
        copy(TagFuzzTest.class, "tags", "valid-vp9-opus-srt.mkv", mkv);
        write(TagFuzzTest.class, "xmp", "valid-packet.xmp", XMP.getBytes(StandardCharsets.UTF_8));

        copy(SubtitleFuzzTest.class, "read", "valid-cues.srt", Files.writeString(srt, SRT));
        write(SubtitleFuzzTest.class, "read", "valid-cues.vtt", VTT.getBytes(StandardCharsets.UTF_8));
        copy(SubtitleFuzzTest.class, "read", "valid-mov-text.mp4", ffmpeg("mp4", args(video(32, 32, 2),
                List.of("-i", srt.toString())), "-map", "0", "-map", "1", "-c:v", "libx264", "-preset", "ultrafast",
                "-c:s", "mov_text"));
        copy(SubtitleFuzzTest.class, "read", "valid-ass.mkv", ffmpeg("mkv", args(video(32, 32, 2),
                List.of("-i", srt.toString())), "-map", "0", "-map", "1", "-c:v", "libvpx", "-deadline", "realtime",
                "-c:s", "ass"));
        Files.delete(srt);
    }

    @Test
    @DisplayName("copies demuxer seeds to the remux target")
    void remux() {
        // the remuxer reads ISO base media, Matroska, MPEG-TS, AVI and Ogg files
        for (var seed : List.of("mp4/valid-h264-aac.mp4", "mp4/valid-fragmented.mp4", "mp4/valid-alac.m4a",
                "matroska/valid-h264-ac3.mkv", "ogg/valid-opus.opus", "avi/valid-mpeg4-mp3.avi",
                "mpegTs/valid-h264-aac.ts")) {
            var file = FuzzSeeds.directory(DemuxerFuzzTest.class, seed.substring(0, seed.indexOf('/')))
                    .resolve(seed.substring(seed.indexOf('/') + 1));
            copy(RemuxFuzzTest.class, "mp4", file.getFileName().toString(), file);
        }
        copy(RemuxFuzzTest.class, "mp4", "valid-mov-text.mp4", FuzzSeeds.directory(SubtitleFuzzTest.class, "read")
                .resolve("valid-mov-text.mp4"));
    }

    @Test
    @DisplayName("writes the seeds of the subtitle cue and markup targets")
    void cues() {
        write(SubtitleFuzzTest.class, "cues", "valid-subrip.bin", cues(0, null, "<i>Hello</i>\nworld", "two"));
        write(SubtitleFuzzTest.class, "cues", "valid-webvtt.bin", cues(1, null,
                "<v Ann>Hello <c.loud>there</c></v>", "<b>two</b>"));
        // "Hello" with a styl box making it italic at 18 px
        var movText = new byte[]{0, 5, 'H', 'e', 'l', 'l', 'o', 0, 0, 0, 22, 's', 't', 'y', 'l', 0, 1, 0, 0, 0, 5,
                0, 1, 2, 18, -1, -1, -1, -1};
        write(SubtitleFuzzTest.class, "cues", "valid-mov-text.bin", cues(2, null, new String(movText,
                StandardCharsets.ISO_8859_1), "\u0000\u0003two"));
        write(SubtitleFuzzTest.class, "cues", "valid-ass.bin", cues(3, ASS_HEADER,
                "0,0,Default,,0,0,0,,{\\i1}Hello{\\i0}\\Nworld", "1,0,Default,,0,0,0,,two"));
        write(SubtitleFuzzTest.class, "markup", "valid-subrip.txt",
                "<i>it</i> <b>bold</b> <font color=\"red\">red</font>\n{\\an8}top".getBytes(StandardCharsets.UTF_8));
        write(SubtitleFuzzTest.class, "markup", "valid-webvtt.txt",
                "<v Ann>Hi</v> <c.yellow.bg_blue>c</c> &amp; <00:00:01.000>k".getBytes(StandardCharsets.UTF_8));
        write(SubtitleFuzzTest.class, "markup", "valid-ass.txt",
                "{\\i1}it{\\i0}\\N{\\b1\\c&H00FF00&}green{\\r}\\h end".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("writes the seeds of the ICC and PCM targets")
    void profilesAndPcm() throws IOException {
        write(ColorFuzzTest.class, "icc", "valid-srgb.icc", ColorProfile.srgb().toByteArray());
        write(ColorFuzzTest.class, "icc", "valid-display-p3.icc", ColorProfile.displayP3().toByteArray());
        write(ColorFuzzTest.class, "icc", "valid-rec2020.icc", ColorProfile.rec2020().toByteArray());
        write(ColorFuzzTest.class, "icc", "valid-gray.icc", ColorProfile.grayGamma22().toByteArray());
        write(ColorFuzzTest.class, "icc", "valid-lcms-pq.icc", Files.readAllBytes(Path.of("src", "test", "resources",
                "com", "github", "auties00", "smalt", "image", "color", "hdr", "lcms-pq.icc")));
        for (var name : new String[]{"mpe-rgb", "link-mpe-rgb", "abstract-mpe-lab", "named-cmyk"}) {
            write(ColorFuzzTest.class, "icc", "valid-" + name + ".icc", Files.readAllBytes(Path.of("src", "test",
                    "resources", "com", "github", "auties00", "smalt", "image", "color", "advanced", name + ".icc")));
        }
        var codecs = Arrays.stream(Codec.values()).filter(Codec::isPcm).toList();
        var s16 = ffmpeg("wav", tone(8000, 0.02), "-c:a", "pcm_s16le", "-ac", "2");
        write(PcmFuzzTest.class, "pcm", "valid-s16le.bin", FuzzSeeds.packets(s16, Codec.PCM_S16LE,
                codecs.indexOf(Codec.PCM_S16LE) << 1, 8));
        var alaw = ffmpeg("wav", tone(8000, 0.02), "-c:a", "pcm_alaw");
        write(PcmFuzzTest.class, "pcm", "valid-alaw.bin", FuzzSeeds.packets(alaw, Codec.PCM_ALAW,
                codecs.indexOf(Codec.PCM_ALAW) << 1, 8));
        var f32 = ffmpeg("wav", tone(8000, 0.02), "-c:a", "pcm_f32le");
        write(PcmFuzzTest.class, "pcm", "valid-f32le.bin", FuzzSeeds.packets(f32, Codec.PCM_F32LE,
                codecs.indexOf(Codec.PCM_F32LE) << 1, 8));
    }

    private static byte[] cues(int codec, String config, String... texts) {
        // mov_text samples are binary, carried here as ISO 8859-1 strings
        var charset = codec == 2 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
        var parts = Arrays.stream(texts).map(text -> new FuzzPackets.Part(text.getBytes(charset), true)).toList();
        var header = new byte[1 + (config == null ? 0 : config.getBytes(StandardCharsets.UTF_8).length)];
        header[0] = (byte) codec;
        if (config != null) {
            var bytes = config.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, header, 1, bytes.length);
        }
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(header);
        for (var part : parts) {
            out.writeBytes(FuzzPackets.MARKER);
            out.write(1);
            out.writeBytes(part.data());
        }
        return out.toByteArray();
    }
}
