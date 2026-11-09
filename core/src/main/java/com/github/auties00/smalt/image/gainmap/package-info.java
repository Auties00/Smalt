/**
 * HDR gain maps (ISO 21496-1, the Adobe gain map specification and Google's Ultra HDR): their metadata
 * ({@link GainMapMetadata}, {@link GainMapChannel}), the gain map with its image ({@link GainMap}), and the
 * rendering of a base image with its gain map for a display headroom and the computation of a gain map from two
 * renditions ({@link GainMaps}).
 *
 * <p>The decoders of the image artifacts read gain maps from Ultra HDR and Adobe JPEG files, the {@code tmap}
 * derived image items of AVIF and HEIF files, Apple's HEIC gain map auxiliary images and the JPEG XL
 * {@code jhgm} box, and the JPEG and AVIF encoders write them.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.image.gainmap;
