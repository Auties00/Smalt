"""Regenerates the Matroska virtual timeline sample of the I/O behavior harness and the linked-file fuzz seeds.

Writes, under the repository's core test resources:

- samples/matroska/ordered-parts.mkv: a hand-built Matroska file with an uncompressed video track (a key frame
  every 400 ms) and an 8-bit 1 kHz PCM track whose default edition is ordered: a chapter whose nested chapters play two
  spans in reverse order, a hidden chapter, a disabled chapter (ChapterFlagEnabled 0), a chapter that starts
  between key frames and frames, and one without ChapterTimeEnd;
- com/github/auties00/smalt/fuzz/DemuxerFuzzTestInputs/matroska/valid-ordered-parts.mkv: the same file;
- com/github/auties00/smalt/fuzz/DemuxerFuzzTestInputs/matroskaLinked/valid-medium-linked.mkv: three files one
  after another, a program whose ordered chapters play a span of an intro by its SegmentUUID and an ordered edition
  of an ending by ChapterSegmentEditionUID, and the two files they name;
- com/github/auties00/smalt/fuzz/DemuxerFuzzTestInputs/matroskaLinked/valid-hard-linked.mkv: the middle file of a
  chain of three hard-linked segments (PrevUUID, NextUUID) followed by the first and the last;
- com/github/auties00/smalt/fuzz/DemuxerFuzzTestInputs/matroskaLinked/valid-linked-editions-loop.mkv: a program
  whose ordered chapter plays an edition of a second file, followed by that file and a third, whose eight ordered
  editions each play the next edition of the other file 64 times over, so that a full expansion would visit 64^8
  chapters.

Every frame starts with its track number, its file number and its stored timestamp. As for make-samples.sh, the
committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-timeline-samples.py
"""
import os
import struct


def ebml_id(value):
    length = 4 if value >= 0x1000000 else 3 if value >= 0x10000 else 2 if value >= 0x100 else 1
    return value.to_bytes(length, "big")


def ebml_size(value):
    length = 1
    while value >= (1 << (7 * length)) - 1:
        length += 1
    return (value | (1 << (7 * length))).to_bytes(length, "big")


def element(identifier, payload):
    return ebml_id(identifier) + ebml_size(len(payload)) + payload


def master(identifier, *children):
    return element(identifier, b"".join(children))


