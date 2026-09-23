# Infestations - Implementation batches

Work in order. Each batch should compile and be testable before the next.

See [SYSTEM.md](SYSTEM.md) for locked rules. See [TEST_MATRIX.md](TEST_MATRIX.md) for the manual checklist.

Sign-off pass: [REVIEW_BATCHES.md](REVIEW_BATCHES.md) (all seven closed).

---

## Batch 1 - Scaffold

- [x] Maven project, plugin.yml, config/messages/groups yaml
- [x] Bootstrap class, ConfigLoader, GroupLoader, Cache, Messages
- [x] `/infestation reload`
- [x] Documentation (`SYSTEM.md`, this file, `TEST_MATRIX.md`)

**Test:** `mvn package`, plugin enables, `/infestation reload` succeeds.

---

## Batch 2 - Infestation data + admin

- [x] Gson under `Data/infestations.json`
- [x] One record per province: id, group, severity
- [x] `/infestation set here|<id> <group> <severity>`, `clear`, `list`
- [x] Reject second infestation in the same province
- [x] Province from SimpleFactions grid

**Test:** set/list/clear, restart persistence, duplicate set fails.

---

## Batch 3 - Ambient spawn

- [x] SpawnPlanner (player origin, ring min/max, clearance, same province)
- [x] Tick players in infested provinces only
- [x] PDC `infestationId` + `kind=ambient`
- [x] Caps; silent remove when no players / chunk unload
- [x] Deaths do not change infestation

**Test:** walk infested province, `SwampGhoul` at ring; leave province, ambients stay; sea/uninfested quiet.

---

## Batch 4 - Lure place, protect, join UI

- [x] `FurniturePlaceEvent`: IF lure only if infested, else cancel; vanilla `BlockPlace` of the lure item is cancelled
- [x] Protect until plugin break
- [x] TextDisplay remaining/countdown; chunk load/unload + restart, no orphans
- [x] 20s join: broadcast + action bar
- [x] Right-click block to commit (window and after activate)
- [x] Non-joiners: leave warning

**Test:** no infestation = no place; chunk unload/reload display; restart; second lure denied.

---

## Batch 5 - Lure wave, any-kill, victory/fail, deserter

- [x] On activate: stop ambient, spawn budget near lure (radius 48)
- [x] Any killer decrements remaining; hologram updates
- [x] Remaining 0: victory particles, plugin-break lure, delete infestation
- [x] Wipe: plugin-break lure, keep infestation, resume ambient
- [x] Leave province while committed: lure fails (destroyed, infestation stays)
- [x] Late click still commits

**Test:** kill remaining to clear; walk out fails the lure; wipe fail keeps infestation.

---

## Batch 6 - Logout grace + login kill

- [x] Quit while committed: 5 min grace (persisted)
- [x] Rejoin in time: still committed, no kill
- [x] After grace: flag; on login fatal vanilla damage so death events fire
- [x] That death can fail the lure

**Test:** logout 10s and return; logout past grace and login-die.

---

## Batch 7 - Website infestation map mode

- [x] `infestation_data.json` upload
- [x] Paint `infestation_map.png` (yellow to dark red, no green)
- [x] Frontend mode, overlay, hover
- [x] Map docs

**Test:** set severe swamp, upload/regen, overlay dark red, hover `Infestation: Severe (Swamp Monsters)`; clear then transparent.
