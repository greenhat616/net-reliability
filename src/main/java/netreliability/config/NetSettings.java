package netreliability.config;

import necesse.engine.modLoader.ModSettings;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/** Hooks {@link NetConfig} into the vanilla per-mod settings file ({@code cfg/mods/a632079.netreliability.cfg}). */
public class NetSettings extends ModSettings {

    @Override
    public void addSaveData(SaveData save) {
        NetConfig.get().addSaveData(save);
    }

    @Override
    public void applyLoadData(LoadData save) {
        NetConfig.set(NetConfig.fromLoadData(save));
    }
}
