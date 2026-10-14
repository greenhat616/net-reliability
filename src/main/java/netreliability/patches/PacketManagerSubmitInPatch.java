package netreliability.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.PacketManager;
import net.bytebuddy.asm.Advice;
import netreliability.core.Receiving;

/**
 * Every datagram from the network, one {@code NetworkPacket} per datagram, passes here before the game merges
 * fragments ({@link Receiving}). Returning true skips the game's method, i.e. the game never sees the datagram.
 */
@ModMethodPatch(target = PacketManager.class, name = "submitInPacket", arguments = {NetworkPacket.class})
public class PacketManagerSubmitInPatch {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean onEnter(@Advice.This PacketManager manager, @Advice.Argument(0) NetworkPacket packet) {
        return Receiving.onDatagram(manager, packet);
    }
}
