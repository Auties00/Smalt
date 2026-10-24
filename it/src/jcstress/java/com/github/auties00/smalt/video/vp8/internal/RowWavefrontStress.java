package com.github.auties00.smalt.video.vp8.internal;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Mode;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Signal;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The VP8 encoder's row wavefront ({@link RowWavefront}): a parked row wakes when the row above publishes (its park
 * has no timeout, so a lost wake-up would hang the encoder), and a publication makes the row's writes visible.
 */
public final class RowWavefrontStress {
    private RowWavefrontStress() {
    }

    @JCStressTest(Mode.Termination)
    @Outcome(id = "TERMINATED", expect = Expect.ACCEPTABLE, desc = "the waiting row woke up when the row published")
    @Outcome(id = "STALE", expect = Expect.FORBIDDEN, desc = "the waiting row missed the publication")
    @State
    public static class Wakeup {
        private final RowWavefront wavefront = new RowWavefront(2);

        @Actor
        public void waiter() {
            wavefront.await(0, 1);
        }

        @Signal
        public void publish() {
            wavefront.publish(0, 1);
        }
    }

    @JCStressTest
    @Outcome(id = "1, 42", expect = Expect.ACCEPTABLE, desc = "the waiter saw the progress and the data before it")
    @Outcome(expect = Expect.FORBIDDEN, desc = "the progress was seen without the data written before it")
    @State
    public static class Publication {
        private final RowWavefront wavefront = new RowWavefront(2);
        private int data;

        @Actor
        public void writer() {
            data = 42;
            wavefront.publish(0, 1);
        }

        @Actor
        public void reader(II_Result result) {
            result.r1 = wavefront.await(0, 1);
            result.r2 = data;
        }
    }
}
