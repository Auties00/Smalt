/**
 * Loudness measurement, silence detection and loudness normalization.
 *
 * <p>{@link com.github.auties00.smalt.audio.loudness.LoudnessMeter} measures loudness as ITU-R
 * BS.1770-4 and EBU R128 define it (momentary, short-term and integrated loudness, loudness range,
 * sample and true peak) over a stream of samples with bounded memory;
 * {@link com.github.auties00.smalt.audio.loudness.Loudness} measures a whole file.
 * {@link com.github.auties00.smalt.audio.loudness.LoudnessNormalization} turns the measurements
 * into the gain that reaches a target loudness under a true-peak ceiling, which the transcode
 * package applies in two passes.
 * {@link com.github.auties00.smalt.audio.loudness.SilenceDetector} finds the stretches below a
 * level that last at least a minimum duration.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.audio.loudness;
