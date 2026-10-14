# Net Reliability: notes for agents

- Game dir: `D:\Program Files (x86)\Steam\steamapps\common\Necesse`. Decompiled sources: `../.decompiled/src/necesse` (client jar) and `../.decompiled/srv` (server jar).
- Build: `JAVA_HOME=<JDK 17+> ./gradlew buildModJar` (Gradle needs 17+; the output is Java 8 bytecode).
- **Game update?** Follow `docs/UPGRADING.md`. Start with `tools/check-internals.sh`.
- Layering rules (`docs/ARCHITECTURE.md` §3):
  - Private game members are reached only through `compat/GameInternals`.
  - Steam types are referenced only inside `steam/` (Server.jar has none).
  - `patches/` classes only delegate to other code.
  - Every hook catches its own exceptions.
- Every game member the mod relies on is listed in `docs/GAME-INTERNALS.md` **and** checked by `tools/check-internals.sh`. Change both together.
- Protocol changes: follow `docs/PROTOCOL.md` (bump `PacketFragmentNack.VERSION`; register new packets only after the existing ones).
- Tests: `tools/net-test.sh <label> [--server k=v] [--client k=v]` for one run, `tools/net-bench.sh` for the vanilla-vs-mod matrix. Both need Steam running. Results go in `docs/RESULTS.md`.
