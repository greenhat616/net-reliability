package netreliability.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.NetworkPacket;
import net.bytebuddy.asm.Advice;
import netreliability.core.Sending;

/** Every packet sent over the network (client, server, Steam) goes out through this method; see {@link Sending}. */
@ModMethodPatch(target = NetworkPacket.class, name = "sendPacket", arguments = {})
public class NetworkPacketSendPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This NetworkPacket packet) {
        Sending.onSent(packet);
    }
}
