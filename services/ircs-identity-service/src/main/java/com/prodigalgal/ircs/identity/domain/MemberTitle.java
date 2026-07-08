package com.prodigalgal.ircs.identity.domain;

import java.util.Arrays;

public enum MemberTitle {
    NOVICE(1, "萌新影迷"),
    TRAINEE(5, "进阶学徒"),
    ENTHUSIAST(10, "资深影迷"),
    CRITIC(20, "鉴片达人"),
    MASTER(30, "光影大师"),
    LEGEND(50, "影坛传奇"),
    GOD(80, "收视之神");

    private final int minLevel;
    private final String displayName;

    MemberTitle(int minLevel, String displayName) {
        this.minLevel = minLevel;
        this.displayName = displayName;
    }

    public static String byLevel(int level) {
        return Arrays.stream(values())
                .sorted((left, right) -> Integer.compare(right.minLevel, left.minLevel))
                .filter(title -> level >= title.minLevel)
                .findFirst()
                .map(title -> title.displayName)
                .orElse(NOVICE.displayName);
    }
}
