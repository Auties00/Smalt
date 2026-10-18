/**
 * Byte sources and sinks: {@link MediaSource} is the random-access input every decoder, demuxer and prober
 * reads, and {@link MediaSink} the output every encoder and muxer writes.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.io;
