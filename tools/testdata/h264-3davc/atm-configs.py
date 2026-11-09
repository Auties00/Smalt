"""Writes 3DV-ATM 14.0 encoder configurations for the synthetic two-view sequences of atm-inputs.py.

Usage: atm-configs.py <atm configs dir> <out dir> <width> <height> <frames> <entropy 0 cavlc / 1 cabac>
       <coding order> [key=value ...]
Keys given as key=value override both configurations; tex.key=value and dep.key=value only one; acq=1 (default)
gives the camera parameters of the sequence with a depth range per view, acq=2 a depth parameter set per frame
with the depth ranges, translations and ranges of atm-inputs.py.
"""
import re
import shutil
import sys

src, out, width, height, frames, entropy, order = sys.argv[1:8]
width, height, frames = int(width), int(height), int(frames)
extra = sys.argv[8:]
ACQ = '1'
for item in list(extra):
    if item.startswith('acq='):
        ACQ = item[4:]
        extra.remove(item)


def rewrite(path, values):
    text = open(path, encoding='latin-1').read()
    for key, value in values.items():
        pattern = re.compile(r'^(' + re.escape(key) + r')\s*=.*$', re.M)
        if pattern.search(text):
            text = pattern.sub(lambda m: m.group(1) + ' = ' + value, text)
        else:
            text += '\n' + key + ' = ' + value + '\n'
    return text


common = {
    'FramesToBeEncoded': str(frames), 'StartFrame': '0', 'SymbolMode': entropy, 'NumberOfViews': '2',
    'IntraPeriod': '8', 'NumberBFrames': '1', 'HierarchicalCoding': '0', 'SearchRange': '16',
    'NumberReferenceFrames': '4', 'Log2MaxFNumMinus4': '4', 'Log2MaxPOCLsbMinus4': '4', 'QPISlice': '30',
    'QPPSlice': '31', 'QPBSlice': '32', 'GenerateSEIMessage': '0', 'Verbose': '0', 'ReportFrameStats': '0',
}
tex = dict(common, **{
    'SourceWidth': str(width), 'SourceHeight': str(height), 'OutputWidth': str(width), 'OutputHeight': str(height),
    'OriginalWidth': str(width), 'OriginalHeight': str(height), '3DVCodingOrder': '"' + order + '"',
    '3DVConfigFile': 'tex_add.cfg', 'OutputFile': '"test.264"', 'ReconFile1': '"texture_rec.yuv"',
    'TraceFile': '"trace_enc_texture.txt"', 'AlcSearchRangeX': '8', 'AlcSearchRangeY': '4',
})
dep = dict(common, **{
    'SourceWidth': str(width // 2), 'SourceHeight': str(height // 2), 'OutputWidth': str(width // 2),
    'OutputHeight': str(height // 2), 'OriginalWidth': str(width // 2), 'OriginalHeight': str(height // 2),
    '3DVConfigFile': 'dep_add.cfg', 'ReconFile1': '"depth_rec.yuv"', 'TraceFile': '"trace_enc_depth.txt"',
    'ViewSynRDO': '0', 'NormalizeDepth': '0', 'PostDilation': '0',
})
for item in extra:
    key, value = item.split('=', 1)
    if key.startswith('tex.'):
        tex[key[4:]] = value
    elif key.startswith('dep.'):
        dep[key[4:]] = value
    else:
        tex[key] = value
        dep[key] = value
open(out + '/tex.cfg', 'w').write(rewrite(src + '/3davc/encoder_texture.cfg', tex))
open(out + '/dep.cfg', 'w').write(rewrite(src + '/3davc/encoder_depth.cfg', dep))
open(out + '/tex_add.cfg', 'w').write('''ViewCodingOrder    3-1

ViewId             3
InputFile          t3.yuv

ViewId             1
InputFile          t1.yuv
QPOffset           0
ReorderAtAnchor    01
ReorderAtNonAnchor 01
FwdAnchorRefs      1 3
BwdAnchorRefs      0
FwdNonAnchorRefs   1 3
BwdNonAnchorRefs   0
''')
open(out + '/dep_add.cfg', 'w').write('''ViewCodingOrder     3-1
AcquisitionIdc      ''' + ACQ + '''
CameraOrder         1-3
CameraParameterFile cam.txt

ViewId              3
InputFile           d3.yuv
ZNear               448.251214
ZFar                11206.28035
CameraName          param_balloons03
TanslationFile      ''' + ('trans3.txt' if ACQ == '2' else 'NULL') + '''
ZNearFile           ''' + ('znear3.txt' if ACQ == '2' else 'NULL') + '''
ZFarFile            ''' + ('zfar3.txt' if ACQ == '2' else 'NULL') + '''
GridPosX            0
GridPosY            0

ViewId              1
InputFile           d1.yuv
QPOffset            0
ReorderAtAnchor     01
ReorderAtNonAnchor  01
FwdAnchorRefs       1 3
BwdAnchorRefs       0
FwdNonAnchorRefs    1 3
BwdNonAnchorRefs    0
ZNear               612.5
ZFar                9800.0
CameraName          param_balloons01
TanslationFile      ''' + ('trans1.txt' if ACQ == '2' else 'NULL') + '''
ZNearFile           ''' + ('znear1.txt' if ACQ == '2' else 'NULL') + '''
ZFarFile            ''' + ('zfar1.txt' if ACQ == '2' else 'NULL') + '''
GridPosX            0
GridPosY            0
''')
shutil.copy(src + '/cam_param/cam_param_balloons.txt', out + '/cam.txt')
