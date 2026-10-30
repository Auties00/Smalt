package com.github.auties00.smalt.fuzz;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Attachment;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ChapterEdition;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.aiff.AiffMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.container.PresentationSamples;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzPresentations;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams;
import com.github.auties00.smalt.testkit.subtitle.CaptionScript;
import com.github.auties00.smalt.testkit.subtitle.TeletextWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.SphericalVideo;
import com.github.auties00.smalt.track.StereoMode;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoFormat;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
        write(DemuxerFuzzTest.class, "mp4", "valid-compressed-moov.mp4", compressBoxes(Files.readAllBytes(mp4),
                java.util.Set.of("moov")));
        var fragmented = ffmpeg("mp4", av, "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "16k",
                "-ac", "1", "-movflags", "frag_keyframe+empty_moov+default_base_moof");
        copy(DemuxerFuzzTest.class, "mp4", "valid-fragmented.mp4", fragmented);
        write(DemuxerFuzzTest.class, "mp4", "valid-compressed-fragments.mp4", compressBoxes(Files.readAllBytes(fragmented),
                java.util.Set.of("moov", "moof")));
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
        copy(DemuxerFuzzTest.class, "matroska", "valid-vp8-alpha.webm", ffmpeg("webm", video(32, 32, 2), "-c:v",
                "libvpx", "-pix_fmt", "yuva420p", "-auto-alt-ref", "0", "-deadline", "realtime", "-b:v", "50k", "-vf",
                "format=yuva420p,geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='8*X'"));
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
        copy(DemuxerFuzzTest.class, "wav", "valid-ima-adpcm.wav", ffmpeg("wav", tone(8000, 0.1),
                "-c:a", "adpcm_ima_wav"));
        copy(DemuxerFuzzTest.class, "wav", "valid-ms-adpcm.wav", ffmpeg("wav", tone(8000, 0.1), "-c:a", "adpcm_ms"));
        // GSM 06.10 from the behavior sample libgsm encoded (tools/testdata/behavior/make-wav-samples.py), since the
        // ffmpeg of every runner has no GSM encoder
        copy(DemuxerFuzzTest.class, "wav", "valid-gsm-ms.wav", Path.of("src", "test", "resources", "samples", "wav",
                "gsm.wav"));
        copy(DemuxerFuzzTest.class, "wav", "valid-g726.wav", ffmpeg("wav", tone(8000, 0.1), "-c:a", "g726",
                "-b:a", "40k"));
        copy(DemuxerFuzzTest.class, "wav", "valid-g722.wav", ffmpeg("wav", tone(16000, 0.1), "-c:a", "g722"));
        // the ADPCM and telephony behavior samples outside WAVE (tools/testdata/behavior/make-adpcm-samples.py)
        for (var sample : new String[][]{{"aiff", "ima4.aifc"}, {"mp4", "gsm.mov"}, {"mp4", "ms-adpcm.mov"},
                {"avi", "ms-adpcm.avi"}, {"matroska", "ima-adpcm.mka"}}) {
            copy(DemuxerFuzzTest.class, sample[0], "valid-" + sample[1], Path.of("src", "test", "resources",
                    "samples", sample[0], sample[1]));
        }

        var flac = ffmpeg("flac", tone(8000, 0.1), "-c:a", "flac", "-metadata", "title=seed");
        copy(DemuxerFuzzTest.class, "flac", "valid-flac.flac", flac);

        copy(DemuxerFuzzTest.class, "amr", "valid-nb.amr", ffmpeg("amr", tone(8000, 0.2),
                "-c:a", "libopencore_amrnb", "-ar", "8000"));
        copy(DemuxerFuzzTest.class, "amr", "valid-wb.amr", ffmpeg("amr", tone(16000, 0.2),
                "-c:a", "libvo_amrwbenc", "-ar", "16000"));
        // multichannel files: the first frame-blocks of the behavior samples of make-amr-mc-samples.py
        var amrSamples = Path.of("src", "test", "resources", "samples", "amr");
        write(DemuxerFuzzTest.class, "amr", "valid-nb-stereo.amr",
                Arrays.copyOf(Files.readAllBytes(amrSamples.resolve("nb-stereo.amr")), 16 + 10 * 2 * 32));
        write(DemuxerFuzzTest.class, "amr", "valid-wb-3ch.amr",
                Arrays.copyOf(Files.readAllBytes(amrSamples.resolve("wb-3ch.awb")), 19 + 6 * 3 * 61));

        copy(DemuxerFuzzTest.class, "aiff", "valid-s16.aiff", ffmpeg("aiff", tone(8000, 0.05),
                "-c:a", "pcm_s16be"));

        // the behavior samples of tools/testdata/behavior/make-audio-container-samples.py
        var audioSamples = Path.of("src", "test", "resources", "samples");
        copy(DemuxerFuzzTest.class, "caf", "valid-opus.caf", audioSamples.resolve("caf/opus.caf"));
        copy(DemuxerFuzzTest.class, "caf", "valid-ima4.caf", audioSamples.resolve("caf/ima4.caf"));
        copy(DemuxerFuzzTest.class, "w64", "valid-ima.w64", audioSamples.resolve("w64/ima.w64"));
        copy(DemuxerFuzzTest.class, "au", "valid-g721.au", audioSamples.resolve("au/g721.au"));
        copy(DemuxerFuzzTest.class, "au", "valid-mulaw.au", audioSamples.resolve("au/mulaw.au"));
        copy(DemuxerFuzzTest.class, "loas", "valid-aac.loas", audioSamples.resolve("loas/aac.loas"));
        copy(DemuxerFuzzTest.class, "adif", "valid-aac.adif", audioSamples.resolve("adif/aac.adif"));

        copy(DemuxerFuzzTest.class, "ivf", "valid-vp8.ivf", ffmpeg("ivf", video(32, 32, 2), "-c:v", "libvpx",
                "-deadline", "realtime", "-b:v", "50k"));
        copy(DemuxerFuzzTest.class, "ivf", "valid-av1.ivf", ffmpeg("ivf", video(32, 32, 2), "-c:v", "libaom-av1",
                "-cpu-used", "8", "-b:v", "50k"));

        copy(DemuxerFuzzTest.class, "avi", "valid-mjpeg-pcm.avi", ffmpeg("avi", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "mjpeg", "-q:v", "20", "-c:a", "pcm_u8"));
        copy(DemuxerFuzzTest.class, "avi", "valid-mpeg4-mp3.avi", ffmpeg("avi", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "mpeg4", "-q:v", "20", "-c:a", "libmp3lame", "-b:a", "8k"));

        var ts = ffmpeg("ts", av, "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "16k", "-ac", "1");
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-h264-aac.ts", ts);
        write(DemuxerFuzzTest.class, "mpegTs", "valid-h264-aac-names.ts", namedTracks(Files.readAllBytes(ts)));
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-hevc-mp2.ts", ffmpeg("ts", args(video(32, 32, 2),
                tone(16000, 0.2)), "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=0",
                "-c:a", "mp2", "-b:a", "32k"));

        copy(DemuxerFuzzTest.class, "ac3", "valid-ac3.ac3", ffmpeg("ac3", tone(48000, 0.1), "-c:a", "ac3",
                "-b:a", "64k"));
        copy(DemuxerFuzzTest.class, "ac3", "valid-eac3.eac3", ffmpeg("eac3", tone(48000, 0.1), "-c:a", "eac3",
                "-b:a", "64k"));
        copy(DemuxerFuzzTest.class, "dts", "valid-dts.dts", ffmpeg("dts", tone(48000, 0.1), "-c:a", "dca",
                "-strict", "-2", "-b:a", "320k"));

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
        copy(TagFuzzTest.class, "tags", "valid-mpeg4-mp3.avi", ffmpeg("avi", args(video(32, 32, 2),
                tone(8000, 0.2)), "-c:v", "mpeg4", "-q:v", "20", "-c:a", "libmp3lame", "-b:a", "8k"));
        var taggedAmr = withTags(Files.readAllBytes(ffmpeg("amr", tone(8000, 0.2), "-c:a", "libopencore_amrnb",
                "-ar", "8000")));
        write(TagFuzzTest.class, "tags", "valid-nb-id3.amr", taggedAmr);
        write(DemuxerFuzzTest.class, "amr", "valid-nb-id3.amr", taggedAmr);
        write(TagFuzzTest.class, "tags", "valid-eac3-id3.eac3", withTags(Files.readAllBytes(ffmpeg("eac3",
                tone(48000, 0.1), "-c:a", "eac3", "-b:a", "64k"))));
        write(TagFuzzTest.class, "tags", "valid-s16-rifx.wav", rifx(Files.readAllBytes(wav)));
        write(TagFuzzTest.class, "tags", "valid-h264-aac-names.ts", namedTracks(Files.readAllBytes(ts)));
        write(TagFuzzTest.class, "xmp", "valid-packet.xmp", XMP.getBytes(StandardCharsets.UTF_8));

        copy(SubtitleFuzzTest.class, "read", "valid-cues.srt", Files.writeString(srt, SRT));
        write(SubtitleFuzzTest.class, "read", "valid-cues.vtt", VTT.getBytes(StandardCharsets.UTF_8));
        for (var sample : List.of("cues.ass", "cues.ssa", "cues.ttml", "cues.smi", "cues.lrc", "cues.sub")) {
            copy(SubtitleFuzzTest.class, "read", "valid-" + sample, Path.of("src", "test", "resources", "samples",
                    "subtitle", sample));
        }
        copy(SubtitleFuzzTest.class, "read", "valid-mov-text.mp4", ffmpeg("mp4", args(video(32, 32, 2),
                List.of("-i", srt.toString())), "-map", "0", "-map", "1", "-c:v", "libx264", "-preset", "ultrafast",
                "-c:s", "mov_text"));
        copy(SubtitleFuzzTest.class, "read", "valid-ass.mkv", ffmpeg("mkv", args(video(32, 32, 2),
                List.of("-i", srt.toString())), "-map", "0", "-map", "1", "-c:v", "libvpx", "-deadline", "realtime",
                "-c:s", "ass"));
        Files.delete(srt);
    }

    @Test
    @DisplayName("writes the transport stream seeds of AV1, PCM, SCTE 35, MPEG-4 Part 2, LATM and program map changes")
    void transportStreams() throws IOException {
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-lpcm.m2ts", ffmpeg("m2ts", tone(48000, 0.02), "-c:a", "pcm_bluray",
                "-ac", "2", "-f", "mpegts", "-mpegts_m2ts_mode", "1"));
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-s302m.ts", ffmpeg("ts", tone(48000, 0.02), "-c:a", "s302m",
                "-strict", "-2", "-ac", "2"));
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-mpeg4-latm.ts", ffmpeg("ts", args(video(32, 32, 2),
                tone(16000, 0.1)), "-c:v", "mpeg4", "-q:v", "20", "-c:a", "aac", "-b:a", "16k", "-ac", "1",
                "-mpegts_flags", "latm"));
        var first = Files.readAllBytes(ffmpeg("ts", args(video(32, 32, 2), tone(16000, 0.1)), "-c:v", "libx264",
                "-preset", "ultrafast", "-c:a", "aac", "-b:a", "16k", "-ac", "1"));
        var second = Files.readAllBytes(ffmpeg("ts", args(video(32, 32, 2), tone(16000, 0.1)), "-c:v", "libx264",
                "-preset", "ultrafast", "-c:a", "mp2", "-b:a", "32k", "-mpegts_start_pid", "0x200", "-tables_version",
                "1"));
        var both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        write(DemuxerFuzzTest.class, "mpegTs", "valid-pmt-change.ts", both);
        var av1 = ffmpeg("ivf", video(32, 32, 2), "-c:v", "libaom-av1", "-cpu-used", "8", "-b:v", "50k");
        try (var source = com.github.auties00.smalt.io.MediaSource.of(av1);
             var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(source)) {
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            try (var muxer = new com.github.auties00.smalt.container.ts.TsMuxer(sink)) {
                var video = demuxer.tracks().getFirst();
                muxer.addTrack(video);
                muxer.addTrack(com.github.auties00.smalt.track.DataTrack.builder().id(video.id() + 1)
                        .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(90_000)).handler("scte35").build());
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                    if (packet.keyframe()) {
                        var time = video.timeBase().rescale(packet.pts(),
                                com.github.auties00.smalt.track.Rational.timeBaseOf(90_000));
                        muxer.writePacket(new com.github.auties00.smalt.track.Packet(video.id() + 1, spliceNull(),
                                time, time, 0, true));
                    }
                }
                muxer.finish();
            }
            write(DemuxerFuzzTest.class, "mpegTs", "valid-av1-scte35.ts", sink.toByteArray());
        }
    }

    @Test
    @DisplayName("writes the program stream seeds of MPEG-1 and MPEG-2 video, H.264, MP2, AC-3, DTS, linear PCM and"
            + " subpictures")
    void programStreams() throws IOException {
        var video = List.of("-f", "lavfi", "-i", "testsrc2=size=32x32:rate=25:duration=0.12");
        var audio = tone(48000, 0.1);
        copy(DemuxerFuzzTest.class, "mpegPs", "valid-mpeg2-mp2.vob", ffmpeg("vob", args(video, audio), "-c:v",
                "mpeg2video", "-q:v", "20", "-g", "3", "-bf", "1", "-c:a", "mp2", "-b:a", "32k", "-ac", "1", "-f", "vob"));
        copy(DemuxerFuzzTest.class, "mpegPs", "valid-mpeg1.mpg", ffmpeg("mpg", args(video, audio), "-c:v",
                "mpeg1video", "-q:v", "20", "-g", "3", "-bf", "1", "-c:a", "mp2", "-b:a", "32k", "-ac", "1", "-f",
                "mpeg"));
        copy(DemuxerFuzzTest.class, "mpegPs", "valid-dvd-ac3-lpcm.vob", ffmpeg("vob", args(video, audio), "-map", "0",
                "-map", "1", "-map", "1", "-c:v", "mpeg2video", "-q:v", "20", "-g", "3", "-bf", "1", "-c:a:0", "ac3",
                "-b:a:0", "64k", "-ac:a:0", "1", "-c:a:1", "pcm_dvd", "-ac:a:1", "1", "-f", "dvd"));
        copy(DemuxerFuzzTest.class, "mpegPs", "valid-dts.vob", ffmpeg("vob", args(video, audio), "-c:v", "mpeg2video",
                "-q:v", "20", "-g", "3", "-bf", "1", "-c:a", "dca", "-strict", "-2", "-ac", "2", "-f", "dvd"));
        copy(DemuxerFuzzTest.class, "mpegPs", "valid-h264.mpg", ffmpeg("mpg", args(video, audio), "-c:v", "libx264",
                "-preset", "ultrafast", "-g", "3", "-bf", "1", "-x264-params", "threads=1", "-c:a", "mp2", "-b:a", "32k",
                "-ac", "1", "-f", "mpeg"));
        // the first subpicture of the VobSub sample, made by make-bitmap-caption-samples.py, over a second of video
        var idx = Path.of("target", "fuzz-vobsub", "vobsub.idx");
        Files.createDirectories(idx.getParent());
        Files.copy(Path.of("src", "test", "resources", "samples", "subtitle", "vobsub.sub"),
                idx.resolveSibling("vobsub.sub"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(idx, VOBSUB_INDEX);
        copy(DemuxerFuzzTest.class, "mpegPs", "valid-subpicture.vob", ffmpeg("vob", args(List.of("-f", "lavfi", "-i",
                "testsrc2=size=32x32:rate=25:duration=1.1"), List.of("-i", idx.toString())), "-map", "0:v", "-map",
                "1:s:0", "-c:v", "mpeg2video", "-q:v", "31", "-g", "30", "-bf", "0", "-c:s", "copy", "-t", "1.1", "-f",
                "dvd"));
    }

    @Test
    @DisplayName("writes the MXF seeds: OP1a of ProRes, MPEG-2 and H.264 with PCM, OP-Atom of VC-3 and of PCM")
    void mxf() throws IOException {
        var video = List.of("-f", "lavfi", "-i", "testsrc2=size=32x32:rate=25:duration=0.12");
        var audio = tone(48000, 0.12);
        copy(DemuxerFuzzTest.class, "mxf", "valid-op1a-prores.mxf", ffmpeg("mxf", args(video, audio), "-c:v",
                "prores_ks", "-profile:v", "0", "-pix_fmt", "yuv422p10le", "-c:a", "pcm_s16le", "-ac", "1", "-f",
                "mxf"));
        copy(DemuxerFuzzTest.class, "mxf", "valid-op1a-mpeg2.mxf", ffmpeg("mxf", args(video, audio), "-c:v",
                "mpeg2video", "-q:v", "20", "-g", "3", "-bf", "1", "-c:a", "pcm_s16le", "-ac", "1", "-f", "mxf"));
        copy(DemuxerFuzzTest.class, "mxf", "valid-op1a-h264.mxf", ffmpeg("mxf", args(video, audio), "-c:v",
                "libx264", "-preset", "ultrafast", "-g", "3", "-bf", "1", "-x264-params", "threads=1", "-c:a",
                "pcm_s24le", "-ac", "1", "-f", "mxf"));
        copy(DemuxerFuzzTest.class, "mxf", "valid-opatom-dnxhr.mxf", ffmpeg("mxf", List.of("-f", "lavfi", "-i",
                "testsrc2=size=256x120:rate=25:duration=0.08"), "-c:v", "dnxhd", "-profile:v", "dnxhr_lb", "-pix_fmt",
                "yuv422p", "-f", "mxf_opatom"));
        copy(DemuxerFuzzTest.class, "mxf", "valid-opatom-pcm.mxf", ffmpeg("mxf", tone(48000, 0.04), "-c:a",
                "pcm_s24le", "-ac", "1", "-f", "mxf_opatom", "-mxf_audio_edit_rate", "25"));
    }

    /** The index of the VobSub behavior sample, as make-bitmap-caption-samples.py writes it. */
    private static final String VOBSUB_INDEX = """
            # VobSub index file, v7 (do not modify this line!)
            size: 720x480
            org: 0, 0
            scale: 100%, 100%
            alpha: 100%
            smooth: OFF
            fadein/out: 0, 0
            align: OFF at LEFT TOP
            time offset: 0
            forced subs: OFF
            palette: 000000, ffffff, 808080, ff0000, 00ff00, 0000ff, ffff00, ff00ff, 00ffff, 404040, c0c0c0, 800000, \
            008000, 000080, 808000, 800080
            custom colors: OFF, tridx: 0000, colors: 000000, 000000, 000000, 000000
            langidx: 0

            id: en, index: 0
            timestamp: 00:00:01:000, filepos: 000000000
            timestamp: 00:00:02:000, filepos: 000001000
            timestamp: 00:00:03:000, filepos: 000002800

            id: de, index: 1
            timestamp: 00:00:01:500, filepos: 000000800
            timestamp: 00:00:02:500, filepos: 000002000
            timestamp: 00:00:03:500, filepos: 000003000
            """;

    /** Returns an SCTE 35 {@code splice_info_section} holding a {@code splice_null} command. */
    private static byte[] spliceNull() {
        var section = new byte[]{(byte) 0xFC, 0x30, 0x11, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xF0, 0, 0, 0, 0,
                0, 0, 0, 0};
        var crc = com.github.auties00.smalt.internal.header.Crc32Mpeg.update(
                com.github.auties00.smalt.internal.header.Crc32Mpeg.MPEG_INIT, section, 0, section.length - 4);
        section[section.length - 4] = (byte) (crc >>> 24);
        section[section.length - 3] = (byte) (crc >>> 16);
        section[section.length - 2] = (byte) (crc >>> 8);
        section[section.length - 1] = (byte) crc;
        return section;
    }

    @Test
    @DisplayName("writes the seeds of QuickTime compressed movies, compressed Matroska frames and the decompressors")
    void compression() throws IOException {
        var mov = Files.readAllBytes(Path.of("src", "test", "resources", "samples", "mp4", "prores.mov"));
        write(DemuxerFuzzTest.class, "mp4", "valid-cmov.mov", quickTimeCompressed(mov));
        var vectors = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "internal", "compress");
        for (var algorithm : new int[]{1, 2}) {
            var format = algorithm == 1 ? "bzip2" : "lzo";
            var extension = algorithm == 1 ? ".bz2" : ".lzo";
            var frames = List.of(Files.readAllBytes(vectors.resolve(format).resolve("frame-12" + extension)),
                    Files.readAllBytes(vectors.resolve(format).resolve("frame-14" + extension)));
            write(DemuxerFuzzTest.class, "matroska", "valid-" + (algorithm == 1 ? "bzlib" : "lzo") + ".mkv",
                    compressedMatroska(algorithm, frames));
        }
        for (var name : List.of("randomized.bz2", "four-same-9.bz2", "all-bytes-1.bz2", "text-9.bz2", "concatenated.bz2")) {
            copy(CompressionFuzzTest.class, "bzip2", "valid-" + name, vectors.resolve("bzip2").resolve(name));
        }
        for (var name : List.of("m1-short.lzo", "m1-far.lzo", "text.lzo", "all-bytes.lzo", "empty.lzo")) {
            copy(CompressionFuzzTest.class, "lzo1x", "valid-" + name, vectors.resolve("lzo").resolve(name));
        }
    }

    @Test
    @DisplayName("writes the seeds of chapter editions, attachments, timecodes, spherical video and descriptive chunks")
    void descriptive() throws IOException {
        var editions = List.of(new ChapterEdition(List.of(new Chapter(0, 50_000, Optional.of("Intro"),
                        List.of(new Chapter.Title("Intro", "eng"), new Chapter.Title("Introduction", "fr-CA")),
                        List.of(Chapter.of(10_000, 20_000, "inner"))), Chapter.of(50_000, 100_000, "Rest")), false, false,
                List.of(new Chapter.Title("Cut", "en"))),
                new ChapterEdition(List.of(Chapter.of(0, 100_000, "All")), true, true, List.of()));
        var mkvTags = MediaTags.builder().editions(editions).add("timecode", "01:00:00:05")
                .addAttachment(Attachment.of("font.ttf", "font/ttf", "", new byte[64])).build();
        var spherical = new SphericalVideo(new SphericalVideo.Equirectangular(1, 2, 3, 4), 10, -5, 2.5, "seed");
        var video = Containers.openDemuxer(MediaSource.of(ffmpeg("mkv", video(32, 32, 2), "-c:v", "mpeg4")));
        var tracks = new java.util.ArrayList<com.github.auties00.smalt.track.Track>();
        var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
        try (video) {
            for (var track : video.tracks()) {
                tracks.add(track instanceof VideoTrack picture ? picture.toBuilder().spherical(spherical)
                        .stereoMode(StereoMode.TOP_BOTTOM_LEFT_FIRST).build() : track);
            }
            for (var packet = video.read(); packet != null; packet = video.read()) {
                packets.add(packet);
            }
        }
        var mkv = muxed(MatroskaMuxer::new, tracks, packets, mkvTags);
        write(DemuxerFuzzTest.class, "matroska", "valid-editions-spherical.mkv", mkv);
        write(TagFuzzTest.class, "tags", "valid-editions-spherical.mkv", mkv);
        var mov = muxed(sink -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime()), tracks, packets,
                MediaTags.builder().add("timecode", "01:00:00:05").build());
        write(DemuxerFuzzTest.class, "mp4", "valid-timecode-spherical.mov", mov);
        var chapters = List.of(Chapter.of(0, 40_000, "one"), Chapter.of(40_000, 100_000, "two"));
        var wavTags = MediaTags.builder().chapters(chapters).add("originator", "seed").add("time_reference", "8000")
                .add("coding_history", "A=PCM").add("ixml", "<BWFXML/>").add("chna", "1 ATU_00000001 AT_00010001_01 AP_00010002")
                .add("smpl:unity_note", "60").add("smpl:loop", "0 0 10 20 0 0").build();
        var wav = withMuxedTags(ffmpeg("wav", tone(8000, 0.1), "-c:a", "pcm_s16le"), WavMuxer::new, wavTags);
        write(DemuxerFuzzTest.class, "wav", "valid-bext-cue.wav", wav);
        write(TagFuzzTest.class, "tags", "valid-bext-cue.wav", wav);
        var aiffTags = MediaTags.builder().chapters(chapters).add("inst:base_note", "60")
                .add("inst:sustain_loop", "1 10 20").build();
        var aiff = withMuxedTags(ffmpeg("aiff", tone(8000, 0.1), "-c:a", "pcm_s16be"), AiffMuxer::new, aiffTags);
        write(DemuxerFuzzTest.class, "aiff", "valid-markers.aiff", aiff);
        write(TagFuzzTest.class, "tags", "valid-markers.aiff", aiff);
        var flac = withMuxedTags(ffmpeg("flac", tone(8000, 0.1), "-c:a", "flac"), RawMuxer::new,
                MediaTags.builder().chapters(chapters).build());
        write(DemuxerFuzzTest.class, "flac", "valid-cuesheet.flac", flac);
        write(TagFuzzTest.class, "tags", "valid-cuesheet.flac", flac);
    }

    @Test
    @DisplayName("writes the tag seeds that carry XMP packets, QuickTime keyed metadata and 3GPP locations")
    void xmpAndPhoneMetadata() throws IOException {
        var samples = Path.of("src", "test", "resources", "samples", "mp4");
        copy(TagFuzzTest.class, "tags", "valid-phone-keyed.mov", samples.resolve("phone-keyed.mov"));
        copy(TagFuzzTest.class, "tags", "valid-location-xmp.mp4", samples.resolve("location-xmp.mp4"));
        var seeds = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "fuzz",
                "TagFuzzTestInputs", "tags");
        var packet = com.github.auties00.smalt.metadata.XmpPacket.parse(XMP);
        for (var base : List.of("valid-s16.wav", "valid-markers.aiff", "valid-mp3-id3.mp3")) {
            var data = Files.readAllBytes(seeds.resolve(base));
            var name = "valid-xmp-" + base.substring("valid-".length());
            write(TagFuzzTest.class, "tags", name, TagEditor.write(data, TagEditor.read(MediaSource.of(data)).toBuilder()
                    .set(StandardTag.TITLE, "seed").xmp(packet).build(), TagWriteOptions.defaults()));
        }
    }

    private static byte[] muxed(java.util.function.Function<MediaSink, com.github.auties00.smalt.container.Muxer> factory,
                                List<com.github.auties00.smalt.track.Track> tracks,
                                List<com.github.auties00.smalt.track.Packet> packets, MediaTags tags) {
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            tracks.forEach(muxer::addTrack);
            muxer.setTags(tags);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static byte[] withMuxedTags(Path file, java.util.function.Function<MediaSink,
            com.github.auties00.smalt.container.Muxer> factory, MediaTags tags) {
        var sink = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file)); var muxer = factory.apply(sink)) {
            demuxer.tracks().forEach(muxer::addTrack);
            muxer.setTags(tags);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Replaces the movie box of a file by a QuickTime compressed movie: cmov holding dcom zlib and cmvd. */
    private static byte[] quickTimeCompressed(byte[] file) {
        var out = new java.io.ByteArrayOutputStream();
        var position = 0;
        while (position + 8 <= file.length) {
            var size = java.nio.ByteBuffer.wrap(file, position, 4).getInt();
            var type = new String(file, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("moov")) {
                var deflater = new java.util.zip.Deflater();
                deflater.setInput(file, position, size);
                deflater.finish();
                var compressed = new java.io.ByteArrayOutputStream();
                var buffer = new byte[8192];
                while (!deflater.finished()) {
                    compressed.write(buffer, 0, deflater.deflate(buffer));
                }
                deflater.end();
                var cmvd = java.nio.ByteBuffer.allocate(4 + compressed.size()).putInt(size).put(compressed.toByteArray())
                        .array();
                out.writeBytes(atom("moov", atom("cmov", concat(atom("dcom", "zlib".getBytes(StandardCharsets.ISO_8859_1)),
                        atom("cmvd", cmvd)))));
            } else {
                out.write(file, position, size);
            }
            position += size;
        }
        return out.toByteArray();
    }

    private static byte[] atom(String type, byte[] payload) {
        return java.nio.ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload).array();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Writes a Matroska file of one 16-bit mono PCM track whose frames are compressed with a ContentCompAlgo. */
    private static byte[] compressedMatroska(int algorithm, List<byte[]> frames) {
        var header = element(0x1A45DFA3, concat(uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
                element(0x4282, "matroska".getBytes(StandardCharsets.US_ASCII)), uint(0x4287, 4), uint(0x4285, 2)));
        var info = element(0x1549A966, uint(0x2AD7B1, 1_000_000));
        var encoding = element(0x6D80, element(0x6240, concat(uint(0x5032, 1), element(0x5034, uint(0x4254, algorithm)))));
        var audio = element(0xE1, concat(element(0xB5, java.nio.ByteBuffer.allocate(8).putDouble(8000).array()),
                uint(0x9F, 1), uint(0x6264, 16)));
        var track = element(0xAE, concat(uint(0xD7, 1), uint(0x73C5, 1), uint(0x83, 2),
                element(0x86, "A_PCM/INT/LIT".getBytes(StandardCharsets.US_ASCII)), audio, encoding));
        var blocks = new java.io.ByteArrayOutputStream();
        blocks.writeBytes(uint(0xE7, 0));
        for (var index = 0; index < frames.size(); index++) {
            blocks.writeBytes(element(0xA3, concat(new byte[]{(byte) 0x81, (byte) (index >> 0), 0, (byte) 0x80},
                    frames.get(index))));
        }
        var segment = concat(info, element(0x1654AE6B, track), element(0x1F43B675, blocks.toByteArray()));
        return concat(header, element(0x18538067, segment));
    }

    private static byte[] element(long id, byte[] payload) {
        var out = new java.io.ByteArrayOutputStream();
        var idLength = (64 - Long.numberOfLeadingZeros(id) + 7) / 8;
        for (var i = idLength - 1; i >= 0; i--) {
            out.write((int) (id >>> (8 * i)));
        }
        long size = payload.length;
        var length = 1;
        while (size >= (1L << (7 * length)) - 1) {
            length++;
        }
        var marked = size | (1L << (7 * length));
        for (var i = length - 1; i >= 0; i--) {
            out.write((int) (marked >>> (8 * i)));
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] uint(long id, long value) {
        var length = Math.max(1, (64 - Long.numberOfLeadingZeros(value) + 7) / 8);
        var bytes = new byte[length];
        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) (value >>> (8 * (length - 1 - i)));
        }
        return element(id, bytes);
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
    @DisplayName("writes tagged seeds, with chapters and a cover, of the remux tags target")
    void remuxTags() throws IOException {
        var metadata = Files.createDirectories(Path.of("target", "media-fixtures")).resolve("remux-tags-seed.txt");
        Files.writeString(metadata, ";FFMETADATA1\ntitle=Seed Title\nartist=Seed Artist\nalbum=Seed Album\n\n"
                + "[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=100\ntitle=One\n\n"
                + "[CHAPTER]\nTIMEBASE=1/1000\nSTART=100\nEND=200\ntitle=Two\n");
        var cover = ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=16x16:rate=1"), "-frames:v", "1");
        var sources = FuzzSeeds.directory(RemuxFuzzTest.class, "mp4");
        copy(RemuxFuzzTest.class, "tags", "valid-tagged.m4a", ffmpeg("m4a", List.of("-i",
                sources.resolve("valid-alac.m4a").toString(), "-i", metadata.toString(), "-i", cover.toString()),
                "-map", "0", "-map", "2", "-c", "copy", "-disposition:v", "attached_pic", "-map_metadata", "1",
                "-map_chapters", "1"));
        copy(RemuxFuzzTest.class, "tags", "valid-tagged.mkv", ffmpeg("mkv", List.of("-i",
                sources.resolve("valid-h264-ac3.mkv").toString(), "-i", metadata.toString()), "-map", "0", "-c", "copy",
                "-map_metadata", "1", "-map_chapters", "1", "-attach", cover.toString(), "-metadata:s:t",
                "mimetype=image/png", "-metadata:s:t", "filename=cover.png"));
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
        write(SubtitleFuzzTest.class, "cues", "valid-ttml.bin", cues(4, null, TTML_SEED));
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
    @DisplayName("writes the seeds of the bitmap subtitle, caption and closed caption targets and demuxers")
    void bitmapsAndCaptions() {
        var header = ("# VobSub index file, v7 (do not modify this line!)\nsize: 720x480\npalette: 000000, ffffff, "
                + "808080, ff0000, 00ff00, 0000ff, ffff00, ff00ff, 00ffff, 404040, c0c0c0, 800000, 008000, 000080, "
                + "808000, 800080\n").getBytes(StandardCharsets.UTF_8);
        byte[] service = {'e', 'n', 'g', 0x10, 0, 1, 0, 1};
        var pgsIndices = BitmapStreams.indices(24, 6, (x, y) -> (x / 4 + y) % 3);
        var pgsShow = join(BitmapStreams.pcs(720, 480, 0, 0x80, false, 0, new int[]{1, 0, 10, 400, 0}),
                BitmapStreams.wds(new int[]{0, 10, 400, 24, 6}), BitmapStreams.pds(0, 0, new int[]{1, 235, 128, 128, 255},
                        new int[]{2, 81, 90, 240, 160}),
                join(BitmapStreams.ods(1, 24, 6, pgsIndices, 1000).toArray(new byte[0][])), BitmapStreams.pgsEnd());
        var pgsClear = join(BitmapStreams.pcs(720, 480, 1, 0, false, 0), BitmapStreams.wds(), BitmapStreams.pgsEnd());
        var dvbPixels = new BitmapStreams.DvbPixels().string(4).run4(10, 1).run4(6, 2).end4().endLine().bytes();
        var dvbShow = join(BitmapStreams.dvbPage(1, 5, 2, new int[]{0, 20, 500}),
                BitmapStreams.dvbRegion(1, 0, true, 16, 2, 2, 0, 0, new int[]{1, 0, 0}),
                BitmapStreams.dvbObject(1, 1, dvbPixels, new byte[0], false), BitmapStreams.dvbEnd(1));
        var dvbClear = join(BitmapStreams.dvbPage(1, 5, 2), BitmapStreams.dvbEnd(1));
        var spuIndices = BitmapStreams.indices(16, 4, (x, y) -> (x + y) % 4);
        var spu = BitmapStreams.spu(16, 4, spuIndices, 40, 400, 0, 20, new int[]{3, 2, 1, 0}, new int[]{15, 15, 8, 0},
                false);
        write(SubtitleFuzzTest.class, "bitmaps", "valid-pgs.bin", packets(0, null, pgsShow, pgsClear));
        write(SubtitleFuzzTest.class, "bitmaps", "valid-dvb.bin", packets(1, service, dvbShow, dvbClear));
        write(SubtitleFuzzTest.class, "bitmaps", "valid-dvd.bin", packets(2, header, spu));
        var colcon = BitmapStreams.spu(16, 4, spuIndices, 40, 400, 0, 20, new int[]{3, 2, 1, 0},
                new int[]{15, 15, 8, 0}, false, BitmapStreams.colcon(new int[]{400, 401, 44, 0x4560, 0xFF80, 50, 0x1230,
                        0xF0F0}, new int[]{403, 410, 30, 0x7890, 0xFFF0}));
        write(SubtitleFuzzTest.class, "bitmaps", "valid-dvd-colcon.bin", packets(2, header, colcon));

        var ticks = com.github.auties00.smalt.track.Rational.timeBaseOf(90_000);
        var pgs = com.github.auties00.smalt.track.DataTrack.builder().id(1).codec(Codec.PGS).timeBase(ticks).build();
        var pgsPackets = List.of(new com.github.auties00.smalt.track.Packet(1, pgsShow, 9000, 9000, -1, true),
                new com.github.auties00.smalt.track.Packet(1, pgsClear, 18000, 18000, -1, false));
        var sup = mux(List.of(pgs), pgsPackets, com.github.auties00.smalt.container.subtitle.SupMuxer::new);
        var mkv = mux(List.of(pgs), pgsPackets, com.github.auties00.smalt.container.matroska.MatroskaMuxer::new);
        var dvb = com.github.auties00.smalt.track.DataTrack.builder().id(1).codec(Codec.DVB_SUBTITLE).timeBase(ticks)
                .codecConfig(service).build();
        var ts = mux(List.of(dvb), List.of(new com.github.auties00.smalt.track.Packet(1, dvbShow, 9000, 9000, -1, true),
                new com.github.auties00.smalt.track.Packet(1, dvbClear, 90000, 90000, -1, true)), TsMuxer::new);
        var dvd = com.github.auties00.smalt.track.DataTrack.builder().id(1).codec(Codec.DVD_SUBTITLE).timeBase(ticks)
                .codecConfig(header).build();
        var idx = MediaSink.memory();
        var sub = MediaSink.memory();
        try (var muxer = new com.github.auties00.smalt.container.subtitle.VobSubMuxer(idx, sub)) {
            muxer.addTrack(dvd);
            muxer.writePacket(new com.github.auties00.smalt.track.Packet(1, spu, 9000, 9000, 20 * 1024, true));
            muxer.finish();
        }
        write(SubtitleFuzzTest.class, "readBitmaps", "valid-pgs.sup", sup);
        write(SubtitleFuzzTest.class, "readBitmaps", "valid-pgs.mkv", mkv);
        write(SubtitleFuzzTest.class, "readBitmaps", "valid-dvb.ts", ts);
        write(SubtitleFuzzTest.class, "readBitmaps", "valid-vobsub.sub", sub.toByteArray());
        write(DemuxerFuzzTest.class, "sup", "valid-pgs.sup", sup);
        write(DemuxerFuzzTest.class, "vobSub", "valid-vobsub.sub", sub.toByteArray());
        write(DemuxerFuzzTest.class, "mpegTs", "valid-dvb-subtitles.ts", ts);

        var teletext = new TeletextWriter().header(8, 0x88, true, true, false, 1)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B, 0x03}, "Teletext", 0x7B, 0x0A, 0x0A))
                .enhancements(8, 62 | 0x04 << 6, 4 | 0x12 << 6 | 'e' << 11).packet();
        var erase = new TeletextWriter().header(8, 0x88, true, true, false, 1).packet();
        write(SubtitleFuzzTest.class, "captions", "valid-teletext.bin", packets(0,
                new byte[]{'g', 'e', 'r', 0x02 << 3, (byte) 0x88}, teletext, erase));
        var arabic = new TeletextWriter().header(8, 0x88, true, true, false, 0b111)
                .row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "(12) &SQOI{~S", 0x0A, 0x0A))
                .enhancements(8, 62 | 0x04 << 6, 3 | 0x0F << 6 | 0x3C << 11).packet();
        write(SubtitleFuzzTest.class, "captions", "valid-teletext-arabic.bin", packets(0,
                new byte[]{'a', 'r', 'a', 0x02 << 3, (byte) 0x88}, arabic, erase));
        var script = new CaptionScript().command(0, 1, 0x20).preamble(1, 1, 15, 0).text(2, 1, "Pop on")
                .control(5, 1, 0x11, 0x2E).text(6, 1, "x").command(8, 1, 0x2F).command(16, 1, 0x2C)
                .service(1, 1, join(new byte[]{(byte) 0x98, 0x38, 0x4A, 0, 0, 0x1F, 0}, "dtv".getBytes(
                        StandardCharsets.US_ASCII))).service(16, 1, new byte[]{(byte) 0x8C, 0x01});
        var cc = script.build(20, 10);
        var field1 = new byte[cc.length][];
        for (var frame = 0; frame < cc.length; frame++) {
            field1[frame] = Arrays.copyOf(cc[frame], 6);
        }
        write(SubtitleFuzzTest.class, "captions", "valid-cea608.bin", packets(1, null, field1));
        write(SubtitleFuzzTest.class, "captions", "valid-cea708.bin", packets(20, null, cc));

        var h264 = new byte[cc.length][];
        var hevc = new byte[cc.length][];
        var mpeg2 = new byte[cc.length][];
        for (var frame = 0; frame < cc.length; frame++) {
            h264[frame] = prefixed(CaptionScript.seiUnit(cc[frame], false));
            hevc[frame] = prefixed(CaptionScript.seiUnit(cc[frame], true));
            mpeg2[frame] = join(new byte[]{0, 0, 1, 0, 0, 0x0F, (byte) 0xFF, (byte) 0xF8}, new byte[]{0, 0, 1,
                    (byte) 0xB2}, CaptionScript.ga94(cc[frame]), new byte[]{0, 0, 1, 1, 0x12, 0x34});
        }
        write(SubtitleFuzzTest.class, "closedCaptions", "valid-h264.bin", packets(0, null, h264));
        write(SubtitleFuzzTest.class, "closedCaptions", "valid-hevc.bin", packets(1, null, hevc));
        write(SubtitleFuzzTest.class, "closedCaptions", "valid-mpeg2.bin", packets(2, null, mpeg2));
    }

    /** Returns a NAL unit behind a four-byte length. */
    private static byte[] prefixed(byte[] unit) {
        return join(new byte[]{(byte) (unit.length >>> 24), (byte) (unit.length >>> 16), (byte) (unit.length >>> 8),
                (byte) unit.length}, unit);
    }

    /** Joins byte arrays. */
    private static byte[] join(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Returns the packets of a FuzzPackets input with one header field and a configuration, every packet key. */
    private static byte[] packets(int options, byte[] config, byte[]... packets) {
        var out = new java.io.ByteArrayOutputStream();
        out.write(options);
        if (config != null) {
            out.writeBytes(config);
        }
        for (var packet : packets) {
            out.writeBytes(FuzzPackets.MARKER);
            out.write(1);
            out.writeBytes(packet);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("writes ambisonic seeds: SA3D in MP4, the B-format WAVE sub-format and Opus mapping family 2")
    void ambisonic() throws IOException {
        var foa = ffmpeg("wav", tone(8000, 0.05), "-c:a", "pcm_s16le", "-ac", "6");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(foa))) {
            var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            var track = (com.github.auties00.smalt.track.AudioTrack) demuxer.tracks().getFirst();
            var headLocked = track.toBuilder().layout(ChannelLayout.ofAmbisonics(Ambisonics.of(1),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT))).build();
            write(DemuxerFuzzTest.class, "mp4", "valid-ambisonic-pcm.mp4", mux(List.of(headLocked), packets,
                    sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.MP4).build())));
        }
        var bFormat = ffmpeg("wav", tone(8000, 0.05), "-c:a", "pcm_s16le", "-ac", "4");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(bFormat))) {
            var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            var track = (com.github.auties00.smalt.track.AudioTrack) demuxer.tracks().getFirst();
            write(DemuxerFuzzTest.class, "wav", "valid-ambisonic-bformat.wav", mux(List.of(track.toBuilder()
                    .layout(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4))).build()), packets, WavMuxer::new));
        }
        copy(DemuxerFuzzTest.class, "ogg", "valid-opus-ambisonic.opus", ffmpeg("opus", tone(48000, 0.1),
                "-ac", "4", "-c:a", "libopus", "-mapping_family", "2", "-b:a", "48k"));
    }

    /** Muxes packets into memory. */
    private static byte[] mux(List<com.github.auties00.smalt.track.Track> tracks,
                              List<com.github.auties00.smalt.track.Packet> packets,
                              java.util.function.Function<MediaSink, com.github.auties00.smalt.container.Muxer> factory) {
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            tracks.forEach(muxer::addTrack);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
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

    /** Adds an ID3v2.4 tag without padding and an ID3v1 tag, both titled "Seed", to a raw stream. */
    private static byte[] withTags(byte[] stream) {
        return TagEditor.write(stream, MediaTags.builder().set(StandardTag.TITLE, "Seed").build(),
                TagWriteOptions.builder().padding(0).id3v1Mode(TagWriteOptions.Id3v1Mode.ALWAYS).build());
    }

    /** Remuxes a transport stream with a component name for every track and the hearing-impaired audio type. */
    private static byte[] namedTracks(byte[] ts) {
        var sink = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(ts)); var muxer = new TsMuxer(sink)) {
            var tags = MediaTags.builder();
            for (var track : demuxer.tracks()) {
                muxer.addTrack(track);
                tags.track(TrackTags.of(track.id(), "Stream " + track.id(), TrackDisposition.HEARING_IMPAIRED));
            }
            muxer.setTags(tags.build());
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Converts a 16-bit PCM WAV file to a big-endian RIFX file, its INFO entries included. */
    private static byte[] rifx(byte[] wav) {
        var in = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var out = ByteBuffer.allocate(wav.length).order(ByteOrder.BIG_ENDIAN);
        out.put("RIFX".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        for (var position = 12; position + 8 <= wav.length; ) {
            var id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            var size = in.getInt(position + 4);
            var body = Arrays.copyOfRange(wav, position + 8, position + 8 + size);
            position += 8 + size + (size & 1);
            switch (id) {
                case "fmt " -> {
                    var fields = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                    ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).putShort(0, fields.getShort(0))
                            .putShort(2, fields.getShort(2)).putInt(4, fields.getInt(4)).putInt(8, fields.getInt(8))
                            .putShort(12, fields.getShort(12)).putShort(14, fields.getShort(14));
                }
                case "data" -> {
                    for (var index = 0; index + 1 < body.length; index += 2) {
                        var low = body[index];
                        body[index] = body[index + 1];
                        body[index + 1] = low;
                    }
                }
                case "LIST" -> {
                    for (var at = 4; at + 8 <= body.length; ) {
                        var entry = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(at + 4);
                        ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).putInt(at + 4, entry);
                        at += 8 + entry + (entry & 1);
                    }
                }
                default -> {
                    continue;
                }
            }
            out.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length).put(body);
            if ((body.length & 1) != 0) {
                out.put((byte) 0);
            }
        }
        out.putInt(4, out.position() - 8);
        return Arrays.copyOf(out.array(), out.position());
    }

    /** The document of the TTML seed: two timed paragraphs with a style, a span and a line break. */
    private static final String TTML_SEED = "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\">"
            + "<body><div><p begin=\"0s\" end=\"1s\">Hello <span tts:fontStyle=\"italic\">there</span></p>"
            + "<p begin=\"1s\" end=\"2s\">two<br/>lines</p></div></body></tt>";

    @Test
    @DisplayName("writes the seeds of the elementary stream targets")
    void elementary() throws IOException {
        copy(DemuxerFuzzTest.class, "h264", "valid-h264.h264", ffmpeg("h264", video(32, 32, 6), "-c:v", "libx264",
                "-preset", "ultrafast", "-bf", "2", "-g", "4"));
        copy(DemuxerFuzzTest.class, "hevc", "valid-hevc.h265", ffmpeg("hevc", video(32, 32, 6), "-c:v", "libx265",
                "-preset", "ultrafast", "-x265-params", "log-level=0:keyint=4:bframes=2"));
        var obu = ffmpeg("obu", video(32, 32, 4), "-c:v", "libaom-av1", "-cpu-used", "8", "-b:v", "50k");
        copy(DemuxerFuzzTest.class, "av1", "valid-av1.obu", obu);
        var annexB = Files.createTempFile("seed", ".obu");
        Oracle.run("python", java.time.Duration.ofMinutes(1), null, List.of("-I",
                Path.of("..", "tools", "testdata", "behavior", "make-obu-annexb.py").toString(), obu.toString(),
                annexB.toString()));
        copy(DemuxerFuzzTest.class, "av1AnnexB", "valid-av1-annexb.obu", annexB);
        copy(DemuxerFuzzTest.class, "mpeg4Video", "valid-mpeg4.m4v", ffmpeg("m4v", video(32, 32, 6), "-c:v",
                "mpeg4", "-bf", "1", "-q:v", "20", "-f", "m4v"));
        copy(DemuxerFuzzTest.class, "h263", "valid-h263.263", ffmpeg("h263", video(128, 96, 3), "-c:v", "h263",
                "-q:v", "20"));
        copy(DemuxerFuzzTest.class, "y4m", "valid-yuv420.y4m", ffmpeg("y4m", video(16, 16, 3), "-pix_fmt",
                "yuv420p"));
        var pcm = new byte[3 + 64];
        pcm[1] = 1;
        for (var i = 3; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 37);
        }
        write(ElementaryFuzzTest.class, "pcmDemuxer", "valid-s16le-stereo.bin", pcm);
        var frame = new byte[8 * 4 * 3 / 2];
        for (var i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i * 5);
        }
        var config = RawVideoFormat.of(RawPixelFormat.YUV420P).toCodecConfig();
        write(ElementaryFuzzTest.class, "rawVideo", "valid-yuv420p.bin", FuzzPackets.video(0, 8, 4, config,
                List.of(new FuzzPackets.Part(frame, true), new FuzzPackets.Part(frame, true))));
        var rgb = RawVideoFormat.of(RawPixelFormat.BGR24).withRowAlignment(4).withBottomUp(true).toCodecConfig();
        write(ElementaryFuzzTest.class, "rawVideo", "valid-bgr24-dib.bin", FuzzPackets.video(0, 3, 2, rgb,
                List.of(new FuzzPackets.Part(new byte[24], true))));
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

    /**
     * Replaces the top-level boxes of the given types by their gzip-compressed forms ({@code !mov}, {@code !mof}),
     * every offset left unchanged, as the offsets of a file with compressed boxes count in the file uncompressed.
     */
    private static byte[] compressBoxes(byte[] file, java.util.Set<String> types) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        var position = 0;
        var compressed = 0;
        while (position + 8 <= file.length) {
            var size = java.nio.ByteBuffer.wrap(file, position, 4).getInt();
            var type = new String(file, position + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (types.contains(type)) {
                var payload = new java.io.ByteArrayOutputStream();
                try (var zip = new java.util.zip.GZIPOutputStream(payload)) {
                    zip.write(file, position + 8, size - 8);
                }
                out.writeBytes(java.nio.ByteBuffer.allocate(8).putInt(8 + payload.size())
                        .put(java.util.Map.of("moov", "!mov", "moof", "!mof").get(type)
                                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                        .array());
                payload.writeTo(out);
                compressed++;
            } else {
                out.write(file, position, size);
            }
            position += size;
        }
        if (compressed == 0) {
            throw new IllegalStateException("no " + types + " box");
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("HLS and DASH presentations packaged by Smalt from the behavior samples, the first half second")
    void presentations() {
        for (var name : PresentationSamples.names()) {
            var sample = PresentationSamples.of(name, 500_000);
            write(DemuxerFuzzTest.class, name.startsWith("dash") ? "dash" : "hls", "valid-" + name,
                    FuzzPresentations.pack(sample.manifestBytes(), sample.others()));
        }
    }

    @Test
    @DisplayName("the ordered-chapters behavior sample; the linked-file seeds come from make-timeline-samples.py")
    void timelines() throws IOException {
        var samples = Path.of("src", "test", "resources", "samples");
        copy(DemuxerFuzzTest.class, "matroska", "valid-ordered-parts.mkv",
                samples.resolve("matroska/ordered-parts.mkv"));
    }

    @Test
    @DisplayName("Dolby Vision behavior samples and the first RPU of each, unescaped, for the RPU target")
    void dolbyVision() throws IOException {
        var samples = Path.of("src", "test", "resources", "samples");
        copy(DemuxerFuzzTest.class, "mp4", "valid-dolby-vision-p84.mp4", samples.resolve("mp4/dolby-vision-p84.mp4"));
        copy(DemuxerFuzzTest.class, "mp4", "valid-dolby-vision-p5.mp4", samples.resolve("mp4/dolby-vision-p5.mp4"));
        copy(DemuxerFuzzTest.class, "matroska", "valid-dolby-vision-p84.mkv",
                samples.resolve("matroska/dolby-vision-p84.mkv"));
        copy(DemuxerFuzzTest.class, "mpegTs", "valid-dolby-vision-p84.ts", samples.resolve("ts/dolby-vision-p84.ts"));
        for (var profile : List.of("p84", "p5")) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(samples.resolve("mp4/dolby-vision-" + profile
                    + ".mp4")))) {
                var nal = com.github.auties00.smalt.testkit.DolbyVisionRpus.rpuNals(demuxer.read().data()).getFirst();
                write(DolbyVisionFuzzTest.class, "rpu", "valid-" + profile + ".bin",
                        com.github.auties00.smalt.internal.nal.NalUnits.unescape(nal, 2, nal.length - 2));
            }
        }
    }
}
