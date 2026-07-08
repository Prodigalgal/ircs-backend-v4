package com.prodigalgal.ircs.common.logging;

public final class Log4j2RuntimeBootstrap {

    static final String ALLOWED_PROTOCOLS_PROPERTY = "log4j2.Configuration.allowedProtocols";
    static final String NATIVE_SAFE_ALLOWED_PROTOCOLS = "resource,file,jar,http,https";

    private Log4j2RuntimeBootstrap() {
    }

    public static void configureBeforeSpringApplicationRun() {
        System.setProperty(
                ALLOWED_PROTOCOLS_PROPERTY,
                System.getProperty(ALLOWED_PROTOCOLS_PROPERTY, NATIVE_SAFE_ALLOWED_PROTOCOLS)
        );
    }
}
