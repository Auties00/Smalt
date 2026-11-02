/**
 * Video frames and the video codec contracts: {@link VideoFrame}, the {@link VideoDecoder} and
 * {@link VideoEncoder} interfaces, the {@link VideoDecoders} and {@link VideoEncoders} registries, and
 * {@link VideoReader}, which decodes the video of a file, and {@link VideoWriter}, which encodes pictures into a
 * new one.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.video;
