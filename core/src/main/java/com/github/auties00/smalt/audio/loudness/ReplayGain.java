package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.metadata.MediaTags;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Holds the playback gains of a track as ReplayGain 2.0 defines them: the gain that brings the track,
 * and the gain that brings its album, to the ReplayGain reference loudness of {@value #REFERENCE_LUFS}
 * LUFS, each with the peak that gain may push into clipping.
 *
 * <p>Gains are in decibels and peaks are linear magnitudes, 1.0 being full scale; an absent value is
 * {@code NaN}. The gains are measured as EBU R128 integrated loudness (ITU-R BS.1770-4, see
 * {@link LoudnessMeter}); an album's loudness gates the 400 ms blocks of all its tracks together, and
 * its peak is the largest track peak.
 *
 * <p>The values travel in tags in one of two forms ({@link TagFormat}): the {@code REPLAYGAIN_*}
 * fields most formats use, and the {@code R128_*} fields RFC 7845 section 5.2.1 defines for Opus.
 * {@link #fromTags(MediaTags)} reads either form and {@link #toTags(MediaTags, TagFormat)} writes one.
 *
 * @param trackGainDb the gain that brings the track to {@value #REFERENCE_LUFS} LUFS, in dB, or
 * {@code NaN} when unknown
 * @param trackPeak the largest magnitude of the track, the true peak when it was measured, or
 * {@code NaN} when unknown
 * @param albumGainDb the gain that brings the album to {@value #REFERENCE_LUFS} LUFS, in dB, or
 * {@code NaN} when unknown
 * @param albumPeak the largest magnitude of the album, or {@code NaN} when unknown
 */
public record ReplayGain(double trackGainDb, double trackPeak, double albumGainDb, double albumPeak) {
    /**
     * The ReplayGain 2.0 reference loudness, in LUFS.
     */
    public static final double REFERENCE_LUFS = -18;

    /**
     * The reference loudness of the Opus {@code R128_*} gains, in LUFS (EBU R128).
     */
    public static final double R128_REFERENCE_LUFS = -23;

    /**
     * The largest gain magnitude a tag may carry, in dB.
     */
    private static final double MAX_GAIN_DB = 200;

    /**
     * The track gain field of the {@code REPLAYGAIN_*} form.
     */
    public static final String TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN";

    /**
     * The track peak field of the {@code REPLAYGAIN_*} form.
     */
    public static final String TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK";

    /**
     * The album gain field of the {@code REPLAYGAIN_*} form.
     */
    public static final String ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN";

    /**
     * The album peak field of the {@code REPLAYGAIN_*} form.
     */
    public static final String ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK";

    /**
     * The reference loudness field of the {@code REPLAYGAIN_*} form.
     */
    public static final String REFERENCE_LOUDNESS = "REPLAYGAIN_REFERENCE_LOUDNESS";

    /**
     * The Opus track gain field, a Q7.8 decibel integer relative to {@value #R128_REFERENCE_LUFS} LUFS.
     */
    public static final String R128_TRACK_GAIN = "R128_TRACK_GAIN";

    /**
     * The Opus album gain field, a Q7.8 decibel integer relative to {@value #R128_REFERENCE_LUFS} LUFS.
     */
    public static final String R128_ALBUM_GAIN = "R128_ALBUM_GAIN";

    /**
     * Selects the tag fields the gains are written to.
     */
    public enum TagFormat {
        /**
         * The {@code REPLAYGAIN_TRACK_GAIN}, {@code REPLAYGAIN_TRACK_PEAK}, {@code REPLAYGAIN_ALBUM_GAIN},
         * {@code REPLAYGAIN_ALBUM_PEAK} and {@code REPLAYGAIN_REFERENCE_LOUDNESS} fields of ReplayGain 2.0,
         * gains written as {@code "-6.48 dB"} and peaks as {@code "0.988553"}.
         */
        REPLAYGAIN,

        /**
         * The {@code R128_TRACK_GAIN} and {@code R128_ALBUM_GAIN} fields of RFC 7845 for Opus: decimal
         * integers in 1/256 dB relative to {@value ReplayGain#R128_REFERENCE_LUFS} LUFS, applied on top of the
         * output gain of the Opus header. They carry no peak.
         */
        R128
    }

    /**
     * Selects which gain a player applies.
     */
    public enum Mode {
        /**
         * The track gain, which levels every track on its own.
         */
        TRACK,

        /**
         * The album gain, which keeps the loudness differences between the tracks of an album; the track
         * gain when the album gain is absent.
         */
        ALBUM
    }

    /**
     * Returns the gains of a measured track, with no album values.
     *
     * @param stats the measurements of the track
     * @return the gains; the gain is {@code NaN} when the track is silent (no block above the
     * absolute gate)
     */
    public static ReplayGain ofTrack(LoudnessStats stats) {
        Objects.requireNonNull(stats, "stats");
        return new ReplayGain(gainOf(stats.integrated()), peakOf(stats), Double.NaN, Double.NaN);
    }

    /**
     * Returns the gain that brings an integrated loudness to the reference.
     *
     * @param integrated the loudness in LUFS
     * @return the gain in dB, {@code NaN} when the loudness is not finite
     */
    static double gainOf(double integrated) {
        return Double.isFinite(integrated) ? REFERENCE_LUFS - integrated : Double.NaN;
    }

    /**
     * Returns the peak a measurement reports: the true peak when measured, else the sample peak.
     *
     * @param stats the measurements
     * @return the linear peak
     */
    static double peakOf(LoudnessStats stats) {
        return Double.isNaN(stats.truePeak()) ? stats.samplePeak() : stats.truePeak();
    }

    /**
     * Returns these gains with album values.
     *
     * @param gainDb the album gain in dB, or {@code NaN}
     * @param peak the album peak, or {@code NaN}
     * @return the gains
     */
    public ReplayGain withAlbum(double gainDb, double peak) {
        return new ReplayGain(trackGainDb, trackPeak, gainDb, peak);
    }

    /**
     * Reads the gains a file's tags carry.
     *
     * <p>The {@code REPLAYGAIN_*} fields are read first; a gain they lack is taken from the
     * matching {@code R128_*} field, converted to the {@value #REFERENCE_LUFS} LUFS reference (5 dB
     * added). Field names are matched ignoring case; a gain is a decimal number optionally followed
     * by {@code "dB"} and a peak a non-negative decimal number. A field that does not parse, or a
     * gain outside -200 to 200 dB, counts as absent.
     *
     * @param tags the tags of a file
     * @return the gains, every value {@code NaN} when the tags carry none
     */
    public static ReplayGain fromTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        var trackGain = parseGain(tags, TRACK_GAIN);
        if (Double.isNaN(trackGain)) {
            trackGain = parseR128(tags, R128_TRACK_GAIN);
        }
        var albumGain = parseGain(tags, ALBUM_GAIN);
        if (Double.isNaN(albumGain)) {
            albumGain = parseR128(tags, R128_ALBUM_GAIN);
        }
        return new ReplayGain(trackGain, parsePeak(tags, TRACK_PEAK), albumGain, parsePeak(tags, ALBUM_PEAK));
    }

    /**
     * Parses a {@code REPLAYGAIN_*} gain field.
     *
     * @param tags the tags to search
     * @param key the field
     * @return the gain in dB, or {@code NaN}
     */
    private static double parseGain(MediaTags tags, String key) {
        var value = tags.get(key);
        if (value.isEmpty()) {
            return Double.NaN;
        }
        var text = value.get().strip();
        if (text.regionMatches(true, Math.max(0, text.length() - 2), "dB", 0, 2)) {
            text = text.substring(0, text.length() - 2).strip();
        }
        var gain = parseNumber(text);
        return Math.abs(gain) <= MAX_GAIN_DB ? gain : Double.NaN;
    }

    /**
     * Parses a {@code REPLAYGAIN_*} peak field.
     *
     * @param tags the tags to search
     * @param key the field
     * @return the peak, or {@code NaN}
     */
    private static double parsePeak(MediaTags tags, String key) {
        var peak = tags.get(key).map(value -> parseNumber(value.strip())).orElse(Double.NaN);
        return peak >= 0 && Double.isFinite(peak) ? peak : Double.NaN;
    }

    /**
     * Parses an {@code R128_*} gain field and converts it to the ReplayGain reference.
     *
     * @param tags the tags to search
     * @param key the field
     * @return the gain in dB relative to {@value #REFERENCE_LUFS} LUFS, or {@code NaN}
     */
    private static double parseR128(MediaTags tags, String key) {
        var value = tags.get(key);
        if (value.isEmpty()) {
            return Double.NaN;
        }
        var text = value.get().strip();
        if (!text.matches("[+-]?[0-9]{1,6}")) {
            return Double.NaN;
        }
        var q8 = Integer.parseInt(text.startsWith("+") ? text.substring(1) : text);
        if (q8 < Short.MIN_VALUE || q8 > Short.MAX_VALUE) {
            return Double.NaN;
        }
        return q8 / 256.0 + (REFERENCE_LUFS - R128_REFERENCE_LUFS);
    }

    /**
     * Parses a plain decimal number.
     *
     * @param text the candidate number, stripped
     * @return the number, or {@code NaN} when the text is not a finite decimal number
     */
    private static double parseNumber(String text) {
        if (!text.matches("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)")) {
            return Double.NaN;
        }
        return Double.parseDouble(text);
    }

    /**
     * Returns tags holding these gains in place of any gains the given tags carry.
     *
     * <p>Every {@code REPLAYGAIN_*} and {@code R128_*} field of {@code tags} is removed; then the
     * known values are written in {@code format}. Under {@link TagFormat#REPLAYGAIN} the reference
     * loudness field is written too; under {@link TagFormat#R128} a gain is rounded to the nearest
     * 1/256 dB, clamped to the field's 16-bit range, and the peaks are not written. The other entries,
     * pictures and chapters are kept.
     *
     * @param tags the tags to update
     * @param format the fields to write
     * @return the updated tags
     */
    public MediaTags toTags(MediaTags tags, TagFormat format) {
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(format, "format");
        var builder = tags.toBuilder();
        for (var key : new String[]{TRACK_GAIN, TRACK_PEAK, ALBUM_GAIN, ALBUM_PEAK, REFERENCE_LOUDNESS,
                R128_TRACK_GAIN, R128_ALBUM_GAIN}) {
            builder.remove(key);
        }
        switch (format) {
            case REPLAYGAIN -> {
                var any = false;
                if (!Double.isNaN(trackGainDb)) {
                    builder.set(TRACK_GAIN, formatGain(trackGainDb));
                    any = true;
                }
                if (!Double.isNaN(trackPeak)) {
                    builder.set(TRACK_PEAK, formatPeak(trackPeak));
                }
                if (!Double.isNaN(albumGainDb)) {
                    builder.set(ALBUM_GAIN, formatGain(albumGainDb));
                    any = true;
                }
                if (!Double.isNaN(albumPeak)) {
                    builder.set(ALBUM_PEAK, formatPeak(albumPeak));
                }
                if (any) {
                    builder.set(REFERENCE_LOUDNESS, String.format(Locale.ROOT, "%.2f LUFS", REFERENCE_LUFS));
                }
            }
            case R128 -> {
                if (!Double.isNaN(trackGainDb)) {
                    builder.set(R128_TRACK_GAIN, Integer.toString(q8(trackGainDb)));
                }
                if (!Double.isNaN(albumGainDb)) {
                    builder.set(R128_ALBUM_GAIN, Integer.toString(q8(albumGainDb)));
                }
            }
        }
        return builder.build();
    }

    /**
     * Formats a gain as ReplayGain 2.0 writes it.
     *
     * @param gain the gain in dB
     * @return the text, two decimals and the unit
     */
    private static String formatGain(double gain) {
        var rounded = Math.round(gain * 100) / 100.0;
        return String.format(Locale.ROOT, "%.2f dB", rounded == 0 ? 0.0 : rounded);
    }

    /**
     * Formats a peak as ReplayGain 2.0 writes it.
     *
     * @param peak the linear peak
     * @return the text, six decimals
     */
    private static String formatPeak(double peak) {
        return String.format(Locale.ROOT, "%.6f", peak);
    }

    /**
     * Converts a gain relative to {@value #REFERENCE_LUFS} LUFS to an {@code R128_*} value.
     *
     * @param gain the gain in dB
     * @return the Q7.8 gain relative to {@value #R128_REFERENCE_LUFS} LUFS, clamped to 16 bits
     */
    private static int q8(double gain) {
        var q8 = Math.round((gain - (REFERENCE_LUFS - R128_REFERENCE_LUFS)) * 256);
        return (int) Math.clamp(q8, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Returns the gain a player applies under a mode.
     *
     * <p>The gain is the track gain, or under {@link Mode#ALBUM} the album gain when present, plus
     * {@code preampDb}. With {@code preventClipping}, when the peak that goes with the chosen gain is
     * known and the gain would raise it above full scale, the gain is lowered to put the peak at full
     * scale. When the chosen gain is absent the result is empty.
     *
     * @param mode the gain to apply
     * @param preampDb a gain added to the tagged one, in dB, finite
     * @param preventClipping whether the gain is lowered to keep the peak at or below full scale
     * @return the gain in dB, or empty when the tags carry no gain for {@code mode}
     * @throws IllegalArgumentException if {@code preampDb} is not finite
     */
    public OptionalDouble playbackGainDb(Mode mode, double preampDb, boolean preventClipping) {
        Objects.requireNonNull(mode, "mode");
        if (!Double.isFinite(preampDb)) {
            throw new IllegalArgumentException("preamp " + preampDb + " is not finite");
        }
        var album = mode == Mode.ALBUM && !Double.isNaN(albumGainDb);
        var gain = album ? albumGainDb : trackGainDb;
        if (Double.isNaN(gain)) {
            return OptionalDouble.empty();
        }
        gain += preampDb;
        var peak = album ? albumPeak : trackPeak;
        if (preventClipping && peak > 0 && Double.isFinite(peak)) {
            gain = Math.min(gain, -20 * StrictMath.log10(peak));
        }
        return OptionalDouble.of(gain);
    }
}
