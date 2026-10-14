package netreliability.test;

import java.io.File;
import necesse.engine.GameLog;
import necesse.engine.save.CharacterSave;
import necesse.engine.util.GameRandom;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.HumanLook;

/**
 * Creates the benchmark character in a fresh {@code -datadir}: the vanilla {@code -character <name>} option only
 * creates a missing character when another one already exists.
 */
public final class TestCharacter {

    public static final String NAME = "NetTester";

    private TestCharacter() {
    }

    public static void ensureExists() {
        File[] existing = new File(CharacterSave.getCharacterSavesPath()).listFiles();
        if (existing != null && existing.length > 0) return;
        PlayerMob player = new PlayerMob(0L, null);
        player.look = new HumanLook(GameRandom.globalRandom, false);
        player.playerName = NAME;
        player.getInv().giveStarterItems();
        File file = CharacterSave.saveCharacter(CharacterSave.newCharacter(player, 0L), null, false);
        GameLog.out.println("[NetReliability] test created character " + NAME + " at " + file.getName());
    }
}
