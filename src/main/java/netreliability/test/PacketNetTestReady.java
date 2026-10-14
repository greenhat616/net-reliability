package netreliability.test;

import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;

/** Client to server, benchmark only ({@link NetTest}): finished loading after teleport {@code seq}. */
public class PacketNetTestReady extends Packet {

    public final int seq;
    public final long durationMs;

    public PacketNetTestReady(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.seq = reader.getNextInt();
        this.durationMs = reader.getNextLong();
    }

    public PacketNetTestReady(int seq, long durationMs) {
        this.seq = seq;
        this.durationMs = durationMs;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextInt(seq);
        writer.putNextLong(durationMs);
    }

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        NetTest.onClientReady(server, client, seq, durationMs);
    }
}
