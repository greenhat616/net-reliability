package netreliability.config;

import customsettingslib.components.CustomModSetting;
import customsettingslib.components.settings.IntSetting;
import customsettingslib.components.settings.SelectionSetting;
import customsettingslib.settings.CustomModSettings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;
import netreliability.NetReliabilityMod;

/**
 * The settings file ({@code cfg/mods/a632079.netreliability.cfg}) and the in-game page under Settings > Mod settings
 * (Mod Settings Lib). The page shows the options a player may want to change; timings and testing options stay in
 * the file only. Labels are in {@code locale/*.lang} under {@code [settingsui]}, keyed by the page ids below.
 *
 * <p>The page's options are stored under their page ids ({@code netrel...}); the file's other keys keep the names of
 * {@link NetConfig}. A file from before the page existed has the page's options under their old names: they are read
 * from there once and saved under the page ids from then on.
 *
 * <p>Order of a save from the page: the lib copies the page's values into its setting objects, then the game saves
 * every mod's settings, which calls {@link #addSaveData}. That is where the page's values reach {@link NetConfig}
 * (the lib's own save listeners run before the values are copied, so they would see the old ones).
 *
 * <p>All options are per machine: none is a lib "server setting", so the page stays editable in a world.
 */
public class NetSettings extends CustomModSettings {

    static final String KCP = "netrelkcpenabled";
    static final String KCP_ACCEPT = "netrelkcpaccept";
    static final String NACK = "netrelnackenabled";
    static final String MERGE_FIX = "netrelmergefix";
    static final String STEAM = "netrelsteammode";
    static final String SOCKET_BUFFER = "netrelsocketbufferkb";
    static final String STATS_LOG = "netrelstatslogseconds";

    /** {@link NetConfig} keys the page writes under its own ids instead. */
    private static final Set<String> PAGE_KEYS = new HashSet<>(Arrays.asList(
        "kcpEnabled", "kcpAcceptFromClients", "nackEnabled", "fixVanillaMergeBug", "steamSendMode", "socketBufferKB",
        "statsLogSeconds"));

    private static NetSettings instance;

    public NetSettings() {
        NetConfig d = NetConfig.defaults();
        addTextSeparator("netrelsection");
        addBooleanSetting(NACK, d.nackEnabled);
        addBooleanSetting(MERGE_FIX, d.fixVanillaMergeBug);
        addIntSetting(SOCKET_BUFFER, d.socketBufferKB, 0, 16384, IntSetting.DisplayMode.INPUT);
        addParagraph("netrelbasichint");
        addTextSeparator("netrelkcpsection");
        addBooleanSetting(KCP, d.kcpEnabled);
        addParagraph("netrelkcphint");
        addBooleanSetting(KCP_ACCEPT, d.kcpAcceptFromClients);
        addTextSeparator("netrelsteamsection");
        addSelectionSetting(STEAM, d.steamSendMode.ordinal(),
            new SelectionSetting.Option("netrelsteamvanilla"),
            new SelectionSetting.Option("netrelsteamfragments"),
            new SelectionSetting.Option("netrelsteamall"));
        addParagraph("netrelsteamhint");
        addTextSeparator("netrelstatssection");
        addIntSetting(STATS_LOG, d.statsLogSeconds, 0, 3600, IntSetting.DisplayMode.INPUT);
        addParagraph("netrelfilehint");
        instance = this;
    }

    @Override
    public void addSaveData(SaveData save) {
        pullFromPage();
        NetConfig.saved().addSaveData(save, PAGE_KEYS);
        super.addSaveData(save);
    }

    @Override
    public void applyLoadData(LoadData load) {
        super.applyLoadData(load);
        NetConfig fromFile = NetConfig.fromLoadData(load);
        NetConfig.set(fromFile);
        // Options saved before the page existed are under their old names: take those for missing page ids.
        for (String id : new String[] {KCP, KCP_ACCEPT, NACK, MERGE_FIX, STEAM, SOCKET_BUFFER, STATS_LOG}) {
            if (!load.hasLoadDataByName(id)) push(id, fromFile);
        }
        pullFromPage();
    }

    /** After the file was re-read by a command: show its values on the page. */
    public static void pushToPage() {
        if (instance == null) return;
        NetConfig c = NetConfig.saved();
        for (String id : instance.settingsMap.keySet()) instance.push(id, c);
    }

    /** The page's values into {@link NetConfig}, for the saved settings and the ones in force. */
    private void pullFromPage() {
        NetConfig saved = NetConfig.saved();
        NetConfig current = NetConfig.get();
        boolean runtimeChanges = current != saved;
        NetConfig.set(apply(saved.toBuilder()).build());
        // Keep a command's runtime change (e.g. simulated loss) in force, with the page's values on top.
        if (runtimeChanges) NetConfig.setRuntime(apply(current.toBuilder()).build());
        NetReliabilityMod.applyConfig();
    }

    private NetConfig.Builder apply(NetConfig.Builder b) {
        b.kcpEnabled = bool(KCP, b.kcpEnabled);
        b.kcpAcceptFromClients = bool(KCP_ACCEPT, b.kcpAcceptFromClients);
        b.nackEnabled = bool(NACK, b.nackEnabled);
        b.fixVanillaMergeBug = bool(MERGE_FIX, b.fixVanillaMergeBug);
        int steam = integer(STEAM, b.steamSendMode.ordinal());
        b.steamSendMode = SteamSendMode.values()[Math.max(0, Math.min(SteamSendMode.values().length - 1, steam))];
        b.socketBufferKB = Math.max(0, Math.min(16384, integer(SOCKET_BUFFER, b.socketBufferKB)));
        b.statsLogSeconds = Math.max(0, Math.min(3600, integer(STATS_LOG, b.statsLogSeconds)));
        return b;
    }

    @SuppressWarnings("unchecked")
    private void push(String id, NetConfig c) {
        CustomModSetting<?> setting = settingsMap.get(id);
        if (setting == null) return;
        Object value;
        switch (id) {
            case KCP: value = c.kcpEnabled; break;
            case KCP_ACCEPT: value = c.kcpAcceptFromClients; break;
            case NACK: value = c.nackEnabled; break;
            case MERGE_FIX: value = c.fixVanillaMergeBug; break;
            case STEAM: value = c.steamSendMode.ordinal(); break;
            case SOCKET_BUFFER: value = Math.min(16384, c.socketBufferKB); break;
            case STATS_LOG: value = c.statsLogSeconds; break;
            default: return;
        }
        ((CustomModSetting<Object>) setting).changeValue(value);
    }

    private boolean bool(String id, boolean fallback) {
        CustomModSetting<?> s = settingsMap.get(id);
        Object v = s == null ? null : s.getValue();
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private int integer(String id, int fallback) {
        CustomModSetting<?> s = settingsMap.get(id);
        Object v = s == null ? null : s.getValue();
        return v instanceof Integer ? (Integer) v : fallback;
    }
}
