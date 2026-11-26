package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.subtitle.CaptionScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the CEA-608 captions Smalt reads from H.264 video with those of ffmpeg's {@code cc_dec} (black box, its
 * {@code subcc} output of the {@code movie} source written as SubRip). The video is made by libx264, with and
 * without B pictures, and gets ATSC caption SEI messages in display order from the test kit's
 * {@link CaptionScript}: a pop-on caption with italics, a roll-up caption with a color, a paint-on caption with
 * special and extended characters.
 *
 * <p>Tolerance: the cues must carry the same text without markup, in the same order, and end within one frame
 * ({@link Tolerance#maxAbs(double)} of 34 ms); a cue may start later than ffmpeg's, as ffmpeg starts a roll-up or
 * paint-on caption at the end of the previous one, but never before it.
 */
@DisplayName("closed captions against ffmpeg")
class ClosedCaptionDifferentialTest {
    private static final Tolerance FRAME = Tolerance.maxAbs(34);
    private static final Pattern TIMING = Pattern.compile("(\\d+):(\\d+):(\\d+),(\\d+) --> (\\d+):(\\d+):(\\d+),(\\d+)");

    static CaptionScript script() {
        return new CaptionScript()
                .command(28, 1, 0x20).command(30, 1, 0x2E).preamble(32, 1, 15, 0x10 | 1 << 1)
                .text(34, 1, "Hello pop-on").control(42, 1, 0x11, 0x2E).text(44, 1, "italic").command(62, 1, 0x2F)
                .command(120, 1, 0x2C)
                .command(150, 1, 0x25).preamble(152, 1, 15, 0).text(154, 1, "Roll one").command(170, 1, 0x2D)
                .text(172, 1, "Roll two").control(180, 1, 0x11, 0x28).text(182, 1, "red").command(200, 1, 0x2D)
                .command(240, 1, 0x2C)
                .command(260, 1, 0x29).preamble(262, 1, 2, 0x10 | 2 << 1).text(264, 1, "Paint ")
                .control(268, 1, 0x11, 0x37).text(270, 1, "e").control(271, 1, 0x12, 0x30).command(300, 1, 0x2C);
    }

    record Cue(long start, long end, String text) {
    }

    static Path directory() {
        return Scratch.directory("caption-differential");
    }

    /**
     * Encodes ten seconds of video at 29.97 Hz, adds the captions of the script to every access unit in display
     * order, and writes it to Matroska.
     */
    static Path captioned(int bFrames) {
        var raw = directory().resolve("raw-" + bFrames + ".mkv");
        Oracle.assumeEncoder("libx264");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=s=96x64:r=30000/1001:d=11", "-c:v", "libx264", "-preset", "ultrafast",
                "-bf", Integer.toString(bFrames), "-pix_fmt", "yuv420p", raw.toString()));
        var frames = script().build(330, 2);
        var output = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(raw));
             var muxer = new MatroskaMuxer(output)) {
            var video = demuxer.tracks().getFirst();
            muxer.addTrack(video);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var index = (int) Math.round(video.timeBase().toMicros(packet.pts()) * 30_000.0 / 1001 / 1_000_000);
                var data = index >= 0 && index < frames.length
                        ? CaptionScript.withSei(packet.data(), frames[index], false) : packet.data();
                muxer.writePacket(packet.withData(data));
            }
            muxer.finish();
        }
        var file = directory().resolve("captioned-" + bFrames + ".mkv");
        try {
            Files.write(file, output.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    static List<Cue> ffmpeg(Path video) {
        var srt = directory().resolve(video.getFileName() + ".srt");
        var movie = video.toAbsolutePath().toString().replace('\\', '/').replace(":", "\\\\:");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "movie=" + movie + "[out0+subcc]", "-map", "0:1", srt.toString()));
        String text;
        try {
            text = Files.readString(srt, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var cues = new ArrayList<Cue>();
        for (var block : text.replace("\r\n", "\n").split("\n\n")) {
            var lines = block.strip().split("\n", 3);
            if (lines.length < 3) {
                continue;
            }
            var matcher = TIMING.matcher(lines[1]);
            assertTrue(matcher.matches(), lines[1]);
            cues.add(new Cue(millis(matcher, 1), millis(matcher, 5), Subtitles.plainText(lines[2],
                    SubtitleMarkup.SUBRIP)));
        }
        return cues;
    }

    static long millis(java.util.regex.Matcher matcher, int group) {
        return ((Long.parseLong(matcher.group(group)) * 60 + Long.parseLong(matcher.group(group + 1))) * 60
                + Long.parseLong(matcher.group(group + 2))) * 1000 + Long.parseLong(matcher.group(group + 3));
    }

    static List<Cue> smalt(Path video) {
        var cues = new ArrayList<Cue>();
        var options = SubtitleReaderOptions.builder().embeddedCaptions(true).build();
        try (var reader = SubtitleReader.open(MediaSource.of(video), options)) {
            assertEquals(com.github.auties00.smalt.track.Codec.CEA_708, reader.track().codec());
            for (var cue = reader.read(); cue != null; cue = reader.read()) {
                cues.add(new Cue(cue.startMicros() / 1000, cue.endMicros() / 1000,
                        Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP)));
            }
        }
        return cues;
    }

    @ParameterizedTest(name = "{0} B pictures")
    @ValueSource(ints = {0, 2})
    @DisplayName("read the same captions as ffmpeg from H.264 caption SEI messages")
    void h264(int bFrames) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var video = captioned(bFrames);
        var expected = ffmpeg(video);
        var actual = smalt(video);
        assertEquals(expected.stream().map(Cue::text).map(String::strip).toList(),
                actual.stream().map(Cue::text).map(String::strip).toList());
        var expectedEnds = expected.stream().mapToInt(cue -> (int) cue.end()).toArray();
        var actualEnds = actual.stream().mapToInt(cue -> (int) cue.end()).toArray();
        FRAME.assertWithin("cue ends", expectedEnds, actualEnds);
        for (var index = 0; index < expected.size(); index++) {
            assertTrue(actual.get(index).start() >= expected.get(index).start() - 34,
                    "cue " + index + " starts at " + actual.get(index).start() + " before ffmpeg's "
                            + expected.get(index).start());
            assertTrue(actual.get(index).start() < actual.get(index).end());
        }
        assertEquals(5, actual.size(), actual.toString());
    }

    /**
     * The captions of Comcast's caption-inspector test assets, checked against its own decodes: the {@code .608}
     * and {@code .708} files log every command and the text it writes, the {@code .ccd} file every cc_data
     * construct of every frame of the transport stream. The text fragments a log writes must be shown by the cues
     * of ours, in order; the cc_data constructs Smalt extracts from the video must be the dump's, in order. The
     * MacCaption ({@code .mcc}) and Scenarist ({@code .scc}) files carry the cc_data of the two films without video.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("caption-inspector assets")
    class CaptionInspector {
        private static Path vector(String name) {
            return com.github.auties00.smalt.testkit.Conformance.vector("caption-inspector-" + name);
        }

        /** Returns the quoted text fragments of a caption-inspector decode log, in order. */
        static List<String> fragments(Path log) {
            String text;
            try {
                text = Files.readString(log, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var result = new ArrayList<String>();
            var matcher = Pattern.compile("\"([^\"]*)\"").matcher(text);
            while (matcher.find()) {
                var fragment = normalize(matcher.group(1));
                // a quotation mark in the caption text pairs the quotes otherwise: the span is then a command list
                if (!fragment.isEmpty() && !fragment.contains("{")) {
                    result.add(fragment);
                }
            }
            return result;
        }

        /**
         * Normalizes a text for comparison: the logs print CEA-608's apostrophe (0x27, a right single quotation mark
         * in its character set) as ASCII, and the 708 logs, like ffmpeg's SubRip, keep markup and ASS hard spaces.
         */
        static String normalize(String text) {
            return text.replace('\u2019', '\'').replace("\\h", " ").replaceAll("<[^>]*>", "").replaceAll("\\s+", " ")
                    .strip();
        }

        /** Asserts that every fragment of a log is shown by the cues, in order. */
        static void assertShown(List<String> fragments, List<Cue> cues, String label) {
            assertShown(fragments, cues, label, 0);
        }

        /**
         * Asserts that every fragment of a log is shown by the cues, in order. A stream read from the video starts at
         * its first random access point: the fragments before the first one ours shows are skipped, at most
         * {@code skippable} of them.
         */
        static void assertShown(List<String> fragments, List<Cue> cues, String label, int skippable) {
            assertTrue(!fragments.isEmpty(), label + ": the log writes no text");
            assertTrue(!cues.isEmpty(), label + ": no cue");
            var shown = normalize(String.join(" ", cues.stream().map(Cue::text).toList()));
            var first = 0;
            while (first < skippable && first < fragments.size()
                    && !normalize(cues.getFirst().text()).contains(fragments.get(first))) {
                first++;
            }
            if (first == skippable || first == fragments.size()) {
                first = 0;
            }
            fragments = fragments.subList(first, fragments.size());
            var from = 0;
            for (var fragment : fragments) {
                var at = shown.indexOf(fragment, from);
                assertTrue(at >= 0, label + ": \"" + fragment + "\" is not shown after "
                        + shown.substring(Math.max(0, from - 80), from));
                from = at + fragment.length();
            }
        }

        /** Decodes cc_data packets for one caption service. */
        static List<Cue> decode(List<com.github.auties00.smalt.track.Packet> packets, CaptionService service) {
            var track = com.github.auties00.smalt.track.DataTrack.builder().id(1)
                    .codec(com.github.auties00.smalt.track.Codec.CEA_708)
                    .timeBase(new com.github.auties00.smalt.track.Rational(1001, 30000)).build();
            var decoder = new SubtitleDecoder(track, service);
            var cues = new ArrayList<SubtitleCue>();
            for (var packet : packets) {
                cues.addAll(decoder.decodeAll(packet));
            }
            cues.addAll(decoder.flush());
            return cues.stream().map(cue -> new Cue(cue.startMicros() / 1000, cue.endMicros() / 1000,
                    Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP))).toList();
        }

        /** Returns the cc_data of each line of a MacCaption file, expanded from its one-letter codes. */
        static List<com.github.auties00.smalt.track.Packet> mcc(Path file) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var codes = new java.util.HashMap<Character, String>();
            for (var repeat = 1; repeat <= 9; repeat++) {
                codes.put((char) ('G' + repeat - 1), "FA0000".repeat(repeat));
            }
            codes.put('P', "FB8080");
            codes.put('Q', "FC8080");
            codes.put('R', "FD8080");
            codes.put('S', "9669");
            codes.put('T', "6101");
            codes.put('U', "E1000000");
            codes.put('Z', "00");
            var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
            var frame = 0L;
            for (var line : lines) {
                var tab = line.indexOf('\t');
                if (tab < 0 || !Character.isDigit(line.charAt(0))) {
                    continue;
                }
                var hex = new StringBuilder();
                for (var c : line.substring(tab + 1).strip().toCharArray()) {
                    hex.append(codes.getOrDefault(c, String.valueOf(c)));
                }
                var bytes = java.util.HexFormat.of().parseHex(hex.length() % 2 == 0 ? hex : hex.substring(0,
                        hex.length() - 1));
                // the SMPTE 334 packet: DID 61h, SDID 01h, the data count, then the caption distribution packet
                // (SMPTE 334-2: 96h 69h, its length, rate, flags, sequence, then section 72h of cc_data)
                for (var at = 0; at + 1 < bytes.length; at++) {
                    if ((bytes[at] & 0xFF) == 0x96 && (bytes[at + 1] & 0xFF) == 0x69) {
                        var section = at + 7;
                        while (section < bytes.length && (bytes[section] & 0xFF) != 0x72) {
                            section++;
                        }
                        if (section + 1 < bytes.length) {
                            var count = bytes[section + 1] & 0x1F;
                            var end = Math.min(bytes.length, section + 2 + 3 * count);
                            packets.add(new com.github.auties00.smalt.track.Packet(1,
                                    java.util.Arrays.copyOfRange(bytes, section + 2, end), frame, frame, 1, true));
                        }
                        break;
                    }
                }
                frame++;
            }
            return packets;
        }

        /** Returns the field 1 byte pairs of a Scenarist file as cc_data, one pair a frame from each time code. */
        static List<com.github.auties00.smalt.track.Packet> scc(Path file) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
            var timecode = Pattern.compile("(\\d+):(\\d+):(\\d+)[:;](\\d+)\\s+(.*)");
            var last = -1L;
            for (var line : lines) {
                var matcher = timecode.matcher(line.strip());
                if (!matcher.matches()) {
                    continue;
                }
                var frame = ((Long.parseLong(matcher.group(1)) * 60 + Long.parseLong(matcher.group(2))) * 60
                        + Long.parseLong(matcher.group(3))) * 30 + Long.parseLong(matcher.group(4));
                frame = Math.max(frame, last + 1);
                for (var word : matcher.group(5).strip().split("\\s+")) {
                    var pair = java.util.HexFormat.of().parseHex(word);
                    packets.add(new com.github.auties00.smalt.track.Packet(1, new byte[]{(byte) 0xFC, pair[0], pair[1]},
                            frame, frame, 1, true));
                    last = frame++;
                }
            }
            return packets;
        }

        /** Returns the captions of a video for one service, read from the video's caption data. */
        static List<Cue> video(Path file, CaptionService service) {
            var cues = new ArrayList<Cue>();
            var options = SubtitleReaderOptions.builder().embeddedCaptions(true).captionService(service).build();
            try (var reader = SubtitleReader.open(MediaSource.of(file), options)) {
                for (var cue = reader.read(); cue != null; cue = reader.read()) {
                    cues.add(new Cue(cue.startMicros() / 1000, cue.endMicros() / 1000,
                            Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP)));
                }
            }
            return cues;
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({"BigBuckBunny_256x144-24fps-C1.608, CC1",
                "BigBuckBunny_256x144-24fps-C3.608, CC3", "BigBuckBunny_256x144-24fps-S1.708, S1",
                "BigBuckBunny_256x144-24fps-S2.708, S2", "BigBuckBunny_256x144-24fps-S3.708, S3",
                "BigBuckBunny_256x144-24fps-S4.708, S4", "BigBuckBunny_256x144-24fps-S5.708, S5",
                "BigBuckBunny_256x144-24fps-S6.708, S6"})
        @DisplayName("decode every caption channel and service of the transport stream and of its MCC file as logged")
        void bigBuckBunny(String log, String service) {
            var selected = service.startsWith("CC") ? CaptionService.cea608(service.charAt(2) - '0')
                    : CaptionService.cea708(service.charAt(1) - '0');
            var expected = fragments(vector(log));
            assertShown(expected, video(vector("BigBuckBunny_256x144-24fps.ts"), selected), log + " from the TS", 4);
            assertShown(expected, decode(mcc(vector("BigBuckBunny_256x144-24fps.mcc")), selected),
                    log + " from the MCC", 4);
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({"NightOfTheLivingDead-C1.608, NightOfTheLivingDead.mcc, CC1",
                "NightOfTheLivingDead-S1.708, NightOfTheLivingDead.mcc, S1",
                "Plan9fromOuterSpace-C1.608, Plan9fromOuterSpace.mcc, CC1",
                "Plan9fromOuterSpace-C1.608, Plan9fromOuterSpace.scc, CC1"})
        @DisplayName("decode the films' MacCaption and Scenarist captions as logged")
        void films(String log, String source, String service) {
            var selected = service.startsWith("CC") ? CaptionService.cea608(service.charAt(2) - '0')
                    : CaptionService.cea708(service.charAt(1) - '0');
            var packets = source.endsWith(".scc") ? scc(vector(source)) : mcc(vector(source));
            assertShown(fragments(vector(log)), decode(packets, selected), log + " from " + source);
        }

        @org.junit.jupiter.api.Test
        @DisplayName("extracts from the transport stream's H.264 every cc_data construct of the CCD dump, in order")
        void ccData() throws IOException {
            var expected = new ArrayList<String>();
            var token = Pattern.compile("\\b(F1|F2|PS|PD):([0-9A-F]{4})\\b");
            for (var line : Files.readAllLines(vector("BigBuckBunny_256x144-24fps.ccd"), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith(" |") || line.startsWith("/*") || line.contains("=")) {
                    continue;
                }
                var matcher = token.matcher(line);
                while (matcher.find()) {
                    var type = switch (matcher.group(1)) {
                        case "F1" -> 0;
                        case "F2" -> 1;
                        case "PD" -> 2;
                        default -> 3;
                    };
                    expected.add(type + ":" + matcher.group(2));
                }
            }
            var actual = new ArrayList<String>();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(vector("BigBuckBunny_256x144-24fps.ts")))) {
                var videoTrack = demuxer.tracks().stream().filter(track -> track.kind()
                        == com.github.auties00.smalt.track.TrackKind.VIDEO).findFirst().orElseThrow();
                var extractor = new CaptionExtractor(videoTrack);
                var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == videoTrack.id()) {
                        packets.addAll(extractor.extract(packet));
                    }
                }
                packets.addAll(extractor.flush());
                for (var packet : packets) {
                    var data = packet.data();
                    for (var at = 0; at + 2 < data.length; at += 3) {
                        if ((data[at] & 0x04) != 0) {
                            actual.add((data[at] & 0x03) + ":" + java.util.HexFormat.of().withUpperCase()
                                    .formatHex(data, at + 1, at + 3));
                        }
                    }
                }
            }
            assertTrue(expected.size() > 1000, expected.size() + " constructs");
            // the dump starts at the stream's first picture; ours starts at its first random access point
            var start = java.util.Collections.indexOfSubList(expected, actual.subList(0, Math.min(64, actual.size())));
            assertTrue(start >= 0, "the first constructs of ours are not in the dump");
            var compared = expected.subList(start, Math.min(expected.size(), start + actual.size()));
            assertEquals(compared, actual.subList(0, compared.size()));
            assertTrue(expected.size() - start - compared.size() <= 32, "the dump has constructs past ours");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"BigBuckBunny_160x90-24fps.mov", "BigBuckBunny_160x90-24fps.mpg"})
        @DisplayName("read the uncaptioned source assets without captions, as ffmpeg finds none")
        void sourceAssets(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            // the assets the MCC captions were authored for: ffmpeg's cc_dec finds no caption in them either
            assertTrue(ffmpeg(file).isEmpty(), "ffmpeg reads captions");
            assertTrue(video(file, CaptionService.cea608(1)).isEmpty());
            assertTrue(video(file, CaptionService.cea708(1)).isEmpty());
            if (name.endsWith(".mov")) {
                com.github.auties00.smalt.container.ThirdPartyDemux.assertMatchesFfprobe(file,
                        Containers::openDemuxer, com.github.auties00.smalt.container.ThirdPartyDemux.Options.DEFAULT);
            }
        }

        @org.junit.jupiter.api.Test
        @DisplayName("finds the caption channels and services the MediaInfo report of the transport stream lists")
        void services() throws IOException {
            var report = Files.readString(vector("BigBuckBunny_256x144-24fps.inf"), StandardCharsets.ISO_8859_1);
            var formats = Pattern.compile("Text_Format_List\\s*:\\s*(.*)").matcher(report);
            assertTrue(formats.find());
            var list = List.of(formats.group(1).strip().split("\\s*/\\s*"));
            var cea608 = list.stream().filter("EIA-608"::equals).count();
            var cea708 = list.stream().filter("EIA-708"::equals).count();
            assertEquals(2, cea608);
            assertEquals(6, cea708);
            var file = vector("BigBuckBunny_256x144-24fps.ts");
            for (var channel : new int[]{1, 3}) {
                assertTrue(!video(file, CaptionService.cea608(channel)).isEmpty(), "CC" + channel);
            }
            for (var service = 1; service <= cea708; service++) {
                assertTrue(!video(file, CaptionService.cea708(service)).isEmpty(), "service " + service);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fate-sub-scte20.ts", "fate-sub-Closedcaption_rollup.m2v"})
    @DisplayName("read the same CEA-608 captions as ffmpeg from FATE's SCTE 20 and MPEG-2 roll-up samples")
    void fate(String name) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = com.github.auties00.smalt.testkit.Conformance.vector(name);
        var expected = ffmpeg(file);
        assertTrue(!expected.isEmpty(), "ffmpeg reads captions");
        var actual = CaptionInspector.video(file, CaptionService.cea608(1));
        assertEquals(expected.stream().map(Cue::text).map(CaptionInspector::normalize).toList(),
                actual.stream().map(Cue::text).map(CaptionInspector::normalize).toList(), name);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("decode FATE's Scenarist file to the text ffmpeg's SCC reader shows")
    void witch() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = com.github.auties00.smalt.testkit.Conformance.vector("fate-sub-witch.scc");
        var srt = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i",
                file.toString(), "-f", "srt", "-")), StandardCharsets.UTF_8);
        var fragments = new ArrayList<String>();
        for (var block : srt.replace("\r", "").split("\n\n")) {
            var lines = block.strip().split("\n");
            for (var index = 2; index < lines.length; index++) {
                var line = CaptionInspector.normalize(Subtitles.plainText(lines[index], SubtitleMarkup.SUBRIP));
                // the third row's preamble (0x92DA) indents it 20 columns, where ffmpeg places it at 16: its last
                // column is the 32nd, which CEA-608 overwrites with each later character, ffmpeg with none; the
                // columns before it are compared
                if (line.equals("[ Bangs head")) {
                    line = "[ Bangs hea";
                }
                if (!line.isEmpty()) {
                    fragments.add(line);
                }
            }
        }
        CaptionInspector.assertShown(fragments, CaptionInspector.decode(CaptionInspector.scc(file),
                CaptionService.cea608(1)), "witch.scc");
    }
}
