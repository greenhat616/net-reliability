package netreliability.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.NetworkPacket;
import net.bytebuddy.asm.Advice;
import netreliability.core.Receiving;

/**
 * Fixes the fragment count of the packet {@code mergePackets} returns: when fragment 0 arrives after other fragments,
 * the game returns fragment 0's object, whose count was never updated, while {@code this} holds the right count. See
 * {@code docs/GAME-INTERNALS.md} ("vanilla bug"). Without it a split packet whose fragment 0 arrives late never
 * completes, which is exactly what a NACK resend of fragment 0 produces.
 */
@ModMethodPatch(target = NetworkPacket.class, name = "mergePackets", arguments = {NetworkPacket.class})
public class NetworkPacketMergePatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This NetworkPacket self, @Advice.FieldValue("complete") int selfComplete,
                       @Advice.Return NetworkPacket merged) {
        if (merged != self) Receiving.onMergedIntoOther(merged, selfComplete);
    }
}
