package com.github.auties00.smalt.image.jpeg;

/**
 * Enumerates which marker segments of the source {@link JpegTransforms} copies to its output,
 * the choices of {@code jpegtran -copy}.
 *
 * <p>The output always carries the segments its color space needs (a JFIF {@code APP0} for gray
 * and YCbCr images, an Adobe {@code APP14} for RGB, CMYK and YCCK images), and a copied segment
 * that would duplicate one of them is dropped. When the first copied segment is an EXIF
 * {@code APP1}, no JFIF segment is written, as EXIF files have none.
 */
public enum JpegMarkerCopy {
    /**
     * Copies nothing.
     */
    NONE,

    /**
     * Copies the comment ({@code COM}) segments.
     */
    COMMENTS,

    /**
     * Copies the {@code APP2} segments, which carry the ICC profile.
     */
    ICC,

    /**
     * Copies every comment and application segment: EXIF, XMP, ICC, IPTC and the rest.
     */
    ALL
}
