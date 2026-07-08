package com.fleet.idrotate.gen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-country data for the SIM (carriers, MCC/MNC), phone-number format, and device-brand bias.
 * Adding a country is a data edit here — no logic changes. USA is the default and its values are
 * byte-identical to the original hardcoded lists so existing seeded output/tests are unchanged.
 */
public final class Country {
    public final String code;              // "US", "UK"
    public final String name;              // display name
    public final String[][] carriers;      // {mccmnc, name}
    public final String phoneKind;         // "nanp" | "uk"
    public final String[] commonBrands;    // device-brand bias for this market

    private Country(String code, String name, String[][] carriers, String phoneKind, String[] commonBrands) {
        this.code = code; this.name = name; this.carriers = carriers;
        this.phoneKind = phoneKind; this.commonBrands = commonBrands;
    }

    public static final Country US = new Country("US", "United States",
            new String[][]{
                    {"310260", "T-Mobile"}, {"311480", "Verizon"}, {"310410", "AT&T"},
                    {"310120", "Sprint"}, {"311580", "US Cellular"}, {"310030", "AT&T"},
                    {"310160", "T-Mobile"}, {"311870", "Boost Mobile"},
            },
            "nanp",
            new String[]{"samsung", "google", "motorola", "oneplus", "lge"});

    public static final Country UK = new Country("UK", "United Kingdom",
            new String[][]{
                    {"23430", "EE"}, {"23410", "O2"}, {"23415", "Vodafone"},
                    {"23420", "Three"}, {"23433", "EE"}, {"23402", "O2"},
            },
            "uk",
            new String[]{"samsung", "google", "oneplus", "xiaomi", "sony"});

    private static final Map<String, Country> BY_CODE = new LinkedHashMap<>();
    static {
        BY_CODE.put(US.code, US);
        BY_CODE.put(UK.code, UK);
    }

    /** Look up by code (case-insensitive); defaults to US for null/unknown. */
    public static Country of(String code) {
        if (code == null) return US;
        Country c = BY_CODE.get(code.toUpperCase());
        return c != null ? c : US;
    }

    public static Country[] all() { return new Country[]{US, UK}; }
}
