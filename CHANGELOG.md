# Changelog

Every published version has an immutable Git tag and a GitHub Release with a mod JAR, source JAR and SHA-256 checksums. Older versions remain available on the [Releases page](https://github.com/TimonNL2/autocrafter-litematica/releases).

## 0.1.0-alpha.1 — 2026-10-16

First experimental Minecraft Java 26.3 / Fabric release.

- Custom English workshop dashboard with Materials, Storage, Plan, Activity and Settings pages.
- Local world/dimension profiles, region corners, world outlines and staging location.
- Text/clipboard material imports without a Litematica dependency.
- Server-confirmed scans of reachable chests, barrels and placed shulkers; double chests counted once.
- Recursive planner with shared stock, alternative ingredients/recipes, batches, unpacking, fixed remainders and shortage reports.
- Reviewed, small-batch execution using an empty player inventory, a reachable crafting table and one empty output container.
- Full container reopens verify transactions against server snapshots; pause, resume, stop and a local recovery log.
- Gradle wrapper, unit tests, client integration test and GitHub build/release workflows.

### Alpha limits

- Up to nine source stacks per execution; all main inventory slots and the offhand must be empty.
- Only server-supplied crafting recipe displays with plain item stacks and deterministic remainders are supported.
- Target order defines priority. Planning is not a globally optimal allocation solver.
- Packed shulkers are recognized but not automatically placed, broken or packed back into chests. Place them in selected regions manually.
- No automatic walking, smelting, stonecutting, brewing, output sorting rules or direct Litematica API integration.
- All products, unused source items and crafting remainders go to the reviewed output container.
