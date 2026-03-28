package com.example.neteasemedianotification;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.graphics.Color;

public class MainActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        
        TextView titleView = new TextView(this);
        titleView.setText("Netease Cloud Music Media Notification Module");
        titleView.setTextSize(20);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Color.parseColor("#333333"));
        
        TextView descView = new TextView(this);
        descView.setText("\nFeatures:\n\n" +
            "- Force create media notification for Netease Cloud Music IoT version\n" +
            "- Show playback controls in notification bar (Previous/Play/Pause/Next)\n" +
            "- Support HyperOS control center card style\n" +
            "- Enable this module in LSPosed and select Netease Cloud Music IoT\n" +
            "- Restart Netease Cloud Music IoT to take effect\n\n" +
            "Target package: com.netease.cloudmusic.iot\n");
        descView.setTextSize(14);
        descView.setGravity(Gravity.CENTER);
        
        layout.addView(titleView);
        layout.addView(descView);
        
        setContentView(layout);
    }
}