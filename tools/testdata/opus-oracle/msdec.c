/* Reference harness: decodes an opus_demo bitstream (length BE32, final range BE32, payload) with
   the libopus 1.5.2 decoders while dropping packets, and writes the interleaved output in coded
   channel order.

   msdec <in.bit> <out> <rate> <channels> <s|ms|proj> <streams> <coupled> <table> <gain> <fec 0|1>
         <loss pct> <seed> <f32|s16>

   <table> is the channel mapping as comma-separated entries (ms) or the demixing matrix in hex
   (proj); "s" decodes one stream and ignores it. Packet i (never the first) is lost when
   (i * 37 + seed) % 100 < pct. A lost packet is rebuilt from the in-band FEC of the next packet
   when fec is 1 and the next packet arrived (decode_fec with a span of the lost packet's
   duration), and concealed otherwise (a span of the lost packet's duration). <gain> is set with
   OPUS_SET_GAIN. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opus.h"
#include "opus_multistream.h"
#include "opus_projection.h"

static unsigned rd32(const unsigned char *b) {
    return (unsigned)b[0] << 24 | (unsigned)b[1] << 16 | (unsigned)b[2] << 8 | b[3];
}

int main(int argc, char **argv) {
    if (argc < 14) {
        fprintf(stderr, "usage\n");
        return 2;
    }
    FILE *in = fopen(argv[1], "rb");
    fseek(in, 0, SEEK_END);
    long bytes = ftell(in);
    fseek(in, 0, SEEK_SET);
    unsigned char *bits = malloc(bytes);
    fread(bits, 1, bytes, in);
    fclose(in);
    FILE *out = fopen(argv[2], "wb");
    int rate = atoi(argv[3]);
    int channels = atoi(argv[4]);
    const char *kind = argv[5];
    int streams = atoi(argv[6]);
    int coupled = atoi(argv[7]);
    const char *table = argv[8];
    int gain = atoi(argv[9]);
    int fec = atoi(argv[10]);
    int pct = atoi(argv[11]);
    int seed = atoi(argv[12]);
    int s16 = strcmp(argv[13], "s16") == 0;
    int err = OPUS_OK;
    OpusDecoder *dec = NULL;
    OpusMSDecoder *ms = NULL;
    OpusProjectionDecoder *proj = NULL;
    unsigned char mapping[256];
    static unsigned char demixing[255 * 255 * 2];
    if (strcmp(kind, "s") == 0) {
        dec = opus_decoder_create(rate, channels, &err);
        if (err == OPUS_OK) opus_decoder_ctl(dec, OPUS_SET_GAIN(gain));
    } else if (strcmp(kind, "ms") == 0) {
        const char *p = table;
        for (int i = 0; i < channels; i++) {
            mapping[i] = (unsigned char)strtol(p, (char **)&p, 10);
            if (*p == ',') p++;
        }
        ms = opus_multistream_decoder_create(rate, channels, streams, coupled, mapping, &err);
        if (err == OPUS_OK) opus_multistream_decoder_ctl(ms, OPUS_SET_GAIN(gain));
    } else {
        int size = (int)strlen(table) / 2;
        for (int i = 0; i < size; i++) {
            unsigned v;
            sscanf(table + 2 * i, "%2x", &v);
            demixing[i] = (unsigned char)v;
        }
        proj = opus_projection_decoder_create(rate, channels, streams, coupled, demixing, size, &err);
        if (err == OPUS_OK) opus_projection_decoder_ctl(proj, OPUS_SET_GAIN(gain));
    }
    if (err != OPUS_OK) {
        fprintf(stderr, "create failed %d\n", err);
        return 1;
    }
    int count = 0;
    long pos = 0;
    unsigned char **packets = malloc(sizeof(unsigned char *) * (bytes / 8 + 1));
    int *lengths = malloc(sizeof(int) * (bytes / 8 + 1));
    while (pos + 8 <= bytes) {
        int len = (int)rd32(bits + pos);
        packets[count] = bits + pos + 8;
        lengths[count] = len;
        pos += 8 + len;
        count++;
    }
    float *fpcm = malloc(sizeof(float) * 5760 * channels);
    short *spcm = malloc(sizeof(short) * 5760 * channels);
    int lost_count = 0, recovered = 0;
    for (int i = 0; i < count; i++) {
        int lost = i > 0 && (i * 37 + seed) % 100 < pct;
        int next_lost = i + 1 < count && ((i + 1) * 37 + seed) % 100 < pct;
        const unsigned char *data = packets[i];
        int len = lengths[i];
        int frame = 5760;
        int decode_fec = 0;
        if (lost) {
            frame = opus_packet_get_nb_samples(packets[i], lengths[i], rate);
            lost_count++;
            if (fec && i + 1 < count && !next_lost) {
                data = packets[i + 1];
                len = lengths[i + 1];
                decode_fec = 1;
                recovered++;
            } else {
                data = NULL;
                len = 0;
            }
        }
        int samples;
        if (dec) {
            samples = s16 ? opus_decode(dec, data, len, spcm, frame, decode_fec)
                    : opus_decode_float(dec, data, len, fpcm, frame, decode_fec);
        } else if (ms) {
            samples = s16 ? opus_multistream_decode(ms, data, len, spcm, frame, decode_fec)
                    : opus_multistream_decode_float(ms, data, len, fpcm, frame, decode_fec);
        } else {
            samples = s16 ? opus_projection_decode(proj, data, len, spcm, frame, decode_fec)
                    : opus_projection_decode_float(proj, data, len, fpcm, frame, decode_fec);
        }
        if (samples < 0) {
            fprintf(stderr, "decode failed %d at %d\n", samples, i);
            return 1;
        }
        if (s16) {
            fwrite(spcm, sizeof(short), (size_t)samples * channels, out);
        } else {
            fwrite(fpcm, sizeof(float), (size_t)samples * channels, out);
        }
    }
    fclose(out);
    printf("packets=%d lost=%d recovered=%d\n", count, lost_count, recovered);
    return 0;
}
