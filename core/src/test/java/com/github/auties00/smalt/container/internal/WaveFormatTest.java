package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sub-format GUID families of {@link WaveFormat}: classification of the GUIDs of
 * {@code ksmedia.h} and DirectShow in their little-endian {@code GUID} layout, the tags and codecs
 * they map to, and the GUIDs {@link WaveFormat#write} writes back.
 */
@DisplayName("WaveFormat")
class WaveFormatTest {
    static byte[] extensible(String guid) {
        return ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xFFFE).putShort((short) 2)
                .putInt(48000).putInt(192000).putShort((short) 4).putShort((short) 16).putShort((short) 22)
                .putShort((short) 16).putInt(3).put(HexFormat.of().parseHex(guid)).array();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // KSDATAFORMAT_SUBTYPE_PCM, IEEE_FLOAT, ALAW and the standard form of WAVE_FORMAT_DOLBY_AC3_SPDIF
            "01000000000010008000 00aa00389b71, STANDARD, 1, PCM_S16LE",
            "03000000000010008000 00aa00389b71, STANDARD, 3, UNKNOWN_AUDIO",
            "92000000000010008000 00aa00389b71, STANDARD, 146, UNKNOWN_AUDIO",
            // KSDATAFORMAT_SUBTYPE_AMBISONIC_B_FORMAT_PCM and _IEEE_FLOAT
            "0100000021 07d3118644c8c1ca000000, AMBISONIC, 1, PCM_S16LE",
            "0300000021 07d3118644c8c1ca000000, AMBISONIC, 3, UNKNOWN_AUDIO",
            // KSDATAFORMAT_SUBTYPE_IEC61937_DOLBY_DIGITAL_PLUS and _DTS_HD
            "0a000000ea0c10008000 00aa00389b71, IEC61937, 10, UNKNOWN_AUDIO",
            "0b000000ea0c10008000 00aa00389b71, IEC61937, 11, UNKNOWN_AUDIO",
            // MEDIASUBTYPE_DOLBY_AC3 and MEDIASUBTYPE_DOLBY_DDPLUS
            "2c806de046dbcf11b4d1 00805f6cbbea, DOLBY_AC3, 32812, AC3",
            "af87fba7022dfb42a4d4 05cd93843bdd, DOLBY_DIGITAL_PLUS, 34735, EAC3",
            // a standard suffix whose upper Data1 half is not zero, and an Ambisonic GUID of another tag
            "01000100000010008000 00aa00389b71, UNKNOWN, 1, UNKNOWN_AUDIO",
            "0200000021 07d3118644c8c1ca000000, UNKNOWN, 2, UNKNOWN_AUDIO"})
    @DisplayName("classifies sub-format GUIDs and writes them back unchanged")
    void guids(String guid, WaveFormat.SubFormat family, int tag, Codec codec) {
        var compact = guid.replace(" ", "");
        var bytes = extensible(compact);
        var format = WaveFormat.parse(bytes, bytes.length, false);
        assertEquals(family, format.subFormat());
        assertEquals(tag, format.tag());
        assertTrue(format.extensible());
        assertEquals(codec, format.codec());
        if (family != WaveFormat.SubFormat.UNKNOWN) {
            var out = new ByteWriter(format.size(true));
            format.write(out, true);
            assertArrayEquals(bytes, out.toByteArray());
        }
    }

    @Test
    @DisplayName("keeps a plain WAVEFORMATEX and a truncated extensible tag apart from the families")
    void plain() {
        var bytes = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x2000).putShort((short) 6)
                .putInt(48000).putInt(48000).putShort((short) 1).putShort((short) 0).putShort((short) 0).array();
        var format = WaveFormat.parse(bytes, bytes.length, false);
        assertEquals(WaveFormat.SubFormat.NONE, format.subFormat());
        assertFalse(format.extensible());
        assertEquals(Codec.AC3, format.codec());
        var truncated = extensible("01000000000010008000" + "00aa00389b71");
        var shortFormat = WaveFormat.parse(truncated, 30, false);
        assertEquals(WaveFormat.SubFormat.NONE, shortFormat.subFormat());
        assertEquals(WaveFormat.EXTENSIBLE, shortFormat.tag());
    }

    @Test
    @DisplayName("maps 64-bit integer PCM to its codec and back to the PCM tag")
    void sixtyFourBits() {
        assertEquals(Codec.PCM_S64LE, WaveFormat.codec(WaveFormat.PCM, 64));
        assertEquals(WaveFormat.PCM, WaveFormat.tagOf(Codec.PCM_S64LE));
        assertEquals(-1, WaveFormat.tagOf(Codec.PCM_S64BE));
    }
}
