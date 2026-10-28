#!/usr/bin/env python3
"""Writes the committed multi-layer HEVC vectors of smalt-mpeg's HevcMultilayerVectorTest.

The JCT-VC and JCT-3V conformance streams of the scalable and multiview extensions are fetched, never committed (their
licence is unstated), so the tests also decode short streams the SHM reference encoder of those extensions
(TAppEncoder, BSD-3-Clause, used as a black-box encoder) coded from ffmpeg's testsrc pattern. Every stream carries MD5
decoded picture hash SEI messages of each layer; the SHM reference decoder (TAppDecoder) checks every hash while it
decodes the stream, and the script fails unless it reports every picture of every layer as matching and its output is
the encoder's reconstruction. multilayer.tsv next to the streams holds one row per layer:

    name    layer    width    height    frames    stream sha256    frame MD5s

where the comma-separated frame MD5s hash SHM's output of the layer in output order (planar 4:2:0, 8-bit samples, or
16-bit little-endian ones above 8 bits). Layer 0 is decoded with output layer set 0, the layer above it with output
layer set 1, which outputs it. The stream SHA-256 is of the stream that codes the layer: the HEVC stream, or the AVC
stream (.264) of an external base layer, whose frame MD5s hash ffmpeg's decode of it.

    shvc-2x-ld      spatial scalability 2x (64x48 base, 128x96 enhancement), low delay B: luma and chroma
                    resampling and inter-layer motion prediction
    shvc-1.5x-ra    spatial scalability 1.5x (64x48 base, 96x72 enhancement), random access with a CRA picture:
                    the 1.5x phases and the reordered output of both layers
    shvc-snr-8-10   quality scalability with bit depth scalability (8-bit base, 10-bit enhancement, 128x96 both),
                    low delay B
    mvhevc-ld       two views of 128x96 (MV-HEVC, ViewId 0 and 1), low delay B: inter-view prediction; output layer
                    set 1 outputs both views, which ffmpeg's MV-HEVC decoder must decode to the same pictures
    alpha-ld        an alpha auxiliary layer (AuxId 1) over a 128x96 primary layer, low delay B, with an alpha channel
                    information SEI message (transparent 16, opaque 235, no premultiplication)
    shvc-avcbase-2x-ld
                    spatial scalability 2x over an external AVC base layer (hybrid codec scalability, 64x48 x264
                    stream, 128x96 enhancement), low delay B: SHM codes the enhancement layer from ffmpeg's decode of
                    the AVC stream and decodes it with that decode as its base layer pictures

Made with SHM-12.4 (https://vcgit.hhi.fraunhofer.de/jvet/SHM, tag SHM-12.4) compiled with gcc 15 from the sources of
TLibCommon, TLibEncoder, TLibDecoder, TLibVideoIO, TAppCommon, libmd5, TAppEncoder and TAppDecoder (g++ -O2 -w
-fpermissive -std=gnu++11), and ffmpeg 7.1 with libx264 (a black-box encoder of the AVC base layer). Pass the directory holding TAppEncoder and TAppDecoder, the SHM cfg
directory and the output directory:

    python -I tools/testdata/hevc-multilayer/make-vectors.py <SHM bin> <SHM cfg> \\
        <mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/multilayer>
"""
import hashlib
import os
import subprocess
import sys
import tempfile

# the profile, tier and level structures every stream uses: the whole stream, the base layer, the enhancement layer
PTL = ['--Level0=3.1', '--Level1=3', '--Level2=3.1', '--LayerPTLIndex0=1', '--LayerPTLIndex1=2']

