package net.tfminecraft.infestations.utils;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.character.CharacterSession;

/** Shared identity lookups; unavailable and not ready are distinct from standalone play. */
public final class Characters {

    private Characters() {}

    public static boolean enabled() {
        return net.tfminecraft.tlibs.character.Characters.available();
    }

    public static CharacterSession session(Player player) {
        return net.tfminecraft.tlibs.character.Characters.currentSession(player).orElse(null);
    }

    public static boolean isCurrent(CharacterSession session) {
        return net.tfminecraft.tlibs.character.Characters.isCurrent(session);
    }

    public static String activeId(Player player) {
        CharacterSession session = session(player);
        return session == null ? null : session.ref().characterId().toString();
    }

    /** Preserves the lure's existing DEAD/MISSING settlement rule, with verified ownership. */
    public static boolean isDead(Player player, String characterId) {
        UUID id;
        try {
            id = UUID.fromString(characterId);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!id.toString().equalsIgnoreCase(characterId)) {
            return false;
        }
        return net.tfminecraft.tlibs.character.Characters.info(id)
                .filter(info -> info.ref().ownerId().equals(player.getUniqueId()))
                .map(info -> !info.alive()).orElse(false);
    }
}
