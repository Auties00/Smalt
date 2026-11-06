package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the final ALAC configuration that an encoder reports once flushed: {@code updateCodecConfig} stores it in
 * MP4 files (until the movie header is written) and in Matroska files on a seekable sink (in place, over the initial
 * {@code CodecPrivate}), keeps the initial one in Matroska streams, live Matroska and fragmented MP4, and rejects a
 * configuration describing another stream. The track is the ffmpeg-made sample {@code samples/mp4/alac.m4a}, whose
 * real configuration plays the final one; the initial one has the largest packet size and the average bitrate zeroed,
 * as an encoder declares them before the stream ends.
 */
@DisplayName("ALAC final configuration in MP4 and Matroska")
class AlacFinalConfigTest {
    record Source(AudioTrack track, List<Packet> packets, byte[] finalConfig) {
    }

    static Source source() throws IOException {
        var demuxed = MuxSupport.demux(Files.readAllBytes(Path.of("src", "test", "resources", "samples", "mp4",
                "alac.m4a")));
        var track = (AudioTrack) demuxed.tracks().getFirst();
        var finalConfig = AudioConfigs.alacConfig(track.codecConfig().orElseThrow()).clone();
        // a final configuration that differs from the initial one in both fields
        ByteBuffer.wrap(finalConfig).putInt(12, 4321).putInt(16, 654_321);
        var initial = finalConfig.clone();
        ByteBuffer.wrap(initial).putInt(12, 0).putInt(16, 0);
        return new Source(track.toBuilder().codecConfig(initial).bitsPerSample(0).build(), demuxed.packetsOf(track),
                finalConfig);
    }

    static void mux(Source source, Function<MediaSink, Muxer> factory, MediaSink sink, byte[] update) {
        try (var muxer = factory.apply(sink)) {
            muxer.addTrack(source.track());
            source.packets().forEach(muxer::writePacket);
            if (update != null) {
                muxer.updateCodecConfig(source.track().id(), update);
            }
            muxer.finish();
        }
    }

    static byte[] toMemory(Source source, Function<MediaSink, Muxer> factory, byte[] update) {
        var sink = MediaSink.memory();
        mux(source, factory, sink, update);
        return sink.toByteArray();
    }

    static byte[] config(byte[] file) {
        var track = MuxSupport.demux(file).tracks().getFirst();
        return AudioConfigs.alacConfig(track.codecConfig().orElseThrow());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"m4a", "mkv"})
    @DisplayName("stores the final ALACSpecificConfig, and the file decodes cleanly")
    void stored(String container) throws IOException {
        var source = source();
        Function<MediaSink, Muxer> factory = container.equals("m4a")
                ? sink -> new Mp4Muxer(sink, Mp4MuxerOptions.m4a()) : MatroskaMuxer::new;
        var output = toMemory(source, factory, source.finalConfig());
        assertArrayEquals(source.finalConfig(), config(output));
        MuxSupport.assertSamePayloads(MuxSupport.demux(toMemory(source, factory, null)), MuxSupport.demux(output),
                java.util.Map.of(source.track().id(), source.track().id()));
        MuxSupport.assertFfmpegClean("alac-final." + container, output);
    }

    @Test
    @DisplayName("writes the configuration's sample size into the MP4 sample entry of a track that declares none")
    void sampleSize() {
        var config = ByteBuffer.allocate(24).putInt(4096).put((byte) 0).put((byte) 24).put((byte) 40).put((byte) 10)
                .put((byte) 14).put((byte) 2).putShort((short) 255).putInt(0).putInt(0).putInt(48000).array();
        var track = AudioTrack.builder().id(1).codec(com.github.auties00.smalt.track.Codec.ALAC)
                .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(48000)).sampleRate(48000)
                .codecConfig(config).build();
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.m4a())) {
            muxer.addTrack(track);
            muxer.writePacket(new Packet(1, new byte[]{0x20, 0, 0, 0x10, 0x07}, 0, 0, 4096, true));
            muxer.finish();
        }
        var file = sink.toByteArray();
        var entry = -1;
        for (var index = 4; index + 4 <= file.length && entry < 0; index++) {
            if (file[index] == 'a' && file[index + 1] == 'l' && file[index + 2] == 'a' && file[index + 3] == 'c') {
                entry = index;
            }
        }
        // the first alac is the sample entry type; channel count and sample size follow 16 bytes of its fields
        assertEquals(2, ByteBuffer.wrap(file).getShort(entry + 20));
        assertEquals(24, ByteBuffer.wrap(file).getShort(entry + 22));
    }

    @Test
    @DisplayName("keeps the initial configuration in Matroska streams, live Matroska and fragmented MP4")
    void notStored() throws IOException {
        var source = source();
        var stream = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(stream)) {
            mux(source, MatroskaMuxer::new, sink, source.finalConfig());
        }
        var initial = AudioConfigs.alacConfig(source.track().codecConfig().orElseThrow());
        assertArrayEquals(initial, config(stream.toByteArray()));
        var live = MatroskaMuxerOptions.matroska().toBuilder().live(true).build();
        assertArrayEquals(initial, config(toMemory(source, sink -> new MatroskaMuxer(sink, live),
                source.finalConfig())));
        assertArrayEquals(initial, config(toMemory(source, FragmentedMp4Muxer::new, source.finalConfig())));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"m4a", "mkv"})
    @DisplayName("rejects a configuration of another stream or without an ALACSpecificConfig")
    void rejected(String container) throws IOException {
        var source = source();
        Function<MediaSink, Muxer> factory = container.equals("m4a")
                ? sink -> new Mp4Muxer(sink, Mp4MuxerOptions.m4a()) : MatroskaMuxer::new;
        for (var field : new int[]{3, 5, 9, 23}) {
            var other = source.finalConfig().clone();
            other[field]++;
            try (var muxer = factory.apply(MediaSink.memory())) {
                muxer.addTrack(source.track());
                assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(), other),
                        "field at " + field);
                assertThrows(IllegalArgumentException.class, () -> muxer.updateCodecConfig(source.track().id(),
                        new byte[10]));
            }
        }
    }
}
