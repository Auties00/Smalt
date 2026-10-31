package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.StandardCharsets;

/**
 * Reads and lays out a QuickTime timecode track (Apple QuickTime File Format, "Timecode Media"): a track of
 * handler {@code tmcd} whose single sample holds the 32-bit frame number of the first frame, described by a
 * {@code tmcd} sample entry giving the flags, the rate and the nominal frame count of a second, and named by a
 * {@code tref/tmcd} reference of the video tracks.
 *
 * <p>The layout writes the drop-frame flag of the timecode and the 24-hour flag, a sample lasting the whole
 * movie (in a fragmented file, the first fragment, which holds the sample), a QuickTime base media information
 * header ({@code gmhd} holding {@code gmin} and a {@code tmcd} atom with its {@code tcmi} text description), and a
 * handler of type {@code tmcd}. Instances are immutable and thread-safe.
 */
final class TimecodeTrack {
    /**
     * The {@code tmcd} flag of drop-frame counting.
     */
    static final int FLAG_DROP_FRAME = 0x1;

    /**
     * The {@code tmcd} flag of a timecode that wraps at 24 hours.
     */
    static final int FLAG_24_HOURS = 0x2;

    /**
     * The {@code tmcd} flag of a sample that counts rather than holds a frame number.
     */
    static final int FLAG_COUNTER = 0x8;

    /**
     * The size of the fields of a {@code tmcd} sample entry after its data reference index.
     */
    static final int ENTRY_FIELDS = 18;

    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The media timescale, in units per second.
     */
    private final long timescale;

    /**
     * The duration of one frame, in media timescale units.
     */
    private final long frameDuration;

    /**
     * The timecode of the first frame.
     */
    private final Timecode timecode;

    private TimecodeTrack(long timescale, long frameDuration, Timecode timecode) {
        this.timescale = timescale;
        this.frameDuration = frameDuration;
        this.timecode = timecode;
    }

