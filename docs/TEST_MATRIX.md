# Infestations - Manual test matrix

Code reviews 1-7 are closed. Tick these on the live server. Player-facing strings must not use an em dash (U+2014).

---

## Batch 1 - Scaffold

- [ ] `mvn package` succeeds
- [ ] Plugin enables with TLibs, SimpleFactions, MythicMobs, ItemsAdder
- [ ] Default yaml copied to `plugins/Infestations/`
- [ ] `/infestation` with no args shows usage
- [ ] `/infestation reload` as op succeeds
- [ ] No permission: denial from `messages.yml`

---

## Batch 2 - Data + admin

- [ ] `/infestation set here swamp_mobs mild` in a **bog** province creates one
- [ ] Same command in plains (or any non-bog) is rejected (`wrong-terrain`)
- [ ] Second set on the same province is rejected
- [ ] `/infestation list` shows id, group, severity
- [ ] `/infestation clear here` removes it
- [ ] Restart: set infestation is still there
- [ ] Sea/water / unknown province rejected

---

## Batch 3 - Ambient

- [ ] Infested land: `SwampGhoul` spawns at configured ring, same province
- [ ] Cap respected
- [ ] Leave province: ambients stay (infestation remains); plugin does not `remove()` them
- [ ] Uninfested and sea: no plugin spawns
- [ ] Killing ambients does not clear the infestation

---

## Batch 4 - Lure UI

- [ ] Place `ia.tfmc:lure` with no infestation: cancelled + message
- [ ] Place with infestation: lure sits, hologram countdown, province warned
- [ ] Right-click base during window joins
- [ ] Second lure in same province denied
- [ ] Players cannot break the lure; plugin still can
- [ ] Chunk unload/reload and server restart: hologram returns, no extra displays

---

## Batch 5 - Wave + commit

- [ ] After 20s, ambients gone, lure mobs within 48 of lure
- [ ] Any player's kill decrements hologram remaining
- [ ] Remaining 0: particles, lure gone, infestation gone
- [ ] Committed player leaves province: lure destroyed, infestation still listed
- [ ] Late right-click still commits
- [ ] Non-joiner staying after activate: damage + leave message
- [ ] All committed die: lure destroyed, infestation still listed

---

## Batch 6 - Logout

- [ ] Logout while committed, return within 5 minutes: still committed, not killed
- [ ] Logout past 5 minutes, login: die (death event fires)
- [ ] That death can fail the lure if they were last

---

## Batch 7 - Map

- [ ] Set severe infestation, overlay is dark red
- [ ] Hover: `Infestation: Severe (Swamp Monsters)`
- [ ] Mild is yellow; no green anywhere on the overlay
- [ ] Clear infestation: province transparent on infestation mode
