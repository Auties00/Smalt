package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.track.SphericalVideo;
import com.github.auties00.smalt.track.StereoMode;

import java.util.Arrays;

/**
 * Converts between {@link SphericalVideo} and the boxes of the Google Spherical Video V2 specification, whose
 * projection box payloads ({@code equi}, {@code cbmp}, {@code mshp}, version and flags included) are also the
 * Matroska {@code ProjectionPrivate} of the same projection.
 */
public final class SphericalBoxes {
    /**
     * The Matroska {@code ProjectionType} of an equirectangular projection.
     */
    public static final int EQUIRECTANGULAR = 1;

    /**
     * The Matroska {@code ProjectionType} of a cubemap projection.
     */
    public static final int CUBEMAP = 2;

    /**
     * The Matroska {@code ProjectionType} of a mesh projection.
     */
    public static final int MESH = 3;

    private SphericalBoxes() {
        throw new AssertionError();
    }

    /**
     * {@return the Matroska {@code ProjectionType} of a projection}
     *
     * @param projection the projection to classify
     */
    public static int type(SphericalVideo.Projection projection) {
        return switch (projection) {
            case SphericalVideo.Equirectangular ignored -> EQUIRECTANGULAR;
            case SphericalVideo.Cubemap ignored -> CUBEMAP;
            case SphericalVideo.Mesh ignored -> MESH;
        };
    }

    /**
     * {@return the four-character type of the box holding a projection}
     *
     * @param projection the projection to classify
     */
    public static String boxType(SphericalVideo.Projection projection) {
        return switch (projection) {
            case SphericalVideo.Equirectangular ignored -> "equi";
            case SphericalVideo.Cubemap ignored -> "cbmp";
            case SphericalVideo.Mesh ignored -> "mshp";
        };
    }

    /**
     * Encodes the payload of the box holding a projection: a zero version and flags followed by its fields.
     *
     * @param projection the projection to encode
     * @return the payload
     */
    public static byte[] payload(SphericalVideo.Projection projection) {
        return switch (projection) {
            case SphericalVideo.Equirectangular equi -> words(equi.top(), equi.bottom(), equi.left(), equi.right());
            case SphericalVideo.Cubemap cubemap -> words(cubemap.layout(), cubemap.padding());
            case SphericalVideo.Mesh mesh -> {
                var data = mesh.data();
                var out = new byte[4 + data.length];
                System.arraycopy(data, 0, out, 4, data.length);
                yield out;
            }
        };
    }

    /**
     * Decodes the payload of a projection box.
     *
     * @param type the Matroska {@code ProjectionType} of the projection
     * @param data the bytes holding the payload
     * @param offset the index of its version
     * @param length the length of the payload
     * @return the projection, or {@code null} when the type is unknown or the payload is too short or has a
     * version other than 0
     */
    public static SphericalVideo.Projection parse(long type, byte[] data, int offset, int length) {
        if (length < 4 || data[offset] != 0) {
            return null;
        }
        return switch ((int) Math.min(type, Integer.MAX_VALUE)) {
            case EQUIRECTANGULAR -> length < 20 ? null : new SphericalVideo.Equirectangular(u32(data, offset + 4),
                    u32(data, offset + 8), u32(data, offset + 12), u32(data, offset + 16));
            case CUBEMAP -> length < 12 ? null : new SphericalVideo.Cubemap(u32(data, offset + 4), u32(data, offset + 8));
            case MESH -> SphericalVideo.Mesh.of(Arrays.copyOfRange(data, offset + 4, offset + length));
            default -> null;
        };
    }

    /**
     * Converts a 16.16 fixed-point angle of a {@code prhd} box to degrees.
     *
     * @param fixed the angle
     * @return the angle in degrees
     */
    public static double degrees(int fixed) {
        return fixed / 65536.0;
    }

    /**
     * Converts an angle in degrees to the 16.16 fixed-point form of a {@code prhd} box, rounded to nearest.
     *
     * @param degrees the angle, from -180 to 180
     * @return the fixed-point angle
     */
    public static int fixed(double degrees) {
        return (int) Math.round(degrees * 65536.0);
    }

    /**
     * Returns a pose read from a container, or {@code null} when an angle is out of range.
     *
     * @param projection the decoded projection, or {@code null}
     * @param yaw the yaw in degrees
     * @param pitch the pitch in degrees
     * @param roll the roll in degrees
     * @param source the metadata source, empty for none
     * @return the description, or {@code null}
     */
    public static SphericalVideo spherical(SphericalVideo.Projection projection, double yaw, double pitch,
                                           double roll, String source) {
        if (projection == null || !(yaw >= -180 && yaw <= 180 && pitch >= -90 && pitch <= 90 && roll >= -180
                && roll <= 180)) {
            return null;
        }
        return new SphericalVideo(projection, yaw, pitch, roll, source.replace("\0", ""));
    }

    /**
     * {@return the stereo mode an {@code st3d} code names, or {@code null} for the stereo-custom code 3 and the
     * reserved codes}
     *
     * @param code the {@code stereo_mode} field
     */
    public static StereoMode stereoMode(int code) {
        return switch (code) {
            case 0 -> StereoMode.MONO;
            case 1 -> StereoMode.TOP_BOTTOM_LEFT_FIRST;
            case 2 -> StereoMode.SIDE_BY_SIDE_LEFT_FIRST;
            case 4 -> StereoMode.SIDE_BY_SIDE_RIGHT_FIRST;
            default -> null;
        };
    }

    /**
     * {@return the {@code st3d} code of a stereo mode, or {@code -1} when the box cannot store it}
     *
     * @param mode the stereo mode to store
     */
    public static int st3dCode(StereoMode mode) {
        return switch (mode) {
            case MONO -> 0;
            case TOP_BOTTOM_LEFT_FIRST -> 1;
            case SIDE_BY_SIDE_LEFT_FIRST -> 2;
            case SIDE_BY_SIDE_RIGHT_FIRST -> 4;
            default -> -1;
        };
    }

    private static byte[] words(long... values) {
        var out = new byte[4 + 4 * values.length];
        for (var index = 0; index < values.length; index++) {
            var at = 4 + 4 * index;
            out[at] = (byte) (values[index] >>> 24);
            out[at + 1] = (byte) (values[index] >>> 16);
            out[at + 2] = (byte) (values[index] >>> 8);
            out[at + 3] = (byte) values[index];
        }
        return out;
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }
}
