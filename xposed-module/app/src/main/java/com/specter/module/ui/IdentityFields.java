package com.specter.module.ui;

import java.util.Arrays;
import java.util.List;

/**
 * Display metadata for the Identity screen — which profile keys show as editable cards, in what
 * order, with human labels. Mirrors GeerGit's Identity list. The device-simulation Build.* fields
 * are grouped separately (edited as a bundle via "Randomize device"), so individual Build.* keys
 * are not per-card editable here.
 */
final class IdentityFields {
    private IdentityFields() {}

    static final class Field {
        final String key, label;
        final boolean randomizable; // false for device-bundle-derived read-only display
        Field(String key, String label, boolean randomizable) {
            this.key = key; this.label = label; this.randomizable = randomizable;
        }
    }

    /** Individually-rotatable identifiers (each gets EDIT + RANDOMIZE). */
    static final List<Field> IDENTIFIERS = Arrays.asList(
            new Field("android_id", "Android ID", true),
            new Field("gsf_id", "Google Services Framework ID", true),
            new Field("advertising_id", "Advertising ID", true),
            new Field("imei1", "IMEI (SIM 1)", true),
            new Field("imei2", "IMEI (SIM 2)", true),
            new Field("serial", "Serial", true),
            new Field("media_drm_id", "MediaDRM ID", true),
            new Field("bluetooth_mac", "Bluetooth MAC", true),
            new Field("wifi_mac", "Wi-Fi MAC", true),
            new Field("wifi_bssid", "Wi-Fi BSSID", true),
            new Field("wifi_ssid", "Wi-Fi SSID", true),
            new Field("mobile_number", "Phone number", true),
            new Field("sim_subscriber_imsi", "IMSI", true),
            new Field("sim_serial_iccid", "SIM serial (ICCID)", true),
            new Field("gmail", "Gmail", true)
    );

    /** Device-simulation (Build.*) fields — shown read-only, rotated as a coherent bundle. */
    static final List<Field> DEVICE = Arrays.asList(
            new Field("build_manufacturer", "Manufacturer", false),
            new Field("build_model", "Model", false),
            new Field("build_brand", "Brand", false),
            new Field("build_device", "Device", false),
            new Field("build_fingerprint", "Fingerprint", false),
            new Field("sim_operator_name", "Carrier", false)
    );
}
