package id.borderland;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

public class GameListener implements Listener {

    private final BorderlandPlugin plugin;

    public GameListener(BorderlandPlugin plugin) {
        this.plugin = plugin;
    }

    private Game game() {
        return plugin.game();
    }

    /** Tombol F: skill mabuk milik Jack. Untuk pemain lain tidak berbuat apa-apa. */
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Game g = game();
        if (g == null || !g.isParticipant(e.getPlayer())) {
            return;
        }
        e.setCancelled(true);
        g.onDrunkKey(e.getPlayer());
    }

    /** Tombol angka: skill Jack. Pergantian slot dibatalkan supaya tidak terlihat. */
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Game g = game();
        if (g == null) {
            return;
        }
        if (g.onHotbar(e.getPlayer(), e.getNewSlot())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Game g = game();
        if (g != null && g.isParticipant(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Game g = game();
        Block b = e.getClickedBlock();
        if (g == null || b == null) {
            return;
        }
        Location clicked = b.getLocation().add(0.5, 0.5, 0.5);
        if (g.onWorldClick(e.getPlayer(), clicked)) {
            e.setCancelled(true);
        }
    }

    /** Furniture dari plugin lain biasanya berupa entity. */
    @EventHandler
    public void onInteractEntity(PlayerInteractAtEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Game g = game();
        if (g == null) {
            return;
        }
        if (g.onWorldClick(e.getPlayer(), e.getRightClicked().getLocation())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof VoteHolder)) {
            return;
        }
        e.setCancelled(true);
        Game g = game();
        if (g == null || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) {
            return;
        }
        g.onGuiClick(p, raw, ((VoteHolder) top.getHolder()).isAccuse());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof VoteHolder) {
            e.setCancelled(true);
        }
    }

    /** Lapar hanya dikelola plugin (berdasarkan jarak berjalan). */
    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        Game g = game();
        if (g == null || !(e.getEntity() instanceof Player p) || !g.isParticipant(p)) {
            return;
        }
        if (e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Game g = game();
        if (g == null || !(e.getEntity() instanceof Player p) || !g.isParticipant(p)) {
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.STARVATION) {
            if (p.getHealth() - e.getFinalDamage() <= 0) {
                e.setCancelled(true);
                g.onStarved(p);
            }
            return;
        }
        e.setCancelled(true);
    }

    /** Pemain yang sudah mati hanya bisa ngobrol dengan sesama yang mati. */
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        final Game g = game();
        if (g == null || !g.isDead(e.getPlayer())) {
            return;
        }
        e.viewers().removeIf(a -> a instanceof Player v && g.isParticipant(v) && g.isAlive(v));
    }

    /** Makanan kafetaria yang teracun memberi efek lapar cepat saat dimakan. */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Game g = game();
        if (g != null && g.isParticipant(e.getPlayer())) {
            g.onConsume(e.getPlayer(), e.getItem());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Game g = game();
        if (g != null) {
            g.onJoin(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Game g = game();
        if (g != null) {
            g.onQuit(e.getPlayer());
        }
    }
}
