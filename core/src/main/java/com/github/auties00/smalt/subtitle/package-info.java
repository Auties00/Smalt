/**
 * Text subtitles: the cue model, reading and writing cues, and converting between SubRip, WebVTT,
 * 3GPP timed text, ASS and TTML.
 *
 * <p>Subtitle tracks are {@link com.github.auties00.smalt.track.DataTrack}s whose
 * {@link com.github.auties00.smalt.track.Codec} is a text subtitle codec
 * ({@link com.github.auties00.smalt.track.Codec#isSubtitle()}), their packets one cue each in
 * the form the codec documents, or {@link com.github.auties00.smalt.track.Codec#TTML}, their packets documents
 * of cues. {@link com.github.auties00.smalt.subtitle.SubtitleDecoder} and
 * {@link com.github.auties00.smalt.subtitle.SubtitleEncoder} turn packets into
 * {@link com.github.auties00.smalt.subtitle.SubtitleCue}s and back,
 * {@link com.github.auties00.smalt.subtitle.SubtitleReader} reads the cues of a file, and
 * {@link com.github.auties00.smalt.subtitle.Subtitles} converts markup and writes every text subtitle
 * file. The transcode package copies or converts subtitle tracks when it changes containers.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.subtitle;
