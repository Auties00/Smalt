package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authors a DVD-Video title set from a video object Smalt writes with dvdauthor, run as a black box, compares the
 * search and synchronous information of the navigation packs dvdauthor rewrites with Smalt's, field by field, and
 * plays the title set with Smalt's own video object in place of dvdauthor's through libdvdnav, also a black box.
 */
@DisplayName("DVD-Video navigation packs against dvdauthor")
class DvdAuthorInteropTest {
    private static final String[] SRI_NAMES = new String[42];

    static {
        SRI_NAMES[0] = "next unit with video";
        SRI_NAMES[20] = "next unit";
        SRI_NAMES[21] = "previous unit";
        SRI_NAMES[41] = "previous unit with video";
        for (var span = 0; span < DvdNavigationTest.SPANS.length; span++) {
            SRI_NAMES[19 - span] = "forward " + DvdNavigationTest.SPANS[span];
            SRI_NAMES[22 + span] = "backward " + DvdNavigationTest.SPANS[span];
        }
    }

    /** A video object Smalt wrote, and the title set dvdauthor authored from it. */
    private record Authored(byte[] ours, Path directory, Path output) {
        Path vob() {
            return output.resolve("VIDEO_TS").resolve("VTS_01_1.VOB");
        }
    }

    @Test
    @DisplayName("rewrites Smalt's navigation packs with the same search and synchronous information")
    void sameNavigation() throws Exception {
        compare(author("same", pal(List.of())));
    }

    @Test
    @DisplayName("agrees on the synchronous information of two audio codecs, an audio gap and two subpicture streams")
    void sameSynchronousInformation() throws Exception {
        var source = withSubpictures(pal(List.of("-map", "0:v", "-map", "1:a", "-map", "1:a", "-c:a:1", "mp2",
                "-b:a:1", "128k")));
        var authored = author("synci", source);
        compare(authored);
        // the cell exercises what the test is about: the MP2 gap, and subpictures both before and within units
        var mine = new DvdNavigationTest.Vob(authored.ours());
        var mp2Gap = 0;
        var pictureWithin = 0;
        var pictureBefore = 0;
        for (var i = 0; i < mine.units.size(); i++) {
            var time = mine.units.get(i).start() - mine.units.getFirst().start();
            if (time > 50 * 90_000L && time < 60 * 90_000L) {
                mp2Gap += mine.audio(i, 1) > 0x8000 + 1000 && mine.audio(i, 1) < 0xBFFF ? 1 : 0;
            }
            for (var number = 0; number < 2; number++) {
                pictureWithin += mine.subpicture(i, number) == 0x7FFFFFFFL ? 1 : 0;
                pictureBefore += mine.subpicture(i, number) >= 0x80000000L ? 1 : 0;
            }
        }
        assertTrue(mp2Gap > 0, "units in the MP2 gap address the last frame before it, far behind");
        assertTrue(pictureWithin > 0 && pictureBefore > 0, "subpictures start within units and before them: "
                + pictureWithin + " within, " + pictureBefore + " before");
    }

