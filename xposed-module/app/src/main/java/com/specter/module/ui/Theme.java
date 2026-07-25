package com.specter.module.ui;

/**
 * Warm-dark "charcoal" theme tokens (single source of truth for the whole UI). Ported from the
 * design kit: never pure black, off-white text, ONE warm gold accent, desaturated status colors,
 * layered surfaces, 1px hairline borders, tight radii. Dark ink on gold fills.
 *
 * All values are ARGB color ints (0xFF...) so they drop straight into setBackgroundColor /
 * setTextColor. Keep the UI reading from here instead of inline hex.
 */
public final class Theme {
    private Theme() {}

    // Surfaces — step up from the base, never #000
    public static final int BG      = 0xFF16161A; // window background
    public static final int BG2     = 0xFF1E1E24; // recessed / chip
    public static final int CARD    = 0xFF212129; // panel / card
    public static final int CARD2   = 0xFF262630; // raised / input fill / hover / inactive tab
    public static final int SELECT  = 0xFF332B18; // selected row (gold-tinted)
    public static final int LINE    = 0xFF34343F; // hairline border
    public static final int LINE_HI = 0xFF4A4A57; // active/hover border

    // Text — three tiers, off-white not white
    public static final int INK  = 0xFFF1F1F4; // primary
    public static final int SOFT = 0xFFB9B9C4; // secondary
    public static final int DIM  = 0xFF7D7D8A; // captions / labels / placeholders

    // The one warm accent
    public static final int GOLD    = 0xFFE7B94E; // THE accent / primary fill / active tab
    public static final int GOLD_HI = 0xFFF2C963; // hover
    public static final int GOLD_DIM= 0xFFA8862F; // pressed / disabled-ish
    public static final int ON_GOLD = 0xFF231A05; // DARK ink for text ON a gold fill

    // Secondary "outlined ghost" button
    public static final int BTN      = 0xFF2E2E3A;
    public static final int BTN_HI   = 0xFF3A3A48;
    public static final int BTN_EDGE = 0xFF585866;

    // Status — desaturated so they read on charcoal
    public static final int SAGE  = 0xFF7FB58C; // success / on
    public static final int RED   = 0xFFEF8A8A; // error / destructive / warning
    public static final int AMBER = 0xFFF0B562; // warning
    public static final int BLUE  = 0xFF6CC4E8; // info
}
