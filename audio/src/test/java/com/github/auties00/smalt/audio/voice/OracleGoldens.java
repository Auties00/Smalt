package com.github.auties00.smalt.audio.voice;

/**
 * Prints {@code oracle-goldens.txt}: the SHA-256 of the WebRTC APM oracle's output for every case
 * of {@link VoiceProcessorOracleTest}. Run it after changing the oracle or the scenarios and replace
 * the resource with its output.
 */
public final class OracleGoldens {
    private OracleGoldens() {
    }

    public static void main(String[] args) {
        System.out.println("# SHA-256 of the WebRTC APM oracle output (float32 little-endian) per configuration and scenario.");
        System.out.println("# Regenerate with OracleGoldens.");
        for (var c : VoiceProcessorOracleTest.cases()) {
            var scenario = VoiceProcessorOracleTest.scenarioOf(c.scenario());
            var result = VoiceProcessorOracleTest.run(c.options(), scenario);
            var key = VoiceProcessorOracleTest.key(c.options(), scenario);
            if (!result.javaDigest().equals(result.oracleDigest()) || result.levelMismatches() != 0) {
                System.err.println("Java output differs from the oracle for " + key);
            }
            System.out.println(key + " " + result.oracleDigest());
        }
    }
}
