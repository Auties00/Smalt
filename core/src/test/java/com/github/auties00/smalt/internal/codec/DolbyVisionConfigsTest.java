package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.DolbyVision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Dolby Vision configuration record and transport stream descriptor: their exact bytes for the common
 * profiles, round trips of every field over its whole range, short and long inputs, the configuration box a profile
 * selects, and the field ranges and backward compatibility of {@link DolbyVision}.
 */
@DisplayName("Dolby Vision configurations")
class DolbyVisionConfigsTest {
    private static final HexFormat HEX = HexFormat.of();

    @ParameterizedTest(name = "profile {0}.{2} level {1}")
    @CsvSource({
            // profile 8.4 level 1: 8 << 9 | 1 << 3 | rpu | bl = 0x100D, compatibility 4 in the high nibble
            "8, 1, 4, 0100100d40",
            // profile 5 level 6: 5 << 9 | 6 << 3 | rpu | bl = 0x0A35
            "5, 6, 0, 01000a3500",
            // profile 10.1 level 9: 10 << 9 | 9 << 3 | rpu | bl = 0x144D
            "10, 9, 1, 0100144d10"})
    @DisplayName("writes the record and descriptor bytes of a single-layer stream")
    void bytes(int profile, int level, int compatibility, String expected) {
        var configuration = DolbyVision.of(profile, level, compatibility);
        var record = DolbyVisionConfigs.record(configuration);
        assertEquals(24, record.length);
        assertEquals(expected, HEX.formatHex(record, 0, 5));
        for (var index = 5; index < 24; index++) {
            assertEquals(0, record[index]);
        }
        var descriptor = DolbyVisionConfigs.descriptor(configuration, 0x100);
        assertEquals(5, descriptor.length);
        assertEquals(expected.substring(0, 9), HEX.formatHex(descriptor, 0, 5).substring(0, 9));
        // the descriptor sets its two reserved bits
        assertEquals((compatibility << 4) | 3, descriptor[4] & 0xFF);
        assertEquals(configuration, DolbyVisionConfigs.parseRecord(record));
        assertEquals(configuration, DolbyVisionConfigs.parseDescriptor(descriptor, 0, descriptor.length));
    }

    @Test
    @DisplayName("round trips every field through the record and the descriptor")
    void roundTrips() {
        var random = new SplittableRandom(7);
        for (var round = 0; round < 2000; round++) {
            var configuration = new DolbyVision(random.nextInt(256), random.nextInt(256), random.nextInt(128),
                    random.nextInt(64), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextInt(16), random.nextInt(4));
            assertEquals(configuration, DolbyVisionConfigs.parseRecord(DolbyVisionConfigs.record(configuration)));
            var pid = random.nextInt(0x2000);
            var descriptor = DolbyVisionConfigs.descriptor(configuration, pid);
            assertEquals(configuration.baseLayerPresent() ? 5 : 7, descriptor.length);
            assertEquals(configuration, DolbyVisionConfigs.parseDescriptor(descriptor, 0, descriptor.length));
            if (!configuration.baseLayerPresent()) {
                assertEquals(pid, (descriptor[4] & 0xFF) << 5 | (descriptor[5] & 0xFF) >>> 3);
            }
        }
    }

    @Test
    @DisplayName("names the base layer PID in the descriptor of an enhancement layer stream")
    void dependency() {
        var layer = new DolbyVision(1, 0, 7, 6, true, true, false, 6, 0);
        var descriptor = DolbyVisionConfigs.descriptor(layer, 0x1011);
        // profile 7 level 6 with RPU and enhancement layer: 0x0E36; PID 0x1011 in 13 bits, 3 reserved ones;
        // compatibility 6 and 2 reserved ones
        assertEquals("01000e36808f63", HEX.formatHex(descriptor));
        assertEquals(0x1011, (descriptor[4] & 0xFF) << 5 | (descriptor[5] & 0xFF) >>> 3);
    }

    @Test
    @DisplayName("reads short records and descriptors, and rejects ones without the flags")
    void lengths() {
        var record = DolbyVisionConfigs.record(DolbyVision.of(8, 4, 1));
        assertEquals(DolbyVision.of(8, 4, 1), DolbyVisionConfigs.parseRecord(record, 0, 5));
        assertNull(DolbyVisionConfigs.parseRecord(record, 0, 4));
        assertNull(DolbyVisionConfigs.parseRecord(null));
        var longer = new byte[40];
        System.arraycopy(record, 0, longer, 3, 24);
        assertEquals(DolbyVision.of(8, 4, 1), DolbyVisionConfigs.parseRecord(longer, 3, 30));
        var descriptor = DolbyVisionConfigs.descriptor(DolbyVision.of(8, 4, 1), 0);
        assertEquals(DolbyVision.of(8, 4, 0), DolbyVisionConfigs.parseDescriptor(descriptor, 0, 4));
        assertNull(DolbyVisionConfigs.parseDescriptor(descriptor, 0, 3));
        var layer = DolbyVisionConfigs.descriptor(new DolbyVision(1, 0, 7, 6, true, true, false, 6, 0), 0x40);
        assertEquals(0, DolbyVisionConfigs.parseDescriptor(layer, 0, 6).baseLayerCompatibility());
    }

    @ParameterizedTest(name = "profile {0}")
    @CsvSource({"0, dvcC", "4, dvcC", "5, dvcC", "7, dvcC", "8, dvvC", "9, dvvC", "10, dvvC", "11, dvwC", "20, dvwC",
            "127, dvwC"})
    @DisplayName("selects dvcC up to profile 7, dvvC for 8 to 10 and dvwC above")
    void configType(int profile, String type) {
        var code = DolbyVisionConfigs.configType(DolbyVision.of(profile, 1, 0));
        assertEquals(type, new String(new byte[]{(byte) (code >>> 24), (byte) (code >>> 16), (byte) (code >>> 8),
                (byte) code}, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(DolbyVisionConfigs.isConfigType(code));
        assertFalse(DolbyVisionConfigs.isConfigType(0x68766343L));
    }

    @Test
    @DisplayName("checks the field ranges and tells backward compatible configurations apart")
    void model() {
        assertThrows(IllegalArgumentException.class, () -> DolbyVision.of(128, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> DolbyVision.of(8, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> DolbyVision.of(8, 1, 16));
        assertThrows(IllegalArgumentException.class, () -> DolbyVision.of(-1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DolbyVision(256, 0, 8, 1, true, false, true, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DolbyVision(1, -1, 8, 1, true, false, true, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DolbyVision(1, 0, 8, 1, true, false, true, 1, 4));
        assertFalse(DolbyVision.of(5, 6, 0).isBackwardCompatible());
        assertTrue(DolbyVision.of(8, 6, 1).isBackwardCompatible());
        assertTrue(DolbyVision.of(8, 6, 4).isBackwardCompatible());
        assertFalse(new DolbyVision(1, 0, 7, 6, true, true, false, 6, 0).isBackwardCompatible());
        assertArrayEquals(DolbyVisionConfigs.record(DolbyVision.of(8, 6, 1)),
                DolbyVisionConfigs.record(new DolbyVision(1, 0, 8, 6, true, false, true, 1, 0)));
    }
}
