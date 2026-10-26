package id.borderland;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BorderlandPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private File dataFile;
    private YamlConfiguration data;
    private final Map<String, Location> foodStations = new LinkedHashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private Game game;

    /** Sel voting satu pemain: tempat berdiri + kotak tebakan. */
    public record Cell(Location spawn, Location box) {
    }

    public List<Cell> cells() {
        return cells;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        PluginCommand cmd = getCommand("borderland");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.cleanup();
        }
    }

    // ------------------------------------------------------------------ data

    private void loadData() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        foodStations.clear();
        cells.clear();
        ConfigurationSection cs = data.getConfigurationSection("cells");
        if (cs != null) {
            List<String> keys = new ArrayList<>(cs.getKeys(false));
            keys.sort((x, y) -> {
                try {
                    return Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
                } catch (NumberFormatException ex) {
                    return x.compareTo(y);
                }
            });
            for (String k : keys) {
                Location sp = cs.getLocation(k + ".spawn");
                Location bx = cs.getLocation(k + ".box");
                if (sp != null && bx != null && sp.getWorld() != null) {
                    cells.add(new Cell(sp, bx));
                }
            }
        }
        ConfigurationSection sec = data.getConfigurationSection("food");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Location l = sec.getLocation(k);
                if (l != null && l.getWorld() != null) {
                    foodStations.put(key(l), l);
                }
            }
        }
    }

    private void saveData() {
        data.set("cells", null);
        int ci = 0;
        for (Cell c : cells) {
            data.set("cells." + ci + ".spawn", c.spawn());
            data.set("cells." + ci + ".box", c.box());
            ci++;
        }
        data.set("food", null);
        int i = 0;
        for (Location l : foodStations.values()) {
            data.set("food." + i, l);
            i++;
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Gagal menyimpan data.yml: " + ex.getMessage());
        }
    }

    public Location loc(String name) {
        return data.getLocation(name);
    }

    private void setLoc(String name, Location l) {
        data.set(name, l);
        saveData();
    }

    public Collection<Location> stations() {
        return foodStations.values();
    }

    public static String key(Location l) {
        String w = l.getWorld() == null ? "?" : l.getWorld().getName();
        return w + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    public Game game() {
        return game;
    }

    public void clearGame(Game g) {
        if (game == g) {
            game = null;
        }
    }

    // -------------------------------------------------------------- commands

    private void say(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    /** Lokasi blok yang ditatap admin, atau posisi admin kalau pakai "here". */
    private Location target(Player p, boolean here) {
        if (here) {
            return p.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        Block b = p.getTargetBlockExact(6);
        if (b == null) {
            return null;
        }
        return b.getLocation().add(0.5, 0.5, 0.5);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("skip")) {
            if (s instanceof Player p && game != null && game.isParticipant(p)) {
                game.onSkipCommand(p);
            } else {
                say(s, "Tidak ada game yang berjalan.", NamedTextColor.RED);
            }
            return true;
        }
        if (!s.hasPermission("borderland.admin")) {
            say(s, "Kamu tidak punya izin.", NamedTextColor.RED);
            return true;
        }
        if (a.length == 0) {
            say(s, "/bl start | stop | status", NamedTextColor.YELLOW);
            say(s, "/bl setarena | setvoteroom | setlobby  (pakai posisimu)", NamedTextColor.YELLOW);
            say(s, "/bl addcell (berdiri di sel, tatap kotak) | clearcells", NamedTextColor.YELLOW);
            say(s, "/bl setvotebox [here] | addfood [here] | clearfood", NamedTextColor.YELLOW);
            return true;
        }
        boolean here = a.length > 1 && a[1].equalsIgnoreCase("here");
        switch (a[0].toLowerCase()) {
            case "start": {
                if (game != null) {
                    say(s, "Game sedang berjalan. Pakai /bl stop dulu.", NamedTextColor.RED);
                    return true;
                }
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                int min = getConfig().getInt("min-players", 3);
                if (players.size() < min) {
                    say(s, "Butuh minimal " + min + " pemain online.", NamedTextColor.RED);
                    return true;
                }
                boolean cellsOk = cells.size() >= players.size();
                if (!cellsOk && (loc("votebox") == null || loc("voteroom") == null)) {
                    say(s, "Atur sel voting (/bl addcell, butuh " + players.size() + " sel; baru ada "
                            + cells.size() + ") atau /bl setvotebox + /bl setvoteroom.", NamedTextColor.RED);
                    return true;
                }
                if (foodStations.isEmpty()) {
                    say(s, "Peringatan: belum ada tempat makan (/bl addfood).", NamedTextColor.GOLD);
                }
                game = new Game(this, players);
                game.begin();
                return true;
            }
            case "stop": {
                if (game == null) {
                    say(s, "Tidak ada game yang berjalan.", NamedTextColor.RED);
                    return true;
                }
                game.cleanup();
                say(s, "Game dihentikan.", NamedTextColor.GREEN);
                return true;
            }
            case "status": {
                say(s, "Game: " + (game == null ? "tidak berjalan" : "berjalan"), NamedTextColor.YELLOW);
                say(s, "Arena: " + (loc("arena") != null) + " | Ruang voting: " + (loc("voteroom") != null)
                        + " | Kotak voting: " + (loc("votebox") != null) + " | Lobby: " + (loc("lobby") != null),
                        NamedTextColor.YELLOW);
                say(s, "Tempat makan: " + foodStations.size() + " | Sel voting: " + cells.size(), NamedTextColor.YELLOW);
                return true;
            }
            case "setarena":
            case "setvoteroom":
            case "setlobby": {
                if (!(s instanceof Player p)) {
                    say(s, "Hanya untuk pemain.", NamedTextColor.RED);
                    return true;
                }
                String name = a[0].toLowerCase().substring(3); // arena / voteroom / lobby
                setLoc(name, p.getLocation());
                say(s, "Posisi '" + name + "' disimpan.", NamedTextColor.GREEN);
                return true;
            }
            case "setvotebox": {
                if (!(s instanceof Player p)) {
                    say(s, "Hanya untuk pemain.", NamedTextColor.RED);
                    return true;
                }
                Location l = target(p, here);
                if (l == null) {
                    say(s, "Tatap sebuah blok, atau pakai: /bl setvotebox here", NamedTextColor.RED);
                    return true;
                }
                setLoc("votebox", l);
                say(s, "Kotak voting disimpan.", NamedTextColor.GREEN);
                return true;
            }
            case "addcell": {
                if (!(s instanceof Player p)) {
                    say(s, "Hanya untuk pemain.", NamedTextColor.RED);
                    return true;
                }
                Location box = target(p, false);
                if (box == null) {
                    say(s, "Berdiri di dalam sel, tatap kotak tebakan (blok di bawah/dekat furniturnya), lalu ketik lagi.",
                            NamedTextColor.RED);
                    return true;
                }
                cells.add(new Cell(p.getLocation(), box));
                saveData();
                say(s, "Sel #" + cells.size() + " disimpan.", NamedTextColor.GREEN);
                return true;
            }
            case "clearcells": {
                cells.clear();
                saveData();
                say(s, "Semua sel dihapus.", NamedTextColor.GREEN);
                return true;
            }
            case "addfood": {
                if (!(s instanceof Player p)) {
                    say(s, "Hanya untuk pemain.", NamedTextColor.RED);
                    return true;
                }
                Location l = target(p, here);
                if (l == null) {
                    say(s, "Tatap sebuah blok, atau pakai: /bl addfood here", NamedTextColor.RED);
                    return true;
                }
                foodStations.put(key(l), l);
                saveData();
                say(s, "Tempat makan ditambah. Total: " + foodStations.size(), NamedTextColor.GREEN);
                return true;
            }
            case "clearfood": {
                foodStations.clear();
                saveData();
                say(s, "Semua tempat makan dihapus.", NamedTextColor.GREEN);
                return true;
            }
            default:
                say(s, "Perintah tidak dikenal. Ketik /bl", NamedTextColor.RED);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : new String[]{"start", "stop", "status", "setarena", "setvoteroom", "setlobby",
                    "setvotebox", "addcell", "clearcells", "addfood", "clearfood"}) {
                if (x.startsWith(a[0].toLowerCase())) {
                    out.add(x);
                }
            }
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("setvotebox") || a[0].equalsIgnoreCase("addfood"))) {
            out.add("here");
        }
        return out;
    }
}
