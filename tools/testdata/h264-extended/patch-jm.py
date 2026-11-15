"""Patches the JM 19.1 reference software (jvet/JM master) into the oracle of the H.264 Extended profile tests.

Usage: python patch-jm.py <JM source root>

Build fixes for MinGW gcc (the Windows headers' MSVC keywords, the -Werror of the build environment), then the
decoder fixes the expected frame hashes of mpeg/src/test/resources/.../video/h264/extended rely on, each where
ldecod departs from ITU-T H.264:
  - read_comp_cavlc.c, read_comp_cabac.c: the chroma DC levels of SP and SI macroblocks are stored transposed
    (level 1 where block 2's DC belongs), which itrans_sp_cr then pairs with the wrong prediction DC (8.6.1.2);
  - block.c: itrans_sp and itrans_sp_cr recover the levels and scale the residual with the slice QP instead of the
    macroblock's QPY and QPC, and derive QSC without chroma_qp_index_offset (8.6.1, 8.5.8);
  - mb_read.c: Intra_4x4 modes are predicted as if SI neighbors of SI slices were unavailable (8.3.1.1 has no
    such rule), and SI macroblocks read no prev_intra4x4_pred_mode_flag (7.3.5.1);
  - macroblock.c, mb_prediction.c: the luma of SI macroblocks is never reconstructed, and must go through the SP
    and SI transform (8.6.2.1).
The SI fixes make ldecod agree with the model of make-si-streams.py on si-model; ldecod still lacks the SI rule of
constrained_intra_pred_flag (8.3.1.2), so no expected hash comes from it for SI macroblocks.

Build afterwards with CMake: cmake <root> -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_STANDARD_LIBRARIES=-lws2_32

The JM source lines quoted below are part of the JM software, copyright ISO/IEC 2004-2008 and the ITU (2001),
distributed under the terms of its COPYRIGHT_ISO_IEC.txt ("ISO/IEC gives users free license to this software module
or modifications thereof") and COPYRIGHT_ITU.txt.
"""
import sys

root = sys.argv[1]


def patch(name, pairs, count=1):
    path = root + '/' + name
    text = open(path, newline='').read()
    for old, new in pairs:
        found = text.count(old)
        if found < count:
            raise SystemExit('%s: expected text not found: %r' % (name, old[:80]))
        text = text.replace(old, new)
    open(path, 'w', newline='').write(text)


