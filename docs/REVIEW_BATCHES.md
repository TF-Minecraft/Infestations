# Infestations - Review batches

**All seven reviews closed** (30 Aug 2026). Code sign-off is done. Manual in-game checks: [TEST_MATRIX.md](TEST_MATRIX.md).

Locked rules: [SYSTEM.md](SYSTEM.md).

Known shape vs the original plan:

- There is no `commit/` package. Logout, deserter, and wipe live in `InfestationManager`.
- Lure is InteractibleFurniture (`ia.tfmc:lure`), not a solid block. `plugin.yml` also `depend`s InteractibleFurniture.

---

## Review 1 - Docs + scaffold

**Status:** closed

**Files:** `docs/SYSTEM.md`, `pom.xml`, `plugin.yml`, `Infestations.java`, `config.yml`, `messages.yml`, `groups.yml`, loaders, `Cache`, `Messages`, `CommandManager` reload path.

**Confirm:**
- Rules in SYSTEM.md still match what we want
- `depend: [TLibs, SimpleFactions, MythicMobs, ItemsAdder, InteractibleFurniture]`
- Bootstrap only in main class; `/infestation reload`
- `swamp_mobs` / `SwampGhoul`; lure path `ia.tfmc:lure`
- No em dash in `messages.yml`

---

## Review 2 - Data + admin

**Status:** closed

**Files:** `infestation/Infestation.java`, `Severity.java`, `database/*`, `CommandManager` set/clear/list, `utils/Provinces.java`.

**Confirm:**
- One infestation per province; second `set` rejected
- Persist `Data/infestations.json`
- `here` and numeric id; skip water/sea
- Clear does not leave a lure behind
- Chunk load: lure furniture not in saved state is removed

---

## Review 3 - Ambient spawn

**Status:** closed

**Files:** `spawn/SpawnPlanner.java`, `MythicSpawner.java`, `Keys.java`, `AmbientSpawnService.java`.

**Confirm:**
- Ring min/max, 3x3 air + floor, same province
- PDC `infestationId` + `kind=ambient`
- Cap and interval from severity
- Deaths do not clear infestation
- Leave province / chunk unload silent `remove()` (nearby scan, not a full-world sweep)

---

## Review 4 - Lure place, protect, hologram, join window

**Status:** closed

**Files:** `InfestationManager` place/break/interact, `lure/LureHologram.java`, `lure/LureFurniture.java`.

**Confirm:**
- No infestation: place cancelled
- One lure per province
- Protected unless plugin remove; orphan IF lure on interact is deleted, not picked up
- TextDisplay PDC, proximity, chunk load/unload, orphan cleanup
- 20s window; warn province; right-click joins during window **and** after activate

---

## Review 5 - Wave, any-kill, victory, wipe, deserter

**Status:** closed

**Files:** `InfestationManager` activate/wave/death/wipe/deserter.

**Confirm:**
- Activate: ambient stopped; spawn within `lure-spawn-radius` (48)
- Remaining is budget ints; any death of lure-tagged mob counts
- Despawn / chunk unload re-queues, not a kill
- Remaining 0: particles, plugin-break lure, infestation deleted
- All committed dead (grace / pending login-kill still count as in): lure destroyed, infestation stays, ambient can resume
- Committed leave province: lure fails (destroyed, infestation stays)
- Non-joiners still in province after activate: damage + leave message
- Late click commits

---

## Review 6 - Logout grace

**Status:** closed

**Files:** `InfestationManager` quit/join + persisted `logoutGraceUntil` / `deathOnLogin`.

**Confirm:**
- 5 min grace persisted
- Return in time: still committed, not killed (login at logout location)
- After grace: flag; login uses vanilla `damage` so death events fire
- That death can fail the lure if they were last

---

## Review 7 - Website map mode

**Status:** closed

**Files:** `map/InfestationMapExport.java`; ProvinceSystem `infestationgen.py`, `data_routes.py`, `regeneration.py`; frontend `types.ts`, `MapToolbar.tsx`, `useMapModeData.ts`, `MapCanvas.tsx`, `useProvinceHover.ts`.

**Confirm:**
- Upload `infestation_data` (not a SimpleFactions `Province` field)
- Overlay yellow → dark red, no green; empty transparent; skip water/sea
- Regen on fullregen and on upload; overlay `no-store`
- Hover `Infestation: Severe (swamp_mobs)`
- Toolbar option; no nation region fetch for this mode
