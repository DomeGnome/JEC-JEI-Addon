# JEI Gated Discovery (NeoForge 1.21.1)

Hides every JEI recipe on world load, then reveals each recipe the moment you've **discovered**
(held in your inventory at least once) a matching item for **all** of its inputs. It mimics the
vanilla recipe book's "you must have touched the ingredients" feel, but for JEI's whole catalog.

> Spawn in → no recipes. Pick up a log → the planks recipe appears. Craft planks → the sticks
> recipe appears. And so on.

---

## ⚠️ Read this first: the version matters

This mod targets **Minecraft 1.21.1 specifically**, and that's not arbitrary:

- **1.21.1** is the last version where the full recipe set lives **client-side**. JEI can see every
  recipe, so a client-side mod can hide/unhide them freely. ✅
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
   means it works in singleplayer **and** when you connect to a dedicated server (on 1.21.1 the
   server syncs all recipes to your client), and **the server does not need the mod**. There is no
   server-authoritative / shared-across-players discovery — if you want that later, it's a different
   build with packets.
2. **Per-save vs global discovery.** Config `discoveryScope`, default **`PER_SAVE`**. Switch to
   `GLOBAL` for one shared set across all worlds on this client.

If either default is wrong for you, change the config (below) — no code edits needed.

---

## Install (playing)

1. Install **NeoForge for 1.21.1** and **JEI for 1.21.1** (JEI 19.x).
2. Drop this mod's jar into `mods/`. Client only — you don't need it on a server.
3. Launch. Open any inventory: JEI starts empty and fills in as you handle items.

## Build (from source)

Requires **JDK 21**.

```bash
# from the project root
gradle wrapper        # only needed once, to generate the gradlew scripts + wrapper jar
./gradlew build       # jar lands in build/libs/
./gradlew runClient   # launches a dev client with JEI to test
```

> This zip does **not** include the `gradlew`/`gradlew.bat` launchers or `gradle-wrapper.jar`
> (those are binaries). Run `gradle wrapper` once with a system Gradle (8.10+), or copy them from
> the official NeoForge 1.21.1 MDK. `gradle/wrapper/gradle-wrapper.properties` is already set up.

### Versions you may need to bump

Open `gradle.properties` and check the entries marked `TODO(verify)`:

- `neo_version` — any 1.21.1 NeoForge build (21.1.x).
- `jei_version` — latest 1.21.1 JEI on https://maven.blamejared.com/mezz/jei/
- `parchment_*` — optional; delete the `parchment {}` block in `build.gradle` if you drop these.

---

## Config

`config/gatedjei-client.toml`:

| Key | Default | Meaning |
|---|---|---|
| `requireOutputsDiscovered` | `false` | Also require a recipe's **output** to be discovered before showing it. |
| `hideUndiscoveredItems` | `false` | Also remove undiscovered items from JEI's **item list**, not just their recipes. |
| `hideUndiscoveredFluids` | `true` | Hide undiscovered **fluids** (water, lava, modded) from JEI's fluid list. Fluids are a separate JEI ingredient type from items. |
| `granularSubtypeDiscovery` | `false` | Gate items with NBT variants (enchanted books, potions) **per variant** instead of per item. Discovering a Sharpness book reveals only that book (+ the plain book); a Night Vision potion reveals only that potion. Needs `hideUndiscoveredItems = true`. |
| `revealAll` | `false` | Debug: hide nothing. Also toggleable live via `/gatedjei reveal`. |
| `unresolvedRecipePolicy` | `REVEAL` | Recipes whose inputs can't be read (custom modded categories): `REVEAL` or `HIDE`. |
| `discoveryScope` | `PER_SAVE` | `PER_SAVE` or `GLOBAL`. |
| `scanIntervalTicks` | `10` | How often (ticks) to scan inventory for newly touched items. |
| `discoverFluidsByWading` | `true` | Learn a fluid by standing in / wading through it. |
| `discoverFluidsByBucket` | `true` | Learn a fluid by holding a bucket of it (also teaches the contained fluid and the emptied container). |
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
  vanilla-style `RecipeHolder`/`Recipe`, read `getIngredients()` and expand each `Ingredient` (tags
  included) to a "any-of" set of items. A recipe is satisfied when **every** input group contains at
  least one discovered item. Tag slots (e.g. "any plank") are satisfied by discovering **any** match.
- **JEI bridge** (`jei/RecipeGate.java`): on `onRuntimeAvailable`, enumerate all categories/recipes,
  build an `item → recipes` reverse index, hide everything, then unhide what's already satisfied. On
  each discovery delta, only the recipes referencing the **new** items are re-checked and unhidden —
  no full re-scan, so it scales to thousand-recipe packs.
- **Lifecycle**: `onRuntimeAvailable` fires again after `/reload` and resource reloads, so the hidden
  state is rebuilt correctly each time. If JEI's runtime isn't up yet, discovery just accumulates and
  gets applied on the next build.

## Known limitations / things to verify

- **JEI method names**: `RecipeGate.java` has a header block listing every JEI runtime method it
  calls. The lifecycle/runtime accessors are confirmed against JEI's 1.21.x source; the
  `hideRecipes`/`unhideRecipes`/lookup signatures are correct for JEI 19.x but worth a 30-second
  check against the exact `-api` jar you depend on. If anything differs, that one file is the only
  place to fix.
- **Custom modded categories**: recipes that don't use vanilla-style ingredients can't be introspected,
  so they fall under `unresolvedRecipePolicy` (default: stay visible, to avoid permanently hiding
  modded content you can't reason about). A stricter, fully-general approach would harvest inputs by
  running each recipe through its category's layout builder with a capturing `IRecipeLayoutBuilder`;
  that's a larger piece of work and intentionally left out of v1.
- **`Recipe#getIngredients()`**: a few custom recipes implement `Recipe` but don't populate
  ingredients; those are caught and treated as unresolved.
- **Index build cost**: indexing happens once per JEI (re)load on the client thread. Fine for normal
  packs; for enormous packs you may see a brief hitch on load.
```
