package net.tfminecraft.infestations.spawn;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.infestations.Infestations;

public final class Keys {

    public static final String KIND_AMBIENT = "ambient";
    public static final String KIND_LURE = "lure";

    private Keys() {}

    public static NamespacedKey infestationId() {
        return new NamespacedKey(Infestations.plugin, "infestationId");
    }

    public static NamespacedKey kind() {
        return new NamespacedKey(Infestations.plugin, "kind");
    }

    public static NamespacedKey displayProvince() {
        return new NamespacedKey(Infestations.plugin, "lureDisplay");
    }

    public static void tagMob(PersistentDataContainer pdc, int provinceId, String kind) {
        pdc.set(infestationId(), PersistentDataType.INTEGER, provinceId);
        pdc.set(kind(), PersistentDataType.STRING, kind);
    }

    public static Integer infestationId(PersistentDataContainer pdc) {
        if (!pdc.has(infestationId(), PersistentDataType.INTEGER)) {
            return null;
        }
        return pdc.get(infestationId(), PersistentDataType.INTEGER);
    }

    public static String kind(PersistentDataContainer pdc) {
        return pdc.get(kind(), PersistentDataType.STRING);
    }

    public static void tagDisplay(PersistentDataContainer pdc, int provinceId) {
        pdc.set(displayProvince(), PersistentDataType.INTEGER, provinceId);
    }

    public static Integer displayProvince(PersistentDataContainer pdc) {
        if (!pdc.has(displayProvince(), PersistentDataType.INTEGER)) {
            return null;
        }
        return pdc.get(displayProvince(), PersistentDataType.INTEGER);
    }
}
