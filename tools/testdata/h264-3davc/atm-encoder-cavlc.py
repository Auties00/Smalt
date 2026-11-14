"""Patches the 3DV-ATM 14.0 encoder (ITU-T H.264.2) to code the 3D-AVC texture coding tools in CAVLC slices.

The unpatched encoder writes view synthesis prediction and adaptive luminance compensation only with CABAC (it
crashes when they are enabled with SymbolMode 0). This script adds the CAVLC syntax of ITU-T H.264 I.7.3.4.1 and
I.7.3.6 (08/2024):

- mb_skip_type_flag u(1) after every mb_skip_run greater than 0 of a slice with VspRefExist, giving the type of every
  macroblock of the run (view synthesis skip or not);
- mb_direct_type_flag u(1) after the mb_type of a B_Direct_16x16 macroblock with direct_spatial_mv_pred_flag and
  VspRefExist;
- mb_alc_flag u(1) after the mb_type of a P_L0_16x16, P_L0_L0_16x8 or P_L0_L0_8x16 macroblock;
- bvsp_flag_lX u(1) after every ref_idx_lX that is present and refers to an inter-view reference.

The mode decision is restricted to what that syntax can carry: no adaptive luminance compensation skip (CAVLC has no
mb_alc_skip_flag), no block-based view synthesis prediction where the reference index is absent (a list of one
entry, or a macroblock with adaptive luminance compensation) or in a P_8x8ref0 macroblock, and in P slices the
skipped macroblocks of one run share their type. In B slices a skipped macroblock whose type differs from its run's
is written as the equivalent B_Direct_16x16 macroblock with coded_block_pattern 0, which reconstructs identically.

Usage: python -I atm-encoder-cavlc.py <encoder build dir holding lencod/>
"""
import sys

root = sys.argv[1]


def patch(path, pairs):
    full = root + '/' + path
    text = open(full, newline='').read()
    crlf = '\r\n' in text
    text = text.replace('\r\n', '\n')
    for old, new in pairs:
        if text.count(old) != 1:
            raise SystemExit('%s: expected one occurrence of:\n%s\nfound %d' % (path, old, text.count(old)))
        text = text.replace(old, new)
    if crlf:
        text = text.replace('\n', '\r\n')
    open(full, 'w', newline='').write(text)


# The type of the current CAVLC skip run.
patch('lencod/inc/global.h', [(
    '  int cod_counter;             //!< Current count of number of skipped macroblocks in a row\n',
    '  int cod_counter;             //!< Current count of number of skipped macroblocks in a row\n'
    '  int cod_vsskip;              //!< Whether the macroblocks of the current CAVLC skip run are view synthesis skips\n',
)])