# name: (configuration, frames, intra period, (width, height, bit depth) of each layer, options)
VECTORS = {
    'shvc-avcbase-2x-ld': ('encoder_lowdelay_scalable.cfg', 6, -1, [(64, 48, 8), (128, 96, 8)],
                           ['--Profile2=scalable-main', '--RepFormatIdx1=1']),
    'shvc-2x-ld': ('encoder_lowdelay_scalable.cfg', 6, -1, [(64, 48, 8), (128, 96, 8)],
                   ['--Profile2=scalable-main', '--RepFormatIdx1=1']),
    'shvc-1.5x-ra': ('encoder_randomaccess_scalable.cfg', 17, 16, [(64, 48, 8), (96, 72, 8)],
                     ['--Profile2=scalable-main', '--RepFormatIdx1=1']),
    'shvc-snr-8-10': ('encoder_lowdelay_scalable.cfg', 6, -1, [(128, 96, 8), (128, 96, 10)],
                      ['--Profile2=scalable-main10', '--RepFormatIdx1=1', '--InternalBitDepth1=10',
                       '--InternalBitDepthC1=10', '--OutputBitDepth1=10', '--OutputBitDepthC1=10']),
    'mvhevc-ld': ('encoder_lowdelay_scalable.cfg', 6, -1, [(128, 96, 8), (128, 96, 8)],
                  ['--Profile2=multiview-main', '--ScalabilityMask1=1', '--ScalabilityMask2=0', '--ViewOrderIndex0=0',
                   '--ViewOrderIndex1=1', '--ViewId0=0', '--ViewId1=1', '--RepFormatIdx1=0',
                   '--DefaultTargetOutputLayerIdc=0']),
    'alpha-ld': ('encoder_lowdelay_scalable.cfg', 6, -1, [(128, 96, 8), (128, 96, 8)],
                 ['--Profile2=scalable-main', '--ScalabilityMask2=1', '--ScalabilityMask3=1', '--AuxId0=0', '--AuxId1=1',
                  '--SEIAlphaChannelInfo=1', '--SEIAlphaCancelFlag=0', '--SEIAlphaUseIdc=0',
                  '--SEIAlphaTransparentValue=16', '--SEIAlphaOpaqueValue=235', '--RepFormatIdx1=0',
                  '--DefaultTargetOutputLayerIdc=0']),
}


def tool(directory, name):
    path = os.path.abspath(os.path.join(directory, name))
    return path + '.exe' if os.path.exists(path + '.exe') else path


