package com.github.auties00.smalt.internal.sei;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Parses the SEI messages of ITU-T H.264 and H.265 that describe how to display a picture: the
 * frame packing arrangement (H.264 D.1.26, H.265 D.2.16) and the display orientation (H.264 D.1.27,
 * H.265 D.2.17), and maps the {@code pic_struct} of the picture timing message to the scan and
 * repetition of a frame.
 */
public final class DisplaySei {
    /**
     * A frame packing arrangement message.
     *
     * @param cancel whether the message cancels the persistence of the previous one
     * @param persists whether the message applies to the frames output after its own
     * @param packing the arrangement, or {@code null} when {@code cancel} is set
     */
    public record FramePackingMessage(boolean cancel, boolean persists, FramePacking packing)
            implements SeiPersistence.Message {
    }

    /**
     * A display orientation message.
     *
     * @param cancel whether the message cancels the persistence of the previous one
     * @param persists whether the message applies to the pictures output after its own
     * @param orientation the orientation, or {@code null} when {@code cancel} is set
     */
    public record OrientationMessage(boolean cancel, boolean persists, Orientation orientation)
            implements SeiPersistence.Message {
    }

    private DisplaySei() {
        throw new AssertionError();
    }

    /**
     * Parses a frame packing arrangement message.
     *
     * <p>A message with a reserved {@code frame_packing_arrangement_type} or
     * {@code frame_packing_arrangement_id} is ignored, as the semantics require.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @param hevc whether the message follows the H.265 syntax (a persistence flag and the
     * {@code upsampled_aspect_ratio_flag}) rather than the H.264 one (a repetition period)
     * @return the message, or {@code null} when it is truncated, malformed or to be ignored
     */
    public static FramePackingMessage framePacking(H264BitReader reader, boolean hevc) {
        try {
            var id = reader.readUe();
            if (id >= 256 && id <= 511) {
                return null;
            }
            if (reader.readFlag()) {
                return reader.overrun() ? null : new FramePackingMessage(true, false, null);
            }
            var type = reader.readBits(7);
            var quincunx = reader.readFlag();
            var interpretation = reader.readBits(6);
            var spatialFlipping = reader.readFlag();
            var frame0Flipped = reader.readFlag();
            var fieldViews = reader.readFlag();
            var currentFrameIsFrame0 = reader.readFlag();
            var frame0SelfContained = reader.readFlag();
            var frame1SelfContained = reader.readFlag();
            int frame0X = 0;
            int frame0Y = 0;
            int frame1X = 0;
            int frame1Y = 0;
            if (!quincunx && type != 5) {
                frame0X = reader.readBits(4);
                frame0Y = reader.readBits(4);
                frame1X = reader.readBits(4);
                frame1Y = reader.readBits(4);
            }
            reader.skipBits(8);
            boolean persists;
            var upsampled = false;
            if (hevc) {
                persists = reader.readFlag();
                upsampled = reader.readFlag();
            } else {
                persists = reader.readUe() != 0;
            }
            if (reader.overrun() || type > 7) {
                return null;
            }
            var arrangement = FramePacking.Arrangement.ofCode(type);
            // flags the semantics tie to particular arrangements are ignored for the others
            var flipping = spatialFlipping && (type == 3 || type == 4);
            var unpacked = arrangement == FramePacking.Arrangement.UNPACKED;
            var gridded = !quincunx && type != 5 && !unpacked;
            var packing = new FramePacking(arrangement,
                    unpacked ? FramePacking.Interpretation.UNSPECIFIED : FramePacking.Interpretation.ofCode(interpretation),
                    quincunx, flipping, flipping && frame0Flipped, fieldViews && type == 2,
                    currentFrameIsFrame0 && type == 5, frame0SelfContained && type > 1 && !unpacked,
                    frame1SelfContained && type > 1 && !unpacked, gridded ? frame0X : 0, gridded ? frame0Y : 0,
                    gridded ? frame1X : 0, gridded ? frame1Y : 0, upsampled);
            return new FramePackingMessage(false, persists, packing);
        } catch (MediaException ignored) {
            // an invalid Exp-Golomb code makes the message unusable, not the stream
            return null;
        }
    }

