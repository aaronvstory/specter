#!/system/bin/sh
MOD=/data/adb/modules/specter_zygisk
rm -rf "$MOD"
mkdir -p "$MOD/zygisk"
cp /data/local/tmp/spz/module.prop "$MOD/module.prop"
cp /data/local/tmp/spz/sepolicy.rule "$MOD/sepolicy.rule"
cp /data/local/tmp/spz/zygisk/arm64-v8a.so "$MOD/zygisk/arm64-v8a.so"
chown -R 0:0 "$MOD"
chmod 0755 "$MOD" "$MOD/zygisk"
chmod 0644 "$MOD/module.prop" "$MOD/sepolicy.rule" "$MOD/zygisk/arm64-v8a.so"
rm -f "$MOD/zygisk/libshadowhook.so"
echo "=== installed ==="
ls -laZ "$MOD" "$MOD/zygisk"
