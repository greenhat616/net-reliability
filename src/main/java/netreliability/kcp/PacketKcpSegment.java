package netreliability.kcp;

import necesse.engine.network.Packet;

/**
 * Carrier for raw KCP datagrams. KCP output travels as an ordinary single-datagram game packet of this type, so the
 * game's own receive loops parse it without errors and hand it to {@code PacketManager.submitInPacket}, where
 * {@link KcpTransport} takes it out before the game processes it. The content is the KCP bytes, unframed.
 *
 * <p>If it ever reaches the game's packet handling (KCP code failed to intercept it) it does nothing.
 */
public class PacketKcpSegment extends Packet {

    public final byte[] raw;

    public PacketKcpSegment(byte[] data) {
        super(data);
        this.raw = data;
    }
}
