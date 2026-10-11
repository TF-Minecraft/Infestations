package net.tfminecraft.infestations.infestation;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;

/** Registered only with RPCharacters: a character's lure grace ends, or its pending death applies, once active. */
public final class CharacterActivatedListener implements Listener {

    private final InfestationManager manager;

    public CharacterActivatedListener(InfestationManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onCharacterActivated(CharacterActivatedEvent event) {
        if (event.getOwner() != null) {
            manager.characterReturned(event.getOwner());
        }
    }
}
