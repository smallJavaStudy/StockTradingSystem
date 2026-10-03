package com.stock.agent;

public enum ModelChoice {
    DEEPSEEK_V4("deepseek-v4", "DeepSeek V4 PRO"),
    DEEPSEEK_V5("deepseek-v5", "DeepSeek V5 FLASH"),
    KIMI("kimi", "Kimi");

    private final String key;
    private final String displayName;

    ModelChoice(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }

    public static ModelChoice fromKey(String key) {
        for (ModelChoice m : values()) {
            if (m.key.equalsIgnoreCase(key)) return m;
        }
        throw new IllegalArgumentException("Unknown model choice: " + key);
    }
}
