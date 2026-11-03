package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the IEC 61937 encapsulations of {@link WavMuxer}: the data chunk of ffmpeg-encoded AC-3, E-AC-3, MPEG audio,
 * AAC, DTS and TrueHD streams against ffmpeg's {@code spdif} muxer (black box) byte for byte, the read back through
 * {@link WavDemuxer} packet for packet, ffprobe's packets and ffmpeg's decode of the AC-3 and DTS files, the
 * {@code fmt } structures of both forms, DTS-HD bursts of extension substreams against ffmpeg's high bit rate output,
 * E-AC-3 groups of fewer-block frames and dependent substreams, TrueHD access units longer than their MAT slots, the
 * error flag, and the rejected inputs. The expected bursts of the synthetic cases are built in the test from
 * IEC 61937-1 (preamble, little-endian words, zero stuffing) independently of the writer.
 */
@DisplayName("WavMuxer IEC 61937 bursts")
class WavMuxerIec61937Test {
    private static final byte[] PCM_GUID = HexFormat.of().parseHex("0100000000001000800000aa00389b71");

    /**
     * An ffmpeg-encoded stream: the IEC 61937 data type of its bursts ({@code -1} when its frames fill their
     * repetition period and go without bursts), the size of a burst, the sampling rate and channel count of the burst
     * stream, and whether ffprobe finds the bursts in a PCM WAVE file.
     */
    record Source(String name, Supplier<Path> file, Codec codec, int type, int period, int rate, int channels,
                  boolean ffprobeReads) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Supplier<Path> encoded(String extension, int rate, String layout, double noise, String... options) {
        return () -> {
            var source = "aevalsrc=exprs=0.4*sin(2*PI*440*t)+0.1*sin(2*PI*1500*t)+" + noise + "*(random(0)-0.5):c="
                    + layout + ":s=" + rate + ":d=1.3";
            return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", source), List.of(options));
        };
    }

    static Stream<Source> sources() {
        return Stream.of(
                new Source("AC-3 48 kHz stereo", encoded("ac3", 48000, "stereo", 0.05, "-c:a", "ac3", "-b:a", "192k"),
                        Codec.AC3, 1, 6144, 48000, 2, true),
                new Source("AC-3 44.1 kHz 5.1 bsmod 1", encoded("ac3", 44100, "5.1", 0.05, "-c:a", "ac3", "-b:a", "384k",
                        "-audio_service_type", "1"), Codec.AC3, 0x101, 6144, 44100, 2, true),
                new Source("E-AC-3 5.1", encoded("eac3", 48000, "5.1", 0.05, "-c:a", "eac3", "-b:a", "384k"), Codec.EAC3,
                        21, 24576, 192000, 2, false),
                new Source("MP2 48 kHz stereo", encoded("mp2", 48000, "stereo", 0.05, "-c:a", "mp2", "-b:a", "192k"),
                        Codec.MP2, 5, 4608, 48000, 2, false),
                new Source("MP2 44.1 kHz mono", encoded("mp2", 44100, "mono", 0.05, "-c:a", "mp2", "-b:a", "96k"),
                        Codec.MP2, 5, 4608, 44100, 2, false),
                new Source("MP2 24 kHz", encoded("mp2", 24000, "stereo", 0.05, "-c:a", "mp2", "-b:a", "96k"), Codec.MP2, 9,
                        9216, 48000, 2, false),
                new Source("MP3 48 kHz", encoded("mka", 48000, "stereo", 0.05, "-c:a", "libmp3lame", "-b:a", "128k"),
                        Codec.MP3, 5, 4608, 48000, 2, false),
                new Source("MP3 22.05 kHz", encoded("mka", 22050, "mono", 0.05, "-c:a", "libmp3lame", "-b:a", "32k"),
                        Codec.MP3, 10, 4608, 44100, 2, false),
                new Source("AAC stereo", encoded("aac", 48000, "stereo", 0.05, "-c:a", "aac", "-b:a", "128k"), Codec.AAC, 7,
                        4096, 48000, 2, false),
                new Source("AAC 5.1", encoded("aac", 44100, "5.1", 0.05, "-c:a", "aac", "-b:a", "256k"), Codec.AAC, 7, 4096,
                        44100, 2, false),
                new Source("DTS stereo", encoded("mkv", 48000, "stereo", 0.05, "-strict", "-2", "-c:a", "dca"), Codec.DTS,
                        11, 2048, 48000, 2, true),
                new Source("DTS 5.1", encoded("mkv", 48000, "5.1", 0.05, "-strict", "-2", "-c:a", "dca"), Codec.DTS, 11,
                        2048, 48000, 2, true),
                new Source("DTS at the full rate", encoded("mkv", 48000, "stereo", 0.05, "-strict", "-2", "-c:a", "dca",
                        "-b:a", "1536k"), Codec.DTS, -1, 2048, 48000, 2, false),
                new Source("TrueHD 48 kHz 5.1", encoded("mka", 48000, "5.1", 0.6, "-strict", "-2", "-c:a", "truehd"),
                        Codec.TRUEHD, 22, 61440, 192000, 8, false),
                new Source("TrueHD 96 kHz stereo", encoded("mka", 96000, "stereo", 0.6, "-strict", "-2", "-c:a", "truehd"),
                        Codec.TRUEHD, 22, 61440, 192000, 8, false),
                new Source("TrueHD 44.1 kHz mono", encoded("mka", 44100, "mono", 0.01, "-strict", "-2", "-c:a", "truehd"),
                        Codec.TRUEHD, 22, 61440, 176400, 8, false));
    }

