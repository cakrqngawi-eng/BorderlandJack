package id.borderland;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

public enum Suit {
    HEART("♥", NamedTextColor.RED, Material.RED_WOOL, "Hati"),
    DIAMOND("♦", NamedTextColor.RED, Material.ORANGE_WOOL, "Wajik"),
    CLUB("♣", NamedTextColor.GRAY, Material.BLACK_WOOL, "Keriting"),
    SPADE("♠", NamedTextColor.WHITE, Material.LIGHT_GRAY_WOOL, "Sekop");

    public final String symbol;
    public final NamedTextColor color;
    public final Material icon;
    public final String label;

    Suit(String symbol, NamedTextColor color, Material icon, String label) {
        this.symbol = symbol;
        this.color = color;
        this.icon = icon;
        this.label = label;
    }

    /** Hanya simbol, dipakai di punggung pemain. */
    public Component component() {
        return Component.text(symbol, color);
    }

    /** Nama + simbol, contoh "Wajik ♦". */
    public Component named() {
        return Component.text(label + " " + symbol, color);
    }
}
