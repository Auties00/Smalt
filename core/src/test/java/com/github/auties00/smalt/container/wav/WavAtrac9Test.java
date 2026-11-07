package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers ATRAC9 in WAVE ({@code .at9} files: the {@code WAVEFORMATEXTENSIBLE} ATRAC9 sub-format, the 12-byte
 * {@code fact} chunk with the encoder delay, and superframes in the {@code data} chunk), in Matroska ({@code A_MS/ACM}
 * with the same format structure), and ATRAC1 in Matroska ({@code A_ATRAC/AT1}). The files are assembled by the test
 * in the layout Sony's tools write; the superframes are opaque to the containers, so they are random bytes.
 */
@DisplayName("ATRAC9 and ATRAC1 in WAVE and Matroska")
class WavAtrac9Test {
    /** 48 kHz stereo, 384-byte frames, four frames (1536 bytes, 1024 samples) per superframe. */
    static final byte[] CONFIG = config(7, 2, 384, 2);

    static byte[] config(int rateIndex, int channelConfig, int frameBytes, int superframeIndex) {
        var word = 0xFE << 24 | rateIndex << 20 | channelConfig << 17 | (frameBytes - 1) << 5 | superframeIndex << 3;
        return new byte[]{(byte) (word >>> 24), (byte) (word >>> 16), (byte) (word >>> 8), (byte) word};
    }

    static final byte[] GUID = {(byte) 0xD2, 0x42, (byte) 0xE1, 0x47, (byte) 0xBA, 0x36, (byte) 0x8D, 0x4D,
            (byte) 0x88, (byte) 0xFC, 0x61, 0x65, 0x4F, (byte) 0x8C, (byte) 0x83, 0x6C};

