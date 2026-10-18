package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests AMR in its storage formats: the {@code .amr} file Smalt writes is what ffmpeg (and any
 * consumer of {@code audio/amr}) expects, MP4 output carries {@code samr}/{@code sawb}
 * with a {@code damr} box, and files ffmpeg writes in 3GP and Matroska decode bit-exactly.
 */
class AmrContainersTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
    @DisplayName("writes an .amr storage file ffmpeg decodes cleanly and to the same samples")
    void storageFile(Codec codec) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var mode = AmrMode.highest(codec);
        var packets = AmrTestSupport.encode(AmrEncoderOptions.of(mode).toBuilder().dtx(true).build(), AmrTestSupport.signal(mode.sampleRate()));
        var file = mux(ContainerFormat.AMR, codec, packets, sink -> Containers.openMuxer(ContainerFormat.AMR, sink));
        var magic = codec == Codec.AMR_WB ? "#!AMR-WB\n" : "#!AMR\n";
        assertEquals(magic, new String(file, 0, magic.length(), StandardCharsets.ISO_8859_1));
        assertArrayEquals(AmrTestSupport.storage(codec, packets), file);
        var path = AmrTestSupport.write(directory, "out.amr", file);
        AmrTestSupport.assertDecodesCleanly(codec, path);
        var probe = Oracle.probe(path);
        assertTrue(probe.contains(codec == Codec.AMR_WB ? "\"amr_wb\"" : "\"amr_nb\""), probe);
        assertTrue(probe.contains("\"sample_rate\": \"" + mode.sampleRate() + "\""), probe);
        assertTrue(probe.contains("\"channels\": 1"), probe);
        assertArrayEquals(AmrTestSupport.ffmpegDecode(codec, file, directory), AmrTestSupport.decode(codec, packets));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"AMR_NB, false", "AMR_WB, false", "AMR_NB, true", "AMR_WB, true"})
    @DisplayName("writes samr and sawb sample entries in MP4, read back by ffmpeg and Smalt")
    void mp4(Codec codec, boolean fragmented) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var mode = AmrMode.highest(codec);
        var packets = AmrTestSupport.encode(AmrEncoderOptions.of(mode), AmrTestSupport.signal(mode.sampleRate()));
        Function<MediaSink, Muxer> factory = fragmented ? FragmentedMp4Muxer::new : sink -> new Mp4Muxer(sink, Mp4MuxerOptions.m4a());
        var file = mux(ContainerFormat.MP4, codec, packets, factory);
        var path = AmrTestSupport.write(directory, "out.m4a", file);
        AmrTestSupport.assertDecodesCleanly(codec, path);
        var probe = Oracle.probe(path);
        assertTrue(probe.contains(codec == Codec.AMR_WB ? "\"sawb\"" : "\"samr\""), probe);
        var box = indexOf(file, "damr".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(box > 0, "damr box");
        var modeSet = (file[box + 9] & 0xFF) << 8 | file[box + 10] & 0xFF;
        assertEquals(fragmented ? (codec == Codec.AMR_WB ? 0x01FF : 0x00FF) : 1 << mode.frameType(), modeSet);
        var read = readAll(file);
        assertEquals(packets.size(), read.size());
        for (var index = 0; index < packets.size(); index++) {
            assertArrayEquals(packets.get(index).data(), read.get(index).data(), "packet " + index);
        }
    }

    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource({"AMR_NB, 3gp", "AMR_WB, 3gp", "AMR_NB, mkv", "AMR_WB, mkv"})
    @DisplayName("decodes AMR that ffmpeg muxed into 3GP and Matroska")
    void foreignContainers(Codec codec, String extension) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var mode = AmrMode.highest(codec);
        var amr = AmrTestSupport.ffmpegEncode(mode, false, AmrTestSupport.signal(mode.sampleRate()), directory);
        var input = AmrTestSupport.write(directory, "in.amr", amr);
        var output = directory.resolve("muxed." + extension);
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", input.toString(), "-c", "copy",
                output.toString()));
        var packets = readAll(AmrTestSupport.read(output));
        assertEquals(AmrTestSupport.frames(amr).size(), packets.size());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(codec, track.codec());
            assertEquals(mode.sampleRate(), track.sampleRate());
        }
        assertArrayEquals(AmrTestSupport.ffmpegDecode(codec, amr, directory), AmrTestSupport.decode(codec, packets));
    }

    @Test
    @DisplayName("transcodes 44.1 kHz stereo to the single-channel 8 kHz .amr file the Cloud API accepts")
    void transcodeToAmr() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = Fixtures.tone("wav", 440, 44100, 2, 2.0, List.of("-c:a", "pcm_s16le"));
        var output = directory.resolve("voice.amr");
        var result = Transcoder.transcode(input, output);
        assertEquals(OutputContainer.AMR, result.container());
        var file = AmrTestSupport.read(output);
        assertEquals("#!AMR\n", new String(file, 0, 6, StandardCharsets.ISO_8859_1));
        Oracle.assertDecodesCleanly(output);
        var probe = Oracle.probe(output);
        assertTrue(probe.contains("\"amr_nb\"") && probe.contains("\"sample_rate\": \"8000\"") && probe.contains("\"channels\": 1"),
                probe);
        var frames = AmrTestSupport.frames(file);
        assertTrue(Math.abs(frames.size() - 100) <= 2, "about two seconds of frames: " + frames.size());
        assertTrue(frames.stream().allMatch(frame -> frame.size() == 32), "every frame at 12.2 kbit/s");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"AMR_NB, 7400", "AMR_WB, 12650"})
    @DisplayName("transcodes to AMR in M4A at the requested bitrate and back to AAC")
    void transcodeThroughMp4(Codec codec, int bitrate) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = Fixtures.tone("wav", 440, 48000, 1, 1.5, List.of("-c:a", "pcm_s16le"));
        var amr = directory.resolve("voice.m4a");
        Transcoder.transcode(input, amr, TranscodeOptions.of(OutputContainer.M4A)
                .toBuilder().audio(AudioTranscodeOptions.encode(codec).toBuilder().bitrate(bitrate).build()).build());
        Oracle.assertDecodesCleanly(amr);
        var packets = readAll(AmrTestSupport.read(amr));
        var expected = AmrMode.closest(codec, bitrate).frameType();
        assertTrue(packets.stream().allMatch(packet -> ((packet.data()[0] >> 3) & 0x0F) == expected));
        var aac = directory.resolve("back.m4a");
        Transcoder.transcode(amr, aac, TranscodeOptions.of(OutputContainer.M4A).toBuilder()
                .audio(AudioTranscodeOptions.encode(Codec.AAC)).build());
        Oracle.assertDecodesCleanly(aac);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(aac))) {
            assertEquals(Codec.AAC, demuxer.tracks().getFirst().codec());
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(amr));
             var decoder = AudioDecoders.create((AudioTrack) demuxer.tracks().getFirst())) {
            assertEquals(AmrTestSupport.rate(codec), decoder.sampleRate());
        }
    }

    private static byte[] mux(ContainerFormat format, Codec codec, List<Packet> packets, Function<MediaSink, Muxer> factory) {
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            muxer.addTrack(AmrTestSupport.track(codec));
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        assertEquals(Optional.of(format), Containers.detect(MediaSource.of(sink.toByteArray())));
        return sink.toByteArray();
    }

    private static List<Packet> readAll(byte[] file) {
        var packets = new ArrayList<Packet>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        return packets;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
