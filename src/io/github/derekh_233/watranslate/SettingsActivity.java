package io.github.derekh_233.watranslate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.graphics.drawable.Drawable;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The module entry point.
 *
 * Reached three ways, none of them inside WhatsApp's composer:
 *   1. the app icon in the launcher (MAIN/LAUNCHER);
 *   2. the LSPosed module list, which launches this activity;
 *   3. the "打开 WhatsApp" shortcut in the header below.
 */
public class SettingsActivity extends Activity {

    private static final int[] COLOR_PRESETS = {
            0xFF8A8A8A, 0xFF9E9E9E, 0xFF4A90D9, 0xFF1E88E5,
            0xFF43A047, 0xFFE53935, 0xFF8E24AA, 0xFF000000,
    };
    private static final String[] COLOR_NAMES = {
            "灰（默认）", "浅灰", "蓝", "深蓝", "绿", "红", "紫", "黑",
    };

    // WhatsApp's own palette, in both of its themes: the module lives inside
    // WhatsApp, so borrowing its colours reads as intended rather than as a
    // theme that missed. The indigo this used to run on was Material Design of
    // a decade ago, and it showed. Values are set by resolvePalette().
    private int PAGE;
    private int SURFACE;
    private int TEXT;
    private int MUTED;
    private int HAIRLINE;
    private int INACTIVE;
    private int FIELD;
    private int RIPPLE;
    private int ERROR;
    private int EDGE;
    private static final int ACCENT = 0xFF00A884;
    private boolean dark;

    private SharedPreferences sp;
    private LinearLayout engineKeyBox;

    private final List<View> tabViews = new ArrayList<View>();
    private final List<TextView> tabLabels = new ArrayList<TextView>();
    private final List<View> tabBars = new ArrayList<View>();
    private final List<String> tabNames = new ArrayList<String>();
    private LinearLayout tabTranslate;
    private LinearLayout tabLanguage;
    private LinearLayout tabAppearance;
    private LinearLayout tabAbout;
    private int currentTab;

