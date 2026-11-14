package com.github.auties00.smalt.internal.header;

import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value semantics of the shared header records and the DV profile detection from a frame's header block
 * and VAUX packs, with and without a source pack.
 */
@DisplayName("shared header values")
class HeaderValuesTest {
    @Test
    @DisplayName("FLAC STREAMINFO compares every field, the MD5 by content")
    void flacStreamInfo() {
        var md5 = new byte[16];
        var other = new byte[16];
        other[3] = 1;
        ValueContract.check(() -> new FlacStreamInfo(16, 4096, 1, 2, 44100, 2, 16, 10, md5.clone()), List.of(
                new FlacStreamInfo(17, 4096, 1, 2, 44100, 2, 16, 10, md5),
                new FlacStreamInfo(16, 4095, 1, 2, 44100, 2, 16, 10, md5),
                new FlacStreamInfo(16, 4096, 9, 2, 44100, 2, 16, 10, md5),
                new FlacStreamInfo(16, 4096, 1, 9, 44100, 2, 16, 10, md5),
                new FlacStreamInfo(16, 4096, 1, 2, 48000, 2, 16, 10, md5),
                new FlacStreamInfo(16, 4096, 1, 2, 44100, 1, 16, 10, md5),
                new FlacStreamInfo(16, 4096, 1, 2, 44100, 2, 24, 10, md5),
                new FlacStreamInfo(16, 4096, 1, 2, 44100, 2, 16, 11, md5),
                new FlacStreamInfo(16, 4096, 1, 2, 44100, 2, 16, 10, other)));
    }

    @Test
    @DisplayName("Opus headers compare every field, the mapping table by content")
    void opusHeader() {
        var mapping = new byte[]{0, 1};
        ValueContract.check(() -> new OpusHeader(1, 2, 312, 48000, 0, 1, 1, 1, mapping), List.of(
                new OpusHeader(2, 2, 312, 48000, 0, 1, 1, 1, mapping),
                new OpusHeader(1, 3, 312, 48000, 0, 1, 1, 1, mapping),
                new OpusHeader(1, 2, 313, 48000, 0, 1, 1, 1, mapping),
                new OpusHeader(1, 2, 312, 44100, 0, 1, 1, 1, mapping),
                new OpusHeader(1, 2, 312, 48000, 5, 1, 1, 1, mapping),
                new OpusHeader(1, 2, 312, 48000, 0, 2, 1, 1, mapping),
                new OpusHeader(1, 2, 312, 48000, 0, 1, 2, 1, mapping),
                new OpusHeader(1, 2, 312, 48000, 0, 1, 1, 0, mapping),
                new OpusHeader(1, 2, 312, 48000, 0, 1, 1, 1, new byte[]{1, 0})));
    }

    @ParameterizedTest(name = "{0} channels of family {1}")
    @CsvSource({"1,2,true", "4,2,true", "6,2,true", "9,3,true", "11,3,true", "5,2,false", "4,1,false",
            "0,2,false", "4,0,false", "250,2,false"})
    @DisplayName("Opus ambisonic layouts need a family of 2 or 3 and a square count, plus an optional pair")
    void ambisonicLayout(int channels, int family, boolean layout) {
        var result = OpusHeader.ambisonicLayout(channels, family);
        assertEquals(layout, result != null, String.valueOf(result));
        if (layout) {
            assertEquals(channels, result.channels());
        }
    }

    /**
     * Returns a frame holding a header block and, in its first VAUX block, the given packs.
     */
    private static byte[] frame(int size, boolean fifty, int application, int[]... packs) {
        var data = new byte[size];
        data[3] = (byte) (fifty ? 0x80 : 0);
        data[4] = (byte) application;
        var vaux = 3 * DifProfile.BLOCK;
        data[vaux] = (byte) (DifProfile.SECTION_VAUX << 5);
        var at = vaux + 3;
        for (var pack : packs) {
            for (var index = 0; index < pack.length; index++) {
                data[at + index] = (byte) pack[index];
            }
            at += 5;
        }
        for (var index = at; index + 5 <= vaux + 78; index += 5) {
            data[index] = (byte) 0xFF;
        }
        return data;
    }

