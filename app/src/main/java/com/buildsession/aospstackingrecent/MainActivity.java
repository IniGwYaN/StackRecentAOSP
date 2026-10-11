package com.buildsession.aospstackingrecent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

/** Settings screen for the StackRecentAOSP LSPosed module. */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "com.buildsession.aospstackingrecent_preferences";
    private static final String KEY_SPACING = "card_spacing_factor";
    private static final String KEY_TIGHTNESS = "card_tightness_factor";
    private static final float DEFAULT_SPACING = 4.7f;
    private static final float DEFAULT_TIGHTNESS = 0.72f;
    private static final String GITHUB_URL = "https://github.com/IniGwYaN/StackRecentAOSP";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_SPACING)) {
            prefs.edit().putFloat(KEY_SPACING, DEFAULT_SPACING).commit();
        }
        if (!prefs.contains(KEY_TIGHTNESS)) {
            prefs.edit().putFloat(KEY_TIGHTNESS, DEFAULT_TIGHTNESS).commit();
        }
        fixPermissions();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface, Color.BLACK));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipToPadding(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Keep the content clear of status/navigation bars on edge-to-edge Android versions.
        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(
                    bars.left + dp(22),
                    bars.top + dp(20),
                    bars.right + dp(22),
                    bars.bottom + dp(24));
            return windowInsets;
        });

        TextView eyebrow = text("LSPOSED MODULE", 12, color(androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4));
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setPadding(0, dp(6), 0, dp(8));
        content.addView(eyebrow);

        TextView title = text(getString(com.buildsession.aospstackingrecent.R.string.app_name), 30,
                color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView subtitle = text(getString(com.buildsession.aospstackingrecent.R.string.module_subtitle), 16,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBDBDBD));
        subtitle.setPadding(0, dp(5), 0, dp(16));
        content.addView(subtitle);

        TextView debugBadge = text(getString(com.buildsession.aospstackingrecent.R.string.debug_preview), 11,
                color(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF202020));
        debugBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        debugBadge.setLetterSpacing(0.05f);
        debugBadge.setPadding(dp(13), dp(8), dp(13), dp(8));
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setColor(color(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8));
        badgeBackground.setCornerRadius(dp(100));
        debugBadge.setBackground(badgeBackground);
        LinearLayout.LayoutParams badgeParams = wrapParams();
        badgeParams.bottomMargin = dp(24);
        content.addView(debugBadge, badgeParams);

        TextView setupLabel = sectionLabel("GETTING STARTED");
        content.addView(setupLabel);

        MaterialCardView setupCard = createCard();
        LinearLayout setupCardContent = verticalContainer(dp(18));

        LinearLayout setupHeading = new LinearLayout(this);
        setupHeading.setOrientation(LinearLayout.HORIZONTAL);
        setupHeading.setGravity(Gravity.CENTER_VERTICAL);

        TextView checkIcon = text("✓", 22,
                color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF202020));
        checkIcon.setGravity(Gravity.CENTER);
        checkIcon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setColor(color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF));
        iconBackground.setCornerRadius(dp(16));
        checkIcon.setBackground(iconBackground);
        setupHeading.addView(checkIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout headingText = verticalContainer(0);
        LinearLayout.LayoutParams headingTextParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headingTextParams.leftMargin = dp(14);
        setupHeading.addView(headingText, headingTextParams);

        TextView setupTitle = text("Enable in LSPosed", 17,
                color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
        setupTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headingText.addView(setupTitle);
        TextView setupSubline = text("Then select a supported launcher", 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBDBDBD));
        setupSubline.setPadding(0, dp(3), 0, 0);
        headingText.addView(setupSubline);
        setupCardContent.addView(setupHeading);

        TextView setupBody = text(getString(com.buildsession.aospstackingrecent.R.string.module_setup_body), 14,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBDBDBD));
        setupBody.setPadding(0, dp(16), 0, dp(14));
        setupBody.setLineSpacing(dp(3), 1f);
        setupCardContent.addView(setupBody);

        TextView scopeChip = text("Launcher3   •   Pixel Launcher", 12,
                color(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF202020));
        scopeChip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        scopeChip.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable scopeBackground = new GradientDrawable();
        scopeBackground.setColor(color(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8));
        scopeBackground.setCornerRadius(dp(12));
        scopeChip.setBackground(scopeBackground);
        setupCardContent.addView(scopeChip, wrapParams());

        setupCard.addView(setupCardContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(setupCard);

        TextView preferencesLabel = sectionLabel("PREFERENCES");
        LinearLayout.LayoutParams preferencesParams = wrapParams();
        preferencesParams.topMargin = dp(8);
        content.addView(preferencesLabel, preferencesParams);

        MaterialCardView iconCard = createCard();
        LinearLayout iconCardContent = verticalContainer(dp(18));
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout iconTextColumn = verticalContainer(0);
        LinearLayout.LayoutParams iconTextParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        iconRow.addView(iconTextColumn, iconTextParams);

        TextView iconTitle = text(getString(com.buildsession.aospstackingrecent.R.string.launcher_icon_title), 17,
                color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
        iconTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        iconTextColumn.addView(iconTitle);
        TextView iconBody = text(getString(com.buildsession.aospstackingrecent.R.string.launcher_icon_body), 13,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBDBDBD));
        iconBody.setPadding(0, dp(5), dp(10), 0);
        iconBody.setLineSpacing(dp(2), 1f);
        iconTextColumn.addView(iconBody);

        SwitchCompat iconSwitch = new SwitchCompat(this);
        iconSwitch.setChecked(isLauncherIconEnabled());
        iconSwitch.setContentDescription(getString(com.buildsession.aospstackingrecent.R.string.show_launcher_icon));
        iconSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setLauncherIconEnabled(isChecked));
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.leftMargin = dp(8);
        iconRow.addView(iconSwitch, switchParams);
        iconCardContent.addView(iconRow);
        iconCard.addView(iconCardContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(iconCard);

        MaterialButton githubButton = new MaterialButton(this);
        githubButton.setText(getString(com.buildsession.aospstackingrecent.R.string.github_button));
        githubButton.setTextSize(15);
        githubButton.setAllCaps(false);
        githubButton.setCornerRadius(dp(18));
        githubButton.setMinHeight(dp(54));
        githubButton.setInsetTop(0);
        githubButton.setInsetBottom(0);
        githubButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF)));
        githubButton.setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF202020));
        githubButton.setOnClickListener(v -> openRepository());
        LinearLayout.LayoutParams githubParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        githubParams.topMargin = dp(8);
        githubParams.bottomMargin = dp(14);
        content.addView(githubButton, githubParams);

        TextView debugNote = text(getString(com.buildsession.aospstackingrecent.R.string.debug_note), 12,
                color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBDBDBD));
        debugNote.setGravity(Gravity.CENTER);
        debugNote.setPadding(dp(12), dp(4), dp(12), dp(12));
        content.addView(debugNote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scrollView);
        ViewCompat.requestApplyInsets(scrollView);
    }

    private void configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        boolean isLightTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(isLightTheme);
        controller.setAppearanceLightNavigationBars(isLightTheme);
    }

    private MaterialCardView createCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant, 0xFF49454F));
        card.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow,
                color(com.google.android.material.R.attr.colorSurface, Color.BLACK)));
        card.setUseCompatPadding(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(16);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout verticalContainer(int paddingDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (paddingDp > 0) {
            int padding = dp(paddingDp);
            layout.setPadding(padding, padding, padding, padding);
        }
        return layout;
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 12,
                color(androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4));
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLetterSpacing(0.12f);
        label.setPadding(dp(2), dp(2), 0, dp(10));
        return label;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        return view;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int attribute, int fallback) {
        return MaterialColors.getColor(this, attribute, fallback);
    }

    private void openRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
        } catch (Exception exception) {
            Toast.makeText(this, "Could not open GitHub", Toast.LENGTH_SHORT).show();
        }
    }

    private ComponentName launcherAlias() {
        return new ComponentName(this, "com.buildsession.aospstackingrecent.LauncherAlias");
    }

    private boolean isLauncherIconEnabled() {
        try {
            return getPackageManager().getComponentEnabledSetting(launcherAlias())
                    == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception exception) {
            return false;
        }
    }

    private void setLauncherIconEnabled(boolean enabled) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    launcherAlias(),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception exception) {
            Toast.makeText(this, "Unable to update launcher icon", Toast.LENGTH_SHORT).show();
        }
    }

    private void fixPermissions() {
        try {
            java.io.File sharedPrefsDir = new java.io.File(getApplicationInfo().dataDir, "shared_prefs");
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.setExecutable(true, false);
                sharedPrefsDir.setReadable(true, false);
                java.io.File prefsFile = new java.io.File(sharedPrefsDir, PREFS_NAME + ".xml");
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false);
                }
            }
        } catch (Exception ignored) {
            // Preference access is best-effort; the module settings screen should remain usable.
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
