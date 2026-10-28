package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Cuts an MPEG-1 or MPEG-2 video stream (ISO/IEC 11172-2, ISO/IEC 13818-2) or an MPEG-4 Part 2 video stream
 * (ISO/IEC 14496-2) into its access units, whatever its PES packetization, and hands each access unit to the parser
 * of the coding as a PES packet of its own.
 *
 * <p>A program stream splits a picture over packs of a few kilobytes and puts the end of one picture and the start
 * of the next in one PES packet, so its PES packets are not pictures. An access unit is a picture (a video object
 * plane) with the sequence, group of pictures (video object sequence, visual object, video object layer, group of
 * video object planes) headers and user data before it; a second field picture joins the access unit of its first
 * field, and a sequence end code ends the access unit it follows. The timestamps of a PES packet belong to the first
 * picture start code that begins in it (ISO/IEC 13818-1 clause 2.4.3.7). A picture whose PES packet carried no
 * timestamps gets them from the frame rate: its decoding time follows the previous access unit by that unit's
 * display duration (a frame, a field more for a repeated first field, two or three frames for a repeated progressive
 * frame), and its presentation time is its decoding time for a B-picture or a stream that does not reorder its
 * pictures (none was a B-picture or decoded before it was presented), and otherwise that of the group of pictures'
 * timestamped pictures moved by its {@code temporal_reference}, or, when the group has none, its decoding time
 * delayed by the B-pictures that follow it in decoding order and one frame. Bytes before the first header are dropped.
 * Instances are not thread-safe.
 */
final class AccessUnitFramer extends ElementaryParser {
    /**
     * The largest access unit; a longer one is cut there and flagged as damaged.
     */
    private static final int MAX_UNIT = 16 << 20;

    /**
     * The largest number of access units held back while a reference picture waits for the B-pictures that
     * follow it.
     */
    private static final int MAX_HELD = 64;

    /**
     * The parser of the coding.
     */
    private final ElementaryParser delegate;

    /**
     * Whether the stream is MPEG-4 Part 2 rather than MPEG-1 or MPEG-2 video.
     */
    private final boolean mpeg4;

    /**
     * The buffered elementary stream.
     */
    private byte[] buffer = new byte[64 * 1024];

    /**
     * The number of bytes in {@link #buffer}.
     */
    private int length;

    /**
     * The stream position of {@code buffer[0]}.
     */
    private long base;

    /**
     * The index from which start codes are still to be looked for.
     */
    private int scanned;

    /**
     * The index of the first byte of the access unit being collected, or {@code -1} before the first header.
     */
    private int unitStart = -1;

    /**
     * The index of the start code of the first picture of the access unit being collected, or {@code -1} while it
     * holds only headers.
     */
    private int picture = -1;

    /**
     * The {@code temporal_reference} of that picture.
     */
    private int temporalReference;

    /**
     * The coding type of that picture: 1 I, 2 P, 3 B, 4 D for MPEG-1 and MPEG-2; 0 I, 1 P, 2 B, 3 S for MPEG-4.
     */
    private int codingType;

    /**
     * The number of fields the access unit displays.
     */
    private int fields = 2;

    /**
     * Whether the access unit holds the first field of a field pair whose second field is still to come.
     */
    private boolean awaitingField;

    /**
     * {@code progressive_sequence} of the sequence extension in force.
     */
    private boolean progressiveSequence = true;

    /**
     * The PES packets whose bytes are buffered, in stream order.
     */
    private final ArrayDeque<Marker> markers = new ArrayDeque<>();

    /**
     * The decoding time of the last access unit passed on, raw, or {@link TsClock#NONE}.
     */
    private long lastDts = TsClock.NONE;

    /**
     * The fields displayed since {@link #lastDts}'s timestamped access unit, the access units passed on since it
     * included.
     */
    private long fieldsSince;

    /**
     * The decoding time of the last timestamped access unit, raw, or {@link TsClock#NONE}.
     */
    private long stampedDts = TsClock.NONE;

    /**
     * The presentation time of the group of pictures' first picture moved by a {@code temporal_reference} of zero,
     * raw, or {@link TsClock#NONE} while the group has no timestamped picture.
     */
    private long groupAnchor = TsClock.NONE;

    /**
     * Whether the stream reorders its pictures: a B-picture was met, or a timestamped picture decodes before it is
     * presented.
     */
    private boolean reordered;

    /**
     * The access units held back, in decoding order, while the first of them waits for its presentation time.
     */
    private final ArrayDeque<Unit> held = new ArrayDeque<>();

