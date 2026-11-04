package com.github.auties00.smalt.audio.behavior;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes a chained Ogg stream of a Vorbis, an Opus and a FLAC link read once with an unknown length, whose later
 * links the demuxer describes only as it reaches them: a caller that creates an {@link AudioStreamDecoder} for each
 * track when it meets the track's first packet gets, bit for bit, the samples of a decode of the same bytes from
 * memory, where every link is described at construction, and {@link AudioReader} reads the first link as from
 * memory.
 */
@DisplayName("decoding a chained Ogg stream read once")
class ChainedStreamDecodeTest {
    static byte[] chain() {
        var output = new ByteArrayOutputStream();
        output.writeBytes(Fixtures.read(Fixtures.tone("ogg", 440, 44_100, 2, 0.8, List.of("-c:a", "libvorbis",
                "-serial_offset", "100"))));
        output.writeBytes(Fixtures.read(Fixtures.tone("ogg", 660, 48_000, 1, 0.7, List.of("-c:a", "libopus",
                "-serial_offset", "200"))));
        output.writeBytes(Fixtures.read(Fixtures.tone("ogg", 880, 32_000, 2, 0.6, List.of("-c:a", "flac",
                "-serial_offset", "300"))));
        return output.toByteArray();
    }

    /** Decodes every track, creating its decoder when its first packet arrives, and returns its samples. */
    static Map<Integer, float[][]> decodeAll(Supplier<MediaSource> source) {
        var decoders = new LinkedHashMap<Integer, AudioStreamDecoder>();
        var samples = new LinkedHashMap<Integer, float[][]>();
        try (var input = source.get(); var demuxer = new OggDemuxer(input)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var decoder = decoders.get(packet.trackId());
                if (decoder == null) {
                    var id = packet.trackId();
                    var track = (AudioTrack) demuxer.tracks().stream().filter(candidate -> candidate.id() == id)
                            .findFirst().orElseThrow();
                    decoder = AudioStreamDecoder.create(track);
                    decoders.put(id, decoder);
                }
                decoder.decode(packet);
            }
            for (var entry : decoders.entrySet()) {
                var decoder = entry.getValue();
                decoder.finish();
                var channels = decoder.layout().channels();
                var planes = new ArrayList<float[]>();
                for (var channel = 0; channel < channels; channel++) {
                    planes.add(new float[0]);
                }
                var block = new float[channels][4096];
                int read;
                while ((read = decoder.read(block, 0, block[0].length)) > 0) {
                    for (var channel = 0; channel < channels; channel++) {
                        var previous = planes.get(channel);
                        var grown = java.util.Arrays.copyOf(previous, previous.length + read);
                        System.arraycopy(block[channel], 0, grown, previous.length, read);
                        planes.set(channel, grown);
                    }
                }
                samples.put(entry.getKey(), planes.toArray(float[][]::new));
                decoder.close();
            }
        }
        return samples;
    }

    @Test
    @DisplayName("decodes every link of the stream exactly as from memory")
    void everyLink() {
        var file = chain();
        var expected = decodeAll(() -> MediaSource.of(file));
        var actual = decodeAll(() -> MediaSource.of(new ByteArrayInputStream(file), -1));
        assertEquals(3, expected.size());
        assertEquals(expected.keySet(), actual.keySet());
        for (var id : expected.keySet()) {
            assertEquals(expected.get(id).length, actual.get(id).length);
            assertTrue(expected.get(id)[0].length > 10_000, "link " + id + " decoded too few samples");
            for (var channel = 0; channel < expected.get(id).length; channel++) {
                assertArrayEquals(expected.get(id)[channel], actual.get(id)[channel], "track " + id + " channel " + channel);
            }
        }
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            assertEquals(List.of(Codec.VORBIS, Codec.OPUS, Codec.FLAC),
                    demuxer.tracks().stream().map(track -> track.codec()).toList());
        }
    }

    @Test
    @DisplayName("reads the first link with AudioReader as from memory")
    void reader() {
        var file = chain();
        assertArrayEquals(read(MediaSource.of(file)), read(MediaSource.of(new ByteArrayInputStream(file), -1)));
    }

    static float[] read(MediaSource source) {
        var samples = new ArrayList<Float>();
        try (source; var reader = AudioReader.open(source)) {
            for (var block = reader.read(); block != null; block = reader.read()) {
                for (var index = 0; index < block.frames(); index++) {
                    samples.add(block.plane(0)[index]);
                }
            }
        }
        var result = new float[samples.size()];
        for (var index = 0; index < result.length; index++) {
            result[index] = samples.get(index);
        }
        return result;
    }
}
