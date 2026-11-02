/**
 * Subtitles and captions: the text cue model, reading and writing cues, converting between SubRip, WebVTT,
 * 3GPP timed text, ASS and TTML, the picture cues of bitmap subtitles, and teletext and closed captions.
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
 * <p>Bitmap subtitle tracks ({@link com.github.auties00.smalt.track.Codec#isBitmapSubtitle()}: PGS, DVB subtitles
 * and DVD subpictures) decode with {@link com.github.auties00.smalt.subtitle.BitmapSubtitleDecoder} into
 * {@link com.github.auties00.smalt.subtitle.BitmapCue}s, pictures placed on a canvas, which
 * {@link com.github.auties00.smalt.subtitle.Subtitles#readBitmaps} reads from any file. Teletext and closed caption
 * tracks ({@link com.github.auties00.smalt.track.Codec#isCaption()}) decode to text cues with
 * {@link com.github.auties00.smalt.subtitle.SubtitleDecoder}, one
 * {@link com.github.auties00.smalt.subtitle.CaptionService} at a time;
 * {@link com.github.auties00.smalt.subtitle.ClosedCaptions} and
 * {@link com.github.auties00.smalt.subtitle.CaptionExtractor} read the captions carried inside video packets.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.subtitle;
