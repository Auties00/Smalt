package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * A provider of a format core names and implements: it edits the tags of YUV4MPEG2 files, which core recognizes,
 * reads and writes but does not tag, and implements nothing else. Each tag is a {@code XSMALTTAG=} header parameter
 * holding the hexadecimal UTF-8 bytes of {@code key=value}, a parameter the YUV4MPEG2 demuxer ignores.
 */
public final class Y4mTagProvider implements ContainerProvider {
    /** The prefix of a tag parameter. */
    static final String PARAMETER = "XSMALTTAG=";

    /** The longest header line read. */
    private static final int MAX_HEADER = 4096;

    public Y4mTagProvider() {
    }

    @Override
    public String name() {
        return "y4m";
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.Y4M;
    }

    @Override
    public boolean editsTags() {
        return true;
    }

    @Override
    public MediaTags readTags(MediaSource source, MediaLimits limits) {
        var tags = MediaTags.builder();
        for (var parameter : header(source).split(" ")) {
            if (parameter.startsWith(PARAMETER)) {
                var entry = new String(HexFormat.of().parseHex(parameter, PARAMETER.length(), parameter.length()),
                        StandardCharsets.UTF_8);
                var separator = entry.indexOf('=');
                if (separator <= 0) {
                    throw new MediaException.Malformed("a YUV4MPEG2 tag parameter without a key");
                }
                tags.add(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        return tags.build();
    }

    @Override
    public TagRewrite planTags(MediaSource source, MediaTags tags, TagWriteOptions options) {
        var header = header(source);
        var line = new StringBuilder();
        for (var parameter : header.split(" ")) {
            if (!parameter.startsWith(PARAMETER)) {
                line.append(line.isEmpty() ? "" : " ").append(parameter);
            }
        }
        for (var entry : tags.entries()) {
            var bytes = (entry.key() + "=" + entry.value()).getBytes(StandardCharsets.UTF_8);
            line.append(' ').append(PARAMETER).append(HexFormat.of().formatHex(bytes));
        }
        var end = header.length() + 1L;
        return TagRewrite.builder().bytes(line.append('\n').toString().getBytes(StandardCharsets.US_ASCII))
                .copy(end, source.size() - end).build();
    }

    /** {@return the header line of a YUV4MPEG2 file, without its line feed} */
    private static String header(MediaSource source) {
        var bytes = new byte[(int) Math.min(source.size(), MAX_HEADER)];
        source.readFully(0, bytes, 0, bytes.length);
        for (var i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return new String(bytes, 0, i, StandardCharsets.US_ASCII);
            }
        }
        throw new MediaException.Malformed("the YUV4MPEG2 header line does not end");
    }
}
