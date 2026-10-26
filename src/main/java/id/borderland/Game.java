package id.borderland;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Satu sesi game "Jack". Semua logika (peran, logo di punggung, mabuk, racun,
 * lapar, voting, mayat) ada di sini.
 */
public class Game {

    public enum Phase { ROAM, VOTE, RESULT, ENDED }

    private static final int[] SUIT_SLOTS = {10, 12, 14, 16};
    private static final int SLOT_INFO = 4;
    private static final int SLOT_SAVE = 21;
    private static final int SLOT_CLOSE = 23;
    private static final int SLOT_ACCUSE = 26;
    private static final int ACC_BACK = 45;
    private static final int GUI_CLEAR_SLOT = 49;
    private static final int GUI_HEAD_START = 0;
    private static final String[] FOOD_NAMES = {"Steak", "Roti", "Apel", "Ikan Bakar", "Ayam Panggang"};
    private static final Material[] FOOD_MATS = {Material.COOKED_BEEF, Material.BREAD, Material.APPLE,
            Material.COOKED_COD, Material.COOKED_CHICKEN};

    private final BorderlandPlugin plugin;
    private final FileConfiguration cfg;
    private final Random rng = new Random();

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private UUID jack;
    private Phase phase = Phase.ROAM;
    private int round = 0;
    private int timeLeft = 0;
    private boolean running = true;

    private final Map<UUID, Suit> suits = new HashMap<>();
    private final Map<UUID, Map<Suit, TextDisplay>> logos = new HashMap<>();
    private final Map<UUID, Long> drunkUntil = new HashMap<>();
    private final Map<UUID, Map<UUID, Suit>> fakeView = new HashMap<>();
    private final Map<UUID, Long> poisonedUntil = new HashMap<>();
    private final Set<String> poisonedStations = new HashSet<>();
    private final Map<UUID, Long> cooldownDrunk = new HashMap<>();
    private final Map<UUID, Long> cooldownPoison = new HashMap<>();
    private final Map<UUID, Long> cooldownEat = new HashMap<>();
    private final Map<UUID, Suit> votes = new HashMap<>();      // tebakan yang sudah DISIMPAN
    private final Map<UUID, Suit> pending = new HashMap<>();    // pilihan di GUI, belum disimpan
    private final Set<UUID> skipVotes = new HashSet<>();
    private final Map<UUID, BorderlandPlugin.Cell> cellOf = new HashMap<>();
    private final Map<UUID, Long> hudPause = new HashMap<>();
    private final List<TextDisplay> foodDisplays = new ArrayList<>();
    private final NamespacedKey poisonKey;
    private int foodStock;
    private int foodMax;
    private final Map<UUID, UUID> accusations = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Double> hungerDebt = new HashMap<>();
    private final List<ArmorStand> corpses = new ArrayList<>();

    private BossBar bar;
    private BukkitTask secondTask;
    private BukkitTask followTask;

