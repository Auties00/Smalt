package com.github.auties00.smalt.audio.behavior;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.TranscodeResult;
import com.github.auties00.smalt.transcode.TranscodedStream;
import com.github.auties00.smalt.transcode.Transcoder;
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
 * memory, where every link is described at construction, {@link AudioReader} reads the first link as from
 * memory, and a transcode to MP4 that copies the links it can and encodes the others treats the stream as the memory
 * read.
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

    @Test
    @DisplayName("transcodes every link to MP4, copying the Opus link and encoding the Vorbis one, as from memory")
    void transcodesToMp4() {
        var output = new ByteArrayOutputStream();
        output.writeBytes(Fixtures.read(Fixtures.tone("ogg", 660, 48_000, 1, 0.7, List.of("-c:a", "libopus",
                "-serial_offset", "200"))));
        output.writeBytes(Fixtures.read(Fixtures.tone("ogg", 440, 44_100, 2, 0.8, List.of("-c:a", "libvorbis",
                "-serial_offset", "100"))));
        var file = output.toByteArray();
        var options = TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                .audio(AudioTranscodeOptions.defaults().toBuilder().codec(Codec.OPUS).allTracks().build()).build();
        var memorySink = MediaSink.memory();
        var fromMemory = Transcoder.transcode(MediaSource.of(file), memorySink, options);
        var streamSink = MediaSink.memory();
        // the Vorbis link is found only while reading, after the remuxer would have been chosen for the Opus link
        var fromStream = Transcoder.transcode(MediaSource.of(new ByteArrayInputStream(file), -1), streamSink, options);
        assertEquals(List.of(true, false), copied(fromMemory));
        assertEquals(copied(fromMemory), copied(fromStream));
        var expected = packets(memorySink.toByteArray());
        var actual = packets(streamSink.toByteArray());
        assertEquals(2, expected.size());
        assertEquals(expected.size(), actual.size());
        for (var track = 0; track < expected.size(); track++) {
            assertEquals(expected.get(track).size(), actual.get(track).size(), "packets of output track " + track);
            assertTrue(expected.get(track).size() > 10, "output track " + track + " has too few packets");
        }
        for (var index = 0; index < expected.getFirst().size(); index++) {
            assertArrayEquals(expected.getFirst().get(index).data(), actual.getFirst().get(index).data());
        }
    }

    static List<Boolean> copied(TranscodeResult result) {
        return result.streams().stream().map(TranscodedStream::copied).toList();
    }

    /** The packets of every track of a file, in track order. */
    static List<List<Packet>> packets(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var result = new ArrayList<List<Packet>>();
            var ids = new ArrayList<Integer>();
            for (var track : demuxer.tracks()) {
                assertEquals(Codec.OPUS, track.codec());
                ids.add(track.id());
                result.add(new ArrayList<>());
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                result.get(ids.indexOf(packet.trackId())).add(packet);
            }
            return result;
        }
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
