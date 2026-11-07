"""Writes a small 3D-AVC (ITU-T H.264 Annex I, profile_idc 139) Annex B stream from scratch.

The stream has two views with a texture and a depth view each, 32x16 samples, two access units: I_PCM base view
components with fixed sample patterns, and skipped P view components predicted from the other view or the previous
access unit. It exercises the 3D-AVC subset sequence parameter set, a depth parameter set with a non-linear depth
representation, the 3D-AVC NAL unit header, slice header prediction from every source and the closing slice header
fields, in the layout the 3DV-ATM reference decoder (ITU-T H.264.2) reads.

Usage: python -I make-stream.py <output.264> [<fuzz seed output>]
"""
import sys


class Bits:
    def __init__(self):
        self.bits = []

    def u(self, n, value):
        for i in range(n - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def ue(self, value):
        value += 1
        n = value.bit_length()
        self.u(n - 1, 0)
        self.u(n, value)

    def se(self, value):
        self.ue(2 * value - 1 if value > 0 else -2 * value)

    def align_zero(self):
        while len(self.bits) % 8:
            self.bits.append(0)

    def trailing(self):
        self.bits.append(1)
        self.align_zero()

    def bytes(self):
        assert len(self.bits) % 8 == 0
        out = bytearray()
        for i in range(0, len(self.bits), 8):
            byte = 0
            for bit in self.bits[i:i + 8]:
                byte = byte << 1 | bit
            out.append(byte)
        return bytes(out)


def nal(header, rbsp):
    out = bytearray(b'\x00\x00\x00\x01')
    out += header
    zeros = 0
    for byte in rbsp:
        if zeros >= 2 and byte <= 3:
            out.append(3)
            zeros = 0
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


WIDTH_MBS, HEIGHT_MBS = 2, 1


def sps_data(b, profile, sps_id):
    b.u(8, profile)
    b.u(8, 0)
    b.u(8, 30)
    b.ue(sps_id)
    b.ue(1)          # chroma_format_idc
    b.ue(0)          # bit_depth_luma_minus8
    b.ue(0)          # bit_depth_chroma_minus8
    b.u(1, 0)        # qpprime_y_zero_transform_bypass_flag
    b.u(1, 0)        # seq_scaling_matrix_present_flag
    b.ue(0)          # log2_max_frame_num_minus4
    b.ue(2)          # pic_order_cnt_type
    b.ue(2)          # max_num_ref_frames
    b.u(1, 0)        # gaps_in_frame_num_value_allowed_flag
    b.ue(WIDTH_MBS - 1)
    b.ue(HEIGHT_MBS - 1)
    b.u(1, 1)        # frame_mbs_only_flag
    b.u(1, 1)        # direct_8x8_inference_flag
    b.u(1, 0)        # frame_cropping_flag
    b.u(1, 0)        # vui_parameters_present_flag


def sps():
    b = Bits()
    sps_data(b, 100, 0)
    b.trailing()
    return nal(b'\x67', b.bytes())


def subset_sps(sps_id):
    b = Bits()
    sps_data(b, 139, sps_id)
    b.u(1, 1)        # bit_equal_to_one
    b.ue(1)          # num_views_minus1
    b.ue(0)          # view_id[0]
    b.ue(1)          # view_id[1]
    for _ in range(2):   # anchor, then non-anchor references of view 1
        b.ue(1)
        b.ue(0)
        b.ue(0)
    b.ue(0)          # num_level_values_signalled_minus1
    b.u(8, 30)
    b.ue(0)          # num_applicable_ops_minus1
    b.u(3, 0)        # applicable_op_temporal_id
    b.ue(0)          # applicable_op_num_target_views_minus1
    b.ue(1)          # applicable_op_target_view_id
    b.u(1, 1)        # applicable_op_depth_flag
    b.u(1, 1)        # applicable_op_texture_flag
    b.ue(1)          # applicable_op_num_texture_views_minus1
    b.ue(2)          # applicable_op_num_depth_views
    b.u(1, 0)        # mvcd_vui_parameters_present_flag
    b.u(1, 0)        # texture_vui_parameters_present_flag
    b.u(2, 1)        # 3dv_acquisition_idc
    b.ue(0)          # camera view_id of each entry
    b.ue(1)
    b.ue(5)          # disparity parameter precision
    depth_ranges(b)
    b.u(1, 1)        # disparity parameters present
    for value in (20, -3, 40, 2):   # vsp_param() of the view pair (0, 1)
        b.se(value)
    b.u(1, 0)        # reduced_resolution_flag
    b.u(1, 0)        # depth_frame_cropping_flag
    b.ue(0)          # grid_pos_num_views
    b.u(1, 1)        # slice_header_prediction_flag
    b.u(1, 0)        # seq_view_synthesis_flag
    b.u(1, 0)        # alc_sps_enable_flag
    b.u(1, 0)        # enable_rle_skip_flag
    b.u(1, 0)        # additional_extension2_flag
    b.trailing()
    return nal(b'\x6f', b.bytes())


def pps(pps_id, sps_id):
    b = Bits()
    b.ue(pps_id)
    b.ue(sps_id)
    b.u(1, 0)        # entropy_coding_mode_flag
    b.u(1, 0)        # bottom_field_pic_order_in_frame_present_flag
    b.ue(0)          # num_slice_groups_minus1
    b.ue(0)          # num_ref_idx_l0_default_active_minus1
    b.ue(0)          # num_ref_idx_l1_default_active_minus1
    b.u(1, 0)        # weighted_pred_flag
    b.u(2, 0)        # weighted_bipred_idc
    b.se(0)          # pic_init_qp_minus26
    b.se(0)          # pic_init_qs_minus26
    b.se(0)          # chroma_qp_index_offset
    b.u(1, 1)        # deblocking_filter_control_present_flag
    b.u(1, 0)        # constrained_intra_pred_flag
    b.u(1, 0)        # redundant_pic_cnt_present_flag
    b.trailing()
    return nal(b'\x68', b.bytes())


def depth_ranges(b):
    b.u(1, 1)        # z_near_flag
    b.u(1, 1)        # z_far_flag
    for exponent, mantissa in ((30, 5), (36, 9)):
        b.u(1, 1)    # element_equal_flag (z_near, then z_far)
        b.u(5, 3)    # mantissa_len_minus1
        b.u(1, 0)    # sign0
        b.u(7, exponent)
        b.u(4, mantissa)


def dps():
    b = Bits()
    b.ue(1)          # depth_parameter_set_id
    b.u(2, 2)        # pred_direction
    b.ue(1)          # num_depth_views_minus1
    depth_ranges(b)
    b.u(1, 0)        # vsp_param_flag
    b.u(1, 0)        # depth_param_additional_extension_flag
    b.ue(2)          # nonlinear_depth_representation_num
    b.ue(12)         # nonlinear_depth_representation_model[1]
    b.ue(5)          # nonlinear_depth_representation_model[2]
    b.trailing()
    return nal(b'\x10', b.bytes())


def prefix(idr):
    # nal_unit_header_mvc_extension of the base view: svc 0, non_idr, priority 0, view_id 0, temporal_id 0,
    # anchor, inter_view 1, reserved_one_bit 1
    value = (0 if idr else 1) << 22 | (1 if idr else 0) << 2 | 1 << 1 | 1
    return nal(bytes([0x6e]) + value.to_bytes(3, 'big'), b'')


def header3d(view_id, depth, idr):
    value = 1 << 15 | view_id << 7 | depth << 6 | (0 if idr else 1) << 5 | (1 if idr else 0) << 1 | 1
    return bytes([0x75]) + value.to_bytes(2, 'big')


def pcm(b, seed, chroma):
    b.ue(25)         # mb_type I_PCM
    b.align_zero()
    for i in range(256):
        b.u(8, (seed * 37 + i * 11 + (i >> 4) * 7) & 0xFF)
    for i in range(128):
        b.u(8, 128 if not chroma else (seed * 13 + i * 5) & 0xFF)


def base_texture(idr, frame_num):
    b = Bits()
    b.ue(0)
    b.ue(7 if idr else 5)    # I, or P
    b.ue(0)
    b.u(4, frame_num)
    if idr:
        b.ue(0)              # idr_pic_id
    if not idr:
        b.u(1, 0)            # num_ref_idx_active_override_flag
        b.u(1, 0)            # ref_pic_list_modification_flag_l0
    if idr:
        b.u(1, 0)            # no_output_of_prior_pics_flag
        b.u(1, 0)            # long_term_reference_flag
    else:
        b.u(1, 0)            # adaptive_ref_pic_marking_mode_flag
    b.se(0)                  # slice_qp_delta
    b.ue(1)                  # disable_deblocking_filter_idc
    if idr:
        pcm(b, 1, True)
        pcm(b, 2, True)
    else:
        b.ue(2)              # mb_skip_run
    b.trailing()
    return prefix(idr) + nal(bytes([0x65 if idr else 0x61]), b.bytes())


def predicted(view_id, depth, idr, slice_type, src, lists_src, marking_src, data):
    b = Bits()
    b.ue(0)
    b.ue(slice_type)
    b.ue(1 if depth else 0)  # the texture views share the base view's set, the depth views have their own
    b.u(2, src)              # pre_slice_header_src
    if slice_type % 5 != 2:
        b.u(2, lists_src)    # pre_ref_lists_src
        if lists_src == 0:
            b.u(1, 0)        # num_ref_idx_active_override_flag
            b.u(1, 0)        # ref_pic_list_modification_flag_l0
    b.u(2, marking_src)      # pre_dec_ref_pic_marking_src
    if marking_src == 0:
        b.u(1, 0)
        if idr:
            b.u(1, 0)
    b.se(-2 if depth else 1)  # slice_qp_delta
    if slice_type % 5 != 2:
        if depth:
            b.u(1, 0)        # depth_weighted_pred_flag
        else:
            b.u(1, 0)        # dmvp_flag
    if depth:
        b.u(1, 1)            # adaptive depth representation flag of 3DV-ATM
    data(b)
    b.trailing()
    return nal(header3d(view_id, depth, idr), b.bytes())


def skipped(b):
    b.ue(2)                  # mb_skip_run


def depth_pcm(seed):
    def write(b):
        pcm(b, seed, False)
        pcm(b, seed + 1, False)
    return write


def stream():
    out = sps() + subset_sps(0) + pps(0, 0) + subset_sps(1) + pps(1, 1)
    out += base_texture(True, 0) + dps()
    out += predicted(0, 1, True, 7, 2, 0, 2, depth_pcm(5))
    out += predicted(1, 0, True, 5, 1, 1, 1, skipped)
    out += predicted(1, 1, True, 5, 1, 2, 2, skipped)
    out += base_texture(False, 1)
    out += predicted(0, 1, False, 5, 2, 2, 0, skipped)
    out += predicted(1, 0, False, 5, 3, 3, 3, skipped)
    out += predicted(1, 1, False, 5, 2, 1, 1, skipped)
    return out


def seed(data):
    marker = b'FUZZ-TAG'
    header = bytes([2]) + (32).to_bytes(2, 'big') + (16).to_bytes(2, 'big')
    return header + marker + b'\x01' + data


if __name__ == '__main__':
    data = stream()
    with open(sys.argv[1], 'wb') as handle:
        handle.write(data)
    if len(sys.argv) > 2:
        with open(sys.argv[2], 'wb') as handle:
            handle.write(seed(data))
