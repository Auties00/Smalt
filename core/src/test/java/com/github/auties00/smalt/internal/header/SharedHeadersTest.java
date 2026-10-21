package com.github.auties00.smalt.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared header helpers of {@code internal.header}: the CRCs, the Opus identification
 * header, the Vorbis channel order, Xiph lacing, ADTS headers, AAC rates and the FLAC stream info.
 * Where a helper replaces private copies that differ, the copies are kept here as references and
 * the helper is pinned against them.
 */
class SharedHeadersTest {
    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Nested
    @DisplayName("CRC-16 and CRC-32/MPEG-2")
    class Crcs {
        @Test
        @DisplayName("match the cataloged check values of their parameter sets")
        void checkValues() {
            assertEquals(0xFEE8, Crc16.crc16(0, CHECK, 0, CHECK.length));
            assertEquals(0xAEE7, Crc16.crc16(0xFFFF, CHECK, 0, CHECK.length));
            assertEquals(0x0376E6E7, Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, CHECK, 0, CHECK.length));
        }

        @ParameterizedTest(name = "init {0}")
        @ValueSource(ints = {0, 0xFFFFFFFF, 0x1234ABCD})
        @DisplayName("CRC-32/MPEG-2 equals the bitwise definition over every length and split")
        void crc32Bitwise(int init) {
            var random = new Random(init);
            var data = new byte[200];
            random.nextBytes(data);
            for (var length = 0; length <= 64; length++) {
                for (var offset : new int[]{0, 3}) {
                    var expected = bitwiseCrc32(init, data, offset, length);
                    assertEquals(expected, Crc32Mpeg.update(init, data, offset, length));
                    var split = length / 3;
                    var partial = Crc32Mpeg.update(init, data, offset, split);
                    assertEquals(expected, Crc32Mpeg.update(partial, data, offset + split, length - split));
                }
            }
            assertEquals(bitwiseCrc32(init, data, 0, data.length), Crc32Mpeg.update(init, data, 0, data.length));
        }

