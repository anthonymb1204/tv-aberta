package com.vizzion.tvaberta;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

// Categories on the left, channels and search on the right. OK on a channel checks that it answers,
// then plays it with Android's own player.
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private float scale = 1f;
    private ListView categories, channels;
    private EditText search;
    private TextView status;
    private List<Channels.Channel> all = new ArrayList<>(), shown = new ArrayList<>();
    private int category = -1, loadToken;
    private ChannelAdapter adapter;
    private int pendingCategory;
    private final Runnable pendingLoad = () -> loadCategory(pendingCategory);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF101722, 0xFF07090D}));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(px(64), px(56), px(64), px(40));

        // Left: title and categories.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // The logo (res/drawable/banner.png), rounded, in place of a text title.
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(getResources().getIdentifier("banner", "drawable", getPackageName()));
        logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        logo.setClipToOutline(true);
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(px(22));
        logo.setBackground(round);
        left.addView(logo, new LinearLayout.LayoutParams(px(400), px(225)));
        TextView sub = text(22, 0x99FFFFFF);
        sub.setText("Canais gratuitos · iptv-org");
        sub.setPadding(px(6), px(12), 0, 0);
        left.addView(sub);
        categories = list();
        categories.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return Channels.CATEGORY_NAMES.length; }
            @Override public Object getItem(int p) { return Channels.CATEGORY_NAMES[p]; }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup g) {
                TextView t = v != null ? (TextView) v : row(32);
                t.setText(Channels.CATEGORY_NAMES[p]);
                t.setTextColor(p == category ? 0xFF7FC8FF : 0xE6FFFFFF);
                return t;
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = px(32);
        left.addView(categories, clp);
        root.addView(left, new LinearLayout.LayoutParams(px(400), -1));

        // Right: status, search and channels.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        status = text(26, 0xB3FFFFFF);
        right.addView(status);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar canal…");
        search.setHintTextColor(0x80FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28));
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setPadding(px(24), px(14), px(24), px(14));
        search.setBackground(selector(0x1AFFFFFF, 0x40FFFFFF));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            hideKeyboard();
            if (!shown.isEmpty()) channels.requestFocus();
            return true;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(16);
        right.addView(search, slp);
        channels = list();
        adapter = new ChannelAdapter();
        channels.setAdapter(adapter);
        channels.setOnItemClickListener((p, v, pos, id) -> play(pos));
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        chlp.topMargin = px(16);
        right.addView(channels, chlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.leftMargin = px(48);
        root.addView(right, rlp);

        setContentView(root);

        // Moving through the categories loads each one after a short pause; OK loads it at once.
        categories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                ui.removeCallbacks(pendingLoad);
                pendingCategory = pos;
                ui.postDelayed(pendingLoad, 450);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        categories.setOnItemClickListener((p, v, pos, id) -> {
            ui.removeCallbacks(pendingLoad);
            loadCategory(pos);
            if (!shown.isEmpty()) channels.requestFocus();
        });
        categories.requestFocus();
        categories.setSelection(0);
        loadCategory(0);
    }

    private void loadCategory(final int which) {
        if (which == category) return;
        category = which;
        ((BaseAdapter) categories.getAdapter()).notifyDataSetChanged();
        final int token = ++loadToken;
        status.setText("Carregando " + Channels.CATEGORY_NAMES[which] + "…");
        all = new ArrayList<>();
        filter();
        new Thread(() -> {
            List<Channels.Channel> list;
            String error = null;
            try { list = Channels.load(this, which); }
            catch (Exception e) { list = new ArrayList<>(); error = e.getMessage(); }
            final List<Channels.Channel> result = list;
            final String err = error;
            ui.post(() -> {
                if (token != loadToken) return; // another category was picked meanwhile
                all = result;
                filter();
                if (err != null) status.setText("Sem conexão com a lista (" + err + ")");
            });
        }).start();
    }

    private void filter() {
        String q = Channels.fold(search.getText().toString().trim());
        List<Channels.Channel> out = new ArrayList<>();
        for (Channels.Channel c : all) if (q.isEmpty() || c.search.contains(q)) out.add(c);
        shown = out;
        adapter.notifyDataSetChanged();
        if (category >= 0) status.setText(Channels.CATEGORY_NAMES[category] + " · " + out.size() + " canais"
                + (q.isEmpty() ? "" : " para \"" + search.getText() + "\""));
    }

    private void play(final int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        final Channels.Channel ch = shown.get(pos);
        final List<Channels.Channel> list = new ArrayList<>(shown);
        Toast.makeText(this, "Testando " + ch.name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = Channels.responds(ch);
            ui.post(() -> {
                if (!ok) {
                    Toast.makeText(this, ch.name + " não respondeu. Tente outro canal.", Toast.LENGTH_LONG).show();
                    return;
                }
                Channels.playing = list;
                startActivity(new Intent(this, PlayerActivity.class).putExtra("index", pos));
            });
        }).start();
    }

    // Typing on the remote: any letter key while the channel list has focus jumps to search.
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN && !search.hasFocus() && ev.isPrintingKey()
                && Character.isLetter(ev.getUnicodeChar())) {
            search.requestFocus();
            search.append(String.valueOf((char) ev.getUnicodeChar()));
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View v, ViewGroup g) {
            TextView t = v != null ? (TextView) v : row(28);
            Channels.Channel c = shown.get(p);
            String group = c.group == null ? "" : c.group.replace(";", " · ");
            t.setText(group.isEmpty() ? c.name : c.name + "   ·   " + group);
            return t;
        }
    }

    private ListView list() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setVerticalScrollBarEnabled(false);
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        l.setItemsCanFocus(false);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(18));
        focus.setColor(0x38FFFFFF);
        l.setSelector(focus);
        l.setDrawSelectorOnTop(false);
        return l;
    }

    private TextView row(int size) {
        TextView t = text(size, 0xE6FFFFFF);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(px(size * 2 + 14));
        t.setPadding(px(22), 0, px(22), 0);
        return t;
    }

    private StateListDrawable selector(int rest, int focused) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(px(18));
        f.setColor(focused);
        f.setStroke(px(2), 0x80FFFFFF);
        GradientDrawable r = new GradientDrawable();
        r.setCornerRadius(px(18));
        r.setColor(rest);
        s.addState(new int[] {android.R.attr.state_focused}, f);
        s.addState(new int[0], r);
        return s;
    }

    private TextView text(int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        return t;
    }

    private int px(int v) { return Math.round(v * scale); }
}
