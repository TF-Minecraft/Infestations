package net.tfminecraft.infestations.infestation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class Infestation {

    private final int provinceId;
    private String groupId;
    private Severity severity;
    private LurePhase phase = LurePhase.NONE;
    private String worldName;
    private Integer lureX;
    private Integer lureY;
    private Integer lureZ;
    private long joinEndsAt;
    private int lureRemaining;
    private int pendingSpawns;
    private int enemiesAlive;
    private int ambientAlive;
    private int waveRetryAtTick;
    private long lureActivatedAt;
    private final Set<UUID> committed = new HashSet<>();
    private final Map<UUID, Long> logoutGraceUntil = new HashMap<>();
    private final Set<UUID> deathOnLogin = new HashSet<>();

    public Infestation(int provinceId, String groupId, Severity severity) {
        this.provinceId = provinceId;
        this.groupId = groupId;
        this.severity = severity;
    }

    public int getProvinceId() {
        return provinceId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public LurePhase getPhase() {
        return phase;
    }

    public void setPhase(LurePhase phase) {
        this.phase = phase;
    }

    public boolean hasLure() {
        return phase != LurePhase.NONE && worldName != null && lureX != null;
    }

    public Location lureLocation() {
        if (worldName == null || lureX == null || lureY == null || lureZ == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, lureX + 0.5, lureY, lureZ + 0.5);
    }

    public Block lureBlock() {
        if (worldName == null || lureX == null || lureY == null || lureZ == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return world.getBlockAt(lureX, lureY, lureZ);
    }

    public boolean isLureBlock(Block block) {
        if (block == null || worldName == null || lureX == null) {
            return false;
        }
        return worldName.equals(block.getWorld().getName())
                && lureX == block.getX()
                && lureY == block.getY()
                && lureZ == block.getZ();
    }

    public void placeLure(Block block, long joinEndsAt, int budget) {
        this.worldName = block.getWorld().getName();
        this.lureX = block.getX();
        this.lureY = block.getY();
        this.lureZ = block.getZ();
        this.phase = LurePhase.JOINING;
        this.joinEndsAt = joinEndsAt;
        this.lureRemaining = budget;
        this.pendingSpawns = 0;
        this.enemiesAlive = 0;
        this.committed.clear();
        this.logoutGraceUntil.clear();
        this.deathOnLogin.clear();
        this.waveRetryAtTick = 0;
        this.lureActivatedAt = 0;
    }

    public void clearLure() {
        phase = LurePhase.NONE;
        worldName = null;
        lureX = null;
        lureY = null;
        lureZ = null;
        joinEndsAt = 0;
        lureRemaining = 0;
        pendingSpawns = 0;
        enemiesAlive = 0;
        committed.clear();
        logoutGraceUntil.clear();
        deathOnLogin.clear();
        waveRetryAtTick = 0;
        lureActivatedAt = 0;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public Integer getLureX() {
        return lureX;
    }

    public void setLureX(Integer lureX) {
        this.lureX = lureX;
    }

    public Integer getLureY() {
        return lureY;
    }

    public void setLureY(Integer lureY) {
        this.lureY = lureY;
    }

    public Integer getLureZ() {
        return lureZ;
    }

    public void setLureZ(Integer lureZ) {
        this.lureZ = lureZ;
    }

    public long getJoinEndsAt() {
        return joinEndsAt;
    }

    public void setJoinEndsAt(long joinEndsAt) {
        this.joinEndsAt = joinEndsAt;
    }

    public int joinSecondsLeft() {
        long left = joinEndsAt - System.currentTimeMillis();
        return (int) Math.max(0, Math.ceil(left / 1000.0));
    }

    public int getLureRemaining() {
        return lureRemaining;
    }

    public void setLureRemaining(int lureRemaining) {
        this.lureRemaining = Math.max(0, lureRemaining);
    }

    public int displayRemaining() {
        if (phase == LurePhase.JOINING) {
            return lureRemaining;
        }
        return Math.max(lureRemaining, pendingSpawns + enemiesAlive);
    }

    public int getPendingSpawns() {
        return pendingSpawns;
    }

    public void setPendingSpawns(int pendingSpawns) {
        this.pendingSpawns = Math.max(0, pendingSpawns);
    }

    public int getEnemiesAlive() {
        return enemiesAlive;
    }

    public void setEnemiesAlive(int enemiesAlive) {
        this.enemiesAlive = Math.max(0, enemiesAlive);
    }

    public int getAmbientAlive() {
        return ambientAlive;
    }

    public void setAmbientAlive(int ambientAlive) {
        this.ambientAlive = Math.max(0, ambientAlive);
    }

    public int getWaveRetryAtTick() {
        return waveRetryAtTick;
    }

    public void setWaveRetryAtTick(int waveRetryAtTick) {
        this.waveRetryAtTick = Math.max(0, waveRetryAtTick);
    }

    public long getLureActivatedAt() {
        return lureActivatedAt;
    }

    public void setLureActivatedAt(long lureActivatedAt) {
        this.lureActivatedAt = Math.max(0, lureActivatedAt);
    }

    public int ambientCap() {
        return net.tfminecraft.infestations.loader.GroupLoader.tune(groupId, severity).ambientCap();
    }

    public int lureBudget() {
        return net.tfminecraft.infestations.loader.GroupLoader.tune(groupId, severity).lureCount();
    }

    public Set<UUID> getCommitted() {
        return committed;
    }

    public Map<UUID, Long> getLogoutGraceUntil() {
        return logoutGraceUntil;
    }

    public Set<UUID> getDeathOnLogin() {
        return deathOnLogin;
    }

    public boolean isCommitted(UUID id) {
        return committed.contains(id);
    }
}
