/**
 * Reading and writing Material Exchange Format files (SMPTE ST 377-1): {@link MxfDemuxer} reads the essence of
 * any operational pattern whose essence is internal to the file, {@link MxfMuxer} writes OP1a and OP-Atom files
 * with the settings of {@link MxfMuxerOptions}.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.container.mxf;
