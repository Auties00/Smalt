package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads and writes the FLAC {@code PICTURE} metadata block, which Vorbis comments also embed in
 * base64 as {@code METADATA_BLOCK_PICTURE}.
 *
 * <p>The block holds big-endian fields: the picture type, a length-prefixed ASCII media type, a
 * length-prefixed UTF-8 description, the width, height, color depth and palette size, and the
 * length-prefixed picture data.
 */
public final class FlacPicture {
    private FlacPicture() {
        throw new AssertionError();
    }

    /**
     * Decodes a picture block.
     *
     * @param data the bytes
     * @param offset the index of the block body
     * @param length the size of the block body
     * @return the picture, or {@code null} when a length runs past the block
     */
    public static AttachedPicture decode(byte[] data, int offset, int length) {
        var end = offset + length;
        var position = offset;
        if (end - position < 8) {
            return null;
        }
        var type = ByteArrays.u32be(data, position);
        position += 4;
        var mimeLength = ByteArrays.u32be(data, position);
        position += 4;
        if (mimeLength > end - position) {
            return null;
        }
        var mime = new String(data, position, (int) mimeLength, StandardCharsets.ISO_8859_1);
        position += (int) mimeLength;
        if (end - position < 4) {
            return null;
        }
        var descriptionLength = ByteArrays.u32be(data, position);
        position += 4;
        if (descriptionLength > end - position) {
            return null;
        }
        var description = new String(data, position, (int) descriptionLength, StandardCharsets.UTF_8);
        position += (int) descriptionLength;
        if (end - position < 20) {
            return null;
        }
        position += 16;
        var dataLength = ByteArrays.u32be(data, position);
        position += 4;
        if (dataLength > end - position) {
            return null;
        }
        return AttachedPicture.of(AttachedPicture.Type.of(type > 20 ? 0 : (int) type), mime, description,
                Arrays.copyOfRange(data, position, position + (int) dataLength));
    }

    /**
     * Encodes a picture block body, probing the picture for its geometry.
     *
     * @param picture the picture to store
     * @return the block body
     */
    public static byte[] encode(AttachedPicture picture) {
        var mime = TagIo.mediaType(picture).getBytes(StandardCharsets.ISO_8859_1);
        var description = picture.description().getBytes(StandardCharsets.UTF_8);
        var data = picture.data();
        var geometry = TagIo.geometry(picture);
        var out = new ByteWriter(32 + mime.length + description.length + data.length);
        out.u32be(picture.type().code());
        out.u32be(mime.length);
        out.bytes(mime);
        out.u32be(description.length);
        out.bytes(description);
        out.u32be(geometry[0]);
        out.u32be(geometry[1]);
        out.u32be(geometry[2]);
        out.u32be(geometry[3]);
        out.u32be(data.length);
        out.bytes(data);
        return out.toByteArray();
    }
}
