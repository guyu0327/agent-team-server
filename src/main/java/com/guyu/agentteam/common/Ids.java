package com.guyu.agentteam.common;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    public static String next() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
