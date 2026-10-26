package id.borderland;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Penanda inventory GUI voting. accuse=true berarti menu "Tuduh Jack". */
public class VoteHolder implements InventoryHolder {
    private final boolean accuse;
    private Inventory inventory;

    public VoteHolder(boolean accuse) {
        this.accuse = accuse;
    }

    public boolean isAccuse() {
        return accuse;
    }

    public void set(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
