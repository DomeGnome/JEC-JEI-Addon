# Comprehension Mind Map

Status: **concept recorded, research and design mockups in progress.** Nothing here is decided
unless it says so.

## The idea, in the author's words

> When a person picks stuff up, and "comprehends" parts of it, he gains wisdom about the world
> basically, he picks up patterns and coincidences.
>
> And what we are trying to do, is showcase, where things lead/build into, and where there seem to
> be gaps.

This is the heart of the feature. The rest of the mod hides what you have not comprehended. The mind
map is where comprehension becomes **inference**: from what you know, you can see the shape of what
you don't.

## The example that defines it

> I may know of wood, and I may know of string, and so if I pick up a crossbow, I wouldn't be able to
> say what the missing piece is, but I can intuit that it takes wood and string, and could guess how
> many parts are left, and if I also know of iron, I would have a good guess that the missing piece
> in the crossbow is a tripwire hook.

### Traced through the real 1.21.1 recipes

From the game's own data (`data/minecraft/recipe/`):

| Item | Recipe |
|---|---|
| `crossbow` | stick ×3 + iron ingot + string ×2 + **tripwire hook** (crafting, shaped) |
| `tripwire_hook` | iron ingot + stick + any planks (crafting, shaped, makes 2) |

So a player who knows wood, string and iron, and picks up a crossbow, should see:

- **wood → crossbow** and **string → crossbow**: known inputs, connected directly.
- **one missing piece** feeding into the crossbow: a dashed, unnamed node. The player can count it —
  "how many parts are left".
- that missing piece connected **down to iron and wood**, because those are *its* inputs, and the
  player knows them. That connection is what makes it a good guess.

One nuance the real recipe adds: iron feeds the crossbow **twice** — directly, and through the
tripwire hook. The map should show both.

The defining condition for a **good guess** is therefore: *a missing piece whose own inputs are all
comprehended.* The tripwire hook meets it exactly. A missing piece that needs further unknowns is a
weaker hint and should read as one.

## Vocabulary

| Term | Meaning |
|---|---|
| **Known node** | An item the player has comprehended. |
| **Missing piece** | An uncomprehended *input* of something the player knows. Sits *below* what it feeds. |
| **Good guess** | A missing piece whose own inputs are all known. The crossbow's tripwire hook. |
| **Lead** | An uncomprehended *output* reachable from known items — "where things lead". Sits *above*. |

Gaps are **never named**. The point is that the player infers them. A gap shows its *shape* in the
graph — what feeds it, what it feeds — not its identity. This matches the rest of the mod, which
never shows the player something they have not comprehended.

## Layout

> I am thinking of doing it bottom up, base materials at the bottom, and building up, though it will
> likely start wrapping into itself pretty quickly.

Crafting depth gives the layers: things nothing produces at the bottom, each crafted item above its
inputs. It *will* wrap into itself — vanilla has genuine cycles, such as iron ingot ⇄ iron block ⇄
iron nugget — so the layering has to break cycles deliberately rather than assume a tree.

## Interaction

> I'm thinking of it being like its own screen, that a player goes into when they press a key (by
> default C).

- Its own screen, opened by a key. **Default C.**
- **Known conflict:** C is vanilla's *Save Toolbar Activator* (C + a number saves a hotbar in
  creative). It will show as a conflict in Controls. The author asked for C; this is recorded so the
  choice is made knowingly.
- Interactive and fluid: pan, zoom, hover for detail, and motion that settles rather than snaps.

## Visual reference

Pathologic 2 and 3's *Thoughts* screen. The vocabulary worth borrowing:

- circular icon nodes; **size** carries importance
- thin lines connecting related nodes
- **dashed empty circles** for things not yet known
- a **red rim** on what is new, a **golden glow** on what is focused, **faded grey** for what is older
  or less relevant
- a short prose tooltip on hover
- the world behind, blurred and darkened
- a left sidebar of sections (their *Act I / II / III*)

## Open questions

To be decided with mockups, not assumed:

1. **Scope.** A mid-game player knows hundreds of items. Show everything, the neighbourhood of what
   was recently comprehended, or what the player clicks on? Reveal gaps only when zoomed in?
2. **Leads.** Show every uncomprehended output reachable from known items, or only the closest ones?
   They can easily outnumber known nodes.
3. **"How many parts are left."** A count on the node, or simply the number of dashed nodes feeding it?
4. **Recipes with alternatives.** Most items have one recipe that matters; some have many (planks
   from every log). Which one is drawn?
5. **Sections.** What plays the role of Pathologic's Acts — crafting depth, tiers, or categories?
6. **The key.** Keep C despite the conflict, or choose another default?

## Related, parked

A *comprehension lens* — hold a key over an item to see what it unlocks and what it is one step from —
was started before this feature was specified and is parked in a git stash. Its analysis layer
(per-item "missing pieces" and "one step away") overlaps heavily with what the map needs and is likely
to be reused rather than duplicated.
