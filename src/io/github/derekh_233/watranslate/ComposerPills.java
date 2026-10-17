package io.github.derekh_233.watranslate;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * The two pills that sit just above the chat box.
 *
 * <p>Replaces a gesture that had to be explained. Long-pressing the send button
 * was invisible and read as a trick; a visible language and a visible action
 * sitting where the eye already is needs no explaining.</p>
 *
 * <p>Colours are taken from the chat box's own text colour at low opacity rather
 * than hard-coded, so the pills follow WhatsApp's light and dark themes instead
 * of fighting them.</p>
 *
 * <p>The chosen language is kept in the host app's files directory. That looks
 * odd but is forced: the framework hands a hooked process read-only preferences
 * and offers no way back to the module app, so a choice made inside a chat has
 * nowhere else to live. Only this side ever reads it, which is what the outgoing
 * translation needs.</p>
 */
final class ComposerPills {

    /** Marks our row so a re-inflated composer does not collect duplicates. */
    private static final String ROW_TAG = "lstrans-composer-pills";

    /** Where the in-chat language choice is kept; see the class comment. */
    private static final String OVERRIDE_FILE = "lstrans_out_lang.txt";

    private ComposerPills() {
    }

    /**
     * Put the pills above the chat box, once.
     *
     * <p>They go into the conversation's RelativeLayout as a sibling pinned above
     * the composer, and the message list is re-pointed to sit above them. Adding
     * a row <em>inside</em> the composer instead - between the input row and its
     * wrapper - was tried first and blew the composer up from 222px to 2312px,
     * squeezing the message list to a sliver: that container is built for exactly
     * one child.</p>
     *
     * <p>Called every time the box has its text set, so a composer WhatsApp
     * rebuilt gets its pills back. Bailing out is the common case and costs a
     * tag lookup.</p>
     */
    static void ensure(TextView input) {
        if (input == null) {
            return;
        }
        Context ctx = null;
        try {
            ctx = input.getContext();
            View composer = findFooter(input);
            if (composer == null) {
                note(ctx, "no footer above the chat box");
                return;
            }
            ViewParent containerParent = composer.getParent();
            if (!(containerParent instanceof RelativeLayout)) {
                note(ctx, "container is "
                        + (containerParent == null ? "null" : containerParent.getClass().getName())
                        + ", not a RelativeLayout");
                return;
            }
            RelativeLayout container = (RelativeLayout) containerParent;
            if (!Prefs.inputTranslateEnabled()) {
                View stale = container.findViewWithTag(ROW_TAG);
                if (stale != null) {
                    container.removeView(stale);
                }
                note(ctx, "input translation is off");
                return;
            }
            View existing = container.findViewWithTag(ROW_TAG);
            if (existing != null) {
                refreshLabel(existing, input);
                if (existing instanceof LinearLayout) {
                    keepClearOfSendButton((LinearLayout) existing, input, container);
                }
                note(ctx, "already placed");
                return;
            }
            View row = build(input);
            row.setId(View.generateViewId());
            row.setTag(ROW_TAG);
            // Fixed height on purpose. With only an ABOVE rule the RelativeLayout
            // stretched this row over the whole message area - 2498px instead of
            // 130 - which parked the pills halfway up the chat. A definite height
            // leaves nothing to stretch.
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    dp(ctx, 46));
            lp.addRule(RelativeLayout.ABOVE, composer.getId());
            container.addView(row, lp);
            if (row instanceof LinearLayout) {
                final LinearLayout insetRow = (LinearLayout) row;
                final TextView insetInput = input;
                final View insetContainer = container;
                // The send button has no width until the composer has been laid
                // out, and setText does not come round again once the box is
                // empty - so wait for the layout rather than the next visit.
                row.getViewTreeObserver().addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                keepClearOfSendButton(insetRow, insetInput, insetContainer);
                                if (insetRow.getPaddingRight() > 0) {
                                    android.view.ViewTreeObserver vto =
                                            insetRow.getViewTreeObserver();
                                    if (vto.isAlive()) {
                                        vto.removeOnGlobalLayoutListener(this);
                                    }
                                }
                            }
                        });
            }

            // The message list has to stop where our row starts, or it would run
            // underneath it.
            View content = container.findViewById(android.R.id.content);
            if (content != null
                    && content.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams clp =
                        (RelativeLayout.LayoutParams) content.getLayoutParams();
                clp.addRule(RelativeLayout.ABOVE, row.getId());
                content.setLayoutParams(clp);
            }
            Logger.d("composer pills placed above the composer");
            note(ctx, "placed above " + container.getClass().getSimpleName());
        } catch (Throwable t) {
            Logger.w("composer pills skipped: " + t);
            note(ctx, "threw: " + t);
        }
    }

    /**
     * A breadcrumb the developer can read without root.
     *
     * <p>The module's own log lives in LSPosed's directory, which needs root to
     * open, and this particular piece of code only runs inside WhatsApp - so a
     * failing placement would otherwise be invisible. The host app's external
     * files directory is world-readable.</p>
     */
    private static void note(Context ctx, String why) {
        if (ctx == null || !Prefs.debugLog()) {
            return;
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "lstrans_pills.txt");
            if (f.exists() && f.length() > 8192) {
                if (!f.delete()) {
                    return;
                }
            }
            FileOutputStream out = new FileOutputStream(f, true);
            out.write((System.currentTimeMillis() + "  " + why + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Stop the row short of the send button.
     *
     * <p>WhatsApp puts its "jump to the latest message" button in the same corner
     * the row would otherwise reach, and a pill on top of it swallows the tap -
     * so the row is inset by the width of the send button column. The width is
     * only known after a layout pass, which is why this also runs on every
     * revisit rather than once at insertion.</p>
     */
    private static void keepClearOfSendButton(LinearLayout row, TextView input,
                                              View container) {
        try {
            Context ctx = input.getContext();
            if (ctx == null) {
                return;
            }
            int inset = clearOfChatBox(input, container);
            if (inset <= 0) {
                int id = input.getResources()
                        .getIdentifier("buttons", "id", ctx.getPackageName());
                View buttons = id == 0 ? null : container.findViewById(id);
                if (buttons == null || buttons.getWidth() <= 0) {
                    return;
                }
                inset = buttons.getWidth() + dp(ctx, 10);
            }
            if (row.getPaddingRight() == inset) {
                return;
            }
            row.setPadding(row.getPaddingLeft(), row.getPaddingTop(), inset,
                    row.getPaddingBottom());
        } catch (Throwable ignored) {
        }
    }

    /**
     * How far the row must be inset to line up with the chat box's right edge.
     *
     * <p>The composer's white pill is inset a few dp inside the row that carries
     * it, so aligning to the outer row left the translate pill jutting out past
     * the box it belongs to. Measuring the box itself puts the two edges
     * together.</p>
     */
    private static int clearOfChatBox(TextView input, View container) {
        int id = input.getResources()
                .getIdentifier("input_layout", "id", input.getContext().getPackageName());
        View box = id == 0 ? null : container.findViewById(id);
        if (box == null || box.getWidth() <= 0) {
            return 0;
        }
        int right = box.getRight();
        ViewParent p = box.getParent();
        while (p instanceof View && p != container) {
            right += ((View) p).getLeft();
            p = p.getParent();
        }
        int inset = container.getWidth() - right;
        return inset > 0 ? inset : 0;
    }

    private static View findFooter(TextView input) {
        try {
            Context ctx = input.getContext();
            if (ctx == null) {
                return null;
            }
            int footerId = input.getResources()
                    .getIdentifier("footer", "id", ctx.getPackageName());
            if (footerId == 0) {
                return null;
            }
            View node = input;
            for (int i = 0; i < 12 && node != null; i++) {
                if (node.getId() == footerId) {
                    return node;
                }
                ViewParent p = node.getParent();
                node = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Walk up a fixed number of parents; null if the chain is shorter. */
    private static View ancestor(View v, int steps) {
        View node = v;
        for (int i = 0; i < steps && node != null; i++) {
            ViewParent p = node.getParent();
            node = (p instanceof View) ? (View) p : null;
        }
        return node;
    }

    // ------------------------------------------------------------- the row

    private static View build(final TextView input) {
        Context ctx = input.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 6));

        final TextView language = pill(ctx, input, labelFor(input));
        language.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLanguage(input, language);
            }
        });
        row.addView(language);

        View spacer = new View(ctx);
        row.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView translate = pill(ctx, input, "翻译");
        translate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputTranslator.translateDraft(input);
            }
        });
        row.addView(translate);
        return row;
    }

    /** A rounded chip tinted from the chat box so it suits either theme. */
    private static TextView pill(Context ctx, TextView input, CharSequence text) {
        int base = input.getCurrentTextColor();
        int fill = (base & 0x00FFFFFF) | 0x14000000;   // 8% of the text colour

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor((base & 0x00FFFFFF) | 0xB0000000);   // ~70%
        tv.setPadding(dp(ctx, 14), dp(ctx, 5), dp(ctx, 14), dp(ctx, 5));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 20));
        bg.setColor(fill);
        tv.setBackground(bg);
        return tv;
    }

    private static void refreshLabel(View row, TextView input) {
        try {
            if (!(row instanceof LinearLayout)) {
                return;
            }
            LinearLayout r = (LinearLayout) row;
            if (r.getChildCount() == 0 || !(r.getChildAt(0) instanceof TextView)) {
                return;
            }
            TextView language = (TextView) r.getChildAt(0);
            String want = labelFor(input);
            if (!want.contentEquals(language.getText())) {
                language.setText(want);
            }
        } catch (Throwable ignored) {
        }
    }

    /** What the left pill says: the language the draft will be turned into. */
    private static String labelFor(TextView input) {
        String code = InputTranslator.effectiveTarget();
        String label = LocaleInfo.labelOf(code);
        String override = overrideLang();
        if (override != null) {
            return label + " ▾";
        }
        return label + "（自动）▾";
    }

    // ------------------------------------------------ language selection

    private static void chooseLanguage(final TextView input, final TextView pillView) {
        Context ctx = input.getContext();
        final boolean follows = overrideLang() == null;
        final String[] items = {"跟随对方（自动）", "指定语言…"};
        new AlertDialog.Builder(ctx)
                .setTitle("输入框翻译成什么语言")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            setOverrideLang(input.getContext(), null);
                            pillView.setText(labelFor(input));
                            toast(input, "已改为跟随对方");
                            return;
                        }
                        PickerDialog.showLanguage(input.getContext(),
                                InputTranslator.effectiveTarget(),
                                new PickerDialog.OnPick() {
                                    @Override
                                    public void picked(String code, String label) {
                                        setOverrideLang(input.getContext(), code);
                                        pillView.setText(labelFor(input));
                                        toast(input, "已改为固定翻译成" + label);
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        if (follows) {
            Logger.d("language pill opened; currently following the peer");
        }
    }

    // --------------------------------- the choice, stored beside the host app

    /** The in-chat choice, or null when the language follows the peer. */
    static String overrideLang() {
        try {
            File f = overrideFile(null, false);
            if (f == null || !f.exists()) {
                return null;
            }
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String v = new String(bos.toByteArray(), "UTF-8").trim();
            return v.isEmpty() ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setOverrideLang(Context ctx, String code) {
        try {
            File f = overrideFile(ctx, true);
            if (f == null) {
                return;
            }
            if (code == null) {
                if (f.exists() && !f.delete()) {
                    Logger.d("could not clear the in-chat language choice");
                }
                return;
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(code.getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            Logger.w("could not store the in-chat language choice: " + t);
        }
    }

    /** The store needs a Context once; later reads reuse the cached path. */
    private static volatile File cachedFile;

    private static File overrideFile(Context ctx, boolean forWrite) {
        File f = cachedFile;
        if (f == null && ctx != null) {
            f = new File(ctx.getFilesDir(), OVERRIDE_FILE);
            cachedFile = f;
        }
        return f;
    }

    private static void toast(View anchor, String text) {
        try {
            Context ctx = anchor.getContext();
            if (ctx != null) {
                Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
