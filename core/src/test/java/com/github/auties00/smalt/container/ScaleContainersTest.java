package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.avi.AviMuxerOptions;
import com.github.auties00.smalt.container.caf.CafMuxer;
import com.github.auties00.smalt.container.dv.DvDemuxer;
import com.github.auties00.smalt.container.flv.FlvMuxer;
import com.github.auties00.smalt.container.ivf.IvfMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsDemuxerOptions;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.container.wav.Wave64Muxer;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Containers at the scale where 32-bit fields, frame limits and table sizes give out: files past 4 GiB (MP4 and
 * QuickTime with {@code co64} and a 64-bit {@code mdat}, RF64, Wave64, CAF, Matroska, MPEG-TS) and AVI past the 1 GiB
 * of its first RIFF, more than a million frames (MP4, Matroska, AVI, FLV, IVF, DV), 64 tracks (Matroska, MP4) and
 * 16 programs (MPEG-TS). The files are generated here at run time, never downloaded: the large ones are mostly zero
 * payload, held as their nonzero pages ({@link SparseMedia}) and saved as sparse files under
 * {@code Conformance.cache("scale")} for ffprobe, the oracle of every case; the DV file of a million frames is never
 * stored at all, a source repeating one frame.
 */
@DisplayName("containers at scale")
class ScaleContainersTest {
    /** The size past which a 32-bit size or offset wraps. */
    private static final long FOUR_GIB = 1L << 32;

    /** The number of frames past the default {@link MediaLimits#maxFrames()}. */
    private static final int MANY = 1_100_000;

    private static Path cache(String name) {
        return Conformance.cache("scale").resolve(name);
    }

