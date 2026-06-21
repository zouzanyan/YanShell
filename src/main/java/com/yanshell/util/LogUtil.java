package com.yanshell.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny logging helper. Centralises slf4j access so other layers don't need
 * the import. Kept intentionally minimal for the MVP.
 */
public final class LogUtil {

    private LogUtil() {
    }

    public static Logger get(Class<?> cls) {
        return LoggerFactory.getLogger(cls);
    }
}
