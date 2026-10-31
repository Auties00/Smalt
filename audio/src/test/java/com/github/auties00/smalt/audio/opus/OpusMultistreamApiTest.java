package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.opus.internal.OpusPacket;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the multichannel surface of the Opus encoder: the options that choose a channel mapping,
 * the identification header, the routing of every input channel to the decoded channel of the
 * same speaker (through Smalt's decoder, through the Ogg, WebM, Matroska and MP4 muxers and the
 * transcoder, and through ffmpeg as a black-box decoder), and the per-frame and buffered paths.
 *
 * <p>Channel routing is checked with tones: every input channel carries a sine of its own
 * frequency, and each decoded channel must hold far more energy at the frequency of the input
 * channel feeding the same speaker than at any other channel's.
 */
class OpusMultistreamApiTest {
    private static final ChannelLayout SURROUND_5_1_SIDE = new ChannelLayout(List.of(Speaker.FRONT_LEFT,
            Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));

    private static final ChannelLayout UNKNOWN_6 = new ChannelLayout(Collections.nCopies(6, Speaker.UNKNOWN));

    /** Returns the tone frequency of an input channel; the LFE gets a low tone. */
    static double tone(ChannelLayout layout, int channel) {
        return layout.speakers().get(channel) == Speaker.LOW_FREQUENCY ? 70 : 440 + 330 * channel;
    }

    /** Builds two seconds of a distinct tone per channel. */
    static float[][] tones(ChannelLayout layout, int rate, int frames) {
        var planes = new float[layout.channels()][frames];
        for (var c = 0; c < planes.length; c++) {
            var f = tone(layout, c);
            for (var i = 0; i < frames; i++) {
                planes[c][i] = (float) (0.25 * Math.sin(2 * Math.PI * f * i / rate));
            }
        }
        return planes;
    }

