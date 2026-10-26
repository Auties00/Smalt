package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Records the files and segments written for one packaged stream: a CMAF track, or a rendition
 * muxed into transport stream segments.
 *
 * <p>Segment sinks handed to the muxers count the bytes written through them, and find the key frames of an
 * I-frame playlist when asked to. With byte-range addressing every sink writes into the stream's single file,
 * which stays open until {@link #close()}; otherwise each segment is a file of its own. Instances are not
 * thread-safe.
 */
final class SegmentedStream {
    /**
     * Describes one written segment.
     *
     * @param uri the file holding the segment
     * @param offset the offset of the segment in the file
     * @param size the segment size in bytes
     * @param startMicros the presentation time of the segment, in microseconds
     * @param durationMicros the duration of the segment, in microseconds
     * @param independent whether the segment starts with a key frame
     * @param ranged whether the segment shares its file with the stream's other segments
     */
    record Written(String uri, long offset, long size, long startMicros, long durationMicros, boolean independent,
                   boolean ranged) {
    }

    /**
     * Describes one key frame an I-frame playlist addresses.
     *
     * @param uri the file holding it
     * @param offset the offset of its byte range in the file
     * @param size the size of its byte range
     * @param startMicros its presentation time, in microseconds
     */
    record KeyFrame(String uri, long offset, long size, long startMicros) {
    }

    /**
     * Enumerates how the key frames of segments are found.
     */
    enum Scan {
        /**
         * Key frames are not looked for.
         */
        NONE,

        /**
         * Transport stream segments: every video PES packet at a random access point.
         */
        TRANSPORT_STREAM,

        /**
         * Fragmented MP4 segments: the first sample of each segment's fragment.
         */
        FRAGMENTS
    }

    /**
     * The name the stream's files start with.
     */
    final String name;

    /**
     * The name of the rendition the stream comes from.
     */
    final String rendition;

    /**
     * The tracks the stream carries, video first.
     */
    final List<Track> tracks;

    /**
     * The extension of media segments, with its dot.
     */
    private final String extension;

    /**
     * The output the files are opened on.
     */
    private final PackageOutput output;

    /**
     * Receives the name of every completed file.
     */
    private final List<String> files;

    /**
     * The single file of a byte-range stream, or {@code null}.
     */
    private MediaSink single;

    /**
     * The name of the single file of a byte-range stream.
     */
    private final String singleName;

    /**
     * The number of bytes written to the single file.
     */
    private long singleSize;

    /**
     * The initialization segment, or {@code null}.
     */
    Written init;

    /**
     * The media segments, in order.
     */
    final List<Written> segments = new ArrayList<>();

    /**
     * The first key frame of the video track, or the first packet of a text track, kept for codec strings that
     * need it.
     */
    byte[] keyFrame;

    /**
     * The key frames of the segments, in order, when they are looked for.
     */
    final List<KeyFrame> keyFrames = new ArrayList<>();

    /**
     * The presentation times of the key frames written to a transport stream muxer whose PES packets have not been
     * found yet, in microseconds.
     */
    final ArrayDeque<Long> pendingKeyTimes = new ArrayDeque<>();

    /**
     * The file holding the program tables that open the first transport stream segment, or {@code null}.
     */
    String tablesUri;

    /**
     * The offset of those tables in their file.
     */
    long tablesOffset;

    /**
     * The size of those tables in bytes.
     */
    long tablesSize;

    /**
     * How key frames are found.
     */
    private Scan scan = Scan.NONE;

    /**
     * The scanner of the single file of an indexed stream, or {@code null}.
     */
    private KeyFrameScanner indexScanner;

    /**
     * Receives the stream after each media segment, or {@code null}.
     */
    private Consumer<SegmentedStream> listener;

    /**
     * The number of leading segments whose files were deleted.
     */
    int removed;

    /**
     * The AES-128 key of whole-segment encryption, or {@code null} for clear segments.
     */
    private byte[] segmentKey;

    /**
     * The initialization vector of every encrypted segment, or {@code null} for each segment's media sequence
     * number.
     */
    private byte[] segmentIv;

    /**
     * The offset of the segment index of an indexed stream, in bytes.
     */
    long indexOffset;

    /**
     * The size of the segment index of an indexed stream, in bytes; zero when the stream is not
     * indexed.
     */
    long indexSize;

    /**
     * Constructs the record of a stream.
     *
     * @param name the name the stream's files start with
     * @param rendition the name of the rendition
     * @param tracks the tracks, video first
     * @param extension the extension of media segments, with its dot
     * @param byteRanges whether the stream is one file addressed by byte ranges
     * @param output the provider of the stream's files
     * @param files receives the name of every completed file
     */
    SegmentedStream(String name, String rendition, List<Track> tracks, String extension, boolean byteRanges,
                    PackageOutput output, List<String> files) {
        this.name = name;
        this.rendition = rendition;
        this.tracks = List.copyOf(tracks);
        this.extension = extension;
        this.output = output;
        this.files = files;
        this.singleName = byteRanges ? name + (extension.equals(".ts") ? ".ts" : ".mp4") : null;
    }

    /**
     * Encrypts every media segment written from now on with AES-128 in CBC mode and PKCS#7 padding, as HLS
     * {@code METHOD=AES-128} describes; the initialization segment stays clear.
     *
     * @param key the 16-byte key
     * @param iv the 16-byte initialization vector of every segment, or {@code null} for each segment's media
     * sequence number as a 128-bit big-endian integer
     */
    void encryptSegments(byte[] key, byte[] iv) {
        this.segmentKey = key.clone();
        this.segmentIv = iv == null ? null : iv.clone();
    }

    /**
     * Looks for the key frames of the media segments written from now on.
     *
     * @param scan how they are found
     */
    void scanKeyFrames(Scan scan) {
        this.scan = scan;
    }

    /**
     * Sets what receives the stream after each media segment it records.
     *
     * @param listener the receiver
     */
    void onSegment(Consumer<SegmentedStream> listener) {
        this.listener = listener;
    }

    /**
     * Opens the sink of the initialization segment.
     *
     * @return the sink, which the muxer closes
     */
    MediaSink openInit() {
        return open(name + "_init.mp4", true, 0, 0, true, -1);
    }

    /**
     * Opens the sink of a media segment.
     *
     * @param sequence the segment number, from one
     * @param startMicros the presentation time of the segment
     * @param durationMicros the duration of the segment
     * @param independent whether the segment starts with a key frame
     * @return the sink, which the muxer closes
     */
    MediaSink openSegment(int sequence, long startMicros, long durationMicros, boolean independent) {
        return open(String.format(Locale.ROOT, "%s_%05d%s", name, sequence, extension), false, startMicros,
                durationMicros, independent, sequence - 1);
    }

    /**
     * Opens a counting sink for one segment.
     *
     * @param fileName the file of a segment of its own
     * @param initialization whether this is the initialization segment
     * @param startMicros the presentation time of the segment
     * @param durationMicros the duration of the segment
     * @param independent whether the segment starts with a key frame
     * @param mediaSequence the media sequence number of a media segment, from zero
     * @return the sink
     */
    private MediaSink open(String fileName, boolean initialization, long startMicros, long durationMicros,
                           boolean independent, long mediaSequence) {
        MediaSink target;
        String uri;
        long offset;
        if (singleName != null) {
            if (single == null) {
                single = output.open(singleName);
            }
            target = single;
            uri = singleName;
            offset = singleSize;
        } else {
            target = output.open(fileName);
            uri = fileName;
            offset = 0;
        }
        var owns = singleName == null;
        if (!initialization && segmentKey != null) {
            var iv = segmentIv;
            if (iv == null) {
                iv = new byte[16];
                for (var index = 0; index < 8; index++) {
                    iv[15 - index] = (byte) (mediaSequence >>> (8 * index));
                }
            }
            target = new EncryptingSink(target, owns, segmentKey, iv);
            owns = true;
        }
        var scanner = initialization ? null : switch (scan) {
            case NONE -> null;
            case TRANSPORT_STREAM -> KeyFrameScanner.transportStream();
            case FRAGMENTS -> KeyFrameScanner.boxes();
        };
        return new CountingSink(target, owns, scanner, size -> {
            if (singleName != null) {
                singleSize += size;
            } else {
                files.add(fileName);
            }
            var written = new Written(uri, offset, size, startMicros, durationMicros, independent, singleName != null);
            if (initialization) {
                init = written;
                return;
            }
            segments.add(written);
            if (scanner != null) {
                keyFramesOf(scanner, written);
            }
            if (listener != null) {
                listener.accept(this);
            }
        });
    }

    /**
     * Records the key frames a scanner found in a segment.
     *
     * @param scanner the scanner of the segment
     * @param written the segment
     */
    private void keyFramesOf(KeyFrameScanner scanner, Written written) {
        scanner.end();
        if (scanner instanceof KeyFrameScanner.Ts ts) {
            if (tablesUri == null && ts.tablesEnd > 0) {
                tablesUri = written.uri();
                tablesOffset = written.offset();
                tablesSize = ts.tablesEnd;
            }
            for (var found : ts.found) {
                var time = pendingKeyTimes.poll();
                if (time == null) {
                    break;
                }
                keyFrames.add(new KeyFrame(written.uri(), written.offset() + found.offset(),
                        found.end() - found.offset(), time));
            }
        } else if (!scanner.found.isEmpty()) {
            var first = scanner.found.getFirst();
            keyFrames.add(new KeyFrame(written.uri(), written.offset(), first.end(), written.startMicros()));
        }
    }

    /**
     * Opens the single file of a stream indexed by one segment index, which the muxer writes
     * whole; {@link #indexed(FragmentedMp4Muxer.IndexLayout)} then records its layout.
     *
     * @return the sink of the file
     * @throws IllegalStateException if the stream is not one file
     */
    MediaSink openIndexed() {
        if (singleName == null) {
            throw new IllegalStateException("an indexed stream is one file");
        }
        single = output.open(singleName);
        if (scan == Scan.NONE) {
            return single;
        }
        indexScanner = KeyFrameScanner.boxes();
        return new ObservingSink(single, indexScanner);
    }

    /**
     * Records the layout of the single file of an indexed stream: the initialization range, the
     * index range and one byte-range segment per subsegment.
     *
     * @param layout the layout the muxer reported
     */
    void indexed(FragmentedMp4Muxer.IndexLayout layout) {
        init = new Written(singleName, 0, layout.initializationSize(), 0, 0, true, true);
        indexOffset = layout.indexOffset();
        indexSize = layout.indexSize();
        for (var subsegment : layout.subsegments()) {
            segments.add(new Written(singleName, subsegment.offset(), subsegment.size(), subsegment.startMicros(),
                    subsegment.durationMicros(), subsegment.independent(), true));
            if (indexScanner != null) {
                for (var found : indexScanner.found) {
                    if (found.offset() == subsegment.offset()) {
                        keyFrames.add(new KeyFrame(singleName, found.offset(), found.end() - found.offset(),
                                subsegment.startMicros()));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Deletes the files of the leading segments before an index, which no playlist lists any more; segments that
     * share the stream's single file are kept.
     *
     * @param end the index of the first segment kept
     */
    void removeBefore(int end) {
        while (removed < end && removed < segments.size()) {
            var segment = segments.get(removed++);
            if (!segment.ranged()) {
                output.delete(segment.uri());
                files.remove(segment.uri());
            }
        }
    }

    /**
     * Returns whether the stream is one file indexed by a single segment index.
     *
     * @return {@code true} once {@link #indexed(FragmentedMp4Muxer.IndexLayout)} recorded its
     * layout
     */
    boolean isIndexed() {
        return indexSize > 0;
    }

    /**
     * Closes the single file of a byte-range stream.
     */
    void close() {
        if (single != null) {
            single.close();
            single = null;
            files.add(singleName);
        }
    }

    /**
     * Returns the duration of the stream.
     *
     * @return the end of the last segment, in microseconds
     */
    long durationMicros() {
        var end = 0L;
        for (var segment : segments) {
            end = Math.max(end, segment.startMicros + segment.durationMicros);
        }
        return end;
    }

    /**
     * Returns the longest media segment.
     *
     * @return the duration in microseconds
     */
    long longestSegment() {
        var longest = 0L;
        for (var segment : segments) {
            longest = Math.max(longest, segment.durationMicros);
        }
        return longest;
    }

    /**
     * Returns the peak segment bit rate of RFC 8216 clause 4.3.4.2: the highest bit rate of any run of consecutive
     * media segments lasting between half and one and a half target durations, so that a short last segment
     * counts with the one before it; without such a run, the highest bit rate of one segment.
     *
     * @return the bit rate in bits per second, at least the average
     */
    long peakBandwidth() {
        var durations = new long[segments.size()];
        var target = 1L;
        for (var index = 0; index < durations.length; index++) {
            var segment = segments.get(index);
            var next = index + 1 < durations.length ? segments.get(index + 1).startMicros - segment.startMicros : 0;
            durations[index] = next > 0 ? next : Math.max(0, segment.durationMicros);
            target = Math.max(target, (durations[index] + 500_000) / 1_000_000);
        }
        var low = target * 500_000;
        var high = target * 1_500_000;
        var peak = 0L;
        for (var first = 0; first < durations.length; first++) {
            var bytes = 0L;
            var micros = 0L;
            for (var last = first; last < durations.length && micros <= high; last++) {
                bytes += segments.get(last).size;
                micros += durations[last];
                if (micros >= low && micros <= high) {
                    peak = Math.max(peak, bytes * 8_000_000L / micros);
                }
            }
        }
        if (peak == 0) {
            for (var index = 0; index < durations.length; index++) {
                if (durations[index] > 0) {
                    peak = Math.max(peak, segments.get(index).size * 8_000_000L / durations[index]);
                }
            }
        }
        return Math.max(peak, averageBandwidth());
    }

    /**
     * Returns the bit rate of the whole stream.
     *
     * @return the bit rate in bits per second
     */
    long averageBandwidth() {
        var bytes = 0L;
        for (var segment : segments) {
            bytes += segment.size;
        }
        var duration = durationMicros() - (segments.isEmpty() ? 0 : segments.getFirst().startMicros);
        return duration > 0 ? bytes * 8_000_000L / duration : 0;
    }

    /**
     * Returns whether every media segment starts with a key frame.
     *
     * @return {@code true} when every segment is independent
     */
    boolean independent() {
        for (var segment : segments) {
            if (!segment.independent) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the video track of the stream.
     *
     * @return the track, or {@code null}
     */
    VideoTrack video() {
        for (var track : tracks) {
            if (track instanceof VideoTrack video) {
                return video;
            }
        }
        return null;
    }

    /**
     * Returns the audio track of the stream.
     *
     * @return the track, or {@code null}
     */
    AudioTrack audio() {
        for (var track : tracks) {
            if (track instanceof AudioTrack audio) {
                return audio;
            }
        }
        return null;
    }

    /**
     * Returns the codec string of the stream.
     *
     * @return the codec strings of its tracks, video first, comma-separated
     */
    String codecString() {
        var builder = new StringBuilder();
        for (var track : tracks) {
            var value = CodecStrings.of(track, track.kind() == TrackKind.AUDIO ? null : keyFrame);
            if (value == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    /**
     * Receives the size of a completed segment.
     */
    @FunctionalInterface
    private interface Completion {
        /**
         * Receives the size.
         *
         * @param size the number of bytes written
         */
        void completed(long size);
    }

    /**
     * Forwards writes to a sink, counting them, and reports the count when closed.
     */
    private static final class CountingSink implements MediaSink {
        /**
         * The sink written to.
         */
        private final MediaSink target;

        /**
         * Whether closing this sink closes the target.
         */
        private final boolean owns;

        /**
         * Receives the count when the sink is closed.
         */
        private final Completion completion;

        /**
         * Finds the key frames written, or {@code null}.
         */
        private final KeyFrameScanner scanner;

        /**
         * The number of bytes written.
         */
        private long count;

        /**
         * Whether the sink was closed.
         */
        private boolean closed;

        /**
         * Constructs a counting sink.
         *
         * @param target the sink written to
         * @param owns whether closing this sink closes the target
         * @param scanner finds the key frames written, or {@code null}
         * @param completion receives the count when the sink is closed
         */
        CountingSink(MediaSink target, boolean owns, KeyFrameScanner scanner, Completion completion) {
            this.target = Objects.requireNonNull(target, "target");
            this.owns = owns;
            this.scanner = scanner;
            this.completion = completion;
        }

        @Override
        public long position() {
            return count;
        }

        /**
         * {@inheritDoc}
         *
         * @throws MediaException.Unsupported if {@code position} is not the current position: a
         * segment is written in order
         */
        @Override
        public void position(long position) {
            if (position != count) {
                throw new MediaException.Unsupported("non-seekable output", "packaged segments are written in order");
            }
        }

        @Override
        public boolean isSeekable() {
            return false;
        }

        @Override
        public void write(ByteBuffer data) {
            count += data.remaining();
            if (scanner != null) {
                scanner.observe(data);
            }
            target.write(data);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (owns) {
                target.close();
            }
            completion.completed(target instanceof EncryptingSink encrypting ? encrypting.written : count);
        }
    }

    /**
     * Forwards every call to a sink, showing a key frame scanner the bytes appended at its end; bytes written over
     * earlier ones, which a muxer patches into a seekable sink, are not shown.
     */
    private static final class ObservingSink implements MediaSink {
        /**
         * The sink written to.
         */
        private final MediaSink target;

        /**
         * Observes the bytes appended.
         */
        private final KeyFrameScanner scanner;

        /**
         * The position of the target where the observed bytes start.
         */
        private final long base;

        /**
         * Constructs an observing sink.
         *
         * @param target the sink written to
         * @param scanner observes the bytes appended
         */
        ObservingSink(MediaSink target, KeyFrameScanner scanner) {
            this.target = target;
            this.scanner = scanner;
            this.base = target.position();
        }

        @Override
        public long position() {
            return target.position();
        }

        @Override
        public void position(long position) {
            target.position(position);
        }

        @Override
        public boolean isSeekable() {
            return target.isSeekable();
        }

        @Override
        public void write(ByteBuffer data) {
            if (target.position() == base + scanner.position) {
                scanner.observe(data);
            }
            target.write(data);
        }

        @Override
        public void close() {
            target.close();
        }
    }

    /**
     * Encrypts what is written with AES-128 in CBC mode and PKCS#7 padding before forwarding it, the final padded
     * block written when the sink is closed.
     */
    private static final class EncryptingSink implements MediaSink {
        /**
         * The sink written to.
         */
        private final MediaSink target;

        /**
         * Whether closing this sink closes the target.
         */
        private final boolean owns;

        private final Cipher cipher;

        /**
         * The number of encrypted bytes written to the target.
         */
        private long written;

        /**
         * The number of clear bytes received.
         */
        private long received;

        /**
         * The ciphertext of the last write, reused across writes.
         */
        private ByteBuffer encrypted = ByteBuffer.allocate(8192);

        /**
         * Constructs an encrypting sink.
         *
         * @param target the sink written to
         * @param owns whether closing this sink closes the target
         * @param key the 16-byte key
         * @param iv the 16-byte initialization vector
         */
        EncryptingSink(MediaSink target, boolean owns, byte[] key, byte[] iv) {
            this.target = target;
            this.owns = owns;
            try {
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("AES is not available", exception);
            }
        }

        @Override
        public long position() {
            return received;
        }

        /**
         * {@inheritDoc}
         *
         * @throws MediaException.Unsupported if {@code position} is not the current position: an encrypted segment
         * is written in order
         */
        @Override
        public void position(long position) {
            if (position != received) {
                throw new MediaException.Unsupported("non-seekable output", "encrypted segments are written in order");
            }
        }

        @Override
        public boolean isSeekable() {
            return false;
        }

        @Override
        public void write(ByteBuffer data) {
            var length = data.remaining();
            received += length;
            var needed = cipher.getOutputSize(length);
            if (encrypted.capacity() < needed) {
                encrypted = ByteBuffer.allocate(Math.max(needed, 2 * encrypted.capacity()));
            }
            encrypted.clear();
            try {
                cipher.update(data, encrypted);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException(exception);
            }
            encrypted.flip();
            forward(encrypted);
        }

        @Override
        public void close() {
            try {
                forward(ByteBuffer.wrap(cipher.doFinal()));
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException(exception);
            }
            if (owns) {
                target.close();
            }
        }

        private void forward(ByteBuffer bytes) {
            var length = bytes.remaining();
            if (length > 0) {
                written += length;
                target.write(bytes);
            }
        }
    }
}
