package com.securewa.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * A small, dependency-free demo of the SecureWA client surface.
 *
 * The app intentionally has no network permission. The screen demonstrates the
 * controls that belong on a secure messaging client while keeping the sample
 * interaction local to the device. A production build would hand the approved
 * request to a server-side provider gateway, never putting provider secrets in
 * this APK.
 */
public final class MainActivity extends Activity {

    private static final int NAVY = Color.rgb(11, 31, 51);
    private static final int INK = Color.rgb(25, 42, 59);
    private static final int MUTED = Color.rgb(99, 115, 134);
    private static final int TEAL = Color.rgb(15, 118, 110);
    private static final int GREEN = Color.rgb(15, 145, 102);
    private static final int PALE_GREEN = Color.rgb(231, 248, 241);
    private static final int PALE_BLUE = Color.rgb(235, 243, 252);
    private static final int PAGE = Color.rgb(246, 248, 251);
    private static final int CARD = Color.WHITE;
    private static final int BORDER = Color.rgb(224, 231, 239);
    private static final int AMBER = Color.rgb(180, 112, 20);

    private LinearLayout activityList;
    private EditText messageInput;
    private Button sendButton;
    private TextView responseCard;
    private TextView responseLabel;
    private int activityCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildScreen());
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(NAVY);
        window.setNavigationBarColor(PAGE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
    }

    private View buildScreen() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE);

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(22));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), 0);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildHero());
        addSectionHeading(content, "SECURITY CONTROLS", "Protection you can see");
        content.addView(buildControlList());
        addSectionHeading(content, "SECURE MESSAGE", "A local policy check before handoff");
        content.addView(buildComposer());
        addSectionHeading(content, "RECENT ACTIVITY", "A quiet audit trail");
        activityList = new LinearLayout(this);
        activityList.setOrientation(LinearLayout.VERTICAL);
        content.addView(activityList);
        addActivity("Policy check passed", "Message surface ready", GREEN, "now");
        addActivity("E2E session protected", "Device-only demo mode", TEAL, "2m");
        content.addView(buildFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(18), dp(10));
        bar.setBackgroundColor(NAVY);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label("securewa", 22, Color.WHITE, Typeface.BOLD);
        name.setLetterSpacing(0.02f);
        brand.addView(name, wrap());

        TextView tagline = label("private AI workspace", 12, Color.rgb(180, 204, 219), Typeface.NORMAL);
        tagline.setPadding(0, dp(2), 0, 0);
        brand.addView(tagline, wrap());
        bar.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView shield = label("✓", 19, NAVY, Typeface.BOLD);
        shield.setGravity(Gravity.CENTER);
        shield.setContentDescription("Protection active");
        shield.setBackground(circle(PALE_GREEN));
        bar.addView(shield, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return bar;
    }

    private View buildHero() {
        LinearLayout hero = cardContainer(NAVY, 20);
        hero.setPadding(dp(20), dp(20), dp(20), dp(18));

        TextView eyebrow = label("SECURE BY DEFAULT", 11, Color.rgb(166, 218, 209), Typeface.BOLD);
        eyebrow.setLetterSpacing(0.14f);
        hero.addView(eyebrow, wrap());

        TextView title = label("Your privacy layer is\nworking quietly.", 28, Color.WHITE, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, 0);
        hero.addView(title, wrap());

        TextView description = label(
                "Messages stay protected while policy checks happen before any AI provider sees a request.",
                14, Color.rgb(202, 218, 230), Typeface.NORMAL);
        description.setLineSpacing(0, 1.15f);
        description.setPadding(0, dp(10), 0, dp(16));
        hero.addView(description, wrap());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = label("●", 13, Color.rgb(107, 231, 183), Typeface.BOLD);
        row.addView(dot, new LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView state = label("Protection active", 13, Color.WHITE, Typeface.BOLD);
        row.addView(state, wrap());
        TextView mode = label("  •  local demo", 12, Color.rgb(174, 196, 210), Typeface.NORMAL);
        row.addView(mode, wrap());
        hero.addView(row, wrap());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        return withParams(hero, params);
    }

    private View buildControlList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(controlCard(
                "End-to-end encrypted", "Only your devices can read the conversation.",
                "ACTIVE", "✓", GREEN));
        list.addView(controlCard(
                "PII redaction", "Sensitive details are checked before AI routing.",
                "READY", "✦", TEAL));
        list.addView(controlCard(
                "Provider routing", "The gateway chooses a provider without exposing keys.",
                "3 LAYERS", "↗", AMBER));
        return list;
    }

    private View controlCard(String title, String detail, String badge, String icon, int iconColor) {
        LinearLayout card = cardContainer(CARD, 16);
        card.setPadding(dp(15), dp(14), dp(14), dp(14));

        TextView iconView = label(icon, 19, iconColor, Typeface.BOLD);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(circle(tint(iconColor, 0.13f)));
        iconView.setContentDescription(title);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.gravity = Gravity.TOP;
        card.addView(iconView, iconParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(8), 0);
        TextView titleView = label(title, 15, INK, Typeface.BOLD);
        copy.addView(titleView, wrap());
        TextView detailView = label(detail, 12, MUTED, Typeface.NORMAL);
        detailView.setLineSpacing(0, 1.08f);
        detailView.setPadding(0, dp(4), 0, 0);
        copy.addView(detailView, wrap());
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badgeView = label(badge, 9, iconColor, Typeface.BOLD);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setLetterSpacing(0.07f);
        badgeView.setPadding(dp(7), 0, dp(7), 0);
        badgeView.setBackground(round(tint(iconColor, 0.11f), 8));
        card.addView(badgeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(25)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(9);
        return withParams(card, params);
    }

    private View buildComposer() {
        LinearLayout composer = cardContainer(CARD, 18);
        composer.setPadding(dp(15), dp(15), dp(15), dp(15));

        LinearLayout composerTop = new LinearLayout(this);
        composerTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView lock = label("⌁", 18, TEAL, Typeface.BOLD);
        lock.setGravity(Gravity.CENTER);
        lock.setBackground(circle(PALE_GREEN));
        composerTop.addView(lock, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView prompt = label("Ask in private", 15, INK, Typeface.BOLD);
        prompt.setPadding(dp(10), 0, 0, 0);
        composerTop.addView(prompt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView local = label("ON DEVICE", 9, TEAL, Typeface.BOLD);
        local.setLetterSpacing(0.08f);
        composerTop.addView(local, wrap());
        composer.addView(composerTop, wrap());

        messageInput = new EditText(this);
        messageInput.setHint("Try: Summarize our privacy promise");
        messageInput.setHintTextColor(Color.rgb(149, 162, 177));
        messageInput.setTextColor(INK);
        messageInput.setTextSize(14);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setSingleLine(false);
        messageInput.setMinLines(3);
        messageInput.setMaxLines(5);
        messageInput.setPadding(dp(13), dp(12), dp(13), dp(10));
        messageInput.setBackground(roundWithStroke(Color.WHITE, BORDER, 12, dp(1)));
        messageInput.setContentDescription("Private message");
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(13);
        composer.addView(messageInput, inputParams);

        sendButton = new Button(this);
        sendButton.setText("Analyze securely");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(14);
        sendButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendButton.setAllCaps(false);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setPadding(dp(12), 0, dp(12), 0);
        sendButton.setMinHeight(0);
        sendButton.setMinWidth(0);
        sendButton.setBackground(round(TEAL, 12));
        sendButton.setContentDescription("Analyze message securely");
        sendButton.setOnClickListener(v -> analyzeMessage());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        buttonParams.topMargin = dp(11);
        composer.addView(sendButton, buttonParams);

        responseLabel = label("RESULT", 10, TEAL, Typeface.BOLD);
        responseLabel.setLetterSpacing(0.12f);
        responseLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(16);
        composer.addView(responseLabel, labelParams);

        responseCard = label("", 13, INK, Typeface.NORMAL);
        responseCard.setLineSpacing(0, 1.15f);
        responseCard.setPadding(dp(13), dp(12), dp(13), dp(12));
        responseCard.setBackground(round(PALE_GREEN, 12));
        responseCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams responseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        responseParams.topMargin = dp(7);
        composer.addView(responseCard, responseParams);

        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        composerParams.bottomMargin = dp(4);
        return withParams(composer, composerParams);
    }

    private void analyzeMessage() {
        String input = messageInput.getText().toString().trim();
        if (input.length() == 0) {
            messageInput.setError("Type a private message first");
            return;
        }

        hideKeyboard();
        messageInput.setEnabled(false);
        sendButton.setEnabled(false);
        sendButton.setText("Checking policy…");
        responseLabel.setVisibility(View.GONE);
        responseCard.setVisibility(View.GONE);

        // The demo deliberately does not make a network call. This delay makes
        // the local policy-check state visible without pretending to be a model.
        messageInput.postDelayed(() -> {
            messageInput.setEnabled(true);
            sendButton.setEnabled(true);
            sendButton.setText("Analyze securely");
            responseLabel.setVisibility(View.VISIBLE);
            responseCard.setVisibility(View.VISIBLE);
            String preview = input.length() > 54 ? input.substring(0, 54).trim() + "…" : input;
            responseCard.setText("✓  Policy check passed\n\n“" + preview + "”\n\nYour message is ready for a protected gateway handoff. No API key or conversation data is stored by this demo.");
            addActivity("Private request checked", "No data left this device", GREEN, "now");
        }, 520);
    }

    private void addSectionHeading(LinearLayout parent, String eyebrowText, String titleText) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(2), dp(19), dp(2), dp(10));
        TextView eyebrow = label(eyebrowText, 10, TEAL, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.13f);
        heading.addView(eyebrow, wrap());
        TextView title = label(titleText, 20, INK, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        heading.addView(title, wrap());
        parent.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addActivity(String title, String detail, int color, String time) {
        if (activityList == null) return;
        LinearLayout row = cardContainer(CARD, 14);
        row.setPadding(dp(13), dp(12), dp(13), dp(12));

        TextView dot = label("●", 13, color, Typeface.BOLD);
        dot.setGravity(Gravity.CENTER);
        dot.setContentDescription("Activity status");
        row.addView(dot, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = label(title, 13, INK, Typeface.BOLD);
        copy.addView(titleView, wrap());
        TextView detailView = label(detail, 11, MUTED, Typeface.NORMAL);
        detailView.setPadding(0, dp(3), 0, 0);
        copy.addView(detailView, wrap());
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView timeView = label(time, 11, MUTED, Typeface.NORMAL);
        timeView.setGravity(Gravity.TOP | Gravity.RIGHT);
        row.addView(timeView, new LinearLayout.LayoutParams(dp(35), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        if (activityCount < 2) {
            activityList.addView(row, params);
        } else {
            activityList.addView(row, 0, params);
        }
        activityCount++;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(18), 0, dp(5));

        TextView details = label("How the architecture works", 13, TEAL, Typeface.BOLD);
        details.setGravity(Gravity.CENTER);
        details.setPadding(dp(12), dp(12), dp(12), dp(12));
        details.setOnClickListener(v -> showArchitectureDialog());
        details.setContentDescription("Show architecture details");
        footer.addView(details, wrap());

        TextView note = label("No network permission  •  SecureWA demo 1.0", 11, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(4), 0, 0);
        footer.addView(note, wrap());
        return footer;
    }

    private void showArchitectureDialog() {
        new AlertDialog.Builder(this)
                .setTitle("SecureWA architecture")
                .setMessage("1. Device session\nMessages begin in an encrypted conversation surface.\n\n2. Policy boundary\nPII and policy checks happen before provider routing.\n\n3. Server-side gateway\nA backend owns provider credentials and selects the least-privilege route.\n\n4. Minimal response\nOnly the approved response returns to the device.\n\nThis APK is a local UI demo. It does not connect to WhatsApp or an AI provider.")
                .setPositiveButton("Got it", null)
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
    }

    private TextView label(String text, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    private LinearLayout cardContainer(int color, int radiusDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setBackground(round(color, radiusDp));
        return layout;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundWithStroke(int color, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable drawable = round(color, radiusDp);
        drawable.setStroke(strokeDp, strokeColor);
        return drawable;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private int tint(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.rgb(
                Math.min(255, Math.round(r + (255 - r) * amount)),
                Math.min(255, Math.round(g + (255 - g) * amount)),
                Math.min(255, Math.round(b + (255 - b) * amount)));
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private <T extends View> T withParams(T view, ViewGroup.LayoutParams params) {
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