    /**
     * Describes the bytes of one PES packet.
     */
    private static final class Marker {
        final long start;
        final long end;
        final long pts;
        final long dts;
        final long fileOffset;
        final boolean corrupt;
        boolean taken;

        Marker(long start, long end, long pts, long dts, long fileOffset, boolean corrupt) {
            this.start = start;
            this.end = end;
            this.pts = pts;
            this.dts = dts;
            this.fileOffset = fileOffset;
            this.corrupt = corrupt;
        }
    }

    /**
     * Holds an access unit waiting for its timestamps.
     */
    private static final class Unit {
        byte[] data;
        final long fileOffset;
        final boolean corrupt;
        final int codingType;
        final int fields;
        long pts;
        long dts;

        Unit(byte[] data, long fileOffset, boolean corrupt, int codingType, int fields, long pts, long dts) {
            this.data = data;
            this.fileOffset = fileOffset;
            this.corrupt = corrupt;
            this.codingType = codingType;
            this.fields = fields;
            this.pts = pts;
            this.dts = dts;
        }
    }

    /**
     * Constructs a framer.
     *
     * @param delegate the parser of the coding: {@link MpegVideoParser} or {@link Mpeg4VideoParser}
     * @param mpeg4 whether the stream is MPEG-4 Part 2
     */
    AccessUnitFramer(ElementaryParser delegate, boolean mpeg4) {
        super(delegate.entry, delegate.limits);
        this.delegate = delegate;
        this.mpeg4 = mpeg4;
        this.track = delegate.track();
    }

    @Override
    ElementaryParser fresh() {
        var fresh = new AccessUnitFramer(delegate.fresh(), mpeg4);
        fresh.progressiveSequence = progressiveSequence;
        return fresh;
    }

    @Override
    void keepLeadingUnits() {
        delegate.keepLeadingUnits();
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        if (pes.length() > 0) {
            ensure(pes.length());
            System.arraycopy(pes.data(), pes.offset(), buffer, length, pes.length());
            var start = base + length;
            length += pes.length();
            markers.add(new Marker(start, base + length, pes.pts(), pes.dts(), pes.fileOffset(), pes.corrupt()));
            scan(clock, sink, false);
        }
        track = delegate.track();
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        scan(clock, sink, true);
        if (picture >= 0) {
            unit(length, clock, sink);
        }
        release(clock, sink, true);
        delegate.flush(clock, sink);
        track = delegate.track();
    }

