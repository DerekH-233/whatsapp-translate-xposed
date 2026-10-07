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

        TextView t = new TextView(this);
        t.setText("WhatsApp 翻译助手");
        t.setTextSize(22f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);

        TextView sub = new TextView(this);
        sub.setText("把对方发来的聊天消息翻译成你设定的语言，译文以小字显示在原文下方。\n"
                + "翻译接口可以选公开的，也可以填自己的 AI 接口。");
        sub.setTextSize(13f);
        sub.setTextColor(0xFF777777);
        sub.setLineSpacing(0f, 1.35f);
        sub.setPadding(0, dp(6), 0, dp(12));
        card.addView(sub);

        Button open = new Button(this);
        open.setText("打开 WhatsApp");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWhatsApp();
            }
        });
        card.addView(open);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setTextColor(0xFF888888);
        hint.setPadding(0, dp(8), 0, 0);
        hint.setText("这里是模块的设置入口，装好后从桌面图标或 LSPosed 模块列表进入都可以。\n"
                + "改完设置立即生效，不需要重启 WhatsApp。");
        card.addView(hint);
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
        hint(root, "打开后，别人发来的消息会在气泡里原文下方多出一行小字译文。关掉则只保留原文。");
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

        hint(root, "前 4 个是公开接口，不需要注册也不需要密钥，装上就能用，建议先用 Google。\n"
                + "后 4 个要用你自己的账号：百度、DeepL、微软、以及任意 OpenAI 兼容的 AI 接口（如 DeepSeek）。");
    }

    private void rebuildEngineKeys(Engine engine) {
        engineKeyBox.removeAllViews();
        switch (engine) {
            case CUSTOM_AI:
                edit(engineKeyBox, "接口地址（可只填 base，会自动补 /v1/chat/completions）",
                        Prefs.KEY_AI_ENDPOINT, "https://api.deepseek.com");
                edit(engineKeyBox, "API Key", Prefs.KEY_AI_KEY, "sk-...");
                edit(engineKeyBox, "模型名", Prefs.KEY_AI_MODEL, "deepseek-chat / gpt-4o-mini");
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
        hint(root, "译文是原文下面的小字，点上面的色块就能换颜色。默认灰色，和原文区分得开又不抢眼。");

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

        hint(root, "按对方手机号的国家代码离线查表得出，不会把号码发到任何服务器。\n"
                + "覆盖 " + LocaleInfo.allAreaCodes().size() + " 个国家/地区区号，含语言、货币和当地时区。");
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
        tv.setPadding(0, dp(18), 0, dp(6));
        parent.addView(tv);
    }

    private void hint(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF888888);
        tv.setPadding(0, dp(4), 0, 0);
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
