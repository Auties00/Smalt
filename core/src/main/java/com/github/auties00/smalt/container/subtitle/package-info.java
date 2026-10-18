/**
 * Text subtitle files: SubRip ({@code .srt}) and WebVTT ({@code .vtt}) demuxers and muxers.
 *
 * <p>Each file holds one subtitle track. The demuxers read the whole file, tolerate the
 * irregularities players accept (encodings, line breaks, missing counters and blank lines) and
 * return the cues in start order; the muxers write cue by cue in order, so a streaming sink works.
 * Subtitle tracks inside MP4 and Matroska files are handled by those containers, and the
 * {@code subtitle} package converts cues between the formats.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.container.subtitle;