    /** Returns the power of a plane at a frequency over its middle half (Goertzel). */
    static double power(float[] plane, double frequency, int rate) {
        var start = plane.length / 4;
        var end = 3 * plane.length / 4;
        var w = 2 * Math.PI * frequency / rate;
        var coefficient = 2 * Math.cos(w);
        double s1 = 0;
        double s2 = 0;
        for (var i = start; i < end; i++) {
            var s = plane[i] + coefficient * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return (s1 * s1 + s2 * s2 - coefficient * s1 * s2) / (end - start);
    }

    /**
     * Asserts that each decoded plane carries the tone of the input channel feeding the same
     * speaker, at least 20 dB above every other tone.
     */
    static void assertRouted(ChannelLayout input, ChannelLayout decoded, float[][] planes, int rate) {
        assertEquals(input.channels(), decoded.channels());
        for (var d = 0; d < decoded.channels(); d++) {
            var speaker = decoded.speakers().get(d);
            var source = input.indexOf(speaker);
            if (source < 0) {
                source = input.indexOf(switch (speaker) {
                    case BACK_LEFT -> Speaker.SIDE_LEFT;
                    case BACK_RIGHT -> Speaker.SIDE_RIGHT;
                    default -> speaker;
                });
            }
            if (speaker == Speaker.UNKNOWN || decoded.ambisonics().isPresent()) {
                source = d;
            }
            assertTrue(source >= 0, "no input channel for " + speaker);
            var own = power(planes[d], tone(input, source), rate);
            assertTrue(own > 1e-4, "decoded " + speaker + " is silent: " + own);
            for (var other = 0; other < input.channels(); other++) {
                if (other != source) {
                    var leak = power(planes[d], tone(input, other), rate);
                    assertTrue(own > 100 * leak, "decoded " + speaker + " leaks the tone of input channel " + other
                            + ": " + own + " vs " + leak);
                }
            }
        }
    }

    /** Encodes planar input through the buffering path. */
    static List<Packet> encode(OpusEncoder encoder, float[][] planes) {
        var packets = new ArrayList<Packet>();
        var frames = planes[0].length;
        var random = new Random(frames);
        var position = 0;
        while (position < frames) {
            var count = Math.min(frames - position, 1 + random.nextInt(4000));
            var chunk = new float[planes.length][];
            for (var c = 0; c < planes.length; c++) {
                chunk[c] = Arrays.copyOfRange(planes[c], position, position + count);
            }
            encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), chunk, count), packets::add);
            position += count;
        }
        encoder.flush(packets::add);
        return packets;
    }

    static AudioTrack track(byte[] head, int rate) {
        return AudioTrack.builder().codec(Codec.OPUS)
                .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(48000))
                .codecConfig(head).sampleRate(rate).build();
    }

    /** Decodes packets with Smalt's decoder into planes in the decoder's layout. */
    static float[][] decode(OpusDecoder decoder, Iterable<Packet> packets) {
        var channels = decoder.layout().channels();
        var collected = new ArrayList<float[][]>();
        var total = new int[1];
        java.util.function.Consumer<AudioBuffer> sink = buffer -> {
            var copy = new float[channels][];
            for (var c = 0; c < channels; c++) {
                copy[c] = Arrays.copyOf(buffer.plane(c), buffer.frames());
            }
            collected.add(copy);
            total[0] += buffer.frames();
        };
        for (var packet : packets) {
            decoder.decode(packet, sink);
        }
        decoder.flush(sink);
        var out = new float[channels][total[0]];
        var position = 0;
        for (var block : collected) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(block[c], 0, out[c], position, block[c].length);
            }
            position += block[0].length;
        }
        return out;
    }

    /** Splits a multistream packet into its stream packets, checking the self-delimited framing. */
    private static List<byte[]> streams(byte[] packet, int count) {
        var out = new ArrayList<byte[]>();
        var layout = new OpusPacket.Layout();
        var offset = 0;
        for (var s = 0; s < count; s++) {
            var selfDelimited = s != count - 1;
            var frames = OpusPacket.parseImpl(packet, offset, packet.length - offset, selfDelimited, layout);
            assertTrue(frames > 0, "stream " + s + " does not parse: " + frames);
            out.add(Arrays.copyOfRange(packet, offset, offset + layout.packetOffset));
            offset += layout.packetOffset;
        }
        assertEquals(packet.length, offset, "the streams fill the packet");
        return out;
    }

    @Nested
    @DisplayName("OpusEncoderOptions")
    class Options {
        @Test
        @DisplayName("chooses the Vorbis mapping for surround layouts in any order and discrete channels otherwise")
        void automaticMapping() {
            for (var channels = 1; channels <= 8; channels++) {
                var options = OpusEncoderOptions.builder().format(48000, channels).build();
                assertEquals(ChannelLayout.defaultFor(channels), options.layout());
                assertEquals(OpusChannelMapping.VORBIS, options.channelMapping(), channels + " channels");
            }
            var reversed = new ArrayList<>(ChannelLayout.SURROUND_7_1.speakers());
            Collections.reverse(reversed);
            assertEquals(OpusChannelMapping.VORBIS, OpusEncoderOptions.builder()
                    .format(48000, new ChannelLayout(reversed)).build().channelMapping());
            assertEquals(OpusChannelMapping.VORBIS, OpusEncoderOptions.builder()
                    .format(48000, SURROUND_5_1_SIDE).build().channelMapping());
            assertEquals(OpusChannelMapping.DISCRETE, OpusEncoderOptions.builder()
                    .format(48000, UNKNOWN_6).build().channelMapping());
            assertEquals(OpusChannelMapping.DISCRETE, OpusEncoderOptions.builder().format(48000, 9).build().channelMapping());
            assertEquals(OpusChannelMapping.DISCRETE, OpusEncoderOptions.builder().format(48000, 255).build().channelMapping());
            // 7.1 with side speakers but no backs is not a Vorbis layout: only 4 to 6 channels may swap.
            var sevenSides = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.TOP_CENTER, Speaker.BACK_CENTER));
            assertEquals(OpusChannelMapping.DISCRETE, OpusEncoderOptions.builder()
                    .format(48000, sevenSides).build().channelMapping());
        }

        @Test
        @DisplayName("rejects a mapping that cannot code the layout")
        void rejectsMappings() {
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder().format(48000, UNKNOWN_6)
                    .channelMapping(OpusChannelMapping.VORBIS).build());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder().format(48000, 9)
                    .channelMapping(OpusChannelMapping.VORBIS).build());
            for (var bad : new int[]{2, 5, 7, 8, 10, 12, 228}) {
                assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder().format(48000, bad)
                        .channelMapping(OpusChannelMapping.AMBISONIC).build(), bad + " channels");
            }
            for (var good : new int[]{1, 3, 4, 6, 9, 11, 16, 18, 225, 227}) {
                assertEquals(OpusChannelMapping.AMBISONIC, OpusEncoderOptions.builder().format(48000, good)
                        .channelMapping(OpusChannelMapping.AMBISONIC).build().channelMapping());
            }
            assertThrows(NullPointerException.class, () -> OpusEncoderOptions.builder().channelMapping(null));
        }

        @Test
        @DisplayName("keeps the mapping through toBuilder and resets it with automaticChannelMapping")
        void builderRoundTrip() {
            var discrete = OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                    .channelMapping(OpusChannelMapping.DISCRETE).build();
            assertEquals(OpusChannelMapping.DISCRETE, discrete.channelMapping());
            assertEquals(discrete, discrete.toBuilder().build());
            assertEquals(discrete.hashCode(), discrete.toBuilder().build().hashCode());
            var automatic = discrete.toBuilder().automaticChannelMapping().build();
            assertEquals(OpusChannelMapping.VORBIS, automatic.channelMapping());
            assertNotEquals(discrete, automatic);
            // An explicit mapping equal to the automatic choice still differs from leaving it automatic.
            assertNotEquals(automatic, automatic.toBuilder().channelMapping(OpusChannelMapping.VORBIS).build());
            assertEquals(ChannelLayout.SURROUND_5_1, automatic.layout());
            assertEquals(6, automatic.channels());
            assertTrue(automatic.toString().contains("channelMapping=auto"));
        }

        @Test
        @DisplayName("scales the bitrate limit with the channel count")
        void bitrateLimit() {
            assertEquals(1_800_000, OpusEncoderOptions.builder().format(48000, 6).bitrate(1_800_000).build()
                    .bitrate().getAsInt());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder().format(48000, 6)
                    .bitrate(1_800_001).build());
        }
    }

    @Nested
    @DisplayName("OpusEncoder")
    class Encoder {
        @ParameterizedTest(name = "{0} channels, {1}")
        @CsvSource({
                "3, VORBIS, 1, 2, 1, '0,2,1'",
                "4, VORBIS, 1, 2, 2, '0,1,2,3'",
                "5, VORBIS, 1, 3, 2, '0,4,1,2,3'",
                "6, VORBIS, 1, 4, 2, '0,4,1,2,3,5'",
                "7, VORBIS, 1, 4, 3, '0,4,1,2,3,5,6'",
                "8, VORBIS, 1, 5, 3, '0,6,1,2,3,4,5,7'",
                "1, DISCRETE, 255, 1, 0, '0'",
                "2, DISCRETE, 255, 2, 0, '0,1'",
                "6, DISCRETE, 255, 6, 0, '0,1,2,3,4,5'",
                "4, AMBISONIC, 2, 4, 0, '0,1,2,3'",
                "6, AMBISONIC, 2, 5, 1, '2,3,4,5,0,1'",
        })
        @DisplayName("declares the stream counts and the mapping table in the OpusHead")
        void head(int channels, OpusChannelMapping mapping, int family, int streams, int coupled, String table) {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, channels)
                    .channelMapping(mapping).build());
            var head = OpusHead.parse(encoder.codecConfig().orElseThrow(), MediaLimits.DEFAULT);
            assertEquals(1, head.version());
            assertEquals(channels, head.channels());
            assertEquals(312, head.preSkip());
            assertEquals(48000, head.inputSampleRate());
            assertEquals(0, head.outputGain());
            assertEquals(family, head.mappingFamily());
            assertEquals(streams, head.streamCount());
            assertEquals(coupled, head.coupledCount());
            assertArrayEquals(Arrays.stream(table.split(",")).mapToInt(Integer::parseInt).toArray(), head.mapping());
            assertEquals(streams, encoder.streamCount());
            assertEquals(21 + channels, encoder.codecConfig().orElseThrow().length);
        }

        @Test
        @DisplayName("keeps one elementary stream and no mapping table for mono and stereo")
        void singleStream() {
            var encoder = new OpusEncoder(OpusEncoderOptions.defaults());
            assertEquals(1, encoder.streamCount());
            assertEquals(19, encoder.codecConfig().orElseThrow().length);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"5.1", "5.1 side", "7.1", "7.1 reversed", "6.1", "quad", "3.0", "5.0"})
        @DisplayName("routes every input channel to the decoded channel of its speaker")
        void routing(String name) {
            var layout = switch (name) {
                case "5.1" -> ChannelLayout.SURROUND_5_1;
                case "5.1 side" -> SURROUND_5_1_SIDE;
                case "7.1" -> ChannelLayout.SURROUND_7_1;
                case "7.1 reversed" -> {
                    var speakers = new ArrayList<>(ChannelLayout.SURROUND_7_1.speakers());
                    Collections.reverse(speakers);
                    yield new ChannelLayout(speakers);
                }
                case "6.1" -> ChannelLayout.defaultFor(7);
                case "quad" -> ChannelLayout.defaultFor(4);
                case "3.0" -> ChannelLayout.defaultFor(3);
                default -> ChannelLayout.defaultFor(5);
            };
            var options = OpusEncoderOptions.builder().format(48000, layout).bitrate(64000 * layout.channels()).build();
            var encoder = new OpusEncoder(options);
            assertEquals(layout, encoder.layout());
            var input = tones(layout, 48000, 96000);
            var packets = encode(encoder, input);
            var decoder = new OpusDecoder(track(encoder.codecConfig().orElseThrow(), 48000));
            assertEquals(ChannelLayout.defaultFor(layout.channels()), decoder.layout());
            var decoded = decode(decoder, packets);
            assertRouted(layout, decoder.layout(), decoded, 48000);
        }

        @Test
        @DisplayName("codes the LFE as a narrowband CELT stream and the pairs as stereo CELT")
        void lfeStream() {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                    .bitrate(320000).build());
            var packets = encode(encoder, tones(ChannelLayout.SURROUND_5_1, 48000, 48000));
            for (var packet : packets) {
                var streams = streams(packet.data(), 4);
                for (var s = 0; s < 2; s++) {
                    var toc = streams.get(s)[0] & 0xFF;
                    assertEquals(OpusPacket.getMode(toc), com.github.auties00.smalt.audio.opus.internal.OpusDefines.MODE_CELT_ONLY);
                    assertEquals(2, OpusPacket.getNbChannels(toc), "coupled stream " + s + " is stereo");
                    assertEquals(OpusBandwidth.FULLBAND, OpusPackets.bandwidth(streams.get(s), 0));
                }
                var lfe = streams.get(3)[0] & 0xFF;
                assertEquals(com.github.auties00.smalt.audio.opus.internal.OpusDefines.MODE_CELT_ONLY, OpusPacket.getMode(lfe));
                assertEquals(OpusBandwidth.NARROWBAND, OpusPackets.bandwidth(streams.get(3), 0));
                assertEquals(1, OpusPacket.getNbChannels(lfe));
            }
        }

        @Test
        @DisplayName("codes surround at the full bandwidth at the automatic bitrate")
        void automaticBitrateBandwidth() {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1).build());
            var packets = encode(encoder, tones(ChannelLayout.SURROUND_5_1, 48000, 48000));
            for (var packet : packets) {
                var streams = streams(packet.data(), 4);
                for (var s = 0; s < 3; s++) {
                    assertEquals(OpusBandwidth.FULLBAND, OpusPackets.bandwidth(streams.get(s), 0), "stream " + s);
                }
            }
            assertTrue(encoder.bitrate() > 250000, "automatic 5.1 bitrate " + encoder.bitrate());
        }

        @Test
        @DisplayName("the per-frame paths reorder interleaved input like the buffering path")
        void perFrame() {
            var speakers = new ArrayList<>(ChannelLayout.SURROUND_5_1.speakers());
            Collections.reverse(speakers);
            var layout = new ChannelLayout(speakers);
            var options = OpusEncoderOptions.builder().format(48000, layout).bitrate(256000).build();
            var input = tones(layout, 48000, 960 * 30);
            var buffered = encode(new OpusEncoder(options), input);
            var floats = new OpusEncoder(options);
            var frame = new float[960 * 6];
            var packet = new byte[6000];
            for (var k = 0; k < 30; k++) {
                for (var i = 0; i < 960; i++) {
                    for (var c = 0; c < 6; c++) {
                        frame[i * 6 + c] = input[c][k * 960 + i];
                    }
                }
                var length = floats.encode(frame, 0, 960, packet, 0, packet.length);
                assertArrayEquals(buffered.get(k).data(), Arrays.copyOf(packet, length), "float packet " + k);
            }
        }

        @Test
        @DisplayName("16-bit frames equal float frames of the same samples at a 16-bit depth")
        void shortEqualsFloatAt16Bits() {
            var options = OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_7_1).bitrate(256000).build();
            var floats = new OpusEncoder(options);
            floats.setLsbDepth(16);
            var shorts = new OpusEncoder(options);
            var random = new Random(7);
            var a = new byte[8000];
            var b = new byte[8000];
            for (var k = 0; k < 20; k++) {
                var frame16 = new short[960 * 8];
                var frame = new float[960 * 8];
                for (var i = 0; i < frame16.length; i++) {
                    frame16[i] = (short) (random.nextGaussian() * 4000 * Math.sin(i / 700.0));
                    frame[i] = frame16[i] / 32768f;
                }
                var la = shorts.encode(frame16, 0, 960, a, 0, a.length);
                var lb = floats.encode(frame, 0, 960, b, 0, b.length);
                assertArrayEquals(Arrays.copyOf(a, la), Arrays.copyOf(b, lb), "packet " + k);
                assertEquals(shorts.finalRange(), floats.finalRange());
            }
        }

        @Test
        @DisplayName("reset() restarts the coding state of every stream")
        void reset() {
            var options = OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1).bitrate(192000).build();
            var input = tones(ChannelLayout.SURROUND_5_1, 48000, 960 * 10);
            var fresh = encode(new OpusEncoder(options), input);
            var reused = new OpusEncoder(options);
            var warm = new ArrayList<Packet>();
            reused.encode(new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, tones(ChannelLayout.SURROUND_5_1, 48000,
                    960 * 7), 960 * 7), warm::add);
            reused.reset();
            var again = new ArrayList<Packet>();
            reused.encode(new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, input, input[0].length), again::add);
            for (var i = 0; i < again.size(); i++) {
                assertArrayEquals(fresh.get(i).data(), again.get(i).data(), "packet " + i + " after reset");
            }
        }

        @Test
        @DisplayName("the live controls retarget every stream")
        void setters() {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                    .bitrate(384000).bitrateMode(OpusBitrateMode.CBR).build());
            var input = tones(ChannelLayout.SURROUND_5_1, 48000, 960 * 4);
            var high = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, input, input[0].length), high::add);
            encoder.setBitrate(96000);
            var low = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, input, input[0].length), low::add);
            for (var packet : high) {
                assertEquals(384000 / 8 / 50, packet.data().length, "CBR packet size at 384 kb/s");
            }
            for (var packet : low) {
                assertEquals(96000 / 8 / 50, packet.data().length, "CBR packet size at 96 kb/s");
            }
            encoder.setComplexity(3);
            assertEquals(3, encoder.complexity());
            encoder.setSignal(OpusSignal.MUSIC);
            encoder.setMaxBandwidth(OpusBandwidth.WIDEBAND);
            encoder.setBitrateMode(OpusBitrateMode.VBR);
            var limited = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, input, input[0].length), limited::add);
            for (var packet : limited) {
                for (var stream : streams(packet.data(), 4)) {
                    assertTrue(OpusPackets.bandwidth(stream, 0).compareTo(OpusBandwidth.WIDEBAND) <= 0);
                }
            }
            assertThrows(IllegalStateException.class, () -> encoder.setForcedChannels(1));
            encoder.setAutomaticChannels();
            assertThrows(IllegalArgumentException.class, () -> encoder.setComplexity(11));
            assertThrows(IllegalArgumentException.class, () -> encoder.setBitrate(0));
            encoder.setAutomaticBitrate();
            encoder.setMaximumBitrate();
            encoder.setInbandFec(true);
            encoder.setPacketLossPercent(5);
            encoder.setDtx(true);
            encoder.setPredictionDisabled(true);
            encoder.setPhaseInversionDisabled(true);
            encoder.setAutomaticBandwidth();
            encoder.setBandwidth(OpusBandwidth.SUPERWIDEBAND);
            var forced = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, input, input[0].length), forced::add);
            for (var packet : forced) {
                var streams = streams(packet.data(), 4);
                for (var s = 0; s < 3; s++) {
                    assertEquals(OpusBandwidth.SUPERWIDEBAND, OpusPackets.bandwidth(streams.get(s), 0), "stream " + s);
                }
                assertEquals(OpusBandwidth.NARROWBAND, OpusPackets.bandwidth(streams.get(3), 0), "the LFE stream");
            }
            encoder.flush(p -> {
            });
        }

        @Test
        @DisplayName("rejects a packet space smaller than one byte per stream framing")
        void smallPacket() {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1).build());
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new float[960 * 6], 0, 960, new byte[6],
                    0, 6));
            assertTrue(encoder.encode(new float[960 * 6], 0, 960, new byte[7], 0, 7) <= 7);
        }

        @Test
        @DisplayName("codes non-finite and out-of-range input as silence or clipped samples")
        void robustness() {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_7_1).build());
            var frame = new float[960 * 8];
            Arrays.fill(frame, Float.NaN);
            assertTrue(encoder.encode(frame, 0, 960, new byte[20000], 0, 20000) > 0);
            Arrays.fill(frame, Float.POSITIVE_INFINITY);
            assertTrue(encoder.encode(frame, 0, 960, new byte[20000], 0, 20000) > 0);
            Arrays.fill(frame, 1e30f);
            assertTrue(encoder.encode(frame, 0, 960, new byte[20000], 0, 20000) > 0);
            var planes = new float[8][4800];
            for (var plane : planes) {
                Arrays.fill(plane, Float.NaN);
                plane[7] = 1e9f;
            }
            var packets = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.SURROUND_7_1, 48000, planes, 4800), packets::add);
            encoder.flush(packets::add);
            var decoded = decode(new OpusDecoder(track(encoder.codecConfig().orElseThrow(), 48000)), packets);
            for (var plane : decoded) {
                for (var sample : plane) {
                    assertTrue(Float.isFinite(sample) && Math.abs(sample) < 2);
                }
            }
        }

        @ParameterizedTest(name = "{0} channels, {1} us")
        @CsvSource({"1, 20000", "2, 20000", "2, 60000", "2, 120000", "6, 20000", "6, 60000", "8, 120000",
                "3, 10000", "6, 40000"})
        @DisplayName("keeps per-frame packets within the bound maxPacketBytes documents")
        void perFramePacketBound(int channels, int micros) {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, channels)
                    .frameDurationMicros(micros).bitrate(300_000 * channels).build());
            var frame = 48 * micros / 1000;
            var subframes = Math.max(1, micros / 20000);
            var perStream = subframes == 1 ? OpusEncoder.maxPacketBytes() : (OpusEncoder.maxPacketBytes() + 1) * subframes;
            var streams = encoder.streamCount();
            var bound = streams * perStream + 2 * (streams - 1);
            var random = new Random(channels * 1000L + micros);
            var pcm = new float[frame * channels];
            var packet = new byte[2 * bound];
            var largest = 0;
            for (var k = 0; k < 8; k++) {
                for (var i = 0; i < pcm.length; i++) {
                    pcm[i] = (float) (0.4 * random.nextGaussian());
                }
                largest = Math.max(largest, encoder.encode(pcm, 0, frame, packet, 0, packet.length));
            }
            assertTrue(largest <= bound, "packet of " + largest + " bytes exceeds the bound " + bound);
            // Noise at the highest bitrate comes near the bound, so the check is not vacuous.
            assertTrue(largest > bound / 3, "largest packet " + largest + " of " + bound);
        }

        @Test
        @DisplayName("checks the bounds of a multichannel per-frame call against the channel count")
        void perFrameBounds() {
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1).build());
            var packet = new byte[8000];
            assertThrows(IndexOutOfBoundsException.class, () -> encoder.encode(new float[960 * 6 - 1], 0, 960, packet,
                    0, packet.length));
            assertThrows(IndexOutOfBoundsException.class, () -> encoder.encode(new short[960 * 6], 1, 960, packet,
                    0, packet.length));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new float[961 * 6], 0, 961, packet,
                    0, packet.length));
            assertTrue(encoder.encode(new short[960 * 6 + 3], 3, 960, packet, 0, packet.length) > 0);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"DISCRETE, 12", "AMBISONIC, 6", "AMBISONIC, 11"})
        @DisplayName("keeps every channel of a discrete or ambisonic stream in its coded position")
        void otherMappingsRouting(OpusChannelMapping mapping, int channels) {
            var layout = new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, layout).channelMapping(mapping)
                    .bitrate(48000 * channels).build());
            var input = new float[channels][48000];
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < input[c].length; i++) {
                    input[c][i] = (float) (0.25 * Math.sin(2 * Math.PI * (300 + 250 * c) * i / 48000));
                }
            }
            var packets = encode(encoder, input);
            var decoder = new OpusDecoder(track(encoder.codecConfig().orElseThrow(), 48000));
            var decoded = decode(decoder, packets);
            assertEquals(channels, decoded.length);
            for (var d = 0; d < channels; d++) {
                var own = power(decoded[d], 300 + 250 * d, 48000);
                assertTrue(own > 1e-4, "decoded channel " + d + " is silent");
                for (var other = 0; other < channels; other++) {
                    if (other != d) {
                        assertTrue(own > 100 * power(decoded[d], 300 + 250 * other, 48000),
                                "decoded channel " + d + " leaks input channel " + other);
                    }
                }
            }
        }

        @Test
        @DisplayName("codes 255 discrete channels, one stream each, in input order")
        void maximumChannels() {
            var channels = 255;
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, channels).build());
            assertEquals(OpusChannelMapping.DISCRETE, encoder.options().channelMapping());
            assertEquals(channels, encoder.streamCount());
            var input = new float[channels][9600];
            for (var c = 0; c < channels; c++) {
                if (c % 3 == 0) {
                    continue;
                }
                for (var i = 0; i < input[c].length; i++) {
                    input[c][i] = (float) (0.25 * Math.sin(2 * Math.PI * (500 + 500 * (c % 16)) * i / 48000));
                }
            }
            var packets = encode(encoder, input);
            for (var packet : packets) {
                streams(packet.data(), channels);
            }
            var decoded = decode(new OpusDecoder(track(encoder.codecConfig().orElseThrow(), 48000),
                    OpusDecoderOptions.builder().limits(MediaLimits.DEFAULT.toBuilder().maxChannels(255).build()).build()),
                    packets);
            assertEquals(channels, decoded.length);
            for (var c = 0; c < channels; c++) {
                var own = power(decoded[c], 500 + 500 * (c % 16), 48000);
                if (c % 3 == 0) {
                    assertTrue(own < 1e-7, "silent input channel " + c + " decodes to " + own);
                    continue;
                }
                assertTrue(own > 1e-3, "decoded channel " + c + " is silent");
                for (var k = 0; k < 16; k++) {
                    if (k != c % 16) {
                        assertTrue(own > 100 * power(decoded[c], 500 + 500 * k, 48000),
                                "decoded channel " + c + " holds the tone of channel class " + k);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {8000, 12000, 16000, 24000})
        @DisplayName("codes surround at every Opus input rate")
        void rates(int rate) {
            var layout = ChannelLayout.SURROUND_5_1;
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(rate, layout).bitrate(256000).build());
            var input = tones(new ChannelLayout(layout.speakers()), rate, rate * 2);
            // Keep the tones below the input Nyquist frequency: shift them down at low rates.
            if (rate < 16000) {
                for (var c = 0; c < 6; c++) {
                    var f = layout.speakers().get(c) == Speaker.LOW_FREQUENCY ? 70 : 300 + 150 * c;
                    for (var i = 0; i < input[c].length; i++) {
                        input[c][i] = (float) (0.25 * Math.sin(2 * Math.PI * f * i / rate));
                    }
                }
            }
            var packets = encode(encoder, input);
            var decoder = new OpusDecoder(track(encoder.codecConfig().orElseThrow(), rate),
                    OpusDecoderOptions.builder().sampleRate(rate).build());
            var decoded = decode(decoder, packets);
            assertEquals(6, decoded.length);
            for (var c = 0; c < 6; c++) {
                double energy = 0;
                for (var sample : decoded[c]) {
                    energy += sample * sample;
                }
                assertTrue(energy / decoded[c].length > 1e-3, "channel " + c + " at " + rate + " Hz");
            }
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        /** Writes a 16-bit WAVE_FORMAT_EXTENSIBLE file. */
        private static byte[] wav(ChannelLayout layout, int mask, float[][] planes, int rate) {
            var channels = planes.length;
            var frames = planes[0].length;
            var data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < frames; i++) {
                for (var c = 0; c < channels; c++) {
                    data.putShort((short) Math.round(planes[c][i] * 32767));
                }
            }
            var out = ByteBuffer.allocate(68 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            out.put("RIFF".getBytes()).putInt(60 + data.capacity()).put("WAVE".getBytes());
            out.put("fmt ".getBytes()).putInt(40).putShort((short) 0xFFFE).putShort((short) channels).putInt(rate)
                    .putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                    .putShort((short) 22).putShort((short) 16).putInt(mask)
                    .putInt(1).putShort((short) 0x0000).putShort((short) 0x0010)
                    .put(new byte[]{(byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71});
            out.put("data".getBytes()).putInt(data.capacity()).put(data.array());
            assertEquals(layout.channels(), channels);
            return out.array();
        }

        private static Demuxer demuxer(OutputContainer container, byte[] file) {
            var source = MediaSource.of(file);
            return switch (container) {
                case OGG -> new OggDemuxer(source);
                case WEBM, MATROSKA -> new MatroskaDemuxer(source);
                default -> new Mp4Demuxer(source);
            };
        }

        private static List<Packet> packets(Demuxer demuxer) {
            var out = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                out.add(packet);
            }
            return out;
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "OGG, 5.1", "WEBM, 5.1", "MATROSKA, 5.1", "MP4, 5.1", "M4A, 5.1", "FRAGMENTED_MP4, 5.1",
                "OGG, 7.1", "WEBM, 7.1", "MP4, 7.1", "OGG, 5.1 side", "MP4, 5.1 side",
        })
        @DisplayName("transcodes surround WAV to Opus with every channel at its speaker")
        void transcode(OutputContainer container, String name, @TempDir Path directory) throws Exception {
            var layout = switch (name) {
                case "5.1" -> ChannelLayout.SURROUND_5_1;
                case "7.1" -> ChannelLayout.SURROUND_7_1;
                default -> SURROUND_5_1_SIDE;
            };
            var mask = switch (name) {
                case "5.1" -> 0x3F;
                case "7.1" -> 0x63F;
                default -> 0x60F;
            };
            var input = tones(layout, 48000, 3 * 48000);
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(wav(layout, mask, input, 48000)), sink,
                    TranscodeOptions.of(container).toBuilder().audio(AudioTranscodeOptions.encode(Codec.OPUS)).build());
            var file = sink.toByteArray();
            try (var demuxer = demuxer(container, file)) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.OPUS, track.codec());
                var head = OpusHead.parse(track.codecConfig().orElseThrow(), MediaLimits.DEFAULT);
                assertEquals(1, head.mappingFamily());
                assertEquals(layout.channels(), head.channels());
                assertEquals(layout.channels() == 6 ? 4 : 5, head.streamCount());
                var decoder = new OpusDecoder(track);
                var decoded = decode(decoder, packets(demuxer));
                assertRouted(layout, decoder.layout(), decoded, 48000);
                assertEquals(3 * 48000, decoded[0].length, 960, "decoded length");
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = directory.resolve("surround." + container.extension());
                Files.write(path, file);
                Oracle.assertDecodesCleanly(path);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains("\"channels\": " + layout.channels()), probe);
                var interleaved = Oracle.decodeAudio(path);
                var channels = layout.channels();
                var planes = new float[channels][interleaved.length / channels];
                for (var i = 0; i < planes[0].length; i++) {
                    for (var c = 0; c < channels; c++) {
                        planes[c][i] = interleaved[i * channels + c];
                    }
                }
                // ffmpeg decodes the Vorbis order into the WAVE order of the default layout.
                assertRouted(layout, ChannelLayout.defaultFor(channels), planes, 48000);
            }
        }

        @Test
        @DisplayName("ffmpeg decodes discrete and ambisonic multistreams to the coded channels")
        void otherFamilies(@TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            for (var mapping : List.of(OpusChannelMapping.DISCRETE, OpusChannelMapping.AMBISONIC)) {
                var layout = ChannelLayout.defaultFor(4);
                var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, layout).channelMapping(mapping)
                        .bitrate(256000).build());
                var input = tones(layout, 48000, 2 * 48000);
                var packets = encode(encoder, input);
                var samples48 = new int[packets.size()];
                Arrays.fill(samples48, 960);
                var head = encoder.codecConfig().orElseThrow();
                var data = new ArrayList<byte[]>();
                for (var packet : packets) {
                    data.add(packet.data());
                }
                var file = OggOpusIO.write(head, data, samples48, 312, 2 * 48000L);
                var path = directory.resolve(mapping + ".opus");
                Files.write(path, file);
                Oracle.assertDecodesCleanly(path);
                var interleaved = Oracle.decodeAudio(path);
                var planes = new float[4][interleaved.length / 4];
                for (var i = 0; i < planes[0].length; i++) {
                    for (var c = 0; c < 4; c++) {
                        planes[c][i] = interleaved[i * 4 + c];
                    }
                }
                var unknown = new ChannelLayout(Collections.nCopies(4, Speaker.UNKNOWN));
                assertRouted(unknown, unknown, planes, 48000);
                var decoder = new OpusDecoder(track(head, 48000));
                assertRouted(unknown, decoder.layout(), decode(decoder, packets), 48000);
            }
        }

        @Test
        @DisplayName("ffmpeg's decode of a 5.1 stream matches Smalt's")
        void matchesFfmpeg(@TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var layout = ChannelLayout.SURROUND_5_1;
            var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, layout).bitrate(320000).build());
            var input = tones(layout, 48000, 2 * 48000);
            var random = new Random(3);
            for (var plane : input) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] += (float) (0.05 * random.nextGaussian());
                }
            }
            var packets = encode(encoder, input);
            var samples48 = new int[packets.size()];
            Arrays.fill(samples48, 960);
            var data = new ArrayList<byte[]>();
            for (var packet : packets) {
                data.add(packet.data());
            }
            var path = directory.resolve("s51.opus");
            Files.write(path, OggOpusIO.write(encoder.codecConfig().orElseThrow(), data, samples48, 312, 2 * 48000L));
            var interleaved = Oracle.decodeAudio(path);
            var decoder = new OpusDecoder(track(encoder.codecConfig().orElseThrow(), 48000));
            var smalt = decode(decoder, packets);
            var frames = Math.min(interleaved.length / 6, smalt[0].length);
            assertTrue(frames >= 2 * 48000 - 960, "decoded " + frames + " frames");
            for (var c = 0; c < 6; c++) {
                double signal = 0;
                double error = 0;
                for (var i = 0; i < frames; i++) {
                    var reference = interleaved[i * 6 + c];
                    signal += reference * reference;
                    var difference = reference - smalt[c][i];
                    error += difference * difference;
                }
                var snr = 10 * Math.log10(signal / Math.max(error, 1e-20));
                assertTrue(snr > 60, "channel " + c + " differs from ffmpeg's decode: " + snr + " dB");
            }
        }
    }
}
