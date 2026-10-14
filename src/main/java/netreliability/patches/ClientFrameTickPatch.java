package netreliability.patches;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.Client;
import net.bytebuddy.asm.Advice;
import netreliability.core.FrameTick;

/** Runs after the client handled its incoming packets each frame, also while it is still connecting. */
@ModMethodPatch(target = Client.class, name = "frameTick", arguments = {TickManager.class})
public class ClientFrameTickPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Client client) {
        FrameTick.client(client);
    }
}
