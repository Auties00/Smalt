package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.YuvPicture;

import java.io.ByteArrayOutputStream;

/**
 * Helpers shared by the tests of the internal package.
 */
final class JpegTestSupportInternal {
    private JpegTestSupportInternal() {
        throw new AssertionError();
    }

    /**
     * Concatenates the tight planes of a picture in Y, Cb, Cr order.
     */
    static byte[] planar(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        if (picture.chromaFormat().hasChroma()) {
            for (var plane : new byte[][]{picture.u(), picture.v()}) {
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    out.write(plane, y * picture.uvStride(), picture.chromaWidth());
                }
            }
        }
        return out.toByteArray();
    }
}
