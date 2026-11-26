package com.github.auties00.smalt.container;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.Assertions;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ISO/IEC 14496-26 2nd edition conformance streams ({@code compressedMp4/*.mp4}) of the hosted test data, found
 * by file name among the members of the archive sets, the smoke set first.
 */
public final class Mpeg4AudioStreams {
    private static final List<String> SETS = List.of("mpeg4audio-ed2-streams-smoke", "mpeg4audio-ed2-streams");

    /** The members of each opened set, by file name. */
    private static final Map<String, Map<String, String>> MEMBERS = new ConcurrentHashMap<>();

    private Mpeg4AudioStreams() {
    }

    /**
     * Returns a stream.
     *
     * @param name the file name, for example {@code al_sbr_i_44_1.mp4}
     * @return the file
     */
    public static Path file(String name) {
        for (var set : SETS) {
            var member = MEMBERS.computeIfAbsent(set, Mpeg4AudioStreams::index).get(name);
            if (member != null) {
                return Conformance.archive(set).member(member);
            }
        }
        return Assertions.fail(name + " is not a member of " + SETS);
    }

    private static Map<String, String> index(String set) {
        var result = new HashMap<String, String>();
        for (var member : Conformance.archive(set).members()) {
            result.put(member.substring(member.lastIndexOf('/') + 1), member);
        }
        return result;
    }
}
