package netreliability.compat;

/**
 * What this mod assumes about the game's datagram layout ({@code NetworkPacket(Packet, NetworkInfo)}):
 *
 * <pre>
 * bit 0 of byte 0   split flag (PacketWriter.putNextBoolean writes bit 0 first)
 * if split:         int identifier, u16 fragment index, u16 fragment count
 * if index == 0:    u16 packet type, bool has timestamp, [long timestamp]
 * rest              up to 992 bytes of the packet's content
 * </pre>
 *
 * Only the split flag is read directly from bytes (by the Steam send routing, which sees nothing but the datagram);
 * everything else is read from the game's own parsed {@code NetworkPacket} through {@link GameInternals}.
 */
public final class WireFormat {

    /** The game's own cap on a datagram ({@code NetworkPacket.MAX_PACKET_SIZE}). */
    public static final int MAX_DATAGRAM = 1024;

    private WireFormat() {
    }

    public static boolean isFragment(byte[] datagram) {
        return datagram != null && datagram.length > 0 && (datagram[0] & 1) != 0;
    }
}
