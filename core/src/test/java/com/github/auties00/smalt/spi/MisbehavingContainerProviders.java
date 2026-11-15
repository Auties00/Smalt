package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

/**
 * Container providers that break or test the edges of the provider contract, registered next to
 * {@link ToyContainerProvider} by {@link ContainerProviderTest}.
 */
public final class MisbehavingContainerProviders {
    private MisbehavingContainerProviders() {
    }

    /** Throws from every recognition attempt. */
    public static final class Throwing implements ContainerProvider {
        public Throwing() {
        }

        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public boolean matches(byte[] header, long offset) {
            throw new IllegalStateException("broken provider");
        }
    }

    /**
     * Claims every RIFF file, and every file that starts with {@code "CLAIM"} when handed the largest probe, and asks
     * for more leading bytes than recognition reads; core's RIFF formats must stay core's.
     */
    public static final class RiffClaiming implements ContainerProvider {
        public RiffClaiming() {
        }

        @Override
        public String name() {
            return "riff-claimer";
        }

        @Override
        public int probeLength() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean matches(byte[] header, long offset) {
            if (header.length > MAX_PROBE_LENGTH) {
                throw new AssertionError("handed " + header.length + " bytes");
            }
            return header.length >= 4 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    || header.length >= 8 && header[0] == 'C' && header[1] == 'L' && header[2] == 'A'
                    && header[3] == 'I' && header[4] == 'M' && offset == 0 && header.length == MAX_PROBE_LENGTH;
        }
    }

    /** Names its container after a {@link ContainerFormat} constant it does not provide. */
    public static final class StealsName implements ContainerProvider {
        public StealsName() {
        }

        @Override
        public String name() {
            return "wav";
        }
    }

    /** Provides a named format under another name. */
    public static final class WrongName implements ContainerProvider {
        public WrongName() {
        }

        @Override
        public String name() {
            return "mxf";
        }

        @Override
        public ContainerFormat format() {
            return ContainerFormat.MATROSKA;
        }
    }

    /** Has a name that is not lowercase. */
    public static final class Uppercase implements ContainerProvider {
        public Uppercase() {
        }

        @Override
        public String name() {
            return "Toy";
        }
    }

    /** Reports no name. */
    public static final class NullName implements ContainerProvider {
        public NullName() {
        }

        @Override
        public String name() {
            return null;
        }
    }

    /** Reports no format. */
    public static final class NullFormat implements ContainerProvider {
        public NullFormat() {
        }

        @Override
        public String name() {
            return "null-format";
        }

        @Override
        public ContainerFormat format() {
            return null;
        }
    }

    /** Fails to report its name. */
    public static final class FailingName implements ContainerProvider {
        public FailingName() {
        }

        @Override
        public String name() {
            throw new IllegalStateException("broken name");
        }
    }

    /** Cannot be instantiated. */
    public static final class FailingConstructor implements ContainerProvider {
        public FailingConstructor() {
            throw new IllegalStateException("broken constructor");
        }

        @Override
        public String name() {
            return "failing-constructor";
        }
    }

    /** Recognizes {@code "OVRC"} files, which hold no tags, and plans tag edits that copy past their end. */
    public static final class OverCopying implements ContainerProvider {
        public OverCopying() {
        }

        @Override
        public String name() {
            return "overcopy";
        }

        @Override
        public int probeLength() {
            return 4;
        }

        @Override
        public boolean matches(byte[] header, long offset) {
            return header.length >= 4 && header[0] == 'O' && header[1] == 'V' && header[2] == 'R' && header[3] == 'C';
        }

        @Override
        public boolean editsTags() {
            return true;
        }

        @Override
        public MediaTags readTags(MediaSource source, MediaLimits limits) {
            return MediaTags.empty();
        }

        @Override
        public TagRewrite planTags(MediaSource source, MediaTags tags, TagWriteOptions options) {
            return TagRewrite.builder().copy(0, source.size() + 1).build();
        }
    }
}
