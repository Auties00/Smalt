package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Edits committed annex streams at the level of their start codes to cover how the annex engine routes the data of a
 * picture: start codes of sub-bitstreams that are not decoding, runaway sequences of end of sub-bitstream codes, and
 * the redundant pictures of the Reference Picture Selection modes.
 */
@DisplayName("H.263 annex stream routing")
class H263PlusStreamRoutingTest {
    private static byte[] insert(byte[] data, int at, byte[] inserted) {
        var out = new ByteArrayOutputStream();
        out.write(data, 0, at);
        out.writeBytes(inserted);
        out.write(data, at, data.length - at);
        return out.toByteArray();
    }

    private static void assertSameDecode(Map<Integer, List<H263AnnexStreamsTest.Decoded>> expected,
                                         Map<Integer, List<H263AnnexStreamsTest.Decoded>> actual) {
        assertEquals(expected.keySet(), actual.keySet(), "sub-bitstreams");
        for (var entry : expected.entrySet()) {
            var pictures = actual.get(entry.getKey());
            assertEquals(entry.getValue().size(), pictures.size(), "pictures of sub-bitstream " + entry.getKey());
            for (var i = 0; i < pictures.size(); i++) {
                assertArrayEquals(entry.getValue().get(i).samples(), pictures.get(i).samples(),
                        "picture " + i + " of sub-bitstream " + entry.getKey());
            }
        }
    }

    /**
     * Sets the 8-bit temporal reference of the picture whose byte aligned start code is at an offset.
     */
    private static void setReference(byte[] data, int start, int value) {
        var at = start * 8L + 22;
        for (var bit = 0; bit < 8; bit++, at++) {
            var mask = 0x80 >> (int) (at & 7);
            var index = (int) (at >> 3);
            data[index] = (byte) ((value >> (7 - bit) & 1) != 0 ? data[index] | mask : data[index] & ~mask);
        }
    }

    private static int reference(byte[] data, int start) {
        var at = start * 8L + 22;
        var value = 0;
        for (var bit = 0; bit < 8; bit++, at++) {
            value = value << 1 | data[(int) (at >> 3)] >> (7 - (int) (at & 7)) & 1;
        }
        return value;
    }

    @Test
    @DisplayName("skips slice and group of blocks headers of sub-bitstreams that are not decoding a picture")
    void idleSubBitstreams() {
        var data = H263AnnexStreamsTest.resource("smalt-cpm-slices.263");
        var expected = H263AnnexStreamsTest.decode(data, true);
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        // a slice of sub-bitstream 2 (GN 27: SSBI 11) and a group of blocks header with GN 16 for sub-bitstream 3;
        // the stream sends pictures in sub-bitstreams 0 and 1 only
        var edited = insert(data, starts.get(1), new byte[]{0, 0, (byte) 0xEC, 0, 0, 0, (byte) 0xC3, 0});
        assertSameDecode(expected, H263AnnexStreamsTest.decode(edited, true));
    }

    @Test
    @DisplayName("refuses a picture followed by an endless run of end of sub-bitstream codes")
    void runawayStartCodes() {
        var data = H263AnnexStreamsTest.resource("smalt-ist.263");
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var out = new ByteArrayOutputStream();
        out.write(data, 0, starts.get(1));
        for (var i = 0; i < 70_000; i++) {
            out.writeBytes(new byte[]{0, 0, (byte) 0xF8, 0});
        }
        var edited = out.toByteArray();
        var stream = new H263PlusStream(MediaLimits.defaults());
        var exception = assertThrows(MediaException.Malformed.class, () -> stream.decode(edited, 0, edited.length,
                (picture, time, keyframe, layer, subBitstream) -> { }));
        assertEquals("corrupt H.263 picture data", exception.getMessage());
    }

    @Test
    @DisplayName("conceals macroblocks whose reference index passes the references or names one of another size")
    void corruptReferenceIndexes() throws Exception {
        var data = H263AnnexStreamsTest.resource("smalt-bad-refs.263");
        assertEquals("725db693bc14cb5a47f4d6475a96c74c8a3e44dc5b73595239b793acb957a29d",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)));
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        assertEquals(4, starts.size());
        var stream = new H263PlusStream(MediaLimits.defaults());
        var sizes = new java.util.ArrayList<String>();
        H263PlusStream.Sink sink = (picture, time, keyframe, layer, subBitstream) -> sizes.add(picture.width() + "x"
                + picture.height());
        var failed = new java.util.ArrayList<Integer>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            try {
                stream.decode(data, starts.get(k), end - starts.get(k), sink);
            } catch (MediaException.Malformed expected) {
                failed.add(k);
            }
        }
        stream.flush(sink);
        // the second picture names a third reference of a buffer holding one, the third the first picture, of
        // 176x144, from a 128x96 picture
        assertEquals(List.of(1, 2), failed);
        assertEquals(List.of("176x144", "176x144", "128x96", "176x144"), sizes);
    }

    @Test
    @DisplayName("drops a repeated RPS picture, but decodes an ERPS picture of a repeated reference and a new number")
    void redundantPictures() {
        var rps = H263AnnexStreamsTest.resource("smalt-rps.263");
        var rpsPictures = H263AnnexStreamsTest.decode(rps, true).get(0).size();
        var rpsStarts = H263AnnexStreamsTest.pictureStarts(rps);
        var last = rpsStarts.getLast();
        setReference(rps, last, reference(rps, rpsStarts.get(rpsStarts.size() - 2)));
        assertEquals(rpsPictures - 1, H263AnnexStreamsTest.decode(rps, true).get(0).size());

        // the picture before the last takes the temporal reference of its predecessor; output keeps one picture per
        // instant, but the last picture, which predicts from it through the multi-picture buffer, must not change
        var erps = H263AnnexStreamsTest.resource("smalt-erps.263");
        var original = H263AnnexStreamsTest.decode(erps, true).get(0);
        var erpsStarts = H263AnnexStreamsTest.pictureStarts(erps);
        var edited = erpsStarts.size() - 2;
        setReference(erps, erpsStarts.get(edited), reference(erps, erpsStarts.get(edited - 1)));
        var decoded = H263AnnexStreamsTest.decode(erps, true).get(0);
        assertEquals(original.size() - 1, decoded.size());
        assertArrayEquals(original.getLast().samples(), decoded.getLast().samples());
    }
}