    /**
     * Compares the navigation packs of Smalt's video object with those dvdauthor rewrote, field by field.
     */
    private static void compare(Authored authored) throws Exception {
        var ours = authored.ours();
        var bytes = Files.readAllBytes(authored.vob());
        var mine = new DvdNavigationTest.Vob(ours);
        var theirs = new DvdNavigationTest.Vob(bytes, false);
        var report = new StringBuilder();
        report.append("ours: ").append(ours.length / 2048).append(" blocks, ").append(mine.units.size())
                .append(" units; dvdauthor: ").append(bytes.length / 2048).append(" blocks, ")
                .append(theirs.units.size()).append(" units\n");
        var differences = 0;
        var units = Math.min(mine.units.size(), theirs.units.size());
        for (var i = 0; i < units && differences < 400; i++) {
            var a = mine.units.get(i);
            var b = theirs.units.get(i);
            // dvdauthor rewrites the end of the last unit past its last video frame (by 960 ticks for this source);
            // that end is not part of the search or synchronous information, so only its start is compared
            var last = i == units - 1;
            if (last ? a.lbn() != b.lbn() || a.last() != b.last() || a.start() != b.start() || a.video() != b.video()
                    : !a.equals(b)) {
                report.append("unit ").append(i).append(": ours ").append(a).append(", dvdauthor ").append(b)
                        .append('\n');
                differences++;
            }
            var want = theirs.search(i);
            var got = mine.search(i);
            for (var entry = 0; entry < 42; entry++) {
                if (want[entry] != got[entry]) {
                    report.append("unit ").append(i).append(' ').append(SRI_NAMES[entry]).append(": ours 0x")
                            .append(Long.toHexString(got[entry])).append(", dvdauthor 0x")
                            .append(Long.toHexString(want[entry])).append('\n');
                    differences++;
                }
            }
            for (var number = 0; number < 8; number++) {
                if (mine.audio(i, number) != theirs.audio(i, number)) {
                    report.append("unit ").append(i).append(" audio ").append(number).append(": ours 0x")
                            .append(Integer.toHexString(mine.audio(i, number))).append(", dvdauthor 0x")
                            .append(Integer.toHexString(theirs.audio(i, number))).append('\n');
                    differences++;
                }
            }
            for (var number = 0; number < 32; number++) {
                if (!sameSubpicture(mine, theirs, i, number)) {
                    report.append("unit ").append(i).append(" subpicture ").append(number).append(": ours 0x")
                            .append(Long.toHexString(mine.subpicture(i, number))).append(", dvdauthor 0x")
                            .append(Long.toHexString(theirs.subpicture(i, number))).append('\n');
                    differences++;
                }
            }
        }
        System.out.println(report);
        if (differences > 0) {
            var table = new StringBuilder("unit lbn start | A_SYNCA 0,1 ours / dvdauthor | SP_SYNCA 0,1 ours / dvdauthor\n");
            for (var i = 0; i < units; i++) {
                var unit = mine.units.get(i);
                table.append(i).append(' ').append(unit.lbn()).append(' ').append(unit.start()).append(" | ");
                for (var number = 0; number < 2; number++) {
                    table.append(Integer.toHexString(mine.audio(i, number))).append('/')
                            .append(Integer.toHexString(theirs.audio(i, number))).append(' ');
                }
                table.append("| ");
                for (var number = 0; number < 2; number++) {
                    table.append(Long.toHexString(mine.subpicture(i, number))).append('/')
                            .append(Long.toHexString(theirs.subpicture(i, number))).append(' ');
                }
                table.append('\n');
            }
            for (var packet : mine.pes) {
                if (packet.key() >= 0x120 && packet.key() < 0x140) {
                    table.append("pes 0x").append(Integer.toHexString(packet.key())).append(" lbn ")
                            .append(packet.lbn()).append(" pts ").append(packet.pts()).append(" bytes ")
                            .append(packet.length()).append('\n');
                }
            }
            System.out.println(table);
        }
        assertEquals(mine.units.size(), theirs.units.size(), report.toString());
        assertTrue(differences == 0, report.toString());
    }

    /**
     * Returns whether a subpicture address of Smalt's agrees with dvdauthor's.
     *
     * <p>dvdauthor departs from the published description of {@code SP_SYNCA} ("relative offset to 1st subpicture
     * packet for this VOBU"; {@code 3FFFFFFF} no subpicture data for this unit, {@code 7FFFFFFF} data within this
     * unit), which Smalt follows: it addresses the navigation pack of the unit holding the subpicture rather than the
     * subpicture's pack, writes {@code 7FFFFFFF} where no subpicture is displayed, and, before a stream's first
     * subpicture, addresses an upcoming unit. The addresses agree when they name the same subpicture: Smalt's pack
     * lies in the unit dvdauthor addresses, both mark data within the unit, or Smalt finds none where dvdauthor writes
     * {@code 7FFFFFFF} or, before the first subpicture, a forward address.
     */
    private static boolean sameSubpicture(DvdNavigationTest.Vob mine, DvdNavigationTest.Vob theirs, int i, int number) {
        var ours = mine.subpicture(i, number);
        var dvdauthor = theirs.subpicture(i, number);
        if (ours == dvdauthor) {
            return true;
        }
        var lbn = mine.units.get(i).lbn();
        var key = 0x120 + number;
        if (ours == 0x3FFFFFFFL) {
            var first = mine.pes.stream().filter(packet -> packet.key() == key).mapToLong(DvdNavigationTest.Vob.Pes::lbn)
                    .min().orElse(Long.MAX_VALUE);
            return dvdauthor == 0x7FFFFFFFL || (dvdauthor & 0x80000000L) == 0 && lbn < first;
        }
        if (ours == 0x7FFFFFFFL || dvdauthor == 0 || dvdauthor == 0x3FFFFFFFL || dvdauthor == 0x7FFFFFFFL) {
            return false;
        }
        var target = (ours & 0x80000000L) != 0 ? lbn - (ours & 0x7FFFFFFFL) : lbn + ours;
        var unit = (dvdauthor & 0x80000000L) != 0 ? lbn - (dvdauthor & 0x7FFFFFFFL) : lbn + dvdauthor;
        return mine.units.stream().anyMatch(other -> other.lbn() == unit && target > unit && target <= other.last());
    }

