package netreliability;

import necesse.engine.GameEventListener;
import necesse.engine.GameEvents;
import necesse.engine.GameLog;
import necesse.engine.commands.CommandsManager;
import necesse.engine.events.ServerClientDisconnectEvent;
import necesse.engine.modLoader.ModSettings;
import necesse.engine.modLoader.annotations.ModEntry;
import necesse.engine.registries.PacketRegistry;
import netreliability.commands.NetRelCommand;
import netreliability.compat.GameInternals;
import netreliability.config.NetConfig;
import netreliability.config.NetSettings;
import netreliability.core.ReceiveTracker;
import netreliability.packets.PacketFragmentNack;
import netreliability.steam.SteamSupport;
import netreliability.test.NetTest;
import netreliability.test.PacketNetTestReady;
import netreliability.test.TestCharacter;

/**
 * Recovers lost fragments of the game's split UDP packets (NACK + selective resend) and adds network measurement
 * tools. See {@code docs/ARCHITECTURE.md}; settings live in {@code cfg/mods/a632079.netreliability.cfg}.
 */
@ModEntry
public class NetReliabilityMod {

    public ModSettings initSettings() {
        return new NetSettings();
    }

    public void init() {
        GameInternals.verify();
        // Registration order is the packet ID: both sides must register the same packets in the same order.
        PacketRegistry.registerPacket(true, false, false, PacketFragmentNack.class);
        PacketRegistry.registerPacket(PacketNetTestReady.class);
        CommandsManager.registerServerCommand(new NetRelCommand(false));
        CommandsManager.registerClientCommand(new NetRelCommand(true));
        GameEvents.addListener(ServerClientDisconnectEvent.class, new GameEventListener<ServerClientDisconnectEvent>() {
            @Override
            public void onEvent(ServerClientDisconnectEvent event) {
                ReceiveTracker.forget(event.client.networkInfo);
            }
        });
    }

    public void postInit() {
        applyConfig();
        GameLog.out.println("[NetReliability] " + NetConfig.get().describe());
        if (NetTest.ENABLED) TestCharacter.ensureExists();
    }

    /** Pushes settings that live in game state rather than being read on each use. */
    public static void applyConfig() {
        SteamSupport.applyMode(NetConfig.get().steamSendMode);
    }
}
