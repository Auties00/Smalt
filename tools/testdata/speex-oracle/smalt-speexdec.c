/* Decodes Speex packets with the fixed-point libspeex 1.2.1 decoder, the reference of Smalt's Speex tests.
 *
 * Usage: smalt-speexdec <mode: 0 narrowband, 1 wideband, 2 ultra-wideband> <frames per packet> <channels>
 *                       <enhancement 0 or 1> <packets> <output>
 * The packets file holds every packet as a 32-bit little-endian length and its bytes; the length 0xffffffff marks a
 * lost packet, whose frames are concealed. The output holds the 16-bit little-endian samples of every frame decoded,
 * interleaved for stereo, as speexdec writes them before its granule position trimming: a frame that fails to decode
 * ends its packet. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <speex/speex.h>
#include <speex/speex_stereo.h>
#include <speex/speex_callbacks.h>

int main(int argc, char **argv)
{
   if (argc != 7) {
      fprintf(stderr, "usage: smalt-speexdec mode frames channels enh packets output\n");
      return 2;
   }
   int mode = atoi(argv[1]), frames = atoi(argv[2]), channels = atoi(argv[3]);
   spx_int32_t enh = atoi(argv[4]);
   FILE *in = fopen(argv[5], "rb"), *out = fopen(argv[6], "wb");
   if (!in || !out) {
      fprintf(stderr, "cannot open the files\n");
      return 2;
   }
   void *st = speex_decoder_init(speex_lib_get_mode(mode));
   speex_decoder_ctl(st, SPEEX_SET_ENH, &enh);
   spx_int32_t frame_size;
   speex_decoder_ctl(st, SPEEX_GET_FRAME_SIZE, &frame_size);
   SpeexStereoState stereo = SPEEX_STEREO_STATE_INIT;
   SpeexCallback callback;
   callback.callback_id = SPEEX_INBAND_STEREO;
   callback.func = speex_std_stereo_request_handler;
   callback.data = &stereo;
   speex_decoder_ctl(st, SPEEX_SET_HANDLER, &callback);
   SpeexBits bits;
   speex_bits_init(&bits);
   static char packet[1 << 16];
   static spx_int16_t output[2 * 640];
   unsigned char head[4];
   while (fread(head, 1, 4, in) == 4) {
      unsigned int length = head[0] | head[1] << 8 | head[2] << 16 | (unsigned int) head[3] << 24;
      int lost = length == 0xffffffffu;
      if (!lost) {
         if (length > sizeof packet || fread(packet, 1, length, in) != length) {
            fprintf(stderr, "truncated packets file\n");
            return 2;
         }
         speex_bits_read_from(&bits, packet, (int) length);
      }
      for (int j = 0; j < frames; j++) {
         int ret = speex_decode_int(st, lost ? NULL : &bits, output);
         if (ret == -1 || ret == -2) {
            break;
         }
         if (!lost && speex_bits_remaining(&bits) < 0) {
            break;
         }
         if (channels == 2) {
            speex_decode_stereo_int(output, frame_size, &stereo);
         }
         for (int i = 0; i < frame_size * channels; i++) {
            fputc(output[i] & 0xff, out);
            fputc((output[i] >> 8) & 0xff, out);
         }
      }
   }
   speex_bits_destroy(&bits);
   speex_decoder_destroy(st);
   fclose(out);
   return 0;
}