# Shared helper: mb_skip_type_flag after a run.
patch('lencod/src/macroblock.c', [
    ('#include "alc.h"\n',
     '#include "alc.h"\n'
     'static int write_skip_type_cavlc(Macroblock *currMB, int run, DataPartition *dataPart);\n'),
    ('int ZeroRef (Macroblock* currMB)\n{\n'
     '  PicMotionParams **motion = currMB->p_Vid->enc_picture->mv_info;\n'
     '  int i,j;\n'
     '\n'
     '  for (j=currMB->block_y; j<currMB->block_y + BLOCK_MULTIPLE; ++j)\n'
     '  {\n'
     '    for (i=currMB->block_x; i<currMB->block_x + BLOCK_MULTIPLE; ++i)\n'
     '    {\n'
     '      if (motion[j][i].ref_idx[LIST_0] != 0)\n'
     '        return 0;\n',
     'static int write_skip_type_cavlc(Macroblock *currMB, int run, DataPartition *dataPart)\n'
     '{\n'
     '  SyntaxElement se;\n'
     '  if (run <= 0 || !currMB->p_Slice->bVspRefExist)\n'
     '    return 0;\n'
     '  se.value1 = currMB->p_Vid->cod_vsskip;\n'
     '  se.value2 = 0;\n'
     '  se.type   = SE_MBTYPE;\n'
     '  TRACE_SE (se.tracestring, "mb_skip_type_flag");\n'
     '  writeSE_Flag (&se, dataPart);\n'
     '  return se.len;\n'
     '}\n'
     '\n'
     'int ZeroRef (Macroblock* currMB)\n{\n'
     '  PicMotionParams **motion = currMB->p_Vid->enc_picture->mv_info;\n'
     '  int i,j;\n'
     '\n'
     '  for (j=currMB->block_y; j<currMB->block_y + BLOCK_MULTIPLE; ++j)\n'
     '  {\n'
     '    for (i=currMB->block_x; i<currMB->block_x + BLOCK_MULTIPLE; ++i)\n'
     '    {\n'
     '      if (motion[j][i].ref_idx[LIST_0] != 0 || motion[j][i].isVSPRef[LIST_0])\n'
     '        return 0;\n'),
    # end of slice run (slice modes 0 and 1)
    ('      TRACE_SE (se.tracestring, "mb_skip_run");\n'
     '      writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '      rlc_bits=se.len;\n'
     '      currMB->bits.mb_mode = currMB->bits.mb_mode + (unsigned short) rlc_bits;\n'
     '      p_Vid->cod_counter = 0;\n',
     '      TRACE_SE (se.tracestring, "mb_skip_run");\n'
     '      writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '      rlc_bits=se.len;\n'
     '      rlc_bits += write_skip_type_cavlc(currMB, p_Vid->cod_counter, dataPart);\n'
     '      currMB->bits.mb_mode = currMB->bits.mb_mode + (unsigned short) rlc_bits;\n'
     '      p_Vid->cod_counter = 0;\n'),
    # B slice: forced coded direct macroblock for a skip of the other type
    ('  int             skip       = (currMB->mb_type != 0 && currMB->mb_type != BVSP_DIRECT) ? 0 : !currMB->cbp;  \n'
     '#else\n'
     '  int             skip       = currMB->mb_type ? 0 : !currMB->cbp;  \n'
     '#endif\n',
     '  int             skip       = (currMB->mb_type != 0 && currMB->mb_type != BVSP_DIRECT) ? 0 : !currMB->cbp;  \n'
     '  int             force_coded = currSlice->symbol_mode == CAVLC && skip && currSlice->bVspRefExist\n'
     '                              && p_Vid->cod_counter > 0\n'
     '                              && (currMB->mb_type == BVSP_DIRECT) != p_Vid->cod_vsskip;\n'
     '#else\n'
     '  int             skip       = currMB->mb_type ? 0 : !currMB->cbp;  \n'
     '#endif\n'),
    ('  // VLC not intra\n'
     '  else if (currMB->mb_type != 0 || ((currMB->cbp != 0 || currSlice->cmp_cbp[1] != 0 || currSlice->cmp_cbp[2]!=0 )))\n'
     '  {\n'
     '    //===== Run Length Coding: Non-Skipped macroblock =====\n'
     '    se.value1 = p_Vid->cod_counter;\n'
     '    se.value2 = 0;\n'
     '    se.type   = SE_MBTYPE;\n'
     '\n'
     '    TRACE_SE (se.tracestring, "mb_skip_run");\n'
     '    writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '\n'
     '    // Reset cod counter\n'
     '    p_Vid->cod_counter = 0;\n',
     '  // VLC not intra\n'
     '  else if (force_coded || (currMB->mb_type != 0 && currMB->mb_type != BVSP_DIRECT) || ((currMB->cbp != 0 || currSlice->cmp_cbp[1] != 0 || currSlice->cmp_cbp[2]!=0 )))\n'
     '  {\n'
     '    //===== Run Length Coding: Non-Skipped macroblock =====\n'
     '    se.value1 = p_Vid->cod_counter;\n'
     '    se.value2 = 0;\n'
     '    se.type   = SE_MBTYPE;\n'
     '\n'
     '    TRACE_SE (se.tracestring, "mb_skip_run");\n'
     '    writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '    no_bits         += write_skip_type_cavlc(currMB, p_Vid->cod_counter, dataPart);\n'
     '\n'
     '    // Reset cod counter\n'
     '    p_Vid->cod_counter = 0;\n'),
    ('#if TRACE\n'
     '    snprintf(se.tracestring, TRACESTRING_SIZE, "mb_type (B_SLICE) (%2d,%2d) = %3d",currMB->mb_x, currMB->mb_y, currMB->mb_type);\n'
     '#endif\n'
     '\n'
     '    currSlice->writeMB_typeInfo(currMB, &se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '  }\n'
     '  else\n'
     '  {\n'
     '    //Run Length Coding: Skipped macroblock\n'
     '    ++(p_Vid->cod_counter);\n',
     '#if TRACE\n'
     '    snprintf(se.tracestring, TRACESTRING_SIZE, "mb_type (B_SLICE) (%2d,%2d) = %3d",currMB->mb_x, currMB->mb_y, currMB->mb_type);\n'
     '#endif\n'
     '\n'
     '    currSlice->writeMB_typeInfo(currMB, &se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '    if ((currMB->mb_type == 0 || currMB->mb_type == BVSP_DIRECT) && currSlice->bVspRefExist)\n'
     '    {\n'
     '      if (!currSlice->direct_spatial_mv_pred_flag && currMB->mb_type == BVSP_DIRECT)\n'
     '        error("view synthesis direct prediction needs spatial direct prediction", 600);\n'
     '      if (currSlice->direct_spatial_mv_pred_flag)\n'
     '      {\n'
     '        se.value1 = currMB->mb_type == BVSP_DIRECT;\n'
     '        se.value2 = 0;\n'
     '        se.type   = SE_MBTYPE;\n'
     '        TRACE_SE (se.tracestring, "mb_direct_type_flag");\n'
     '        writeSE_Flag (&se, dataPart);\n'
     '        no_bits += se.len;\n'
     '      }\n'
     '    }\n'
     '  }\n'
     '  else\n'
     '  {\n'
     '    //Run Length Coding: Skipped macroblock\n'
     '    if (!rdopt && p_Vid->cod_counter == 0)\n'
     '      p_Vid->cod_vsskip = currMB->mb_type == BVSP_DIRECT;\n'
     '    currMB->vsskip_flag = currMB->mb_type == BVSP_DIRECT;\n'
     '    ++(p_Vid->cod_counter);\n'),
    ('    if(FmoGetNextMBNr(p_Vid, currMB->mbAddrX) == -1 && p_Vid->cod_counter > 0)\n'
     '    {\n'
     '      // Put out run\n'
     '      se.value1  = p_Vid->cod_counter;\n'
     '      se.value2  = 0;\n'
     '      se.type    = SE_MBTYPE;\n'
     '\n'
     '      TRACE_SE(se.tracestring, "mb_skip_run");\n'
     '      writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '      no_bits         += se.len;\n'
     '\n'
     '      // Reset cod counter\n'
     '      p_Vid->cod_counter = 0;\n'
     '    }\n'
     '  }\n'
     '  mbBits->mb_mode = mbBits->mb_mode + (unsigned short) no_bits;;\n'
     '\n'
     '  //init NoMbPartLessThan8x8Flag\n'
     '#if EXT3D\n',
     '    if(FmoGetNextMBNr(p_Vid, currMB->mbAddrX) == -1 && p_Vid->cod_counter > 0)\n'
     '    {\n'
     '      // Put out run\n'
     '      se.value1  = p_Vid->cod_counter;\n'
     '      se.value2  = 0;\n'
     '      se.type    = SE_MBTYPE;\n'
     '\n'
     '      TRACE_SE(se.tracestring, "mb_skip_run");\n'
     '      writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '      no_bits         += se.len;\n'
     '      no_bits         += write_skip_type_cavlc(currMB, p_Vid->cod_counter, dataPart);\n'
     '\n'
     '      // Reset cod counter\n'
     '      p_Vid->cod_counter = 0;\n'
     '    }\n'
     '  }\n'
     '  mbBits->mb_mode = mbBits->mb_mode + (unsigned short) no_bits;;\n'
     '\n'
     '  //init NoMbPartLessThan8x8Flag\n'
     '#if EXT3D\n'),
    ('#if EXT3D\n'
     '  if ((currMB->mb_type!=0 && currMB->mb_type != BVSP_DIRECT) || ((currMB->cbp!=0 || currSlice->cmp_cbp[1]!=0 || currSlice->cmp_cbp[2]!=0)))\n',
     '#if EXT3D\n'
     '  if (force_coded || (currMB->mb_type!=0 && currMB->mb_type != BVSP_DIRECT) || ((currMB->cbp!=0 || currSlice->cmp_cbp[1]!=0 || currSlice->cmp_cbp[2]!=0)))\n'),
    # P slice
    ('  // VLC not intra\n'
     '  else if (currMB->mb_type != 0)\n'
     '  {\n'
     '    //===== Run Length Coding: Non-Skipped macroblock =====\n'
     '    se.value1 = p_Vid->cod_counter;\n'
     '    se.value2 = 0;\n'
     '    se.type   = SE_MBTYPE;\n'
     '\n'
     '    TRACE_SE (se.tracestring, "mb_skip_run");\n'
     '    writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n',
     '  // VLC not intra\n'
     '  else if (!skip)\n'
     '  {\n'
     '    //===== Run Length Coding: Non-Skipped macroblock =====\n'
     '    se.value1 = p_Vid->cod_counter;\n'
     '    se.value2 = 0;\n'
     '    se.type   = SE_MBTYPE;\n'
     '\n'
     '    TRACE_SE (se.tracestring, "mb_skip_run");\n'
     '    writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '    no_bits         += write_skip_type_cavlc(currMB, p_Vid->cod_counter, dataPart);\n'),
    ('#if TRACE\n'
     '      snprintf(se.tracestring, TRACESTRING_SIZE, "mb_type (P_SLICE) (%2d,%2d) = %3d",currMB->mb_x, currMB->mb_y, currMB->mb_type);\n'
     '#endif\n'
     '\n'
     '    currSlice->writeMB_typeInfo(currMB, &se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '\n'
     '  }\n'
     '  else\n'
     '  {\n'
     '    //Run Length Coding: Skipped macroblock\n'
     '    ++(p_Vid->cod_counter);\n',
     '#if TRACE\n'
     '      snprintf(se.tracestring, TRACESTRING_SIZE, "mb_type (P_SLICE) (%2d,%2d) = %3d",currMB->mb_x, currMB->mb_y, currMB->mb_type);\n'
     '#endif\n'
     '\n'
     '    currSlice->writeMB_typeInfo(currMB, &se, dataPart);\n'
     '\n'
     '    no_bits         += se.len;\n'
     '    if (1 == currSlice->alc_slice_flag && currMB->mb_type >= 1 && currMB->mb_type <= 3)\n'
     '    {\n'
     '      se.value1  = currMB->is_alc_mode;\n'
     '      se.value2  = 0;\n'
     '      se.type    = SE_MBTYPE;\n'
     '      TRACE_SE (se.tracestring, "mb_alc_flag");\n'
     '      writeSE_Flag (&se, dataPart);\n'
     '      no_bits    += se.len;\n'
     '    }\n'
     '\n'
     '  }\n'
     '  else\n'
     '  {\n'
     '    //Run Length Coding: Skipped macroblock\n'
     '    if (currSlice->alc_slice_flag && currMB->is_alc_mode)\n'
     '      error("adaptive luminance compensation skip in a CAVLC slice", 600);\n'
     '    if (!rdopt && p_Vid->cod_counter == 0)\n'
     '      p_Vid->cod_vsskip = currMB->mb_type == PVSP_SKIP;\n'
     '    else if (!rdopt && p_Vid->cod_vsskip != (currMB->mb_type == PVSP_SKIP))\n'
     '      error("skip types differ within a CAVLC skip run", 600);\n'
     '    currMB->vsskip_flag = currMB->mb_type == PVSP_SKIP;\n'
     '    ++(p_Vid->cod_counter);\n'),
    ('    if(FmoGetNextMBNr(p_Vid, currMB->mbAddrX) == -1 && p_Vid->cod_counter > 0)\n'
     '    {\n'
     '      // Put out run\n'
     '      se.value1  = p_Vid->cod_counter;\n'
     '      se.value2  = 0;\n'
     '      se.type    = SE_MBTYPE;\n'
     '\n'
     '      TRACE_SE(se.tracestring, "mb_skip_run");\n'
     '      writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '      no_bits         += se.len;\n'
     '\n'
     '      // Reset cod counter\n'
     '      p_Vid->cod_counter = 0;\n'
     '    }\n'
     '  }\n'
     '  mbBits->mb_mode = mbBits->mb_mode + (unsigned short) no_bits;;\n'
     '\n'
     '  //init NoMbPartLessThan8x8Flag\n'
     '  currMB->NoMbPartLessThan8x8Flag = TRUE;\n',
     '    if(FmoGetNextMBNr(p_Vid, currMB->mbAddrX) == -1 && p_Vid->cod_counter > 0)\n'
     '    {\n'
     '      // Put out run\n'
     '      se.value1  = p_Vid->cod_counter;\n'
     '      se.value2  = 0;\n'
     '      se.type    = SE_MBTYPE;\n'
     '\n'
     '      TRACE_SE(se.tracestring, "mb_skip_run");\n'
     '      writeSE_UVLC(&se, dataPart);\n'
     '\n'
     '      no_bits         += se.len;\n'
     '      no_bits         += write_skip_type_cavlc(currMB, p_Vid->cod_counter, dataPart);\n'
     '\n'
     '      // Reset cod counter\n'
     '      p_Vid->cod_counter = 0;\n'
     '    }\n'
     '  }\n'
     '  mbBits->mb_mode = mbBits->mb_mode + (unsigned short) no_bits;;\n'
     '\n'
     '  //init NoMbPartLessThan8x8Flag\n'
     '  currMB->NoMbPartLessThan8x8Flag = TRUE;\n'),
    # bvsp_flag: only after a ref_idx that is present
    ('    currSlice->writeMB_Ref_VSP_flag(currMB,list_idx,&se, dataPart);\n',
     '    if (currSlice->symbol_mode == CAVLC)\n'
     '    {\n'
     '      if (currSlice->writeRefFrame[list] == writeSE_Dummy)\n'
     '      {\n'
     '        if (se.value1)\n'
     '          error("block-based view synthesis prediction without a reference index in a CAVLC slice", 600);\n'
     '        se.len = 0;\n'
     '      }\n'
     '      else\n'
     '        writeSE_Flag (&se, dataPart);\n'
     '    }\n'
     '    else\n'
     '    currSlice->writeMB_Ref_VSP_flag(currMB,list_idx,&se, dataPart);\n'),
])

