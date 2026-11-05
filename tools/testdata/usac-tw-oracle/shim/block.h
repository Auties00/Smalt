/* Minimal stand-in for the reference software's block.h: the block lengths and the window shape enumeration. */
#ifndef BLOCK_H
#define BLOCK_H 1
#define BLOCK_LEN_LONG 1024
#define BLOCK_LEN_MEDIUM 512
#define BLOCK_LEN_SHORT 128
#define BLOCK_LEN_LONG_S 960
#define BLOCK_LEN_MEDIUM_S 480
#define BLOCK_LEN_SHORT_S 120
#define BLOCK_LEN_LONG_SSR 256
#define BLOCK_LEN_SHORT_SSR 32
typedef enum { WS_FHG, WS_DOLBY, WS_ZPW, N_WINDOW_SHAPES } WINDOW_SHAPE;
#endif