    /**
     * Lays out the timecode track of an address in its text form.
     *
     * <p>The rate is the frame rate of the video, whose rounded-up value is the nominal rate the address counts
     * at; without it, a drop-frame address counts at 30 frames per second with frames of 1001/30000 seconds.
     *
     * @param text the address, as {@link Timecode#parse} reads it
     * @param frameRate the frame rate of the first video track, or {@code null} when there is none
     * @return the layout
     * @throws IllegalArgumentException if the address is invalid at that rate, the rate is outside 1 to 255 frames
     * per second, or there is no frame rate and the address is not a drop-frame one
     */
    static TimecodeTrack of(String text, Rational frameRate) {
        if (frameRate == null) {
            var timecode = Timecode.parse(text, 30);
            if (!timecode.dropFrame()) {
                throw new IllegalArgumentException("the timecode " + text + " needs a video track with a frame rate");
            }
            return new TimecodeTrack(30000, 1001, timecode);
        }
        var rate = frameRate.reduce();
        var numerator = rate.numerator();
        var denominator = rate.denominator();
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("invalid frame rate " + frameRate);
        }
        var nominal = (numerator + denominator - 1) / denominator;
        if (nominal < 1 || nominal > 255) {
            throw new IllegalArgumentException("a timecode needs a frame rate from 1 to 255, not " + frameRate);
        }
        while (numerator > U32_MAX || denominator > U32_MAX) {
            numerator = Math.max(1, numerator >>> 1);
            denominator = Math.max(1, denominator >>> 1);
        }
        return new TimecodeTrack(numerator, denominator, Timecode.parse(text, (int) nominal));
    }

    /**
     * Reads the timecode a {@code tmcd} sample entry and the track's first sample give.
     *
     * @param fields the sample entry after its data reference index: reserved, flags, timescale, frame
     * duration, number of frames and reserved
     * @param sample the first sample
     * @return the timecode, or {@code null} when the entry or the sample is too short, the rate is invalid, or the
     * sample is a counter
     */
    static Timecode read(byte[] fields, byte[] sample) {
        if (fields == null || fields.length < ENTRY_FIELDS || sample == null || sample.length < 4) {
            return null;
        }
        var flags = (int) u32(fields, 4);
        var timescale = u32(fields, 8);
        var frameDuration = u32(fields, 12);
        var nominal = fields[16] & 0xFF;
        if ((flags & FLAG_COUNTER) != 0) {
            return null;
        }
        if (nominal == 0) {
            if (timescale == 0 || frameDuration == 0) {
                return null;
            }
            var derived = (timescale + frameDuration - 1) / frameDuration;
            if (derived > 255) {
                return null;
            }
            nominal = (int) derived;
        }
        var drop = (flags & FLAG_DROP_FRAME) != 0 && nominal % 30 == 0;
        var frame = (int) u32(sample, 0);
        return Timecode.ofFrameNumber(frame, nominal, drop);
    }

    /**
     * {@return the sample: the frame number of the first frame, big-endian}
     */
    byte[] sample() {
        var number = timecode.frameNumber();
        return new byte[]{(byte) (number >>> 24), (byte) (number >>> 16), (byte) (number >>> 8), (byte) number};
    }

    /**
     * Writes the {@code tref} child that names a timecode track.
     *
     * @param writer the destination, inside a {@code tref} box
     * @param trackId the identifier of the timecode track
     */
    static void writeReference(BoxWriter writer, long trackId) {
        writer.begin(BoxTypes.TMCD);
        writer.u32(trackId);
        writer.end();
    }

    /**
     * Returns the duration of the sample of a movie fragment that spans a given time, in media timescale units.
     *
     * @param duration the span, in units of {@code timescale}
     * @param timescale the timescale of the span, in units per second
     * @return the duration, at least one frame and at most {@code 2^32 - 1}
     */
    long fragmentSampleDuration(long duration, long timescale) {
        return Math.min(U32_MAX, Math.max(frameDuration, Mp4Track.rescale(duration, timescale, this.timescale)));
    }

    /**
     * Writes the {@code trak} box.
     *
     * @param writer the destination
     * @param trackId the track identifier
     * @param movieTimescale the movie timescale, in units per second
     * @param movieDuration the movie duration, in movie timescale units
     * @param chunkOffset the file offset of the sample
     * @param co64 whether the chunk offset is written as a {@code co64} box rather than {@code stco}
     */
    void writeTrak(BoxWriter writer, long trackId, long movieTimescale, long movieDuration, long chunkOffset,
                   boolean co64) {
        writeTrak(writer, trackId, Math.max(frameDuration, Mp4Track.rescale(movieDuration, movieTimescale, timescale)),
                Math.max(1, movieDuration), chunkOffset, co64, false);
    }

    /**
     * Writes the {@code trak} box of a fragmented file: zero durations and empty sample tables, as the sample is
     * in a movie fragment.
     *
     * @param writer the destination
     * @param trackId the track identifier
     */
    void writeFragmentedTrak(BoxWriter writer, long trackId) {
        writeTrak(writer, trackId, 0, 0, 0, false, true);
    }

    /**
     * Writes the {@code trak} box.
     *
     * @param writer the destination
     * @param trackId the track identifier
     * @param mediaDuration the media duration, in media timescale units
     * @param trackDuration the track duration, in movie timescale units
     * @param chunkOffset the file offset of the sample
     * @param co64 whether the chunk offset is written as a {@code co64} box rather than {@code stco}
     * @param empty whether the sample tables are empty
     */
    private void writeTrak(BoxWriter writer, long trackId, long mediaDuration, long trackDuration, long chunkOffset,
                           boolean co64, boolean empty) {
        writer.begin(BoxTypes.TRAK);
        var wide = trackDuration > U32_MAX;
        writer.beginFull(BoxTypes.TKHD, wide ? 1 : 0, 3);
        writer.zeros(wide ? 16 : 8);
        writer.u32(trackId);
        writer.u32(0);
        MovieHeaderWriter.writeLength(writer, trackDuration, wide);
        writer.zeros(16);
        writer.u32(0x00010000);
        writer.zeros(12);
        writer.u32(0x00010000);
        writer.zeros(12);
        writer.u32(0x40000000);
        writer.zeros(8);
        writer.end();
        writer.begin(BoxTypes.MDIA);
        var mediaWide = mediaDuration > U32_MAX;
        writer.beginFull(BoxTypes.MDHD, mediaWide ? 1 : 0, 0);
        writer.zeros(mediaWide ? 16 : 8);
        writer.u32(timescale);
        MovieHeaderWriter.writeLength(writer, mediaDuration, mediaWide);
        writer.u16(MovieHeaderWriter.packLanguage(null));
        writer.u16(0);
        writer.end();
        writer.beginFull(BoxTypes.HDLR, 0, 0);
        writer.u32(0);
        writer.u32(BoxTypes.TMCD);
        writer.zeros(12);
        writer.cString("TimeCodeHandler");
        writer.end();
        writer.begin(BoxTypes.MINF);
        writer.begin(BoxTypes.GMHD);
        writer.beginFull(BoxTypes.GMIN, 0, 0);
        writer.u16(0x40);
        writer.u16(0x8000);
        writer.u16(0x8000);
        writer.u16(0x8000);
        writer.u16(0);
        writer.u16(0);
        writer.end();
        writer.begin(BoxTypes.TMCD);
        writer.beginFull(BoxTypes.TCMI, 0, 0);
        writer.u16(0);
        writer.u16(0);
        writer.u16(12);
        writer.zeros(6);
        writer.u16(0xFFFF);
        writer.u16(0xFFFF);
        writer.u16(0xFFFF);
        var font = "Helvetica".getBytes(StandardCharsets.US_ASCII);
        writer.u8(font.length);
        writer.bytes(font);
        writer.end();
        writer.end();
        writer.end();
        writer.begin(BoxTypes.DINF);
        writer.beginFull(BoxTypes.DREF, 0, 0);
        writer.u32(1);
        writer.beginFull(BoxTypes.URL, 0, 1);
        writer.end();
        writer.end();
        writer.end();
        writer.begin(BoxTypes.STBL);
        writer.beginFull(BoxTypes.STSD, 0, 0);
        writer.u32(1);
        writer.begin(BoxTypes.TMCD);
        writer.zeros(6);
        writer.u16(1);
        writer.u32(0);
        writer.u32(FLAG_24_HOURS | (timecode.dropFrame() ? FLAG_DROP_FRAME : 0));
        writer.u32(timescale);
        writer.u32(frameDuration);
        writer.u8(timecode.framesPerSecond());
        writer.u8(0);
        writer.end();
        writer.end();
        if (empty) {
            for (var table : new int[]{BoxTypes.STTS, BoxTypes.STSC, BoxTypes.STCO}) {
                writer.beginFull(table, 0, 0);
                writer.u32(0);
                writer.end();
            }
            writer.beginFull(BoxTypes.STSZ, 0, 0);
            writer.u32(0);
            writer.u32(0);
            writer.end();
        } else {
            writer.beginFull(BoxTypes.STTS, 0, 0);
            writer.u32(1);
            writer.u32(1);
            writer.u32(Math.min(mediaDuration, U32_MAX));
            writer.end();
            writer.beginFull(BoxTypes.STSC, 0, 0);
            writer.u32(1);
            writer.u32(1);
            writer.u32(1);
            writer.u32(1);
            writer.end();
            writer.beginFull(BoxTypes.STSZ, 0, 0);
            writer.u32(4);
            writer.u32(1);
            writer.end();
            writer.beginFull(co64 ? BoxTypes.CO64 : BoxTypes.STCO, 0, 0);
            writer.u32(1);
            if (co64) {
                writer.u64(chunkOffset);
            } else {
                writer.u32(chunkOffset);
            }
            writer.end();
        }
        writer.end();
        writer.end();
        writer.end();
        writer.end();
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }
}
