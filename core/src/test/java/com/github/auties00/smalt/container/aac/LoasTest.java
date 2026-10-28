package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers standalone LOAS: {@link LoasMuxer} output compared byte for byte with ffmpeg's LATM muxer and decoded by
 * ffmpeg as its ADTS source, {@link LoasDemuxer} over ffmpeg's file and the muxer's own output (configuration
 * intervals, {@code audioMuxVersion} 1, configuration changes), recognition and malformed input.
 */
@DisplayName("LOAS")
class LoasTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    static ContainerProbe.Demuxed adts() {
        return ContainerProbe.demux(SAMPLES.resolve("adts/aac.aac"), AdtsDemuxer::new);
    }

    static ContainerProbe.Demuxed read(byte[] file) {
        try (var demuxer = new LoasDemuxer(MediaSource.of(file))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    static void assertSamePayloads(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual) {
        assertEquals(expected.order().size(), actual.order().size(), "packet count");
        for (var index = 0; index < expected.order().size(); index++) {
            assertArrayEquals(expected.order().get(index).data(), actual.order().get(index).data(), "packet " + index);
            assertEquals(1024L * index, actual.order().get(index).pts(), "pts of packet " + index);
        }
    }

    /** Returns the offsets of the AudioSyncStream frames of a stream. */
    static List<Integer> frames(byte[] stream) {
        var offsets = new ArrayList<Integer>();
        var offset = 0;
        while (offset < stream.length) {
            assertEquals(0x56, stream[offset] & 0xFF, "sync at " + offset);
            assertEquals(0xE0, stream[offset + 1] & 0xE0, "sync at " + offset);
            offsets.add(offset);
            offset += 3 + ((stream[offset + 1] & 0x1F) << 8 | stream[offset + 2] & 0xFF);
        }
        assertEquals(stream.length, offset);
        return offsets;
    }

    @Test
    @DisplayName("writes the bytes ffmpeg's LATM muxer writes for the same raw data blocks")
    void ffmpegBytes() throws Exception {
        var input = adts();
        var output = MuxSupport.toStream(List.of(input.tracks().getFirst()), input.order(), LoasMuxer::new);
        assertArrayEquals(Files.readAllBytes(SAMPLES.resolve("loas/aac.loas")), output);
    }

    @Test
    @DisplayName("reads ffmpeg's file as the payloads and configuration of its ADTS source")
    void ffmpegFile() {
        var input = adts();
        var result = ContainerProbe.demux(SAMPLES.resolve("loas/aac.loas"), LoasDemuxer::new);
        assertSamePayloads(input, result);
        assertArrayEquals(input.tracks().getFirst().codecConfig().orElseThrow(),
                result.tracks().getFirst().codecConfig().orElseThrow());
        assertEquals(input.order().size() * 1024L, ((AudioTrack) result.tracks().getFirst()).duration());
    }

    @ParameterizedTest(name = "every {0} frames")
    @ValueSource(ints = {1, 7, 20, 1000})
    @DisplayName("carries the configuration at its interval, and ffmpeg decodes the stream as its ADTS source")
    void intervals(int interval) {
        var input = adts();
        var options = LoasMuxerOptions.builder().configInterval(interval).build();
        var output = MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(),
                sink -> new LoasMuxer(sink, options));
        var offsets = frames(output);
        for (var index = 0; index < offsets.size(); index++) {
            var useSame = (output[offsets.get(index) + 3] & 0x80) != 0;
            assertEquals(index % interval != 0, useSame, "useSameStreamMux of frame " + index);
        }
        assertSamePayloads(input, read(output));
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = MuxSupport.save("loas-" + interval + ".loas", output);
        assertEquals("loas", ContainerProbe.probe(file).format().get("format_name"));
        assertArrayEquals(Oracle.decodeAudio(SAMPLES.resolve("adts/aac.aac")), Oracle.decodeAudio(file));
    }

    @Test
    @DisplayName("writes audioMuxVersion 1 for a configuration with an explicit backward-compatible SBR signal")
    void version1() {
        var input = adts();
        var core = input.tracks().getFirst().codecConfig().orElseThrow();
        // LC at 44.1 kHz stereo, then the sync extension 0x2B7, object type 5 (SBR) present at 88.2 kHz
        var explicit = new byte[]{core[0], core[1], 0x56, (byte) 0xE5, (byte) 0x88};
        var track = ((AudioTrack) input.tracks().getFirst()).toBuilder().codecConfig(explicit).build();
        var output = MuxSupport.toMemory(List.of(track), input.order(), LoasMuxer::new);
        assertEquals(0x40, output[3] & 0xC0, "useSameStreamMux 0, audioMuxVersion 1");
        var result = read(output);
        assertEquals(input.order().size(), result.order().size());
        for (var index = 0; index < input.order().size(); index++) {
            assertArrayEquals(input.order().get(index).data(), result.order().get(index).data());
        }
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assertFfmpegDecodes(MuxSupport.save("loas-version1.loas", output));
    }

    @Test
    @DisplayName("announces a configuration change in the frame that carries it")
    void configurationChange() {
        var input = adts();
        var track = (AudioTrack) input.tracks().getFirst();
        var mono = AudioConfigs.audioSpecificConfig(2, 44_100, 1, 0, false);
        var packets = new ArrayList<>(input.order());
        packets.set(10, packets.get(10).withSideData(PacketSideData.builder().newCodecConfig(mono).build()));
        var output = MuxSupport.toMemory(List.of(track), packets, LoasMuxer::new);
        var offsets = frames(output);
        assertEquals(0, output[offsets.get(10) + 3] & 0x80, "the changed frame carries its configuration");
        var result = read(output);
        assertArrayEquals(mono, result.order().get(10).sideData().newCodecConfig().orElseThrow());
        assertTrue(result.order().get(9).sideData().newCodecConfig().isEmpty());
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {
        @Test
        @DisplayName("refuses tracks without an AudioSpecificConfig or of an object type LOAS readers cannot delimit")
        void tracks() {
            var aac = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).build();
            assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(aac));
            var eld = aac.toBuilder().codecConfig(new byte[]{(byte) 0xF8, (byte) 0xE6, 0x50, 0x00}).build();
            assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(eld));
            var mp3 = aac.toBuilder().codec(Codec.MP3).build();
            assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(mp3));
            assertFalse(LoasMuxer.supports(Codec.MP3));
            assertThrows(IllegalArgumentException.class, () -> LoasMuxerOptions.builder().configInterval(0));
        }

        @Test
        @DisplayName("refuses a payload larger than a frame")
        void largePayload() {
            var input = adts();
            try (var muxer = new LoasMuxer(MediaSink.of(new ByteArrayOutputStream()))) {
                muxer.addTrack(input.tracks().getFirst());
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(input.tracks()
                        .getFirst().id(), new byte[8192], 0, 0, 1024, true)));
            }
        }
    }

    @Nested
    @DisplayName("recognition")
    class Recognition {
        @Test
        @DisplayName("recognizes LOAS by two frames, also after an ID3v2 tag whose text becomes metadata")
        void detection() throws Exception {
            var loas = Files.readAllBytes(SAMPLES.resolve("loas/aac.loas"));
            assertEquals(ContainerFormat.LOAS, Containers.detect(MediaSource.of(loas)).orElseThrow());
            var title = "Rain".getBytes(StandardCharsets.ISO_8859_1);
            var frame = new byte[10 + 1 + title.length];
            System.arraycopy("TIT2".getBytes(StandardCharsets.US_ASCII), 0, frame, 0, 4);
            frame[7] = (byte) (1 + title.length);
            System.arraycopy(title, 0, frame, 11, title.length);
            var tag = new byte[10 + frame.length];
            System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, tag, 0, 3);
            tag[3] = 3;
            tag[9] = (byte) frame.length;
            System.arraycopy(frame, 0, tag, 10, frame.length);
            var tagged = new byte[tag.length + loas.length];
            System.arraycopy(tag, 0, tagged, 0, tag.length);
            System.arraycopy(loas, 0, tagged, tag.length, loas.length);
            assertEquals(ContainerFormat.LOAS, Containers.detect(MediaSource.of(tagged)).orElseThrow());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(tagged))) {
                assertInstanceOf(LoasDemuxer.class, demuxer);
                assertEquals("Rain", demuxer.metadata().get("TIT2"));
                assertSamePayloads(adts(), ContainerProbe.demuxAll(demuxer));
            }
            var lone = Arrays.copyOf(loas, 3 + ((loas[1] & 0x1F) << 8 | loas[2] & 0xFF));
            assertEquals(ContainerFormat.LOAS, Containers.detect(MediaSource.of(lone)).orElseThrow(),
                    "one frame that ends the input");
            var broken = loas.clone();
            broken[lone.length] = 0;
            assertTrue(Containers.detect(MediaSource.of(broken)).filter(ContainerFormat.LOAS::equals).isEmpty(),
                    "a first frame that does not lead to a second");
        }

        @Test
        @DisplayName("rejects a stream without a frame of a supported configuration")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> new LoasDemuxer(MediaSource.of(new byte[100])));
        }

        @Test
        @DisplayName("seeks on a source of known size and refuses to on a stream")
        void seeking() throws Exception {
            var loas = Files.readAllBytes(SAMPLES.resolve("loas/aac.loas"));
            try (var demuxer = new LoasDemuxer(MediaSource.of(loas))) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(500_000);
                var packet = demuxer.read();
                assertTrue(packet.pts() <= 22_050 && packet.pts() + packet.duration() > 22_050, "pts " + packet.pts());
            }
        }
    }

    @Test
    @DisplayName("is opened by Containers")
    void containers() {
        assertInstanceOf(LoasMuxer.class, Containers.openMuxer(ContainerFormat.LOAS, MediaSink.memory()));
        assertTrue(Containers.isMuxable(ContainerFormat.LOAS, Codec.AAC));
        assertEquals("loas", ContainerFormat.LOAS.shortName());
    }
}
