package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds and checks the ambisonic fixtures of the ALAC and Vorbis layout tests: PCM whose every channel is a tone of
 * its own frequency, MP4 spatial audio boxes added to a sample entry as Google's spatial media injector adds them,
 * and the check that the decoded channel {@code k} carries the tone of PCM channel {@code k}.
 */
public final class AmbisonicFixtures {
    /**
     * The sampling rate of the fixtures.
     */
    public static final int RATE = 48_000;

    /**
     * The length of the fixtures in samples.
     */
    public static final int FRAMES = 48_000;

    private AmbisonicFixtures() {
    }

    /** {@return the tone frequency of every channel, spaced so that lossy codecs keep them apart} */
    public static double[] tones(int channels) {
        var tones = new double[channels];
        for (var k = 0; k < channels; k++) {
            tones[k] = 300 + 650 * k;
        }
        return tones;
    }

    /** {@return interleaved 16-bit little-endian PCM, channel {@code k} a tone of {@code tones(channels)[k]}} */
    public static byte[] pcm(int channels) {
        return pcm(tones(channels));
    }

    /** {@return interleaved 16-bit little-endian PCM, channel {@code k} a tone of {@code tones[k]}} */
    public static byte[] pcm(double[] tones) {
        var channels = tones.length;
        var pcm = ByteBuffer.allocate(FRAMES * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < FRAMES; i++) {
            for (var k = 0; k < channels; k++) {
                pcm.putShort((short) Math.round(6000 * StrictMath.sin(2 * StrictMath.PI * tones[k] * i / RATE)));
            }
        }
        return pcm.array();
    }

    /** {@return planar 16-bit samples of {@link #pcm(int)}} */
    public static int[][] planes(int channels) {
        var pcm = ByteBuffer.wrap(pcm(channels)).order(ByteOrder.LITTLE_ENDIAN);
        var planes = new int[channels][FRAMES];
        for (var i = 0; i < FRAMES; i++) {
            for (var k = 0; k < channels; k++) {
                planes[k][i] = pcm.getShort();
            }
        }
        return planes;
    }

    /** Builds a spatial audio box of a periphonic ACN SN3D sound field. */
    public static byte[] sa3d(int order, boolean headLocked, long... map) {
        var box = ByteBuffer.allocate(20 + 4 * map.length);
        box.putInt(20 + 4 * map.length).put("SA3D".getBytes(StandardCharsets.US_ASCII));
        box.put((byte) 0).put((byte) (headLocked ? 0x80 : 0)).putInt(order).put((byte) 0).put((byte) 0)
                .putInt(map.length);
        for (var entry : map) {
            box.putInt((int) entry);
        }
        return box.array();
    }

    /**
     * Appends a box to the first sample entry of a file whose {@code moov} follows its media, growing every enclosing
     * box.
     */
    public static byte[] inject(byte[] file, byte[] box) {
        var parents = new ArrayList<Integer>();
        var insert = find(file, 0, file.length, parents);
        assertTrue(insert > 0, "an audio sample entry");
        var data = ByteBuffer.wrap(file);
        for (var parent : parents) {
            data.putInt(parent, data.getInt(parent) + box.length);
        }
        var result = new byte[file.length + box.length];
        System.arraycopy(file, 0, result, 0, insert);
        System.arraycopy(box, 0, result, insert, box.length);
        System.arraycopy(file, insert, result, insert + box.length, file.length - insert);
        return result;
    }

    /**
     * Decodes the first track of a file with the stream decoder, checks that it outputs the expected layout and that
     * output channel {@code k} carries the tone of PCM channel {@code k}.
     *
     * @return the decoded planes
     */
    public static float[][] assertChannels(byte[] file, ChannelLayout expected) {
        return assertChannels(file, expected, tones(expected.channels()));
    }

    /**
     * Decodes the first track of a file with the stream decoder, checks that it outputs the expected layout and that
     * output channel {@code k} carries the tone {@code tones[k]}.
     *
     * @return the decoded planes
     */
    public static float[][] assertChannels(byte[] file, ChannelLayout expected, double[] tones) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(expected, track.layout().orElseThrow(), "the declared layout");
            float[][] planes;
            try (var decoder = AudioStreamDecoder.create(track)) {
                assertEquals(expected, decoder.layout(), "the output layout");
                planes = new float[expected.channels()][FRAMES];
                var filled = 0;
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet);
                    filled += decoder.read(planes, filled, FRAMES - filled);
                }
                decoder.finish();
                filled += decoder.read(planes, filled, FRAMES - filled);
                assertTrue(filled > FRAMES / 2, "decoded " + filled + " samples");
            }
            for (var k = 0; k < expected.channels(); k++) {
                assertEquals(k, strongest(planes[k], tones), "output channel " + k);
            }
            return planes;
        }
    }

    private static int strongest(float[] signal, double[] tones) {
        var best = -1;
        var bestPower = -1.0;
        var start = signal.length / 4;
        var length = Math.min(8192, signal.length / 2);
        for (var t = 0; t < tones.length; t++) {
            double re = 0;
            double im = 0;
            for (var i = 0; i < length; i++) {
                var phase = 2 * StrictMath.PI * tones[t] * i / RATE;
                re += signal[start + i] * StrictMath.cos(phase);
                im += signal[start + i] * StrictMath.sin(phase);
            }
            var power = re * re + im * im;
            if (power > bestPower) {
                bestPower = power;
                best = t;
            }
        }
        return best;
    }

    private static int find(byte[] file, int start, int end, List<Integer> parents) {
        var data = ByteBuffer.wrap(file);
        var position = start;
        while (position + 8 <= end) {
            var size = data.getInt(position);
            var type = new String(file, position + 4, 4, StandardCharsets.US_ASCII);
            if (List.of("moov", "trak", "mdia", "minf", "stbl").contains(type)) {
                parents.add(position);
                var hit = find(file, position + 8, position + size, parents);
                if (hit > 0) {
                    return hit;
                }
                parents.removeLast();
            } else if (type.equals("stsd")) {
                var entry = position + 16;
                parents.add(position);
                parents.add(entry);
                return entry + data.getInt(entry);
            }
            position += size;
        }
        return -1;
    }
}
