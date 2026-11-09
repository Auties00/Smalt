package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.DATA_START;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.audioEntry;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.file;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.hdlr;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mdhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stszCount;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.tkhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.MATRIX;
import static com.github.auties00.smalt.container.mp4.Mp4EditListTest.elst;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Silence filling the gaps that empty and dwell edits after a media edit leave in an audio track's
 * presentation (ISO/IEC 14496-12 clause 8.6.6), on hand-built 16-bit PCM files at 8 kHz whose sample
 * {@code i} holds {@code i + 1}, so that a zero is a filled sample; expectations are worked out by hand
 * from the specification, since ffmpeg's MP4 demuxer does not place edits after an empty one.
 */
@DisplayName("MP4 audio edit gaps")
class Mp4AudioEditGapTest {
    static final int RATE = 8000;

    static final int MEDIA = 3000;

    /** {@value #MEDIA} frames of mono PCM in one chunk, presented by {@code edts}. */
    static byte[] pcmFile(byte[] edts, long movieDuration) {
        var media = new byte[MEDIA * 2];
        for (var index = 0; index < MEDIA; index++) {
            media[index * 2] = (byte) (index + 1);
            media[index * 2 + 1] = (byte) ((index + 1) >> 8);
        }
        var trak = box("trak", tkhd(1, 0, 0, MATRIX), edts,
                box("mdia", mdhd(RATE, MEDIA), hdlr("soun"), box("minf", full("smhd", 0, 0, u32(0)),
                        box("stbl", stsd(audioEntry("sowt", 1, RATE)), table("stts", 0, 2, MEDIA, 1),
                                table("stsc", 0, 3, 1, MEDIA, 1), stszCount(2, MEDIA), table("stco", 0, 1, DATA_START)))));
        return file(media, mvhd(RATE, movieDuration), trak);
    }

    /** Appends the media samples {@code from} to {@code to} (exclusive) to a sample list. */
    static void media(List<Integer> out, int from, int to) {
        for (var index = from; index < to; index++) {
            out.add(index + 1);
        }
    }

    /** Appends {@code count} zeros to a sample list. */
    static void silence(List<Integer> out, int count) {
        for (var index = 0; index < count; index++) {
            out.add(0);
        }
    }

