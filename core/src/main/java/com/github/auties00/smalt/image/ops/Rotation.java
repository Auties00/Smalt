package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;

/**
 * Plans rotations by arbitrary angles: the canvas size, the map from canvas to picture, and the
 * exact path for multiples of 90 degrees.
 */
final class Rotation {
    /**
     * The tolerance under which the bounding box of a rotated picture is taken as a whole number
     * of pixels, absorbing the rounding of the sine and cosine.
     */
    private static final double EPSILON = 1e-9;

    /**
     * Prevents instantiation.
     */
    private Rotation() {
        throw new AssertionError();
    }

    /**
     * Rotates a picture clockwise about its center.
     *
     * @param picture the picture to rotate
     * @param degrees the finite angle in degrees
     * @param interpolation how to sample between pixels
     * @param canvas the canvas mode
     * @param argbBackground the background as {@code 0xAARRGGBB}
     * @return the rotated picture
     */
    static Picture rotate(Picture picture, double degrees, Interpolation interpolation, RotationCanvas canvas,
                          int argbBackground) {
        var normalized = degrees % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        var width = picture.width();
        var height = picture.height();
        if (normalized == 0) {
            return picture;
        }
        if (normalized == 180) {
            return ImageOps.orient(picture, Orientation.ROTATE_180);
        }
        if (normalized == 90 || normalized == 270) {
            var turned = ImageOps.orient(picture, normalized == 90 ? Orientation.ROTATE_90 : Orientation.ROTATE_270);
            return switch (canvas) {
                case EXPAND -> turned;
                case KEEP -> ImageOps.resizeCanvas(turned, width, height, Anchor.CENTER, argbBackground);
                case INSCRIBED -> {
                    var scale = Math.min((double) height / width, (double) width / height);
                    var w = (int) Math.max(1, Math.floor(scale * width + EPSILON));
                    var h = (int) Math.max(1, Math.floor(scale * height + EPSILON));
                    yield ImageOps.crop(turned, (turned.width() - w) / 2, (turned.height() - h) / 2, w, h);
                }
            };
        }
        var rotation = AffineTransform.rotation(normalized);
        var cos = Math.abs(rotation.m00());
        var sin = Math.abs(rotation.m10());
        var boxWidth = width * cos + height * sin;
        var boxHeight = width * sin + height * cos;
        int outWidth;
        int outHeight;
        switch (canvas) {
            case EXPAND -> {
                outWidth = size(Math.ceil(boxWidth - EPSILON));
                outHeight = size(Math.ceil(boxHeight - EPSILON));
            }
            case KEEP -> {
                outWidth = width;
                outHeight = height;
            }
            case INSCRIBED -> {
                var margin = switch (interpolation) {
                    case NEAREST -> 0;
                    case BILINEAR -> 0.5;
                    case BICUBIC -> 1.5;
                };
                var halfWidth = Math.max(0, width / 2.0 - margin);
                var halfHeight = Math.max(0, height / 2.0 - margin);
                var scale = Math.min(2 * halfWidth / boxWidth, 2 * halfHeight / boxHeight);
                outWidth = size(Math.floor(scale * width + EPSILON));
                outHeight = size(Math.floor(scale * height + EPSILON));
            }
            default -> throw new IllegalArgumentException("unknown canvas " + canvas);
        }
        OpChecks.checkResult(picture, outWidth, outHeight);
        var forward = AffineTransform.translation(-width / 2.0, -height / 2.0)
                .then(rotation)
                .then(AffineTransform.translation(outWidth / 2.0, outHeight / 2.0));
        var packed = Canvas.toPacked(picture);
        var hasAlpha = packed.hasAlpha() || (canvas != RotationCanvas.INSCRIBED && argbBackground >>> 24 != 255);
        return warp(packed, forward.inverse(), outWidth, outHeight, interpolation, argbBackground, hasAlpha);
    }

    /**
     * Resamples a packed picture through an affine map.
     *
     * @param packed the packed picture, 8 or 16-bit
     * @param inverse the map from output to picture coordinates
     * @param width the output width
     * @param height the output height
     * @param interpolation how to sample between pixels
     * @param argbBackground the background as {@code 0xAARRGGBB}
     * @param hasAlpha whether the result reports alpha
     * @return the resampled picture, at the input's depth
     */
    static Picture warp(Picture packed, AffineTransform inverse, int width, int height, Interpolation interpolation,
                        int argbBackground, boolean hasAlpha) {
        return switch (packed) {
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * width * height);
                yield Warp.rgba(rgba, inverse, width, height, interpolation, argbBackground, hasAlpha);
            }
            case Rgba16Picture rgba -> {
                OpChecks.checkArray(4L * width * height);
                yield Warp.rgba16(rgba, inverse, width, height, interpolation, argbBackground, hasAlpha);
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        };
    }

    /**
     * Converts a computed canvas side to an integer of at least 1.
     *
     * @param side the side, already rounded
     * @return the side as an integer
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the side exceeds the largest integer
     */
    private static int size(double side) {
        if (side > Integer.MAX_VALUE) {
            throw new com.github.auties00.smalt.MediaException.LimitExceeded("rotated picture side " + side + " is too large");
        }
        return (int) Math.max(1, side);
    }
}
