package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes pictures as Windows animated cursors (ANI).
 *
 * <p>Each stored frame is an icon or cursor file that {@link IcoEncoder} writes with
 * {@link AniEncoderOptions#frameOptions()}, so a frame is at most 256 by 256 pixels unless those
 * options set entry sizes. The file is laid out as
 * {@code RIFF('ACON' [LIST('INFO' INAM IART)] anih [rate] [seq ] LIST('fram' icon ...))}: the
 * {@code INFO} list only when a title or an author is set, the {@code rate} chunk only when the
 * steps have different display times (otherwise the {@code anih} default display time holds them),
 * and the {@code seq } chunk only when the steps do not show each frame once, in order. The
 * {@code anih} chunk declares that the frames are icon files; its raw-frame size, bit count and
 * plane fields are zero.
 *
 * <p>This class is thread-safe.
 */
public final class AniEncoder {
    /**
     * The display time of a picture encoded alone, in jiffies: one second.
     */
    private static final long STILL_JIFFIES = 60;

    /**
     * The {@code anih} flag declaring that the frames are icon files.
     */
    private static final int AF_ICON = 1;

    /**
     * The {@code anih} flag declaring a {@code seq } chunk.
     */
    private static final int AF_SEQUENCE = 2;

    /**
     * The largest RIFF chunk size.
     */
    private static final long MAX_RIFF = 0xFFFF_FFFFL;

    /**
     * Prevents instantiation.
     */
    private AniEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a one-frame animated cursor with the {@link AniEncoderOptions#defaults()}.
     *
     * @param picture the picture, at most 256 by 256 pixels
     * @return the complete file
     * @throws IllegalArgumentException if the picture is larger than 256 by 256 pixels
     * @see #encode(Picture, AniEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, AniEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as a one-frame animated cursor of one step, shown for one second.
     *
     * @param picture the picture; at most 256 by 256 pixels unless the frame options set sizes
     * @param options the frame options, the title and the author
     * @return the complete file
     * @throws IllegalArgumentException if an entry would be larger than 256 by 256 pixels
     */
    public static byte[] encode(Picture picture, AniEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture as a one-frame animated cursor of one step, shown for one second, and writes it
     * to a sink.
     *
     * @param picture the picture; at most 256 by 256 pixels unless the frame options set sizes
     * @param options the frame options, the title and the author
     * @param sink the output, written from its position; not closed
     * @throws IllegalArgumentException if an entry would be larger than 256 by 256 pixels
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, AniEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        encode(List.of(picture), List.of(new AniStep(0, STILL_JIFFIES)), options, sink);
    }

    /**
     * Encodes frames and the steps that show them as an animated cursor.
     *
     * @param frames the stored frames, at least one; each at most 256 by 256 pixels unless the frame
     * options set sizes
     * @param steps the displayed steps, in order, at least one, each naming one of {@code frames}
     * @param options the frame options, the title and the author
     * @return the complete file
     * @throws NullPointerException if a frame or a step is {@code null}
     * @throws IllegalArgumentException if {@code frames} or {@code steps} is empty, a step names no
     * frame, or an entry would be larger than 256 by 256 pixels
     * @throws MediaException.LimitExceeded if the file would exceed the 4 GiB a RIFF file holds
     */
    public static byte[] encode(List<? extends Picture> frames, List<AniStep> steps, AniEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(frames, steps, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes frames and the steps that show them as an animated cursor and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(List, List, AniEncoderOptions)} returns. A frame no
     * step shows is still stored.
     *
     * @param frames the stored frames, at least one; each at most 256 by 256 pixels unless the frame
     * options set sizes
     * @param steps the displayed steps, in order, at least one, each naming one of {@code frames}
     * @param options the frame options, the title and the author
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if a frame or a step is {@code null}
     * @throws IllegalArgumentException if {@code frames} or {@code steps} is empty, a step names no
     * frame, or an entry would be larger than 256 by 256 pixels
     * @throws MediaException.LimitExceeded if the file would exceed the 4 GiB a RIFF file holds
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(List<? extends Picture> frames, List<AniStep> steps, AniEncoderOptions options,
                              MediaSink sink) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("an animated cursor needs at least one frame");
        }
        var checked = List.copyOf(steps);
        check(checked, frames.size());
        var icons = new ArrayList<byte[]>(frames.size());
        for (var frame : frames) {
            icons.add(IcoEncoder.encode(Objects.requireNonNull(frame, "frame"), options.frameOptions()));
        }
        write(icons, checked, options, sink);
    }

    /**
     * Opens a writer that encodes an animation as an animated cursor.
     *
     * <p>Each written picture becomes a step shown for its duration, in jiffies (sixtieths of a
     * second): the step's end time is rounded to the nearest jiffy, halves up, and its display time is
     * the difference from the previous step's rounded end, so the rounding does not accumulate. A
     * picture identical to an earlier one is stored once and shown again through the {@code seq }
     * chunk. The animation plays forever.
     *
     * @param width the canvas width, positive; at most 256 unless the frame options set sizes
     * @param height the canvas height, positive; at most 256 unless the frame options set sizes
     * @param options the frame options, the title and the author
     * @param sink the output, written from its position by {@link AnimationWriter#finish()}; not closed
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.LimitExceeded if a dimension exceeds 256 and the frame options set no sizes
     */
    public static AnimationWriter openAnimationWriter(int width, int height, AniEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid canvas size " + width + "x" + height);
        }
        if ((width > 256 || height > 256) && options.frameOptions().sizes().length == 0) {
            throw new MediaException.LimitExceeded("an animated cursor frame is at most 256x256 pixels, not "
                    + width + "x" + height);
        }
        return new AniAnimationWriter(width, height, options, sink);
    }

    /**
     * Checks steps against the frame count.
     *
     * @param steps the displayed steps, in order
     * @param frames the number of frames
     * @throws IllegalArgumentException if there is no step or a step names no frame
     */
    private static void check(List<AniStep> steps, int frames) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("an animated cursor needs at least one step");
        }
        for (var step : steps) {
            if (step.frame() >= frames) {
                throw new IllegalArgumentException("step frame " + step.frame() + " is outside 0.." + (frames - 1));
            }
        }
    }

    /**
     * Writes a file from encoded frames.
     *
     * @param icons the icon file of each frame
     * @param steps the displayed steps, each naming one of {@code icons}
     * @param options the title and the author
     * @param sink the output
     * @throws MediaException.LimitExceeded if the file would exceed the 4 GiB a RIFF file holds
     */
    static void write(List<byte[]> icons, List<AniStep> steps, AniEncoderOptions options, MediaSink sink) {
        var sequenced = steps.size() != icons.size();
        var rated = false;
        for (var i = 0; i < steps.size(); i++) {
            sequenced |= steps.get(i).frame() != i;
            rated |= steps.get(i).jiffies() != steps.getFirst().jiffies();
        }
        var head = new ByteArrayOutputStream();
        if (options.title().isPresent() || options.artist().isPresent()) {
            var info = new ByteArrayOutputStream();
            info.writeBytes(fourCc("INFO"));
            options.title().ifPresent(title -> text(info, "INAM", title));
            options.artist().ifPresent(artist -> text(info, "IART", artist));
            chunk(head, "LIST", info.toByteArray());
        }
        var anih = new byte[36];
        ByteArrays.put32le(anih, 0, 36);
        ByteArrays.put32le(anih, 4, icons.size());
        ByteArrays.put32le(anih, 8, steps.size());
        ByteArrays.put32le(anih, 28, steps.getFirst().jiffies());
        ByteArrays.put32le(anih, 32, AF_ICON | (sequenced ? AF_SEQUENCE : 0));
        chunk(head, "anih", anih);
        if (rated) {
            var rate = new byte[steps.size() * 4];
            for (var i = 0; i < steps.size(); i++) {
                ByteArrays.put32le(rate, i * 4, steps.get(i).jiffies());
            }
            chunk(head, "rate", rate);
        }
        if (sequenced) {
            var seq = new byte[steps.size() * 4];
            for (var i = 0; i < steps.size(); i++) {
                ByteArrays.put32le(seq, i * 4, steps.get(i).frame());
            }
            chunk(head, "seq ", seq);
        }
        var frames = 4L;
        for (var icon : icons) {
            frames += 8 + icon.length + (icon.length & 1);
        }
        var riff = 4L + head.size() + 8 + frames;
        if (frames > MAX_RIFF || riff > MAX_RIFF) {
            throw new MediaException.LimitExceeded("the animated cursor exceeds the 4 GiB a RIFF file holds");
        }
        var header = new byte[12];
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        ByteArrays.put32le(header, 4, riff);
        System.arraycopy(fourCc("ACON"), 0, header, 8, 4);
        sink.write(header);
        sink.write(head.toByteArray());
        var list = new byte[12];
        System.arraycopy(fourCc("LIST"), 0, list, 0, 4);
        ByteArrays.put32le(list, 4, frames);
        System.arraycopy(fourCc("fram"), 0, list, 8, 4);
        sink.write(list);
        var iconHeader = new byte[8];
        System.arraycopy(fourCc("icon"), 0, iconHeader, 0, 4);
        for (var icon : icons) {
            ByteArrays.put32le(iconHeader, 4, icon.length);
            sink.write(iconHeader);
            sink.write(icon);
            if ((icon.length & 1) != 0) {
                sink.write(new byte[1]);
            }
        }
    }

    /**
     * Appends a chunk, padded to an even size.
     *
     * @param out the destination
     * @param id the chunk identifier
     * @param data the chunk data
     */
    private static void chunk(ByteArrayOutputStream out, String id, byte[] data) {
        out.writeBytes(fourCc(id));
        var size = new byte[4];
        ByteArrays.put32le(size, 0, data.length);
        out.writeBytes(size);
        out.writeBytes(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
    }

    /**
     * Appends a NUL-terminated {@code INFO} string chunk.
     *
     * @param out the destination
     * @param id the chunk identifier
     * @param text the text, storable in Windows-1252
     */
    private static void text(ByteArrayOutputStream out, String id, String text) {
        var bytes = text.getBytes(AniFile.TEXT);
        var data = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, data, 0, bytes.length);
        chunk(out, id, data);
    }

    /**
     * Returns the bytes of a four-character code.
     *
     * @param id the code
     * @return its four ASCII bytes
     */
    private static byte[] fourCc(String id) {
        return new byte[]{(byte) id.charAt(0), (byte) id.charAt(1), (byte) id.charAt(2), (byte) id.charAt(3)};
    }
}