def uint(identifier, value):
    return element(identifier, value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big"))


def string(identifier, value):
    return element(identifier, value.encode("utf-8"))


def floating(identifier, value):
    return element(identifier, struct.pack(">d", value))


def uuid(value):
    return bytes([value]) * 15 + b"\xa5"


VIDEO = 1
AUDIO = 2
SLOT = 40
SAMPLES = 40


def frame(track, number, pts, size):
    head = bytes([track, number]) + struct.pack(">q", pts)
    return head + bytes((pts // SLOT + index) & 0xFF for index in range(size - len(head)))


def frames(number, start, end, key_every=400):
    result = []
    for pts in range(start, end, SLOT):
        result.append((VIDEO, pts, pts % key_every == 0, frame(VIDEO, number, pts, 32)))
        result.append((AUDIO, pts, True, frame(AUDIO, number, pts, SAMPLES)))
    return result


def clusters(items):
    result = []
    blocks = []
    cluster_time = None
    for track, pts, key, payload in items:
        if cluster_time is None or pts - cluster_time >= 1000:
            if blocks:
                result.append(master(0x1F43B675, *blocks))
            cluster_time = pts
            blocks = [uint(0xE7, cluster_time)]
        header = bytes([0x80 | track]) + struct.pack(">h", pts - cluster_time) + bytes([0x80 if key else 0])
        blocks.append(element(0xA3, header + payload))
    if blocks:
        result.append(master(0x1F43B675, *blocks))
    return result


def tracks():
    video = master(0xAE, uint(0xD7, VIDEO), uint(0x73C5, VIDEO), uint(0x83, 1), string(0x86, "V_UNCOMPRESSED"),
                   uint(0x23E383, 40_000_000),
                   master(0xE0, uint(0xB0, 4), uint(0xBA, 4), element(0x2EB524, b"YUY2")))
    audio = master(0xAE, uint(0xD7, AUDIO), uint(0x73C5, AUDIO), uint(0x83, 2), string(0x86, "A_PCM/INT/LIT"),
                   master(0xE1, floating(0xB5, 1000.0), uint(0x9F, 1), uint(0x6264, 8)))
    return master(0x1654AE6B, video, audio)


def info(duration, identifier, *extra):
    return master(0x1549A966, uint(0x2AD7B1, 1_000_000), floating(0x4489, float(duration)),
                  element(0x73A4, identifier), *extra)


UID = [0]


def atom(start, end, title, *extra):
    UID[0] += 1
    fields = [uint(0x73C4, UID[0]), uint(0x91, start * 1_000_000)]
    if end is not None:
        fields.append(uint(0x92, end * 1_000_000))
    fields.append(master(0x80, string(0x85, title), string(0x437C, "eng")))
    return master(0xB6, *fields, *extra)


def edition(uid, ordered, default, *atoms):
    fields = [uint(0x45BC, uid)]
    if ordered:
        fields.append(uint(0x45DD, 1))
    if default:
        fields.append(uint(0x45DB, 1))
    return master(0x45B9, *fields, *atoms)


def matroska(segment_info, chapters, items):
    header = master(0x1A45DFA3, uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
                    string(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2))
    children = [segment_info, tracks()]
    if chapters is not None:
        children.append(master(0x1043A770, *chapters))
    children += clusters(items)
    return header + master(0x18538067, *children)


def ordered_parts():
    nested = atom(0, None, "Reversed", atom(1200, 1600, "Later"), atom(400, 800, "Earlier"))
    hidden = atom(2000, 2400, "Hidden", uint(0x98, 1))
    disabled = atom(2400, 2800, "Disabled", uint(0x4598, 0))
    straddling = atom(2600, 3010, "Straddling")
    open_ended = atom(3600, None, "Open")
    chapters = [edition(1, False, False, atom(0, 4000, "Whole")),
                edition(2, True, True, nested, hidden, disabled, straddling, open_ended)]
    return matroska(info(4000, uuid(1)), chapters, frames(0, 0, 4000))


def medium_linked():
    program_uid, intro_uid, ending_uid = uuid(1), uuid(2), uuid(3)
    program = matroska(info(2000, program_uid), [edition(1, True, True,
                                                          atom(0, 800, "Intro", element(0x6E67, intro_uid)),
                                                          atom(0, 2000, "Program"),
                                                          atom(0, None, "Ending", element(0x6E67, ending_uid),
                                                               uint(0x6EBC, 7)),
                                                          atom(0, 400, "Missing", element(0x6E67, uuid(9))))],
                       frames(0, 0, 2000))
    intro = matroska(info(1200, intro_uid), None, frames(1, 0, 1200))
    ending = matroska(info(1200, ending_uid), [edition(7, True, False, atom(800, 1200, "Late"),
                                                       atom(0, 400, "Early"))], frames(2, 0, 1200))
    return program + intro + ending


def hard_linked():
    first_uid, middle_uid, last_uid = uuid(1), uuid(2), uuid(3)
    first = matroska(info(1000, first_uid, element(0x3EB923, middle_uid)),
                     [edition(1, False, True, atom(0, 1000, "First"))], frames(1, 0, 1000))
    middle = matroska(info(1000, middle_uid, element(0x3CB923, first_uid), element(0x3EB923, last_uid)), None,
                      frames(2, 1000, 2000))
    last = matroska(info(1000, last_uid, element(0x3CB923, middle_uid)),
                    [edition(1, False, True, atom(2000, 3000, "Last"))], frames(3, 2000, 3000))
    return middle + first + last


def linked_loop():
    program_uid, first_uid, second_uid = uuid(1), uuid(21), uuid(22)
    files = []
    for own, other in ((first_uid, second_uid), (second_uid, first_uid)):
        editions = []
        for level in range(1, 9):
            if level < 8:
                atoms = [atom(0, None, "Loop", element(0x6E67, other), uint(0x6EBC, level + 1)) for _ in range(64)]
            else:
                atoms = [atom(0, 0, "Empty") for _ in range(64)]
            editions.append(edition(level, True, level == 1, *atoms))
        files.append(matroska(info(1000, own), editions, frames(1, 0, 1000)))
    program = matroska(info(1000, program_uid), [edition(1, True, True, atom(0, None, "Loop",
                                                                              element(0x6E67, first_uid),
                                                                              uint(0x6EBC, 1)))],
                       frames(0, 0, 1000))
    return program + files[0] + files[1]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    resources = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(here))), "core", "src", "test",
                             "resources")
    seeds = os.path.join(resources, "com", "github", "auties00", "smalt", "fuzz", "DemuxerFuzzTestInputs")
    parts = ordered_parts()
    outputs = {
        os.path.join(resources, "samples", "matroska", "ordered-parts.mkv"): parts,
        os.path.join(seeds, "matroska", "valid-ordered-parts.mkv"): parts,
        os.path.join(seeds, "matroskaLinked", "valid-medium-linked.mkv"): medium_linked(),
        os.path.join(seeds, "matroskaLinked", "valid-hard-linked.mkv"): hard_linked(),
        os.path.join(seeds, "matroskaLinked", "valid-linked-editions-loop.mkv"): linked_loop(),
    }
    for path, data in outputs.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as file:
            file.write(data)


if __name__ == "__main__":
    main()
