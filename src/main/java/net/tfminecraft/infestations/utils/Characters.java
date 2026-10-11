package net.tfminecraft.infestations.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/** RPCharacters lookups; without RPCharacters every player has no character ID. */
public final class Characters {

    private Characters() {}

    public static boolean enabled() {
        return Bukkit.getPluginManager().isPluginEnabled("RPCharacters");
    }

    /** The player's active character ID, or null. */
    public static String activeId(Player player) {
        if (!enabled()) {
            return null;
        }
        PlayerData data = PlayerManager.get(player);
        RPCharacter character = data == null ? null : data.getActiveCharacter();
        return character == null ? null : character.getId();
    }

    /** True when the player's character with this ID exists and is no longer alive. */
    public static boolean isDead(Player player, String characterId) {
        if (!enabled()) {
            return false;
        }
        PlayerData data = PlayerManager.get(player);
        if (data == null) {
            return false;
        }
        for (RPCharacter character : data.getCharacters()) {
            if (characterId.equals(character.getId())) {
                return character.getStatus() != Status.ALIVE;
            }
        }
        return false;
    }
}
