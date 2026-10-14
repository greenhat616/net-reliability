package netreliability.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.platforms.steam.network.networkInfo.SteamNetworkMessagesInfo;
import net.bytebuddy.asm.Advice;
import netreliability.steam.SteamRouting;

/**
 * Steam P2P sends ({@link SteamRouting}). Returning true skips the game's send because the datagram already went out.
 *
 * <p>The dedicated server has no Steam classes, so there the mod loader prints "Error loading mod patch class
 * netreliability.patches.SteamSendPatch from likely outdated mod" and skips this patch. That is expected. The advice
 * takes {@code Object} rather than the Steam type so that loading this class never needs Steam.
 */
@ModMethodPatch(target = SteamNetworkMessagesInfo.class, name = "send", arguments = {byte[].class})
public class SteamSendPatch {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean onEnter(@Advice.This Object self, @Advice.Argument(0) byte[] data) {
        return SteamRouting.trySend(self, data);
    }
}
