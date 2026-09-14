package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class EvolveCommand implements CommandExecutor, TabCompleter {
    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;
    private final TestMonsterController monsterController;
    private final MonsterGameplayController gameplayController;

    public EvolveCommand(ProjectEvolvePlugin plugin, MatchManager match, TestMonsterController monsterController, MonsterGameplayController gameplayController) {
        this.plugin = plugin;
        this.match = match;
        this.monsterController = monsterController;
        this.gameplayController = gameplayController;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "/evolve join, leave, start, status, monster, levelup, wildlife, setmonster, setevolution, reset");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "join" -> {
                if (!(sender instanceof Player p)) return true;
                match.join(p);
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return true;
                monsterController.restorePlayer(p);
                match.leave(p);
            }
            case "status" -> sender.sendMessage(ChatColor.AQUA + "State=" + match.getState()
                    + " Stage=" + match.getMonsterStage()
                    + " Evolution=" + match.getEvolution()
                    + " Players=" + match.getJoined().size());
            case "start" -> {
                if (!admin(sender)) return true;
                match.start(sender);
            }
            case "monster" -> {
                if (!admin(sender)) return true;
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "プレイヤーが見つかりません。");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(ChatColor.RED + "/evolve monster <player>");
                    return true;
                }
                monsterController.becomeTestMonster(target);
                sender.sendMessage(ChatColor.GREEN + target.getName() + " をテストMonsterにしました。");
            }
            case "levelup" -> {
                if (!admin(sender)) return true;
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "プレイヤーが見つかりません。");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(ChatColor.RED + "/evolve levelup <player>");
                    return true;
                }
                monsterController.levelUp(target);
            }
            case "wildlife" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "プレイヤーから実行してください。");
                    return true;
                }
                int count = plugin.getConfig().getInt("wildlife.test-spawn-count", 6);
                if (args.length >= 2) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(ChatColor.RED + "数値を指定してください。例: /evolve wildlife 6");
                        return true;
                    }
                }
                gameplayController.spawnTestWildlife(p, count);
            }
            case "reset" -> {
                if (!admin(sender)) return true;
                monsterController.restoreAll();
                gameplayController.clearAll();
                match.reset();
                sender.sendMessage(ChatColor.GREEN + "ゲーム状態をリセットしました。");
            }
            case "setmonster" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/evolve setmonster <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "プレイヤーが見つかりません。");
                    return true;
                }
                match.setMonster(target);
                sender.sendMessage(ChatColor.GREEN + target.getName() + " をMonsterに設定しました。");
            }
            case "setevolution" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/evolve setevolution <amount>");
                    return true;
                }
                try {
                    int before = match.getMonsterStage();
                    match.addEvolution(Integer.parseInt(args[1]));
                    if (match.getMonsterStage() != before) monsterController.refreshMonsterBody();
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "数値を指定してください。");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("evolve.admin")) return true;
        sender.sendMessage(ChatColor.RED + "権限がありません。");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("join", "leave", "status", "start", "monster", "levelup", "wildlife", "reset", "setmonster", "setevolution").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("setmonster")
                || args[0].equalsIgnoreCase("monster")
                || args[0].equalsIgnoreCase("levelup"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
