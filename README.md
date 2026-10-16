# Autocrafter Litematica

A client-side crafting workshop for **Minecraft Java 26.3**. Import a material list, select your storage and crafting area, review a production plan and craft small batches through normal Minecraft interactions. All mod text and documentation are in English.

**Status: experimental alpha.** The full planned shulker automation is not implemented yet. Read the limits below before starting a task.

[Download current and older versions](https://github.com/TimonNL2/autocrafter-litematica/releases) · [Version history](CHANGELOG.md) · [Report an issue](https://github.com/TimonNL2/autocrafter-litematica/issues)

## Install

1. Install Fabric Loader **0.19.5 or later** for Minecraft **26.3**.
2. Add Fabric API **0.162.0+26.3** and the release mod JAR to your client `mods` directory.
3. Use **Java 25**. Start the Fabric profile and join your world/server.
4. Press **K** to open the workshop. Key bindings can be changed in Minecraft Controls.

Use the JAR without `-sources` in its name. Litematica is optional. The server does not need a plugin or mod.

## Set up your craft room

1. In **Storage**, choose **+ Input**. Aim at a corner block and press **U**, then do the same at the opposite corner. Select both halves of double chests. Repeat for additional regions.
2. Select an **Output** region containing an empty chest, barrel or already-placed shulker, then a **Work** region containing a crafting table.
3. Keep the selected containers and table within normal reach, unobstructed and loaded. This version does not walk for you.
4. Optionally mark a **Staging** position by aiming at its support block and pressing **U**. Packed shulker placement/mining is not automatic in this alpha.
5. Open **Materials**, type a list or import it from the clipboard, then **Validate & save**. Plain lines, CSV and Litematica-style text tables are supported. Export totals are used, not the export's missing column.

```text
minecraft:repeater 16
minecraft:comparator 8
minecraft:iron_ingot 9
```

6. Choose **Scan storage**, then **Plan → Build plan**. The scan opens selected containers without moving their items. Only recipes received in the connected client's recipe book are used.
7. Empty your main inventory and offhand. Open **Review moves** and inspect whole-stack pickups, output destination, quantities, shortages and start checks. **Confirm start** authorizes this exact workflow.
8. Use **K** to pause at the next verified checkpoint. Resume from **Activity**, or press **O** for a safe stop. Do not manually click the containers during a running task.

Products, surplus ingredients and crafting remainders go to the reviewed output container. A stopped task leaves items available for manual recovery; it never resumes automatically after reconnecting.

## Supported and limited behavior

| Area | This alpha |
| --- | --- |
| Storage | Selected chests, double chests, barrels and placed shulkers |
| Plans | Recursive 2×2/3×3 recipes, batch counts, shared resources, alternatives, fixed remainders, block unpacking |
| Execution | At most nine source stacks, empty main inventory/offhand, one empty output container |
| Shulkers in chests | Recognized; not automatically placed/mined/recovered. Manually place in an Input/Output region |
| Recipes | Known server-supplied displays; component-sensitive/dynamic recipes are excluded |
| Priority | Material list order; no claim of globally optimal allocation |
| Other workstations | Not supported: furnaces, stonecutters, brewing, smithing |
| Other limitations | No automatic walking, output sorting rules or direct Litematica API integration |

Selected regions are stored per world/server and dimension under `config/autocrafter-litematica/`. Filenames use a hashed world identifier. Recovery logs stay in the same local directory. There is no telemetry, external account or server-address logging.

## Build from source

Install a **JDK 25**, set `JAVA_HOME`, and clone this repository. Gradle is included through the checked-in wrapper; it downloads the pinned build dependencies on first use.

Windows PowerShell:

```powershell
git clone https://github.com/TimonNL2/autocrafter-litematica.git
cd autocrafter-litematica
.\gradlew.bat build
```

Linux/macOS:

```sh
git clone https://github.com/TimonNL2/autocrafter-litematica.git
cd autocrafter-litematica
./gradlew build
```

The mod is at `build/libs/autocrafter-litematica-0.1.0-alpha.1.jar`. Sources and test reports are generated alongside the build. Run `./gradlew runClient` for a development client or `./gradlew runClientGameTest` for the real-client smoke/integration test.

Minecraft 26.3 is unobfuscated; the project uses the game's actual class names without Yarn mappings. Pinned versions are in `gradle.properties`. The wrapper checksum is pinned too.

To build an older release, check out its tag (for example `git checkout v0.1.0-alpha.1`) and use that version's README and wrapper.

## Releases and contributions

Every push/PR builds on Windows and Linux and runs unit tests. For a new release, update `version` in `gradle.properties`, update `CHANGELOG.md`, add `docs/releases/<version>.md`, commit, then push a matching `v<version>` tag. The release workflow builds and publishes JARs, source JARs and SHA-256 checksums. Versions containing a suffix such as `-alpha.1` become prereleases. Never move an existing release tag.

See [architecture](docs/ARCHITECTURE.md) and [testing](docs/TESTING.md). The code is MIT licensed; Minecraft and Fabric dependencies are downloaded by Gradle rather than committed to Git.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
