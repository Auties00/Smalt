package com.github.auties00.smalt.internal.sei;

import com.github.auties00.smalt.image.AmbientViewingEnvironment;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentColorVolume;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.OmnidirectionalVideo;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Parses the SEI messages of Rec. ITU-T H.274 that describe the viewing conditions, color volume, sample shape and
 * spherical mapping of pictures: the ambient viewing environment (clause 8.13), content colour volume (8.14),
 * equirectangular projection (8.15.2), generalized cubemap projection (8.15.3), sphere rotation (8.15.4), region-wise
 * packing (8.15.5), omnidirectional viewport (8.15.6) and sample aspect ratio information (8.17) messages.
 *
 * <p>Every method returns {@code null} for a truncated payload or a message whose values the semantics forbid or
 * tell decoders to ignore.
 */
public final class H274Sei {
    /**
     * The payload type of the ambient viewing environment message.
     */
    public static final int AMBIENT_VIEWING_ENVIRONMENT = 148;

    /**
     * The payload type of the content colour volume message.
     */
    public static final int CONTENT_COLOUR_VOLUME = 149;

    /**
     * The payload type of the equirectangular projection message.
     */
    public static final int EQUIRECTANGULAR_PROJECTION = 150;

    /**
     * The payload type of the generalized cubemap projection message.
     */
    public static final int GENERALIZED_CUBEMAP_PROJECTION = 153;

    /**
     * The payload type of the sphere rotation message.
     */
    public static final int SPHERE_ROTATION = 154;

    /**
     * The payload type of the region-wise packing message.
     */
    public static final int REGION_WISE_PACKING = 155;

    /**
     * The payload type of the omnidirectional viewport message.
     */
    public static final int OMNI_VIEWPORT = 156;

    /**
     * The payload type of the sample aspect ratio information message.
     */
    public static final int SAMPLE_ASPECT_RATIO_INFO = 204;

    /**
     * The scale of the 0.00002 chromaticity increments.
     */
    private static final double CHROMATICITY_UNIT = 0.00002;

    /**
     * The scale of the 2<sup>-16</sup> degree angle units.
     */
    private static final double ANGLE_UNIT = 1.0 / 65536;

    private H274Sei() {
        throw new AssertionError();
    }

    /**
     * A message with the cancel and persistence flags of its kind.
     *
     * @param cancel whether the message cancels the persistence of the previous one
     * @param persists whether the message applies to the pictures output after its own
     * @param value what the message describes, or {@code null} for a cancelling message
     * @param <T> the type of the description
     */
    public record Message<T>(boolean cancel, boolean persists, T value) implements SeiPersistence.Message {
    }

    /**
     * Parses an ambient viewing environment message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the environment, or {@code null} when the illuminance is 0 or a chromaticity coordinate exceeds 50000
     */
    public static AmbientViewingEnvironment ambientViewing(H264BitReader reader) {
        var illuminance = reader.readLong(32);
        var x = reader.readBits(16);
        var y = reader.readBits(16);
        if (reader.overrun() || illuminance == 0 || x > 50_000 || y > 50_000) {
            return null;
        }
        return new AmbientViewingEnvironment(illuminance * 0.0001,
                new Chromaticity(x * CHROMATICITY_UNIT, y * CHROMATICITY_UNIT));
    }

