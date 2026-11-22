package net.tfminecraft.infestations;

import java.lang.reflect.*;
import java.util.*;
import net.tfminecraft.infestations.cache.Cache;
import net.tfminecraft.infestations.loader.GroupLoader;
import net.tfminecraft.infestations.reward.PermadeathZones;
import net.tfminecraft.infestations.reward.RewardConfig;
import net.tfminecraft.infestations.reward.RewardDrops;
import net.tfminecraft.infestations.spawn.SpawnLog;
import net.tfminecraft.infestations.wildlife.WildlifeConfig;

public final class InfestationsTestState implements AutoCloseable {
    private final Map<Field,Object> state = new LinkedHashMap<>();
    public InfestationsTestState() throws Exception {
        for (Class<?> type : List.of(Infestations.class, Cache.class, GroupLoader.class, Messages.class, SpawnLog.class,
                RewardConfig.class, RewardDrops.class, PermadeathZones.class, WildlifeConfig.class)) {
            for (Field f : type.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (!Modifier.isFinal(f.getModifiers())) state.put(f, f.get(null));
                else if (f.get(null) instanceof Map<?,?> map) state.put(f, new LinkedHashMap<>(map));
            }
        }
    }
    @Override @SuppressWarnings({"rawtypes", "unchecked"}) public void close() throws Exception {
        for (var entry : state.entrySet()) {
            Field f = entry.getKey();
            if (Modifier.isFinal(f.getModifiers())) {
                Map map = (Map) f.get(null); map.clear(); map.putAll((Map)entry.getValue());
            } else f.set(null, entry.getValue());
        }
    }
}
