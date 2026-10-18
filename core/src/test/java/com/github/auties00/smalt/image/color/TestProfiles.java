package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches the pinned test profiles of {@code lcms/profiles.tsv} and builds the deterministic input
 * sets of {@code tools/testdata/lcms-oracle.py}.
 */
final class TestProfiles {
    private static final Map<String, String[]> PROFILES = load();

    private TestProfiles() {
    }

    static List<String[]> table(String resource) {
        try (InputStream in = TestProfiles.class.getResourceAsStream("lcms/" + resource)) {
            var rows = new ArrayList<String[]>();
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    rows.add(line.split("\t"));
                }
            }
            return rows;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, String[]> load() {
        var map = new LinkedHashMap<String, String[]>();
        for (var row : table("profiles.tsv")) {
            map.put(row[0], row);
        }
        return map;
    }

    static List<String> names() {
        return List.copyOf(PROFILES.keySet());
    }

    static byte[] bytes(String name) {
        var row = PROFILES.get(name);
        try {
            return Files.readAllBytes(Conformance.fetch(name, row[2], row[1]));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static ColorProfile profile(String name) {
        return ColorProfile.parse(bytes(name));
    }

    static byte[] resource(String name) {
        try (InputStream in = TestProfiles.class.getResourceAsStream("lcms/" + name)) {
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] noise(int count) {
        var out = new byte[count];
        var state = 0x9E3779B97F4A7C15L;
        for (var i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            out[i] = (byte) (state >>> 56);
        }
        return out;
    }

    static byte[] inputs(ProfileColorSpace space) {
        if (space == ProfileColorSpace.GRAY) {
            var out = new byte[256];
            for (var i = 0; i < 256; i++) {
                out[i] = (byte) i;
            }
            return out;
        }
        var channels = space == ProfileColorSpace.RGB ? 3 : 4;
        var levels = space == ProfileColorSpace.RGB ? 17 : 9;
        var values = new int[levels];
        for (var i = 0; i < levels; i++) {
            values[i] = (i * 255 + (levels - 1) / 2) / (levels - 1);
        }
        var total = 1;
        for (var c = 0; c < channels; c++) {
            total *= levels;
        }
        var noise = noise(2048 * channels);
        var out = new byte[total * channels + noise.length];
        for (var index = 0; index < total; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                out[index * channels + c] = (byte) values[rest % levels];
                rest /= levels;
            }
        }
        System.arraycopy(noise, 0, out, total * channels, noise.length);
        return out;
    }
}
