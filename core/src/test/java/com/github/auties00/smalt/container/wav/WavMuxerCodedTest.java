package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the coded payloads and converted PCM of {@link WavMuxer}: ffmpeg-encoded MPEG audio, AC-3, E-AC-3,
 * AAC, FLAC, Opus, AMR and DTS streams muxed into WAVE and read back packet for packet by {@link WavDemuxer} and
 * ffprobe (black box) and decoded by ffmpeg, the {@code MPEG1WAVEFORMAT}, {@code MPEGLAYER3WAVEFORMAT} and
 * {@code mext} fields of EBU Tech 3285 Supplement 1 against values derived in the test from the frame headers,
 * the self-delimiting Opus framing against the independent converter of {@link WavCodedPayloadTest}, the
 * provisional header of a streaming sink, big-endian and signed 8-bit PCM against ffmpeg's decode of the raw
 * samples, and, with {@code -Dsmalt.test.mfAudioDecode}, the decode of Windows Media Foundation.
 */
@DisplayName("WavMuxer coded payloads")
class WavMuxerCodedTest {
    private static final byte[] DDPLUS_GUID = HexFormat.of().parseHex("af87fba7022dfb42a4d405cd93843bdd");

    /** A coded stream encoded by ffmpeg, the format tag its WAVE file declares and whether ffmpeg reads it back. */
    record Source(String name, Supplier<Path> file, Codec codec, int tag, boolean ffmpegReads) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Path tone(String extension, int rate, int channels, String... options) {
        return Fixtures.tone(extension, 440, rate, channels, 1.0, List.of(options));
    }

    static Supplier<Path> encoded(String encoder, String extension, int rate, int channels, String... options) {
        return () -> {
            Oracle.assumeEncoder(encoder);
            var arguments = new ArrayList<>(List.of("-c:a", encoder));
            arguments.addAll(List.of(options));
            return tone(extension, rate, channels, arguments.toArray(String[]::new));
        };
    }

    static Stream<Source> sources() {
        return Stream.of(
                new Source("MP2 48 kHz stereo", encoded("mp2", "mp2", 48000, 2, "-b:a", "192k"), Codec.MP2, 0x0050, true),
                new Source("MP2 44.1 kHz mono", encoded("mp2", "mp2", 44100, 1, "-b:a", "96k"), Codec.MP2, 0x0050, true),
                new Source("MP3 CBR", encoded("libmp3lame", "mp3", 48000, 2, "-b:a", "128k"), Codec.MP3, 0x0055, true),
                new Source("MP3 VBR", encoded("libmp3lame", "mp3", 44100, 2, "-q:a", "4"), Codec.MP3, 0x0055, true),
                new Source("MP3 MPEG-2", encoded("libmp3lame", "mp3", 22050, 1, "-b:a", "32k"), Codec.MP3, 0x0055, true),
                new Source("AC-3 5.1", encoded("ac3", "ac3", 48000, 6, "-b:a", "384k"), Codec.AC3, 0x2000, true),
                new Source("E-AC-3", encoded("eac3", "eac3", 48000, 2, "-b:a", "192k"), Codec.EAC3, 0xFFFE, true),
                new Source("AAC in ADTS", encoded("aac", "aac", 48000, 2, "-b:a", "128k"), Codec.AAC, 0x1600, true),
                new Source("AAC in M4A", encoded("aac", "m4a", 44100, 1), Codec.AAC, 0x1600, true),
                new Source("FLAC", encoded("flac", "flac", 44100, 2), Codec.FLAC, 0xF1AC, true),
                new Source("Opus stereo", encoded("libopus", "opus", 48000, 2, "-b:a", "64k"), Codec.OPUS, 0x704F, false),
                new Source("Opus 5.1", encoded("libopus", "opus", 48000, 6, "-b:a", "192k"), Codec.OPUS, 0x704F, false),
                new Source("AMR-NB", encoded("libopencore_amrnb", "amr", 8000, 1, "-b:a", "12.2k"), Codec.AMR_NB, 0x0057,
                        true),
                new Source("AMR-WB", encoded("libvo_amrwbenc", "amr", 16000, 1, "-b:a", "23.85k"), Codec.AMR_WB, 0x0058,
                        true),
                new Source("DTS", encoded("dca", "mkv", 48000, 2, "-strict", "-2"), Codec.DTS, 0x2001, true));
    }

    /** A track and its packets, as a demuxer returns them. */
    record Stream_(AudioTrack track, List<Packet> packets) {
    }