    /**
     * Makes room for more bytes, dropping the bytes before the access unit being collected.
     *
     * @param more the number of bytes to add
     */
    private void ensure(int more) {
        var keep = unitStart >= 0 ? unitStart : Math.max(0, Math.min(scanned, length - 3));
        if (keep > 0) {
            System.arraycopy(buffer, keep, buffer, 0, length - keep);
            length -= keep;
            scanned -= keep;
            base += keep;
            if (unitStart >= 0) {
                unitStart -= keep;
            }
            if (picture >= 0) {
                picture -= keep;
            }
            while (!markers.isEmpty() && markers.peekFirst().end <= base) {
                markers.pollFirst();
            }
        }
        if (length + more > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(length + more, Math.min(2 * buffer.length, MAX_UNIT + more)));
        }
    }

    /**
     * Looks for the start codes of the buffered bytes and passes on the access units they complete.
     *
     * @param clock the timeline
     * @param sink receives the packets
     * @param flushing whether the input ended, so that a start code whose fields are cut is taken as it is
     */
    private void scan(TsClock clock, UnitSink sink, boolean flushing) {
        while (true) {
            var at = NalUnits.findStartCode(buffer, scanned, length);
            if (at < 0 || at + 3 >= length) {
                scanned = Math.max(scanned, length - 3);
                if (unitStart >= 0 && length - unitStart > MAX_UNIT) {
                    unit(length, clock, sink);
                }
                return;
            }
            if (unitStart >= 0 && at - unitStart > MAX_UNIT) {
                unit(at, clock, sink);
            }
            var code = buffer[at + 3] & 0xFF;
            var needed = mpeg4 ? (code == 0xB6 ? 5 : 4) : switch (code) {
                case 0x00 -> 6;
                case 0xB5 -> 9;
                default -> 4;
            };
            if (at + needed > length && !flushing) {
                scanned = at;
                return;
            }
            scanned = at + 3;
            if (mpeg4) {
                mpeg4Code(at, code, clock, sink);
            } else {
                mpeg12Code(at, code, clock, sink);
            }
        }
    }

    /**
     * Handles one start code of an MPEG-1 or MPEG-2 video stream.
     *
     * @param at the index of the start code
     * @param code the start code value
     * @param clock the timeline
     * @param sink receives the packets
     */
    private void mpeg12Code(int at, int code, TsClock clock, UnitSink sink) {
        switch (code) {
            case 0x00 -> {
                if (picture >= 0 && awaitingField) {
                    awaitingField = false;
                    return;
                }
                if (picture >= 0) {
                    unit(at, clock, sink);
                }
                if (unitStart < 0) {
                    unitStart = at;
                }
                picture = at;
                fields = 2;
                awaitingField = false;
                if (at + 6 <= length) {
                    temporalReference = (buffer[at + 4] & 0xFF) << 2 | (buffer[at + 5] & 0xFF) >> 6;
                    codingType = (buffer[at + 5] >> 3) & 0x07;
                }
            }
            case 0xB3, 0xB8 -> {
                if (picture >= 0) {
                    unit(at, clock, sink);
                }
                if (unitStart < 0) {
                    unitStart = at;
                }
                if (code == 0xB8 || code == 0xB3) {
                    groupAnchor = TsClock.NONE;
                }
            }
            case 0xB5 -> {
                if (at + 9 > length) {
                    return;
                }
                var id = (buffer[at + 4] & 0xFF) >> 4;
                if (id == 1) {
                    progressiveSequence = (buffer[at + 5] & 0x08) != 0;
                } else if (id == 8 && picture >= 0) {
                    var structure = buffer[at + 6] & 0x03;
                    var topFirst = (buffer[at + 7] & 0x80) != 0;
                    var repeat = (buffer[at + 7] & 0x02) != 0;
                    if (structure != 3) {
                        awaitingField = at > picture && !secondFieldOf(at);
                        fields = 2;
                    } else if (progressiveSequence) {
                        fields = repeat ? (topFirst ? 6 : 4) : 2;
                    } else {
                        fields = repeat ? 3 : 2;
                    }
                }
            }
            case 0xB7 -> {
                if (picture >= 0) {
                    unit(at + 4, clock, sink);
                    unitStart = -1;
                }
            }
            default -> {
            }
        }
    }

    /**
     * Returns whether the picture coding extension at an index belongs to the second field of the access unit,
     * that is, whether a second picture start code lies between the unit's first picture and the extension.
     *
     * @param at the index of the extension's start code
     * @return {@code true} for the second field's extension
     */
    private boolean secondFieldOf(int at) {
        var position = NalUnits.findStartCode(buffer, picture + 4, at);
        while (position >= 0 && position + 3 < at) {
            if ((buffer[position + 3] & 0xFF) == 0x00) {
                return true;
            }
            position = NalUnits.findStartCode(buffer, position + 4, at);
        }
        return false;
    }

    /**
     * Handles one start code of an MPEG-4 Part 2 video stream.
     *
     * @param at the index of the start code
     * @param code the start code value
     * @param clock the timeline
     * @param sink receives the packets
     */
    private void mpeg4Code(int at, int code, TsClock clock, UnitSink sink) {
        if (code == 0xB6) {
            if (picture >= 0) {
                unit(at, clock, sink);
            }
            if (unitStart < 0) {
                unitStart = at;
            }
            picture = at;
            fields = 2;
            temporalReference = -1;
            codingType = at + 5 <= length ? (buffer[at + 4] & 0xFF) >> 6 : 0;
        } else if (code <= 0x2F || code == 0xB0 || code == 0xB3 || code == 0xB5) {
            if (picture >= 0) {
                unit(at, clock, sink);
            }
            if (unitStart < 0) {
                unitStart = at;
            }
        } else if (code == 0xB1 && picture >= 0) {
            unit(at + 4, clock, sink);
            unitStart = -1;
        }
    }

    /**
     * Completes the access unit being collected at an index and starts the next one there.
     *
     * @param end the index after the unit's last byte
     * @param clock the timeline
     * @param sink receives the packets
     */
    private void unit(int end, TsClock clock, UnitSink sink) {
        if (unitStart < 0 || picture < 0) {
            unitStart = end;
            picture = -1;
            return;
        }
        var start = base + unitStart;
        var pictureAt = base + picture;
        var stop = base + end;
        var pts = TsClock.NONE;
        var dts = TsClock.NONE;
        var fileOffset = -1L;
        var corrupt = stop - start > MAX_UNIT;
        for (var marker : markers) {
            if (marker.end <= start || marker.start >= stop) {
                continue;
            }
            if (fileOffset < 0) {
                fileOffset = marker.fileOffset;
            }
            corrupt |= marker.corrupt;
            if (!marker.taken && marker.start <= pictureAt && pictureAt < marker.end) {
                marker.taken = true;
                pts = marker.pts;
                dts = marker.dts;
            }
        }
        var unitEnd = Math.min(end, unitStart + MAX_UNIT);
        var unit = new Unit(null, Math.max(0, fileOffset), corrupt, codingType, fields, pts, dts);
        stamp(unit);
        if (held.isEmpty() && (unit.pts != TsClock.NONE || unit.dts == TsClock.NONE)) {
            delegate.accept(new Pes(buffer, unitStart, unitEnd - unitStart, unit.pts, unit.dts, unit.fileOffset,
                    unit.corrupt, false), clock, sink);
        } else {
            unit.data = Arrays.copyOfRange(buffer, unitStart, unitEnd);
            held.add(unit);
            release(clock, sink, false);
        }
        unitStart = end;
        picture = -1;
        awaitingField = false;
    }

    /**
     * Gives an access unit its decoding time and, when it can be known now, its presentation time.
     *
     * @param unit the access unit
     */
    private void stamp(Unit unit) {
        var period = fieldTicks();
        reordered |= isBidirectional(unit.codingType);
        if (unit.pts != TsClock.NONE) {
            if (unit.dts == TsClock.NONE) {
                unit.dts = unit.pts;
            }
            reordered |= unit.dts != unit.pts;
            stampedDts = unit.dts;
            fieldsSince = unit.fields;
            if (!mpeg4 && temporalReference >= 0 && !Double.isNaN(period)) {
                groupAnchor = (unit.pts - Math.round(temporalReference * 2 * period)) & (TsClock.WRAP - 1);
            }
            lastDts = unit.dts;
            return;
        }
        if (stampedDts == TsClock.NONE || Double.isNaN(period)) {
            return;
        }
        unit.dts = (stampedDts + Math.round(fieldsSince * period)) & (TsClock.WRAP - 1);
        fieldsSince += unit.fields;
        lastDts = unit.dts;
        if (isBidirectional(unit.codingType) || !reordered) {
            unit.pts = unit.dts;
        } else if (!mpeg4 && groupAnchor != TsClock.NONE) {
            unit.pts = (groupAnchor + Math.round(temporalReference * 2 * period)) & (TsClock.WRAP - 1);
        }
    }

    /**
     * Passes on the held access units whose presentation times are known.
     *
     * @param clock the timeline
     * @param sink receives the packets
     * @param all whether every held unit is passed on, the waiting ones with the B-pictures held behind them
     */
    private void release(TsClock clock, UnitSink sink, boolean all) {
        while (!held.isEmpty()) {
            var first = held.peekFirst();
            if (first.pts == TsClock.NONE && first.dts != TsClock.NONE) {
                var following = 0;
                var resolved = false;
                var skip = true;
                for (var unit : held) {
                    if (skip) {
                        skip = false;
                        continue;
                    }
                    if (!isBidirectional(unit.codingType)) {
                        resolved = true;
                        break;
                    }
                    following++;
                }
                if (!resolved && !all && held.size() < MAX_HELD) {
                    return;
                }
                var period = fieldTicks();
                first.pts = (first.dts + Math.round((following + 1) * 2 * period)) & (TsClock.WRAP - 1);
            }
            held.pollFirst();
            delegate.accept(new Pes(first.data, 0, first.data.length, first.pts, first.dts, first.fileOffset,
                    first.corrupt, false), clock, sink);
        }
    }

    /**
     * Returns whether a coding type is that of a B-picture.
     *
     * @param type the coding type
     * @return {@code true} for a B-picture or B-VOP
     */
    private boolean isBidirectional(int type) {
        return mpeg4 ? type == 2 : type == 3;
    }

    /**
     * Returns the duration of a field in 90 kHz ticks, from the frame rate of the track.
     *
     * @return the duration, or {@code NaN} while the frame rate is unknown
     */
    private double fieldTicks() {
        var described = delegate.track();
        if (!(described instanceof VideoTrack video)) {
            return Double.NaN;
        }
        var rate = video.frameRate().orElse(null);
        if (rate == null || rate.numerator() <= 0 || rate.denominator() <= 0) {
            return Double.NaN;
        }
        return 45_000.0 * rate.denominator() / rate.numerator();
    }
}
