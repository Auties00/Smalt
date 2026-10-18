/**
 * The entry points and shared model of Smalt: {@link Media} probes and opens media files,
 * {@link MediaException} and its subtypes report every failure, {@link MediaLimits} bounds the resources a
 * decoder may use, and {@link MediaRuntime} reports the Vector API and threading configuration.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt;
