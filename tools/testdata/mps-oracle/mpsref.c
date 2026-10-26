/* Oracle frontend over the MPEG Surround encoder and decoder libraries of the ISO/IEC 23003-3 reference software.

   mpsref enc <tree 5151|5152|525> <in.wav (6 ch)> <dmx.wav> <out.bs>
   mpsref dec <dmx.wav> <in.bs> <out.wav> [decType] [upmixType]

   The .bs file holds the SpatialSpecificConfig block (sacTimeAlignFlag, header length, config, byte aligned) and then
   one byte-aligned SpatialFrame per frame, each block preceded by its length as a 32-bit little-endian integer.
   Samples are float32 WAV files in 16-bit scale divided by 32768 on output, read from 16-bit or float WAV files. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "sac_dec_interface.h"
#include "sac_polyphase.h"
void mpsref_dump(void *decoder, int frame);

typedef struct {
    float *data;
    int channels;
    int frames;
    int rate;
} Wave;

static int rd16(const unsigned char *p) { return p[0] | p[1] << 8; }
static int rd32(const unsigned char *p) { return p[0] | p[1] << 8 | p[2] << 16 | p[3] << 24; }

static Wave readWave(const char *path) {
    Wave w = {0};
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(1); }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(size);
    fread(b, 1, size, f);
    fclose(f);
    long p = 12;
    int format = 1, bits = 16;
    while (p + 8 <= size) {
        int len = rd32(b + p + 4);
        if (!memcmp(b + p, "fmt ", 4)) {
            format = rd16(b + p + 8);
            w.channels = rd16(b + p + 10);
            w.rate = rd32(b + p + 12);
            bits = rd16(b + p + 22);
            if (format == 0xFFFE) format = rd16(b + p + 32);
        } else if (!memcmp(b + p, "data", 4)) {
            int bytes = bits / 8;
            if (p + 8 + len > size) len = size - p - 8;
            w.frames = len / (bytes * w.channels);
            w.data = malloc(sizeof(float) * w.frames * w.channels);
            for (long i = 0; i < (long) w.frames * w.channels; i++) {
                const unsigned char *s = b + p + 8 + i * bytes;
                if (format == 3) { float v; memcpy(&v, s, 4); w.data[i] = v * 32768.0f; }
                else if (bits == 16) w.data[i] = (short) rd16(s);
                else if (bits == 24) w.data[i] = ((s[0] << 8 | s[1] << 16 | s[2] << 24) >> 8) / 256.0f;
                else { fprintf(stderr, "unsupported wave\n"); exit(1); }
            }
            break;
        }
        p += 8 + len + (len & 1);
    }
    free(b);
    return w;
}

static void wr32(FILE *f, int v) { fputc(v, f); fputc(v >> 8, f); fputc(v >> 16, f); fputc(v >> 24, f); }
static void wr16(FILE *f, int v) { fputc(v, f); fputc(v >> 8, f); }

static void writeWave(const char *path, const float *data, int channels, int frames, int rate) {
    FILE *f = fopen(path, "wb");
    int bytes = frames * channels * 4;
    fwrite("RIFF", 1, 4, f); wr32(f, 36 + bytes); fwrite("WAVEfmt ", 1, 8, f); wr32(f, 16); wr16(f, 3);
    wr16(f, channels); wr32(f, rate); wr32(f, rate * channels * 4); wr16(f, channels * 4); wr16(f, 32);
    fwrite("data", 1, 4, f); wr32(f, bytes);
    for (long i = 0; i < (long) frames * channels; i++) { float v = data[i] / 32768.0f; fwrite(&v, 4, 1, f); }
    fclose(f);
}

#include "sac_enc.h"

static int encode(int tree, const char *in, const char *dmx, const char *out, int slots) {
    Wave w = readWave(in);
    if (w.channels != 6) { fprintf(stderr, "need 6 channels\n"); return 1; }
    Stream header;
    InitStream(&header, NULL, STREAM_WRITE);
    int bufferSize;
    tSpatialEnc *enc = SpatialEncOpen(tree, slots, w.rate, &bufferSize, &header, 0, 0, "");
    FILE *bs = fopen(out, "wb");
    int headerBytes = (GetBitsInStream(&header) + 7) / 8;
    wr32(bs, headerBytes);
    fwrite(header.buffer, 1, headerBytes, bs);
    int frameSize = bufferSize;
    int frames = w.frames / frameSize;
    int dmxChannels = tree == 525 ? 2 : 1;
    float *input = calloc(frameSize * 6, sizeof(float));
    float *output = calloc(frameSize * 2, sizeof(float));
    float *downmix = calloc((long) frames * frameSize * dmxChannels, sizeof(float));
    for (int f = 0; f < frames; f++) {
        memcpy(input, w.data + (long) f * frameSize * 6, sizeof(float) * frameSize * 6);
        Stream frame;
        InitStream(&frame, NULL, STREAM_WRITE);
        SpatialEncApply(enc, input, output, &frame, 0);
        ByteAlignWrite(&frame);
        int bytes = (GetBitsInStream(&frame) + 7) / 8;
        wr32(bs, bytes);
        fwrite(frame.buffer, 1, bytes, bs);
        memcpy(downmix + (long) f * frameSize * dmxChannels, output, sizeof(float) * frameSize * dmxChannels);
    }
    fclose(bs);
    writeWave(dmx, downmix, dmxChannels, frames * frameSize, w.rate);
    SpatialEncClose(enc);
    return 0;
}

typedef struct {
    BYTE_READER reader;
    const unsigned char *data;
    int length;
    int position;
} MemoryReader;

static int readByte(HANDLE_BYTE_READER reader) {
    MemoryReader *m = (MemoryReader *) reader;
    return m->position < m->length ? m->data[m->position++] : 0;
}

static unsigned char *readBlocks(const char *path, int *count, int **lengths) {
    FILE *f = fopen(path, "rb");
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(size);
    fread(b, 1, size, f);
    fclose(f);
    int n = 0;
    long p = 0;
    *lengths = malloc(sizeof(int) * (size / 4 + 1));
    while (p + 4 <= size) {
        int len = rd32(b + p);
        (*lengths)[n++] = len;
        p += 4 + len;
    }
    *count = n;
    return b;
}

static int decode(const char *dmx, const char *in, const char *out, int decType, int upmixType) {
    Wave w = readWave(dmx);
    int blocks;
    int *lengths;
    unsigned char *b = readBlocks(in, &blocks, &lengths);
    MemoryReader reader = {{readByte}, b + 4, lengths[0], 0};
    int timeSlots = 0, bitsRead = 0;
    int qmfBands = w.rate < 27713 ? 32 : w.rate >= 55426 ? 128 : 64;
    spatialDec *dec = SpatialDecOpen(&reader.reader, "", w.rate, w.channels, &timeSlots, qmfBands, 0, 0, decType,
                                     upmixType, 0, 0, lengths[0] * 8, &bitsRead, NULL);
    if (!dec) { fprintf(stderr, "open failed\n"); return 1; }
    int outChannels = SpatialDecGetNumOutputChannels(dec);
    int frameLength = timeSlots * qmfBands;
    int frames = blocks - 1;
    if ((long) frames * frameLength > w.frames) frames = w.frames / frameLength;
    float *pcm = calloc((long) frames * frameLength * outChannels, sizeof(float));
    float **real[6], **imag[6];
    float **pointers[12];
    for (int c = 0; c < 6; c++) {
        real[c] = malloc(sizeof(float *) * timeSlots);
        imag[c] = malloc(sizeof(float *) * timeSlots);
        for (int t = 0; t < timeSlots; t++) {
            real[c][t] = calloc(128, sizeof(float));
            imag[c][t] = calloc(128, sizeof(float));
        }
        pointers[2 * c] = real[c];
        pointers[2 * c + 1] = imag[c];
    }
    SacInitAnaFilterbank(NULL, qmfBands);
    SAC_POLYPHASE_ANA_FILTERBANK *analysis[6];
    for (int c = 0; c < w.channels; c++) SacOpenAnaFilterbank(&analysis[c]);
    float **timeOut = malloc(sizeof(float *) * outChannels);
    for (int c = 0; c < outChannels; c++) timeOut[c] = calloc(frameLength, sizeof(float));
    float *slot = calloc(qmfBands, sizeof(float));
    long offset = 4 + lengths[0];
    for (int f = 0; f < frames; f++) {
        offset += 4;
        MemoryReader frame = {{readByte}, b + offset, lengths[f + 1], 0};
        offset += lengths[f + 1];
        if (getenv("MPSREF_TRACE")) fprintf(stderr, "frame %d (%d bytes)\n", f, lengths[f + 1]);
        SpatialDecResetBitstream(dec, &frame.reader);
        int read = 0;
        SpatialDecParseFrame(dec, &read, 0);
        for (int c = 0; c < w.channels; c++) {
            for (int t = 0; t < timeSlots; t++) {
                for (int i = 0; i < qmfBands; i++) {
                    slot[i] = w.data[((long) f * frameLength + t * qmfBands + i) * w.channels + c];
                }
                SacCalculateAnaFilterbank(analysis[c], slot, real[c][t], imag[c][t]);
            }
        }
        SpatialDecApplyFrame(dec, timeSlots, pointers, timeOut, 1.0f, 0);
        if (getenv("MPSREF_DUMP")) mpsref_dump(dec, f);
        for (int c = 0; c < outChannels; c++) {
            for (int i = 0; i < frameLength; i++) {
                pcm[((long) f * frameLength + i) * outChannels + c] = timeOut[c][i];
            }
        }
    }
    writeWave(out, pcm, outChannels, frames * frameLength, w.rate);
    fprintf(stderr, "decoded %d frames of %d samples to %d channels\n", frames, frameLength, outChannels);
    SpatialDecClose(dec);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 6 && !strcmp(argv[1], "enc")) return encode(atoi(argv[2]), argv[3], argv[4], argv[5], argc > 6 ? atoi(argv[6]) : 32);
    if (argc >= 5 && !strcmp(argv[1], "dec")) {
        return decode(argv[2], argv[3], argv[4], argc > 5 ? atoi(argv[5]) : 0, argc > 6 ? atoi(argv[6]) : 0);
    }
    fprintf(stderr, "usage: mpsref enc <tree> <in.wav> <dmx.wav> <out.bs> | dec <dmx.wav> <in.bs> <out.wav> [decType] [upmixType]\n");
    return 2;
}
