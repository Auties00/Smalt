package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.internal.io.ByteWriter;
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
public final class Mp4TimecodeTrack {
    /**
     * The handler type and sample entry type of timecode tracks, also the {@code tref} reference type.
     */
    public static final String TYPE = "tmcd";

    /**
     * The {@code tmcd} flag of drop-frame counting.
     */
    public static final int FLAG_DROP_FRAME = 0x1;

    /**
     * The {@code tmcd} flag of a timecode that wraps at 24 hours.
     */
    public static final int FLAG_24_HOURS = 0x2;

    /**
     * The {@code tmcd} flag of a sample that counts rather than holds a frame number.
     */
    public static final int FLAG_COUNTER = 0x8;

    /**
     * The size of the fields of a {@code tmcd} sample entry after its data reference index.
     */
    public static final int ENTRY_FIELDS = 18;

    /**
     * The size of the sample, a 32-bit frame number.
     */
    public static final int SAMPLE_SIZE = 4;

    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The packed ISO 639-2 code {@code und}.
     */
    private static final int LANGUAGE_UNDETERMINED = 0x55C4;

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

    private Mp4TimecodeTrack(long timescale, long frameDuration, Timecode timecode) {
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
    public static Mp4TimecodeTrack of(String text, Rational frameRate) {
        if (frameRate == null) {
            var timecode = Timecode.parse(text, 30);
            if (!timecode.dropFrame()) {
                throw new IllegalArgumentException("the timecode " + text + " needs a video track with a frame rate");
            }
            return new Mp4TimecodeTrack(30000, 1001, timecode);
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
        return new Mp4TimecodeTrack(numerator, denominator, Timecode.parse(text, (int) nominal));
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
    public static Timecode read(byte[] fields, byte[] sample) {
        if (fields == null || fields.length < ENTRY_FIELDS || sample == null || sample.length < SAMPLE_SIZE) {
            return null;
        }
        var flags = (int) u32(fields, 4);
        if ((flags & FLAG_COUNTER) != 0) {
            return null;
        }
        var nominal = nominalRate(fields);
        if (nominal == 0) {
            return null;
        }
        var drop = (flags & FLAG_DROP_FRAME) != 0 && nominal % 30 == 0;
        var frame = (int) u32(sample, 0);
        return Timecode.ofFrameNumber(frame, nominal, drop);
    }

    /**
     * Returns the nominal frame count of a second a {@code tmcd} sample entry counts at: its number of frames, or
     * when that is zero its rate rounded up.
     *
     * @param fields the sample entry after its data reference index, at least {@value #ENTRY_FIELDS} bytes
     * @return the count, from 1 to 255, or {@code 0} when the entry gives none
     */
    public static int nominalRate(byte[] fields) {
        var nominal = fields[16] & 0xFF;
        if (nominal != 0) {
            return nominal;
        }
        var timescale = u32(fields, 8);
        var frameDuration = u32(fields, 12);
        if (timescale == 0 || frameDuration == 0) {
            return 0;
        }
        var derived = (timescale + frameDuration - 1) / frameDuration;
        return derived > 255 ? 0 : (int) derived;
    }

    /**
     * {@return the sample: the frame number of the first frame, big-endian}
     */
    public byte[] sample() {
        return sample(timecode);
    }

    /**
     * {@return the sample of a timecode: its frame number, big-endian}
     *
     * @param timecode the timecode of the first frame
     */
    public static byte[] sample(Timecode timecode) {
        var number = timecode.frameNumber();
        return new byte[]{(byte) (number >>> 24), (byte) (number >>> 16), (byte) (number >>> 8), (byte) number};
    }

    /**
     * {@return the timecode of the first frame}
     */
    public Timecode timecode() {
        return timecode;
    }

    /**
     * Encodes the {@code tref} child that names a timecode track.
     *
     * @param trackId the identifier of the timecode track
     * @return the {@code tmcd} reference box
     */
    public static byte[] reference(long trackId) {
        var out = new ByteWriter(12);
        out.u32be(trackId);
        return Mp4Boxes.box(TYPE, out.toByteArray());
    }

    /**
     * Returns the duration of the sample of a movie fragment that spans a given time, in media timescale units.
     *
     * @param duration the span, in units of {@code timescale}
     * @param timescale the timescale of the span, in units per second
     * @return the duration, at least one frame and at most {@code 2^32 - 1}
     */
    public long fragmentSampleDuration(long duration, long timescale) {
        return Math.min(U32_MAX, Math.max(frameDuration, rescale(duration, timescale, this.timescale)));
    }

    /**
     * Encodes the {@code trak} box.
     *
     * @param trackId the track identifier
     * @param movieTimescale the movie timescale, in units per second
     * @param movieDuration the movie duration, in movie timescale units
     * @param chunkOffset the file offset of the sample
     * @param co64 whether the chunk offset is written as a {@code co64} box rather than {@code stco}
     * @return the box
     */
    public byte[] trak(long trackId, long movieTimescale, long movieDuration, long chunkOffset, boolean co64) {
        return trak(trackId, Math.max(frameDuration, rescale(movieDuration, movieTimescale, timescale)),
                Math.max(1, movieDuration), chunkOffset, co64, false);
    }

    /**
     * Encodes the {@code trak} box of a fragmented file: zero durations and empty sample tables, as the sample is
     * in a movie fragment.
     *
     * @param trackId the track identifier
     * @return the box
     */
    public byte[] fragmentedTrak(long trackId) {
        return trak(trackId, 0, 0, 0, false, true);
    }

    /**
     * Encodes the {@code trak} box.
     *
     * @param trackId the track identifier
     * @param mediaDuration the media duration, in media timescale units
     * @param trackDuration the track duration, in movie timescale units
     * @param chunkOffset the file offset of the sample
     * @param co64 whether the chunk offset is written as a {@code co64} box rather than {@code stco}
     * @param empty whether the sample tables are empty
     * @return the box
     */
    private byte[] trak(long trackId, long mediaDuration, long trackDuration, long chunkOffset, boolean co64,
                        boolean empty) {
        var writer = new ByteWriter(512);
        writer.begin(code("trak"));
        var wide = trackDuration > U32_MAX;
        writer.beginFull(code("tkhd"), wide ? 1 : 0, 3);
        writer.zeros(wide ? 16 : 8);
        writer.u32be(trackId);
        writer.u32be(0);
        length(writer, trackDuration, wide);
        writer.zeros(16);
        writer.u32be(0x00010000);
        writer.zeros(12);
        writer.u32be(0x00010000);
        writer.zeros(12);
        writer.u32be(0x40000000);
        writer.zeros(8);
        writer.end();
        writer.begin(code("mdia"));
        var mediaWide = mediaDuration > U32_MAX;
        writer.beginFull(code("mdhd"), mediaWide ? 1 : 0, 0);
        writer.zeros(mediaWide ? 16 : 8);
        writer.u32be(timescale);
        length(writer, mediaDuration, mediaWide);
        writer.u16be(LANGUAGE_UNDETERMINED);
        writer.u16be(0);
        writer.end();
        writer.beginFull(code("hdlr"), 0, 0);
        writer.u32be(0);
        writer.u32be(code(TYPE));
        writer.zeros(12);
        writer.cString("TimeCodeHandler");
        writer.end();
        writer.begin(code("minf"));
        writer.begin(code("gmhd"));
        writer.beginFull(code("gmin"), 0, 0);
        writer.u16be(0x40);
        writer.u16be(0x8000);
        writer.u16be(0x8000);
        writer.u16be(0x8000);
        writer.u16be(0);
        writer.u16be(0);
        writer.end();
        writer.begin(code(TYPE));
        writer.beginFull(code("tcmi"), 0, 0);
        writer.u16be(0);
        writer.u16be(0);
        writer.u16be(12);
        writer.zeros(6);
        writer.u16be(0xFFFF);
        writer.u16be(0xFFFF);
        writer.u16be(0xFFFF);
        var font = "Helvetica".getBytes(StandardCharsets.US_ASCII);
        writer.u8(font.length);
        writer.bytes(font);
        writer.end();
        writer.end();
        writer.end();
        writer.begin(code("dinf"));
        writer.beginFull(code("dref"), 0, 0);
        writer.u32be(1);
        writer.beginFull(code("url "), 0, 1);
        writer.end();
        writer.end();
        writer.end();
        writer.begin(code("stbl"));
        writer.beginFull(code("stsd"), 0, 0);
        writer.u32be(1);
        writer.begin(code(TYPE));
        writer.zeros(6);
        writer.u16be(1);
        writer.u32be(0);
        writer.u32be(FLAG_24_HOURS | (timecode.dropFrame() ? FLAG_DROP_FRAME : 0));
        writer.u32be(timescale);
        writer.u32be(frameDuration);
        writer.u8(timecode.framesPerSecond());
        writer.u8(0);
        writer.end();
        writer.end();
        if (empty) {
            for (var table : new String[]{"stts", "stsc", "stco"}) {
                writer.beginFull(code(table), 0, 0);
                writer.u32be(0);
                writer.end();
            }
            writer.beginFull(code("stsz"), 0, 0);
            writer.u32be(0);
            writer.u32be(0);
            writer.end();
        } else {
            writer.beginFull(code("stts"), 0, 0);
            writer.u32be(1);
            writer.u32be(1);
            writer.u32be(Math.min(mediaDuration, U32_MAX));
            writer.end();
            writer.beginFull(code("stsc"), 0, 0);
            writer.u32be(1);
            writer.u32be(1);
            writer.u32be(1);
            writer.u32be(1);
            writer.end();
            writer.beginFull(code("stsz"), 0, 0);
            writer.u32be(SAMPLE_SIZE);
            writer.u32be(1);
            writer.end();
            writer.beginFull(code(co64 ? "co64" : "stco"), 0, 0);
            writer.u32be(1);
            if (co64) {
                writer.u64be(chunkOffset);
            } else {
                writer.u32be(chunkOffset);
            }
            writer.end();
        }
        writer.end();
        writer.end();
        writer.end();
        writer.end();
        return writer.toByteArray();
    }

    /**
     * Writes a duration of a header box.
     *
     * @param writer the destination
     * @param value the duration
     * @param wide whether the field is 64 bits rather than 32
     */
    private static void length(ByteWriter writer, long value, boolean wide) {
        if (wide) {
            writer.u64be(value);
        } else {
            writer.u32be(value);
        }
    }

    /**
     * Converts a time between timescales, saturating on overflow.
     *
     * @param value the time
     * @param from the timescale of {@code value}, in units per second
     * @param to the timescale of the result, in units per second
     * @return the time in units of {@code to}
     */
    private static long rescale(long value, long from, long to) {
        if (from == to || value == 0) {
            return value;
        }
        try {
            return new Rational(1, from).rescale(value, new Rational(1, to));
        } catch (ArithmeticException exception) {
            return value < 0 ? Long.MIN_VALUE / 4 : Long.MAX_VALUE / 4;
        }
    }

    /**
     * {@return the big-endian integer form of a four-character code}
     *
     * @param type the code, four ASCII characters
     */
    private static int code(String type) {
        return type.charAt(0) << 24 | type.charAt(1) << 16 | type.charAt(2) << 8 | type.charAt(3);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }
}
