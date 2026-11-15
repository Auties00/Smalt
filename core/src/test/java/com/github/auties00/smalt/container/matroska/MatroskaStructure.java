package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.internal.tags.EbmlIds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the structure of a Matroska file the way mkvalidator does for the properties the muxer
 * controls: element sizes nest exactly, the segment and cluster sizes match their content (or are
 * unknown), every {@code SeekHead} entry points at an element of the named type, every cue point
 * leads to a block of its track with its time, block timestamps fit their cluster, and the
 * {@code Duration} covers every block.
 */
final class MatroskaStructure {
    static final long UNKNOWN = -1;

    final byte[] data;
    final List<Long> clusterPositions = new ArrayList<>();
    final Map<Long, Long> seeks = new HashMap<>();
    final List<long[]> cues = new ArrayList<>();
    final List<long[]> blocks = new ArrayList<>();
    final List<List<Element>> tracks = new ArrayList<>();
    long segmentStart;
    long segmentSize;
    double duration = -1;
    long timestampScale = 1_000_000;
    String docType;
    boolean unknownClusters;

    /** One child of a {@code TrackEntry}: its id, absolute offset and payload. */
    record Element(long id, int offset, byte[] payload) {
    }

    /** Returns the first child of a track entry with an id, or {@code null}. */
    static Element child(List<Element> entry, long id) {
        for (var element : entry) {
            if (element.id() == id) {
                return element;
            }
        }
        return null;
    }

    private MatroskaStructure(byte[] data) {
        this.data = data;
    }

    static MatroskaStructure check(byte[] data) {
        var structure = new MatroskaStructure(data);
        structure.walk();
        structure.verify();
        return structure;
    }

    private long id(int[] position) {
        var first = data[position[0]] & 0xFF;
        var length = Integer.numberOfLeadingZeros(first) - 23;
        assertTrue(length >= 1 && length <= 4, "invalid id at " + position[0]);
        var value = 0L;
        for (var i = 0; i < length; i++) {
            value = (value << 8) | (data[position[0]++] & 0xFF);
        }
        return value;
    }

    private long size(int[] position) {
        var first = data[position[0]] & 0xFF;
        var length = Integer.numberOfLeadingZeros(first) - 23;
        assertTrue(length >= 1 && length <= 8, "invalid size at " + position[0]);
        var value = first & (0xFF >> length);
        var allOnes = value == (0xFF >> length);
        position[0]++;
        long result = value;
        for (var i = 1; i < length; i++) {
            var b = data[position[0]++] & 0xFF;
            allOnes &= b == 0xFF;
            result = (result << 8) | b;
        }
        return allOnes ? UNKNOWN : result;
    }

