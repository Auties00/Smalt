package com.github.auties00.smalt.internal.sei;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the parsing of the frame packing arrangement and display orientation messages in both syntaxes, the
 * mapping of display orientations and {@code pic_struct} values, and the persistence of messages in output order.
 */
@DisplayName("DisplaySei")
class DisplaySeiTest {
    static H264BitReader reader(byte[] payload) {
        var reader = new H264BitReader();
        reader.init(Arrays.copyOf(payload, payload.length + H264BitReader.PADDING), 0, payload.length);
        return reader;
    }

    @Nested
    @DisplayName("display orientation")
    class Orientations {
        /** Applies the message's transform as H.264 D.2.27 states it: flips, then a counterclockwise rotation. */
        static int[][] transform(int[][] image, boolean horizontalFlip, boolean verticalFlip, int quarters) {
            var height = image.length;
            var width = image[0].length;
            var flipped = new int[height][width];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    flipped[verticalFlip ? height - 1 - y : y][horizontalFlip ? width - 1 - x : x] = image[y][x];
                }
            }
            var result = flipped;
            for (var q = 0; q < quarters; q++) {
                var h = result.length;
                var w = result[0].length;
                var turned = new int[w][h];
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        // a counterclockwise quarter turn moves (x, y) to (y, w - 1 - x)
                        turned[w - 1 - x][y] = result[y][x];
                    }
                }
                result = turned;
            }
            return result;
        }

        @ParameterizedTest(name = "rotation {0} quarter turns")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("the orientation transforms pictures as the flips and rotation do")
        void matchesTransform(int quarters) {
            int[][] image = {{1, 2, 3}, {4, 5, 6}};
            for (var horizontal : new boolean[]{false, true}) {
                for (var vertical : new boolean[]{false, true}) {
                    var expected = transform(image, horizontal, vertical, quarters);
                    var orientation = DisplaySei.orientation(horizontal, vertical, quarters * 16384);
                    var picture = YuvPicture.allocate(3, 2, ChromaFormat.MONOCHROME, false, ColorInfo.BT601_LIMITED);
                    for (var y = 0; y < 2; y++) {
                        for (var x = 0; x < 3; x++) {
                            picture.y()[y * picture.yStride() + x] = (byte) image[y][x];
                        }
                    }
                    var oriented = (YuvPicture) ImageOps.orient(picture, orientation);
                    var actual = new int[oriented.height()][oriented.width()];
                    for (var y = 0; y < oriented.height(); y++) {
                        for (var x = 0; x < oriented.width(); x++) {
                            actual[y][x] = oriented.y()[y * oriented.yStride() + x];
                        }
                    }
                    assertArrayEquals(expected, actual, "flips " + horizontal + "/" + vertical + " -> " + orientation);
                }
            }
        }

        @Test
        @DisplayName("rotations round to the nearest quarter turn")
        void rounding() {
            assertEquals(Orientation.NORMAL, DisplaySei.orientation(false, false, 8191));
            assertEquals(Orientation.ROTATE_270, DisplaySei.orientation(false, false, 8192));
            assertEquals(Orientation.ROTATE_180, DisplaySei.orientation(false, false, 32768 + 100));
            assertEquals(Orientation.NORMAL, DisplaySei.orientation(false, false, 65535));
        }

        @Test
        @DisplayName("both syntaxes parse, with their persistence")
        void syntaxes() {
            var h264 = DisplaySei.displayOrientation(reader(SeiWriter.displayOrientation(false, true, false, 16384, 1)),
                    false);
            assertEquals(new DisplaySei.OrientationMessage(false, true, Orientation.TRANSPOSE), h264);
            var hevc = DisplaySei.displayOrientation(reader(SeiWriter.displayOrientation(true, false, true, 0, 0)), true);
            assertEquals(new DisplaySei.OrientationMessage(false, false, Orientation.FLIP_VERTICAL), hevc);
            var cancel = DisplaySei.displayOrientation(reader(SeiWriter.displayOrientationCancel()), true);
            assertTrue(cancel.cancel());
            assertNull(DisplaySei.displayOrientation(reader(new byte[]{0x40}), false), "truncated");
        }
    }

    @Nested
    @DisplayName("frame packing")
    class Packing {
        @Test
        @DisplayName("an H.264 side-by-side arrangement keeps its flags and grid")
        void h264() {
            var payload = SeiWriter.framePacking(false, 3, 3, false, 1, true, true, true, true, true, false,
                    new int[]{4, 8, 12, 8}, 1, false);
            var message = DisplaySei.framePacking(reader(payload), false);
            var expected = new FramePacking(FramePacking.Arrangement.SIDE_BY_SIDE, FramePacking.Interpretation.FRAME0_LEFT,
                    false, true, true, false, false, true, false, 4, 8, 12, 8, false);
            assertEquals(new DisplaySei.FramePackingMessage(false, true, expected), message);
        }

        @Test
        @DisplayName("an H.265 frame alternation reads the persistence flag and the upsampled aspect ratio")
        void hevc() {
            var payload = SeiWriter.framePacking(true, 0, 5, false, 2, false, false, false, true, false, false,
                    new int[0], 0, true);
            var message = DisplaySei.framePacking(reader(payload), true);
            var expected = new FramePacking(FramePacking.Arrangement.FRAME_ALTERNATION,
                    FramePacking.Interpretation.FRAME0_RIGHT, false, false, false, false, true, false, false, 0, 0, 0,
                    0, true);
            assertEquals(new DisplaySei.FramePackingMessage(false, false, expected), message);
        }

        @Test
        @DisplayName("a checkerboard arrangement has no grid and an unpacked frame no interpretation")
        void special() {
            var checkerboard = DisplaySei.framePacking(reader(SeiWriter.framePacking(false, 0, 0, true, 1, false,
                    false, false, false, false, false, new int[0], 0, false)), false);
            assertEquals(new FramePacking(FramePacking.Arrangement.CHECKERBOARD, FramePacking.Interpretation.FRAME0_LEFT),
                    checkerboard.packing());
            var unpacked = DisplaySei.framePacking(reader(SeiWriter.framePacking(false, 0, 6, false, 0, false, false,
                    false, false, true, true, new int[]{1, 2, 3, 4}, 0, false)), false);
            assertEquals(FramePacking.Arrangement.UNPACKED, unpacked.packing().arrangement());
            assertEquals(FramePacking.Interpretation.UNSPECIFIED, unpacked.packing().interpretation());
            assertFalse(unpacked.packing().frame0SelfContained());
            assertEquals(0, unpacked.packing().frame0GridX());
        }

        @Test
        @DisplayName("reserved types and ids, truncated payloads and cancels are told apart")
        void ignored() {
            assertNull(DisplaySei.framePacking(reader(SeiWriter.framePacking(false, 0, 8, false, 0, false, false, false,
                    false, false, false, new int[]{0, 0, 0, 0}, 0, false)), false), "reserved type");
            assertNull(DisplaySei.framePacking(reader(SeiWriter.framePacking(false, 300, 3, false, 0, false, false,
                    false, false, false, false, new int[]{0, 0, 0, 0}, 0, false)), false), "reserved id");
            var payload = SeiWriter.framePacking(true, 0, 4, false, 1, false, false, false, false, false, false,
                    new int[]{0, 0, 0, 0}, 1, false);
            assertNull(DisplaySei.framePacking(reader(Arrays.copyOf(payload, payload.length - 2)), true), "truncated");
            assertTrue(DisplaySei.framePacking(reader(SeiWriter.framePackingCancel(true)), true).cancel());
        }
    }

    @Test
    @DisplayName("pic_struct maps to the scan and the repeated fields")
    void picStruct() {
        FieldOrder[] orders = {FieldOrder.PROGRESSIVE, null, null, FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST,
                FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST, FieldOrder.PROGRESSIVE, FieldOrder.PROGRESSIVE, null};
        int[] repeats = {0, 0, 0, 0, 0, 1, 1, 2, 4, 0};
        for (var picStruct = 0; picStruct < 10; picStruct++) {
            assertEquals(orders[picStruct], DisplaySei.fieldOrder(picStruct), "pic_struct " + picStruct);
            assertEquals(repeats[picStruct], DisplaySei.repeatedFields(picStruct), "pic_struct " + picStruct);
        }
        assertSame(FrameSideData.NONE, DisplaySei.sideData(null, 0, null, null));
        var data = DisplaySei.sideData(FieldOrder.TOP_FIRST, 1, null,
                new DisplaySei.OrientationMessage(false, false, Orientation.ROTATE_90));
        assertEquals(FieldOrder.TOP_FIRST, data.fieldOrder().orElseThrow());
        assertEquals(1, data.repeatedFields());
        assertEquals(Orientation.ROTATE_90, data.orientation().orElseThrow());
        assertTrue(data.framePacking().isEmpty());
    }

    @Test
    @DisplayName("messages persist in output order until replaced, cancelled or a new sequence starts")
    void persistence() {
        var persistence = new SeiPersistence<DisplaySei.OrientationMessage>();
        var lasting = new DisplaySei.OrientationMessage(false, true, Orientation.ROTATE_90);
        var once = new DisplaySei.OrientationMessage(false, false, Orientation.ROTATE_180);
        var cancel = new DisplaySei.OrientationMessage(true, false, null);
        assertNull(persistence.resolve(false, null));
        assertSame(lasting, persistence.resolve(false, lasting));
        assertSame(lasting, persistence.resolve(false, null));
        assertSame(once, persistence.resolve(false, once));
        assertNull(persistence.resolve(false, null), "a message for one picture ends the persistence of the last");
        assertSame(lasting, persistence.resolve(false, lasting));
        assertNull(persistence.resolve(true, null), "a new sequence ends it");
        assertSame(lasting, persistence.resolve(true, lasting), "a new sequence may start with a message");
        assertNull(persistence.resolve(false, cancel));
        assertNull(persistence.resolve(false, null));
        persistence.resolve(false, lasting);
        persistence.reset();
        assertNull(persistence.resolve(false, null));
    }
}
