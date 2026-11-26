package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.testkit.Conformance;

import java.nio.file.Path;
import java.util.List;

/**
 * The IETF CELLAR FLAC decoder testbench (github.com/ietf-wg-cellar/flac-test-files, CC0), commit
 * aa7b0c6cf32994c106ae517a08134c28a96ff5b2: the {@code flac-cellar-<group>-<number>.flac} vectors of the hosted test
 * data.
 */
public final class FlacConformanceCorpus {
    /** The prefix of the corpus's vector names. */
    private static final String PREFIX = "flac-cellar-";

    private FlacConformanceCorpus() {
        throw new AssertionError();
    }

    /** One corpus file. */
    public record Vector(String group, String number) {
        /** Returns the hosted copy. */
        public Path file() {
            return Conformance.vector(PREFIX + group + "-" + number + ".flac");
        }

        @Override
        public String toString() {
            return group + "/" + number;
        }
    }

    /** Returns every corpus file, by group and number. */
    public static List<Vector> all() {
        return Conformance.vectors(PREFIX).stream().map(name -> {
            var stem = name.substring(PREFIX.length(), name.length() - ".flac".length());
            var separator = stem.lastIndexOf('-');
            return new Vector(stem.substring(0, separator), stem.substring(separator + 1));
        }).toList();
    }

    /** Returns the hosted copy of one corpus file. */
    public static Path vector(String group, String number) {
        return new Vector(group, number).file();
    }
}
