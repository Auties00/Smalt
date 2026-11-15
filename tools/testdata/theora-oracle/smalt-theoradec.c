/* Decodes Theora packets with libtheora 1.2.0, the reference of Smalt's Theora tests.
 *
 * Usage: smalt-theoradec <packets> <output>
 * The packets file holds every packet, the three headers first, as a 32-bit little-endian length and its bytes. The
 * output holds, for every data packet, the picture region of the decoded frame (a repeated frame included), the
 * Y'CbCr planes one after the other, top row first, as th_decode_ycbcr_out returns them with post-processing off.
 * A packet that fails to decode ends the output with exit status 1. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <theora/theoradec.h>

static unsigned char *read_packet(FILE *in, long *length)
{
   unsigned char size[4];
   if (fread(size, 1, 4, in) != 4) {
      return NULL;
   }
   *length = size[0] | size[1] << 8 | size[2] << 16 | (long) size[3] << 24;
   unsigned char *data = malloc(*length > 0 ? *length : 1);
   if (*length > 0 && fread(data, 1, *length, in) != (size_t) *length) {
      free(data);
      return NULL;
   }
   return data;
}

int main(int argc, char **argv)
{
   if (argc != 3) {
      fprintf(stderr, "usage: smalt-theoradec packets output\n");
      return 2;
   }
   FILE *in = fopen(argv[1], "rb"), *out = fopen(argv[2], "wb");
   if (!in || !out) {
      fprintf(stderr, "cannot open the files\n");
      return 2;
   }
   th_info info;
   th_comment comment;
   th_setup_info *setup = NULL;
   th_info_init(&info);
   th_comment_init(&comment);
   long number = 0;
   for (int i = 0; i < 3; i++) {
      long length;
      unsigned char *data = read_packet(in, &length);
      if (!data) {
         fprintf(stderr, "missing header\n");
         return 2;
      }
      ogg_packet packet = {data, length, i == 0, 0, -1, number++};
      if (th_decode_headerin(&info, &comment, &setup, &packet) < 0) {
         fprintf(stderr, "invalid header %d\n", i);
         return 2;
      }
      free(data);
   }
   th_dec_ctx *decoder = th_decode_alloc(&info, setup);
   th_setup_free(setup);
   int level = 0;
   th_decode_ctl(decoder, TH_DECCTL_SET_PPLEVEL, &level, sizeof(level));
   long length;
   unsigned char *data;
   while ((data = read_packet(in, &length)) != NULL) {
      ogg_packet packet = {data, length, 0, 0, -1, number++};
      int result = th_decode_packetin(decoder, &packet, NULL);
      free(data);
      if (result < 0) {
         fprintf(stderr, "packet %ld failed: %d\n", number, result);
         return 1;
      }
      th_ycbcr_buffer buffer;
      th_decode_ycbcr_out(decoder, buffer);
      int xshift = info.pixel_fmt != TH_PF_444, yshift = info.pixel_fmt == TH_PF_420;
      for (int pli = 0; pli < 3; pli++) {
         int x0 = pli ? info.pic_x >> xshift : info.pic_x;
         int y0 = pli ? info.pic_y >> yshift : info.pic_y;
         int w = pli ? (info.pic_x + info.pic_width + xshift >> xshift) - x0 : info.pic_width;
         int h = pli ? (info.pic_y + info.pic_height + yshift >> yshift) - y0 : info.pic_height;
         for (int y = 0; y < h; y++) {
            fwrite(buffer[pli].data + (y0 + y) * (long) buffer[pli].stride + x0, 1, w, out);
         }
      }
   }
   th_decode_free(decoder);
   fclose(out);
   return 0;
}