# Mode decision: same skip type within a P run, no ALC skip, no VSP without a coded reference index.
patch('lencod/src/mode_decision.c', [(
    '  enc_mb->valid[PVSP_SKIP] = (short) 0;\n'
    '  if(enc_mb->valid[0] && currSlice->bVspRefExist == TRUE)\n'
    '    enc_mb->valid[PVSP_SKIP] = (short) 1;\n',
    '  enc_mb->valid[PVSP_SKIP] = (short) 0;\n'
    '  if(enc_mb->valid[0] && currSlice->bVspRefExist == TRUE)\n'
    '    enc_mb->valid[PVSP_SKIP] = (short) 1;\n'
    '  if (currSlice->symbol_mode == CAVLC && currSlice->slice_type == P_SLICE && currSlice->bVspRefExist\n'
    '      && p_Vid->cod_counter > 0)\n'
    '  {\n'
    '    if (p_Vid->cod_vsskip)\n'
    '      enc_mb->valid[0] = 0;\n'
    '    else\n'
    '      enc_mb->valid[PVSP_SKIP] = 0;\n'
    '  }\n',
)])
patch('lencod/src/slice.c', [(
    '    // check ALCskip mode\n'
    '    currMB->is_alc_mode    = 1; // turn on ALC mode features (mode validation, MV prediction)\n'
    '    currSlice->check_alc_skip = 1;\n'
    '    check_macroblock_modes(currMB);\n'
    '    currSlice->check_alc_skip = 0;\n'
    '    currMB->mb_type = 0;\n',
    '    // check ALCskip mode\n'
    '    if (currSlice->symbol_mode == CABAC)\n'
    '    {\n'
    '    currMB->is_alc_mode    = 1; // turn on ALC mode features (mode validation, MV prediction)\n'
    '    currSlice->check_alc_skip = 1;\n'
    '    check_macroblock_modes(currMB);\n'
    '    currSlice->check_alc_skip = 0;\n'
    '    currMB->mb_type = 0;\n'
    '    }\n',
)])
gate = ('(numlists==1) // @DT: Bi-VSP support. add numlists == 1\n'
        '            && !( currMB->is_alc_mode && p_Vid->isTextureFirst ) )')
