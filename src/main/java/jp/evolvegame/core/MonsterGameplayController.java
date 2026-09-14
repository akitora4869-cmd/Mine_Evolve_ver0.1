package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Gameplay logic separated from the visual renderer.
 * This class is intentionally Model Engine independent: attack volumes,
 * wildlife/corpses and evolution can be retained when the renderer is replaced.
 */
public final class MonsterGameplayController implements Listener {
    private static final String WILDLIFE_TAG = "project_evolve_wildlife";
    private static final String CORPSE_TAG = "project_evolve_corpse";

    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;
    private final TestMonsterController monsterRenderer;
    private final Map<UUID, Long> lastAttackTick = new HashMap<>();
    private final Map<UUID, Corpse> corpses = new HashMap<>();

    private record Corpse(UUID interactionId, UUID displayId, int evolution, long expiresAtTick) {}

    public MonsterGameplayController(ProjectEvolvePlugin plugin, MatchManager match, TestMonsterController monsterRenderer) {
        this.plugin = plugin;
        this.match = match;
        this.monsterRenderer = monsterRenderer;
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireCorpses, 20L, 20L);
    }

    public void spawnTestWildlife(Player center, int requestedCount) {
        int count = Math.max(1, Math.min(30, requestedCount));
        EntityType[] types = {EntityType.PIG, EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN};

        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0 * i / count) + (Math.random() * 0.35);
            double radius = 4.0 + Math.random() * 5.0;
            Location loc = center.getLocation().clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius);
            loc.setY(center.getWorld().getHighestBlockYAt(loc) + 1.0);

            Entity spawned = center.getWorld().spawnEntity(loc, types[i % types.length]);
            spawned.addScoreboardTag(WILDLIFE_TAG);
            if (spawned instanceof LivingEntity living) {
                living.setCustomName(ChatColor.GREEN + "Wildlife");
                living.setCustomNameVisible(false);
                living.setPersistent(true);
            }
        }
        center.sendMessage(ChatColor.GREEN + "テスト用Wildlifeを " + count + " 体スポーンしました。");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMonsterSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!monsterRenderer.isControlled(player)) return;
        performMonsterAttack(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void cancelVanillaMonsterHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!monsterRenderer.isControlled(player)) return;
        // Monster melee is resolved by our large forward hit volume instead.
        event.setCancelled(true);
    }

    private void performMonsterAttack(Player monster) {
        int stage = match.getMonsterStage();
        int cooldown = plugin.getConfig().getInt("monster.attack.cooldown-ticks", 16);
        long now = Bukkit.getCurrentTick();
        long last = lastAttackTick.getOrDefault(monster.getUniqueId(), Long.MIN_VALUE / 2);
        if (now - last < cooldown) return;
        lastAttackTick.put(monster.getUniqueId(), now);

        String key = "monster.attack.stage-" + stage;
        double damage = plugin.getConfig().getDouble(key + ".damage", 6.0 + stage * 2.0);
        double range = plugin.getConfig().getDouble(key + ".range", 3.0 + stage);
        double width = plugin.getConfig().getDouble(key + ".width", 2.0 + stage);

        Location origin = monster.getEyeLocation();
        Vector forward = origin.getDirection().normalize();
        int hits = 0;

        Collection<Entity> candidates = monster.getWorld().getNearbyEntities(
                monster.getLocation(), range + width, range + width, range + width);
        LivingEntity body = monsterRenderer.getBody(monster.getUniqueId());

        for (Entity entity : candidates) {
            if (!(entity instanceof LivingEntity target)) continue;
            if (entity.getUniqueId().equals(monster.getUniqueId())) continue;
            if (body != null && entity.getUniqueId().equals(body.getUniqueId())) continue;
            if (entity instanceof ArmorStand) continue;

            boolean isWildlife = entity.getScoreboardTags().contains(WILDLIFE_TAG);
            boolean isHunter = entity instanceof Player p
                    && match.getJoined().contains(p.getUniqueId())
                    && match.getRole(p.getUniqueId()) != Role.MONSTER;
            if (!isWildlife && !isHunter) continue;

            BoundingBox box = entity.getBoundingBox();
            Vector targetCenter = new Vector(
                    (box.getMinX() + box.getMaxX()) * 0.5,
                    (box.getMinY() + box.getMaxY()) * 0.5,
                    (box.getMinZ() + box.getMaxZ()) * 0.5);
            Vector toTarget = targetCenter.subtract(origin.toVector());
            double forwardDistance = toTarget.dot(forward);
            if (forwardDistance < 0.0 || forwardDistance > range) continue;

            Vector closestOnAxis = forward.clone().multiply(forwardDistance);
            double lateralDistance = toTarget.clone().subtract(closestOnAxis).length();
            double targetPadding = Math.max(box.getWidthX(), box.getWidthZ()) * 0.5;
            if (lateralDistance > (width * 0.5) + targetPadding) continue;

            target.damage(damage);
            Vector push = forward.clone().setY(Math.max(0.15, forward.getY() * 0.25)).multiply(0.65 + 0.15 * stage);
            target.setVelocity(target.getVelocity().add(push));
            hits++;
        }

        monster.getWorld().playSound(monster.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.75f + stage * 0.08f);
        monster.sendActionBar(ChatColor.RED + "MONSTER ATTACK" + ChatColor.GRAY + "  Hit: " + hits);
    }

    @EventHandler
    public void onWildlifeDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!dead.getScoreboardTags().contains(WILDLIFE_TAG)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        int evolution = evolutionFor(dead.getType());
        createCorpse(dead.getLocation(), evolution);
    }

    private int evolutionFor(EntityType type) {
        return switch (type) {
            case COW -> plugin.getConfig().getInt("wildlife.evolution.cow", 25);
            case SHEEP -> plugin.getConfig().getInt("wildlife.evolution.sheep", 20);
            case CHICKEN -> plugin.getConfig().getInt("wildlife.evolution.chicken", 10);
            default -> plugin.getConfig().getInt("wildlife.evolution.pig", 20);
        };
    }

    private void createCorpse(Location location, int evolution) {
        Location loc = location.clone().add(0, 0.25, 0);

        ItemDisplay display = (ItemDisplay) location.getWorld().spawnEntity(loc, EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(Material.ROTTEN_FLESH));
        display.setCustomName(ChatColor.DARK_RED + "死骸" + ChatColor.LIGHT_PURPLE + "  +" + evolution + " Evolution");
        display.setCustomNameVisible(true);
        display.addScoreboardTag(CORPSE_TAG);
        display.setPersistent(true);

        Interaction interaction = (Interaction) location.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionWidth(1.5f);
        interaction.setInteractionHeight(1.2f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag(CORPSE_TAG);
        interaction.setPersistent(true);

        int lifeSeconds = plugin.getConfig().getInt("wildlife.corpse-expire-seconds", 120);
        corpses.put(interaction.getUniqueId(), new Corpse(
                interaction.getUniqueId(), display.getUniqueId(), evolution,
                Bukkit.getCurrentTick() + lifeSeconds * 20L));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCorpseInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!clicked.getScoreboardTags().contains(CORPSE_TAG)) return;
        Corpse corpse = corpses.get(clicked.getUniqueId());
        if (corpse == null) return;

        Player player = event.getPlayer();
        if (!monsterRenderer.isControlled(player)) {
            player.sendActionBar(ChatColor.GRAY + "Monsterだけが捕食できます。");
            return;
        }

        double feedRange = plugin.getConfig().getDouble("wildlife.feed-range", 3.5);
        if (!player.getWorld().equals(clicked.getWorld()) || player.getLocation().distance(clicked.getLocation()) > feedRange) {
            player.sendActionBar(ChatColor.RED + "死骸に近づいてください。");
            return;
        }

        consumeCorpse(player, corpse);
        event.setCancelled(true);
    }

    private void consumeCorpse(Player monster, Corpse corpse) {
        int beforeStage = match.getMonsterStage();
        match.addEvolution(corpse.evolution());
        removeCorpse(corpse.interactionId());

        monster.getWorld().playSound(monster.getLocation(), Sound.ENTITY_GENERIC_EAT, 1.0f, 0.65f);
        monster.sendMessage(ChatColor.LIGHT_PURPLE + "+" + corpse.evolution() + " Evolution" + ChatColor.GRAY + " (捕食)");

        if (match.getMonsterStage() != beforeStage) {
            monsterRenderer.refreshMonsterBody();
        }
    }

    private void expireCorpses() {
        long now = Bukkit.getCurrentTick();
        for (Corpse corpse : new ArrayList<>(corpses.values())) {
            if (now >= corpse.expiresAtTick()) removeCorpse(corpse.interactionId());
        }
    }

    private void removeCorpse(UUID interactionId) {
        Corpse corpse = corpses.remove(interactionId);
        if (corpse == null) return;
        Entity interaction = Bukkit.getEntity(corpse.interactionId());
        Entity display = Bukkit.getEntity(corpse.displayId());
        if (interaction != null) interaction.remove();
        if (display != null) display.remove();
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(corpses.keySet())) removeCorpse(id);
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(WILDLIFE_TAG)) entity.remove();
            }
        }
        lastAttackTick.clear();
    }
}