    public Game(BorderlandPlugin plugin, List<Player> players) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
        this.poisonKey = new NamespacedKey(plugin, "poisoned");
        this.foodMax = cfg.getInt("food.stock", 80);
        this.foodStock = foodMax;
        for (Player p : players) {
            participants.add(p.getUniqueId());
            alive.add(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------ queries

    public boolean isParticipant(Player p) {
        return running && participants.contains(p.getUniqueId());
    }

    public boolean isAlive(Player p) {
        return alive.contains(p.getUniqueId());
    }

    public boolean isDead(Player p) {
        return isParticipant(p) && !alive.contains(p.getUniqueId());
    }

    private boolean isJack(Player p) {
        return p.getUniqueId().equals(jack);
    }

    // -------------------------------------------------------------- start

    public void begin() {
        List<UUID> list = new ArrayList<>(alive);
        jack = list.get(rng.nextInt(list.size()));
        bar = BossBar.bossBar(Component.text("Borderland"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.setSaturation(0f);
            for (PotionEffect ef : new ArrayList<>(p.getActivePotionEffects())) {
                p.removePotionEffect(ef.getType());
            }
            p.getInventory().setHeldItemSlot(0);
            if (cfg.getBoolean("bossbar", false)) {
                p.showBossBar(bar);
            }
            announceRole(p);
        }

        assignCells();
        spawnFoodDisplays();
        spawnLogos();
        secondTask = Bukkit.getScheduler().runTaskTimer(plugin, this::secondTick, 20L, 20L);
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followTick, 1L, 1L);
        startRound();
    }

    private void assignCells() {
        List<BorderlandPlugin.Cell> cs = new ArrayList<>(plugin.cells());
        if (cs.size() < alive.size()) {
            return; // pakai mode lama: satu kotak + satu ruang voting
        }
        Collections.shuffle(cs, rng);
        int i = 0;
        for (UUID id : alive) {
            BorderlandPlugin.Cell c = cs.get(i++);
            cellOf.put(id, c);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(Component.text("Selmu: #" + (plugin.cells().indexOf(c) + 1)
                        + ". Masuk ke selmu lalu klik kotak untuk voting skip.", NamedTextColor.GRAY));
            }
        }
    }

    private void announceRole(Player p) {
        if (isJack(p)) {
            p.showTitle(Title.title(Component.text("KAMU ADALAH JACK", NamedTextColor.RED),
                    Component.text("Buat yang lain salah menebak logonya", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("Kamu JACK. Skill:", NamedTextColor.RED));
            p.sendMessage(Component.text("  Mabuk: tatap pemain lalu tekan F (atau tombol "
                    + cfg.getInt("keys.drunk-slot", 8) + ")", NamedTextColor.GRAY));
            p.sendMessage(Component.text("  Racun: dekati tempat makan lalu tekan tombol "
                    + cfg.getInt("keys.poison-slot", 9), NamedTextColor.GRAY));
            p.sendMessage(Component.text("  Kamu bisa melihat logomu sendiri dan lebih awet lapar.", NamedTextColor.GRAY));
        } else {
            p.showTitle(Title.title(Component.text("KAMU MANUSIA", NamedTextColor.AQUA),
                    Component.text("Cari Jack. Jangan salah pilih logomu.", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("Kamu tidak bisa melihat logo di punggungmu sendiri. "
                    + "Tanya pemain lain, tapi waspada: ada yang bisa salah lihat.", NamedTextColor.GRAY));
        }
    }

    private void startRound() {
        round++;
        phase = Phase.ROAM;
        timeLeft = cfg.getInt("roam-seconds", 240);
        votes.clear();
        pending.clear();
        skipVotes.clear();
        accusations.clear();
        drunkUntil.clear();
        fakeView.clear();
        suits.clear();
        for (UUID id : alive) {
            suits.put(id, Suit.values()[rng.nextInt(Suit.values().length)]);
        }
        Location arena = plugin.loc("arena");
        if (arena != null) {
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.teleport(arena);
                }
            }
        }
        refreshVisibility();
        broadcast(Component.text("== Putaran " + round + " == Berkelilinglah, makan, dan tanya logo temanmu.",
                NamedTextColor.YELLOW));
        Player jp = Bukkit.getPlayer(jack);
        if (jp != null && alive.contains(jack)) {
            jp.sendMessage(Component.text("Logomu putaran ini: ", NamedTextColor.GRAY).append(suits.get(jack).named()));
        }
    }

    // ---------------------------------------------------- logo di punggung

    private void spawnLogos() {
        float sc = (float) cfg.getDouble("logo.scale", 1.2);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Location l = backLocation(p);
            Map<Suit, TextDisplay> m = new EnumMap<>(Suit.class);
            for (Suit s : Suit.values()) {
                TextDisplay d = p.getWorld().spawn(l, TextDisplay.class, e -> {
                    e.text(s.component());
                    e.setBillboard(Display.Billboard.FIXED);
                    e.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                            new Vector3f(sc, sc, sc), new AxisAngle4f()));
                    e.setPersistent(false);
                    e.setInvulnerable(true);
                    e.setVisibleByDefault(false);
                });
                m.put(s, d);
            }
            logos.put(id, m);
        }
    }

    private Location backLocation(Player p) {
        Location l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        double dist = cfg.getDouble("logo.back-distance", 0.30);
        double y = cfg.getDouble("logo.y-offset", 0.9) - (p.isSneaking() ? 0.15 : 0.0);
        Location out = l.clone().add(Math.sin(yaw) * dist, y, -Math.cos(yaw) * dist);
        out.setYaw(l.getYaw() + (float) cfg.getDouble("logo.yaw-offset", 180.0));
        out.setPitch(0f);
        return out;
    }

