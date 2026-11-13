package com.github.auties00.smalt.audio.dst;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.dsd.DsdiffDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the DST streams of the ISO/IEC 14496-26 conformance suite (MP4 files of audio object type 35, at 64, 128
 * and 256 times 44.1 kHz, in up to 64 channels) and compares every DSD bit with the plain DSD of the suite's
 * reference streams; wraps the frames into DSDIFF files, with and without CRC chunks, to check the DSDIFF demuxer,
 * the CRC verification and seeking; and decodes FFmpeg's FATE DSDIFF file.
 */
@DisplayName("DST decoder")
class DstConformanceTest {
    static Stream<String> smoke() {
        return Conformance.archive("mpeg4audio-dst-compressed-smoke").members().stream();
    }

    static Stream<String> full() {
        return Conformance.archive("mpeg4audio-dst-compressed").members().stream();
    }

    static String file(String member) {
        return member.substring(member.lastIndexOf('/') + 1);
    }

    record Stream_(AudioTrack track, List<Packet> packets) {
    }

    static Stream_ read(byte[] data) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Stream_(track, packets(demuxer));
        }
    }

    static List<Packet> packets(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            packets.add(packet);
        }
        return packets;
    }

    static byte[] dsd(Stream_ stream, AudioDecoderOptions options) {
        var decoder = new DstDecoder(stream.track(), options);
        var out = new ByteArrayOutputStream();
        var frame = new byte[decoder.frameBytes()];
        for (var packet : stream.packets()) {
            decoder.decodeDsd(packet, frame, 0);
            out.write(frame, 0, frame.length);
        }
        return out.toByteArray();
    }

    static void conformance(String set, String referenceSet, String member) throws IOException {
        var name = file(member);
        var compressed = read(Files.readAllBytes(Conformance.archive(set).fetch(member)));
        var referenceMember = Conformance.archive(referenceSet).members().stream()
                .filter(m -> file(m).equals(name)).findFirst().orElseThrow();
        var reference = read(Files.readAllBytes(Conformance.archive(referenceSet).fetch(referenceMember)));
        assertEquals(Codec.DST, compressed.track().codec());
        assertEquals(Codec.DST, reference.track().codec());
        assertEquals(compressed.track().sampleRate(), reference.track().sampleRate());
        assertEquals(compressed.packets().size(), reference.packets().size());
        Tolerance.exact().assertWithin(name, dsd(reference, AudioDecoderOptions.defaults()),
                dsd(compressed, AudioDecoderOptions.defaults()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smoke")
    @DisplayName("decodes the smoke streams bit for bit to the reference DSD")
    void streams(String member) throws IOException {
        conformance("mpeg4audio-dst-compressed-smoke", "mpeg4audio-dst-references-smoke", member);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("full")
    @DisplayName("decodes every conformance stream bit for bit to the reference DSD")
    void allStreams(String member) throws IOException {
        conformance("mpeg4audio-dst-compressed", "mpeg4audio-dst-references", member);
    }

    /**
     * Writes a DSDIFF file of DST frames, optionally followed each by the CRC chunk of its decoded DSD.
     */
    static byte[] dsdiff(int rate, int channels, List<Packet> frames, List<byte[]> crcs) {
        var sound = new ByteArrayOutputStream();
        chunk(sound, "FRTE", be(frames.size(), 4, 75, 2));
        for (var i = 0; i < frames.size(); i++) {
            chunk(sound, "DSTF", frames.get(i).data());
            if (crcs != null) {
                chunk(sound, "DSTC", crcs.get(i));
            }
        }
        var channelIds = new ByteArrayOutputStream();
        channelIds.write(channels >> 8);
        channelIds.write(channels);
        for (var c = 0; c < channels; c++) {
            var id = channels == 2 ? (c == 0 ? "SLFT" : "SRGT") : String.format("C%03d", c);
            channelIds.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        }
        var properties = new ByteArrayOutputStream();
        properties.writeBytes("SND ".getBytes(StandardCharsets.US_ASCII));
        chunk(properties, "FS  ", be(rate, 4));
        chunk(properties, "CHNL", channelIds.toByteArray());
        var compression = new ByteArrayOutputStream();
        compression.writeBytes("DST ".getBytes(StandardCharsets.US_ASCII));
        compression.write(11);
        compression.writeBytes("DST Encoded".getBytes(StandardCharsets.US_ASCII));
        chunk(properties, "CMPR", compression.toByteArray());
        var form = new ByteArrayOutputStream();
        form.writeBytes("DSD ".getBytes(StandardCharsets.US_ASCII));
        chunk(form, "FVER", be(0x01050000, 4));
        chunk(form, "PROP", properties.toByteArray());
        chunk(form, "DST ", sound.toByteArray());
        var file = new ByteArrayOutputStream();
        chunk(file, "FRM8", form.toByteArray());
        return file.toByteArray();
    }

    static byte[] be(long... valuesAndSizes) {
        var out = new ByteArrayOutputStream();
        for (var i = 0; i < valuesAndSizes.length; i += 2) {
            var value = valuesAndSizes[i];
            var size = (int) valuesAndSizes[i + 1];
            for (var k = size - 1; k >= 0; k--) {
                out.write((int) (value >> (8 * k)));
            }
        }
        return out.toByteArray();
    }

    static byte[] be(long value, int size) {
        return be(new long[]{value, size});
    }

    static void chunk(ByteArrayOutputStream out, String id, byte[] body) {
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(be(body.length, 8));
        out.writeBytes(body);
        if ((body.length & 1) != 0) {
            out.write(0);
        }
    }

    static Stream_ smokeStream(String name) throws IOException {
        var archive = Conformance.archive("mpeg4audio-dst-compressed-smoke");
        var member = archive.members().stream().filter(m -> file(m).equals(name)).findFirst().orElseThrow();
        return read(Files.readAllBytes(archive.fetch(member)));
    }

    @Test
    @DisplayName("reads DST frames from DSDIFF, verifies their CRCs and rejects a wrong one")
    void dsdiffFrames() throws IOException {
        var stream = smokeStream("DST_00_01_064.mp4");
        var channels = stream.track().layout().orElseThrow().channels();
        var decoded = dsd(stream, AudioDecoderOptions.defaults());
        var frameBytes = decoded.length / stream.packets().size();
        var crcs = new ArrayList<byte[]>();
        for (var i = 0; i < stream.packets().size(); i++) {
            crcs.add(be(DstDecoder.crc(decoded, i * frameBytes, frameBytes), 4));
        }
        var file = dsdiff(stream.track().sampleRate(), channels, stream.packets(), crcs);
        var verify = AudioDecoderOptions.builder().verifyIntegrity(true).build();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.DSDIFF, demuxer.format());
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.DST, track.codec());
            assertEquals((long) stream.packets().size() * stream.track().sampleRate() / 75, track.duration());
            var packets = packets(demuxer);
            assertTrue(packets.getFirst().sideData().extra(DsdiffDemuxer.CRC_KEY).isPresent());
            Tolerance.exact().assertWithin("DSDIFF DST", decoded, dsd(new Stream_(track, packets), verify));
            var damaged = new ArrayList<>(packets);
            var first = packets.getFirst();
            var crc = first.sideData().extra(DsdiffDemuxer.CRC_KEY).orElseThrow().clone();
            crc[3] ^= 1;
            damaged.set(0, first.withSideData(PacketSideData.builder().extra(DsdiffDemuxer.CRC_KEY, crc).build()));
            assertThrows(MediaException.Malformed.class, () -> dsd(new Stream_(track, damaged), verify));
        }
    }

    @Test
    @DisplayName("seeks a DSDIFF DST file to the frame holding the target")
    void dsdiffSeek() throws IOException {
        var stream = smokeStream("DST_00_01_128.mp4");
        var channels = stream.track().layout().orElseThrow().channels();
        var file = dsdiff(stream.track().sampleRate(), channels, stream.packets(), null);
        try (var demuxer = new DsdiffDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var frame = track.sampleRate() / 75;
            demuxer.seek(track.timeBase().toMicros(5L * frame + frame / 2));
            var packet = demuxer.read();
            assertEquals(5L * frame, packet.pts());
            assertEquals(stream.packets().get(5).data().length, packet.data().length);
            demuxer.seek(demuxer.durationMicros() + 1);
            assertEquals(null, demuxer.read());
        }
    }

    @Test
    @DisplayName("rejects a frame whose arithmetic code is damaged")
    void damaged() throws IOException {
        var stream = smokeStream("DST_00_01_064.mp4");
        var decoder = new DstDecoder(stream.track());
        var packet = stream.packets().getFirst();
        var data = packet.data().clone();
        assertTrue(data.length > 64);
        data[data.length - 3] ^= 0x55;
        data[data.length / 2] ^= 0x55;
        var damaged = new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true);
        var reference = new byte[decoder.frameBytes()];
        decoder.decodeDsd(packet, reference, 0);
        var out = new byte[decoder.frameBytes()];
        try {
            decoder.decodeDsd(damaged, out, 0);
            assertTrue(!java.util.Arrays.equals(reference, out), "damage must change the output or be rejected");
        } catch (MediaException.Malformed expected) {
            // the usual outcome: the arithmetic decoder does not end where the frame does
        }
    }

    @Test
    @DisplayName("decodes FFmpeg's FATE DSDIFF file to PCM through AudioDecoders")
    void fate() throws IOException {
        var data = Files.readAllBytes(Conformance.fetchListed("fate-dst-64fs44-2ch.dff"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            assertEquals(ContainerFormat.DSDIFF, demuxer.format());
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.DST, track.codec());
            assertEquals(2822400, track.sampleRate());
            try (var decoder = AudioDecoders.create(track)) {
                assertEquals(176400, decoder.sampleRate());
                var samples = new long[1];
                Packet packet;
                var frames = 0;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet, block -> samples[0] += block.frames());
                    frames++;
                }
                decoder.flush(block -> samples[0] += block.frames());
                assertEquals(10, frames);
                assertEquals(frames * 37632L / 16, samples[0]);
            }
        }
    }
}
