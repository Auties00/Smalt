"""Regenerates the H.264 Extended profile test streams and their expected frame hashes.

Usage: python make-vectors.py <JM bin directory> <JM cfg directory> <output directory>

The JM binaries are lencod and ldecod of JM 19.1 patched by patch-jm.py. ffmpeg (7.1) must be on PATH; it makes the
input sequences. The output directory receives <name>.264 for every stream, streams.rows.tsv (the rows of
streams.tsv) and frames.tsv (the MD5 of every frame ldecod outputs, as planar 8-bit 4:2:0), plus a work directory
per stream holding the encoder's reconstruction and the logs.
"""
import hashlib
import os
import subprocess
import sys

jm, cfg, out = (os.path.abspath(path) for path in sys.argv[1:4])
os.makedirs(out, exist_ok=True)
exe = '.exe' if os.name == 'nt' else ''

SOURCES = {
    'src176.yuv': ('testsrc2=size=176x144:rate=25,noise=alls=10:allf=t+u', 30),
    'srcpcm.yuv': ("testsrc2=size=176x144:rate=25,split[a][b];[b]crop=64:144:112:0,noise=alls=100:allf=t+u[n];"
                   "[a][n]overlay=112:0,lutyuv=y='clip(val,16,235)':u='clip(val,16,240)':v='clip(val,16,240)'", 20),
    'srcint.yuv': ('testsrc2=size=176x144:rate=50,tinterlace=mode=merge,noise=alls=8:allf=t+u', 12),
}

STREAMS = [
    ('dp-slices', 'src176.yuv', 12, 'PartitionMode=1 SliceMode=1 SliceArgument=33 NumberBFrames=1 '
                                    'RandomIntraMBRefresh=3 QPISlice=28 QPPSlice=28 QPBSlice=30'),
    ('dp-cip', 'src176.yuv', 10, 'PartitionMode=1 SliceMode=1 SliceArgument=20 NumberBFrames=0 ConstrainedIntraPred=1 '
                                 'RandomIntraMBRefresh=6 QPISlice=24 QPPSlice=26'),
    ('dp-pcm', 'srcpcm.yuv', 5, 'PartitionMode=1 SliceMode=1 SliceArgument=30 NumberBFrames=1 RandomIntraMBRefresh=4 '
                                'QPISlice=12 QPPSlice=14 QPBSlice=16'),
    ('dp-mbaff', 'srcint.yuv', 8, 'PartitionMode=1 MbInterlace=2 NumberBFrames=1 ReferenceReorder=0 '
                                  'PocMemoryManagement=0 RandomIntraMBRefresh=3 QPISlice=26 QPPSlice=27 QPBSlice=29'),
    ('dp-field', 'srcint.yuv', 8, 'PartitionMode=1 PicInterlace=1 NumberBFrames=1 SliceMode=1 SliceArgument=24 '
                                  'QPISlice=26 QPPSlice=27 QPBSlice=29'),
    ('sp', 'src176.yuv', 12, 'SPPicturePeriodicity=2 NumberBFrames=0 QPISlice=28 QPPSlice=28 QPSPSlice=30 '
                             'QPSP2Slice=27 RandomIntraMBRefresh=2'),
    ('sp-dp-chroma', 'src176.yuv', 12, 'SPPicturePeriodicity=1 NumberBFrames=1 PartitionMode=1 SliceMode=1 '
                                       'SliceArgument=40 ChromaQPOffset=4 QPISlice=26 QPPSlice=27 QPBSlice=29 '
                                       'QPSPSlice=29 QPSP2Slice=31 RandomIntraMBRefresh=2'),
    ('sp-qp', 'src176.yuv', 10, 'SPPicturePeriodicity=2 NumberBFrames=0 UseRDOQuant=1 RDOQ_QP_Num=5 RDOQ_CP_Mode=0 '
                                'RDOQ_CP_MV=0 QPISlice=28 QPPSlice=28 QPSPSlice=30 QPSP2Slice=28 ChromaQPOffset=-3 '
                                'RandomIntraMBRefresh=1'),
    ('sp-mbaff-dp', 'srcint.yuv', 8, 'SPPicturePeriodicity=2 MbInterlace=2 ReferenceReorder=0 PocMemoryManagement=0 '
                                     'PartitionMode=1 NumberBFrames=0 RandomIntraMBRefresh=2 QPISlice=27 QPPSlice=27 '
                                     'QPSPSlice=29 QPSP2Slice=26'),
    ('sp-coefficients', 'src176.yuv', 10, 'SPPicturePeriodicity=3 NumberBFrames=0 QPISlice=30 QPPSlice=30 '
                                          'QPSPSlice=32 QPSP2Slice=29 SP_output=1 '
                                          'SP_output_name=sp-coefficients.dat'),
    ('sp-switch', 'src176.yuv', 10, 'SPPicturePeriodicity=3 NumberBFrames=0 QPISlice=26 QPPSlice=26 QPSPSlice=28 '
                                    'QPSP2Slice=29 SP2_FRAMES=1 SP2_input_name1=sp-coefficients.dat '
                                    'SP2_input_name2=sp-coefficients.dat'),
    ('si', 'src176.yuv', 10, 'SPPicturePeriodicity=3 SI_FRAMES=1 NumberBFrames=0 QPISlice=26 QPPSlice=26 '
                             'QPSPSlice=28 QPSISlice=28 QPSP2Slice=29 SP2_input_name1=sp-coefficients.dat '
                             'SP2_input_name2=sp-coefficients.dat'),
]

