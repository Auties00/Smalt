package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FrameSideData, FramePacking and VideoFrame.sideData")
class FrameSideDataTest {
    @Test
    @DisplayName("an empty builder and a builder emptied by its resets give NONE")
    void none() {
        assertSame(FrameSideData.NONE, FrameSideData.builder().build());
        assertTrue(FrameSideData.NONE.isEmpty());
        var full = FrameSideData.builder().fieldOrder(FieldOrder.TOP_FIRST).repeatedFields(1)
                .framePacking(new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE,
                        FramePacking.Interpretation.FRAME0_LEFT))
                .orientation(Orientation.ROTATE_90).build();
        assertFalse(full.isEmpty());
        assertSame(FrameSideData.NONE, full.toBuilder().noFieldOrder().repeatedFields(0).noFramePacking()
                .noOrientation().build());
    }

    @Test
    @DisplayName("toBuilder keeps every entry and equality covers each of them")
    void roundTrip() {
        var packing = new FramePacking(FramePacking.Arrangement.TOP_BOTTOM, FramePacking.Interpretation.FRAME0_RIGHT);
        var data = FrameSideData.builder().fieldOrder(FieldOrder.BOTTOM_FIRST).repeatedFields(4).framePacking(packing)
                .orientation(Orientation.FLIP_VERTICAL).build();
        assertEquals(data, data.toBuilder().build());
        assertEquals(data.hashCode(), data.toBuilder().build().hashCode());
        assertEquals(FieldOrder.BOTTOM_FIRST, data.fieldOrder().orElseThrow());
        assertEquals(4, data.repeatedFields());
        assertEquals(packing, data.framePacking().orElseThrow());
        assertEquals(Orientation.FLIP_VERTICAL, data.orientation().orElseThrow());
        assertNotEquals(data, data.toBuilder().fieldOrder(FieldOrder.TOP_FIRST).build());
        assertNotEquals(data, data.toBuilder().repeatedFields(2).build());
        assertNotEquals(data, data.toBuilder().noFramePacking().build());
        assertNotEquals(data, data.toBuilder().orientation(Orientation.NORMAL).build());
        assertTrue(data.toString().contains("BOTTOM_FIRST"), data.toString());
    }

    @Test
    @DisplayName("the layer entry round-trips, takes part in equality and is cleared by noLayer")
    void layer() {
        var layer = new VideoLayer(1, 2, VideoLayer.Type.PRIMARY);
        var data = FrameSideData.builder().layer(layer).build();
        assertFalse(data.isEmpty());
        assertEquals(layer, data.layer().orElseThrow());
        assertEquals(data, data.toBuilder().build());
        assertEquals(data.hashCode(), data.toBuilder().build().hashCode());
        assertNotEquals(data, data.toBuilder().layer(new VideoLayer(1, 2, VideoLayer.Type.ALPHA)).build());
        assertTrue(data.toString().contains("PRIMARY"), data.toString());
        assertSame(FrameSideData.NONE, data.toBuilder().noLayer().build());
        assertTrue(FrameSideData.NONE.layer().isEmpty());
        assertThrows(NullPointerException.class, () -> FrameSideData.builder().layer(null));
    }

    @Test
    @DisplayName("a layer names an identifier from 0 to 63, a non-negative view and a type")
    void videoLayer() {
        var layer = new VideoLayer(63, 0, VideoLayer.Type.DEPTH);
        assertEquals(63, layer.id());
        assertEquals(0, layer.viewId());
        assertEquals(VideoLayer.Type.DEPTH, layer.type());
        assertThrows(IllegalArgumentException.class, () -> new VideoLayer(64, 0, VideoLayer.Type.PRIMARY));
        assertThrows(IllegalArgumentException.class, () -> new VideoLayer(-1, 0, VideoLayer.Type.PRIMARY));
        assertThrows(IllegalArgumentException.class, () -> new VideoLayer(0, -1, VideoLayer.Type.PRIMARY));
        assertThrows(NullPointerException.class, () -> new VideoLayer(0, 0, null));
    }

    @Test
    @DisplayName("the builder rejects nulls and repeated fields outside 0 to 4")
    void validation() {
        var builder = FrameSideData.builder();
        assertThrows(NullPointerException.class, () -> builder.fieldOrder(null));
        assertThrows(NullPointerException.class, () -> builder.framePacking(null));
        assertThrows(NullPointerException.class, () -> builder.orientation(null));
        assertThrows(IllegalArgumentException.class, () -> builder.repeatedFields(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.repeatedFields(5));
    }

    @Test
    @DisplayName("frame packing codes map both ways and grid positions stay within 0 to 15")
    void framePacking() {
        for (var arrangement : FramePacking.Arrangement.values()) {
            assertSame(arrangement, FramePacking.Arrangement.ofCode(arrangement.code()));
        }
        assertThrows(IllegalArgumentException.class, () -> FramePacking.Arrangement.ofCode(8));
        assertThrows(IllegalArgumentException.class, () -> FramePacking.Arrangement.ofCode(-1));
        for (var interpretation : FramePacking.Interpretation.values()) {
            assertSame(interpretation, FramePacking.Interpretation.ofCode(interpretation.code()));
        }
        assertSame(FramePacking.Interpretation.UNSPECIFIED, FramePacking.Interpretation.ofCode(3));
        assertTrue(new FramePacking(FramePacking.Arrangement.CHECKERBOARD, FramePacking.Interpretation.UNSPECIFIED)
                .quincunxSampling());
        assertFalse(new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE, FramePacking.Interpretation.UNSPECIFIED)
                .quincunxSampling());
        assertThrows(IllegalArgumentException.class, () -> new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE,
                FramePacking.Interpretation.FRAME0_LEFT, false, false, false, false, false, false, false, 16, 0, 0, 0,
                false));
        assertThrows(IllegalArgumentException.class, () -> new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE,
                FramePacking.Interpretation.FRAME0_LEFT, false, false, false, false, false, false, false, 0, 0, 0, -1,
                false));
        assertThrows(NullPointerException.class, () -> new FramePacking(null, FramePacking.Interpretation.FRAME0_LEFT));
        assertThrows(NullPointerException.class, () -> new FramePacking(FramePacking.Arrangement.TILE, null));
    }

    @Test
    @DisplayName("a video frame defaults to NONE, rejects a null side data and compares it")
    void videoFrame() {
        var picture = YuvPicture.allocate(16, 16, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var plain = new VideoFrame(picture, 0, 1, true);
        assertSame(FrameSideData.NONE, plain.sideData());
        assertThrows(NullPointerException.class, () -> new VideoFrame(picture, 0, 1, true, null));
        var annotated = new VideoFrame(picture, 0, 1, true,
                FrameSideData.builder().orientation(Orientation.ROTATE_180).build());
        assertNotEquals(plain, annotated);
        assertEquals(plain, new VideoFrame(picture, 0, 1, true, FrameSideData.NONE));
        assertThrows(IllegalArgumentException.class, () -> new VideoFrame(picture, 0, -2, true, FrameSideData.NONE));
        assertEquals(Packet.UNKNOWN_DURATION, new VideoFrame(picture, 0, Packet.UNKNOWN_DURATION, false).duration());
    }
}
