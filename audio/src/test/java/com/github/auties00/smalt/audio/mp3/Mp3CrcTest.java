package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the CRC-16 check of {@link Mp3Decoder} and the concealment of the frames that fail it: streams with
 * valid CRCs (Smalt's encoder, the ISO/IEC 11172-4 Layer I, II and III compliance streams) decode with nothing
 * concealed and exactly as without the check, and a damaged or flagged frame is concealed alone.
 */
class Mp3CrcTest {
    private record Stream(AudioTrack track, List<Packet> packets) {
    }

    private static Stream demux(byte[] file) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(file))) {
            var track = AudioPipeline.firstAudio(demuxer);
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            return new Stream(track, packets);
        }
    }

    private static Mp3Decoder decoder(AudioTrack track, boolean check) {
        return new Mp3Decoder(track, Mp3DecoderOptions.builder().crcCheck(check).build());
    }

    private static float[] decode(Stream stream, Mp3Decoder decoder) {
        return AudioPipeline.decode(stream.track(), stream.packets(), decoder).interleaved();
    }

    private static Stream encoded(int rate, int channels, int kbps, Mp3Encoding.Shape shape) {
        var options = Mp3EncoderOptions.builder().format(rate, Mp3Encoding.layout(channels)).bitrate(kbps * 1000)
                .crc(true).build();
        var input = Mp3Encoding.signal(shape, channels, rate, rate * 2, 41);
        return demux(Mp3Encoding.concatenate(Mp3Encoding.encode(options, input)));
    }

    private static Stream withPacket(Stream stream, int index, Packet replacement) {
        var packets = new ArrayList<>(stream.packets());
        packets.set(index, replacement);
        return new Stream(stream.track(), packets);
    }

    private static double energy(float[] samples, int from, int to) {
        var sum = 0.0;
        for (var i = from; i < to; i++) {
            sum += samples[i] * samples[i];
        }
        return sum;
    }

    @Nested
    @DisplayName("valid CRC words")
    class Valid {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"44100:2:128", "48000:1:64", "22050:2:64", "8000:1:16"})
        @DisplayName("Smalt's CRC-protected MP3 frames all pass and decode exactly as with the check off")
        void encoder(String format) {
            var parts = format.split(":");
            var stream = encoded(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Mp3Encoding.Shape.MUSIC);
            assertTrue(MpegAudioHeader.hasCrc(MpegAudioHeader.read(stream.packets().getFirst().data(), 0)));
            var checked = decoder(stream.track(), true);
            var output = decode(stream, checked);
            assertEquals(0, checked.concealedFrames());
            assertArrayEquals(decode(stream, decoder(stream.track(), false)), output);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"l1-fl1", "l1-fl2", "l1-fl3", "l1-fl5", "l1-fl6", "l1-fl7", "l2-fl10", "l2-fl11",
                "l2-fl12", "l2-fl14", "l2-fl15", "l2-fl16", "l3-hecommon"})
        @DisplayName("the CRC-protected compliance streams of every layer pass with nothing concealed")
        void compliance(String name) {
            var stream = demux(Mp3Vectors.read(Mp3Vectors.fetch(name + ".bit")));
            var protectedFrames = stream.packets().stream()
                    .filter(packet -> MpegAudioHeader.hasCrc(MpegAudioHeader.read(packet.data(), 0))).count();
            assertTrue(protectedFrames > 0, name + " has no CRC-protected frame");
            var checked = decoder(stream.track(), true);
            var output = decode(stream, checked);
            assertEquals(0, checked.concealedFrames(), name + " frames failed their CRC");
            assertArrayEquals(decode(stream, decoder(stream.track(), false)), output);
        }
    }

    @Nested
    @DisplayName("damaged frames")
    class Damaged {
        @ParameterizedTest(name = "Layer III at {0} Hz")
        @ValueSource(ints = {44100, 22050})
        @DisplayName("a bit flipped in the side information conceals that frame alone, and only with the check on")
        void layer3(int rate) {
            var stream = encoded(rate, 2, 128 * rate / 44100, Mp3Encoding.Shape.SINE);
            var index = 20;
            var original = stream.packets().get(index);
            var data = original.data().clone();
            data[MpegAudioHeader.SIZE + 2 + 3] ^= 0x10;
            var damaged = withPacket(stream, index, new Packet(original.trackId(), data, original.pts(), original.dts(),
                    original.duration(), true));
            var checked = decoder(stream.track(), true);
            var concealed = decode(damaged, checked);
            assertEquals(1, checked.concealedFrames());
            var unchecked = decoder(stream.track(), false);
            var plain = decode(damaged, unchecked);
            assertEquals(0, unchecked.concealedFrames());
            assertFalse(java.util.Arrays.equals(concealed, plain));
            var clean = decode(stream, decoder(stream.track(), true));
            assertEquals(clean.length, concealed.length, "the concealed frame keeps its length");
            var frame = original.duration() * 2;
            var start = (int) (index * frame);
            var repeated = energy(concealed, start, (int) (start + frame));
            var reference = energy(clean, start, (int) (start + frame));
            assertTrue(repeated > 0.05 * reference && repeated < reference,
                    "concealment energy " + repeated + " against " + reference);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"l1-fl1", "l2-fl10"})
        @DisplayName("a damaged Layer I or II allocation conceals the frame, and a run of damaged frames fades to silence")
        void allocationRun(String name) {
            var stream = demux(Mp3Vectors.read(Mp3Vectors.fetch(name + ".bit")));
            var damaged = new Stream(stream.track(), new ArrayList<>(stream.packets()));
            for (var index = 10; index < 20; index++) {
                var original = stream.packets().get(index);
                var data = original.data().clone();
                data[MpegAudioHeader.SIZE + 2] ^= 0x01;
                damaged.packets().set(index, new Packet(original.trackId(), data, original.pts(), original.dts(),
                        original.duration(), true));
            }
            var checked = decoder(stream.track(), true);
            var output = decode(damaged, checked);
            assertEquals(10, checked.concealedFrames());
            var channels = MpegAudioHeader.channels(MpegAudioHeader.read(stream.packets().getFirst().data(), 0));
            var frame = MpegAudioHeader.samplesPerFrame(MpegAudioHeader.read(stream.packets().getFirst().data(), 0))
                    * channels;
            var clean = decode(stream, decoder(stream.track(), true));
            assertArrayEquals(java.util.Arrays.copyOf(clean, 10 * frame), java.util.Arrays.copyOf(output, 10 * frame),
                    "the frames before the damage decode as without it");
            var last = energy(output, 19 * frame, 20 * frame);
            assertEquals(0, last, 1e-12, "the tenth concealed frame in a row is silent");
            assertTrue(energy(output, 10 * frame, 11 * frame) > 0, "the first concealed frame repeats the signal");
        }

        @Test
        @DisplayName("a packet flagged corrupt is concealed even without a CRC word")
        void flaggedCorrupt() {
            var options = Mp3EncoderOptions.defaults();
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.SINE, 2, 44100, 44100, 3);
            var stream = demux(Mp3Encoding.concatenate(Mp3Encoding.encode(options, input)));
            assertFalse(MpegAudioHeader.hasCrc(MpegAudioHeader.read(stream.packets().getFirst().data(), 0)));
            var original = stream.packets().get(12);
            var flagged = withPacket(stream, 12, original.withFlags(EnumSet.of(PacketFlag.KEY, PacketFlag.CORRUPT)));
            var checked = decoder(stream.track(), false);
            decode(flagged, checked);
            assertEquals(1, checked.concealedFrames());
        }

        @Test
        @DisplayName("a stream whose headers were rewritten after coding fails the CRC of every frame")
        void rewrittenHeaders() {
            var stream = demux(Mp3Vectors.read(Mp3Vectors.fetch("l2-nonstandard-free_format.bit")));
            var checked = decoder(stream.track(), true);
            decode(stream, checked);
            assertEquals(stream.packets().size(), checked.concealedFrames());
        }
    }
}
