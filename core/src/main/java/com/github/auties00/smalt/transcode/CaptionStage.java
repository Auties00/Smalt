package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.subtitle.CaptionExtractor;
import com.github.auties00.smalt.track.Packet;

/**
 * Turns the closed captions carried in the packets of a video track into a text subtitle stream: an extractor
 * reads them in presentation order and a subtitle stage converts them.
 */
final class CaptionStage implements Stage {
    private final CaptionExtractor extractor;
    private final SubtitleStage subtitles;

    /**
     * Constructs a stage.
     *
     * @param extractor the extractor of the video track's captions
     * @param subtitles the stage converting the caption track the extractor makes
     */
    CaptionStage(CaptionExtractor extractor, SubtitleStage subtitles) {
        this.extractor = extractor;
        this.subtitles = subtitles;
    }

    @Override
    public int sourceTrackId() {
        return extractor.track().id();
    }

    @Override
    public void accept(Packet packet) {
        for (var captions : extractor.extract(packet)) {
            subtitles.accept(captions);
        }
    }

    @Override
    public void finish() {
        for (var captions : extractor.flush()) {
            subtitles.accept(captions);
        }
        subtitles.finish();
    }

    @Override
    public boolean done() {
        return subtitles.done();
    }

    @Override
    public long positionMicros() {
        return subtitles.positionMicros();
    }

    @Override
    public TranscodedStream result() {
        return subtitles.result();
    }

    @Override
    public void close() {
        subtitles.close();
    }
}
