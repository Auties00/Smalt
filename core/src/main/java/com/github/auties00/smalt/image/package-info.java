/**
 * Pictures and the image codec contracts: planar YUV and RGBA pictures in 8 and 16 bits, color descriptions
 * (primaries, transfer, matrix, range, chroma siting, HDR metadata), orientation, image formats, and the
 * {@link Images} registry that decodes and encodes still images, animations and multi-page files with the
 * codecs present at run time.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.image;
