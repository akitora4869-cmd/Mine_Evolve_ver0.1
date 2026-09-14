package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {
    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;

    public PlayerListener(ProjectEvolvePlugin plugin, MatchManager match) {
        this.plugin = plugin;
        this.match = match;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Join one tick later so Paper has finished the normal join sequence
        // before EVOLVE replaces the player's sidebar.
        Bukkit.getScheduler().runTask(plugin, () -> match.autoJoin(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (match.getJoined().contains(event.getPlayer().getUniqueId())) {
            match.leave(event.getPlayer());
        }
    }
}
