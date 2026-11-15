package com.github.auties00.smalt.audio.pcm;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Builds integer PCM streams that alternate DSD over PCM (DoP 1.1) and plain PCM sections, for the DoP decoder tests
 * and the fuzz seeds.
 */
public final class DopStreams {
    private DopStreams() {
    }

    /**
     * One section of a stream: its integer samples, one array per channel, and for a DoP section the DSD bytes it
     * carries, two per frame and channel, most significant bit first.
     *
     * @param samples the samples, sign-extended from the sample size
     * @param dsd the DSD bytes per channel, or {@code null} for a PCM section
     */
    public record Section(int[][] samples, byte[][] dsd) {
        /**
         * {@return the number of frames}
         */
        public int frames() {
            return samples[0].length;
        }

        /**
         * {@return whether the section carries DoP}
         */
        public boolean isDop() {
            return dsd != null;
        }

        /**
         * Returns the frames from {@code from} to {@code to}, a DoP section staying DoP.
         *
         * @param from the first frame
         * @param to the frame after the last
         * @return the slice
         */
        public Section slice(int from, int to) {
            var channels = samples.length;
            var sliced = new int[channels][];
            var bytes = dsd == null ? null : new byte[channels][];
            for (var c = 0; c < channels; c++) {
                sliced[c] = Arrays.copyOfRange(samples[c], from, to);
                if (bytes != null) {
                    bytes[c] = Arrays.copyOfRange(dsd[c], 2 * from, 2 * to);
                }
            }
            return new Section(sliced, bytes);
        }

        /**
         * Returns the frames from {@code from} to {@code to} as PCM.
         *
         * @param from the first frame
         * @param to the frame after the last
         * @return the slice, without DSD bytes
         */
        public Section pcmSlice(int from, int to) {
            return new Section(slice(from, to).samples(), null);
        }

        /**
         * {@return the marker of the last frame of a DoP section}
         */
        public int lastMarker(int bits) {
            return (samples[0][frames() - 1] >>> (bits - 8)) & 0xFF;
        }
    }

    /**
     * Returns a DoP section of random DSD.
     *
     * @param channels the channel count
     * @param frames the frame count
     * @param bits 24 or 32
     * @param firstMarker the marker of the first frame, {@code 0x05} or {@code 0xFA}
     * @param seed the seed of the DSD bytes and, at 32 bits, of the unused low byte
     * @return the section
     */
    public static Section dop(int channels, int frames, int bits, int firstMarker, long seed) {
        var random = new Random(seed);
        var dsd = new byte[channels][2 * frames];
        var samples = new int[channels][frames];
        for (var c = 0; c < channels; c++) {
            random.nextBytes(dsd[c]);
            var marker = firstMarker;
            for (var i = 0; i < frames; i++) {
                var word = marker << 16 | (dsd[c][2 * i] & 0xFF) << 8 | dsd[c][2 * i + 1] & 0xFF;
                samples[c][i] = bits == 24 ? word << 8 >> 8 : word << 8 | random.nextInt(256);
                marker ^= 0xFF;
            }
        }
        return new Section(samples, dsd);
    }

    /**
     * Returns a PCM section of random samples whose top byte is never a DoP marker.
     *
     * @param channels the channel count
     * @param frames the frame count
     * @param bits 24 or 32
     * @param seed the seed
     * @return the section
     */
    public static Section pcm(int channels, int frames, int bits, long seed) {
        var random = new Random(seed);
        var samples = new int[channels][frames];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < frames; i++) {
                int value;
                do {
                    value = random.nextInt();
                } while ((value >>> 24) == 0x05 || (value >>> 24) == 0xFA);
                samples[c][i] = bits == 24 ? value >> 8 : value;
            }
        }
        return new Section(samples, null);
    }

    /**
     * Joins the samples of sections.
     *
     * @param sections the sections, of one channel count
     * @return the samples of every section in order, one array per channel
     */
    public static int[][] join(List<Section> sections) {
        var channels = sections.getFirst().samples().length;
        var total = sections.stream().mapToInt(Section::frames).sum();
        var out = new int[channels][total];
        var at = 0;
        for (var section : sections) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(section.samples()[c], 0, out[c], at, section.frames());
            }
            at += section.frames();
        }
        return out;
    }

    /**
     * Interleaves frames of samples into the bytes of integer PCM.
     *
     * @param samples the samples, one array per channel
     * @param from the first frame
     * @param count the frame count
     * @param bits 24 or 32
     * @param bigEndian whether the bytes are big-endian
     * @return the bytes
     */
    public static byte[] encode(int[][] samples, int from, int count, int bits, boolean bigEndian) {
        var channels = samples.length;
        var size = bits / 8;
        var out = new byte[count * channels * size];
        var at = 0;
        for (var i = from; i < from + count; i++) {
            for (var c = 0; c < channels; c++) {
                var value = samples[c][i];
                for (var k = 0; k < size; k++) {
                    var shift = bigEndian ? 8 * (size - 1 - k) : 8 * k;
                    out[at++] = (byte) (value >>> shift);
                }
            }
        }
        return out;
    }
}
