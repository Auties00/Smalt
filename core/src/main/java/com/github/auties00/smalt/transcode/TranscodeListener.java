package com.github.auties00.smalt.transcode;

/**
 * Receives the progress of a transcode.
 *
 * <p>The listener runs on the thread that runs the transcode, between packets, so it must return
 * quickly; a listener that throws aborts the transcode with that exception. Positions never
 * decrease, and the last call, made once the output is complete, reports the output duration.
 */
@FunctionalInterface
public interface TranscodeListener {
    /**
     * Reports progress.
     *
     * @param progress the output position reached and the expected output duration
     */
    void onProgress(TranscodeProgress progress);
}
