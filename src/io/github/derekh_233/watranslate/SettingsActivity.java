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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
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

    private SharedPreferences sp;
    private LinearLayout engineKeyBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = Prefs.get(this);
        // Connect to the framework so edits reach the hooked process.
        PrefsBridge.start(this);
        LocaleInfo.load(this);
        importConfigIfPresent();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        buildHeader(root);
        buildSwitches(root);
        buildEngine(root);
        buildLanguages(root);
        buildStyle(root);
        buildLocale(root);
        buildDebug(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ============================================================= header

    private void buildHeader(LinearLayout root) {
        LinearLayout card = card(root);

        // No title in the card: the action bar already names the app, and
        // printing the same words twice in the first screenful is pure noise.
        TextView sub = new TextView(this);
        sub.setText("把对方发来的消息翻译成你设定的语言，译文以小字显示在原文下方。\n"
                + "翻译接口可以选公开的，也可以填自己的 AI 接口。");
        sub.setTextSize(13.5f);
        sub.setTextColor(0xFF666666);
        sub.setLineSpacing(dp(4), 1.0f);
        card.addView(sub);

        TextView open = new TextView(this);
        open.setText("打开 WhatsApp");
        open.setTextSize(15f);
        open.setGravity(Gravity.CENTER);
        open.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFF3F51B5);
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
        lp.topMargin = dp(14);
        open.setLayoutParams(lp);
        card.addView(open);

        hint(card, "这里是模块的设置入口，从桌面图标或 LSPosed 模块列表进入都可以。");
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
        section(root, "翻译开关");
        check(root, "翻译收到的消息", Prefs.KEY_INCOMING_ENABLED, true);
        hint(root, "打开后，收到的消息会在原文下方多出一行小字译文；关掉就只留原文。");
    }

    // ============================================================= engine

    private void buildEngine(LinearLayout root) {
        section(root, "翻译引擎");

        final Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Engine.labels());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(indexOf(Engine.ids(), Prefs.engine()));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put(Prefs.KEY_ENGINE, Engine.ids()[pos]);
                save(m);
                rebuildEngineKeys(Engine.values()[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(spinner);

        engineKeyBox = new LinearLayout(this);
        engineKeyBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(engineKeyBox);
        rebuildEngineKeys(Engine.of(Prefs.engine()));

        note(root, "· 前 4 个是公开接口 —— 不用注册、不用密钥，装上就能用，先用这个。\n"
                + "· 后 4 个要用你自己的账号 —— 百度、DeepL、微软，以及任意 OpenAI 兼容的 AI 接口（DeepSeek 等）。");
    }

    // ========================================================= custom AI UI

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
        TextView label = new TextView(this);
        label.setText("API 格式（不确定就保持第一个）");
        label.setTextSize(13f);
        label.setPadding(0, dp(10), 0, dp(2));
        parent.addView(label);

        final TextView value = new TextView(this);
        value.setTextSize(15f);
        value.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x33000000);
        bg.setColor(0xFFF2F2F2);
        value.setBackground(bg);
        value.setTextColor(0xFF222222);
        value.setText(aiFormatLabel(sp.getString(Prefs.KEY_AI_FORMAT, Translators.AI_CHAT)));
        value.setOnClickListener(new View.OnClickListener() {
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
        parent.addView(value);

        note(parent, "服务端不一样，选错了会返回 404。\n"
                + "\n"
                + "· Chat Completions —— POST .../v1/chat/completions，Authorization 鉴权。\n"
                + "    绝大多数服务和第三方中转站都是这个。\n"
                + "· Responses —— POST .../v1/responses，OpenAI 的新接口。\n"
                + "· Anthropic Messages —— POST .../v1/messages，system 是单独的字段，x-api-key 鉴权。\n"
                + "    DeepSeek 的地址填 https://api.deepseek.com/anthropic");
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
        btn.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x33000000);
        bg.setColor(0xFFE8E8E8);
        btn.setBackground(bg);
        btn.setTextColor(0xFF222222);
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

        note(parent, "向接口要一份「这条 Key 能用的模型」清单，点一个就自动填好。\n"
                + "\n"
                + "两种错误容易混淆：\n"
                + "· 400 / 404 model_not_found —— 模型名打错了。\n"
                + "· 403 Access to model denied —— 这条 Key 没有该模型的权限，"
                + "要去中转站后台把它加进令牌允许列表。");
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
        bg.setColor(0xFF2E7D32);
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

        note(parent, "真的发一条 \"Hello\" 过去，把状态码和试过的地址都列出来。\n"
                + "\n"
                + "· 404 —— 地址或 API 格式不对\n"
                + "· 401 —— Key 不对\n"
                + "· 400 —— 模型名写错");
    }

    private void rebuildEngineKeys(Engine engine) {
        engineKeyBox.removeAllViews();
        switch (engine) {
            case CUSTOM_AI:
                aiFormatRow(engineKeyBox);
                edit(engineKeyBox, "接口地址（填到 /v1 为止或填完整路径都行）",
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
                tv.setTextColor(0xFF2E7D32);
                tv.setPadding(0, dp(8), 0, 0);
                engineKeyBox.addView(tv);
                break;
        }
    }

    // ========================================================== languages

    private void buildLanguages(LinearLayout root) {
        section(root, "译文语言");

        pickerRow(root, "把消息翻译成", LocaleInfo.labelOf(Prefs.targetLang()) + "（"
                + Prefs.targetLang() + "）", Prefs.KEY_TARGET_LANG, false);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setTextColor(0xFF888888);
        hint.setPadding(0, dp(4), 0, dp(6));
        hint.setText("点上面一行打开语言列表，可以直接搜索。共 "
                + LocaleInfo.allLanguages().size() + " 种语言，中文和英文名都能搜。");
        root.addView(hint);

        buildInputTranslate(root);
    }

    // ==================================================== input translate

    private void buildInputTranslate(LinearLayout root) {
        section(root, "输入框翻译");

        check(root, "长按发送键翻译我打的字", Prefs.KEY_INPUT_TRANSLATE_ENABLED, true);

        check(root, "目标语言跟随对方（按对方号码自动判断）", Prefs.KEY_FOLLOW_PEER, true);

        pickerRow(root, "对方认不出来时翻译成", LocaleInfo.labelOf(Prefs.outLang()) + "（"
                + Prefs.outLang() + "）", Prefs.KEY_OUT_LANG, false);

        note(root, "在聊天框里打好字，长按发送键，草稿变成译文；点发送键才发出去。再长按一次还原成原文。\n"
                + "\n"
                + "默认跟着对方走：按对方号码的国家代码查表，对方是哪个国家的就用哪种语言，"
                + "遇到多个国家也不用切来切去。\n"
                + "群聊、以及对方号码被隐藏的情况判断不出来，就用下面那个语言。\n"
                + "\n"
                + "注意方向正好相反：\n"
                + "· 译文语言 —— 别人的消息翻译成什么语言（收）\n"
                + "· 输入框翻译 —— 我打的话翻译成什么语言（发）");
    }

    private void pickerRow(LinearLayout parent, String title, final String currentLabel,
                           final String key, final boolean areaCode) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13f);
        tv.setPadding(0, dp(10), 0, dp(2));
        parent.addView(tv);

        final TextView value = new TextView(this);
        value.setText(currentLabel);
        value.setTextSize(15f);
        value.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x33000000);
        bg.setColor(0xFFF2F2F2);
        value.setBackground(bg);
        value.setTextColor(0xFF222222);
        value.setOnClickListener(new View.OnClickListener() {
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
        parent.addView(value);
    }

    // ============================================================== style

    private void buildStyle(LinearLayout root) {
        section(root, "译文外观");

        final TextView colorPreview = new TextView(this);
        final int[] currentColor = {Prefs.translationColor()};
        colorPreview.setText("译文颜色");
        colorPreview.setTextSize(15f);
        colorPreview.setGravity(Gravity.CENTER);
        colorPreview.setPadding(dp(10), dp(14), dp(10), dp(14));
        paintColor(colorPreview, currentColor[0]);
        colorPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("选择译文颜色")
                        .setItems(COLOR_NAMES, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                currentColor[0] = COLOR_PRESETS[which];
                                Map<String, Object> m = new HashMap<String, Object>();
                                m.put(Prefs.KEY_TRANSLATION_COLOR, currentColor[0]);
                                save(m);
                                paintColor(colorPreview, currentColor[0]);
                            }
                        })
                        .show();
            }
        });
        root.addView(colorPreview);
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
        section(root, "对方信息");

        TextView cur = new TextView(this);
        cur.setTextSize(14f);
        cur.setPadding(0, dp(4), 0, dp(8));
        StringBuilder sb = new StringBuilder("当前设置：我的区号 +" + Prefs.myRegion());
        LocaleInfo.Info mine = LocaleInfo.forPhone(Prefs.myRegion());
        if (mine != null) {
            sb.append(" · ").append(LocaleInfo.describe(mine));
        }
        cur.setText(sb.toString());
        root.addView(cur);

        pickerRow(root, "我的国家/地区（用于判断对方是否与我在同一地区）",
                "+" + Prefs.myRegion(), Prefs.KEY_MY_REGION, true);

        note(root, "按对方号码的国家代码离线查表，查表过程不发网络请求。\n"
                + "覆盖 " + LocaleInfo.allAreaCodes().size() + " 个区号，含语言、货币和当地时区。");
    }

    // ============================================================== debug

    private void buildDebug(LinearLayout root) {
        section(root, "调试");
        check(root, "输出详细日志（Xposed 日志搜 LSTrans）", Prefs.KEY_DEBUG_LOG, true);

        Button test = new Button(this);
        test.setText("测试当前引擎");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testEngine();
            }
        });
        root.addView(test);

        TextView note = new TextView(this);
        note.setTextSize(12f);
        note.setTextColor(0xFF777777);
        note.setPadding(0, dp(14), 0, 0);
        note.setText("译文没出现时，去 Xposed 日志里搜 LSTrans：请求用的端点、HTTP 状态码、"
                + "接口返回的内容都会记下来，方便看出是接口挂了还是配置写错了。");
        root.addView(note);
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
        bg.setStroke(dp(1), 0x22000000);
        bg.setColor(Color.WHITE);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        parent.addView(c);
        return c;
    }

    private void section(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF3F51B5);
        tv.setPadding(0, dp(24), 0, dp(8));
        parent.addView(tv);

        // A hairline under each heading: headings alone do not group anything
        // when the rows below them are the same weight as everything else.
        View rule = new View(this);
        rule.setBackgroundColor(0x14000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        rule.setLayoutParams(lp);
        parent.addView(rule);
    }

    /** One line of clarification, tucked directly under the control it belongs to. */
    private void hint(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF8A8A8A);
        tv.setLineSpacing(dp(3), 1.0f);
        tv.setPadding(0, dp(6), 0, dp(2));
        parent.addView(tv);
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
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFF5A5A5A);
        tv.setLineSpacing(dp(4), 1.0f);
        int pad = dp(12);
        tv.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFFF4F5F7);
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private void check(LinearLayout parent, String label, final String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(15f);
        cb.setChecked(sp.getBoolean(key, def));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put(key, isChecked);
                save(m);
            }
        });
        parent.addView(cb);
    }

    private void edit(LinearLayout parent, String label, final String key, String hintText) {
        edit(parent, label, key, hintText, false);
    }

    private void edit(LinearLayout parent, String label, final String key, String hintText,
                      boolean multiLine) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13f);
        tv.setPadding(0, dp(10), 0, dp(2));
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
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void paintColor(TextView tv, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x33000000);
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
