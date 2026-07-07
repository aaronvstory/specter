package com.fleet.idrotate;

/**
 * Pure, Android-free decision logic for the hooks — extracted so it can be unit-tested on a plain
 * JVM (no device, no Robolectric). HookEntry calls into these; the Android glue stays in HookEntry.
 */
public final class SpoofLogic {
    private SpoofLogic() {}

    /**
     * A gservices cursor row is (name, value). The value is the LAST column. This returns true when
     * the caller is reading the value column of the "android_id" row — i.e. the GSF id to spoof.
     */
    public static boolean isAndroidIdValueColumn(String rowName, int columnIndex, int columnCount) {
        if (columnCount < 1) return false;
        return "android_id".equals(rowName) && columnIndex == (columnCount - 1);
    }

    /** getImei(slot)/getDeviceId(slot): slot 0 -> imei1, slot 1 -> imei2, anything else -> imei1. */
    public static String imeiForSlot(int slot, String imei1, String imei2) {
        return slot == 1 ? imei2 : imei1;
    }

    /** Parse a decimal GSF id to long; returns fallback on any malformed value (never throws). */
    public static long gsfToLong(String gsf, long fallback) {
        try {
            return Long.parseLong(gsf);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