    static Stream_ demux(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = AudioPipeline.firstAudio(demuxer);
            return new Stream_(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    static Stream_ demuxWav(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Stream_(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    /** Returns the body of each chunk of a little-endian RIFF WAVE file, by identifier, the first of each kind. */
    static Map<String, byte[]> chunks(byte[] wav) {
        var chunks = new LinkedHashMap<String, byte[]>();
        var buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var position = 12;
        while (position + 8 <= wav.length) {
            var id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            var size = buffer.getInt(position + 4) & 0xFFFFFFFFL;
            var end = (int) Math.min(wav.length, position + 8 + size);
            chunks.putIfAbsent(id, Arrays.copyOfRange(wav, position + 8, end));
            if (id.equals("data")) {
                break;
            }
            position = end + (int) (size & 1);
        }
        return chunks;
    }

    static ByteBuffer le(byte[] body) {
        return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
    }

    static long fact(byte[] wav) {
        return le(chunks(wav).get("fact")).getInt(0) & 0xFFFFFFFFL;
    }

    static byte[] mux(Stream_ stream) {
        return MuxSupport.toMemory(List.of(stream.track()), stream.packets(), WavMuxer::new);
    }

    static byte[] concatenate(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /**
     * Returns the bytes the {@code data} chunk stores for each packet: ADTS framed for AAC, self-delimited (by the
     * independent converter of {@link WavCodedPayloadTest}) for Opus, the packet otherwise.
     */
    static List<byte[]> stored(Stream_ stream, int tag) {
        return stream.packets().stream().map(packet -> switch (tag) {
            case 0x1600 -> adtsFrame(stream.track(), packet);
            case 0x704F -> WavCodedPayloadTest.Opus.selfDelimited(packet.data());
            default -> packet.data();
        }).toList();
    }

    static byte[] adtsFrame(AudioTrack track, Packet packet) {
        var sink = MediaSink.memory();
        try (var muxer = new AdtsMuxer(sink)) {
            muxer.addTrack(track);
            muxer.writePacket(packet);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @Nested
    @DisplayName("coded audio")
    class Coded {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerCodedTest#sources")
        @DisplayName("stores each packet so that WavDemuxer and ffprobe return it unchanged and ffmpeg decodes the file")
        void roundTrip(Source source) {
            var input = demux(Fixtures.read(source.file().get()));
            assertEquals(source.codec(), input.track().codec());
            var wav = mux(input);
            var chunks = chunks(wav);
            var format = le(chunks.get("fmt "));
            assertEquals(source.tag(), format.getShort(0) & 0xFFFF, "format tag");
            assertEquals(input.track().layout().orElseThrow().channels(), format.getShort(2), "channels");
            assertEquals(input.track().sampleRate(), format.getInt(4), "sampling rate");
            assertTrue(format.getInt(8) > 0, "average byte rate");
            assertTrue(chunks.containsKey("fact"), "fact chunk");
            assertEquals(source.codec() == Codec.MP2, chunks.containsKey("mext"), "mext chunk");
            var units = stored(input, source.tag());
            assertArrayEquals(concatenate(units), Arrays.copyOfRange(chunks.get("data"), 0, concatenate(units).length));

            var output = demuxWav(wav);
            assertEquals(source.codec(), output.track().codec(), "codec read back");
            assertEquals(input.track().sampleRate(), output.track().sampleRate(), "sampling rate read back");
            assertEquals(input.track().layout().orElseThrow().channels(), output.track().layout().orElseThrow().channels());
            assertEquals(input.packets().size(), output.packets().size(), "packet count");
            for (var index = 0; index < input.packets().size(); index++) {
                assertArrayEquals(input.packets().get(index).data(), output.packets().get(index).data(), "packet " + index);
            }
            if (source.codec() == Codec.OPUS) {
                assertArrayEquals(input.track().codecConfig().orElseThrow(), output.track().codecConfig().orElseThrow());
                var samples = input.packets().stream().mapToLong(packet -> opusSamples(packet.data())).sum();
                assertEquals(samples, fact(wav), "fact samples");
            }
            if (source.ffmpegReads()) {
                var file = MuxSupport.save("coded-" + source.name().replace(' ', '-') + ".wav", wav);
                var probed = ProbedPacket.probe(file);
                assertEquals(units.size(), probed.size(), "ffprobe packet count");
                for (var index = 0; index < units.size(); index++) {
                    assertEquals(units.get(index).length, probed.get(index).size(), "ffprobe size of packet " + index);
                    assertEquals(ProbedPacket.crc32(units.get(index)), probed.get(index).crc32(), "ffprobe CRC of packet " + index);
                }
                assertEquals(probed.stream().mapToLong(ProbedPacket::duration).sum(), fact(wav), "fact samples");
                MuxSupport.assertFfmpegClean("coded-" + source.name().replace(' ', '-') + ".wav", wav);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerCodedTest#sources")
        @DisplayName("writes a stream to a non-seekable sink with the provisional header, which WavDemuxer reads")
        void streaming(Source source) {
            var input = demux(Fixtures.read(source.file().get()));
            var wav = MuxSupport.toStream(List.of(input.track()), input.packets(), WavMuxer::new);
            var format = le(chunks(wav).get("fmt "));
            assertEquals(source.tag(), format.getShort(0) & 0xFFFF);
            assertEquals(1, format.getShort(12), "the block alignment a single packet cannot establish");
            assertEquals(0, fact(wav));
            var output = demuxWav(wav);
            assertEquals(input.packets().size(), output.packets().size());
            for (var index = 0; index < input.packets().size(); index++) {
                assertArrayEquals(input.packets().get(index).data(), output.packets().get(index).data(), "packet " + index);
            }
        }

        @Test
        @DisplayName("declares E-AC-3 with the DDPLUS sub-format and the layout's channel mask")
        void eac3Extensible() {
            var input = demux(Fixtures.read(encoded("eac3", "eac3", 48000, 6, "-b:a", "384k").get()));
            var format = chunks(mux(input)).get("fmt ");
            var fields = le(format);
            assertEquals(0xFFFE, fields.getShort(0) & 0xFFFF);
            assertEquals(22, fields.getShort(16), "cbSize");
            // ffmpeg's E-AC-3 5.1 is 5.1(side): FL, FR, FC, LFE, SL and SR
            assertEquals(0x60F, fields.getInt(20), "channel mask of 5.1");
            assertArrayEquals(DDPLUS_GUID, Arrays.copyOfRange(format, 24, 40));
        }

        @Test
        @DisplayName("stores AAC that ADTS cannot express as raw data blocks after an HEAACWAVEINFO")
        void rawAac() {
            var input = demux(Fixtures.read(encoded("aac", "aac", 48000, 2, "-b:a", "96k").get()));
            // the AAC LC configuration of 48 kHz stereo with the sampling rate coded explicitly (index 15)
            var explicit = new byte[]{0x17, (byte) 0x80, 0x5D, (byte) 0xC0, 0x10};
            var track = input.track().toBuilder().codecConfig(explicit).build();
            var wav = mux(new Stream_(track, input.packets()));
            var format = chunks(wav).get("fmt ");
            var fields = le(format);
            assertEquals(0x1610, fields.getShort(0) & 0xFFFF);
            assertEquals(12 + explicit.length, fields.getShort(16), "cbSize");
            assertEquals(0, fields.getShort(18), "wPayloadType: raw");
            assertEquals(0xFE, fields.getShort(20), "wAudioProfileLevelIndication: unspecified");
            assertArrayEquals(explicit, Arrays.copyOfRange(format, 30, format.length));
            assertArrayEquals(concatenate(input.packets().stream().map(Packet::data).toList()), chunks(wav).get("data"));
            var output = demuxWav(wav);
            assertArrayEquals(explicit, output.track().codecConfig().orElseThrow());
            assertEquals(input.packets().size(), output.packets().size());
            for (var index = 0; index < input.packets().size(); index++) {
                assertArrayEquals(input.packets().get(index).data(), output.packets().get(index).data(), "packet " + index);
            }
            assertEquals(input.packets().size() * 1024L, fact(wav));
        }

        @Test
        @DisplayName("stores multichannel AMR after the multichannel magic and channel description")
        void multichannelAmr() {
            var mono = demux(Fixtures.read(encoded("libopencore_amrnb", "amr", 8000, 1, "-b:a", "7.95k").get()));
            var packets = new ArrayList<Packet>();
            for (var index = 0; index + 1 < mono.packets().size(); index += 2) {
                var block = concatenate(List.of(mono.packets().get(index).data(), mono.packets().get(index + 1).data()));
                packets.add(new Packet(0, block, packets.size() * 160L, packets.size() * 160L, 160, true));
            }
            var track = AudioTrack.builder().id(0).codec(Codec.AMR_NB).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                    .layout(ChannelLayout.STEREO).build();
            var wav = mux(new Stream_(track, packets));
            var data = chunks(wav).get("data");
            var magic = "#!AMR_MC1.0\n".getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(magic, Arrays.copyOf(data, magic.length));
            assertEquals(2, ByteBuffer.wrap(data, magic.length, 4).getInt(), "channel description");
            assertEquals(packets.size() * 160L, fact(wav));
            var output = demuxWav(wav);
            assertEquals(2, output.track().layout().orElseThrow().channels());
            assertEquals(packets.size(), output.packets().size());
            for (var index = 0; index < packets.size(); index++) {
                assertArrayEquals(packets.get(index).data(), output.packets().get(index).data(), "frame-block " + index);
            }
        }

        @Test
        @DisplayName("switches to RF64 with the sample count of the coded audio in ds64")
        void rf64() {
            var input = demux(Fixtures.read(encoded("mp2", "mp2", 48000, 2, "-b:a", "192k").get()));
            var sink = MediaSink.memory();
            MuxSupport.mux(List.of(input.track()), input.packets(), new WavMuxer(sink, WavMuxerOptions.defaults(), 1000));
            var wav = sink.toByteArray();
            assertEquals("RF64", new String(wav, 0, 4, StandardCharsets.US_ASCII));
            var header = le(wav);
            assertEquals(input.packets().size() * 1152L, header.getLong(36), "ds64 sample count");
            assertEquals(input.packets().size(), demuxWav(wav).packets().size());
            MuxSupport.assertFfmpegClean("coded-rf64.wav", wav);
        }
    }

    /** The fields of an MPEG audio frame header, decoded in the test (ISO/IEC 11172-3 clause 2.4.2.3). */
    record Header(boolean mpeg1, int layer, boolean crc, int bitrateIndex, int rateIndex, boolean padded,
                  boolean privateBit, int mode, int modeExtension, boolean copyright, boolean original, int emphasis) {
        static Header of(byte[] frame) {
            var b1 = frame[1] & 0xFF;
            var b2 = frame[2] & 0xFF;
            var b3 = frame[3] & 0xFF;
            return new Header((b1 & 0x08) != 0, 4 - ((b1 >> 1) & 3), (b1 & 1) == 0, b2 >> 4, (b2 >> 2) & 3, (b2 & 2) != 0,
                    (b2 & 1) != 0, b3 >> 6, (b3 >> 4) & 3, (b3 & 8) != 0, (b3 & 4) != 0, b3 & 3);
        }
    }

    @Nested
    @DisplayName("MPEG audio fields of EBU Tech 3285 Supplement 1")
    class Mpeg {
        /** The {@code MPEG1WAVEFORMAT} header flags of a set of frames, as Tech 3285 Supplement 1 A1.3 defines them. */
        static int[] mpegFlags(List<Packet> frames) {
            int layers = 0, modes = 0, extensions = 0, flags = 0;
            for (var packet : frames) {
                var header = Header.of(packet.data());
                layers |= 1 << (header.layer() - 1);
                modes |= 1 << header.mode();
                if (header.mode() == 1) {
                    extensions |= 1 << header.modeExtension();
                }
                flags |= (header.privateBit() ? 1 : 0) | (header.copyright() ? 2 : 0) | (header.original() ? 4 : 0)
                        | (header.crc() ? 8 : 0) | (header.mpeg1() ? 0x10 : 0);
            }
            return new int[]{layers, modes, extensions, flags};
        }

        static void assertMpeg1Format(byte[] wav, List<Packet> frames, int blockAlign, long bitrate, long byteRate) {
            var fields = le(chunks(wav).get("fmt "));
            var expected = mpegFlags(frames);
            assertEquals(0x0050, fields.getShort(0) & 0xFFFF);
            assertEquals(byteRate, fields.getInt(8), "nAvgBytesPerSec");
            assertEquals(blockAlign, fields.getShort(12), "nBlockAlign");
            assertEquals(0, fields.getShort(14), "wBitsPerSample");
            assertEquals(22, fields.getShort(16), "cbSize");
            assertEquals(expected[0], fields.getShort(18), "fwHeadLayer");
            assertEquals(bitrate, fields.getInt(20), "dwHeadBitrate");
            assertEquals(expected[1], fields.getShort(24), "fwHeadMode");
            assertEquals(expected[2], fields.getShort(26), "fwHeadModeExt");
            assertEquals(Header.of(frames.getFirst().data()).emphasis() + 1, fields.getShort(28), "wHeadEmphasis");
            assertEquals(expected[3], fields.getShort(30), "fwHeadFlags");
            assertEquals(0L, fields.getLong(32), "dwPTSLow and dwPTSHigh");
            assertEquals(frames.size() * (long) (Header.of(frames.getFirst().data()).layer() == 1 ? 384 : 1152), fact(wav));
        }

        static void assertExtension(byte[] wav, int information, int frameSize) {
            var mext = chunks(wav).get("mext");
            assertEquals(12, mext.length);
            var fields = le(mext);
            assertEquals(information, fields.getShort(0), "SoundInformation");
            assertEquals(frameSize, fields.getShort(2), "FrameSize");
            assertEquals(0, fields.getShort(4), "AncillaryDataLength");
            assertEquals(0, fields.getShort(6), "AncillaryDataDef");
            assertEquals(0, fields.getInt(8), "Reserved");
        }

        @Test
        @DisplayName("describes constant 576-byte Layer II frames at 48 kHz as homogeneous without padding")
        void constantFrames() {
            var input = demux(Fixtures.read(encoded("mp2", "mp2", 48000, 2, "-b:a", "192k").get()));
            assertTrue(input.packets().stream().allMatch(packet -> packet.size() == 576));
            var wav = mux(input);
            assertMpeg1Format(wav, input.packets(), 576, 192_000, 24_000);
            assertExtension(wav, 0b011, 576);
        }

        @Test
        @DisplayName("describes padded 44.1 kHz frames by their unpadded size and a block alignment of 1")
        void paddedFrames() {
            var input = demux(Fixtures.read(encoded("mp2", "mp2", 44100, 1, "-b:a", "96k").get()));
            var sizes = input.packets().stream().mapToInt(Packet::size).distinct().sorted().toArray();
            assertArrayEquals(new int[]{313, 314}, sizes);
            var wav = mux(input);
            assertMpeg1Format(wav, input.packets(), 1, 96_000, 12_000);
            assertExtension(wav, 0b001, 313);
        }

        @Test
        @DisplayName("describes frames of two bit rates as non-homogeneous with a variable bit rate")
        void variableBitrate() {
            var fast = demux(Fixtures.read(encoded("mp2", "mp2", 48000, 2, "-b:a", "192k").get()));
            var slow = demux(Fixtures.read(encoded("mp2", "mp2", 48000, 2, "-b:a", "128k").get()));
            var packets = new ArrayList<>(fast.packets());
            packets.addAll(slow.packets());
            var wav = mux(new Stream_(fast.track(), packets));
            var bytes = packets.stream().mapToLong(Packet::size).sum();
            assertMpeg1Format(wav, packets, 1, 0, Math.round(bytes * 48000.0 / (packets.size() * 1152L)));
            assertExtension(wav, 0, 0);
        }

        /** Builds Layer II frames of a header and zero bytes. */
        static List<Packet> frames(int header, int count, int size) {
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < count; index++) {
                var frame = new byte[size];
                ByteBuffer.wrap(frame).putInt(header);
                packets.add(new Packet(0, frame, index * 1152L, index * 1152L, 1152, true));
            }
            return packets;
        }

        static AudioTrack track(Codec codec, int rate, ChannelLayout layout) {
            return AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                    .layout(layout).build();
        }

        @Test
        @DisplayName("flags unpadded 44.1 kHz frames (bit 2) and free format (bit 3)")
        void syntheticFrames() {
            // MPEG-1 Layer II, no CRC, 192 kbit/s, 44.1 kHz, no padding, private bit, joint stereo, mode extension 2,
            // copyright, original, no emphasis: 144 * 192000 / 44100 = 626 bytes
            var unpadded = frames(0xFFFDA16C, 20, 626);
            var wav = mux(new Stream_(track(Codec.MP2, 44100, ChannelLayout.STEREO), unpadded));
            assertMpeg1Format(wav, unpadded, 626, 192_000, 24_000);
            assertExtension(wav, 0b111, 626);
            assertEquals(1 << 2, le(chunks(wav).get("fmt ")).getShort(26), "the mode extension of joint stereo");
            // free format (bit rate index 0) at 48 kHz, 500-byte frames
            var free = frames(0xFFFD0400, 20, 500);
            var freeWav = mux(new Stream_(track(Codec.MP2, 48000, ChannelLayout.STEREO), free));
            assertMpeg1Format(freeWav, free, 500, 0, Math.round(20 * 500 * 48000.0 / (20 * 1152)));
            assertExtension(freeWav, 0b1011, 500);
        }

        @Test
        @DisplayName("writes MPEGLAYER3WAVEFORMAT with the padding policy, block size and encoder delay of the frames")
        void layer3() {
            var cbr = demux(Fixtures.read(encoded("libmp3lame", "mp3", 48000, 2, "-b:a", "128k").get()));
            var fields = le(chunks(mux(cbr)).get("fmt "));
            assertEquals(0x0055, fields.getShort(0) & 0xFFFF);
            assertEquals(16_000, fields.getInt(8), "nAvgBytesPerSec");
            assertEquals(384, fields.getShort(12), "nBlockAlign");
            assertEquals(12, fields.getShort(16), "cbSize");
            assertEquals(1, fields.getShort(18), "wID");
            assertEquals(2, fields.getInt(20), "fdwFlags: padding off");
            assertEquals(384, fields.getShort(24), "nBlockSize");
            assertEquals(1, fields.getShort(26), "nFramesPerBlock");
            assertEquals(Math.min(0xFFFF, cbr.track().skipSamples()), fields.getShort(28) & 0xFFFF, "nCodecDelay");

            var padded = demux(Fixtures.read(encoded("libmp3lame", "mp3", 44100, 2, "-b:a", "128k").get()));
            fields = le(chunks(mux(padded)).get("fmt "));
            assertEquals(16_000, fields.getInt(8));
            assertEquals(1, fields.getShort(12), "nBlockAlign of frames of two sizes");
            assertEquals(0, fields.getInt(20), "fdwFlags: ISO padding");
            assertEquals(417, fields.getShort(24), "nBlockSize: 144 * 128000 / 44100");

            var vbr = demux(Fixtures.read(encoded("libmp3lame", "mp3", 44100, 2, "-q:a", "4").get()));
            var wav = mux(vbr);
            fields = le(chunks(wav).get("fmt "));
            var bytes = vbr.packets().stream().mapToLong(Packet::size).sum();
            assertEquals(Math.round(bytes * 44100.0 / (vbr.packets().size() * 1152L)), fields.getInt(8));
            assertEquals(vbr.packets().stream().mapToInt(Packet::size).max().orElseThrow(), fields.getShort(24),
                    "nBlockSize of variable frames: the largest");
            assertFalse(chunks(wav).containsKey("mext"));
        }

        @Test
        @DisplayName("writes provisional fields to a non-seekable sink and final ones over them when seekable")
        void provisional() {
            var input = demux(Fixtures.read(encoded("mp2", "mp2", 48000, 2, "-b:a", "192k").get()));
            var streamed = MuxSupport.toStream(List.of(input.track()), input.packets(), WavMuxer::new);
            var fields = le(chunks(streamed).get("fmt "));
            assertEquals(1, fields.getShort(12), "nBlockAlign");
            assertEquals(0, fields.getInt(20), "dwHeadBitrate");
            assertEquals(24_000, fields.getInt(8), "nAvgBytesPerSec of the first frame");
            assertExtension(streamed, 0, 0);
            var unfinished = MediaSink.memory();
            var muxer = new WavMuxer(unfinished);
            muxer.addTrack(input.track());
            input.packets().forEach(muxer::writePacket);
            muxer.close();
            assertEquals(1, le(chunks(unfinished.toByteArray()).get("fmt ")).getShort(12),
                    "an unfinished file keeps the provisional fields");
        }
    }

    static long opusSamples(byte[] packet) {
        var toc = packet[0] & 0xFF;
        var config = toc >> 3;
        int frame;
        if (config < 12) {
            frame = (config & 3) == 3 ? 2880 : 480 << (config & 3);
        } else if (config < 16) {
            frame = (config & 1) == 0 ? 480 : 960;
        } else {
            frame = 120 << (config & 3);
        }
        var frames = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> packet[1] & 0x3F;
        };
        return (long) frame * frames;
    }

    @Nested
    @DisplayName("self-delimited Opus")
    class SelfDelimited {
        static byte[] bytes(int length, int seed) {
            var data = new byte[length];
            for (var index = 0; index < length; index++) {
                data[index] = (byte) (seed + index * 7);
            }
            return data;
        }

        static List<byte[]> packets() {
            var packets = new ArrayList<byte[]>();
            packets.add(concatenate(List.of(new byte[]{(byte) 0x78}, bytes(40, 1))));
            packets.add(new byte[]{(byte) 0x78});
            packets.add(concatenate(List.of(new byte[]{(byte) 0x79}, bytes(600, 2))));
            packets.add(concatenate(List.of(new byte[]{(byte) 0x7A, 30}, bytes(30 + 300, 3))));
            packets.add(concatenate(List.of(new byte[]{(byte) 0x7A, (byte) 253, 8}, bytes(285 + 10, 4))));
            // code 3, constant bit rate, 3 frames, 255 + 9 bytes of padding
            packets.add(concatenate(List.of(new byte[]{(byte) 0x0B, 0x43, (byte) 255, 10}, bytes(3 * 100 + 254 + 10, 5))));
            // code 3, variable bit rate, 3 frames of 10, 260 and 5 bytes, 2 bytes of padding
            packets.add(concatenate(List.of(new byte[]{(byte) 0x0B, (byte) 0xC3, 2, 10, (byte) 252, 2}, bytes(10 + 260 + 5 + 2, 6))));
            return packets;
        }

        @Test
        @DisplayName("adds the implicit frame length of every packet code, as the reference converter does")
        void framing() {
            var packets = packets();
            var stream = new ArrayList<Packet>();
            for (var packet : packets) {
                stream.add(new Packet(0, packet, stream.size() * 960L, stream.size() * 960L, 960, true));
            }
            var track = AudioTrack.builder().id(0).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO).build();
            var wav = mux(new Stream_(track, stream));
            var expected = concatenate(packets.stream().map(WavCodedPayloadTest.Opus::selfDelimited).toList());
            assertArrayEquals(expected, chunks(wav).get("data"));
            var head = Arrays.copyOfRange(chunks(wav).get("fmt "), 18, 18 + 19);
            assertEquals("OpusHead", new String(head, 0, 8, StandardCharsets.US_ASCII));
            assertEquals(2, head[9], "channel count");
            var output = demuxWav(wav);
            assertEquals(packets.size(), output.packets().size());
            for (var index = 0; index < packets.size(); index++) {
                assertArrayEquals(packets.get(index), output.packets().get(index).data(), "packet " + index);
            }
            assertEquals(packets.stream().mapToLong(WavMuxerCodedTest::opusSamples).sum(), fact(wav));
        }

        @Test
        @DisplayName("rejects packets that break the framing rules")
        void malformed() {
            var track = AudioTrack.builder().id(0).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.MONO).build();
            var bad = List.of(new byte[0], new byte[]{0x79, 1, 2, 3}, new byte[]{0x7A, (byte) 253},
                    new byte[]{0x7A, 50, 1, 2}, new byte[]{0x0B}, new byte[]{0x0B, 0x03, 1, 2},
                    new byte[]{0x0B, (byte) 0xC2, 9, 1}, new byte[]{0x0B, 0x00},
                    concatenate(List.of(new byte[]{0x78}, new byte[1276])),
                    new byte[]{0x0B, 0x41});
            for (var packet : bad) {
                try (var muxer = new WavMuxer(MediaSink.memory())) {
                    muxer.addTrack(track);
                    assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, packet, 0, 0, 960, true)),
                            HexFormat.of().formatHex(packet));
                }
            }
        }
    }

    @Nested
    @DisplayName("converted PCM")
    class ConvertedPcm {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_S8", "PCM_S16BE", "PCM_S24BE", "PCM_S32BE", "PCM_S64BE",
                "PCM_F32BE", "PCM_F64BE"})
        @DisplayName("stores the same samples little-endian or unsigned, as ffmpeg's decode of the raw samples shows")
        void convert(Codec codec) {
            var input = PcmPackets.tone(codec, 44_100, ChannelLayout.STEREO, 4410);
            var wav = mux(new Stream_(input.track(), input.packets()));
            var output = demuxWav(wav);
            var stored = switch (codec) {
                case PCM_S8 -> Codec.PCM_U8;
                case PCM_S16BE -> Codec.PCM_S16LE;
                case PCM_S24BE -> Codec.PCM_S24LE;
                case PCM_S32BE -> Codec.PCM_S32LE;
                case PCM_S64BE -> Codec.PCM_S64LE;
                case PCM_F32BE -> Codec.PCM_F32LE;
                default -> Codec.PCM_F64LE;
            };
            assertEquals(stored, output.track().codec());
            var original = input.bytes();
            var expected = new byte[original.length];
            var width = codec.pcmBitsPerSample().orElseThrow() / 8;
            for (var sample = 0; sample < original.length; sample += width) {
                for (var index = 0; index < width; index++) {
                    expected[sample + index] = codec == Codec.PCM_S8 ? (byte) (original[sample] ^ 0x80)
                            : original[sample + width - 1 - index];
                }
            }
            assertArrayEquals(expected, PcmPackets.concatenate(output.packets()));
            if (codec != Codec.PCM_S64BE) {
                var raw = MuxSupport.save("raw-" + codec + ".pcm", original);
                var file = MuxSupport.save("converted-" + codec + ".wav", wav);
                var format = codec.displayName().substring(4);
                var reference = Oracle.decodeAudio(raw, List.of("-f", format, "-ar", "44100", "-ac", "2"));
                assertArrayEquals(reference, Oracle.decodeAudio(file));
                MuxSupport.assertFfmpegClean("converted-" + codec + ".wav", wav);
            }
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {
        static AudioTrack track(Codec codec, int rate, ChannelLayout layout, byte[] config) {
            var builder = AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                    .layout(layout);
            if (config != null) {
                builder.codecConfig(config);
            }
            return builder.build();
        }

        @Test
        @DisplayName("rejects tracks without what their codec needs and codecs WAV cannot carry")
        void tracks() {
            var cases = List.of(track(Codec.AAC, 48000, ChannelLayout.STEREO, null),
                    track(Codec.AAC, 48000, ChannelLayout.STEREO, new byte[]{0x12}),
                    track(Codec.FLAC, 44100, ChannelLayout.STEREO, null),
                    track(Codec.OPUS, 48000, ChannelLayout.SURROUND_5_1, null),
                    track(Codec.AMR_NB, 16000, ChannelLayout.MONO, null),
                    track(Codec.AMR_WB, 8000, ChannelLayout.MONO, null),
                    track(Codec.VORBIS, 48000, ChannelLayout.STEREO, null),
                    track(Codec.ALAC, 48000, ChannelLayout.STEREO, null));
            for (var track : cases) {
                assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory()).addTrack(track),
                        track.codec().displayName());
            }
            assertFalse(WavMuxer.supports(Codec.VORBIS));
            assertTrue(WavMuxer.supports(Codec.PCM_S16BE));
            assertTrue(Containers.isMuxable(ContainerFormat.WAV, Codec.MP3));
        }

        @Test
        @DisplayName("rejects packets that do not start with a frame of the track's codec")
        void packets() {
            var mp2 = demux(Fixtures.read(encoded("mp2", "mp2", 48000, 2, "-b:a", "192k").get())).packets().getFirst();
            var cases = List.of(
                    Map.entry(track(Codec.MP3, 48000, ChannelLayout.STEREO, null), mp2.data()),
                    Map.entry(track(Codec.MP2, 48000, ChannelLayout.STEREO, null), new byte[]{0, 1, 2, 3, 4}),
                    Map.entry(track(Codec.AC3, 48000, ChannelLayout.STEREO, null), new byte[]{0x0B, 0x78, 0, 0, 0, 0}),
                    Map.entry(track(Codec.DTS, 48000, ChannelLayout.STEREO, null), new byte[16]),
                    Map.entry(track(Codec.AMR_NB, 8000, ChannelLayout.MONO, null), new byte[]{0x3C, 1, 2}),
                    Map.entry(track(Codec.AMR_NB, 8000, ChannelLayout.STEREO, null), new byte[]{0x7C}));
            for (var entry : cases) {
                try (var muxer = new WavMuxer(MediaSink.memory())) {
                    muxer.addTrack(entry.getKey());
                    assertThrows(IllegalArgumentException.class,
                            () -> muxer.writePacket(new Packet(0, entry.getValue(), 0, 0, 1, true)),
                            entry.getKey().codec().displayName());
                }
            }
        }
    }

