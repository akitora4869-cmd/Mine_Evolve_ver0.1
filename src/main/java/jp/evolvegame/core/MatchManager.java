package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.*;

public final class MatchManager {
    private final ProjectEvolvePlugin plugin;
    private final Set<UUID> joined = new LinkedHashSet<>();
    private final Map<UUID, Role> roles = new HashMap<>();
    private MatchState state = MatchState.LOBBY;
    private UUID monsterId;
    private int monsterStage;
    private int evolution;
    private int taskId = -1;

    public MatchManager(ProjectEvolvePlugin plugin) {
        this.plugin = plugin;
        this.monsterStage = plugin.getConfig().getInt("monster.starting-stage", 1);
        startSidebarTask();
    }

    public void autoJoin(Player player) {
        if (state == MatchState.LOBBY) {
            if (joined.add(player.getUniqueId())) {
                roles.put(player.getUniqueId(), Role.SPECTATOR);
            }
            updateAllSidebars();
            return;
        }

        // During an active match, reconnecting players are restored if they were
        // already part of the match. New joiners simply keep the normal scoreboard.
        if (joined.contains(player.getUniqueId())) {
            updateSidebar(player);
        }
    }

    public void join(Player player) {
        if (state != MatchState.LOBBY) {
            player.sendMessage(ChatColor.RED + "ゲーム進行中のため参加できません。");
            return;
        }
        joined.add(player.getUniqueId());
        roles.put(player.getUniqueId(), Role.SPECTATOR);
        player.sendMessage(ChatColor.GREEN + "Project EVOLVE のロビーに参加しました。");
        updateAllSidebars();
    }

    public void leave(Player player) {
        joined.remove(player.getUniqueId());
        roles.remove(player.getUniqueId());
        if (Objects.equals(monsterId, player.getUniqueId())) monsterId = null;
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        updateAllSidebars();
    }

    public boolean start(CommandSender sender) {
        if (state != MatchState.LOBBY) {
            sender.sendMessage(ChatColor.RED + "すでにゲームが開始されています。");
            return false;
        }
        if (joined.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "参加者がいません。");
            return false;
        }

        List<UUID> players = new ArrayList<>(joined);
        if (monsterId == null || !joined.contains(monsterId)) {
            monsterId = players.get(0);
        }

        roles.put(monsterId, Role.MONSTER);
        Role[] hunterRoles = {Role.ASSAULT, Role.TRACKER, Role.MEDIC, Role.SUPPORT};
        int i = 0;
        for (UUID id : players) {
            if (id.equals(monsterId)) continue;
            roles.put(id, hunterRoles[Math.min(i, hunterRoles.length - 1)]);
            i++;
        }

        monsterStage = 1;
        evolution = 0;
        state = MatchState.RUNNING;

