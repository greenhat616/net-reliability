package netreliability.steam;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamNetworkingMessages.SendFlag;
import com.codedisaster.steamworks.SteamResult;
import java.nio.ByteBuffer;
import necesse.engine.platforms.steam.network.networkInfo.SteamNetworkMessagesInfo;
import netreliability.compat.WireFormat;
import netreliability.config.NetConfig;
import netreliability.config.SteamSendMode;
import netreliability.core.HookStatus;

/**
 * Steam P2P send routing ({@link SteamSendMode}). Only reached through {@link SteamSupport} or the Steam patch, so this
 * class is never loaded where Steam is missing.
 *
 * <p>The game sends every datagram with one static flag ({@code SteamNetworkMessagesInfo.sendFlag}, default
 * Unreliable, settable with the vanilla {@code /steamnetwork <flag>} client command). ALL_RELIABLE sets that flag;
 * FRAGMENTS_RELIABLE sends only fragments of split packets itself with a reliable flag and leaves the rest to the game.
 */
public final class SteamRouting {

    private static final SendFlag RELIABLE = SendFlag.ReliableNoNagle;
    /** The flag before ALL_RELIABLE changed it, to put back; null while this mod has not changed it. */
    private static SendFlag replacedFlag;

    private SteamRouting() {
    }

    static synchronized void applyMode(SteamSendMode mode) {
        if (mode == SteamSendMode.ALL_RELIABLE) {
            if (replacedFlag == null) replacedFlag = SteamNetworkMessagesInfo.sendFlag;
            SteamNetworkMessagesInfo.sendFlag = RELIABLE;
        } else if (replacedFlag != null) {
            SteamNetworkMessagesInfo.sendFlag = replacedFlag;
            replacedFlag = null;
        }
    }

    static String describe() {
        return "Steam: mode " + NetConfig.get().steamSendMode + ", game send flag " + SteamNetworkMessagesInfo.sendFlag;
    }

    /**
     * Called by the patch on {@code SteamNetworkMessagesInfo.send(byte[])} instead of the game's body when it returns
     * true. Returns false to let the game send (and handle any error) as usual.
     */
    public static boolean trySend(Object self, byte[] data) {
        HookStatus.steamSend = true;
        if (NetConfig.get().steamSendMode != SteamSendMode.FRAGMENTS_RELIABLE || !WireFormat.isFragment(data)) return false;
        if (!(self instanceof SteamNetworkMessagesInfo)) return false;
        SteamNetworkMessagesInfo info = (SteamNetworkMessagesInfo) self;
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
        buffer.put(data);
        buffer.flip();
        try {
            return info.networking.sendMessageToUser(info.remoteID, buffer, RELIABLE, SteamNetworkMessagesInfo.defaultChannel) == SteamResult.OK;
        } catch (SteamException | RuntimeException | LinkageError e) {
            // Let the game send it the vanilla way and report any error itself.
            return false;
        }
    }
}