def run(args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit('%s failed:\n%s%s' % (args[0], result.stdout[-3000:], result.stderr[-3000:]))
    return result.stdout


def ffmpeg(*args):
    run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y'] + list(args))


def pixel_format(depth):
    return 'yuv420p' if depth == 8 else 'yuv420p%dle' % depth


def frame_size(width, height, depth):
    return width * height * 3 // 2 * (2 if depth > 8 else 1)


def sources(name, frames, layers, work):
    """Writes the input pictures of each layer and returns their paths."""
    paths = [os.path.join(work, '%s-in%d.yuv' % (name, layer)) for layer in range(len(layers))]
    (width0, height0, depth0), (width1, height1, depth1) = layers
    if name.startswith('mvhevc'):
        # the right view sees the pattern 16 samples further right
        for layer, x in enumerate((0, 16)):
            ffmpeg('-f', 'lavfi', '-i', 'testsrc=size=%dx%d:rate=25' % (width0 + 16, height0), '-frames:v', str(frames),
                   '-vf', 'crop=%d:%d:%d:0' % (width0, height0, x), '-pix_fmt', 'yuv420p', paths[layer])
    elif name.startswith('alpha'):
        ffmpeg('-f', 'lavfi', '-i', 'testsrc=size=%dx%d:rate=25' % (width0, height0), '-frames:v', str(frames),
               '-pix_fmt', 'yuv420p', paths[0])
        # an opaque disc on a transparent background that grows by four samples a picture
        disc = "geq=lum='if(lt(hypot(X-%d\\,Y-%d)\\,20+4*N)\\,235\\,16)':cb=128:cr=128" % (width1 // 2, height1 // 2)
        ffmpeg('-f', 'lavfi', '-i', 'color=black:size=%dx%d:rate=25,format=yuv420p,%s' % (width1, height1, disc),
               '-frames:v', str(frames), '-pix_fmt', 'yuv420p', paths[1])
    else:
        ffmpeg('-f', 'lavfi', '-i', 'testsrc=size=%dx%d:rate=25' % (width1, height1), '-frames:v', str(frames),
               '-pix_fmt', pixel_format(depth1), paths[1])
        ffmpeg('-f', 'lavfi', '-i', 'testsrc=size=%dx%d:rate=25' % (width1, height1), '-frames:v', str(frames),
               '-vf', 'scale=%d:%d:flags=bicubic' % (width0, height0), '-pix_fmt', pixel_format(depth0), paths[0])
    return paths


def avc_base(name, frames, layers, base_input, out_dir, work):
    """Codes the base layer with x264 and returns ffmpeg's decode of the AVC stream and the row of its MD5s."""
    width, height, _ = layers[0]
    stream = os.path.join(out_dir, name + '.264')
    ffmpeg('-f', 'rawvideo', '-pix_fmt', 'yuv420p', '-s', '%dx%d' % (width, height), '-r', '25', '-i', base_input,
           '-frames:v', str(frames), '-c:v', 'libx264', '-preset', 'medium', '-profile:v', 'main', '-bf', '0',
           '-g', '1000', '-qp', '24', '-x264-params', 'scenecut=0', '-bsf:v', 'filter_units=remove_types=6',
           '-f', 'h264', stream)
    decoded = os.path.join(work, name + '-avc.yuv')
    ffmpeg('-f', 'h264', '-i', stream, '-f', 'rawvideo', '-pix_fmt', 'yuv420p', decoded)
    data = open(decoded, 'rb').read()
    size = frame_size(width, height, 8)
    assert len(data) == frames * size, 'ffmpeg wrote %d bytes for the AVC base layer of %s' % (len(data), name)
    md5s = [hashlib.md5(data[i * size:(i + 1) * size]).hexdigest() for i in range(frames)]
    row = '\t'.join([name, '0', str(width), str(height), str(frames),
                     hashlib.sha256(open(stream, 'rb').read()).hexdigest(), ','.join(md5s)])
    return decoded, row


def make(name, shm_bin, cfg, out_dir, work):
    config, frames, intra, layers, options = VECTORS[name]
    inputs = sources(name, frames, layers, work)
    bit = os.path.join(out_dir, name + '.hevc')
    recon = [os.path.join(work, '%s-rec%d.yuv' % (name, layer)) for layer in range(len(layers))]
    external = 'avcbase' in name
    rows = []
    base_options = []
    if external:
        base_decode, row = avc_base(name, frames, layers, inputs[0], out_dir, work)
        rows.append(row)
        base_options = ['-ibl', base_decode]
    command = [tool(shm_bin, 'TAppEncoder'), '-c', os.path.join(cfg, config),
               '-c', os.path.join(cfg, 'layers_avcbase.cfg' if external else 'layers.cfg'),
               '--Profile0=main', '--Profile1=main', '--FramesToBeEncoded=%d' % frames, '--BitstreamFile=' + bit,
               '--SEIDecodedPictureHash=1'] + PTL + base_options
    for layer, (width, height, depth) in enumerate(layers):
        command += ['--InputFile%d=%s' % (layer, inputs[layer]), '--ReconFile%d=%s' % (layer, recon[layer]),
                    '--SourceWidth%d=%d' % (layer, width), '--SourceHeight%d=%d' % (layer, height),
                    '--FrameRate%d=25' % layer, '--InputBitDepth%d=%d' % (layer, depth),
                    '--IntraPeriod%d=%d' % (layer, intra), '--ConformanceMode%d=1' % layer]
    run(command + ['--RepFormatIdx0=0'] + options)
    stream_sha = hashlib.sha256(open(bit, 'rb').read()).hexdigest()
    for layer, (width, height, depth) in enumerate(layers):
        if external and layer == 0:
            continue
        out = os.path.join(work, '%s-dec%d.yuv' % (name, layer))
        log = run([tool(shm_bin, 'TAppDecoder'), '-b', bit, '-olsidx', str(layer), '-o%d' % layer, out]
                  + base_options)
        # the external base layer carries no picture hash
        hashed = frames * (layer if external else layer + 1)
        if log.count('(OK)') != hashed or 'ERROR' in log or 'MISMATCH' in log:
            raise SystemExit('SHM did not verify every picture hash of %s layer %d:\n%s' % (name, layer, log[-3000:]))
        decoded = open(out, 'rb').read()
        if decoded != open(recon[layer], 'rb').read():
            raise SystemExit('SHM decodes %s layer %d to other pictures than the encoder reconstructed' % (name, layer))
        size = frame_size(width, height, depth)
        assert len(decoded) == frames * size, 'SHM wrote %d bytes for %s layer %d' % (len(decoded), name, layer)
        md5s = [hashlib.md5(decoded[i * size:(i + 1) * size]).hexdigest() for i in range(frames)]
        rows.append('\t'.join([name, str(layer), str(width), str(height), str(frames), stream_sha, ','.join(md5s)]))
        if name.startswith('mvhevc'):
            # ffmpeg names the views by ViewId, here equal to the layer identifier
            view = os.path.join(work, '%s-view%d.yuv' % (name, layer))
            ffmpeg('-f', 'hevc', '-i', bit, '-map', '0:v:0:view:%d' % layer, '-f', 'rawvideo', '-pix_fmt', 'yuv420p',
                   view)
            if open(view, 'rb').read() != decoded:
                raise SystemExit('ffmpeg decodes view %d of %s to other pictures than SHM' % (layer, name))
    return rows


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    shm_bin, cfg, out_dir = sys.argv[1:]
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name in VECTORS:
            rows += make(name, shm_bin, cfg, out_dir, work)
    with open(os.path.join(out_dir, 'multilayer.tsv'), 'w', newline='\n') as f:
        f.write('# name\tlayer\twidth\theight\tframes\tstream sha256\tframe MD5s\n')
        for row in rows:
            f.write(row + '\n')


if __name__ == '__main__':
    main()
