package com.github.auties00.smalt.internal.task;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs the parallel work of every codec and picture operation of the module on one shared,
 * bounded set of helper threads.
 *
 * <p>This class is internal to the module. It offers four shapes of work:
 * <ul>
 *   <li>{@link #run(int, Band)} computes the independent bands of an operation, typically rows of
 *       a picture, and returns when every band has completed;</li>
 *   <li>{@link #invokeAll(List)} runs independent computations and returns their results in
 *       submission order;</li>
 *   <li>{@link #pipeline(int, Band)} runs the stages of a pipeline, each of which may wait for
 *       the progress of the stages before it;</li>
 *   <li>{@link #submit(Callable)} and {@link #start(Runnable)} start one computation that overlaps
 *       with the caller's own work, which later waits for it with {@link Pending#join()}.</li>
 * </ul>
 *
 * <p>The calling thread always takes part: it claims the tasks of its own batch together with the
 * helpers it wakes, and runs a started computation itself when it waits for one no helper has
 * picked up. Progress therefore never depends on a helper being free, whatever other operations
 * occupy the helpers, and a task may itself fan out (the thread running it takes part in the
 * nested batch the same way) without any risk of deadlock. Bands and the computations of
 * {@link #invokeAll} must not wait for one another; pipeline stages may wait for earlier stages,
 * and a started computation for the computations its thread started before it (see
 * {@link #submit(Callable)}). Every result is computed from its own inputs, so the outcome is the
 * same whatever the scheduling.
 *
 * <p>Codecs and picture operations wrap each public call in an {@link #operation()}, and a decoder,
 * reader or encoder that is called over and over wraps its calls in an {@link Operation} of its
 * own. Operations decide how long a calling virtual thread keeps its carrier (see
 * {@link #checkpoint()}) and how many threads {@link #available()} reports.
 *
 * <p>Failures never interrupt a batch: every task runs to completion, then the failure of the
 * first failing task in index order is rethrown unchanged, with the failures of the later tasks
 * added to it as suppressed exceptions; a checked exception, which codec tasks never throw, is
 * wrapped in an {@link IllegalStateException}.
 *
 * @implNote This implementation keeps one process-wide set of at most {@link #THREADS} minus one
 * daemon platform threads (the caller being the remaining participant), started on
 * demand and stopped after {@link #IDLE_NANOS} without work, so an idle library holds
 * no threads and any number of concurrent callers never starts more. Threads that wait
 * free their processors, so the limit grows while they do: by one while a platform
 * thread other than a helper is parked in a wait of this class (a parked virtual thread
 * frees its carrier for other virtual threads instead), and by one for every helper
 * blocked in a wait declared with {@link #beginBlocking()}, such as a picture waiting for
 * the rows of its reference, up to {@link #THREADS} minus one more; so at most twice
 * {@link #THREADS} minus one helpers ever exist. Batches and started computations with
 * unclaimed work are posted on a first-in first-out board; an idle helper takes the
 * oldest one and claims its tasks until none are left. Helpers wake
 * only when there is work to claim, and a woken helper that finds the work already
 * claimed goes back to sleep, so nothing stale lingers on the board or keeps the inputs
 * of a finished operation reachable. A poster wakes only {@link #WAKE_FANOUT} helpers
 * itself and each helper it woke wakes {@link #WAKE_FANOUT} more while tasks remain
 * unclaimed, because a single unpark can cost tens of microseconds on some platforms and
 * the poster would otherwise spend that time, serially, once per helper before starting
 * its own share. Work runs on platform threads, never on the common
 * fork-join pool or on virtual threads, because it is pure computation that would pin a
 * carrier anyway.
 * <p>Callers are often virtual threads, which the JDK does not time-slice: a virtual
 * thread that parks gives its carrier to the next runnable virtual thread, and when every
 * carrier runs long computations it waits for one of them to finish before it runs
 * again. A thread that waits here therefore first runs other posted bands and the
 * computations it started before the work it waits for that no helper has claimed, then
 * spins, for up to {@link #YOUNG_SPIN_NANOS} when a young operation waits for its own
 * batch and {@link #SPIN_NANOS} otherwise, and parks only after that; a pipeline's
 * caller runs the first stage and a later stage only once every stage before it has
 * finished, so it never waits inside a stage.
 * <p>The carriers of the virtual threads are shared least attained service first: an
 * operation keeps its carrier through every {@link #checkpoint()} until it has computed
 * for its budget ({@link #YOUNG_NANOS} for one call, {@link #STREAM_YOUNG_NANOS} for the
 * calls of a stream), and from then on offers it every {@link #OLD_SLICE_NANOS}; a
 * virtual thread outside any operation offers it every {@link #SLICE_NANOS}. Short
 * operations therefore finish as if alone on their carrier while long ones share the
 * carriers left in short slices, which keeps the throughput of first-in first-out
 * scheduling and the latency of short operations of processor sharing. Codecs place
 * checkpoints between units of work (frames, macroblock rows), and every entry point of
 * this class is one.
 * <p>{@link #available()} counts, besides the busy helpers, one processor for every other
 * thread running an operation, or, for a young operation, for every other young one, so
 * speculative and pipelined work (which only changes how a result is computed, never the
 * result) runs only on processors the concurrent operations leave free, and young
 * operations keep it at the expense of long ones.
 */
public final class ParallelBands {
    /**
     * The most threads, the caller included, that run the tasks of the module at once, from the
     * {@code smalt.threads} system property, by default the number of available processors;
     * {@code 1} runs every task on the calling thread.
     */
    public static final int THREADS = Math.max(1, Integer.getInteger("smalt.threads",
            Runtime.getRuntime().availableProcessors()));

    /**
     * The number of helper threads the module runs, shared by every operation: {@link #THREADS}
     * minus the calling thread; {@link #limit()} lets more run while threads wait.
     */
    private static final int HELPERS = THREADS - 1;

    /**
     * The number of platform threads, other than helpers, parked in a wait of this class.
     */
    private static final AtomicInteger PARKED = new AtomicInteger();

    /**
     * The number of helpers blocked in a wait declared with {@link #beginBlocking()}.
     */
    private static final AtomicInteger BLOCKED = new AtomicInteger();

    /**
     * The most helpers one banded picture operation asks for, which bounds its band count.
     */
    private static final int MAX_BAND_HELPERS = 7;

    /**
     * The number of helpers a banded operation asks for, from zero to {@link #MAX_BAND_HELPERS}:
     * one less than the number of available processors, or than {@link #THREADS} when that is
     * smaller.
     */
    private static final int BAND_HELPERS = Math.clamp(Math.min(Runtime.getRuntime().availableProcessors(), THREADS) - 1,
            0, MAX_BAND_HELPERS);

    /**
     * The most stages of a pipeline.
     */
    private static final int MAX_STAGES = 31;

    /**
     * The smallest amount of work, in output samples, worth splitting across threads.
     */
    public static final long MIN_PARALLEL_WORK = 1L << 19;

    /**
     * The nanoseconds an idle helper waits for work before it stops: the consecutive frames of a
     * video keep the helpers alive, while a library that has finished its work holds no threads a
     * second later.
     */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);

    /**
     * The nanoseconds a waiting thread with nothing to help with spins before it parks: longer
     * than a park and unpark round trip, shorter than a band of the smallest operation worth
     * splitting.
     */
    private static final long SPIN_NANOS = TimeUnit.MICROSECONDS.toNanos(50);

    /**
     * The nanoseconds a virtual thread running a young {@link Operation} spins before it parks
     * while it waits for its own batch: long enough to cover a band a helper runs for it, since
     * once unparked it may wait for a carrier behind every other runnable virtual thread.
     */
    private static final long YOUNG_SPIN_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    /**
     * The most helpers one poster or one woken helper wakes itself: the others are woken in turn
     * by the helpers it woke, so that a poster does not spend a batch's critical path on a dozen
     * unparks.
     */
    private static final int WAKE_FANOUT = 2;

    /**
     * The most computations a waiting thread runs for others on top of each other, which bounds
     * the stack a chain of waits can build.
     */
    private static final int MAX_HELP_DEPTH = 2;

    /**
     * The nanoseconds a virtual thread outside any {@link Operation} computes between two offers of
     * its carrier to other virtual threads.
     */
    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /**
     * The budget of the operation of a single call ({@link #operation()}): the computing time, in
     * nanoseconds, it attains before its thread starts offering its carrier to other virtual
     * threads. Calls shorter than this run to the end once they have a carrier.
     */
    private static final long YOUNG_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

    /**
     * The budget of the operation of a stream of calls ({@link Operation#Operation()}), larger
     * than {@link #YOUNG_NANOS} because each call of a stream, such as the decoding of one frame,
     * is short and its caller waits for every one: a short clip decodes within it.
     */
    private static final long STREAM_YOUNG_NANOS = TimeUnit.MILLISECONDS.toNanos(400);

    /**
     * The nanoseconds a virtual thread running an {@link Operation} that has used up its budget
     * computes between two offers of its carrier.
     */
    private static final long OLD_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    /**
     * The state of each thread: its operation, its time slice and how deep it helps others.
     */
    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);

    /**
     * The number of threads running an {@link Operation}.
     */
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    /**
     * The number of threads running an {@link Operation} that has not used up its budget, as far
     * as their last checkpoint knows.
     */
    private static final AtomicInteger YOUNG_ACTIVE = new AtomicInteger();

    /**
     * The number that orders posted work, increasing with every post.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * The kind of batch posted by {@link #run}: short independent bands that a waiting thread may
     * run for another operation.
     */
    private static final int BANDS = 0;

    /**
     * The kind of batch posted by {@link #invokeAll}: independent computations, possibly long.
     */
    private static final int TASKS = 1;

    /**
     * The kind of batch posted by {@link #pipeline}: stages that may wait for earlier ones.
     */
    private static final int STAGES = 2;

    /**
     * The batches and started computations that may still have unclaimed work, oldest first.
     */
    private static final ConcurrentLinkedQueue<Work> BOARD = new ConcurrentLinkedQueue<>();

    /**
     * The number of batches and started computations ever posted on {@link #BOARD}, counted after
     * each is posted: posted work is only ever claimed, never released, so a waiting thread whose
     * last look at the board found nothing to help with looks again only once this has changed.
     */
    private static final AtomicLong POSTED = new AtomicLong();

    /**
     * Guards {@link #IDLE}, {@link #idleCount}, {@link #started} and {@link #created}.
     */
    private static final Object LOCK = new Object();

    /**
     * The sleeping helpers, the most recently idle first.
     */
    private static final ArrayDeque<Helper> IDLE = new ArrayDeque<>();

    /**
     * The number of sleeping helpers, readable without {@link #LOCK}.
     */
    private static volatile int idleCount;

    /**
     * The number of live helper threads, readable without {@link #LOCK}.
     */
    private static volatile int started;

    /**
     * The number of helpers running or claiming a task.
     */
    private static final AtomicInteger BUSY = new AtomicInteger();

    /**
     * The number of helper threads created so far, which numbers their names.
     */
    private static int created;

    /**
     * Prevents instantiation.
     */
    private ParallelBands() {
        throw new AssertionError();
    }

    /**
     * Computes one band of an operation, or one stage of a pipeline.
     */
    @FunctionalInterface
    public interface Band {
        /**
         * Computes the band.
         *
         * @param index the index of the band, from zero to the band count minus one
         */
        void run(int index);
    }

    /**
     * Offers the carrier of a virtual calling thread to the other virtual threads waiting for one
     * when the thread has computed long enough: never while its operation is within its budget,
     * every {@link #OLD_SLICE_NANOS} once the operation has used it up, and every
     * {@link #SLICE_NANOS} outside any operation.
     *
     * <p>The JDK does not time-slice virtual threads: one that computes keeps its carrier until it
     * parks or ends, so when more virtual threads compute than there are carriers, a short
     * operation can wait for long ones to finish before it even starts. Codecs call this between
     * units of work, such as frames and macroblock rows, and every entry point of this class calls
     * it, so that long operations share the carriers in short slices while short ones run through.
     * On a platform thread, and when no other virtual thread is waiting, it returns at once.
     */
    public static void checkpoint() {
        if (!Thread.currentThread().isVirtual()) {
            return;
        }
        var local = LOCAL.get();
        var now = System.nanoTime();
        long slice;
        if (local.depth > 0) {
            if (local.attain(now) < local.operation.budget) {
                return;
            }
            slice = OLD_SLICE_NANOS;
        } else {
            slice = SLICE_NANOS;
        }
        if (now - local.sliceStart < slice) {
            return;
        }
        Thread.yield();
        var resumed = System.nanoTime();
        local.sliceStart = resumed;
        local.mark = resumed;
    }

    /**
     * Enters a new operation on the calling thread, until the returned scope is closed.
     *
     * <p>Codecs and picture operations wrap each public call in an operation. The operation
     * accumulates the time the calling thread computes inside it, excluding the time it waits;
     * while that time is within {@link #YOUNG_NANOS} the thread keeps its carrier at every
     * {@link #checkpoint()}, and afterwards it offers its carrier often, so short operations finish
     * without waiting behind long ones. Inside another operation of the same thread, the call
     * continues that operation.
     *
     * @return the scope to close, on the calling thread, when the call returns
     */
    public static Scope operation() {
        var local = LOCAL.get();
        if (local.depth == 0) {
            local.single.attained = 0;
        }
        return local.enter(local.single);
    }

    /**
     * Enters an operation that continues across calls on the calling thread, until the returned
     * scope is closed.
     *
     * <p>A decoder of a stream of frames enters its own {@link Operation} on each call, so the
     * computing time of the whole stream decides how long it keeps its carrier, as
     * {@link #operation()} describes. Inside another operation of the same thread, the call
     * continues that operation.
     *
     * @param operation the operation, used by one thread at a time
     * @return the scope to close, on the calling thread, when the call returns
     */
    public static Scope operation(Operation operation) {
        return LOCAL.get().enter(operation);
    }

    /**
     * Returns whether tasks may run on several threads.
     *
     * @return {@code true} when {@link #THREADS} exceeds 1
     */
    public static boolean enabled() {
        return HELPERS > 0;
    }

    /**
     * Returns how many helpers could start a task right now without taking a processor from
     * concurrent operations: the helpers not running a task, less one for every other thread
     * running an operation, or, when the caller runs a young operation (one within its budget),
     * less one for every other young operation only.
     *
     * <p>The value is a snapshot that other threads may change at once; callers use it only to
     * choose between ways of computing the same result, such as how many speculative computations
     * to start or whether to pipeline, never for correctness.
     *
     * @return the number of available helpers, from zero to {@link #THREADS} minus one
     */
    public static int available() {
        if (HELPERS == 0) {
            return 0;
        }
        var local = LOCAL.get();
        var others = local.counted ? YOUNG_ACTIVE.get() - 1 : ACTIVE.get() - (local.depth > 0 ? 1 : 0);
        return Math.clamp(Math.min(HELPERS - BUSY.get(), HELPERS - others), 0, HELPERS);
    }

    /**
     * Returns how many helpers are not running a task right now, whatever the concurrent
     * operations: the helpers that would claim posted work at once.
     *
     * <p>Unlike {@link #available()}, this does not leave processors to other operations; callers
     * use it to decide who runs work that has to run anyway, never to start speculative work. The
     * value is a snapshot that other threads may change at once.
     *
     * @return the number of idle helpers, from zero to {@link #THREADS} minus one
     */
    public static int idle() {
        return HELPERS == 0 ? 0 : Math.clamp(HELPERS - BUSY.get(), 0, HELPERS);
    }

    /**
     * The computing time of a sequence of calls that form one operation, such as the frames a
     * decoder decodes; see {@link #operation(Operation)}. The calls of an operation run one at a
     * time.
     */
    public static final class Operation {
        /**
         * The computing time, in nanoseconds, the operation attains before its thread starts
         * offering its carrier to other virtual threads.
         */
        private final long budget;

        /**
         * The nanoseconds the operation has computed so far.
         */
        private long attained;

        /**
         * Creates the operation of a stream of calls, which keeps its carrier for
         * {@link #STREAM_YOUNG_NANOS}.
         */
        public Operation() {
            this(STREAM_YOUNG_NANOS);
        }

        /**
         * Creates an operation that has not computed yet.
         *
         * @param budget the computing time, in nanoseconds, before the operation offers its
         * carrier
         */
        private Operation(long budget) {
            this.budget = budget;
        }
    }

    /**
     * The scope of a call inside an operation, closed when the call returns.
     */
    public sealed interface Scope extends AutoCloseable permits Local {
        /**
         * Leaves the operation, or the nested call of it.
         */
        @Override
        void close();
    }

    /**
     * The state of one thread.
     */
    private static final class Local implements Scope {
        /**
         * The operation of a single call, reused by every {@link #operation()} of the thread.
         */
        private final Operation single = new Operation(YOUNG_NANOS);

        /**
         * The operation the thread runs, or {@code null} outside any.
         */
        private Operation operation;

        /**
         * The number of nested scopes the thread is in.
         */
        private int depth;

        /**
         * The {@link System#nanoTime()} from which the thread computes for {@link #operation}
         * without having accounted it yet.
         */
        private long mark;

        /**
         * The {@link System#nanoTime()} at which the thread last obtained its carrier.
         */
        private long sliceStart = System.nanoTime();

        /**
         * The number of computations the thread runs for others while waiting.
         */
        private int helpDepth;

        /**
         * The value of {@link #POSTED} when the thread last looked at the board and found nothing
         * to help with, or {@code -1}.
         */
        private long idlePosted = -1;

        /**
         * The sequence number the thread waited for when it last looked at the board and found
         * nothing to help with.
         */
        private long idleBefore;

        /**
         * Whether the thread counts in {@link #YOUNG_ACTIVE}.
         */
        private boolean counted;

        /**
         * Whether the thread is a helper.
         */
        private boolean helper;

        /**
         * Creates the state of a thread outside any operation.
         */
        private Local() {
        }

        /**
         * Enters an operation, or a nested call when the thread already runs one.
         *
         * @param entered the operation
         * @return this scope
         */
        private Scope enter(Operation entered) {
            if (depth++ == 0) {
                operation = entered;
                mark = System.nanoTime();
                ACTIVE.incrementAndGet();
                if (entered.attained < entered.budget) {
                    counted = true;
                    YOUNG_ACTIVE.incrementAndGet();
                }
            }
            return this;
        }

        /**
         * Accounts the computing time since {@link #mark} to the operation and returns its total.
         *
         * @param now the current {@link System#nanoTime()}
         * @return the nanoseconds the operation has computed
         */
        private long attain(long now) {
            var current = operation;
            current.attained += now - mark;
            mark = now;
            if (counted && current.attained >= current.budget) {
                counted = false;
                YOUNG_ACTIVE.decrementAndGet();
            }
            return current.attained;
        }

        /**
         * Returns whether the thread is a virtual thread running an operation still within its
         * budget.
         *
         * @param now the current {@link System#nanoTime()}
         * @return {@code true} for a young operation on a virtual thread
         */
        private boolean young(long now) {
            return depth > 0 && operation.attained + (now - mark) < operation.budget && Thread.currentThread().isVirtual();
        }

        /**
         * Accounts the computing time up to a wait that is about to begin.
         *
         * @param now the current {@link System#nanoTime()}
         */
        private void pauseAt(long now) {
            if (depth > 0) {
                attain(now);
            }
        }

        /**
         * Restarts the accounting after a wait.
         *
         * @param now the current {@link System#nanoTime()}
         */
        private void resumeAt(long now) {
            mark = now;
            sliceStart = now;
        }

        @Override
        public void close() {
            if (--depth == 0) {
                attain(System.nanoTime());
                operation = null;
                ACTIVE.decrementAndGet();
                if (counted) {
                    counted = false;
                    YOUNG_ACTIVE.decrementAndGet();
                }
            }
        }
    }

    /**
     * Returns the number of bands to split a picture operation into.
     *
     * @param units the number of indivisible units (rows or row groups) of the operation
     * @param work the amount of work of the operation, in output samples
     * @return the band count: one when the operation should run on the calling thread, otherwise
     * at most one band per participating thread and per unit
     */
    public static int bands(int units, long work) {
        if (BAND_HELPERS == 0 || work < MIN_PARALLEL_WORK || units < 2) {
            return 1;
        }
        return (int) Math.min(units, Math.min(BAND_HELPERS + 1L, Math.max(2L, work / MIN_PARALLEL_WORK)));
    }

    /**
     * Returns the first unit of a band when {@code units} units are split into {@code bands}
     * contiguous bands of nearly equal size.
     *
     * @param band the band index, from zero to {@code bands}
     * @param bands the number of bands
     * @param units the number of units
     * @return the first unit of the band, or {@code units} for {@code band == bands}
     */
    public static int start(int band, int bands, int units) {
        return (int) ((long) band * units / bands);
    }

    /**
     * Runs the bands of an operation and returns when all have completed.
     *
     * <p>With a single band, or without helper threads, the bands run on the calling thread in
     * index order. Otherwise the calling thread and up to {@code bands - 1} helpers claim bands in
     * index order until none are left. Either way every band runs to completion and the failures
     * are rethrown as the class documentation describes. Bands must not wait for one another.
     *
     * @param bands the number of bands, positive
     * @param band the computation of one band
     * @throws RuntimeException the unchecked exception of the first failing band
     * @throws Error the error of the first failing band
     */
    public static void run(int bands, Band band) {
        checkpoint();
        if (bands <= 1 || HELPERS == 0) {
            runInline(bands, band);
            return;
        }
        execute(new Batch(bands, band, BANDS));
    }

    /**
     * Runs the stages of a pipeline and returns when all have completed.
     *
     * <p>Stage {@code k} may wait for the progress of any stage before it, never for a later one.
     * The calling thread runs the first stage, and a later stage only once every stage before it
     * has finished, so none of the stages it runs ever waits; helpers take the other stages in
     * order and run them alongside. With one stage, or without helper threads, the stages run on
     * the calling thread in order. Every stage runs to completion and the failures are rethrown as
     * the class documentation describes.
     *
     * @param stages the number of stages, from 1 to 31
     * @param stage the computation of one stage
     * @throws IllegalArgumentException if {@code stages} is out of range
     * @throws RuntimeException the unchecked exception of the first failing stage
     * @throws Error the error of the first failing stage
     */
    public static void pipeline(int stages, Band stage) {
        if (stages < 1 || stages > MAX_STAGES) {
            throw new IllegalArgumentException("stages out of range: " + stages);
        }
        checkpoint();
        if (stages == 1 || HELPERS == 0) {
            runInline(stages, stage);
            return;
        }
        execute(new Batch(stages, stage, STAGES));
    }

    /**
     * Runs independent computations and returns their results in submission order.
     *
     * <p>Every computation runs to completion, even when an earlier one fails; the caller takes
     * part, so the computations may themselves call this class. The computations must not wait
     * for one another.
     *
     * @param tasks the computations
     * @param <T> the result type
     * @return the results, index for index, in an unmodifiable list that may hold {@code null}
     * @throws RuntimeException the unchecked exception of the first failing computation
     * @throws Error the error of the first failing computation
     * @throws IllegalStateException wrapping the checked exception of the first failing
     * computation
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> invokeAll(List<? extends Callable<? extends T>> tasks) {
        checkpoint();
        var count = tasks.size();
        var results = new Object[count];
        Band body = index -> results[index] = call(tasks.get(index));
        if (count <= 1 || HELPERS == 0) {
            runInline(count, body);
        } else {
            execute(new Batch(count, body, TASKS));
        }
        return (List<T>) Collections.unmodifiableList(Arrays.asList(results));
    }

    /**
     * Starts a computation that a helper may run while the calling thread does other work; the
     * caller then waits for its result with {@link Pending#join()}, or gives it up with
     * {@link Pending#cancel()}.
     *
     * <p>The computation runs at most once: on a helper if one picks it up first, otherwise on the
     * waiting thread inside {@link Pending#join()}, so waiting never depends on the helpers being
     * free; the submitting thread may also run it while it waits for other work of the module.
     * Without helper threads it always runs inside {@link Pending#join()}. Threads claim the
     * computations one thread starts in the order it started them, so a computation may wait for
     * the progress of computations the same thread started before it, provided that thread joins
     * them in the order it started them; started computations must not wait for one another in
     * any other way.
     *
     * @param task the computation
     * @param <T> the result type
     * @return the handle to wait for the result with
     */
    public static <T> Pending<T> submit(Callable<? extends T> task) {
        checkpoint();
        var pending = new Pending<T>(task);
        if (HELPERS > 0) {
            pending.posted = true;
            BOARD.offer(pending);
            POSTED.incrementAndGet();
            signal(1);
        }
        return pending;
    }

    /**
     * Starts a computation without a result, as {@link #submit(Callable)} does.
     *
     * @param body the computation
     * @return the handle to wait for the computation with
     */
    public static Pending<Void> start(Runnable body) {
        return submit(() -> {
            body.run();
            return null;
        });
    }

    /**
     * Runs every task of a batch on the calling thread in index order, then rethrows the failures
     * as the class documentation describes.
     *
     * @param count the number of tasks
     * @param body the computation of one task
     * @throws RuntimeException the unchecked exception of the first failing task
     * @throws Error the error of the first failing task
     * @throws IllegalStateException wrapping the checked exception of the first failing task
     */
    private static void runInline(int count, Band body) {
        if (count == 1) {
            body.run(0);
            return;
        }
        Failure failures = null;
        for (var index = 0; index < count; index++) {
            try {
                body.run(index);
            } catch (Throwable throwable) {
                failures = new Failure(index, throwable, failures);
            }
        }
        Failure.rethrowFirst(failures);
    }

    /**
     * Posts a batch, takes part in it until every task is claimed, waits for the tasks other
     * threads claimed, and rethrows the failures.
     *
     * @param batch the batch, created by the calling thread
     */
    private static void execute(Batch batch) {
        var pipeline = batch.kind == STAGES;
        if (pipeline) {
            batch.next = 1;
        }
        BOARD.offer(batch);
        POSTED.incrementAndGet();
        signal(batch.count - 1);
        if (pipeline) {
            batch.runStages();
        } else {
            batch.runAll();
        }
        BOARD.remove(batch);
        batch.await();
        batch.rethrow();
    }

    /**
     * Runs a computation on the calling thread, wrapping a checked exception.
     *
     * @param task the computation
     * @param <T> the result type
     * @return the result
     * @throws IllegalStateException wrapping the checked exception the computation threw
     */
    private static <T> T call(Callable<? extends T> task) {
        try {
            return task.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("codec task failed", exception);
        }
    }

    /**
     * Rethrows a failure unchanged when it is unchecked, wrapped otherwise.
     *
     * @param failure the failure, or {@code null}
     * @throws RuntimeException the failure, when it is one
     * @throws Error the failure, when it is one
     * @throws IllegalStateException wrapping a checked failure
     */
    private static void rethrow(Throwable failure) {
        switch (failure) {
            case null -> {
            }
            case RuntimeException exception -> throw exception;
            case Error error -> throw error;
            default -> throw new IllegalStateException("codec task failed", failure);
        }
    }

    /**
     * Waits a little for a condition another thread will make true: runs one piece of work the
     * waiting thread may help with, or else spins until {@link #SPIN_NANOS} after {@code since}
     * ({@link #YOUNG_SPIN_NANOS} for a young operation waiting for its own batch), or else parks
     * until unparked.
     *
     * <p>The caller re-checks its condition after every call; whoever makes it true unparks the
     * waiting thread. An interrupt ends the park early and stays set.
     *
     * @param blocker the object the thread parks on
     * @param since the {@link System#nanoTime()} at which the wait began
     * @param before the sequence number of the work waited for: the thread runs only its own
     * started computations posted before it
     * @param batch whether the thread waits for the tasks or stages of its own batch, which the
     * helpers run for it, rather than for a started computation, which may be long
     */
    private static void pause(Object blocker, long since, long before, boolean batch) {
        if (help(before)) {
            return;
        }
        var now = System.nanoTime();
        var local = LOCAL.get();
        if (now - since < (batch && local.young(now) ? YOUNG_SPIN_NANOS : SPIN_NANOS)) {
            Thread.onSpinWait();
            return;
        }
        local.pauseAt(now);
        var compensated = !local.helper && !Thread.currentThread().isVirtual();
        if (compensated && PARKED.getAndIncrement() == 0 && !BOARD.isEmpty()) {
            signal(1);
        }
        LockSupport.park(blocker);
        if (compensated) {
            PARKED.decrementAndGet();
        }
        local.resumeAt(System.nanoTime());
    }

    /**
     * Returns the most helpers that may run tasks at once: {@link #HELPERS}, plus one while a
     * platform thread other than a helper is parked in a wait of this class, plus one for every
     * helper blocked in a wait declared with {@link #beginBlocking()}, at most {@link #HELPERS}
     * more.
     *
     * @return the limit, at most twice {@link #THREADS} minus one
     */
    private static int limit() {
        return HELPERS + (PARKED.get() > 0 ? 1 : 0) + Math.min(BLOCKED.get(), HELPERS);
    }

    /**
     * Declares that the calling thread is about to block until another computation of the module
     * makes progress, such as a picture waiting for the rows of its reference picture; the thread
     * calls {@link #endBlocking()} once it runs again.
     *
     * <p>A helper that blocks keeps no processor busy, so while it is blocked one more helper may
     * claim posted work, up to {@link #HELPERS} more in all: the work the blocked tasks wait for,
     * and the work other operations posted, keeps running. On other threads this does nothing.
     */
    public static void beginBlocking() {
        if (HELPERS > 0 && LOCAL.get().helper) {
            BLOCKED.incrementAndGet();
            if (!BOARD.isEmpty()) {
                signal(1);
            }
        }
    }

    /**
     * Ends a wait declared with {@link #beginBlocking()}.
     */
    public static void endBlocking() {
        if (HELPERS > 0 && LOCAL.get().helper) {
            BLOCKED.decrementAndGet();
        }
    }

    /**
     * Runs one piece of posted work that a waiting thread may take on: a band of another
     * operation, or a computation the calling thread started, before the work it waits for, and
     * no helper has claimed.
     *
     * <p>Computations of {@link #invokeAll} and pipeline stages are left to their callers and the
     * helpers: the former can be long, the latter may wait. Computations the thread started after
     * the work it waits for are left too: they may be long, and may even wait for that work.
     *
     * <p>A thread that found nothing to help with does not look at the board again, for the same
     * wait, until more work is posted: the look walks the board with an iterator, which a thread
     * spinning in {@link #pause} would otherwise allocate on every turn.
     *
     * @param before the sequence number of the work the thread waits for
     * @return {@code true} when a piece of work was run
     */
    private static boolean help(long before) {
        if (BOARD.isEmpty()) {
            return false;
        }
        var local = LOCAL.get();
        if (local.helpDepth >= MAX_HELP_DEPTH) {
            return false;
        }
        var posted = POSTED.get();
        if (posted == local.idlePosted && before == local.idleBefore) {
            return false;
        }
        var self = Thread.currentThread();
        local.helpDepth++;
        try {
            for (var work : BOARD) {
                switch (work) {
                    case Batch batch -> {
                        if (batch.kind == BANDS && batch.runOne()) {
                            return true;
                        }
                    }
                    case Pending<?> pending -> {
                        if (pending.owner == self && pending.sequence < before && pending.runOne()) {
                            BOARD.remove(pending);
                            return true;
                        }
                    }
                }
            }
            local.idlePosted = posted;
            local.idleBefore = before;
            return false;
        } finally {
            local.helpDepth--;
        }
    }

    /**
     * Wakes sleeping helpers, starting new ones while fewer than the maximum are alive, to claim
     * newly posted work.
     *
     * <p>The work is posted before this is called, and a helper going to sleep registers itself
     * before it looks at the board one last time, so either the helper sees the work or this sees
     * the helper. At most {@link #WAKE_FANOUT} helpers are woken directly; a woken helper that
     * finds more unclaimed tasks than it can take wakes {@link #WAKE_FANOUT} more, so the sleeping
     * helpers wake as a tree and the poster, which waits for each unpark, starts its own share of
     * the work after two. Helpers that cannot be woken because too few exist are started here
     * instead. A helper that cannot be started is ignored: the caller runs the work itself.
     *
     * @param wanted the number of helpers the work can use
     */
    private static void signal(int wanted) {
        var limit = limit();
        if (idleCount == 0 && started >= limit) {
            return;
        }
        var remaining = Math.min(wanted, limit);
        remaining -= wake(Math.min(remaining, WAKE_FANOUT));
        remaining -= idleCount;
        if (remaining <= 0) {
            return;
        }
        var start = 0;
        var first = 0;
        synchronized (LOCK) {
            start = Math.min(remaining, limit - started);
            if (start > 0) {
                started += start;
                first = created;
                created += start;
            }
        }
        for (var i = 0; i < start; i++) {
            try {
                new Helper(first + i + 1).thread.start();
            } catch (Throwable failure) {
                synchronized (LOCK) {
                    started--;
                }
            }
        }
    }

    /**
     * Wakes up to {@code max} sleeping helpers, taking them off {@link #IDLE} under the lock and
     * unparking them after it is released, since an unpark can take tens of microseconds.
     *
     * @param max the most helpers to wake, at most {@link #WAKE_FANOUT}
     * @return the number of helpers woken
     */
    private static int wake(int max) {
        Helper first = null;
        Helper second = null;
        var woke = 0;
        synchronized (LOCK) {
            Helper helper;
            while (woke < max && (helper = IDLE.pollFirst()) != null) {
                helper.woken = true;
                if (woke == 0) {
                    first = helper;
                } else {
                    second = helper;
                }
                woke++;
            }
            if (woke > 0) {
                idleCount = IDLE.size();
            }
        }
        if (first != null) {
            LockSupport.unpark(first.thread);
        }
        if (second != null) {
            LockSupport.unpark(second.thread);
        }
        return woke;
    }

    /**
     * Work posted on the board: a batch of tasks or one started computation.
     */
    private sealed interface Work permits Batch, Pending {
        /**
         * Claims and runs one unclaimed task.
         *
         * @return {@code false} when no task was left to claim
         */
        boolean runOne();

        /**
         * Returns how many tasks no thread has claimed yet, at least as an estimate.
         *
         * @return the number of unclaimed tasks
         */
        int unclaimed();
    }

    /**
     * A helper thread: it claims posted work until the board is empty, then sleeps until woken or
     * until it has been idle for {@link #IDLE_NANOS}.
     */
    private static final class Helper implements Runnable {
        /**
         * The thread running this helper.
         */
        private final Thread thread;

        /**
         * Whether a poster has taken this helper off {@link #IDLE} to claim new work.
         */
        private volatile boolean woken;

        /**
         * Creates a helper and its unstarted daemon thread.
         *
         * @param number the number in the thread name
         */
        Helper(int number) {
            this.thread = Thread.ofPlatform()
                    .name("smalt-worker-" + number)
                    .daemon(true)
                    .inheritInheritableThreadLocals(false)
                    .unstarted(this);
            thread.setContextClassLoader(ParallelBands.class.getClassLoader());
        }

        /**
         * Claims work until the helper has been idle for {@link #IDLE_NANOS}.
         */
        @Override
        public void run() {
            LOCAL.get().helper = true;
            var exited = false;
            try {
                do {
                    drain();
                } while (sleep());
                exited = true;
            } finally {
                if (!exited) {
                    synchronized (LOCK) {
                        IDLE.remove(this);
                        idleCount = IDLE.size();
                        started--;
                    }
                }
            }
        }

        /**
         * Claims and runs the tasks of the oldest posted work until the board is empty, removing
         * the work whose tasks are all claimed, or until {@link #limit()} helpers are busy.
         */
        private static void drain() {
            Work work;
            while ((work = BOARD.peek()) != null && BUSY.get() < limit()) {
                BUSY.incrementAndGet();
                boolean ran;
                try {
                    ran = work.runOne();
                } finally {
                    BUSY.decrementAndGet();
                }
                if (!ran) {
                    BOARD.remove(work);
                }
            }
        }

        /**
         * Sleeps until woken for new work.
         *
         * @return {@code true} to claim work again, {@code false} when the helper has stopped
         * after being idle for {@link #IDLE_NANOS}
         */
        private boolean sleep() {
            synchronized (LOCK) {
                woken = false;
                IDLE.addFirst(this);
                idleCount = IDLE.size();
            }
            if (BOARD.peek() != null && BUSY.get() < limit()) {
                synchronized (LOCK) {
                    if (!woken && IDLE.remove(this)) {
                        idleCount = IDLE.size();
                    }
                }
                return true;
            }
            var deadline = System.nanoTime() + IDLE_NANOS;
            while (!woken) {
                var left = deadline - System.nanoTime();
                if (left <= 0) {
                    synchronized (LOCK) {
                        if (!woken) {
                            IDLE.remove(this);
                            idleCount = IDLE.size();
                            if (BOARD.peek() != null) {
                                return true;
                            }
                            started--;
                            return false;
                        }
                    }
                    break;
                }
                LockSupport.parkNanos(this, left);
                Thread.interrupted();
            }
            var work = BOARD.peek();
            if (work != null && idleCount != 0 && work.unclaimed() > 1) {
                wake(WAKE_FANOUT);
            }
            return true;
        }
    }

    /**
     * The shared state of a batch of tasks: the next unclaimed index, the number of unfinished
     * tasks, the finished stages of a pipeline and the failures.
     */
    private static final class Batch implements Work {
        /**
         * The handle of {@link #next}.
         */
        private static final VarHandle NEXT;

        /**
         * The handle of {@link #remaining}.
         */
        private static final VarHandle REMAINING;

        /**
         * The handle of {@link #finished}.
         */
        private static final VarHandle FINISHED;

        /**
         * The handle of {@link #failures}.
         */
        private static final VarHandle FAILURES;

        static {
            try {
                var lookup = MethodHandles.lookup();
                NEXT = lookup.findVarHandle(Batch.class, "next", int.class);
                REMAINING = lookup.findVarHandle(Batch.class, "remaining", int.class);
                FINISHED = lookup.findVarHandle(Batch.class, "finished", int.class);
                FAILURES = lookup.findVarHandle(Batch.class, "failures", Failure.class);
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        /**
         * The number of tasks.
         */
        private final int count;

        /**
         * The computation of one task.
         */
        private final Band body;

        /**
         * The kind of batch: {@link #BANDS}, {@link #TASKS} or {@link #STAGES}.
         */
        private final int kind;

        /**
         * The thread that posted the batch, woken when the last task completes, and for a
         * pipeline when any stage completes.
         */
        private final Thread caller;

        /**
         * The sequence number of the batch among all posted work.
         */
        private final long sequence;

        /**
         * The index of the next task to claim.
         */
        private volatile int next;

        /**
         * The number of tasks that have not completed.
         */
        private volatile int remaining;

        /**
         * The completed stages of a pipeline, one bit per stage.
         */
        private volatile int finished;

        /**
         * The failures recorded so far, the latest first, or {@code null}.
         */
        private volatile Failure failures;

        /**
         * Creates the state of a batch posted by the calling thread.
         *
         * @param count the number of tasks
         * @param body the computation of one task
         * @param kind the kind of batch
         */
        Batch(int count, Band body, int kind) {
            this.count = count;
            this.body = body;
            this.kind = kind;
            this.caller = Thread.currentThread();
            this.remaining = count;
            this.sequence = SEQUENCE.incrementAndGet();
        }

        @Override
        public int unclaimed() {
            return Math.max(0, count - next);
        }

        @Override
        public boolean runOne() {
            if (next >= count) {
                return false;
            }
            var index = (int) NEXT.getAndAdd(this, 1);
            if (index >= count) {
                return false;
            }
            runTask(index);
            return true;
        }

        /**
         * Runs a claimed task, records its failure, and wakes the caller when the batch or, for
         * a pipeline, a stage completes.
         *
         * @param index the index of the task
         */
        private void runTask(int index) {
            try {
                body.run(index);
            } catch (Throwable throwable) {
                fail(index, throwable);
            } finally {
                if (kind == STAGES) {
                    FINISHED.getAndBitwiseOr(this, 1 << index);
                }
                var last = (int) REMAINING.getAndAdd(this, -1) == 1;
                if ((last || kind == STAGES) && Thread.currentThread() != caller) {
                    LockSupport.unpark(caller);
                }
            }
        }

        /**
         * Claims and runs tasks until none are left to claim.
         */
        void runAll() {
            boolean claimed;
            do {
                claimed = runOne();
            } while (claimed);
        }

        /**
         * Runs the stages of a pipeline on its caller: the first stage, which the caller claimed
         * before posting the batch, then every later stage no helper has claimed, each only once
         * the stages before it have finished.
         */
        void runStages() {
            runTask(0);
            var since = 0L;
            var interrupted = false;
            while (true) {
                var stage = next;
                if (stage >= count) {
                    break;
                }
                var before = (1 << stage) - 1;
                if ((finished & before) == before) {
                    if (NEXT.compareAndSet(this, stage, stage + 1)) {
                        runTask(stage);
                    }
                    since = 0L;
                    continue;
                }
                if (since == 0L) {
                    since = System.nanoTime();
                }
                pause(this, since, sequence, true);
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Records the failure of a task.
         *
         * @param index the index of the task
         * @param failure what the task threw
         */
        void fail(int index, Throwable failure) {
            Failure head;
            do {
                head = failures;
            } while (!FAILURES.compareAndSet(this, head, new Failure(index, failure, head)));
        }

        /**
         * Waits until every task has completed, helping with other work meanwhile.
         */
        void await() {
            if (remaining == 0) {
                return;
            }
            var since = System.nanoTime();
            var interrupted = false;
            while (remaining != 0) {
                pause(this, since, sequence, true);
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Rethrows the failure of the first failing task in index order, with the later failures
         * suppressed in it.
         *
         * @throws RuntimeException the first failure, when it is one
         * @throws Error the first failure, when it is one
         * @throws IllegalStateException wrapping a checked first failure
         */
        void rethrow() {
            Failure.rethrowFirst(failures);
        }
    }

    /**
     * A recorded failure of a task, in a list of failures.
     *
     * @param index the index of the task
     * @param failure what the task threw
     * @param next the failure recorded before, or {@code null}
     */
    private record Failure(int index, Throwable failure, Failure next) {
        /**
         * Rethrows the failure of the lowest task index in a list, with the distinct failures of
         * the other tasks, in index order, suppressed in it; does nothing for an empty list.
         *
         * @param head the latest recorded failure, or {@code null}
         * @throws RuntimeException the first failure, when it is one
         * @throws Error the first failure, when it is one
         * @throws IllegalStateException wrapping a checked first failure
         */
        static void rethrowFirst(Failure head) {
            if (head == null) {
                return;
            }
            var size = 0;
            for (var node = head; node != null; node = node.next()) {
                size++;
            }
            var sorted = new Failure[size];
            var i = 0;
            for (var node = head; node != null; node = node.next()) {
                sorted[i++] = node;
            }
            Arrays.sort(sorted, (a, b) -> Integer.compare(a.index(), b.index()));
            var first = sorted[0].failure();
            for (var k = 1; k < size; k++) {
                var other = sorted[k].failure();
                var seen = false;
                for (var j = 0; j < k && !seen; j++) {
                    seen = sorted[j].failure() == other;
                }
                if (!seen) {
                    first.addSuppressed(other);
                }
            }
            rethrow(first);
        }
    }

    /**
     * A computation started by {@link #submit(Callable)} or {@link #start(Runnable)}, which runs at
     * most once.
     *
     * <p>Any thread may wait for the computation, and several may wait one after the other; the
     * result and the failure are reported to each.
     *
     * @param <T> the result type
     */
    public static final class Pending<T> implements Work {
        /**
         * The state of a computation no thread has claimed yet.
         */
        private static final int QUEUED = 0;

        /**
         * The state of a computation a thread is running.
         */
        private static final int RUNNING = 1;

        /**
         * The state of a finished computation.
         */
        private static final int DONE = 2;

        /**
         * The state of a computation canceled before any thread started it.
         */
        private static final int CANCELED = 3;

        /**
         * The handle of {@link #state}.
         */
        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(Pending.class, "state", int.class);
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        /**
         * The thread that started the computation, which may run it while waiting for other work.
         */
        private final Thread owner;

        /**
         * The sequence number of the computation among all posted work.
         */
        private final long sequence;

        /**
         * The computation, released once claimed so that a finished handle does not keep its
         * inputs reachable.
         */
        private Callable<? extends T> task;

        /**
         * Whether the handle was posted on the board.
         */
        private boolean posted;

        /**
         * The state: {@link #QUEUED}, {@link #RUNNING}, {@link #DONE} or {@link #CANCELED}.
         */
        private volatile int state;

        /**
         * The thread waiting for the computation, or {@code null} while none is.
         */
        private volatile Thread waiter;

        /**
         * The result, once computed.
         */
        private T result;

        /**
         * The failure of the computation, or {@code null}.
         */
        private Throwable failure;

        /**
         * Creates the handle of a computation started by the calling thread.
         *
         * @param task the computation
         */
        private Pending(Callable<? extends T> task) {
            this.task = task;
            this.owner = Thread.currentThread();
            this.sequence = SEQUENCE.incrementAndGet();
        }

        @Override
        public int unclaimed() {
            return 1;
        }

        @Override
        public boolean runOne() {
            if (state != QUEUED || !STATE.compareAndSet(this, QUEUED, RUNNING)) {
                return false;
            }
            var body = task;
            task = null;
            try {
                result = body.call();
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                state = DONE;
                var thread = waiter;
                if (thread != null && thread != Thread.currentThread()) {
                    LockSupport.unpark(thread);
                }
            }
            return true;
        }

        /**
         * Waits for the computation, running it on the calling thread if no helper has started it,
         * and returns its result.
         *
         * @return the result
         * @throws RuntimeException the unchecked exception the computation threw
         * @throws Error the error the computation threw
         * @throws IllegalStateException wrapping the checked exception the computation threw, or
         * if the computation was canceled
         */
        public T join() {
            checkpoint();
            if (runOne() && posted) {
                BOARD.remove(this);
            }
            awaitDone();
            if (failure != null) {
                rethrow(failure);
            }
            if (state == CANCELED) {
                throw new IllegalStateException("codec task canceled");
            }
            return result;
        }

        /**
         * Returns whether a thread has started the computation, or it finished or was canceled.
         *
         * <p>The value is a snapshot: a computation reported as not started may start at once.
         *
         * @return {@code false} while the computation waits for a thread to claim it
         */
        public boolean started() {
            return state != QUEUED;
        }

        /**
         * Prevents the computation from running if no thread has started it, without waiting.
         *
         * @return {@code true} if the computation will never run, {@code false} if a thread
         * started it (it may still be running) or it already finished
         */
        public boolean tryCancel() {
            if (state == CANCELED) {
                return true;
            }
            if (!STATE.compareAndSet(this, QUEUED, CANCELED)) {
                return false;
            }
            task = null;
            if (posted) {
                BOARD.remove(this);
            }
            return true;
        }

        /**
         * Prevents the computation from running if no thread has started it, and otherwise waits
         * for it to finish, ignoring its failure.
         */
        public void cancel() {
            if (STATE.compareAndSet(this, QUEUED, CANCELED)) {
                task = null;
                if (posted) {
                    BOARD.remove(this);
                }
                return;
            }
            awaitDone();
        }

        /**
         * Waits until the computation has finished or been canceled, helping with other work
         * meanwhile.
         *
         * <p>The waiting thread registers itself before it checks the state, and a finishing
         * computation sets the state before it looks for a waiter, so either the waiter sees the
         * computation finished or the computation wakes the waiter.
         */
        private void awaitDone() {
            if (state >= DONE) {
                return;
            }
            waiter = Thread.currentThread();
            var since = System.nanoTime();
            var interrupted = false;
            while (state < DONE) {
                pause(this, since, sequence, false);
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            waiter = null;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
