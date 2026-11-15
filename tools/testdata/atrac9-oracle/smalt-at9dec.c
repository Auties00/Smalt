/*
 * smalt-at9dec: decodes an ATRAC9 stream with LibAtrac9 (Alex Barney, MIT), the reference of Smalt's ATRAC9
 * decoder tests.
 *
 * Usage: smalt-at9dec <input> <output>
 *
 * The input is the 4-byte ATRAC9 configuration word followed by the superframes. Each superframe is decoded from a
 * copy padded with zeros, so that a decoder reading past its end reads zeros, as Smalt's decoder does; the decoded
 * samples are written to the output as interleaved signed 16-bit little-endian integers. On a decoding error the
 * program stops before the superframe that failed and prints "error <superframe index> <status>" to standard
 * output; it prints "ok <superframes>" otherwise. The exit status is 0 in both cases and 1 for a usage or I/O
 * failure or a configuration LibAtrac9 rejects.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libatrac9.h"

#define PADDING 65536

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "usage: smalt-at9dec <input> <output>\n");
        return 1;
    }
    FILE *in = fopen(argv[1], "rb");
    if (in == NULL) {
        perror(argv[1]);
        return 1;
    }
    fseek(in, 0, SEEK_END);
    long size = ftell(in);
    fseek(in, 0, SEEK_SET);
    if (size < 4) {
        fprintf(stderr, "input shorter than a configuration word\n");
        return 1;
    }
    unsigned char *data = malloc((size_t) size);
    if (data == NULL || fread(data, 1, (size_t) size, in) != (size_t) size) {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 1;
    }
    fclose(in);

    void *handle = Atrac9GetHandle();
    int status = Atrac9InitDecoder(handle, data);
    if (status != 0) {
        fprintf(stderr, "configuration rejected: %08x\n", (unsigned) status);
        return 1;
    }
    Atrac9CodecInfo info;
    Atrac9GetCodecInfo(handle, &info);

    FILE *out = fopen(argv[2], "wb");
    if (out == NULL) {
        perror(argv[2]);
        return 1;
    }
    unsigned char *superframe = calloc((size_t) info.superframeSize + PADDING, 1);
    short *pcm = malloc(sizeof(short) * (size_t) info.frameSamples * (size_t) info.channels);
    short *output = malloc(sizeof(short) * (size_t) info.frameSamples * (size_t) info.channels
                           * (size_t) info.framesInSuperframe);
    long position = 4;
    long index = 0;
    while (position < size) {
        long length = size - position < info.superframeSize ? size - position : info.superframeSize;
        memset(superframe, 0, (size_t) info.superframeSize + PADDING);
        memcpy(superframe, data + position, (size_t) length);
        int offset = 0;
        for (int frame = 0; frame < info.framesInSuperframe; frame++) {
            int used = 0;
            status = Atrac9Decode(handle, superframe + offset, pcm, &used);
            if (status != 0) {
                printf("error %ld %08x\n", index, (unsigned) status);
                fclose(out);
                return 0;
            }
            offset += used;
            memcpy(output + (size_t) frame * info.frameSamples * info.channels, pcm,
                   sizeof(short) * (size_t) info.frameSamples * (size_t) info.channels);
        }
        size_t count = (size_t) info.frameSamples * (size_t) info.channels * (size_t) info.framesInSuperframe;
        for (size_t i = 0; i < count; i++) {
            unsigned char bytes[2] = {(unsigned char) (output[i] & 0xFF), (unsigned char) ((output[i] >> 8) & 0xFF)};
            fwrite(bytes, 1, 2, out);
        }
        position += info.superframeSize;
        index++;
    }
    printf("ok %ld\n", index);
    fclose(out);
    return 0;
}
