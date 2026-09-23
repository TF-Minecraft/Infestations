# Infestations

> Seed monster infestations across TF-Minecraft provinces and let players clear them with lure raids.

Infestations ties MythicMobs encounters to SimpleFactions provinces. An infested
province harasses players with ambient monsters until a party places a lure,
commits to the fight, and defeats every monster the lure draws in.

## Features

- **Province infestations** — administrators assign a configured mob group and
  severity to a land province, with at most one infestation per province.
- **Ambient harassment** — MythicMobs spawn around players inside an infested
  province, with per-severity caps, intervals, and spawn rings, plus optional
  night-only, minimum-height, and terrain rules.
- **Lure raids** — an InteractibleFurniture lure opens a join window, then
  releases a paced wave; defeating the full wave clears the infestation.
- **Committed parties** — leaving the province or losing the whole party fails
  the lure, bystanders are warned and damaged, and logged-out players get a
  grace period.
- **Optional spread** — idle infestations can worsen and spread to neighbouring
  land, including across a single water or sea province.
- **Map and persistence** — infestation severity is exported to the
  SimpleFactions map service, and infestation and lure state survive restarts.

Originally created by [Drefvelin](https://github.com/Drefvelin).

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/Infestations/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and pre-existing
material retain their own licenses.
