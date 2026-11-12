package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens a process tap while nobody answers the System Audio Recording request, the state of a hosted
 * runner whose permission database holds no decision. It needs that state from the JVM's first tap and
 * leaves CoreAudio busy for minutes, so CI runs it alone after the suite, with
 * {@code SMALT_TEST_TAP_UNANSWERED=true}.
 */
@Timeout(300)
@DisplayName("CoreAudio process tap without an answer (live)")
class CoreAudioTapUnansweredLiveTest {
    @BeforeAll
    static void requireOptIn() {
        Skip.unless("true".equals(System.getenv("SMALT_TEST_TAP_UNANSWERED")), "opt-in:capture-tap-unanswered",
                "set SMALT_TEST_TAP_UNANSWERED=true in a process whose permission was never answered");
    }

    private static Thread play(AudioPlaybackSession player) {
        return Thread.ofPlatform().daemon().start(() -> {
            var samples = new float[player.periodFrames() * player.channels()];
            try {
                while (player.write(samples, 0, player.periodFrames()) == player.periodFrames()) {
                    Thread.onSpinWait();
                }
            } catch (InterruptedException exception) {
                // stopped
            }
        });
    }

    @Test
    @DisplayName("opening ends by itself, reads wait for the answer, and closing the session ends them without waiting")
    void closeBounded() throws InterruptedException {
        var part = Skip.setting("smalt.test.audioLoopback", "SMALT_TEST_AUDIO_LOOPBACK").split("\\|", -1)[0].toLowerCase(Locale.ROOT);
        AudioDevice.Output device = AudioDevices.outputs().stream().filter(output -> output.name().toLowerCase(Locale.ROOT).contains(part)).findFirst().orElseThrow();
        var permission = CoreAudio.tapPermission();
        assertTrue(permission != 0 && permission != CoreAudio.TAP_PERMISSION_DENIED, "permission " + permission);
        var player = AudioDevices.openPlayback(device, AudioStreamOptions.builder().bufferDuration(Duration.ofSeconds(1)).build());
        var writer = play(player);
        var opening = System.nanoTime();
        var tap = AudioDevices.openLoopback(device, AudioStreamOptions.forVoice());
        System.out.println("open of an unanswered tap took " + (System.nanoTime() - opening) / 1_000_000 + " ms");
        var reader = Thread.ofPlatform().daemon().start(() -> {
            var frames = new float[160];
            try {
                while (tap.read(frames, 0, 160) == 160) {
                    Thread.onSpinWait();
                }
            } catch (InterruptedException exception) {
                // stopped
            }
        });
        reader.join(5_000);
        assertTrue(reader.isAlive(), "the read waits for the answer");
        var start = System.nanoTime();
        tap.close();
        var millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("close of an unanswered tap took " + millis + " ms");
        assertTrue(millis < 10_000, "close took " + millis + " ms");
        reader.join(5_000);
        assertFalse(reader.isAlive(), "close ends the read");
        var closer = Thread.ofPlatform().daemon().start(() -> {
            writer.interrupt();
            var started = System.nanoTime();
            player.close();
            System.out.println("close of the player beside it took " + (System.nanoTime() - started) / 1_000_000 + " ms");
        });
        closer.join(120_000);
        System.out.println("the player's close " + (closer.isAlive() ? "still blocks after 120 s" : "returned"));
    }
}
