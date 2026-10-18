package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.MediaInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks how DNG and camera raw files are told from TIFF and other images: the signatures of the
 * non-TIFF raw formats, and the directories of the TIFF-structured ones, within the header bytes
 * and through a source.
 */
class RawDetectionTest {
    /**
     * Builds a little-endian TIFF whose first directory, placed at an offset, holds the given SHORT
     * entries, optionally pointing to a child directory with a CFA photometric interpretation.
     */
    static byte[] tiff(int directoryOffset, boolean childCfa, int... tagValues) {
        var buffer = ByteBuffer.allocate(directoryOffset + 512).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'I', 'I', 42, 0}).putInt(directoryOffset);
        buffer.position(directoryOffset);
        var count = tagValues.length / 2 + (childCfa ? 1 : 0);
        buffer.putShort((short) count);
        for (var i = 0; i < tagValues.length; i += 2) {
            buffer.putShort((short) tagValues[i]).putShort((short) 3).putInt(1).putShort((short) tagValues[i + 1]).putShort((short) 0);
        }
        var child = directoryOffset + 2 + 12 * count + 4;
        if (childCfa) {
            buffer.putShort((short) 330).putShort((short) 4).putInt(1).putInt(child);
        }
        buffer.putInt(0);
        buffer.position(child);
        buffer.putShort((short) 1).putShort((short) 262).putShort((short) 3).putInt(1).putShort((short) 32803)
                .putShort((short) 0).putInt(0);
        return buffer.array();
    }

    @Test
    @DisplayName("a TIFF whose first directory holds DNGVersion is a DNG, wherever the directory lies")
    void dng() {
        var near = tiff(8, false, 256, 16, 50706, 1);
        assertEquals(Optional.of(ImageFormat.DNG), ImageFormat.detect(near, near.length));
        var far = tiff(4000, false, 256, 16, 50706, 1);
        assertEquals(Optional.of(ImageFormat.TIFF), ImageFormat.detect(far, ImageFormat.DETECTION_LENGTH));
        assertEquals(Optional.of(ImageFormat.DNG), Images.detect(MediaSource.of(far)));
    }

    @Test
    @DisplayName("a TIFF with a CFA image in a child directory is a camera raw file")
    void cfaChild() {
        var nef = tiff(8, true, 256, 160, 262, 2);
        assertEquals(Optional.of(ImageFormat.RAW), Images.detect(MediaSource.of(nef)));
        var plain = tiff(8, false, 256, 160, 262, 2);
        assertEquals(Optional.of(ImageFormat.TIFF), Images.detect(MediaSource.of(plain)));
    }

    @Test
    @DisplayName("Canon CR2 is recognized from its CR marker")
    void cr2() {
        var header = Arrays.copyOf(new byte[]{'I', 'I', 42, 0, 16, 0, 0, 0, 'C', 'R', 2, 0}, 64);
        assertEquals(Optional.of(ImageFormat.RAW), ImageFormat.detect(header, header.length));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"IIRO", "IIRS", "MMOR", "IIU\0", "FUJIFILMCCD-RAW 0201", "FOVb", "\0MRM", "II\u001A\0\0\0HEAPCCDR"})
    @DisplayName("the non-TIFF raw signatures are camera raw files")
    void signatures(String signature) {
        var header = Arrays.copyOf(signature.getBytes(StandardCharsets.ISO_8859_1), 64);
        assertEquals(Optional.of(ImageFormat.RAW), ImageFormat.detect(header, header.length));
    }

    @Test
    @DisplayName("a CR3 file is recognized from its ftyp brand")
    void cr3() {
        var header = Arrays.copyOf(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' ', 0, 0, 0, 1, 'c',
                'r', 'x', ' ', 'i', 's', 'o', 'm'}, 64);
        assertEquals(Optional.of(ImageFormat.RAW), ImageFormat.detect(header, header.length));
    }

    @Test
    @DisplayName("damaged directories fall back to TIFF")
    void damaged() {
        var bytes = tiff(8, true, 256, 160);
        bytes[8] = 0;
        bytes[9] = 0;
        assertEquals(Optional.of(ImageFormat.TIFF), Images.detect(MediaSource.of(bytes)));
        var loop = tiff(8, false, 256, 160);
        ByteBuffer.wrap(loop).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + 2 + 12, 8);
        assertEquals(Optional.of(ImageFormat.TIFF), Images.detect(MediaSource.of(loop)));
    }

    @Test
    @DisplayName("without the raw artifact a DNG is reported as an image Smalt cannot decode here")
    void probeWithoutDecoder() {
        var dng = tiff(8, false, 256, 16, 257, 8, 50706, 1);
        if (Images.isDecodable(ImageFormat.DNG)) {
            return;
        }
        var failure = assertThrows(MediaException.Unsupported.class, () -> Images.decode(MediaSource.of(dng)));
        assertEquals("dng", failure.format());
        MediaInfo info = com.github.auties00.smalt.Media.probe(MediaSource.of(dng));
        assertEquals(ImageFormat.DNG, info.imageFormat().orElseThrow());
        assertEquals(16, info.image().orElseThrow().width());
    }
}
