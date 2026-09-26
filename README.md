# Creatures of Westercraft

A Fabric mod for Minecraft 1.20.1 adding creatures to the Westercraft server, built around
**Viserion** — a fully rideable, GeckoLib-animated dragon.

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.19.3+
- Fabric API
- [GeckoLib](https://www.curseforge.com/minecraft/mc-mods/geckolib) 4

## Features

### Viserion

- **Rideable** — bond with Viserion (`/dragon allow <player>`, op-only) and mount him. Full manual
  flight: throttle-based forward/strafe movement, jump to ascend, sneak to descend/land, sprint +
  sneak + forward to dive.
- **Dracarys** — right-click while mounted for a sustained fire-breath stream of thrown fireballs;
  ignites terrain, melts blocks, and deals armor-bypassing damage. `/dragon revert` cleans up all
  dragonfire damage (melted blocks and ignited fire) on demand.
- **Combat** — bite/claw melee (left-click while mounted), a wild AI mode for unbonded encounters,
  and a "dragon-slaying weapon" damage gate: ordinary weapons do nothing at all, only gear tagged
  `creaturesofwesteros:dragon_slaying_weapons` (or projectiles tagged
  `creaturesofwesteros:dragonbane_projectiles`) get through.
- **Persistent and unique** — Viserion never despawns, and only one may exist on the server at a
  time.

### Commands

| Command | Effect |
|---|---|
| `/dragon allow <player>` | Permit a player to bond with Viserion (op-only) |
| `/dragon disallow <player>` | Revoke bonding permission (op-only) |
| `/dragon list` | List players currently allowed to bond (op-only) |
| `/dragon revert` | Restore all dragonfire-melted blocks and extinguish dragon-ignited fire (op-only) |

### In development

Additional creatures (Dragon, Direwolf, Wyvern) exist in the codebase but are currently disabled
pending further work.

## License

All Rights Reserved — see [LICENSE](LICENSE).
