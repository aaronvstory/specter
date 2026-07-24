package com.specter.module.gen;

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

    // USA only. Carriers span the real MNOs + top MVNOs; brands are the dominant US-market Android
    // makers (Samsung/Google/Motorola/LG) so the device, carrier, and phone all read as one US device.
    public static final Country US = new Country("US", "United States",
            new String[][]{
                    {"310260", "T-Mobile"}, {"311480", "Verizon"}, {"310410", "AT&T"},
                    {"310120", "Sprint"}, {"311580", "US Cellular"}, {"310030", "AT&T"},
                    {"310160", "T-Mobile"}, {"311870", "Boost Mobile"},
                    {"310004", "Verizon"}, {"310090", "AT&T"}, {"312530", "Sprint"},
                    {"311882", "Mint Mobile"}, {"310240", "T-Mobile"},
            },
            "nanp",
            new String[]{"samsung", "google", "motorola", "lge"});

    private static final Map<String, Country> BY_CODE = new LinkedHashMap<>();
    static {
        BY_CODE.put(US.code, US);
    }

    /** Look up by code (case-insensitive); always US (USA-only build). */
    public static Country of(String code) {
        return US;
    }

    public static Country[] all() { return new Country[]{US}; }
}
