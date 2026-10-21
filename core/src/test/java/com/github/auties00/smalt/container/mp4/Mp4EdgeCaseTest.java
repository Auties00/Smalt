package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.DATA_START;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.avc1;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.audioEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.co64;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.file;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.fourCc;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.ftyp;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.largeBox;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.samples;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsz;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stszCount;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.trak;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u16;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u64;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u8;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.visualEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.zeros;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases of {@link Mp4Demuxer} on hand-built files: degenerate tables, every sample table
 * layout, and structural damage that must end in a {@link MediaException} rather than a hang or
 * an unchecked exception.
 */
@DisplayName("Mp4Demuxer edge cases")
class Mp4EdgeCaseTest {
    static ContainerProbe.Demuxed demux(byte[] data) {
        return demux(data, MediaLimits.DEFAULT);
    }

    static ContainerProbe.Demuxed demux(byte[] data, MediaLimits limits) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source,
                    DemuxerOptions.builder().limits(limits).build())) {
                return ContainerProbe.demuxAll(demuxer);
            }
        });
    }

    /** A video track of {@code count} samples of {@code size} bytes, one chunk per sample. */
    static byte[] videoFile(int count, int size, byte[]... extraTables) {
        var offsets = new long[count];
        for (var index = 0; index < count; index++) {
            offsets[index] = DATA_START + (long) index * size;
        }
        var sizes = new int[count];
        Arrays.fill(sizes, size);
        return file(samples(count, size), mvhd(1000, count * 40L),
                trak(1, "vide", 1000, count * 40L, avc1(), table("stts", 0, 2, count, 40),
                        table("stsc", 0, 3, 1, 1, 1), stsz(0, sizes), table("stco", 0, 1, offsets), concat(extraTables)));
    }

    @Nested
    @DisplayName("color metadata")
    class ColorMetadata {
        private byte[] fileWith(byte[]... children) {
            var entry = visualEntry("avc1", 64, 48, concat(box("avcC", new byte[]{1, 66, 0, 30, (byte) 0xFF, (byte) 0xE1, 0, 4,
                    0x67, 66, 0, 30, 1, 0, 2, 0x68, 0x40}), concat(children)));
            return file(samples(1, 4), mvhd(1000, 40), trak(1, "vide", 1000, 40, entry, table("stts", 0, 2, 1, 40),
                    table("stsc", 0, 3, 1, 1, 1), stsz(0, 4), table("stco", 0, 1, DATA_START)));
        }

        private byte[] mdcv() {
            // primaries in the order green, blue, red, in units of 0.00002; luminances in 0.0001 cd/m2
            return box("mdcv", u16(8500), u16(39850), u16(6550), u16(2300), u16(35400), u16(14600), u16(15635), u16(16450),
                    u32(10_000_000), u32(50));
        }

        @Test
        @DisplayName("attaches the mdcv and clli boxes to the colr description")
        void hdr() {
            var data = fileWith(box("colr", fourCc("nclx"), u16(9), u16(16), u16(9), u8(0x80)), mdcv(),
                    box("clli", u16(1000), u16(400)));
            var color = ((VideoTrack) demux(data).tracks().getFirst()).color().orElseThrow();
            assertEquals(ColorRange.FULL, color.range());
            assertEquals(new ContentLightLevel(1000, 400), color.contentLightLevel().orElseThrow());
            var display = color.masteringDisplay().orElseThrow();
            assertEquals(0.708, display.red().x(), 1e-9);
            assertEquals(0.797, display.green().y(), 1e-9);
            assertEquals(0.131, display.blue().x(), 1e-9);
            assertEquals(0.329, display.whitePoint().y(), 1e-9);
            assertEquals(1000, display.maxLuminance(), 1e-9);
            assertEquals(0.005, display.minLuminance(), 1e-9);
        }

        @Test
        @DisplayName("declares an unspecified color carrying mdcv without a colr box and ignores truncated metadata")
        void withoutColr() {
            var unspecified = ((VideoTrack) demux(fileWith(mdcv())).tracks().getFirst()).color().orElseThrow();
            assertEquals(MatrixCoefficients.UNSPECIFIED, unspecified.matrix());
            assertEquals(ColorPrimaries.UNSPECIFIED, unspecified.primaries());
            assertEquals(TransferCharacteristics.UNSPECIFIED, unspecified.transfer());
            assertEquals(ColorRange.LIMITED, unspecified.range());
            assertEquals(1000, unspecified.masteringDisplay().orElseThrow().maxLuminance(), 1e-9);
            assertTrue(unspecified.contentLightLevel().isEmpty());
            assertTrue(((VideoTrack) demux(fileWith(box("mdcv", u16(1)), box("clli", u16(1)))).tracks().getFirst())
                    .color().isEmpty());
            var truncated = fileWith(box("colr", fourCc("nclx"), u16(1), u16(1), u16(1), u8(0)), box("mdcv", u16(1)),
                    box("clli", u16(1)));
            var color = ((VideoTrack) demux(truncated).tracks().getFirst()).color().orElseThrow();
            assertTrue(color.masteringDisplay().isEmpty());
            assertTrue(color.contentLightLevel().isEmpty());
        }
    }

    @Nested
    @DisplayName("degenerate tables")
    class Degenerate {
        @Test
        @DisplayName("lists an empty track and returns no packet")
        void emptyTrack() {
            var data = file(new byte[0], mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2),
                    table("stsc", 0, 3), stsz(0), table("stco", 0, 1)));
            var ours = demux(data);
            assertEquals(1, ours.tracks().size());
            assertTrue(ours.order().isEmpty());
        }

        @Test
        @DisplayName("reads a one-sample file")
        void oneSample() {
            var ours = demux(videoFile(1, 10));
            assertEquals(1, ours.order().size());
            var packet = ours.order().getFirst();
            assertEquals(0, packet.pts());
            assertEquals(40, packet.duration());
            assertTrue(packet.keyframe());
            assertEquals(25, ((VideoTrack) ours.tracks().getFirst()).frameRate().orElseThrow().toDouble(), 1e-9);
        }

        @Test
        @DisplayName("accepts zero-duration samples")
        void zeroDuration() {
            var data = file(samples(3, 4), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 3, 0),
                    table("stsc", 0, 3, 1, 3, 1), stsz(4, new int[3]), table("stco", 0, 1, DATA_START)));
            var packets = demux(data).order();
            assertEquals(3, packets.size());
            assertTrue(packets.stream().allMatch(packet -> packet.pts() == 0 && packet.duration() == 0));
            assertArrayEquals(new byte[]{2, 2, 2, 2}, packets.get(2).data());
        }

        @Test
        @DisplayName("reconciles tables that disagree about the sample count")
        void mismatchedCounts() {
            var data = file(samples(4, 4), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 2, 40),
                    table("stsc", 0, 3, 1, 3, 1), stsz(4, new int[5]), table("stco", 0, 1, DATA_START)));
            var packets = demux(data).order();
            assertEquals(3, packets.size(), "the chunks cover three of the five declared samples");
            assertEquals(List.of(0L, 40L, 80L), packets.stream().map(Packet::dts).toList(), "the last stts delta repeats");
        }

        @Test
        @DisplayName("marks every sample key when stss is empty and only the listed ones otherwise")
        void syncSamples() {
            assertTrue(demux(videoFile(4, 4, table("stss", 0, 1))).order().stream().allMatch(Packet::keyframe));
            var listed = demux(videoFile(4, 4, table("stss", 0, 1, 1, 3))).order();
            assertEquals(List.of(true, false, true, false), listed.stream().map(Packet::keyframe).toList());
        }
    }

    @Nested
    @DisplayName("sample table layouts")
    class Layouts {
        @Test
        @DisplayName("walks every stsc pattern")
        void stscPatterns() {
            // chunks of 2, 2, 1, 3, 3 samples: runs starting at chunks 1, 3 and 4
            var sizes = new int[11];
            for (var index = 0; index < sizes.length; index++) {
                sizes[index] = index + 1;
            }
            var chunkOffsets = new long[]{DATA_START, DATA_START + 100, DATA_START + 200, DATA_START + 300, DATA_START + 400};
            var data = file(zeros(500), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 11, 10),
                    table("stsc", 0, 3, 1, 2, 1, 3, 1, 1, 4, 3, 1), stsz(0, sizes), table("stco", 0, 1, chunkOffsets)));
            try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
                for (var index = 0; index < sizes.length; index++) {
                    var packet = demuxer.read();
                    assertEquals(sizes[index], packet.size(), "size of sample " + index);
                }
                assertNull(demuxer.read());
            }
            var layout = new MovieParser(MediaLimits.DEFAULT);
            layout.parse(BoxCursor.of(Arrays.copyOfRange(data, DATA_START + 500 + 8, data.length), BoxTypes.MOOV));
            var offsets = Arrays.copyOf(layout.tracks.getFirst().samples.offsets, 11);
            var base = (long) DATA_START;
            assertArrayEquals(new long[]{base, base + 1, base + 100, base + 103, base + 200, base + 300, base + 306, base + 313,
                    base + 400, base + 409, base + 419}, offsets);
        }

        @ParameterizedTest(name = "{0}-bit fields")
        @ValueSource(ints = {4, 8, 16})
        @DisplayName("reads compact sample sizes")
        void stz2(int bits) {
            var sizes = new int[]{3, 1, 4, 1, 5};
            var payload = new java.io.ByteArrayOutputStream();
            payload.writeBytes(u32(bits));
            payload.writeBytes(u32(sizes.length));
            if (bits == 4) {
                payload.writeBytes(new byte[]{0x31, 0x41, 0x50});
            } else {
                for (var size : sizes) {
                    payload.writeBytes(bits == 8 ? u8(size) : u16(size));
                }
            }
            var data = file(samples(5, 5), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 5, 10),
                    table("stsc", 0, 3, 1, 5, 1), full("stz2", 0, 0, payload.toByteArray()), table("stco", 0, 1, DATA_START)));
            assertEquals(List.of(3, 1, 4, 1, 5), demux(data).order().stream().map(Packet::size).toList());
        }

        @Test
        @DisplayName("reads 64-bit chunk offsets and 64-bit box sizes")
        void largeOffsets() {
            var data = concat(ftyp(), largeBox("mdat", samples(2, 8)),
                    box("moov", mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 2, 20),
                            table("stsc", 0, 3, 1, 1, 1), stsz(8, new int[2]), co64(DATA_START + 8, DATA_START + 16))));
            var packets = demux(data).order();
            assertEquals(2, packets.size());
            assertArrayEquals(new byte[8], packets.get(0).data());
            assertArrayEquals(samples(2, 8), concat(packets.get(0).data(), packets.get(1).data()));
        }

        @Test
        @DisplayName("reads an mdat of size zero that runs to the end of the file")
        void sizeZeroMdat() {
            var moov = box("moov", mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 2, 20),
                    table("stsc", 0, 3, 1, 2, 1), stsz(3, new int[2]), table("stco", 0, 1, 20 + 0)));
            var moovSize = moov.length;
            moov = box("moov", mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 2, 20),
                    table("stsc", 0, 3, 1, 2, 1), stsz(3, new int[2]), table("stco", 0, 1, 20 + moovSize + 8)));
            var data = concat(ftyp(), moov, u32(0), fourCc("mdat"), samples(2, 3));
            var packets = demux(data).order();
            assertEquals(2, packets.size());
            assertArrayEquals(new byte[]{1, 1, 1}, packets.get(1).data());
        }

        @Test
        @DisplayName("applies empty edits and negative composition offsets")
        void editsAndComposition() {
            var elst = full("elst", 1, 0, u32(2), u64(500), u64(-1L), u16(1), u16(0), u64(1000), u64(10), u16(1), u16(0));
            var data = file(samples(3, 2), mvhd(1000, 1500), box("trak",
                    Mp4Builder.tkhd(1, 64, 48, new int[]{0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000}),
                    box("edts", elst), box("mdia", Mp4Builder.mdhd(1000, 60), Mp4Builder.hdlr("vide"),
                            box("minf", box("stbl", Mp4Builder.stsd(avc1()), table("stts", 0, 2, 3, 20),
                                    table("ctts", 1, 2, 1, 10, 1, -10 & 0xFFFFFFFFL, 1, 0),
                                    table("stsc", 0, 3, 1, 3, 1), stsz(2, new int[3]), table("stco", 0, 1, DATA_START))))));
            var ours = demux(data);
            var packets = ours.order();
            // delay 500 minus media time 10; pts = dts + cts; dts lowered by the -10 offset
            assertEquals(List.of(500L, 500L, 530L), packets.stream().map(Packet::pts).toList());
            assertEquals(List.of(480L, 500L, 520L), packets.stream().map(Packet::dts).toList());
            assertEquals(1500, ours.tracks().getFirst().duration());
        }

        @Test
        @DisplayName("reads a source whose size is unknown")
        void unknownSize() {
            var data = videoFile(3, 7);
            var inner = MediaSource.of(data);
            var unsized = new MediaSource() {
                @Override
                public long size() {
                    return -1;
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    return inner.read(position, buffer, offset, length);
                }

                @Override
                public void close() {
                }
            };
            try (var demuxer = new Mp4Demuxer(unsized)) {
                assertEquals(3, ContainerProbe.demuxAll(demuxer).order().size());
            }
        }

        @Test
        @DisplayName("maps QuickTime PCM and ISO PCM sample entries")
        void pcmEntries() {
            var ipcm = file(zeros(4096), mvhd(8000, 0), trak(1, "soun", 8000, 0,
                    audioEntry("ipcm", 2, 8000, full("pcmC", 0, 0, u8(1), u8(24))),
                    table("stts", 0, 2, 100, 1), table("stsc", 0, 3, 1, 100, 1), stsz(6, new int[100]), table("stco", 0, 1, DATA_START)));
            var ours = demux(ipcm);
            var track = assertInstanceOf(AudioTrack.class, ours.tracks().getFirst());
            assertEquals(Codec.PCM_S24LE, track.codec());
            assertEquals(24, track.bitsPerSample());
            assertEquals(1, ours.order().size());
            assertEquals(600, ours.order().getFirst().size(), "100 frames grouped into one packet");
        }
    }

    @Nested
    @DisplayName("fragments and gapless")
    class Fragments {
        @Test
        @DisplayName("reads runs without data offsets, defaults, missing tfdt and signed offsets")
        void flagCombinations() {
            var moov = box("moov", mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2), table("stsc", 0, 3),
                            stsz(0), table("stco", 0, 1)),
                    box("mvex", full("trex", 0, 0, u32(1), u32(1), u32(10), u32(3), u32(0x00010000))));
            var moovEnd = Mp4Builder.FTYP_SIZE + moov.length;
            // fragment 1: tfhd with explicit base data offset and default size; two runs, the second
            // continuing where the first ended; version 1 run with a negative composition offset
            var base = moovEnd + 1000L;
            var traf1 = box("traf", full("tfhd", 0, 0x000001 | 0x000002 | 0x000010, u32(1), u64(base), u32(1), u32(5)),
                    full("tfdt", 1, 0, u64(100)),
                    full("trun", 1, 0x000001 | 0x000004 | 0x000800, u32(2), u32(0), u32(0x02000000), u32(20), u32(-10 & 0xFFFFFFFFL)),
                    full("trun", 0, 0x000100 | 0x000200 | 0x000400, u32(1), u32(7), u32(6), u32(0x01010000)));
            var moof1 = box("moof", full("mfhd", 0, 0, u32(1)), traf1);
            var padding = new byte[(int) (base - moovEnd - moof1.length - 8)];
            var mdat1 = box("mdat", concat(padding, samples(2, 5), new byte[]{9, 9, 9, 9, 9, 9}));
            // fragment 2: no tfdt (continues), default-base-is-moof, trex defaults for everything
            var traf2Size = 8 + (8 + 4 + 4) + (8 + 4 + 4 + 4);
            var moof2Size = 8 + 16 + traf2Size;
            var traf2 = box("traf", full("tfhd", 0, 0x020000, u32(1)), full("trun", 0, 0x000001, u32(2), u32(moof2Size + 8)));
            var moof2 = box("moof", full("mfhd", 0, 0, u32(2)), traf2);
            assertEquals(moof2Size, moof2.length);
            var mdat2 = box("mdat", new byte[]{1, 1, 1, 2, 2, 2});
            var data = concat(ftyp(), moov, moof1, mdat1, moof2, mdat2);
            var packets = demux(data).order();
            assertEquals(List.of(5, 5, 6, 3, 3), packets.stream().map(Packet::size).toList());
            assertEquals(List.of(100L, 110L, 120L, 127L, 137L), packets.stream().map(Packet::dts).toList().stream()
                    .map(dts -> dts + 10).toList(), "decoding times, lowered by the -10 composition offset");
            assertEquals(List.of(120L, 100L, 120L, 127L, 137L), packets.stream().map(Packet::pts).toList());
            assertEquals(List.of(true, false, false, false, false), packets.stream().map(Packet::keyframe).toList());
            assertEquals(List.of(10L, 10L, 7L, 10L, 10L), packets.stream().map(Packet::duration).toList());
            assertArrayEquals(new byte[]{9, 9, 9, 9, 9, 9}, packets.get(2).data());
            assertArrayEquals(new byte[]{2, 2, 2}, packets.get(4).data());
        }

        @Test
        @DisplayName("takes the audio skip from iTunSMPB when there is no edit list")
        void gapless() {
            var smpb = " 00000000 00000840 000001CA 0000000000005AC8 00000000 00000000";
            var freeform = box("----", full("mean", 0, 0, "com.apple.iTunes".getBytes()), full("name", 0, 0, "iTunSMPB".getBytes()),
                    box("data", u32(1), u32(0), smpb.getBytes()));
            var udta = box("udta", full("meta", 0, 0, Mp4Builder.hdlr("mdir"), box("ilst", freeform,
                    box("\u00A9nam", box("data", u32(1), u32(0), "Gapless".getBytes())))));
            var data = file(zeros(40), mvhd(44100, 4096), trak(1, "soun", 44100, 4096,
                    audioEntry("mp4a", 2, 44100, full("esds", 0, 0, u8(3), u8(25), u16(1), u8(0), u8(4), u8(17), u8(0x40), u8(0x15),
                            zeros(3), u32(0), u32(0), u8(5), u8(2), u8(0x12), u8(0x10), u8(6), u8(1), u8(2))),
                    table("stts", 0, 2, 4, 1024), table("stsc", 0, 3, 1, 4, 1), stsz(10, new int[4]), table("stco", 0, 1, DATA_START)), udta);
            var ours = demux(data);
            var audio = assertInstanceOf(AudioTrack.class, ours.tracks().getFirst());
            assertEquals(Codec.AAC, audio.codec());
            assertEquals(0x840, audio.skipSamples());
            assertEquals(0x5AC8, audio.duration(), "the original sample count of the tag");
            assertArrayEquals(new byte[]{0x12, 0x10}, audio.codecConfig().orElseThrow());
            assertEquals("Gapless", ours.metadata().get("title"));
            assertEquals(smpb, ours.metadata().get("iTunSMPB"));
            assertEquals(0, ours.order().getFirst().pts());
        }
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        void assertMalformed(byte[] data) {
            assertThrows(MediaException.class, () -> demux(data));
        }

        @Test
        @DisplayName("rejects inputs that are not ISO base media")
        void notMp4() {
            assertThrows(MediaException.Malformed.class, () -> demux(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> demux(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0}));
            assertThrows(MediaException.Malformed.class, () -> demux(ftyp()), "no moov");
        }

        @Test
        @DisplayName("rejects a track without stsd")
        void missingStsd() {
            var data = file(zeros(4), mvhd(1000, 0), box("trak", Mp4Builder.tkhd(1, 0, 0, new int[9]),
                    box("mdia", Mp4Builder.mdhd(1000, 0), Mp4Builder.hdlr("vide"), box("minf", box("stbl", table("stts", 0, 2))))));
            assertEquals("track 1 has no stsd", assertThrows(MediaException.Malformed.class, () -> demux(data)).getMessage());
        }

        @Test
        @DisplayName("rejects a truncated moov and a truncated mdat")
        void truncated() {
            var data = videoFile(10, 100);
            for (var cut = DATA_START + 1000 + 8; cut < data.length; cut += 7) {
                assertMalformed(Arrays.copyOf(data, cut));
            }
            var mdatFirst = concat(ftyp(), box("moov", mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 3, 10),
                    table("stsc", 0, 3, 1, 3, 1), stsz(100, new int[3]), table("stco", 0, 1, 2000))));
            var truncatedMdat = concat(mdatFirst, u32(308), fourCc("mdat"), zeros(150));
            try (var source = MediaSource.of(truncatedMdat); var demuxer = new Mp4Demuxer(source)) {
                assertThrows(MediaException.Malformed.class, demuxer::read);
            }
        }

        @Test
        @DisplayName("rejects chunk offsets beyond the file")
        void bogusOffsets() {
            var data = file(zeros(8), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 2, 10),
                    table("stsc", 0, 3, 1, 1, 1), stsz(4, new int[2]), table("stco", 0, 1, DATA_START, 0xFFFFFFF0L)));
            try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
                assertTrue(demuxer.read() != null);
                assertThrows(MediaException.Malformed.class, demuxer::read);
            }
        }

        @Test
        @DisplayName("rejects boxes that overflow their parent or are smaller than their header")
        void oversizedBoxes() {
            var overflow = concat(ftyp(), u32(1000), fourCc("moov"), mvhd(1000, 0));
            assertMalformed(overflow);
            var tiny = concat(ftyp(), box("moov", u32(4), fourCc("trak"), mvhd(1000, 0)));
            assertMalformed(tiny);
            var childOverflow = concat(ftyp(), box("moov", mvhd(1000, 0), u32(0x7FFFFFFF), fourCc("trak")));
            assertMalformed(childOverflow);
        }

        @Test
        @DisplayName("bounds huge sample counts by the frame limit")
        void hugeCounts() {
            var limits = MediaLimits.builder().maxDimension(4096).maxPixels(1 << 24).maxFrames(1000).maxChannels(8)
                    .maxBytes(1 << 26).build();
            var atLimit = file(zeros(1000), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 1000, 1),
                    table("stsc", 0, 3, 1, 1000, 1), stszCount(1, 1000), table("stco", 0, 1, DATA_START)));
            assertEquals(1000, demux(atLimit, limits).order().size());
            var overLimit = file(zeros(1001), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 1001, 1),
                    table("stsc", 0, 3, 1, 1001, 1), stszCount(1, 1001), table("stco", 0, 1, DATA_START)));
            assertThrows(MediaException.LimitExceeded.class, () -> demux(overLimit, limits));
            var hostile = file(zeros(8), mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2, 0xFFFFFFFFL, 1),
                    table("stsc", 0, 3, 1, 0xFFFFFFFFL, 1), stszCount(1, 0xFFFFFFFFL), table("stco", 0, 1, DATA_START)));
            assertThrows(MediaException.LimitExceeded.class, () -> demux(hostile));
            var hostileTrun = concat(ftyp(), box("moov", mvhd(1000, 0), trak(1, "vide", 1000, 0, avc1(), table("stts", 0, 2),
                            table("stsc", 0, 3), stsz(0), table("stco", 0, 1)),
                            box("mvex", full("trex", 0, 0, u32(1), u32(1), u32(1), u32(1), u32(0)))),
                    box("moof", full("mfhd", 0, 0, u32(1)), box("traf", full("tfhd", 0, 0x20000, u32(1)),
                            full("trun", 0, 0, u32(0x7FFFFFFF)))));
            assertThrows(MediaException.LimitExceeded.class, () -> demux(hostileTrun));
        }

        @Test
        @DisplayName("rejects encrypted tracks as unsupported")
        void encrypted() {
            var data = file(zeros(4), mvhd(1000, 0), trak(1, "vide", 1000, 0, Mp4Builder.visualEntry("encv", 64, 48),
                    table("stts", 0, 2), table("stsc", 0, 3), stsz(0), table("stco", 0, 1)));
            assertEquals("mp4 encryption", assertThrows(MediaException.Unsupported.class, () -> demux(data)).format());
        }

        @Test
        @DisplayName("skips top-level garbage after the last box")
        void trailingGarbage() {
            var data = concat(videoFile(2, 4), new byte[]{1, 2, 3});
            assertEquals(2, demux(data).order().size());
            assertFalse(demux(concat(videoFile(2, 4), zeros(16))).order().isEmpty());
        }
    }
}
