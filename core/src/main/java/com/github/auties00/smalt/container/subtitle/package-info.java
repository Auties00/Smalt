/**
 * Text subtitle files: demuxers and muxers of SubRip ({@code .srt}), WebVTT ({@code .vtt}), SubStation Alpha
 * and Advanced SubStation Alpha ({@code .ssa}, {@code .ass}), TTML ({@code .ttml}, {@code .dfxp}), SAMI
 * ({@code .smi}), LRC ({@code .lrc}) and MicroDVD ({@code .sub}).
 *
 * <p>Each file holds one subtitle track, except a SAMI file, which holds one per language. The demuxers read the
 * whole file, from a source of known or unknown size, tolerate the irregularities players accept (encodings,
 * line breaks, missing counters and blank lines, unsorted cues) and return the cues in start order; the text
 * encoding of a file without a byte order mark is the one
 * {@link com.github.auties00.smalt.container.DemuxerOptions#charset()} names, or one detected. The SubRip and
 * WebVTT muxers write cue by cue in order, so a streaming sink works; the others write their file when they
 * finish. Subtitle tracks inside MP4 and Matroska files are handled by those containers, and the
 * {@code subtitle} package converts cues between the formats.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.container.subtitle;