    /** Assembles an .at9 file; a negative sample count leaves out the fact chunk. */
    static byte[] at9(byte[] config, int channels, int rate, long mask, int superframeBytes, int superframeSamples,
                      long samples, int delay, byte[] data) {
        var fmt = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        fmt.putShort((short) 0xFFFE).putShort((short) channels).putInt(rate)
                .putInt((int) ((long) superframeBytes * rate / superframeSamples)).putShort((short) superframeBytes)
                .putShort((short) 0).putShort((short) 34).putShort((short) superframeSamples).putInt((int) mask)
                .put(GUID).putInt(1).put(config).putInt(0);
        var out = new ByteArrayOutputStream();
        var body = new ByteArrayOutputStream();
        chunk(body, "fmt ", fmt.array());
        if (samples >= 0) {
            var fact = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt((int) samples).putInt(delay)
                    .putInt(delay);
            chunk(body, "fact", fact.array());
        }
        chunk(body, "data", data);
        out.writeBytes("RIFF".getBytes());
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + body.size()).array());
        out.writeBytes("WAVE".getBytes());
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String id, byte[] body) {
        out.writeBytes(id.getBytes());
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).array());
        out.writeBytes(body);
        if ((body.length & 1) != 0) {
            out.write(0);
        }
    }

    static byte[] superframes(int count, int size, long seed) {
        var data = new byte[count * size];
        new Random(seed).nextBytes(data);
        return data;
    }

    record Read(AudioTrack track, List<Packet> packets) {
    }

    static Read demux(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Read(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    @Nested
    @DisplayName("WAVE")
    class Wave {
        @Test
        @DisplayName("reads the configuration, encoder delay, sample count, layout and superframes")
        void reads() {
            var data = superframes(5, 1536, 1);
            var file = at9(CONFIG, 2, 48000, 3, 1536, 1024, 4000, 256, data);
            var read = demux(file);
            var track = read.track();
            assertEquals(Codec.ATRAC9, track.codec());
            assertArrayEquals(CONFIG, track.codecConfig().orElseThrow());
            assertEquals(48000, track.sampleRate());
            assertEquals(ChannelLayout.STEREO, track.layout().orElseThrow());
            assertEquals(256, track.skipSamples());
            assertEquals(4000, track.duration());
            assertEquals(5, read.packets().size());
            for (var i = 0; i < 5; i++) {
                var packet = read.packets().get(i);
                assertArrayEquals(Arrays.copyOfRange(data, i * 1536, (i + 1) * 1536), packet.data());
                assertEquals(i * 1024L, packet.pts());
                assertEquals(1024, packet.duration());
            }
        }

        @Test
        @DisplayName("takes the superframe size from the configuration, not the block alignment")
        void blockAlignment() {
            var data = superframes(3, 1536, 2);
            var file = at9(CONFIG, 2, 48000, 3, 1536, 1024, 3000, 0, data);
            // a wrong nBlockAlign, which some files carry
            file[12 + 8 + 12] = 0x10;
            file[12 + 8 + 13] = 0;
            assertEquals(3, demux(file).packets().size());
        }

        @Test
        @DisplayName("takes the sampling rate and channels from the configuration, not the format's fields")
        void formatFields() {
            var data = superframes(2, 1536, 8);
            var read = demux(at9(CONFIG, 1, 44100, 4, 1536, 1024, 2048, 0, data));
            assertEquals(48000, read.track().sampleRate());
            assertEquals(ChannelLayout.STEREO, read.track().layout().orElseThrow());
            assertEquals(Rational.timeBaseOf(48000), read.track().timeBase());
        }

        @Test
        @DisplayName("seeks to the superframe holding the target sample")
        void seek() {
            var data = superframes(5, 1536, 9);
            var file = at9(CONFIG, 2, 48000, 3, 1536, 1024, 5000, 0, data);
            try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                // sample 2500 lies in the third superframe (samples 2048 to 3071)
                demuxer.seek(2500L * 1_000_000 / 48000);
                var packet = demuxer.read();
                assertEquals(2048, packet.pts());
                assertArrayEquals(Arrays.copyOfRange(data, 2 * 1536, 3 * 1536), packet.data());
                demuxer.seek(0);
                assertEquals(0, demuxer.read().pts());
                demuxer.seek(10_000_000);
                assertEquals(null, demuxer.read());
            }
        }

        @Test
        @DisplayName("keeps a truncated last superframe and counts the data without a fact chunk")
        void truncated() {
            var data = superframes(3, 1536, 3);
            var file = at9(config(2, 0, 100, 0), 1, 16000, 4, 100, 128, -1, 0, Arrays.copyOf(data, 250));
            var read = demux(file);
            assertEquals(3, read.packets().size());
            assertEquals(50, read.packets().get(2).data().length);
            assertEquals(3 * 128, read.track().duration());
            assertEquals(0, read.track().skipSamples());
        }

        @Test
        @DisplayName("rejects a format without the configuration word or with an invalid one")
        void invalid() {
            var data = superframes(1, 1536, 4);
            var bad = CONFIG.clone();
            bad[0] = 0x12;
            assertThrows(MediaException.Malformed.class,
                    () -> demux(at9(bad, 2, 48000, 3, 1536, 1024, 1000, 0, data)));
            var file = at9(CONFIG, 2, 48000, 3, 1536, 1024, 1000, 0, data);
            // cbSize of 22: the WAVEFORMATEXTENSIBLE fields alone
            file[12 + 8 + 16] = 22;
            assertThrows(MediaException.Malformed.class, () -> demux(file));
        }

        @Test
        @DisplayName("writes back the fmt, fact and data chunks it read")
        void roundTrip() {
            var data = superframes(4, 1536, 5);
            var file = at9(CONFIG, 2, 48000, 3, 1536, 1024, 3500, 256, data);
            var read = demux(file);
            var written = MuxSupport.toMemory(List.of(read.track()), read.packets(), WavMuxer::new);
            var chunks = WavMuxerCodedTest.chunks(written);
            var original = WavMuxerCodedTest.chunks(file);
            assertArrayEquals(original.get("fmt "), chunks.get("fmt "));
            assertArrayEquals(original.get("fact"), chunks.get("fact"));
            assertArrayEquals(data, chunks.get("data"));
            var again = demux(written);
            assertEquals(read.track(), again.track());
        }

        @Test
        @DisplayName("refuses a track whose configuration disagrees with its layout, or has none")
        void refuses() {
            var track = AudioTrack.builder().codec(Codec.ATRAC9).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.MONO).codecConfig(CONFIG).build();
            assertThrows(IllegalArgumentException.class,
                    () -> MuxSupport.toMemory(List.of(track), List.of(), WavMuxer::new));
            var none = track.toBuilder().layout(ChannelLayout.STEREO).codecConfig(null).build();
            assertThrows(IllegalArgumentException.class,
                    () -> MuxSupport.toMemory(List.of(none), List.of(), WavMuxer::new));
        }
    }

    @Nested
    @DisplayName("Matroska")
    class Matroska {
        static Read remux(AudioTrack track, List<Packet> packets) {
            var file = MuxSupport.toMemory(List.of(track), packets, MatroskaMuxer::new);
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(file))) {
                var read = (AudioTrack) demuxer.tracks().getFirst();
                return new Read(read, AudioPipeline.packets(demuxer, read.id()));
            }
        }

        @Test
        @DisplayName("carries ATRAC9 as A_MS/ACM with its configuration, delay and superframes")
        void atrac9() {
            var data = superframes(4, 1536, 6);
            var source = demux(at9(CONFIG, 2, 48000, 3, 1536, 1024, 3500, 256, data));
            var read = remux(source.track(), source.packets());
            assertEquals(Codec.ATRAC9, read.track().codec());
            assertArrayEquals(CONFIG, read.track().codecConfig().orElseThrow());
            assertEquals(48000, read.track().sampleRate());
            assertEquals(256, read.track().skipSamples());
            assertEquals(source.packets().size(), read.packets().size());
            for (var i = 0; i < read.packets().size(); i++) {
                assertArrayEquals(source.packets().get(i).data(), read.packets().get(i).data());
            }
        }

        @Test
        @DisplayName("carries ATRAC1 as A_ATRAC/AT1")
        void atrac1() {
            var track = AudioTrack.builder().codec(Codec.ATRAC1).timeBase(Rational.timeBaseOf(44100)).sampleRate(44100)
                    .layout(ChannelLayout.STEREO).build();
            var packets = new ArrayList<Packet>();
            var random = new Random(7);
            for (var i = 0; i < 6; i++) {
                var data = new byte[424];
                random.nextBytes(data);
                packets.add(new Packet(track.id(), data, i * 512L, i * 512L, 512, true));
            }
            var read = remux(track, packets);
            assertEquals(Codec.ATRAC1, read.track().codec());
            assertEquals(44100, read.track().sampleRate());
            assertEquals(6, read.packets().size());
            for (var i = 0; i < 6; i++) {
                assertArrayEquals(packets.get(i).data(), read.packets().get(i).data());
            }
        }
    }
}