    private long unsigned(int offset, long length) {
        var value = 0L;
        for (var i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private void walk() {
        var position = new int[]{0};
        assertEquals(EbmlIds.EBML, id(position));
        var headerSize = size(position);
        var headerEnd = position[0] + (int) headerSize;
        while (position[0] < headerEnd) {
            var child = id(position);
            var length = size(position);
            if (child == EbmlIds.DOC_TYPE) {
                docType = new String(data, position[0], (int) length);
            }
            position[0] += (int) length;
        }
        assertEquals(headerEnd, position[0]);
        assertEquals(EbmlIds.SEGMENT, id(position));
        segmentSize = size(position);
        segmentStart = position[0];
        if (segmentSize != UNKNOWN) {
            assertEquals(data.length - segmentStart, segmentSize, "segment size");
        }
        var clusterTime = 0L;
        while (position[0] < data.length) {
            var start = position[0];
            var element = id(position);
            var length = size(position);
            var body = position[0];
            if (element == EbmlIds.CLUSTER) {
                clusterPositions.add(start - segmentStart);
                if (length == UNKNOWN) {
                    unknownClusters = true;
                    length = clusterEnd(body) - body;
                }
            } else {
                assertTrue(length != UNKNOWN, "unknown size of 0x" + Long.toHexString(element));
            }
            var end = body + length;
            assertTrue(end <= data.length, "element 0x" + Long.toHexString(element) + " overruns the file");
            if (element == EbmlIds.SEEK_HEAD) {
                readSeekHead(body, end);
            } else if (element == EbmlIds.INFO) {
                readInfo(body, end);
            } else if (element == EbmlIds.TRACKS) {
                readTracks(body, end);
            } else if (element == EbmlIds.CUES) {
                readCues(body, end);
            } else if (element == EbmlIds.CLUSTER) {
                readCluster(start - segmentStart, body, (int) end);
            }
            position[0] = (int) end;
        }
        assertEquals(data.length, position[0]);
    }

    private int clusterEnd(int body) {
        var position = new int[]{body};
        while (position[0] < data.length) {
            var start = position[0];
            var element = id(position);
            if (element == EbmlIds.CLUSTER || element == EbmlIds.CUES || element == EbmlIds.TAGS) {
                return start;
            }
            var length = size(position);
            position[0] += (int) length;
        }
        return data.length;
    }

    private void readSeekHead(int body, long end) {
        var position = new int[]{body};
        while (position[0] < end) {
            assertEquals(EbmlIds.SEEK, id(position));
            var seekEnd = position[0] + (int) size(position);
            long target = -1;
            long where = -1;
            while (position[0] < seekEnd) {
                var child = id(position);
                var length = size(position);
                if (child == EbmlIds.SEEK_ID) {
                    target = unsigned(position[0], length);
                } else if (child == EbmlIds.SEEK_POSITION) {
                    where = unsigned(position[0], length);
                }
                position[0] += (int) length;
            }
            seeks.put(target, where);
        }
    }

    private void readTracks(int body, long end) {
        var position = new int[]{body};
        while (position[0] < end) {
            assertEquals(EbmlIds.TRACK_ENTRY, id(position));
            var entrySize = (int) size(position);
            var entryEnd = position[0] + entrySize;
            var entry = new ArrayList<Element>();
            while (position[0] < entryEnd) {
                var offset = position[0];
                var child = id(position);
                var length = size(position);
                entry.add(new Element(child, offset, java.util.Arrays.copyOfRange(data, position[0], position[0] + (int) length)));
                position[0] += (int) length;
            }
            assertEquals(entryEnd, position[0], "TrackEntry content overruns");
            tracks.add(entry);
        }
    }

    private void readInfo(int body, long end) {
        var position = new int[]{body};
        while (position[0] < end) {
            var child = id(position);
            var length = size(position);
            if (child == EbmlIds.DURATION) {
                duration = Double.longBitsToDouble(unsigned(position[0], 8));
            } else if (child == EbmlIds.TIMESTAMP_SCALE) {
                timestampScale = unsigned(position[0], length);
            }
            position[0] += (int) length;
        }
    }

    private void readCues(int body, long end) {
        var position = new int[]{body};
        while (position[0] < end) {
            assertEquals(0xBB, id(position));
            var pointEnd = position[0] + (int) size(position);
            long time = -1;
            long track = -1;
            long cluster = -1;
            long relative = -1;
            while (position[0] < pointEnd) {
                var child = id(position);
                var length = size(position);
                if (child == 0xB3) {
                    time = unsigned(position[0], length);
                    position[0] += (int) length;
                } else if (child == 0xB7) {
                    var positionsEnd = position[0] + (int) length;
                    while (position[0] < positionsEnd) {
                        var field = id(position);
                        var fieldLength = size(position);
                        var value = unsigned(position[0], fieldLength);
                        if (field == 0xF7) {
                            track = value;
                        } else if (field == 0xF1) {
                            cluster = value;
                        } else if (field == 0xF0) {
                            relative = value;
                        }
                        position[0] += (int) fieldLength;
                    }
                } else {
                    position[0] += (int) length;
                }
            }
            cues.add(new long[]{time, track, cluster, relative});
        }
    }

    private void readCluster(long clusterPosition, int body, int end) {
        var position = new int[]{body};
        var clusterTime = -1L;
        while (position[0] < end) {
            var offset = position[0] - body;
            var child = id(position);
            var length = size(position);
            assertTrue(length != UNKNOWN, "unknown size inside a cluster");
            if (child == EbmlIds.TIMESTAMP) {
                clusterTime = unsigned(position[0], length);
            } else if (child == EbmlIds.SIMPLE_BLOCK || child == EbmlIds.BLOCK_GROUP) {
                assertTrue(clusterTime >= 0, "block before the cluster timestamp");
                var blockStart = position[0];
                if (child == EbmlIds.BLOCK_GROUP) {
                    var inner = new int[]{position[0]};
                    assertEquals(EbmlIds.BLOCK, id(inner));
                    size(inner);
                    blockStart = inner[0];
                }
                var inner = new int[]{blockStart};
                var track = size(inner);
                var relative = (short) ((data[inner[0]] & 0xFF) << 8 | (data[inner[0] + 1] & 0xFF));
                var flags = data[inner[0] + 2] & 0xFF;
                blocks.add(new long[]{clusterPosition, offset, track, clusterTime + relative, child == EbmlIds.SIMPLE_BLOCK ? flags >> 7 : 1});
            }
            position[0] += (int) length;
        }
        assertEquals(end, position[0], "cluster content overruns");
    }

    private void verify() {
        for (var seek : seeks.entrySet()) {
            var position = new int[]{(int) (segmentStart + seek.getValue())};
            assertEquals((long) seek.getKey(), id(position), "SeekHead entry for 0x" + Long.toHexString(seek.getKey()));
        }
        for (var cue : cues) {
            assertTrue(clusterPositions.contains(cue[2]), "cue points at no cluster: " + cue[2]);
            long[] found = null;
            for (var block : blocks) {
                if (block[0] == cue[2] && block[1] == cue[3]) {
                    found = block;
                }
            }
            assertNotNull(found, "cue relative position points at no block");
            assertEquals(cue[1], found[2], "cue track");
            assertEquals(cue[0], found[3], "cue time");
            assertEquals(1, found[4], "cue on a non-key block");
        }
        if (duration >= 0) {
            for (var block : blocks) {
                if (block[3] > duration + 1e-6) {
                    fail("block at " + block[3] + " beyond the duration " + duration);
                }
            }
        }
    }
}
