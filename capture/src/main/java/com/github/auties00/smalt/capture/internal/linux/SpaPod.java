package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Builds and reads the Simple Plugin API (SPA) plain old data ({@code spa_pod}) values that PipeWire
 * negotiates stream formats with.
 *
 * <p>A pod is a header of two native-endian 32-bit words, the body size and the type, followed by
 * the body and padding to 8 bytes. An object pod's body is its object type and parameter id
 * followed by properties, each a key, flags and a pod; a choice pod's body is its choice type,
 * flags and a child header followed by the child bodies back to back. This class writes the video
 * {@code EnumFormat} objects a capture stream offers and the {@code Buffers} and {@code Meta}
 * parameters it answers a format with, and reads the {@code Format} the producer chose.
 */
final class SpaPod {
    /**
     * The pod types used.
     */
    static final int TYPE_ID = 3, TYPE_INT = 4, TYPE_LONG = 5, TYPE_RECTANGLE = 10, TYPE_FRACTION = 11, TYPE_OBJECT = 15,
            TYPE_CHOICE = 19;

    /**
     * The choice types used.
     */
    static final int CHOICE_NONE = 0, CHOICE_RANGE = 1, CHOICE_ENUM = 3, CHOICE_FLAGS = 4;

    /**
     * The {@code SPA_TYPE_OBJECT_Format} object type.
     */
    static final int OBJECT_FORMAT = 0x40003;

    /**
     * The {@code SPA_TYPE_OBJECT_ParamBuffers} object type.
     */
    static final int OBJECT_PARAM_BUFFERS = 0x40004;

    /**
     * The {@code SPA_TYPE_OBJECT_ParamMeta} object type.
     */
    static final int OBJECT_PARAM_META = 0x40005;

    /**
     * The {@code SPA_PARAM_EnumFormat} and {@code SPA_PARAM_Format} parameter ids.
     */
    static final int PARAM_ENUM_FORMAT = 3, PARAM_FORMAT = 4;

    /**
     * The {@code SPA_PARAM_Buffers} and {@code SPA_PARAM_Meta} parameter ids.
     */
    static final int PARAM_BUFFERS = 5, PARAM_META = 6;

    /**
     * The {@code SPA_PARAM_BUFFERS_dataType} key.
     */
    static final int KEY_BUFFERS_DATA_TYPE = 6;

    /**
     * The {@code SPA_PARAM_META_type} and {@code SPA_PARAM_META_size} keys.
     */
    static final int KEY_META_TYPE = 1, KEY_META_SIZE = 2;

    /**
     * The {@code SPA_META_Cursor} metadata type.
     */
    static final int META_CURSOR = 5;

    /**
     * The property flags of a modifier list: {@code SPA_POD_PROP_FLAG_MANDATORY}, so that only a
     * DMA-BUF format of the producer matches the object, and {@code SPA_POD_PROP_FLAG_DONT_FIXATE},
     * so that the choice among the modifiers stays with the producer, which allocates the buffers.
     */
    static final int PROP_MANDATORY = 1 << 3, PROP_DONT_FIXATE = 1 << 4;

    /**
     * The {@code DRM_FORMAT_MOD_LINEAR} modifier: rows of pixels in plain memory order.
     */
    static final long MODIFIER_LINEAR = 0;

    /**
     * The {@code DRM_FORMAT_MOD_INVALID} modifier, which also stands for a modifier this class could
     * not read.
     */
    static final long MODIFIER_INVALID = 0x00FF_FFFF_FFFF_FFFFL;

    /**
     * The format keys: {@code SPA_FORMAT_mediaType}, {@code _mediaSubtype}, {@code _VIDEO_format},
     * {@code _VIDEO_modifier}, {@code _VIDEO_size} and {@code _VIDEO_framerate}.
     */
    static final int KEY_MEDIA_TYPE = 1, KEY_MEDIA_SUBTYPE = 2, KEY_VIDEO_FORMAT = 0x20001, KEY_VIDEO_MODIFIER = 0x20002,
            KEY_VIDEO_SIZE = 0x20003, KEY_VIDEO_FRAMERATE = 0x20004;

    /**
     * The {@code SPA_MEDIA_TYPE_video} media type and {@code SPA_MEDIA_SUBTYPE_raw} subtype.
     */
    static final int MEDIA_VIDEO = 2, SUBTYPE_RAW = 1;

