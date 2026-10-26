package com.gathin.tvhome;

/** One switch to quiet the decorative animations while a big menu (settings) is open, so the menu stays smooth. */
final class Perf {
    static volatile boolean paused;

    private Perf() {
    }
}
