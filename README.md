# JEI Gated Discovery (Forge 1.20.1)

> **This is the 1.20.1 / Forge backport branch.** The 1.21.1 / NeoForge version lives on `main`.
> Behaviour, config keys, defaults and the on-disk discovery format are identical between the two;
> only the platform APIs differ (see [Porting notes](#porting-notes-1211-neoforge--1201-forge)).

Hides every JEI recipe on world load, then reveals each recipe the moment you've **discovered**
(held in your inventory at least once) a matching item for **all** of its inputs. It mimics the
vanilla recipe book's "you must have touched the ingredients" feel, but for JEI's whole catalog.

> Spawn in → no recipes. Pick up a log → the planks recipe appears. Craft planks → the sticks
> recipe appears. And so on.

---

## ⚠️ Read this first: the version matters

This branch targets **Minecraft 1.20.1 + Forge 47.x**, and the version ceiling is not arbitrary:

- **1.20.1 and 1.21.1** both keep the full recipe set **client-side**. JEI can see every recipe, so a
  client-side mod can hide/unhide them freely. ✅
- **1.21.2+** moved recipes **server-side** and only sends *unlocked* recipes to the client. A
  client-only approach like this one can't see the recipes it would need to gate. Porting up would
  require a server-side component (and a rewrite of the ingredient resolver, since the
  ingredient/recipe API was also reworked). See `RecipeInputResolver.java` and the TODOs.

It also relies on **real JEI**. EMI's JEI-compat shim (TooManyRecipeViewers) intentionally throws on
runtime recipe edits, so under EMI this mod is a no-op (it logs a warning rather than crashing).

---

## Design decisions (your two open questions, resolved)

You flagged two ambiguities. Both became config toggles rather than hard forks, with defaults that
match the lean of your spec:

1. **Singleplayer vs multiplayer.** This is a **client-side-only** mod. Discovery is detected by
   scanning your own inventory on the client, and hiding is done through JEI's client runtime. That
   means it works in singleplayer **and** when you connect to a dedicated server (on 1.20.1 the
   server syncs all recipes to your client), and **the server does not need the mod**. There is no
   server-authoritative / shared-across-players discovery — if you want that later, it's a different
   build with packets.
2. **Per-save vs global discovery.** Config `discoveryScope`, default **`PER_SAVE`**. Switch to
   `GLOBAL` for one shared set across all worlds on this client.

If either default is wrong for you, change the config (below) — no code edits needed.

---

## Install (playing)

1. Install **Forge for 1.20.1** (47.x) and **JEI for 1.20.1** (JEI 15.x).
2. Drop this mod's jar into `mods/`. Client only — you don't need it on a server.
3. Launch. Open any inventory: JEI starts empty and fills in as you handle items.

On Modrinth/CurseForge this is uploaded as **Client-side (required)**, loader **Forge**, game
version **1.20.1**.

## Build (from source)

Requires **JDK 17** (1.20.1's Java version — ForgeGradle 6 does not run on 21). The Gradle wrapper is
committed, so no system Gradle is needed:

```bash
# from the project root
./gradlew build       # jar lands in build/libs/gatedjei-1.20.1-<version>.jar
./gradlew runClient   # launches a dev client with JEI to test
```

Ignore the `-sources.jar` next to it; the plain `gatedjei-1.20.1-<version>.jar` is the one you ship.

If your default JDK is not 17, point Gradle at one for the build:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew build
```

### Versions you may want to bump

Everything is in `gradle.properties`:

- `forge_version` — any 1.20.1 Forge build (47.x).
- `jei_version` — latest 1.20.1 JEI on https://maven.blamejared.com/mezz/jei/ (the `15.x` line).
- `mapping_channel` / `mapping_version` — Mojang official mappings by default. To use Parchment
  instead, add the `org.parchmentmc.librarian.forgegradle` plugin and set the channel to
  `parchment` with a 1.20.1 Parchment version.

---

## Config

`config/gatedjei-client.toml`:

**Out of the box, the mod runs in full-gating mode:** both recipes *and* the item list start
hidden and reveal as you discover items, item variants (enchantments, potions) are gated
individually, and recipes the mod can't read are hidden rather than left showing. The toggles below
let you loosen any of that — e.g. set `hideUndiscoveredItems = false` to gate only recipes and leave
JEI's item list fully visible.

| Key | Default | Meaning |
|---|---|---|
| `requireOutputsDiscovered` | `false` | Also require a recipe's **output** to be discovered before showing it. |
| `hideUndiscoveredItems` | `true` | Remove undiscovered items from JEI's **item list**, not just their recipes. Set `false` to gate recipes only. |
| `hideUndiscoveredFluids` | `true` | Hide undiscovered **fluids** (water, lava, modded) from JEI's fluid list. Fluids are a separate JEI ingredient type from items. |
| `granularSubtypeDiscovery` | `true` | Gate items with NBT variants (enchanted books, potions) **per variant** instead of per item. Discovering a Sharpness book reveals only that book (+ the plain book); a Night Vision potion reveals only that potion. Needs `hideUndiscoveredItems = true`. |
| `revealAll` | `false` | Debug: hide nothing. Also toggleable live via `/gatedjei reveal`. |
| `unresolvedRecipePolicy` | `HIDE` | Recipes whose inputs can't be read (custom modded categories): `REVEAL` keeps them visible, `HIDE` gates them. |
| `discoveryScope` | `PER_SAVE` | `PER_SAVE` or `GLOBAL`. |
| `scanIntervalTicks` | `10` | How often (ticks) to scan inventory for newly touched items. |
| `discoverFluidsByWading` | `true` | Learn a fluid by standing in / wading through it. |
| `discoverFluidsByBucket` | `true` | Learn a fluid by holding a bucket of it (also teaches the contained fluid). |
| `discoverBaseContainer` | `true` | When you comprehend a "filled" item, also discover the empty base it's built on: filled bucket → bucket, enchanted book → book, potion → glass bottle, tipped arrow → arrow, soup → bowl. Set false to learn only the filled item. |
| `logStats` | `false` | Log indexing/gating stats. |

### Commands (client-side)

- `/gatedjei stats` — discovered count + visible/total recipes.
- `/gatedjei reveal` — toggle reveal-all and re-apply.
- `/gatedjei reset` — wipe discovery for this save and re-hide.
- `/gatedjei discoverall` — mark every item discovered (tests the unhide path).

---

## How it works (architecture)

```
ClientDiscoveryHandler ──scan inventory──▶ DiscoveryState ──delta listener──▶ RecipeGate ──hide/unhide──▶ JEI
        │                                       │                                  ▲
        └─ load/save per save key ──▶ DiscoveryStorage (NBT)        GatedJeiPlugin.onRuntimeAvailable
                       (SaveContext)                                  (builds index, full apply)
```

- **Discovery tracking** (`discovery/`): a throttled client-tick scan of inventory + armor + offhand
  + cursor marks every held item type as discovered. Permanent, persisted to
  `<gamedir>/gatedjei/<key>.dat` as compressed NBT (item registry ids). Scanning (not pickup events)
  is what makes it work identically in SP and on dedicated servers from the client side.
- **Recipe input model** (`recipe/RecipeInputResolver.java`): for each JEI recipe object, if it's a
  vanilla-style `Recipe`, read `getIngredients()` and expand each `Ingredient` (tags
  included) to a "any-of" set of items. A recipe is satisfied when **every** input group contains at
  least one discovered item. Tag slots (e.g. "any plank") are satisfied by discovering **any** match.
- **JEI bridge** (`jei/RecipeGate.java`): on `onRuntimeAvailable`, enumerate all categories/recipes,
  build an `item → recipes` reverse index, hide everything, then unhide what's already satisfied. On
  each discovery delta, only the recipes referencing the **new** items are re-checked and unhidden —
  no full re-scan, so it scales to thousand-recipe packs.
- **Lifecycle**: `onRuntimeAvailable` fires again after `/reload` and resource reloads, so the hidden
  state is rebuilt correctly each time. If JEI's runtime isn't up yet, discovery just accumulates and
  gets applied on the next build.

---

## Porting notes (1.21.1 NeoForge → 1.20.1 Forge)

The gating engine is unchanged — same snapshot-based hiding, same reveal order, same config keys and
defaults. Only the platform APIs moved:

| Area | 1.21.1 (NeoForge) | 1.20.1 (Forge) |
|---|---|---|
| Java | 21 | 17 |
| Build | ModDevGradle | ForgeGradle 6 (`net.minecraftforge.gradle`) |
| Mod metadata | `META-INF/neoforge.mods.toml`, `type = "required"` | `META-INF/mods.toml`, `mandatory = true` |
| `pack.mcmeta` | `pack_format 34` | `pack_format 15` |
| Config spec | `ModConfigSpec` | `ForgeConfigSpec` (identical builder API) |
| Config registration | `modContainer.registerConfig(...)` | `ModLoadingContext.get().registerConfig(...)` |
| Event subscriber | `@EventBusSubscriber(bus = Bus.GAME)` | `@Mod.EventBusSubscriber(bus = Bus.FORGE)` |
| Client tick | `ClientTickEvent.Post` | `TickEvent.ClientTickEvent`, filtered to `Phase.END` |
| Fluid handler cap | `stack.getCapability(Capabilities.FluidHandler.ITEM)` | `stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve()` |
| JEI fluid type | `NeoForgeTypes.FLUID_STACK` (JEI 19.x) | `ForgeTypes.FLUID_STACK` (JEI 15.x) |
| `ResourceLocation` | `fromNamespaceAndPath(ns, path)` | `new ResourceLocation(ns, path)` |
| Recipe objects | `RecipeHolder<Recipe<?>>` | plain `Recipe<?>` (no `RecipeHolder` before 1.21) |
| NBT IO | `NbtIo.readCompressed(Path, NbtAccounter)` | `NbtIo.readCompressed(File)` |
| Item variants | Data Components (`STORED_ENCHANTMENTS`, `POTION_CONTENTS`) | NBT (`StoredEnchantments` tag, `PotionUtils`) |

The last row is the only one with real behavioural risk, and it lives entirely in
`discovery/SubtypeKeys.java`. Two things were deliberate there:

- Stored enchantments are read via `EnchantedBookItem.getEnchantments(stack)` (the
  `StoredEnchantments` tag), **not** `EnchantmentHelper.getEnchantments(stack)` — the latter also
  reads the regular `Enchantments` tag, which would make every enchanted tool a "subtype variant"
  and diverge from the 1.21.1 behaviour.
- The emitted key strings keep the exact 1.21.1 format (`<id>#ench:<id>=<lvl>,...` and
  `<id>#potion:<id>|<effect>:<dur>:<amp>,...`, both sorted), so `<gamedir>/gatedjei/<key>.dat`
  discovery files are interchangeable between the 1.20.1 and 1.21.1 builds.
