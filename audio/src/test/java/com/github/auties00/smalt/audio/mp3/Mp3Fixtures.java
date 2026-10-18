package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.testkit.Fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds MPEG audio fixtures with ffmpeg from a deterministic test signal: a tone per channel
 * plus gated bursts (which force Layer III short blocks) and a little seeded noise (which
 * exercises every Huffman table), different on each channel so stereo tools have work to do.
 */
final class Mp3Fixtures {
    private Mp3Fixtures() {
        throw new AssertionError();
    }

    static List<String> signal(int rate, int channels, double seconds) {
        var left = "0.35*sin(2*PI*330*t)+0.3*sin(2*PI*2200*t)*gt(mod(t\\,0.37)\\,0.3)+0.08*(random(0)-0.5)";
        var right = "0.3*sin(2*PI*495*t+1)+0.35*gt(mod(t\\,0.29)\\,0.25)*sin(2*PI*880*t)+0.08*(random(1)-0.5)";
        var exprs = channels == 1 ? left : left + "|" + right;
        return List.of("-f", "lavfi", "-i", "aevalsrc=exprs=" + exprs + ":s=" + rate + ":d=" + seconds);
    }

    static Path encode(String extension, int rate, int channels, double seconds, List<String> codec) {
        var output = new ArrayList<String>(List.of("-ar", Integer.toString(rate), "-ac", Integer.toString(channels)));
        output.addAll(codec);
        return Fixtures.ffmpeg(extension, signal(rate, channels, seconds), output);
    }

    static Path lame(int rate, int channels, double seconds, String... options) {
        var codec = new ArrayList<String>(List.of("-c:a", "libmp3lame"));
        codec.addAll(List.of(options));
        return encode("mp3", rate, channels, seconds, codec);
    }
}
