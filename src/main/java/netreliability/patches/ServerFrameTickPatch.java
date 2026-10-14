package netreliability.patches;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.Server;
import net.bytebuddy.asm.Advice;
import netreliability.core.FrameTick;

/** Runs after the server handled its incoming packets each frame. */
@ModMethodPatch(target = Server.class, name = "frameTick", arguments = {TickManager.class})
public class ServerFrameTickPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Server server) {
        FrameTick.server(server);
    }
}
