package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks ProRes and DNxHD through the core's containers, registries, probe and transcoder:
 * QuickTime and Matroska files ffmpeg writes demux with the codec and its sample entry type,
 * decode through {@link VideoDecoders}, and probe like ffprobe; Smalt writes ProRes into Matroska
 * and transcodes into QuickTime files ffmpeg decodes cleanly.
 */
@DisplayName("ProRes and DNxHD in containers")
class IntraContainerTest {
    static Path source(String extension, List<String> codec) {
        var output = new java.util.ArrayList<>(List.of("-frames:v", "3"));
        output.addAll(codec);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25"), output);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "prores_ks HQ in mov, mov, prores_ks:3:yuv422p10le, PRORES, apch, HQ, 10, YUV422, 10",
            "prores_ks 4444 in mov, mov, prores_ks:4:yuva444p10le, PRORES, ap4h, 4444, 12, YUV444, 12",
            "prores_ks LT in mkv, mkv, prores_ks:1:yuv422p10le, PRORES, apcs, LT, 10, YUV422, 10",
            "dnxhr_hq in mov, mov, dnxhd:dnxhr_hq:yuv422p, DNXHD, AVdh, DNXHR HQ, 8, YUV422, 8",
            // ffmpeg's 4:4:4 Y'CbCr sets the color format flag, RGB in ST 2019-1: decoded to 16-bit RGBA
            "dnxhr_444 in mkv, mkv, dnxhd:dnxhr_444:yuv444p10le, DNXHD, AVdh, DNXHR 444, 10, YUV444, 16"})
    @DisplayName("demux, decode through the registry and probe like ffprobe")
    void demuxAndProbe(String label, String extension, String codecSpec, Codec codec, String fourCc, String profile,
                       int bitDepth, ChromaFormat chroma, int decodedDepth) {
        var parts = codecSpec.split(":");
        var file = source(extension, List.of("-c:v", parts[0], "-profile:v", parts[1], "-pix_fmt", parts[2]));
        var stream = IntraTesting.demux(file);
        assertEquals(codec, stream.track().codec(), label);
        assertEquals(fourCc, new String(stream.track().codecConfig().orElseThrow(), StandardCharsets.US_ASCII), label);
        try (var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.builder().highBitDepth(true)
                .build())) {
            var frames = new int[1];
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> {
                    assertEquals(320, frame.picture().width());
                    assertEquals(decodedDepth, frame.picture().bitDepth());
                    frames[0]++;
                });
            }
            assertEquals(3, frames[0]);
        }
        var info = Media.probe(MediaSource.of(Fixtures.read(file)));
        var video = info.videoStreams().getFirst();
        assertEquals(codec, video.codec());
        assertEquals(profile, video.profile().orElseThrow());
        assertEquals(OptionalInt.of(bitDepth), video.bitDepth());
        assertEquals(chroma, video.chromaFormat().orElseThrow());
    }

    @Test
    @DisplayName("ProRes goes into Matroska as V_PRORES")
    void matroska(@TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var pictures = IntraTesting.sourceFrames(160, 120, "yuv422p10le", 2);
        var encoder = new ProResEncoder(160, 120);
        var file = directory.resolve("prores.mkv");
        try (var muxer = new MatroskaMuxer(MediaSink.of(file), MatroskaMuxerOptions.matroska())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.PRORES).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(160).height(120).build());
            var pts = 0L;
            for (var picture : pictures) {
                muxer.writePacket(encoder.encodeFrame(new VideoFrame(picture, pts, 3600, true)).withTrackId(1));
                pts += 3600;
            }
            muxer.finish();
        }
        Oracle.assertDecodesCleanly(file);
        assertTrue(Oracle.probe(file).contains("\"codec_name\": \"prores\""));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"DNXHR_HQ, AVdh, dnxhr_hq", "DNXHD, AVdn, dnxhd"})
    @DisplayName("DNxHD goes into Matroska as V_QUICKTIME with a QuickTime sample description")
    void matroskaDnxhd(DnxhdProfile profile, String fourCc, String ffprobeProfile, @TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var width = profile == DnxhdProfile.DNXHD ? 1280 : 320;
        var height = profile == DnxhdProfile.DNXHD ? 720 : 240;
        var pictures = IntraTesting.sourceFrames(width, height, "yuv422p", 2);
        var encoder = new DnxhdEncoder(width, height, DnxhdEncoderOptions.of(profile));
        var file = directory.resolve("dnxhd.mkv");
        var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
        try (var muxer = new MatroskaMuxer(MediaSink.of(file), MatroskaMuxerOptions.matroska())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.DNXHD).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(width).height(height).build());
            var pts = 0L;
            for (var picture : pictures) {
                var packet = encoder.encodeFrame(new VideoFrame(picture, pts, 3600, true));
                packets.add(packet);
                muxer.writePacket(packet.withTrackId(1));
                pts += 3600;
            }
            muxer.finish();
        }
        Oracle.assertDecodesCleanly(file);
        var probe = Oracle.probe(file);
        assertTrue(probe.contains("\"codec_name\": \"dnxhd\""), probe);
        assertTrue(probe.contains("\"codec_tag_string\": \"" + fourCc + "\""), probe);
        assertTrue(probe.contains("\"profile\": \"" + ffprobeProfile.toUpperCase().replace('_', ' ') + "\""), probe);
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.DNXHD, stream.track().codec());
        assertEquals(fourCc, new String(stream.track().codecConfig().orElseThrow(), StandardCharsets.US_ASCII));
        assertEquals(packets.size(), stream.packets().size());
        for (var i = 0; i < packets.size(); i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(packets.get(i).data(), stream.packets().get(i).data());
        }
        var decoder = new DnxhdDecoder(stream.track());
        decoder.decode(stream.packets().getFirst(), frame -> assertEquals(width, frame.picture().width()));
    }

    @Test
    @DisplayName("probes RGB DNxHR 444 with the identity matrix")
    void probeRgb(@TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var pictures = IntraTesting.sourceFrames(320, 240, "rgba", 1);
        var encoder = new DnxhdEncoder(320, 240, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_444).toBuilder().rgb(true)
                .build());
        var file = directory.resolve("rgb.mov");
        try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.DNXHD).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(320).height(240).build());
            muxer.writePacket(encoder.encodeFrame(new VideoFrame(pictures.getFirst(), 0, 3600, true)).withTrackId(1));
            muxer.finish();
        }
        var video = Media.probe(MediaSource.of(Fixtures.read(file))).videoStreams().getFirst();
        assertEquals(ChromaFormat.YUV444, video.chromaFormat().orElseThrow());
        assertEquals(MatrixCoefficients.IDENTITY, video.color().orElseThrow().matrix());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"PRORES, prores", "DNXHD, dnxhd"})
    @DisplayName("transcodes into QuickTime files ffmpeg decodes cleanly")
    void transcode(Codec codec, String name, @TempDir Path directory) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = source("webm", List.of("-c:v", "libvpx-vp9", "-b:v", "500k", "-an"));
        var output = directory.resolve("out.mov");
        assertEquals(OutputContainer.MOV, OutputContainer.forFileName(output.toString()).orElseThrow());
        var options = TranscodeOptions.of(OutputContainer.MOV).toBuilder().noAudio()
                .video(VideoTranscodeOptions.encode(codec)).build();
        Transcoder.transcode(MediaSource.of(Fixtures.read(input)), MediaSink.of(output), options);
        Oracle.assertDecodesCleanly(output);
        var probe = Oracle.probe(output);
        assertTrue(probe.contains("\"codec_name\": \"" + name + "\""), probe);
        assertTrue(probe.contains("\"major_brand\": \"qt  \""), probe);
    }
}