patch('lencod/src/mv_search.c', [
    (gate,
     '(numlists==1) // @DT: Bi-VSP support. add numlists == 1\n'
     '            && !( currMB->is_alc_mode && p_Vid->isTextureFirst )\n'
     '            && !( currSlice->symbol_mode == CAVLC && ( currSlice->num_ref_idx_active[list] <= 1 || currMB->is_alc_mode ) ) )'),
    ('(numlists==1) // @DT: Bi-VSP support. add numlists==1\n'
     '                && !( currMB->is_alc_mode && p_Vid->isTextureFirst ) && blocktype <=SMB8x8 )',
     '(numlists==1) // @DT: Bi-VSP support. add numlists==1\n'
     '                && !( currMB->is_alc_mode && p_Vid->isTextureFirst ) && blocktype <=SMB8x8\n'
     '                && !( currSlice->symbol_mode == CAVLC && ( currSlice->num_ref_idx_active[list] <= 1 || currMB->is_alc_mode ) ) )'),
])

# Test-stream bias: with ATM_VSP_BIAS=n (n > 1) in the environment the mode decision divides the rate-distortion cost
# of view synthesis skip and direct macroblocks and of block-based view synthesis prediction by n, so that streams of
# synthetic sequences, whose depth does not give the disparities of their textures, still use view synthesis
# prediction. The bitstream and the reconstruction stay consistent; only the choices change.
patch('lencod/inc/global.h', [(
    '#include "params.h"\n',
    '#include "params.h"\n'
    'static inline int atm_vsp_bias(void)\n'
    '{\n'
    '  static int bias = -1;\n'
    '  if (bias < 0)\n'
    '  {\n'
    '    const char *value = getenv("ATM_VSP_BIAS");\n'
    '    bias = value ? atoi(value) : 0;\n'
    '  }\n'
    '  return bias;\n'
    '}\n',
)])
patch('lencod/src/rdopt.c', [(
    'int RDCost_for_macroblocks (Macroblock  *currMB,   // <-- Current Macroblock to code\n',
    'static int vsp_bias_of(Macroblock *currMB, short mode)\n'
    '{\n'
    '  PicMotionParams **motion = currMB->p_Vid->enc_picture->mv_info;\n'
    '  int i, j;\n'
    '  if (atm_vsp_bias() <= 1)\n'
    '    return 1;\n'
    '  if (mode == PVSP_SKIP || mode == BVSP_DIRECT)\n'
    '    return atm_vsp_bias();\n'
    '  if (mode < 1 || mode > P8x8)\n'
    '    return 1;\n'
    '  for (j = 0; j < 4; j++)\n'
    '    for (i = 0; i < 4; i++)\n'
    '      if (motion[currMB->block_y + j][currMB->block_x + i].isVSPRef[LIST_0]\n'
    '          || motion[currMB->block_y + j][currMB->block_x + i].isVSPRef[LIST_1])\n'
    '        return atm_vsp_bias();\n'
    '  return 1;\n'
    '}\n'
    '\n'
    'int RDCost_for_macroblocks (Macroblock  *currMB,   // <-- Current Macroblock to code\n',
), (
    '  if ((currSlice->slice_type != I_SLICE) && (p_Inp->BiasSkipRDO == 1) && (mode == 1)',
    '  rdcost /= vsp_bias_of(currMB, mode);\n'
    '\n'
    '  if ((currSlice->slice_type != I_SLICE) && (p_Inp->BiasSkipRDO == 1) && (mode == 1)',
), (
    '  if (distortion > currMB->min_rdcost)\n',
    '  if (distortion / vsp_bias_of(currMB, mode) > currMB->min_rdcost)\n',
)])
text = open(root + '/lencod/src/mv_search.c', newline='').read()
old = 'selectVSP = (disVSP<*m_cost)?1:0;'
assert text.count(old) == 2
first = text.index(old)
text = (text[:first] + 'selectVSP = ((atm_vsp_bias() > 1 ? disVSP / atm_vsp_bias() : disVSP)<*m_cost)?1:0;\n'
        '            if (selectVSP && atm_vsp_bias() > 1)\n'
        '              *m_cost = disVSP / atm_vsp_bias();' + text[first + len(old):])
text = text.replace(old,'selectVSP = ((atm_vsp_bias() > 1 ? disVSP / atm_vsp_bias() : disVSP)<*m_cost)?1:0;')
open(root + '/lencod/src/mv_search.c', 'w', newline='').write(text)
