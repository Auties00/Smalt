package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.subtitle.AssDemuxer;
import com.github.auties00.smalt.container.subtitle.AssMuxer;
import com.github.auties00.smalt.container.subtitle.AssScriptType;
import com.github.auties00.smalt.container.subtitle.LrcDemuxer;
import com.github.auties00.smalt.container.subtitle.LrcMuxer;
import com.github.auties00.smalt.container.subtitle.MicroDvdDemuxer;
import com.github.auties00.smalt.container.subtitle.MicroDvdMuxer;
import com.github.auties00.smalt.container.subtitle.SamiDemuxer;
import com.github.auties00.smalt.container.subtitle.TtmlDemuxer;
import com.github.auties00.smalt.container.subtitle.TtmlMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the SSA, ASS, TTML, SAMI, LRC and MicroDVD files end to end: recognition, the registry's demuxers
 * and muxers, round trips of every cue, sources of unknown size, the text encoding options, multi-language
 * SAMI files, script versions, and the transcoder writing each file.
 */
@DisplayName("Subtitle file formats")
class SubtitleFormatsTest {
    private static final char BS = (char) 92;

    static final List<SubtitleCue> SEQUENTIAL = List.of(
            SubtitleCue.of(500_000, 2_000_000, "<i>Hello</i> world"),
            SubtitleCue.of(2_000_000, 3_000_000, "Line \u00E9\nsecond line"),
            SubtitleCue.of(5_000_000, 6_250_000, "<b>Bold</b> & <u>under</u>"),
            SubtitleCue.of(9_000_000, 10_000_000, "\u3053\u3093\u306B\u3061\u306F"));

    static byte[] write(List<SubtitleCue> cues, ContainerFormat format) {
        var sink = MediaSink.memory();
        Subtitles.write(cues, SubtitleMarkup.SUBRIP, format, sink);
        return sink.toByteArray();
    }

    static List<SubtitleCue> read(byte[] file) {
        return Subtitles.read(MediaSource.of(file), SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build());
    }

