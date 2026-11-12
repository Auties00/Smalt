package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads the CELP conformance streams of ISO/IEC 14496-26 through Smalt's MP4 demuxer and decodes them.
 */
final class CelpTestSupport {
    private CelpTestSupport() {
    }

    /**
     * The tracks of a stream and the access units of each, in file order.
     *
     * @param tracks the CELP tracks
     * @param units the access units of each track
     */
    record Stream(List<AudioTrack> tracks, List<List<Packet>> units) {
        /**
         * {@return the number of frames of the base layer}
         */
        int frames() {
            return units.getFirst().size();
        }

        /**
         * Returns the access units of one frame of every track, up to the first track that has none.
         *
         * @param frame the frame index
         * @return the access units, in track order
         */
        List<Packet> frame(int frame) {
            var packets = new ArrayList<Packet>();
            for (var track : units) {
                if (frame >= track.size()) {
                    break;
                }
                packets.add(track.get(frame));
            }
            return packets;
        }
    }

    /**
     * Opens a listed conformance stream.
     *
     * @param name the stream name, without {@code mpeg4audio-} and the extension
     * @return its tracks and access units
     */
    static Stream stream(String name) {
        return stream(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".mp4")));
    }

    /**
     * Demuxes the CELP tracks of an MP4 file.
     *
     * @param file the file
     * @return its tracks and access units
     */
    static Stream stream(byte[] file) {
        var units = new LinkedHashMap<Integer, List<Packet>>();
        var tracks = new LinkedHashMap<Integer, AudioTrack>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var track : demuxer.tracks()) {
                if (track instanceof AudioTrack audio && audio.codec() == Codec.CELP) {
                    tracks.put(track.id(), audio);
                    units.put(track.id(), new ArrayList<>());
                }
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var list = units.get(packet.trackId());
                if (list != null) {
                    list.add(packet);
                }
            }
        }
        return new Stream(List.copyOf(tracks.values()), List.copyOf(units.values()));
    }

    /**
     * Decodes a stream from all its tracks.
     *
     * @param stream the stream
     * @param options the decoder settings
     * @return the decoded samples
     */
    static float[] decode(Stream stream, CelpDecoderOptions options) {
        try (var decoder = new CelpDecoder(stream.tracks(), options)) {
            return decode(decoder, stream);
        }
    }

    /**
     * Decodes every frame of a stream with a decoder.
     *
     * @param decoder the decoder
     * @param stream the stream
     * @return the decoded samples
     */
    static float[] decode(CelpDecoder decoder, Stream stream) {
        var out = new Samples();
        for (var frame = 0; frame < stream.frames(); frame++) {
            decoder.decode(stream.frame(frame), buffer -> {
                assertEquals(decoder.sampleRate(), buffer.sampleRate());
                out.add(buffer.plane(0), buffer.frames());
            });
        }
        return out.toArray();
    }

    /**
     * A growing array of samples.
     */
    static final class Samples {
        private float[] data = new float[1 << 16];

        private int length;

        void add(float[] samples, int count) {
            if (data.length < length + count) {
                data = Arrays.copyOf(data, Math.max(2 * data.length, length + count));
            }
            System.arraycopy(samples, 0, data, length, count);
            length += count;
        }

        float[] toArray() {
            return Arrays.copyOf(data, length);
        }
    }

    /**
     * Reads a listed reference waveform.
     *
     * @param name the reference name, without {@code mpeg4audio-} and the extension
     * @return the first channel, full scale 1
     */
    static float[] reference(String name) {
        return readWav(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".wav")));
    }

    /**
     * Reads the first channel of a 16-bit or 24-bit PCM WAV file.
     *
     * @param data the file
     * @return the samples, full scale 1
     */
    static float[] readWav(byte[] data) {
        var position = 12;
        var channels = 1;
        var bits = 16;
        while (position + 8 <= data.length) {
            var id = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            var size = ByteBuffer.wrap(data, position + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("fmt ")) {
                var buffer = ByteBuffer.wrap(data, position + 8, size).order(ByteOrder.LITTLE_ENDIAN);
                buffer.getShort();
                channels = buffer.getShort();
                buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                bits = buffer.getShort();
            } else if (id.equals("data")) {
                var bytes = bits / 8;
                var frames = Math.min(size, data.length - position - 8) / (bytes * channels);
                var samples = new float[frames];
                for (var i = 0; i < frames; i++) {
                    var p = position + 8 + i * bytes * channels;
                    samples[i] = bits == 16
                            ? (short) ((data[p] & 0xFF) | (data[p + 1] << 8)) / 32768f
                            : ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] << 16)) / 8388608f;
                }
                return samples;
            }
            position += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("no data chunk");
    }
}