    /**
     * The largest frame side the offered format allows.
     */
    static final int MAX_SIDE = 16_384;

    /**
     * The highest frame rate the offered format allows; the session paces the frames itself, so any
     * producer rate is accepted.
     */
    static final int MAX_RATE = 1_000;

    private SpaPod() {
        throw new AssertionError();
    }

    /**
     * Builds the {@code EnumFormat} of a raw video capture stream.
     *
     * @param formats the {@code spa_video_format} ids accepted, preferred first
     * @param width the preferred width, or {@code 0} for none
     * @param height the preferred height, or {@code 0} for none
     * @param rate the preferred frames per second; every rate up to {@value #MAX_RATE} is offered
     * @return the pod, in native byte order
     */
    static byte[] videoEnumFormat(List<Integer> formats, int width, int height, int rate) {
        return videoEnumFormat(formats, width, height, rate, new long[0]);
    }

    /**
     * Builds the {@code EnumFormat} of a raw video capture stream, in DMA-BUF memory when modifiers
     * are given.
     *
     * @param formats the {@code spa_video_format} ids accepted, preferred first
     * @param width the preferred width, or {@code 0} for none
     * @param height the preferred height, or {@code 0} for none
     * @param rate the preferred frames per second; every rate up to {@value #MAX_RATE} is offered
     * @param modifiers the DRM format modifiers accepted, preferred first; empty for buffers without
     * a modifier, which are shared memory
     * @return the pod, in native byte order
     */
    static byte[] videoEnumFormat(List<Integer> formats, int width, int height, int rate, long[] modifiers) {
        var buffer = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder());
        var object = begin(buffer, TYPE_OBJECT);
        buffer.putInt(OBJECT_FORMAT).putInt(PARAM_ENUM_FORMAT);
        property(buffer, KEY_MEDIA_TYPE);
        id(buffer, MEDIA_VIDEO);
        property(buffer, KEY_MEDIA_SUBTYPE);
        id(buffer, SUBTYPE_RAW);
        property(buffer, KEY_VIDEO_FORMAT);
        var choice = begin(buffer, TYPE_CHOICE);
        buffer.putInt(CHOICE_ENUM).putInt(0).putInt(4).putInt(TYPE_ID);
        buffer.putInt(formats.getFirst());
        for (var format : formats) {
            buffer.putInt(format);
        }
        end(buffer, choice);
        if (modifiers.length > 0) {
            buffer.putInt(KEY_VIDEO_MODIFIER).putInt(PROP_MANDATORY | PROP_DONT_FIXATE);
            choice = begin(buffer, TYPE_CHOICE);
            buffer.putInt(CHOICE_ENUM).putInt(0).putInt(8).putInt(TYPE_LONG);
            buffer.putLong(modifiers[0]);
            for (var modifier : modifiers) {
                buffer.putLong(modifier);
            }
            end(buffer, choice);
        }
        property(buffer, KEY_VIDEO_SIZE);
        choice = begin(buffer, TYPE_CHOICE);
        buffer.putInt(CHOICE_RANGE).putInt(0).putInt(8).putInt(TYPE_RECTANGLE);
        buffer.putInt(width > 0 ? width : 1920).putInt(height > 0 ? height : 1080);
        buffer.putInt(1).putInt(1);
        buffer.putInt(MAX_SIDE).putInt(MAX_SIDE);
        end(buffer, choice);
        property(buffer, KEY_VIDEO_FRAMERATE);
        choice = begin(buffer, TYPE_CHOICE);
        buffer.putInt(CHOICE_RANGE).putInt(0).putInt(8).putInt(TYPE_FRACTION);
        buffer.putInt(rate).putInt(1);
        buffer.putInt(0).putInt(1);
        buffer.putInt(MAX_RATE).putInt(1);
        end(buffer, choice);
        end(buffer, object);
        return finish(buffer);
    }

    /**
     * Builds the {@code Buffers} parameter that restricts a stream's buffers to memory types.
     *
     * @param dataTypes the mask of {@code spa_data_type} values accepted, each as {@code 1 << type}
     * @return the pod, in native byte order
     */
    static byte[] buffers(int dataTypes) {
        var buffer = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        var object = begin(buffer, TYPE_OBJECT);
        buffer.putInt(OBJECT_PARAM_BUFFERS).putInt(PARAM_BUFFERS);
        property(buffer, KEY_BUFFERS_DATA_TYPE);
        var choice = begin(buffer, TYPE_CHOICE);
        buffer.putInt(CHOICE_FLAGS).putInt(0).putInt(4).putInt(TYPE_INT).putInt(dataTypes);
        end(buffer, choice);
        end(buffer, object);
        return finish(buffer);
    }

    /**
     * Builds the {@code Meta} parameter that asks for the pointer's metadata on every buffer.
     *
     * @param size the preferred size of the metadata, a {@code spa_meta_cursor} with its bitmap
     * @param minSize the smallest size accepted
     * @param maxSize the largest size accepted
     * @return the pod, in native byte order
     */
    static byte[] cursorMeta(int size, int minSize, int maxSize) {
        var buffer = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        var object = begin(buffer, TYPE_OBJECT);
        buffer.putInt(OBJECT_PARAM_META).putInt(PARAM_META);
        property(buffer, KEY_META_TYPE);
        id(buffer, META_CURSOR);
        property(buffer, KEY_META_SIZE);
        var choice = begin(buffer, TYPE_CHOICE);
        buffer.putInt(CHOICE_RANGE).putInt(0).putInt(4).putInt(TYPE_INT).putInt(size).putInt(minSize).putInt(maxSize);
        end(buffer, choice);
        end(buffer, object);
        return finish(buffer);
    }

    /**
     * Returns the bytes written to a buffer.
     */
    private static byte[] finish(ByteBuffer buffer) {
        var bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }

    /**
     * Writes a pod header with a placeholder size.
     *
     * @return the position of the header
     */
    private static int begin(ByteBuffer buffer, int type) {
        var at = buffer.position();
        buffer.putInt(0).putInt(type);
        return at;
    }

    /**
     * Sets the size of a pod begun at a position and pads it to 8 bytes.
     */
    private static void end(ByteBuffer buffer, int at) {
        buffer.putInt(at, buffer.position() - at - 8);
        while ((buffer.position() & 7) != 0) {
            buffer.put((byte) 0);
        }
    }

    /**
     * Writes a property's key and flags.
     */
    private static void property(ByteBuffer buffer, int key) {
        buffer.putInt(key).putInt(0);
    }

    /**
     * Writes an id pod.
     */
    private static void id(ByteBuffer buffer, int value) {
        buffer.putInt(4).putInt(TYPE_ID).putInt(value).putInt(0);
    }

    /**
     * Reads a raw video {@code Format}.
     *
     * @param pod the parameter the stream received
     * @return the format, or {@code null} when the pod is not a raw video format with a format id
     * and a size
     * @throws MediaException.Malformed if a size or offset leaves the pod
     */
    static VideoFormat readVideoFormat(MemorySegment pod) {
        if (pod.byteSize() < 8) {
            throw new MediaException.Malformed("an SPA pod of " + pod.byteSize() + " bytes");
        }
        var head = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        head.put(pod.asSlice(0, 8).toArray(JAVA_BYTE)).flip();
        var size = Integer.toUnsignedLong(head.getInt());
        if (size > 64 * 1024 || 8 + size > pod.byteSize()) {
            throw new MediaException.Malformed("an SPA pod of " + size + " bytes leaves its buffer");
        }
        var bytes = pod.asSlice(0, 8 + size).toArray(JAVA_BYTE);
        return readVideoFormat(bytes);
    }

    /**
     * Reads a raw video {@code Format} from its bytes.
     *
     * @param bytes the pod, in native byte order
     * @return the format, or {@code null} when the pod is not a raw video format with a format id
     * and a size
     * @throws MediaException.Malformed if a size or offset leaves the pod
     */
    static VideoFormat readVideoFormat(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        if (bytes.length < 8) {
            throw new MediaException.Malformed("an SPA pod of " + bytes.length + " bytes");
        }
        var size = buffer.getInt();
        var type = buffer.getInt();
        if (size < 0 || 8L + size > bytes.length) {
            throw new MediaException.Malformed("an SPA pod of " + Integer.toUnsignedLong(size) + " bytes leaves its buffer");
        }
        if (type != TYPE_OBJECT) {
            return null;
        }
        if (size < 8) {
            throw new MediaException.Malformed("an SPA object of " + size + " bytes");
        }
        if (buffer.getInt() != OBJECT_FORMAT) {
            return null;
        }
        buffer.getInt();
        var end = 8 + size;
        var mediaType = -1;
        var subtype = -1;
        var format = -1;
        var width = 0;
        var height = 0;
        var rateNumerator = 0;
        var rateDenominator = 0;
        var dmaBuf = false;
        var modifier = MODIFIER_INVALID;
        while (buffer.position() + 16 <= end) {
            var key = buffer.getInt();
            buffer.getInt();
            var valueSize = buffer.getInt();
            var valueType = buffer.getInt();
            var bodyStart = buffer.position();
            if (valueSize < 0 || bodyStart + (long) valueSize > end) {
                throw new MediaException.Malformed("an SPA property of " + Integer.toUnsignedLong(valueSize) + " bytes leaves its object");
            }
            var valueStart = bodyStart;
            var valueKind = valueType;
            var valueBytes = valueSize;
            if (valueType == TYPE_CHOICE) {
                if (valueSize < 16) {
                    throw new MediaException.Malformed("an SPA choice of " + valueSize + " bytes");
                }
                var choiceType = buffer.getInt(bodyStart);
                valueBytes = buffer.getInt(bodyStart + 8);
                valueKind = buffer.getInt(bodyStart + 12);
                valueStart = bodyStart + 16;
                if (valueBytes < 0 || valueStart + (long) valueBytes > bodyStart + valueSize) {
                    throw new MediaException.Malformed("an SPA choice child leaves its choice");
                }
                if (choiceType != CHOICE_NONE && key != KEY_VIDEO_MODIFIER) {
                    valueKind = -1;
                }
            }
            var needed = switch (valueKind) {
                case TYPE_ID, TYPE_INT -> 4;
                case TYPE_LONG, TYPE_RECTANGLE, TYPE_FRACTION -> 8;
                default -> 0;
            };
            if (valueBytes < needed) {
                throw new MediaException.Malformed("an SPA value of type " + valueKind + " has " + valueBytes + " bytes");
            }
            switch (key) {
                case KEY_MEDIA_TYPE -> mediaType = valueKind == TYPE_ID ? buffer.getInt(valueStart) : -1;
                case KEY_MEDIA_SUBTYPE -> subtype = valueKind == TYPE_ID ? buffer.getInt(valueStart) : -1;
                case KEY_VIDEO_FORMAT -> format = valueKind == TYPE_ID ? buffer.getInt(valueStart) : -1;
                case KEY_VIDEO_MODIFIER -> {
                    dmaBuf = true;
                    modifier = valueKind == TYPE_LONG ? buffer.getLong(valueStart) : MODIFIER_INVALID;
                }
                case KEY_VIDEO_SIZE -> {
                    if (valueKind == TYPE_RECTANGLE) {
                        width = buffer.getInt(valueStart);
                        height = buffer.getInt(valueStart + 4);
                    }
                }
                case KEY_VIDEO_FRAMERATE -> {
                    if (valueKind == TYPE_FRACTION) {
                        rateNumerator = buffer.getInt(valueStart);
                        rateDenominator = buffer.getInt(valueStart + 4);
                    }
                }
                default -> {
                }
            }
            buffer.position((int) Math.min(end, bodyStart + ((valueSize + 7L) & ~7L)));
        }
        if (mediaType != MEDIA_VIDEO || subtype != SUBTYPE_RAW || format < 0 || width <= 0 || height <= 0) {
            return null;
        }
        return new VideoFormat(format, width, height, rateNumerator, rateDenominator, dmaBuf, modifier);
    }

    /**
     * Describes a negotiated raw video format.
     *
     * @param format the {@code spa_video_format} id
     * @param width the width in pixels
     * @param height the height in pixels
     * @param rateNumerator the frame rate numerator, or {@code 0} when variable
     * @param rateDenominator the frame rate denominator
     * @param dmaBuf whether the format has a modifier, so its buffers are DMA-BUFs
     * @param modifier the DRM format modifier, the default of a choice the producer has not fixated
     * yet; {@link #MODIFIER_INVALID} without one, or when it is not a {@code long}
     */
    record VideoFormat(int format, int width, int height, int rateNumerator, int rateDenominator, boolean dmaBuf, long modifier) {
        /**
         * Returns whether the buffers can be read as rows of pixels through a plain mapping.
         *
         * @return {@code true} for shared memory and for linear DMA-BUFs
         */
        boolean mappable() {
            return !dmaBuf || modifier == MODIFIER_LINEAR;
        }
    }
}