    static Stream<Arguments> sourcesAndForms() {
        return sources().flatMap(source -> Stream.of(WavMuxerOptions.Encapsulation.IEC61937_PCM,
                WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE).map(form -> Arguments.of(source, form)));
    }

    static WavMuxerOptions options(WavMuxerOptions.Encapsulation form) {
        return WavMuxerOptions.builder().encapsulation(form).build();
    }

    static byte[] mux(AudioTrack track, List<Packet> packets, WavMuxerOptions options) {
        return MuxSupport.toMemory(List.of(track), packets, sink -> new WavMuxer(sink, options));
    }

    static byte[] mux(WavMuxerCodedTest.Stream_ stream, WavMuxerOptions options) {
        return mux(stream.track(), stream.packets(), options);
    }

    static byte[] spdif(Path raw, String... options) {
        var output = new ArrayList<>(List.of("-c", "copy", "-f", "spdif"));
        output.addAll(List.of(options));
        return Fixtures.read(Fixtures.ffmpeg("spdif", List.of("-i", raw.toString()), output));
    }

    static int word(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    /** Returns the bytes as little-endian 16-bit words, a last odd byte padded with zero. */
    static byte[] words(byte[] bitstream) {
        var stored = new byte[bitstream.length + 1 & ~1];
        for (var index = 0; index < bitstream.length; index++) {
            stored[index ^ 1] = bitstream[index];
        }
        return stored;
    }

    /** Builds a burst from IEC 61937-1: Pa, Pb, Pc, Pd as little-endian words, the payload words, zero stuffing. */
    static byte[] burst(int pc, int pd, byte[] payload, int period) {
        var burst = ByteBuffer.allocate(period).order(ByteOrder.LITTLE_ENDIAN);
        burst.putShort((short) 0xF872).putShort((short) 0x4E1F).putShort((short) pc).putShort((short) pd);
        burst.put(words(payload));
        return burst.array();
    }

    static byte[] concatenate(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    @Nested
    @DisplayName("ffmpeg-encoded streams")
    class Streams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerIec61937Test#sources")
        @DisplayName("stores the bursts ffmpeg's spdif muxer writes for the same stream")
        void matchesFfmpeg(Source source) {
            var raw = source.file().get();
            var input = WavMuxerCodedTest.demux(Fixtures.read(raw));
            assertEquals(source.codec(), input.track().codec());
            var data = WavMuxerCodedTest.chunks(mux(input, WavMuxerOptions.forIec61937())).get("data");
            var reference = spdif(raw);
            if (source.codec() == Codec.TRUEHD) {
                // ffmpeg does not write the last MAT frame, which this muxer pads and writes
                assertEquals(reference.length + source.period(), data.length, "one more MAT frame");
                assertArrayEquals(reference, Arrays.copyOf(data, reference.length));
                return;
            }
            assertEquals(reference.length, data.length, "length of the burst stream");
            if (source.type() < 0) {
                assertNotEquals(0xF872, word(data, 0), "frames that fill their period go without bursts");
                assertArrayEquals(reference, data);
                return;
            }
            for (var offset = 0; offset < data.length; offset += source.period()) {
                assertEquals(0xF872, word(data, offset), "Pa of the burst at " + offset);
                assertEquals(source.type(), word(data, offset + 4), "Pc of the burst at " + offset);
                var ours = word(data, offset + 6);
                var theirs = word(reference, offset + 6);
                if (ours != theirs) {
                    // ffmpeg rounds the bit length of an odd-sized MPEG or ADTS frame up to whole words; Pd counts its
                    // bits
                    assertTrue(source.codec() == Codec.MP2 || source.codec() == Codec.MP3 || source.codec() == Codec.AAC,
                            "Pd at " + offset);
                    assertEquals(theirs - 8, ours, "Pd of an odd-sized frame at " + offset);
                    assertEquals(8, ours % 16, "an odd-sized frame at " + offset);
                    reference[offset + 6] = data[offset + 6];
                    reference[offset + 7] = data[offset + 7];
                }
            }
            assertArrayEquals(reference, data);
        }

        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerIec61937Test#sourcesAndForms")
        @DisplayName("declares the burst stream and reads back every packet with contiguous timestamps")
        void roundTrip(Source source, WavMuxerOptions.Encapsulation form) {
            var input = WavMuxerCodedTest.demux(Fixtures.read(source.file().get()));
            var wav = mux(input, options(form));
            var chunks = WavMuxerCodedTest.chunks(wav);
            assertFalse(chunks.containsKey("fact"), "PCM carries no fact chunk");
            var fmt = chunks.get("fmt ");
            var format = WavMuxerCodedTest.le(fmt);
            var extensible = form == WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE;
            assertEquals(extensible || source.channels() > 2 ? 0xFFFE : 1, format.getShort(0) & 0xFFFF, "format tag");
            assertEquals(source.channels(), format.getShort(2), "channels");
            assertEquals(source.rate(), format.getInt(4), "sampling rate");
            assertEquals(source.rate() * 2 * source.channels(), format.getInt(8), "byte rate");
            assertEquals(2 * source.channels(), format.getShort(12), "block alignment");
            assertEquals(16, format.getShort(14), "bits per sample");
            var layout = input.track().layout().orElseThrow();
            if (extensible) {
                assertEquals(34, format.getShort(16), "cbSize of WAVEFORMATEXTENSIBLE_IEC61937");
                assertEquals(16, format.getShort(18), "valid bits");
                assertArrayEquals(iecGuid(source), Arrays.copyOfRange(fmt, 24, 40), "sub-format");
                assertEquals(input.track().sampleRate(), format.getInt(40), "dwEncodedSamplesPerSec");
                assertEquals(layout.channels(), format.getInt(44), "dwEncodedChannelCount");
                assertTrue(format.getInt(48) > 0, "dwAverageBytesPerSec");
            } else if (source.channels() > 2) {
                assertEquals(0, format.getInt(20), "no channel mask");
                assertArrayEquals(PCM_GUID, Arrays.copyOfRange(fmt, 24, 40));
            } else {
                assertEquals(16, fmt.length, "plain PCMWAVEFORMAT");
            }

            var output = WavMuxerCodedTest.demuxWav(wav);
            assertEquals(source.codec(), output.track().codec(), "codec read back");
            assertEquals(input.track().sampleRate(), output.track().sampleRate(), "sampling rate read back");
            assertEquals(layout.channels(), output.track().layout().orElseThrow().channels(), "channels read back");
            assertEquals(input.packets().size(), output.packets().size(), "packet count");
            var time = 0L;
            for (var index = 0; index < input.packets().size(); index++) {
                var expected = input.packets().get(index).data();
                if (source.codec() == Codec.DTS) {
                    expected = Arrays.copyOf(expected, dtsCoreSize(expected, 0));
                }
                assertArrayEquals(expected, output.packets().get(index).data(), "packet " + index);
                assertEquals(time, output.packets().get(index).pts(), "timestamp of packet " + index);
                time += output.packets().get(index).duration();
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerIec61937Test#sources")
        @DisplayName("writes a file ffmpeg reads cleanly, whose AC-3 and DTS packets and decode match the raw stream")
        void ffmpeg(Source source) {
            var raw = source.file().get();
            var input = WavMuxerCodedTest.demux(Fixtures.read(raw));
            var wav = mux(input, WavMuxerOptions.forIec61937());
            var name = "iec61937-" + source.name().replace(' ', '-').replace('.', '_') + ".wav";
            MuxSupport.assertFfmpegClean(name, wav);
            if (!source.ffprobeReads()) {
                return;
            }
            var file = MuxSupport.save(name, wav);
            var expected = ProbedPacket.probe(raw).stream().map(packet -> List.of((long) packet.size(), packet.crc32()))
                    .toList();
            var actual = ProbedPacket.probe(file).stream().map(packet -> List.of((long) packet.size(), packet.crc32()))
                    .toList();
            assertEquals(expected, actual, "ffprobe packets");
            assertArrayEquals(Oracle.decodeAudio(raw), Oracle.decodeAudio(file), "ffmpeg decode");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerIec61937Test#sources")
        @DisplayName("writes the same bursts to a non-seekable sink, which WavDemuxer reads back")
        void streaming(Source source) {
            var input = WavMuxerCodedTest.demux(Fixtures.read(source.file().get()));
            var options = WavMuxerOptions.forIec61937();
            var seekable = mux(input, options);
            var streamed = MuxSupport.toStream(List.of(input.track()), input.packets(), sink -> new WavMuxer(sink, options));
            assertArrayEquals(WavMuxerCodedTest.chunks(seekable).get("data"), WavMuxerCodedTest.chunks(streamed).get("data"));
            assertEquals(-1, WavMuxerCodedTest.le(streamed).getInt(4), "streaming RIFF size");
            assertEquals(input.packets().size(), WavMuxerCodedTest.demuxWav(streamed).packets().size());
        }

        static byte[] iecGuid(Source source) {
            var tag = switch (source.codec()) {
                case AC3 -> -1;
                case EAC3 -> 10;
                case MP2 -> source.type() == 9 ? 5 : 3;
                case MP3 -> 4;
                case AAC -> 6;
                case DTS -> -2;
                default -> 12;
            };
            return switch (tag) {
                case -1 -> HexFormat.of().parseHex("9200000000001000800000aa00389b71");
                case -2 -> HexFormat.of().parseHex("0800000000001000800000aa00389b71");
                default -> HexFormat.of().parseHex(String.format("%02x000000ea0c1000800000aa00389b71", tag));
            };
        }

        @Test
        @DisplayName("updates the average coded byte rate at the end and leaves it zero in a streaming header")
        void averageByteRate() {
            var input = WavMuxerCodedTest.demux(Fixtures.read(sources().findFirst().orElseThrow().file().get()));
            var options = options(WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE);
            var seekable = WavMuxerCodedTest.le(WavMuxerCodedTest.chunks(mux(input, options)).get("fmt "));
            // 192 kbit/s AC-3: 768-byte frames of 1536 samples at 48 kHz
            assertEquals(24000, seekable.getInt(48));
            var streamed = MuxSupport.toStream(List.of(input.track()), input.packets(), sink -> new WavMuxer(sink, options));
            assertEquals(0, WavMuxerCodedTest.le(WavMuxerCodedTest.chunks(streamed).get("fmt ")).getInt(48));
        }

        @Test
        @DisplayName("switches to RF64 with the frame count of the burst stream in ds64")
        void rf64() {
            var input = WavMuxerCodedTest.demux(Fixtures.read(sources().findFirst().orElseThrow().file().get()));
            var sink = MediaSink.memory();
            MuxSupport.mux(List.of(input.track()), input.packets(), new WavMuxer(sink, WavMuxerOptions.forIec61937(), 1000));
            var wav = sink.toByteArray();
            assertEquals("RF64", new String(wav, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(input.packets().size() * 1536L, WavMuxerCodedTest.le(wav).getLong(36), "ds64 frame count");
            assertEquals(input.packets().size(), WavMuxerCodedTest.demuxWav(wav).packets().size());
        }
    }

    static int dtsCoreSize(byte[] frame, int offset) {
        return (((frame[offset + 5] & 0x03) << 12) | ((frame[offset + 6] & 0xFF) << 4) | ((frame[offset + 7] & 0xF0) >> 4)) + 1;
    }

    static List<byte[]> dtsFrames(byte[] raw) {
        var frames = new ArrayList<byte[]>();
        for (var offset = 0; offset + 11 <= raw.length; ) {
            var size = dtsCoreSize(raw, offset);
            frames.add(Arrays.copyOfRange(raw, offset, offset + size));
            offset += size;
        }
        return frames;
    }

    static AudioTrack track(Codec codec, int rate, ChannelLayout layout) {
        return AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate).layout(layout)
                .build();
    }

    static List<Packet> packets(List<byte[]> units, int samples) {
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < units.size(); index++) {
            packets.add(new Packet(0, units.get(index), (long) index * samples, (long) index * samples, samples, true));
        }
        return packets;
    }

    @Nested
    @DisplayName("DTS-HD")
    class DtsHd {
        /** Returns ffmpeg's DTS core frames, each followed by an extension substream of 300 bytes. */
        static List<byte[]> frames() {
            var core = dtsFrames(Fixtures.read(encoded("dts", 48000, "stereo", 0.05, "-strict", "-2", "-c:a", "dca").get()));
            // sync, user bits, index 0, narrow sizes, a 32-byte header, 300 bytes
            var extension = new byte[300];
            new SplittableRandom(5).nextBytes(extension);
            System.arraycopy(HexFormat.of().parseHex("64582025" + "0003e0256000"), 0, extension, 0, 10);
            return core.stream().map(frame -> concatenate(List.of(frame, extension))).toList();
        }

        @Test
        @DisplayName("stores frames with an extension substream as the high bit rate type IV bursts of ffmpeg")
        void matchesFfmpeg() {
            var frames = frames();
            var raw = Scratch.share(Scratch.directory("iec61937").resolve("dtshd-" + frames.size() + ".dts"),
                    concatenate(frames));
            var reference = spdif(raw, "-dtshd_rate", "768000");
            var wav = mux(track(Codec.DTS, 48000, ChannelLayout.STEREO), packets(frames, 512), WavMuxerOptions.forIec61937());
            var data = WavMuxerCodedTest.chunks(wav).get("data");
            assertEquals(0x411, word(data, 4), "type IV, period 512 << 4");
            assertEquals(32768, reference.length / frames.size(), "burst size");
            assertArrayEquals(reference, data);
            // the expected first burst, from IEC 61937-5: a 12-byte header with the frame size, the DTS-HD frame
            var header = HexFormat.of().parseHex("0100000000000000fefe");
            var payload = concatenate(List.of(header, new byte[]{(byte) (frames.getFirst().length >> 8),
                    (byte) frames.getFirst().length}, frames.getFirst()));
            var pd = (payload.length + 8 + 15 & ~15) - 8;
            assertArrayEquals(burst(0x411, pd, payload, 32768), Arrays.copyOf(data, 32768));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = WavMuxerOptions.Encapsulation.class, names = {"IEC61937_PCM", "IEC61937_EXTENSIBLE"})
        @DisplayName("declares 8 channels at 192 kHz and reads back each frame with its extension substream")
        void roundTrip(WavMuxerOptions.Encapsulation form) {
            var frames = frames();
            var wav = mux(track(Codec.DTS, 48000, ChannelLayout.STEREO), packets(frames, 512), options(form));
            var fmt = WavMuxerCodedTest.chunks(wav).get("fmt ");
            var format = WavMuxerCodedTest.le(fmt);
            assertEquals(0xFFFE, format.getShort(0) & 0xFFFF);
            assertEquals(8, format.getShort(2));
            assertEquals(192000, format.getInt(4));
            assertEquals(16, format.getShort(12));
            assertArrayEquals(form == WavMuxerOptions.Encapsulation.IEC61937_PCM ? PCM_GUID
                    : HexFormat.of().parseHex("0b000000ea0c1000800000aa00389b71"), Arrays.copyOfRange(fmt, 24, 40));
            var output = WavMuxerCodedTest.demuxWav(wav);
            assertEquals(Codec.DTS, output.track().codec());
            assertEquals(48000, output.track().sampleRate());
            assertEquals(frames.size(), output.packets().size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index), output.packets().get(index).data(), "frame " + index);
                assertEquals(512L * index, output.packets().get(index).pts());
            }
            assertEquals(512L * frames.size(), output.track().duration());
        }

        @Test
        @DisplayName("keeps core bursts of the core frame when the first packet has no extension substream")
        void coreOnly() {
            var frames = frames();
            var core = dtsFrames(Fixtures.read(encoded("dts", 48000, "stereo", 0.05, "-strict", "-2", "-c:a", "dca").get()));
            var mixed = new ArrayList<byte[]>(frames);
            mixed.set(0, core.getFirst());
            var data = WavMuxerCodedTest.chunks(mux(track(Codec.DTS, 48000, ChannelLayout.STEREO), packets(mixed, 512),
                    WavMuxerOptions.forIec61937())).get("data");
            assertEquals(2048 * frames.size(), data.length);
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(burst(11, 8 * core.get(index).length, core.get(index), 2048),
                        Arrays.copyOfRange(data, 2048 * index, 2048 * (index + 1)), "burst " + index);
            }
        }
    }

    @Nested
    @DisplayName("E-AC-3 groups")
    class Eac3Groups {
        static List<Packet> sixBlockFrames() {
            var stream = WavMuxerCodedTest.demux(Fixtures.read(encoded("eac3", 48000, "stereo", 0.05, "-c:a", "eac3", "-b:a",
                    "96k").get()));
            return stream.packets();
        }

        /** Returns a copy of a sync frame with its {@code numblkscod} set (ETSI TS 102 366 E.1.2.2). */
        static byte[] blocks(byte[] frame, int code) {
            var copy = frame.clone();
            copy[4] = (byte) (copy[4] & 0xCF | code << 4);
            return copy;
        }

        /** Returns a copy of a sync frame turned into a dependent substream frame ({@code strmtyp} 1). */
        static byte[] dependent(byte[] frame) {
            var copy = frame.clone();
            copy[2] = (byte) (copy[2] & 0x3F | 0x40);
            return copy;
        }

        static AudioTrack track() {
            return WavMuxerIec61937Test.track(Codec.EAC3, 48000, ChannelLayout.STEREO);
        }

        @Test
        @DisplayName("gathers three frames of two blocks into each burst, and reads back each frame at 512 samples")
        void twoBlocks() {
            var units = sixBlockFrames().stream().limit(10).map(packet -> blocks(packet.data(), 1)).toList();
            var wav = mux(track(), packets(units, 512), WavMuxerOptions.forIec61937());
            var data = WavMuxerCodedTest.chunks(wav).get("data");
            var expected = new ArrayList<byte[]>();
            for (var first = 0; first < units.size(); first += 3) {
                var group = concatenate(units.subList(first, Math.min(units.size(), first + 3)));
                expected.add(burst(21, group.length, group, 24576));
            }
            assertArrayEquals(concatenate(expected), data);
            var output = WavMuxerCodedTest.demuxWav(wav);
            assertEquals(units.size(), output.packets().size());
            for (var index = 0; index < units.size(); index++) {
                assertArrayEquals(units.get(index), output.packets().get(index).data(), "frame " + index);
                assertEquals(512L * index, output.packets().get(index).pts(), "timestamp " + index);
            }
        }

        @Test
        @DisplayName("ends a group early when the next access unit would carry it past six blocks")
        void overflow() {
            var frames = sixBlockFrames();
            var two = blocks(frames.get(0).data(), 1);
            var three = blocks(frames.get(1).data(), 2);
            var six = frames.get(2).data();
            var units = List.of(two, two, three, six, three, three);
            var data = WavMuxerCodedTest.chunks(mux(track(), packets(units, 512), WavMuxerOptions.forIec61937())).get("data");
            var expected = concatenate(List.of(burst(21, 2 * two.length, concatenate(List.of(two, two)), 24576),
                    burst(21, three.length, three, 24576), burst(21, six.length, six, 24576),
                    burst(21, 2 * three.length, concatenate(List.of(three, three)), 24576)));
            assertArrayEquals(expected, data);
        }

        @Test
        @DisplayName("keeps a dependent substream frame in the burst of the independent frame it follows")
        void dependentSubstream() {
            var frames = sixBlockFrames().stream().limit(6).map(Packet::data).toList();
            var units = frames.stream().map(frame -> concatenate(List.of(frame, dependent(frame)))).toList();
            var wav = mux(track(), packets(units, 1536), WavMuxerOptions.forIec61937());
            var data = WavMuxerCodedTest.chunks(wav).get("data");
            assertArrayEquals(concatenate(units.stream().map(unit -> burst(21, unit.length, unit, 24576)).toList()), data);
            var output = WavMuxerCodedTest.demuxWav(wav);
            assertEquals(units.size(), output.packets().size());
            for (var index = 0; index < units.size(); index++) {
                assertArrayEquals(units.get(index), output.packets().get(index).data(), "access unit " + index);
            }
        }
    }

    @Nested
    @DisplayName("TrueHD MAT frames")
    class Mat {
        static final int START = 20;
        static final int MIDDLE = 30708;
        static final int END = 61408;

        static List<byte[]> units(byte[] raw) {
            var units = new ArrayList<byte[]>();
            for (var offset = 0; offset + 4 <= raw.length; ) {
                var size = (((raw[offset] & 0x0F) << 8) | (raw[offset + 1] & 0xFF)) * 2;
                units.add(Arrays.copyOfRange(raw, offset, offset + size));
                offset += size;
            }
            return units;
        }

        /** Returns a non-restart access unit of a length, with random data after its header. */
        static byte[] unit(int length, SplittableRandom random) {
            var unit = new byte[length];
            random.nextBytes(unit);
            unit[0] = (byte) (0xA0 | (length / 2) >> 8);
            unit[1] = (byte) (length / 2);
            // not a major sync
            unit[4] = 0x12;
            return unit;
        }

        /** Returns the TrueHD data of MAT bursts with the codes removed, and checks each burst's fixed fields. */
        static byte[] trueHdData(byte[] data) {
            var out = new ByteArrayOutputStream();
            for (var offset = 0; offset < data.length; offset += 61440) {
                assertEquals(0xF872, word(data, offset));
                assertEquals(22, word(data, offset + 4) & 0x7F);
                assertEquals(61424, word(data, offset + 6));
                var payload = words(Arrays.copyOfRange(data, offset + 8, offset + 61440));
                assertEquals("079e000384010101800056a53bf48183498077e0", HexFormat.of().formatHex(payload, 0, START));
                assertEquals("c3c142493bfa8283498077e0", HexFormat.of().formatHex(payload, MIDDLE, MIDDLE + 12));
                assertEquals("c3c2c0c4000000000000000000009711", HexFormat.of().formatHex(payload, END, END + 16));
                out.write(payload, START, MIDDLE - START);
                out.write(payload, MIDDLE + 12, END - MIDDLE - 12);
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("lets access units longer than their slots push the next ones back across the codes and frames")
        void longUnits() {
            var first = units(Fixtures.read(encoded("thd", 48000, "stereo", 0.3, "-strict", "-2", "-c:a", "truehd").get()))
                    .getFirst();
            var random = new SplittableRandom(11);
            var units = new ArrayList<byte[]>();
            units.add(first);
            for (var index = 1; index < 80; index++) {
                units.add(unit(index % 9 == 0 ? 8000 : 2400 + 2 * random.nextInt(600), random));
            }
            var wav = mux(track(Codec.TRUEHD, 48000, ChannelLayout.STEREO), packets(units, 40), WavMuxerOptions.forIec61937());
            var data = WavMuxerCodedTest.chunks(wav).get("data");
            assertEquals(0, data.length % 61440);
            // the units follow each other, separated only by zero padding, in the data between the codes
            var stream = trueHdData(data);
            var position = 0;
            for (var index = 0; index < units.size(); index++) {
                while (stream[position] == 0 && stream[position + 1] == 0) {
                    position += 2;
                }
                assertArrayEquals(units.get(index), Arrays.copyOfRange(stream, position, position + units.get(index).length),
                        "unit " + index);
                position += units.get(index).length;
            }
            for (var index = position; index < stream.length; index++) {
                assertEquals(0, stream[index], "padding after the last unit");
            }
            var total = units.stream().mapToInt(unit -> unit.length).sum();
            assertTrue(data.length / 61440 >= Math.ceil(total / (61424.0 - 48)), "frames for every byte");
            var output = WavMuxerCodedTest.demuxWav(wav);
            assertEquals(units.size(), output.packets().size());
            for (var index = 0; index < units.size(); index++) {
                assertArrayEquals(units.get(index), output.packets().get(index).data(), "unit " + index);
                assertEquals(40L * index, output.packets().get(index).pts());
            }
        }

        @Test
        @DisplayName("places each access unit at its 2560-byte slot, after the start code and the middle code")
        void slots() {
            var units = units(Fixtures.read(encoded("thd", 48000, "5.1", 0.6, "-strict", "-2", "-c:a", "truehd").get()))
                    .subList(0, 30);
            var data = WavMuxerCodedTest.chunks(mux(track(Codec.TRUEHD, 48000, ChannelLayout.SURROUND_5_1),
                    packets(units, 40), WavMuxerOptions.forIec61937())).get("data");
            assertEquals(2 * 61440, data.length, "a full frame and the padded last one");
            for (var index = 0; index < units.size(); index++) {
                var frame = index / 24;
                var slot = index % 24;
                var payload = words(Arrays.copyOfRange(data, frame * 61440 + 8, (frame + 1) * 61440));
                var at = Math.max(START, slot * 2560);
                assertArrayEquals(units.get(index), Arrays.copyOfRange(payload, at, at + units.get(index).length),
                        "unit " + index);
            }
        }
    }

    @Nested
    @DisplayName("burst fields")
    class Fields {
        @Test
        @DisplayName("sets the error flag of the burst of a corrupt packet, which WavDemuxer flags corrupt again")
        void errorFlag() {
            var input = WavMuxerCodedTest.demux(Fixtures.read(sources().findFirst().orElseThrow().file().get()));
            var packets = new ArrayList<>(input.packets());
            packets.set(3, packets.get(3).withFlags(Set.of(PacketFlag.KEY, PacketFlag.CORRUPT)));
            var wav = mux(input.track(), packets, WavMuxerOptions.forIec61937());
            var data = WavMuxerCodedTest.chunks(wav).get("data");
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index == 3 ? 0x81 : 0x01, word(data, 6144 * index + 4), "Pc of burst " + index);
            }
            var output = WavMuxerCodedTest.demuxWav(wav).packets();
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index == 3, output.get(index).flags().contains(PacketFlag.CORRUPT), "packet " + index);
            }
        }

