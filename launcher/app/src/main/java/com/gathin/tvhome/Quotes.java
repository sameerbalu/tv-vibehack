package com.gathin.tvhome;

/** Short lines for the quote widget and the screensaver. Written for this launcher, no names attached. */
final class Quotes {

    private Quotes() {
    }

    static final String[] LINES = {
            "Small steps every day still reach the top.",
            "Slow is fine. Stopping is the only way to lose.",
            "Today is a good day to start the thing.",
            "Quiet progress beats loud plans.",
            "Rest is part of the work.",
            "Be the calm in a busy room.",
            "You do not need the whole map, just the next turn.",
            "Good things grow in unhurried time.",
            "Make it simple, then make it kind.",
            "A tidy mind makes better choices.",
            "Curiosity is a fine way to spend an evening.",
            "Finish one thing and the next gets easier.",
            "Warm light, good music, no rush.",
            "What you repeat becomes what you are.",
            "Listen first, answer second.",
            "The best view comes after the hardest climb.",
            "Let the day be as big as you want it.",
            "Patience is speed you cannot see yet.",
            "Do it well, then do it again.",
            "Dream in colour, plan in pencil.",
            "Every expert was once a beginner who kept going.",
            "Breathe in, slow down, carry on.",
            "Kind words cost nothing and travel far.",
            "Your pace is your own.",
            "Sunsets happen every day. Go and look.",
            "Start where you are with what you have.",
            "Noise fades, good habits stay.",
            "Choose the path that makes you curious.",
            "A little courage goes a long way.",
            "Tonight, the sky is enough."
    };

    static String forDay(int dayOfYear) {
        return LINES[Math.floorMod(dayOfYear, LINES.length)];
    }
}
