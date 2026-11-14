package com.github.auties00.smalt.audio.twinvq;

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

/**
 * Reads the TwinVQ conformance streams of ISO/IEC 14496-26 through Smalt's MP4 demuxer and decodes them.
 */
final class TwinVqTestSupport {
    private TwinVqTestSupport() {
    }

    /**
     * The tracks of a stream and the access units of each, in file order.
     *
     * @param tracks the TwinVQ tracks
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
     * Demuxes the TwinVQ tracks of an MP4 file.
     *
     * @param file the file
     * @return its tracks and access units
     */
    static Stream stream(byte[] file) {
        var units = new LinkedHashMap<Integer, List<Packet>>();
        var tracks = new LinkedHashMap<Integer, AudioTrack>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var track : demuxer.tracks()) {
                if (track instanceof AudioTrack audio && audio.codec() == Codec.TWINVQ) {
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
     * Decodes every frame of a stream with a decoder.
     *
     * @param decoder the decoder
     * @param stream the stream
     * @return the samples of each channel
     */
    static float[][] decode(TwinVqDecoder decoder, Stream stream) {
        var channels = decoder.layout().channels();
        var out = new float[channels][(stream.frames() + 1) * decoder.frameSize()];
        var length = new int[1];
        for (var frame = 0; frame < stream.frames(); frame++) {
            decoder.decode(stream.frame(frame), buffer -> {
                for (var channel = 0; channel < channels; channel++) {
                    System.arraycopy(buffer.plane(channel), 0, out[channel], length[0], buffer.frames());
                }
                length[0] += buffer.frames();
            });
        }
        for (var channel = 0; channel < channels; channel++) {
            out[channel] = Arrays.copyOf(out[channel], length[0]);
        }
        return out;
    }

    /**
     * Reads a listed reference waveform.
     *
     * @param name the reference name, without {@code mpeg4audio-} and the extension
     * @return the samples of each channel, full scale 1
     */
    static float[][] reference(String name) {
        return readWav(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".wav")));
    }

    /**
     * Reads a 16-bit or 24-bit PCM WAV file.
     *
     * @param data the file
     * @return the samples of each channel, full scale 1
     */
    static float[][] readWav(byte[] data) {
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
                var samples = new float[channels][frames];
                for (var channel = 0; channel < channels; channel++) {
                    for (var i = 0; i < frames; i++) {
                        var p = position + 8 + (i * channels + channel) * bytes;
                        samples[channel][i] = bits == 16
                                ? (short) ((data[p] & 0xFF) | (data[p + 1] << 8)) / 32768f
                                : ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] << 16)) / 8388608f;
                    }
                }
                return samples;
            }
            position += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("no data chunk");
    }
}