    @ParameterizedTest(name = "signal type {0}, 50 Hz {1}, application {2}")
    @CsvSource({"0,false,0,DV_525", "0,false,1,DVCPRO_525", "0,true,0,DV_625", "0,true,1,DVCPRO_625",
            "4,false,1,DVCPRO50_525", "4,true,1,DVCPRO50_625", "20,false,1,DVCPRO_HD_1080I60",
            "21,true,1,DVCPRO_HD_1080I50", "24,false,1,DVCPRO_HD_720P60", "24,true,1,DVCPRO_HD_720P50"})
    @DisplayName("names the profile of the VAUX source pack's signal type")
    void detectFromSource(int type, boolean fifty, int application, DifProfile expected) {
        var data = frame(DifProfile.SEQUENCE, fifty, application, new int[]{DifProfile.PACK_VAUX_SOURCE, 0, 0, type, 0});
        assertEquals(expected, DifProfile.detect(data, 0, data.length));
    }

    @Test
    @DisplayName("refuses an unknown signal type and data that does not open with a header block")
    void detectRefuses() {
        var unknown = frame(DifProfile.SEQUENCE, false, 0, new int[]{DifProfile.PACK_VAUX_SOURCE, 0, 0, 0x1F, 0});
        assertNull(DifProfile.detect(unknown, 0, unknown.length));
        var data = frame(DifProfile.SEQUENCE, false, 0);
        assertNull(DifProfile.detect(data, 0, 6 * DifProfile.BLOCK - 1));
        for (var index : new int[]{0, 1, 2}) {
            var copy = data.clone();
            copy[index] = (byte) 0x20;
            assertNull(DifProfile.detect(copy, 0, copy.length), "byte " + index);
        }
        assertNull(DifProfile.detect(data, 0, 6 * DifProfile.BLOCK), "too short for any standard frame");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"DV_525", "DVCPRO_525", "DV_625", "DVCPRO_625", "DVCPRO50_525", "DVCPRO50_625"})
    @DisplayName("without a source pack, matches the frame size and application ID")
    void detectFromSize(DifProfile profile) {
        var data = frame(profile.frameSize(), profile.fiftyHz(), profile.applicationId());
        data[3 * DifProfile.BLOCK] = 0;
        assertEquals(profile, DifProfile.detect(data, 0, data.length));
    }

    @ParameterizedTest(name = "50 Hz {0}, application {1}")
    @CsvSource({"false,0,DV_525", "false,1,DVCPRO_525", "true,0,DV_625", "true,1,DVCPRO_625"})
    @DisplayName("without a source pack and with a nonstandard size, falls back on the standard definition profile")
    void detectFallback(boolean fifty, int application, DifProfile expected) {
        var size = (fifty ? DifProfile.DV_625 : DifProfile.DV_525).frameSize() + DifProfile.BLOCK;
        var data = frame(size, fifty, application);
        data[3 * DifProfile.BLOCK] = 0;
        assertEquals(expected, DifProfile.detect(data, 0, data.length));
    }

    @Test
    @DisplayName("reads the 16:9 display from the VAUX source control pack")
    void wide() {
        for (var display = 0; display < 8; display++) {
            var data = frame(DifProfile.SEQUENCE, false, 0, new int[]{DifProfile.PACK_VAUX_CONTROL, 0, display, 0, 0});
            assertEquals(display == 1 || display == 2, DifProfile.DV_525.wide(data, 0, data.length), "mode " + display);
        }
        var none = frame(DifProfile.SEQUENCE, false, 0);
        assertFalse(DifProfile.DV_525.wide(none, 0, none.length));
        assertTrue(DifProfile.DVCPRO_HD_720P60.wide(none, 0, none.length));
        assertNotNull(DifProfile.DV_625.sampleAspectRatio(true));
    }

    @Test
    @DisplayName("finds AAUX packs only in audio blocks")
    void findAudioPack() {
        var data = new byte[3 * DifProfile.BLOCK];
        data[DifProfile.BLOCK] = (byte) (DifProfile.SECTION_AUDIO << 5);
        data[DifProfile.BLOCK + 3] = 0x51;
        data[2 * DifProfile.BLOCK] = (byte) (DifProfile.SECTION_AUDIO << 5);
        data[2 * DifProfile.BLOCK + 3] = (byte) DifProfile.PACK_AAUX_SOURCE;
        assertEquals(2 * DifProfile.BLOCK + 3, DifProfile.findPack(data, 0, data.length, DifProfile.PACK_AAUX_SOURCE,
                DifProfile.SECTION_AUDIO));
        assertEquals(-1, DifProfile.findPack(data, 0, 2 * DifProfile.BLOCK, DifProfile.PACK_AAUX_SOURCE,
                DifProfile.SECTION_AUDIO));
    }
}