    static List<String> plain(List<SubtitleCue> cues) {
        var result = new ArrayList<String>();
        for (var cue : cues) {
            result.add(Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP));
        }
        return result;
    }

    @Nested
    @DisplayName("files")
    class Files {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ContainerFormat.class, names = {"ASS", "TTML", "SAMI"})
        @DisplayName("round-trip every cue with its styles")
        void exact(ContainerFormat format) {
            var file = write(SEQUENTIAL, format);
            assertEquals(format, Containers.detect(MediaSource.of(file)).orElseThrow());
            assertEquals(SEQUENTIAL, read(file));
        }

        @Test
        @DisplayName("round-trip the text and times of LRC files, which hold plain text")
        void lrc() {
            var file = write(SEQUENTIAL, ContainerFormat.LRC);
            assertEquals(ContainerFormat.LRC, Containers.detect(MediaSource.of(file)).orElseThrow());
            var cues = read(file);
            assertEquals(plain(SEQUENTIAL), plain(cues));
            for (var index = 0; index < cues.size(); index++) {
                assertEquals(SEQUENTIAL.get(index).startMicros(), cues.get(index).startMicros());
                assertEquals(SEQUENTIAL.get(index).endMicros(), cues.get(index).endMicros());
            }
        }

        @Test
        @DisplayName("round-trip MicroDVD files to the nearest frame, styles covering whole lines")
        void microDvd() {
            var file = write(SEQUENTIAL, ContainerFormat.MICRODVD);
            assertEquals(ContainerFormat.MICRODVD, Containers.detect(MediaSource.of(file)).orElseThrow());
            var cues = read(file);
            assertEquals(plain(SEQUENTIAL), plain(cues));
            for (var index = 0; index < cues.size(); index++) {
                assertEquals(SEQUENTIAL.get(index).startMicros(), cues.get(index).startMicros(), 1_001_000 / 48.0);
                assertEquals(SEQUENTIAL.get(index).endMicros(), cues.get(index).endMicros(), 1_001_000 / 48.0);
            }
            var lines = SubtitleCue.of(0, 1_000_000, "<i>whole</i>\n<b><u>line</u></b>");
            assertEquals(lines.text(), read(write(List.of(lines), ContainerFormat.MICRODVD)).getFirst().text());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ContainerFormat.class, names = {"ASS", "TTML", "SAMI", "LRC", "MICRODVD"})
        @DisplayName("read from sources of unknown size, through the registry")
        void sizeless(ContainerFormat format) {
            var file = write(SEQUENTIAL, format);
            var sized = read(file);
            var source = MediaSource.of(new ByteArrayInputStream(file), -1);
            assertEquals(sized, Subtitles.read(source, SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build()));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(format, demuxer.format());
                assertTrue(demuxer.isSeekable());
            }
        }

        @Test
        @DisplayName("open with their own demuxers, which reject other files")
        void demuxers() {
            var srt = write(SEQUENTIAL, ContainerFormat.SUBRIP);
            assertThrows(MediaException.Malformed.class, () -> new AssDemuxer(MediaSource.of(srt)));
            assertThrows(MediaException.Malformed.class, () -> new TtmlDemuxer(MediaSource.of(srt)));
            assertThrows(MediaException.Malformed.class, () -> new SamiDemuxer(MediaSource.of(srt)));
            assertThrows(MediaException.Malformed.class, () -> new LrcDemuxer(MediaSource.of(srt)));
            assertThrows(MediaException.Malformed.class, () -> new MicroDvdDemuxer(MediaSource.of(srt)));
            try (var demuxer = new AssDemuxer(MediaSource.of(write(SEQUENTIAL, ContainerFormat.ASS)))) {
                assertEquals(Codec.ASS, demuxer.tracks().getFirst().codec());
                assertEquals(SEQUENTIAL.size(), demuxer.cues().size());
            }
            try (var demuxer = new TtmlDemuxer(MediaSource.of(write(SEQUENTIAL, ContainerFormat.TTML)))) {
                var track = demuxer.tracks().getFirst();
                assertEquals(Codec.TTML, track.codec());
                var packet = demuxer.read();
                assertEquals(0, packet.pts());
                assertEquals(10_000, packet.duration());
                assertEquals(null, demuxer.read());
                demuxer.seek(5_000_000);
                assertEquals(packet, demuxer.read());
                assertEquals(SEQUENTIAL, demuxer.cues());
            }
            try (var demuxer = new LrcDemuxer(MediaSource.of(write(SEQUENTIAL, ContainerFormat.LRC)))) {
                assertEquals(Codec.WEBVTT, demuxer.tracks().getFirst().codec());
            }
        }

        @Test
        @DisplayName("write their tags and keep the muxers to their codecs")
        void muxers() {
            assertTrue(Containers.isMuxable(ContainerFormat.ASS, Codec.ASS));
            assertTrue(!Containers.isMuxable(ContainerFormat.ASS, Codec.SUBRIP));
            assertTrue(Containers.isMuxable(ContainerFormat.TTML, Codec.TTML));
            assertTrue(Containers.isMuxable(ContainerFormat.SAMI, Codec.SUBRIP));
            assertTrue(Containers.isMuxable(ContainerFormat.LRC, Codec.WEBVTT));
            assertTrue(Containers.isMuxable(ContainerFormat.MICRODVD, Codec.SUBRIP));
            assertThrows(IllegalArgumentException.class, () -> new AssMuxer(MediaSink.memory())
                    .addTrack(SubtitleEncoder.track(Codec.SUBRIP, 1)));
            assertThrows(IllegalArgumentException.class, () -> new TtmlMuxer(MediaSink.memory())
                    .addTrack(SubtitleEncoder.track(Codec.SUBRIP, 1)));
            var sink = MediaSink.memory();
            try (var muxer = new LrcMuxer(sink)) {
                muxer.setTags(MediaTags.builder().set(StandardTag.TITLE, "Song").set(StandardTag.ARTIST, "Band").build());
                var track = SubtitleEncoder.track(Codec.WEBVTT, 1);
                muxer.addTrack(track);
                muxer.writePacket(new SubtitleEncoder(track).encode(SubtitleCue.of(0, 1_000_000, "la"), SubtitleMarkup.WEBVTT));
                assertThrows(IllegalStateException.class, () -> muxer.setTags(MediaTags.builder().build()));
                muxer.finish();
            }
            assertEquals("[ti:Song]\n[ar:Band]\n[00:00.00]la\n[00:01.00]\n", new String(sink.toByteArray(), StandardCharsets.UTF_8));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
                assertEquals("Song", demuxer.metadata().get("title"));
                assertEquals("Band", demuxer.metadata().get("artist"));
            }
        }
    }

    @Nested
    @DisplayName("text encodings")
    class Encodings {
        @Test
        @DisplayName("read Windows-1251 and Shift_JIS SubRip files exactly, detected or named")
        void legacy() {
            var russian = List.of(SubtitleCue.of(1_000_000, 2_000_000, "\u041F\u0440\u0438\u0432\u0435\u0442, \u043C\u0438\u0440!"),
                    SubtitleCue.of(3_000_000, 4_000_000, "<i>\u0414\u043E \u0441\u0432\u0438\u0434\u0430\u043D\u0438\u044F</i>"));
            var japanese = List.of(SubtitleCue.of(1_000_000, 2_000_000, "\u3053\u3093\u306B\u3061\u306F\u4E16\u754C"),
                    SubtitleCue.of(3_000_000, 4_000_000, "\u3055\u3088\u3046\u306A\u3089"));
            for (var entry : List.of(new Object[]{"windows-1251", russian}, new Object[]{"Shift_JIS", japanese})) {
                var charset = Charset.forName((String) entry[0]);
                @SuppressWarnings("unchecked")
                var cues = (List<SubtitleCue>) entry[1];
                var file = new String(write(cues, ContainerFormat.SUBRIP), StandardCharsets.UTF_8).getBytes(charset);
                var options = SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).charset(charset).build();
                assertEquals(cues, Subtitles.read(MediaSource.of(file), options), charset.name());
                assertEquals(cues, read(file), charset.name() + " detected");
            }
        }

        @Test
        @DisplayName("apply the encoding option to every text format, a byte order mark winning")
        void everyFormat() {
            var greek = List.of(SubtitleCue.of(1_000_000, 2_000_000, "\u039A\u03B1\u03BB\u03B7\u03BC\u03AD\u03C1\u03B1"));
            var charset = Charset.forName("windows-1253");
            for (var format : List.of(ContainerFormat.SUBRIP, ContainerFormat.WEBVTT, ContainerFormat.ASS,
                    ContainerFormat.SAMI, ContainerFormat.LRC, ContainerFormat.MICRODVD)) {
                var text = new String(write(greek, format), StandardCharsets.UTF_8);
                if (format == ContainerFormat.SAMI) {
                    text = text.replace("charset=utf-8", "");
                }
                var file = text.getBytes(charset);
                var cues = Subtitles.read(MediaSource.of(file), SubtitleReaderOptions.builder()
                        .markup(SubtitleMarkup.SUBRIP).charset(charset).build());
                assertEquals(plain(greek), plain(cues), format.name());
            }
            var bom = ("\uFEFF" + new String(write(greek, ContainerFormat.SUBRIP), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            assertEquals(greek, Subtitles.read(MediaSource.of(bom), SubtitleReaderOptions.builder().charset(charset).build()));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(write(greek, ContainerFormat.SUBRIP)),
                    DemuxerOptions.builder().charset(charset).automaticCharset().build())) {
                assertEquals(Codec.SUBRIP, demuxer.tracks().getFirst().codec());
            }
        }
    }

    @Nested
    @DisplayName("SAMI languages")
    class Languages {
        private static final String FILE = """
                <SAMI><HEAD><STYLE TYPE="text/css"><!--
                .ENCC { Name: English; lang: en-US; }
                .KRCC { Name: Korean; lang: ko-KR; }
                --></STYLE></HEAD><BODY>
                <SYNC Start=1000><P Class=ENCC>Hello<P Class=KRCC>\uC548\uB155
                <SYNC Start=2000><P Class=ENCC>&nbsp;<P Class=KRCC>&nbsp;
                </BODY></SAMI>
                """;

        @Test
        @DisplayName("become tracks with their ISO 639-2 languages and names")
        void tracks() {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(FILE.getBytes(StandardCharsets.UTF_8)))) {
                assertEquals(2, demuxer.tracks().size());
                assertEquals("eng", demuxer.tracks().get(0).language().orElseThrow());
                assertEquals("kor", demuxer.tracks().get(1).language().orElseThrow());
                assertEquals("Korean", demuxer.trackMetadata(2).get("title"));
            }
            var korean = Subtitles.read(MediaSource.of(FILE.getBytes(StandardCharsets.UTF_8)),
                    SubtitleReaderOptions.builder().trackId(2).build());
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_000_000, "\uC548\uB155")), korean);
            var sink = MediaSink.memory();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(FILE.getBytes(StandardCharsets.UTF_8)));
                 var muxer = Containers.openMuxer(ContainerFormat.SAMI, sink)) {
                for (var track : demuxer.tracks()) {
                    muxer.addTrack(track);
                }
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var written = new String(sink.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(written.contains(".ENCC { Name: English; lang: en; SAMIType: CC; }"), written);
            assertTrue(written.contains(".KOCC { Name: Korean; lang: ko; SAMIType: CC; }"), written);
            assertEquals(korean, Subtitles.read(MediaSource.of(sink.toByteArray()),
                    SubtitleReaderOptions.builder().trackId(2).build()));
        }
    }

    @Nested
    @DisplayName("MicroDVD frame rates")
    class FrameRates {
        @Test
        @DisplayName("count frames in the declared rate, the default or the option")
        void rates() {
            var file = "{1}{1}25\n{25}{50}one\n".getBytes(StandardCharsets.US_ASCII);
            assertEquals(1_000_000, read(file).getFirst().startMicros());
            var forced = Subtitles.read(MediaSource.of(file), SubtitleReaderOptions.builder()
                    .subtitleFrameRate(new Rational(50, 1)).build());
            assertEquals(500_000, forced.getFirst().startMicros());
            try (var demuxer = new MicroDvdDemuxer(MediaSource.of(file))) {
                assertEquals(new Rational(25, 1), demuxer.frameRate());
                assertEquals(new Rational(25, 1), demuxer.declaredFrameRate().orElseThrow());
            }
            var undeclared = "{24}{48}x\n".getBytes(StandardCharsets.US_ASCII);
            assertEquals(1_001_000, read(undeclared).getFirst().startMicros());
            assertThrows(IllegalArgumentException.class, () -> SubtitleReaderOptions.builder()
                    .subtitleFrameRate(new Rational(0, 1)).build());
            var sink = MediaSink.memory();
            var track = SubtitleEncoder.track(Codec.SUBRIP, 1);
            try (var muxer = new MicroDvdMuxer(sink, new Rational(30, 1))) {
                muxer.addTrack(track);
                muxer.writePacket(new SubtitleEncoder(track).encode(SubtitleCue.of(1_000_000, 2_000_000, "x"),
                        SubtitleMarkup.SUBRIP));
                muxer.finish();
            }
            assertEquals("{1}{1}30.000\n{30}{60}x\n", new String(sink.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    @Nested
    @DisplayName("script versions")
    class Versions {
        private static final String SSA = """
                [Script Info]
                ScriptType: v4.00

                [V4 Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, \
                Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
                Style: Default,Arial,20,16777215,255,0,0,0,0,1,2,0,6,10,10,10,0,0

                [Events]
                Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0000,0000,0000,,top line
                """;

        @Test
        @DisplayName("write SSA scripts as SSA, convert them on request and carry them in Matroska as S_TEXT/SSA")
        void ssa() {
            var file = SSA.getBytes(StandardCharsets.UTF_8);
            var demuxer = new AssDemuxer(MediaSource.of(file));
            var track = demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            assertEquals("0,0,Default,,0000,0000,0000,,top line", new String(packets.getFirst().data(), StandardCharsets.UTF_8));
            var same = MediaSink.memory();
            try (var muxer = Containers.openMuxer(ContainerFormat.ASS, same)) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            assertEquals(SSA, new String(same.toByteArray(), StandardCharsets.UTF_8));
            var upgraded = MediaSink.memory();
            try (var muxer = new AssMuxer(upgraded, AssScriptType.V4_PLUS)) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            var ass = new String(upgraded.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(ass.contains("ScriptType: v4.00+"), ass);
            assertTrue(ass.contains("Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,"
                    + "1,2,0,8,10,10,10,0"), ass);
            assertTrue(ass.contains("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0000,0000,0000,,top line"), ass);
            var cue = Subtitles.read(MediaSource.of(upgraded.toByteArray()), SubtitleReaderOptions.builder()
                    .markup(SubtitleMarkup.SUBRIP).build()).getFirst();
            assertEquals("{" + BS + "an8}top line", cue.text());
            var mkv = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(mkv)) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            assertTrue(new String(mkv.toByteArray(), StandardCharsets.ISO_8859_1).contains("S_TEXT/SSA"));
            try (var back = Containers.openDemuxer(MediaSource.of(mkv.toByteArray()))) {
                assertEquals(SSA.replace("Dialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0000,0000,0000,,top line\n", ""),
                        new String(back.tracks().getFirst().codecConfig().orElseThrow(), StandardCharsets.UTF_8));
            }
        }
    }

    @Nested
    @DisplayName("transcoding")
    class Transcoding {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OutputContainer.class, names = {"ASS", "SSA", "TTML", "SAMI", "LRC", "MICRODVD"})
        @DisplayName("writes each subtitle file from a SubRip file")
        void fromSubRip(OutputContainer container) {
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(write(SEQUENTIAL, ContainerFormat.SUBRIP)), sink, TranscodeOptions.of(container));
            var cues = read(sink.toByteArray());
            assertEquals(plain(SEQUENTIAL), plain(cues), container.name());
            assertEquals(container.format(), Containers.detect(MediaSource.of(sink.toByteArray())).orElseThrow());
        }

        @Test
        @DisplayName("carries a TTML file into MP4 as an stpp document and converts its styles to 3GPP text")
        void ttmlToMp4() {
            var ttml = write(SEQUENTIAL, ContainerFormat.TTML);
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(ttml), sink, TranscodeOptions.of(OutputContainer.MP4));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
                assertEquals(Codec.TTML, demuxer.tracks().getFirst().codec());
                assertTrue(java.util.Arrays.equals(ttml, demuxer.read().data()));
            }
            assertEquals(SEQUENTIAL, read(sink.toByteArray()));
            var trimmed = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(ttml), trimmed, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                    .start(1_000_000).build());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trimmed.toByteArray()))) {
                assertEquals(Codec.MOV_TEXT, demuxer.tracks().getFirst().codec());
            }
            var cues = read(trimmed.toByteArray());
            assertEquals(SubtitleCue.of(0, 1_000_000, "<i>Hello</i> world"), cues.getFirst());
        }

        @Test
        @DisplayName("gives the 3GPP text of an ASS track the look of its styles")
        void assStyles() {
            var script = """
                    [Script Info]
                    ScriptType: v4.00+

                    [V4+ Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, \
                    Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, \
                    Alignment, MarginL, MarginR, MarginV, Encoding
                    Style: Thought,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,-1,0,0,100,100,0,0,1,2,0,2,10,10,10,1

                    [Events]
                    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    Dialogue: 0,0:00:01.00,0:00:02.00,Thought,,0,0,0,,in my head
                    """;
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(script.getBytes(StandardCharsets.UTF_8)), sink,
                    TranscodeOptions.of(OutputContainer.MP4));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
                assertEquals(Codec.MOV_TEXT, demuxer.tracks().getFirst().codec());
            }
            assertEquals(List.of(SubtitleCue.of(1_000_000, 2_000_000, "<i>in my head</i>")), read(sink.toByteArray()));
        }
    }

    @Test
    @DisplayName("probes the new files with their codecs")
    void probe() {
        for (var format : List.of(ContainerFormat.ASS, ContainerFormat.TTML, ContainerFormat.SAMI, ContainerFormat.LRC,
                ContainerFormat.MICRODVD)) {
            var info = com.github.auties00.smalt.Media.probe(MediaSource.of(write(SEQUENTIAL, format)));
            assertEquals(format, info.container().orElseThrow());
            assertInstanceOf(com.github.auties00.smalt.probe.DataStreamInfo.class, info.streams().getFirst());
        }
        var mp4 = MediaSink.memory();
        try (var muxer = new Mp4Muxer(mp4)) {
            var track = SubtitleEncoder.track(Codec.MOV_TEXT, 1);
            muxer.addTrack(track);
            muxer.writePacket(new SubtitleEncoder(track).encode(SEQUENTIAL.getFirst(), SubtitleMarkup.SUBRIP));
            muxer.finish();
        }
        assertEquals(List.of(SEQUENTIAL.getFirst()), read(mp4.toByteArray()));
    }
}
