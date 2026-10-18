package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the ring-backed sessions with a fake engine standing in for a device thread: timestamps
 * derived from delivery anchors, sample conversions, overrun and underrun counting, delay
 * reporting, and the close and failure contracts.
 */
@Timeout(10)
@DisplayName("ring-backed sessions")
class RingSessionTest {
    private static final AudioDevice.Input INPUT = new AudioDevice.Input("in", "Fake microphone", true, List.of());

    private static final AudioDevice.Output OUTPUT = new AudioDevice.Output("out", "Fake speaker", true, List.of());

    private static StreamPlan voicePlan() {
        return StreamPlan.resolve(AudioStreamOptions.forVoice(), 48_000, 2);
    }

    @Test
    @DisplayName("resolves options against the device's format with a two-period queue floor")
    void plan() {
        var voice = voicePlan();
        assertEquals(16_000, voice.sampleRate());
        assertEquals(1, voice.channels());
        assertEquals(160, voice.periodFrames());
        assertEquals(960, voice.bufferFrames());
        assertEquals(20_000_000L, voice.latencyNanos());
        var native_ = StreamPlan.resolve(AudioStreamOptions.defaults().toBuilder().bufferDuration(Duration.ofMillis(1)).build(), 44_100, 2);
        assertEquals(44_100, native_.sampleRate());
        assertEquals(441, native_.periodFrames());
        assertEquals(882, native_.bufferFrames());
        assertEquals(10_000_000L, native_.nanos(441));
    }

    @Nested
    @DisplayName("capture")
    class Capture {
        @Test
        @DisplayName("stamps reads from the device's anchors, not from the reader's clock")
        void timestamps() throws InterruptedException {
            var session = new RingCaptureSession(INPUT, voicePlan());
            session.deliver(new float[480], 480, System.nanoTime());
            var first = session.read();
            assertNotNull(first);
            assertEquals(160, first.frames());
            var firstStamp = session.timestampMicros();
            assertNotNull(session.read());
            assertEquals(10_000, session.timestampMicros() - firstStamp, "160 frames at 16 kHz are 10 ms");
            Thread.sleep(30);
            assertTrue(session.delayMicros() >= 20_000, "captured 10 ms after the delivery anchor, 30 ms ago at least: " + session.delayMicros());
            assertEquals(160, session.available());
        }

        @Test
        @DisplayName("converts to 16-bit with rounding and clamping")
        void shorts() throws InterruptedException {
            var session = new RingCaptureSession(INPUT, voicePlan());
            session.deliver(new float[]{0.5f, -1.0f, 1.0f, 2.0f, 1.0f / 65536}, 5, System.nanoTime());
            var out = new short[5];
            assertEquals(5, session.read(out, 0, 5));
            assertEquals(List.of((short) 16384, (short) -32768, (short) 32767, (short) 32767, (short) 1),
                    List.of(out[0], out[1], out[2], out[3], out[4]));
        }

        @Test
        @DisplayName("counts the frames dropped by a slow reader")
        void overruns() {
            var session = new RingCaptureSession(INPUT, voicePlan());
            for (var i = 0; i < 10; i++) {
                session.deliver(new float[160], 160, System.nanoTime());
            }
            assertEquals(960, session.available());
            assertEquals(640, session.overruns());
        }

        @Test
        @DisplayName("deinterleaves into the planes of the returned buffer")
        void planes() throws InterruptedException {
            var plan = StreamPlan.resolve(AudioStreamOptions.defaults().toBuilder().period(Duration.ofMillis(1)).build(), 8_000, 2);
            var session = new RingCaptureSession(INPUT, plan);
            var samples = new float[16];
            for (var i = 0; i < 8; i++) {
                samples[2 * i] = i;
                samples[2 * i + 1] = -i;
            }
            session.deliver(samples, 8, System.nanoTime());
            var buffer = session.read();
            assertEquals(8, buffer.frames());
            assertEquals(ChannelLayout.STEREO, buffer.layout());
            assertEquals(7.0f, buffer.plane(0)[7]);
            assertEquals(-7.0f, buffer.plane(1)[7]);
        }

        @Test
        @DisplayName("stops the engine on close and reports the end afterwards")
        void close() throws InterruptedException {
            var session = new RingCaptureSession(INPUT, voicePlan());
            var stops = new AtomicInteger();
            session.attach(stops::incrementAndGet, 22_000_000L);
            assertEquals(22_000, session.latencyMicros());
            session.close();
            session.close();
            assertEquals(1, stops.get());
            assertTrue(session.stopped());
            assertNull(session.read());
            assertEquals(-1, session.read(new float[1], 0, 1));
        }