    /**
     * Whether to draw dark, from the setting and the system.
     *
     * <p>Read straight from the preferences store rather than through Prefs,
     * because this runs before super.onCreate - the window background is chosen
     * when the theme is applied, so the theme has to be set first.</p>
     */
    private boolean resolveDark() {
        try {
            String mode = getApplicationContext()
                    .getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                    .getString(Prefs.KEY_THEME, "system");
            if ("dark".equals(mode)) {
                return true;
            }
            if ("light".equals(mode)) {
                return false;
            }
            int flags = getApplicationContext().getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private void resolvePalette() {
        if (dark) {
            PAGE = 0xFF0B141A;
            SURFACE = 0xFF1F2C34;
            TEXT = 0xFFE9EDEF;
            MUTED = 0xFF8696A0;
            HAIRLINE = 0xFF2A3942;
            INACTIVE = 0xFF8696A0;
            FIELD = 0xFF2A3942;
            RIPPLE = 0x1FFFFFFF;
            ERROR = 0xFFF2B8B5;
            EDGE = 0x33FFFFFF;
        } else {
            PAGE = 0xFFF7F8FA;
            SURFACE = 0xFFFFFFFF;
            TEXT = 0xFF111B21;
            MUTED = 0xFF667781;
            HAIRLINE = 0xFFE9EDEF;
            INACTIVE = 0xFF8696A0;
            FIELD = 0xFFF2F5F7;
            RIPPLE = 0x12000000;
            ERROR = 0xFFB3261E;
            EDGE = 0x33000000;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // No themed ActionBar: a stock one forces its own colours on the top of
        // the screen and cannot be made to match the rows underneath it.
        dark = resolveDark();
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        resolvePalette();
        sp = Prefs.get(this);
        // Connect to the framework so edits reach the hooked process.
        PrefsBridge.start(this);
        LocaleInfo.load(this);
        importConfigIfPresent();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);
        root.addView(buildTopBar());

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        tabTranslate = addTab(content, "翻译");
        tabLanguage = addTab(content, "语言");
        tabAppearance = addTab(content, "外观");
        tabAbout = addTab(content, "关于");

        // Every section is built here, in onCreate, which means one exception
        // anywhere used to close the whole screen - the app simply refused to
        // open, with no way in to find out why. Each section now fails on its
        // own and says so, while the rest of the screen stays usable.
        final LinearLayout t1 = tabTranslate;
        final LinearLayout t2 = tabLanguage;
        final LinearLayout t3 = tabAppearance;
        final LinearLayout t4 = tabAbout;

        // The engine leads, because it is the one setting nobody can avoid;
        // language and looks are refinements of it.
        guarded("介绍", t1, new Runnable() {
            public void run() {
                buildHeader(t1);
            }
        });
        guarded("翻译开关", t1, new Runnable() {
            public void run() {
                buildSwitches(t1);
            }
        });
        guarded("翻译引擎", t1, new Runnable() {
            public void run() {
                buildEngine(t1);
            }
        });

        guarded("译文语言", t2, new Runnable() {
            public void run() {
                buildLanguages(t2);
            }
        });
        guarded("输入框翻译", t2, new Runnable() {
            public void run() {
                buildInputTranslate(t2);
            }
        });
        guarded("对方信息", t2, new Runnable() {
            public void run() {
                buildLocale(t2);
            }
        });

        guarded("译文外观", t3, new Runnable() {
            public void run() {
                buildStyle(t3);
            }
        });

        guarded("调试", t4, new Runnable() {
            public void run() {
                buildDebug(t4);
            }
        });
        guarded("版本", t4, new Runnable() {
            public void run() {
                buildVersion(t4);
            }
        });

        root.addView(buildTabBar());
        setContentView(root);
        showTab(0);
    }

    /**
     * Build one section, and survive it if it throws.
     *
     * <p>The screen is assembled in one pass, so a throw used to reach
     * onCreate and take the entire activity down: the module could not be
     * opened at all. Contained here, a broken section shows a line of red text
     * where it would have been and everything else keeps working.</p>
     */
    private void guarded(String label, LinearLayout host, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            android.util.Log.e("LSTrans", "settings section '" + label + "' failed", t);
            TextView err = new TextView(this);
            err.setText("\u300c" + label + "\u300d\u52a0\u8f7d\u5931\u8d25\uff0c"
                    + "\u5176\u4ed6\u90e8\u5206\u4e0d\u53d7\u5f71\u54cd\u3002");
            err.setTextSize(13f);
            err.setTextColor(ERROR);
            err.setPadding(dp(16), dp(14), dp(16), dp(14));
            host.addView(err);
        }
    }

    /** A scrollable tab page. Rows carry their own insets, so it needs none. */
    private LinearLayout addTab(FrameLayout host, String name) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column(0, 0, 0, dp(20));
        scroll.addView(body);
        scroll.setVisibility(View.GONE);
        host.addView(scroll);
        tabNames.add(name);
        tabViews.add(scroll);
        return body;
    }

    /** The app bar, drawn rather than inherited so it can match the rows. */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(SURFACE);

        TextView title = new TextView(this);
        title.setText("WhatsApp 翻译助手");
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        title.setPadding(dp(20), dp(16), dp(20), dp(14));
        bar.addView(title);
        bar.addView(hairline());
        return bar;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(HAIRLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    /** A plain vertical column with the given padding. */
    private LinearLayout column(int left, int top, int right, int bottom) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(left, top, right, bottom);
        return c;
    }

    private void showTab(int index) {
        for (int i = 0; i < tabViews.size(); i++) {
            tabViews.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
            TextView label = tabLabels.get(i);
            boolean on = i == index;
            label.setTextColor(on ? ACCENT : INACTIVE);
            label.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            View icon = tabBars.get(i);
            for (Drawable d : new Drawable[]{icon.getBackground()}) {
                if (d != null) {
                    d.setTint(on ? ACCENT : INACTIVE);
                }
            }
            if (icon instanceof ImageView) {
                ((ImageView) icon).setColorFilter(on ? ACCENT : INACTIVE);
            }
        }
        currentTab = index;
    }

