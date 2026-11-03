package tech.hawkon.dotctrl;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

/** A standalone touch counter for testing gesture delivery without interacting with another app. */
public class GestureTargetActivity extends Activity {
    private int clicks;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView counter = new TextView(this) {
            @Override public boolean onFilterTouchEventForSecurity(MotionEvent event) {
                boolean accepted = super.onFilterTouchEventForSecurity(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    Log.i("DotCtrlGestureTarget", "touch flags=" + event.getFlags() + " accepted=" + accepted);
                }
                return accepted;
            }
        };
        counter.setFilterTouchesWhenObscured(getIntent().getBooleanExtra("rejectObscured", false));
        counter.setGravity(Gravity.CENTER);
        counter.setTextSize(28);
        counter.setTextColor(Color.BLACK);
        counter.setBackgroundColor(Color.rgb(235, 245, 240));
        counter.setText("DotCtrl gesture test\nClicks: 0");
        counter.setOnClickListener(view -> {
            clicks++;
            counter.setText("DotCtrl gesture test\nClicks: " + clicks);
            Log.i("DotCtrlGestureTarget", "clicks=" + clicks);
        });
        setContentView(counter);
    }
}
