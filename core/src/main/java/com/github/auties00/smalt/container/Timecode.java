package com.github.auties00.smalt.container;

import java.util.Objects;

/**
 * Holds a SMPTE ST 12-1 time address, the hours, minutes, seconds and frames a timecode track or
 * tag gives the first frame of a presentation.
 *
 * <p>A timecode counts frames at a whole nominal rate: 30 frames per second for 29.97 Hz video, 24
 * for 23.976 Hz. Drop-frame counting, defined for nominal rates that are multiples of 30, skips the
 * first {@code framesPerSecond / 15} frame numbers of every minute except each tenth minute, so that
 * the address follows the clock of video at 1000/1001 of the nominal rate. Addresses wrap at 24
 * hours.
 *
 * <p>The text form is {@code HH:MM:SS:FF}, with a {@code ;} before the frames for drop-frame
 * counting ({@code 01:00:00;00}); the frames have three digits at nominal rates above 100.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param hours the hours, from 0 to 23
 * @param minutes the minutes, from 0 to 59
 * @param seconds the seconds, from 0 to 59
 * @param frames the frame within the second, from 0 to {@code framesPerSecond - 1}; with drop-frame
 * counting at least {@code framesPerSecond / 15} in the first second of a minute that is not a
 * multiple of ten
 * @param framesPerSecond the nominal frame rate, from 1 to 255
 * @param dropFrame whether the address uses drop-frame counting
 */
public record Timecode(int hours, int minutes, int seconds, int frames, int framesPerSecond, boolean dropFrame) {
    /**
     * The number of seconds in a day, where addresses wrap.
     */
    private static final int DAY_SECONDS = 24 * 3600;

    /**
     * Validates the address.
     *
     * @throws IllegalArgumentException if a field is outside its range, drop-frame counting is requested at a
     * nominal rate that is not a multiple of 30, or the address names a frame number drop-frame counting skips
     */
    public Timecode {
        if (framesPerSecond < 1 || framesPerSecond > 255) {
            throw new IllegalArgumentException("nominal frame rate " + framesPerSecond + " is not from 1 to 255");
        }
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59 || frames < 0
                || frames >= framesPerSecond) {
            throw new IllegalArgumentException("invalid time address " + hours + ":" + minutes + ":" + seconds + ":"
                    + frames + " at " + framesPerSecond + " frames per second");
        }
        if (dropFrame) {
            if (framesPerSecond % 30 != 0) {
                throw new IllegalArgumentException("drop-frame counting needs a multiple of 30 frames per second, not "
                        + framesPerSecond);
            }
            if (seconds == 0 && minutes % 10 != 0 && frames < framesPerSecond / 15) {
                throw new IllegalArgumentException("drop-frame counting skips frame " + frames + " of minute " + minutes);
            }
        }
    }

    /**
     * Parses the text form of an address.
     *
     * <p>The four fields are separated by {@code :}; a {@code ;}, {@code .} or {@code ,} before the frames
     * selects drop-frame counting. Each field has one to three digits.
     *
     * @param text the address, for example {@code 01:00:00;00}
     * @param framesPerSecond the nominal frame rate, from 1 to 255
     * @return the address
     * @throws IllegalArgumentException if the text is not an address valid at that rate
     */
    public static Timecode parse(String text, int framesPerSecond) {
        Objects.requireNonNull(text, "text");
        var fields = new int[4];
        var field = 0;
        var digits = 0;
        var drop = false;
        for (var index = 0; index < text.length(); index++) {
            var c = text.charAt(index);
            if (c >= '0' && c <= '9') {
                if (++digits > 3) {
                    throw new IllegalArgumentException("invalid timecode \"" + text + "\"");
                }
                fields[field] = fields[field] * 10 + (c - '0');
            } else if (digits > 0 && field < 3 && (c == ':' || (field == 2 && (c == ';' || c == '.' || c == ',')))) {
                drop = c != ':';
                field++;
                digits = 0;
            } else {
                throw new IllegalArgumentException("invalid timecode \"" + text + "\"");
            }
        }
        if (field != 3 || digits == 0) {
            throw new IllegalArgumentException("invalid timecode \"" + text + "\"");
        }
        return new Timecode(fields[0], fields[1], fields[2], fields[3], framesPerSecond, drop);
    }

    /**
     * Returns the address of a frame counted from midnight.
     *
     * @param frameNumber the number of frames since 00:00:00:00, wrapped into one day; negative counts back from
     * midnight
     * @param framesPerSecond the nominal frame rate, from 1 to 255
     * @param dropFrame whether to use drop-frame counting
     * @return the address
     * @throws IllegalArgumentException if the rate is outside its range, or drop-frame counting is requested at a
     * rate that is not a multiple of 30
     */
    public static Timecode ofFrameNumber(long frameNumber, int framesPerSecond, boolean dropFrame) {
        if (framesPerSecond < 1 || framesPerSecond > 255 || (dropFrame && framesPerSecond % 30 != 0)) {
            throw new IllegalArgumentException("invalid nominal frame rate " + framesPerSecond);
        }
        var perDay = framesPerDay(framesPerSecond, dropFrame);
        var counted = Math.floorMod(frameNumber, perDay);
        if (dropFrame) {
            var drop = framesPerSecond / 15;
            var perTenMinutes = framesPerSecond * 600L - drop * 9L;
            var perMinute = framesPerSecond * 60L - drop;
            var tens = counted / perTenMinutes;
            var rest = counted % perTenMinutes;
            counted += drop * 9L * tens;
            if (rest > drop) {
                counted += drop * ((rest - drop) / perMinute);
            }
        }
        var totalSeconds = counted / framesPerSecond;
        return new Timecode((int) (totalSeconds / 3600), (int) (totalSeconds / 60 % 60), (int) (totalSeconds % 60),
                (int) (counted % framesPerSecond), framesPerSecond, dropFrame);
    }

    /**
     * Returns the number of frames from 00:00:00:00 to this address.
     *
     * @return the frame number, from 0 to the number of frame numbers in a day minus one
     */
    public long frameNumber() {
        var totalMinutes = 60L * hours + minutes;
        var number = ((totalMinutes * 60) + seconds) * framesPerSecond + frames;
        if (dropFrame) {
            number -= (long) framesPerSecond / 15 * (totalMinutes - totalMinutes / 10);
        }
        return number;
    }

    /**
     * Returns the number of frame numbers in one day.
     *
     * @param framesPerSecond the nominal frame rate
     * @param dropFrame whether drop-frame counting skips some
     * @return the count
     */
    private static long framesPerDay(int framesPerSecond, boolean dropFrame) {
        var all = (long) DAY_SECONDS * framesPerSecond;
        return dropFrame ? all - (long) framesPerSecond / 15 * (24 * 60 - 24 * 6) : all;
    }

    /**
     * {@return the text form of the address, such as {@code 01:00:00;00}}
     */
    @Override
    public String toString() {
        return two(hours) + ":" + two(minutes) + ":" + two(seconds) + (dropFrame ? ";" : ":")
                + (framesPerSecond > 100 && frames < 100 ? "0" : "") + two(frames);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