    @Nested
    @DisplayName("Windows Media Foundation")
    class MediaFoundation {
        static Path driver() {
            var setting = System.getProperty("smalt.test.mfAudioDecode");
            Skip.unless(setting != null && Files.isRegularFile(Path.of(setting)), "opt-in:mf-decode",
                    "-Dsmalt.test.mfAudioDecode names no Media Foundation audio driver");
            return Path.of(setting);
        }

        static byte[] decode(Path driver, Path input) {
            try {
                var output = Files.createTempFile(Scratch.directory("mf-wav"), "mf", ".f32");
                var result = Oracle.execute(driver, Duration.ofMinutes(2), null, List.of(input.toString(), output.toString()));
                if (result.exitCode() != 0) {
                    throw new AssertionError("Media Foundation could not decode " + input + ": "
                            + result.stderr());
                }
                return Files.readAllBytes(output);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        static Stream<Arguments> streams() {
            return Stream.of(
                    Arguments.of("mp3", encoded("libmp3lame", "mp3", 44100, 2, "-b:a", "128k")),
                    Arguments.of("mp2", encoded("mp2", "mp2", 48000, 2, "-b:a", "192k")),
                    Arguments.of("aac", encoded("aac", "aac", 48000, 2, "-b:a", "128k")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("streams")
        @DisplayName("decodes the WAVE file exactly as it decodes the frames of its data chunk as an elementary stream")
        void decodes(String extension, Supplier<Path> file) {
            var driver = driver();
            var input = demux(Fixtures.read(file.get()));
            var bytes = mux(input);
            var wav = MuxSupport.save("mf-" + extension + ".wav", bytes);
            var elementary = MuxSupport.save("mf-" + extension + "." + extension, chunks(bytes).get("data"));
            var expected = decode(driver, elementary);
            assertTrue(expected.length > 0);
            assertArrayEquals(expected, decode(driver, wav));
        }
    }
}