        @Test
        @DisplayName("gives zero over a transport stream section with its CRC appended, as TsCrc did")
        void transportSection() {
            var section = new byte[]{0x00, (byte) 0xB0, 0x0D, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00, 0x00, 0x01,
                    (byte) 0xF0, 0x00, 0, 0, 0, 0};
            var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, 12);
            section[12] = (byte) (crc >>> 24);
            section[13] = (byte) (crc >>> 16);
            section[14] = (byte) (crc >>> 8);
            section[15] = (byte) crc;
            assertEquals(0, Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, section.length));
        }

        @Test
        @DisplayName("CRC-16 from 0xFFFF over the MP3 header and side information equals the bitwise MP3 CRC")
        void mp3Crc() {
            var random = new Random(3);
            var frame = new byte[40];
            random.nextBytes(frame);
            var crc = 0xFFFF;
            for (var i = 2; i < 4; i++) {
                crc = bitwiseCrc16(crc, frame[i]);
            }
            for (var i = 6; i < 23; i++) {
                crc = bitwiseCrc16(crc, frame[i]);
            }
            assertEquals(crc, Crc16.crc16(Crc16.crc16(0xFFFF, frame, 2, 2), frame, 6, 17));
        }

        @Test
        @DisplayName("rejects a range outside the array")
        void bounds() {
            assertThrows(IndexOutOfBoundsException.class, () -> Crc32Mpeg.update(0, new byte[4], 2, 4));
        }

        private static int bitwiseCrc32(int crc, byte[] data, int offset, int length) {
            for (var i = offset; i < offset + length; i++) {
                crc ^= (data[i] & 0xFF) << 24;
                for (var bit = 0; bit < 8; bit++) {
                    crc = crc < 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
                }
            }
            return crc;
        }

        private static int bitwiseCrc16(int crc, byte value) {
            crc ^= (value & 0xFF) << 8;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
            return crc;
        }
    }

    @Nested
    @DisplayName("OpusHeader")
    class Opus {
        @Test
        @DisplayName("parses a family 0 and a family 1 header")
        void parses() {
            var stereo = OpusHeader.parse(head(2, 312, 44100, -256, 0, null), MediaLimits.DEFAULT);
            assertEquals(2, stereo.channels());
            assertEquals(312, stereo.preSkip());
            assertEquals(44100, stereo.inputSampleRate());
            assertEquals(-256, stereo.outputGain());
            assertEquals(1, stereo.streamCount());
            assertEquals(1, stereo.coupledCount());
            assertEquals(0, stereo.mapping().length);
            assertEquals(1, stereo.mapping(1));
            var surround = OpusHeader.parse(head(6, 0, 48000, 0, 1, new int[]{4, 2, 0, 4, 1, 2, 3, 5}),
                    MediaLimits.DEFAULT);
            assertEquals(4, surround.streamCount());
            assertEquals(2, surround.coupledCount());
            assertEquals(4, surround.mapping(1));
            assertEquals(2, surround.mapping(3));
            assertEquals(surround, OpusHeader.parse(head(6, 0, 48000, 0, 1, new int[]{4, 2, 0, 4, 1, 2, 3, 5}),
                    MediaLimits.DEFAULT));
            assertNotEquals(surround, stereo);
        }

        @Test
        @DisplayName("copies its mapping table in and out")
        void defensiveCopy() {
            var table = new byte[]{0, 1};
            var header = new OpusHeader(1, 2, 0, 0, 0, 1, 1, 1, table);
            table[0] = 9;
            assertEquals(0, header.mapping()[0]);
            header.mapping()[0] = 9;
            assertEquals(0, header.mapping(0));
        }

        @Test
        @DisplayName("strict parsing rejects what OpusMapping and the Opus decoder rejected")
        void strict() {
            assertThrows(MediaException.Malformed.class,
                    () -> OpusHeader.parse(head(3, 0, 0, 0, 0, null), MediaLimits.DEFAULT));
            assertThrows(MediaException.Malformed.class,
                    () -> OpusHeader.parse(head(2, 0, 0, 0, 1, new int[]{0, 0, 0, 1}), MediaLimits.DEFAULT));
            assertThrows(MediaException.Malformed.class,
                    () -> OpusHeader.parse(head(2, 0, 0, 0, 1, new int[]{1, 2, 0, 1}), MediaLimits.DEFAULT));
            assertThrows(MediaException.Malformed.class,
                    () -> OpusHeader.parse(head(2, 0, 0, 0, 1, new int[]{1, 0, 0, 1}), MediaLimits.DEFAULT));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> OpusHeader.parse(head(2, 0, 0, 0, 0, null), MediaLimits.builder().maxChannels(1).build()));
            OpusHeader.parse(head(2, 0, 0, 0, 1, new int[]{1, 0, 0, 255}), MediaLimits.DEFAULT);
            OpusHeader.parse(head(10, 0, 0, 0, 1, new int[]{10, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9}),
                    MediaLimits.DEFAULT);
            // Family 3 carries a demixing matrix, not a mapping table: four entries are a truncated matrix.
            assertThrows(MediaException.Malformed.class,
                    () -> OpusHeader.parse(head(4, 0, 0, 0, 3, new int[]{2, 2, 0, 1, 2, 3}), MediaLimits.DEFAULT));
            var matrix = new int[2 + 32];
            matrix[0] = 2;
            matrix[1] = 2;
            Arrays.fill(matrix, 2, matrix.length, 0xFF);
            OpusHeader.parse(head(4, 0, 0, 0, 3, matrix), MediaLimits.DEFAULT);
        }

        @Test
        @DisplayName("reads the demixing matrix of family 3 instead of a mapping table")
        void family3() {
            // Two output channels from one coupled stream and one mono stream: a 2 x 3 matrix, column-major.
            int[] coefficients = {32767, -32768, 0, 1, -1, 16384};
            var table = new int[2 + 12];
            table[0] = 2;
            table[1] = 1;
            for (var i = 0; i < coefficients.length; i++) {
                table[2 + 2 * i] = coefficients[i] & 0xFF;
                table[3 + 2 * i] = (coefficients[i] >> 8) & 0xFF;
            }
            var header = OpusHeader.parse(head(2, 312, 48000, 3050, 3, table), MediaLimits.DEFAULT);
            assertEquals(3, header.family());
            assertEquals(2, header.streamCount());
            assertEquals(1, header.coupledCount());
            assertEquals(12, header.mapping().length);
            assertEquals(12, OpusHeader.tableSize(3, 2, 2, 1));
            assertEquals(5, OpusHeader.tableSize(1, 5, 3, 2));
            for (var column = 0; column < 3; column++) {
                for (var row = 0; row < 2; row++) {
                    assertEquals(coefficients[column * 2 + row], header.demixing(row, column));
                }
            }
            assertThrows(IllegalStateException.class, () -> header.mapping(0));
            var surround = OpusHeader.parse(head(6, 0, 48000, 0, 1, new int[]{4, 2, 0, 4, 1, 2, 3, 5}),
                    MediaLimits.DEFAULT);
            assertThrows(IllegalStateException.class, () -> surround.demixing(0, 0));
            var truncated = Arrays.copyOf(head(2, 312, 48000, 3050, 3, table), 19 + 13);
            var error = assertThrows(MediaException.Malformed.class, () -> OpusHeader.parseLenient(truncated));
            assertEquals("OpusHead channel mapping truncated", error.getMessage());
            assertThrows(MediaException.Malformed.class,
                    () -> OpusHeader.parseLenient(Arrays.copyOf(head(2, 0, 0, 0, 3, table), 20)));
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("lenient parsing keeps the exceptions and fields of the former AudioConfigs.parseOpusHead")
        void lenientPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 4000; trial++) {
                var data = mutate(head(random.nextInt(4), random.nextInt(65536), random.nextInt(),
                        random.nextInt(65536) - 32768, random.nextInt(3) == 0 ? 0 : random.nextInt(4), null), random);
                var expected = formerParseOpusHead(data);
                if (expected instanceof RuntimeException failure) {
                    var actual = assertThrows(RuntimeException.class, () -> OpusHeader.parseLenient(data));
                    assertEquals(failure.getClass(), actual.getClass());
                    assertEquals(failure.getMessage(), actual.getMessage());
                } else {
                    var fields = (long[]) expected;
                    var header = OpusHeader.parseLenient(data);
                    assertEquals(fields[0], header.channels());
                    assertEquals(fields[1], header.preSkip());
                    assertEquals(fields[2], header.inputSampleRate());
                    assertEquals(fields[3], header.outputGain());
                    assertEquals(fields[4], header.family());
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {5, 6})
        @DisplayName("strict parsing accepts and rejects the headers the former OpusMapping.parseHead did")
        void strictPin(int seed) {
            var random = new Random(seed);
            var limits = MediaLimits.DEFAULT;
            for (var trial = 0; trial < 4000; trial++) {
                var channels = 1 + random.nextInt(9);
                var family = random.nextInt(3) == 0 ? 0 : 1;
                var table = new int[2 + channels];
                table[0] = random.nextInt(6);
                table[1] = random.nextInt(4);
                for (var i = 0; i < channels; i++) {
                    table[2 + i] = random.nextInt(12) == 0 ? 255 : random.nextInt(10);
                }
                var data = head(channels, 0, 48000, 0, family, family == 0 ? null : table);
                var accepted = formerOpusMappingAccepts(data, limits);
                if (accepted) {
                    OpusHeader.parse(data, limits);
                } else {
                    assertThrows(MediaException.class, () -> OpusHeader.parse(data, limits));
                }
            }
        }

        private static byte[] head(int channels, int preSkip, long rate, int gain, int family, int[] table) {
            var data = new byte[19 + (table == null ? 0 : table.length)];
            System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 8);
            data[8] = 1;
            data[9] = (byte) channels;
            data[10] = (byte) preSkip;
            data[11] = (byte) (preSkip >> 8);
            for (var i = 0; i < 4; i++) {
                data[12 + i] = (byte) (rate >> (8 * i));
            }
            data[16] = (byte) gain;
            data[17] = (byte) (gain >> 8);
            data[18] = (byte) family;
            if (table != null) {
                for (var i = 0; i < table.length; i++) {
                    data[19 + i] = (byte) table[i];
                }
            }
            return data;
        }

        private static byte[] mutate(byte[] data, Random random) {
            return switch (random.nextInt(6)) {
                case 0 -> Arrays.copyOf(data, random.nextInt(data.length + 4));
                case 1 -> {
                    var copy = data.clone();
                    copy[random.nextInt(9)] ^= (byte) (1 << random.nextInt(8));
                    yield copy;
                }
                case 2 -> {
                    var copy = Arrays.copyOf(data, data.length + random.nextInt(12));
                    copy[18] = (byte) (1 + random.nextInt(255));
                    yield copy;
                }
                default -> data;
            };
        }

        /**
         * The former {@code AudioConfigs.parseOpusHead}, with the family 3 table size of RFC 8486: its fields, or
         * the exception it threw.
         */
        private static Object formerParseOpusHead(byte[] head) {
            if (head.length < 19 || !Arrays.equals(head, 0, 8, "OpusHead".getBytes(StandardCharsets.US_ASCII), 0, 8)) {
                return new MediaException.Malformed("invalid OpusHead");
            }
            var version = head[8] & 0xFF;
            if (version >> 4 != 0) {
                return new MediaException.Unsupported("opus", "unsupported OpusHead version " + version);
            }
            var channels = head[9] & 0xFF;
            var preSkip = (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
            var inputRate = (head[12] & 0xFFL) | (head[13] & 0xFFL) << 8 | (head[14] & 0xFFL) << 16
                    | (head[15] & 0xFFL) << 24;
            var gain = (short) ((head[16] & 0xFF) | (head[17] & 0xFF) << 8);
            var family = head[18] & 0xFF;
            // Family 3 (RFC 8486) holds a demixing matrix of 2 * channels * (streams + coupled) bytes.
            if (family != 0 && (head.length < 21 || head.length < 21 + (family == 3
                    ? 2 * channels * ((head[19] & 0xFF) + (head[20] & 0xFF)) : channels))) {
                return new MediaException.Malformed("OpusHead channel mapping truncated");
            }
            if (channels == 0) {
                return new MediaException.Malformed("OpusHead declares zero channels");
            }
            return new long[]{channels, preSkip, inputRate, gain, family};
        }

        /** Whether the former {@code OpusMapping.parseHead} accepted a well-formed header. */
        private static boolean formerOpusMappingAccepts(byte[] packet, MediaLimits limits) {
            var channels = packet[9] & 0xFF;
            try {
                LimitChecks.checkChannels(limits, channels);
            } catch (MediaException exception) {
                return false;
            }
            var family = packet[18] & 0xFF;
            if (family == 0) {
                return channels <= 2;
            }
            if (packet.length < 21 + channels) {
                return false;
            }
            var streams = packet[19] & 0xFF;
            var coupled = packet[20] & 0xFF;
            if (streams == 0 || coupled > streams || streams + coupled > 255) {
                return false;
            }
            for (var i = 0; i < channels; i++) {
                var mapping = packet[21 + i] & 0xFF;
                if (mapping != 255 && mapping >= streams + coupled) {
                    return false;
                }
            }
            return true;
        }
    }

    @Nested
    @DisplayName("VorbisChannelOrder")
    class ChannelOrder {
        @Test
        @DisplayName("equals the tables of the Vorbis decoder, encoder and Opus header for one to eight channels")
        void table() {
            int[][] former = {null, {0}, {0, 1}, {0, 2, 1}, {0, 1, 2, 3}, {0, 2, 1, 3, 4}, {0, 2, 1, 4, 5, 3},
                    {0, 2, 1, 5, 6, 4, 3}, {0, 2, 1, 6, 7, 4, 5, 3}};
            for (var channels = 1; channels <= 8; channels++) {
                assertArrayEquals(former[channels], VorbisChannelOrder.toWave(channels));
                for (var index = 0; index < channels; index++) {
                    assertEquals(former[channels][index], VorbisChannelOrder.waveIndex(channels, index));
                }
            }
            assertNull(VorbisChannelOrder.toWave(0));
            assertNull(VorbisChannelOrder.toWave(9));
            assertThrows(IndexOutOfBoundsException.class, () -> VorbisChannelOrder.waveIndex(9, 0));
            VorbisChannelOrder.toWave(3)[0] = 7;
            assertEquals(0, VorbisChannelOrder.waveIndex(3, 0));
        }
    }

    @Nested
    @DisplayName("XiphLacing")
    class Lacing {
        @Test
        @DisplayName("laces as OggMapping.xiphLace did, sizes that are multiples of 255 included")
        void lace() {
            var random = new Random(9);
            for (var trial = 0; trial < 200; trial++) {
                var packets = new ArrayList<byte[]>();
                var count = 1 + random.nextInt(4);
                for (var i = 0; i < count; i++) {
                    var size = random.nextInt(4) == 0 ? 255 * random.nextInt(4) : random.nextInt(800);
                    var packet = new byte[size];
                    random.nextBytes(packet);
                    packets.add(packet);
                }
                var laced = XiphLacing.lace(packets);
                assertArrayEquals(formerXiphLace(packets.toArray(byte[][]::new)), laced);
                var unlaced = XiphLacing.unlace(laced);
                assertEquals(count, unlaced.length);
                for (var i = 0; i < count; i++) {
                    assertArrayEquals(packets.get(i), unlaced[i]);
                }
            }
        }

        @Test
        @DisplayName("returns null where the former unlacers threw, and splits what they split")
        void unlacePin() {
            var random = new Random(10);
            for (var trial = 0; trial < 5000; trial++) {
                var data = new byte[random.nextInt(12)];
                random.nextBytes(data);
                if (data.length > 0) {
                    data[0] = 2;
                }
                if (data.length > 1 && random.nextBoolean()) {
                    data[1] = (byte) random.nextInt(4);
                }
                if (data.length > 2 && random.nextBoolean()) {
                    data[2] = (byte) random.nextInt(4);
                }
                var former = formerUnlace(data);
                var shared = XiphLacing.unlace(data);
                if (former == null) {
                    assertTrue(shared == null || shared.length != 3);
                } else {
                    assertEquals(3, shared.length);
                    for (var i = 0; i < 3; i++) {
                        assertArrayEquals(former[i], shared[i]);
                    }
                }
            }
            assertNull(XiphLacing.unlace(new byte[0]));
            assertNull(XiphLacing.unlace(new byte[]{1, (byte) 255}));
            assertNull(XiphLacing.unlace(new byte[]{1, 5, 1}));
            assertThrows(IllegalArgumentException.class, () -> XiphLacing.lace(List.of()));
        }

        /** The former {@code OggMapping.xiphLace}. */
        private static byte[] formerXiphLace(byte[][] packets) {
            var size = 1;
            for (var i = 0; i < packets.length; i++) {
                size += packets[i].length;
                if (i < packets.length - 1) {
                    size += packets[i].length / 255 + 1;
                }
            }
            var result = new byte[size];
            result[0] = (byte) (packets.length - 1);
            var offset = 1;
            for (var i = 0; i < packets.length - 1; i++) {
                var length = packets[i].length;
                while (length >= 255) {
                    result[offset++] = (byte) 255;
                    length -= 255;
                }
                result[offset++] = (byte) length;
            }
            for (var packet : packets) {
                System.arraycopy(packet, 0, result, offset, packet.length);
                offset += packet.length;
            }
            return result;
        }

        /** The former {@code VorbisSetup.splitXiphLacing}, {@code null} where it threw. */
        private static byte[][] formerUnlace(byte[] config) {
            if (config.length == 0 || (config[0] & 0xFF) + 1 != 3) {
                return null;
            }
            var position = 1;
            var sizes = new long[3];
            for (var i = 0; i < 2; i++) {
                long size = 0;
                int lace;
                do {
                    if (position >= config.length) {
                        return null;
                    }
                    lace = config[position++] & 0xFF;
                    size += lace;
                } while (lace == 255);
                sizes[i] = size;
            }
            sizes[2] = config.length - position - sizes[0] - sizes[1];
            if (sizes[2] < 0) {
                return null;
            }
            var packets = new byte[3][];
            for (var i = 0; i < 3; i++) {
                packets[i] = Arrays.copyOfRange(config, position, position + (int) sizes[i]);
                position += packets[i].length;
            }
            return packets;
        }
    }

    @Nested
    @DisplayName("AdtsHeader")
    class Adts {
        @Test
        @DisplayName("writes the CRC-less header the TS and ADTS muxers wrote and parses it back")
        void writeAndParse() {
            var header = AdtsHeader.fromAudioSpecificConfig(new byte[]{0x12, 0x10});
            assertEquals(1, header.profile());
            assertEquals(4, header.frequencyIndex());
            assertEquals(2, header.channelConfiguration());
            assertEquals(44100, header.sampleRate());
            var frame = new byte[7 + 300];
            header.write(frame, 0, frame.length);
            assertArrayEquals(formerTsHeader(1, 4, 2, frame.length), Arrays.copyOf(frame, 7));
            var parsed = AdtsHeader.parse(frame, 0);
            assertEquals(new AdtsHeader(1, 4, 2, true, frame.length, 1), parsed);
            assertEquals(7, parsed.headerSize());
            assertArrayEquals(new byte[]{0x12, 0x10}, parsed.audioSpecificConfig());
        }

        @Test
        @DisplayName("rejects the configurations the TS and ADTS muxers rejected, with their messages")
        void configurationErrors() {
            assertMessage("an AAC track needs its AudioSpecificConfig", null);
            assertMessage("truncated AudioSpecificConfig", new byte[]{0x12});
            assertMessage("an explicit sampling rate cannot be expressed in ADTS", new byte[]{0x17, (byte) 0x80, 0, 0, 0});
            assertMessage("AAC object type 6 cannot be expressed in ADTS", new byte[]{0x32, 0x10});
            assertMessage("invalid sampling frequency index 13", new byte[]{0x16, (byte) 0x90});
            assertMessage("channel configuration 0 cannot be expressed in ADTS", new byte[]{0x12, 0x00});
            assertEquals(1, AdtsHeader.fromAudioSpecificConfig(new byte[]{0x2A, 0x10, 0x08, 0x00}).profile());
        }

        @Test
        @DisplayName("accepts and sizes headers as the TS demuxer's ADTS parser did")
        void parsePin() {
            var random = new Random(12);
            for (var trial = 0; trial < 20000; trial++) {
                var data = new byte[9];
                random.nextBytes(data);
                data[0] = (byte) 0xFF;
                data[1] = (byte) (0xF0 | random.nextInt(16));
                var header = AdtsHeader.parse(data, 0);
                var former = formerAdtsLength(data);
                if (former < 0) {
                    assertNull(header);
                } else {
                    assertEquals(former, header.frameLength());
                }
            }
            assertNull(AdtsHeader.parse(new byte[6], 0));
            assertThrows(IllegalArgumentException.class, () -> new AdtsHeader(1, 4, 2, true, 0, 1)
                    .write(new byte[7], 0, 8192));
        }

        private static void assertMessage(String message, byte[] config) {
            var failure = assertThrows(IllegalArgumentException.class, () -> AdtsHeader.fromAudioSpecificConfig(config));
            assertEquals(message, failure.getMessage());
        }

        /** The header the former {@code TsMuxer.audioFrame} wrote. */
        private static byte[] formerTsHeader(int profile, int frequency, int channels, int length) {
            return new byte[]{(byte) 0xFF, (byte) 0xF1, (byte) ((profile << 6) | (frequency << 2) | (channels >> 2)),
                    (byte) (((channels & 3) << 6) | (length >> 11)), (byte) (length >> 3),
                    (byte) (((length & 7) << 5) | 0x1F), (byte) 0xFC};
        }

        /** The frame length the former {@code AdtsParser} accepted, or -1 for a header it rejected. */
        private static int formerAdtsLength(byte[] data) {
            if ((data[1] & 0xF6) != 0xF0) {
                return -1;
            }
            var frequency = (data[2] >> 2) & 0x0F;
            if (frequency >= 13) {
                return -1;
            }
            var length = ((data[3] & 3) << 11) | ((data[4] & 0xFF) << 3) | ((data[5] & 0xFF) >> 5);
            int header;
            if ((data[1] & 1) != 0) {
                header = 7;
            } else {
                var blocks = (data[6] & 3) + 1;
                header = blocks == 1 ? 9 : 7 + 2 * blocks;
            }
            return length > header ? length : -1;
        }
    }

    @Nested
    @DisplayName("AacRates")
    class Rates {
        @Test
        @DisplayName("maps the thirteen rates of ISO/IEC 14496-3 Table 1.18 both ways")
        void rates() {
            int[] table = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
            for (var index = 0; index < table.length; index++) {
                assertEquals(table[index], AacRates.rate(index));
                assertEquals(index, AacRates.index(table[index]));
            }
            for (var index : new int[]{-1, 13, 14, 15, 16}) {
                assertEquals(0, AacRates.rate(index));
            }
            assertEquals(-1, AacRates.index(44000));
            assertEquals(-1, AacRates.index(0));
        }
    }

    @Nested
    @DisplayName("FlacStreamInfo")
    class StreamInfo {
        @Test
        @DisplayName("compares MD5 signatures by content and reads fields without the parse checks")
        void equality() {
            var body = new byte[FlacStreamInfo.SIZE];
            body[1] = 16;
            body[3] = 16;
            body[10] = 0x0A;
            body[11] = (byte) 0xC4;
            body[12] = 0x42;
            body[13] = (byte) 0xF0;
            body[33] = 7;
            var first = FlacStreamInfo.parse(body, 0);
            var second = FlacStreamInfo.parse(body.clone(), 0);
            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertTrue(first.toString().contains("md5=00000000000000000000000000000007"));
            assertEquals(44100, first.sampleRate());
            assertEquals(2, first.channels());
            assertEquals(16, first.bitsPerSample());
            assertTrue(first.hasMd5());
            var other = body.clone();
            other[33] = 8;
            assertNotEquals(first, FlacStreamInfo.parse(other, 0));
            var invalid = new byte[FlacStreamInfo.SIZE];
            assertThrows(MediaException.Malformed.class, () -> FlacStreamInfo.parse(invalid, 0));
            var raw = FlacStreamInfo.read(invalid, 0);
            assertEquals(0, raw.sampleRate());
            assertFalse(raw.hasMd5());
            assertThrows(IndexOutOfBoundsException.class, () -> FlacStreamInfo.read(new byte[33], 0));
        }
    }
}
