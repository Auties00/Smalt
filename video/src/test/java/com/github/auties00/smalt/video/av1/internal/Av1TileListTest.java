package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the light field vectors of {@code Av1LargeScaleTileTest} through the decoding context in every threading
 * mode, the forced one splitting each tile list into bands of tiles decoded with their own frame states, and requires
 * the tiles to match libaom's tile list decoder in each; also covers the camera frame header checks and anchor frame
 * ownership.
 */
@DisplayName("AV1 tile list decoding")
class Av1TileListTest {
    private static final String BASE = "/com/github/auties00/smalt/video/av1/lightfield/";

    record Vector(String name, int anchors, String tilesSha256) {
    }

    static List<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        for (var line : new String(resource("lightfield.tsv"), StandardCharsets.US_ASCII).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var columns = line.strip().split("\t");
            vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), columns[4]));
        }
        return vectors;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Av1Context.Threading.class)
    @DisplayName("the tiles match libaom's tile list decoder in every threading mode")
    void threading(Av1Context.Threading threading) {
        for (var vector : vectors()) {
            assertEquals(vector.tilesSha256(), decode(vector, threading), vector.name());
        }
    }

    @Test
    @DisplayName("tile lists decoded twice from the same anchor frames give the same tiles")
    void repeatable() {
        var vector = vectors().getFirst();
        var units = units(resource(vector.name()));
        var context = new Av1Context(MediaLimits.DEFAULT);
        context.anchorFrames(anchors(context, units, vector.anchors()));
        decodeAll(context, units.get(vector.anchors()));
        var last = units.getLast();
        var first = tiles(context, last);
        assertArrayEquals(first, tiles(context, last));
    }

    @Test
    @DisplayName("an intra camera frame header is rejected, the anchor frames are released on reset")
    void cameraHeaderChecks() {
        var vector = vectors().getFirst();
        var units = units(resource(vector.name()));
        var context = new Av1Context(MediaLimits.DEFAULT);
        var anchors = anchors(context, units, vector.anchors());
        context.anchorFrames(anchors);
        // the key frame of the first temporal unit cannot be a camera frame header
        assertThrows(MediaException.Malformed.class, () -> decodeAll(context, units.getFirst()));
        assertEquals(vector.anchors(), context.anchorFrameCount());
        context.reset();
        assertEquals(0, context.anchorFrameCount());
        assertThrows(IllegalArgumentException.class, () -> context.anchorFrames(new Av1Picture[129]));
    }

    private static String decode(Vector vector, Av1Context.Threading threading) {
        var units = units(resource(vector.name()));
        var context = new Av1Context(MediaLimits.DEFAULT);
        context.threading(threading);
        context.anchorFrames(anchors(context, units, vector.anchors()));
        assertTrue(decodeAll(context, units.get(vector.anchors())).isEmpty());
        var out = new ByteArrayOutputStream();
        for (var i = vector.anchors() + 1; i < units.size(); i++) {
            out.writeBytes(tiles(context, units.get(i)));
        }
        return sha256(out.toByteArray());
    }

    /** Decodes a tile list unit and returns its tiles as libaom's tile list decoder writes them. */
    private static byte[] tiles(Av1Context context, byte[] unit) {
        var pictures = decodeAll(context, unit);
        assertEquals(1, pictures.size());
        var picture = pictures.getFirst();
        var layout = layout(unit);
        var out = new ByteArrayOutputStream();
        var tileWidth = picture.w / layout[0];
        var tileHeight = picture.h / layout[1];
        for (var tile = 0; tile < layout[2]; tile++) {
            var x0 = (tile % layout[0]) * tileWidth;
            var y0 = (tile / layout[0]) * tileHeight;
            for (var plane = 0; plane < 3; plane++) {
                var shift = plane == 0 ? 0 : 1;
                var stride = picture.stride[plane == 0 ? 0 : 1];
                for (var y = 0; y < tileHeight >> shift; y++) {
                    for (var x = 0; x < tileWidth >> shift; x++) {
                        out.write(picture.data[plane][((y0 >> shift) + y) * stride + (x0 >> shift) + x]);
                    }
                }
            }
        }
        picture.release();
        return out.toByteArray();
    }

    private static int[] layout(byte[] unit) {
        var position = 0;
        while (position < unit.length) {
            var header = unit[position] & 0xFF;
            var cursor = position + 1;
            long size = 0;
            for (var i = 0; ; i++) {
                var b = unit[cursor++] & 0xFF;
                size |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (((header >> 3) & 0xF) == Obu.OBU_TILE_LIST) {
                return new int[]{(unit[cursor] & 0xFF) + 1, (unit[cursor + 1] & 0xFF) + 1,
                        ((unit[cursor + 2] & 0xFF) << 8 | (unit[cursor + 3] & 0xFF)) + 1};
            }
            position = (int) (cursor + size);
        }
        throw new IllegalStateException("no tile list OBU");
    }

    private static Av1Picture[] anchors(Av1Context context, List<byte[]> units, int count) {
        var anchors = new Av1Picture[count];
        for (var i = 0; i < count; i++) {
            var shown = decodeAll(context, units.get(i));
            var picture = shown.getLast();
            var anchor = Av1Picture.allocate(picture.w, picture.h, picture.layout, picture.bpc);
            for (var plane = 0; plane < 3; plane++) {
                if (picture.data[plane] != null) {
                    System.arraycopy(picture.data[plane], 0, anchor.data[plane], 0, picture.data[plane].length);
                }
            }
            anchors[i] = anchor;
            for (var p : shown) {
                p.release();
            }
        }
        return anchors;
    }

    private static List<Av1Picture> decodeAll(Av1Context context, byte[] unit) {
        var out = new ArrayList<Av1Picture>();
        context.decode(unit, 0, unit.length, 0, 0, out);
        context.flush(out);
        return out;
    }

    private static List<byte[]> units(byte[] ivf) {
        var units = new ArrayList<byte[]>();
        for (var position = (ivf[6] & 0xFF) | (ivf[7] & 0xFF) << 8; position + 12 <= ivf.length; ) {
            var size = (ivf[position] & 0xFF) | (ivf[position + 1] & 0xFF) << 8 | (ivf[position + 2] & 0xFF) << 16
                    | (ivf[position + 3] & 0xFF) << 24;
            position += 12;
            units.add(java.util.Arrays.copyOfRange(ivf, position, position + size));
            position += size;
        }
        return units;
    }

    private static byte[] resource(String name) {
        try (var input = Av1TileListTest.class.getResourceAsStream(BASE + name)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
