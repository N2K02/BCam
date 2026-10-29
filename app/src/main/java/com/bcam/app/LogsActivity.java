package com.bcam.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Settings > Logs: privacy-filtered, app-private diagnostic journal viewer. */
public final class LogsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        page.setPadding(pad,pad,pad,pad);
        TextView header = new TextView(this);
        header.setText("‹  Logs                                " + getApplicationInfo().loadLabel(getPackageManager()));
        header.setTextSize(20);
        header.setOnClickListener(v -> finish());
        page.addView(header);
        Switch enabled = new Switch(this);
        enabled.setText("Enable detailed logging");
        enabled.setChecked(AppLog.INSTANCE.getEnabled().getValue());
        enabled.setOnCheckedChangeListener((CompoundButton v, boolean on) -> AppLog.INSTANCE.setEnabled(on));
        page.addView(enabled);
        TextView details = new TextView(this);
        details.setText("Private local storage • 7 days • 2 MB maximum • fatal crashes always captured");
        page.addView(details);
        TextView content = new TextView(this);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextSize(11);
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> content.setText(AppLog.INSTANCE.all()));
        page.addView(refresh);
        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> {
            ClipboardManager mgr = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            mgr.setPrimaryClip(ClipData.newPlainText("App logs", AppLog.INSTANCE.all()));
        });
        page.addView(copy);
        Button clear = new Button(this);
        clear.setText("Clear all");
        clear.setOnClickListener(v -> { AppLog.INSTANCE.clear(); content.setText(""); });
        page.addView(clear);
        content.setText(AppLog.INSTANCE.all());
        setContentView(page);
    }
}
