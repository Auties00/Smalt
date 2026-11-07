package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the search ({@code VOBU_SRI}) and synchronous ({@code SYNCI}) information of the DVD-Video navigation packs:
 * the index on its own against a brute-force reference over random cells, and muxed video objects (the DVD sample and
 * a synthetic two-and-a-half-minute cell with audio gaps and subpictures) read back by a parser of this class that
 * derives every expected pointer from the pack layout and the source packets alone, on seekable and streaming sinks.
 */
@DisplayName("DVD-Video search and synchronous information")
class DvdNavigationTest {
    /** The spans of the search pointers, in half seconds, closest first. */
    static final int[] SPANS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 20, 60, 120, 240};

    static final long NONE = 0x3FFFFFFFL;
    static final long NO_VIDEO = 0xBFFFFFFFL;
    static final long VALID = 0x80000000L;
    static final Rational TICKS = Rational.timeBaseOf(90_000);

    /** A video object unit: its navigation pack, last pack, start and end presentation times and video presence. */
    record Unit(long lbn, long last, long start, long end, boolean video) {
    }

    /** A pack that starts an audio frame or a subpicture unit; {@code end} is the display end or MIN_VALUE. */
    record Mark(long lbn, long pts, long end) {
    }

    /** The search entries a unit must carry, computed from the known units by brute force. */
    static long[] expectedSearch(List<Unit> units, int known, boolean finished, long cellEnd, int i) {
        var out = new long[42];
        var unit = units.get(i);
        var newest = known - 1;
        out[0] = NO_VIDEO;
        for (var j = i + 1; j <= newest; j++) {
            if (units.get(j).video()) {
                out[0] = VALID | (units.get(j).lbn() - unit.lbn());
                break;
            }
        }
        var previous = i + 1;
        var valid = i < newest;
        var edge = valid;
        for (var span = 0; span < SPANS.length; span++) {
            var time = unit.start() + SPANS[span] * 45_000L;
            if (finished && time >= cellEnd) {
                valid = false;
            }
            if (!valid) {
                // the first span past the end of the cell marks the units after the closer span's target
                out[19 - span] = NONE | (edge && previous < newest ? 0x40000000L : 0);
                edge = false;
                continue;
            }
            var target = i + 1;
            for (var j = i + 1; j <= newest; j++) {
                if (units.get(j).start() <= time) {
                    target = j;
                }
            }
            out[19 - span] = VALID | (target - previous > 1 ? 0x40000000L : 0) | (units.get(target).lbn() - unit.lbn());
            previous = target;
        }
        out[20] = i < newest ? VALID | (units.get(i + 1).lbn() - unit.lbn()) : NONE;
        out[21] = i > 0 ? VALID | (unit.lbn() - units.get(i - 1).lbn()) : NONE;
        previous = i - 1;
        valid = i > 0;
        edge = valid;
        for (var span = 0; span < SPANS.length; span++) {
            var time = unit.start() - SPANS[span] * 45_000L;
            if (time < units.getFirst().start()) {
                valid = false;
            }
            if (!valid) {
                out[22 + span] = NONE | (edge && previous > 0 ? 0x40000000L : 0);
                edge = false;
                continue;
            }
            var target = 0;
            for (var j = 0; j < i; j++) {
                if (units.get(j).start() <= time) {
                    target = j;
                }
            }
            out[22 + span] = VALID | (previous - target > 1 ? 0x40000000L : 0) | (unit.lbn() - units.get(target).lbn());
            previous = target;
        }
        out[41] = NO_VIDEO;
        for (var j = i - 1; j >= 0; j--) {
            if (units.get(j).video()) {
                out[41] = VALID | (unit.lbn() - units.get(j).lbn());
                break;
            }
        }
        return out;
    }

    /**
     * The audio entry of a unit: the pack of the last frame starting at or before its start, even in a gap, or else
     * of the next frame.
     */
    static int expectedAudio(List<Mark> frames, Unit unit) {
        Mark target = null;
        for (var frame : frames) {
            if (frame.pts() <= unit.start()) {
                target = frame;
            }
        }
        if (target == null) {
            for (var frame : frames) {
                if (frame.pts() > unit.start()) {
                    target = frame;
                    break;
                }
            }
        }
        if (target == null) {
            return 0x3FFF;
        }
        var distance = target.lbn() - unit.lbn();
        if (distance > 0 && distance <= 0x3FFE) {
            return (int) distance;
        }
        return distance < 0 && -distance <= 0x3FFE ? 0x8000 | (int) -distance : 0x3FFF;
    }

    /** The subpicture entry of a unit: the unit displayed at its start, or else the first starting during it. */
    static long expectedSubpicture(List<Mark> pictures, Unit unit, long end) {
        Mark target = null;
        for (var picture : pictures) {
            if (picture.pts() <= unit.start()) {
                target = picture;
            }
        }
        if (target != null && target.end() != Long.MIN_VALUE && target.end() <= unit.start()) {
            target = null;
        }
        if (target == null) {
            for (var picture : pictures) {
                if (picture.pts() >= unit.start() && picture.pts() < end) {
                    target = picture;
                    break;
                }
            }
        }
        if (target == null) {
            return 0x3FFFFFFFL;
        }
        var distance = target.lbn() - unit.lbn();
        if (distance > 0 && target.lbn() <= unit.last()) {
            return 0x7FFFFFFFL;
        }
        return distance > 0 ? distance : VALID | -distance;
    }

    static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8
                | data[at + 3] & 0xFFL;
    }

    @Nested
    @DisplayName("index")
    class Index {
        static List<Unit> randomCell(Random random, int count) {
            var units = new ArrayList<Unit>();
            var lbn = 0L;
            var time = 50_000L;
            for (var index = 0; index < count; index++) {
                var blocks = 1 + random.nextInt(random.nextInt(10) == 0 ? 400 : 30);
                var length = 9_000 + random.nextInt(random.nextInt(8) == 0 ? 200_000 : 90_000);
                units.add(new Unit(lbn, lbn + blocks - 1, time, time + length, random.nextInt(5) != 0));
                lbn += blocks;
                time += length;
            }
            return units;
        }

        static long[] read(byte[] block) {
            var out = new long[42];
            for (var entry = 0; entry < 42; entry++) {
                out[entry] = u32(block, 4 * entry);
            }
            return out;
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("fills each unit, as soon as it resolves, as a brute-force search over the whole cell does")
        void resolved(int seed) {
            var random = new Random(seed);
            var units = randomCell(random, 1500);
            var cellEnd = units.stream().mapToLong(Unit::end).max().orElseThrow();
            var index = new DvdSearchIndex();
            var block = new byte[DvdSearchIndex.BLOCK_LENGTH];
            var filled = new ArrayList<long[]>();
            for (var unit : units) {
                index.addUnit(unit.lbn(), unit.last(), unit.start(), unit.end(), unit.video());
                while (index.isResolvable(unit.last() + 1)) {
                    index.fill(block, 0);
                    filled.add(read(block));
                }
                index.discard(unit.start());
            }
            index.finish();
            while (index.isResolvable(Long.MAX_VALUE)) {
                index.fill(block, 0);
                filled.add(read(block));
            }
            assertEquals(units.size(), filled.size());
            for (var i = 0; i < units.size(); i++) {
                assertArrayEquals(expectedSearch(units, units.size(), true, cellEnd, i), filled.get(i), "unit " + i);
            }
        }

        @Test
        @DisplayName("addresses the newest known unit when filled before the units its spans reach")
        void forced() {
            var units = randomCell(new Random(7), 400);
            var index = new DvdSearchIndex();
            var block = new byte[DvdSearchIndex.BLOCK_LENGTH];
            for (var known = 1; known <= units.size(); known++) {
                var unit = units.get(known - 1);
                index.addUnit(unit.lbn(), unit.last(), unit.start(), unit.end(), unit.video());
                // a held window of three units: the unit three back is filled with what is known
                if (known >= 4) {
                    var i = index.next();
                    index.fill(block, 0);
                    assertArrayEquals(expectedSearch(units, known, false, Long.MAX_VALUE, i), read(block), "unit " + i);
                    index.discard(unit.start());
                }
            }
        }

        @Test
        @DisplayName("waits for the next unit with video, however far, when the cell has a video stream")
        void videoGap() {
            var units = new ArrayList<Unit>();
            var lbn = 0L;
            for (var index = 0; index < 400; index++) {
                // video, then 150 s of audio-only units, then video again
                var video = index < 3 || index >= 303;
                units.add(new Unit(lbn, lbn + 9, index * 45_000L, (index + 1) * 45_000L, video));
                lbn += 10;
            }
            for (var expect : new boolean[]{true, false}) {
                var index = new DvdSearchIndex();
                if (expect) {
                    index.expectVideo();
                }
                var block = new byte[DvdSearchIndex.BLOCK_LENGTH];
                var filled = new ArrayList<long[]>();
                var beforeVideo = -1;
                for (var unit : units) {
                    if (unit == units.get(303)) {
                        beforeVideo = filled.size();
                    }
                    index.addUnit(unit.lbn(), unit.last(), unit.start(), unit.end(), unit.video());
                    while (index.isResolvable(unit.last() + 1)) {
                        index.fill(block, 0);
                        filled.add(read(block));
                    }
                    index.discard(unit.start());
                }
                if (expect) {
                    assertEquals(2, beforeVideo, "the last unit with video before the gap waits for video to return");
                } else {
                    assertTrue(filled.size() > 3, "without a video stream units resolve past the longest span");
                    assertEquals(NO_VIDEO, filled.get(2)[0], "only the known units are searched for video");
                }
                index.finish();
                while (index.isResolvable(Long.MAX_VALUE)) {
                    index.fill(block, 0);
                    filled.add(read(block));
                }
                assertEquals(units.size(), filled.size());
                if (expect) {
                    var cellEnd = units.getLast().end();
                    for (var i = 0; i < units.size(); i++) {
                        assertArrayEquals(expectedSearch(units, units.size(), true, cellEnd, i), filled.get(i),
                                "unit " + i);
                    }
                    assertEquals(VALID | (units.get(303).lbn() - units.get(2).lbn()), filled.get(2)[0],
                            "the unit before the gap addresses the unit after it");
                }
            }
        }

        @Test
        @DisplayName("waits for the audio frame of a unit within reach, then addresses it in either direction")
        void audio() {
            var index = new DvdSearchIndex();
            index.declareAudio(0);
            index.declareAudio(3);
            index.declareAudio(5);
            var block = new byte[DvdSearchIndex.BLOCK_LENGTH];
            index.audioPack(3, 95, 1_000);
            index.addUnit(100, 120, 1_000, 50_000, true);
            index.addUnit(121, 150, 50_000, 15_000_000, true);
            index.addUnit(151, 160, 15_000_000, 15_100_000, true);
            assertTrue(!index.isResolvable(161), "stream 0 has no frame yet");
            assertTrue(!index.isResolvable(100 + 0x3FFE), "a frame may still come within reach");
            assertTrue(index.isResolvable(101 + 0x3FFE), "beyond reach");
            index.audioPack(0, 100 + 0x3FFE, 2_000);
            index.fill(block, 0);
            assertEquals(0x3FFE, (block[168] & 0xFF) << 8 | (block[169] & 0xFF), "stream 0 at the farthest reach");
            assertEquals(0x8005, (block[174] & 0xFF) << 8 | (block[175] & 0xFF), "stream 3, five blocks before, plays");
            assertEquals(0, (block[170] & 0xFF) << 8 | (block[171] & 0xFF), "stream 1 is absent");
            index.audioPack(0, 121 + 0x3FFF, 60_000);
            index.audioPack(5, 121 + 0x3FFF, 60_000);
            index.finish();
            index.fill(block, 0);
            assertEquals(0x3FFE - 21, (block[168] & 0xFF) << 8 | (block[169] & 0xFF),
                    "stream 0: the last frame before the unit, in a gap, written after its navigation pack");
            assertEquals(0x8000 | 26, (block[174] & 0xFF) << 8 | (block[175] & 0xFF),
                    "stream 3: the last frame before the unit, from an earlier unit");
            assertEquals(0x3FFF, (block[178] & 0xFF) << 8 | (block[179] & 0xFF), "stream 5: the next frame beyond reach");
        }

        @Test
        @DisplayName("addresses the subpicture displayed at a unit's start, or else the first starting during it")
        void subpictures() {
            var index = new DvdSearchIndex();
            for (var number : new int[]{0, 1, 2, 3, 31}) {
                index.declareSubpicture(number);
            }
            index.subpictureUnit(0, 40, 0, 900_000);
            index.subpictureUnit(1, 50, 0, 10_000);
            index.subpictureUnit(1, 110, 30_000, Long.MIN_VALUE);
            index.subpictureUnit(2, 60, 5_000, Long.MIN_VALUE);
            index.subpictureUnit(3, 150, 60_000, 70_000);
            index.addUnit(100, 120, 20_000, 50_000, true);
            index.addUnit(121, 200, 50_000, 90_000, true);
            index.finish();
            var block = new byte[DvdSearchIndex.BLOCK_LENGTH];
            index.fill(block, 0);
            assertEquals(VALID | 60, u32(block, 184), "a unit displayed since before, 60 blocks back");
            assertEquals(0x7FFFFFFFL, u32(block, 188), "the first unit starting during it, in the unit");
            assertEquals(VALID | 40, u32(block, 192), "a unit without an end still displayed");
            assertEquals(0x3FFFFFFFL, u32(block, 196), "a unit starting during the next unit is not this one's");
            assertEquals(0x3FFFFFFFL, u32(block, 184 + 4 * 31), "no subpicture in stream 31");
            assertEquals(0, u32(block, 184 + 4 * 4), "stream 4 is absent");
            index.fill(block, 0);
            assertEquals(VALID | (121 - 110), u32(block, 188), "the unit without an end displayed at its start");
            assertEquals(0x7FFFFFFFL, u32(block, 196), "a unit starting in it");
        }
    }

    /** The video object units, PES payloads and search blocks of a video object, parsed from its packs. */
    static final class Vob {
        /** A PES packet: block, stream key (stream id, or 0x100 plus substream id), payload bytes and PTS. */
        record Pes(long lbn, int key, int length, long pts) {
        }

        final byte[] data;
        final List<Unit> units = new ArrayList<>();
        final List<Long> dsi = new ArrayList<>();
        final List<Pes> pes = new ArrayList<>();
        final long cellEnd;

        Vob(byte[] data) {
            this(data, true);
        }

        /** Parses a video object; {@code ownStarts} checks that each unit starts at its earliest picture. */
        Vob(byte[] data, boolean ownStarts) {
            this.data = data;
            assertEquals(0, data.length % 2048);
            var blocks = data.length / 2048;
            var navigation = new ArrayList<Long>();
            for (var block = 0; block < blocks; block++) {
                var at = block * 2048;
                var position = at + 14 + (data[at + 13] & 7);
                while (position + 6 <= at + 2048 && data[position] == 0 && data[position + 1] == 0
                        && data[position + 2] == 1) {
                    var id = data[position + 3] & 0xFF;
                    var length = (data[position + 4] & 0xFF) << 8 | (data[position + 5] & 0xFF);
                    var end = position + 6 + length;
                    if (id == 0xBF && data[position + 6] == 0) {
                        navigation.add((long) block);
                    } else if (id == 0xBF) {
                        dsi.add((long) position + 7);
                    } else if (id == 0xBD || id >= 0xC0 && id <= 0xEF) {
                        var payload = position + 9 + (data[position + 8] & 0xFF);
                        var pts = (data[position + 7] & 0x80) != 0 ? pts(data, position + 9) : -1;
                        var key = id;
                        if (id == 0xBD) {
                            var sub = data[payload] & 0xFF;
                            key = 0x100 | sub;
                            payload += sub >= 0xA0 && sub <= 0xA7 ? 7 : sub >= 0x80 && sub <= 0x8F ? 4 : 1;
                        }
                        pes.add(new Pes(block, key, end - payload, pts));
                    }
                    position = end;
                }
            }
            var cellEnd = Long.MIN_VALUE;
            for (var index = 0; index < navigation.size(); index++) {
                var lbn = navigation.get(index);
                var last = index + 1 < navigation.size() ? navigation.get(index + 1) - 1 : blocks - 1;
                var pci = (int) (lbn * 2048) + 14 + 24 + 7;
                var start = u32(data, pci + 12);
                var end = u32(data, pci + 16);
                var video = false;
                var earliest = Long.MAX_VALUE;
                for (var packet : pes) {
                    if (packet.key() == 0xE0 && packet.lbn() > lbn && packet.lbn() <= last) {
                        video = true;
                        if (packet.pts() >= 0) {
                            earliest = Math.min(earliest, packet.pts());
                        }
                    }
                }
                if (video && ownStarts) {
                    assertEquals(earliest, start, "vobu_s_ptm of the unit at block " + lbn);
                }
                units.add(new Unit(lbn, last, start, end, video));
                cellEnd = Math.max(cellEnd, end);
            }
            this.cellEnd = cellEnd;
            assertEquals(units.size(), dsi.size());
        }

        static long pts(byte[] data, int at) {
            return (data[at] & 0x0EL) << 29 | (data[at + 1] & 0xFFL) << 22 | (data[at + 2] & 0xFEL) << 14
                    | (data[at + 3] & 0xFFL) << 7 | (data[at + 4] & 0xFFL) >> 1;
        }

        long[] search(int unit) {
            var out = new long[42];
            var at = (int) (long) dsi.get(unit) + 234;
            for (var entry = 0; entry < 42; entry++) {
                out[entry] = u32(data, at + 4 * entry);
            }
            return out;
        }

        int audio(int unit, int number) {
            var at = (int) (long) dsi.get(unit) + 402 + 2 * number;
            return (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
        }

        long subpicture(int unit, int number) {
            return u32(data, (int) (long) dsi.get(unit) + 402 + 16 + 4 * number);
        }

        /**
         * Places the frames or units of one stream: each source packet's first byte found in the PES payloads of the
         * stream, its presentation time moved by the offset that the first timestamped PES packet shows.
         */
        List<Mark> marks(int key, Track track, List<Packet> packets, boolean subpicture) {
            var payloads = pes.stream().filter(packet -> packet.key() == key).toList();
            assertTrue(!payloads.isEmpty(), "stream 0x" + Integer.toHexString(key) + " is carried");
            var starts = new long[payloads.size() + 1];
            for (var index = 0; index < payloads.size(); index++) {
                starts[index + 1] = starts[index] + payloads.get(index).length();
            }
            var offsets = new long[packets.size()];
            for (var index = 1; index < packets.size(); index++) {
                offsets[index] = offsets[index - 1] + packets.get(index - 1).size();
            }
            assertEquals(offsets[packets.size() - 1] + packets.getLast().size(), starts[payloads.size()],
                    "the stream carries every byte of its packets");
            var marks = new ArrayList<Mark>();
            var cursor = 0;
            var shift = Long.MIN_VALUE;
            for (var index = 0; index < packets.size(); index++) {
                while (starts[cursor + 1] <= offsets[index]) {
                    cursor++;
                }
                var payload = payloads.get(cursor);
                var pts = track.timeBase().rescale(packets.get(index).pts(), TICKS);
                var firstInPes = starts[cursor] <= offsets[index] && (index == 0 || offsets[index - 1] < starts[cursor]);
                if (firstInPes && payload.pts() >= 0) {
                    if (shift == Long.MIN_VALUE) {
                        shift = payload.pts() - pts;
                    }
                    assertEquals(payload.pts(), pts + shift, "the PTS of the PES packet at block " + payload.lbn());
                }
                if (subpicture) {
                    assertEquals(starts[cursor], offsets[index], "a subpicture unit starts a PES packet");
                }
                var duration = packets.get(index).duration();
                marks.add(new Mark(payload.lbn(), pts, duration > 0 ? pts + track.timeBase().rescale(duration, TICKS)
                        : Long.MIN_VALUE));
            }
            var applied = shift;
            return marks.stream().map(mark -> new Mark(mark.lbn(), mark.pts() + applied,
                    mark.end() == Long.MIN_VALUE ? Long.MIN_VALUE : mark.end() + applied)).toList();
        }
    }

    /** A source: tracks and packets in decoding order. */
    record Source(List<Track> tracks, List<Packet> packets) {
        List<Packet> of(Track track) {
            return packets.stream().filter(packet -> packet.trackId() == track.id()).toList();
        }
    }

    /**
     * Builds a cell of fake MPEG-2 pictures (an I, B, B, P pattern with key frames every 12 pictures), AC-3 frames
     * from 0 to 40 s and 70 to 100 s, MP2 frames throughout and subpicture units with and without durations.
     */
    static Source synthetic(double seconds) {
        var video = VideoTrack.builder().id(1).codec(Codec.MPEG2_VIDEO).timeBase(TICKS).width(720).height(576)
                .build();
        var ac3 = AudioTrack.builder().id(2).codec(Codec.AC3).timeBase(Rational.timeBaseOf(48_000))
                .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
        var mp2 = AudioTrack.builder().id(3).codec(Codec.MP2).timeBase(Rational.timeBaseOf(48_000))
                .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
        var subtitles = DataTrack.builder().id(4).codec(Codec.DVD_SUBTITLE).timeBase(TICKS).build();
        var packets = new ArrayList<Packet>();
        var frames = (int) (seconds * 25);
        var order = new ArrayList<Integer>();
        var previousAnchor = -1;
        for (var display = 0; display < frames; display++) {
            if (display % 3 == 0) {
                order.add(display);
                for (var b = previousAnchor + 1; b < display; b++) {
                    order.add(b);
                }
                previousAnchor = display;
            }
        }
        for (var b = previousAnchor + 1; b < frames; b++) {
            order.add(b);
        }
        for (var decode = 0; decode < order.size(); decode++) {
            var display = order.get(decode);
            var type = display % 12 == 0 ? 1 : display % 3 == 0 ? 2 : 3;
            var data = new byte[900 + display % 7 * 100];
            Arrays.fill(data, (byte) 0x55);
            data[2] = 1;
            data[3] = 0;
            data[4] = (byte) ((display % 1024) >> 2);
            data[5] = (byte) ((display & 3) << 6 | type << 3);
            data[0] = 0;
            data[1] = 0;
            packets.add(new Packet(1, data, 3600 + display * 3600L, decode * 3600L, 3600, type == 1));
        }
        for (var frame = 0L; frame * 1536 < seconds * 48_000; frame++) {
            var time = frame * 1536 / 48_000.0;
            if (time < 40 || time >= 70 && time < 100) {
                var data = new byte[768];
                Arrays.fill(data, (byte) (frame + 1));
                packets.add(new Packet(2, data, frame * 1536, frame * 1536, 1536, true));
            }
        }
        for (var frame = 0L; frame * 1152 < seconds * 48_000; frame++) {
            var data = new byte[576];
            Arrays.fill(data, (byte) (frame * 3 + 7));
            packets.add(new Packet(3, data, frame * 1152, frame * 1152, 1152, true));
        }
        long[][] units = {{2, 3}, {10, -1}, {20, 1}, {21, 2}, {33, -1}, {100, 5}, {140, -1}};
        for (var unit : units) {
            if (unit[0] < seconds) {
                var data = new byte[300];
                Arrays.fill(data, (byte) 0x33);
                data[0] = 0x01;
                data[1] = 0x2C;
                packets.add(new Packet(4, data, unit[0] * 90_000, unit[0] * 90_000,
                        unit[1] < 0 ? Packet.UNKNOWN_DURATION : unit[1] * 90_000, true));
            }
        }
        var tracks = List.<Track>of(video, ac3, mp2, subtitles);
        packets.sort(Comparator.comparingLong(packet -> tracks.stream()
                .filter(track -> track.id() == packet.trackId()).findFirst().orElseThrow().timeBase()
                .toMicros(packet.dts())));
        return new Source(tracks, packets);
    }

    static byte[] mux(Source source, PsMuxerOptions options, boolean seekable) {
        return seekable ? MuxSupport.toMemory(source.tracks(), source.packets(), sink -> new PsMuxer(sink, options))
                : MuxSupport.toStream(source.tracks(), source.packets(), sink -> new PsMuxer(sink, options));
    }

    /** Asserts that every unit of a video object carries the information derived from its packs and source. */
    static void assertExact(Source source, byte[] data, String what) {
        Validators.assertValidPs(what, data, true);
        var vob = new Vob(data);
        assertTrue(vob.units.size() > 1, what + " has several units");
        var audioNumber = 0;
        var pictureNumber = 0;
        var audio = new ArrayList<List<Mark>>();
        var audioNumbers = new ArrayList<Integer>();
        var pictures = new ArrayList<List<Mark>>();
        var pictureNumbers = new ArrayList<Integer>();
        for (var track : source.tracks()) {
            if (track.kind() == TrackKind.AUDIO) {
                var key = switch (track.codec()) {
                    case MP2 -> 0xC0 + audioNumber;
                    case AC3 -> 0x180 + audioNumber;
                    case DTS -> 0x188 + audioNumber;
                    default -> 0x1A0 + audioNumber;
                };
                audio.add(vob.marks(key, track, source.of(track), false));
                audioNumbers.add(audioNumber++);
            } else if (track.kind() == TrackKind.DATA) {
                pictures.add(vob.marks(0x120 + pictureNumber, track, source.of(track), true));
                pictureNumbers.add(pictureNumber++);
            }
        }
        for (var i = 0; i < vob.units.size(); i++) {
            var unit = vob.units.get(i);
            var where = what + " unit " + i + " at block " + unit.lbn();
            assertArrayEquals(expectedSearch(vob.units, vob.units.size(), true, vob.cellEnd, i), vob.search(i),
                    where + " VOBU_SRI");
            for (var number = 0; number < 8; number++) {
                var stream = audioNumbers.indexOf(number);
                assertEquals(stream < 0 ? 0 : expectedAudio(audio.get(stream), unit), vob.audio(i, number),
                        where + " A_SYNCA " + number);
            }
            var end = i + 1 < vob.units.size() ? vob.units.get(i + 1).start() : vob.cellEnd;
            for (var number = 0; number < 32; number++) {
                var stream = pictureNumbers.indexOf(number);
                assertEquals(stream < 0 ? 0 : expectedSubpicture(pictures.get(stream), unit, end),
                        vob.subpicture(i, number), where + " SP_SYNCA " + number);
            }
        }
    }

    @Nested
    @DisplayName("muxed video objects")
    class Muxed {
        @Test
        @DisplayName("address the units, audio frames and subpictures of the DVD sample")
        void sample() {
            var source = PsMuxerTest.interleaved(PsMuxerTest.sample("dvd.vob"));
            var data = mux(new Source(source.tracks(), source.packets()), PsMuxerOptions.forDvd(), true);
            assertExact(new Source(source.tracks(), source.packets()), data, "dvd.vob");
        }

        @Test
        @DisplayName("address every span of a long cell, across audio gaps and subpicture changes")
        void longCell() {
            var source = synthetic(150);
            var data = mux(source, PsMuxerOptions.forDvd(), true);
            assertExact(source, data, "synthetic");
            var vob = new Vob(data);
            var early = vob.units.stream().filter(unit -> unit.start() > 20 * 90_000L).findFirst().orElseThrow();
            var spans = vob.search(vob.units.indexOf(early));
            assertTrue((spans[1] & VALID) != 0, "a unit more than 120 s before the end has a valid 120 s span");
            var lateSpans = vob.search(vob.units.size() - 20);
            assertEquals(NONE, lateSpans[1], "a unit less than 120 s before the end has none");
            var gap = vob.units.stream().filter(unit -> unit.start() > 55 * 90_000L).findFirst().orElseThrow();
            var gapUnit = vob.units.indexOf(gap);
            assertTrue(vob.audio(gapUnit, 0) > 0x8000 + 100 && vob.audio(gapUnit, 0) < 0xBFFF,
                    "a unit in the AC-3 gap addresses the last frame before it");
            var late = vob.units.stream().filter(unit -> unit.start() > 110 * 90_000L).findFirst().orElseThrow();
            assertTrue(vob.audio(vob.units.indexOf(late), 0) > 0x8000, "after 100 s the last AC-3 frame is behind");
            assertTrue(vob.audio(vob.units.indexOf(late), 1) != 0x3FFF, "MP2 continues");
        }

        @Test
        @DisplayName("number the audio streams of DVD-Video across codecs")
        void numbering() {
            var source = synthetic(4);
            var vob = new Vob(mux(source, PsMuxerOptions.forDvd(), true));
            assertTrue(vob.pes.stream().anyMatch(packet -> packet.key() == 0x180), "AC-3 is audio stream 0");
            assertTrue(vob.pes.stream().anyMatch(packet -> packet.key() == 0xC1), "MP2 is audio stream 1");
            assertTrue(vob.pes.stream().noneMatch(packet -> packet.key() == 0xC0), "no MP2 stream 0");
        }

        @Test
        @DisplayName("write the same bytes to a stream as to memory when the lookahead covers every span")
        void streamedWithinLookahead() {
            var source = synthetic(150);
            var options = PsMuxerOptions.forDvd().toBuilder().navigationLookaheadMicros(120_000_000).build();
            assertArrayEquals(mux(source, options, true), mux(source, options, false));
        }

        @ParameterizedTest(name = "lookahead {0} us")
        @ValueSource(longs = {0, 5_000_000, 15_000_000})
        @DisplayName("hold a stream for the lookahead, exact within it and addressing held units beyond it")
        void streamedBeyondLookahead(long lookahead) {
            var source = synthetic(80);
            var options = PsMuxerOptions.forDvd().toBuilder().navigationLookaheadMicros(lookahead).build();
            var exact = new Vob(mux(source, options, true));
            var streamed = mux(source, options, false);
            Validators.assertValidPs("streamed", streamed, true);
            var held = new Vob(streamed);
            assertEquals(exact.data.length, streamed.length);
            assertEquals(exact.units, held.units);
            var differences = 0;
            for (var i = 0; i < exact.units.size(); i++) {
                var want = exact.search(i);
                var got = held.search(i);
                var where = "unit " + i;
                assertEquals(want[0], got[0], where + " next unit with video");
                for (var entry = 20; entry < 42; entry++) {
                    assertEquals(want[entry], got[entry], where + " entry " + entry);
                }
                for (var span = 0; span < SPANS.length; span++) {
                    var entry = 19 - span;
                    if (SPANS[span] * 45_000L <= lookahead * 9 / 100) {
                        assertEquals(want[entry], got[entry], where + " span " + SPANS[span] + " within the lookahead");
                    } else if (want[entry] != got[entry]) {
                        differences++;
                        assertTrue((got[entry] & VALID) != 0, where + " span " + SPANS[span] + " addresses a held unit");
                        assertTrue(want[entry] == NONE || (got[entry] & NONE) <= (want[entry] & NONE),
                                where + " span " + SPANS[span] + " addresses a nearer unit");
                    }
                }
                for (var number = 0; number < 8; number++) {
                    var audio = held.audio(i, number);
                    assertTrue(audio == exact.audio(i, number) || audio == 0x3FFF, where + " A_SYNCA " + number);
                }
                for (var number = 0; number < 32; number++) {
                    var picture = held.subpicture(i, number);
                    assertTrue(picture == exact.subpicture(i, number) || picture == 0x3FFFFFFFL,
                            where + " SP_SYNCA " + number);
                }
                // outside the search and synchronous information the units are identical
                var lbn = (int) exact.units.get(i).lbn();
                var block = (int) (long) exact.dsi.get(i) + 234;
                assertArrayEquals(Arrays.copyOfRange(exact.data, lbn * 2048, block),
                        Arrays.copyOfRange(streamed, lbn * 2048, block), where + " before its search information");
                var after = block + DvdSearchIndex.BLOCK_LENGTH;
                var next = (int) (exact.units.get(i).last() + 1) * 2048;
                assertArrayEquals(Arrays.copyOfRange(exact.data, after, next), Arrays.copyOfRange(streamed, after, next),
                        where + " after its search information");
            }
            assertTrue(differences > 0, "spans beyond the lookahead differ from the exact ones");
        }

        @Test
        @DisplayName("patch the units of a seekable sink that starts past zero, and of a file")
        void seekableSinks() throws Exception {
            var source = synthetic(20);
            var options = PsMuxerOptions.forDvd();
            var expected = mux(source, options, true);
            var sink = MediaSink.memory();
            var prefix = new byte[1000];
            Arrays.fill(prefix, (byte) 0x5A);
            sink.write(prefix);
            try (var muxer = new PsMuxer(sink, options)) {
                source.tracks().forEach(muxer::addTrack);
                source.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            var written = sink.toByteArray();
            assertArrayEquals(prefix, Arrays.copyOf(written, 1000));
            assertArrayEquals(expected, Arrays.copyOfRange(written, 1000, written.length));
            var file = com.github.auties00.smalt.testkit.Scratch.directory("dvd-navigation").resolve("patched.vob");
            try (var fileSink = MediaSink.of(file); var muxer = new PsMuxer(fileSink, options)) {
                source.tracks().forEach(muxer::addTrack);
                source.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            assertArrayEquals(expected, Files.readAllBytes(file));
            var brief = synthetic(12);
            var stream = new ByteArrayOutputStream();
            try (var streamSink = MediaSink.of(stream); var muxer = new PsMuxer(streamSink, options)) {
                brief.tracks().forEach(muxer::addTrack);
                brief.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            assertArrayEquals(mux(brief, options, true), stream.toByteArray(), "a 12 s cell fits the default lookahead");
        }
    }
}
