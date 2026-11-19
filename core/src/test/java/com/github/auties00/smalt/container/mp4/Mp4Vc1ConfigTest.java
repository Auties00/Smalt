package com.github.auties00.smalt.container.mp4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reads the VC-1 configuration of {@code dvc1} boxes laid out as SMPTE RP 2025 section 8 defines them.
 */
@DisplayName("MP4 dvc1 VC-1 configuration")
class Mp4Vc1ConfigTest {
    private static final byte[] STRUCT_C = {0x4E, 0x79, 0x1A, 0x01};

    private static final byte[] STRUCT_B = {(byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x19};

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(ints = {0, 4})
    @DisplayName("takes the STRUCT_C right after the profile and level byte of the simple and main profiles")
    void simpleMain(int profile) {
        var box = new byte[1 + STRUCT_C.length + STRUCT_B.length];
        box[0] = (byte) (profile << 4 | 2 << 1);
        System.arraycopy(STRUCT_C, 0, box, 1, STRUCT_C.length);
        System.arraycopy(STRUCT_B, 0, box, 1 + STRUCT_C.length, STRUCT_B.length);
        assertArrayEquals(STRUCT_C, SampleEntry.vc1Config(box));
    }

    @Test
    @DisplayName("takes the advanced profile sequence header and entry point after the flags and frame rate")
    void advanced() {
        var headers = new byte[]{0, 0, 1, 0x0F, (byte) 0xCA, 0x12, 0x34, 0, 0, 1, 0x0E, 0x56, 0x78};
        var box = new byte[7 + headers.length];
        box[0] = (byte) (12 << 4 | 3 << 1);
        box[1] = 0x00;
        box[2] = 0x3E;
        box[3] = box[4] = box[5] = box[6] = (byte) 0xFF;
        System.arraycopy(headers, 0, box, 7, headers.length);
        assertArrayEquals(headers, SampleEntry.vc1Config(box));
    }

    @Test
    @DisplayName("returns no configuration for a missing, empty or truncated box")
    void none() {
        assertNull(SampleEntry.vc1Config(null));
        assertNull(SampleEntry.vc1Config(new byte[0]));
        assertNull(SampleEntry.vc1Config(new byte[]{0x40, 0x4E, 0x79}));
        assertNull(SampleEntry.vc1Config(new byte[]{(byte) 0xC6, 0, 0x3E, 0, 0, 0, 0x19}));
    }
}
