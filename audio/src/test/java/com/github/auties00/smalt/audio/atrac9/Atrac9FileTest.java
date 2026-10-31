package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes ATRAC9 from {@code .at9} files that {@link WavMuxer} writes from streams of the test encoder: through the
 * WAVE demuxer and {@link AudioReader}, which drop the encoder delay and stop at the {@code fact} count, and, as a
 * black box, through ffmpeg, whose own WAVE demuxer and ATRAC9 decoder (a separate float implementation) must read
 * the same file to nearly the same samples.
 */
@DisplayName("ATRAC9 .at9 files")
class Atrac9FileTest {
    /** Writes a stream to an .at9 file with an encoder delay and a sample count. */
    static byte[] at9(Atrac9TestStreams.Stream stream, int delay, long samples) {
        var config = stream.parsed();
        var track = AudioTrack.builder().id(1).codec(Codec.ATRAC9).timeBase(Rational.timeBaseOf(config.sampleRate()))
                .sampleRate(config.sampleRate()).layout(com.github.auties00.smalt.audio.ChannelLayout
                        .defaultFor(config.channelCount())).codecConfig(stream.config()).skipSamples(delay)
                .duration(samples).build();
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink)) {
            muxer.addTrack(track);
            var pts = 0L;
            for (var superframe : stream.superframes()) {
                muxer.writePacket(new Packet(1, superframe, pts, pts, config.superframeSamples(), true));
                pts += config.superframeSamples();
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static Atrac9TestStreams.Stream encoded() {
        var input = Atrac9DecoderTest.signal(2, 9000, 44100, 9);
        return Atrac9TestStreams.encode(6, 2, 2, 600, input, true);
    }

    @Test
    @DisplayName("reads back through AudioReader without the encoder delay, up to the fact count")
    void reader() throws Exception {
        var stream = encoded();
        var config = stream.parsed();
        var direct = Atrac9DecoderTest.decode(stream);
        var delay = config.frameSamples();
        var count = 8500;
        var file = at9(stream, delay, count);
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.ATRAC9, track.codec());
            assertEquals(delay, track.skipSamples());
            assertEquals(count, track.duration());
        }
        var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
        try (var reader = AudioReader.open(MediaSource.of(file), options)) {
            var actual = new short[count * 2];
            var filled = 0;
            IntegerAudioBuffer block;
            while ((block = reader.readIntegers()) != null) {
                for (var i = 0; i < block.frames(); i++) {
                    assertTrue(filled < count, "more samples than the fact count");
                    actual[2 * filled] = (short) block.plane(0)[i];
                    actual[2 * filled + 1] = (short) block.plane(1)[i];
                    filled++;
                }
            }
            assertEquals(count, filled);
            var expected = Arrays.copyOfRange(direct, delay * 2, delay * 2 + count * 2);
            Tolerance.exact().assertPcm("reader", expected, actual);
        }
    }

    static Stream<Arguments> ffmpegCases() {
        return Stream.of(Arguments.of(6, 0, 44100), Arguments.of(7, 1, 48000), Arguments.of(2, 0, 16000),
                Arguments.of(12, 1, 96000), Arguments.of(0, 1, 11025));
    }

    // ffmpeg reads the frames of a superframe at multiples of the frame size and does not align the blocks of a
    // frame to bytes, which agrees with LibAtrac9 on streams whose frames fill their size and whose blocks end on
    // bytes, as Sony's do; the test encoder's frames do not fill their size, so these streams have one frame per
    // superframe. ffmpeg also reads the stereo blocks of the test encoder differently from LibAtrac9, which the
    // differential test covers, so these streams are mono and dual mono.
    @ParameterizedTest(name = "rate index {0}, channel configuration {1}")
    @MethodSource("ffmpegCases")
    @DisplayName("decodes as ffmpeg does")
    void ffmpeg(int rateIndex, int channelConfig, int rate) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var channels = Atrac9Tables.CHANNEL_COUNTS[channelConfig];
        var stream = Atrac9TestStreams.encode(rateIndex, channelConfig, 0, 1500,
                Atrac9DecoderTest.signal(channels, 9000, rate, rateIndex), rateIndex % 2 == 0);
        var direct = Atrac9DecoderTest.decode(stream);
        var file = Scratch.directory("atrac9-ffmpeg").resolve("encoded-" + rateIndex + "-" + channelConfig + ".at9");
        Files.write(file, at9(stream, 0, direct.length / channels));
        var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-f", "s16le", "-"));
        var ffmpeg = new short[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(ffmpeg);
        Files.deleteIfExists(file);
        var length = Math.min(direct.length, ffmpeg.length);
        assertTrue(length > direct.length * 9 / 10, "ffmpeg decoded " + ffmpeg.length + " of " + direct.length
                + " samples");
        // ffmpeg's decoder inverts the polarity of LibAtrac9's (and Smalt's) output, the first frame aside, which
        // holds the encoder's leading silence; it decodes in floats, so its samples may round differently
        var expected = Arrays.copyOf(direct, length);
        var inverted = new short[length];
        for (var i = 0; i < length; i++) {
            inverted[i] = (short) Math.clamp(-ffmpeg[i], Short.MIN_VALUE, Short.MAX_VALUE);
        }
        var start = stream.parsed().frameSamples() * channels;
        Tolerance.snr(70).assertPcm("ffmpeg, polarity inverted", Arrays.copyOfRange(expected, start, length),
                Arrays.copyOfRange(inverted, start, length));
    }
}
