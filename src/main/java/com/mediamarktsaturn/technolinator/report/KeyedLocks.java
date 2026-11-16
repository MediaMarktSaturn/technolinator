package com.mediamarktsaturn.technolinator.report;

/**
 * A fixed number of locks, shared by all keys, so that operations on the same key exclude each other without remembering any key.
 */
final class KeyedLocks {

    private final Object[] locks;

    KeyedLocks(int stripes) {
        locks = new Object[stripes];
        for (var i = 0; i < stripes; i++) {
            locks[i] = new Object();
        }
    }

    Object of(String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
