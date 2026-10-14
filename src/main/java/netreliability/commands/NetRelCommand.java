package netreliability.commands;

import java.util.ArrayList;
import java.util.List;
import necesse.engine.commands.CmdParameter;
import necesse.engine.commands.CommandLog;
import necesse.engine.commands.ModularChatCommand;
import necesse.engine.commands.PermissionLevel;
import necesse.engine.commands.parameterHandlers.PresetStringParameterHandler;
import necesse.engine.commands.parameterHandlers.StringParameterHandler;
import necesse.engine.network.PacketManager;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import netreliability.NetReliabilityMod;
import netreliability.compat.GameInternals;
import netreliability.config.NetConfig;
import netreliability.config.SteamSendMode;
import netreliability.core.HookStatus;
import netreliability.core.ReceiveTracker;
import netreliability.core.Receiving;
import netreliability.core.SendCache;
import netreliability.stats.NetStats;
import netreliability.stats.StatsLog;
import netreliability.steam.SteamSupport;

/**
 * {@code /netrel} (server side, ADMIN) and {@code /netrelc} (this client, USER): measurement and runtime switches.
 * <ul>
 * <li>{@code status}: settings, hook health, socket buffers, Steam mode, the game's and this mod's counters</li>
 * <li>{@code reset}: zero this mod's counters</li>
 * <li>{@code reload}: re-read {@code cfg/mods/a632079.netreliability.cfg}</li>
 * <li>{@code nack <on|off>}, {@code sim <loss 0-1> [all|fragments]}, {@code steam <mode>}: change a setting until the
 * next reload (the file is not written)</li>
 * </ul>
 * A host's server and client share one process, so both commands show the same mod counters there.
 */
public class NetRelCommand extends ModularChatCommand {

    private final boolean clientSide;

    public NetRelCommand(boolean clientSide) {
        super(clientSide ? "netrelc" : "netrel",
            "Net Reliability (" + (clientSide ? "this client" : "server") + "): status | reset | reload | nack <on|off> | sim <loss> [all|fragments] | steam <VANILLA|FRAGMENTS_RELIABLE|ALL_RELIABLE>",
            clientSide ? PermissionLevel.USER : PermissionLevel.ADMIN, false,
            new CmdParameter("subcommand", new PresetStringParameterHandler("status", "reset", "reload", "nack", "sim", "steam")),
            new CmdParameter("arg", new StringParameterHandler(null, "on", "off", "0", "0.05", "0.1", "VANILLA", "FRAGMENTS_RELIABLE", "ALL_RELIABLE"), true),
            new CmdParameter("extra", new StringParameterHandler(null, "all", "fragments"), true));
        this.clientSide = clientSide;
    }

    @Override
    public void runModular(Client client, Server server, ServerClient serverClient, Object[] args, String[] errors, CommandLog logs) {
        String sub = (String) args[0];
        String arg = (String) args[1];
        String extra = (String) args[2];
        switch (sub) {
            case "status":
                for (String line : status(client, server)) logs.add(line);
                break;
            case "reset":
                NetStats.reset();
                logs.add("Counters reset");
                break;
            case "reload":
                String error = NetConfig.reloadFromFile();
                NetReliabilityMod.applyConfig();
                logs.add(error == null ? "Reloaded " + NetConfig.file() : "Could not read " + NetConfig.file() + ", settings unchanged: " + error);
                logs.add(NetConfig.get().describe());
                break;
            case "nack":
                if (!"on".equals(arg) && !"off".equals(arg)) {
                    logs.add("Usage: nack <on|off>");
                    return;
                }
                NetConfig.Builder nb = NetConfig.get().toBuilder();
                nb.nackEnabled = "on".equals(arg);
                NetConfig.set(nb.build());
                logs.add("NACK recovery " + arg + " (until reload)");
                break;
            case "sim":
                float loss;
                try {
                    loss = arg == null ? -1 : Float.parseFloat(arg);
                } catch (NumberFormatException e) {
                    loss = -1;
                }
                if (loss < 0 || loss > 1) {
                    logs.add("Usage: sim <loss 0-1> [all|fragments]");
                    return;
                }
                NetConfig.Builder sb = NetConfig.get().toBuilder();
                sb.simulatedLoss = loss;
                if (extra != null) sb.simulatedLossFragmentsOnly = "fragments".equals(extra);
                NetConfig.set(sb.build());
                logs.add("Simulated incoming loss " + loss + (sb.simulatedLossFragmentsOnly ? " (fragments only)" : " (all datagrams)")
                    + " on this " + (clientSide ? "client" : "server") + " (until reload)");
                break;
            case "steam":
                SteamSendMode mode;
                try {
                    mode = SteamSendMode.valueOf(arg == null ? "" : arg.toUpperCase());
                } catch (IllegalArgumentException e) {
                    logs.add("Usage: steam <VANILLA|FRAGMENTS_RELIABLE|ALL_RELIABLE>");
                    return;
                }
                NetConfig.Builder tb = NetConfig.get().toBuilder();
                tb.steamSendMode = mode;
                NetConfig.set(tb.build());
                NetReliabilityMod.applyConfig();
                logs.add(SteamSupport.describe() + " (until reload)");
                break;
            default:
                logs.add("Unknown subcommand: " + sub);
        }
    }

    private List<String> status(Client client, Server server) {
        List<String> out = new ArrayList<>();
        out.add("Settings: " + NetConfig.get().describe());
        out.add(GameInternals.isAvailable() ? "Game internals: OK" : "Game internals: CHANGED, NACK recovery off: " + GameInternals.problem());
        out.add(HookStatus.describe());
        out.add(Receiving.socketBufferReport());
        out.add(SteamSupport.describe());
        PacketManager manager = clientSide ? (client == null ? null : client.packetManager) : (server == null ? null : server.packetManager);
        if (manager != null) out.addAll(StatsLog.describeGame(clientSide ? "client" : "server", manager));
        if (!clientSide && server != null) {
            server.streamClients().forEach(c -> out.add("  " + c.getName() + " @ " + (c.networkInfo == null ? "LOCAL" : c.networkInfo.getDisplayName())
                + ", latency " + c.latency + " ms"));
        }
        out.addAll(NetStats.describe());
        out.add(ReceiveTracker.describe());
        out.add(SendCache.describe());
        return out;
    }
}
