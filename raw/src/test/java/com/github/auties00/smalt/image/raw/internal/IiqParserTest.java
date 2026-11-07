package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the recognition of Phase One IIQ files on synthetic files laid out as the CC0 samples are: a TIFF header,
 * Phase One's block signature at byte 8 and a first directory naming the camera.
 */
class IiqParserTest {
    private static byte[] iiq(String signature) {
        var order = ByteOrder.LITTLE_ENDIAN;
        var header = ByteBuffer.allocate(16).order(order);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(16);
        header.put(signature.getBytes(StandardCharsets.ISO_8859_1));
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(VendorTiffWriter.directory(List.of(VendorTiffWriter.ascii(271, "Phase One"),
                VendorTiffWriter.ascii(272, "P40+")), 16, order));
        out.writeBytes(new byte[64]);
        return out.toByteArray();
    }

    @Test
    @DisplayName("a file with Phase One's block is described as IIQ and its sensor data rejected as unsupported")
    void recognized() {
        var file = RawParser.parse(MediaSource.of(iiq("IIIICwaR")), MediaLimits.DEFAULT);
        assertEquals(RawFormat.IIQ, file.format);
        assertEquals("Phase One", file.make);
        assertEquals("P40+", file.model);
        assertNull(file.sensor);
        assertEquals("the sensor data of Phase One IIQ files is not supported", file.unsupported);
        var info = RawDecoder.info(MediaSource.of(iiq("IIIICwaR")));
        assertEquals(RawFormat.IIQ, info.format());
        assertFalse(info.isDecodable());
        var exception = assertThrows(MediaException.Unsupported.class,
                () -> RawDecoder.decode(MediaSource.of(iiq("IIIICwaR")), RawDecoderOptions.defaults()));
        assertTrue(exception.getMessage().contains("Phase One IIQ"), exception.getMessage());
    }

    @Test
    @DisplayName("a TIFF file without the whole signature is not taken for an IIQ file")
    void signature() {
        for (var signature : new String[]{"IIIICwaX", "IIIXCwaR", "MMMMCwaR"}) {
            var file = RawParser.parse(MediaSource.of(iiq(signature)), MediaLimits.DEFAULT);
            assertNotEquals(RawFormat.IIQ, file.format, signature);
        }
        assertFalse(IiqParser.matches("II*\0\u0010\0\0\0IIIICwa".getBytes(StandardCharsets.ISO_8859_1), 15));
    }
}