        forEachOnline(player -> {
            Role role = roles.getOrDefault(player.getUniqueId(), Role.SPECTATOR);
            player.sendTitle(ChatColor.DARK_RED + "PROJECT EVOLVE", ChatColor.WHITE + role.name(), 10, 50, 10);
            player.sendMessage(ChatColor.GOLD + "ゲーム開始！ 役割: " + ChatColor.WHITE + role.name());
        });
        updateAllSidebars();
        return true;
    }

    public void reset() {
        state = MatchState.LOBBY;
        monsterId = null;
        monsterStage = 1;
        evolution = 0;
        roles.replaceAll((id, role) -> Role.SPECTATOR);
        updateAllSidebars();
    }

    public void startTestMonster(Player player) {
        if (!joined.contains(player.getUniqueId())) joined.add(player.getUniqueId());
        monsterId = player.getUniqueId();
        roles.put(player.getUniqueId(), Role.MONSTER);
        monsterStage = 1;
        evolution = 0;
        state = MatchState.RUNNING;
        updateAllSidebars();
    }

    public void setMonsterStage(int stage) {
        monsterStage = Math.max(1, Math.min(3, stage));
        updateAllSidebars();
    }

    public void setMonster(Player player) {
        if (!joined.contains(player.getUniqueId())) joined.add(player.getUniqueId());
        monsterId = player.getUniqueId();
        roles.put(player.getUniqueId(), Role.MONSTER);
        updateAllSidebars();
    }

    public void addEvolution(int amount) {
        if (state != MatchState.RUNNING) return;
        evolution = Math.max(0, evolution + amount);
        int stage2 = plugin.getConfig().getInt("monster.evolution-needed.stage-2", 100);
        int stage3 = plugin.getConfig().getInt("monster.evolution-needed.stage-3", 250);

        if (monsterStage == 1 && evolution >= stage2) {
            monsterStage = 2;
            broadcastStageUp();
        }
        if (monsterStage == 2 && evolution >= stage3) {
            monsterStage = 3;
            broadcastStageUp();
        }
        updateAllSidebars();
    }

    private void broadcastStageUp() {
        forEachOnline(p -> p.sendTitle(
                ChatColor.DARK_PURPLE + "EVOLUTION",
                ChatColor.RED + "MONSTER STAGE " + monsterStage,
                10, 60, 20));
    }

    public MatchState getState() { return state; }
    public Set<UUID> getJoined() { return Collections.unmodifiableSet(joined); }
    public Role getRole(UUID id) { return roles.getOrDefault(id, Role.SPECTATOR); }
    public int getMonsterStage() { return monsterStage; }
    public int getEvolution() { return evolution; }
    public UUID getMonsterId() { return monsterId; }

    public void updateSidebar(Player player) {
        if (!plugin.getConfig().getBoolean("ui.sidebar", true)) return;
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = manager.getNewScoreboard();
        Objective obj = board.registerNewObjective("evolve", "dummy", ChatColor.DARK_RED + "PROJECT EVOLVE");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        if (state == MatchState.LOBBY) {
            obj.getScore(ChatColor.GRAY + "────────────").setScore(6);
            obj.getScore(ChatColor.WHITE + "Status: " + ChatColor.YELLOW + "LOBBY").setScore(5);
            obj.getScore(ChatColor.WHITE + "Players: " + ChatColor.GREEN + joined.size()).setScore(4);
            obj.getScore(ChatColor.AQUA + "Waiting for start...").setScore(3);
            obj.getScore(ChatColor.GRAY + "/evolve start").setScore(2);
            obj.getScore(ChatColor.DARK_GRAY + "v0.3.0").setScore(1);
        } else {
            Role role = getRole(player.getUniqueId());
            if (role == Role.MONSTER) {
                obj.getScore(ChatColor.GRAY + "────────────").setScore(7);
                obj.getScore(ChatColor.WHITE + "Role: " + ChatColor.DARK_RED + role.name()).setScore(6);
                obj.getScore(ChatColor.WHITE + "Stage: " + ChatColor.RED + monsterStage).setScore(5);
                String evoText;
                if (monsterStage >= 3) {
                    evoText = "MAX";
                } else {
                    int needed = monsterStage == 1
                            ? plugin.getConfig().getInt("monster.evolution-needed.stage-2", 100)
                            : plugin.getConfig().getInt("monster.evolution-needed.stage-3", 250);
                    evoText = evolution + " / " + needed;
                }
                obj.getScore(ChatColor.WHITE + "Evolution: " + ChatColor.LIGHT_PURPLE + evoText).setScore(4);
                obj.getScore(ChatColor.WHITE + "Players: " + ChatColor.GREEN + joined.size()).setScore(3);
                obj.getScore(ChatColor.WHITE + "State: " + ChatColor.YELLOW + state.name()).setScore(2);
                obj.getScore(ChatColor.DARK_GRAY + "v0.3.0").setScore(1);
            } else {
                obj.getScore(ChatColor.GRAY + "────────────").setScore(6);
                obj.getScore(ChatColor.WHITE + "Role: " + ChatColor.AQUA + role.name()).setScore(5);
                obj.getScore(ChatColor.WHITE + "Stage: " + ChatColor.RED + monsterStage).setScore(4);
                obj.getScore(ChatColor.WHITE + "Players: " + ChatColor.GREEN + joined.size()).setScore(3);
                obj.getScore(ChatColor.WHITE + "State: " + ChatColor.YELLOW + state.name()).setScore(2);
                obj.getScore(ChatColor.DARK_GRAY + "v0.3.0").setScore(1);
            }
        }

        player.setScoreboard(board);
    }

    public void updateAllSidebars() {
        forEachOnline(this::updateSidebar);
    }

    private void forEachOnline(java.util.function.Consumer<Player> consumer) {
        for (UUID id : joined) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) consumer.accept(p);
        }
    }

    private void startSidebarTask() {
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::updateAllSidebars, 20L, 40L);
    }

    public void shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }
}
