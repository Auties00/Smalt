package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.Loudness;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleReaderOptions;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how a transcode copies and converts subtitle streams (container changes, trims, modes,
 * track selection) and how it normalizes loudness in two passes.
 */
@DisplayName("Transcode of subtitles and loudness")
class SubtitleTranscodeTest {
    private static final List<SubtitleCue> CUES = List.of(
            SubtitleCue.of(500_000, 2_000_000, "<i>One</i> & two"),
            SubtitleCue.of(2_500_000, 4_000_000, "Three\nfour"),
            SubtitleCue.of(5_000_000, 6_000_000, "<b>Five</b>"));

    private static byte[] srt(List<SubtitleCue> cues) {
        var sink = MediaSink.memory();
        Subtitles.write(cues, SubtitleMarkup.SUBRIP, ContainerFormat.SUBRIP, sink);
        return sink.toByteArray();
    }

    private static byte[] transcode(byte[] input, TranscodeOptions options) {
        var sink = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(input), sink, options);
        return sink.toByteArray();
    }

    private static byte[] ffmpegSrt(Path input) {
        return Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error", "-xerror", "-i", input.toString(),
                "-map", "0:s:0", "-f", "srt", "-"));
    }

    private static List<SubtitleCue> cues(byte[] file, int trackId) {
        var options = SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build();
        return Subtitles.read(MediaSource.of(file), trackId < 0 ? options : options.toBuilder().trackId(trackId)
                .build());
    }

    /**
     * Builds a Matroska file with 7 s of PCM audio, a SubRip track and an ASS track.
     */
    private static byte[] matroska() {
        return matroska(CUES, 48_000, 7);
    }

    /**
     * Builds a Matroska file with PCM audio of a rate and length, a SubRip track and an ASS track
     * carrying the same cues.
     */
    private static byte[] matroska(List<SubtitleCue> cues, int sampleRate, int seconds) {
        var layout = sampleRate == 48_000 ? ChannelLayout.STEREO : ChannelLayout.MONO;
        var audio = PcmPackets.tone(Codec.PCM_S16LE, sampleRate, layout, seconds * sampleRate);
        var subrip = SubtitleEncoder.track(Codec.SUBRIP, 2).toBuilder().language("eng").build();
        var ass = SubtitleEncoder.track(Codec.ASS, 3).toBuilder().language("ita").build();
        var packets = new ArrayList<Packet>(audio.packets());
        var subripEncoder = new SubtitleEncoder(subrip);
        var assEncoder = new SubtitleEncoder(ass);
        for (var cue : cues) {
            packets.add(subripEncoder.encode(cue, SubtitleMarkup.SUBRIP));
            packets.add(assEncoder.encode(cue, SubtitleMarkup.SUBRIP));
        }
        var tracks = List.<Track>of(audio.track(), subrip, ass);
        packets.sort(Comparator.comparingLong(packet -> tracks.stream().filter(track -> track.id() == packet.trackId()).findFirst()
                .orElseThrow().timeBase().toMicros(packet.pts())));
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            tracks.forEach(muxer::addTrack);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @Nested
    @DisplayName("subtitles")
    class Subtitle {
        @Test
        @DisplayName("converts SubRip to WebVTT and back")
        void textFiles() {
            var vtt = transcode(srt(CUES), TranscodeOptions.of(OutputContainer.WEBVTT));
            assertTrue(new String(vtt, java.nio.charset.StandardCharsets.UTF_8).contains("<i>One</i> &amp; two"));
            assertEquals(CUES, cues(vtt, -1));
            var back = transcode(vtt, TranscodeOptions.of(OutputContainer.SRT));
            assertEquals(CUES, cues(back, -1));
        }

        @Test
        @DisplayName("writes a subtitle-only MP4 as 3GPP timed text, or WebVTT on request")
        void mp4() {
            var options = TranscodeOptions.of(OutputContainer.MP4);
            var result = Transcoder.transcode(MediaSource.of(srt(CUES)), MediaSink.memory(), options);
            assertEquals(Codec.MOV_TEXT, result.streams().getFirst().track().codec());
            assertFalse(result.streams().getFirst().copied());
            assertEquals(CUES, cues(transcode(srt(CUES), options), -1));
            var webVtt = transcode(srt(CUES), options.toBuilder()
                    .subtitles(SubtitleTranscodeOptions.encode(Codec.WEBVTT)).build());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(webVtt))) {
                assertEquals(Codec.WEBVTT, demuxer.tracks().getFirst().codec());
            }
            assertEquals(CUES, cues(webVtt, -1));
        }

        @Test
        @DisplayName("copies every subtitle track of a Matroska file with the audio, keeping languages")
        void copyAll() {
            var output = transcode(matroska(), TranscodeOptions.of(OutputContainer.MATROSKA));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
                var tracks = demuxer.tracks();
                assertEquals(3, tracks.size());
                assertEquals(Codec.SUBRIP, tracks.get(1).codec());
                assertEquals("eng", tracks.get(1).language().orElseThrow());
                assertEquals(Codec.ASS, tracks.get(2).codec());
                assertEquals("ita", tracks.get(2).language().orElseThrow());
            }
            assertEquals(CUES, cues(output, 2));
            assertEquals(CUES, cues(output, 3));
            var result = Transcoder.transcode(MediaSource.of(matroska()), MediaSink.memory(), TranscodeOptions.of(OutputContainer.MATROSKA));
            assertTrue(result.streams().stream().allMatch(TranscodedStream::copied));
        }

        @Test
        @DisplayName("selects one track, drops subtitles, and converts to WebM WebVTT")
        void selection() {
            var first = transcode(matroska(), TranscodeOptions.of(OutputContainer.MATROSKA)
                    .toBuilder().subtitles(SubtitleTranscodeOptions.builder().trackId(3).build()).build());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(first))) {
                assertEquals(2, demuxer.tracks().size());
                assertEquals(Codec.ASS, demuxer.tracks().get(1).codec());
            }
            var dropped = transcode(matroska(), TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder().noSubtitles()
                    .build());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(dropped))) {
                assertEquals(1, demuxer.tracks().size());
            }
            var webm = transcode(matroska(), TranscodeOptions.of(OutputContainer.WEBM).toBuilder().noAudio()
                    .subtitles(SubtitleTranscodeOptions.builder().firstTrack().build()).build());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(webm))) {
                assertEquals(1, demuxer.tracks().size());
                assertEquals(Codec.WEBVTT, demuxer.tracks().getFirst().codec());
            }
            assertEquals(CUES, cues(webm, -1));
            var srt = transcode(matroska(), TranscodeOptions.of(OutputContainer.SRT));
            assertEquals(CUES, cues(srt, -1));
        }

        @Test
        @DisplayName("cuts cues to the kept range and moves them to the output timeline")
        void trim() {
            var options = TranscodeOptions.of(OutputContainer.SRT).toBuilder().range(1_000_000, 5_500_000).build();
            assertEquals(List.of(SubtitleCue.of(0, 1_000_000, "<i>One</i> & two"), SubtitleCue.of(1_500_000, 3_000_000, "Three\nfour"),
                    SubtitleCue.of(4_000_000, 4_500_000, "<b>Five</b>")), cues(transcode(srt(CUES), options), -1));
            var copied = transcode(matroska(), TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                    .range(1_000_000, 5_500_000).build());
            assertEquals(List.of(SubtitleCue.of(0, 1_000_000, "<i>One</i> & two"), SubtitleCue.of(1_500_000, 3_000_000, "Three\nfour"),
                    SubtitleCue.of(4_000_000, 4_500_000, "<b>Five</b>")), cues(copied, 2));
        }

        @Test
        @DisplayName("keeps a cue still on screen at a trimmed start far past the seek point")
        void longCueAtTrimmedStart() {
            // a 60 s cue starting 25 s before the trimmed start, a cue in the cluster the seek lands in,
            // and two cues after the start; 8 kHz mono PCM keeps the 70 s file small
            var cues = List.of(
                    SubtitleCue.of(5_000_000, 65_000_000, "Long"),
                    SubtitleCue.of(29_000_000, 31_000_000, "Edge"),
                    SubtitleCue.of(31_000_000, 33_000_000, "After"),
                    SubtitleCue.of(40_000_000, 41_000_000, "Late"));
            var input = matroska(cues, 8_000, 70);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(input))) {
                assertTrue(demuxer.isSeekable(), "the test needs the seeking path");
            }
            var expected = List.of(SubtitleCue.of(0, 35_000_000, "Long"), SubtitleCue.of(0, 1_000_000, "Edge"),
                    SubtitleCue.of(1_000_000, 3_000_000, "After"), SubtitleCue.of(10_000_000, 11_000_000, "Late"));
            var trimmed = TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder().start(30_000_000).build();
            var copied = transcode(input, trimmed);
            assertEquals(expected, cues(copied, 2));
            assertEquals(expected, cues(copied, 3));
            assertEquals(expected, cues(transcode(input, TranscodeOptions.of(OutputContainer.SRT).toBuilder()
                    .start(30_000_000).build()), -1));
            var unseeked = transcode(input, trimmed.toBuilder().seeking(false).build());
            assertEquals(cues(unseeked, 2), cues(copied, 2));
            var mp4 = transcode(input, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                    .subtitles(SubtitleTranscodeOptions.builder().trackId(2).build()).build());
            // 3GPP timed text cannot overlap cues, so the MP4 splits them; the seeked trim must still
            // equal a trim that decodes from the start
            var mp4Trim = TranscodeOptions.of(OutputContainer.SRT).toBuilder().start(30_000_000).build();
            var mp4Cues = cues(transcode(mp4, mp4Trim), -1);
            assertEquals(cues(transcode(mp4, mp4Trim.toBuilder().seeking(false).build()), -1), mp4Cues);
            assertEquals(0, mp4Cues.getFirst().startMicros());
            assertTrue(mp4Cues.getFirst().text().startsWith("Long"), mp4Cues.toString());
            var webm = transcode(input, TranscodeOptions.of(OutputContainer.WEBM).toBuilder().noAudio()
                    .range(30_000_000, 39_000_000).build());
            assertEquals(List.of(SubtitleCue.of(0, 9_000_000, "Long"), SubtitleCue.of(0, 1_000_000, "Edge"),
                    SubtitleCue.of(1_000_000, 3_000_000, "After")), cues(webm, -1));
        }

        @Test
        @DisplayName("keeps a cue that appears with another and is stored far after its time")
        void lateCueAtTrimmedStart() {
            var cues = List.of(
                    SubtitleCue.of(5_000_000, 65_000_000, "Long"),
                    SubtitleCue.of(5_000_000, 65_000_000, "Late stored"),
                    SubtitleCue.of(31_000_000, 33_000_000, "After"));
            var audio = PcmPackets.tone(Codec.PCM_S16LE, 8_000, ChannelLayout.MONO, 70 * 8_000);
            var subrip = SubtitleEncoder.track(Codec.SUBRIP, 2);
            var encoder = new SubtitleEncoder(subrip);
            var packets = new ArrayList<Packet>(audio.packets());
            packets.add(encoder.encode(cues.get(0), SubtitleMarkup.SUBRIP));
            packets.add(encoder.encode(cues.get(2), SubtitleMarkup.SUBRIP));
            packets.sort(Comparator.comparingLong(packet -> (packet.trackId() == 2 ? subrip : audio.track()).timeBase()
                    .toMicros(packet.pts())));
            // the second cue goes after the audio of 50 s, past the reach of the subtitle scan
            var late = encoder.encode(cues.get(1), SubtitleMarkup.SUBRIP);
            var at = 0;
            while (packets.get(at).trackId() == 2 || audio.track().timeBase().toMicros(packets.get(at).pts()) < 50_000_000) {
                at++;
            }
            packets.add(at, late);
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink)) {
                muxer.addTrack(audio.track());
                muxer.addTrack(subrip);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            var input = sink.toByteArray();
            var expected = List.of(SubtitleCue.of(0, 35_000_000, "Long"), SubtitleCue.of(0, 35_000_000, "Late stored"),
                    SubtitleCue.of(1_000_000, 3_000_000, "After"));
            var trimmed = TranscodeOptions.of(OutputContainer.SRT).toBuilder().start(30_000_000).build();
            assertEquals(expected, cues(transcode(input, trimmed.toBuilder().seeking(false).build()), -1));
            assertEquals(expected, cues(transcode(input, trimmed), -1));
        }

        @Test
        @DisplayName("keeps a cue still on screen at a trimmed start of a source read once")
        void longCueAtTrimmedStartOfStream() {
            var cues = List.of(
                    SubtitleCue.of(5_000_000, 65_000_000, "Long"),
                    SubtitleCue.of(31_000_000, 33_000_000, "After"));
            var input = matroska(cues, 8_000, 70);
            var expected = List.of(SubtitleCue.of(0, 35_000_000, "Long"), SubtitleCue.of(1_000_000, 3_000_000, "After"));
            for (var container : List.of(OutputContainer.MATROSKA, OutputContainer.SRT)) {
                var trimmed = TranscodeOptions.of(container).toBuilder().start(30_000_000).build();
                var sink = MediaSink.memory();
                // a memory-only stream source fails any read of the bytes a parser already released
                Transcoder.transcode(MediaSource.of(new java.io.ByteArrayInputStream(input), input.length,
                        com.github.auties00.smalt.io.StreamSourceOptions.memoryOnly()), sink, trimmed);
                assertEquals(expected, cues(sink.toByteArray(), container == OutputContainer.SRT ? -1 : 2), container.name());
            }
        }

        @Test
        @DisplayName("fails when asked for what the container cannot do")
        void failures() {
            assertThrows(MediaException.Unsupported.class, () -> transcode(srt(CUES), TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().subtitles(SubtitleTranscodeOptions.copy()).build()));
            assertThrows(IllegalArgumentException.class, () -> transcode(srt(CUES), TranscodeOptions.of(OutputContainer.WAV)));
            assertThrows(IllegalArgumentException.class, () -> transcode(srt(CUES), TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().subtitles(SubtitleTranscodeOptions.encode(Codec.SUBRIP)).build()));
            assertThrows(IllegalArgumentException.class, () -> transcode(srt(CUES), TranscodeOptions.of(OutputContainer.MP4)
                    .toBuilder().subtitles(SubtitleTranscodeOptions.encode(Codec.ASS)).build()));
        }

        @Test
        @DisplayName("writes MP4 and Matroska files ffmpeg reads")
        void ffmpeg(@TempDir Path directory) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var mp4 = directory.resolve("a.mp4");
            Files.write(mp4, transcode(matroska(), TranscodeOptions.of(OutputContainer.MP4).toBuilder().noAudio()
                    .build()));
            assertTrue(Oracle.probe(mp4).contains("mov_text"));
            assertEquals(CUES, cues(ffmpegSrt(mp4), -1));
            var mkv = directory.resolve("b.mkv");
            Files.write(mkv, transcode(Files.readAllBytes(mp4), TranscodeOptions.of(OutputContainer.MATROSKA)));
            assertEquals(CUES, cues(Files.readAllBytes(mkv), -1));
            assertEquals(CUES, cues(ffmpegSrt(mkv), -1));
        }

        @Test
        @DisplayName("options follow the immutable options convention")
        void options() {
            var defaults = SubtitleTranscodeOptions.defaults();
            assertEquals(StreamMode.AUTO, defaults.mode());
            assertTrue(defaults.allTracks());
            assertEquals(StreamMode.COPY, SubtitleTranscodeOptions.copy().mode());
            assertEquals(StreamMode.DROP, SubtitleTranscodeOptions.drop().mode());
            var encode = SubtitleTranscodeOptions.encode(Codec.WEBVTT).toBuilder().trackId(4).build();
            assertEquals(encode, encode.toBuilder().build());
            assertEquals(encode.hashCode(), encode.toBuilder().build().hashCode());
            assertFalse(encode.allTracks());
            assertEquals(4, encode.trackId().getAsInt());
            assertTrue(encode.toBuilder().automaticCodec().build().codec().isEmpty());
            assertTrue(encode.toBuilder().allTracks().build().trackId().isEmpty());
            assertNotEquals(defaults, defaults.toBuilder().firstTrack().build());
            assertThrows(IllegalArgumentException.class, () -> SubtitleTranscodeOptions.encode(Codec.H264));
            assertEquals(List.of(Codec.MOV_TEXT, Codec.WEBVTT), OutputContainer.MP4.subtitleCodecs());
            assertTrue(OutputContainer.FRAGMENTED_MP4.subtitleCodecs().isEmpty());
            assertEquals(OutputContainer.SRT, OutputContainer.forFileName("movie.SRT").orElseThrow());
            assertEquals(OutputContainer.WEBVTT, OutputContainer.forFileName("x.vtt").orElseThrow());
            assertEquals(defaults, TranscodeOptions.defaults().subtitles());
        }
    }

    @Nested
    @DisplayName("loudness normalization")
    class Normalization {
        private static byte[] wav(double... segments) {
            var planes = tones(segments);
            var sink = MediaSink.memory();
            var track = PcmPackets.tone(Codec.PCM_F32LE, 48_000, ChannelLayout.STEREO, 1).track();
            try (var muxer = new WavMuxer(sink)) {
                muxer.addTrack(track);
                var data = java.nio.ByteBuffer.allocate(planes[0].length * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (var frame = 0; frame < planes[0].length; frame++) {
                    data.putFloat(planes[0][frame]).putFloat(planes[1][frame]);
                }
                muxer.writePacket(new Packet(track.id(), data.array(), 0, 0, planes[0].length, true));
                muxer.finish();
            }
            return sink.toByteArray();
        }

        private static float[][] tones(double... segments) {
            var total = 0;
            for (var index = 0; index < segments.length; index += 2) {
                total += (int) (segments[index] * 48_000);
            }
            var planes = new float[2][total];
            var position = 0;
            for (var index = 0; index < segments.length; index += 2) {
                var frames = (int) (segments[index] * 48_000);
                var amplitude = Math.pow(10, segments[index + 1] / 20);
                for (var frame = 0; frame < frames; frame++) {
                    var value = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * (position + frame) / 48_000.0));
                    planes[0][position + frame] = value;
                    planes[1][position + frame] = value;
                }
                position += frames;
            }
            return planes;
        }

        @Test
        @DisplayName("measures the kept range first and reaches the target")
        void target() {
            var input = wav(5, -35, 5, -25);
            var options = TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.builder().codec(Codec.PCM_F32LE)
                            .loudnessNormalization(LoudnessNormalization.forPodcast()).build()).build();
            var stats = Loudness.measure(MediaSource.of(transcode(input, options)));
            assertEquals(-16, stats.integrated(), 0.1);
            var trimmed = Loudness.measure(MediaSource.of(transcode(input, options.toBuilder().range(0, 5_000_000)
                    .build())));
            assertEquals(-16, trimmed.integrated(), 0.1);
            assertEquals(5_000_000, trimmed.durationMicros(), 1000);
        }

        @Test
        @DisplayName("keeps the true peak under the ceiling")
        void ceiling() {
            var input = wav(8, -30, 0.02, -3, 2, -30);
            var options = TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(AudioTranscodeOptions.builder()
                    .codec(Codec.PCM_F32LE).loudnessNormalization(LoudnessNormalization.ebuR128()).build()).build();
            var source = Loudness.measure(MediaSource.of(input));
            assertTrue(source.truePeakDb() + (-23 - source.integrated()) > -1, "the target gain would exceed the ceiling");
            var stats = Loudness.measure(MediaSource.of(transcode(input, options)));
            assertEquals(-1, stats.truePeakDb(), 0.05);
            assertEquals(source.integrated() + (-1 - source.truePeakDb()), stats.integrated(), 0.05);
        }

        @Test
        @DisplayName("refuses to copy a normalized stream and changes the options' identity")
        void options() {
            var normalized = AudioTranscodeOptions.copy().toBuilder()
                    .loudnessNormalization(LoudnessNormalization.ebuR128()).build();
            assertThrows(MediaException.Unsupported.class, () -> transcode(wav(2, -20),
                    TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(normalized).build()));
            assertNotEquals(AudioTranscodeOptions.copy(), normalized);
            assertEquals(normalized, normalized.toBuilder().build());
            assertTrue(normalized.toBuilder().noLoudnessNormalization().build().loudnessNormalization().isEmpty());
            assertTrue(normalized.changesSamples(48_000, ChannelLayout.STEREO));
        }
    }
}
