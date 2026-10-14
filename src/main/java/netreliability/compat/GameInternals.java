package netreliability.compat;

import java.lang.reflect.Field;
import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;

/**
 * The only place that touches private game members. Everything here is looked up once by {@link #verify()}; if a
 * game update renamed or retyped any of it, {@link #isAvailable()} turns false, the features that need it switch off
 * and the game runs with vanilla networking. Each member is listed in {@code docs/GAME-INTERNALS.md}, and
 * {@code tools/check-internals.sh} checks them against the installed jars.
 *
 * <p>Wire format facts (the first bit of a datagram) live in {@link WireFormat}.
 */
public final class GameInternals {

    /** {@code private final int NetworkPacket.packetIndex}: which fragment a received datagram is. */
    private static Field packetIndex;
    /** {@code private final NetworkPacket.PacketData[] NetworkPacket.packets}: one entry per fragment. */
    private static Field packets;
    /** {@code private final byte[] NetworkPacket.PacketData.packet}: the fragment's whole datagram, header included. */
    private static Field packetDataBytes;
    /** {@code private int NetworkPacket.complete}: how many fragments the game thinks it has. */
    private static Field complete;
    private static volatile boolean available;
    private static volatile String problem = "not verified yet";

    private GameInternals() {
    }

    public static void verify() {
        try {
            packetIndex = field(NetworkPacket.class, "packetIndex", int.class);
            packets = field(NetworkPacket.class, "packets", null);
            if (!packets.getType().isArray()) throw new NoSuchFieldException("NetworkPacket.packets is not an array");
            Class<?> packetData = packets.getType().getComponentType();
            packetDataBytes = field(packetData, "packet", byte[].class);
            complete = field(NetworkPacket.class, "complete", int.class);
            available = true;
            problem = null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            available = false;
            problem = e.toString();
            GameLog.warn.println("[NetReliability] game internals changed, NACK recovery is OFF: " + problem
                + " (see docs/UPGRADING.md)");
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /** Why {@link #isAvailable()} is false, or null. */
    public static String problem() {
        return problem;
    }

    /** Index of the fragment a received single-datagram packet holds. */
    public static int fragmentIndex(NetworkPacket packet) {
        try {
            return packetIndex.getInt(packet);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Number of fragments of the whole packet (from the fragment header, so known from any one fragment). */
    public static int fragmentCount(NetworkPacket packet) {
        try {
            return ((Object[]) packets.get(packet)).length;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The raw datagrams of a complete outgoing packet, one per fragment, ready to be sent again as they are. */
    public static byte[][] fragmentDatagrams(NetworkPacket packet) {
        try {
            Object[] data = (Object[]) packets.get(packet);
            byte[][] out = new byte[data.length][];
            for (int i = 0; i < data.length; i++) {
                out[i] = data[i] == null ? null : (byte[]) packetDataBytes.get(data[i]);
            }
            return out;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Sets the game's fragment count of {@code packet}. Works around a vanilla bug in {@code NetworkPacket.mergePackets}:
     * when fragment 0 arrives after other fragments, the method returns fragment 0's object with every fragment copied
     * in but its count still at 1, so the packet never completes. Fragment 0 arrives late whenever it was lost and
     * resent, or simply reordered.
     */
    public static void setFragmentCount(NetworkPacket packet, int count) {
        if (!available || packet == null) return;
        try {
            complete.setInt(packet, count);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        if (type != null && f.getType() != type) {
            throw new NoSuchFieldException(owner.getSimpleName() + "." + name + " is " + f.getType().getName() + ", expected " + type.getName());
        }
        f.setAccessible(true);
        return f;
    }
}
