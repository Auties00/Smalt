package com.github.auties00.smalt;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.ts.TsMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.container.PresentationSamples;
import com.github.auties00.smalt.testkit.validate.Findings;
import com.github.auties00.smalt.testkit.validate.IsoBmff;
import com.github.auties00.smalt.testkit.validate.IsoBmffValidator;
import com.github.auties00.smalt.testkit.validate.MatroskaValidator;
import com.github.auties00.smalt.testkit.validate.TsValidator;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the test kit's container validators: Smalt's own muxer outputs pass (which makes these tests also checks of
 * the muxers), and each rule fails on an output mutated to break it, both in the Java validators and, where the
 * machine has them, in the reference validators they run (mkvalidator, TSDuck, MP4Box). HLS and DASH presentations
 * are written by hand over segments of Smalt's segmenting muxers, then broken one rule at a time.
 */
@DisplayName("Container validators")
class ValidatorsTest {
    private static final Path OUTPUT = Path.of("target", "validators-test");

    private static AudioTrack aac() {
        return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                .codecConfig(new byte[]{0x11, (byte) 0x90}).sampleRate(48_000).layout(ChannelLayout.STEREO).build();
    }

    /** A silent AAC-LC stereo frame of 1024 samples. */
    private static byte[] silentFrame() {
        return new byte[]{0x21, 0x00, 0x49, (byte) 0x90, 0x02, 0x19, 0x00, 0x23, (byte) 0x80};
    }

    private static void write(Muxer muxer, int frames) {
        try (muxer) {
            muxer.addTrack(aac());
            for (var frame = 0; frame < frames; frame++) {
                muxer.writePacket(new Packet(1, silentFrame(), frame * 1024L, frame * 1024L, 1024, true));
            }
            muxer.finish();
        }
    }

    private static byte[] mux(Function<MediaSink, Muxer> factory, int frames) {
        var sink = MediaSink.memory();
        write(factory.apply(sink), frames);
        return sink.toByteArray();
    }

    private static Findings check(String name, byte[] data, Consumer<Findings> validator) {
        var findings = new Findings(name);
        validator.accept(findings);
        return findings;
    }

    private static Findings isoBmff(byte[] data) {
        return check("mp4", data, findings -> IsoBmffValidator.validate(data, findings, IsoBmffValidator.Kind.FILE,
                Map.of()));
    }

    private static Findings matroska(byte[] data) {
        return check("mkv", data, findings -> MatroskaValidator.validate(data, findings));
    }

    private static Findings ts(byte[] data) {
        return check("ts", data, findings -> TsValidator.validate(data, findings));
    }

    private static void assertFinds(Findings findings, String expected) {
        assertTrue(findings.problems().stream().anyMatch(problem -> problem.contains(expected)),
                "expected a problem containing \"" + expected + "\", found " + findings.problems());
    }

    private static IsoBmff.Box box(byte[] data, String... path) {
        var boxes = IsoBmff.parse(data, new Findings(""));
        var first = IsoBmff.top(boxes, path[0]).orElseThrow();
        return first.find(java.util.Arrays.copyOfRange(path, 1, path.length)).orElseThrow();
    }

    private static void put32(byte[] data, long offset, long value) {
        data[(int) offset] = (byte) (value >>> 24);
        data[(int) offset + 1] = (byte) (value >>> 16);
        data[(int) offset + 2] = (byte) (value >>> 8);
        data[(int) offset + 3] = (byte) value;
    }

    private static long get32(byte[] data, long offset) {
        return (data[(int) offset] & 0xFFL) << 24 | (data[(int) offset + 1] & 0xFF) << 16
                | (data[(int) offset + 2] & 0xFF) << 8 | data[(int) offset + 3] & 0xFF;
    }

