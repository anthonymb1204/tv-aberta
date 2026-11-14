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
    private boolean tv = true; // false on a phone: portrait, touch layout
    private LinearLayout chips; // the phone's category chips
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
    // CazéTV's official live, read at most every 10 minutes; on game days it goes to the top.
    private volatile CazeLive caze;
    private volatile long cazeAt;
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
        tv = Device.isTv(this);
        scale = Device.scale(this, tv);
        getWindow().setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF101722, 0xFF07090D}));
        if (tv) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            // Phone: upright, with the status bar, and the list shrinks above the keyboard.
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().setStatusBarColor(0xFF101722);
            getWindow().setNavigationBarColor(0xFF07090D);
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        buildShared();
        setContentView(tv ? buildTv() : buildPhone());

        // Start on the channel watched last (Back then shows its list), else on the favourites if
        // there are any, else on Brasil. On a phone the list just opens there: nothing plays by itself.
        Channels.Channel last = state == null ? Channels.last(this) : null;
        int start = last != null ? Channels.lastCategory(this)
                : Channels.favorites(this).isEmpty() ? 1 : Channels.FAVORITES;
        if (tv && last != null && !Channels.isCaze(last)) resumeUrl = Channels.key(last); // CazéTV opens YouTube: not on its own
        if (tv) {
            categories.requestFocus();
            categories.setSelection(start);
        }
        loadCategory(start);

        android.content.IntentFilter f = new android.content.IntentFilter(Updater.ACTION_STATUS);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(installStatus, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installStatus, f);
        checkUpdate();
    }

    // The TV: categories on the left, status, search and channels on the right.
    private View buildTv() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(px(64), px(56), px(64), px(40));

        // Left: title and categories.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(logo(), new LinearLayout.LayoutParams(px(400), px(225)));
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

        // Right: status with the clock and the Novidades button on its right, then search and channels.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clock = clock("HH:mm   ·   EEEE, d 'de' MMMM", 24);
        LinearLayout.LayoutParams ckl = new LinearLayout.LayoutParams(-2, -2);
        ckl.rightMargin = px(28);
        top.addView(clock, ckl);
        top.addView(newsButton());
        right.addView(top);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(16);
        right.addView(search, slp);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        chlp.topMargin = px(16);
        right.addView(channels, chlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.leftMargin = px(48);
        root.addView(right, rlp);

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
        return root;
    }

    // The phone: logo and title, search, a row of category chips that scrolls sideways, then the
    // channels. Tap a channel to watch it, touch and hold to favourite it.
    private View buildPhone() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(36), px(28), px(36), 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(logo(), new LinearLayout.LayoutParams(px(176), px(99)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(40, Color.WHITE);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setText("TV Aberta");
        titles.addView(name);
        titles.addView(clock("HH:mm  ·  EEE, d 'de' MMM", 24)); // the time and date under the name
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = px(28);
        head.addView(titles, tl);
        head.addView(newsButton()); // top right
        root.addView(head);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = px(32);
        root.addView(search, slp);

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chips = new LinearLayout(this);
        chips.setPadding(0, px(24), 0, px(16));
        for (int i = 0; i < Channels.CATEGORY_NAMES.length; i++) {
            final int which = i;
            TextView c = text(26, 0xE6FFFFFF);
            c.setText(Channels.CATEGORY_NAMES[i]);
            c.setPadding(px(30), px(16), px(30), px(16));
            c.setOnClickListener(v -> {
                loadCategory(which);
                hideKeyboard();
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = px(14);
            chips.addView(c, cl);
        }
        scroll.addView(chips);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        status.setPadding(px(8), 0, 0, px(8));
        root.addView(status);
        root.addView(channels, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    // ---- Clock, Novidades and updates ----------------------------------------------------------

    private TextView newsButton;
    private Updater.Release update; // a newer version, when there is one
    private android.app.AlertDialog progressDialog;

    private TextView clock(String format, int size) {
        android.widget.TextClock c = new android.widget.TextClock(this);
        c.setFormat12Hour(format);
        c.setFormat24Hour(format);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        c.setTextColor(0xB3FFFFFF);
        c.setSingleLine(true);
        return c;
    }

    // "Novidades" (what's new); "● Nova versão" in red when an update is out.
    private TextView newsButton() {
        newsButton = text(tv ? 24 : 24, Color.WHITE);
        newsButton.setPadding(px(26), px(12), px(26), px(12));
        newsButton.setFocusable(true);
        newsButton.setClickable(true);
        newsButton.setBackground(selector(0x1AFFFFFF, 0x40FFFFFF));
        newsButton.setOnClickListener(v -> showNews(false));
        showUpdateState();
        return newsButton;
    }

    private void showUpdateState() {
        if (newsButton == null) return;
        if (update == null) {
            newsButton.setText("✦  Novidades");
            return;
        }
        android.text.SpannableString s = new android.text.SpannableString("●  Nova versão");
        s.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF5A5A), 0, 1, 0);
        newsButton.setText(s);
    }

    // Asks GitHub (at most every 30 minutes) whether there's a newer version; the first time a version
    // is found, the notice opens by itself.
    private void checkUpdate() {
        new Thread(() -> {
            Updater.Release r = Updater.check(this, false);
            ui.post(() -> {
                update = r;
                showUpdateState();
                if (r != null && !Updater.noticeShown(this, r.version) && !isFinishing()) {
                    Updater.markNoticeShown(this, r.version);
                    showNews(true);
                }
            });
        }).start();
    }

    // The update (if any) with what it brings, then what each version brought, newest first. Opened
    // by hand, it asks GitHub right away, so a version that just came out shows up.
    private void showNews(boolean auto) {
        new Thread(() -> {
            final Updater.Release r = auto ? update : Updater.check(this, true);
            List<Updater.Notes> notes = Updater.notes(this, true);
            ui.post(() -> {
                if (isFinishing()) return;
                update = r;
                showUpdateState();
                String mine = Updater.current(this);
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(px(tv ? 56 : 44), px(20), px(tv ? 56 : 44), px(8));
                if (r != null) {
                    TextView h = text(30, 0xFFFF8A80);
                    h.setText("Versão " + r.version + " disponível");
                    h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    box.addView(h);
                    for (Updater.Notes n : notes)
                        if (Updater.compare(n.version, mine) > 0) addNotes(box, n, false);
                    TextView sep = text(22, 0x80FFFFFF);
                    sep.setText("Você está na versão " + mine);
                    sep.setPadding(0, px(8), 0, px(20));
                    box.addView(sep);
                }
                for (Updater.Notes n : notes)
                    if (Updater.compare(n.version, mine) <= 0) addNotes(box, n, n.version.equals(mine));
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(box);
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(r != null ? "Atualização do TV Aberta" : "Novidades do TV Aberta")
                        .setView(scroll);
                if (r != null) b.setPositiveButton("Atualizar agora", (d, w) -> startUpdate(r)).setNegativeButton("Depois", null);
                else b.setPositiveButton("OK", null);
                android.app.AlertDialog d = b.show();
                if (r != null) d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocus();
            });
        }).start();
    }

    private void addNotes(LinearLayout box, Updater.Notes n, boolean installed) {
        TextView v = text(26, Color.WHITE);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setText(n.version + (installed ? "   (instalada)" : ""));
        v.setPadding(0, px(14), 0, px(4));
        box.addView(v);
        for (String line : n.lines) {
            TextView l = text(24, 0xCCFFFFFF);
            l.setText("•  " + line);
            l.setPadding(px(8), px(2), 0, px(2));
            box.addView(l);
        }
    }

    // Downloads and installs the update. Android asks the person to confirm; the first time, it may
    // ask to allow TV Aberta to install updates.
    private void startUpdate(final Updater.Release r) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita que o TV Aberta instale atualizações e toque em Atualizar de novo.",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {} // some TVs have no such screen: the installer asks instead
        }
        final TextView msg = text(26, Color.WHITE);
        msg.setPadding(px(56), px(28), px(56), px(28));
        msg.setText("Baixando a versão " + r.version + "…");
        progressDialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Atualizando").setView(msg).setCancelable(false).show();
        new Thread(() -> Updater.install(this, r, new Updater.Progress() {
            @Override public void at(int pct) {
                ui.post(() -> msg.setText(pct >= 0 ? "Baixando a versão " + r.version + "…  " + pct + "%" : "Baixando…"));
            }
            @Override public void failed(String why) {
                ui.post(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    Toast.makeText(MainActivity.this, "Não deu para atualizar (" + why + "). Tente de novo mais tarde.",
                            Toast.LENGTH_LONG).show();
                });
            }
        })).start();
    }

    // Android's installer reports back here: it needs the person's OK, it worked, or it failed.
    private final android.content.BroadcastReceiver installStatus = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (progressDialog != null) progressDialog.dismiss();
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) try { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "A atualização não foi instalada" + (m == null ? "." : " (" + m + ")."), Toast.LENGTH_LONG).show();
            }
        }
    };

    // Colours the chosen category: in the TV's list, or the phone's chip (filled) and the others.
    private void showCategory() {
        if (categories != null) ((BaseAdapter) categories.getAdapter()).notifyDataSetChanged();
        if (chips == null) return;
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView c = (TextView) chips.getChildAt(i);
            boolean on = i == category;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(40));
            bg.setColor(on ? 0xFF7FC8FF : 0x1AFFFFFF);
            c.setBackground(bg);
            c.setTextColor(on ? 0xFF07090D : 0xE6FFFFFF);
            if (on) {
                final View chip = c;
                chips.post(() -> ((android.widget.HorizontalScrollView) chips.getParent())
                        .smoothScrollTo(Math.max(0, chip.getLeft() - px(36)), 0));
            }
        }
    }

    // The logo (res/drawable/banner.png), rounded, in place of a text title.
    private View logo() {
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(getResources().getIdentifier("banner", "drawable", getPackageName()));
        logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        logo.setClipToOutline(true);
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(px(tv ? 22 : 16));
        logo.setBackground(round);
        return logo;
    }

    // Status line, search box and channel list: the same on both, placed by buildTv or buildPhone.
    private void buildShared() {
        status = text(26, 0xB3FFFFFF);
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
        if (!tv) {
            search.setHint("Buscar canal");
            search.setBackground(selector(0x1AFFFFFF, 0x2EFFFFFF));
        }
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
        // Dragging the list on a phone puts the keyboard away.
        if (!tv) channels.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {
                if (s == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            @Override public void onScroll(android.widget.AbsListView v, int a, int b, int c) {}
        });
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
        showCategory();
        final int token = ++loadToken;
        all = new ArrayList<>();
        filter();
        status.setText("Carregando " + Channels.CATEGORY_NAMES[which] + "…"); // after filter(), which writes the count
        new Thread(() -> {
            List<Channels.Channel> list;
            String error = null;
            try { list = Channels.load(this, which); }
            catch (Exception e) { list = new ArrayList<>(); error = e.getMessage(); }
            // Game day: CazéTV first.
            int ci = -1;
            for (int i = 0; i < list.size(); i++) if (Channels.isCaze(list.get(i))) ci = i;
            if (ci >= 0) {
                if (System.currentTimeMillis() - cazeAt > 10 * 60 * 1000L) {
                    cazeAt = System.currentTimeMillis();
                    caze = CazeLive.fetch();
                }
                CazeLive cz = caze;
                if (cz != null && cz.today() && ci > 0) list.add(0, list.remove(ci));
            }
            final List<Channels.Channel> result = list;
            final String err = error;
            ui.post(() -> {
                if (token != loadToken) return; // another category was picked meanwhile
                all = result;
                filter();
                checkHealth(result);
                checkLives(result, token);
                if (err != null) status.setText("Sem conexão com a lista (" + err + ")");
                else if (which == Channels.FAVORITES && result.isEmpty())
                    status.setText(tv ? "Nenhum favorito ainda: segure OK num canal para favoritar"
                            : "Nenhum favorito ainda: toque e segure um canal para favoritar");
                if (resumeUrl != null) {
                    String url = resumeUrl;
                    resumeUrl = null;
                    for (int i = 0; i < shown.size(); i++)
                        if (Channels.key(shown.get(i)).equals(url)) {
                            channels.requestFocus();
                            channels.setSelection(i);
                            play(i);
                            return;
                        }
                }
            });
        }).start();
    }

    // The channels shown: the category's, minus those off the air, matching the search. A channel
    // that several lists carry shows once: its first version on the air (the list is ranked best
    // quality first), so if that one goes down another takes its place.
    private void filter() {
        String q = Channels.fold(search.getText().toString().trim());
        List<Channels.Channel> out = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        int down = 0;
        for (Channels.Channel c : all) {
            if (Health.isDown(this, c)) { down++; continue; }
            if (category != Channels.FAVORITES && !names.add(Channels.baseName(c))) continue;
            if (q.isEmpty() || c.search.contains(q)) out.add(c);
        }
        shown = out;
        adapter.notifyDataSetChanged();
        if (category >= 0) status.setText(Channels.CATEGORY_NAMES[category] + " · " + out.size() + " canais"
                + (q.isEmpty() ? "" : " para \"" + search.getText() + "\"")
                + (down == 0 ? "" : " · " + down + " fora do ar"));
    }

    // Channels found off the air disappear in batches (not one row at a time while you look), and the
    // channel you were on stays selected.
    private final Runnable hideDown = () -> {
        int pos = channels.getSelectedItemPosition();
        String keep = pos >= 0 && pos < shown.size() ? Channels.key(shown.get(pos)) : null;
        filter();
        if (keep != null) for (int i = 0; i < shown.size(); i++)
            if (Channels.key(shown.get(i)).equals(keep)) { channels.setSelection(i); break; }
    };

    // ---- YouTube channels: which are live now ----

    private final java.util.Map<String, CazeLive> ytLive = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> ytAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService ytPool = java.util.concurrent.Executors.newFixedThreadPool(4);

    // The channel's current (or today's) live, read from its "/live" page at most every 10 minutes
    // (or now, when asked to be fresh). Null when there's none. Call it off the main thread.
    private CazeLive liveOf(Channels.Channel ch, boolean fresh) {
        if (Channels.isCaze(ch)) {
            if (fresh || caze == null || System.currentTimeMillis() - cazeAt > 10 * 60 * 1000L) {
                caze = CazeLive.fetch();
                cazeAt = System.currentTimeMillis();
            }
            return caze;
        }
        Long at = ytAt.get(ch.url);
        if (fresh || at == null || System.currentTimeMillis() - at > 10 * 60 * 1000L) {
            CazeLive cz = CazeLive.fetch(ch.url);
            if (cz != null) ytLive.put(ch.url, cz); else ytLive.remove(ch.url);
            ytAt.put(ch.url, System.currentTimeMillis());
        }
        return ytLive.get(ch.url);
    }

    // Reads the YouTube channels' live status in the background; the ones live now (or later today)
    // move to the top of the list, with "● AO VIVO" and the live's title.
    private void checkLives(final List<Channels.Channel> list, final int token) {
        final List<Channels.Channel> yt = new ArrayList<>();
        for (Channels.Channel c : list) if (Channels.isYouTube(c) && !Channels.isCaze(c)) yt.add(c);
        if (yt.isEmpty()) return;
        final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(yt.size());
        for (final Channels.Channel c : yt) ytPool.execute(() -> {
            liveOf(c, false);
            if (left.decrementAndGet() > 0) return;
            ui.post(() -> {
                if (token != loadToken) return;
                List<Channels.Channel> live = new ArrayList<>(), rest = new ArrayList<>();
                for (Channels.Channel ch : all) {
                    CazeLive cz = Channels.isYouTube(ch) && !Channels.isCaze(ch) ? ytLive.get(ch.url) : null;
                    (cz != null && cz.today() ? live : rest).add(ch);
                }
                if (live.isEmpty()) { adapter.notifyDataSetChanged(); return; }
                // Live now first, then the ones later today; CazéTV on a game day stays first of all.
                java.util.Collections.sort(live, (a, b) -> Boolean.compare(!ytLive.get(a.url).liveNow, !ytLive.get(b.url).liveNow));
                int at = !rest.isEmpty() && Channels.isCaze(rest.get(0)) && caze != null && caze.today() ? 1 : 0;
                rest.addAll(at, live);
                all = rest;
                hideDown.run(); // filter again, keeping the selected channel
            });
        });
    }

    private void checkHealth(List<Channels.Channel> list) {
        Health.check(this, list, (ch, ok) -> {
            if (ok) return;
            ui.removeCallbacks(hideDown);
            ui.postDelayed(hideDown, 1500);
        });
    }

    private void play(final int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        final Channels.Channel ch = shown.get(pos);
        if (Channels.isGloboplay(ch)) { // Globo live, in the Globoplay app
            Intent g = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Channels.GLOBOPLAY)).setPackage("com.globo.globotv");
            try { startActivity(g); }
            catch (Exception e) {
                Intent app = tv ? getPackageManager().getLeanbackLaunchIntentForPackage("com.globo.globotv") : null;
                if (app == null) app = getPackageManager().getLaunchIntentForPackage("com.globo.globotv");
                if (app != null) startActivity(app);
                else Toast.makeText(this, "Instale o Globoplay para ver a Globo ao vivo.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (Channels.isYouTube(ch)) { // CazéTV, GOAT, N Sports...: the channel's live, in the YouTube app
            Toast.makeText(this, "Abrindo " + ch.name + " no YouTube…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                CazeLive cz = liveOf(ch, true);
                ui.post(() -> {
                    if (cz != null) { CazeLive.open(this, cz.videoId); return; }
                    // No live, nothing scheduled: the channel itself, with its past lives and videos.
                    Toast.makeText(this, ch.name + " não está ao vivo agora. Abrindo o canal.", Toast.LENGTH_LONG).show();
                    CazeLive.openUrl(this, ch.url.replaceFirst("/live$", "/streams"));
                });
            }).start();
            return;
        }
        final List<Channels.Channel> list = new ArrayList<>(shown);
        Toast.makeText(this, "Testando " + ch.name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = Channels.responds(ch);
            if (Health.testable(ch)) Health.mark(this, ch, ok);
            ui.post(() -> {
                if (!ok) {
                    Toast.makeText(this, ch.name + " está fora do ar agora e saiu da lista.", Toast.LENGTH_LONG).show();
                    ui.removeCallbacks(hideDown);
                    ui.post(hideDown);
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
            String name = (p + 1) + "   " + (category != Channels.FAVORITES && Channels.isFavorite(MainActivity.this, c)
                    ? "★ " : "") + c.name;
            CazeLive cz = Channels.isCaze(c) ? caze : Channels.isYouTube(c) ? ytLive.get(c.url) : null;
            if (cz != null && cz.today()) {
                // Game day: "● AO VIVO" in red (or today's time), then the game.
                String tag = cz.liveNow ? "●  AO VIVO" : "hoje às " + cz.startTime();
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name + "   ");
                int s = sb.length();
                sb.append(tag);
                sb.setSpan(new android.text.style.ForegroundColorSpan(cz.liveNow ? 0xFFFF5A5A : 0xFF7FC8FF), s, sb.length(), 0);
                if (cz.title != null && !cz.title.isEmpty()) sb.append("   ·   ").append(cz.title);
                t.setText(sb);
            } else {
                t.setText(group.isEmpty() ? name : name + "   ·   " + group);
            }
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
        t.setMinHeight(px(size * 2 + (tv ? 14 : 44))); // a phone row is a comfortable finger's height
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
        try { unregisterReceiver(installStatus); } catch (Exception ignored) {}
        logoPool.shutdownNow();
        ytPool.shutdownNow();
        super.onDestroy();
    }
}