    private void followTick() {
        if (!running) {
            return;
        }
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            Map<Suit, TextDisplay> m = logos.get(id);
            if (p == null || m == null) {
                continue;
            }
            Location l = backLocation(p);
            for (TextDisplay d : m.values()) {
                if (d.isValid()) {
                    d.teleport(l);
                }
            }
        }
    }

    /** Logo yang dilihat {@code viewer} pada punggung {@code target}. */
    private Suit perceived(UUID viewer, UUID target) {
        Long until = drunkUntil.get(viewer);
        if (until != null && until > System.currentTimeMillis()) {
            Map<UUID, Suit> m = fakeView.get(viewer);
            if (m != null) {
                Suit f = m.get(target);
                if (f != null) {
                    return f;
                }
            }
        }
        return suits.get(target);
    }

    private void refreshVisibility() {
        for (UUID vid : participants) {
            Player viewer = Bukkit.getPlayer(vid);
            if (viewer == null) {
                continue;
            }
            boolean viewerAlive = alive.contains(vid);
            for (Map.Entry<UUID, Map<Suit, TextDisplay>> en : logos.entrySet()) {
                UUID tid = en.getKey();
                Suit show = null;
                if (alive.contains(tid)) {
                    if (!viewerAlive) {
                        show = suits.get(tid);                 // penonton melihat yang asli
                    } else if (tid.equals(vid)) {
                        show = vid.equals(jack) ? suits.get(tid) : null; // hanya Jack yang melihat miliknya
                    } else {
                        show = perceived(vid, tid);
                    }
                }
                for (Map.Entry<Suit, TextDisplay> se : en.getValue().entrySet()) {
                    if (se.getKey() == show) {
                        viewer.showEntity(plugin, se.getValue());
                    } else {
                        viewer.hideEntity(plugin, se.getValue());
                    }
                }
            }
        }
    }

    private void hudMsg(Player p, Component c) {
        p.sendActionBar(c);
        hudPause.put(p.getUniqueId(), System.currentTimeMillis() + 2500L);
    }

    // ------------------------------------------------------- skill Jack

    private String seconds(long ms) {
        return String.valueOf(ms / 1000 + 1);
    }

    public boolean onHotbar(Player p, int newSlot) {
        if (!isParticipant(p) || !isJack(p)) {
            return false;
        }
        int drunkSlot = cfg.getInt("keys.drunk-slot", 8) - 1;
        int poisonSlot = cfg.getInt("keys.poison-slot", 9) - 1;
        if (newSlot == drunkSlot) {
            onDrunkKey(p);
            return true;
        }
        if (newSlot == poisonSlot) {
            onPoisonKey(p);
            return true;
        }
        return false;
    }

    public void onDrunkKey(Player p) {
        UUID id = p.getUniqueId();
        if (phase != Phase.ROAM || !alive.contains(id) || !isJack(p)) {
            return;
        }
        long now = System.currentTimeMillis();
        long cd = cooldownDrunk.getOrDefault(id, 0L);
        if (cd > now) {
            hudMsg(p, Component.text("Skill mabuk cooldown " + seconds(cd - now) + " detik", NamedTextColor.RED));
            return;
        }
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(),
                cfg.getDouble("drunk.range", 6.0), 0.4,
                en -> en instanceof Player t && !t.equals(p) && alive.contains(t.getUniqueId()));
        Entity hit = r == null ? null : r.getHitEntity();
        if (!(hit instanceof Player target)) {
            hudMsg(p, Component.text("Tatap seorang pemain dulu.", NamedTextColor.GRAY));
            return;
        }
        UUID tid = target.getUniqueId();
        Long until = drunkUntil.get(tid);
        if (until != null && until > now) {
            hudMsg(p, Component.text(target.getName() + " sudah mabuk.", NamedTextColor.GRAY));
            return;
        }
        cooldownDrunk.put(id, now + cfg.getLong("drunk.cooldown-seconds", 40L) * 1000L);
        startDrunk(target);
        hudMsg(p, Component.text(target.getName() + " mabuk selama "
                + cfg.getInt("drunk.duration-seconds", 90) + " detik.", NamedTextColor.GOLD));
    }

    private void startDrunk(Player victim) {
        UUID vid = victim.getUniqueId();
        drunkUntil.put(vid, System.currentTimeMillis() + cfg.getLong("drunk.duration-seconds", 90L) * 1000L);
        Map<UUID, Suit> fake = new HashMap<>();
        for (UUID t : alive) {
            if (t.equals(vid)) {
                continue;
            }
            Suit real = suits.get(t);
            Suit f;
            do {
                f = Suit.values()[rng.nextInt(Suit.values().length)];
            } while (f == real);
            fake.put(t, f);
        }
        fakeView.put(vid, fake);
        refreshVisibility(); // korban tidak diberi notifikasi apa pun
    }

    public void onPoisonKey(Player p) {
        UUID id = p.getUniqueId();
        if (phase != Phase.ROAM || !alive.contains(id) || !isJack(p)) {
            return;
        }
        long now = System.currentTimeMillis();
        long cd = cooldownPoison.getOrDefault(id, 0L);
        if (cd > now) {
            hudMsg(p, Component.text("Skill racun cooldown " + seconds(cd - now) + " detik", NamedTextColor.RED));
            return;
        }
        Location st = nearestStation(p.getLocation(), cfg.getDouble("poison.reach", 4.0));
        if (st == null) {
            hudMsg(p, Component.text("Dekati tempat makan dulu.", NamedTextColor.GRAY));
            return;
        }
        if (!poisonedStations.add(BorderlandPlugin.key(st))) {
            hudMsg(p, Component.text("Makanan ini sudah diracuni.", NamedTextColor.GRAY));
            return;
        }
        cooldownPoison.put(id, now + cfg.getLong("poison.cooldown-seconds", 20L) * 1000L);
        hudMsg(p, Component.text("Makanan diracuni.", NamedTextColor.DARK_GREEN));
    }

    // --------------------------------------------- kafetaria & voting box

    private boolean near(Location a, Location b, double r) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) <= r * r;
    }

    private Location nearestStation(Location from, double radius) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (Location l : plugin.stations()) {
            if (l.getWorld() == null || !l.getWorld().equals(from.getWorld())) {
                continue;
            }
            double d = l.distanceSquared(from);
            if (d <= radius * radius && d < bestD) {
                bestD = d;
                best = l;
            }
        }
        return best;
    }

    /** Klik kanan pada blok/entity. Mengembalikan true kalau klik itu "dipakai". */
    public boolean onWorldClick(Player p, Location clicked) {
        UUID id = p.getUniqueId();
        if (!isParticipant(p) || !alive.contains(id)) {
            return false;
        }
        BorderlandPlugin.Cell own = cellOf.get(id);
        if (own != null) {
            if (near(clicked, own.box(), 1.6)) {
                if (phase == Phase.VOTE) {
                    openGui(p, false);
                } else if (phase == Phase.ROAM) {
                    voteSkip(p);
                }
                return true;
            }
            for (BorderlandPlugin.Cell c : cellOf.values()) {
                if (c != own && near(clicked, c.box(), 1.6)) {
                    hudMsg(p, Component.text("Itu kotak milik pemain lain.", NamedTextColor.GRAY));
                    return true;
                }
            }
        } else if (phase == Phase.VOTE && near(clicked, plugin.loc("votebox"), 1.6)) {
            openGui(p, false);
            return true;
        }
        if (phase == Phase.ROAM) {
            Location st = nearestStation(clicked, 1.6);
            if (st != null) {
                takeFood(p, st);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------- voting skip

    private void voteSkip(Player p) {
        UUID id = p.getUniqueId();
        if (phase != Phase.ROAM || !alive.contains(id)) {
            return;
        }
        if (!skipVotes.add(id)) {
            hudMsg(p, Component.text("Kamu sudah voting skip.", NamedTextColor.GRAY));
            return;
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        broadcast(Component.text(p.getName() + " telah voting skip. (" + skipVotes.size() + "/" + alive.size() + ")",
                NamedTextColor.GRAY));
        checkSkip();
    }

    private void checkSkip() {
        if (phase != Phase.ROAM || alive.isEmpty()) {
            return;
        }
        double ratio = cfg.getDouble("skip.required-ratio", 1.0);
        if (skipVotes.size() >= (int) Math.ceil(alive.size() * ratio)) {
            broadcast(Component.text("Semua setuju skip. Voting dimulai!", NamedTextColor.YELLOW));
            startVote();
        }
    }

    /** /skip: hanya dipakai kalau sel belum diatur. */
    public void onSkipCommand(Player p) {
        if (!alive.contains(p.getUniqueId())) {
            return;
        }
        if (cellOf.containsKey(p.getUniqueId())) {
            hudMsg(p, Component.text("Masuk ke selmu lalu klik kotak untuk voting skip.", NamedTextColor.GRAY));
            return;
        }
        voteSkip(p);
    }

    // ------------------------------------------------------- kafetaria

    private Component foodText() {
        return Component.text("KAFETARIA\n", NamedTextColor.WHITE)
                .append(Component.text(foodStock + "/" + foodMax, foodStock > 0 ? NamedTextColor.WHITE : NamedTextColor.RED,
                        TextDecoration.BOLD));
    }

    private void spawnFoodDisplays() {
        List<Location> placed = new ArrayList<>();
        for (Location st : plugin.stations()) {
            if (st.getWorld() == null) {
                continue;
            }
            boolean close = false;
            for (Location o : placed) {
                if (near(st, o, 3.5)) {
                    close = true;
                    break;
                }
            }
            if (close) {
                continue; // satu papan per kelompok tempat makan
            }
            placed.add(st);
            Location l = st.clone().add(0, 1.4, 0);
            TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, e -> {
                e.text(foodText());
                e.setBillboard(Display.Billboard.CENTER);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f()));
                e.setPersistent(false);
            });
            foodDisplays.add(d);
        }
    }

    private void updateFoodDisplays() {
        for (TextDisplay d : foodDisplays) {
            if (d.isValid()) {
                d.text(foodText());
            }
        }
    }

    private int foodItemsHeld(Player p) {
        int n = 0;
        for (ItemStack st : p.getInventory().getContents()) {
            if (st != null && st.getType().isEdible()) {
                n += st.getAmount();
            }
        }
        return n;
    }

    private void takeFood(Player p, Location st) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (cooldownEat.getOrDefault(id, 0L) > now) {
            return;
        }
        if (foodStock <= 0) {
            hudMsg(p, Component.text("Makanan sudah habis!", NamedTextColor.RED));
            return;
        }
        if (p.getFoodLevel() >= 20 && foodItemsHeld(p) == 0) {
            hudMsg(p, Component.text("Kamu masih kenyang.", NamedTextColor.GRAY));
            return;
        }
        if (foodItemsHeld(p) >= 2) {
            hudMsg(p, Component.text("Habiskan makananmu dulu.", NamedTextColor.GRAY));
            return;
        }
        cooldownEat.put(id, now + 2000L);
        int k = rng.nextInt(FOOD_NAMES.length);
        ItemStack food = new ItemStack(FOOD_MATS[k]);
        ItemMeta meta = food.getItemMeta();
        meta.displayName(Component.text(FOOD_NAMES[k], NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        // sama persis dari luar, teracun atau tidak
        if (!isJack(p) && poisonedStations.remove(BorderlandPlugin.key(st))) {
            meta.getPersistentDataContainer().set(poisonKey, PersistentDataType.BYTE, (byte) 1);
        }
        food.setItemMeta(meta);
        Map<Integer, ItemStack> left = p.getInventory().addItem(food);
        if (!left.isEmpty()) {
            hudMsg(p, Component.text("Inventarismu penuh.", NamedTextColor.RED));
            return;
        }
        foodStock--;
        updateFoodDisplays();
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        p.sendActionBar(Component.text("Ambil " + FOOD_NAMES[k] + ". Sisa makanan " + foodStock + "/" + foodMax + ".",
                NamedTextColor.GRAY));
        hudPause.put(id, now + 2500L);
    }

    public void onConsume(Player p, ItemStack item) {
        UUID id = p.getUniqueId();
        if (!alive.contains(id) || item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(poisonKey, PersistentDataType.BYTE)) {
            poisonedUntil.put(id, System.currentTimeMillis() + cfg.getLong("poison.duration-seconds", 120L) * 1000L);
        }
    }

    // ------------------------------------------------------------ lapar

    private void hungerTick() {
        long now = System.currentTimeMillis();
        double base = cfg.getDouble("hunger.food-per-block", 0.06);
        double jackMul = cfg.getDouble("hunger.jack-multiplier", 0.4);
        double poisonMul = cfg.getDouble("hunger.poison-multiplier", 4.0);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Location cur = p.getLocation();
            Location prev = lastPos.put(id, cur);
            if (prev != null && prev.getWorld() != null && prev.getWorld().equals(cur.getWorld())) {
                double dx = cur.getX() - prev.getX();
                double dz = cur.getZ() - prev.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 12.0) { // abaikan teleport
                    double rate = base;
                    if (id.equals(jack)) {
                        rate *= jackMul;
                    }
                    Long pu = poisonedUntil.get(id);
                    if (pu != null && pu > now) {
                        rate *= poisonMul;
                    }
                    double debt = hungerDebt.getOrDefault(id, 0.0) + d * rate;
                    int lose = (int) debt;
                    debt -= lose;
                    hungerDebt.put(id, debt);
                    if (lose > 0) {
                        p.setFoodLevel(Math.max(0, p.getFoodLevel() - lose));
                    }
                }
            }
            p.setSaturation(0f);
            p.setExhaustion(0f);
            if (p.getFoodLevel() <= 0) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, false, false));
            }
        }
    }

    public void onStarved(Player p) {
        if (alive.contains(p.getUniqueId())) {
            eliminate(p, true);
        }
    }

    // --------------------------------------------------------- jam & fase

    private void secondTick() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = drunkUntil.entrySet().removeIf(en -> en.getValue() <= now);
        if (changed) {
            fakeView.keySet().retainAll(drunkUntil.keySet());
            refreshVisibility();
        }
        poisonedUntil.values().removeIf(t -> t <= now);
        if (phase == Phase.ROAM || phase == Phase.VOTE) {
            hungerTick();
        }
        if (phase == Phase.ROAM) {
            timeLeft--;
            if (timeLeft <= 0) {
                startVote();
            }
        } else if (phase == Phase.VOTE) {
            timeLeft--;
            if (timeLeft <= 0) {
                resolveVote();
            }
        }
        updateBar();
        hud();
        refreshOpenGuis();
    }

    /** HUD action bar: "Ronde 1 | 5:16 | 8/10" atau "Ronde 1 | 0:35 | Tebakan: Wajik ♦". */
    private void hud() {
        if (phase == Phase.ENDED) {
            return;
        }
        long now = System.currentTimeMillis();
        Component sep = Component.text(" | ", NamedTextColor.DARK_GRAY);
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || hudPause.getOrDefault(id, 0L) > now) {
                continue;
            }
            Component c = Component.text("Ronde " + round, NamedTextColor.GOLD).append(sep)
                    .append(Component.text(fmt(timeLeft), NamedTextColor.AQUA)).append(sep);
            if (phase == Phase.ROAM) {
                c = c.append(Component.text(skipVotes.size() + "/" + alive.size(), NamedTextColor.GREEN));
            } else if (alive.contains(id)) {
                Suit g = votes.get(id);
                c = c.append(Component.text("Tebakan: ", NamedTextColor.GRAY))
                        .append(g == null ? Component.text("-", NamedTextColor.DARK_GRAY) : g.named());
            } else {
                c = c.append(Component.text("Penonton", NamedTextColor.GRAY));
            }
            p.sendActionBar(c);
        }
    }

    private void refreshOpenGuis() {
        if (phase != Phase.VOTE) {
            return;
        }
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof VoteHolder h && !h.isAccuse()) {
                top.setItem(SLOT_INFO, infoItem(p));
            }
        }
    }

    private String fmt(int sec) {
        int s = Math.max(0, sec);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void updateBar() {
        if (bar == null) {
            return;
        }
        String name;
        int total;
        switch (phase) {
            case ROAM:
                name = "Putaran " + round + " - Berkeliling " + fmt(timeLeft);
                total = cfg.getInt("roam-seconds", 240);
                bar.color(BossBar.Color.YELLOW);
                break;
            case VOTE:
                name = "VOTING " + fmt(timeLeft);
                total = cfg.getInt("vote-seconds", 60);
                bar.color(BossBar.Color.RED);
                break;
            case RESULT:
                name = "Menghitung hasil...";
                total = 1;
                break;
            default:
                name = "Selesai";
                total = 1;
                break;
        }
        bar.name(Component.text(name));
        float prog = (phase == Phase.ROAM || phase == Phase.VOTE) ? timeLeft / (float) total : 1f;
        bar.progress(Math.max(0f, Math.min(1f, prog)));
    }

    private void startVote() {
        phase = Phase.VOTE;
        timeLeft = cfg.getInt("vote-seconds", 60);
        votes.clear();
        pending.clear();
        accusations.clear();
        Location room = plugin.loc("voteroom");
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            BorderlandPlugin.Cell c = cellOf.get(id);
            if (c != null) {
                p.teleport(c.spawn());
            } else if (room != null) {
                p.teleport(room);
            }
        }
        broadcast(Component.text("VOTING! Klik kotak di selmu untuk menebak simbol di punggungmu. "
                + "Salah menebak = tereliminasi.", NamedTextColor.RED));
    }

    private void resolveVote() {
        phase = Phase.RESULT;
        timeLeft = 0;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.closeInventory();
            }
        }

        final Set<UUID> shot = new LinkedHashSet<>();

        // 1) tuduhan: mayoritas mutlak menuduh satu orang
        Map<UUID, Integer> tally = new HashMap<>();
        for (Map.Entry<UUID, UUID> en : accusations.entrySet()) {
            if (alive.contains(en.getKey()) && alive.contains(en.getValue())) {
                tally.merge(en.getValue(), 1, Integer::sum);
            }
        }
        UUID top = null;
        int topN = 0;
        boolean tie = false;
        for (Map.Entry<UUID, Integer> en : tally.entrySet()) {
            if (en.getValue() > topN) {
                top = en.getKey();
                topN = en.getValue();
                tie = false;
            } else if (en.getValue() == topN) {
                tie = true;
            }
        }
        if (top != null && !tie && topN * 2 > alive.size()) {
            shot.add(top);
            Player tp = Bukkit.getPlayer(top);
            broadcast(Component.text("Mayoritas menuduh " + (tp == null ? "seseorang" : tp.getName()) + "!",
                    NamedTextColor.GOLD));
        }

        // 2) logo: salah pilih (atau tidak memilih) = tertembak
        for (UUID id : alive) {
            Suit v = votes.get(id);
            if (v == null || v != suits.get(id)) {
                shot.add(id);
            }
        }

        broadcast(Component.text("Menghitung hasil...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!running) {
                return;
            }
            for (UUID id : new ArrayList<>(alive)) {
                Player p = Bukkit.getPlayer(id);
                Suit real = suits.get(id);
                Suit guess = votes.get(id);
                boolean correct = guess != null && guess == real;
                if (p == null) {
                    if (shot.contains(id)) {
                        alive.remove(id);
                    }
                    continue;
                }
                if (!shot.contains(id)) {
                    p.showTitle(Title.title(Component.text("SELAMAT", NamedTextColor.GREEN),
                            Component.text("+ ").color(NamedTextColor.GRAY).append(real.named())));
                    p.sendMessage(Component.text("Benar! Simbolmu memang ", NamedTextColor.GREEN).append(real.named()));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    continue;
                }
                if (correct) {
                    p.sendMessage(Component.text("Tebakanmu benar, tapi mayoritas menuduhmu sebagai Jack.",
                            NamedTextColor.RED));
                } else {
                    p.sendMessage(Component.text(guess == null ? "Kamu tidak menebak! " : "Salah! ", NamedTextColor.RED)
                            .append(Component.text("Simbolmu sebenarnya ", NamedTextColor.RED))
                            .append(real.named()));
                }
                broadcast(Component.text(p.getName() + " tereliminasi.", NamedTextColor.RED));
                p.sendMessage(Component.text("Kamu tereliminasi. Sekarang jadi penonton.", NamedTextColor.GRAY));
                eliminate(p, false);
            }
            refreshVisibility();
            checkWin();
            if (running && phase != Phase.ENDED) {
                broadcast(Component.text("Yang selamat lanjut ke ronde berikutnya. Sisa: " + alive.size(),
                        NamedTextColor.GREEN));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (running && phase != Phase.ENDED) {
                        startRound();
                    }
                }, 100L);
            }
        }, 60L);
    }

    // --------------------------------------------------- eliminasi & akhir

    private void eliminate(Player p, boolean check) {
        UUID id = p.getUniqueId();
        if (!alive.remove(id)) {
            return;
        }
        spawnCorpse(p);
        Map<Suit, TextDisplay> m = logos.remove(id);
        if (m != null) {
            for (TextDisplay d : m.values()) {
                d.remove();
            }
        }
        blood(p.getLocation());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 0.7f);
        p.setGameMode(GameMode.SPECTATOR);
        p.showTitle(Title.title(Component.text("TERELIMINASI", NamedTextColor.RED),
                Component.text("Kamu jadi penonton sekarang", NamedTextColor.GRAY)));
        refreshVisibility();
        if (check) {
            checkWin();
        }
    }

    /** Cipratan darah di sekitar mayat (beberapa kali supaya kelihatan). */
    private void blood(Location at) {
        final Location base = at.clone().add(0, 0.2, 0);
        for (int i = 0; i < 4; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!running || base.getWorld() == null) {
                    return;
                }
                base.getWorld().spawnParticle(Particle.BLOCK, base, 30, 0.35, 0.1, 0.35, 0.05,
                        Material.REDSTONE_BLOCK.createBlockData());
                base.getWorld().spawnParticle(Particle.DUST, base, 20, 0.35, 0.1, 0.35, 0.0,
                        new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.6f));
            }, i * 8L);
        }
    }

    private void spawnCorpse(Player p) {
        Location l = p.getLocation().clone().add(0, -0.65, 0);
        double lie = Math.toRadians(-90);
        ArmorStand as = l.getWorld().spawn(l, ArmorStand.class, a -> {
            a.setArms(true);
            a.setBasePlate(false);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setRotation(p.getLocation().getYaw(), 0f);
            a.setBodyPose(new EulerAngle(lie, 0, 0));
            a.setHeadPose(new EulerAngle(lie, 0, 0));
            a.setLeftLegPose(new EulerAngle(lie, 0, 0));
            a.setRightLegPose(new EulerAngle(lie, 0, 0));
            a.setLeftArmPose(new EulerAngle(lie, 0, 0));
            a.setRightArmPose(new EulerAngle(lie, 0, 0));
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(p);
            head.setItemMeta(sm);
            a.getEquipment().setHelmet(head);
            a.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
            a.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
            a.customName(Component.text("☠ " + p.getName(), NamedTextColor.GRAY));
            a.setCustomNameVisible(true);
        });
        corpses.add(as);
    }

    private void checkWin() {
        if (!running || phase == Phase.ENDED) {
            return;
        }
        if (!alive.contains(jack)) {
            finish(true);
            return;
        }
        int humans = alive.size() - 1;
        if (humans <= cfg.getInt("jack-wins-when-humans-left", 1)) {
            finish(false);
        }
    }

    private void finish(boolean crewWins) {
        phase = Phase.ENDED;
        updateBar();
        Player jp = Bukkit.getPlayer(jack);
        String jackName = jp == null ? "Jack" : jp.getName();
        Component title = crewWins
                ? Component.text("PEMAIN MENANG", NamedTextColor.GREEN)
                : Component.text("JACK MENANG", NamedTextColor.RED);
        Component sub = Component.text("Jack adalah " + jackName, NamedTextColor.GRAY);
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.showTitle(Title.title(title, sub));
            }
        }
        broadcast(Component.text("Permainan selesai. Jack adalah " + jackName + ".", NamedTextColor.GOLD));
        Bukkit.getScheduler().runTaskLater(plugin, this::cleanup, 240L);
    }

    public void cleanup() {
        if (!running && secondTask == null) {
            plugin.clearGame(this);
            return;
        }
        running = false;
        if (secondTask != null) {
            secondTask.cancel();
            secondTask = null;
        }
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        for (Map<Suit, TextDisplay> m : logos.values()) {
            for (TextDisplay d : m.values()) {
                d.remove();
            }
        }
        logos.clear();
        for (TextDisplay d : foodDisplays) {
            d.remove();
        }
        foodDisplays.clear();
        for (ArmorStand a : corpses) {
            a.remove();
        }
        corpses.clear();
        Location lobby = plugin.loc("lobby");
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            if (bar != null) {
                p.hideBossBar(bar);
            }
            p.closeInventory();
            for (PotionEffect ef : new ArrayList<>(p.getActivePotionEffects())) {
                p.removePotionEffect(ef.getType());
            }
            p.setGameMode(Bukkit.getDefaultGameMode());
            p.setFoodLevel(20);
            if (lobby != null) {
                p.teleport(lobby);
            }
        }
        plugin.clearGame(this);
    }

    // ------------------------------------------------------ join & quit

    public void onJoin(Player p) {
        if (!running) {
            return;
        }
        UUID id = p.getUniqueId();
        if (bar != null && cfg.getBoolean("bossbar", false)) {
            p.showBossBar(bar);
        }
        if (!participants.contains(id)) {
            participants.add(id); // pemain baru jadi penonton
        }
        if (!alive.contains(id)) {
            p.setGameMode(GameMode.SPECTATOR);
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshVisibility, 10L);
    }

    public void onQuit(Player p) {
        if (!running) {
            return;
        }
        UUID id = p.getUniqueId();
        if (alive.contains(id)) {
            eliminate(p, true);
        }
    }

    // ------------------------------------------------------------- GUI

    private List<UUID> accuseTargets(UUID self) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : alive) {
            if (!id.equals(self)) {
                out.add(id);
            }
        }
        return out;
    }

    private ItemStack item(Material m, Component name, boolean glint) {
        ItemStack st = new ItemStack(m);
        ItemMeta meta = st.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.setEnchantmentGlintOverride(glint ? Boolean.TRUE : null);
        st.setItemMeta(meta);
        return st;
    }

    private ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), false);
    }

    private ItemStack infoItem(Player p) {
        UUID id = p.getUniqueId();
        Suit saved = votes.get(id);
        ItemStack st = new ItemStack(Material.PAPER);
        ItemMeta meta = st.getItemMeta();
        meta.displayName(Component.text("Pilih satu simbol, lalu tekan Simpan Tebakan.", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Sisa waktu: " + Math.max(0, timeLeft) + " detik", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add((saved == null
                ? Component.text("Belum ada tebakan tersimpan!", NamedTextColor.RED)
                : Component.text("Tebakan tersimpan: ", NamedTextColor.GREEN).append(saved.named()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        st.setItemMeta(meta);
        return st;
    }

    private void openGui(Player p, boolean accuse) {
        VoteHolder holder = new VoteHolder(accuse);
        Inventory inv = Bukkit.createInventory(holder, accuse ? 54 : 27,
                Component.text(accuse ? "TUDUH SIAPA JACK?" : "TEBAK SIMBOL DI PUNGGUNGMU", NamedTextColor.GOLD));
        holder.set(inv);
        if (accuse) {
            fillAccuse(inv, p);
        } else {
            fillMain(inv, p);
        }
        p.openInventory(inv);
    }

    private void fillMain(Inventory inv, Player p) {
        inv.clear();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler());
        }
        UUID id = p.getUniqueId();
        Suit shown = pending.getOrDefault(id, votes.get(id));
        inv.setItem(SLOT_INFO, infoItem(p));
        for (int i = 0; i < SUIT_SLOTS.length; i++) {
            Suit s = Suit.values()[i];
            inv.setItem(SUIT_SLOTS[i], item(s.icon, s.named(), shown == s));
        }
        inv.setItem(SLOT_SAVE, item(Material.EMERALD, Component.text("Simpan Tebakan", NamedTextColor.GREEN), false));
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, Component.text("Tutup", NamedTextColor.RED), false));
        inv.setItem(SLOT_ACCUSE, item(Material.SKELETON_SKULL,
                Component.text("Tuduh Jack (opsional)", NamedTextColor.GOLD), accusations.containsKey(id)));
    }

    private void fillAccuse(Inventory inv, Player p) {
        inv.clear();
        UUID id = p.getUniqueId();
        List<UUID> targets = accuseTargets(id);
        UUID accused = accusations.get(id);
        for (int i = 0; i < targets.size() && GUI_HEAD_START + i < ACC_BACK; i++) {
            UUID tid = targets.get(i);
            Player t = Bukkit.getPlayer(tid);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(tid));
            sm.displayName(Component.text(t == null ? "?" : t.getName(), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            sm.setEnchantmentGlintOverride(tid.equals(accused) ? Boolean.TRUE : null);
            head.setItemMeta(sm);
            inv.setItem(GUI_HEAD_START + i, head);
        }
        inv.setItem(ACC_BACK, item(Material.ARROW, Component.text("Kembali", NamedTextColor.WHITE), false));
        inv.setItem(GUI_CLEAR_SLOT, item(Material.BARRIER,
                Component.text("Tidak menuduh siapa pun", NamedTextColor.RED), accused == null));
    }

    public void onGuiClick(Player p, int slot, boolean accuseMode) {
        UUID id = p.getUniqueId();
        if (phase != Phase.VOTE || !alive.contains(id)) {
            return;
        }
        Inventory top = p.getOpenInventory().getTopInventory();
        if (accuseMode) {
            if (slot == ACC_BACK) {
                Bukkit.getScheduler().runTask(plugin, () -> openGui(p, false));
                return;
            }
            boolean handled = false;
            if (slot == GUI_CLEAR_SLOT) {
                accusations.remove(id);
                handled = true;
            } else if (slot >= GUI_HEAD_START && slot < ACC_BACK) {
                List<UUID> t = accuseTargets(id);
                int idx = slot - GUI_HEAD_START;
                if (idx < t.size()) {
                    accusations.put(id, t.get(idx));
                    handled = true;
                }
            }
            if (handled) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                if (top.getHolder() instanceof VoteHolder) {
                    fillAccuse(top, p);
                }
            }
            return;
        }

        for (int i = 0; i < SUIT_SLOTS.length; i++) {
            if (slot == SUIT_SLOTS[i]) {
                pending.put(id, Suit.values()[i]);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                fillMain(top, p);
                return;
            }
        }
        if (slot == SLOT_SAVE) {
            Suit pick = pending.get(id);
            if (pick == null) {
                hudMsg(p, Component.text("Pilih satu simbol dulu.", NamedTextColor.GRAY));
                return;
            }
            votes.put(id, pick);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.4f);
            p.sendMessage(Component.text("Tebakan disimpan: ", NamedTextColor.YELLOW).append(pick.named()));
            fillMain(top, p);
            if (votes.keySet().containsAll(alive)) {
                timeLeft = Math.min(timeLeft, 5); // semua sudah menyimpan, percepat
            }
        } else if (slot == SLOT_CLOSE) {
            Bukkit.getScheduler().runTask(plugin, p::closeInventory);
        } else if (slot == SLOT_ACCUSE) {
            Bukkit.getScheduler().runTask(plugin, () -> openGui(p, true));
        }
    }

    // ----------------------------------------------------------- util

    private void broadcast(Component c) {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(c);
            }
        }
    }
}
