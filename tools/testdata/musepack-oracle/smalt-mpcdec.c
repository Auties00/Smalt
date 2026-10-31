/* Decodes a Musepack file with libmpcdec (BSD 3-Clause, musepack_src_r475, floating-point build) and writes the
   decoded samples as raw interleaved 32-bit little-endian floats, frame by frame up to the first frame that fails,
   then prints the number of samples per channel.
   Usage: smalt-mpcdec <input.mpc> <output.raw>
   Build: cc -O2 -I<src>/include <src>/libmpcdec/*.c smalt-mpcdec.c -lm -o smalt-mpcdec */
#include <stdio.h>
#include <mpc/mpcdec.h>

int main(int argc, char **argv)
{
    mpc_reader reader;
    mpc_demux *demux;
    mpc_streaminfo info;
    MPC_SAMPLE_FORMAT buffer[MPC_DECODER_BUFFER_LENGTH];
    FILE *out;
    long long total = 0;
    if (argc != 3)
        return 2;
    if (mpc_reader_init_stdio(&reader, argv[1]) != MPC_STATUS_OK)
        return 3;
    demux = mpc_demux_init(&reader);
    if (demux == 0)
        return 4;
    mpc_demux_get_info(demux, &info);
    out = fopen(argv[2], "wb");
    for (;;) {
        mpc_frame_info frame;
        mpc_status status;
        frame.buffer = buffer;
        status = mpc_demux_decode(demux, &frame);
        if (frame.bits == -1 || status != MPC_STATUS_OK)
            break;
        fwrite(buffer, sizeof(MPC_SAMPLE_FORMAT), frame.samples * info.channels, out);
        total += frame.samples;
    }
    fclose(out);
    printf("%lld %u %u %lld\n", total, info.channels, info.sample_freq, (long long) mpc_streaminfo_get_length_samples(&info));
    mpc_demux_exit(demux);
    mpc_reader_exit_stdio(&reader);
    return 0;
}
