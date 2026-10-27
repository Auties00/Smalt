package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagReadOptions;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.probe.ProbeOptions;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs an input through every public entry point that reads its kind of file, each on a thread with a
 * {@link Hostile#SMALL_STACK} stack, within a deadline and an allocation bound, and reports the steps that broke the
 * hostile-input contract: only a {@link MediaException} may escape, and it must come from a check of the input
 * rather than from running out of memory or stack.
 */
final class EntryPoints {
    private EntryPoints() {
        throw new AssertionError();
    }

    /**
     * Returns the failures of the image entry points: probe, decode, a reduced decode, the page reader with every
     * page, the animation reader with every frame, the metadata reader and two metadata edits.
     *
     * @param what the input, for messages
     * @param data the file
     * @param limits the limits
     * @return the messages of the steps that failed, empty when every step kept the contract
     */
    static List<String> image(String what, byte[] data, MediaLimits limits) {
        var failures = new ArrayList<String>();
        var options = ImageDecoderOptions.builder().limits(limits).build();
        step(failures, what, "probe", data, () -> Images.probe(MediaSource.of(data)));
        step(failures, what, "decode", data, () -> Images.decode(MediaSource.of(data), options));
        step(failures, what, "reduced decode", data, () -> Images.decode(MediaSource.of(data),
                options.toBuilder().minSize(8, 8).build()));
        step(failures, what, "pages", data, () -> {
            try (var pages = Images.openPages(MediaSource.of(data), options)) {
                var count = pages.pageCount();
                if (count > 64) {
                    throw new AssertionError(count + " pages in a file of " + data.length + " bytes");
                }
                for (var index = 0; index < count; index++) {
                    pages.probe(index);
                    pages.read(index);
                }
            }
        });
        step(failures, what, "animation", data, () -> {
            try (var animation = Images.openAnimation(MediaSource.of(data), options)) {
                while (animation.read() != null) {
                    // drains
                }
            }
        });
        step(failures, what, "metadata", data, () -> ImageMetadata.read(MediaSource.of(data), limits));
        step(failures, what, "strip metadata", data, () -> ImageMetadataEditor.apply(data, ImageMetadataEdit.stripAll()));
        step(failures, what, "keep metadata", data, () -> ImageMetadataEditor.apply(data, ImageMetadataEdit.keepAll()));
        return failures;
    }

    /**
     * Returns the failures of the container entry points: demux with every packet and the chapters, metadata and
     * attached pictures, probe, and reading and writing the tags.
     *
     * @param what the input, for messages
     * @param data the file
     * @param limits the limits
     * @return the messages of the steps that failed, empty when every step kept the contract
     */
    static List<String> container(String what, byte[] data, MediaLimits limits) {
        var failures = new ArrayList<String>();
        step(failures, what, "demux", data, () -> {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data), DemuxerOptions.builder().limits(limits).build())) {
                demuxer.metadata();
                demuxer.chapters();
                demuxer.attachedPictures();
                for (var track : demuxer.tracks()) {
                    demuxer.trackMetadata(track.id());
                }
                while (demuxer.read() != null) {
                    // drains
                }
                if (demuxer.isSeekable()) {
                    demuxer.seek(0);
                    demuxer.read();
                }
            }
        });
        step(failures, what, "probe", data, () -> Media.probe(MediaSource.of(data), ProbeOptions.builder()
                .limits(limits).attachedPictures(true).readFirstFrames(true).build()));
        step(failures, what, "tags", data, () -> {
            var format = Containers.detect(MediaSource.of(data));
            if (format.isEmpty() || !TagEditor.isSupported(format.get())) {
                return;
            }
            var tags = TagEditor.read(MediaSource.of(data), TagReadOptions.builder().limits(limits).build());
            var edited = (tags == null ? MediaTags.builder() : tags.toBuilder()).set(StandardTag.TITLE, "x").build();
            TagEditor.write(data, edited, TagWriteOptions.builder().limits(limits).build());
            TagEditor.stripLocation(data, TagWriteOptions.builder().limits(limits).build());
        });
        return failures;
    }

    private static void step(List<String> failures, String what, String step, byte[] data, Hostile.Step body) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var before = threads.getTotalThreadAllocatedBytes();
        var thrown = Hostile.onSmallStack(what + " " + step, body);
        var allocated = threads.getTotalThreadAllocatedBytes() - before;
        var name = what + " " + step;
        if (thrown instanceof MediaException media) {
            try {
                Hostile.assertNoHiddenFailure(name, media);
            } catch (AssertionError failure) {
                failures.add(failure.getMessage());
            }
        } else if (thrown != null) {
            failures.add(name + " threw " + thrown + (thrown.getStackTrace().length > 0
                    ? " at " + thrown.getStackTrace()[0] : ""));
        }
        if (allocated > Hostile.MAX_ALLOCATION + 3L * data.length) {
            failures.add(name + " allocated " + (allocated >> 20) + " MiB");
        }
    }
}
