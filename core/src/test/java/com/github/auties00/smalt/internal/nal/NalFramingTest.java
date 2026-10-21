package com.github.auties00.smalt.internal.nal;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared NAL unit framing and the configuration record parsers. The former private
 * copies in the containers, the probe and the H.264 and HEVC decoders are kept here as references,
 * and the shared helpers are pinned against them on random input biased towards the bytes that
 * matter (zeros, one, three).
 */
class NalFramingTest {
    private static byte[] biased(Random random, int length) {
        var data = new byte[length];
        for (var i = 0; i < length; i++) {
            data[i] = switch (random.nextInt(6)) {
                case 0, 1 -> 0;
                case 2 -> 1;
                case 3 -> 3;
                default -> (byte) random.nextInt(256);
            };
        }
        return data;
    }

    @Nested
    @DisplayName("unescaping")
    class Unescaping {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("removes the same bytes as the zero-counting unescape of the probe and the TS parser")
        void zeroCounterPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 5000; trial++) {
                var data = biased(random, random.nextInt(40));
                var offset = data.length == 0 ? 0 : random.nextInt(data.length + 1);
                var length = data.length - offset;
                assertArrayEquals(formerZeroCounter(data, offset, length), NalUnits.unescape(data, offset, length));
            }
        }

        @Test
        @DisplayName("records the escaped offsets of the removed bytes as the HEVC decoder did")
        void removedPositions() {
            var data = new byte[]{0x40, 0, 0, 3, 1, 0, 0, 3, 0, 0, 3};
            var out = new byte[data.length];
            var removed = new int[data.length / 3 + 1];
            var result = NalUnits.unescape(data, 1, data.length - 1, out, removed);
            assertEquals(7, (int) result);
            assertEquals(3, (int) (result >>> 32));
            assertArrayEquals(new int[]{2, 6, 9}, Arrays.copyOf(removed, 3));
            assertArrayEquals(new byte[]{0, 0, 1, 0, 0, 0, 0}, Arrays.copyOf(out, 7));
            assertEquals(7, NalUnits.unescape(data, 1, data.length - 1, out));
        }

        @Test
        @DisplayName("finds the stop bit of a payload")
        void stopBit() {
            assertEquals(13, NalUnits.stopBitPosition(new byte[]{0x55, 0x04, 0}, 0, 3));
            assertEquals(24, NalUnits.stopBitPosition(new byte[3], 0, 3));
        }

        /** The former {@code VideoConfigs.unescape} and {@code NalParser.unescape}. */
        private static byte[] formerZeroCounter(byte[] data, int offset, int length) {
            var out = new byte[Math.max(0, length)];
            var size = 0;
            var zeros = 0;
            for (var index = offset; index < offset + length; index++) {
                var value = data[index];
                if (zeros >= 2 && value == 3) {
                    zeros = 0;
                    continue;
                }
                out[size++] = value;
                zeros = value == 0 ? zeros + 1 : 0;
            }
            return Arrays.copyOf(out, size);
        }
    }

    @Nested
    @DisplayName("Annex B and length-prefixed walks")
    class Walks {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {4, 5})
        @DisplayName("splits an Annex B stream as the decoders' feed loops did")
        void annexBPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 3000; trial++) {
                var data = biased(random, random.nextInt(60));
                var expected = new ArrayList<String>();
                var position = NalUnits.findStartCode(data, 0, data.length);
                while (position >= 0) {
                    var start = position + 3;
                    var next = NalUnits.findStartCode(data, start, data.length);
                    var end = next < 0 ? data.length : next;
                    while (end > start && data[end - 1] == 0) {
                        end--;
                    }
                    if (end > start) {
                        expected.add(start + ":" + (end - start));
                    }
                    position = next;
                }
                var actual = new ArrayList<String>();
                NalUnits.forEachAnnexB(data, (array, offset, length) -> actual.add(offset + ":" + length));
                assertEquals(expected, actual);
                assertEquals(formerFindStartCode(data), NalUnits.findStartCode(data, 0, data.length));
            }
        }

        @Test
        @DisplayName("detects an Annex B stream behind leading zeros")
        void looksLikeAnnexB() {
            assertTrue(NalUnits.looksLikeAnnexB(new byte[]{0, 0, 1, 0x65}));
            assertTrue(NalUnits.looksLikeAnnexB(new byte[]{0, 0, 0, 0, 1}));
            assertFalse(NalUnits.looksLikeAnnexB(new byte[]{0, 1, 0x65}));
            assertFalse(NalUnits.looksLikeAnnexB(new byte[]{0, 0, 2}));
            assertFalse(NalUnits.looksLikeAnnexB(new byte[]{0, 0, 0}));
        }

        @ParameterizedTest(name = "length size {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("accepts the access units the decoders accepted and reports where the framing breaks")
        void lengthPrefixedPin(int lengthSize) {
            var random = new Random(lengthSize);
            for (var trial = 0; trial < 3000; trial++) {
                var data = new byte[random.nextInt(30)];
                random.nextBytes(data);
                for (var i = 0; i < data.length; i += 1 + random.nextInt(5)) {
                    data[i] = (byte) random.nextInt(6);
                }
                var formerUnits = new ArrayList<String>();
                var formerResult = formerFeed(data, lengthSize, formerUnits);
                var units = new ArrayList<String>();
                var stop = NalUnits.forEachLengthPrefixed(data, lengthSize, (array, offset, length) ->
                        units.add(offset + ":" + length));
                assertEquals(formerUnits, units);
                assertEquals(formerResult == null, stop == data.length);
                assertEquals(stop == data.length, NalUnits.isLengthPrefixed(data, lengthSize));
                if (formerResult != null) {
                    var truncated = stop + lengthSize > data.length;
                    assertEquals(formerResult.startsWith("truncated"), truncated);
                }
            }
        }

        /** The former {@code H264Nal.findStartCode} and {@code HevcNal.findStartCode}. */
        private static int formerFindStartCode(byte[] data) {
            for (var i = 0; i + 2 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * The former {@code feedLengthPrefixed} of the H.264 and HEVC decoders: the units it fed, and
         * the message of the error it threw, or {@code null}.
         */
        private static String formerFeed(byte[] data, int lengthSize, List<String> units) {
            var position = 0;
            while (position < data.length) {
                if (position + lengthSize > data.length) {
                    return "truncated NAL unit length prefix";
                }
                long length = 0;
                for (var i = 0; i < lengthSize; i++) {
                    length = (length << 8) | (data[position + i] & 0xFF);
                }
                position += lengthSize;
                if (length > data.length - position) {
                    return "NAL unit length " + length + " exceeds the packet";
                }
                units.add(position + ":" + length);
                position += (int) length;
            }
            return null;
        }
    }

    @Nested
    @DisplayName("Annex B conversion")
    class Conversion {
        private static final byte[] SETS = {0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2};
        private static final byte[] AVC_DELIMITER = {0, 0, 0, 1, 0x09, (byte) 0xF0};
        private static final byte[] HEVC_DELIMITER = {0, 0, 0, 1, 0x46, 0x01, 0x50};

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {6, 7})
        @DisplayName("converts as the TS muxer did, with delimiters and parameter sets")
        void tsPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 2000; trial++) {
                var hevc = random.nextBoolean();
                var keyframe = random.nextBoolean();
                var lengthSize = 1 + random.nextInt(4);
                var data = accessUnit(random, lengthSize, hevc);
                var expected = formerTs(data, lengthSize, hevc, keyframe ? SETS : null, hevc ? HEVC_DELIMITER : AVC_DELIMITER);
                assertArrayEquals(expected, NalUnits.toAnnexB(data, lengthSize, hevc, keyframe ? SETS : null,
                        hevc ? HEVC_DELIMITER : AVC_DELIMITER));
                var raw = formerTs(data, lengthSize, hevc, keyframe ? SETS : null, null);
                assertArrayEquals(raw, NalUnits.toAnnexB(data, lengthSize, hevc, keyframe ? SETS : null, null));
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {8, 9})
        @DisplayName("converts Annex B back to four-byte length prefixes, unit for unit")
        void lengthPrefixedRoundTrip(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 2000; trial++) {
                var hevc = random.nextBoolean();
                // Escaped units never hold a start code and never end in a zero byte.
                var data = accessUnit(random, 4, hevc);
                for (var offset = 0; offset < data.length; ) {
                    var length = (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16
                            | (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
                    for (var index = offset + 4; index < offset + 4 + length; index++) {
                        if (data[index] == 0) {
                            data[index] = (byte) (1 + random.nextInt(255));
                        }
                    }
                    offset += 4 + length;
                }
                var expected = new ByteArrayOutputStream();
                NalUnits.forEachLengthPrefixed(data, 4, (unit, offset, length) -> {
                    if (length > 0) {
                        expected.write(length >>> 24);
                        expected.write(length >>> 16);
                        expected.write(length >>> 8);
                        expected.write(length);
                        expected.write(unit, offset, length);
                    }
                });
                var annexB = NalUnits.toAnnexB(data, 4, hevc, null, null);
                var trailing = random.nextBoolean() ? new byte[]{0, 0} : new byte[0];
                var padded = java.util.Arrays.copyOf(annexB, annexB.length + trailing.length);
                assertArrayEquals(expected.toByteArray(), NalUnits.toLengthPrefixed(padded));
            }
            assertEquals(0, NalUnits.toLengthPrefixed(new byte[]{1, 2, 3}).length);
            assertArrayEquals(new byte[]{0, 0, 0, 2, 0x65, 7, 0, 0, 0, 1, 0x41},
                    NalUnits.toLengthPrefixed(new byte[]{9, 0, 0, 1, 0x65, 7, 0, 0, 0, 0, 1, 0x41}));
        }

        @Test
        @DisplayName("rejects broken framing with the muxers' messages")
        void errors() {
            var truncated = assertThrows(IllegalArgumentException.class,
                    () -> NalUnits.toAnnexB(new byte[]{0, 0, 0}, 4, false, null, null));
            assertEquals("truncated NAL unit length", truncated.getMessage());
            var overrun = assertThrows(IllegalArgumentException.class,
                    () -> NalUnits.toAnnexB(new byte[]{0, 5, 0x65}, 2, false, null, null));
            assertEquals("NAL unit of 5 bytes overruns its packet", overrun.getMessage());
        }

        @Test
        @DisplayName("lays out parameter sets with four-byte start codes as the AVI and TS muxers did")
        void parameterSets() {
            var sets = List.of(new byte[]{0x67, 1}, new byte[]{0x68, 2});
            assertArrayEquals(SETS, NalUnits.annexBParameterSets(sets));
            assertEquals(0, NalUnits.annexBParameterSets(List.of()).length);
        }

        private static byte[] accessUnit(Random random, int lengthSize, boolean hevc) {
            var out = new ByteArrayOutputStream();
            var units = random.nextInt(5);
            for (var i = 0; i < units; i++) {
                var length = random.nextInt(6) == 0 ? 0 : 1 + random.nextInt(20);
                for (var b = lengthSize - 1; b >= 0; b--) {
                    out.write(length >> (8 * b));
                }
                for (var j = 0; j < length; j++) {
                    if (j == 0) {
                        var type = switch (random.nextInt(5)) {
                            case 0 -> hevc ? 35 : 9;
                            case 1 -> hevc ? 32 + random.nextInt(3) : 7 + random.nextInt(2);
                            default -> hevc ? random.nextInt(32) : 1 + random.nextInt(6);
                        };
                        out.write(hevc ? type << 1 : type);
                    } else {
                        out.write(random.nextInt(256));
                    }
                }
            }
            return out.toByteArray();
        }

        /** The former {@code TsMuxer.annexB}, and {@code RawMuxer.writeAnnexB} without a delimiter. */
        private static byte[] formerTs(byte[] data, int lengthSize, boolean hevc, byte[] parameterSets, byte[] delimiter) {
            var units = new ArrayList<int[]>();
            var hasDelimiter = false;
            var hasParameterSets = false;
            for (var offset = 0; offset < data.length; ) {
                long length = 0;
                for (var index = 0; index < lengthSize; index++) {
                    length = (length << 8) | (data[offset + index] & 0xFF);
                }
                var start = offset + lengthSize;
                if (length > 0) {
                    var type = hevc ? (data[start] >> 1) & 0x3F : data[start] & 0x1F;
                    if (units.isEmpty() && type == (hevc ? 35 : 9)) {
                        hasDelimiter = true;
                    }
                    hasParameterSets |= hevc ? type >= 32 && type <= 34 : type == 7 || type == 8;
                    units.add(new int[]{start, (int) length});
                }
                offset = start + (int) length;
            }
            var out = new ByteArrayOutputStream();
            var start = new byte[]{0, 0, 0, 1};
            var index = 0;
            if (delimiter != null) {
                if (hasDelimiter) {
                    out.writeBytes(start);
                    out.write(data, units.getFirst()[0], units.getFirst()[1]);
                    index = 1;
                } else {
                    out.writeBytes(delimiter);
                }
            }
            if (parameterSets != null && !hasParameterSets) {
                out.writeBytes(parameterSets);
            }
            for (; index < units.size(); index++) {
                out.writeBytes(start);
                out.write(data, units.get(index)[0], units.get(index)[1]);
            }
            return out.toByteArray();
        }
    }

    @Nested
    @DisplayName("configuration records")
    class Records {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {8, 9})
        @DisplayName("strict and lenient avcC parsing keep the former decoder and container semantics")
        void avcPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 4000; trial++) {
                var record = avcRecord(random);
                var lenient = NalRecords.parseAvc(record);
                var formerLenient = formerParseAvc(record);
                assertEquals(formerLenient == null, lenient == null);
                if (lenient != null) {
                    assertEquals(formerLenient.lengthSize(), lenient.lengthSize());
                    assertSetsEqual(formerLenient.parameterSets(), lenient.parameterSets());
                }
                Object former;
                try {
                    former = formerStrictAvc(record);
                } catch (MediaException.Malformed exception) {
                    former = exception.getMessage();
                }
                if (former instanceof String message) {
                    var failure = assertThrows(MediaException.Malformed.class, () -> NalRecords.parseAvcStrict(record));
                    assertEquals(message, failure.getMessage());
                } else {
                    var parsed = NalRecords.parseAvcStrict(record);
                    var expected = (NalRecords.Parsed) former;
                    assertEquals(expected.lengthSize(), parsed.lengthSize());
                    assertSetsEqual(expected.parameterSets(), parsed.parameterSets());
                }
            }
            assertNull(NalRecords.parseAvc(null));
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {10, 11})
        @DisplayName("strict and lenient hvcC parsing keep the former decoder and container semantics")
        void hevcPin(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 4000; trial++) {
                var record = hevcRecord(random);
                var lenient = NalRecords.parseHevc(record);
                var formerLenient = formerParseHevc(record);
                assertEquals(formerLenient == null, lenient == null);
                if (lenient != null) {
                    assertEquals(formerLenient.lengthSize(), lenient.lengthSize());
                    assertSetsEqual(formerLenient.parameterSets(), lenient.parameterSets());
                }
                Object former;
                try {
                    former = formerStrictHevc(record);
                } catch (MediaException.Malformed exception) {
                    former = exception.getMessage();
                }
                if (former instanceof String message) {
                    var failure = assertThrows(MediaException.Malformed.class, () -> NalRecords.parseHevcStrict(record));
                    assertEquals(message, failure.getMessage());
                } else {
                    var parsed = NalRecords.parseHevcStrict(record);
                    var expected = (NalRecords.Parsed) former;
                    assertEquals(expected.lengthSize(), parsed.lengthSize());
                    assertSetsEqual(expected.parameterSets(), parsed.parameterSets());
                }
            }
            assertFalse(NalRecords.looksLikeHevc(new byte[23]));
            var minimal = new byte[23];
            minimal[0] = 1;
            assertTrue(NalRecords.looksLikeHevc(minimal));
        }

        private static void assertSetsEqual(List<byte[]> expected, List<byte[]> actual) {
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i));
            }
        }

        private static byte[] avcRecord(Random random) {
            var out = new ByteArrayOutputStream();
            out.write(random.nextInt(8) == 0 ? 2 : 1);
            out.write(100);
            out.write(0);
            out.write(31);
            out.write(0xFC | random.nextInt(4));
            var sps = random.nextInt(3);
            out.write(0xE0 | sps);
            for (var i = 0; i < sps; i++) {
                unit(out, random);
            }
            if (random.nextInt(4) != 0) {
                var pps = random.nextInt(3);
                out.write(pps);
                for (var i = 0; i < pps; i++) {
                    unit(out, random);
                }
            }
            var bytes = out.toByteArray();
            return random.nextInt(3) == 0 ? Arrays.copyOf(bytes, random.nextInt(bytes.length + 1)) : bytes;
        }

        private static byte[] hevcRecord(Random random) {
            var out = new ByteArrayOutputStream();
            out.write(random.nextInt(8) == 0 ? 0 : 1);
            out.writeBytes(new byte[20]);
            out.write(0xF0 | random.nextInt(4));
            var arrays = random.nextInt(4);
            out.write(arrays);
            for (var a = 0; a < arrays; a++) {
                out.write(0x80 | (32 + a));
                var count = random.nextInt(3);
                out.write(0);
                out.write(count);
                for (var i = 0; i < count; i++) {
                    unit(out, random);
                }
            }
            var bytes = out.toByteArray();
            return random.nextInt(3) == 0 ? Arrays.copyOf(bytes, random.nextInt(bytes.length + 1)) : bytes;
        }

        private static void unit(ByteArrayOutputStream out, Random random) {
            var length = random.nextInt(5) == 0 ? 0 : 1 + random.nextInt(6);
            out.write(length >> 8);
            out.write(length);
            for (var i = 0; i < length; i++) {
                out.write(random.nextInt(256));
            }
        }

        /** The former {@code NalConfigs.parseAvc}. */
        private static NalRecords.Parsed formerParseAvc(byte[] config) {
            if (config == null || config.length < 7 || config[0] != 1) {
                return null;
            }
            var sets = new ArrayList<byte[]>();
            var position = 5;
            for (var group = 0; group < 2; group++) {
                if (position >= config.length) {
                    return group == 1 ? new NalRecords.Parsed((config[4] & 3) + 1, List.copyOf(sets)) : null;
                }
                var count = group == 0 ? config[position] & 0x1F : config[position] & 0xFF;
                position++;
                for (var index = 0; index < count; index++) {
                    if (position + 2 > config.length) {
                        return null;
                    }
                    var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                    position += 2;
                    if (position + length > config.length) {
                        return null;
                    }
                    if (length > 0) {
                        sets.add(Arrays.copyOfRange(config, position, position + length));
                    }
                    position += length;
                }
            }
            return new NalRecords.Parsed((config[4] & 3) + 1, List.copyOf(sets));
        }

        /** The former {@code H264AvcConfig.parse}. */
        private static NalRecords.Parsed formerStrictAvc(byte[] data) {
            if (data.length < 7 || data[0] != 1) {
                throw new MediaException.Malformed("invalid AVCDecoderConfigurationRecord");
            }
            var lengthSize = (data[4] & 3) + 1;
            if (lengthSize == 3) {
                throw new MediaException.Malformed("invalid NAL unit length size 3");
            }
            var sets = new ArrayList<byte[]>();
            var position = 5;
            var spsCount = data[position++] & 0x1F;
            position = readSets(data, position, spsCount, sets);
            if (position < data.length) {
                var ppsCount = data[position++] & 0xFF;
                readSets(data, position, ppsCount, sets);
            }
            return new NalRecords.Parsed(lengthSize, List.copyOf(sets));
        }

        private static int readSets(byte[] data, int position, int count, List<byte[]> sets) {
            for (var i = 0; i < count; i++) {
                if (position + 2 > data.length) {
                    throw new MediaException.Malformed("truncated AVCDecoderConfigurationRecord");
                }
                var length = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                position += 2;
                if (position + length > data.length) {
                    throw new MediaException.Malformed("truncated AVCDecoderConfigurationRecord");
                }
                if (length > 0) {
                    sets.add(Arrays.copyOfRange(data, position, position + length));
                }
                position += length;
            }
            return position;
        }

        /** The former {@code NalConfigs.parseHevc}. */
        private static NalRecords.Parsed formerParseHevc(byte[] config) {
            if (config == null || config.length < 23) {
                return null;
            }
            var sets = new ArrayList<byte[]>();
            var arrays = config[22] & 0xFF;
            var position = 23;
            for (var array = 0; array < arrays; array++) {
                if (position + 3 > config.length) {
                    return null;
                }
                var count = ((config[position + 1] & 0xFF) << 8) | (config[position + 2] & 0xFF);
                position += 3;
                for (var index = 0; index < count; index++) {
                    if (position + 2 > config.length) {
                        return null;
                    }
                    var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                    position += 2;
                    if (position + length > config.length) {
                        return null;
                    }
                    if (length > 0) {
                        sets.add(Arrays.copyOfRange(config, position, position + length));
                    }
                    position += length;
                }
            }
            return new NalRecords.Parsed((config[21] & 3) + 1, List.copyOf(sets));
        }

        /** The former {@code HevcConfig.parse}. */
        private static NalRecords.Parsed formerStrictHevc(byte[] config) {
            if (config.length < 23) {
                throw new MediaException.Malformed("truncated HEVCDecoderConfigurationRecord");
            }
            var lengthSize = (config[21] & 3) + 1;
            if (lengthSize == 3) {
                throw new MediaException.Malformed("invalid hvcC NAL unit length size 3");
            }
            var arrays = config[22] & 0xFF;
            var position = 23;
            var units = new ArrayList<byte[]>();
            for (var i = 0; i < arrays; i++) {
                if (position + 3 > config.length) {
                    throw new MediaException.Malformed("truncated hvcC array");
                }
                var count = ((config[position + 1] & 0xFF) << 8) | (config[position + 2] & 0xFF);
                position += 3;
                for (var j = 0; j < count; j++) {
                    if (position + 2 > config.length) {
                        throw new MediaException.Malformed("truncated hvcC NAL unit length");
                    }
                    var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                    position += 2;
                    if (length > config.length - position) {
                        throw new MediaException.Malformed("hvcC NAL unit exceeds the record");
                    }
                    units.add(Arrays.copyOfRange(config, position, position + length));
                    position += length;
                }
            }
            return new NalRecords.Parsed(lengthSize, List.copyOf(units));
        }
    }
}