        @Test
        @DisplayName("writes one burst per frame of a packet that holds several, with the bitstream's odd byte padded")
        void severalFrames() {
            var input = WavMuxerCodedTest.demux(Fixtures.read(sources().skip(4).findFirst().orElseThrow().file().get()));
            assertEquals(Codec.MP2, input.track().codec());
            var frames = input.packets().stream().limit(6).map(Packet::data).toList();
            var packets = List.of(new Packet(0, concatenate(frames.subList(0, 3)), 0, 0, 3456, true),
                    new Packet(0, concatenate(frames.subList(3, 6)), 3456, 3456, 3456, true));
            var data = WavMuxerCodedTest.chunks(mux(input.track(), packets, WavMuxerOptions.forIec61937())).get("data");
            assertArrayEquals(concatenate(frames.stream().map(frame -> burst(5, 8 * frame.length, frame, 4608)).toList()),
                    data);
            assertTrue(frames.stream().anyMatch(frame -> frame.length % 2 == 1), "44.1 kHz frames of odd sizes");
        }

        @Test
        @DisplayName("reads 8-channel PCM without bursts as PCM and rejects declared bursts of other channel counts")
        void highBitRateDetection() {
            var noise = new byte[16 * 48000];
            new SplittableRandom(23).nextBytes(noise);
            var pcm = mux(track(Codec.PCM_S16LE, 192000, ChannelLayout.SURROUND_7_1),
                    List.of(new Packet(0, noise, 0, 0, 48000, true)), WavMuxerOptions.defaults());
            assertEquals(Codec.PCM_S16LE, WavMuxerCodedTest.demuxWav(pcm).track().codec());
            var frames = DtsHd.frames();
            var wav = mux(track(Codec.DTS, 48000, ChannelLayout.STEREO), packets(frames, 512),
                    options(WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE));
            var fmt = WavMuxerCodedTest.chunks(wav).get("fmt ");
            var at = 12;
            while (!new String(wav, at, 4, StandardCharsets.US_ASCII).equals("fmt ")) {
                at += 8 + WavMuxerCodedTest.le(wav).getInt(at + 4);
            }
            at += 8;
            for (var channels : new int[]{6, 4}) {
                var copy = wav.clone();
                ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putShort(at + 2, (short) channels)
                        .putShort(at + 12, (short) (2 * channels));
                assertArrayEquals(fmt, Arrays.copyOfRange(wav, at, at + fmt.length));
                assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                        () -> new WavDemuxer(MediaSource.of(copy)), channels + " channels");
            }
        }

        @Test
        @DisplayName("writes PCM as it is under the IEC 61937 options")
        void pcm() {
            var track = track(Codec.PCM_S16LE, 44100, ChannelLayout.STEREO);
            var samples = new byte[4 * 1000];
            new SplittableRandom(3).nextBytes(samples);
            var packets = List.of(new Packet(0, samples, 0, 0, 1000, true));
            assertArrayEquals(mux(track, packets, WavMuxerOptions.defaults()), mux(track, packets, WavMuxerOptions.forIec61937()));
        }
    }

    @Nested
    @DisplayName("options and rejections")
    class Rejections {
        @Test
        @DisplayName("names the codecs each encapsulation carries")
        void supports() {
            var native_ = WavMuxerOptions.defaults();
            var iec = WavMuxerOptions.forIec61937();
            for (var codec : List.of(Codec.AC3, Codec.EAC3, Codec.MP2, Codec.MP3, Codec.AAC, Codec.DTS, Codec.TRUEHD,
                    Codec.PCM_S16LE, Codec.PCM_F32BE)) {
                assertTrue(WavMuxer.supports(codec, iec), codec.toString());
            }
            for (var codec : List.of(Codec.OPUS, Codec.FLAC, Codec.AMR_NB, Codec.AMR_WB, Codec.VORBIS)) {
                assertFalse(WavMuxer.supports(codec, iec), codec.toString());
            }
            assertFalse(WavMuxer.supports(Codec.TRUEHD, native_));
            assertFalse(WavMuxer.supports(Codec.TRUEHD));
            assertTrue(WavMuxer.supports(Codec.OPUS, native_));
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory()).addTrack(
                    track(Codec.TRUEHD, 48000, ChannelLayout.STEREO)));
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory(), iec).addTrack(
                    track(Codec.OPUS, 48000, ChannelLayout.STEREO)));
        }

        @Test
        @DisplayName("keeps the encapsulation through the builder, equality and toBuilder")
        void builder() {
            var built = WavMuxerOptions.builder().encapsulation(WavMuxerOptions.Encapsulation.IEC61937_PCM).build();
            assertEquals(WavMuxerOptions.forIec61937(), built);
            assertEquals(WavMuxerOptions.forIec61937().hashCode(), built.hashCode());
            assertEquals(WavMuxerOptions.Encapsulation.NATIVE, WavMuxerOptions.defaults().encapsulation());
            assertEquals(WavMuxerOptions.Encapsulation.IEC61937_PCM, WavMuxerOptions.forIec61937().encapsulation());
            var extensible = built.toBuilder().encapsulation(WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE)
                    .rf64Policy(WavMuxerOptions.Rf64.NEVER).build();
            assertEquals(WavMuxerOptions.Rf64.NEVER, extensible.rf64Policy());
            assertNotEquals(built, extensible);
            assertTrue(extensible.toString().contains("IEC61937_EXTENSIBLE"));
            assertThrows(NullPointerException.class, () -> WavMuxerOptions.builder().encapsulation(null));
        }

        @Test
        @DisplayName("rejects tracks whose configuration or rate IEC 61937 cannot carry")
        void tracks() {
            var iec = WavMuxerOptions.forIec61937();
            // AAC LC 48 kHz stereo with an explicit sampling rate (index 15), and with 960-sample frames
            for (var config : List.of(new byte[]{0x17, (byte) 0x80, 0x5D, (byte) 0xC0, 0x10}, new byte[]{0x11, (byte) 0x94})) {
                var aac = track(Codec.AAC, 48000, ChannelLayout.STEREO).toBuilder().codecConfig(config).build();
                assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory(), iec).addTrack(aac));
            }
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory(), iec).addTrack(
                    track(Codec.AAC, 48000, ChannelLayout.STEREO)));
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory(), iec).addTrack(
                    track(Codec.MP3, 8000, ChannelLayout.MONO)));
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory(), iec).addTrack(
                    track(Codec.TRUEHD, 32000, ChannelLayout.STEREO)));
            assertDoesNotThrow(() -> new WavMuxer(MediaSink.memory(), iec).addTrack(track(Codec.AAC, 48000,
                    ChannelLayout.STEREO).toBuilder().codecConfig(new byte[]{0x11, (byte) 0x90}).build()));
        }

        static void assertRejected(AudioTrack track, byte[] packet) {
            var muxer = new WavMuxer(MediaSink.memory(), WavMuxerOptions.forIec61937());
            muxer.addTrack(track);
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, packet, 0, 0, 0, true)));
        }

        @Test
        @DisplayName("rejects packets that are not whole frames of the codec or do not fit a burst")
        void packets() {
            var ac3 = WavMuxerCodedTest.demux(Fixtures.read(sources().findFirst().orElseThrow().file().get()));
            var frame = ac3.packets().getFirst().data();
            assertRejected(ac3.track(), Arrays.copyOf(frame, frame.length - 2));
            assertRejected(ac3.track(), new byte[100]);
            var eac3 = Eac3Groups.sixBlockFrames().getFirst().data();
            assertRejected(ac3.track(), eac3);
            assertRejected(Eac3Groups.track(), frame);
            var mp2 = WavMuxerCodedTest.demux(Fixtures.read(sources().skip(3).findFirst().orElseThrow().file().get()));
            var mpeg = mp2.packets().getFirst().data();
            assertRejected(mp2.track(), Arrays.copyOf(mpeg, 100));
            var freeFormat = mpeg.clone();
            freeFormat[2] &= 0x0F;
            assertRejected(mp2.track(), freeFormat);
            // the frame of a 48 kHz stream in a 44.1 kHz track
            assertRejected(mp2.track().toBuilder().sampleRate(44100).build(), mpeg);
            var aac = track(Codec.AAC, 48000, ChannelLayout.STEREO).toBuilder().codecConfig(new byte[]{0x11, (byte) 0x90})
                    .build();
            assertRejected(aac, new byte[4090]);
            var dts = dtsFrames(Fixtures.read(encoded("dts", 48000, "stereo", 0.05, "-strict", "-2", "-c:a", "dca").get()));
            var dtsTrack = track(Codec.DTS, 48000, ChannelLayout.STEREO);
            assertRejected(dtsTrack, Arrays.copyOf(dts.getFirst(), 500));
            // NBLKS 7: 256-sample frames, which no data type carries
            var short_ = dts.getFirst().clone();
            short_[4] = (byte) (short_[4] & 0xFE);
            short_[5] = (byte) (short_[5] & 0x03 | 0x1C);
            assertRejected(dtsTrack, short_);
            var muxer = new WavMuxer(MediaSink.memory(), WavMuxerOptions.forIec61937());
            muxer.addTrack(dtsTrack);
            muxer.writePacket(new Packet(0, dts.getFirst(), 0, 0, 512, true));
            var longer = dts.get(1).clone();
            longer[4] = (byte) (longer[4] | 0x01);
            longer[5] = (byte) (longer[5] | 0xFC);
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, longer, 512, 512, 512, true)));
            var unit = Mat.unit(400, new SplittableRandom(1));
            unit[1] += 1;
            assertRejected(track(Codec.TRUEHD, 48000, ChannelLayout.STEREO), unit);
        }

        @Test
        @DisplayName("leaves the stream as it was after a rejected packet")
        void rejectedPacketsChangeNothing() {
            var ac3 = WavMuxerCodedTest.demux(Fixtures.read(sources().findFirst().orElseThrow().file().get()));
            var frame = ac3.packets().getFirst().data();
            var units = Eac3Groups.sixBlockFrames().stream().limit(9).map(packet -> Eac3Groups.blocks(packet.data(), 1))
                    .toList();
            var cases = List.of(
                    List.<Object>of(ac3.track(), ac3.packets().subList(0, 6), concatenate(List.of(frame, new byte[50]))),
                    List.<Object>of(Eac3Groups.track(), WavMuxerIec61937Test.packets(units, 512), frame),
                    List.<Object>of(Eac3Groups.track(), WavMuxerIec61937Test.packets(units, 512), new byte[24576]));
            for (var test : cases) {
                var track = (AudioTrack) test.get(0);
                @SuppressWarnings("unchecked")
                var packets = (List<Packet>) test.get(1);
                var bad = (byte[]) test.get(2);
                var expected = mux(track, packets, options(WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE));
                var sink = MediaSink.memory();
                try (var muxer = new WavMuxer(sink, options(WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE))) {
                    muxer.addTrack(track);
                    for (var index = 0; index < packets.size(); index++) {
                        muxer.writePacket(packets.get(index));
                        if (index == 1) {
                            assertThrows(IllegalArgumentException.class,
                                    () -> muxer.writePacket(new Packet(0, bad, 0, 0, 0, true)));
                        }
                    }
                    muxer.finish();
                }
                assertArrayEquals(expected, sink.toByteArray(), track.codec().toString());
            }
        }

        @Test
        @DisplayName("throws only IllegalArgumentException for random packets of every codec")
        void randomPackets() {
            var random = new SplittableRandom(19);
            var aacConfig = new byte[]{0x11, (byte) 0x90};
            var tracks = List.of(track(Codec.AC3, 48000, ChannelLayout.STEREO), track(Codec.EAC3, 48000, ChannelLayout.STEREO),
                    track(Codec.MP2, 48000, ChannelLayout.STEREO), track(Codec.MP3, 24000, ChannelLayout.STEREO),
                    track(Codec.AAC, 48000, ChannelLayout.STEREO).toBuilder().codecConfig(aacConfig).build(),
                    track(Codec.DTS, 48000, ChannelLayout.STEREO), track(Codec.TRUEHD, 48000, ChannelLayout.STEREO));
            var syncs = List.of(new byte[]{0x0B, 0x77}, new byte[]{0x0B, 0x77}, new byte[]{(byte) 0xFF, (byte) 0xFD},
                    new byte[]{(byte) 0xFF, (byte) 0xF3}, new byte[0], new byte[]{0x7F, (byte) 0xFE, (byte) 0x80, 0x01},
                    new byte[0]);
            for (var index = 0; index < tracks.size(); index++) {
                var track = tracks.get(index);
                for (var form : List.of(WavMuxerOptions.Encapsulation.IEC61937_PCM,
                        WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE)) {
                    var muxer = new WavMuxer(MediaSink.memory(), options(form));
                    muxer.addTrack(track);
                    for (var attempt = 0; attempt < 300; attempt++) {
                        var data = new byte[1 + random.nextInt(3000)];
                        random.nextBytes(data);
                        var sync = syncs.get(index);
                        System.arraycopy(sync, 0, data, 0, Math.min(sync.length, data.length));
                        try {
                            muxer.writePacket(new Packet(0, data, 0, 0, 0, true));
                        } catch (IllegalArgumentException expected) {
                            // rejected packets leave the muxer usable
                        }
                    }
                    muxer.finish();
                }
            }
        }
    }
}
