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

    // The one accent — a BRIGHT pastel yellow (distinctive, not a dim/muddy orange-gold).
    public static final int GOLD    = 0xFFFFD54A; // THE accent / primary fill / active nav (bright pastel yellow)
    public static final int GOLD_HI = 0xFFFFE07A; // hover
    public static final int GOLD_DIM= 0xFFD9B23A; // pressed
    public static final int ON_GOLD = 0xFF211B02; // DARK ink for text ON a yellow fill

    // Secondary "outlined ghost" button
    public static final int BTN      = 0xFF2E2E3A;
    public static final int BTN_HI   = 0xFF3A3A48;
    public static final int BTN_EDGE = 0xFF585866;

    // Status — desaturated so they read on charcoal
    public static final int SAGE  = 0xFF7FB58C; // success / on
    public static final int RED   = 0xFFEF8A8A; // error / destructive / warning
    public static final int AMBER = 0xFFF0B562; // warning
    public static final int BLUE  = 0xFF6CC4E8; // info
    // The fourth step between AMBER and RED, so a vendor's own four-band risk scale (Scamalytics:
    // low / medium / high / very high) can be reported in its own colours without collapsing two
    // of the bands into one.
    public static final int ORANGE = 0xFFEE9B5A;

    // ---- SPACING SCALE (dp) — an 8pt-ish grid so the whole UI shares one rhythm. Use these, not ad-hoc
    // numbers. (A pro layout has ~5 spacing steps, not 22 different values.)
    public static final int S1 = 4;    // hairline gaps / inline
    public static final int S2 = 8;    // default gap between related items
    public static final int S3 = 12;   // padding inside a control
    public static final int S4 = 16;   // card padding / section inset (the standard edge margin)
    public static final int S5 = 24;   // between sections
    public static final int S6 = 32;   // big breaks

    // ---- TYPE SCALE (sp) — 5 steps, not 8 random sizes. title > heading > body > label > caption.
    public static final int T_TITLE   = 22;  // screen title (the wordmark)
    public static final int T_HEADING = 17;  // card title / primary value
    public static final int T_BODY    = 15;  // body text / button label
    public static final int T_LABEL   = 13;  // secondary / row label
    public static final int T_CAPTION = 12;  // captions / meta / hints

    // ---- RADIUS (dp) — TIGHT, intentional corners (not the soft "AI-slop" over-rounded look). Crisp.
    public static final int R_CARD = 8;    // cards / sheets
    public static final int R_CTRL = 6;    // buttons / inputs
    public static final int R_PILL = 999;  // fully-round (only for genuine pills / icon-button ripple)
}
