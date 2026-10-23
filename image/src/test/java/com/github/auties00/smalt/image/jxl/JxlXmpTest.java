package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checks that the XMP packet of a JPEG XL container's {@code xml } box reaches
 * {@code DecodedImage.xmp()}, for a bare conformance codestream wrapped into a container and for a
 * conformance container with the box appended.
 */
@DisplayName("JPEG XL XMP")
class JxlXmpTest {
    static final byte[] PACKET = ("<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF"
            + " xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about=''"
            + " xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:format>image/jxl</dc:format></rdf:Description>"
            + "</rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);

    static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        var size = 8 + payload.length;
        out.write(size >>> 24);
        out.write(size >>> 16);
        out.write(size >>> 8);
        out.write(size);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** Wraps a bare codestream into a container with an xml box, or inserts the box into a container. */
    static byte[] withXmp(byte[] file) {
        var out = new ByteArrayOutputStream();
        if ((file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0x0A) {
            out.writeBytes(new byte[]{0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A});
            out.writeBytes(box("ftyp", "jxl \0\0\0\0jxl ".getBytes(StandardCharsets.ISO_8859_1)));
            out.writeBytes(box("xml ", PACKET));
            out.writeBytes(box("jxlc", file));
        } else {
            // after the signature and ftyp boxes, since the last box may extend to the end of the file
            var ftypEnd = 12 + ((file[12] & 0xFF) << 24 | (file[13] & 0xFF) << 16 | (file[14] & 0xFF) << 8
                    | (file[15] & 0xFF));
            out.write(file, 0, ftypEnd);
            out.writeBytes(box("xml ", PACKET));
            out.write(file, ftypEnd, file.length - ftypEnd);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("return the xml box of a container, before or after the codestream, and none for a bare codestream")
    void xmlBox() {
        var bare = JxlDecoderTest.file("grayscale");
        assertFalse(JxlDecoder.decode(MediaSource.of(bare)).xmp().isPresent());
        var container = new ByteArrayOutputStream();
        container.writeBytes(new byte[]{0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A});
        container.writeBytes(box("ftyp", "jxl \0\0\0\0jxl ".getBytes(StandardCharsets.ISO_8859_1)));
        container.writeBytes(box("xml ", PACKET));
        container.writeBytes(box("jxlc", bare));
        assertArrayEquals(PACKET, JxlDecoder.decode(MediaSource.of(container.toByteArray())).xmp().orElseThrow());

        var cafe = JxlDecoderTest.file("cafe");
        var appended = new ByteArrayOutputStream();
        appended.writeBytes(cafe);
        appended.writeBytes(box("xml ", PACKET));
        assertArrayEquals(PACKET, JxlDecoder.decode(MediaSource.of(appended.toByteArray())).xmp().orElseThrow());
    }
}
