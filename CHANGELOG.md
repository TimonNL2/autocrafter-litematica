# Changelog

Every published version has an immutable Git tag and a GitHub Release with a mod JAR, source JAR and SHA-256 checksums. Older versions remain available on the [Releases page](https://github.com/TimonNL2/autocrafter-litematica/releases).

## 0.3.0-beta.1 — 2026-10-16

- Requires the official Baritone API Fabric 1.20.0 mod for Minecraft 26.3.
- Walk to selected storage and crafting tables and turn toward interactions using Baritone. Navigation temporarily disables breaking, placing, parkour, sprinting and inventory management, then restores previous settings.
- Walk to a clear standing position beside Staging and precisely centre the player before placing a shulker.
- Visible scan progress and failure reasons. A completed scan is no longer invalidated by an idle Stop action; start checks distinguish missing scans from missing production plans.
- Collect all reserved source stacks from the same container in one opening session, with one authoritative verification afterward.
- Survival integration tests explicitly remove OP/command permissions and exercise distant storage behind an obstacle.

## 0.2.0-beta.2 — 2026-10-16

- Select region boxes with left-click for the first corner and right-click for the second corner.
- Left-click can replace the first corner before saving. Either button selects a staging support block.
- Selection clicks are consumed before mining, item use or container opening. Normal mouse controls remain unchanged outside selection mode; U remains available.
- Updated English tooltips and setup instructions, with a real-client mouse-event regression check.

## 0.2.0-beta.1 — 2026-10-16

- Packed shulker lifecycle: reserve an original slot, place at Staging, open, recover with normal survival mining/pickup, and return to the same slot.
- Reviewed physical inspection when closed box contents are unknown; scan-only/automatic settings.
- Empty output shulkers, mixed output or one item type per reserved container.
- Durable shulker recovery records and an explicit Recover box action after interruptions.
- Pause and safe stop return the active shulker before yielding.
- Removed the nine-source-stack limit. Inventory capacity is still checked before moving anything; jobs requiring more than 27 occupied slots must be split.
- English completion report with requested/delivered/uncompleted quantities and a combined raw-material shopping list, available in Activity, clipboard and a local text file.
- Fixed shortages reusing stock already allocated to later targets and double-counting recipe batch surplus.
- Read fixed item crafting remainders as well as explicit recipe-display remainders, including nested displays, before reserving output space.
- Expanded real-client tests for packed source/output boxes, partial completion reports, output sorting and interrupted-box recovery.

The alpha tag and downloads remain unchanged. This beta has local integrated-server validation; dedicated-server latency validation remains outstanding. Other workstations and storage pathfinding remain outside this release.

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