WIDTH, HEIGHT = 176, 144
SIZE = WIDTH * HEIGHT * 3 // 2

for name, (graph, frames) in SOURCES.items():
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', graph, '-frames:v',
                    str(frames), '-pix_fmt', 'yuv420p', '-f', 'rawvideo', os.path.join(out, name)], check=True)

rows = []
golden = []
for name, source, frames, parameters in STREAMS:
    work = os.path.join(out, name + '.work')
    os.makedirs(work, exist_ok=True)
    stream = os.path.join(out, name + '.264')
    args = [os.path.join(jm, 'lencod' + exe), '-d', os.path.join(cfg, 'encoder_extended.cfg'),
            '-p', 'InputFile=' + os.path.join(out, source), '-p', 'SourceWidth=%d' % WIDTH,
            '-p', 'SourceHeight=%d' % HEIGHT, '-p', 'OutputWidth=%d' % WIDTH, '-p', 'OutputHeight=%d' % HEIGHT,
            '-p', 'FramesToBeEncoded=%d' % frames, '-p', 'OutputFile=' + stream,
            '-p', 'ReconFile=' + os.path.join(work, 'rec.yuv'), '-p', 'StatsFile=' + os.path.join(work, 'stats.dat'),
            '-p', 'LevelIDC=30']
    for parameter in parameters.split():
        key, value = parameter.split('=', 1)
        if value.endswith('.dat'):
            args += ['-p', key + '=' + os.path.join(out, value)]
        else:
            args += ['-p', parameter]
    encoder = subprocess.run(args, cwd=work, capture_output=True, text=True)
    open(os.path.join(work, 'enc.log'), 'w').write(encoder.stdout + encoder.stderr)
    if encoder.returncode != 0 or not os.path.exists(stream):
        raise SystemExit(name + ': lencod failed, see ' + work)
    decoded = os.path.join(work, 'dec.yuv')
    decoder = subprocess.run([os.path.join(jm, 'ldecod' + exe), '-i', stream, '-o', decoded], cwd=work,
                             capture_output=True, text=True)
    open(os.path.join(work, 'dec.log'), 'w').write(decoder.stdout + decoder.stderr)
    if decoder.returncode != 0:
        raise SystemExit(name + ': ldecod failed, see ' + work)
    data = open(decoded, 'rb').read()
    count = len(data) // SIZE
    for index in range(count):
        golden.append('%s\t%d\t%s' % (name, index, hashlib.md5(data[index * SIZE:(index + 1) * SIZE]).hexdigest()))
    content = open(stream, 'rb').read()
    rows.append('\t'.join([name, hashlib.sha256(content).hexdigest(), str(len(content)),
                           '%dx%d, %d frames' % (WIDTH, HEIGHT, count), parameters]))
    print(rows[-1])

open(os.path.join(out, 'streams.rows.tsv'), 'w', newline='\n').write('\n'.join(rows) + '\n')
open(os.path.join(out, 'frames.tsv'), 'w', newline='\n').write('\n'.join(golden) + '\n')
