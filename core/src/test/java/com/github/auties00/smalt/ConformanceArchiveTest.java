package com.github.auties00.smalt;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the test kit's reader of the hosted test data: archive sets and vectors are read from the local copy,
 * and an unlisted or absent name fails the test instead of skipping it.
 */
@DisplayName("hosted test data")
class ConformanceArchiveTest {
    /** A small archive set of {@code tools/testdata/archives.tsv}. */
    private static final String SET = "dash-livesim-testpic_2s-S1";

    @Test
    @DisplayName("lists the members of an archive set and reads each one from the hosted copy")
    void members() throws Exception {
        var archive = Conformance.archive(SET);
        assertEquals(SET, archive.name());
        assertSame(archive, Conformance.archive(SET), "a set is opened once per JVM");
        var members = archive.members();
        assertFalse(members.isEmpty());
        for (var member : members) {
            var file = archive.member(member);
            assertTrue(Files.isRegularFile(file), member);
            assertTrue(file.startsWith(Conformance.directory()), member + " is read from the hosted copy");
        }
    }

    @Test
    @DisplayName("rejects a member outside the set")
    void outside() {
        var archive = Conformance.archive(SET);
        assertThrows(IllegalArgumentException.class, () -> archive.member("absent/member.mp4"));
    }

    @Test
    @DisplayName("fails on an archive set or a vector the manifests do not list")
    void unlisted() {
        assertTrue(assertThrows(AssertionFailedError.class, () -> Conformance.archive("no-such-set"))
                .getMessage().contains("not listed"));
        assertTrue(assertThrows(AssertionFailedError.class, () -> Conformance.vector("no-such-vector.bin"))
                .getMessage().contains("not listed"));
        assertFalse(Conformance.listed("no-such-vector.bin"));
    }
}