    /**
     * Parses a content colour volume message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, or {@code null} when it signals no value or a primary coordinate is out of range
     */
    public static Message<ContentColorVolume> contentColorVolume(H264BitReader reader) {
        if (reader.readFlag()) {
            return reader.overrun() ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        var primariesPresent = reader.readFlag();
        var minPresent = reader.readFlag();
        var maxPresent = reader.readFlag();
        var averagePresent = reader.readFlag();
        reader.readBits(2);
        var primaries = new ArrayList<ContentColorVolume.Coordinate>(3);
        if (primariesPresent) {
            for (var c = 0; c < 3; c++) {
                var x = reader.readBits(32);
                var y = reader.readBits(32);
                if (Math.abs((long) x) > 5_000_000 || Math.abs((long) y) > 5_000_000) {
                    return null;
                }
                primaries.add(new ContentColorVolume.Coordinate(x * CHROMATICITY_UNIT, y * CHROMATICITY_UNIT));
            }
        }
        var min = minPresent ? OptionalDouble.of(reader.readLong(32) * 0.0000001) : OptionalDouble.empty();
        var max = maxPresent ? OptionalDouble.of(reader.readLong(32) * 0.0000001) : OptionalDouble.empty();
        var average = averagePresent ? OptionalDouble.of(reader.readLong(32) * 0.0000001) : OptionalDouble.empty();
        if (reader.overrun() || !(primariesPresent || minPresent || maxPresent || averagePresent)) {
            return null;
        }
        return new Message<>(false, persists, new ContentColorVolume(primaries, min, max, average));
    }

    /**
     * Parses a sample aspect ratio information message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, whose ratio is {@code null} for an unspecified, reserved or zero ratio, or {@code null}
     */
    public static Message<Rational> sampleAspectRatio(H264BitReader reader) {
        if (reader.readFlag()) {
            return reader.overrun() ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        var idc = reader.readBits(8);
        Rational ratio;
        if (idc == 255) {
            var width = reader.readBits(16);
            var height = reader.readBits(16);
            ratio = width > 0 && height > 0 ? new Rational(width, height).reduce() : null;
        } else {
            ratio = VideoConfigs.aspectRatioOf(idc);
        }
        return reader.overrun() ? null : new Message<>(false, persists, ratio);
    }

    /**
     * Parses an equirectangular projection message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, or {@code null}
     */
    public static Message<OmnidirectionalVideo.Projection> equirectangular(H264BitReader reader) {
        if (reader.readFlag()) {
            return reader.overrun() ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        var guardBand = reader.readFlag();
        reader.readBits(2);
        Optional<OmnidirectionalVideo.Equirectangular.GuardBands> bands = Optional.empty();
        if (guardBand) {
            var type = reader.readBits(3);
            var left = reader.readBits(8);
            var right = reader.readBits(8);
            bands = Optional.of(new OmnidirectionalVideo.Equirectangular.GuardBands(type, left, right));
        }
        return reader.overrun() ? null
                : new Message<>(false, persists, new OmnidirectionalVideo.Equirectangular(bands));
    }

    /**
     * Parses a generalized cubemap projection message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, or {@code null}
     */
    public static Message<OmnidirectionalVideo.Projection> generalizedCubemap(H264BitReader reader) {
        if (reader.readFlag()) {
            return reader.overrun() ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        var packing = reader.readBits(3);
        var mapping = reader.readBits(2);
        var count = packing == 4 || packing == 5 ? 5 : 6;
        var faces = new ArrayList<OmnidirectionalVideo.GeneralizedCubemap.Face>(count);
        for (var i = 0; i < count; i++) {
            var index = reader.readBits(3);
            var rotation = reader.readBits(2);
            var coefficientU = 0;
            var uByV = false;
            var coefficientV = 0;
            var vByU = false;
            if (mapping == 2) {
                coefficientU = reader.readBits(7);
                uByV = reader.readFlag();
                coefficientV = reader.readBits(7);
                vByU = reader.readFlag();
            }
            faces.add(new OmnidirectionalVideo.GeneralizedCubemap.Face(index, rotation, coefficientU, uByV,
                    coefficientV, vByU));
        }
        Optional<OmnidirectionalVideo.GeneralizedCubemap.CubemapGuardBand> band = Optional.empty();
        if (reader.readFlag()) {
            var type = reader.readBits(3);
            var exterior = reader.readFlag();
            var samples = reader.readBits(4) + 1;
            band = Optional.of(new OmnidirectionalVideo.GeneralizedCubemap.CubemapGuardBand(type, exterior, samples));
        }
        return reader.overrun() ? null : new Message<>(false, persists,
                new OmnidirectionalVideo.GeneralizedCubemap(packing, mapping, faces, band));
    }

    /**
     * Parses a sphere rotation message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, or {@code null} when an angle is out of range
     */
    public static Message<OmnidirectionalVideo.SphereRotation> sphereRotation(H264BitReader reader) {
        if (reader.readFlag()) {
            return reader.overrun() ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        reader.readBits(6);
        var yaw = reader.readBits(32);
        var pitch = reader.readBits(32);
        var roll = reader.readBits(32);
        if (reader.overrun() || !inRange(yaw, 180) || pitch < -90 * 65536 || pitch > 90 * 65536
                || !inRange(roll, 180)) {
            return null;
        }
        return new Message<>(false, persists,
                new OmnidirectionalVideo.SphereRotation(yaw * ANGLE_UNIT, pitch * ANGLE_UNIT, roll * ANGLE_UNIT));
    }

    /**
     * Parses a region-wise packing message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, or {@code null} when a picture size is 0
     */
    public static Message<OmnidirectionalVideo.RegionWisePacking> regionWisePacking(H264BitReader reader) {
        if (reader.readFlag()) {
            return reader.overrun() ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        var matching = reader.readFlag();
        reader.readBits(5);
        var count = reader.readBits(8);
        var projectedWidth = reader.readLong(32);
        var projectedHeight = reader.readLong(32);
        var packedWidth = reader.readBits(16);
        var packedHeight = reader.readBits(16);
        var regions = new ArrayList<OmnidirectionalVideo.RegionWisePacking.Region>(count);
        for (var i = 0; i < count && !reader.overrun(); i++) {
            reader.readBits(4);
            var transform = reader.readBits(3);
            var guarded = reader.readFlag();
            var regionWidth = reader.readLong(32);
            var regionHeight = reader.readLong(32);
            var regionTop = reader.readLong(32);
            var regionLeft = reader.readLong(32);
            var width = reader.readBits(16);
            var height = reader.readBits(16);
            var top = reader.readBits(16);
            var left = reader.readBits(16);
            Optional<OmnidirectionalVideo.RegionWisePacking.RegionGuardBands> bands = Optional.empty();
            if (guarded) {
                var leftBand = reader.readBits(8);
                var rightBand = reader.readBits(8);
                var topBand = reader.readBits(8);
                var bottomBand = reader.readBits(8);
                var notUsed = reader.readFlag();
                var types = new ArrayList<Integer>(4);
                for (var j = 0; j < 4; j++) {
                    types.add(reader.readBits(3));
                }
                reader.readBits(3);
                bands = Optional.of(new OmnidirectionalVideo.RegionWisePacking.RegionGuardBands(leftBand, rightBand,
                        topBand, bottomBand, notUsed, types));
            }
            regions.add(new OmnidirectionalVideo.RegionWisePacking.Region(transform, regionWidth, regionHeight,
                    regionTop, regionLeft, width, height, top, left, bands));
        }
        if (reader.overrun() || projectedWidth == 0 || projectedHeight == 0 || packedWidth == 0
                || packedHeight == 0) {
            return null;
        }
        return new Message<>(false, persists, new OmnidirectionalVideo.RegionWisePacking(matching, projectedWidth,
                projectedHeight, packedWidth, packedHeight, regions));
    }

    /**
     * Parses an omnidirectional viewport message.
     *
     * @param reader the reader positioned at the start of the payload, limited to it
     * @return the message, or {@code null} for a reserved identifier from 512 to 1023 or an angle out of range
     */
    public static Message<OmnidirectionalVideo.Viewports> omniViewport(H264BitReader reader) {
        var id = reader.readBits(10);
        if (reader.readFlag()) {
            return reader.overrun() || id >= 512 ? null : new Message<>(true, false, null);
        }
        var persists = reader.readFlag();
        var count = reader.readBits(4) + 1;
        var viewports = new ArrayList<OmnidirectionalVideo.Viewports.Viewport>(count);
        for (var i = 0; i < count; i++) {
            var azimuth = reader.readBits(32);
            var elevation = reader.readBits(32);
            var tilt = reader.readBits(32);
            var horizontal = reader.readLong(32);
            var vertical = reader.readLong(32);
            if (!inRange(azimuth, 180) || elevation < -90 * 65536 || elevation > 90 * 65536 || !inRange(tilt, 180)
                    || horizontal < 1 || horizontal > 360L * 65536 || vertical < 1 || vertical > 180L * 65536) {
                return null;
            }
            viewports.add(new OmnidirectionalVideo.Viewports.Viewport(azimuth * ANGLE_UNIT, elevation * ANGLE_UNIT,
                    tilt * ANGLE_UNIT, horizontal * ANGLE_UNIT, vertical * ANGLE_UNIT));
        }
        if (reader.overrun() || id >= 512) {
            return null;
        }
        return new Message<>(false, persists, new OmnidirectionalVideo.Viewports(id, viewports));
    }

    /**
     * Combines the omnidirectional messages that apply to a picture into its description.
     *
     * <p>The sphere rotation, region-wise packing and viewports apply only with a projection, and the projection is
     * ignored when a frame packing arrangement applies whose views are not side by side, top and bottom or
     * alternating frames on a plain grid (H.274 clause 8.15.2.2).
     *
     * @param projection the projection message that applies, or {@code null}
     * @param rotation the sphere rotation message that applies, or {@code null}
     * @param packing the region-wise packing message that applies, or {@code null}
     * @param viewports the omnidirectional viewport message that applies, or {@code null}
     * @param framePacking the frame packing arrangement that applies, or {@code null}
     * @return the description, or {@code null} when no projection applies
     */
    public static OmnidirectionalVideo omnidirectional(Message<OmnidirectionalVideo.Projection> projection,
                                                       Message<OmnidirectionalVideo.SphereRotation> rotation,
                                                       Message<OmnidirectionalVideo.RegionWisePacking> packing,
                                                       Message<OmnidirectionalVideo.Viewports> viewports,
                                                       FramePacking framePacking) {
        if (projection == null || projection.value() == null
                || (framePacking != null && !effectivelyApplicable(framePacking))) {
            return null;
        }
        return new OmnidirectionalVideo(Optional.of(projection.value()), valueOf(rotation), valueOf(packing),
                valueOf(viewports));
    }

    /**
     * {@return whether a frame packing arrangement is one under which a projection still applies}
     *
     * @param packing the arrangement
     */
    private static boolean effectivelyApplicable(FramePacking packing) {
        var arrangement = packing.arrangement();
        return (arrangement == FramePacking.Arrangement.SIDE_BY_SIDE
                || arrangement == FramePacking.Arrangement.TOP_BOTTOM
                || arrangement == FramePacking.Arrangement.FRAME_ALTERNATION)
                && !packing.quincunxSampling() && !packing.spatialFlipping() && !packing.fieldViews()
                && packing.frame0GridX() == 0 && packing.frame0GridY() == 0 && packing.frame1GridX() == 0
                && packing.frame1GridY() == 0;
    }

    /**
     * {@return the description of a message, empty for none}
     *
     * @param message the message, or {@code null}
     * @param <T> the type of the description
     */
    private static <T> Optional<T> valueOf(Message<T> message) {
        return message == null ? Optional.empty() : Optional.ofNullable(message.value());
    }

    /**
     * {@return whether an angle in units of 2<sup>-16</sup> degrees lies from {@code -limit} inclusive to
     * {@code limit} degrees exclusive}
     *
     * @param value the angle
     * @param limit the limit in degrees
     */
    private static boolean inRange(int value, int limit) {
        return value >= -limit * 65536 && value < limit * 65536;
    }
}
