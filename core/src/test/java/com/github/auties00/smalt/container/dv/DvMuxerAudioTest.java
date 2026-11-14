package com.github.auties00.smalt.container.dv;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.internal.header.DifAudio;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.DvValidator;
import com.github.auties00.smalt.testkit.validate.Findings;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the audio pairs of {@link DvMuxer}: tracks of up to eight channels spread over consecutive pairs, the 12-bit
 * four-channel mode of the IEC 61834 formats and the 720-line frame groups, read back by {@link DvDemuxer} and by
 * ffmpeg as a black box, and the test kit's {@link DvValidator} that checks every written stream.
 */
@DisplayName("DV muxer audio pairs")
class DvMuxerAudioTest {
    /**
     * A muxed stream and the stereo samples each pair should read back as.
     */
    private record Muxed(byte[] data, List<byte[]> pairs) {
    }

    private static Muxed mux(DifProfile profile, int rate, int frameCount, int... channels) {
        var video = VideoTrack.builder().id(1).codec(Codec.DV).timeBase(profile.frameRate().invert())
                .width(profile.width()).height(profile.height()).build();
        var tracks = new ArrayList<Track>(List.of(video));
        var packets = new ArrayList<Packet>();
        var pairs = new ArrayList<byte[]>();
        var twelveBit = Arrays.stream(channels).map(count -> (count + 1) / 2).sum() > (profile.progressive() ? 4
                : profile.channels());
        var firstPair = 0;
        for (var t = 0; t < channels.length; t++) {
            var group = profile.progressive() ? firstPair / 2 : 0;
            var audioFrames = 0;
            for (var f = 0; f < frameCount; f++) {
                if (!profile.progressive() || f % 2 == group) {
                    audioFrames++;
                }
            }
            var needed = 0;
            for (var f = 0; f < audioFrames; f++) {
                needed += DifAudio.samplesPerFrame(profile, rate, f);
            }
            var tone = PcmPackets.tone(Codec.PCM_S16LE, rate, ChannelLayout.defaultFor(channels[t]), needed);
            var track = tone.track().toBuilder().id(2 + t).build();
            tracks.add(track);
            for (var packet : tone.packets()) {
                packets.add(new Packet(track.id(), packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
            }
            var samples = tone.bytes();
            for (var pair = 0; pair < (channels[t] + 1) / 2; pair++) {
                var out = new ByteArrayOutputStream();
                for (var n = 0; n < needed; n++) {
                    var left = 2 * pair;
                    var right = Math.min(2 * pair + 1, channels[t] - 1);
                    for (var channel : new int[]{left, right}) {
                        var at = 2 * (n * channels[t] + channel);
                        var value = (short) ((samples[at] & 0xFF) | samples[at + 1] << 8);
                        int stored = twelveBit ? DifAudio.expand(DifAudio.compress(value))
                                : value == Short.MIN_VALUE ? Short.MIN_VALUE + 1 : value;
                        out.write(stored);
                        out.write(stored >> 8);
                    }
                }
                pairs.add(out.toByteArray());
            }
            firstPair += (channels[t] + 1) / 2;
        }
        for (var i = 0; i < frameCount; i++) {
            packets.add(new Packet(1, DvContainerTest.skeleton(profile, false, i), i, i, 1, true));
        }
        return new Muxed(MuxSupport.toMemory(tracks, packets, DvMuxer::new), pairs);
    }

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of(DifProfile.DVCPRO50_625, 48000, new int[]{4}, true),
                Arguments.of(DifProfile.DVCPRO50_525, 48000, new int[]{1, 2}, true),
                Arguments.of(DifProfile.DVCPRO_HD_1080I60, 48000, new int[]{6}, true),
                Arguments.of(DifProfile.DVCPRO_HD_1080I50, 48000, new int[]{8}, true),
                Arguments.of(DifProfile.DVCPRO_HD_1080I50, 48000, new int[]{3, 2}, true),
                Arguments.of(DifProfile.DVCPRO_HD_720P50, 48000, new int[]{4, 4}, false),
                Arguments.of(DifProfile.DVCPRO_HD_720P60, 48000, new int[]{2, 1, 3}, false),
                Arguments.of(DifProfile.DV_525, 32000, new int[]{4}, true),
                Arguments.of(DifProfile.DV_625, 32000, new int[]{2, 2}, true),
                Arguments.of(DifProfile.DV_625, 32000, new int[]{3}, true));
    }