        @Test
        @DisplayName("stops an engine attached after the close at once")
        void lateAttach() {
            var session = new RingCaptureSession(INPUT, voicePlan());
            session.close();
            var stops = new AtomicInteger();
            session.attach(stops::incrementAndGet, 0);
            assertEquals(1, stops.get());
        }

        @Test
        @DisplayName("throws the device failure once after the queued audio")
        void failure() throws InterruptedException {
            var session = new RingCaptureSession(INPUT, voicePlan());
            session.deliver(new float[100], 100, System.nanoTime());
            session.fail(new MediaException.Io("unplugged", new IOException("unplugged")));
            assertEquals(100, session.read().frames());
            assertThrows(MediaException.Io.class, session::read);
            assertNull(session.read());
        }

        @Test
        @DisplayName("checks array bounds")
        void bounds() {
            var session = new RingCaptureSession(INPUT, voicePlan());
            assertThrows(IndexOutOfBoundsException.class, () -> session.read(new float[10], 5, 6));
            assertThrows(IndexOutOfBoundsException.class, () -> session.read(new short[10], 0, -1));
        }
    }

    @Nested
    @DisplayName("playback")
    class Playback {
        @Test
        @DisplayName("renders queued audio, then silence counted as underruns once writing started")
        void render() throws InterruptedException {
            var session = new RingPlaybackSession(OUTPUT, voicePlan());
            var out = new float[160];
            assertEquals(0, session.render(out, 160));
            assertEquals(0, session.underruns(), "silence before the first write is not an underrun");
            var samples = new short[100];
            java.util.Arrays.fill(samples, (short) 16384);
            assertEquals(100, session.write(samples, 0, 100));
            assertEquals(100, session.queued());
            assertEquals(860, session.available());
            assertEquals(100, session.render(out, 160));
            assertEquals(0.5f, out[99]);
            assertEquals(0.0f, out[100]);
            assertEquals(60, session.underruns());
        }

        @Test
        @DisplayName("reports the queue plus the aging device delay")
        void delay() throws InterruptedException {
            var session = new RingPlaybackSession(OUTPUT, voicePlan());
            session.attach(() -> {
            }, 30_000_000L);
            assertEquals(30_000, session.delayMicros(), "before any report, the granted latency");
            session.reportDeviceDelay(25_000_000L);
            session.write(new float[160], 0, 160);
            var delay = session.delayMicros();
            assertTrue(delay <= 35_000 && delay > 30_000, "10 ms queued plus at most 25 ms of device: " + delay);
            Thread.sleep(30);
            assertEquals(10_000, session.delayMicros(), "the device report ages to zero");
        }

        @Test
        @DisplayName("accepts buffers of its format only")
        void buffers() throws InterruptedException {
            var session = new RingPlaybackSession(OUTPUT, voicePlan());
            var buffer = new AudioBuffer(ChannelLayout.MONO, 16_000, 80);
            buffer.setFrames(80);
            buffer.plane(0)[0] = 0.25f;
            assertEquals(80, session.write(buffer));
            var out = new float[80];
            session.render(out, 80);
            assertEquals(0.25f, out[0]);
            assertThrows(IllegalArgumentException.class, () -> session.write(new AudioBuffer(ChannelLayout.MONO, 48_000, 10)));
            assertThrows(IllegalArgumentException.class, () -> session.write(new AudioBuffer(ChannelLayout.STEREO, 16_000, 10)));
        }

        @Test
        @DisplayName("drains once the engine took the queue and the device delay elapsed")
        void drain() throws InterruptedException {
            var session = new RingPlaybackSession(OUTPUT, voicePlan());
            session.write(new float[320], 0, 320);
            var engine = Thread.ofPlatform().start(() -> {
                var out = new float[160];
                for (var i = 0; i < 2; i++) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException exception) {
                        return;
                    }
                    session.render(out, 160);
                    session.reportDeviceDelay(20_000_000L);
                }
            });
            var start = System.nanoTime();
            session.drain();
            var elapsed = (System.nanoTime() - start) / 1_000_000;
            assertEquals(0, session.queued());
            assertTrue(elapsed >= 25, "waited for the device delay too: " + elapsed + " ms");
            engine.join();
        }

        @Test
        @DisplayName("throws the device failure once, then discards writes")
        void failure() throws InterruptedException {
            var session = new RingPlaybackSession(OUTPUT, voicePlan());
            session.fail(new MediaException.Io("unplugged", new IOException("unplugged")));
            assertThrows(MediaException.Io.class, () -> session.write(new float[10], 0, 10));
            assertEquals(0, session.write(new float[10], 0, 10));
            assertTrue(session.stopped());
        }
    }
}
