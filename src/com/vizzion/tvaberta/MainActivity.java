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
    // Opening the app goes straight back to the channel watched last; this is its URL until it starts.
    private String resumeUrl;
    // Typing a number on the remote jumps to that position in the list.
    private final StringBuilder digits = new StringBuilder();
    private final Runnable jump = () -> {
        int n = Integer.parseInt(digits.toString());
        digits.setLength(0);
        if (n >= 1 && n <= shown.size()) {
            channels.requestFocus();
            channels.setSelection(n - 1);
        }
    };
    // Channel logos: small, loaded two at a time for the rows on screen, kept in memory and on disk.
    private final android.util.LruCache<String, android.graphics.drawable.Drawable> logos = new android.util.LruCache<>(90);
    private final java.util.Set<String> loading = new java.util.HashSet<>(), noLogo = new java.util.HashSet<>();
    private final java.util.concurrent.ExecutorService logoPool = java.util.concurrent.Executors.newFixedThreadPool(2);

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
        // Hold OK on a channel (or press Menu) to add it to, or remove it from, the favourites.
        channels.setOnItemLongClickListener((p, v, pos, id) -> { toggleFavorite(pos); return true; });
        channels.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_MENU) return false;
            if (ev.getAction() == KeyEvent.ACTION_UP) toggleFavorite(channels.getSelectedItemPosition());
            return true;
        });
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
        // Start on the channel watched last (Back then shows its list), else on the favourites if
        // there are any, else on Brasil.
        Channels.Channel last = state == null ? Channels.last(this) : null;
        int start = last != null ? Channels.lastCategory(this)
                : Channels.favorites(this).isEmpty() ? 1 : Channels.FAVORITES;
        if (last != null) resumeUrl = last.url;
        categories.requestFocus();
        categories.setSelection(start);
        loadCategory(start);
    }

    private void toggleFavorite(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        Channels.Channel ch = shown.get(pos);
        boolean fav = Channels.toggleFavorite(this, ch);
        Toast.makeText(this, (fav ? "★ " + ch.name + " nos favoritos" : ch.name + " saiu dos favoritos"),
                Toast.LENGTH_SHORT).show();
        if (category == Channels.FAVORITES) {
            all = Channels.favorites(this);
            filter();
        } else {
            adapter.notifyDataSetChanged();
        }
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
                else if (which == Channels.FAVORITES && result.isEmpty())
                    status.setText("Nenhum favorito ainda: segure OK num canal para favoritar");
                if (resumeUrl != null) {
                    String url = resumeUrl;
                    resumeUrl = null;
                    for (int i = 0; i < shown.size(); i++)
                        if (shown.get(i).url.equals(url)) {
                            channels.requestFocus();
                            channels.setSelection(i);
                            play(i);
                            return;
                        }
                }
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
                startActivity(new Intent(this, PlayerActivity.class).putExtra("index", pos).putExtra("category", category));
            });
        }).start();
    }

    // Typing on the remote: any letter key while the channel list has focus jumps to search.
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (!search.hasFocus() && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && digits.length() < 5) {
                digits.append((char) ('0' + code - KeyEvent.KEYCODE_0));
                status.setText("Ir para o canal " + digits + "…");
                ui.removeCallbacks(jump);
                ui.postDelayed(jump, 1200);
            }
            return true;
        }
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
            String name = (p + 1) + "   " + (category != Channels.FAVORITES && Channels.isFavorite(MainActivity.this, c.url)
                    ? "★ " : "") + c.name;
            t.setText(group.isEmpty() ? name : name + "   ·   " + group);
            // The logo to the left: from memory, or a blank of the same size while it loads.
            t.setTag(c.logo);
            android.graphics.drawable.Drawable d = c.logo == null ? null : logos.get(c.logo);
            if (d == null) {
                d = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
                if (c.logo != null) fetchLogo(c.logo);
            }
            d.setBounds(0, 0, px(LOGO_W), px(LOGO_H));
            t.setCompoundDrawables(d, null, null, null);
            t.setCompoundDrawablePadding(px(22));
            return t;
        }
    }

    private static final int LOGO_W = 84, LOGO_H = 48;

    private void fetchLogo(final String url) {
        if (loading.contains(url) || noLogo.contains(url)) return;
        loading.add(url);
        logoPool.execute(() -> {
            android.graphics.Bitmap bm = Channels.logo(this, url, px(LOGO_H) * 2);
            android.graphics.drawable.Drawable d = null;
            if (bm != null) {
                // Fit inside the logo box, keeping its shape, on a transparent picture.
                int w = px(LOGO_W), h = px(LOGO_H);
                android.graphics.Bitmap box = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                float s = Math.min((float) w / bm.getWidth(), (float) h / bm.getHeight());
                int dw = Math.round(bm.getWidth() * s), dh = Math.round(bm.getHeight() * s);
                new android.graphics.Canvas(box).drawBitmap(bm, null,
                        new android.graphics.Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh),
                        new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                bm.recycle();
                d = new android.graphics.drawable.BitmapDrawable(getResources(), box);
            }
            final android.graphics.drawable.Drawable logo = d;
            ui.post(() -> {
                loading.remove(url);
                if (logo == null) { noLogo.add(url); return; }
                logos.put(url, logo);
                // Only the rows on screen showing this logo are updated.
                for (int i = 0; i < channels.getChildCount(); i++) {
                    View row = channels.getChildAt(i);
                    if (url.equals(row.getTag()) && row instanceof TextView) {
                        logo.setBounds(0, 0, px(LOGO_W), px(LOGO_H));
                        ((TextView) row).setCompoundDrawables(logo, null, null, null);
                    }
                }
            });
        });
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

    @Override
    protected void onDestroy() {
        logoPool.shutdownNow();
        super.onDestroy();
    }
}
