package com.github.auties00.smalt.track;

import java.util.Arrays;
import java.util.Objects;

/**
 * Describes how the pictures of a 360-degree or VR video track map onto a sphere, as the Google
 * Spherical Video V2 boxes of MP4 ({@code sv3d}) and the Matroska {@code Projection} element store
 * it.
 *
 * <p>The pose rotates the projection before display, in the directions of the Spherical Video V2
 * projection header: first {@code yaw} counterclockwise about the up axis, then {@code pitch}
 * counterclockwise about the right axis, then {@code roll} clockwise about the forward axis. The Matroska
 * pose elements are carried as stored, as the WebM binding of Spherical Video V2 defines them in the same
 * directions (RFC 9559 describes the yaw and the roll the other way around).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param projection the projection of the pictures onto the sphere
 * @param yaw the yaw of the pose, in degrees, from -180 to 180
 * @param pitch the pitch of the pose, in degrees, from -90 to 90
 * @param roll the roll of the pose, in degrees, from -180 to 180
 * @param metadataSource the tool an MP4 {@code svhd} box names as the writer of the metadata, empty when unknown
 */
public record SphericalVideo(Projection projection, double yaw, double pitch, double roll, String metadataSource) {
    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if an angle is outside its range or not finite, or the source holds a NUL
     * character
     */
    public SphericalVideo {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(metadataSource, "metadataSource");
        if (!(yaw >= -180 && yaw <= 180 && pitch >= -90 && pitch <= 90 && roll >= -180 && roll <= 180)) {
            throw new IllegalArgumentException("invalid pose " + yaw + ", " + pitch + ", " + roll);
        }
        if (metadataSource.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("the metadata source holds a NUL character");
        }
    }

    /**
     * Returns a description with no rotation and no metadata source.
     *
     * @param projection the projection to use
     * @return the description
     */
    public static SphericalVideo of(Projection projection) {
        return new SphericalVideo(projection, 0, 0, 0, "");
    }

    /**
     * Projects the pictures onto a sphere.
     *
     * <p>This sealed type may gain subtypes in later releases; a {@code switch} over it needs a {@code default}
     * branch.
     */
    public sealed interface Projection permits Equirectangular, Cubemap, Mesh {
    }

    /**
     * Maps the pictures to latitude and longitude, possibly cropped.
     *
     * <p>Each bound is the part of the picture the projection does not cover on that side, as a fraction of the
     * picture's height (top and bottom) or width (left and right) in units of 2<sup>-32</sup>.
     *
     * @param top the uncovered part at the top, from 0 to {@code 0xFFFFFFFF}
     * @param bottom the uncovered part at the bottom, from 0 to {@code 0xFFFFFFFF}
     * @param left the uncovered part on the left, from 0 to {@code 0xFFFFFFFF}
     * @param right the uncovered part on the right, from 0 to {@code 0xFFFFFFFF}
     */
    public record Equirectangular(long top, long bottom, long left, long right) implements Projection {
        /**
         * The projection that covers the whole picture.
         */
        public static final Equirectangular FULL = new Equirectangular(0, 0, 0, 0);

        /**
         * Validates the bounds.
         *
         * @throws IllegalArgumentException if a bound is outside its range
         */
        public Equirectangular {
            if (((top | bottom | left | right) & ~0xFFFFFFFFL) != 0) {
                throw new IllegalArgumentException("equirectangular bounds are unsigned 32-bit fractions");
            }
        }
    }

    /**
     * Maps the pictures to the six faces of a cube.
     *
     * @param layout the arrangement of the faces, 0 for the 3x2 layout of Spherical Video V2 (right, left, up,
     * down, front, back), from 0 to {@code 0xFFFFFFFF}
     * @param padding the number of pixels between adjacent faces, from 0 to {@code 0xFFFFFFFF}
     */
    public record Cubemap(long layout, long padding) implements Projection {
        /**
         * Validates the fields.
         *
         * @throws IllegalArgumentException if a field is outside its range
         */
        public Cubemap {
            if (((layout | padding) & ~0xFFFFFFFFL) != 0) {
                throw new IllegalArgumentException("cubemap fields are unsigned 32-bit values");
            }
        }
    }

    /**
     * Maps the pictures onto a mesh of triangles, described by the contents of a Spherical Video V2 {@code mshp}
     * box after its version and flags (a CRC, the compression method and the possibly compressed meshes).
     *
     * <p>Instances are immutable and thread-safe; {@link #data()} returns a copy.
     */
    public static final class Mesh implements Projection {
        private final byte[] data;

        private Mesh(byte[] data) {
            this.data = data;
        }

        /**
         * Returns a mesh projection.
         *
         * @param data the {@code mshp} box contents after its version and flags, copied
         * @return the projection
         */
        public static Mesh of(byte[] data) {
            return new Mesh(data.clone());
        }

        /**
         * {@return a copy of the {@code mshp} box contents after its version and flags}
         */
        public byte[] data() {
            return data.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Mesh that && Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "Mesh[size=" + data.length + "]";
        }
    }
}
