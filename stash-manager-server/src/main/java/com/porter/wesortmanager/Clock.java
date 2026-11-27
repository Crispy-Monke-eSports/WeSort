package com.porter.wesortmanager;

/**
 * PR3 (#142): injectable clock (wall + monotonic) for deterministic tests.
 *
 * <p>Replaces the ad-hoc wall-clock supplier pairs and raw system time
 * calls. Production default is the
 * system clock; tests inject a simulated clock controlling both axes.
 */
public interface Clock {
    long millis();

    long nano();

    static Clock system() {
        return new Clock() {
            @Override
            public long millis() {
                return System.currentTimeMillis();
            }

            @Override
            public long nano() {
                return System.nanoTime();
            }
        };
    }
}
