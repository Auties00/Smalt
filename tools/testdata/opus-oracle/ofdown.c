/* Reference harness: muxes an opus_demo bitstream (length BE32, final range BE32, payload) into an Ogg Opus
   stream with libogg and decodes it with opusfile 0.12's op_read_float_stereo, which mixes multichannel
   streams down to stereo.

   ofdown <in.bit> <out.f32> <channels> <family> <streams> <coupled> <mapping> <pre-skip>

   <mapping> is the channel mapping as comma-separated entries. Every packet's granule position is the number
   of 48 kHz samples decoded through its end, so opusfile trims only the pre-skip. Writes the interleaved
   stereo float output. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ogg/ogg.h>
#include "opus.h"
#include "opusfile.h"

static unsigned rd32(const unsigned char *b) {
    return (unsigned)b[0] << 24 | (unsigned)b[1] << 16 | (unsigned)b[2] << 8 | b[3];
}

static unsigned char *ogg_data;
static size_t ogg_size, ogg_capacity;

static void append(const unsigned char *p, long n) {
    if (ogg_size + n > ogg_capacity) {
        ogg_capacity = (ogg_size + n) * 2;
        ogg_data = realloc(ogg_data, ogg_capacity);
    }
    memcpy(ogg_data + ogg_size, p, n);
    ogg_size += n;
}

static void flush(ogg_stream_state *os) {
    ogg_page page;
    while (ogg_stream_flush(os, &page)) {
        append(page.header, page.header_len);
        append(page.body, page.body_len);
    }
}

int main(int argc, char **argv) {
    if (argc < 9) {
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
    int channels = atoi(argv[3]);
    int family = atoi(argv[4]);
    int streams = atoi(argv[5]);
    int coupled = atoi(argv[6]);
    const char *table = argv[7];
    int preskip = atoi(argv[8]);
    unsigned char head[21 + 255];
    int head_size = family == 0 ? 19 : 21 + channels;
    memcpy(head, "OpusHead", 8);
    head[8] = 1;
    head[9] = (unsigned char)channels;
    head[10] = (unsigned char)preskip;
    head[11] = (unsigned char)(preskip >> 8);
    head[12] = 0x80;
    head[13] = 0xBB;
    head[14] = 0;
    head[15] = 0;
    head[16] = 0;
    head[17] = 0;
    head[18] = (unsigned char)family;
    if (family != 0) {
        head[19] = (unsigned char)streams;
        head[20] = (unsigned char)coupled;
        const char *p = table;
        for (int i = 0; i < channels; i++) {
            head[21 + i] = (unsigned char)strtol(p, (char **)&p, 10);
            if (*p == ',') p++;
        }
    }
    unsigned char tags[] = {'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 4, 0, 0, 0, 't', 'e', 's', 't', 0, 0, 0, 0};
    ogg_stream_state os;
    ogg_stream_init(&os, 1);
    ogg_packet op;
    memset(&op, 0, sizeof(op));
    op.packet = head;
    op.bytes = head_size;
    op.b_o_s = 1;
    ogg_stream_packetin(&os, &op);
    flush(&os);
    memset(&op, 0, sizeof(op));
    op.packet = tags;
    op.bytes = sizeof(tags);
    op.packetno = 1;
    ogg_stream_packetin(&os, &op);
    flush(&os);
    long pos = 0;
    ogg_int64_t granule = 0;
    ogg_int64_t packetno = 2;
    while (pos + 8 <= bytes) {
        int len = (int)rd32(bits + pos);
        unsigned char *data = bits + pos + 8;
        pos += 8 + len;
        granule += opus_packet_get_nb_samples(data, len, 48000);
        memset(&op, 0, sizeof(op));
        op.packet = data;
        op.bytes = len;
        op.granulepos = granule;
        op.packetno = packetno++;
        op.e_o_s = pos + 8 > bytes;
        ogg_stream_packetin(&os, &op);
        flush(&os);
    }
    ogg_stream_clear(&os);
    int err = 0;
    OggOpusFile *of = op_open_memory(ogg_data, ogg_size, &err);
    if (of == NULL) {
        fprintf(stderr, "op_open_memory failed %d\n", err);
        return 1;
    }
    FILE *out = fopen(argv[2], "wb");
    float pcm[5760 * 2];
    long total = 0;
    for (;;) {
        int samples = op_read_float_stereo(of, pcm, 5760 * 2);
        if (samples < 0) {
            fprintf(stderr, "op_read_float_stereo failed %d\n", samples);
            return 1;
        }
        if (samples == 0) break;
        fwrite(pcm, sizeof(float), (size_t)samples * 2, out);
        total += samples;
    }
    fclose(out);
    op_free(of);
    printf("samples=%ld\n", total);
    return 0;
}
