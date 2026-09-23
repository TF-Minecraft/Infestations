package net.tfminecraft.infestations.infestation;

import java.util.Locale;

public enum Severity {
    MILD,
    WORRYING,
    SEVERE,
    EXTREME;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String display() {
        String id = id();
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    public boolean canWorsen() {
        return this != EXTREME;
    }

    public Severity worse() {
        return switch (this) {
            case MILD -> WORRYING;
            case WORRYING -> SEVERE;
            case SEVERE, EXTREME -> EXTREME;
        };
    }

    public static Severity fromString(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
