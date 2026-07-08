package com.fleet.idrotate.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fleet.idrotate.gen.IdentityService;

import java.util.Map;

/**
 * Minimal self-apply gate (Stage 3): one button generates a fresh identity on-device and writes it
 * to /data/local/tmp/specter/&lt;pkg&gt;.json via su — no PC. Proves the app can self-apply before the
 * full Compose UI is built. Scoped to DevInfo (fleet safety); target pkg is fixed here.
 *
 * This is a plain-View debug screen; the real UI is the Compose MainActivity (Stage 4).
 */
public class DebugActivity extends Activity {

    private static final String TARGET = "com.liuzh.deviceinfo"; // DevInfo only — never a fleet app

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Specter — self-apply test\nTarget: " + TARGET);
        title.setTextSize(18);

        Button gen = new Button(this);
        gen.setText("Generate + Apply new identity");

        final TextView out = new TextView(this);
        out.setText("Tap the button to generate a fresh identity and write it (via su) to where the "
                + "hook reads. Then relaunch DevInfo and check its Device tab.");
        out.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(out);

        gen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                out.setText("Generating…");
                new Thread(() -> {
                    String msg;
                    try {
                        IdentityService svc = new IdentityService(getApplicationContext());
                        Map<String, String> p = svc.generateAndApply(TARGET);
                        StringBuilder sb = new StringBuilder("APPLIED to " + TARGET + ":\n\n");
                        for (String k : new String[]{"android_id", "advertising_id", "gsf_id",
                                "serial", "build_manufacturer", "build_model", "imei1"})
                            sb.append(k).append(" = ").append(p.get(k)).append('\n');
                        sb.append("\nNow relaunch DevInfo to see these on its Device tab.");
                        msg = sb.toString();
                    } catch (Throwable t) {
                        msg = "FAILED: " + t.getMessage()
                                + "\n\n(If this is a root error, grant this app in Magisk and retry.)";
                    }
                    final String fmsg = msg;
                    runOnUiThread(() -> out.setText(fmsg));
                }).start();
            }
        });

        root.addView(title);
        root.addView(gen);
        root.addView(scroll);
        setContentView(root);
    }
}