    /** Runs ffprobe, counting every packet, for the streams and the format of a file. */
    private static Map<String, String> ffprobe(Path file, String... extra) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var arguments = new ArrayList<>(List.of("-v", "error", "-count_packets", "-show_entries",
                "stream=index,codec_name,nb_read_packets:format=size,duration,nb_streams,nb_programs", "-of", "flat"));
        arguments.addAll(List.of(extra));
        arguments.add(file.toString());
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(10), arguments),
                StandardCharsets.UTF_8);
        var result = new HashMap<String, String>();
        for (var line : output.split("\r?\n")) {
            var equals = line.indexOf('=');
            if (equals > 0) {
                result.put(line.substring(0, equals), line.substring(equals + 1).replace("\"", ""));
            }
        }
        return result;
    }

    /** Writes the bytes of a packet index into a payload: big-endian at its start and at its end. */
    private static byte[] marked(int size, long index) {
        var payload = new byte[size];
        ByteBuffer.wrap(payload).putLong(0, index + 1).putLong(size - 8, ~index);
        return payload;
    }

    private static void assertMarked(Packet packet, long index, String label) {
        var data = ByteBuffer.wrap(packet.data());
        assertEquals(index + 1, data.getLong(0), label + " start marker of packet " + index);
        assertEquals(~index, data.getLong(packet.size() - 8), label + " end marker of packet " + index);
    }

    /** The PCM track of the large files: 7.1, 192 kHz, 32 bits, 6 144 000 bytes a second. */
    private static AudioTrack pcm() {
        return AudioTrack.builder().id(1).codec(Codec.PCM_S32LE).timeBase(Rational.timeBaseOf(192_000))
                .sampleRate(192_000).layout(ChannelLayout.SURROUND_7_1).bitsPerSample(32).build();
    }

    /** Muxes {@code count} PCM packets of one second each, marked, into a sparse file. */
    private static SparseMedia largePcm(Function<MediaSink, Muxer> factory, int count) {
        var media = new SparseMedia();
        var track = pcm();
        try (var muxer = factory.apply(media.sink())) {
            muxer.addTrack(track);
            for (var index = 0; index < count; index++) {
                muxer.writePacket(new Packet(1, marked(6_144_000, index), index * 192_000L, index * 192_000L,
                        192_000, true));
            }
            muxer.finish();
        }
        return media;
    }

    /**
     * Reads every packet of a large file, checks that its payload, however the demuxer splits it, is the muxed byte
     * stream (the markers at the start and end of every muxed packet where they belong) with the timestamps of its
     * position, seeks into its last quarter, then compares the size and the duration with ffprobe's reading of the
     * saved sparse file.
     */
    private static void assertLarge(SparseMedia media, String name, int count, long ticksPerPacket) {
        assertTrue(media.size() > FOUR_GIB, name + " is " + media.size() + " bytes");
        var packetBytes = 6_144_000L;
        var bytesPerTick = packetBytes / ticksPerPacket;
        try (var demuxer = Containers.openDemuxer(media.source())) {
            var track = demuxer.tracks().getFirst();
            var offset = 0L;
            var markers = 0;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var pts = track.timeBase().rescale(packet.pts(), Rational.timeBaseOf(192_000));
                assertEquals(offset / bytesPerTick, pts, name + " pts of the payload at " + offset);
                var data = packet.data();
                var end = offset + data.length;
                // the markers the packet holds: the start and the end of a muxed packet
                for (var index = offset / packetBytes; index * packetBytes < end && index < count; index++) {
                    var start = index * packetBytes;
                    if (start >= offset && start + 8 <= end) {
                        assertEquals(index + 1, ByteBuffer.wrap(data).getLong((int) (start - offset)),
                                name + " start marker of packet " + index);
                        markers++;
                    }
                    var last = start + packetBytes - 8;
                    if (last >= offset && last + 8 <= end) {
                        assertEquals(~index, ByteBuffer.wrap(data).getLong((int) (last - offset)),
                                name + " end marker of packet " + index);
                        markers++;
                    }
                }
                offset = end;
            }
            assertEquals(packetBytes * count, offset, name + " payload bytes");
            assertTrue(markers >= count, name + ": " + markers + " markers found");
        }
        try (var demuxer = Containers.openDemuxer(media.source())) {
            // a seek into the last quarter lands on a packet past 4 GiB, at most one packet before its target
            var target = count * 3 / 4;
            demuxer.seek(target * 1_000_000L);
            var packet = demuxer.read();
            var seconds = demuxer.tracks().getFirst().timeBase().rescale(packet.pts(), Rational.timeBaseOf(1_000))
                    / 1000.0;
            assertTrue(seconds <= target && seconds > target - 1.5, name + " seek to " + target + " reads " + seconds);
        }
        var file = media.save(cache(name));
        try {
            var probe = ffprobe(file);
            assertEquals(Long.toString(media.size()), probe.get("format.size"), name + " size");
            assertTrue(Long.parseLong(probe.get("streams.stream.0.nb_read_packets")) >= count, name + " packets");
            assertEquals(count, Double.parseDouble(probe.get("format.duration")), 0.01, name + " duration");
        } finally {
            delete(file);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String fourCc(SparseMedia media, long position) {
        return new String(media.bytes(position, 4), StandardCharsets.ISO_8859_1);
    }

    @Nested
    @DisplayName("past 4 GiB")
    class Large {
        @Test
        @DisplayName("QuickTime: co64 chunk offsets, a 64-bit mdat, and a tag edit that moves the moov past 4 GiB")
        void quickTime() {
            var media = largePcm(sink -> new Mp4Muxer(sink, Mp4MuxerOptions.quickTime()), 760);
            var mdat = findTop(media, "mdat");
            assertTrue(mdat >= 0, "an mdat box");
            assertEquals(1, ByteBuffer.wrap(media.bytes(mdat, 4)).getInt(), "a 64-bit mdat size");
            assertTrue(ByteBuffer.wrap(media.bytes(mdat + 8, 8)).getLong() > FOUR_GIB);
            assertTrue(findBox(media, "co64") > 0, "a co64 box");
            assertLarge(media, "gen-mp4-co64-over4gib.mov", 760, 192_000);
            // a tag edit rewrites the moov after the mdat; every sample keeps its offset past 4 GiB
            var edited = new SparseMedia();
            TagEditor.write(media.source(), edited.sink(), MediaTags.builder().set(StandardTag.TITLE,
                    "Past four gibibytes").build(), TagWriteOptions.defaults());
            assertEquals("Past four gibibytes", TagEditor.read(edited.source()).get(StandardTag.TITLE).orElseThrow());
            assertLarge(edited, "gen-mp4-co64-over4gib-tagged.mov", 760, 192_000);
        }

        @Test
        @DisplayName("RF64: a WAVE file that outgrows 4 GiB switches its RIFF header to RF64 with a ds64 chunk")
        void rf64() {
            var media = largePcm(WavMuxer::new, 760);
            assertEquals("RF64", fourCc(media, 0));
            assertEquals("WAVE", fourCc(media, 8));
            assertEquals("ds64", fourCc(media, 12));
            var ds64 = ByteBuffer.wrap(media.bytes(20, 16)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertEquals(media.size() - 8, ds64.getLong(0), "the ds64 RIFF size");
            assertEquals(6_144_000L * 760, ds64.getLong(8), "the ds64 data size");
            assertLarge(media, "gen-rf64-over4gib.wav", 760, 192_000);
        }

        @Test
        @DisplayName("Wave64: 64-bit chunk sizes")
        void wave64() {
            var media = largePcm(Wave64Muxer::new, 760);
            assertEquals("riff", fourCc(media, 0));
            assertEquals(media.size(), ByteBuffer.wrap(media.bytes(16, 8)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .getLong(), "the riff size");
            assertLarge(media, "gen-w64-over4gib.w64", 760, 192_000);
        }

        @Test
        @DisplayName("CAF: a 64-bit data chunk and a packet table of 4 400 ALAC packets of 1 MiB")
        void caf() {
            var media = new SparseMedia();
            var config = ByteBuffer.allocate(24).putInt(4096).put((byte) 0).put((byte) 16).put((byte) 40)
                    .put((byte) 10).put((byte) 14).put((byte) 2).putShort((short) 255).putInt(0).putInt(0)
                    .putInt(44100).array();
            var track = AudioTrack.builder().id(1).codec(Codec.ALAC).timeBase(Rational.timeBaseOf(44100))
                    .sampleRate(44100).layout(ChannelLayout.STEREO).bitsPerSample(16).codecConfig(config).build();
            var count = 4400;
            try (var muxer = new CafMuxer(media.sink())) {
                muxer.addTrack(track);
                for (var index = 0; index < count; index++) {
                    muxer.writePacket(new Packet(1, marked((1 << 20) + index % 7, index), index * 4096L,
                            index * 4096L, 4096, true));
                }
                muxer.finish();
            }
            assertTrue(media.size() > FOUR_GIB);
            var data = findCafChunk(media, "data");
            assertTrue(ByteBuffer.wrap(media.bytes(data + 4, 8)).getLong() > FOUR_GIB, "the 64-bit data size");
            var pakt = findCafChunk(media, "pakt");
            assertEquals(count, ByteBuffer.wrap(media.bytes(pakt + 12, 8)).getLong(), "the packet table count");
            try (var demuxer = Containers.openDemuxer(media.source())) {
                var index = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    assertEquals((1 << 20) + index % 7, packet.size());
                    assertMarked(packet, index, "caf");
                    assertEquals(index * 4096L, packet.pts());
                    index++;
                }
                assertEquals(count, index);
            }
            var file = media.save(cache("gen-caf-over4gib.caf"));
            try {
                var probe = ffprobe(file);
                assertEquals(Integer.toString(count), probe.get("streams.stream.0.nb_read_packets"));
            } finally {
                delete(file);
            }
        }

        @Test
        @DisplayName("Matroska: 8-byte element sizes and cues that point past 4 GiB")
        void matroska() {
            var media = largePcm(MatroskaMuxer::new, 760);
            assertLarge(media, "gen-matroska-over4gib.mkv", 760, 192_000);
        }

        @Test
        @DisplayName("MPEG-TS: seeks into a stream past 4 GiB land on the packet of their time")
        void transportStream() throws Exception {
            // every transport packet holds a header, so this file takes its full size on disk; it is written once
            var file = cache("gen-ts-over4gib.ts");
            var frame = h264Frame();
            var frames = (int) (FOUR_GIB / frame.data().length) + 200;
            var track = frame.track();
            if (!Files.isRegularFile(file) || Files.size(file) <= FOUR_GIB) {
                try (var sink = MediaSink.of(file); var muxer = new TsMuxer(sink)) {
                    muxer.addTrack(track);
                    for (var index = 0; index < frames; index++) {
                        muxer.writePacket(new Packet(track.id(), frame.data(), index * 3600L, index * 3600L, 3600,
                                true));
                    }
                    muxer.finish();
                }
            }
            assertTrue(Files.size(file) > FOUR_GIB);
            var target = frames - 100;
            try (var source = MediaSource.of(file); var demuxer = new TsDemuxer(source)) {
                var base = demuxer.tracks().getFirst().timeBase();
                var start = demuxer.read().pts();
                demuxer.seek(target * 3600L * 1_000_000 / 90_000);
                var packet = demuxer.read();
                var time = base.rescale(packet.pts() - start, Rational.timeBaseOf(90_000));
                // at most a second before the target, never after it
                assertTrue(time <= target * 3600L && time > target * 3600L - 90_000, "the seek to " + target * 3600L
                        + " reads " + time);
                // a whole frame, with the parameter sets the muxer repeats before each key frame
                assertTrue(packet.size() >= frame.length() && packet.size() <= frame.length() + 256,
                        packet.size() + " bytes for a frame of " + frame.length());
            }
            // ffprobe finds the last timestamps past 4 GiB: the duration it reads at the end of the file is the frames'
            var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(5), List.of("-v", "error",
                    "-show_entries", "format=duration,size", "-of", "csv=p=0", file.toString())),
                    StandardCharsets.UTF_8).strip().split(",");
            assertEquals(frames * 0.04, Double.parseDouble(output[0]), 0.1, "ffprobe's duration");
            assertEquals(Files.size(file), Long.parseLong(output[1]));
        }

        @Test
        @DisplayName("AVI: OpenDML indx and ix## indexes and AVIX lists past the first gibibyte")
        void openDml() {
            var media = new SparseMedia();
            var count = 200;
            var track = pcm();
            try (var muxer = new AviMuxer(media.sink(), AviMuxerOptions.builder().riffSize(64L << 20).build())) {
                muxer.addTrack(track);
                for (var index = 0; index < count; index++) {
                    muxer.writePacket(new Packet(1, marked(6_144_000, index), index * 192_000L, index * 192_000L,
                            192_000, true));
                }
                muxer.finish();
            }
            assertTrue(media.size() > 1L << 30, "past 1 GiB");
            var avix = 0;
            for (var position = 0L; position + 12 <= media.size(); ) {
                var size = Integer.toUnsignedLong(ByteBuffer.wrap(media.bytes(position + 4, 4))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
                if (fourCc(media, position + 8).equals("AVIX")) {
                    avix++;
                }
                position += 8 + size + (size & 1);
            }
            assertTrue(avix >= 10, avix + " AVIX lists");
            try (var demuxer = Containers.openDemuxer(media.source())) {
                // the demuxer splits PCM chunks into packets of its own; the payload must be the muxed bytes
                var payload = 0L;
                var first = new byte[8];
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (payload < 8) {
                        System.arraycopy(packet.data(), 0, first, (int) payload, (int) Math.min(8 - payload,
                                packet.size()));
                    }
                    payload += packet.size();
                }
                assertEquals(6_144_000L * count, payload);
                assertEquals(1, ByteBuffer.wrap(first).getLong(), "the first marker");
            }
            var file = media.save(cache("gen-avi-opendml-over1gib.avi"));
            try {
                var probe = ffprobe(file);
                assertEquals(Long.toString(media.size()), probe.get("format.size"));
                assertEquals(count, Double.parseDouble(probe.get("format.duration")), 0.01);
            } finally {
                delete(file);
            }
        }
    }

    /** A track and one of its frames. */
    record Frame(Track track, byte[] data) {
        int length() {
            return data.length;
        }
    }

    /** Returns a 1080p H.264 IDR frame of about a megabyte and its track, encoded by ffmpeg. */
    private static Frame h264Frame() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var mp4 = cache("h264-idr-1080p.mp4");
        if (!Files.isRegularFile(mp4)) {
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                    "testsrc2=size=1920x1080:rate=25,noise=alls=60:allf=t", "-frames:v", "1", "-c:v", "libx264",
                    "-qp", "0", "-pix_fmt", "yuv420p", mp4.toString()));
        }
        var demuxed = ContainerProbe.demux(mp4, source -> Containers.openDemuxer(source));
        var track = ((VideoTrack) demuxed.tracks().getFirst()).toBuilder().id(256)
                .timeBase(Rational.timeBaseOf(90_000)).build();
        return new Frame(track, demuxed.order().getFirst().data());
    }

    /** Returns the offset of a top-level ISO box. */
    private static long findTop(SparseMedia media, String type) {
        for (var position = 0L; position + 8 <= media.size(); ) {
            var size = Integer.toUnsignedLong(ByteBuffer.wrap(media.bytes(position, 4)).getInt());
            if (size == 1) {
                size = ByteBuffer.wrap(media.bytes(position + 8, 8)).getLong();
            }
            if (fourCc(media, position + 4).equals(type)) {
                return position;
            }
            if (size < 8) {
                break;
            }
            position += size;
        }
        return -1;
    }

    /** Returns the offset of a box inside the moov, found by its type. */
    private static long findBox(SparseMedia media, String type) {
        var moov = findTop(media, "moov");
        var size = (int) Integer.toUnsignedLong(ByteBuffer.wrap(media.bytes(moov, 4)).getInt());
        var bytes = media.bytes(moov, size);
        var index = new String(bytes, StandardCharsets.ISO_8859_1).indexOf(type);
        return index < 0 ? -1 : moov + index;
    }

    /** Returns the offset of a CAF chunk, found by walking the chunks. */
    private static long findCafChunk(SparseMedia media, String type) {
        for (var position = 8L; position + 12 <= media.size(); ) {
            var size = ByteBuffer.wrap(media.bytes(position + 4, 8)).getLong();
            if (fourCc(media, position).equals(type)) {
                return position;
            }
            if (size < 0) {
                break;
            }
            position += 12 + size;
        }
        return -1;
    }

    @Nested
    @DisplayName("past a million frames")
    class ManyFrames {
        private static AudioTrack aac(int id) {
            return AudioTrack.builder().id(id).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO).codecConfig(AudioConfigs.audioSpecificConfig(2, 48000, 2, 0, false))
                    .build();
        }

        /** Muxes {@code count} AAC frames of a few bytes into memory. */
        private static byte[] frames(Function<MediaSink, Muxer> factory, int count) {
            var sink = MediaSink.memory();
            try (var muxer = factory.apply(sink)) {
                muxer.addTrack(aac(1));
                for (var index = 0; index < count; index++) {
                    muxer.writePacket(new Packet(1, new byte[]{(byte) 0x21, (byte) index, (byte) (index >> 8),
                            (byte) (index >> 16)}, index * 1024L, index * 1024L, 1024, true));
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }

        /** Reads every frame of a file and compares the count with ffprobe's. */
        private static void assertFrames(byte[] data, String name, int count) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                var index = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    var data2 = packet.data();
                    assertEquals(index & 0xFFFFFF, (data2[1] & 0xFF) | (data2[2] & 0xFF) << 8 | (data2[3] & 0xFF) << 16,
                            name + " frame " + index);
                    index++;
                }
                assertEquals(count, index, name + " frames");
            }
            var file = cache(name);
            try {
                Files.write(file, data);
                assertEquals(Integer.toString(count), ffprobe(file).get("streams.stream.0.nb_read_packets"), name);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                delete(file);
            }
        }

        @Test
        @DisplayName("MP4: stts, stsz and stco tables of more audio samples than the frame limit")
        void mp4() {
            assertFrames(frames(Mp4Muxer::new, MANY), "gen-mp4-over1m-frames.mp4", MANY);
        }

        @Test
        @DisplayName("Matroska: blocks and cues of more frames than the frame limit")
        void matroska() {
            assertFrames(frames(MatroskaMuxer::new, MANY), "gen-matroska-over1m-frames.mkv", MANY);
        }

        @Test
        @DisplayName("AVI: idx1 and indx entries of more chunks than the frame limit")
        void avi() {
            assertFrames(frames(AviMuxer::new, MANY), "gen-avi-over1m-frames.avi", MANY);
        }

        @Test
        @DisplayName("FLV: more tags than the frame limit and the keyframe index")
        void flv() {
            assertFrames(frames(FlvMuxer::new, MANY), "gen-flv-over1m-frames.flv", MANY);
        }

        @Test
        @DisplayName("IVF: a frame count past the frame limit, read with the limit raised")
        void ivf() {
            var track = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(Rational.timeBaseOf(30)).width(16)
                    .height(16).frameRate(new Rational(30, 1)).build();
            var sink = MediaSink.memory();
            try (var muxer = new IvfMuxer(sink)) {
                muxer.addTrack(track);
                for (var index = 0; index < MANY; index++) {
                    // a VP8 inter frame header: frame type 1, version 0, shown, the first partition empty
                    muxer.writePacket(new Packet(1, new byte[]{0x11, 0x00, 0x00, (byte) index}, index, index, 1,
                            false));
                }
                muxer.finish();
            }
            var data = sink.toByteArray();
            var raised = DemuxerOptions.builder().limits(MediaLimits.DEFAULT.toBuilder().maxFrames(2 * MANY).build())
                    .build();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data), raised)) {
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(MANY, count);
            }
            var file = cache("gen-ivf-over1m-frames.ivf");
            try {
                Files.write(file, data);
                assertEquals(Integer.toString(MANY), ffprobe(file).get("streams.stream.0.nb_read_packets"));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                delete(file);
            }
        }

        @Test
        @DisplayName("DV: a stream of more frames than the frame limit, streamed from one frame, seeks by frame")
        void dv() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var one = cache("dv-pal-frame.dv");
            if (!Files.isRegularFile(one)) {
                Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                        "testsrc2=size=720x576:rate=25", "-frames:v", "1", "-pix_fmt", "yuv420p", "-f", "dv",
                        one.toString()));
            }
            byte[] frame;
            try {
                frame = Files.readAllBytes(one);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            assertEquals(144_000, frame.length, "a PAL DV frame");
            var count = (long) MANY;
            var source = new MediaSource() {
                @Override
                public long size() {
                    return count * frame.length;
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    if (position >= size()) {
                        return -1;
                    }
                    var within = (int) (position % frame.length);
                    var chunk = Math.min(length, frame.length - within);
                    System.arraycopy(frame, within, buffer, offset, chunk);
                    return chunk;
                }

                @Override
                public void close() {
                }
            };
            var raised = DemuxerOptions.builder().limits(MediaLimits.DEFAULT.toBuilder().maxFrames(2 * MANY).build())
                    .build();
            try (var demuxer = new DvDemuxer(source, raised)) {
                var video = demuxer.tracks().getFirst();
                assertEquals(count * 40_000, demuxer.durationMicros(), 40_000, "the duration of a million frames");
                var target = count - 10;
                demuxer.seek(target * 40_000);
                var packet = demuxer.read();
                assertEquals(target, video.timeBase().rescale(packet.pts(), Rational.timeBaseOf(25)), "the frame");
                assertArrayEquals(frame, packet.data());
            }
        }
    }

    @Nested
    @DisplayName("many tracks and programs")
    class Many {
        private static byte[] tracks(Function<MediaSink, Muxer> factory) {
            var sink = MediaSink.memory();
            try (var muxer = factory.apply(sink)) {
                for (var id = 1; id <= 64; id++) {
                    muxer.addTrack(ManyFrames.aac(id));
                }
                for (var index = 0; index < 20; index++) {
                    for (var id = 1; id <= 64; id++) {
                        muxer.writePacket(new Packet(id, new byte[]{0x21, (byte) id, (byte) index}, index * 1024L,
                                index * 1024L, 1024, true));
                    }
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }

        private static void assertTracks(byte[] data, String name) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                assertEquals(64, demuxer.tracks().size());
                var counts = new HashMap<Integer, Integer>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    var id = packet.trackId();
                    var track = demuxer.tracks().stream().filter(candidate -> candidate.id() == id)
                            .findFirst().orElseThrow();
                    var order = demuxer.tracks().indexOf(track) + 1;
                    assertEquals(order, packet.data()[1], name + ": the packet of track " + order);
                    counts.merge(order, 1, Integer::sum);
                }
                for (var order = 1; order <= 64; order++) {
                    assertEquals(20, counts.getOrDefault(order, 0), name + " track " + order);
                }
            }
            var file = cache(name);
            try {
                Files.write(file, data);
                var probe = ffprobe(file);
                assertEquals("64", probe.get("format.nb_streams"));
                for (var index = 0; index < 64; index++) {
                    assertEquals("20", probe.get("streams.stream." + index + ".nb_read_packets"), name + " " + index);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                delete(file);
            }
        }

        @Test
        @DisplayName("Matroska: 64 tracks")
        void matroska() {
            assertTracks(tracks(MatroskaMuxer::new), "gen-matroska-64-tracks.mkv");
        }

        @Test
        @DisplayName("MP4: 64 tracks")
        void mp4() {
            assertTracks(tracks(Mp4Muxer::new), "gen-mp4-64-tracks.mp4");
        }

        @Test
        @DisplayName("MPEG-TS: 16 programs, each selected in turn, as ffmpeg writes them")
        void programs() throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = cache("gen-ts-16-programs.ts");
            var arguments = new ArrayList<>(List.of("-v", "error", "-y"));
            for (var index = 0; index < 16; index++) {
                arguments.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=" + (200 + 50 * index)
                        + ":sample_rate=48000:duration=1"));
            }
            for (var index = 0; index < 16; index++) {
                arguments.addAll(List.of("-map", index + ":a"));
            }
            arguments.addAll(List.of("-c:a", "mp2"));
            for (var index = 0; index < 16; index++) {
                arguments.addAll(List.of("-program", "program_num=" + (index + 1) + ":st=" + index));
            }
            arguments.addAll(List.of("-f", "mpegts", file.toString()));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
            try {
                assertEquals("16", ffprobe(file).get("format.nb_programs"));
                List<com.github.auties00.smalt.container.ts.TsProgram> programs;
                try (var source = MediaSource.of(file); var demuxer = new TsDemuxer(source)) {
                    programs = demuxer.programs();
                }
                assertEquals(16, programs.size());
                var probe = ContainerProbe.probe(file);
                for (var program : programs) {
                    assertEquals(1, program.elementaryPids().size(), "program " + program.number());
                    var pid = program.elementaryPids().getFirst();
                    var options = TsDemuxerOptions.builder().program(program.number()).build();
                    try (var source = MediaSource.of(file); var demuxer = new TsDemuxer(source, options)) {
                        assertEquals(List.of(pid), demuxer.tracks().stream().map(Track::id).toList());
                        var stream = probe.streams().stream().filter(candidate -> Long.decode(candidate.get("id")).longValue()
                                == pid).findFirst().orElseThrow();
                        var expected = probe.packetsOf(stream.index()).stream().map(ContainerProbe.ProbePacket::hash)
                                .toList();
                        var actual = new ArrayList<String>();
                        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                            actual.add(ContainerProbe.crc32(packet.data()));
                        }
                        assertEquals(expected, actual, "program " + program.number());
                    }
                }
            } finally {
                delete(file);
            }
        }
    }
}
