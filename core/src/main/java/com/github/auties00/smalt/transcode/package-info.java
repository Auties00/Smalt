/**
 * Converts media files: remuxing, transcoding, trimming, scaling and resampling, the operations
 * applications otherwise run the ffmpeg command line for.
 *
 * <p>{@link com.github.auties00.smalt.transcode.Transcoder} is the entry point. A job is described
 * by immutable options: {@link com.github.auties00.smalt.transcode.TranscodeOptions} for
 * audio/video (the {@link com.github.auties00.smalt.transcode.OutputContainer}, one
 * {@link com.github.auties00.smalt.transcode.VideoTranscodeOptions} and one
 * {@link com.github.auties00.smalt.transcode.AudioTranscodeOptions}, the time range, a progress
 * listener) and {@link com.github.auties00.smalt.transcode.ImageTranscodeOptions} for images.
 *
 * <p>Design:
 * <ul>
 *   <li><b>Remux when possible.</b> Every stream has a
 *       {@link com.github.auties00.smalt.transcode.StreamMode}; the default copies a stream when
 *       the output container can carry its codec and no requested operation needs its decoded
 *       content, and encodes it otherwise. A whole-file copy into MP4, M4A or QuickTime goes
 *       through {@link com.github.auties00.smalt.container.mp4.Mp4Remuxer}.</li>
 *   <li><b>Codecs from the registries.</b> Encoders and decoders are created through the
 *       registries with the codec-independent options
 *       ({@link com.github.auties00.smalt.video.VideoEncoderOptions},
 *       {@link com.github.auties00.smalt.audio.AudioEncoderOptions},
 *       {@link com.github.auties00.smalt.image.ImageEncoderOptions}), so this package names no
 *       codec implementation and the artifacts present decide what is available. A caller that
 *       needs a codec's own parameters encodes with that codec directly and muxes the packets.</li>
 *   <li><b>Exact time ranges.</b> Encoded streams are trimmed to the frame and the sample; the
 *       end of encoded audio is marked through the duration of its last packet, which muxers turn
 *       into an edit list, a final Ogg granule position or Matroska discard padding. Copied
 *       streams start at a decodable packet and rely on MP4 edit lists for an exact start.</li>
 *   <li><b>Streaming and bounded memory.</b> One demuxer feeds all streams in file order; the
 *       caller's thread runs the job and codecs use the module's shared pool.</li>
 *   <li><b>Seek when possible.</b> A start time seeks the demuxer when it supports seeking, and
 *       otherwise decodes and drops the media before it, with the same output; subtitle packets
 *       stored before the seek point are read through a second demuxer, so that the cues still
 *       shown at the start are kept.</li>
 *   <li><b>Video processing.</b> Encoded video can be converted between color descriptions (tone
 *       mapping included), deinterlaced ({@link com.github.auties00.smalt.transcode.Deinterlacer}),
 *       converted to a constant rate by repeating or blending frames
 *       ({@link com.github.auties00.smalt.transcode.FrameRateConversion}), telecined or inverse
 *       telecined ({@link com.github.auties00.smalt.transcode.Pulldown}), and overlaid with
 *       burned-in subtitles ({@link com.github.auties00.smalt.transcode.SubtitleBurnIn}) and a
 *       watermark; {@link com.github.auties00.smalt.transcode.VideoTranscodeOptions} gives the
 *       order of the steps.</li>
 * </ul>
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.transcode;
