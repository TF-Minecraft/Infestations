package net.tfminecraft.infestations.database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InfestationData {

    public int provinceId;
    public String groupId;
    public String severity;
    public String phase;
    public String worldName;
    public Integer lureX;
    public Integer lureY;
    public Integer lureZ;
    public long joinEndsAt;
    public int lureRemaining;
    public int pendingSpawns;
    public int enemiesAlive;
    public int ambientAlive;
    public long lureActivatedAt;
    public List<String> committed = new ArrayList<>();
    public Map<String, Long> logoutGraceUntil = new HashMap<>();
    public List<String> deathOnLogin = new ArrayList<>();
}
