# Validation

Run `./gradlew build` for compilation, packaging and pure-Java planner/import tests.
Run `./gradlew runClientGameTest` on a machine with a graphics environment for the real-client test. The test mod lives in `src/gametest` and is not bundled into the published mod.

The client test opens each dashboard page, captures screenshots and creates a temporary world. It verifies iron-block unpacking into nine ingots, recursive log-to-stick crafting into a placed shulker, cake crafting with all three buckets returned, personal-inventory protection and refusal of a source changed after scanning. Server-side output containers are checked, not just the predicted client inventory. Setup commands are test fixtures; production code never issues server commands.

## Multiplayer validation before a stable release

- Repeat the workflow on a normal dedicated 26.3 server at low and high latency.
- Verify single/double chests, barrels and already-placed shulkers; test unloaded, obstructed and out-of-reach containers.
- Change a source or output after scanning. Confirm execution stops before using the changed snapshot.
- Test recipe-book alternatives, centered recipes, unavailable recipes and bucket remainders.
- Pause/resume after a verified action; change the job inventory while paused and verify resume refuses.
- Disconnect during collection, recipe placement and output. Inspect the local recovery log; reconnect must not resume.
- Check full output, player death, unexpected screens and user clicks during execution.
- Confirm packed shulkers are never treated as empty or moved automatically.
- Verify the dashboard at multiple GUI scales, keyboard navigation, input method handling and narration.

## Known boundaries

Automated local integration is not proof of compatibility with every server plugin or every recipe. A server mismatch stops execution. A stopped task may leave owned ingredients in the craft grid or player inventory; the log identifies the last intended and confirmed action. Recovery is deliberately manual.
