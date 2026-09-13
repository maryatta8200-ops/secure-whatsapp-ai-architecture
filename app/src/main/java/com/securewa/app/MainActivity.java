package com.securewa.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.Switch;
import android.widget.TextView;

/**
 * A small, dependency-free demo of the SecureWA client surface.
 *
 * The app keeps scanning and review local. Network access is optional and only
 * goes to a user-configured HTTPS gateway; provider secrets never enter this
 * APK. WhatsApp consumer integration remains an explicit user-mediated share.
 */
public final class MainActivity extends Activity {

    private static final int NAVY = Color.rgb(11, 31, 51);
    private static final int INK = Color.rgb(25, 42, 59);
    private static final int MUTED = Color.rgb(99, 115, 134);
    private static final int TEAL = Color.rgb(15, 118, 110);
    private static final int GREEN = Color.rgb(15, 145, 102);
    private static final int PALE_GREEN = Color.rgb(231, 248, 241);
    private static final int PAGE = Color.rgb(246, 248, 251);
    private static final int CARD = Color.WHITE;
    private static final int BORDER = Color.rgb(224, 231, 239);
    private static final int AMBER = Color.rgb(180, 112, 20);

    private LinearLayout activityList;
    private EditText messageInput;
    private Button sendButton;
    private Button shareButton;
    private Button gatewayButton;
    private Button cloudButton;
    private TextView responseCard;
    private TextView responseLabel;
    private SecureStore secureStore;
    private GatewayClient gatewayClient;
    private PrivacyScanner.ScanResult lastScan;
    private String lastSafeMessage;
    private boolean redactBeforeShare;
    private boolean keepAudit;
    private int activityCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secureStore = new SecureStore(this);
        gatewayClient = new GatewayClient();
        redactBeforeShare = secureStore.isRedactionEnabled();
        keepAudit = secureStore.isAuditEnabled();
        configureWindow();
        setContentView(buildScreen());
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (gatewayClient != null) gatewayClient.shutdown();
        super.onDestroy();
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.length() == 0 || messageInput == null) return;
        messageInput.setText(shared.toString());
        messageInput.setSelection(messageInput.length());
        addActivity("Text imported for review", "Nothing shared automatically", TEAL, "now");
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
        addActivity("Encrypted audit vault", "Android Keystore protected", TEAL, "2m");
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
                "Messages are scanned on-device, audit events are encrypted, and sharing stays under your control.",
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
        TextView mode = label("  •  device protected", 12, Color.rgb(174, 196, 210), Typeface.NORMAL);
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
                "Encrypted local vault", "Audit metadata is protected by the Android Keystore.",
                "ACTIVE", "✓", GREEN));
        list.addView(controlCard(
                "PII redaction", "Common emails, phones, cards, and keys are caught on-device.",
                "READY", "✦", TEAL));
        list.addView(controlCard(
                "WhatsApp handoff", "Share only after review; SecureWA never reads your chats.",
                "USER-DRIVEN", "↗", AMBER));
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
        TextView prompt = label("Review before sharing", 15, INK, Typeface.BOLD);
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
        sendButton.setText("Scan & prepare safely");
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

        shareButton = new Button(this);
        shareButton.setText("Share safe version to WhatsApp");
        shareButton.setTextColor(TEAL);
        shareButton.setTextSize(14);
        shareButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        shareButton.setAllCaps(false);
        shareButton.setGravity(Gravity.CENTER);
        shareButton.setPadding(dp(12), 0, dp(12), 0);
        shareButton.setMinHeight(0);
        shareButton.setMinWidth(0);
        shareButton.setBackground(roundWithStroke(Color.WHITE, TEAL, 12, dp(1)));
        shareButton.setContentDescription("Share the reviewed message to WhatsApp");
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> shareToWhatsApp());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        shareParams.topMargin = dp(9);
        composer.addView(shareButton, shareParams);

        gatewayButton = new Button(this);
        gatewayButton.setText("Ask secure AI gateway");
        gatewayButton.setTextColor(TEAL);
        gatewayButton.setTextSize(14);
        gatewayButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        gatewayButton.setAllCaps(false);
        gatewayButton.setGravity(Gravity.CENTER);
        gatewayButton.setPadding(dp(12), 0, dp(12), 0);
        gatewayButton.setMinHeight(0);
        gatewayButton.setMinWidth(0);
        gatewayButton.setBackground(roundWithStroke(Color.WHITE, BORDER, 12, dp(1)));
        gatewayButton.setContentDescription("Ask the configured secure AI gateway");
        gatewayButton.setVisibility(View.GONE);
        gatewayButton.setOnClickListener(v -> askSecureGateway());
        LinearLayout.LayoutParams gatewayParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        gatewayParams.topMargin = dp(9);
        composer.addView(gatewayButton, gatewayParams);

        cloudButton = new Button(this);
        cloudButton.setText("Send via WhatsApp Cloud API");
        cloudButton.setTextColor(NAVY);
        cloudButton.setTextSize(14);
        cloudButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cloudButton.setAllCaps(false);
        cloudButton.setGravity(Gravity.CENTER);
        cloudButton.setPadding(dp(12), 0, dp(12), 0);
        cloudButton.setMinHeight(0);
        cloudButton.setMinWidth(0);
        cloudButton.setBackground(roundWithStroke(Color.WHITE, NAVY, 12, dp(1)));
        cloudButton.setContentDescription("Send the reviewed message through WhatsApp Cloud API");
        cloudButton.setVisibility(View.GONE);
        cloudButton.setOnClickListener(v -> sendViaCloudApi());
        LinearLayout.LayoutParams cloudParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        cloudParams.topMargin = dp(9);
        composer.addView(cloudButton, cloudParams);

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

        final PrivacyScanner.ScanResult scan = PrivacyScanner.scan(input);
        final String safeMessage = redactBeforeShare ? scan.redactedText : input;
        lastScan = scan;
        lastSafeMessage = safeMessage;

        hideKeyboard();
        messageInput.setEnabled(false);
        sendButton.setEnabled(false);
        sendButton.setText("Scanning on device…");
        responseLabel.setVisibility(View.GONE);
        responseCard.setVisibility(View.GONE);
        shareButton.setVisibility(View.GONE);

        // Keep the scan local. The only persisted value is encrypted metadata,
        // never the message itself.
        messageInput.postDelayed(() -> {
            messageInput.setEnabled(true);
            sendButton.setEnabled(true);
            sendButton.setText("Scan & prepare safely");
            responseLabel.setVisibility(View.VISIBLE);
            responseCard.setVisibility(View.VISIBLE);
            shareButton.setVisibility(View.VISIBLE);
            updateGatewayActions();
            String preview = safeMessage.length() > 120
                    ? safeMessage.substring(0, 120).trim() + "…"
                    : safeMessage;
            boolean saved = keepAudit && secureStore.saveAudit(
                    "policy_passed|findings=" + scan.findingCount()
                            + "|redaction=" + redactBeforeShare
                            + "|at=" + System.currentTimeMillis());
            String auditLine = saved
                    ? "Encrypted audit event saved on this device."
                    : "Audit event was not saved; no plaintext fallback was used.";
            responseCard.setText("✓  Policy check passed\n\n"
                    + scan.summary() + "\n\nSafe handoff preview:\n“"
                    + preview + "”\n\n" + auditLine);
            addActivity("Private request checked", saved
                    ? "Encrypted metadata only" : "Audit write unavailable", GREEN, "now");
        }, 520);
    }

    private void updateGatewayActions() {
        if (gatewayButton == null || cloudButton == null) return;
        String endpoint = secureStore.getGatewayEndpoint();
        boolean gatewayConfigured = GatewayClient.isHttpsEndpoint(endpoint);
        gatewayButton.setVisibility(View.VISIBLE);
        gatewayButton.setText(gatewayConfigured
                ? "Ask secure AI gateway" : "Configure secure AI gateway");

        String recipient = secureStore.getWhatsAppRecipient();
        boolean cloudConfigured = gatewayConfigured && GatewayClient.isE164(recipient);
        cloudButton.setVisibility(View.VISIBLE);
        cloudButton.setText(cloudConfigured
                ? "Send via WhatsApp Cloud API" : "Configure Cloud API handoff");
    }

    private boolean refreshSafeMessage() {
        if (messageInput == null) return false;
        String input = messageInput.getText().toString().trim();
        if (input.length() == 0) {
            messageInput.setError("Type a private message first");
            return false;
        }
        lastScan = PrivacyScanner.scan(input);
        lastSafeMessage = redactBeforeShare ? lastScan.redactedText : input;
        return true;
    }

    private void askSecureGateway() {
        if (!refreshSafeMessage()) return;
        String endpoint = secureStore.getGatewayEndpoint();
        if (!GatewayClient.isHttpsEndpoint(endpoint)) {
            showPrivacyDialog();
            return;
        }

        gatewayButton.setEnabled(false);
        gatewayButton.setText("Contacting secure gateway…");
        gatewayClient.complete(endpoint, secureStore.getGatewayToken(), lastSafeMessage,
                new GatewayClient.Callback() {
                    @Override
                    public void onSuccess(String message) {
                        gatewayButton.setEnabled(true);
                        updateGatewayActions();
                        responseLabel.setVisibility(View.VISIBLE);
                        responseCard.setVisibility(View.VISIBLE);
                        responseCard.setText("✓  Secure gateway response\n\n" + limit(message, 4000)
                                + "\n\nThe request sent to the gateway was the reviewed handoff text.");
                        secureStore.saveAudit("gateway_complete|findings=" + lastScan.findingCount()
                                + "|at=" + System.currentTimeMillis());
                        addActivity("Secure AI response received", "Gateway credentials stayed server-side", GREEN, "now");
                    }

                    @Override
                    public void onError(String message) {
                        gatewayButton.setEnabled(true);
                        updateGatewayActions();
                        responseLabel.setVisibility(View.VISIBLE);
                        responseCard.setVisibility(View.VISIBLE);
                        responseCard.setText("Gateway unavailable\n\n" + message
                                + "\n\nNo provider credential is stored in this APK.");
                        addActivity("Gateway request failed", "No provider key was exposed", AMBER, "now");
                    }
                });
    }

    private void sendViaCloudApi() {
        if (!refreshSafeMessage()) return;
        String endpoint = secureStore.getGatewayEndpoint();
        String recipient = secureStore.getWhatsAppRecipient();
        if (!GatewayClient.isHttpsEndpoint(endpoint) || !GatewayClient.isE164(recipient)) {
            showPrivacyDialog();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Send through WhatsApp Cloud API?")
                .setMessage("This sends the reviewed text to " + recipient
                        + " through your configured server gateway. Meta credentials stay on the server.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> performCloudSend(endpoint, recipient))
                .show();
    }

    private void performCloudSend(String endpoint, String recipient) {
        cloudButton.setEnabled(false);
        cloudButton.setText("Sending securely…");
        gatewayClient.sendWhatsApp(endpoint, secureStore.getGatewayToken(), recipient, lastSafeMessage,
                new GatewayClient.Callback() {
                    @Override
                    public void onSuccess(String message) {
                        cloudButton.setEnabled(true);
                        updateGatewayActions();
                        responseLabel.setVisibility(View.VISIBLE);
                        responseCard.setVisibility(View.VISIBLE);
                        responseCard.setText("✓  WhatsApp Cloud API accepted the message\n\n"
                                + message + "\n\nThe Meta access token remained on the gateway.");
                        secureStore.saveAudit("whatsapp_cloud_send|redaction=" + redactBeforeShare
                                + "|at=" + System.currentTimeMillis());
                        addActivity("Cloud message accepted", "Meta credential stayed server-side", GREEN, "now");
                    }

                    @Override
                    public void onError(String message) {
                        cloudButton.setEnabled(true);
                        updateGatewayActions();
                        responseLabel.setVisibility(View.VISIBLE);
                        responseCard.setVisibility(View.VISIBLE);
                        responseCard.setText("Cloud API unavailable\n\n" + message);
                        addActivity("Cloud send failed", "No direct Meta call from APK", AMBER, "now");
                    }
                });
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max).trim() + "…" : value;
    }

    private void shareToWhatsApp() {
        if (!refreshSafeMessage()) return;

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, lastSafeMessage);

        String whatsappPackage = findWhatsAppPackage();
        if (whatsappPackage != null) share.setPackage(whatsappPackage);

        try {
            if (whatsappPackage == null) {
                startActivity(Intent.createChooser(share, "Share reviewed message"));
            } else {
                startActivity(share);
            }
            secureStore.saveAudit("whatsapp_handoff|redaction=" + redactBeforeShare
                    + "|at=" + System.currentTimeMillis());
            addActivity("WhatsApp handoff opened", redactBeforeShare
                    ? "Redacted text shared" : "User-approved original shared", TEAL, "now");
        } catch (ActivityNotFoundException ignored) {
            new AlertDialog.Builder(this)
                    .setTitle("WhatsApp is not installed")
                    .setMessage("Install WhatsApp or WhatsApp Business, then try the handoff again. SecureWA cannot read or send chat messages by itself.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private String findWhatsAppPackage() {
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};
        PackageManager packageManager = getPackageManager();
        for (String packageName : packages) {
            Intent probe = new Intent(Intent.ACTION_SEND);
            probe.setType("text/plain");
            probe.setPackage(packageName);
            if (packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                return packageName;
            }
        }
        return null;
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

        TextView privacy = label("Privacy settings", 13, INK, Typeface.BOLD);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(12), dp(10), dp(12), dp(10));
        privacy.setOnClickListener(v -> showPrivacyDialog());
        privacy.setContentDescription("Open privacy settings");
        footer.addView(privacy, wrap());

        TextView note = label("HTTPS gateway only  •  SecureWA 1.2", 11, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(4), 0, 0);
        footer.addView(note, wrap());
        return footer;
    }

    private void showPrivacyDialog() {
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(6), dp(2), dp(6), 0);

        TextView summary = label(
                "Choose what can leave this device. WhatsApp sharing is user-driven; the optional gateway must use HTTPS.",
                13, MUTED, Typeface.NORMAL);
        summary.setLineSpacing(0, 1.12f);
        settings.addView(summary, wrap());

        Switch redact = new Switch(this);
        redact.setText("Redact common PII before handoff");
        redact.setTextColor(INK);
        redact.setTextSize(14);
        redact.setChecked(redactBeforeShare);
        redact.setPadding(0, dp(12), 0, 0);
        settings.addView(redact, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch audit = new Switch(this);
        audit.setText("Keep encrypted policy audit metadata");
        audit.setTextColor(INK);
        audit.setTextSize(14);
        audit.setChecked(keepAudit);
        audit.setPadding(0, dp(4), 0, 0);
        settings.addView(audit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView gatewayTitle = label("OPTIONAL SERVER GATEWAY", 10, TEAL, Typeface.BOLD);
        gatewayTitle.setLetterSpacing(0.12f);
        gatewayTitle.setPadding(0, dp(17), 0, dp(5));
        settings.addView(gatewayTitle, wrap());

        EditText endpoint = new EditText(this);
        endpoint.setHint("https://your-gateway.example.com");
        endpoint.setText(secureStore.getGatewayEndpoint());
        endpoint.setTextColor(INK);
        endpoint.setHintTextColor(Color.rgb(149, 162, 177));
        endpoint.setTextSize(14);
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setPadding(dp(11), 0, dp(11), 0);
        endpoint.setBackground(roundWithStroke(Color.WHITE, BORDER, 10, dp(1)));
        endpoint.setContentDescription("HTTPS gateway URL");
        settings.addView(endpoint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        EditText token = new EditText(this);
        token.setHint("Gateway bearer token, if required");
        token.setTextColor(INK);
        token.setHintTextColor(Color.rgb(149, 162, 177));
        token.setTextSize(14);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setPadding(dp(11), 0, dp(11), 0);
        token.setBackground(roundWithStroke(Color.WHITE, BORDER, 10, dp(1)));
        token.setContentDescription("Gateway bearer token");
        LinearLayout.LayoutParams tokenParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        tokenParams.topMargin = dp(8);
        settings.addView(token, tokenParams);

        EditText recipient = new EditText(this);
        recipient.setHint("Cloud API recipient, e.g. +923001234567");
        recipient.setText(secureStore.getWhatsAppRecipient());
        recipient.setTextColor(INK);
        recipient.setHintTextColor(Color.rgb(149, 162, 177));
        recipient.setTextSize(14);
        recipient.setSingleLine(true);
        recipient.setInputType(InputType.TYPE_CLASS_PHONE);
        recipient.setPadding(dp(11), 0, dp(11), 0);
        recipient.setBackground(roundWithStroke(Color.WHITE, BORDER, 10, dp(1)));
        recipient.setContentDescription("WhatsApp Cloud API recipient in E.164 format");
        LinearLayout.LayoutParams recipientParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        recipientParams.topMargin = dp(8);
        settings.addView(recipient, recipientParams);

        TextView count = label("Encrypted entries on device: " + secureStore.auditCount(), 12, MUTED, Typeface.NORMAL);
        count.setPadding(0, dp(12), 0, 0);
        settings.addView(count, wrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Privacy and connections")
                .setView(settings)
                .setNegativeButton("Clear audit", (d, which) -> {
                    secureStore.clearAudits();
                    addActivity("Audit vault cleared", "No message content was stored", AMBER, "now");
                })
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String endpointValue = endpoint.getText().toString().trim();
                    String recipientValue = recipient.getText().toString().trim();
                    if (endpointValue.length() > 0 && !GatewayClient.isHttpsEndpoint(endpointValue)) {
                        endpoint.setError("Use a valid HTTPS URL");
                        return;
                    }
                    if (recipientValue.length() > 0 && !GatewayClient.isE164(recipientValue)) {
                        recipient.setError("Use E.164 format, for example +923001234567");
                        return;
                    }
                    if (!secureStore.setGatewayToken(token.getText().toString())) {
                        token.setError("Token could not be encrypted; it was not saved");
                        return;
                    }
                    redactBeforeShare = redact.isChecked();
                    keepAudit = audit.isChecked();
                    secureStore.setRedactionEnabled(redactBeforeShare);
                    secureStore.setAuditEnabled(keepAudit);
                    secureStore.setGatewayEndpoint(endpointValue);
                    secureStore.setWhatsAppRecipient(recipientValue);
                    if (lastSafeMessage != null) refreshSafeMessage();
                    updateGatewayActions();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showArchitectureDialog() {
        new AlertDialog.Builder(this)
                .setTitle("SecureWA architecture")
                .setMessage("1. Device session\nMessages begin in a review surface.\n\n2. Policy boundary\nCommon PII is detected and redacted before sharing.\n\n3. WhatsApp handoff\nThe Android share sheet opens WhatsApp only after your tap; this app cannot read chats.\n\n4. Server-side gateway\nThe optional HTTPS gateway owns AI-provider and Meta credentials. The APK sends only the reviewed text.\n\nDirect provider keys are never bundled in this APK.")
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
splayMetrics().density);
    }
}