    /**
     * Parses a display orientation message.
     *
     * <p>The flips apply before the counterclockwise rotation, which is rounded to the nearest
     * multiple of 90 degrees.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @param hevc whether the message follows the H.265 syntax (a persistence flag) rather than the
     * H.264 one (a repetition period and an extension flag)
     * @return the message, or {@code null} when it is truncated or malformed
     */
    public static OrientationMessage displayOrientation(H264BitReader reader, boolean hevc) {
        try {
            if (reader.readFlag()) {
                return reader.overrun() ? null : new OrientationMessage(true, false, null);
            }
            var horizontalFlip = reader.readFlag();
            var verticalFlip = reader.readFlag();
            var rotation = reader.readBits(16);
            var persists = hevc ? reader.readFlag() : reader.readUe() != 0;
            if (reader.overrun()) {
                return null;
            }
            return new OrientationMessage(false, persists, orientation(horizontalFlip, verticalFlip, rotation));
        } catch (MediaException ignored) {
            // an invalid Exp-Golomb code makes the message unusable, not the stream
            return null;
        }
    }

    /**
     * Returns the orientation of a display orientation message.
     *
     * <p>A vertical flip is a horizontal flip followed by a half turn, so the transform is a
     * horizontal flip when exactly one flip is set, followed by a clockwise rotation of a half turn
     * for a vertical flip, less the counterclockwise rotation.
     *
     * @param horizontalFlip {@code hor_flip}
     * @param verticalFlip {@code ver_flip}
     * @param rotation {@code anticlockwise_rotation}, in units of 2^-16 of a turn
     * @return the orientation
     */
    static Orientation orientation(boolean horizontalFlip, boolean verticalFlip, int rotation) {
        var quarters = (rotation + 8192) >> 14;
        var clockwise = (verticalFlip ? 180 : 0) - 90 * quarters;
        return Orientation.ofRotation(clockwise, horizontalFlip ^ verticalFlip);
    }

    /**
     * Returns the scan a {@code pic_struct} signals for a frame (ITU-T H.264 Table D-1, H.265
     * Table D.2).
     *
     * @param picStruct the {@code pic_struct}
     * @return {@link FieldOrder#PROGRESSIVE} for 0, 7 and 8, {@link FieldOrder#TOP_FIRST} for 3 and
     * 5, {@link FieldOrder#BOTTOM_FIRST} for 4 and 6, {@code null} otherwise
     */
    public static FieldOrder fieldOrder(int picStruct) {
        return switch (picStruct) {
            case 0, 7, 8 -> FieldOrder.PROGRESSIVE;
            case 3, 5 -> FieldOrder.TOP_FIRST;
            case 4, 6 -> FieldOrder.BOTTOM_FIRST;
            default -> null;
        };
    }

    /**
     * Returns the field periods a {@code pic_struct} displays a frame for beyond its own two.
     *
     * @param picStruct the {@code pic_struct}, or -1
     * @return 1 for 5 and 6, 2 for 7, 4 for 8, 0 otherwise
     */
    public static int repeatedFields(int picStruct) {
        return switch (picStruct) {
            case 5, 6 -> 1;
            case 7 -> 2;
            case 8 -> 4;
            default -> 0;
        };
    }

    /**
     * Assembles the side data of a frame.
     *
     * @param fieldOrder the scan, or {@code null}
     * @param repeatedFields the extra field periods, from 0 to 4
     * @param packing the frame packing message that applies, or {@code null}
     * @param orientation the display orientation message that applies, or {@code null}
     * @return the side data
     */
    public static FrameSideData sideData(FieldOrder fieldOrder, int repeatedFields, FramePackingMessage packing,
                                         OrientationMessage orientation) {
        if (fieldOrder == null && repeatedFields == 0 && packing == null && orientation == null) {
            return FrameSideData.NONE;
        }
        var builder = FrameSideData.builder().repeatedFields(repeatedFields);
        if (fieldOrder != null) {
            builder.fieldOrder(fieldOrder);
        }
        if (packing != null) {
            builder.framePacking(packing.packing());
        }
        if (orientation != null) {
            builder.orientation(orientation.orientation());
        }
        return builder.build();
    }
}
