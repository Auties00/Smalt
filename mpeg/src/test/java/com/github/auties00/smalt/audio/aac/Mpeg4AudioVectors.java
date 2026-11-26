package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.Assertions;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ISO/IEC 14496-26 conformance streams ({@code .mp4}) and reference waveforms ({@code .wav}) of the hosted test
 * data: a vector {@code mpeg4audio-<name>} of {@code tools/testdata/vectors.tsv} when the manifest lists one, else the
 * member of that name of the 2nd edition archive sets, the smoke set first.
 */
public final class Mpeg4AudioVectors {
    private static final List<String> STREAMS = List.of("mpeg4audio-ed2-streams-smoke", "mpeg4audio-ed2-streams");

    private static final List<String> REFERENCES = List.of("mpeg4audio-ed2-references-smoke",
            "mpeg4audio-ed2-references");

    /** The members of each opened set, by file name. */
    private static final Map<String, Map<String, String>> MEMBERS = new ConcurrentHashMap<>();

    private Mpeg4AudioVectors() {
    }

    /**
     * Returns a stream or reference waveform.
     *
     * @param name the file name, for example {@code al00_48.mp4} or {@code al00_48.wav}
     * @return the file
     */
    public static Path file(String name) {
        var vector = "mpeg4audio-" + name;
        if (Conformance.listed(vector)) {
            return Conformance.vector(vector);
        }
        var sets = name.endsWith(".wav") ? REFERENCES : STREAMS;
        for (var set : sets) {
            var member = MEMBERS.computeIfAbsent(set, Mpeg4AudioVectors::index).get(name);
            if (member != null) {
                return Conformance.archive(set).member(member);
            }
        }
        return Assertions.fail(name + " is neither the vector " + vector + " nor a member of " + sets);
    }

    /**
     * Returns whether a stream or reference waveform is hosted, as a vector or as a member of the archive sets.
     *
     * @param name the file name
     * @return whether {@link #file(String)} finds it
     */
    public static boolean exists(String name) {
        if (Conformance.listed("mpeg4audio-" + name)) {
            return true;
        }
        for (var set : name.endsWith(".wav") ? REFERENCES : STREAMS) {
            if (MEMBERS.computeIfAbsent(set, Mpeg4AudioVectors::index).containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> index(String set) {
        var result = new HashMap<String, String>();
        for (var member : Conformance.archive(set).members()) {
            result.put(member.substring(member.lastIndexOf('/') + 1), member);
        }
        return result;
    }
}
