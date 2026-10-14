package netreliability.packets;

import necesse.engine.GameLog;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import netreliability.core.SendCache;

/**
 * Either direction: "resend these fragments of your split packet {@code identifier}". See {@code docs/PROTOCOL.md}.
 *
 * <p>Registered to be processed on the receive thread as it arrives and also from peers that are not connected yet
 * (connect-time packets can be split too). It always fits in one datagram, so it is never split itself.
 */
public class PacketFragmentNack extends Packet {

    public static final int VERSION = 1;
    /** 1 + 4 + 2 + 4 x 200 bytes: well inside one datagram's 992 content bytes. */
    public static final int MAX_RANGES = 200;
    private static volatile boolean errorLogged;

    public final int version;
    public final int identifier;
    /** Pairs of (first fragment index, number of fragments). */
    public final int[] ranges;

    public PacketFragmentNack(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.version = reader.getNextByteUnsigned();
        if (this.version != VERSION) {
            // A newer format from a newer mod version: ignore rather than misread it.
            this.identifier = 0;
            this.ranges = new int[0];
            return;
        }
        this.identifier = reader.getNextInt();
        int count = Math.min(reader.getNextShortUnsigned(), MAX_RANGES);
        this.ranges = new int[count * 2];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = reader.getNextShortUnsigned();
        }
    }

    public PacketFragmentNack(int identifier, int[] ranges) {
        if (ranges.length % 2 != 0 || ranges.length / 2 > MAX_RANGES) throw new IllegalArgumentException("bad ranges");
        this.version = VERSION;
        this.identifier = identifier;
        this.ranges = ranges;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextByteUnsigned(VERSION);
        writer.putNextInt(identifier);
        writer.putNextShortUnsigned(ranges.length / 2);
        for (int value : ranges) writer.putNextShortUnsigned(value);
    }

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        handle(packet);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        handle(packet);
    }

    private void handle(NetworkPacket packet) {
        if (version != VERSION) return;
        // An exception here would reach the game's packet handling, which disconnects the sender or stops the server.
        try {
            SendCache.onNack(packet.networkInfo, identifier, ranges);
        } catch (RuntimeException | LinkageError e) {
            if (!errorLogged) {
                errorLogged = true;
                GameLog.err.println("[NetReliability] error handling a NACK (further errors not logged): " + e);
                e.printStackTrace();
            }
        }
    }
}
