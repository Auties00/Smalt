package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.audio.AudioDevices;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;

import java.util.Locale;

/**
 * Taps an output past the permission preflight in a process of its own while playing to it: the
 * system answers a process's first tap from the permission database, and a process that has tapped
 * once keeps its first answer. Prints what the reads saw.
 */
public final class TapProbe {
    private TapProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        var part = args[0].toLowerCase(Locale.ROOT);
        var device = AudioDevices.outputs().stream().filter(output -> output.name().toLowerCase(Locale.ROOT).contains(part)).findFirst().orElseThrow();
        System.out.println("PROBE preflight " + CoreAudio.tapPermission());
        var player = AudioDevices.openPlayback(device);
        var writer = Thread.ofPlatform().daemon().start(() -> {
            var frames = player.periodFrames();
            var channels = player.channels();
            var samples = new float[frames * channels];
            var position = 0L;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    for (var i = 0; i < frames; i++) {
                        var value = (float) (0.5 * Math.sin(2 * Math.PI * 1_000 * (position + i) / player.sampleRate()));
                        for (var c = 0; c < channels; c++) {
                            samples[i * channels + c] = value;
                        }
                    }
                    if (player.write(samples, 0, frames) < frames) {
                        return;
                    }
                    position += frames;
                }
            } catch (InterruptedException exception) {
                // stopped
            }
        });
        var tap = CoreAudioTap.open(device, AudioStreamOptions.forVoice());
        var captured = new float[8_000];
        var peak = new float[]{-1};
        var reader = Thread.ofPlatform().daemon().start(() -> {
            try {
                tap.read(captured, 0, 8_000);
                var loudest = 0f;
                for (var sample : captured) {
                    loudest = Math.max(loudest, Math.abs(sample));
                }
                peak[0] = loudest;
            } catch (InterruptedException exception) {
                // stopped
            }
        });
        reader.join(10_000);
        System.out.println("PROBE read " + (reader.isAlive() ? "blocked after 10 s" : "returned, peak " + peak[0]));
        var start = System.nanoTime();
        tap.close();
        System.out.println("PROBE close took " + (System.nanoTime() - start) / 1_000_000 + " ms");
        reader.join(5_000);
        System.out.println("PROBE read ended by close: " + !reader.isAlive());
        writer.interrupt();
        player.close();
        System.exit(0);
    }
}
