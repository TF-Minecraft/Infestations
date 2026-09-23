package net.tfminecraft.infestations.cache;

import java.util.HashSet;
import java.util.Set;

import net.tfminecraft.infestations.infestation.Severity;

public final class Cache {

    public static final double[] DEFAULT_SPREAD_LAND = {0.00099, 0.0030, 0.0050, 0.00694};
    public static final double[] DEFAULT_SPREAD_WATER = {0.00040, 0.0012, 0.0020, 0.00278};
    public static final double[] DEFAULT_SPREAD_SEA = {0.00015, 0.00045, 0.00075, 0.00104};

    public static boolean debug = false;
    public static boolean loggingEnabled = true;
    public static boolean wipeLog = true;
    public static boolean spread = false;
    public static int spreadIntervalSeconds = 600;
    public static double worsenChance = 0.0015;
    public static double[] spreadChanceLand = DEFAULT_SPREAD_LAND.clone();
    public static double[] spreadChanceWater = DEFAULT_SPREAD_WATER.clone();
    public static double[] spreadChanceSea = DEFAULT_SPREAD_SEA.clone();
    public static String lureItem = "ia.tfmc:lure";
    public static int joinSeconds = 20;
    public static int lureSpawnRadius = 48;
    public static int logoutGraceSeconds = 300;
    public static double deserterDamage = 2.0;
    public static double hologramViewRange = 96;
    public static Set<String> skipTerrains = new HashSet<>();

    private Cache() {}

    public static double hopChance(Severity severity, int viaOrdinal) {
        double[] table = switch (viaOrdinal) {
            case 0 -> spreadChanceLand;
            case 1 -> spreadChanceWater;
            default -> spreadChanceSea;
        };
        int index = severity.ordinal();
        if (index < 0 || index >= table.length) {
            return table[table.length - 1];
        }
        return table[index];
    }
}
