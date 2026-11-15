package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Gaps between the timestamps of audio packets on synthetic 16-bit PCM packets at 8 or 44.1 kHz, every
 * sample of packet {@code p} holding {@code p + 1}: which gaps the stream decoder fills with silence and
 * which differences it treats as the rounding or drift of the timestamps.
 */
@DisplayName("audio stream decoder gaps")
class AudioStreamGapTest {
    /** A mono 16-bit PCM track in a time base. */
    static AudioTrack track(int rate, Rational timeBase) {
        return AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(timeBase).sampleRate(rate)
                .layout(ChannelLayout.MONO).bitsPerSample(16).build();
    }

    /** A packet of {@code frames} samples of value {@code value}. */
    static Packet packet(int value, int frames, long pts, long duration) {
        var data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < frames; index++) {
            data.putShort((short) value);
        }
        return new Packet(1, data.array(), pts, pts, duration, true);
    }

    /** Decodes packets and returns the output as the integer sample values. */
    static int[] decode(AudioTrack track, AudioStreamDecoderOptions options, List<Packet> packets) {
        try (var decoder = AudioStreamDecoder.create(track, options)) {
            for (var packet : packets) {
                decoder.decode(packet);
            }
            decoder.finish();
            var planes = new int[1][decoder.available()];
            decoder.readIntegers(planes, 0, planes[0].length);
            return planes[0];
        }
    }

    static int[] decode(AudioTrack track, List<Packet> packets) {
        return decode(track, AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.INTEGER).build(), packets);
    }

    /** The runs of equal values in a sample sequence, as value and length pairs. */
    static List<String> runs(int[] samples) {
        var out = new ArrayList<String>();
        for (var start = 0; start < samples.length; ) {
            var end = start;
            while (end < samples.length && samples[end] == samples[start]) {
                end++;
            }
            out.add(samples[start] + "x" + (end - start));
            start = end;
        }
        return out;
    }

    @Test
    @DisplayName("fills a gap between packets in sample timestamps to the sample")
    void sampleTimestamps() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 2001, 1000),
                packet(4, 1000, 3001, 1000), packet(5, 500, 4501, 500));
        assertEquals(List.of("1x1000", "2x1000", "0x1", "3x1000", "4x1000", "0x500", "5x500"),
                runs(decode(track, packets)));
    }

    @Test
    @DisplayName("fills a gap in millisecond timestamps and ignores their rounding")
    void millisecondTimestamps() {
        // 1024 samples at 44.1 kHz last 23.22 ms, so the rounded timestamps of contiguous packets differ from
        // their sample counts by up to a millisecond either way
        var track = track(44_100, Rational.MILLISECONDS);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 20; index++) {
            packets.add(packet(index + 1, 1024, Math.round(index * 1024 * 1000.0 / 44_100), 23));
        }
        var afterGap = Math.round(20 * 1024 * 1000.0 / 44_100) + 250;
        for (var index = 0; index < 5; index++) {
            packets.add(packet(21 + index, 1024, afterGap + Math.round(index * 1024 * 1000.0 / 44_100), 23));
        }
        var expected = new ArrayList<String>();
        for (var index = 0; index < 20; index++) {
            expected.add((index + 1) + "x1024");
        }
        // the gap runs from the end of the 20th packet, placed by its rounded timestamp, to the 21st
        var samples = Rational.timeBaseOf(44_100);
        var gap = Rational.MILLISECONDS.rescale(afterGap, samples)
                - Rational.MILLISECONDS.rescale(Math.round(19 * 1024 * 1000.0 / 44_100), samples) - 1024;
        expected.add("0x" + gap);
        for (var index = 0; index < 5; index++) {
            expected.add((21 + index) + "x1024");
        }
        // the last packet's declared 23 ms trims it to 1014 samples
        expected.set(expected.size() - 1, "25x" + Rational.MILLISECONDS.rescale(23, Rational.timeBaseOf(44_100)));
        assertEquals(expected, runs(decode(track, packets)));
    }

    @Test
    @DisplayName("ignores millisecond rounding in timestamps reported in samples and fills a real gap")
    void millisecondsInSamples() {
        // Matroska stores milliseconds and reports audio timestamps in samples: 1024-sample packets at 48 kHz
        // start up to 24 samples early or late, so a packet starts up to 48 samples after the one before ends
        var track = track(48_000, Rational.timeBaseOf(48_000));
        var packets = new ArrayList<Packet>();
        var starts = new ArrayList<Long>();
        for (var index = 0; index < 30; index++) {
            starts.add(Math.round(index * 1024 / 48.0) * 48);
        }
        var resume = Math.round(30 * 1024 / 48.0 + 300) * 48;
        for (var index = 0; index < 5; index++) {
            starts.add(resume + Math.round(index * 1024 / 48.0) * 48);
        }
        for (var index = 0; index < starts.size(); index++) {
            packets.add(packet(index + 1, 1024, starts.get(index), Packet.UNKNOWN_DURATION));
        }
        var expected = new ArrayList<String>();
        for (var index = 0; index < 30; index++) {
            expected.add((index + 1) + "x1024");
        }
        expected.add("0x" + (resume - starts.get(29) - 1024));
        for (var index = 30; index < 35; index++) {
            expected.add((index + 1) + "x1024");
        }
        assertEquals(expected, runs(decode(track, packets)));
    }

    @Test
    @DisplayName("keeps the samples joined when the timestamps drift from them in every packet")
    void drift() {
        // 960-sample packets whose container counts 1024 samples each, so every packet starts 64 samples late
        var track = track(48_000, Rational.timeBaseOf(48_000));
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 10; index++) {
            packets.add(packet(index + 1, 960, index * 1024L, 1024));
        }
        var expected = new ArrayList<String>();
        for (var index = 0; index < 10; index++) {
            expected.add((index + 1) + "x960");
        }
        assertEquals(expected, runs(decode(track, packets)));
    }

    @Test
    @DisplayName("keeps overlapping packets whole")
    void overlap() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 1500, 1000),
                packet(4, 1000, 2500, 1000));
        assertEquals(List.of("1x1000", "2x1000", "3x1000", "4x1000"), runs(decode(track, packets)));
    }

    @Test
    @DisplayName("opens no gap after a single timestamp that jumps back")
    void backwardGlitch() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 0, 1000),
                packet(4, 1000, 3000, 1000), packet(5, 1000, 4000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "3x1000", "4x1000", "5x1000"), runs(decode(track, packets)));
    }

    @Test
    @DisplayName("measures gaps again after timestamps that restart")
    void restartedTimestamps() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 0, 1000),
                packet(4, 1000, 1000, 1000), packet(5, 1000, 3000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "3x1000", "4x1000", "0x1000", "5x1000"),
                runs(decode(track, packets)));
    }

    @Test
    @DisplayName("fills the gap of a single timestamp that jumps forward and joins the overlap after it")
    void forwardGlitch() {
        // a forward jump cannot be told from a gap until the next packet, so it is filled
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 5000, 1000),
                packet(4, 1000, 3000, 1000), packet(5, 1000, 4000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "0x3000", "3x1000", "4x1000", "5x1000"),
                runs(decode(track, packets)));
    }

    @Test
    @DisplayName("places packets without timestamps after the samples before them")
    void missingTimestamps() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000),
                packet(3, 1000, Packet.NO_TIMESTAMP, 1000), packet(4, 1000, 4000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "3x1000", "0x1000", "4x1000"), runs(decode(track, packets)));
    }

    @Test
    @DisplayName("ignores a one-sample difference in a time base finer than the samples")
    void fineTimeBase() {
        // 90 kHz timestamps truncated rather than rounded: 1001 samples at 8 kHz are 11261.25 ticks
        var track = track(8000, new Rational(1, 90_000));
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 8; index++) {
            packets.add(packet(index + 1, 1001, index * 1001L * 90_000 / 8000, 11_261));
        }
        packets.add(packet(9, 1001, 9 * 1001L * 90_000 / 8000, 11_261));
        var expected = new ArrayList<String>();
        for (var index = 0; index < 8; index++) {
            expected.add((index + 1) + "x1001");
        }
        expected.add("0x1001");
        expected.add("9x1001");
        assertEquals(expected, runs(decode(track, packets)));
    }

    @Test
    @DisplayName("fills the time of a discarded packet and of a discard start the timestamps keep")
    void discards() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000),
                packet(3, 1000, 2000, 1000).withFlags(java.util.Set.of(com.github.auties00.smalt.track.PacketFlag.KEY,
                        com.github.auties00.smalt.track.PacketFlag.DISCARD)),
                packet(4, 1000, 3000, 1000).withSideData(com.github.auties00.smalt.track.PacketSideData.builder()
                        .discard(250, 0).build()),
                packet(5, 1000, 4000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "0x1250", "4x750", "5x1000"), runs(decode(track, packets)));
    }

    @Test
    @DisplayName("counts the silence toward the declared duration")
    void declaredDuration() {
        var track = track(8000, Rational.timeBaseOf(8000)).toBuilder().duration(2500).build();
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 3000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "0x500"), runs(decode(track, packets)));
    }

    @Test
    @DisplayName("drops the priming before the silence it reaches")
    void priming() {
        var track = track(8000, Rational.timeBaseOf(8000)).toBuilder().skipSamples(1500).build();
        var packets = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 2000, 1000), packet(3, 1000, 3000, 1000));
        assertEquals(List.of("0x500", "2x1000", "3x1000"), runs(decode(track, packets)));
    }

    @Test
    @DisplayName("converts the silence to another layout and sample format")
    void converted() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var packets = List.of(packet(16_384, 1000, 0, 1000), packet(16_384, 1000, 1000, 1000),
                packet(16_384, 1000, 2500, 1000));
        try (var decoder = AudioStreamDecoder.create(track, AudioStreamDecoderOptions.of(8000, ChannelLayout.STEREO))) {
            for (var packet : packets) {
                decoder.decode(packet);
            }
            decoder.finish();
            var planes = new float[2][decoder.available()];
            assertEquals(3500, decoder.read(planes, 0, planes[0].length));
            for (var plane : planes) {
                for (var index = 0; index < 3500; index++) {
                    var silent = index >= 2000 && index < 2500;
                    assertEquals(silent, plane[index] == 0, "sample " + index);
                }
            }
        }
    }

    @Test
    @DisplayName("rejects a gap whose silence exceeds the byte limit")
    void limit() {
        var track = track(8000, Rational.timeBaseOf(8000));
        var options = AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.INTEGER)
                .limits(MediaLimits.builder().maxBytes(1 << 20).build()).build();
        var small = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000), packet(3, 1000, 200_000, 1000));
        assertEquals(List.of("1x1000", "2x1000", "0x198000", "3x1000"), runs(decode(track, options, small)));
        var huge = List.of(packet(1, 1000, 0, 1000), packet(2, 1000, 1000, 1000),
                packet(3, 1000, 1L << 40, 1000));
        assertThrows(MediaException.LimitExceeded.class, () -> decode(track, options, huge));
    }
}
