package cn.local.simpleauth;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Dependency-free navigation drawer with edge-open and left-swipe-close gestures. */
final class DrawerHost extends FrameLayout {
    private final View shade;
    private final View main;
    private final ScrollView panel;
    private final BooleanSupplier allowed;
    private final Supplier<View> menu;
    private float startX, startY;
    private boolean open, dragging;
    private int topInset, bottomInset;
    DrawerHost(Context context, View main, BooleanSupplier allowed, Supplier<View> menu) {
        super(context); this.main = main; this.allowed = allowed; this.menu = menu;
        addView(main, new LayoutParams(-1, -1));
        shade = new View(context); shade.setBackgroundColor(0x660D241A); shade.setOnClickListener(v -> close());
        addView(shade, new LayoutParams(-1, -1)); shade.setVisibility(GONE);
        panel = new ScrollView(context); panel.setFillViewport(true); panel.setBackgroundColor(0xFFF4F7F3); panel.setElevation(dp(12));
        LayoutParams p = new LayoutParams(Math.min(dp(320), (int)(getResources().getDisplayMetrics().widthPixels * .86)), -1, Gravity.LEFT);
        addView(panel, p); panel.setVisibility(GONE);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }
    void setBars(int top, int bottom) { topInset = top; bottomInset = bottom; panel.setPadding(0, topInset, 0, bottomInset); updateGestureArea(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); updateGestureArea(); }
    private void updateGestureArea() {
        if (android.os.Build.VERSION.SDK_INT < 29 || getHeight() == 0) return;
        int top = Math.max(topInset, (getHeight() - dp(180)) / 2);
        int bottom = Math.min(getHeight() - bottomInset, top + dp(180));
        if (bottom > top) setSystemGestureExclusionRects(java.util.Collections.singletonList(new android.graphics.Rect(0, top, dp(36), bottom)));
    }
    boolean isOpen() { return open; }
    void open() {
        if (!allowed.getAsBoolean() || open) return;
        ((android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(main.getWindowToken(), 0);
        panel.removeAllViews(); panel.addView(menu.get()); panel.setPadding(0, topInset, 0, bottomInset);
        open = true; shade.setVisibility(VISIBLE); panel.setVisibility(VISIBLE); panel.requestFocus();
        main.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        announceForAccessibility(getContext().getString(R.string.drawer_opened));
    }
    void close() { open = false; panel.setVisibility(GONE); shade.setVisibility(GONE); panel.removeAllViews(); main.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO); }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { startX = event.getX(); startY = event.getY(); dragging = false; }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                if (!open && startX < dp(36) && dx > 0 && allowed.getAsBoolean()) { open(); dragging = true; return true; }
                if (open && dx < 0) { close(); dragging = true; return true; }
            }
        }
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (dragging) { if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = false; return true; }
        return super.onTouchEvent(event);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
