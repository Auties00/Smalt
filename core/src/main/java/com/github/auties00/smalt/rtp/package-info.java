/**
 * RTP payload formats of realtime codecs: {@link com.github.auties00.smalt.rtp.RtpPacketizer} turns the packets of
 * an elementary stream into RTP packets and {@link com.github.auties00.smalt.rtp.RtpDepacketizer} reassembles them,
 * putting reordered packets back in sequence and reporting loss; {@link com.github.auties00.smalt.rtp.RtpPacket}
 * reads and writes the RFC 3550 packet layout and {@link com.github.auties00.smalt.rtp.RtpPayloadFormat} the SDP
 * description of a payload format.
 *
 * <p>The package implements the payload formats, not RTP sessions: it opens no socket, sends no RTCP and keeps no
 * jitter buffer timing beyond reordering. Encryption (SRTP), retransmission and forward error correction belong to
 * the caller's transport.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.rtp;