    @ParameterizedTest(name = "{0} at {1} Hz, channels {2}")
    @MethodSource("layouts")
    @DisplayName("spreads multichannel tracks over consecutive pairs, read back exactly by Smalt and by ffmpeg")
    void pairs(DifProfile profile, int rate, int[] channels, boolean ffmpegReads) {
        var frameCount = 10;
        var muxed = mux(profile, rate, frameCount, channels);
        assertEquals(frameCount * profile.frameSize(), muxed.data().length);
        var summary = Validators.assertValidDv("pairs", muxed.data());
        var twelveBit = muxed.pairs().size() > (profile.progressive() ? 4 : profile.channels());
        for (var audio : summary.audio()) {
            assertEquals(rate, audio.sampleRate());
            assertEquals(twelveBit, audio.twelveBit());
        }
        var demuxed = MuxSupport.demux(muxed.data());
        assertEquals(1 + muxed.pairs().size(), demuxed.tracks().size());
        for (var pair = 0; pair < muxed.pairs().size(); pair++) {
            var track = (AudioTrack) demuxed.tracks().get(1 + pair);
            assertEquals(rate, track.sampleRate());
            assertArrayEquals(muxed.pairs().get(pair), PcmPackets.concatenate(demuxed.packetsOf(track)), "pair " + pair);
        }
        if (ffmpegReads && Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var file = MuxSupport.save("dv-pairs-" + profile + "-" + channels.length + ".dv", muxed.data());
            for (var pair = 0; pair < muxed.pairs().size(); pair++) {
                var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i",
                        file.toString(), "-map", "0:a:" + pair, "-f", "s16le", "-"));
                var expected = muxed.pairs().get(pair);
                assertTrue(reference.length >= expected.length * 9 / 10, "ffmpeg read " + reference.length);
                assertArrayEquals(Arrays.copyOf(expected, reference.length), reference, "ffmpeg pair " + pair);
            }
        }
    }

    private static DvMuxer muxer(DifProfile profile, int rate, int... channels) {
        var muxer = new DvMuxer(MediaSink.memory());
        muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.DV).timeBase(profile.frameRate().invert())
                .width(profile.width()).height(profile.height()).build());
        for (var t = 0; t < channels.length; t++) {
            muxer.addTrack(AudioTrack.builder().id(2 + t).codec(Codec.PCM_S16LE).timeBase(new Rational(1, rate))
                    .sampleRate(rate).layout(ChannelLayout.defaultFor(channels[t])).build());
        }
        return muxer;
    }

    private static void assertFirstFrameRejected(DifProfile profile, int rate, int... channels) {
        var muxer = muxer(profile, rate, channels);
        var frame = DvContainerTest.skeleton(profile, false, 0);
        assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1, frame, 0, 0, 1, true)),
                profile + " " + rate + " " + Arrays.toString(channels));
    }

    @Test
    @DisplayName("rejects tracks whose pairs a format cannot carry")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> muxer(DifProfile.DVCPRO_HD_1080I50, 48000, 9));
        assertThrows(IllegalArgumentException.class, () -> muxer(DifProfile.DVCPRO_HD_1080I50, 48000, 8, 2));
        // pairs 1 and 2 lie in different frames of a 720-line stream
        assertFirstFrameRejected(DifProfile.DVCPRO_HD_720P60, 48000, 2, 4);
        assertFirstFrameRejected(DifProfile.DVCPRO_HD_720P50, 48000, 6);
        // four channels at 25 Mb/s only at 32 kHz, and only in the consumer formats
        assertFirstFrameRejected(DifProfile.DV_525, 48000, 4);
        assertFirstFrameRejected(DifProfile.DV_625, 44100, 2, 2);
        assertFirstFrameRejected(DifProfile.DVCPRO_625, 32000, 4);
        assertFirstFrameRejected(DifProfile.DVCPRO_525, 48000, 2, 2);
        assertFirstFrameRejected(DifProfile.DV_525, 32000, 4, 2);
        assertFirstFrameRejected(DifProfile.DVCPRO50_625, 48000, 4, 2);
        var mixed = new DvMuxer(MediaSink.memory());
        mixed.addTrack(VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(1, 25)).width(720)
                .height(576).build());
        mixed.addTrack(AudioTrack.builder().id(2).codec(Codec.PCM_S16LE).timeBase(new Rational(1, 32000))
                .sampleRate(32000).layout(ChannelLayout.STEREO).build());
        mixed.addTrack(AudioTrack.builder().id(3).codec(Codec.PCM_S16LE).timeBase(new Rational(1, 48000))
                .sampleRate(48000).layout(ChannelLayout.STEREO).build());
        assertThrows(IllegalArgumentException.class, () -> mixed.writePacket(new Packet(1,
                DvContainerTest.skeleton(DifProfile.DV_625, false, 0), 0, 0, 1, true)));
    }

    @Test
    @DisplayName("pads the audio that never came with silence")
    void shortAudio() {
        var profile = DifProfile.DVCPRO50_625;
        var sink = MediaSink.memory();
        var given = 1920 + 100;
        var tone = PcmPackets.tone(Codec.PCM_S16LE, 48000, ChannelLayout.defaultFor(4), given);
        try (var writer = new DvMuxer(sink)) {
            writer.addTrack(VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(1, 25)).width(720)
                    .height(576).build());
            writer.addTrack(tone.track().toBuilder().id(2).build());
            for (var i = 0; i < 3; i++) {
                writer.writePacket(new Packet(1, DvContainerTest.skeleton(profile, false, i), i, i, 1, true));
            }
            for (var packet : tone.packets()) {
                writer.writePacket(new Packet(2, packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
            }
            writer.finish();
        }
        var data = sink.toByteArray();
        assertEquals(3 * profile.frameSize(), data.length);
        Validators.assertValidDv("short-audio", data);
        var demuxed = MuxSupport.demux(data);
        var source = tone.bytes();
        for (var pair = 0; pair < 2; pair++) {
            var samples = PcmPackets.concatenate(demuxed.packetsOf(demuxed.tracks().get(1 + pair)));
            assertEquals(3 * 1920 * 4, samples.length);
            for (var n = 0; n < 3 * 1920; n++) {
                for (var side = 0; side < 2; side++) {
                    var at = 4 * n + 2 * side;
                    var from = 2 * (4 * n + 2 * pair + side);
                    var expected = n < given ? new byte[]{source[from], source[from + 1]} : new byte[2];
                    assertArrayEquals(expected, new byte[]{samples[at], samples[at + 1]}, "pair " + pair + " sample "
                            + n);
                }
            }
        }
    }

    @Nested
    @DisplayName("DvValidator")
    class Validator {
        @ParameterizedTest
        @EnumSource(DifProfile.class)
        @DisplayName("accepts the frames of every format and recognizes them by their first bytes")
        void accepts(DifProfile profile) {
            var data = new ByteArrayOutputStream();
            for (var i = 0; i < 4; i++) {
                data.writeBytes(DvContainerTest.skeleton(profile, i == 0, i));
            }
            var bytes = data.toByteArray();
            assertEquals(Validators.Format.DV, Validators.sniff(bytes));
            var summary = Validators.assertValidDv(profile.toString(), bytes);
            assertNotNull(summary);
            assertEquals(4, summary.frames());
            assertEquals(profile.frameSize(), summary.frameSize());
            assertEquals(profile.fiftyHz(), summary.fiftyHz());
            assertEquals(profile.signalType(), summary.signalType());
            assertEquals(profile.applicationId(), summary.applicationId());
            if (!profile.highDefinition()) {
                assertTrue(summary.wide());
            }
        }

        private static List<String> problems(byte[] data) {
            var findings = new Findings("broken");
            DvValidator.validate(data, findings);
            return findings.problems();
        }

        private static void assertRejected(byte[] data, String expected) {
            var problems = problems(data);
            assertTrue(problems.stream().anyMatch(problem -> problem.contains(expected)),
                    "expected '" + expected + "' in " + problems);
        }

        @Test
        @DisplayName("rejects a truncated stream, a misplaced block, a changed format and impossible audio")
        void rejects() {
            var muxed = mux(DifProfile.DV_525, 48000, 6, 2).data();
            assertTrue(problems(muxed).isEmpty(), problems(muxed).toString());
            assertRejected(Arrays.copyOf(muxed, muxed.length - 80), "whole number");
            var moved = muxed.clone();
            // the ID of video block 1 of frame 2, sequence 3
            moved[2 * 120000 + 3 * 12000 + 8 * 80 + 2] = 7;
            assertRejected(moved, "block number");
            var flag = muxed.clone();
            flag[120000 + 12000 + 3] ^= (byte) 0x80;
            assertRejected(flag, "DIF sequence flag");
            var audio = muxed.clone();
            for (var sequence = 0; sequence < 10; sequence++) {
                for (var block = 0; block < 9; block++) {
                    var at = 120000 + sequence * 12000 + (6 + 16 * block) * 80 + 3;
                    if ((audio[at] & 0xFF) == 0x50) {
                        // SMP 3 is reserved
                        audio[at + 4] = (byte) (0x80 | 3 << 3);
                    }
                }
            }
            assertRejected(audio, "not an audio format");
            var size = muxed.clone();
            for (var sequence = 0; sequence < 5; sequence++) {
                for (var block = 0; block < 9; block++) {
                    var at = sequence * 12000 + (6 + 16 * block) * 80 + 3;
                    if ((size[at] & 0xFF) == 0x50) {
                        // 1620 samples, a locked 48 kHz audio frame of 1600 or 1602 in a 60 Hz system
                        size[at + 1] = (byte) ((size[at + 1] & 0xC0) | 40);
                    }
                }
            }
            assertRejected(size, "locked 48 kHz");
            var stype = muxed.clone();
            for (var block = 3; block < 6; block++) {
                for (var pack = 0; pack < 15; pack++) {
                    var at = 4 * 120000 + block * 80 + 3 + 5 * pack;
                    if ((stype[at] & 0xFF) == 0x60) {
                        stype[at + 3] = (byte) ((stype[at + 3] & 0xE0) | 0x04);
                    }
                }
            }
            assertRejected(stype, "signal type changes");
            assertRejected(new byte[100], "shorter than one DIF sequence");
        }
    }
}
