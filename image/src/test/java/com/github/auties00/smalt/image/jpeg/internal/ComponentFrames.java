package com.github.auties00.smalt.image.jpeg.internal;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Writes DCT JPEG streams of any number of components with any sampling factors, for the tests outside this
 * package: the frame is coded from smooth synthetic planes by {@link TestJpegWriter}, with its scans laid out as
 * asked.
 */
public final class ComponentFrames {
    /**
     * How the scans of a stream hold its components.
     */
    public enum Scans {
        /**
         * One sequential scan of every component; at most four components of at most ten blocks per MCU.
         */
        INTERLEAVED,
        /**
         * One sequential scan per component.
         */
        SEPARATE,
        /**
         * A progressive script of one scan per component and pass: DC at one bit less, AC at one bit less, the
         * last DC bit, the last AC bit.
         */
        PROGRESSIVE
    }

    private ComponentFrames() {
    }

    /**
     * Writes a stream with component identifiers 1, 2, ... and no JFIF or Adobe segment.
     *
     * @param width the image width
     * @param height the image height
     * @param h the horizontal sampling factor of each component
     * @param v the vertical sampling factor of each component
     * @param seed the seed of the synthetic planes
     * @param scans the scan layout
     * @return the stream
     */
    public static byte[] write(int width, int height, int[] h, int[] v, int seed, Scans scans) {
        return write(width, height, h, v, seed, scans, false, -1);
    }

    /**
     * Writes a stream with component identifiers 1, 2, ...
     *
     * @param width the image width
     * @param height the image height
     * @param h the horizontal sampling factor of each component
     * @param v the vertical sampling factor of each component
     * @param seed the seed of the synthetic planes
     * @param scans the scan layout
     * @param jfif whether to write a JFIF segment
     * @param adobeTransform the transform of an Adobe segment, or -1 for none
     * @return the stream
     */
    public static byte[] write(int width, int height, int[] h, int[] v, int seed, Scans scans, boolean jfif,
                               int adobeTransform) {
        var count = h.length;
        var hMax = Arrays.stream(h).max().orElseThrow();
        var vMax = Arrays.stream(v).max().orElseThrow();
        var ids = new int[count];
        var planes = new byte[count][];
        for (var c = 0; c < count; c++) {
            ids[c] = c + 1;
            planes[c] = JpegStreamTest.plane(JpegFrame.ceilDiv((long) width * h[c], hMax),
                    JpegFrame.ceilDiv((long) height * v[c], vMax), seed + c);
        }
        var writer = TestJpegWriter.fromPlanes(width, height, ids, h, v, planes, 85);
        writer.jfif = jfif;
        writer.adobeTransform = adobeTransform;
        return switch (scans) {
            case INTERLEAVED -> {
                var all = new int[count];
                for (var c = 0; c < count; c++) {
                    all[c] = c;
                }
                yield writer.sequential(all);
            }
            case SEPARATE -> {
                var each = new int[count][];
                for (var c = 0; c < count; c++) {
                    each[c] = new int[]{c};
                }
                yield writer.sequential(each);
            }
            case PROGRESSIVE -> {
                var script = new ArrayList<TestJpegWriter.Scan>();
                for (var c = 0; c < count; c++) {
                    script.add(TestJpegWriter.Scan.of(0, 0, 0, 1, c));
                }
                for (var c = 0; c < count; c++) {
                    script.add(TestJpegWriter.Scan.of(1, 63, 0, 1, c));
                }
                for (var c = 0; c < count; c++) {
                    script.add(TestJpegWriter.Scan.of(0, 0, 1, 0, c));
                }
                for (var c = 0; c < count; c++) {
                    script.add(TestJpegWriter.Scan.of(1, 63, 1, 0, c));
                }
                yield writer.progressive(script);
            }
        };
    }
}
