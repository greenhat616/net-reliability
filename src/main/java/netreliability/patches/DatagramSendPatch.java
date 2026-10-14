package netreliability.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.networkInfo.DatagramNetworkInfo;
import net.bytebuddy.asm.Advice;
import netreliability.kcp.KcpHooks;

/**
 * Every datagram the game sends over a direct UDP connection goes out here. Returning true skips the game's send:
 * the datagram went into the peer's KCP session ({@link netreliability.kcp.KcpTransport}).
 */
@ModMethodPatch(target = DatagramNetworkInfo.class, name = "send", arguments = {byte[].class})
public class DatagramSendPatch {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean onEnter(@Advice.This DatagramNetworkInfo info, @Advice.Argument(0) byte[] data) {
        return KcpHooks.onDatagramSend(info, data);
    }
}
