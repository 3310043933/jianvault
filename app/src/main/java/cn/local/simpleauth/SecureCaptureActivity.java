package cn.local.simpleauth;

import android.os.Bundle;
import android.view.WindowManager;
import com.journeyapps.barcodescanner.CaptureActivity;

/** Keep provisioning QR codes out of screenshots and the recent-apps preview. */
public class SecureCaptureActivity extends CaptureActivity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
}
