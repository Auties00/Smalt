package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link Mp3Decoder#conceal(int, java.util.function.Consumer)} on Layer III streams of MPEG-1 and MPEG-2 with
 * and without the bit reservoir and on a Layer II stream: lost frames keep the output length, repeat the last
 * subband samples at a falling gain without clicks, frames whose main data begins in a lost frame are concealed
 * instead of silent, and the decode converges back to the reference. A black-box comparison decodes the same stream
 * with its lost frames removed through ffmpeg's float decoder: one frame after each loss both decoders agree within
 * the tolerance of the decoder comparison, so the concealment leaves no trace in the decoder's state.
 */
class Mp3ConcealmentTest {
    /** One frame after a loss the two float decoders of the same synthesis differ only by rounding. */
    private static final Tolerance AFTER_RECOVERY = Tolerance.snr(90);

    private static final PacketLoss.Bounds BOUNDS = new PacketLoss.Bounds(true, 2.5, 6, 576, 14, 4, 100);

    static PacketLoss.Encoded encode(Mp3EncoderOptions options, double seconds) {
        try (var encoder = new Mp3Encoder(options)) {
            return PacketLoss.encode(encoder, PacketLoss.signal(options.sampleRate(), options.layout().channels(),
                    seconds));
        }
    }

    @ParameterizedTest(name = "{0} Hz {1} channels {2} bit/s, reservoir {3}")
    @CsvSource({"44100, 2, 128000, true", "44100, 2, 128000, false", "24000, 1, 48000, true", "48000, 1, 64000, true",
            "8000, 1, 16000, true"})
    @DisplayName("conceals Layer III bursts and a random trace keeping the length, without clicks, then converges")
    void layer3(int rate, int channels, int bitrate, boolean reservoir) {
        var options = Mp3EncoderOptions.builder().format(rate, ChannelLayout.defaultFor(channels)).cbr(bitrate)
                .bitReservoir(reservoir).build();
        var encoded = encode(options, 4);
        var packets = encoded.packets();
        for (var lost : List.of(PacketLoss.bursts(20, 1, 50, 2, 90, 7),
                PacketLoss.random(rate + channels, packets.size(), 10, 2, 3, 8))) {
            var lossy = PacketLoss.assertConceals(() -> new Mp3Decoder(encoded.track()), packets, lost, BOUNDS);
            for (var run : lossy.runs()) {
                var after = run.lastPacket() + 1;
                // a short loss leaves the concealment audible, so the frame after it is not silent even when its
                // main data begins in a lost frame
                if (after < packets.size() && !lost.contains(after) && run.lastPacket() - run.firstPacket() < 2) {
                    var from = lossy.starts()[after];
                    assertTrue(PacketLoss.energy(lossy.planes(), from, from + lossy.lengths()[after]) > 0,
                            "the frame after the loss of packets " + run.firstPacket() + " to " + run.lastPacket()
                                    + " is not silent");
                }
            }
        }
    }

    @Test
    @DisplayName("conceals Layer II frames")
    void layer2() {
        var options = Mp2EncoderOptions.defaults();
        PacketLoss.Encoded encoded;
        try (var encoder = new Mp2Encoder(options)) {
            encoded = PacketLoss.encode(encoder, PacketLoss.signal(encoder.sampleRate(), encoder.layout().channels(),
                    3));
        }
        PacketLoss.assertConceals(() -> new Mp3Decoder(encoded.track()), encoded.packets(),
                PacketLoss.bursts(15, 1, 40, 4), BOUNDS);
    }

    @ParameterizedTest(name = "{0} Hz {1} channels")
    @CsvSource({"44100, 2, 128000", "22050, 1, 32000"})
    @DisplayName("agrees with ffmpeg decoding the stream without its lost frames, one frame after each loss")
    void againstFfmpeg(int rate, int channels, int bitrate) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var options = Mp3EncoderOptions.builder().format(rate, ChannelLayout.defaultFor(channels)).cbr(bitrate)
                .bitReservoir(false).build();
        var encoded = encode(options, 3);
        var packets = encoded.packets();
        Set<Integer> lost = PacketLoss.bursts(20, 1, 45, 3, 80, 2);
        var reference = PacketLoss.decode(new Mp3Decoder(encoded.track()), packets);
        var lossy = PacketLoss.decode(new Mp3Decoder(encoded.track()), packets, lost, reference);
        var kept = new ByteArrayOutputStream();
        for (var i = 0; i < packets.size(); i++) {
            if (!lost.contains(i)) {
                kept.write(packets.get(i).data());
            }
        }
        var file = Scratch.directory("mp3-concealment").resolve("mp3-loss-" + rate + "-" + channels + ".mp3");
        Files.write(file, kept.toByteArray());
        var ffmpeg = PacketLoss.deinterleave(Mp3DecoderTest.floatReference(file, "mp3float"), channels);
        var frame = packets.getFirst().duration();
        var position = 0;
        var compared = 0;
        for (var i = 0; i < packets.size(); i++) {
            if (lost.contains(i)) {
                continue;
            }
            if (i >= 2 && !lost.contains(i - 1) && !lost.contains(i - 2) && position + frame <= ffmpeg[0].length) {
                for (var c = 0; c < channels; c++) {
                    var ours = Arrays.copyOfRange(lossy.planes()[c], lossy.starts()[i], lossy.starts()[i] + (int) frame);
                    var theirs = Arrays.copyOfRange(ffmpeg[c], position, position + (int) frame);
                    AFTER_RECOVERY.assertPcm("frame " + i + " channel " + c, theirs, ours);
                }
                compared++;
            }
            position += (int) frame;
        }
        assertTrue(compared > packets.size() / 2, "compared " + compared + " frames");
    }

    @Test
    @DisplayName("rounds the span to whole frames and conceals with silence before the first frame")
    void units() {
        var options = Mp3EncoderOptions.builder().format(24000, ChannelLayout.MONO).cbr(32000).build();
        var encoded = encode(options, 0.5);
        var decoder = new Mp3Decoder(encoded.track());
        var handed = new int[1];
        assertEquals(1152, decoder.conceal(100, buffer -> {
            handed[0] += buffer.frames();
            for (var i = 0; i < buffer.frames(); i++) {
                assertEquals(0, buffer.plane(0)[i]);
            }
        }));
        assertEquals(1152, handed[0]);
        decoder.decode(encoded.packets().getFirst(), buffer -> { });
        assertEquals(576, decoder.conceal(500, buffer -> { }));
        assertEquals(576 * 3, decoder.conceal(576 * 3 + 200, buffer -> assertEquals(576, buffer.frames())));
        assertThrows(IllegalArgumentException.class, () -> decoder.conceal(0, buffer -> { }));
    }

    @Test
    @DisplayName("counts the frames it conceals")
    void counted() {
        var encoded = encode(Mp3EncoderOptions.builder().format(44100, ChannelLayout.STEREO).cbr(128000).build(), 0.5);
        var decoder = new Mp3Decoder(encoded.track());
        decoder.decode(encoded.packets().getFirst(), buffer -> { });
        var before = decoder.concealedFrames();
        decoder.conceal(3 * 1152, buffer -> { });
        assertEquals(before + 3, decoder.concealedFrames());
        assertEquals(Codec.MP3, encoded.track().codec());
    }
}
