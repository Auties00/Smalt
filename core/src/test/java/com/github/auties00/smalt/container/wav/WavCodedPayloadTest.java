package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.testkit.audio.UnsizedSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the coded payloads of {@link WavDemuxer}: ffmpeg-written AC-3, E-AC-3, FLAC and AMR files
 * against ffprobe and the elementary stream demuxers, hand-built AAC, FLAC, AMR, Opus and IEC 61937
 * files around fixtures (IEC 61937 streams come from ffmpeg's {@code spdif} muxer), the Ambisonic
 * sub-formats, seeking through each elementary stream demuxer, and mutation robustness.
 */
@DisplayName("WavDemuxer coded payloads")
class WavCodedPayloadTest {
    private static final byte[] DDPLUS_GUID = HexFormat.of().parseHex("af87fba7022dfb42a4d405cd93843bdd");
    private static final byte[] DOLBY_AC3_GUID = HexFormat.of().parseHex("2c806de046dbcf11b4d100805f6cbbea");

    /** A WAVE file assembled from chunks. */
    static final class Wav {
        private final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        private final boolean bigEndian;

        Wav() {
            this(false);
        }

        Wav(boolean bigEndian) {
            this.bigEndian = bigEndian;
        }

        Wav chunk(String id, byte[] body) {
            chunks.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
            chunks.writeBytes(ByteBuffer.allocate(4).order(order()).putInt(body.length).array());
            chunks.writeBytes(body);
            if ((body.length & 1) != 0) {
                chunks.write(0);
            }
            return this;
        }

        ByteOrder order() {
            return bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        }

        byte[] format(int tag, int channels, int rate, int blockAlign, int bits, byte[] extra) {
            return ByteBuffer.allocate(18 + extra.length).order(order())
                    .putShort((short) tag).putShort((short) channels).putInt(rate).putInt(rate * Math.max(1, blockAlign))
                    .putShort((short) blockAlign).putShort((short) bits).putShort((short) extra.length).put(extra)
                    .array();
        }

        byte[] extensible(byte[] guid, int channels, int rate, int blockAlign, int bits, byte[] extra) {
            return ByteBuffer.allocate(40 + extra.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) 0xFFFE).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                    .putShort((short) blockAlign).putShort((short) bits).putShort((short) (22 + extra.length))
                    .putShort((short) bits).putInt(0).put(guid).put(extra).array();
        }

        byte[] build() {
            var body = chunks.toByteArray();
            var output = new ByteArrayOutputStream();
            output.writeBytes((bigEndian ? "RIFX" : "RIFF").getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(ByteBuffer.allocate(4).order(order()).putInt(body.length + 4).array());
            output.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(body);
            return output.toByteArray();
        }
    }

    static byte[] guid(int first, String rest) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(first).put(HexFormat.of().parseHex(rest))
                .array();
    }

    static byte[] iecGuid(int ceaType) {
        return guid(ceaType, "ea0c1000800000aa00389b71");
    }

    static byte[] ambisonicGuid(int tag) {
        return guid(tag, "2107d3118644c8c1ca000000");
    }

    /** Returns the offset of the body of the {@code fmt } chunk of a RIFF WAVE file. */
    static int formatChunk(byte[] wav) {
        var buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        for (var position = 12; position + 8 <= wav.length; position += 8 + (buffer.getInt(position + 4) + 1 & ~1)) {
            if (new String(wav, position, 4, StandardCharsets.US_ASCII).equals("fmt ")) {
                return position + 8;
            }
        }
        throw new AssertionError("no fmt chunk");
    }

    /** Returns the body of the {@code data} chunk of a RIFF WAVE file. */
    static byte[] dataChunk(byte[] wav) {
        var buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var position = 12;
        while (position + 8 <= wav.length) {
            var id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            var size = buffer.getInt(position + 4);
            if (id.equals("data")) {
                return Arrays.copyOfRange(wav, position + 8, position + 8 + size);
            }
            position += 8 + size + (size & 1);
        }
        throw new AssertionError("no data chunk");
    }

    static List<Packet> packets(Demuxer demuxer) {
        return AudioPipeline.packets(demuxer, demuxer.tracks().getFirst().id());
    }

    static List<Packet> packets(byte[] file, Function<MediaSource, Demuxer> factory) {
        try (var demuxer = factory.apply(MediaSource.of(file))) {
            return packets(demuxer);
        }
    }

    static List<Packet> wavPackets(byte[] wav) {
        return packets(wav, WavDemuxer::new);
    }

    /** Asserts that two packet lists carry the same payloads, timestamps and durations. */
    static void assertSamePackets(List<Packet> expected, List<Packet> actual) {
        assertEquals(expected.size(), actual.size(), "packet count");
        for (var index = 0; index < expected.size(); index++) {
            var want = expected.get(index);
            var got = actual.get(index);
            assertArrayEquals(want.data(), got.data(), "payload of packet " + index);
            assertEquals(want.pts(), got.pts(), "pts of packet " + index);
            assertEquals(want.duration(), got.duration(), "duration of packet " + index);
            assertEquals(want.keyframe(), got.keyframe(), "key flag of packet " + index);
        }
    }

    static List<ProbedPacket> probed(List<Packet> packets) {
        return packets.stream().map(ProbedPacket::of).toList();
    }

    static Path tone(String extension, int rate, int channels, double seconds, String... options) {
        return Fixtures.tone(extension, 440, rate, channels, seconds, List.of(options));
    }

    @Nested
    @DisplayName("AC-3 and E-AC-3 sync frames")
    class Dolby {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"ac3, ac3, AC3", "eac3, eac3, EAC3"})
        @DisplayName("reads ffmpeg's files like ffprobe and like the raw stream demuxer")
        void ffmpeg(String encoder, String extension, Codec codec) {
            var wav = tone("wav", 48000, 2, 1.2, "-c:a", encoder, "-b:a", "192k");
            var raw = Fixtures.read(tone(extension, 48000, 2, 1.2, "-c:a", encoder, "-b:a", "192k"));
            var bytes = Fixtures.read(wav);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new Ac3Demuxer(MediaSource.of(raw))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(codec, track.codec());
                assertEquals(expected.tracks().getFirst(), track);
                assertEquals(expected.durationMicros(), demuxer.durationMicros());
                assertTrue(demuxer.isSeekable());
                var actual = packets(demuxer);
                assertEquals(ProbedPacket.probe(wav), probed(actual));
                assertSamePackets(packets(expected), actual);
            }
        }

        @Test
        @DisplayName("reads AC-3 announced by the DirectShow sub-format GUID and seeks like the raw stream")
        void directShowGuid() {
            var raw = Fixtures.read(tone("ac3", 48000, 2, 1.0, "-c:a", "ac3"));
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.extensible(DOLBY_AC3_GUID, 2, 48000, 1, 0, new byte[0]))
                    .chunk("data", raw).build();
            assertSamePackets(packets(raw, Ac3Demuxer::new), wavPackets(bytes));
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new Ac3Demuxer(MediaSource.of(raw))) {
                demuxer.seek(600_000);
                expected.seek(600_000);
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        @Test
        @DisplayName("reads E-AC-3 announced by the DDPLUS sub-format GUID")
        void ddplusGuid() {
            var raw = Fixtures.read(tone("eac3", 48000, 6, 0.6, "-c:a", "eac3"));
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.extensible(DDPLUS_GUID, 6, 48000, 1, 0, new byte[0]))
                    .chunk("data", raw).build();
            var actual = wavPackets(bytes);
            assertSamePackets(packets(raw, Ac3Demuxer::new), actual);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals(Codec.EAC3, demuxer.tracks().getFirst().codec());
                assertEquals(6, ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow().channels());
            }
        }
    }

    @Nested
    @DisplayName("IEC 61937 bursts")
    class Iec61937 {
        static Path raw(String codec) {
            return tone(codec, 48000, 2, 1.5, "-c:a", codec, "-b:a", "192k");
        }

        static byte[] spdif(Path raw) {
            return Fixtures.read(Fixtures.ffmpeg("spdif", List.of("-i", raw.toString()), List.of("-c", "copy", "-f", "spdif")));
        }

        static int rate(String codec) {
            return codec.equals("eac3") ? 192000 : 48000;
        }

        static byte[] swapped(byte[] data) {
            var copy = data.clone();
            for (var index = 0; index + 1 < copy.length; index += 2) {
                copy[index] = data[index + 1];
                copy[index + 1] = data[index];
            }
            return copy;
        }

        static byte[] wav(String signaling, String codec, byte[] bursts) {
            var rate = rate(codec);
            return switch (signaling) {
                case "pcm" -> {
                    var wav = new Wav();
                    yield wav.chunk("fmt ", wav.format(1, 2, rate, 4, 16, new byte[0])).chunk("data", bursts).build();
                }
                case "spdif-tag" -> {
                    var wav = new Wav();
                    yield wav.chunk("fmt ", wav.format(0x0092, 2, rate, 4, 16, new byte[0])).chunk("data", bursts).build();
                }
                case "guid" -> {
                    var wav = new Wav();
                    var cea = codec.equals("eac3") ? 10 : 2;
                    yield wav.chunk("fmt ", wav.extensible(iecGuid(cea), 2, rate, 4, 16, new byte[0]))
                            .chunk("data", bursts).build();
                }
                case "rifx" -> {
                    var wav = new Wav(true);
                    yield wav.chunk("fmt ", wav.format(1, 2, rate, 4, 16, new byte[0])).chunk("data", swapped(bursts))
                            .build();
                }
                default -> throw new IllegalArgumentException(signaling);
            };
        }

        @ParameterizedTest(name = "{1} signaled as {0}")
        @CsvSource({"pcm, ac3", "spdif-tag, ac3", "guid, ac3", "rifx, ac3", "pcm, eac3", "guid, eac3", "rifx, eac3"})
        @DisplayName("returns the access units of ffmpeg's spdif bursts with the raw stream's timestamps")
        void bursts(String signaling, String codec) {
            var raw = raw(codec);
            var bytes = wav(signaling, codec, spdif(raw));
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new Ac3Demuxer(MediaSource.of(Fixtures.read(raw)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var want = (AudioTrack) expected.tracks().getFirst();
                assertEquals(want.codec(), track.codec());
                assertEquals(want.sampleRate(), track.sampleRate());
                assertEquals(want.layout(), track.layout());
                assertEquals(want.duration(), track.duration());
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        @Test
        @DisplayName("matches ffprobe on an AC-3 burst stream stored as PCM")
        void ffprobe() {
            var bytes = wav("pcm", "ac3", spdif(raw("ac3")));
            var file = com.github.auties00.smalt.container.MuxSupport.save("iec61937-ac3.wav", bytes);
            assertEquals(ProbedPacket.probe(file), probed(wavPackets(bytes)));
        }

        @ParameterizedTest(name = "{0} at {1} us")
        @CsvSource({"ac3, 0", "ac3, 1", "ac3, 515000", "ac3, 1000000", "eac3, 300000", "eac3, 1499999", "ac3, 9000000"})
        @DisplayName("seeks to the access unit holding the target sample")
        void seek(String codec, long micros) {
            var raw = raw(codec);
            var bytes = wav("pcm", codec, spdif(raw));
            var all = packets(Fixtures.read(raw), Ac3Demuxer::new);
            var target = micros * 48000 / 1_000_000;
            var expected = all.stream().filter(packet -> packet.pts() + packet.duration() > target).toList();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertTrue(demuxer.isSeekable());
                packets(demuxer);
                demuxer.seek(micros);
                assertSamePackets(expected, packets(demuxer));
            }
        }

        @Test
        @DisplayName("follows bursts after leading silence, skips null and pause bursts, and drops a cut burst")
        void layout() {
            var bursts = spdif(raw("ac3"));
            var expected = packets(Fixtures.read(raw("ac3")), Ac3Demuxer::new);
            var data = new ByteArrayOutputStream();
            data.writeBytes(new byte[10_000]);
            // a pause burst (data type 3) of 32 bits and a null burst (data type 0) before the stream
            data.writeBytes(new byte[]{0x72, (byte) 0xF8, 0x1F, 0x4E, 0x03, 0x00, 0x20, 0x00, 0, 0, 0, 0});
            data.writeBytes(new byte[]{0x72, (byte) 0xF8, 0x1F, 0x4E, 0x00, 0x00, 0x00, 0x00});
            data.writeBytes(new byte[6144 - 20]);
            data.write(bursts, 0, bursts.length - 6144 + 100);
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.format(1, 2, 48000, 4, 16, new byte[0])).chunk("data", data.toByteArray())
                    .build();
            var actual = wavPackets(bytes);
            var shift = (10_000 + 6144) / 4;
            assertEquals(expected.size() - 1, actual.size());
            for (var index = 0; index < actual.size(); index++) {
                assertArrayEquals(expected.get(index).data(), actual.get(index).data());
                assertEquals(expected.get(index).pts() + shift, actual.get(index).pts());
            }
        }

        @Test
        @DisplayName("flags the access units of a burst whose error flag is set as corrupt")
        void errorFlag() {
            var bursts = spdif(raw("ac3"));
            bursts[6144 * 3 + 4] |= (byte) 0x80;
            var actual = wavPackets(wav("pcm", "ac3", bursts));
            for (var index = 0; index < actual.size(); index++) {
                assertEquals(index == 3, actual.get(index).flags().contains(PacketFlag.CORRUPT), "packet " + index);
                assertTrue(actual.get(index).keyframe());
            }
        }

        @Test
        @DisplayName("reads the bursts of a source that reads its input once")
        void stream() {
            var bytes = wav("pcm", "eac3", spdif(raw("eac3")));
            var expected = wavPackets(bytes);
            try (var source = MediaSource.of(new ByteArrayInputStream(bytes), bytes.length);
                 var demuxer = new WavDemuxer(source)) {
                assertSamePackets(expected, packets(demuxer));
            }
            try (var demuxer = new WavDemuxer(new UnsizedSource(bytes, 1000))) {
                assertSamePackets(expected, packets(demuxer));
            }
        }

        @Test
        @DisplayName("keeps PCM without bursts, or with bursts of other data types, as PCM")
        void pcm() {
            var bursts = spdif(raw("ac3"));
            for (var offset = 0; offset < bursts.length; offset += 6144) {
                bursts[offset + 4] = 0x0E;
            }
            var bytes = wav("pcm", "ac3", bursts);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals(Codec.PCM_S16LE, demuxer.tracks().getFirst().codec());
            }
            var beyond = new byte[200_000 + bursts.length];
            System.arraycopy(spdif(raw("ac3")), 0, beyond, 200_000, bursts.length);
            try (var demuxer = new WavDemuxer(MediaSource.of(wav("pcm", "ac3", beyond)))) {
                assertEquals(Codec.PCM_S16LE, demuxer.tracks().getFirst().codec(), "bursts past the probe stay PCM");
            }
            try (var demuxer = new WavDemuxer(MediaSource.of(wav("spdif-tag", "ac3", beyond)))) {
                assertEquals(Codec.AC3, demuxer.tracks().getFirst().codec(), "a declared stream is searched further");
            }
        }

        @Test
        @DisplayName("rejects declared bursts that are missing, of another data type, or of another sample format")
        void rejects() {
            var silent = wav("guid", "ac3", new byte[50_000]);
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(silent)));
            var dts = new Wav();
            for (var cea : new int[]{8, 9, 13, 14}) {
                // ATRAC, one-bit audio, DST and WMA Professional
                var dtsBytes = dts.chunk("fmt ", dts.extensible(iecGuid(cea), 2, 48000, 4, 16, new byte[0]))
                        .chunk("data", new byte[64]).build();
                dts = new Wav();
                var unsupported = assertThrows(MediaException.Unsupported.class,
                        () -> new WavDemuxer(MediaSource.of(dtsBytes)));
                assertEquals("wav iec61937 type " + cea, unsupported.format());
            }
            var mono = new Wav();
            var monoBytes = mono.chunk("fmt ", mono.format(0x0092, 1, 48000, 2, 16, new byte[0]))
                    .chunk("data", new byte[64]).build();
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(monoBytes)));
        }
    }

    @Nested
    @DisplayName("IEC 61937 bursts of MPEG audio, AAC, DTS and TrueHD")
    class Iec61937Families {
        static Path encode(String extension, int rate, String layout, double seconds, String... options) {
            var source = "aevalsrc=exprs=0.4*sin(2*PI*440*t)+0.1*sin(2*PI*1500*t)+0.05*(random(0)-0.5):c=" + layout
                    + ":s=" + rate + ":d=" + seconds;
            return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", source), List.of(options));
        }

        static byte[] spdif(Path raw, String... options) {
            var output = new ArrayList<>(List.of("-c", "copy", "-f", "spdif"));
            output.addAll(List.of(options));
            return Fixtures.read(Fixtures.ffmpeg("spdif", List.of("-i", raw.toString()), output));
        }

        static byte[] pcmWav(byte[] bursts) {
            var wav = new Wav();
            return wav.chunk("fmt ", wav.format(1, 2, 48000, 4, 16, new byte[0])).chunk("data", bursts).build();
        }

        static int burstType(byte[] bursts) {
            assertEquals(0xF872, (bursts[0] & 0xFF) | (bursts[1] & 0xFF) << 8, "a burst starts the data");
            return bursts[4] & 0x1F;
        }

        static List<byte[]> dtsFrames(byte[] raw) {
            var frames = new ArrayList<byte[]>();
            for (var offset = 0; offset + 11 <= raw.length; ) {
                var size = (((raw[offset + 5] & 0x03) << 12) | ((raw[offset + 6] & 0xFF) << 4) | ((raw[offset + 7] & 0xF0) >> 4)) + 1;
                frames.add(Arrays.copyOfRange(raw, offset, offset + size));
                offset += size;
            }
            return frames;
        }

        static List<byte[]> trueHdUnits(byte[] raw) {
            var units = new ArrayList<byte[]>();
            for (var offset = 0; offset + 4 <= raw.length; ) {
                var size = (((raw[offset] & 0x0F) << 8) | (raw[offset + 1] & 0xFF)) * 2;
                units.add(Arrays.copyOfRange(raw, offset, offset + size));
                offset += size;
            }
            return units;
        }

        static List<List<Long>> sizesAndHashes(List<ProbedPacket> packets) {
            return packets.stream().map(packet -> List.of((long) packet.size(), packet.crc32())).toList();
        }

        static boolean majorSync(byte[] unit) {
            return unit.length >= 8 && (unit[4] & 0xFF) == 0xF8 && unit[5] == 0x72 && unit[6] == 0x6F
                    && (unit[7] & 0xFF) == 0xBA;
        }

        @ParameterizedTest(name = "{0} at {1} Hz, {2}, data type {4}")
        @CsvSource({"mp2, 48000, stereo, 192k, 5", "mp2, 44100, mono, 96k, 5", "mp2, 24000, stereo, 96k, 9",
                "mp2, 16000, mono, 48k, 9", "libmp3lame, 48000, stereo, 128k, 5", "libmp3lame, 32000, mono, 64k, 5",
                "libmp3lame, 24000, stereo, 64k, 10", "libmp3lame, 22050, mono, 32k, 10"})
        @DisplayName("returns the frames of ffmpeg's MPEG audio bursts like the MPEG audio demuxer")
        void mpeg(String encoder, int rate, String layout, String bitrate, int type) {
            var raw = encode(encoder.equals("mp2") ? "mp2" : "mp3", rate, layout, 1.5, "-c:a", encoder, "-b:a", bitrate);
            var bursts = spdif(raw);
            assertEquals(type, burstType(bursts));
            try (var demuxer = new WavDemuxer(MediaSource.of(pcmWav(bursts)));
                 var expected = new MpegAudioDemuxer(MediaSource.of(Fixtures.read(raw)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var want = (AudioTrack) expected.tracks().getFirst();
                assertEquals(want.codec(), track.codec());
                assertEquals(want.sampleRate(), track.sampleRate());
                assertEquals(want.layout(), track.layout());
                var packets = packets(demuxer);
                assertSamePackets(packets(expected), packets);
                var end = packets.getLast().pts() + packets.getLast().duration();
                assertTrue(track.duration() >= end && track.duration() < end + 1152, "duration " + track.duration());
            }
        }

        /** Returns bursts of hand-built silent Layer I frames of 48 kHz MPEG-1 or 24 kHz MPEG-2, and the frames. */
        static byte[][] layerOne(boolean lsf, int count) {
            // bitrate index 8: 256 kbit/s for MPEG-1 at 48 kHz, 128 kbit/s for MPEG-2 at 24 kHz, 256 bytes each
            var frameSize = 256;
            var period = (lsf ? 768 : 384) * 4;
            var frame = new byte[frameSize];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) (lsf ? 0xF7 : 0xFF);
            frame[2] = (byte) 0x84;
            frame[3] = 0x00;
            var frames = new ByteArrayOutputStream();
            var bursts = new ByteArrayOutputStream();
            for (var index = 0; index < count; index++) {
                frames.writeBytes(frame);
                var burst = ByteBuffer.allocate(period).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xF872)
                        .putShort((short) 0x4E1F).putShort((short) (lsf ? 8 : 4)).putShort((short) (frameSize * 8));
                for (var at = 0; at < frameSize; at += 2) {
                    burst.put(frame[at + 1]).put(frame[at]);
                }
                bursts.writeBytes(burst.array());
            }
            return new byte[][]{bursts.toByteArray(), frames.toByteArray()};
        }

        @ParameterizedTest(name = "MPEG-2 half rate {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("returns hand-built Layer I bursts of data types 4 and 8 like the MPEG audio demuxer")
        void layerOne(boolean lsf) {
            var built = layerOne(lsf, 40);
            try (var demuxer = new WavDemuxer(MediaSource.of(pcmWav(built[0])));
                 var expected = new MpegAudioDemuxer(MediaSource.of(built[1]))) {
                assertEquals(Codec.MP2, demuxer.tracks().getFirst().codec());
                assertEquals(lsf ? 24000 : 48000, ((AudioTrack) demuxer.tracks().getFirst()).sampleRate());
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        /** Returns little-endian bursts of a data type, one payload per repetition period of {@code period} bytes. */
        static byte[] bursts(int type, int period, List<byte[]> payloads) {
            var out = new ByteArrayOutputStream();
            for (var payload : payloads) {
                var burst = ByteBuffer.allocate(period).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xF872)
                        .putShort((short) 0x4E1F).putShort((short) type).putShort((short) (payload.length * 8));
                var even = Arrays.copyOf(payload, payload.length + (payload.length & 1));
                burst.put(Iec61937.swapped(even));
                out.writeBytes(burst.array());
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("keeps the MPEG-2 extension bitstream of data type 6 bursts with the base frame")
        void mpeg2Extension() {
            var raw = encode("mp2", 48000, "stereo", 0.6, "-c:a", "mp2", "-b:a", "192k");
            var frames = new ArrayList<byte[]>();
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(Fixtures.read(raw)))) {
                packets(demuxer).forEach(packet -> frames.add(packet.data()));
            }
            var extension = new byte[120];
            new java.util.SplittableRandom(6).nextBytes(extension);
            extension[0] = 0x7F;
            extension[1] = (byte) 0xF0;
            var payloads = frames.stream().map(frame -> Opus.concat(frame, extension)).toList();
            var packets = wavPackets(pcmWav(bursts(6, 1152 * 4, payloads)));
            assertEquals(frames.size(), packets.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(payloads.get(index), packets.get(index).data());
                assertEquals(1152L * index, packets.get(index).pts());
                assertEquals(1152, packets.get(index).duration());
            }
        }

        @Test
        @DisplayName("returns the raw data blocks of MPEG-4 AAC bursts in LOAS framing like the LOAS reader")
        void loas() {
            var latm = Aac.latm(2, 1.0);
            var frames = new ArrayList<byte[]>();
            for (var offset = 0; offset + 3 <= latm.length; ) {
                var length = (((latm[offset + 1] & 0x1F) << 8) | (latm[offset + 2] & 0xFF)) + 3;
                frames.add(Arrays.copyOfRange(latm, offset, offset + length));
                offset += length;
            }
            var loasWav = new Wav();
            var expected = wavPackets(loasWav.chunk("fmt ", loasWav.format(0x1602, 2, 44100, 1, 0, new byte[0]))
                    .chunk("data", latm).build());
            var bytes = pcmWav(bursts(20, 4096, frames));
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var reference = new WavDemuxer(MediaSource.of(
                    new Wav().chunk("fmt ", new Wav().format(0x1602, 2, 44100, 1, 0, new byte[0])).chunk("data", latm)
                            .build()))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var want = (AudioTrack) reference.tracks().getFirst();
                assertEquals(Codec.AAC, track.codec());
                assertEquals(want.sampleRate(), track.sampleRate());
                assertEquals(want.layout(), track.layout());
                assertArrayEquals(want.codecConfig().orElseThrow(), track.codecConfig().orElseThrow());
                assertSamePackets(expected, packets(demuxer));
            }
        }

        @Test
        @DisplayName("converts DTS frames of the 14-bit form in type I bursts to the 16-bit form")
        void packedDts() {
            var raw = encode("dts", 48000, "stereo", 0.6, "-strict", "-2", "-c:a", "dca", "-b:a", "768k");
            var frames = dtsFrames(Fixtures.read(raw));
            var payloads = new ArrayList<byte[]>();
            for (var frame : frames) {
                var packed = Iec61937.swapped(DtsFrames.fourteenBit(List.of(frame), true));
                assertTrue(packed.length <= 2048 - 8);
                payloads.add(packed);
            }
            var packets = wavPackets(pcmWav(bursts(11, 2048, payloads)));
            assertEquals(frames.size(), packets.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index), packets.get(index).data(), "frame " + index);
                assertEquals(512L * index, packets.get(index).pts());
            }
        }

        /** Merges every {@code count} ADTS frames of a stream into one frame of {@code count} raw data blocks. */
        static byte[] mergeAdts(byte[] adts, int count) {
            var frames = new ArrayList<byte[]>();
            for (var offset = 0; offset + 7 <= adts.length; ) {
                var length = ((adts[offset + 3] & 3) << 11) | ((adts[offset + 4] & 0xFF) << 3) | ((adts[offset + 5] & 0xFF) >> 5);
                frames.add(Arrays.copyOfRange(adts, offset, offset + length));
                offset += length;
            }
            var out = new ByteArrayOutputStream();
            for (var index = 0; index + count <= frames.size(); index += count) {
                var header = Arrays.copyOf(frames.get(index), 7);
                var body = new ByteArrayOutputStream();
                for (var frame = index; frame < index + count; frame++) {
                    body.write(frames.get(frame), 7, frames.get(frame).length - 7);
                }
                var length = 7 + body.size();
                header[3] = (byte) ((header[3] & 0xFC) | (length >> 11));
                header[4] = (byte) (length >> 3);
                header[5] = (byte) (((length & 7) << 5) | (header[5] & 0x1F));
                header[6] = (byte) ((header[6] & 0xFC) | (count - 1));
                out.writeBytes(header);
                out.writeBytes(body.toByteArray());
            }
            return out.toByteArray();
        }

        @ParameterizedTest(name = "{0} Hz, {1}, {2} blocks per frame")
        @CsvSource({"48000, stereo, 1, 7", "24000, mono, 1, 7", "44100, stereo, 2, 19", "48000, 5.1, 4, 19"})
        @DisplayName("returns the raw data blocks of ffmpeg's AAC bursts like the ADTS demuxer")
        void aac(int rate, String layout, int blocks, int type) {
            var adts = Fixtures.read(encode("aac", rate, layout, 1.5, "-c:a", "aac", "-b:a", "128k"));
            var merged = blocks == 1 ? adts : mergeAdts(adts, blocks);
            var raw = com.github.auties00.smalt.container.MuxSupport.save("iec-aac-" + rate + "-" + blocks + ".aac", merged);
            var bursts = spdif(raw);
            assertEquals(type, burstType(bursts));
            try (var demuxer = new WavDemuxer(MediaSource.of(pcmWav(bursts))); var expected = new AdtsDemuxer(MediaSource.of(merged))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var want = (AudioTrack) expected.tracks().getFirst();
                assertEquals(want.codec(), track.codec());
                assertEquals(want.sampleRate(), track.sampleRate());
                assertEquals(want.layout(), track.layout());
                assertArrayEquals(want.codecConfig().orElseThrow(), track.codecConfig().orElseThrow());
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        static Path dts(String layout) {
            return encode("dts", 48000, layout, 1.5, "-strict", "-2", "-c:a", "dca");
        }

        @ParameterizedTest(name = "{0}, DTS-HD bursts {1}")
        @CsvSource({"stereo, false", "mono, false", "5.1, false", "quad, false", "stereo, true", "5.1, true"})
        @DisplayName("returns the frames of ffmpeg's DTS bursts like ffprobe reads the raw stream")
        void dts(String layout, boolean hd) {
            var raw = dts(layout);
            var bursts = hd ? spdif(raw, "-dtshd_rate", "192000") : spdif(raw);
            assertEquals(hd ? 17 : 11, burstType(bursts));
            var frames = dtsFrames(Fixtures.read(raw));
            try (var demuxer = new WavDemuxer(MediaSource.of(pcmWav(bursts)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.DTS, track.codec());
                assertEquals(48000, track.sampleRate());
                var speakers = switch (layout) {
                    case "mono" -> List.of(Speaker.FRONT_CENTER);
                    case "stereo" -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT);
                    case "quad" -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT);
                    default -> List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                            Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT);
                };
                assertEquals(speakers, track.layout().orElseThrow().speakers());
                var packets = packets(demuxer);
                assertEquals(frames.size(), packets.size());
                for (var index = 0; index < frames.size(); index++) {
                    assertArrayEquals(frames.get(index), packets.get(index).data(), "frame " + index);
                    assertEquals(512L * index, packets.get(index).pts());
                    assertEquals(512, packets.get(index).duration());
                }
                assertEquals(sizesAndHashes(ProbedPacket.probe(raw)), sizesAndHashes(probed(packets)));
                assertEquals(bursts.length / 4 / (hd ? 4 : 1), track.duration());
            }
        }

        static Path trueHdStream(int rate, String layout, double noise) {
            var source = "aevalsrc=exprs=0.4*sin(2*PI*440*t)+" + noise + "*(random(0)-0.5):c=" + layout + ":s=" + rate
                    + ":d=1.2";
            return Fixtures.ffmpeg("thd", List.of("-f", "lavfi", "-i", source), List.of("-strict", "-2", "-c:a", "truehd"));
        }

        @ParameterizedTest(name = "{0} Hz, {1}, noise {2}")
        @CsvSource({"48000, stereo, 0.01", "48000, 5.1, 0.6", "96000, stereo, 0.6", "44100, mono, 0.01",
                "192000, stereo, 0.3"})
        @DisplayName("returns the access units of ffmpeg's MAT bursts like ffprobe reads the raw TrueHD stream")
        void trueHd(int rate, String layout, double noise) {
            var raw = trueHdStream(rate, layout, noise);
            var bursts = spdif(raw);
            assertEquals(22, burstType(bursts));
            var units = trueHdUnits(Fixtures.read(raw));
            var samples = 40 * (rate % 44100 == 0 ? rate / 44100 : rate / 48000);
            try (var demuxer = new WavDemuxer(MediaSource.of(pcmWav(bursts)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.TRUEHD, track.codec());
                assertEquals(rate, track.sampleRate());
                assertEquals(switch (layout) {
                    case "mono" -> 1;
                    case "stereo" -> 2;
                    default -> 6;
                }, track.layout().orElseThrow().channels());
                var packets = packets(demuxer);
                // ffmpeg's spdif muxer does not write the last MAT frame
                assertTrue(packets.size() <= units.size() && packets.size() >= units.size() - 24, packets.size()
                        + " of " + units.size());
                assertEquals(0, packets.size() % 24);
                for (var index = 0; index < packets.size(); index++) {
                    assertArrayEquals(units.get(index), packets.get(index).data(), "unit " + index);
                    assertEquals((long) samples * index, packets.get(index).pts());
                    assertEquals(samples, packets.get(index).duration());
                    assertEquals(majorSync(units.get(index)), packets.get(index).keyframe(), "key flag of unit " + index);
                }
                assertEquals(sizesAndHashes(ProbedPacket.probe(raw)).subList(0, packets.size()),
                        sizesAndHashes(probed(packets)));
                assertEquals(bursts.length / 4 * 24L * samples / 15360, track.duration());
            }
        }

        @Test
        @DisplayName("splits TrueHD access units packed back to back across the middle code and across MAT frames")
        void matPacked() {
            // ffmpeg places each unit in its own 2560-byte slot, so none crosses a MAT code; pack them back to back
            var raw = trueHdStream(48000, "5.1", 0.6);
            var units = trueHdUnits(Fixtures.read(raw));
            var codes = Iec61937.swapped(Arrays.copyOfRange(spdif(raw), 8, 8 + 61424));
            var stream = new ByteArrayOutputStream();
            units.forEach(stream::writeBytes);
            var data = stream.toByteArray();
            var bursts = new ByteArrayOutputStream();
            var acrossMiddle = 0;
            var acrossFrames = 0;
            var consumed = 0;
            var unitEnd = 0;
            var nextUnit = 0;
            while (consumed < data.length) {
                var payload = new byte[61424];
                System.arraycopy(codes, 0, payload, 0, 20);
                System.arraycopy(codes, 30708, payload, 30708, 12);
                System.arraycopy(codes, 61408, payload, 61408, 16);
                for (var region : new int[][]{{20, 30708}, {30720, 61408}}) {
                    var count = Math.min(region[1] - region[0], data.length - consumed);
                    System.arraycopy(data, consumed, payload, region[0], count);
                    consumed += count;
                    while (nextUnit < units.size() && unitEnd + units.get(nextUnit).length <= consumed) {
                        unitEnd += units.get(nextUnit++).length;
                    }
                    if (nextUnit < units.size() && unitEnd < consumed) {
                        if (region[0] == 20) {
                            acrossMiddle++;
                        } else {
                            acrossFrames++;
                        }
                    }
                }
                var burst = ByteBuffer.allocate(61440).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xF872)
                        .putShort((short) 0x4E1F).putShort((short) 22).putShort((short) 61424).put(Iec61937.swapped(payload));
                bursts.writeBytes(burst.array());
            }
            assertTrue(acrossMiddle > 3 && acrossFrames > 3, acrossMiddle + " units cross the middle code, " + acrossFrames
                    + " cross frames");
            var packets = wavPackets(pcmWav(bursts.toByteArray()));
            assertEquals(units.size(), packets.size());
            for (var index = 0; index < units.size(); index++) {
                assertArrayEquals(units.get(index), packets.get(index).data(), "unit " + index);
                assertEquals(40L * index, packets.get(index).pts());
                assertEquals(majorSync(units.get(index)), packets.get(index).keyframe());
            }
        }

        @ParameterizedTest(name = "{0} at {1} us")
        @CsvSource({"dts, 0", "dts, 400000", "dts, 1499999", "mp2, 700000", "aac, 333333", "truehd, 0", "truehd, 1",
                "truehd, 555555", "truehd, 1100000", "truehd, 5000000"})
        @DisplayName("seeks to the access unit holding the target sample, or for TrueHD the last major sync before it")
        void seek(String codec, long micros) {
            var raw = switch (codec) {
                case "dts" -> dts("5.1");
                case "mp2" -> encode("mp2", 24000, "stereo", 1.5, "-c:a", "mp2", "-b:a", "96k");
                case "aac" -> encode("aac", 48000, "stereo", 1.5, "-c:a", "aac");
                default -> trueHdStream(48000, "5.1", 0.6);
            };
            var bytes = pcmWav(spdif(raw));
            var all = wavPackets(bytes);
            var rate = codec.equals("mp2") ? 24000 : 48000;
            var target = micros * rate / 1_000_000;
            var first = 0;
            for (var index = 0; index < all.size(); index++) {
                var packet = all.get(index);
                if (codec.equals("truehd") ? packet.keyframe() && packet.pts() <= target : packet.pts() + packet.duration() <= target) {
                    first = codec.equals("truehd") ? index : index + 1;
                }
            }
            var end = all.getLast().pts() + all.getLast().duration();
            var expected = target >= end ? List.<Packet>of() : all.subList(Math.min(first, all.size()), all.size());
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                packets(demuxer);
                demuxer.seek(micros);
                assertSamePackets(expected, packets(demuxer));
                demuxer.seek(micros);
                assertSamePackets(expected, packets(demuxer));
            }
        }

        static byte[] declared(int tag, byte[] guid, byte[] bursts, boolean bigEndian) {
            var wav = new Wav(bigEndian);
            var format = guid == null ? wav.format(tag, 2, 48000, 4, 16, new byte[0])
                    : wav.extensible(guid, 2, 48000, 4, 16, new byte[0]);
            return wav.chunk("fmt ", format).chunk("data", bigEndian ? Iec61937.swapped(bursts) : bursts).build();
        }

        @Test
        @DisplayName("reads bursts declared by the IEC 61937 sub-formats and WAVE_FORMAT_DTS, and in RIFX")
        void declarations() {
            var dts = spdif(dts("stereo"));
            var expected = wavPackets(pcmWav(dts));
            assertSamePackets(expected, wavPackets(declared(0x0008, null, dts, false)));
            assertSamePackets(expected, wavPackets(declared(0, guid(0x0008, "00001000800000aa00389b71"), dts, false)));
            assertSamePackets(expected, wavPackets(declared(0, iecGuid(7), dts, false)));
            assertSamePackets(expected, wavPackets(declared(1, null, dts, true)));
            var hd = spdif(dts("stereo"), "-dtshd_rate", "192000");
            assertSamePackets(wavPackets(pcmWav(hd)), wavPackets(declared(0, iecGuid(11), hd, false)));
            var mat = spdif(trueHdStream(48000, "stereo", 0.01));
            assertSamePackets(wavPackets(pcmWav(mat)), wavPackets(declared(0, iecGuid(0x10C), mat, false)));
            assertSamePackets(wavPackets(pcmWav(hd)), wavPackets(declared(0, iecGuid(0x10B), hd, false)));
            assertSamePackets(wavPackets(pcmWav(hd)), wavPackets(declared(0, iecGuid(0x30B), hd, false)));
            var mp3 = spdif(encode("mp3", 48000, "stereo", 0.5, "-c:a", "libmp3lame"));
            assertSamePackets(wavPackets(pcmWav(mp3)), wavPackets(declared(0, iecGuid(4), mp3, false)));
            assertSamePackets(wavPackets(pcmWav(mp3)), wavPackets(declared(1, null, mp3, true)));
            var aac = spdif(encode("aac", 48000, "stereo", 0.5, "-c:a", "aac"));
            assertSamePackets(wavPackets(pcmWav(aac)), wavPackets(declared(0, iecGuid(6), aac, false)));
        }

        @Test
        @DisplayName("flags the units of bursts whose error flag is set and follows TrueHD again after a damaged MAT frame")
        void damage() {
            var dts = spdif(dts("stereo"));
            dts[2048 * 5 + 4] |= (byte) 0x80;
            var packets = wavPackets(pcmWav(dts));
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index == 5, packets.get(index).flags().contains(PacketFlag.CORRUPT), "frame " + index);
            }
            var raw = trueHdStream(48000, "stereo", 0.01);
            var mat = spdif(raw);
            var clean = wavPackets(pcmWav(mat));
            var corrupt = mat.clone();
            corrupt[61440 * 2 + 4] |= (byte) 0x80;
            var flagged = wavPackets(pcmWav(corrupt));
            assertEquals(clean.size(), flagged.size());
            assertTrue(flagged.stream().filter(packet -> packet.flags().contains(PacketFlag.CORRUPT)).count() >= 20);
            var broken = mat.clone();
            // overwrite the middle code of the third MAT frame
            Arrays.fill(broken, 61440 * 2 + 8 + 30708, 61440 * 2 + 8 + 30708 + 12, (byte) 0);
            var resynced = wavPackets(pcmWav(broken));
            assertTrue(resynced.size() < clean.size());
            var last = resynced.getLast();
            assertEquals(clean.getLast().pts(), last.pts(), "the units after the damaged frame keep their timestamps");
            assertArrayEquals(clean.getLast().data(), last.data());
            assertTrue(resynced.stream().filter(packet -> packet.pts() >= 960 * 3).findFirst().orElseThrow().keyframe(),
                    "following resumes at a major sync");
        }

        @Test
        @DisplayName("stamps bursts after leading silence with their position, for MAT and DTS")
        void leadingSilence() {
            for (var raw : List.of(dts("stereo"), trueHdStream(48000, "stereo", 0.01))) {
                var bursts = spdif(raw);
                // within the 128 KiB a PCM file is probed for bursts
                var shift = raw.toString().endsWith(".thd") ? 61440 * 2 : 2048 * 7;
                var data = new byte[shift + bursts.length];
                System.arraycopy(bursts, 0, data, shift, bursts.length);
                var expected = wavPackets(pcmWav(bursts));
                var actual = wavPackets(pcmWav(data));
                assertEquals(expected.size(), actual.size());
                var offset = raw.toString().endsWith(".thd") ? 960 * 2 : 512 * 7;
                for (var index = 0; index < expected.size(); index++) {
                    assertArrayEquals(expected.get(index).data(), actual.get(index).data());
                    assertEquals(expected.get(index).pts() + offset, actual.get(index).pts());
                }
            }
        }
    }

    @Nested
    @DisplayName("DTS frames")
    class DtsFrames {
        static byte[] remuxed(Path raw) {
            return Fixtures.read(Fixtures.ffmpeg("wav", List.of("-i", raw.toString()), List.of("-c", "copy")));
        }

        static byte[] wav(byte[] data) {
            var wav = new Wav();
            return wav.chunk("fmt ", wav.format(0x2001, 2, 48000, 4, 16, new byte[0])).chunk("data", data).build();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"stereo", "5.1", "mono"})
        @DisplayName("returns the frames of ffmpeg's DTS in WAVE like ffprobe reads the raw stream")
        void ffmpeg(String layout) {
            var raw = Iec61937Families.dts(layout);
            var bytes = remuxed(raw);
            var tag = (bytes[20] & 0xFF) | (bytes[21] & 0xFF) << 8;
            // ffmpeg declares 5.1 through WAVE_FORMAT_EXTENSIBLE with the KSDATAFORMAT_SUBTYPE GUID of the tag
            assertEquals(0x2001, tag == 0xFFFE ? (bytes[44] & 0xFF) | (bytes[45] & 0xFF) << 8 : tag, "ffmpeg's format tag");
            var frames = Iec61937Families.dtsFrames(Fixtures.read(raw));
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.DTS, track.codec());
                assertEquals(48000, track.sampleRate());
                assertEquals(layout.equals("5.1") ? 6 : layout.equals("mono") ? 1 : 2, track.layout().orElseThrow().channels());
                assertEquals(512L * frames.size(), track.duration());
                var packets = packets(demuxer);
                assertEquals(frames.size(), packets.size());
                for (var index = 0; index < frames.size(); index++) {
                    assertArrayEquals(frames.get(index), packets.get(index).data());
                    assertEquals(512L * index, packets.get(index).pts());
                    assertEquals(512, packets.get(index).duration());
                }
                assertEquals(Iec61937Families.sizesAndHashes(ProbedPacket.probe(raw)),
                        Iec61937Families.sizesAndHashes(probed(packets)));
            }
        }

        @Test
        @DisplayName("reads swapped words, skips junk between frames, keeps extension substreams and drops a cut frame")
        void variants() {
            var raw = Fixtures.read(Iec61937Families.dts("stereo"));
            var frames = Iec61937Families.dtsFrames(raw);
            var expected = wavPackets(wav(raw));
            assertSamePackets(expected, wavPackets(wav(Iec61937.swapped(raw))));
            var junk = new ByteArrayOutputStream();
            junk.writeBytes(new byte[]{1, 2, 3, 4});
            for (var index = 0; index < frames.size(); index++) {
                junk.writeBytes(frames.get(index));
                if (index % 7 == 3) {
                    junk.writeBytes(new byte[]{0x7F, (byte) 0xFE, 0x11, 0x22, 0x33, 0x44});
                }
            }
            assertSamePackets(expected, wavPackets(wav(junk.toByteArray())));
            // a DTS-HD extension substream after every core frame: sync, user bits, index 0, narrow sizes, 300 bytes
            var extension = new byte[300];
            new java.util.SplittableRandom(5).nextBytes(extension);
            System.arraycopy(new byte[]{0x64, 0x58, 0x20, 0x25, 0x00, 0x03, (byte) 0xE0, 0x25, 0x60, 0x00}, 0, extension, 0, 10);
            var extended = new ByteArrayOutputStream();
            frames.forEach(frame -> {
                extended.writeBytes(frame);
                extended.writeBytes(extension);
            });
            var packets = wavPackets(wav(extended.toByteArray()));
            assertEquals(frames.size(), packets.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(Opus.concat(frames.get(index), extension), packets.get(index).data());
                assertEquals(512L * index, packets.get(index).pts());
            }
            var cut = wavPackets(wav(Arrays.copyOf(raw, raw.length - 100)));
            assertSamePackets(expected.subList(0, expected.size() - 1), cut);
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(wav(new byte[5000]))));
        }

        @Test
        @DisplayName("reads WAVE_FORMAT_DTS data of DTS frames rather than bursts as DTS frames")
        void dtsTag() {
            var raw = Fixtures.read(Iec61937Families.dts("5.1"));
            var expected = wavPackets(wav(raw));
            for (var format : List.of(new int[]{2, 4, 16}, new int[]{6, 1, 0})) {
                var file = new Wav();
                var bytes = file.chunk("fmt ", file.format(0x0008, format[0], 48000, format[1], format[2], new byte[0]))
                        .chunk("data", raw).build();
                try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                    assertEquals(Codec.DTS, demuxer.tracks().getFirst().codec());
                    assertSamePackets(expected, packets(demuxer));
                }
            }
            var silent = new Wav();
            var none = silent.chunk("fmt ", silent.format(0x0008, 2, 48000, 4, 16, new byte[0])).chunk("data", new byte[9000])
                    .build();
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(none)));
        }

        @Test
        @DisplayName("follows frames of odd lengths in both 16-bit forms and skips sync words of invalid frame sizes")
        void oddLengths() {
            var frames = Iec61937Families.dtsFrames(Fixtures.read(Iec61937Families.dts("stereo")));
            // an extension substream of 301 bytes after every core frame: narrow sizes, header 32, size 301
            var extension = new byte[301];
            new java.util.SplittableRandom(7).nextBytes(extension);
            System.arraycopy(new byte[]{0x64, 0x58, 0x20, 0x25, 0x00, 0x03, (byte) 0xE0, 0x25, (byte) 0x80, 0x00}, 0,
                    extension, 0, 10);
            // a core sync word whose header declares a 12-byte frame, below the smallest valid size
            var invalid = Arrays.copyOf(frames.getFirst(), 12);
            invalid[5] = (byte) (invalid[5] & 0xFC);
            invalid[6] = 0;
            invalid[7] = (byte) (0xB0 | invalid[7] & 0x0F);
            var stream = new ByteArrayOutputStream();
            for (var index = 0; index < frames.size(); index++) {
                stream.writeBytes(frames.get(index));
                stream.writeBytes(extension);
                if (index % 5 == 2) {
                    stream.writeBytes(invalid);
                }
            }
            var big = Arrays.copyOf(stream.toByteArray(), stream.size() + (stream.size() & 1));
            for (var data : List.of(big, Iec61937.swapped(big))) {
                var packets = wavPackets(wav(data));
                assertEquals(frames.size(), packets.size());
                for (var index = 0; index < frames.size(); index++) {
                    assertArrayEquals(Opus.concat(frames.get(index), extension), packets.get(index).data(), "frame " + index);
                    assertEquals(512L * index, packets.get(index).pts());
                }
            }
        }

        /** Packs the 16-bit form of DTS frames into the 14-bit form, 14 bits per little- or big-endian word. */
        static byte[] fourteenBit(List<byte[]> frames, boolean littleEndian) {
            var out = new ByteArrayOutputStream();
            for (var frame : frames) {
                var bits = 0L;
                var count = 0;
                for (var index = 0; index <= frame.length; index++) {
                    if (index < frame.length) {
                        bits = bits << 8 | frame[index] & 0xFF;
                        count += 8;
                    } else if (count > 0) {
                        bits <<= 14 - count;
                        count = 14;
                    }
                    while (count >= 14) {
                        var word = (int) (bits >>> (count - 14)) & 0x3FFF;
                        count -= 14;
                        word = (word & 0x2000) != 0 ? word | 0xC000 : word;
                        out.write(littleEndian ? word : word >> 8);
                        out.write(littleEndian ? word >> 8 : word);
                    }
                }
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("reads 14-bit DTS frames in PCM samples, as on a DTS audio CD, which ffmpeg reads as DTS too")
        void fourteenBit() {
            var raw = Fixtures.ffmpeg("dts", List.of("-f", "lavfi", "-i",
                            "aevalsrc=exprs=0.4*sin(2*PI*440*t)|0.3*sin(2*PI*660*t):c=stereo:s=44100:d=1.2"),
                    List.of("-strict", "-2", "-c:a", "dca", "-b:a", "1411200"));
            var frames = Iec61937Families.dtsFrames(Fixtures.read(raw));
            for (var littleEndian : new boolean[]{true, false}) {
                var packed = fourteenBit(frames, littleEndian);
                var pcm = new Wav();
                var cd = pcm.chunk("fmt ", pcm.format(1, 2, 44100, 4, 16, new byte[0]))
                        .chunk("data", littleEndian ? packed : Iec61937.swapped(packed)).build();
                if (littleEndian) {
                    var file = com.github.auties00.smalt.container.MuxSupport.save("dts-cd.wav", cd);
                    // ffprobe reads the packed frames as DTS packets of their stored size, though its parser splits a
                    // few of them at sync word emulations
                    var probedPackets = ProbedPacket.probe(file);
                    var stored = (frames.getFirst().length * 8 + 13) / 14 * 2;
                    var whole = probedPackets.stream().filter(packet -> packet.size() == stored).count();
                    assertTrue(whole > frames.size() * 8L / 10, whole + " of " + probedPackets.size() + " packets");
                }
                for (var bytes : List.of(cd, wav(packed))) {
                    try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                        var track = (AudioTrack) demuxer.tracks().getFirst();
                        assertEquals(Codec.DTS, track.codec());
                        assertEquals(44100, track.sampleRate());
                        assertEquals(512L * frames.size(), track.duration());
                        var packets = packets(demuxer);
                        assertEquals(frames.size(), packets.size());
                        for (var index = 0; index < frames.size(); index++) {
                            assertArrayEquals(frames.get(index), packets.get(index).data(), "frame " + index);
                            assertEquals(512L * index, packets.get(index).pts());
                        }
                    }
                }
            }
            var pcm = new Wav();
            var plain = pcm.chunk("fmt ", pcm.format(1, 2, 44100, 4, 16, new byte[0]))
                    .chunk("data", Fixtures.read(raw)).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(plain))) {
                assertEquals(Codec.DTS, demuxer.tracks().getFirst().codec(), "16-bit frames in PCM samples");
                assertEquals(frames.size(), packets(demuxer).size());
            }
            var single = new Wav();
            var lone = single.chunk("fmt ", single.format(1, 2, 44100, 4, 16, new byte[0]))
                    .chunk("data", Opus.concat(frames.getFirst(), new byte[5000])).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(lone))) {
                assertEquals(Codec.PCM_S16LE, demuxer.tracks().getFirst().codec(), "a single frame stays PCM");
            }
        }

        @Test
        @DisplayName("walks a stream larger than the construction walk on its first seek")
        void large() {
            var raw = Fixtures.read(Iec61937Families.dts("stereo"));
            var frames = Iec61937Families.dtsFrames(raw).size();
            var copies = (int) (DtsFramesDemuxer.MAX_WALK / raw.length) + 2;
            var repeated = new byte[raw.length * copies];
            for (var copy = 0; copy < copies; copy++) {
                System.arraycopy(raw, 0, repeated, copy * raw.length, raw.length);
            }
            try (var demuxer = new WavDemuxer(MediaSource.of(wav(repeated)))) {
                assertEquals(-1, demuxer.tracks().getFirst().duration());
                var target = 512L * frames * (copies - 1) + 700;
                demuxer.seek(target * 1000 / 48 + 1);
                var first = demuxer.read();
                assertTrue(first.pts() <= target && first.pts() + 512 > target, "pts " + first.pts());
                var count = 1;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals((long) frames * copies - first.pts() / 512, count);
            }
        }

        @ParameterizedTest(name = "{0} us")
        @ValueSource(longs = {0, 10_000, 500_000, 1_499_000, 3_000_000})
        @DisplayName("seeks to the frame holding the target sample, also from a source read once")
        void seek(long micros) {
            var bytes = remuxed(Iec61937Families.dts("5.1"));
            var all = wavPackets(bytes);
            var target = micros * 48 / 1000;
            var expected = all.stream().filter(packet -> packet.pts() + packet.duration() > target).toList();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                packets(demuxer);
                demuxer.seek(micros);
                assertSamePackets(expected, packets(demuxer));
            }
            try (var demuxer = new WavDemuxer(new UnsizedSource(bytes, 999))) {
                assertSamePackets(all, packets(demuxer));
            }
        }
    }

    @Nested
    @DisplayName("AAC")
    class Aac {
        static byte[] adts() {
            return Fixtures.read(tone("aac", 44100, 2, 1.0, "-c:a", "aac", "-b:a", "96k"));
        }

        static byte[] heaacInfo(int payloadType) {
            return ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort((short) payloadType)
                    .putShort((short) 0xFE).putShort((short) 0).putShort((short) 0).putInt(0).put((byte) 0x12)
                    .put((byte) 0x10).array();
        }

        @ParameterizedTest(name = "tag 0x{0}")
        @ValueSource(strings = {"1600", "00FF", "1610", "706D", "4143", "A106"})
        @DisplayName("hands ADTS payloads to the ADTS demuxer")
        void adts(String tag) {
            var raw = adts();
            var wav = new Wav();
            var number = Integer.parseInt(tag, 16);
            var extra = number == 0x1610 ? heaacInfo(1) : new byte[0];
            var bytes = wav.chunk("fmt ", wav.format(number, 2, 44100, 1, 0, extra)).chunk("data", raw).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AdtsDemuxer(MediaSource.of(raw))) {
                assertEquals(expected.tracks().getFirst(), demuxer.tracks().getFirst());
                assertEquals(expected.durationMicros(), demuxer.durationMicros());
                assertSamePackets(packets(expected), packets(demuxer));
            }
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AdtsDemuxer(MediaSource.of(raw))) {
                demuxer.seek(400_000);
                expected.seek(400_000);
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        @Test
        @DisplayName("rejects a LOAS stream without a configuration")
        void unframed() {
            var loas = new Wav();
            var loasBytes = loas.chunk("fmt ", loas.format(0x1602, 2, 44100, 1, 0, new byte[0]))
                    .chunk("data", new byte[]{0x56, (byte) 0xE0, 0x03, 0, 0, 0}).build();
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(loasBytes)),
                    "a LOAS frame without a StreamMuxConfig");
        }

        static byte[] latm(int channels, double seconds) {
            return Fixtures.read(tone("latm", 44100, channels, seconds, "-c:a", "aac", "-b:a", "96k"));
        }

        @ParameterizedTest(name = "tag 0x{0}")
        @ValueSource(strings = {"1602", "1610", "00FF"})
        @DisplayName("reads LOAS payloads like the ADTS stream of the same encode")
        void loas(String tag) {
            var adts = Fixtures.read(tone("aac", 44100, 2, 1.0, "-c:a", "aac", "-b:a", "96k"));
            var number = Integer.parseInt(tag, 16);
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.format(number, 2, 44100, 1, 0, number == 0x1610 ? heaacInfo(3) : new byte[0]))
                    .chunk("data", latm(2, 1.0)).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AdtsDemuxer(MediaSource.of(adts))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var want = (AudioTrack) expected.tracks().getFirst();
                assertEquals(Codec.AAC, track.codec());
                assertEquals(want.sampleRate(), track.sampleRate());
                assertEquals(want.layout(), track.layout());
                assertEquals(want.duration(), track.duration());
                assertEquals(want.timeBase(), track.timeBase());
                var actual = packets(demuxer);
                assertSamePackets(packets(expected), actual);
                assertTrue(actual.stream().allMatch(packet -> packet.sideData().newCodecConfig().isEmpty()));
            }
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AdtsDemuxer(MediaSource.of(adts))) {
                demuxer.seek(612_000);
                expected.seek(612_000);
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 4, 5, 6})
        @DisplayName("gives LOAS tracks the speakers of their channel configuration in the decoder's order, as ADTS")
        void loasLayout(int channels) {
            var adts = Fixtures.read(tone("aac", 44100, channels, 0.3, "-c:a", "aac", "-b:a", "96k"));
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.format(0x1602, channels, 44100, 1, 0, new byte[0]))
                    .chunk("data", latm(channels, 0.3)).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AdtsDemuxer(MediaSource.of(adts))) {
                var want = ((AudioTrack) expected.tracks().getFirst()).layout().orElseThrow();
                assertEquals(channels, want.channels());
                assertEquals(want, ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow());
            }
        }

        @Test
        @DisplayName("announces a LOAS configuration change on the first packet coded with it")
        void loasConfigChange() {
            var stereo = latm(2, 0.5);
            var mono = latm(1, 0.5);
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.format(0x1602, 2, 44100, 1, 0, new byte[0]))
                    .chunk("data", Opus.concat(stereo, new byte[]{0x13, 0x77}, mono)).build();
            var alone = new Wav();
            var first = wavPackets(alone.chunk("fmt ", alone.format(0x1602, 2, 44100, 1, 0, new byte[0]))
                    .chunk("data", stereo).build());
            var actual = wavPackets(bytes);
            var changed = actual.get(first.size());
            var config = changed.sideData().newCodecConfig().orElseThrow();
            assertEquals(1, (config[1] >> 3) & 0x0F, "the mono channel configuration");
            for (var index = 0; index < actual.size(); index++) {
                assertEquals(1024L * index, actual.get(index).pts());
                assertEquals(index == first.size(), actual.get(index).sideData().newCodecConfig().isPresent(),
                        "packet " + index);
            }
            assertTrue(actual.size() > first.size() + 10);
        }
    }

    @Nested
    @DisplayName("raw AAC and ADIF")
    class RawAac {
        /** Returns a lavfi source with tone bursts (for short windows), a steady tone and noise per channel. */
        static String source(int rate, int channels, double seconds) {
            var exprs = new java.util.StringJoiner("|");
            for (var channel = 0; channel < channels; channel++) {
                exprs.add("0.5*sin(2*PI*" + (440 + 97 * channel) + "*t)*lt(mod(t+" + channel * 0.05
                        + "\\,0.37)\\,0.03)+0.05*sin(2*PI*" + (3000 + 500 * channel) + "*t)+0.02*(random("
                        + channel + ")-0.5)");
            }
            var layout = switch (channels) {
                case 1 -> "mono";
                case 2 -> "stereo";
                case 6 -> "5.1";
                case 8 -> "7.1";
                default -> throw new IllegalArgumentException("channels " + channels);
            };
            return "aevalsrc=exprs=" + exprs + ":c=" + layout + ":s=" + rate + ":d=" + seconds;
        }

        /** Encodes the source with ffmpeg's AAC encoder; WAVE and ADTS outputs of one encode hold the same blocks. */
        static byte[] encode(String extension, String profile, int rate, int channels, String bitrate, String extra) {
            var options = new ArrayList<>(List.of("-strict", "-2", "-c:a", "aac", "-profile:a", profile, "-b:a", bitrate));
            if (!extra.isBlank()) {
                options.addAll(List.of(extra.trim().split(" ")));
            }
            return Fixtures.read(Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", source(rate, channels, 1.5)),
                    options));
        }

        static byte[] stereo(String extension) {
            return encode(extension, "aac_low", 44100, 2, "128k", "");
        }

        static byte[] wav(int tag, byte[] extra, byte[] data) {
            var wav = new Wav();
            return wav.chunk("fmt ", wav.format(tag, 2, 44100, 1, 0, extra)).chunk("data", data).build();
        }

        @ParameterizedTest(name = "{0} {1} Hz {2} ch {3} {4}")
        @CsvSource({
                "aac_low, 48000, 2, 128k, ''",
                "aac_low, 44100, 1, 64k, ''",
                "aac_main, 48000, 2, 256k, ''",
                "aac_main, 22050, 1, 48k, ''",
                "aac_ltp, 32000, 2, 96k, ''",
                "aac_low, 48000, 6, 384k, ''",
                "aac_low, 48000, 6, 384k, -aac_pce 1",
                "aac_low, 48000, 8, 512k, ''",
                "aac_low, 22050, 2, 24k, -aac_pns 1 -aac_is 1",
                "aac_low, 44100, 2, 48k, -aac_pns 1 -aac_is 1 -aac_tns 1",
                "aac_low, 96000, 2, 320k, ''",
                "aac_low, 8000, 1, 12k, ''",
                "aac_low, 16000, 2, 32k, -aac_tns 0 -aac_ms 0",
                "aac_low, 64000, 2, 400k, -aac_coder fast"})
        @DisplayName("splits ffmpeg's raw AAC into the raw data blocks of the ADTS stream of the same encode")
        void ffmpeg(String profile, int rate, int channels, String bitrate, String extra) {
            var wavBytes = encode("wav", profile, rate, channels, bitrate, extra);
            var adtsBytes = encode("aac", profile, rate, channels, bitrate, extra);
            try (var demuxer = new WavDemuxer(MediaSource.of(wavBytes)); var expected = new AdtsDemuxer(MediaSource.of(adtsBytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var want = (AudioTrack) expected.tracks().getFirst();
                assertEquals(Codec.AAC, track.codec());
                assertEquals(want.sampleRate(), track.sampleRate());
                assertEquals(want.layout(), track.layout());
                assertEquals(want.timeBase(), track.timeBase());
                assertEquals(want.duration(), track.duration());
                assertEquals(expected.durationMicros(), demuxer.durationMicros());
                var config = track.codecConfig().orElseThrow();
                assertEquals(want.codecConfig().orElseThrow()[0] >> 3, config[0] >> 3, "object type");
                var wanted = new ArrayList<>(packets(expected));
                if (extra.contains("aac_pce")) {
                    // ffmpeg's ADTS muxer prepends the program config element, whole bytes, to the first block
                    var first = wanted.getFirst();
                    var block = demuxer.read();
                    var pce = first.size() - block.size();
                    assertEquals(5, (first.data()[0] & 0xFF) >> 5);
                    assertArrayEquals(Arrays.copyOfRange(first.data(), pce, first.size()), block.data());
                    wanted.removeFirst();
                }
                assertSamePackets(wanted, packets(demuxer));
            }
        }

        @ParameterizedTest(name = "{0} us")
        @ValueSource(longs = {0, 1, 23_219, 400_000, 1_000_000, 1_499_999, 7_000_000})
        @DisplayName("seeks to the raw data block holding the target sample, like the ADTS demuxer")
        void seek(long micros) {
            var raw = stereo("wav");
            try (var demuxer = new WavDemuxer(MediaSource.of(raw)); var expected = new AdtsDemuxer(MediaSource.of(stereo("aac")))) {
                assertTrue(demuxer.isSeekable());
                packets(demuxer);
                demuxer.seek(micros);
                expected.seek(micros);
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }

        @Test
        @DisplayName("reads raw AAC declared by WAVE_FORMAT_MPEG_HEAAC, or without a configuration, and from sources read once")
        void declarations() {
            var expected = packets(stereo("aac"), AdtsDemuxer::new);
            var data = dataChunk(stereo("wav"));
            var info = Aac.heaacInfo(0);
            assertSamePackets(expected, wavPackets(wav(0x1610, info, data)));
            var bare = wav(0x00FF, new byte[0], data);
            try (var demuxer = new WavDemuxer(MediaSource.of(bare))) {
                assertArrayEquals(new byte[]{0x12, 0x10}, demuxer.tracks().getFirst().codecConfig().orElseThrow(),
                        "a Low Complexity configuration built from the format");
                assertSamePackets(expected, packets(demuxer));
            }
            try (var source = MediaSource.of(new ByteArrayInputStream(bare), bare.length);
                 var demuxer = new WavDemuxer(source)) {
                assertSamePackets(expected, packets(demuxer));
            }
            try (var demuxer = new WavDemuxer(new UnsizedSource(bare, 777))) {
                assertSamePackets(expected, packets(demuxer));
            }
        }

        @Test
        @DisplayName("walks a stream larger than the construction walk on its first seek, its duration from the fact chunk")
        void large() {
            var data = dataChunk(stereo("wav"));
            var blocks = packets(stereo("aac"), AdtsDemuxer::new).size();
            var copies = (int) (com.github.auties00.smalt.container.internal.RawAacPayloadDemuxer.MAX_WALK / data.length) + 2;
            var repeated = new byte[data.length * copies];
            for (var copy = 0; copy < copies; copy++) {
                System.arraycopy(data, 0, repeated, copy * data.length, data.length);
            }
            var wav = new Wav();
            var fact = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blocks * copies * 1024).array();
            var bytes = wav.chunk("fmt ", wav.format(0x00FF, 2, 44100, 1, 0, new byte[0])).chunk("fact", fact)
                    .chunk("data", repeated).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals((long) blocks * copies * 1024, demuxer.tracks().getFirst().duration());
                var target = (long) blocks * (copies - 1) * 1024 + 5000;
                demuxer.seek(target * 1_000_000 / 44100 + 1);
                var first = demuxer.read();
                assertTrue(first.pts() <= target && first.pts() + 1024 > target, "pts " + first.pts());
                var count = 1;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(blocks * copies - first.pts() / 1024, count);
            }
        }

        @Test
        @DisplayName("ends the stream at a cut block or at trailing bytes that start no block")
        void ends() {
            var expected = packets(stereo("aac"), AdtsDemuxer::new);
            var data = dataChunk(stereo("wav"));
            var cut = Arrays.copyOf(data, data.length - expected.getLast().size() / 2);
            assertSamePackets(expected.subList(0, expected.size() - 1), wavPackets(wav(0x00FF, new byte[0], cut)));
            var padded = Arrays.copyOf(data, data.length + 37);
            assertSamePackets(expected, wavPackets(wav(0x00FF, new byte[0], padded)));
        }

        /** Returns an ADIF header for a 44.1 kHz stereo Low Complexity stream. */
        static byte[] adifHeader(boolean variableRate, boolean copyright, int programs) {
            var writer = new com.github.auties00.smalt.internal.bits.MsbBitWriter(64);
            writer.writeBytes("ADIF".getBytes(StandardCharsets.US_ASCII), 0, 4);
            writer.write(copyright ? 1 : 0, 1);
            if (copyright) {
                writer.writeLong(0x123456789ABCDEFL, 64);
                writer.write(0x5A, 8);
            }
            writer.write(0, 2);
            writer.write(variableRate ? 1 : 0, 1);
            writer.write(128000, 23);
            writer.write(programs - 1, 4);
            for (var program = 0; program < programs; program++) {
                if (!variableRate) {
                    writer.write(0xABCDE, 20);
                }
                writeProgram(writer, program);
            }
            writer.alignWithZeros();
            return writer.toByteArray();
        }

        /** Writes a program configuration element of one front channel pair, with a three-byte comment. */
        static void writeProgram(com.github.auties00.smalt.internal.bits.MsbBitWriter writer, int tag) {
            writer.write(tag, 4);
            writer.write(1, 2);
            writer.write(4, 4);
            writer.write(1, 4);
            writer.write(0, 4 + 4 + 2 + 3 + 4);
            writer.write(0, 3);
            writer.write(1, 1);
            writer.write(0, 4);
            writer.alignWithZeros();
            writer.write(3, 8);
            writer.writeBytes("abc".getBytes(StandardCharsets.US_ASCII), 0, 3);
        }

        @ParameterizedTest(name = "variable rate {0}, copyright {1}, {2} programs, payload type 2 {3}")
        @CsvSource({"false, false, 1, false", "true, false, 1, true", "false, true, 2, false", "true, true, 3, true"})
        @DisplayName("reads ADIF streams with the program configuration in the track's configuration")
        void adif(boolean variableRate, boolean copyright, int programs, boolean declared) {
            var expected = packets(stereo("aac"), AdtsDemuxer::new);
            var data = Opus.concat(adifHeader(variableRate, copyright, programs), dataChunk(stereo("wav")));
            var bytes = declared ? wav(0x1610, Aac.heaacInfo(2), data) : wav(0x00FF, new byte[0], data);
            var config = new com.github.auties00.smalt.internal.bits.MsbBitWriter(16);
            config.write(2, 5);
            config.write(4, 4);
            config.write(0, 4);
            config.write(0, 3);
            writeProgram(config, 0);
            config.alignWithZeros();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertArrayEquals(config.toByteArray(), track.codecConfig().orElseThrow());
                assertEquals(44100, track.sampleRate());
                assertEquals(expected.size() * 1024L, track.duration());
                assertSamePackets(expected, packets(demuxer));
                demuxer.seek(500_000);
                assertSamePackets(expected.stream().filter(packet -> packet.pts() + 1024 > 500_000 * 441 / 10_000)
                        .toList(), packets(demuxer));
            }
        }

        @Test
        @DisplayName("rejects error-resilient object types, invalid blocks and a truncated ADIF header")
        void rejects() {
            var data = dataChunk(stereo("wav"));
            // AudioSpecificConfig of ER AAC LD (object type 23), 44.1 kHz stereo
            var ld = wav(0x00FF, new byte[]{(byte) 0xBA, 0x10, 0x00}, data);
            assertEquals("aac raw object type 23",
                    assertThrows(MediaException.Unsupported.class, () -> new WavDemuxer(MediaSource.of(ld))).format());
            // an SCE whose ics_info reserved bit is set
            var reserved = wav(0x00FF, new byte[0], new byte[]{0x00, 0x01, 0, 0, 0, 0});
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(reserved)));
            var adif = adifHeader(true, true, 1);
            var cut = wav(0x00FF, new byte[0], Arrays.copyOf(adif, adif.length - 2));
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(cut)));
            var broken = data.clone();
            var blocks = packets(stereo("aac"), AdtsDemuxer::new);
            var at = 0;
            for (var index = 0; index < 20; index++) {
                at += blocks.get(index).size();
            }
            // block 20 becomes a channel pair whose first section uses the reserved codebook 12
            Arrays.fill(broken, at, at + 8, (byte) 0x00);
            broken[at] = 0x20;
            broken[at + 3] = 0x58;
            try (var demuxer = new WavDemuxer(MediaSource.of(wav(0x00FF, new byte[0], broken)))) {
                assertEquals(20 * 1024L, demuxer.tracks().getFirst().duration(), "the walk stops at the invalid block");
                for (var index = 0; index < 20; index++) {
                    assertArrayEquals(blocks.get(index).data(), demuxer.read().data());
                }
                assertThrows(MediaException.Malformed.class, demuxer::read);
            }
        }
    }

    @Nested
    @DisplayName("FLAC")
    class Flac {
        @Test
        @DisplayName("reads ffmpeg's files like ffprobe and like a native FLAC stream, with the fact duration")
        void ffmpeg() {
            var wav = tone("wav", 48000, 2, 1.5, "-c:a", "flac");
            var native_ = Fixtures.read(tone("flac", 48000, 2, 1.5, "-c:a", "flac"));
            var bytes = Fixtures.read(wav);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new FlacDemuxer(MediaSource.of(native_))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.FLAC, track.codec());
                assertEquals(72000, track.duration(), "from the fact chunk");
                assertEquals(1_500_000, demuxer.durationMicros());
                var config = track.codecConfig().orElseThrow();
                assertEquals("fLaC", new String(config, 0, 4, StandardCharsets.US_ASCII));
                var actual = packets(demuxer);
                assertEquals(ProbedPacket.probe(wav), probed(actual));
                var want = packets(expected);
                assertEquals(want.size(), actual.size());
                for (var index = 0; index < want.size(); index++) {
                    assertArrayEquals(want.get(index).data(), actual.get(index).data(), "frame " + index);
                    assertEquals(want.get(index).pts(), actual.get(index).pts());
                }
            }
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new FlacDemuxer(MediaSource.of(native_))) {
                demuxer.seek(700_000);
                expected.seek(700_000);
                var want = packets(expected);
                var actual = packets(demuxer);
                assertEquals(want.getFirst().pts(), actual.getFirst().pts());
                assertEquals(want.size(), actual.size());
            }
        }

        @Test
        @DisplayName("reads a native FLAC stream and bare frames without a STREAMINFO")
        void variants() {
            var native_ = Fixtures.read(tone("flac", 44100, 1, 0.8, "-c:a", "flac"));
            var expected = packets(native_, FlacDemuxer::new);
            var wav = new Wav();
            var whole = wav.chunk("fmt ", wav.format(0xF1AC, 1, 44100, 1, 16, new byte[0])).chunk("data", native_).build();
            assertSamePackets(expected, wavPackets(whole));
            var frames = Arrays.copyOfRange(native_, firstFrame(native_), native_.length);
            var bare = new Wav();
            var bareBytes = bare.chunk("fmt ", bare.format(0xF1AC, 1, 44100, 1, 16, new byte[0])).chunk("data", frames)
                    .build();
            var actual = wavPackets(bareBytes);
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                assertArrayEquals(expected.get(index).data(), actual.get(index).data());
            }
        }

        static int firstFrame(byte[] flac) {
            var position = 4;
            while (true) {
                var last = (flac[position] & 0x80) != 0;
                var length = (flac[position + 1] & 0xFF) << 16 | (flac[position + 2] & 0xFF) << 8 | flac[position + 3] & 0xFF;
                position += 4 + length;
                if (last) {
                    return position;
                }
            }
        }
    }

    @Nested
    @DisplayName("AMR")
    class Amr {
        @Test
        @DisplayName("reads ffmpeg's AMR-NB files like ffprobe and like the storage file")
        void ffmpeg() {
            var wav = tone("wav", 8000, 1, 1.0, "-c:a", "libopencore_amrnb", "-b:a", "12.2k");
            var amr = Fixtures.read(tone("amr", 8000, 1, 1.0, "-c:a", "libopencore_amrnb", "-b:a", "12.2k"));
            var bytes = Fixtures.read(wav);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AmrDemuxer(MediaSource.of(amr))) {
                assertEquals(expected.tracks().getFirst(), demuxer.tracks().getFirst());
                var actual = packets(demuxer);
                assertEquals(ProbedPacket.probe(wav), probed(actual));
                assertSamePackets(packets(expected), actual);
            }
        }

        @ParameterizedTest(name = "tag 0x{0} with magic {1}")
        @CsvSource({"0058, false", "0058, true", "0057, false"})
        @DisplayName("reads storage-format frames with and without the storage magic")
        void handBuilt(String tag, boolean magic) {
            var wideband = tag.equals("0058");
            var file = Fixtures.read(wideband
                    ? tone("amr", 16000, 1, 0.7, "-c:a", "libvo_amrwbenc", "-b:a", "23850")
                    : tone("amr", 8000, 1, 0.7, "-c:a", "libopencore_amrnb", "-b:a", "7.95k"));
            var magicLength = wideband ? 9 : 6;
            var data = magic ? file : Arrays.copyOfRange(file, magicLength, file.length);
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.format(Integer.parseInt(tag, 16), 1, wideband ? 16000 : 8000, 1, 0,
                    new byte[0])).chunk("data", data).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new AmrDemuxer(MediaSource.of(file))) {
                assertEquals(wideband ? Codec.AMR_WB : Codec.AMR_NB, demuxer.tracks().getFirst().codec());
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }
    }

    @Nested
    @DisplayName("self-delimited Opus")
    class Opus {
        /** Converts a standard Opus packet to the self-delimiting framing of RFC 6716 Appendix B. */
        static byte[] selfDelimited(byte[] packet) {
            var out = new ByteArrayOutputStream();
            var code = packet[0] & 3;
            switch (code) {
                case 0 -> {
                    out.write(packet[0]);
                    writeLength(out, packet.length - 1);
                    out.write(packet, 1, packet.length - 1);
                }
                case 1 -> {
                    out.write(packet[0]);
                    writeLength(out, (packet.length - 1) / 2);
                    out.write(packet, 1, packet.length - 1);
                }
                case 2 -> {
                    var first = readLength(packet, 1);
                    var fieldSize = (packet[1] & 0xFF) < 252 ? 1 : 2;
                    var second = packet.length - 1 - fieldSize - first;
                    out.write(packet, 0, 1 + fieldSize);
                    writeLength(out, second);
                    out.write(packet, 1 + fieldSize, packet.length - 1 - fieldSize);
                }
                default -> {
                    var count = packet[1] & 0xFF;
                    var frames = count & 0x3F;
                    var position = 2;
                    var padding = 0;
                    if ((count & 0x40) != 0) {
                        int value;
                        do {
                            value = packet[position++] & 0xFF;
                            padding += value == 255 ? 254 : value;
                        } while (value == 255);
                    }
                    if ((count & 0x80) == 0) {
                        out.write(packet, 0, position);
                        writeLength(out, (packet.length - position - padding) / frames);
                        out.write(packet, position, packet.length - position);
                    } else {
                        var lengthsStart = position;
                        var sum = 0;
                        for (var frame = 0; frame < frames - 1; frame++) {
                            var length = readLength(packet, position);
                            position += (packet[position] & 0xFF) < 252 ? 1 : 2;
                            sum += length;
                        }
                        out.write(packet, 0, position);
                        writeLength(out, packet.length - position - padding - sum);
                        out.write(packet, position, packet.length - position);
                        assertTrue(lengthsStart <= position);
                    }
                }
            }
            return out.toByteArray();
        }

        static int readLength(byte[] data, int offset) {
            var first = data[offset] & 0xFF;
            return first < 252 ? first : first + 4 * (data[offset + 1] & 0xFF);
        }

        static void writeLength(ByteArrayOutputStream out, int length) {
            if (length < 252) {
                out.write(length);
            } else {
                var first = 252 + (length & 3);
                out.write(first);
                out.write((length - first) / 4);
            }
        }

        static byte[] wav(List<byte[]> packets, int channels, byte[] extra) {
            var data = new ByteArrayOutputStream();
            packets.forEach(packet -> data.writeBytes(selfDelimited(packet)));
            var wav = new Wav();
            return wav.chunk("fmt ", wav.format(0x704F, channels, 48000, 1, 0, extra)).chunk("data", data.toByteArray())
                    .build();
        }

        static Path ogg() {
            return tone("opus", 48000, 2, 1.3, "-c:a", "libopus", "-b:a", "48k", "-frame_duration", "20");
        }

        @Test
        @DisplayName("returns the packets of an Ogg Opus stream with its OpusHead and timestamps")
        void oggPackets() {
            var oggBytes = Fixtures.read(ogg());
            List<Packet> expected;
            byte[] head;
            try (var demuxer = new OggDemuxer(MediaSource.of(oggBytes))) {
                head = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                expected = packets(demuxer);
            }
            var bytes = wav(expected.stream().map(Packet::data).toList(), 2, head);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.OPUS, track.codec());
                assertArrayEquals(head, track.codecConfig().orElseThrow());
                var actual = packets(demuxer);
                assertEquals(expected.size(), actual.size());
                for (var index = 0; index < expected.size(); index++) {
                    assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "packet " + index);
                    assertEquals(expected.get(index).pts(), actual.get(index).pts(), "packet " + index);
                }
                var preSkip = (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
                assertEquals(actual.stream().mapToLong(Packet::duration).sum() - preSkip, track.duration());
            }
        }

        @Test
        @DisplayName("builds the OpusHead from the channel count when the format holds none")
        void synthesizedHead() {
            List<Packet> expected;
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(ogg())))) {
                expected = packets(demuxer);
            }
            var bytes = wav(expected.stream().map(Packet::data).toList(), 2, new byte[0]);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var head = ((AudioTrack) demuxer.tracks().getFirst()).codecConfig().orElseThrow();
                assertArrayEquals(HexFormat.of().parseHex("4f707573486561640102000080bb00000000" + "00"), head);
                var actual = packets(demuxer);
                assertEquals(0, actual.getFirst().pts());
            }
        }

        @Test
        @DisplayName("converts every framing code, length size and padding back to the standard framing")
        void codes() {
            var big = new byte[700];
            Arrays.fill(big, (byte) 0x55);
            var packets = new ArrayList<byte[]>();
            packets.add(concat(new byte[]{(byte) 0x78}, big));
            packets.add(concat(new byte[]{(byte) 0x79}, Arrays.copyOf(big, 600)));
            packets.add(concat(new byte[]{(byte) 0x7A, (byte) 252, 12}, new byte[300], big));
            packets.add(concat(new byte[]{(byte) 0x7B, (byte) 0x43, (byte) 255, 3}, new byte[3 * 40], new byte[257]));
            packets.add(concat(new byte[]{(byte) 0x7B, (byte) 0xC3, 2, 10, (byte) 252, 1}, new byte[10], new byte[256],
                    new byte[5], new byte[2]));
            packets.add(new byte[]{(byte) 0x80, 1, 2, 3});
            var bytes = wav(packets, 2, new byte[0]);
            var actual = wavPackets(bytes);
            assertEquals(packets.size(), actual.size());
            var time = 0L;
            for (var index = 0; index < packets.size(); index++) {
                assertArrayEquals(packets.get(index), actual.get(index).data(), "packet " + index);
                assertEquals(time, actual.get(index).pts());
                time += actual.get(index).duration();
            }
            assertEquals(List.of(960L, 1920L, 1920L, 2880L, 2880L, 120L), actual.stream().map(Packet::duration).toList());
        }

        static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("drops a packet cut short by the end of the data and rejects packets that break the rules")
        void invalid() {
            var good = new byte[]{(byte) 0x78, 1, 2, 3};
            var data = concat(selfDelimited(good), new byte[]{(byte) 0x78, 50, 1, 2});
            var wav = new Wav();
            var truncated = wav.chunk("fmt ", wav.format(0x704F, 1, 48000, 1, 0, new byte[0])).chunk("data", data).build();
            assertEquals(1, wavPackets(truncated).size());
            var runaway = new byte[80];
            Arrays.fill(runaway, (byte) 255);
            runaway[0] = 0x7B;
            runaway[1] = 0x41;
            for (var broken : List.of(
                    new byte[]{(byte) 0x7B, 0x00, 1, 0},
                    new byte[]{(byte) 0x7B, 0x07, 1, 0, 0, 0, 0, 0, 0, 0},
                    runaway)) {
                var bad = new Wav();
                var bytes = bad.chunk("fmt ", bad.format(0x704F, 1, 48000, 1, 0, new byte[0]))
                        .chunk("data", concat(selfDelimited(good), broken, new byte[2000])).build();
                try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                    assertEquals(4, demuxer.read().size(), "the packet before the damage");
                    assertThrows(MediaException.Malformed.class, demuxer::read, HexFormat.of().formatHex(broken));
                }
            }
            var many = new Wav();
            var manyBytes = many.chunk("fmt ", many.format(0x704F, 12, 48000, 1, 0, new byte[0]))
                    .chunk("data", selfDelimited(good)).build();
            assertThrows(MediaException.Unsupported.class, () -> new WavDemuxer(MediaSource.of(manyBytes)));
        }

        @ParameterizedTest(name = "{0} us")
        @ValueSource(longs = {0, 10_000, 333_333, 1_000_000, 1_290_000, 5_000_000})
        @DisplayName("seeks to the packet holding the target sample")
        void seek(long micros) {
            List<Packet> expected;
            byte[] head;
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(ogg())))) {
                head = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                expected = packets(demuxer);
            }
            var bytes = wav(expected.stream().map(Packet::data).toList(), 2, head);
            var target = micros == 0 ? -((head[10] & 0xFF) | (head[11] & 0xFF) << 8) : micros * 48 / 1000;
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                demuxer.seek(micros);
                var actual = packets(demuxer);
                var want = expected.stream().filter(packet -> packet.pts() + 960 > target).toList();
                assertEquals(want.size(), actual.size());
                for (var index = 0; index < want.size(); index++) {
                    assertArrayEquals(want.get(index).data(), actual.get(index).data());
                    assertEquals(want.get(index).pts(), actual.get(index).pts());
                }
            }
        }
    }

    @Nested
    @DisplayName("MPEG audio")
    class Mpeg {
        @ParameterizedTest(name = "{0} us")
        @ValueSource(longs = {0, 250_000, 777_777, 1_500_000, 4_000_000})
        @DisplayName("seeks like the MPEG audio demuxer over the data chunk")
        void seek(long micros) {
            var bytes = Fixtures.read(tone("wav", 44100, 2, 2.0, "-c:a", "libmp3lame", "-b:a", "128k"));
            var data = dataChunk(bytes);
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes)); var expected = new MpegAudioDemuxer(MediaSource.of(data))) {
                assertTrue(demuxer.isSeekable());
                packets(demuxer);
                demuxer.seek(micros);
                expected.seek(micros);
                assertSamePackets(packets(expected), packets(demuxer));
            }
        }
    }

    @Nested
    @DisplayName("Ambisonic B-format")
    class Ambisonic {
        @ParameterizedTest(name = "tag {0}, {1} channels")
        @CsvSource({"1, 4, 16, PCM_S16LE", "1, 9, 24, PCM_S24LE", "3, 4, 32, PCM_F32LE", "3, 16, 64, PCM_F64LE",
                "1, 3, 16, PCM_S16LE", "1, 5, 16, PCM_S16LE", "3, 6, 32, PCM_F32LE", "1, 7, 16, PCM_S16LE",
                "1, 8, 32, PCM_S32LE", "3, 11, 32, PCM_F32LE", "1, 10, 16, PCM_S16LE", "1, 2, 16, PCM_S16LE"})
        @DisplayName("reads the components as PCM in the Furse-Malham layout of the channel count")
        void bFormat(int tag, int channels, int bits, Codec codec) {
            var blockAlign = channels * bits / 8;
            var samples = new byte[blockAlign * 10];
            new java.util.SplittableRandom(channels).nextBytes(samples);
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.extensible(ambisonicGuid(tag), channels, 48000, blockAlign, bits, new byte[0]))
                    .chunk("data", samples).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(codec, track.codec());
                assertEquals(Collections.nCopies(channels, Speaker.UNKNOWN), track.layout().orElseThrow().speakers());
                var defined = channels != 2 && channels != 10;
                assertEquals(defined ? Optional.of(Ambisonics.ofFurseMalham(channels)) : Optional.empty(),
                        track.layout().orElseThrow().ambisonics());
                assertEquals(10, track.duration());
                var packets = packets(demuxer);
                assertEquals(1, packets.size());
                assertArrayEquals(samples, packets.getFirst().data());
            }
        }

        @ParameterizedTest(name = "{0} channels, {1}")
        @CsvSource({"3, PCM_S16LE", "4, PCM_S16LE", "4, PCM_F32LE", "5, PCM_S24LE", "6, PCM_S16LE", "7, PCM_S32LE",
                "8, PCM_F64LE", "9, PCM_S16LE", "11, PCM_F32LE", "16, PCM_S24LE"})
        @DisplayName("writes a Furse-Malham layout with the B-format GUID, which reads back as the same layout")
        void muxer(int channels, Codec codec) {
            var layout = ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(channels));
            var bytesPerSample = codec.pcmBitsPerSample().orElseThrow() / 8;
            var samples = new byte[channels * bytesPerSample * 100];
            new java.util.SplittableRandom(channels).nextBytes(samples);
            var track = AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(layout).build();
            var file = MuxSupport.toMemory(List.of(track), List.of(new Packet(1, samples, 0, 0, 100, true)),
                    WavMuxer::new);
            var tag = codec == Codec.PCM_F32LE || codec == Codec.PCM_F64LE ? 3 : 1;
            var fmt = formatChunk(file);
            var format = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0xFFFE, format.getShort(fmt) & 0xFFFF, "WAVE_FORMAT_EXTENSIBLE");
            assertEquals(0, format.getInt(fmt + 20), "channel mask");
            assertArrayEquals(ambisonicGuid(tag), Arrays.copyOfRange(file, fmt + 24, fmt + 40), "sub-format GUID");
            try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                var read = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(layout, read.layout().orElseThrow());
                assertEquals(codec, read.codec());
                assertArrayEquals(samples, packets(demuxer).getFirst().data());
            }
        }

        @Test
        @DisplayName("writes other ambisonic layouts as channels without positions")
        void otherLayouts() {
            for (var layout : List.of(ChannelLayout.ofAmbisonics(1), ChannelLayout.ofAmbisonics(Ambisonics.of(1,
                    Ambisonics.Normalization.FUMA)), ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(
                    Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)))) {
                var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000))
                        .sampleRate(48000).layout(layout).build();
                var file = MuxSupport.toMemory(List.of(track), List.of(new Packet(1,
                        new byte[2 * layout.channels() * 10], 0, 0, 10, true)), WavMuxer::new);
                var fmt = formatChunk(file);
                assertFalse(Arrays.equals(ambisonicGuid(1), Arrays.copyOfRange(file, fmt + 24, fmt + 40)),
                        layout.toString());
                try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                    var read = ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow();
                    assertTrue(read.ambisonics().isEmpty(), layout.toString());
                    assertEquals(layout.channels(), read.channels());
                }
            }
        }

        @Test
        @DisplayName("rejects an unknown sub-format GUID")
        void unknownGuid() {
            var wav = new Wav();
            var bytes = wav.chunk("fmt ", wav.extensible(guid(1, "2107d3118644c8c1ca000001"), 4, 48000, 8, 16, new byte[0]))
                    .chunk("data", new byte[16]).build();
            assertEquals("wav subformat", assertThrows(MediaException.Unsupported.class,
                    () -> new WavDemuxer(MediaSource.of(bytes))).format());
        }
    }

    @Test
    @DisplayName("resists hundreds of random mutations of coded payloads")
    void mutations() {
        var spdif = Iec61937.spdif(Iec61937.raw("ac3"));
        var opus = new ArrayList<byte[]>();
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(Opus.ogg())))) {
            packets(demuxer).forEach(packet -> opus.add(packet.data()));
        }
        var inputs = List.of(
                Iec61937.wav("pcm", "ac3", Arrays.copyOf(spdif, 6144 * 8)),
                Opus.wav(opus.subList(0, 20), 2, new byte[0]),
                Fixtures.read(tone("wav", 48000, 2, 0.3, "-c:a", "ac3")),
                Fixtures.read(tone("wav", 22050, 1, 0.3, "-c:a", "flac")),
                Fixtures.read(tone("wav", 8000, 1, 0.3, "-c:a", "libopencore_amrnb")),
                new Wav().chunk("fmt ", new Wav().format(0x1602, 2, 44100, 1, 0, new byte[0]))
                        .chunk("data", Aac.latm(2, 0.3)).build(),
                RawAac.wav(0x00FF, new byte[0], Arrays.copyOf(dataChunk(RawAac.stereo("wav")), 6000)),
                RawAac.wav(0x00FF, new byte[0], Opus.concat(RawAac.adifHeader(false, true, 2),
                        Arrays.copyOf(dataChunk(RawAac.stereo("wav")), 3000))),
                Iec61937Families.pcmWav(Arrays.copyOf(Iec61937Families.spdif(Iec61937Families.dts("stereo")), 2048 * 12)),
                Iec61937Families.pcmWav(Arrays.copyOf(Iec61937Families.spdif(Iec61937Families.dts("5.1"),
                        "-dtshd_rate", "192000"), 8192 * 4)),
                Iec61937Families.pcmWav(Arrays.copyOf(Iec61937Families.spdif(
                        Iec61937Families.trueHdStream(48000, "stereo", 0.01)), 61440 * 3)),
                Iec61937Families.pcmWav(Arrays.copyOf(Iec61937Families.spdif(Iec61937Families.encode("mp3", 24000,
                        "stereo", 0.5, "-c:a", "libmp3lame")), 4608 * 10)));
        var stats = AudioMutations.assertRobust(inputs, 120, 0xC0DEDL, Duration.ofSeconds(10), bytes -> {
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                while (demuxer.read() != null) {
                    // drain
                }
                if (demuxer.isSeekable()) {
                    demuxer.seek(100_000);
                    assertNotNull(demuxer.tracks());
                    while (demuxer.read() != null) {
                        // drain
                    }
                }
            }
        });
        System.out.println("WavDemuxer coded payload mutation outcomes: " + stats);
        assertFalse(stats.isEmpty());
    }
}
