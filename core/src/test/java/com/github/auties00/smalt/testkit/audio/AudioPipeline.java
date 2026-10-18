package com.github.auties00.smalt.testkit.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Drives a demuxer and a decoder the way a reader would, collecting interleaved float samples so
 * they can be compared with {@code Oracle.decodeAudio}.
 */
public final class AudioPipeline {
    private AudioPipeline() {
        throw new AssertionError();
    }

    /**
     * The decoded output of the first audio track.
     *
     * @param track       the decoded track
     * @param sampleRate  the rate of the first decoded block
     * @param channels    the channel count of the first decoded block
     * @param interleaved the samples, interleaved, untrimmed
     */
    public record Decoded(AudioTrack track, int sampleRate, int channels, float[] interleaved) {
        /** Returns the number of frames. */
        public int frames() {
            return interleaved.length / channels;
        }

        /**
         * Applies the reader trimming rule: drops {@code skipSamples} leading frames and keeps at
         * most {@code duration} frames after them when the duration is known.
         */
        public float[] trimmed() {
            var skip = (int) Math.min(track.skipSamples(), frames());
            var keep = frames() - skip;
            if (track.duration() >= 0 && track.timeBase().numerator() == 1
                    && track.timeBase().denominator() == sampleRate) {
                keep = (int) Math.min(keep, track.duration());
            }
            var result = new float[keep * channels];
            System.arraycopy(interleaved, skip * channels, result, 0, result.length);
            return result;
        }
    }

    /**
     * Returns every packet of the given track.
     */
    public static List<Packet> packets(Demuxer demuxer, int trackId) {
        var result = new ArrayList<Packet>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            if (packet.trackId() == trackId) {
                result.add(packet);
            }
        }
        return result;
    }

    /**
     * Returns the first audio track of a demuxer.
     */
    public static AudioTrack firstAudio(Demuxer demuxer) {
        for (Track track : demuxer.tracks()) {
            if (track instanceof AudioTrack audio) {
                return audio;
            }
        }
        throw new AssertionError("no audio track");
    }

    /**
     * Decodes the first audio track completely.
     */
    public static Decoded decode(Demuxer demuxer, Function<AudioTrack, AudioDecoder> factory) {
        var track = firstAudio(demuxer);
        var samples = new FloatList();
        var format = new int[2];
        try (var decoder = factory.apply(track)) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    decoder.decode(packet, buffer -> append(buffer, samples, format));
                }
            }
            decoder.flush(buffer -> append(buffer, samples, format));
        }
        return new Decoded(track, format[0], Math.max(1, format[1]), samples.toArray());
    }

    /**
     * Decodes a list of packets with a decoder.
     */
    public static Decoded decode(AudioTrack track, List<Packet> packets, AudioDecoder decoder) {
        var samples = new FloatList();
        var format = new int[2];
        for (var packet : packets) {
            decoder.decode(packet, buffer -> append(buffer, samples, format));
        }
        decoder.flush(buffer -> append(buffer, samples, format));
        return new Decoded(track, format[0], Math.max(1, format[1]), samples.toArray());
    }

    private static void append(com.github.auties00.smalt.audio.AudioBuffer buffer, FloatList samples, int[] format) {
        if (format[0] == 0) {
            format[0] = buffer.sampleRate();
            format[1] = buffer.channels();
        }
        var channels = buffer.channels();
        for (var i = 0; i < buffer.frames(); i++) {
            for (var c = 0; c < channels; c++) {
                samples.add(buffer.plane(c)[i]);
            }
        }
    }

    /** A growable float array. */
    public static final class FloatList {
        private float[] data = new float[4096];
        private int size;

        public void add(float value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        public int size() {
            return size;
        }

        public float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
