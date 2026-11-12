package com.github.auties00.smalt.internal.caption;

import com.github.auties00.smalt.internal.subtitle.StyledText;

/**
 * Turns the changing screen of a caption decoder into cues.
 *
 * <p>A decoder reports every change of what is shown ({@link #changed(long, StyledText)}) and the commands that
 * complete a caption ({@link #commit(long)}: a memory flip, an erase, a carriage return that rolls the text up, a
 * window shown or hidden). Between two commits the screen of a roll-up or paint-on caption grows a character at a
 * time; the cue of that span shows the screen as it stands at the closing commit, from the first change that put
 * text on an empty screen (or from the opening commit when text was already shown). A pop-on caption, which
 * changes only at commits, gives one cue per caption.
 */
public final class CaptionTimeline {
    /**
     * Receives one cue.
     */
    @FunctionalInterface
    public interface CueSink {
        /**
         * Receives a cue.
         *
         * @param startMicros the time the text appears
         * @param endMicros the time it is replaced or removed, after {@code startMicros}
         * @param text the text shown
         */
        void cue(long startMicros, long endMicros, StyledText text);
    }

    private final CueSink sink;
    private StyledText shown = StyledText.EMPTY;
    private long start;
    private boolean started;

    /**
     * Constructs a timeline.
     *
     * @param sink receives the cues
     */
    public CaptionTimeline(CueSink sink) {
        this.sink = sink;
    }

    /**
     * Records a change of the screen.
     *
     * @param micros the time of the change
     * @param text what the screen now shows
     */
    public void changed(long micros, StyledText text) {
        if (!started && !text.runs().isEmpty()) {
            start = micros;
            started = true;
        }
        shown = text;
        if (text.runs().isEmpty()) {
            started = false;
        }
    }

    /**
     * Ends the span of the screen at a command that completes a caption; the caller reports the screen the
     * command leaves with {@link #changed(long, StyledText)} afterwards.
     *
     * @param micros the time of the command
     */
    public void commit(long micros) {
        emit(micros);
        if (started) {
            start = micros;
        }
    }

    /**
     * Ends the span of the screen shown, as at the end of the stream.
     *
     * @param micros the end of the span
     */
    public void finish(long micros) {
        emit(micros);
        shown = StyledText.EMPTY;
        started = false;
    }

    /**
     * Forgets the screen, as after a seek.
     */
    public void reset() {
        shown = StyledText.EMPTY;
        started = false;
    }

    private void emit(long micros) {
        if (started && !shown.runs().isEmpty() && micros > start) {
            sink.cue(start, micros, shown);
        }
    }
}
