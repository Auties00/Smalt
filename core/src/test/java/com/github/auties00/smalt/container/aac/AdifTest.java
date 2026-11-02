package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.internal.AacRawBlocks;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers standalone ADIF: {@link AdifMuxer} output compared byte for byte with the ADIF file the sample script
 * assembles independently from the same ADTS frames, {@link AdifDemuxer} returning the raw data blocks of that file
 * with a configuration ffmpeg decodes exactly as the ADTS source (ffmpeg reads no ADIF file, so the blocks are
 * carried into MP4), program configuration elements of every channel configuration, and malformed input.
 */
@DisplayName("ADIF")
class AdifTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    static ContainerProbe.Demuxed adts() {
        return ContainerProbe.demux(SAMPLES.resolve("adts/aac.aac"), AdtsDemuxer::new);
    }

    static ContainerProbe.Demuxed read(byte[] file) {
        try (var demuxer = new AdifDemuxer(MediaSource.of(file))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    @Test
    @DisplayName("writes the bytes of the independently assembled ADIF file")
    void sampleBytes() throws Exception {
        var input = adts();
        var output = MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(), AdifMuxer::new);
        assertArrayEquals(Files.readAllBytes(SAMPLES.resolve("adif/aac.adif")), output);
    }

    @Test
    @DisplayName("reads the blocks of the ADIF sample, which ffmpeg decodes as the ADTS source once carried into MP4")
    void sampleFile() {
        var input = adts();
        var result = ContainerProbe.demux(SAMPLES.resolve("adif/aac.adif"), AdifDemuxer::new);
        assertEquals(input.order().size(), result.order().size());
        for (var index = 0; index < input.order().size(); index++) {
            assertArrayEquals(input.order().get(index).data(), result.order().get(index).data(), "block " + index);
            assertEquals(1024L * index, result.order().get(index).pts());
        }
        var track = (AudioTrack) result.tracks().getFirst();
        assertEquals(44_100, track.sampleRate());
        assertEquals(ChannelLayout.STEREO, track.layout().orElseThrow());
        assertEquals(2, AacRawBlocks.equivalentConfiguration(track.codecConfig().orElseThrow()));
        var mp4 = MuxSupport.toMemory(List.of(track), result.order(), Mp4Muxer::new);
        Oracle.assume(Oracle.Tool.FFMPEG);
        assertArrayEquals(Oracle.decodeAudio(SAMPLES.resolve("adts/aac.aac")),
                Oracle.decodeAudio(MuxSupport.save("adif-blocks.mp4", mp4)));
    }

    @ParameterizedTest(name = "channel configuration {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    @DisplayName("declares the elements of each channel configuration in a program configuration element")
    void configurations(int configuration) {
        var input = adts();
        var config = AudioConfigs.audioSpecificConfig(2, 48_000, configuration, 0, false);
        var track = ((AudioTrack) input.tracks().getFirst()).toBuilder().codecConfig(config).sampleRate(48_000)
                .layout(AudioConfigs.aacLayout(configuration)).build();
        var output = MuxSupport.toMemory(List.of(track), input.order().subList(0, 4), AdifMuxer::new);
        try (var demuxer = new AdifDemuxer(MediaSource.of(output))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(configuration, AacRawBlocks.equivalentConfiguration(read.codecConfig().orElseThrow()));
            assertEquals(AudioConfigs.aacLayout(configuration), read.layout().orElseThrow());
            assertEquals(48_000, read.sampleRate());
            assertEquals(4, ContainerProbe.demuxAll(demuxer).order().size());
        }
    }

    @Test
    @DisplayName("copies the program configuration element of a configuration of channel configuration 0")
    void programConfig() {
        var result = ContainerProbe.demux(SAMPLES.resolve("adif/aac.adif"), AdifDemuxer::new);
        var track = (AudioTrack) result.tracks().getFirst();
        var output = MuxSupport.toMemory(List.of(track), result.order(), AdifMuxer::new);
        var again = read(output);
        assertArrayEquals(track.codecConfig().orElseThrow(), again.tracks().getFirst().codecConfig().orElseThrow());
        assertEquals(result.order().size(), again.order().size());
    }

    @Test
    @DisplayName("writes the peak bit rate on a seekable sink and zero on a stream")
    void bitrate() {
        var input = adts();
        var largest = input.order().stream().mapToInt(Packet::size).max().orElseThrow();
        var seekable = MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(), AdifMuxer::new);
        var bits = ((seekable[4] & 0x0F) << 19) | ((seekable[5] & 0xFF) << 11) | ((seekable[6] & 0xFF) << 3)
                | ((seekable[7] & 0xFF) >>> 5);
        assertEquals(largest * 8L * 44_100 / 1024, bits);
        var stream = MuxSupport.toStream(List.of(input.tracks().getFirst()), input.order(), AdifMuxer::new);
        assertEquals(0, ((stream[4] & 0x0F) << 19) | ((stream[5] & 0xFF) << 11) | ((stream[6] & 0xFF) << 3)
                | ((stream[7] & 0xFF) >>> 5));
        assertArrayEquals(Arrays.copyOfRange(seekable, 8, seekable.length), Arrays.copyOfRange(stream, 8,
                stream.length));
    }

    @Test
    @DisplayName("refuses what an ADIF header cannot describe")
    void refusals() {
        var input = adts();
        var track = (AudioTrack) input.tracks().getFirst();
        // AAC-LD, 960-sample frames, an escaped sampling rate and channel configuration 8
        for (var config : List.of(AudioConfigs.audioSpecificConfig(23, 48_000, 2, 0, false), new byte[]{0x12, 0x14},
                new byte[]{0x17, (byte) 0x80, 0x00, 0x00, 0x10}, new byte[]{0x12, 0x40})) {
            var bad = track.toBuilder().codecConfig(config).build();
            assertThrows(IllegalArgumentException.class, () -> new AdifMuxer(MediaSink.memory()).addTrack(bad),
                    Arrays.toString(config));
        }
        try (var muxer = new AdifMuxer(MediaSink.of(new ByteArrayOutputStream()))) {
            muxer.addTrack(track);
            muxer.writePacket(input.order().getFirst());
            var change = input.order().get(1).withSideData(PacketSideData.builder()
                    .newCodecConfig(AudioConfigs.audioSpecificConfig(2, 44_100, 1, 0, false)).build());
            assertThrows(MediaException.Unsupported.class, () -> muxer.writePacket(change));
        }
    }

    @Test
    @DisplayName("is recognized by its magic, also after an ID3v2 tag, and rejects other streams")
    void recognition() throws Exception {
        var adif = Files.readAllBytes(SAMPLES.resolve("adif/aac.adif"));
        assertEquals(ContainerFormat.ADIF, Containers.detect(MediaSource.of(adif)).orElseThrow());
        var tag = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        var tagged = new byte[tag.length + adif.length];
        System.arraycopy(tag, 0, tagged, 0, tag.length);
        System.arraycopy(adif, 0, tagged, tag.length, adif.length);
        assertEquals(ContainerFormat.ADIF, Containers.detect(MediaSource.of(tagged)).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(tagged))) {
            assertInstanceOf(AdifDemuxer.class, demuxer);
            assertEquals(adts().order().size(), ContainerProbe.demuxAll(demuxer).order().size());
        }
        assertThrows(MediaException.Malformed.class, () -> new AdifDemuxer(MediaSource.of(
                Files.readAllBytes(SAMPLES.resolve("adts/aac.aac")))));
        for (var length = 0; length < 64; length++) {
            var prefix = Arrays.copyOf(adif, length);
            try (var demuxer = new AdifDemuxer(MediaSource.of(prefix))) {
                while (demuxer.read() != null) {
                    assertTrue(true);
                }
            } catch (MediaException expected) {
                assertInstanceOf(MediaException.class, expected);
            }
        }
        assertInstanceOf(AdifMuxer.class, Containers.openMuxer(ContainerFormat.ADIF, MediaSink.memory()));
    }

    @Test
    @DisplayName("writes a header alone for a stream without packets")
    void empty() {
        var input = adts();
        var output = MuxSupport.toMemory(List.of(input.tracks().getFirst()), new ArrayList<>(), AdifMuxer::new);
        assertTrue(AacRawBlocks.isAdif(output, 0, output.length));
        var adif = AacRawBlocks.readAdif(output, 0, output.length);
        assertEquals(output.length, adif.length());
        assertEquals(2, adif.objectType());
        assertEquals(2, adif.channels());
    }
}
