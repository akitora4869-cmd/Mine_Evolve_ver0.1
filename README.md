# Project EVOLVE v0.3.0

Paper 1.21.1 / Java 21 test build for the monster gameplay loop.

## Added in v0.3.0

- Auto lobby sidebar from previous builds
- Test Monster command: `/evolve monster [player]`
- Manual stage test: `/evolve levelup [player]`
- Stage 1 body: Skeleton, scale 1.5x
- Stage 2 body: Wither Skeleton, scale 2.0x
- Stage 3 body: Wither, scale 1.6x
- Stage-specific large forward melee hit volume
- Scaled vanilla monster body used as the temporary incoming-damage hitbox
- Test wildlife spawn command: `/evolve wildlife [count]`
- Wildlife death creates a corpse marker
- Monster right-clicks a corpse to feed
- Feeding grants Evolution points
- Automatic Stage 1 -> 2 -> 3 evolution at configured thresholds
- Monster-only sidebar shows `Evolution: current / required`, then `MAX` at Stage 3

## Quick test

1. Join the Paper 1.21.1 server.
2. Run `/evolve monster`.
3. Run `/evolve wildlife 8`.
4. Swing your arm while facing wildlife to use the Monster melee hit volume.
5. When wildlife dies, right-click the corpse to feed.
6. Reach 100 Evolution for Stage 2 and 250 total Evolution for Stage 3.

`/evolve levelup` and `/evolve setevolution <amount>` remain available for direct testing.

## Test values

- Stage 1: Skeleton / 1.5x / 6 damage / range 3.0 / width 2.2
- Stage 2: Wither Skeleton / 2.0x / 9 damage / range 4.0 / width 3.0
- Stage 3: Wither / 1.6x / 12 damage / range 5.5 / width 4.5
- Pig: +20 Evolution
- Cow: +25 Evolution
- Sheep: +20 Evolution
- Chicken: +10 Evolution

All values can be changed in `config.yml`.

## Architecture note

The attack volume, feeding, wildlife and Evolution logic live outside the temporary vanilla-Mob renderer. The current Skeleton/Wither Skeleton/Wither visuals can therefore be replaced later by Model Engine models without rewriting the core gameplay loop.