    /**
     * The bar along the bottom.
     *
     * <p>Hand-built from LinearLayout and TextView rather than pulled from
     * Material Components: this module is compiled straight against android.jar
     * and bundles no libraries, which is the whole reason the APK is 200KB. A
     * navigation library would multiply that.</p>
     */
    private View buildTabBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(SURFACE);
        wrap.addView(hairline());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(9), 0, dp(9));
        wrap.addView(bar);

        String[] icons = {"ic_tab_translate", "ic_tab_language",
                "ic_tab_appearance", "ic_tab_about"};

        for (int i = 0; i < tabNames.size(); i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(index);
                }
            });

            ImageView icon = new ImageView(this);
            icon.setImageResource(drawableId(icons[i]));
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(23), dp(23)));
            tabBars.add(icon);
            item.addView(icon);

            TextView label = new TextView(this);
            label.setText(tabNames.get(i));
            label.setTextSize(11.5f);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            tabLabels.add(label);
            item.addView(label);

            bar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return wrap;
    }

    private void buildHeader(LinearLayout root) {
        // A group with no label, so it lines up with every section below it.
        LinearLayout card = section(root, null);

        TextView sub = new TextView(this);
        sub.setText("把对方发来的消息翻译成你设定的语言，译文显示在原文下方。");
        sub.setTextSize(14f);
        sub.setTextColor(MUTED);
        sub.setPadding(dp(16), dp(16), dp(16), dp(4));
        sub.setLineSpacing(dp(4), 1.0f);
        card.addView(sub);

        TextView open = new TextView(this);
        open.setText("打开 WhatsApp");
        open.setTextSize(15.5f);
        open.setGravity(Gravity.CENTER);
        open.setPadding(dp(12), dp(15), dp(12), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(ACCENT);
        open.setBackground(bg);
        open.setTextColor(0xFFFFFFFF);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWhatsApp();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(12), dp(16), dp(16));
        open.setLayoutParams(lp);
        card.addView(open);
        open.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), open.getBackground(),
                null));
        open.setClickable(true);


    }

    private void openWhatsApp() {
        for (String pkg : new String[]{"com.whatsapp.w4b", "com.whatsapp"}) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        toast("没有找到 WhatsApp，请确认已安装");
    }

    // =========================================================== switches

    private void buildSwitches(LinearLayout root) {
        root = section(root, "翻译开关");
        check(root, "翻译收到的消息", Prefs.KEY_INCOMING_ENABLED, true);
        hint(root, "打开后，收到的消息会在原文下方多出一行小字译文；关掉就只留原文。");
    }

    // ============================================================= engine

    private void buildEngine(LinearLayout root) {
        root = section(root, "翻译引擎");

        // A Spinner used to live here. It is a stock widget with its own idea of
        // height, which opened a hole between itself and the rows below, and it
        // looked nothing like the pickers everywhere else on this screen.
        final LinearLayout engineRow = valueRow(root, null);
        final TextView engineValue = valueOf(engineRow);
        engineValue.setTextColor(MUTED);
        engineValue.setText(Engine.of(Prefs.engine()).label());
        engineValue.setMaxLines(1);
        engineRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("翻译引擎")
                        .setItems(Engine.labels(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Map<String, Object> m = new HashMap<String, Object>();
                                m.put(Prefs.KEY_ENGINE, Engine.ids()[which]);
                                save(m);
                                engineValue.setText(Engine.labels()[which]);
                                rebuildEngineKeys(Engine.values()[which]);
                            }
                        })
                        .show();
            }
        });

        engineKeyBox = new LinearLayout(this);
        engineKeyBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(engineKeyBox);
        rebuildEngineKeys(Engine.of(Prefs.engine()));

        note(root, "前 4 个免密钥，装上就能用；"
                + "后 4 个要填自己的账号。");
    }

    private static final String[] AI_FORMAT_LABELS = {
            "OpenAI Chat Completions（最通用，默认）",
            "OpenAI Responses",
            "Anthropic Messages",
    };
    private static final String[] AI_FORMAT_VALUES = {
            Translators.AI_CHAT,
            Translators.AI_RESPONSES,
            Translators.AI_ANTHROPIC,
    };

    private void aiFormatRow(LinearLayout parent) {
        final LinearLayout formatRow = valueRow(parent,
                "API 格式");
        final TextView value = valueOf(formatRow);
        value.setTextColor(MUTED);
        value.setText(aiFormatLabel(sp.getString(Prefs.KEY_AI_FORMAT, Translators.AI_CHAT)));
        formatRow.setBackground(pressed());
        formatRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("选择 API 格式")
                        .setItems(AI_FORMAT_LABELS, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Map<String, Object> m = new HashMap<String, Object>();
                                m.put(Prefs.KEY_AI_FORMAT, AI_FORMAT_VALUES[which]);
                                save(m);
                                value.setText(AI_FORMAT_LABELS[which]);
                            }
                        })
                        .show();
            }
        });

        note(parent, "选错了会返回 404。绝大多数服务和中转站用第一个，"
                + "Claude 那一套用第三个（DeepSeek 的 Claude 地址填 "
                + "https://api.deepseek.com/anthropic）。");
    }

    private String aiFormatLabel(String format) {
        for (int i = 0; i < AI_FORMAT_VALUES.length; i++) {
            if (AI_FORMAT_VALUES[i].equals(format)) {
                return AI_FORMAT_LABELS[i];
            }
        }
        return AI_FORMAT_LABELS[0];
    }

    /**
     * Pulls the catalogue the endpoint actually serves and lets the user pick.
     *
     * <p>This exists because a model the key is not entitled to answers
     * {@code 403 Access to model denied}, which reads like a permissions problem
     * and sends people hunting for a typo that is not there. The catalogue lists
     * the names this key may really use.</p>
     */
    private void aiModelFetchRow(LinearLayout parent, final Engine engine) {
        final TextView btn = new TextView(this);
        btn.setText("拉取模型列表");
        btn.setTextSize(15f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x1400A884);
        btn.setBackground(bg);
        btn.setTextColor(ACCENT);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btn.setEnabled(false);
                btn.setText("拉取中…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Translators.ModelList r = Translators.fetchModels();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btn.setEnabled(true);
                                btn.setText("拉取模型列表");
                                if (r.ids.length == 0) {
                                    new AlertDialog.Builder(SettingsActivity.this)
                                            .setTitle("拉取失败")
                                            .setMessage(r.detail)
                                            .setPositiveButton("知道了", null)
                                            .show();
                                    return;
                                }
                                String current = sp.getString(Prefs.KEY_AI_MODEL, "");
                                new AlertDialog.Builder(SettingsActivity.this)
                                        .setTitle(r.status + "（点一个设为模型名）")
                                        .setItems(r.ids, new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int which) {
                                                Map<String, Object> m =
                                                        new HashMap<String, Object>();
                                                m.put(Prefs.KEY_AI_MODEL, r.ids[which]);
                                                save(m);
                                                rebuildEngineKeys(engine);
                                            }
                                        })
                                        .setNeutralButton("详情", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int i) {
                                                new AlertDialog.Builder(SettingsActivity.this)
                                                        .setTitle("接口列表")
                                                        .setMessage(r.detail)
                                                        .setPositiveButton("知道了", null)
                                                        .show();
                                            }
                                        })
                                        .show();
                                if (!current.isEmpty() && !contains(r.ids, current)) {
                                    new AlertDialog.Builder(SettingsActivity.this)
                                            .setTitle("注意：当前模型名不在列表里")
                                            .setMessage("你填的是「" + current + "」，但这条 Key 能用的模型里"
                                                    + "没有这个名字。\n\n"
                                                    + "这种时候服务器常常回 403（Access to model denied），"
                                                    + "看起来像没权限，其实是名字对不上。"
                                                    + "从列表里挑一个吧。")
                                            .setPositiveButton("知道了", null)
                                            .show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        parent.addView(btn);

        note(parent, "点一个就自动填好。403 是这条 Key 没有该模型的权限，"
                + "要去中转站后台加上才行。");
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private void aiTestRow(LinearLayout parent) {
        final TextView btn = new TextView(this);
        btn.setText("测试连接");
        btn.setTextSize(15f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(ACCENT);
        btn.setBackground(bg);
        btn.setTextColor(0xFFFFFFFF);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btn.setEnabled(false);
                btn.setText("测试中…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String[] r = Translators.probeCustomAi();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btn.setEnabled(true);
                                btn.setText("测试连接");
                                new AlertDialog.Builder(SettingsActivity.this)
                                        .setTitle(r[0])
                                        .setMessage(r[1])
                                        .setPositiveButton("知道了", null)
                                        .show();
                            }
                        });
                    }
                }).start();
            }
        });
        parent.addView(btn);

        note(parent, "真的发一条 Hello 过去，把状态码和试过的地址列出来：404 地址或格式不对，401 Key 不对，400 模型名写错。");;
    }

    private void rebuildEngineKeys(Engine engine) {
        engineKeyBox.removeAllViews();
        switch (engine) {
            case CUSTOM_AI:
                aiFormatRow(engineKeyBox);
                edit(engineKeyBox, "接口地址",
                        Prefs.KEY_AI_ENDPOINT, "https://api.deepseek.com");
                edit(engineKeyBox, "API Key", Prefs.KEY_AI_KEY, "sk-...");
                edit(engineKeyBox, "模型名", Prefs.KEY_AI_MODEL, "deepseek-flash / gpt-4o-mini");
                aiModelFetchRow(engineKeyBox, engine);
                aiTestRow(engineKeyBox);
                edit(engineKeyBox, "自定义提示词（发给 AI 的系统提示；${toCode} 会替换成上面的目标语言）",
                        Prefs.KEY_AI_PROMPT, Translators.DEFAULT_AI_PROMPT, true);
                break;
            case BAIDU:
                edit(engineKeyBox, "百度翻译 APP ID", Prefs.KEY_BAIDU_APPID, "从百度翻译开放平台获取");
                edit(engineKeyBox, "百度翻译密钥", Prefs.KEY_BAIDU_KEY, "密钥");
                break;
            case DEEPL:
                edit(engineKeyBox, "DeepL Auth Key（免费版 key 以 :fx 结尾）",
                        Prefs.KEY_DEEPL_KEY, "xxxxxxxx-xxxx-...:fx");
                break;
            case MICROSOFT:
                edit(engineKeyBox, "微软翻译 Key", Prefs.KEY_MS_KEY, "Azure 订阅密钥");
                edit(engineKeyBox, "微软翻译区域（如 eastasia，可留空）",
                        Prefs.KEY_MS_REGION, "eastasia");
                break;
            default:
                TextView tv = new TextView(this);
                tv.setText("这个引擎不需要配置，直接就能用。");
                tv.setTextSize(13f);
                tv.setTextColor(ACCENT);
                tv.setPadding(dp(16), dp(10), dp(16), dp(12));
                engineKeyBox.addView(tv);
                break;
        }
    }

    // ========================================================== languages

    private void buildLanguages(LinearLayout root) {
        root = section(root, "译文语言");

        pickerRow(root, "把消息翻译成", LocaleInfo.labelOf(Prefs.targetLang()) + "（"
                + Prefs.targetLang() + "）", Prefs.KEY_TARGET_LANG, false);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setTextColor(MUTED);
        hint.setPadding(dp(16), dp(4), dp(16), dp(12));
        hint.setText("点上面一行打开语言列表，可以直接搜索。共 "
                + LocaleInfo.allLanguages().size() + " 种语言，中文和英文名都能搜。");
        root.addView(hint);
    }

    // ==================================================== input translate

    // ====================================================== input translate

    /**
     * Incoming and outgoing translation can use different services.
     *
     * <p>They fail differently and cost differently: a public engine is fine for
     * reading a chat but produces worse prose to send, and a paid model is the
     * other way round. Keeping them on one setting forced a choice between the
     * two.</p>
     */
    private void outEngineRow(LinearLayout parent) {
        final LinearLayout r = valueRow(parent, "用哪个引擎");
        final TextView value = valueOf(r);
        value.setText(outEngineLabel());
        value.setTextColor(MUTED);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] ids = Engine.ids();
                final String[] labels = new String[ids.length + 1];
                labels[0] = "跟随翻译引擎（默认）";
                System.arraycopy(Engine.labels(), 0, labels, 1, ids.length);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("输入框翻译用哪个引擎")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Map<String, Object> m = new HashMap<String, Object>();
                                m.put(Prefs.KEY_OUT_ENGINE, which == 0 ? "" : ids[which - 1]);
                                save(m);
                                value.setText(outEngineLabel());
                            }
                        })
                        .show();
            }
        });
    }

    private String outEngineLabel() {
        String v = sp.getString(Prefs.KEY_OUT_ENGINE, "");
        if (v == null || v.isEmpty()) {
            return "跟随翻译引擎";
        }
        return Engine.of(v).label();
    }

    private void buildInputTranslate(LinearLayout root) {
        root = section(root, "输入框翻译");

        check(root, "在输入框上方显示翻译按钮",
                Prefs.KEY_INPUT_TRANSLATE_ENABLED, true);

        check(root, "跟随对方语言",
                Prefs.KEY_FOLLOW_PEER, true);

        pickerRow(root, "认不出来时翻译成",
                LocaleInfo.labelOf(Prefs.outLang()) + "（" + Prefs.outLang() + "）",
                Prefs.KEY_OUT_LANG, false);

        outEngineRow(root);

        note(root, "打好字点输入框上方的「翻译」，"
                + "再点发送键才发出去；左上的药丸选语言。\n"
                + "两个方向正好相反：译文语言管收到的消息，"
                + "输入框翻译管我打的话。");
    }

    private void pickerRow(LinearLayout parent, String title, final String currentLabel,
                           final String key, final boolean areaCode) {
        final LinearLayout r = valueRow(parent, title);
        final TextView value = valueOf(r);
        value.setText(currentLabel);
        value.setTextColor(MUTED);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PickerDialog.OnPick onPick = new PickerDialog.OnPick() {
                    @Override
                    public void picked(String code, String label) {
                        Map<String, Object> m = new HashMap<String, Object>();
                        m.put(key, code);
                        save(m);
                        value.setText(label + "（" + code + "）");
                    }
                };
                if (areaCode) {
                    PickerDialog.showAreaCode(SettingsActivity.this,
                            sp.getString(key, ""), onPick);
                } else {
                    PickerDialog.showLanguage(SettingsActivity.this,
                            sp.getString(key, ""), onPick);
                }
            }
        });
    }

    private String themeLabel() {
        String mode = sp.getString(Prefs.KEY_THEME, "system");
        if ("dark".equals(mode)) {
            return "深色";
        }
        if ("light".equals(mode)) {
            return "浅色";
        }
        return "跟随系统";
    }

    private void themeRow(LinearLayout root) {
        final LinearLayout r = valueRow(root, "主题");
        final TextView value = valueOf(r);
        value.setTextColor(MUTED);
        value.setText(themeLabel());
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] mode = {"system", "light", "dark"};
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("主题")
                        .setItems(new String[]{"跟随系统", "浅色", "深色"},
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        Map<String, Object> m = new HashMap<String, Object>();
                                        m.put(Prefs.KEY_THEME, mode[which]);
                                        save(m);
                                        // The theme is applied when the window is
                                        // created, so it takes a rebuild to show.
                                        recreate();
                                    }
                                })
                        .show();
            }
        });
    }

    private void buildStyle(LinearLayout root) {
        root = section(root, "译文外观");

        themeRow(root);

        final int[] currentColor = {Prefs.translationColor()};

        // Flooding a full-width bar with the colour made the loudest thing on
        // the screen out of a setting most people never touch. It is a button
        // now, the same one the debug tab uses, with the colour as a dot beside
        // the words - still a preview, no longer a shout.
        LinearLayout colorBtn = new LinearLayout(this);
        colorBtn.setOrientation(LinearLayout.HORIZONTAL);
        colorBtn.setGravity(Gravity.CENTER);
        colorBtn.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable cBg = new GradientDrawable();
        cBg.setCornerRadius(dp(12));
        cBg.setColor(0x1400A884);
        colorBtn.setBackground(cBg);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("\u8bd1\u6587\u989c\u8272");
        colorLabel.setTextSize(15f);
        colorLabel.setTextColor(ACCENT);
        colorBtn.addView(colorLabel);

        final View swatchDot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(20), dp(20));
        dlp.leftMargin = dp(10);
        colorBtn.addView(swatchDot, dlp);
        paintDot(swatchDot, currentColor[0]);

        colorBtn.setClickable(true);
        colorBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("\u9009\u62e9\u8bd1\u6587\u989c\u8272")
                        .setItems(COLOR_NAMES, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                currentColor[0] = COLOR_PRESETS[which];
                                Map<String, Object> m = new HashMap<String, Object>();
                                m.put(Prefs.KEY_TRANSLATION_COLOR, currentColor[0]);
                                save(m);
                                paintDot(swatchDot, currentColor[0]);
                            }
                        })
                        .show();
            }
        });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbp.setMargins(dp(16), dp(4), dp(16), dp(4));
        root.addView(colorBtn, cbp);

        hint(root, "译文是原文下面的小字，点上面的色块换颜色。");

        note(root, "改动会自动重画屏幕上已有的消息，一般不用做什么。\n"
                + "如果个别消息没跟着变，退出聊天再进来一下即可。");

        final TextView scaleLabel = new TextView(this);
        scaleLabel.setTextSize(15f);
        scaleLabel.setText("译文大小：" + Math.round(Prefs.translationScale() * 100) + "%");
        root.addView(scaleLabel);
        SeekBar scale = new SeekBar(this);
        scale.setMax(40);
        scale.setProgress(Math.round(Prefs.translationScale() * 100) - 60);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int pct = progress + 60;
                scaleLabel.setText("译文大小：" + pct + "%");
                Map<String, Object> m = new HashMap<String, Object>();
                m.put(Prefs.KEY_TRANSLATION_SCALE, String.valueOf(pct / 100f));
                save(m);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        root.addView(scale);
        check(root, "译文使用斜体", Prefs.KEY_TRANSLATION_ITALIC, false);
    }

    // ============================================================= locale

    private void buildLocale(LinearLayout root) {
        root = section(root, "对方信息");

        note(root, "打开会话时按对方号码离线查表，"
                + "得出对方的语言，全在本地完成。\n"
                + "群聊和隐藏号码判断不出来，"
                + "用输入框翻译里选的语言。");
    }

    private void buildVersion(LinearLayout root) {
        root = section(root, "版本");

        String version = "?";
        long code = 0;
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            code = info.getLongVersionCode();
        } catch (Throwable ignored) {
        }
        hint(root, "WhatsApp 翻译助手 " + version + "（" + code + "）\n"
                + "包名 " + getPackageName());
    }

    // ============================================================== debug

    private void buildDebug(LinearLayout root) {
        root = section(root, "调试");
        check(root, "输出详细日志", Prefs.KEY_DEBUG_LOG, true);

        final TextView btn = new TextView(this);
        btn.setText("测试当前引擎");
        btn.setTextSize(15f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x1400A884);
        btn.setBackground(bg);
        btn.setTextColor(ACCENT);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testEngine();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(dp(16), dp(4), dp(16), dp(14));
        root.addView(btn, bp);

        note(root, "译文没出现时，去 Xposed 日志"
                + "搜 LSTrans，里面有请求地址和"
                + "接口返回的内容。");
    }

    private void testEngine() {
        toast("测试中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = Translators.translate("Hello, how are you today?", "auto",
                        Prefs.targetLang());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(r == null ? "失败：看 Xposed 日志里的 LSTrans 记录" : "成功：" + r);
                    }
                });
            }
        }).start();
    }

    // ============================================================ helpers

    private void importConfigIfPresent() {
        try {
            java.io.File f = null;
            for (java.io.File c : new java.io.File[]{
                    new java.io.File(getFilesDir(), "lstrans.json"),
                    new java.io.File("/sdcard/Download/lstrans.json")}) {
                if (c.isFile() && c.canRead()) {
                    f = c;
                    break;
                }
            }
            if (f == null) {
                return;
            }
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, "UTF-8"));
            Map<String, Object> m = new HashMap<String, Object>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.get(k);
                if (v instanceof Integer || v instanceof Boolean) {
                    m.put(k, v);
                } else {
                    m.put(k, String.valueOf(v));
                }
            }
            save(m);
            f.renameTo(new java.io.File(f.getAbsolutePath() + ".applied"));
            toast("已导入 " + m.size() + " 项配置");
        } catch (Throwable t) {
            toast("配置导入失败：" + t);
        }
    }

    private void save(Map<String, Object> values) {
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer) {
                ed.putInt(e.getKey(), (Integer) v);
            } else if (v instanceof Boolean) {
                ed.putBoolean(e.getKey(), (Boolean) v);
            } else {
                ed.putString(e.getKey(), String.valueOf(v));
            }
        }
        ed.commit();
        // Hand the edit to the framework; the hooked process reads it from there.
        PrefsBridge.push(values);
    }

    private int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.WHITE);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);
        parent.addView(c);
        return c;
    }

    /** The line between two rows in a group, inset like Android's own lists. */
    private void addRow(LinearLayout group, View row) {
        if (group.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(HAIRLINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            lp.leftMargin = dp(16);
            group.addView(d, lp);
        }
        group.addView(row);
    }

    private Drawable pressed() {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(RIPPLE), null, null);
    }

    /** A row: label on the left, whatever the caller adds on the right. */
    private LinearLayout row(LinearLayout group, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(54));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (label != null) {
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextSize(15.5f);
            t.setTextColor(TEXT);
            t.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(t);
        }
        addRow(group, r);
        return r;
    }

    /**
     * Look a drawable up by name.
     *
     * <p>The generated R class is not usable here: aapt2 link runs after javac
     * in this build, so R does not exist at the moment the sources are compiled.
     * The lookup happens once per view, which costs nothing.</p>
     */
    private int drawableId(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private View chevron() {
        ImageView v = new ImageView(this);
        v.setImageResource(drawableId("ic_chevron"));
        v.setColorFilter(INACTIVE);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        return v;
    }

    /**
     * Opens a section and hands back the block its rows belong in.
     *
     * <p>Was a blue heading plus a hairline, and that grouped nothing: the rows
     * underneath carried the same weight as everything else on the page, so the
     * screen read as one uninterrupted strip however many rules were drawn
     * across it. A card per group draws the edges a heading can only imply.</p>
     */
    private LinearLayout section(LinearLayout parent, String title) {
        if (title != null) {
            TextView label = new TextView(this);
            label.setText(title);
            label.setTextSize(13.5f);
            label.setTextColor(MUTED);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dp(32), dp(20), dp(32), dp(8));
            parent.addView(label);
        }
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(SURFACE);
        group.setBackground(bg);
        group.setClipToOutline(true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(16);
        glp.rightMargin = dp(16);
        if (title == null) {
            // Every other group gets its top gap from the label above it. With no
            // label, nothing supplies one and the card ends up flush against the
            // app bar.
            glp.topMargin = dp(18);
        }
        parent.addView(group, glp);
        return group;
    }

    /** One line of clarification, tucked directly under the control it belongs to. */
    private void hint(LinearLayout parent, String text) {
        noteRow(parent, text, false);
    }

    /**
     * Several points of explanation, set apart as its own block.
     *
     * <p>Deliberately not used everywhere: a screen where every note is a boxed
     * block reads as noise, and one where every note is loose grey text reads as
     * a wall. Short stays plain, long gets a block, and the two never look the
     * same.</p>
     */
    private void note(LinearLayout parent, String text) {
        noteRow(parent, text, true);
    }

    /**
     * Explanation inside a group.
     *
     * <p>It used to sit in its own grey box, which meant a box inside a card
     * inside a page - three levels of container for one paragraph. Plain text
     * between two hairlines carries the same weight without the nesting.</p>
     */
    private void noteRow(LinearLayout parent, String text, boolean roomy) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(MUTED);
        tv.setLineSpacing(dp(4), 1.0f);
        tv.setPadding(dp(16), roomy ? dp(13) : dp(8), dp(16), dp(13));
        addRow(parent, tv);
    }

    private void check(LinearLayout parent, String label, final String key, boolean def) {
        LinearLayout r = row(parent, label);
        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(0x5500A884));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put(key, isChecked);
                save(m);
            }
        });
        r.addView(sw);
    }

    private void edit(LinearLayout parent, String label, final String key, String hintText) {
        edit(parent, label, key, hintText, false);
    }

    private void edit(LinearLayout parent, String label, final String key, String hintText,
                      boolean multiLine) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13f);
        tv.setPadding(dp(16), dp(12), dp(16), dp(4));
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setHint(hintText);
        et.setTextSize(14f);
        et.setSingleLine(!multiLine);
        if (multiLine) {
            et.setMinLines(8);
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setTypeface(Typeface.MONOSPACE);
            et.setTextSize(2, 11.5f);
        }
        et.setText(sp.getString(key, hintText));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put(key, s == null ? "" : s.toString().trim());
                save(m);
            }
        });
        // A filled field instead of the platform's underlined one, which looked
        // like a form from a decade ago next to the rest of the screen.
        GradientDrawable field = new GradientDrawable();
        field.setCornerRadius(dp(10));
        field.setColor(FIELD);
        et.setBackground(field);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.leftMargin = dp(16);
        ep.rightMargin = dp(16);
        ep.bottomMargin = dp(4);
        parent.addView(et, ep);
    }

    /**
     * Turns a stacked label+control into one row, so pickers line up.
     *
     * <p>The label keeps its natural width and the value takes what is left,
     * right aligned and ellipsised. The opposite - a flexible label beside a
     * natural-width value - let a long value squeeze the label to nothing, and
     * the row then read as a stray line of text with no clue what it was: the
     * "API format" row lost its own name to the string "OpenAI Chat
     * Completions (most common)".</p>
     */
    private LinearLayout valueRow(LinearLayout parent, String title) {
        LinearLayout r = row(parent, title);
        // row() gives the label weight so a switch lands hard right; a picker
        // wants it back at its natural width instead.
        if (title != null && r.getChildCount() > 0) {
            View label = r.getChildAt(0);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView value = new TextView(this);
        value.setTextSize(14f);
        value.setGravity(Gravity.END);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setPadding(0, 0, dp(6), 0);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vp.leftMargin = dp(12);
        r.addView(value, vp);
        r.addView(chevron());
        r.setBackground(pressed());
        value.setTag("value");
        return r;
    }

    private TextView valueOf(LinearLayout r) {
        return (TextView) r.findViewWithTag("value");
    }

    /** A round colour chip, with a hairline edge so pale colours stay visible. */
    private void paintDot(View v, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(dp(1), EDGE);
        v.setBackground(bg);
    }

    private void paintColor(TextView tv, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), EDGE);
        bg.setColor(color);
        tv.setBackground(bg);
        boolean dark = (Color.red(color) + Color.green(color) + Color.blue(color)) < 380;
        tv.setTextColor(dark ? Color.WHITE : Color.BLACK);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
