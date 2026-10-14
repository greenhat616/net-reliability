package netreliability.patches;

import java.net.DatagramSocket;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.network.ClientAddressNetwork;
import net.bytebuddy.asm.Advice;
import netreliability.kcp.KcpHooks;

/** The client closes its UDP socket: drop its KCP session. */
@ModMethodPatch(target = ClientAddressNetwork.class, name = "close", arguments = {})
public class ClientCloseConnectionPatch {

    @Advice.OnMethodEnter
    static void onEnter(@Advice.FieldValue("socket") DatagramSocket socket) {
        KcpHooks.onClientClosing(socket);
    }
}
