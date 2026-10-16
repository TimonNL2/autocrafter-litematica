# Workshop architecture

The client owns all configuration. World identities are hashed before being used as filenames. There is no server plugin, telemetry, account system, or network service.

## Data flow

1. Material import resolves identifiers or unambiguous English item names. Invalid lines block replacement of the current list.
2. Input/output/work regions and a shulker staging position belong to a world and dimension.
3. Scanning opens selected, loaded, reachable containers through normal interaction. Only server-delivered slot contents are trusted.
4. The planner consumes a snapshot, recursively resolves recipes, reserves shared stock, and reports achievable targets and shortages. Target order defines priority; this is not a global optimal packing solver.
5. A reviewed plan is bound to its inventory snapshot. The executor must revalidate every source before clicking, use empty inventory slots as a job buffer, and wait for server acknowledgement.
6. Recovery records describe intended and confirmed actions. Reconnecting never resumes a job automatically.

## Implementation boundaries

Recipe and UI adapters are compiled against the actual unobfuscated Minecraft 26.3 classes. No Yarn mapping guesses or older-version packet helpers are used. A recipe must be available to the connected client to be executable. Local recipe resources alone cannot prove a server recipe is valid.

Shulkers require an explicit choice between inspection and handling. Missing component contents means unknown, never empty. Placing, mining, collecting and returning a box requires a separately validated lifecycle; do not silently treat a chest containing boxes as loose item stock.

Inventory actions are fail-closed: no dropping, personal-stack consumption, hidden retry, or blind continuation after a changed menu. WorkshopNavigation integrates the official Baritone 1.20.0 API. It approaches selected targets, aligns the player and invokes ordinary interaction. Movement settings are restricted per navigation session and restored afterward. Container receipt timeouts begin after navigation finishes; movement has its own bounded timeout. No OP permissions or server commands are used.

Packed boxes are virtual storage references keyed by the original chest position and slot. A single ShulkerAccess state machine owns the active box, verifies each chest transaction through a server reopen, persists its expected contents before actions, and returns it to the original reserved slot. Unknown contents require the separately reviewed physical scan. Failures preserve the recovery record; recovery is an explicit user action and rejects occupied slots or changed contents.

The completed task report is bound to the approved plan, independent of later material-list edits. Shortages are evaluated after all achievable targets have reserved stock, with shared virtual stock and batch surplus for the remaining targets. This produces one combined shopping list for the selected known recipes.

Source collection groups consecutive reservations from the same physical or virtual container. Inventory clicks remain paced and the predicted container/inventory snapshots are checked between transfers. The entire batch receives one authoritative container reopen before execution advances.

Baritone 1.20.0 starts permanent cache workers on its shared executor. At client startup, before joining a world, the integration configures future executor threads as daemon threads so they do not prevent normal JVM shutdown. The optimized release renames its public executor accessor; it is resolved by its public static signature rather than an obfuscated name. The runtime dependency is pinned to the tested 1.20.0 release. Normal world-save handling remains unchanged.
