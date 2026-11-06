package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.DATA_START;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.audioEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.avc1;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.file;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.hdlr;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mdhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsz;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stszCount;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.tkhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u16;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u64;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.zeros;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-segment edit lists of ISO/IEC 14496-12 clause 8.6.6 on hand-built files: the presentation
 * index of video (with frame reordering, dwell and empty edits) and of PCM audio, whose discards a
 * reader applies; expectations are worked out by hand from the specification.
 */
@DisplayName("MP4 multi-segment edit lists")
class Mp4EditListTest {
    /** Display index of each sample in decoding order: a key frame, its P frame, then two B frames. */
    static final int[] DISPLAY = {0, 3, 1, 2, 4, 7, 5, 6, 8, 11, 9, 10};

    static final int IDENTITY = 0x40000000;

    static final int[] MATRIX = {0x10000, 0, 0, 0, 0x10000, 0, 0, 0, IDENTITY};

    /** Builds an {@code elst} version 1 from triples of duration, media time and integer rate. */
    static byte[] elst(long... triples) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(u32(triples.length / 3));
        for (var index = 0; index < triples.length; index += 3) {
            out.writeBytes(u64(triples[index]));
            out.writeBytes(u64(triples[index + 1]));
            out.writeBytes(u16((int) triples[index + 2]));
            out.writeBytes(u16(0));
        }
        return box("edts", full("elst", 1, 0, out.toByteArray()));
    }

    static byte[] videoTrak(byte[] edts, byte[] sampleDescriptions, byte[] stsc) {
        return videoTrak("vide", edts, sampleDescriptions, stsc);
    }

    static byte[] videoTrak(String handler, byte[] edts, byte[] sampleDescriptions, byte[] stsc) {
        var offsets = new long[DISPLAY.length];
        var ctts = new long[DISPLAY.length * 2];
        for (var index = 0; index < DISPLAY.length; index++) {
            offsets[index] = DATA_START + 4L * index;
            ctts[index * 2] = 1;
            ctts[index * 2 + 1] = DISPLAY[index] * 100L + 100 - index * 100L;
        }
        var sizes = new int[DISPLAY.length];
        java.util.Arrays.fill(sizes, 4);
        return box("trak", tkhd(1, 64, 48, MATRIX), edts == null ? new byte[0] : edts,
                box("mdia", mdhd(1000, 1200), hdlr(handler), box("minf", full("vmhd", 0, 1, zeros(8)),
                        box("stbl", sampleDescriptions, table("stts", 0, 2, DISPLAY.length, 100), table("ctts", 0, 2, ctts),
                                table("stss", 0, 1, 1, 5, 9), stsc, stsz(0, sizes), table("stco", 0, 1, offsets)))));
    }

    static byte[] videoFile(byte[] edts) {
        return videoFile("vide", edts);
    }

    static byte[] videoFile(String handler, byte[] edts) {
        return file(Mp4Builder.samples(DISPLAY.length, 4), mvhd(1000, 1200),
                videoTrak(handler, edts, stsd(avc1()), table("stsc", 0, 3, 1, 1, 1)));
    }

    static String describe(Packet packet) {
        var text = (packet.data()[0] & 0xFF) + ":" + packet.pts() + ":" + packet.dts() + ":" + packet.duration();
        if (packet.flags().contains(PacketFlag.KEY)) {
            text += ":K";
        }
        if (packet.flags().contains(PacketFlag.DISCARD)) {
            text += ":D";
        }
        return text;
    }

    @Nested
    @DisplayName("video")
    class Video {
        @Test
        @DisplayName("expands media, empty and dwell edits into a presentation index with decode-only pre-roll")
        void presentationIndex() {
            var data = videoFile(elst(400, 500, 1, 200, -1, 1, 300, 350, 0, 250, 1050, 1));
            var demuxed = Mp4EdgeCaseTest.demux(data);
            var described = demuxed.order().stream().map(Mp4EditListTest::describe).toList();
            assertEquals(List.of(
                    "4:0:-100:100:K", "5:300:0:100", "6:100:100:100", "7:200:200:100",
                    "0:400:300:100:K:D", "1:700:400:100:D", "2:500:500:100:D", "3:600:600:300",
                    "8:750:650:100:K:D", "9:1050:750:100", "10:900:850:50", "11:950:950:100"), described);
            assertEquals(1150, demuxed.tracks().getFirst().duration());
        }

        @Test
        @DisplayName("keeps the timestamp shift of a single media edit after empty edits")
        void simpleEditUnchanged() {
            var demuxed = Mp4EdgeCaseTest.demux(videoFile(elst(300, -1, 1, 1100, 100, 1)));
            var first = demuxed.order().getFirst();
            assertEquals(300, first.pts());
            assertEquals(DISPLAY.length, demuxed.order().size());
            assertTrue(demuxed.order().stream().noneMatch(packet -> packet.flags().contains(PacketFlag.DISCARD)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"pict", "auxv"})
        @DisplayName("follows only the first media edit of a HEIF image sequence track")
        void imageSequence(String handler) {
            var edits = elst(1200, 100, 1, 1200, 100, 1);
            var expanded = Mp4EdgeCaseTest.demux(videoFile(edits));
            assertEquals(2 * DISPLAY.length, expanded.order().size());
            var demuxed = Mp4EdgeCaseTest.demux(videoFile(handler, edits));
            var keys = demuxed.order().stream().map(packet -> packet.data()[0] & 0xFF).toList();
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), keys);
            assertEquals(0, demuxed.order().getFirst().pts());
            assertTrue(demuxed.order().stream().noneMatch(packet -> packet.flags().contains(PacketFlag.DISCARD)));
            assertEquals(2400, demuxed.tracks().getFirst().duration());
        }

        @Test
        @DisplayName("continues decoding without pre-roll when an edit starts right after the previous one")
        void contiguousEdits() {
            var demuxed = Mp4EdgeCaseTest.demux(videoFile(elst(400, 100, 1, 800, 500, 1)));
            var keys = demuxed.order().stream().map(packet -> packet.data()[0] & 0xFF).toList();
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), keys);
            assertTrue(demuxed.order().stream().noneMatch(packet -> packet.flags().contains(PacketFlag.DISCARD)));
            assertEquals(List.of(0L, 300L, 100L, 200L, 400L, 700L, 500L, 600L, 800L, 1100L, 900L, 1000L),
                    demuxed.order().stream().map(Packet::pts).toList());
        }

        @Test
        @DisplayName("gives decode-only packets presentation times no presented packet has")
        void distinctDiscardTimes() {
            // the second edit replays frames 0 to 3 at 0 to 400 again, after the same frames
            var demuxed = Mp4EdgeCaseTest.demux(videoFile(elst(400, 100, 1, 200, 300, 1)));
            var presented = new ArrayList<Long>();
            var discarded = new ArrayList<Long>();
            for (var packet : demuxed.order()) {
                (packet.flags().contains(PacketFlag.DISCARD) ? discarded : presented).add(packet.pts());
            }
            assertEquals(List.of(0L, 300L, 100L, 200L, 500L, 400L), presented);
            assertTrue(discarded.stream().noneMatch(presented::contains), discarded.toString());
            var dts = demuxed.order().stream().map(Packet::dts).toList();
            for (var index = 1; index < dts.size(); index++) {
                assertTrue(dts.get(index) >= dts.get(index - 1), "decoding timestamps decrease: " + dts);
            }
        }

        @Test
        @DisplayName("seeks within a later edit to its decode-only key frame")
        void seekIntoEdit() throws Exception {
            var data = videoFile(elst(400, 500, 1, 200, -1, 1, 300, 350, 0, 250, 1050, 1));
            try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
                demuxer.seek(950_000);
                var first = demuxer.read();
                assertEquals("8:750:650:100:K:D", describe(first));
                demuxer.seek(650_000);
                assertEquals("0:400:300:100:K:D", describe(demuxer.read()));
            }
        }

        @Test
        @DisplayName("presents the same over negative composition offsets, and keeps it through a remux")
        void negativeOffsets() {
            // version 1 ctts presenting every sample 100 earlier, with every edit's media time 100 earlier
            var ctts = new long[DISPLAY.length * 2];
            var offsets = new long[DISPLAY.length];
            for (var index = 0; index < DISPLAY.length; index++) {
                ctts[index * 2] = 1;
                ctts[index * 2 + 1] = DISPLAY[index] * 100L - index * 100L;
                offsets[index] = DATA_START + 4L * index;
            }
            var sizes = new int[DISPLAY.length];
            java.util.Arrays.fill(sizes, 4);
            var trak = box("trak", tkhd(1, 64, 48, MATRIX), elst(400, 400, 1, 200, -1, 1, 300, 250, 0, 250, 950, 1),
                    box("mdia", mdhd(1000, 1200), hdlr("vide"), box("minf", full("vmhd", 0, 1, zeros(8)),
                            box("stbl", stsd(avc1()), table("stts", 0, 2, DISPLAY.length, 100), table("ctts", 1, 2, ctts),
                                    table("stss", 0, 1, 1, 5, 9), table("stsc", 0, 3, 1, 1, 1), stsz(0, sizes),
                                    table("stco", 0, 1, offsets)))));
            var input = file(Mp4Builder.samples(DISPLAY.length, 4), mvhd(1000, 1200), trak);
            var expected = Mp4EdgeCaseTest.demux(videoFile(elst(400, 500, 1, 200, -1, 1, 300, 350, 0, 250, 1050, 1)))
                    .order().stream().map(Mp4EditListTest::describe).toList();
            assertEquals(expected, Mp4EdgeCaseTest.demux(input).order().stream().map(Mp4EditListTest::describe).toList());
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            Mp4Remuxer.remux(MediaSource.of(input), sink);
            com.github.auties00.smalt.testkit.validate.Validators.assertValid("edit-list-remux.mp4", sink.toByteArray());
            assertEquals(expected, Mp4EdgeCaseTest.demux(sink.toByteArray()).order().stream()
                    .map(Mp4EditListTest::describe).toList());
        }

        @Test
        @DisplayName("bounds the presentation index by the frame limit")
        void limit() {
            var triples = new long[3 * 400];
            for (var index = 0; index < 400; index++) {
                triples[index * 3] = 1100;
                triples[index * 3 + 1] = 100;
                triples[index * 3 + 2] = 1;
            }
            var limits = MediaLimits.builder().maxFrames(1000).build();
            assertThrows(MediaException.LimitExceeded.class, () -> Mp4EdgeCaseTest.demux(videoFile(elst(triples)), limits));
        }

        @Test
        @DisplayName("survives random edit lists")
        void randomEdits() {
            var random = new Random(7);
            for (var round = 0; round < 300; round++) {
                var count = 1 + random.nextInt(6);
                var triples = new long[count * 3];
                for (var index = 0; index < count; index++) {
                    triples[index * 3] = random.nextInt(5) == 0 ? 0 : random.nextInt(2000);
                    triples[index * 3 + 1] = switch (random.nextInt(6)) {
                        case 0 -> -1;
                        case 1 -> Long.MAX_VALUE - random.nextInt(10);
                        case 2 -> random.nextInt(100_000);
                        default -> random.nextInt(1400);
                    };
                    triples[index * 3 + 2] = random.nextInt(4) == 0 ? 0 : random.nextInt(3) - 1;
                }
                var data = videoFile(elst(triples));
                try {
                    var demuxed = Mp4EdgeCaseTest.demux(data);
                    var dts = demuxed.order().stream().map(Packet::dts).toList();
                    for (var index = 1; index < dts.size(); index++) {
                        assertTrue(dts.get(index) >= dts.get(index - 1), "decoding timestamps decrease: " + dts);
                    }
                } catch (MediaException expected) {
                    // a hostile edit list may be rejected, never crash
                }
            }
        }
    }

    @Nested
    @DisplayName("PCM audio")
    class Audio {
        /** 3000 frames of 16-bit mono PCM at 8 kHz in one chunk; sample i holds the value i. */
        byte[] pcmFile(byte[] edts) {
            var media = new byte[6000];
            for (var index = 0; index < 3000; index++) {
                media[index * 2] = (byte) index;
                media[index * 2 + 1] = (byte) (index >> 8);
            }
            var trak = box("trak", tkhd(1, 0, 0, MATRIX), edts,
                    box("mdia", mdhd(8000, 3000), hdlr("soun"), box("minf", full("smhd", 0, 0, u32(0)),
                            box("stbl", stsd(audioEntry("sowt", 1, 8000)), table("stts", 0, 2, 3000, 1),
                                    table("stsc", 0, 3, 1, 3000, 1), stszCount(2, 3000), table("stco", 0, 1, DATA_START)))));
            return file(media, mvhd(8000, 3700), trak);
        }

        @Test
        @DisplayName("cuts packets at edit boundaries with discard starts and ends")
        void packets() {
            var demuxed = Mp4EdgeCaseTest.demux(pcmFile(elst(1000, 500, 1, 500, -1, 1, 1500, 1800, 1, 700, 2900, 1)));
            var described = new ArrayList<String>();
            for (var packet : demuxed.order()) {
                described.add(packet.pts() + ":" + packet.duration() + ":" + packet.sideData().discardStart() + ":"
                        + packet.sideData().discardEnd());
            }
            assertEquals(List.of("-500:1024:500:0", "524:476:0:548", "724:1024:776:0", "1748:952:0:0",
                    "2148:952:852:0"), described);
            var track = (AudioTrack) demuxed.tracks().getFirst();
            assertEquals(0, track.skipSamples());
            assertEquals(3700, track.duration());
        }

        @Test
        @DisplayName("decodes exactly the samples the edits present")
        void decodes() throws Exception {
            var data = pcmFile(elst(1000, 500, 1, 500, -1, 1, 1500, 1800, 1, 700, 2900, 1));
            var expected = new ArrayList<Integer>();
            for (var index = 500; index < 1500; index++) {
                expected.add(index);
            }
            for (var index = 1800; index < 3000; index++) {
                expected.add(index);
            }
            for (var index = 2900; index < 3000; index++) {
                expected.add(index);
            }
            var actual = new ArrayList<Integer>();
            try (var source = MediaSource.of(data); var reader = AudioReader.open(source)) {
                AudioBuffer block;
                while ((block = reader.read()) != null) {
                    for (var index = 0; index < block.frames(); index++) {
                        actual.add(Math.round(block.plane(0)[index] * 32768));
                    }
                }
            }
            assertEquals(expected, actual);
        }
    }

    /** A track fragment run of samples {@code from} to {@code to} of {@link #DISPLAY}, data right after its moof. */
    static byte[] fragment(int sequence, int from, int to, int description) {
        var count = to - from;
        var records = new ByteArrayOutputStream();
        for (var index = from; index < to; index++) {
            records.writeBytes(u32(100));
            records.writeBytes(u32(4));
            records.writeBytes(u32(index % 4 == 0 ? 0 : 0x00010000));
            records.writeBytes(u32(DISPLAY[index] * 100L + 100 - index * 100L));
        }
        var tfhd = description > 0 ? full("tfhd", 0, 0x020002, u32(1), u32(description)) : full("tfhd", 0, 0x020000, u32(1));
        var placeholder = full("trun", 0, 0x000F01, u32(count), u32(0), records.toByteArray());
        var moofSize = 8 + 16 + 8 + tfhd.length + 20 + placeholder.length;
        var trun = full("trun", 0, 0x000F01, u32(count), u32(moofSize + 8), records.toByteArray());
        var moof = box("moof", full("mfhd", 0, 0, u32(sequence)),
                box("traf", tfhd, full("tfdt", 1, 0, u64(from * 100L)), trun));
        var payload = new byte[count * 4];
        for (var index = 0; index < count; index++) {
            java.util.Arrays.fill(payload, index * 4, index * 4 + 4, (byte) (from + index));
        }
        return concat(moof, box("mdat", payload));
    }

    /** A fragmented file of {@link #DISPLAY} in two fragments, with an empty sample table in its moov. */
    static byte[] fragmentedFile(byte[] edts, byte[] sampleDescriptions, int trexDescription, int secondDescription) {
        var trak = box("trak", tkhd(1, 64, 48, MATRIX), edts, box("mdia", mdhd(1000, 0), hdlr("vide"),
                box("minf", full("vmhd", 0, 1, zeros(8)), box("stbl", sampleDescriptions, table("stts", 0, 2),
                        table("stsc", 0, 3), stsz(0), table("stco", 0, 1)))));
        var mvex = box("mvex", full("trex", 0, 0, u32(1), u32(trexDescription), u32(0), u32(0), u32(0)));
        return concat(Mp4Builder.ftyp(), box("moov", mvhd(1000, 0), trak, mvex), fragment(1, 0, 6, 0),
                fragment(2, 6, 12, secondDescription));
    }

    @Test
    @DisplayName("reads a forward-only fragmented file with a multi-segment edit list whole")
    void forwardOnlyFragments() throws Exception {
        var data = fragmentedFile(elst(400, 500, 1, 300, 150, 1), stsd(avc1()), 1, 0);
        var expected = Mp4EdgeCaseTest.demux(data).order().stream().map(Mp4EditListTest::describe).toList();
        assertEquals(List.of("4:0:-100:100:K", "5:300:0:100", "6:100:100:100", "7:200:200:100", "0:400:250:50:K",
                "1:650:350:50", "2:450:450:100", "3:550:550:100"), expected);
        try (var stream = new java.io.ByteArrayInputStream(data); var source = MediaSource.of(stream, data.length);
             var demuxer = new Mp4Demuxer(source)) {
            var demuxed = ContainerProbe.demuxAll(demuxer);
            assertEquals(expected, demuxed.order().stream().map(Mp4EditListTest::describe).toList());
        }
    }
}
