#!/usr/bin/env python3
"""Builds the 3GPP reference AMR decoders that smalt-mpeg's AmrDtxReceptionTest compares with.

The bit-exact ANSI-C simulations of AMR-NB (TS 26.073) and AMR-WB (TS 26.173) are downloaded from the 3GPP
archive (pinned by SHA-256) and their decoders compiled with gcc, unchanged, as black-box oracles:

  smalt-amrnb-ref   the TS 26.073 decoder built with MMS_IO, so it reads the RFC 4867 storage format
                    ("decoder in.amr out.raw")
  smalt-amrwb-ref   the TS 26.173 decoder ("decoder -mime in.amr out.raw")

Both honor the quality bit of the frame header (a frame with the bit clear is a bad frame), which ffmpeg's
libopencore decoders ignore, and decode the comfort noise and concealment of the storage format as the
specifications define them. The executables go to <work directory>/bin; put that directory on PATH (or pass
-Dsmalt.amr.reference=<work directory>/bin) to run the comparison.

    python -I tools/testdata/amr-oracle.py <work directory>
"""
import hashlib
import os
import subprocess
import sys
import urllib.request
import zipfile

ARCHIVES = {
    'nb': ('https://www.3gpp.org/ftp/Specs/archive/26_series/26.073/26073-j00.zip',
           '8e37c063f1aba04bd226d71595b09fa65804329d267b09f347b2eb94459046fa', '26073-i00_ANSI_C_source_code.zip'),
    'wb': ('https://www.3gpp.org/ftp/Specs/archive/26_series/26.173/26173-j00.zip',
           '738251c4fba4aee33f7af856752d3081a3f54b20d1b977c2e04096ce0bb9ce4c', '26173-i00_ANSI-C_source_code.zip'),
}

# the decoder objects of the TS 26.073 makefile: decoder.c, the speech codec library and the basic operators
NB_SOURCES = ['decoder.c'] + ('agc autocorr az_lsp bits2prm cl_ltp convolve c1035pf d_plsf d_plsf_5 d_gain_c d_gain_p '
                              'dec_lag6 d1035pf cor_h enc_lag3 enc_lag6 g_code g_pitch int_lpc inter_36 inv_sqrt '
                              'lag_wind levinson lsp_az lsp_lsf ol_ltp pitch_fr pitch_ol pow2 pre_big pre_proc pred_lt '
                              'preemph prm2bits pstfilt q_gain_c q_gain_p q_plsf q_plsf_5 lsfwt reorder residu lsp lpc '
                              'ec_gains spreproc syn_filt weight_a qua_gain gc_pred q_plsf_3 post_pro dec_lag3 dec_gain '
                              'd_plsf_3 d4_17pf c4_17pf d3_14pf c3_14pf d2_11pf c2_11pf d2_9pf c2_9pf cbsearch spstproc '
                              'gain_q cod_amr dec_amr sp_enc sp_dec ph_disp g_adapt calc_en qgain795 qgain475 sqrt_l '
                              'set_sign s10_8pf bgnscd gmed_n mac_32 ex_ctrl c_g_aver lsp_avg int_lsf c8_31pf d8_31pf '
                              'p_ol_wgh ton_stab vad1 dtx_enc dtx_dec a_refl b_cn_cod calc_cor hp_max vadname vad2 r_fft '
                              'lflg_upd e_homing d_homing basicop2 count oper_32b copy log2 set_zero strfunc n_proc '
                              'sid_sync').split()

# the decoder objects of the TS 26.173 makefile.gcc
WB_SOURCES = ('decoder agc2 autocorr az_isp bits c2t64fx c4t64fx cod_main convolve cor_h_x d2t64fx d4t64fx decim54 '
              'dec_main deemph dtx d_gain2 gpclip g_pitch hp6k hp7k hp50 hp400 hp_wsp int_lpc isfextrp isp_az isp_isf '
              'lagconc lag_wind levinson lp_dec2 math_op ph_disp pitch_f4 pit_shrp pred_lt4 preemph p_med_ol qisf_ns '
              'qpisf_2s q_gain2 q_pulse random residu scale syn_filt updt_tar util voicefac wb_vad weight_a basicop2 '
              'count log2 oper_32b homing').split()


def fetch(url, target, expected):
    if not os.path.exists(target):
        # the 3GPP server refuses requests without a browser user agent
        request = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(request) as response, open(target + '.part', 'wb') as out:
            out.write(response.read())
        os.replace(target + '.part', target)
    digest = hashlib.sha256(open(target, 'rb').read()).hexdigest()
    if digest != expected:
        raise SystemExit(target + ' has SHA-256 ' + digest + ', expected ' + expected)


def extract(work, codec):
    url, digest, inner = ARCHIVES[codec]
    download = os.path.join(work, 'download', codec)
    os.makedirs(download, exist_ok=True)
    archive = os.path.join(download, url.rsplit('/', 1)[1])
    fetch(url, archive, digest)
    source = os.path.join(download, 'source')
    if not os.path.isdir(source):
        with zipfile.ZipFile(archive) as outer:
            outer.extract(inner, download)
        with zipfile.ZipFile(os.path.join(download, inner)) as code:
            code.extractall(source)
    for root, directories, files in os.walk(source):
        if 'decoder.c' in files:
            return root
    raise SystemExit('no decoder.c in ' + source)


def compile_decoder(code, sources, defines, output):
    # __MSDOS__ selects the little-endian PC platform of typedefs.h; the code predates C23's bool keywords
    command = ['gcc', '-std=gnu99', '-O2', '-w', '-D__MSDOS__', '-I' + code] + ['-D' + define for define in defines] + [
        os.path.join(code, source if source.endswith('.c') else source + '.c') for source in sources] + ['-o', output]
    subprocess.check_call(command)
    if not os.path.exists(output) and not os.path.exists(output + '.exe'):
        raise SystemExit('the build produced no ' + output)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    work = os.path.abspath(sys.argv[1])
    binary = os.path.join(work, 'bin')
    os.makedirs(binary, exist_ok=True)
    compile_decoder(extract(work, 'nb'), NB_SOURCES, ['WMOPS=0', 'VAD1', 'MMS_IO'],
                    os.path.join(binary, 'smalt-amrnb-ref'))
    compile_decoder(extract(work, 'wb'), ['decoder.c' if name == 'decoder' else name for name in WB_SOURCES],
                    ['WMOPS=0'], os.path.join(binary, 'smalt-amrwb-ref'))
    print('built the reference decoders in ' + binary)


if __name__ == '__main__':
    main()
