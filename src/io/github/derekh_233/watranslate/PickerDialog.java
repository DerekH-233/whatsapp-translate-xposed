package io.github.derekh_233.watranslate;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable picker used for the 248-language list and the 215 area codes. */
final class PickerDialog {

    interface OnPick {
        void picked(String code, String label);
    }

    private PickerDialog() {
    }

    static void showLanguage(final Context ctx, String currentCode, final OnPick onPick) {
        final List<String[]> all = LocaleInfo.allLanguages();
        final List<String[]> filtered = new ArrayList<String[]>(all);
        show(ctx, "选择语言（" + all.size() + " 种）", filtered, all, currentCode, onPick);
    }

    static void showAreaCode(final Context ctx, String currentCode, final OnPick onPick) {
        final List<String[]> all = LocaleInfo.allAreaCodes();
        final List<String[]> filtered = new ArrayList<String[]>(all);
        show(ctx, "选择国家/地区区号（" + all.size() + " 个）", filtered, all, currentCode, onPick);
    }

    private static void show(final Context ctx, String title, final List<String[]> filtered,
                             final List<String[]> all, final String currentCode,
                             final OnPick onPick) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (ctx.getResources().getDisplayMetrics().density * 12);
        root.setPadding(pad, pad, pad, pad);

        final EditText search = new EditText(ctx);
        search.setHint("搜索…");
        search.setSingleLine(true);
        root.addView(search);

        final ListView list = new ListView(ctx);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx,
                android.R.layout.simple_list_item_1, labelsOf(filtered, currentCode));
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (ctx.getResources()
                .getDisplayMetrics().heightPixels * 0.5f)));

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                filtered.clear();
                if (q.isEmpty()) {
                    filtered.addAll(all);
                } else {
                    for (String[] row : all) {
                        for (String cell : row) {
                            if (cell != null && cell.toLowerCase(Locale.US).contains(q)) {
                                filtered.add(row);
                                break;
                            }
                        }
                    }
                }
                adapter.clear();
                for (String label : labelsOf(filtered, currentCode)) {
                    adapter.add(label);
                }
                adapter.notifyDataSetChanged();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) {
                    return;
                }
                String[] row = filtered.get(position);
                onPick.picked(row[0], row.length > 1 ? row[1] : row[0]);
                dialog.dismiss();
            }
        });
    }

    private static List<String> labelsOf(List<String[]> rows, String currentCode) {
        List<String> out = new ArrayList<String>(rows.size());
        for (String[] row : rows) {
            String code = row[0];
            String zh = row.length > 1 ? row[1] : code;
            String en = row.length > 2 ? row[2] : "";
            String label = en.isEmpty() || en.equals(zh) ? zh : zh + "  ·  " + en;
            if (code != null && code.equalsIgnoreCase(currentCode)) {
                label = "✓ " + label;
            }
            out.add(label);
        }
        return out;
    }
}
