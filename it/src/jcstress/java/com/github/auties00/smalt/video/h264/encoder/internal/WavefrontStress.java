package com.github.auties00.smalt.video.h264.encoder.internal;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The H.264 encoder's row wavefront ({@link Wavefront}): a blocked row wakes when the row above publishes or the
 * wavefront aborts, and a publication makes the row's writes visible.
 */
public final class WavefrontStress {
    private WavefrontStress() {
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the blocked row woke up when the row published")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the blocked row missed the publication")
    @State
    public static class Wakeup {
        private final Wavefront wavefront = new Wavefront(2);

        @Actor
        public void waiter() {
            wavefront.await(0, 1);
        }

        @Signal
        public void publish() {
            wavefront.publish(0, 1);
        }
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the abort released the blocked row")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the blocked row missed the abort")
    @State
    public static class Abort {
        private final Wavefront wavefront = new Wavefront(2);

        @Actor
        public void waiter() {
            try {
                wavefront.await(0, 1);
            } catch (java.util.concurrent.CancellationException expected) {
                // the abort ends the wait
            }
        }

        @Signal
        public void abort() {
            wavefront.abort();
        }
    }

    @JCStressTest
    @Outcome(id = "1, 42", expect = Expect.ACCEPTABLE, desc = "the waiter saw the progress and the data before it")
    @Outcome(expect = Expect.FORBIDDEN, desc = "the progress was seen without the data written before it")
    @State
    public static class Publication {
        private final Wavefront wavefront = new Wavefront(2);
        private int data;

        @Actor
        public void writer() {
            data = 42;
            wavefront.publish(0, 1);
        }

        @Actor
        public void reader(II_Result result) {
            result.r1 = wavefront.awaitProgress(0, 1);
            result.r2 = data;
        }
    }
}
