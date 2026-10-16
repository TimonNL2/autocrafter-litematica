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

Inventory actions are fail-closed: no dropping, personal-stack consumption, hidden retry, or blind continuation after a changed menu. Automatic walking is outside the initial scope; the player must make each selected container reachable.