    @Test
    @DisplayName("plays through libdvdnav with Smalt's video object in place of dvdauthor's, every unit in order")
    void playsThroughLibdvdnav() throws Exception {
        var authored = author("play", pal(List.of()));
        var compiler = Oracle.find("cc");
        var pkgConfig = Oracle.find("pkg-config");
        Skip.unless(compiler.isPresent() && pkgConfig.isPresent(), "oracle:libdvdnav", "cc and pkg-config are needed");
        var flags = Oracle.execute(pkgConfig.get(), Duration.ofMinutes(1), null, List.of("--cflags", "--libs", "dvdnav"));
        Skip.unless(flags.exitCode() == 0, "oracle:libdvdnav", "libdvdnav is not installed: " + flags.stderr());
        var source = authored.directory().resolve("dvdnav-play.c");
        try (InputStream in = Objects.requireNonNull(DvdAuthorInteropTest.class.getResourceAsStream("dvdnav-play.c"))) {
            Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
        }
        var player = authored.directory().resolve("dvdnav-play");
        var arguments = new ArrayList<>(List.of("-O1", "-o", player.toString(), source.toString()));
        arguments.addAll(List.of(new String(flags.stdout(), StandardCharsets.UTF_8).trim().split("\\s+")));
        var build = Oracle.execute(compiler.get(), Duration.ofMinutes(2), null, arguments);
        assertEquals(0, build.exitCode(), "cc: " + build.stderr());

        // dvdauthor's video object has the same blocks as Smalt's, its navigation packs rewritten: put Smalt's back
        assertEquals(Files.size(authored.vob()), authored.ours().length, "dvdauthor kept the block layout");
        Files.write(authored.vob(), authored.ours());
        var played = Oracle.execute(player, Duration.ofMinutes(2), null, List.of(authored.output().toString()));
        assertEquals(0, played.exitCode(), "dvdnav-play: " + played.stderr());
        // libdvdread and libdvdnav print their own notices on standard output too
        var lines = new String(played.stdout(), StandardCharsets.US_ASCII).lines()
                .filter(line -> line.matches("[0-9]+|end [a-z]+"))
                .toList();
        var expected = new DvdNavigationTest.Vob(authored.ours()).units.stream()
                .map(unit -> Long.toString(unit.lbn())).toList();
        assertTrue(lines.size() > expected.size(), "dvdnav-play printed " + lines.size() + " lines");
        assertEquals(expected, lines.subList(0, expected.size()), "the navigation packs libdvdnav handed out");
        var after = lines.get(expected.size());
        assertTrue(after.startsWith("end ") || after.equals(expected.getFirst()),
                "after the last unit libdvdnav ends or replays the title, not " + after);
    }

