package netreliability.patches;

import java.net.DatagramSocket;
import java.net.InetAddress;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.network.ClientAddressNetwork;
import net.bytebuddy.asm.Advice;
import netreliability.kcp.KcpHooks;

/** The client opened its UDP socket to a server: start a KCP session if {@code kcpEnabled}. */
@ModMethodPatch(target = ClientAddressNetwork.class, name = "openConnection", arguments = {})
public class ClientOpenConnectionPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.Return boolean opened, @Advice.FieldValue("socket") DatagramSocket socket,
                       @Advice.FieldValue("address") InetAddress address, @Advice.FieldValue("port") int port) {
        KcpHooks.onClientOpened(opened, socket, address, port);
    }
}
