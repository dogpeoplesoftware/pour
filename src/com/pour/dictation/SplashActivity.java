package com.pour.dictation;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launch screen: what Pour is, then record or open the bin. */
public class SplashActivity extends Activity {

    private boolean navigating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.initTheme(this);
        CrashLog.install(getApplicationContext());

        // Kroko model ships in APK assets (no download needed).
        // Skip the legacy Whisper download flow entirely.
        SharedPreferences prefs = prefs();
        boolean firstRun = !prefs.getBoolean("first_run_done", false);
        if (firstRun) {
            prefs.edit().putBoolean("first_run_done", true).apply();
        }

        showSplashUi();
    }

    /** Normal splash screen with Start/Bucket/Projects buttons. */
    private void showSplashUi() {

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        // 20dp base like every other page. The header below pins to the
        // top-right at exactly the same spot as bucket/capture/projects;
        // the centered content keeps its own 28dp side margins.
        Ui.applySafeInsets(this, root, 20);

        // Header pinned to the top of the screen, independent of the
        // centered content. (The old single centered LinearLayout let this
        // row drift down with the content; no margin tweak can fix that.)
        LinearLayout headerRow = Ui.header(this, "",
                null, null, null, true, true, false);
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(headerRow, headerParams);

        // Centered splash content, split into two independently-positioned
        // blocks so the logo + tagline can sit slightly above true center
        // (optical centering) while the buttons stay exactly where the old
        // single centered block put them. Same 28dp side margins as before
        // (8dp inside the 20dp root padding). Both start invisible to avoid
        // a one-frame jump when their final offsets are applied below.
        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        brandBlock.setGravity(Gravity.CENTER);
        brandBlock.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams brandParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        brandParams.leftMargin = Ui.dp(this, 8);
        brandParams.rightMargin = Ui.dp(this, 8);
        root.addView(brandBlock, brandParams);

        LinearLayout buttonBlock = new LinearLayout(this);
        buttonBlock.setOrientation(LinearLayout.VERTICAL);
        buttonBlock.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        buttonParams.leftMargin = Ui.dp(this, 8);
        buttonParams.rightMargin = Ui.dp(this, 8);
        root.addView(buttonBlock, buttonParams);

        TextView name = new TextView(this);
        name.setText("POUR");
        name.setTextSize(56);
        name.setTextColor(Ui.ACCENT);
        try {
            android.graphics.Typeface shrikhand =
                    android.graphics.Typeface.createFromAsset(
                            getAssets(), "fonts/Shrikhand-Regular.ttf");
            name.setTypeface(shrikhand);
        } catch (Exception e) {
            name.setTypeface(Typeface.DEFAULT_BOLD);
        }
        name.setGravity(Gravity.CENTER);
        brandBlock.addView(name);

        TextView desc = new TextView(this);
        desc.setText("Speak, and Pour writes it down.\n"
                + "Turn thoughts into words\n"
                + "you can keep and send anywhere.");
        desc.setTextColor(Ui.MUTED);
        desc.setTextSize(16);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = Ui.dp(this, 20);
        descParams.bottomMargin = Ui.dp(this, 40);
        brandBlock.addView(desc, descParams);

        Button recBtn = new Button(this);
        recBtn.setText("Start recording");
        recBtn.setTextColor(Ui.ON_ACCENT);
        recBtn.setTextSize(16);
        recBtn.setBackground(Ui.filled(this, Ui.ACCENT, 16));
        recBtn.setMinHeight(0);
        recBtn.setMinimumHeight(0);
        recBtn.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 16));
        recBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (navigating) return;
                navigating = true;
                Intent i = new Intent(SplashActivity.this, MainActivity.class);
                i.putExtra("autostart", true);
                startActivity(i);
            }
        });
        buttonBlock.addView(recBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button libBtn = new Button(this);
        libBtn.setText("Bucket");
        Ui.styleBtn(this, libBtn);
        libBtn.setTextSize(16);
        LinearLayout.LayoutParams libParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        libParams.topMargin = Ui.dp(this, 12);
        libBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (navigating) return;
                navigating = true;
                startActivity(new Intent(SplashActivity.this,
                        LibraryActivity.class));
            }
        });
        buttonBlock.addView(libBtn, libParams);

        Button projBtn = new Button(this);
        projBtn.setText("Projects");
        Ui.styleBtn(this, projBtn);
        projBtn.setTextSize(16);
        LinearLayout.LayoutParams projParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        projParams.topMargin = Ui.dp(this, 12);
        projBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (navigating) return;
                navigating = true;
                startActivity(new Intent(SplashActivity.this,
                        ProjectsActivity.class));
            }
        });
        buttonBlock.addView(projBtn, projParams);

        setContentView(root);
        // Final positioning, applied after the first layout pass so both
        // blocks have measured heights. The buttons land exactly where the
        // old single centered block put them (pushed down by half the brand
        // block's height); the brand block keeps its old vertical center
        // minus ~3.5% of screen height (optical centering). No clipping
        // risk: this moves the blocks via layout margins, not translation.
        root.post(new Runnable() {
            @Override public void run() {
                int hBrand = brandBlock.getHeight();
                int hBtns = buttonBlock.getHeight();
                if (hBrand <= 0 || hBtns <= 0) {
                    brandBlock.setVisibility(View.VISIBLE);
                    buttonBlock.setVisibility(View.VISIBLE);
                    return;
                }
                int shiftUp = (int) (getResources().getDisplayMetrics()
                        .heightPixels * 0.035f);
                FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams)
                        buttonBlock.getLayoutParams();
                bp.topMargin = hBrand / 2;
                buttonBlock.setLayoutParams(bp);
                FrameLayout.LayoutParams brp = (FrameLayout.LayoutParams)
                        brandBlock.getLayoutParams();
                brp.topMargin = -(hBtns / 2 + shiftUp);
                brandBlock.setLayoutParams(brp);
                brandBlock.setVisibility(View.VISIBLE);
                buttonBlock.setVisibility(View.VISIBLE);
            }
        });
        checkModel();
        // Always go to Capture (MainActivity) — never auto-skip to Bucket.
        // The user explicitly wants the landing screen, not the Bucket.
    }

    /**
     * First-run model preparation screen. Shows a progress bar while the
     * 43MB voice model copies from the APK. When done, shows the normal
     * splash UI. This is seamless — the user sees progress, not a broken
     * record button.
     */

    /**
     * First launch goes straight into the Bin — the app's home — instead
     * of parking on the splash. The Bin's first-use empty state is the
     * one actionable prompt.
     */
    private boolean maybeSkipToBin() {
        final SharedPreferences prefs = prefs();
        if (prefs.getBoolean("first_run_done", false)) return false;
        new Thread(new Runnable() {
            @Override public void run() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        prefs.edit().putBoolean("first_run_done", true)
                                .apply();
                        startActivity(new Intent(SplashActivity.this,
                                LibraryActivity.class));
                        finish();
                    }
                });
            }
        }).start();
        return true;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("pour_pro", MODE_PRIVATE);
    }

    /**
     * The voice model is bundled in the APK: copy it to internal storage
     * on first launch (a local file copy, no download), then warm up the
     * engine. If the copy fails, the app still works via the phone's
     * built-in dictation.
     */
    private void checkModel() {
        // Warm up Kroko in background; the model ships in assets/kroko/.
        KrokoEngine.warmup(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.syncTheme(this)) return;
        navigating = false;
    }
}
