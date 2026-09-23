package net.tfminecraft.infestations.spawn;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.infestations.Infestations;
import net.tfminecraft.infestations.loader.GroupLoader;

public final class MythicSpawner {

    private MythicSpawner() {}

    public static LivingEntity spawn(Location location, String groupId, int provinceId, String kind) {
        String mobId = GroupLoader.pickMobId(groupId);
        if (mobId == null || location == null) {
            return null;
        }
        MythicMob mm = MythicBukkit.inst().getMobManager().getMythicMob(mobId).orElse(null);
        if (mm == null) {
            Infestations.plugin.getLogger().warning("[Infestations] Unknown Mythic mob: " + mobId);
            return null;
        }
        ActiveMob am = mm.spawn(BukkitAdapter.adapt(location), 1);
        if (am == null || am.getEntity() == null) {
            return null;
        }
        if (!(am.getEntity().getBukkitEntity() instanceof LivingEntity living)) {
            return null;
        }
        Keys.tagMob(living.getPersistentDataContainer(), provinceId, kind);
        return living;
    }
}
