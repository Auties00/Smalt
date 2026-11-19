package com.github.auties00.smalt.video;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes how a decoded picture of omnidirectional (360-degree) video maps onto a sphere, as the omnidirectional
 * SEI messages of Rec. ITU-T H.274 clause 8.15 signal it: the projection of the sphere onto a projected picture, the
 * rotation of the sphere, the region-wise packing of the projected picture into the decoded one, and the viewports
 * recommended for display.
 *
 * <p>Each part comes from its own message and is absent when no message of its type applies to the picture. Angles
 * are in degrees (H.274 stores them in units of 2<sup>-16</sup> degrees); azimuth increases eastward and elevation
 * northward, the viewing perspective being from the center of the sphere looking outward. Sizes and positions are in
 * luma samples.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param projection the projection of the sphere onto the projected picture, or empty
 * @param rotation the rotation from the global coordinate axes to the local ones of the projection, or empty
 * @param regionWisePacking the packing of regions of the projected picture into the decoded picture, or empty when
 *                          the decoded picture is the projected picture
 * @param viewports the recommended viewports, or empty
 */
public record OmnidirectionalVideo(Optional<Projection> projection, Optional<SphereRotation> rotation,
                                   Optional<RegionWisePacking> regionWisePacking, Optional<Viewports> viewports) {
    /**
     * Validates the description.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public OmnidirectionalVideo {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(regionWisePacking, "regionWisePacking");
        Objects.requireNonNull(viewports, "viewports");
    }

    /**
     * Projects the sphere onto the projected picture.
     *
     * <p>This sealed type may gain subtypes in later releases; a {@code switch} over it needs a {@code default}
     * branch.
     */
    public sealed interface Projection permits Equirectangular, GeneralizedCubemap {
    }

    /**
     * Maps the sphere to longitude and latitude (the equirectangular projection SEI message, H.274 clause 8.15.2).
     *
     * @param guardBands the guard bands at the left and right of the constituent picture, or empty when it has none
     */
    public record Equirectangular(Optional<GuardBands> guardBands) implements Projection {
        /**
         * Validates the projection.
         *
         * @throws NullPointerException if {@code guardBands} is {@code null}
         */
        public Equirectangular {
            Objects.requireNonNull(guardBands, "guardBands");
        }

        /**
         * The guard bands of an equirectangular constituent picture.
         *
         * @param type {@code erp_guard_band_type}: 0 for content unknown, 1 for content that suffices for interpolation
         *             at fractional positions, 2 for content whose quality changes gradually to that of the spherically
         *             adjacent region, 3 for content of a quality similar to the picture's, from 0 to 7 (values above 3
         *             reserved, to be treated as 0)
         * @param left the width of the guard band on the left, from 0 to 255
         * @param right the width of the guard band on the right, from 0 to 255
         */
        public record GuardBands(int type, int left, int right) {
            /**
             * Validates the guard bands.
             *
             * @throws IllegalArgumentException if a value is outside its range
             */
            public GuardBands {
                if (type < 0 || type > 7 || left < 0 || left > 255 || right < 0 || right > 255) {
                    throw new IllegalArgumentException("invalid guard bands " + type + ", " + left + ", " + right);
                }
            }
        }
    }

    /**
     * Maps the sphere to the faces of a cube, or to one face and four half faces of a hemisphere cube, packed in a
     * grid (the generalized cubemap projection SEI message, H.274 clause 8.15.3).
     *
     * @param packingType {@code gcmp_packing_type}: the arrangement of the faces in a grid of H.274 Table 10, from 0
     *                    to 3 for six faces and 4 or 5 for the hemisphere packings of one face and four half faces,
     *                    from 0 to 7 (values above 5 reserved)
     * @param mappingFunction {@code gcmp_mapping_function_type}: 0 for the plain cubemap mapping, 1 for the
     *                        equi-angular mapping, 2 for the mapping of each face's coefficients, from 0 to 3 (3
     *                        reserved)
     * @param faces the faces in the order of their positions in the grid: 6, or 5 for a hemisphere packing
     * @param guardBand the guard band around the faces, or empty when there is none
     */
    public record GeneralizedCubemap(int packingType, int mappingFunction, List<Face> faces,
                                     Optional<CubemapGuardBand> guardBand) implements Projection {
        /**
         * Validates the projection and copies the list of faces.
         *
         * @throws NullPointerException if a component or a face is {@code null}
         * @throws IllegalArgumentException if a value is outside its range or there are neither 5 nor 6 faces
         */
        public GeneralizedCubemap {
            faces = List.copyOf(faces);
            Objects.requireNonNull(guardBand, "guardBand");
            if (packingType < 0 || packingType > 7 || mappingFunction < 0 || mappingFunction > 3
                    || (faces.size() != 5 && faces.size() != 6)) {
                throw new IllegalArgumentException("invalid cubemap " + packingType + ", " + mappingFunction + ", "
                        + faces.size() + " faces");
            }
        }

        /**
         * One face of a generalized cubemap at a position of the grid.
         *
         * @param index {@code gcmp_face_index}: the face of the cube, which H.274 clause 8.15.1.2 relates to the
         *              sphere coordinates, from 0 to 7 (values above 5 reserved)
         * @param rotation {@code gcmp_face_rotation}: the anticlockwise rotation of the face, in quarter turns, from 0
         *                 to 3
         * @param coefficientU {@code gcmp_function_coeff_u}, from 0 to 127, 0 unless the mapping function is 2:
         *                     the coefficient of the horizontal mapping function is {@code (coefficientU + 1) / 128}
         * @param uAffectedByV {@code gcmp_function_u_affected_by_v_flag}: whether the horizontal mapping depends on
         *                     the vertical position
         * @param coefficientV {@code gcmp_function_coeff_v}, from 0 to 127, 0 unless the mapping function is 2: the
         *                     coefficient of the vertical mapping function is {@code (coefficientV + 1) / 128}
         * @param vAffectedByU {@code gcmp_function_v_affected_by_u_flag}: whether the vertical mapping depends on the
         *                     horizontal position
         */
        public record Face(int index, int rotation, int coefficientU, boolean uAffectedByV, int coefficientV,
                           boolean vAffectedByU) {
            /**
             * Validates the face.
             *
             * @throws IllegalArgumentException if a value is outside its range
             */
            public Face {
                if (index < 0 || index > 7 || rotation < 0 || rotation > 3 || coefficientU < 0 || coefficientU > 127
                        || coefficientV < 0 || coefficientV > 127) {
                    throw new IllegalArgumentException("invalid cubemap face " + index + ", " + rotation);
                }
            }
        }

        /**
         * The guard band of a generalized cubemap.
         *
         * @param type {@code gcmp_guard_band_type}, with the meanings of {@link Equirectangular.GuardBands#type()},
         *             from 0 to 7
         * @param boundaryExterior {@code gcmp_guard_band_boundary_exterior_flag}: whether the guard band also lies
         *                         along the outer boundary of the packed faces, not only between faces
         * @param samples the width of the guard band, from 1 to 16
         */
        public record CubemapGuardBand(int type, boolean boundaryExterior, int samples) {
            /**
             * Validates the guard band.
             *
             * @throws IllegalArgumentException if a value is outside its range
             */
            public CubemapGuardBand {
                if (type < 0 || type > 7 || samples < 1 || samples > 16) {
                    throw new IllegalArgumentException("invalid cubemap guard band " + type + ", " + samples);
                }
            }
        }
    }

    /**
     * Rotates the global coordinate axes to the local axes of the projection (the sphere rotation SEI message, H.274
     * clause 8.15.4): first by the yaw about the vertical axis, then the pitch, then the roll.
     *
     * @param yaw the yaw, from -180 inclusive to 180 exclusive
     * @param pitch the pitch, from -90 to 90
     * @param roll the roll, from -180 inclusive to 180 exclusive
     */
    public record SphereRotation(double yaw, double pitch, double roll) {
        /**
         * Validates the rotation.
         *
         * @throws IllegalArgumentException if an angle is outside its range or not finite
         */
        public SphereRotation {
            if (!(yaw >= -180 && yaw < 180 && pitch >= -90 && pitch <= 90 && roll >= -180 && roll < 180)) {
                throw new IllegalArgumentException("invalid sphere rotation " + yaw + ", " + pitch + ", " + roll);
            }
        }
    }

    /**
     * Places rectangular regions of the projected picture, resized and transformed, in the decoded picture (the
     * region-wise packing SEI message, H.274 clause 8.15.5).
     *
     * @param constituentPictureMatching {@code rwp_constituent_picture_matching_flag}: whether, in a frame packing
     *                                   arrangement, the regions describe each constituent picture
     * @param projectedWidth the width of the projected picture, positive
     * @param projectedHeight the height of the projected picture, positive
     * @param packedWidth the width of the packed (decoded and cropped) picture, positive
     * @param packedHeight the height of the packed picture, positive
     * @param regions the packed regions, at most 255
     */
    public record RegionWisePacking(boolean constituentPictureMatching, long projectedWidth, long projectedHeight,
                                    int packedWidth, int packedHeight, List<Region> regions) {
        /**
         * Validates the packing and copies the list of regions.
         *
         * @throws NullPointerException if {@code regions} or a region is {@code null}
         * @throws IllegalArgumentException if a size is not positive or there are more than 255 regions
         */
        public RegionWisePacking {
            regions = List.copyOf(regions);
            if (projectedWidth <= 0 || projectedHeight <= 0 || packedWidth <= 0 || packedHeight <= 0
                    || regions.size() > 255) {
                throw new IllegalArgumentException("invalid region-wise packing of " + projectedWidth + "x"
                        + projectedHeight + " into " + packedWidth + "x" + packedHeight);
            }
        }

        /**
         * One region of a region-wise packing: a rectangle of the projected picture and the rectangle of the packed
         * picture it is placed in.
         *
         * @param transform {@code rwp_transform_type}, the transform that remaps the packed region to the projected
         *                  one (H.274 Table 13): 0 none, 1 a horizontal mirror, 2 a rotation by 180 degrees, 3 a
         *                  rotation by 180 degrees then a horizontal mirror, 4 a rotation by 90 degrees then a
         *                  horizontal mirror, 5 a rotation by 90 degrees, 6 a rotation by 270 degrees then a horizontal
         *                  mirror, 7 a rotation by 270 degrees (rotations anticlockwise)
         * @param projectedWidth the width of the region in the projected picture
         * @param projectedHeight the height of the region in the projected picture
         * @param projectedTop the top of the region in the projected picture
         * @param projectedLeft the left of the region in the projected picture
         * @param packedWidth the width of the region in the packed picture
         * @param packedHeight the height of the region in the packed picture
         * @param packedTop the top of the region in the packed picture
         * @param packedLeft the left of the region in the packed picture
         * @param guardBands the guard bands around the region in the packed picture, or empty when it has none
         */
        public record Region(int transform, long projectedWidth, long projectedHeight, long projectedTop,
                             long projectedLeft, int packedWidth, int packedHeight, int packedTop, int packedLeft,
                             Optional<RegionGuardBands> guardBands) {
            /**
             * Validates the region.
             *
             * @throws NullPointerException if {@code guardBands} is {@code null}
             * @throws IllegalArgumentException if the transform is not from 0 to 7 or a size or position is negative
             */
            public Region {
                Objects.requireNonNull(guardBands, "guardBands");
                if (transform < 0 || transform > 7 || projectedWidth < 0 || projectedHeight < 0 || projectedTop < 0
                        || projectedLeft < 0 || packedWidth < 0 || packedHeight < 0 || packedTop < 0
                        || packedLeft < 0) {
                    throw new IllegalArgumentException("invalid packed region");
                }
            }
        }

        /**
         * The guard bands around a packed region.
         *
         * @param left the width of the guard band on the left, from 0 to 255
         * @param right the width of the guard band on the right, from 0 to 255
         * @param top the height of the guard band at the top, from 0 to 255
         * @param bottom the height of the guard band at the bottom, from 0 to 255
         * @param notUsedForPrediction {@code rwp_guard_band_not_used_for_pred_flag}: whether the guard bands are not
         *                             used in inter prediction
         * @param types {@code rwp_guard_band_type} of the left, right, top and bottom guard bands, with the meanings
         *              of {@link Equirectangular.GuardBands#type()}
         */
        public record RegionGuardBands(int left, int right, int top, int bottom, boolean notUsedForPrediction,
                                       List<Integer> types) {
            /**
             * Validates the guard bands and copies the list of types.
             *
             * @throws NullPointerException if {@code types} or a type is {@code null}
             * @throws IllegalArgumentException if a size is not from 0 to 255, or there are not four types from 0 to
             *                                  7
             */
            public RegionGuardBands {
                types = List.copyOf(types);
                if (left < 0 || left > 255 || right < 0 || right > 255 || top < 0 || top > 255 || bottom < 0
                        || bottom > 255 || types.size() != 4) {
                    throw new IllegalArgumentException("invalid region guard bands");
                }
                for (var type : types) {
                    if (type < 0 || type > 7) {
                        throw new IllegalArgumentException("invalid guard band type " + type);
                    }
                }
            }
        }
    }

    /**
     * The viewports recommended for display when the viewer does not control the viewing orientation (the
     * omnidirectional viewport SEI message, H.274 clause 8.15.6).
     *
     * @param id {@code omni_viewport_id}: the purpose of the viewports, from 0 to 511: 0 for the director's cut, 1
     *           for viewports chosen from viewing statistics, the others as the application determines (values from
     *           512 to 1023 are reserved, and decoders ignore the messages that carry them)
     * @param viewports the viewports, from 1 to 16
     */
    public record Viewports(int id, List<Viewport> viewports) {
        /**
         * Validates the viewports and copies the list.
         *
         * @throws NullPointerException if {@code viewports} or a viewport is {@code null}
         * @throws IllegalArgumentException if the identifier is outside its range or there are not from 1 to 16
         *                                  viewports
         */
        public Viewports {
            viewports = List.copyOf(viewports);
            if (id < 0 || id > 511 || viewports.isEmpty() || viewports.size() > 16) {
                throw new IllegalArgumentException("invalid viewports " + id + ", " + viewports.size());
            }
        }

        /**
         * A region of the sphere bounded by four great circles, given by its center and its angular ranges.
         *
         * @param azimuth the azimuth of the center, from -180 inclusive to 180 exclusive
         * @param elevation the elevation of the center, from -90 to 90
         * @param tilt the tilt about the axis through the center, from -180 inclusive to 180 exclusive
         * @param horizontalRange the azimuth range through the center, from 0 exclusive to 360
         * @param verticalRange the elevation range through the center, from 0 exclusive to 180
         */
        public record Viewport(double azimuth, double elevation, double tilt, double horizontalRange,
                               double verticalRange) {
            /**
             * Validates the viewport.
             *
             * @throws IllegalArgumentException if an angle is outside its range or not finite
             */
            public Viewport {
                if (!(azimuth >= -180 && azimuth < 180 && elevation >= -90 && elevation <= 90 && tilt >= -180
                        && tilt < 180 && horizontalRange > 0 && horizontalRange <= 360 && verticalRange > 0
                        && verticalRange <= 180)) {
                    throw new IllegalArgumentException("invalid viewport");
                }
            }
        }
    }
}
