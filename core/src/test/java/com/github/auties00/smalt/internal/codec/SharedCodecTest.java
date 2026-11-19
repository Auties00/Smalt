package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared helpers of {@code internal.codec}: the audio configuration helpers, the VP9
 * matrix mapping, the AC-3 tables, the encoder clocks, the consumer failure handoff and the
 * encoder tail. Where a helper replaces private copies that differ, the copies are kept here as
 * references and the helper is pinned against them.
 */
class SharedCodecTest {
    @Nested
    @DisplayName("AudioConfigs")
    class Audio {
        @Test
        @DisplayName("parses every MPEG audio header exactly as the former parser did")
        void mpegHeaderPin() {
            var data = new byte[4];
            data[0] = (byte) 0xFF;
            for (var b1 = 0; b1 < 256; b1++) {
                for (var b2 = 0; b2 < 256; b2++) {
                    data[1] = (byte) b1;
                    data[2] = (byte) b2;
                    var expected = formerMpegHeader(data);
                    var actual = AudioConfigs.parseMpegHeader(data, 0, 4);
                    assertEquals(expected, actual, "header FF " + b1 + " " + b2);
                }
            }
            data[0] = 0x7F;
            assertNull(AudioConfigs.parseMpegHeader(data, 0, 4));
            assertNull(AudioConfigs.parseMpegHeader(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90}, 0, 3));
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("reads the FLAC configuration fields the former parser read, without the stream info checks")
        void flacPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 2000; trial++) {
                var config = new byte[42 + random.nextInt(8)];
                random.nextBytes(config);
                config[0] = 'f';
                config[1] = 'L';
                config[2] = 'a';
                config[3] = 'C';
                config[4] = (byte) (random.nextBoolean() ? 0x80 : 0);
                var info = 8;
                var rate = ((config[info + 10] & 0xFF) << 12) | ((config[info + 11] & 0xFF) << 4)
                        | ((config[info + 12] & 0xFF) >> 4);
                var channels = ((config[info + 12] >> 1) & 7) + 1;
                var depth = (((config[info + 12] & 1) << 4) | ((config[info + 13] & 0xFF) >> 4)) + 1;
                assertEquals(new AudioConfigs.FlacConfig(rate, channels, depth), AudioConfigs.parseFlac(config));
            }
            var zeroRate = new byte[42];
            System.arraycopy(AudioConfigs.FLAC_MARKER, 0, zeroRate, 0, 4);
            assertEquals(0, AudioConfigs.parseFlac(zeroRate).sampleRate());
            assertThrows(MediaException.Malformed.class, () -> AudioConfigs.parseFlac(new byte[41]));
        }

        @Test
        @DisplayName("reads the pre-skip of any configuration of at least 12 bytes and -1 otherwise")
        void opusPreSkip() {
            var head = new byte[19];
            head[10] = 0x38;
            head[11] = 0x01;
            assertEquals(312, AudioConfigs.opusPreSkip(head));
            assertEquals(312, AudioConfigs.opusPreSkip(Arrays.copyOf(head, 12)));
            assertEquals(-1, AudioConfigs.opusPreSkip(Arrays.copyOf(head, 11)));
            assertEquals(-1, AudioConfigs.opusPreSkip(null));
        }

        @Test
        @DisplayName("keeps the mapping bytes of an OpusHead and its dOps round trip")
        void opusHead() {
            var head = new byte[]{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 3, 0x38, 0x01, (byte) 0x80, (byte) 0xBB,
                    0, 0, 0, 0, 1, 2, 1, 0, 2, 1};
            var config = AudioConfigs.parseOpusHead(head);
            assertEquals(3, config.channels());
            assertEquals(312, config.preSkip());
            assertEquals(48000, config.inputRate());
            assertArrayEquals(new byte[]{2, 1, 0, 2, 1}, config.mapping());
            assertArrayEquals(head, AudioConfigs.opusHeadFromDops(AudioConfigs.dopsFromOpusHead(head)));
            // Family 3: the stream counts and a 2 x 3 demixing matrix of Q15 coefficients.
            var projection = new byte[19 + 2 + 12];
            System.arraycopy(head, 0, projection, 0, 19);
            projection[9] = 2;
            projection[18] = 3;
            projection[19] = 2;
            projection[20] = 1;
            for (var i = 21; i < projection.length; i++) {
                projection[i] = (byte) (i * 37);
            }
            var demixing = AudioConfigs.parseOpusHead(projection);
            assertEquals(3, demixing.family());
            assertArrayEquals(Arrays.copyOfRange(projection, 19, projection.length), demixing.mapping());
            assertArrayEquals(projection, AudioConfigs.opusHeadFromDops(AudioConfigs.dopsFromOpusHead(projection)));
            var dops = AudioConfigs.dopsFromOpusHead(projection);
            // A table cut after one byte per channel (FFmpeg's dOps for family 3) is kept short, and the
            // OpusHead it gives fails to parse as a projection header; a shorter one is malformed.
            var shortTable = AudioConfigs.opusHeadFromDops(Arrays.copyOf(dops, dops.length - 1));
            assertArrayEquals(Arrays.copyOf(projection, 19 + 2 + 2), shortTable);
            assertThrows(MediaException.Malformed.class, () -> OpusHeader.parseLenient(shortTable));
            assertThrows(MediaException.Malformed.class,
                    () -> AudioConfigs.opusHeadFromDops(Arrays.copyOf(dops, 11 + 3)));
        }

        @Test
        @DisplayName("maps the AAC rates both ways")
        void aacRates() {
            assertEquals(44100, AudioConfigs.aacSampleRate(4));
            assertEquals(0, AudioConfigs.aacSampleRate(13));
            assertEquals(3, AudioConfigs.aacSampleRateIndex(48000));
            assertEquals(-1, AudioConfigs.aacSampleRateIndex(44000));
        }

        @ParameterizedTest(name = "type {0}, {1} Hz, configuration {2}, output {3} Hz, PS {4}")
        @CsvSource({"2, 44100, 2, 0, false", "2, 48000, 1, 0, false", "2, 22050, 1, 44100, false",
                "2, 24000, 1, 48000, true", "2, 44000, 2, 0, false", "1, 8000, 7, 0, false", "40, 32000, 2, 0, false",
                "2, 16000, 2, 37000, false"})
        @DisplayName("builds an AudioSpecificConfig that parses back to its fields")
        void audioSpecificConfig(int type, int rate, int channels, int output, boolean ps) {
            var config = AudioConfigs.audioSpecificConfig(type, rate, channels, output, ps);
            var parsed = AudioConfigs.parseAac(config);
            assertEquals(type, parsed.objectType());
            assertEquals(rate, parsed.sampleRate());
            assertEquals(output > 0, parsed.sbr());
            assertEquals(output > 0 ? output : rate, parsed.outputRate());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"b98900, 23, 48000, 512, 48000, false", "b98d20, 23, 48000, 480, 48000, false",
                "f8e63000, 39, 48000, 480, 48000, false", "f8ec21b9209c00, 39, 24000, 512, 48000, true",
                "f8ec2126808200, 39, 24000, 512, 24000, true", "898900, 17, 48000, 1024, 48000, false"})
        @DisplayName("parses the frame length and low delay SBR of AAC-LD, AAC-ELD and ER AAC LC configurations")
        void lowDelay(String hex, int type, int rate, int frameLength, int output, boolean sbr) {
            // ISO/IEC 14496-26 er_ad1000, er_ad1111, er_eld1100np, er_eld_sbr_e_1 (dual rate), er_eld_sbr_twi_1
            // (downsampled) and er_al10
            var parsed = AudioConfigs.parseAac(java.util.HexFormat.of().parseHex(hex));
            assertEquals(type, parsed.objectType());
            assertEquals(rate, parsed.sampleRate());
            assertEquals(frameLength, parsed.frameLength());
            assertEquals(output, parsed.outputRate());
            assertEquals(sbr, parsed.sbr());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"f9464362, 48000, 24000, 1024, true, 2, 2", "f94a2541, 32000, 12000, 768, true, 1, 1"})
        @DisplayName("parses the output and core rates, frame length and channels of USAC configurations")
        void usac(String hex, int output, int rate, int frameLength, boolean sbr, int channels, int configuration) {
            // object type 42, then the start of UsacConfig: the 2:1 SBR of 1024-sample frames at 48 kHz in stereo,
            // and the 8:3 SBR of 768-sample frames at 32 kHz in mono
            var parsed = AudioConfigs.parseAac(java.util.HexFormat.of().parseHex(hex));
            assertEquals(42, parsed.objectType());
            assertEquals(output, parsed.outputRate());
            assertEquals(rate, parsed.sampleRate());
            assertEquals(frameLength, parsed.frameLength());
            assertEquals(sbr, parsed.sbr());
            assertEquals(channels, parsed.channels());
            assertEquals(configuration, parsed.channelConfiguration());
            assertThrows(MediaException.Malformed.class,
                    () -> AudioConfigs.parseAac(java.util.HexFormat.of().parseHex(hex.substring(0, 6))));
        }

        @Test
        @DisplayName("writes the bytes the ADTS parsers and demuxer wrote")
        void audioSpecificConfigBytes() {
            var writer = new MsbBitWriter(8);
            writer.write(2, 5);
            writer.write(4, 4);
            writer.write(2, 4);
            writer.write(0, 3);
            writer.alignWithZeros();
            assertArrayEquals(writer.toByteArray(), AudioConfigs.audioSpecificConfig(2, 44100, 2, 0, false));
            var sbr = new MsbBitWriter(8);
            sbr.write(2, 5);
            sbr.write(6, 4);
            sbr.write(1, 4);
            sbr.write(0, 3);
            sbr.write(0x2B7, 11);
            sbr.write(5, 5);
            sbr.write(1, 1);
            sbr.write(3, 4);
            sbr.write(0x548, 11);
            sbr.write(1, 1);
            sbr.alignWithZeros();
            assertArrayEquals(sbr.toByteArray(), AudioConfigs.audioSpecificConfig(2, 24000, 1, 48000, true));
            assertThrows(IllegalArgumentException.class, () -> AudioConfigs.audioSpecificConfig(2, 44100, 0, 0, false));
            assertThrows(IllegalArgumentException.class, () -> AudioConfigs.audioSpecificConfig(0, 44100, 2, 0, false));
        }

        /** The former {@code AudioConfigs.parseMpegHeader}. */
        private static AudioConfigs.MpegHeader formerMpegHeader(byte[] data) {
            var b1 = data[0] & 0xFF;
            var b2 = data[1] & 0xFF;
            var b3 = data[2] & 0xFF;
            if (b1 != 0xFF || (b2 & 0xE0) != 0xE0) {
                return null;
            }
            var version = (b2 >> 3) & 3;
            var layerBits = (b2 >> 1) & 3;
            var frequency = (b3 >> 2) & 3;
            if (version == 1 || layerBits == 0 || frequency == 3) {
                return null;
            }
            var layer = 4 - layerBits;
            var mpeg1 = version == 3;
            var sampleRate = new int[]{44100, 48000, 32000}[frequency] >> (version == 3 ? 0 : version == 2 ? 1 : 2);
            var samples = layer == 1 ? 384 : layer == 2 ? 1152 : mpeg1 ? 1152 : 576;
            return new AudioConfigs.MpegHeader(layer, sampleRate, samples, mpeg1);
        }
    }

    @Nested
    @DisplayName("AAC and TrueHD layouts")
    class Layouts {
        @Test
        @DisplayName("gives the AAC configurations their speakers, none to configuration 0 and the reserved ones")
        void aac() {
            for (var reserved : new int[]{0, 8, 9, 10, 15}) {
                assertNull(AudioConfigs.aacLayout(reserved), "configuration " + reserved);
            }
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER), AudioConfigs.aacLayout(11).speakers());
            assertEquals(ChannelLayout.SURROUND_7_1, AudioConfigs.aacLayout(12));
            assertEquals(24, AudioConfigs.aacLayout(13).channels());
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT),
                    AudioConfigs.aacLayout(14).speakers());
            // AAC LC, 48 kHz, configuration 13
            var config = AudioConfigs.parseAac(new byte[]{0x11, (byte) 0xE8});
            assertEquals(13, config.channelConfiguration());
            assertEquals(24, config.channels());
            assertEquals(AudioConfigs.aacLayout(13), config.layout());
        }

        @Test
        @DisplayName("names the surround direct, wide and second LFE locations of a TrueHD presentation")
        void trueHd() {
            var unit = new byte[12];
            // 8-channel assignment: L/R, C, LFE, Lsd/Rsd (bit 9), Lw/Rw (bit 10), LFE2 (bit 12)
            var assignment = 1 | 2 | 4 | 1 << 9 | 1 << 10 | 1 << 12;
            unit[10] = (byte) (assignment >>> 8);
            unit[11] = (byte) assignment;
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.WIDE_LEFT, Speaker.WIDE_RIGHT, Speaker.SURROUND_DIRECT_LEFT, Speaker.SURROUND_DIRECT_RIGHT,
                    Speaker.LOW_FREQUENCY_2), TrueHdConfigs.layout(unit, 0).speakers());
        }
    }

    @Nested
    @DisplayName("VideoConfigs.vp9Matrix")
    class Vp9Matrix {
        @ParameterizedTest(name = "color space {0} -> {1}")
        @CsvSource({"0, 2", "1, 5", "2, 1", "3, 6", "4, 7", "5, 9", "6, 2", "7, 0", "8, 2", "-1, 2"})
        @DisplayName("maps the VP9 color spaces to the code points ffprobe reports, and the vpcC box follows")
        void mapping(int colorSpace, int matrix) {
            assertEquals(matrix, VideoConfigs.vp9Matrix(colorSpace));
            if (colorSpace >= 0 && colorSpace <= 7) {
                var config = CodecConfigs.vp9Config(keyFrame(colorSpace), 320, 240, null);
                assertEquals(matrix, config[5]);
            }
        }

        private static byte[] keyFrame(int colorSpace) {
            var writer = new MsbBitWriter(16);
            writer.write(2, 2);
            writer.write(0, 2);
            writer.write(0, 1);
            writer.write(0, 1);
            writer.write(1, 1);
            writer.write(0, 1);
            writer.write(0x498342, 24);
            writer.write(colorSpace, 3);
            if (colorSpace != 7) {
                writer.write(0, 1);
            }
            writer.write(319, 16);
            writer.write(239, 16);
            writer.write(0, 16);
            return writer.toByteArray();
        }
    }

    @Nested
    @DisplayName("Ac3Configs")
    class Ac3 {
        @Test
        @DisplayName("orders every coding mode and location combination as the former ordinal loop did")
        void layoutPin() {
            int[] channelLocations = {5, 6, 7, 8, 9, 10, 11, 12, 14};
            for (var acmod = 0; acmod < 8; acmod++) {
                for (var lfe = 0; lfe < 2; lfe++) {
                    for (var locations = 0; locations < 512; locations++) {
                        var speakers = new ArrayList<>(List.of(Ac3Configs.MODES[acmod]));
                        if (lfe == 1) {
                            speakers.add(Speaker.LOW_FREQUENCY);
                        }
                        for (var bit = 0; bit < channelLocations.length; bit++) {
                            if ((locations & (1 << bit)) != 0) {
                                for (var speaker : Ac3Configs.LOCATIONS[channelLocations[bit]]) {
                                    if (speaker == Speaker.UNKNOWN || !speakers.contains(speaker)) {
                                        speakers.add(speaker);
                                    }
                                }
                            }
                        }
                        var ordered = new ArrayList<Speaker>();
                        for (var speaker : Speaker.values()) {
                            for (var candidate : speakers) {
                                if (candidate == speaker) {
                                    ordered.add(candidate);
                                }
                            }
                        }
                        assertEquals(new ChannelLayout(ordered), Ac3Configs.layout(acmod, lfe == 1, locations));
                    }
                }
            }
        }

        @Test
        @DisplayName("exposes the A/52 tables")
        void tables() {
            assertEquals(38, Ac3Configs.FRAME_WORDS.length);
            assertEquals(1394, Ac3Configs.FRAME_WORDS[37][1]);
            assertEquals(19, Ac3Configs.BITRATES.length);
            assertEquals(640, Ac3Configs.BITRATES[18]);
            assertArrayEquals(new int[]{48000, 44100, 32000}, Ac3Configs.RATES);
            assertArrayEquals(new int[]{1, 2, 3, 6}, Ac3Configs.BLOCKS);
            assertEquals(8, Ac3Configs.MODES.length);
            assertEquals(16, Ac3Configs.LOCATIONS.length);
        }
    }

    @Nested
    @DisplayName("FrameClock")
    class Clock {
        private static final Rational TIME_BASE = new Rational(1, 90000);

        @Test
        @DisplayName("synthesizes timestamps and durations from the nominal rate and rejects timestamps that do not increase")
        void timing() {
            var clock = new FrameClock(new Rational(30, 1), TIME_BASE);
            assertEquals(3000, clock.nominalDuration());
            var first = clock.resolve(Packet.NO_TIMESTAMP, 0);
            assertEquals(new FrameClock.Timing(0, 3000), first);
            assertEquals(Packet.NO_TIMESTAMP, clock.lastPts());
            clock.commit(first.pts());
            assertEquals(new FrameClock.Timing(3000, 3000), clock.resolve(Packet.NO_TIMESTAMP, -1));
            assertEquals(new FrameClock.Timing(9000, 1500), clock.resolve(9000, 1500));
            var failure = assertThrows(IllegalArgumentException.class, () -> clock.resolve(0, 0));
            assertEquals("timestamp 0 does not follow 0", failure.getMessage());
            clock.setFrameRate(new Rational(60, 1));
            assertEquals(new FrameClock.Timing(1500, 1500), clock.resolve(Packet.NO_TIMESTAMP, 0));
            var frame = new VideoFrame(RgbaPicture.allocate(1, 1, false), 4500, 0, false);
            assertEquals(new FrameClock.Timing(4500, 1500), clock.resolve(frame));
        }

        @Test
        @DisplayName("rescales to an encoder clock with at least one tick per frame")
        void ticks() {
            var clock = new FrameClock(new Rational(30, 1), TIME_BASE);
            var ticks = new Rational(1, 1000);
            var timing = new FrameClock.Timing(9000, 10);
            assertEquals(100, clock.startTicks(timing, ticks));
            assertEquals(101, clock.endTicks(timing, ticks));
        }

        @Test
        @DisplayName("checks rates with the messages the encoder options used")
        void rates() {
            FrameClock.checkRates(new Rational(30, 1), TIME_BASE);
            assertEquals("unsupported frame rate 1/20", assertThrows(IllegalArgumentException.class,
                    () -> FrameClock.checkRates(new Rational(1, 20), TIME_BASE)).getMessage());
            assertThrows(IllegalArgumentException.class, () -> FrameClock.checkRates(new Rational(1001, 1), TIME_BASE));
            assertThrows(IllegalArgumentException.class,
                    () -> FrameClock.checkRates(new Rational(30, 1), new Rational(0, 1)));
        }

        @Test
        @DisplayName("consumes a key frame request exactly once, whatever thread posts it")
        void keyframeLatch() throws InterruptedException {
            var state = new RealtimeEncoderClock(new Rational(30, 1), TIME_BASE);
            assertFalse(state.takeKeyframe(false));
            assertTrue(state.takeKeyframe(true));
            state.requestKeyframe();
            assertTrue(state.takeKeyframe(false));
            assertFalse(state.takeKeyframe(false));
            // the poster posts the next request only once the coding thread took the previous one,
            // so a request lost between a read and a clear would stall both loops until the deadline
            var requests = 2000;
            var taken = new AtomicInteger();
            var deadline = System.nanoTime() + 20_000_000_000L;
            var start = new CountDownLatch(1);
            var poster = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    return;
                }
                for (var i = 0; i < requests && System.nanoTime() < deadline; i++) {
                    state.requestKeyframe();
                    while (taken.get() <= i && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                }
            });
            start.countDown();
            var spurious = 0;
            while (taken.get() < requests && System.nanoTime() < deadline) {
                if (state.takeKeyframe(false)) {
                    taken.incrementAndGet();
                } else {
                    spurious++;
                }
            }
            poster.join();
            assertEquals(requests, taken.get(), "a request was lost; " + spurious + " empty takes");
        }

        @Test
        @DisplayName("counts coded and dropped frames and commits their timestamps")
        void counters() {
            var state = new RealtimeEncoderClock(new Rational(30, 1), TIME_BASE);
            var timing = state.clock().resolve(Packet.NO_TIMESTAMP, 0);
            var packet = state.coded(new byte[10], timing, true);
            assertEquals(0, packet.pts());
            assertEquals(0, packet.dts());
            assertEquals(3000, packet.duration());
            assertTrue(packet.keyframe());
            state.dropped(state.clock().resolve(Packet.NO_TIMESTAMP, 0));
            state.coded(new byte[4], state.clock().resolve(Packet.NO_TIMESTAMP, 0), false);
            assertEquals(2, state.framesEncoded());
            assertEquals(1, state.keyFramesEncoded());
            assertEquals(1, state.framesDropped());
            assertEquals(14, state.bytesEncoded());
            assertEquals(4, state.lastFrameBytes());
            assertEquals(6000, state.clock().lastPts());
        }

        @Test
        @DisplayName("counts a delayed frame without moving the committed timestamp")
        void delivered() {
            var state = new RealtimeEncoderClock(new Rational(30, 1), TIME_BASE);
            state.clock().commit(9000);
            var packet = state.delivered(new byte[7], new FrameClock.Timing(3000, 3000), false);
            assertEquals(3000, packet.pts());
            assertEquals(3000, packet.dts());
            assertFalse(packet.keyframe());
            assertEquals(1, state.framesEncoded());
            assertEquals(7, state.bytesEncoded());
            assertEquals(9000, state.clock().lastPts());
        }
    }

    @Nested
    @DisplayName("ConsumerFailure")
    class Handoff {
        @Test
        @DisplayName("rethrows the consumer's own exception unchanged")
        void consumerFailure() {
            var failure = new IllegalStateException("consumer");
            Consumer<String> output = value -> {
                throw failure;
            };
            var thrown = assertThrows(IllegalStateException.class,
                    () -> ConsumerFailure.<String>run("AAC packet", output, emit -> emit.accept("frame")));
            assertSame(failure, thrown);
        }

        @Test
        @DisplayName("reports the engine's own runtime failure as a corrupt input and passes media exceptions through")
        void engineFailure() {
            var malformed = assertThrows(MediaException.Malformed.class,
                    () -> ConsumerFailure.<String>run("AAC packet", value -> { }, emit -> {
                        throw new ArrayIndexOutOfBoundsException();
                    }));
            assertEquals("corrupt AAC packet", malformed.getMessage());
            var media = new MediaException.Unsupported("aac", "SSR");
            assertSame(media, assertThrows(MediaException.Unsupported.class,
                    () -> ConsumerFailure.<String>run("AAC packet", value -> { }, emit -> {
                        throw media;
                    })));
        }

        @Test
        @DisplayName("tells the consumer's failure apart in a caller's own catch block")
        void wrap() {
            var received = new ArrayList<String>();
            var handoff = ConsumerFailure.<String>wrap(received::add);
            handoff.accept("a");
            assertEquals(List.of("a"), received);
            var failure = new IllegalArgumentException();
            var throwing = ConsumerFailure.<String>wrap(value -> {
                throw failure;
            });
            assertThrows(IllegalArgumentException.class, () -> throwing.accept("b"));
            assertTrue(throwing.isConsumerFailure(failure));
            assertFalse(throwing.isConsumerFailure(new IllegalArgumentException()));
            assertSame(failure, throwing.translate("frame", failure));
        }
    }

    @Nested
    @DisplayName("EncoderTail")
    class Tail {
        @Test
        @DisplayName("passes packets one behind with the track identifier and trims the last to the input's end")
        void holdAndTrim() {
            var written = new ArrayList<Packet>();
            var tail = new EncoderTail(3, written::add);
            tail.accept(new Packet(0, new byte[1], 0, 0, 960, true));
            assertTrue(written.isEmpty());
            tail.accept(new Packet(0, new byte[1], 960, 960, 960, true));
            assertEquals(1, written.size());
            assertEquals(3, written.getFirst().trackId());
            tail.finish(960 + 100);
            assertEquals(2, written.size());
            assertEquals(100, written.get(1).duration());
            assertEquals(3, written.get(1).trackId());
            tail.finish(5000);
            assertEquals(2, written.size());
        }

        @Test
        @DisplayName("keeps a trimmed duration between 1 and the coded duration")
        void clamp() {
            var written = new ArrayList<Packet>();
            var tail = new EncoderTail(0, written::add);
            tail.accept(new Packet(0, new byte[1], 1000, 1000, 960, true));
            tail.finish(900);
            assertEquals(1, written.getFirst().duration());
            tail.accept(new Packet(0, new byte[1], 2000, 2000, 960, true));
            tail.finish(10_000);
            assertEquals(960, written.get(1).duration());
        }

        @ParameterizedTest(name = "fixed rate {0}, fixed layout {1}")
        @CsvSource({"false, false", "true, false", "false, true", "true, true"})
        @DisplayName("builds the candidates in the order the transcoder tried them and opens the first accepted one")
        void candidates(boolean fixedRate, boolean fixedLayout) throws Exception {
            var tried = new ArrayList<String>();
            var encoder = EncoderTail.open(Codec.PCM_S16LE, 44100, ChannelLayout.SURROUND_5_1, fixedRate, fixedLayout,
                    (rate, layout) -> {
                        tried.add(rate + "/" + layout.channels());
                        return AudioEncoderOptions.of(rate, layout);
                    });
            try (encoder) {
                assertEquals(44100, encoder.sampleRate());
                var expected = new ArrayList<String>();
                expected.add("44100/6");
                if (!fixedRate) {
                    expected.add("48000/6");
                }
                if (!fixedLayout) {
                    expected.add("44100/2");
                    if (!fixedRate) {
                        expected.add("48000/2");
                    }
                }
                assertEquals(expected, tried);
                var track = EncoderTail.track(encoder, 7).build();
                assertEquals(7, track.id());
                assertEquals(Codec.PCM_S16LE, track.codec());
                assertEquals(44100, track.sampleRate());
                assertEquals(encoder.delay(), track.skipSamples());
            }
        }
    }
}