patch('source/lib/lcommon/win32.h', [
    ('#if (_MSC_VER < 1400)', '#if defined(_MSC_VER) && (_MSC_VER < 1400)'),
    ('# define  snprintf _snprintf\n', '#ifdef _MSC_VER\n# define  snprintf _snprintf\n#endif\n'),
    ('# define  inline   _inline\n# define  forceinline __forceinline\n',
     '#ifdef _MSC_VER\n# define  inline   _inline\n# define  forceinline __forceinline\n#else\n'
     '# define  forceinline inline\n#endif\n'),
])
patch('cmake/CMakeBuild/cmake/modules/BBuildEnv.cmake', [
    ('list( APPEND _bb_warning_options "-Werror" )', '#nowerror'),
])
patch('source/app/ldecod/read_comp_cavlc.c', [
    ('currSlice->cof[PLANE_U + uv][0][4] = currSlice->cofu[1];',
     'currSlice->cof[PLANE_U + uv][0][4] = currSlice->cofu[smb ? 2 : 1];'),
    ('currSlice->cof[PLANE_U + uv][4][0] = currSlice->cofu[2];',
     'currSlice->cof[PLANE_U + uv][4][0] = currSlice->cofu[smb ? 1 : 2];'),
])
patch('source/app/ldecod/read_comp_cabac.c', [
    ('currSlice->cof[uv + 1][0][4] = currSlice->cofu[1];', 'currSlice->cof[uv + 1][0][4] = currSlice->cofu[smb ? 2 : 1];'),
    ('currSlice->cof[uv + 1][4][0] = currSlice->cofu[2];', 'currSlice->cof[uv + 1][4][0] = currSlice->cofu[smb ? 1 : 2];'),
])
patch('source/app/ldecod/block.c', [
    ('  int qp = (currSlice->slice_type == SI_SLICE) ? currSlice->qs : currSlice->qp;\n', '  int qp = currMB->qp;\n'),
    ('  qp_per    = p_Vid->qp_per_matrix[ ((currSlice->qp < 0 ? currSlice->qp : QP_SCALE_CR[currSlice->qp]))];\n'
     '  qp_rem    = p_Vid->qp_rem_matrix[ ((currSlice->qp < 0 ? currSlice->qp : QP_SCALE_CR[currSlice->qp]))];\n'
     '\n'
     '  qp_per_sp = p_Vid->qp_per_matrix[ ((currSlice->qs < 0 ? currSlice->qs : QP_SCALE_CR[currSlice->qs]))];\n'
     '  qp_rem_sp = p_Vid->qp_rem_matrix[ ((currSlice->qs < 0 ? currSlice->qs : QP_SCALE_CR[currSlice->qs]))];\n',
     '  qp_per    = p_Vid->qp_per_matrix[ currMB->qp_scaled[uv + 1] ];\n'
     '  qp_rem    = p_Vid->qp_rem_matrix[ currMB->qp_scaled[uv + 1] ];\n'
     '\n'
     '  {\n'
     '    int qsi = iClip3(0, 51, currSlice->qs + currSlice->dec_picture->chroma_qp_offset[uv]);\n'
     '    qp_per_sp = p_Vid->qp_per_matrix[ QP_SCALE_CR[qsi] ];\n'
     '    qp_rem_sp = p_Vid->qp_rem_matrix[ QP_SCALE_CR[qsi] ];\n'
     '  }\n'),
    ('  if (currSlice->slice_type == SI_SLICE)\n'
     '  {\n'
     '    qp_per = qp_per_sp;\n'
     '    qp_rem = qp_rem_sp;\n'
     '  }\n', ''),
])
patch('source/app/ldecod/mb_read.c', [
    ('        if (currSlice->slice_type == SI_SLICE)           // need support for MBINTLC1\n', '        if (0)\n'),
    ('    else if (currMB->mb_type == I4MB)\n      read_ipred_4x4_modes_mbaff(currMB);',
     '    else if (currMB->mb_type == I4MB || currMB->mb_type == SI4MB)\n      read_ipred_4x4_modes_mbaff(currMB);'),
    ('  else if (currMB->mb_type == I4MB)\n    read_ipred_4x4_modes(currMB);',
     '  else if (currMB->mb_type == I4MB || currMB->mb_type == SI4MB)\n    read_ipred_4x4_modes(currMB);'),
])
patch('source/app/ldecod/mb_prediction.c', [
    ('  currMB->itrans_4x4 = (currMB->is_lossless == FALSE) ? itrans4x4 : Inv_Residual_trans_4x4;    \n\n'
     '  for (block8x8 = 0; block8x8 < 4; block8x8++)\n  {\n    for (k = block8x8 * 4; k < block8x8 * 4 + 4; k ++)',
     '  currMB->itrans_4x4 = (currMB->mb_type == SI4MB) ? itrans_sp : (currMB->is_lossless == FALSE) ? itrans4x4 : '
     'Inv_Residual_trans_4x4;    \n\n'
     '  for (block8x8 = 0; block8x8 < 4; block8x8++)\n  {\n    for (k = block8x8 * 4; k < block8x8 * 4 + 4; k ++)'),
])
lines = open(root + '/source/app/ldecod/macroblock.c', newline='').read().split('\n')
start = next(i for i, line in enumerate(lines) if line.startswith('static int decode_one_component_i_slice('))
for i in range(start, start + 20):
    if lines[i].strip() == 'else if (currMB->mb_type == I4MB)':
        lines[i] = lines[i].replace('currMB->mb_type == I4MB', 'currMB->mb_type == I4MB || currMB->mb_type == SI4MB')
        break
else:
    raise SystemExit('macroblock.c: decode_one_component_i_slice not found')
open(root + '/source/app/ldecod/macroblock.c', 'w', newline='').write('\n'.join(lines))
print('patched', root)
