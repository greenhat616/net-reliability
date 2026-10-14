package netreliability.config;

/**
 * How datagrams go out over Steam P2P ({@code SteamNetworkingMessages}). Has no effect on direct IP connections.
 * Deliberately free of Steam classes, so the dedicated server (which has none) can load it.
 */
public enum SteamSendMode {
    /** Leave the game's send flag alone (Unreliable, or whatever {@code /steamnetwork} set). */
    VANILLA,
    /** Fragments of split packets go reliable (Steam resends them); everything else keeps the game's flag. */
    FRAGMENTS_RELIABLE,
    /** Everything goes reliable and in order. Head-of-line blocking can delay movement updates; for comparison only. */
    ALL_RELIABLE
}
