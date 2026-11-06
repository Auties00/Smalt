package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

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
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stszCount;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.tkhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u64;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.DISPLAY;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.MATRIX;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.elst;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.fragmentedFile;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.videoFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the sample index of the packets {@link Mp4Demuxer} returns: in the files of these tests the first byte of
 * sample {@code i} of a video track is {@code i}, and the first frame of PCM sample {@code i} holds {@code i}, so
 * every packet must carry the index its payload names, through edit list presentations, movie fragments and seeks.
 */
@DisplayName("MP4 packet sample indexes")
class Mp4SampleIndexTest {
    private static List<Packet> read(Mp4Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    private static void assertIndexesNamedByPayload(List<Packet> packets) {
        assertFalse(packets.isEmpty());
        for (var packet : packets) {
            assertEquals(packet.data()[0] & 0xFF, packet.sampleIndex(), () -> "packet at pts " + packet.pts());
        }
    }

    @Test
    @DisplayName("numbers the samples of a track from zero in decoding order")
    void sequential() {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(videoFile(null)))) {
            var packets = read(demuxer);
            assertEquals(DISPLAY.length, packets.size());
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index, packets.get(index).sampleIndex());
            }
            assertIndexesNamedByPayload(packets);
        }
    }

    @Test
    @DisplayName("gives each packet of an edit list's presentation the index of the media sample it presents")
    void presentation() {
        var data = videoFile(elst(400, 500, 1, 200, -1, 1, 300, 350, 0, 250, 1050, 1));
        try (var demuxer = new Mp4Demuxer(MediaSource.of(data))) {
            var packets = read(demuxer);
            assertIndexesNamedByPayload(packets);
            // the first edit presents media samples 4 to 7, then the pre-roll of the next edits starts over at 0
            assertEquals(List.of(4L, 5L, 6L, 7L, 0L, 1L, 2L, 3L, 8L, 9L, 10L, 11L),
                    packets.stream().map(Packet::sampleIndex).toList());
            assertTrue(packets.stream().anyMatch(packet -> packet.flags().contains(PacketFlag.DISCARD)));
        }
    }

    @Test
    @DisplayName("keeps numbering across movie fragments")
    void fragments() {
        var data = fragmentedFile(new byte[0], stsd(avc1()), 1, 0);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(data))) {
            var packets = read(demuxer);
            assertEquals(DISPLAY.length, packets.size());
            assertIndexesNamedByPayload(packets);
            assertEquals(11, packets.getLast().sampleIndex());
        }
    }

    @ParameterizedTest(name = "seek to {0} ms")
    @ValueSource(ints = {0, 150, 399, 400, 650, 900, 1150})
    @DisplayName("restarts at the index of the sync sample a seek chooses")
    void seek(int millis) {
        for (var data : List.of(videoFile(null), fragmentedFile(new byte[0], stsd(avc1()), 1, 0))) {
            try (var demuxer = new Mp4Demuxer(MediaSource.of(data))) {
                var all = read(demuxer);
                // the track's timescale is 1000, so a presentation time is in milliseconds
                var expected = 0L;
                for (var packet : all) {
                    if (packet.keyframe() && packet.pts() <= millis) {
                        expected = packet.sampleIndex();
                    }
                }
                demuxer.seek(millis * 1000L);
                var packets = read(demuxer);
                assertIndexesNamedByPayload(packets);
                var first = packets.getFirst();
                assertTrue(first.keyframe());
                assertEquals(expected, first.sampleIndex());
                for (var index = 1; index < packets.size(); index++) {
                    assertEquals(first.sampleIndex() + index, packets.get(index).sampleIndex());
                }
            }
        }
    }

    /** 3000 frames of 16-bit mono PCM at 8 kHz in chunks of 700 frames; frame i holds the value i. */
    private static byte[] pcmFile(byte[] edts) {
        return pcmFile(edts, false);
    }

    /**
     * The PCM file of {@link #pcmFile(byte[])}, followed, when {@code fragmented}, by a movie fragment of ten more
     * one-frame samples holding the values 3000 to 3009.
     */
    private static byte[] pcmFile(byte[] edts, boolean fragmented) {
        var media = new byte[6000];
        for (var index = 0; index < 3000; index++) {
            media[index * 2] = (byte) index;
            media[index * 2 + 1] = (byte) (index >> 8);
        }
        var chunks = new long[5];
        for (var chunk = 0; chunk < chunks.length; chunk++) {
            chunks[chunk] = DATA_START + chunk * 1400L;
        }
        var trak = box("trak", tkhd(1, 0, 0, MATRIX), edts == null ? new byte[0] : edts,
                box("mdia", mdhd(8000, 3000), hdlr("soun"), box("minf", full("smhd", 0, 0, u32(0)),
                        box("stbl", stsd(audioEntry("sowt", 1, 8000)), table("stts", 0, 2, 3000, 1),
                                table("stsc", 0, 3, 1, 700, 1, 5, 200, 1), stszCount(2, 3000),
                                table("stco", 0, 1, chunks)))));
        if (!fragmented) {
            return file(media, mvhd(8000, 3000), trak);
        }
        var mvex = box("mvex", full("trex", 0, 0, u32(1), u32(1), u32(0), u32(0), u32(0)));
        return concat(file(media, mvhd(8000, 3000), trak, mvex), pcmFragment(3000, 10));
    }

    /** A movie fragment of {@code count} one-frame PCM samples from frame {@code from}, frame i holding i. */
    private static byte[] pcmFragment(int from, int count) {
        var records = new ByteArrayOutputStream();
        for (var index = 0; index < count; index++) {
            records.writeBytes(u32(1));
            records.writeBytes(u32(2));
        }
        var tfhd = full("tfhd", 0, 0x020000, u32(1));
        var placeholder = full("trun", 0, 0x000301, u32(count), u32(0), records.toByteArray());
        var moofSize = 8 + 16 + 8 + tfhd.length + 20 + placeholder.length;
        var trun = full("trun", 0, 0x000301, u32(count), u32(moofSize + 8), records.toByteArray());
        var moof = box("moof", full("mfhd", 0, 0, u32(1)), box("traf", tfhd, full("tfdt", 1, 0, u64(from)), trun));
        var payload = new byte[count * 2];
        for (var index = 0; index < count; index++) {
            payload[index * 2] = (byte) (from + index);
            payload[index * 2 + 1] = (byte) ((from + index) >> 8);
        }
        return concat(moof, box("mdat", payload));
    }

    private static int firstFrame(Packet packet) {
        return (packet.data()[0] & 0xFF) | (packet.data()[1] & 0xFF) << 8;
    }

    @Test
    @DisplayName("gives a packet of grouped PCM frames the index of its first frame, with and without edits")
    void pcm() {
        for (var edts : new byte[][]{null, elst(1000, 500, 1, 500, -1, 1, 1500, 1800, 1)}) {
            try (var demuxer = new Mp4Demuxer(MediaSource.of(pcmFile(edts)))) {
                var packets = read(demuxer);
                assertTrue(packets.size() > 4);
                for (var packet : packets) {
                    assertEquals(firstFrame(packet), packet.sampleIndex(), () -> "packet at pts " + packet.pts());
                }
            }
        }
    }

    @Test
    @DisplayName("numbers the samples of a fragment that follows grouped PCM frames after the last frame")
    void pcmFragments() throws Exception {
        var data = pcmFile(null, true);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(data));
             var stream = new ByteArrayInputStream(data); var source = MediaSource.of(stream, data.length);
             var forwardOnly = new Mp4Demuxer(source)) {
            for (var packets : List.of(read(demuxer), read(forwardOnly))) {
                assertEquals(3009, firstFrame(packets.getLast()));
                for (var packet : packets) {
                    assertEquals(firstFrame(packet), packet.sampleIndex(), () -> "packet at pts " + packet.pts());
                }
            }
        }
    }
}
