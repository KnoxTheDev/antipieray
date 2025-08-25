# AntiPieRay

**blocks military-grade radar for block game.**

AntiPieRay is a high-performance [Paper](https://papermc.io) / [Folia](https://github.com/PaperMC/Folia) 1.21 plugin that prevents abuse of the F3 debug pie exploit for base-finding.  
It does this by intercepting outbound block entity packets and filtering out invisible, restricted block entities within a configurable radius of each player.

---

## ✨ Features
- 🚫 Blocks the F3 debug-pie “radar” exploit.
- ⚡ Ultra-fast Netty injection with minimal overhead.
- 🎯 Configurable restricted block entities and detection radius.
- 🧠 Optimized integer DDA ray-casting with LRU caching.
- 🌀 Full Folia support (uses RegionScheduler when available).
- 🔄 Hot reload via `/antipieray reload`.

---

## 📦 Installation
1. Download the compiled `anti-pieray.jar`.
2. Place it in your server’s `plugins/` folder.
3. Start or reload the server.
4. Edit the generated `plugins/anti-pieray/config.yml` to your needs.
5. Run `/antipieray reload` to apply changes without restarting.

---

## ⚙️ Configuration

Default `config.yml`:

```yaml
restricted-entities:
  - minecraft:chest
  - minecraft:barrel
  - minecraft:shulker_box
  - minecraft:hopper
  - minecraft:beacon
  - minecraft:brewing_stand
  - minecraft:spawner
  - minecraft:trial_spawner
  - minecraft:furnace
  - minecraft:blast_furnace
  - minecraft:smoker
  - minecraft:campfire
  - minecraft:jukebox
  - minecraft:enchanting_table
  - minecraft:conduit
  - minecraft:respawn_anchor
  - minecraft:beehive
  - minecraft:skull
  - minecraft:sculk_sensor
  - minecraft:sculk_catalyst
  - minecraft:sculk_shrieker
  - minecraft:structure_block
  - minecraft:crafter
  - minecraft:end_gateway
  - minecraft:end_portal
  - minecraft:lodestone

radius: 64
visibility-cache-size: 512
visibility-cache-ttl: 5000
folia-force-scheduler: false
debug: false