    private static int indexOf(byte[] data, int from, int... pattern) {
        outer:
        for (var i = from; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if ((data[i + j] & 0xFF) != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("pattern not found");
    }

    /** Builds a minimal valid MP4 file of one H.264 sample from its parts, per ISO/IEC 14496-12 and 14496-15. */
    static final class MinimalMp4 {
        private static byte[] box(String type, byte[]... parts) {
            var out = new java.io.ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            var body = out.toByteArray();
            var result = new byte[8 + body.length];
            put32(result, 0, result.length);
            System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, result, 4, 4);
            System.arraycopy(body, 0, result, 8, body.length);
            return result;
        }

        private static byte[] full(String type, int flags, byte[]... parts) {
            var all = new byte[parts.length + 1][];
            all[0] = new byte[]{0, (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags};
            System.arraycopy(parts, 0, all, 1, parts.length);
            return box(type, all);
        }

        private static byte[] u32s(long... values) {
            var out = new byte[values.length * 4];
            for (var i = 0; i < values.length; i++) {
                put32(out, i * 4L, values[i]);
            }
            return out;
        }

        private static byte[] u16s(int... values) {
            var out = new byte[values.length * 2];
            for (var i = 0; i < values.length; i++) {
                out[2 * i] = (byte) (values[i] >> 8);
                out[2 * i + 1] = (byte) values[i];
            }
            return out;
        }

        private static final long[] MATRIX = {0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000};

        static byte[] h264(byte[] sps, byte[] pps, boolean formatFields) {
            var record = new java.io.ByteArrayOutputStream();
            record.writeBytes(new byte[]{1, sps[1], sps[2], sps[3], (byte) 0xFF, (byte) 0xE1});
            record.writeBytes(u16s(sps.length));
            record.writeBytes(sps);
            record.write(1);
            record.writeBytes(u16s(pps.length));
            record.writeBytes(pps);
            if (formatFields) {
                record.writeBytes(new byte[]{(byte) 0xFD, (byte) 0xF8, (byte) 0xF8, 0});
            }
            var entry = box("avc1", new byte[6], u16s(1, 0, 0), new byte[12], u16s(64, 64), u32s(0x480000, 0x480000, 0),
                    u16s(1), new byte[32], u16s(0x18, 0xFFFF), box("avcC", record.toByteArray()));
            var sample = new byte[10];
            var ftyp = box("ftyp", "isom".getBytes(StandardCharsets.ISO_8859_1), u32s(0x200),
                    "isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1));
            byte[] moov = null;
            for (var pass = 0; pass < 2; pass++) {
                var offset = moov == null ? 0 : ftyp.length + moov.length + 8;
                var stbl = box("stbl", full("stsd", 0, u32s(1), entry), full("stts", 0, u32s(1, 1, 1)),
                        full("stsc", 0, u32s(1, 1, 1, 1)), full("stsz", 0, u32s(0, 1, sample.length)),
                        full("stco", 0, u32s(1, offset)));
                var minf = box("minf", full("vmhd", 1, new byte[8]), box("dinf", full("dref", 0, u32s(1),
                        full("url ", 1))), stbl);
                var mdia = box("mdia", full("mdhd", 0, u32s(0, 0, 25, 1), u16s(0x55C4, 0)),
                        full("hdlr", 0, u32s(0), "vide".getBytes(StandardCharsets.ISO_8859_1), new byte[12],
                                "VideoHandler\0".getBytes(StandardCharsets.ISO_8859_1)), minf);
                var trak = box("trak", full("tkhd", 3, u32s(0, 0, 1, 0, 40, 0, 0), u16s(0, 0, 0, 0), u32s(MATRIX),
                        u32s(64L << 16, 64L << 16)), mdia);
                moov = box("moov", full("mvhd", 0, u32s(0, 0, 1000, 40, 0x10000), u16s(0x100), new byte[10],
                        u32s(MATRIX), new byte[24], u32s(2)), trak);
            }
            var out = new java.io.ByteArrayOutputStream();
            out.writeBytes(ftyp);
            out.writeBytes(moov);
            out.writeBytes(box("mdat", sample));
            return out.toByteArray();
        }
    }

    /** Returns the offset of the first child with a one-byte id of the EBML master element at {@code master}. */
    private static int firstChild(byte[] data, int master, int id) {
        var position = master + 4;
        position += Integer.numberOfLeadingZeros(data[position] & 0xFF) - 23;
        while (position < data.length) {
            var idLength = Integer.numberOfLeadingZeros(data[position] & 0xFF) - 23;
            if (idLength == 1 && (data[position] & 0xFF) == id) {
                return position;
            }
            var sizeAt = position + idLength;
            var sizeLength = Integer.numberOfLeadingZeros(data[sizeAt] & 0xFF) - 23;
            var size = (long) (data[sizeAt] & (0xFF >> sizeLength));
            for (var i = 1; i < sizeLength; i++) {
                size = size << 8 | data[sizeAt + i] & 0xFF;
            }
            position = (int) (sizeAt + sizeLength + size);
        }
        throw new AssertionError("no child 0x" + Integer.toHexString(id));
    }

    private static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(OUTPUT);
            var file = OUTPUT.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("ISO base media files")
    class IsoBmffFiles {
        @Test
        @DisplayName("accept Smalt's MP4 and fragmented MP4, with the track and codec they carry")
        void accepts() {
            var plain = mux(Mp4Muxer::new, 100);
            assertEquals(List.of(), isoBmff(plain).problems());
            var movie = Validators.assertValidIsoBmff(save("plain.mp4", plain));
            assertEquals("mp4a.40.2", movie.tracks().get(1).codecs());
            var fragmented = mux(sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.cmaf().toBuilder()
                    .fragmentMicros(500_000).randomAccessIndex(true).build()), 150);
            assertEquals(List.of(), isoBmff(fragmented).problems());
            var fragments = Validators.assertValidIsoBmff(save("fragmented.mp4", fragmented)).fragments();
            assertTrue(fragments.size() > 3, "fragments " + fragments);
            var indexed = mux(sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.dashOnDemand()
                    .toBuilder().fragmentMicros(500_000).build()), 150);
            assertEquals(List.of(), isoBmff(indexed).problems());
            Validators.assertValid(save("indexed.mp4", indexed));
        }

        @Test
        @DisplayName("reject a box that runs past the file")
        void truncated() {
            var data = mux(Mp4Muxer::new, 100);
            assertFinds(isoBmff(java.util.Arrays.copyOf(data, data.length - 9)), "does not fit");
        }

        @Test
        @DisplayName("reject sample tables whose counts disagree")
        void counts() {
            var data = mux(Mp4Muxer::new, 100);
            var stsz = box(data, "moov", "trak", "mdia", "minf", "stbl", "stsz");
            var constant = get32(data, stsz.payload() + 4);
            if (constant == 0) {
                put32(data, stsz.payload() + 8, get32(data, stsz.payload() + 8) - 1);
                assertFinds(isoBmff(data), "size");
            } else {
                put32(data, stsz.payload() + 8, get32(data, stsz.payload() + 8) + 1);
                assertFinds(isoBmff(data), "samples, stsz has");
            }
        }

        @Test
        @DisplayName("reject a chunk outside every mdat")
        void chunks() {
            var data = mux(Mp4Muxer::new, 100);
            var stco = box(data, "moov", "trak", "mdia", "minf", "stbl").child("stco").orElseThrow();
            put32(data, stco.payload() + 8, data.length + 10L);
            assertFinds(isoBmff(data), "outside every mdat");
        }

        @Test
        @DisplayName("reject a media duration that is not the sum of the sample durations")
        void duration() {
            var data = mux(Mp4Muxer::new, 100);
            var mdhd = box(data, "moov", "trak", "mdia", "mdhd");
            var offset = mdhd.payload() + 16;
            put32(data, offset, get32(data, offset) + 1);
            assertFinds(isoBmff(data), "the samples last");
        }

        @Test
        @DisplayName("reject movie fragments whose sequence numbers, timelines or data offsets are wrong")
        void fragments() {
            Function<MediaSink, Muxer> factory = sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions
                    .cmaf().toBuilder().fragmentMicros(500_000).build());
            var data = mux(factory, 150);
            var moofs = IsoBmff.parse(data, new Findings("")).stream().filter(b -> b.type().equals("moof")).toList();
            assertTrue(moofs.size() >= 3);
            var second = moofs.get(1);
            var sequence = data.clone();
            put32(sequence, second.child("mfhd").orElseThrow().payload() + 4, 1);
            assertFinds(isoBmff(sequence), "does not increase");
            var timeline = data.clone();
            var tfdt = second.find("traf", "tfdt").orElseThrow();
            var v1 = timeline[(int) tfdt.payload()] == 1;
            var at = tfdt.payload() + (v1 ? 8 : 4);
            put32(timeline, at, get32(timeline, at) + 1);
            assertFinds(isoBmff(timeline), "base media decode time");
            var offsets = data.clone();
            var trun = second.find("traf", "trun").orElseThrow();
            put32(offsets, trun.payload() + 8, 1_000_000);
            assertFinds(isoBmff(offsets), "outside the mdat");
            var extendsBox = data.clone();
            var trex = box(extendsBox, "moov", "mvex", "trex");
            put32(extendsBox, trex.payload() + 4, 99);
            assertFinds(isoBmff(extendsBox), "trex of unknown track 99");
        }

        @Test
        @DisplayName("reject an esds without the SLConfigDescriptor value ISO/IEC 14496-14 requires")
        void esds() {
            var data = mux(Mp4Muxer::new, 100);
            var esds = box(data, "moov", "trak", "mdia", "minf", "stbl", "stsd", "mp4a", "esds");
            // the SLConfigDescriptor ends the esds: tag 6, a length of 1 in one to four bytes, the value 2
            assertEquals(2, data[(int) esds.end() - 1], "the predefined value ends Smalt's esds");
            data[(int) esds.end() - 1] = 1;
            assertFinds(isoBmff(data), "SLConfigDescriptor");
        }

        @Test
        @DisplayName("accept Smalt's PCM chnl box and reject one whose layout does not fit it")
        void chnl() {
            var pcm = com.github.auties00.smalt.container.PcmPackets.tone(Codec.PCM_S16LE, 48_000,
                    ChannelLayout.STEREO, 4800);
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink)) {
                muxer.addTrack(pcm.track().toBuilder().id(1).build());
                pcm.packets().forEach(packet -> muxer.writePacket(packet.withTrackId(1)));
                muxer.finish();
            }
            var data = sink.toByteArray();
            assertEquals(List.of(), isoBmff(data).problems());
            var entry = box(data, "moov", "trak", "mdia", "minf", "stbl", "stsd");
            var chnl = entry.children().getFirst().child("chnl").orElseThrow();
            assertEquals(0, data[(int) chnl.payload() + 5], "explicit speaker positions");
            data[(int) chnl.payload() + 5] = 2;
            assertFinds(isoBmff(data), "the layout of 2 channels takes");
        }

        @Test
        @DisplayName("accept a minimal hand-built H.264 file and reject its High profile avcC without the format fields")
        void handBuilt() {
            var sps = new byte[]{0x67, 100, 0, 30, (byte) 0xAC, (byte) 0xD9, 0x41, 0x41, (byte) 0xFB, 0x01, 0x10};
            var pps = new byte[]{0x68, (byte) 0xEB, (byte) 0xE3, (byte) 0xCB, 0x22, (byte) 0xC0};
            var valid = MinimalMp4.h264(sps, pps, true);
            assertEquals(List.of(), isoBmff(valid).problems());
            assertFinds(isoBmff(MinimalMp4.h264(sps, pps, false)), "without the chroma format and bit depth fields");
        }

        @Test
        @DisplayName("MP4Box accepts Smalt's files and rejects one with a broken box, where it is installed")
        void mp4Box() {
            if (Oracle.find(Oracle.Tool.MP4BOX).isEmpty()) {
                return;
            }
            assertDoesNotThrow(() -> Validators.mp4Box(save("mp4box-valid.mp4", mux(Mp4Muxer::new, 100))));
            var data = mux(Mp4Muxer::new, 100);
            var stsd = box(data, "moov", "trak", "mdia", "minf", "stbl", "stsd");
            put32(data, stsd.offset(), stsd.size() + 4000);
            assertThrows(AssertionFailedError.class, () -> Validators.mp4Box(save("mp4box-broken.mp4", data)));
        }
    }

    @Nested
    @DisplayName("Matroska files")
    class MatroskaFiles {
        @Test
        @DisplayName("accept Smalt's Matroska, seekable and streamed")
        void accepts() {
            var file = mux(MatroskaMuxer::new, 200);
            assertEquals(List.of(), matroska(file).problems());
            Validators.assertValid(save("valid.mkv", file));
            var stream = new java.io.ByteArrayOutputStream();
            try (var sink = MediaSink.of(stream)) {
                write(new MatroskaMuxer(sink), 200);
            }
            var live = stream.toByteArray();
            var findings = new Findings("live");
            assertTrue(MatroskaValidator.validate(live, findings).live(), "a streamed segment has an unknown size");
            assertEquals(List.of(), findings.problems());
            Validators.assertValid(save("live.mkv", live));
        }

        @Test
        @DisplayName("mkvalidator accepts mkvmerge's remux, the control of the tool's build, where both are installed")
        void control() {
            if (Oracle.find(Oracle.Tool.MKVALIDATOR).isEmpty() || Oracle.find(Oracle.Tool.MKVMERGE).isEmpty()) {
                return;
            }
            var source = save("control-source.mkv", mux(MatroskaMuxer::new, 200));
            var remux = OUTPUT.resolve("control-mkvmerge.mkv").toAbsolutePath();
            Oracle.run(Oracle.Tool.MKVMERGE, java.time.Duration.ofMinutes(1), List.of("--quiet", "-o", remux.toString(),
                    source.toAbsolutePath().toString()));
            assertDoesNotThrow(() -> Validators.mkvalidator(remux, false, false));
            assertEquals(List.of(), matroska(Dash.read(remux)).problems(), "the Matroska validator accepts mkvmerge's file");
        }

        @Test
        @DisplayName("reject a missing mandatory element, a wrong DocType and a block of an unknown track")
        void semantics() {
            var data = mux(MatroskaMuxer::new, 200);
            var app = data.clone();
            var muxingApp = indexOf(app, 0, 0x4D, 0x80);
            app[muxingApp + 1] = (byte) 0x81;
            assertFinds(matroska(app), "MuxingApp and WritingApp are mandatory");
            var doc = data.clone();
            var docType = indexOf(doc, 0, 0x42, 0x82);
            doc[docType + 3] = 'X';
            assertFinds(matroska(doc), "DocType");
            var block = data.clone();
            var simple = firstChild(block, indexOf(block, 0, 0x1F, 0x43, 0xB6, 0x75), 0xA3);
            var length = Integer.numberOfLeadingZeros(block[simple + 1] & 0xFF) - 23;
            assertEquals(0x81, block[simple + 1 + length] & 0xFF, "the first block's track number");
            block[simple + 1 + length] = (byte) 0x85;
            assertFinds(matroska(block), "track 5 is not in Tracks");
            if (Oracle.find(Oracle.Tool.MKVALIDATOR).isPresent()) {
                assertThrows(AssertionFailedError.class, () -> Validators.mkvalidator(save("unknown-track.mkv",
                        block), false, false));
            }
        }

        @Test
        @DisplayName("reject an element that runs past its parent and a SeekHead or Cues position that points nowhere")
        void positions() {
            var data = mux(MatroskaMuxer::new, 200);
            var info = data.clone();
            var at = indexOf(info, 0, 0x15, 0x49, 0xA9, 0x66);
            info[at + 4] = (byte) 0x01;
            for (var i = 1; i < 8; i++) {
                info[at + 4 + i] = (byte) 0x7F;
            }
            assertFalse(matroska(info).problems().isEmpty());
            var seek = data.clone();
            var position = indexOf(seek, 0, 0x53, 0xAC);
            var size = seek[position + 2] & 0x7F;
            seek[position + 2 + size] ^= 0x01;
            assertFinds(matroska(seek), "SeekPosition");
            if (Oracle.find(Oracle.Tool.MKVALIDATOR).isPresent()) {
                assertThrows(AssertionFailedError.class, () -> Validators.mkvalidator(save("seek.mkv", seek), false,
                        false));
            }
            var cues = data.clone();
            var cluster = indexOf(cues, indexOf(cues, 0, 0x1C, 0x53, 0xBB, 0x6B), 0xF1);
            var cueSize = cues[cluster + 1] & 0x7F;
            cues[cluster + 1 + cueSize] ^= 0x02;
            assertFinds(matroska(cues), "CueClusterPosition");
        }
    }

    @Nested
    @DisplayName("transport streams")
    class TransportStreams {
        @Test
        @DisplayName("accept Smalt's transport stream")
        void accepts() {
            var data = mux(TsMuxer::new, 200);
            assertEquals(List.of(), ts(data).problems());
            var summary = Validators.assertValidTs(save("valid.ts", data));
            assertEquals(1, summary.programs());
            assertTrue(summary.tablesFirst());
        }

        @Test
        @DisplayName("reject a lost sync byte, a continuity break, a corrupt PAT and an undeclared PID")
        void broken() {
            var data = mux(TsMuxer::new, 200);
            var sync = data.clone();
            sync[188 * 3] = 0x48;
            assertFinds(ts(sync), "sync byte");
            // the first payload packet of the PMT's first elementary stream, past the tables at the start
            var pmtPid = (data[15] & 0x1F) << 8 | data[16] & 0xFF;
            var counter = -1;
            for (var at = 188 * 4; at < data.length && counter < 0; at += 188) {
                var pid = (data[at + 1] & 0x1F) << 8 | data[at + 2] & 0xFF;
                if (pid != 0 && pid != pmtPid && pid != 0x1FFF && (data[at + 3] & 0x10) != 0) {
                    counter = at;
                }
            }
            assertTrue(counter > 0, "no elementary stream packet");
            var continuity = data.clone();
            continuity[counter + 3] = (byte) ((continuity[counter + 3] & 0xF0) | ((continuity[counter + 3] + 5) & 0xF));
            assertFinds(ts(continuity), "continuity counter");
            if (Oracle.find(Oracle.Tool.TSP).isPresent()) {
                assertThrows(AssertionFailedError.class, () -> Validators.tsduck(save("continuity.ts", continuity)));
            }
            var pat = data.clone();
            pat[5 + 1 + 8] ^= 0x01;
            assertFinds(ts(pat), "CRC mismatch");
            var undeclared = data.clone();
            undeclared[counter + 1] = (byte) ((undeclared[counter + 1] & 0xE0) | 0x07);
            undeclared[counter + 2] = (byte) 0x77;
            assertFinds(ts(undeclared), "no PAT or PMT declares it");
        }
    }

    /** Writes the segments of a segmenting muxer into a directory and returns their descriptions in order. */
    private static List<Segment> segments(Path directory, Function<com.github.auties00.smalt.container.SegmentSink,
            Muxer> factory, String mediaExtension) {
        try {
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    for (var file : files.toList()) {
                        Files.delete(file);
                    }
                }
            }
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var written = new ArrayList<Segment>();
        write(factory.apply(segment -> {
            written.add(segment);
            var name = segment.kind() == Segment.Kind.INIT ? "init.mp4"
                    : "seg-" + segment.sequenceNumber() + mediaExtension;
            return MediaSink.of(directory.resolve(name));
        }), 300);
        return written;
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path writeText(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("HLS presentations")
    class Hls {
        /** Writes a master and a media playlist over the segments and returns the master playlist. */
        private Path presentation(Path directory, boolean fmp4) {
            var segments = fmp4
                    ? segments(directory, sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.cmaf()
                    .toBuilder().fragmentMicros(1_000_000).build()), ".m4s")
                    : segments(directory, sink -> new TsMuxer(sink, TsMuxerOptions.builder().segmentMicros(1_000_000)
                    .build()), ".ts");
            var media = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n");
            var target = 0L;
            var peak = 0L;
            var bits = 0.0;
            var seconds = 0.0;
            var lines = new StringBuilder();
            for (var segment : segments) {
                if (segment.kind() == Segment.Kind.INIT) {
                    lines.append("#EXT-X-MAP:URI=\"init.mp4\"\n");
                    continue;
                }
                var duration = segment.durationMicros() / 1e6;
                target = Math.max(target, Math.round(duration));
                peak = Math.max(peak, (long) Math.ceil(segment.size() * 8 / duration));
                bits += segment.size() * 8.0;
                seconds += duration;
                lines.append(String.format(Locale.ROOT, "#EXTINF:%.6f,\nseg-%d%s\n", duration,
                        segment.sequenceNumber(), fmp4 ? ".m4s" : ".ts"));
            }
            media.append("#EXT-X-TARGETDURATION:").append(target).append("\n#EXT-X-MEDIA-SEQUENCE:0\n")
                    .append("#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n").append(lines)
                    .append("#EXT-X-ENDLIST\n");
            writeText(directory.resolve("media.m3u8"), media.toString());
            return writeText(directory.resolve("master.m3u8"), "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=" + peak + ",AVERAGE-BANDWIDTH=" + Math.round(bits / seconds)
                    + ",CODECS=\"mp4a.40.2\"\nmedia.m3u8\n");
        }

        @Test
        @DisplayName("accept playlists over Smalt's fMP4 and transport stream segments")
        void accepts() {
            Validators.assertValidHls(presentation(OUTPUT.resolve("hls-fmp4"), true));
            Validators.assertValidHls(presentation(OUTPUT.resolve("hls-ts"), false));
        }

        private void assertRejects(Path master, String file, String from, String to, String expected) {
            var target = master.resolveSibling(file);
            var original = text(target);
            var pattern = java.util.regex.Pattern.compile(from);
            assertTrue(pattern.matcher(original).find(), from + " in " + original);
            writeText(target, pattern.matcher(original).replaceAll(to));
            try {
                var error = assertThrows(AssertionFailedError.class, () -> Validators.assertValidHls(master));
                assertTrue(error.getMessage().contains(expected), error.getMessage());
            } finally {
                writeText(target, original);
            }
        }

        @Test
        @DisplayName("reject a low BANDWIDTH, a wrong CODECS, a long segment, a missing ENDLIST and an old version")
        void rules() {
            var master = presentation(OUTPUT.resolve("hls-rules"), true);
            assertRejects(master, "master.m3u8", "(?<!-)BANDWIDTH=\\d+", "BANDWIDTH=1",
                    "is below the peak segment bit rate");
            assertRejects(master, "master.m3u8", "mp4a\\.40\\.2", "mp4a.40.5", "does not list mp4a.40.2");
            assertRejects(master, "media.m3u8", "#EXT-X-TARGETDURATION:\\d+", "#EXT-X-TARGETDURATION:0",
                    "rounds above EXT-X-TARGETDURATION");
            assertRejects(master, "media.m3u8", "#EXT-X-ENDLIST\n", "", "without EXT-X-ENDLIST");
            assertRejects(master, "media.m3u8", "#EXT-X-VERSION:7", "#EXT-X-VERSION:3", "features need 6");
            assertRejects(master, "media.m3u8", "seg-2\\.m4s", "seg-9999.m4s", "does not exist");
        }

        @Test
        @DisplayName("reject a segment whose fragment timeline does not continue the previous one")
        void timeline() {
            var master = presentation(OUTPUT.resolve("hls-timeline"), true);
            var segment = master.resolveSibling("seg-2.m4s");
            byte[] data;
            try {
                data = Files.readAllBytes(segment);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var tfdt = box(data, "moof", "traf", "tfdt");
            var at = tfdt.payload() + (data[(int) tfdt.payload()] == 1 ? 8 : 4);
            put32(data, at, get32(data, at) + 1024);
            save("hls-timeline/seg-2.m4s", data);
            var error = assertThrows(AssertionFailedError.class, () -> Validators.assertValidHls(master));
            assertTrue(error.getMessage().contains("the previous segment ended at"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("DASH presentations")
    class Dash {
        /** Writes an MPD over the fMP4 segments and returns it. */
        private Path presentation(Path directory, String bandwidthOverride, String codecs, long firstDurationDelta) {
            var segments = segments(directory, sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.cmaf()
                    .toBuilder().fragmentMicros(1_000_000).build()), ".m4s");
            var timeline = new StringBuilder();
            var peak = 0L;
            var total = 0.0;
            for (var segment : segments) {
                if (segment.kind() == Segment.Kind.INIT) {
                    continue;
                }
                byte[] data;
                try {
                    data = Files.readAllBytes(directory.resolve("seg-" + segment.sequenceNumber() + ".m4s"));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                var tracks = IsoBmffValidator.validate(read(directory.resolve("init.mp4")), new Findings(""),
                        IsoBmffValidator.Kind.INIT_SEGMENT, Map.of()).tracks();
                var fragment = IsoBmffValidator.validate(data, new Findings(""), IsoBmffValidator.Kind.MEDIA_SEGMENT,
                        tracks).fragments().getFirst();
                var duration = fragment.duration() + (timeline.isEmpty() ? firstDurationDelta : 0);
                timeline.append(String.format(Locale.ROOT, "            <S t=\"%d\" d=\"%d\"/>%n",
                        fragment.baseDecodeTime(), duration));
                peak = Math.max(peak, (long) Math.ceil(data.length * 8 / (fragment.duration() / 48_000.0)));
                total += fragment.duration() / 48_000.0;
            }
            var bandwidth = bandwidthOverride != null ? bandwidthOverride : Long.toString(peak);
            var mpd = String.format(Locale.ROOT, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-live:2011" \
                    type="static" mediaPresentationDuration="PT%.3fS" minBufferTime="PT1S">
                      <Period id="0" start="PT0S">
                        <AdaptationSet id="0" contentType="audio" mimeType="audio/mp4" segmentAlignment="true">
                          <Representation id="audio" bandwidth="%s" codecs="%s" audioSamplingRate="48000">
                            <SegmentTemplate timescale="48000" initialization="init.mp4" media="seg-$Number$.m4s" \
                    startNumber="1">
                              <SegmentTimeline>
                    %s            </SegmentTimeline>
                            </SegmentTemplate>
                          </Representation>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                    """, total, bandwidth, codecs, timeline);
            return writeText(directory.resolve("manifest.mpd"), mpd);
        }

        private static byte[] read(Path file) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Test
        @DisplayName("accept a manifest over Smalt's CMAF segments")
        void accepts() {
            Validators.assertValidDash(presentation(OUTPUT.resolve("dash"), null, "mp4a.40.2", 0));
        }

        @Test
        @DisplayName("reject a schema violation, a wrong codecs, a timeline that disagrees with the fragments, a missing"
                + " segment and a bandwidth the segments cannot be delivered at")
        void rules() {
            var schema = assertThrows(AssertionFailedError.class, () -> Validators.assertValidDash(
                    presentation(OUTPUT.resolve("dash-schema"), "fast", "mp4a.40.2", 0)));
            assertTrue(schema.getMessage().contains("(schema)"), schema.getMessage());
            var codecs = assertThrows(AssertionFailedError.class, () -> Validators.assertValidDash(
                    presentation(OUTPUT.resolve("dash-codecs"), null, "mp4a.40.5", 0)));
            assertTrue(codecs.getMessage().contains("the sample entry is mp4a.40.2"), codecs.getMessage());
            var timeline = assertThrows(AssertionFailedError.class, () -> Validators.assertValidDash(
                    presentation(OUTPUT.resolve("dash-timeline"), null, "mp4a.40.2", 1024)));
            assertTrue(timeline.getMessage().contains("the timeline"), timeline.getMessage());
            var bandwidth = assertThrows(AssertionFailedError.class, () -> Validators.assertValidDash(
                    presentation(OUTPUT.resolve("dash-bandwidth"), "1000", "mp4a.40.2", 0)));
            assertTrue(bandwidth.getMessage().contains("after their presentation ends"), bandwidth.getMessage());
            var manifest = presentation(OUTPUT.resolve("dash-missing"), null, "mp4a.40.2", 0);
            try {
                Files.delete(manifest.resolveSibling("seg-2.m4s"));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var missing = assertThrows(AssertionFailedError.class, () -> Validators.assertValidDash(manifest));
            assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
        }
    }

    @Nested
    @DisplayName("subtitle, trick-play and live presentations")
    class Streaming {
        /** Writes one of the test kit's packaged presentations into a fresh directory and returns its manifest. */
        private Path presentation(String name, String directory) {
            var target = OUTPUT.resolve(directory);
            try {
                if (Files.isDirectory(target)) {
                    try (var stale = Files.list(target)) {
                        for (var file : stale.toList()) {
                            Files.delete(file);
                        }
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return PresentationSamples.write(PresentationSamples.of(name, Long.MAX_VALUE), target);
        }

        private void assertRejected(Path manifest, String expected) {
            var error = assertThrows(AssertionFailedError.class, () -> {
                if (manifest.getFileName().toString().endsWith(".mpd")) {
                    Validators.assertValidDash(manifest);
                } else {
                    Validators.assertValidHls(manifest);
                }
            });
            assertTrue(error.getMessage().contains(expected), error.getMessage());
        }

        private void replace(Path file, String from, String to) {
            var original = text(file);
            assertTrue(original.contains(from), from + " in " + original);
            writeText(file, original.replace(from, to));
        }

        @Test
        @DisplayName("accept the packager's subtitle, I-frame, trick-play and live presentations")
        void accepts() {
            for (var name : List.of("hls-ts", "hls-cmaf", "hls-iframes", "hls-cmaf-iframes", "dash-template",
                    "dash-live")) {
                var manifest = presentation(name, "streaming-" + name);
                if (name.startsWith("dash")) {
                    Validators.assertValidDash(manifest);
                } else {
                    Validators.assertValidHls(manifest);
                }
            }
        }

        @Test
        @DisplayName("reject WebVTT segments on the wrong timeline, and a spanning cue that is not repeated")
        void webVtt() {
            var shifted = presentation("hls-ts", "streaming-vtt-map");
            try (var segments = Files.list(shifted.getParent())) {
                for (var file : segments.filter(path -> path.toString().endsWith(".vtt")).toList()) {
                    replace(file, "MPEGTS:63000", "MPEGTS:0");
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            assertRejected(shifted, "first appears in the segment");
            var dropped = presentation("hls-ts", "streaming-vtt-repeat");
            replace(dropped.resolveSibling("cues_00002.vtt"), "00:00:00.500 --> 00:00:00.900", "00:00:00.700 --> "
                    + "00:00:00.900");
            assertRejected(dropped, "is not repeated");
        }

        @Test
        @DisplayName("reject an I-frame range that does not start at a key frame")
        void iFrames() {
            var master = presentation("hls-ts", "streaming-iframes");
            var playlist = master.resolveSibling("clip_iframes.m3u8");
            var text = text(playlist);
            var matcher = java.util.regex.Pattern.compile("#EXT-X-BYTERANGE:(\\d+)@(\\d+)\nclip_00002").matcher(text);
            assertTrue(matcher.find(), text);
            var length = Long.parseLong(matcher.group(1));
            var offset = Long.parseLong(matcher.group(2));
            writeText(playlist, text.replace(matcher.group(), "#EXT-X-BYTERANGE:" + (length - 188) + "@"
                    + (offset + 188) + "\nclip_00002"));
            assertRejected(master, "random access point");
        }

        @Test
        @DisplayName("reject an event that removed segments, program dates that skip, and a live window too short")
        void live() {
            var master = presentation("hls-ts", "streaming-event");
            var media = master.resolveSibling("clip.m3u8");
            replace(media, "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-PLAYLIST-TYPE:EVENT");
            replace(media, "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:1");
            assertRejected(media, "an EVENT playlist that removed segments");
            var window = presentation("hls-ts", "streaming-window");
            var playlist = window.resolveSibling("clip.m3u8");
            replace(playlist, "#EXT-X-PLAYLIST-TYPE:VOD\n", "");
            replace(playlist, "#EXT-X-ENDLIST\n", "");
            replace(playlist, "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:5");
            assertRejected(playlist, "below three target durations");
            var dates = presentation("hls-ts", "streaming-dates");
            var dated = dates.resolveSibling("clip.m3u8");
            var lines = new ArrayList<>(List.of(text(dated).split("\n")));
            var second = 0;
            for (var index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith("#EXTINF")) {
                    lines.add(index, second++ == 0 ? "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00.000Z"
                            : "#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:10.000Z");
                    index++;
                }
            }
            writeText(dated, String.join("\n", lines) + "\n");
            assertRejected(dated, "EXT-X-PROGRAM-DATE-TIME advances");
        }

        @Test
        @DisplayName("reject a dynamic manifest without its times, and a trick-play set that names no video set")
        void dash() {
            var live = presentation("dash-live", "streaming-dynamic");
            var text = text(live);
            writeText(live, text.replaceFirst(" availabilityStartTime=\"[^\"]+\"", ""));
            assertRejected(live, "without availabilityStartTime");
            var future = presentation("dash-live", "streaming-future");
            writeText(future, text(future).replaceFirst("publishTime=\"[^\"]+\"",
                    "publishTime=\"2026-01-01T00:00:00.500Z\""));
            assertRejected(future, "after the publish time");
            var trick = presentation("dash-template", "streaming-trick");
            replace(trick, "trickmode\" value=\"0\"", "trickmode\" value=\"9\"");
            assertRejected(trick, "no video AdaptationSet");
            var dependent = presentation("dash-template", "streaming-coding");
            replace(dependent, "codingDependency=\"false\"", "codingDependency=\"true\"");
            assertRejected(dependent, "codingDependency");
        }
    }

    @Nested
    @DisplayName("format detection")
    class Sniffing {
        @Test
        @DisplayName("recognizes each container by its first bytes")
        void sniff() {
            assertEquals(Validators.Format.ISOBMFF, Validators.sniff(mux(Mp4Muxer::new, 10)));
            assertEquals(Validators.Format.MATROSKA, Validators.sniff(mux(MatroskaMuxer::new, 10)));
            assertEquals(Validators.Format.TRANSPORT_STREAM, Validators.sniff(mux(TsMuxer::new, 10)));
            assertEquals(Validators.Format.HLS, Validators.sniff("#EXTM3U\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(Validators.Format.DASH, Validators.sniff("<?xml version=\"1.0\"?>\n<MPD>"
                    .getBytes(StandardCharsets.UTF_8)));
            assertEquals(Validators.Format.OTHER, Validators.sniff("RIFF....WAVE".getBytes(StandardCharsets.UTF_8)));
            // a GIF starts with the sync byte of a transport stream
            var gif = Arrays.copyOf("GIF89a".getBytes(StandardCharsets.US_ASCII), 216);
            assertEquals(Validators.Format.OTHER, Validators.sniff(gif));
            // even one whose byte 188 happens to be a sync byte too
            var longGif = Arrays.copyOf("GIF89a".getBytes(StandardCharsets.US_ASCII), 1000);
            longGif[188] = 0x47;
            assertEquals(Validators.Format.OTHER, Validators.sniff(longGif));
            // a stream that loses its sync after the second packet is still a stream, for TsValidator to reject
            var lost = mux(TsMuxer::new, 50);
            lost[188 * 2] = 0x48;
            assertEquals(Validators.Format.TRANSPORT_STREAM, Validators.sniff(lost));
            var error = assertThrows(AssertionFailedError.class, () -> Validators.assertValid("lost-sync.ts", lost));
            assertTrue(error.getMessage().contains("sync byte"), error.getMessage());
        }
    }
}
