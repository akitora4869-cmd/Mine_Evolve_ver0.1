package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Experimental controller used before the custom Model Engine monster is ready.
 * The real player remains the movement/controller entity while an AI-disabled mob
 * is teleported onto the player every tick as the visible monster body.
 */
public final class TestMonsterController implements Listener {
    private static final String CONTROLLED_TAG = "project_evolve_controlled_monster";

    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;
    private final Map<UUID, LivingEntity> bodies = new HashMap<>();
    private final Map<UUID, Boolean> oldAllowFlight = new HashMap<>();
    private final Map<UUID, Boolean> oldFlying = new HashMap<>();
    private int syncTaskId = -1;

    public TestMonsterController(ProjectEvolvePlugin plugin, MatchManager match) {
        this.plugin = plugin;
        this.match = match;
        startSyncTask();
    }

    public void becomeTestMonster(Player player) {
        match.startTestMonster(player);
        showStage(player, 1);
        player.sendMessage(ChatColor.DARK_RED + "テストMonsterになりました。"
                + ChatColor.GRAY + " Stage 1 = Skeleton");
    }

    public boolean levelUp(Player player) {
        if (!isControlled(player)) {
            player.sendMessage(ChatColor.RED + "先に /evolve monster を実行してください。");
            return false;
        }
        int current = match.getMonsterStage();
        if (current >= 3) {
            player.sendMessage(ChatColor.YELLOW + "すでにStage 3です。");
            return false;
        }
        match.setMonsterStage(current + 1);
        showStage(player, current + 1);
        return true;
    }

    public void refreshMonsterBody() {
        UUID id = match.getMonsterId();
        if (id == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) {
            showStage(player, match.getMonsterStage());
        }
    }

    private void showStage(Player player, int stage) {
        removeBody(player.getUniqueId());

        if (!oldAllowFlight.containsKey(player.getUniqueId())) {
            oldAllowFlight.put(player.getUniqueId(), player.getAllowFlight());
            oldFlying.put(player.getUniqueId(), player.isFlying());
        }

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                false
        ));

        EntityType type = switch (stage) {
            case 2 -> EntityType.WITHER_SKELETON;
            case 3 -> EntityType.WITHER;
            default -> EntityType.SKELETON;
        };

        Location spawn = player.getLocation().clone();
        LivingEntity body = (LivingEntity) player.getWorld().spawnEntity(spawn, type);
        body.setAI(false);
        body.setSilent(true);
        body.setInvulnerable(false); // damage is intercepted and forwarded to the controller
        body.setGravity(false);
        body.setCollidable(false);
        body.setPersistent(true);
        body.addScoreboardTag(CONTROLLED_TAG);
        body.setCustomNameVisible(false);

        double scale = switch (stage) {
            case 2 -> plugin.getConfig().getDouble("monster.test-body.stage-2-scale", 2.0);
            case 3 -> plugin.getConfig().getDouble("monster.test-body.stage-3-scale", 1.6);
            default -> plugin.getConfig().getDouble("monster.test-body.stage-1-scale", 1.5);
        };
        AttributeInstance scaleAttribute = body.getAttribute(Attribute.GENERIC_SCALE);
        if (scaleAttribute != null) {
            scaleAttribute.setBaseValue(scale);
        }
        bodies.put(player.getUniqueId(), body);

        boolean witherStage = stage >= 3;
        player.setAllowFlight(witherStage || oldAllowFlight.getOrDefault(player.getUniqueId(), false));
        if (!witherStage && player.isFlying() && !oldFlying.getOrDefault(player.getUniqueId(), false)) {
            player.setFlying(false);
        }

        String mobName = switch (stage) {
            case 2 -> "WITHER SKELETON";
            case 3 -> "WITHER";
            default -> "SKELETON";
        };
        player.sendTitle(
                ChatColor.DARK_PURPLE + "EVOLUTION",
                ChatColor.RED + "STAGE " + stage + ChatColor.GRAY + " - " + mobName,
                5, 35, 10
        );
        match.updateAllSidebars();
    }

    private void startSyncTask() {
        syncTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            UUID monsterId = match.getMonsterId();
            if (monsterId == null) return;

            Player player = Bukkit.getPlayer(monsterId);
            if (player == null || !player.isOnline() || !isControlled(player)) return;

            LivingEntity body = bodies.get(monsterId);
            if (body == null || !body.isValid() || body.isDead()) {
                showStage(player, match.getMonsterStage());
                return;
            }

            Location target = player.getLocation().clone();
            target.setYaw(player.getLocation().getYaw());
            target.setPitch(player.getLocation().getPitch());
            body.teleport(target);
        }, 1L, 1L);
    }

    public LivingEntity getBody(UUID controllerId) {
        return bodies.get(controllerId);
    }

    public boolean isControlled(Player player) {
        return player.getUniqueId().equals(match.getMonsterId()) && bodies.containsKey(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBodyDamage(EntityDamageEvent event) {
        UUID controller = findController(event.getEntity());
        if (controller == null) return;

        event.setCancelled(true);
        Player player = Bukkit.getPlayer(controller);
        if (player == null || !player.isOnline() || player.isDead()) return;

        double damage = event.getFinalDamage();
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity attacker = byEntity.getDamager();
            player.damage(damage, attacker);
        } else {
            player.damage(damage);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bodies.containsKey(event.getPlayer().getUniqueId())) {
            restorePlayer(event.getPlayer());
        }
    }

    private UUID findController(Entity body) {
        if (!body.getScoreboardTags().contains(CONTROLLED_TAG)) return null;
        for (Map.Entry<UUID, LivingEntity> entry : bodies.entrySet()) {
            if (entry.getValue().getUniqueId().equals(body.getUniqueId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void restorePlayer(Player player) {
        removeBody(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.INVISIBILITY);

        boolean allowFlight = oldAllowFlight.getOrDefault(player.getUniqueId(), false);
        boolean flying = oldFlying.getOrDefault(player.getUniqueId(), false);
        player.setAllowFlight(allowFlight);
        if (allowFlight) player.setFlying(flying);

        oldAllowFlight.remove(player.getUniqueId());
        oldFlying.remove(player.getUniqueId());
    }

    public void restoreAll() {
        for (UUID id : bodies.keySet().toArray(UUID[]::new)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) restorePlayer(player);
            else removeBody(id);
        }
    }

    private void removeBody(UUID id) {
        LivingEntity old = bodies.remove(id);
        if (old != null && old.isValid()) old.remove();
    }

    public void shutdown() {
        if (syncTaskId != -1) Bukkit.getScheduler().cancelTask(syncTaskId);
        restoreAll();
    }
}
