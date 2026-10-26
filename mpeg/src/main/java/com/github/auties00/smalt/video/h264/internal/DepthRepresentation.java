package com.github.auties00.smalt.video.h264.internal;

/**
 * Parses the depth representation information SEI message of multiview streams with depth (ITU-T H.264
 * H.13.1.3, {@code payloadType} 50) into the table that maps decoded depth samples to uniformly quantized
 * disparity.
 */
final class DepthRepresentation {
    private DepthRepresentation() {
    }

    /**
     * Reads a {@code depth_representation_info()} message and returns its {@code DepthLUT} (ITU-T H.264
     * H.13.2.3) when it declares non-linearly mapped disparity ({@code depth_representation_type} 3).
     *
     * @param reader the reader positioned at the message payload
     * @return the 256 entries of {@code DepthLUT}, each from 0 to 255, or {@code null} when the depth samples
     * already represent a uniformly quantized quantity or the message is truncated
     */
    static int[] parseLut(H264BitReader reader) {
        var allViewsEqual = reader.readFlag();
        var views = allViewsEqual ? 1 : reader.readUe() + 1;
        if (views > 1024) {
            return null;
        }
        var zNear = reader.readFlag();
        var zFar = reader.readFlag();
        var zAxisEqual = false;
        if (zNear || zFar) {
            zAxisEqual = reader.readFlag();
            if (zAxisEqual) {
                reader.readUe();
            }
        }
        var dMin = reader.readFlag();
        var dMax = reader.readFlag();
        var type = reader.readUe();
        for (var i = 0; i < views; i++) {
            reader.readUe();
            if ((zNear || zFar) && !zAxisEqual) {
                reader.readUe();
            }
            if (dMin || dMax) {
                reader.readUe();
            }
            for (var present : new boolean[]{zNear, zFar, dMin, dMax}) {
                if (present) {
                    reader.readBits(8);
                    reader.skipBits(reader.readBits(5) + 1);
                }
            }
            if (reader.overrun()) {
                return null;
            }
        }
        if (type != 3) {
            return null;
        }
        var segments = reader.readUe() + 2;
        if (segments > 64) {
            return null;
        }
        var model = new int[segments + 1];
        for (var i = 1; i < segments; i++) {
            model[i] = reader.readUe();
        }
        if (reader.overrun()) {
            return null;
        }
        var lut = new int[256];
        for (var k = 0; k < segments; k++) {
            var pos1 = 255 * k / segments;
            var pos2 = 255 * (k + 1) / segments;
            var x1 = pos1 - model[k];
            var y1 = pos1 + model[k];
            var x2 = pos2 - model[k + 1];
            var y2 = pos2 + model[k + 1];
            var span = x2 - x1;
            for (var x = Math.max(x1, 0); x <= Math.min(x2, 255); x++) {
                var value = span == 0 ? y1 : roundDivide((long) (x - x1) * (y2 - y1) + (long) y1 * span, span);
                lut[x] = (int) Math.clamp(value, 0, 255);
            }
        }
        return lut;
    }

    /**
     * Returns {@code numerator / denominator} rounded half away from zero ({@code Round()} of ITU-T H.264
     * 5.7).
     *
     * @param numerator the dividend
     * @param denominator the denominator, positive
     * @return the rounded quotient
     */
    private static long roundDivide(long numerator, long denominator) {
        var magnitude = (2 * Math.abs(numerator) + denominator) / (2 * denominator);
        return numerator < 0 ? -magnitude : magnitude;
    }
}