    /**
     * Returns a 150 s PAL DVD source from ffmpeg: MPEG-2 video and AC-3 audio, plus whatever the extra output options
     * add (the extra options come after the AC-3 settings, so they may override them per stream).
     */
    private static PsMuxerTest.Source pal(List<String> extra) {
        // CI provisions dvdauthor, through apt, on its Linux runners only
        Skip.unless(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"),
                "platform:linux", "dvdauthor runs on Linux only");
        Oracle.assume("dvdauthor");
        var output = new ArrayList<>(List.of("-target", "pal-dvd", "-b:v", "1500k", "-g", "12", "-bf", "2", "-c:a",
                "ac3", "-b:a", "192k"));
        output.addAll(extra);
        return PsMuxerTest.source(Fixtures.ffmpeg("vob", List.of("-f", "lavfi", "-i",
                        "testsrc2=size=720x576:rate=25:duration=150", "-f", "lavfi", "-i",
                        "sine=frequency=440:sample_rate=48000:duration=150"), output), PsDemuxer::new);
    }

    /**
     * Adds to a source a gap from 40 to 70 s and an end at 130 s in its MP2 track and the two subpicture streams of the DVD sample, their
     * units repeated through the cell (several within one unit, and some displayed across units).
     */
    private static PsMuxerTest.Source withSubpictures(PsMuxerTest.Source source) {
        var tracks = new ArrayList<>(source.tracks());
        var packets = new ArrayList<Packet>();
        var earliest = Long.MAX_VALUE;
        for (var packet : source.packets()) {
            var track = trackOf(source, packet.trackId());
            var micros = track.timeBase().toMicros(packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts());
            earliest = Math.min(earliest, micros);
        }
        for (var packet : source.packets()) {
            var track = trackOf(source, packet.trackId());
            var seconds = (track.timeBase().toMicros(packet.pts()) - earliest) / 1e6;
            if (track.codec() != Codec.MP2 || seconds < 40 || seconds >= 70 && seconds < 130) {
                packets.add(packet);
            }
        }
        var sample = PsMuxerTest.sample("dvd.vob");
        var id = 100;
        double[] starts = {1, 1.2, 9, 30, 30.3, 30.6, 61, 95, 130, 147};
        for (var track : sample.tracks()) {
            if (track.kind() != TrackKind.DATA) {
                continue;
            }
            var data = ((DataTrack) track).toBuilder().id(id).build();
            tracks.add(data);
            var units = sample.packets().stream().filter(packet -> packet.trackId() == track.id()).toList();
            for (var index = 0; index < starts.length; index++) {
                var unit = units.get(index % units.size());
                var pts = Rational.timeBaseOf(1_000_000).rescale(earliest + (long) (starts[index] * 1e6)
                        + (id - 100) * 200_000L, data.timeBase());
                packets.add(unit.withTrackId(id).withTiming(pts, pts, unit.duration()));
            }
            id++;
        }
        return PsMuxerTest.interleaved(new PsMuxerTest.Source(tracks, packets));
    }

    private static Track trackOf(PsMuxerTest.Source source, int id) {
        return source.tracks().stream().filter(track -> track.id() == id).findFirst().orElseThrow();
    }

    /** Muxes a source to a DVD video object and authors a title set from it with dvdauthor. */
    private static Authored author(String name, PsMuxerTest.Source unordered) throws Exception {
        var source = PsMuxerTest.interleaved(unordered);
        var sink = MediaSink.memory();
        try (var muxer = new PsMuxer(sink, PsMuxerOptions.forDvd())) {
            source.tracks().forEach(muxer::addTrack);
            source.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var ours = sink.toByteArray();
        var directory = Scratch.directory("dvdauthor-" + name);
        var input = directory.resolve("smalt.vob");
        Files.write(input, ours);
        var output = directory.resolve("dvd");
        if (Files.exists(output)) {
            try (var files = Files.walk(output)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        var config = directory.resolve("dvd.xml");
        Files.writeString(config, "<dvdauthor dest=\"" + output.toAbsolutePath() + "\"><vmgm><menus>"
                + "<video format=\"pal\"/></menus></vmgm><titleset><titles>"
                + "<video format=\"pal\"/><pgc><vob file=\"" + input.toAbsolutePath() + "\"/></pgc></titles>"
                + "</titleset></dvdauthor>", StandardCharsets.UTF_8);
        var result = Oracle.execute(Oracle.find("dvdauthor").orElseThrow(), Duration.ofMinutes(5), null,
                List.of("-x", config.toAbsolutePath().toString()));
        assertEquals(0, result.exitCode(), "dvdauthor: " + result.stderr());
        return new Authored(ours, directory, output);
    }
}
