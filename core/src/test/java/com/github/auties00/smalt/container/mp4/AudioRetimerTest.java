package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the sample counts {@link AudioRetimer} reads from the packets of the codecs without a
 * per-packet duration in their container: every sync frame of an AC-3 or E-AC-3 packet, every
 * storage-format frame of an AMR packet and every frame of PCM.
 */
@DisplayName("AudioRetimer")
class AudioRetimerTest {
    static AudioTrack track(Codec codec, int rate, ChannelLayout layout) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(1000)).sampleRate(rate)
                .layout(layout).build();
    }

    static byte[] concatenate(byte[] data, List<Ac3Configs.Frame> frames, int first, int count) {
        var start = 0;
        for (var index = 0; index < first; index++) {
            start += frames.get(index).length();
        }
        var length = 0;
        for (var index = first; index < first + count; index++) {
            length += frames.get(index).length();
        }
        return Arrays.copyOfRange(data, start, start + length);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ac3, AC3", "eac3, EAC3"})
    @DisplayName("counts every sync frame of a packet that holds several")
    void severalSyncFrames(String encoder, Codec codec) {
        var file = Fixtures.ffmpeg(encoder, List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.5"),
                List.of("-c:a", encoder, "-b:a", "192k", "-ac", "2"));
        var data = Fixtures.read(file);
        var frames = Ac3Configs.frames(data, 0, data.length);
        assertTrue(frames.size() >= 8, "frames " + frames.size());
        var retimer = new AudioRetimer(track(codec, 48000, ChannelLayout.STEREO), 48000);
        // two 1536-sample frames per packet, 64 ms apart at the Matroska millisecond clock
        for (var packet = 0; packet < 4; packet++) {
            var bytes = concatenate(data, frames, 2 * packet, 2);
            var retimed = retimer.retime(new Packet(1, bytes, 64L * packet, 64L * packet, 64, true), 1);
            assertEquals(3072L * packet, retimed.pts(), "pts of packet " + packet);
            assertEquals(3072, retimed.duration(), "duration of packet " + packet);
        }
    }

    @Test
    @DisplayName("counts the storage-format frames of an AMR packet and the frames of PCM")
    void amrAndPcm() {
        var amr = new byte[2 * 32];
        // AMR-NB 12.2 kbit/s frames: header byte 0x3C (frame type 7, quality bit) and 31 payload bytes
        amr[0] = 0x3C;
        amr[32] = 0x3C;
        var amrRetimer = new AudioRetimer(track(Codec.AMR_NB, 8000, ChannelLayout.MONO), 8000);
        assertEquals(320, amrRetimer.retime(new Packet(1, amr, 0, 0, 40, true), 1).duration());
        assertEquals(320, amrRetimer.retime(new Packet(1, amr, 40, 40, 40, true), 1).pts());
        var pcmRetimer = new AudioRetimer(track(Codec.PCM_S16LE, 48000, ChannelLayout.STEREO), 48000);
        // 1000 frames are 20.83 ms, which the millisecond timestamps round
        var first = pcmRetimer.retime(new Packet(1, new byte[4000], 0, 0, 21, true), 1);
        var second = pcmRetimer.retime(new Packet(1, new byte[4000], 21, 21, 21, true), 1);
        assertEquals(1000, first.duration());
        assertEquals(1000, second.pts());
    }
}