    static List<Integer> read(byte[] data) {
        var actual = new ArrayList<Integer>();
        try (var source = MediaSource.of(data); var reader = AudioReader.open(source)) {
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                for (var index = 0; index < block.frames(); index++) {
                    actual.add(Math.round(block.plane(0)[index] * 32768));
                }
            }
        }
        return actual;
    }

    static float[] readFloats(byte[] data, AudioReaderOptions options) {
        var out = new float[0];
        var size = 0;
        try (var source = MediaSource.of(data); var reader = AudioReader.open(source, options)) {
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                if (size + block.frames() > out.length) {
                    out = Arrays.copyOf(out, Math.max(out.length * 2, size + block.frames()));
                }
                System.arraycopy(block.plane(0), 0, out, size, block.frames());
                size += block.frames();
            }
        }
        return Arrays.copyOf(out, size);
    }

    /**
     * An empty edit of 500 after the first media edit, and a media edit of 1500 that has only 1200 samples of
     * media left, followed by a third edit: 500 zeros, then 300.
     */
    static byte[] emptyEditFile() {
        return pcmFile(elst(1000, 500, 1, 500, -1, 1, 1500, 1800, 1, 700, 2900, 1), 3700);
    }

    static List<Integer> emptyEditExpected() {
        var expected = new ArrayList<Integer>();
        media(expected, 500, 1500);
        silence(expected, 500);
        media(expected, 1800, 3000);
        silence(expected, 300);
        media(expected, 2900, 3000);
        return expected;
    }

    @Test
    @DisplayName("fills an empty edit and the end of a media edit longer than its media with silence")
    void emptyEdit() {
        assertEquals(emptyEditExpected(), read(emptyEditFile()));
    }

    @Test
    @DisplayName("fills an audio dwell edit, which presents nothing, with silence of its duration")
    void dwellEdit() {
        var data = pcmFile(elst(1000, 500, 1, 400, 1000, 0, 1000, 1500, 1), 2400);
        var expected = new ArrayList<Integer>();
        media(expected, 500, 1500);
        silence(expected, 400);
        media(expected, 1500, 2500);
        assertEquals(expected, read(data));
    }

    @Test
    @DisplayName("fills the gap after a media edit of a single packet that presented its declared duration")
    void singlePacketEdits() {
        var data = pcmFile(elst(500, 0, 1, 300, -1, 1, 500, 1024, 1), 1300);
        var expected = new ArrayList<Integer>();
        media(expected, 0, 500);
        silence(expected, 300);
        media(expected, 1024, 1524);
        assertEquals(expected, read(data));
    }

    @Test
    @DisplayName("fills several gaps, adjacent edits included, and keeps the samples of contiguous edits joined")
    void severalGaps() {
        var data = pcmFile(elst(256, 0, 1, 100, -1, 1, 77, 1, 0, 1024, 1024, 1, 1000, 100, 1, 64, -1, 1, 10, 0, 1),
                256 + 100 + 77 + 1024 + 1000 + 64 + 10);
        var expected = new ArrayList<Integer>();
        media(expected, 0, 256);
        silence(expected, 177);
        media(expected, 1024, 2048);
        media(expected, 100, 1100);
        silence(expected, 64);
        media(expected, 0, 10);
        assertEquals(expected, read(data));
    }

    @ParameterizedTest
    @EnumSource(value = SampleFormat.class, names = {"INTEGER", "DOUBLE"})
    @DisplayName("fills gaps in exact sample output")
    void exactFormats(SampleFormat format) {
        var data = emptyEditFile();
        var actual = new ArrayList<Integer>();
        try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            try (var decoder = AudioStreamDecoder.create(track,
                    AudioStreamDecoderOptions.builder().sampleFormat(format).build())) {
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet);
                }
                decoder.finish();
                var frames = decoder.available();
                if (format == SampleFormat.INTEGER) {
                    var planes = new int[1][frames];
                    assertEquals(frames, decoder.readIntegers(planes, 0, frames));
                    var shift = decoder.bitsPerSample() - 16;
                    for (var value : planes[0]) {
                        actual.add(value >> shift);
                    }
                } else {
                    var planes = new double[1][frames];
                    assertEquals(frames, decoder.readDoubles(planes, 0, frames));
                    for (var value : planes[0]) {
                        actual.add((int) Math.round(value * 32768));
                    }
                }
            }
        }
        assertEquals(emptyEditExpected(), actual);
    }

    @Test
    @DisplayName("resamples the filled silence as it resamples a stream holding it")
    void resampled() {
        var expected = emptyEditExpected();
        var pcm = ByteBuffer.allocate(expected.size() * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : expected) {
            pcm.putShort((short) (int) value);
        }
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(RATE))
                .sampleRate(RATE).layout(ChannelLayout.MONO).bitsPerSample(16).build();
        var reference = new float[0];
        var options = AudioStreamDecoderOptions.of(16_000, ChannelLayout.MONO);
        try (var decoder = AudioStreamDecoder.create(track, options)) {
            decoder.decode(new Packet(1, pcm.array(), 0, 0, expected.size(), true));
            decoder.finish();
            reference = new float[decoder.available()];
            decoder.read(new float[][]{reference}, 0, reference.length);
        }
        var actual = readFloats(emptyEditFile(), AudioReaderOptions.of(16_000, ChannelLayout.MONO));
        assertEquals(reference.length, actual.length);
        for (var index = 0; index < actual.length; index++) {
            assertEquals(reference[index], actual[index], 1e-5f, "sample " + index);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {50_000, 125_000, 150_000, 187_500, 200_000, 300_000, 360_000, 377_500, 387_000})
    @DisplayName("seeks into, before and after the gaps as a decode from the start presents them")
    void seek(long micros) {
        var data = emptyEditFile();
        var whole = read(data);
        var actual = new ArrayList<Integer>();
        try (var source = MediaSource.of(data); var reader = AudioReader.open(source)) {
            reader.seek(micros);
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                for (var index = 0; index < block.frames(); index++) {
                    actual.add(Math.round(block.plane(0)[index] * 32768));
                }
            }
        }
        var start = (int) (micros * RATE / 1_000_000);
        assertEquals(whole.subList(start, whole.size()), actual);
    }

    /**
     * Edits on packet boundaries, so that the timestamps place every packet exactly: packet 0, an empty
     * edit of 500, then packets 1 and 2.
     */
    static byte[] alignedFile() {
        return pcmFile(elst(1024, 0, 1, 500, -1, 1, 1976, 1024, 1), 3500);
    }

    @ParameterizedTest
    @ValueSource(longs = {100_000, 128_000, 150_000, 190_000, 200_000, 250_000, 400_000})
    @DisplayName("seeks a track whose timestamps are exact into and around its gap")
    void seekExact(long micros) {
        var data = alignedFile();
        try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertTrue(AudioStreamDecoder.measure(demuxer, track, AudioStreamDecoderOptions.defaults()).isExact());
        }
        var whole = read(data);
        assertEquals(3500, whole.size());
        var actual = new ArrayList<Integer>();
        try (var source = MediaSource.of(data); var reader = AudioReader.open(source)) {
            reader.seek(micros);
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                for (var index = 0; index < block.frames(); index++) {
                    actual.add(Math.round(block.plane(0)[index] * 32768));
                }
            }
        }
        var start = (int) (micros * RATE / 1_000_000);
        assertEquals(whole.subList(start, whole.size()), actual);
    }

    @ParameterizedTest
    @ValueSource(ints = {8000, 16_000})
    @DisplayName("restarts inside a gap with silence until the first packet after it")
    void restartInsideGap(int outputRate) {
        var data = alignedFile();
        var expected = new float[0];
        var options = AudioStreamDecoderOptions.of(outputRate, ChannelLayout.MONO);
        float[] whole;
        try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var timing = AudioStreamDecoder.measure(demuxer, track, options);
            demuxer.seek(-1_000_000);
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            assertEquals(3, packets.size());
            whole = drain(track, options, null, 0, packets);
            // 150 ms is sample 1200, inside the gap from 1024 to 1524; the packets start after it
            var actual = drain(track, options, timing, 150_000, packets.subList(1, 3));
            var start = (int) (150_000L * outputRate / 1_000_000);
            expected = Arrays.copyOfRange(whole, start, whole.length);
            assertEquals(expected.length, actual.length);
            for (var index = 0; index < actual.length; index++) {
                assertEquals(expected[index], actual[index], outputRate == RATE ? 0 : 1e-4f, "sample " + index);
            }
        }
        if (outputRate == RATE) {
            for (var index = 0; index < 1524 - 1200; index++) {
                assertEquals(0, expected[index]);
            }
            assertEquals(1025, Math.round(expected[1524 - 1200] * 32768));
        }
    }

    static float[] drain(AudioTrack track, AudioStreamDecoderOptions options, AudioStreamDecoder.Timing timing,
                         long micros, List<Packet> packets) {
        try (var decoder = AudioStreamDecoder.create(track, options)) {
            if (timing != null) {
                decoder.startAt(micros, timing);
            }
            for (var packet : packets) {
                decoder.decode(packet);
            }
            decoder.finish();
            var out = new float[decoder.available()];
            decoder.read(new float[][]{out}, 0, out.length);
            return out;
        }
    }
}
