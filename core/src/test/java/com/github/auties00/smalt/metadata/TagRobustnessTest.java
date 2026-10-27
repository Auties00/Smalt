package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Feeds mutated tagged files of every supported container to {@link TagEditor}: every read and
 * every write must either succeed or raise a {@link MediaException}, in bounded time.
 */
@DisplayName("TagEditor robustness")
class TagRobustnessTest {
    static Stream<Arguments> files() {
        return Stream.of(
                Arguments.of("mp3 v2.4", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "32k"))),
                Arguments.of("mp3 v2.3", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "32k", "-id3v2_version",
                        "3", "-write_id3v1", "1"))),
                Arguments.of("flac", TagFixtures.audio("flac", List.of("-c:a", "flac"))),
                Arguments.of("ogg vorbis", TagFixtures.audio("ogg", List.of("-c:a", "libvorbis"))),
                Arguments.of("opus", TagFixtures.audio("opus", List.of("-c:a", "libopus"))),
                Arguments.of("m4a", TagFixtures.audio("m4a", List.of("-c:a", "aac"))),
                Arguments.of("mka", TagFixtures.audio("mka", List.of("-c:a", "libopus"))),
                Arguments.of("wav", TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le", "-write_id3v2", "1"))),
                Arguments.of("aiff", TagFixtures.audio("aiff", List.of("-c:a", "pcm_s16be", "-write_id3v2", "1"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("mutated metadata raises nothing but MediaException")
    void mutations(String name, Path fixture) {
        var tagged = TagEditor.write(Fixtures.read(fixture), TagFixtures.sample(), TagWriteOptions.defaults());
        var random = new Random(name.hashCode());
        var limits = MediaLimits.builder().maxBytes(16 << 20).build();
        var options = TagWriteOptions.builder().limits(limits).build();
        var metadataEnd = Math.min(tagged.length, 64 * 1024);
        for (var round = 0; round < 150; round++) {
            var mutated = tagged.clone();
            var region = random.nextInt(4) == 0 ? mutated.length : metadataEnd;
            for (var flips = 0; flips < 1 + random.nextInt(10); flips++) {
                var at = random.nextInt(region);
                mutated[at] = random.nextInt(3) == 0 ? (byte) 0xFF : (byte) random.nextInt(256);
            }
            if (random.nextInt(5) == 0) {
                mutated = Arrays.copyOf(mutated, 1 + random.nextInt(mutated.length - 1));
            }
            var input = mutated;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                attempt(() -> TagEditor.read(MediaSource.of(input), TagReadOptions.builder().limits(limits).build()));
                attempt(() -> TagEditor.write(input, TagFixtures.sample(), options));
                attempt(() -> TagEditor.write(input, MediaTags.empty(), options));
            });
        }
    }

    static Stream<Arguments> phoneFiles() {
        var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Robust").with(XmpPacket.EXIF, "GPSLatitude", "1,2N");
        var withXmp = TagFixtures.sample().toBuilder().xmp(xmp).build();
        return Stream.of(
                Arguments.of("iPhone keyed metadata", Fixtures.read(PhoneMetadataTest.PHONE)),
                Arguments.of("3GPP location and XMP uuid", Fixtures.read(PhoneMetadataTest.ANDROID)),
                Arguments.of("wav xmp", TagEditor.write(Fixtures.read(TagFixtures.audio("wav", List.of("-c:a",
                        "pcm_s16le"))), withXmp, TagWriteOptions.defaults())),
                Arguments.of("aiff xmp", TagEditor.write(Fixtures.read(TagFixtures.audio("aiff", List.of("-c:a",
                        "pcm_s16be"))), withXmp, TagWriteOptions.defaults())),
                Arguments.of("mp3 xmp", TagEditor.write(Fixtures.read(TagFixtures.audio("mp3", List.of("-c:a",
                        "libmp3lame", "-b:a", "32k"))), withXmp, TagWriteOptions.defaults())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("phoneFiles")
    @DisplayName("mutated keyed metadata, locations and XMP packets raise nothing but MediaException")
    void phoneMutations(String name, byte[] tagged) {
        var random = new Random(name.hashCode());
        var limits = MediaLimits.builder().maxBytes(16 << 20).build();
        var options = TagWriteOptions.builder().limits(limits).build();
        var start = Math.max(0, tagged.length - 4096);
        for (var round = 0; round < 150; round++) {
            var mutated = tagged.clone();
            for (var flips = 0; flips < 1 + random.nextInt(10); flips++) {
                // the movie box of the MP4 samples and the XMP chunks of the audio files end the files
                var at = random.nextBoolean() ? start + random.nextInt(tagged.length - start) : random.nextInt(4096);
                mutated[at] = random.nextInt(3) == 0 ? (byte) 0xFF : (byte) random.nextInt(256);
            }
            if (random.nextInt(5) == 0) {
                mutated = Arrays.copyOf(mutated, 1 + random.nextInt(mutated.length - 1));
            }
            var input = mutated;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                attempt(() -> {
                    var read = TagEditor.read(MediaSource.of(input), TagReadOptions.builder().limits(limits).build());
                    read.hasLocation();
                    TagEditor.write(input, read.toBuilder().removeLocation().set("com.apple.quicktime.make", "x")
                            .build(), options);
                });
                attempt(() -> TagEditor.stripLocation(input, options));
                attempt(() -> TagEditor.write(input, MediaTags.empty(), options));
            });
        }
    }

    /** Runs an operation that may reject its input, failing on any other exception or on a guarded runtime failure. */
    static void attempt(Runnable operation) {
        try {
            operation.run();
        } catch (MediaException expected) {
            if (expected.getCause() instanceof RuntimeException cause && !(cause instanceof MediaException)) {
                throw new AssertionError("an unexpected runtime exception reached the entry point", cause);
            }
        }
    }
}
