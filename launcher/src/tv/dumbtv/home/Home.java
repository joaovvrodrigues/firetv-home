package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;
    private FrameLayout root;
    private ImageView wall;
    private View shade, glow;
    private TextView clock, date, greeting, selectedName, dockHint;
    private String focusedApp;
    private Row apps, inputs;
    private Tile moving;
    private String wallKey;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final FrameLayout panel;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            title = Ui.text(Home.this, 10, 0xB3FFFFFF, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.18f);
            title.setPadding(dp(8), dp(14), 0, dp(8));
            parent.addView(title);
            strip = new LinearLayout(Home.this);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(dp(20), dp(22), dp(20), dp(22));
            scroll = new HorizontalScrollView(Home.this);
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(true);
            scroll.setClipToPadding(true);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            panel = new FrameLayout(Home.this);
            panel.setClipChildren(true);
            panel.setClipToOutline(true);
            GradientDrawable band = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x36FFFFFF, 0x1AFFFFFF});
            band.setCornerRadius(dp(26));
            band.setStroke(dp(1), 0x24FFFFFF);
            panel.setBackground(band);
            panel.addView(scroll, new FrameLayout.LayoutParams(MATCH, WRAP));
            parent.addView(panel, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        void setVisible(boolean on) {
            title.setVisibility(on && !Catalog.APPS.equals(kind) ? View.VISIBLE : View.GONE);
            panel.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        if (!"2".equals(cfg.get("design.version", ""))) {
            if ("black".equals(cfg.get(Config.BACKGROUND))) cfg.set("background", "scenic");
            cfg.set("design.version", "2");
        }
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        loadApps(null);
        loadInputs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        if (cfg.version() != applied) apply();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /* Visual composition adapted from mihirgupta0900/tv-home (MIT, Mihir Gupta).
     * Fire TV routing, configuration and package handling remain dumbtv's.
     */
    private View build() {
        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        head.setClipChildren(false);
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        greeting = Ui.text(this, 10, 0xCCFFFFFF, Ui.MEDIUM);
        greeting.setLetterSpacing(0.24f);
        greeting.setPadding(dp(2), 0, 0, dp(5));
        clock = Ui.text(this, 58, Color.WHITE, Typeface.create("sans-serif-thin", Typeface.NORMAL));
        date = Ui.text(this, 10, 0xB3FFFFFF, Ui.MEDIUM);
        date.setLetterSpacing(0.18f);
        date.setPadding(dp(2), dp(7), dp(12), 0);
        when.addView(greeting);
        when.addView(clock);
        when.addView(date);
        for (TextView text : new TextView[] {greeting, clock, date}) {
            text.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        }
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(chip(getString(R.string.options), v -> startActivity(new Intent(this, Options.class))));
        head.addView(chip(getString(R.string.settings), v -> openSettings()));
        FrameLayout.LayoutParams header = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP);
        header.setMargins(dp(52), dp(38), dp(52), 0);
        root.addView(head, header);

        LinearLayout bottom = new LinearLayout(this) {
            @Override public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_UP && focused instanceof Tile
                        && Catalog.APPS.equals(((Tile) focused).kind)) return chips.get(0);
                return super.focusSearch(focused, direction);
            }
        };
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        selectedName = Ui.text(this, 32, Color.WHITE, Ui.LIGHT);
        selectedName.setSingleLine();
        selectedName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selectedName.setText(R.string.apps);
        selectedName.setShadowLayer(dp(12), 0, dp(2), 0x99000000);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(MATCH, WRAP);
        label.setMargins(dp(8), 0, dp(8), dp(16));
        bottom.addView(selectedName, label);
        apps = new Row(Catalog.APPS, getString(R.string.apps), bottom);
        inputs = new Row(Catalog.INPUTS, getString(R.string.inputs), bottom);
        dockHint = Ui.text(this, 9, 0xA6FFFFFF, Ui.MEDIUM);
        dockHint.setLetterSpacing(0.12f);
        dockHint.setPadding(dp(8), dp(12), 0, 0);
        bottom.addView(dockHint);
        FrameLayout.LayoutParams dock = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        dock.setMargins(dp(48), 0, dp(48), dp(28));
        root.addView(bottom, dock);
        return root;
    }

    /** The child of {@code strip} horizontally closest to {@code from}, or null when empty. */
    private static View nearest(LinearLayout strip, View from) {
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 11, 0xE6FFFFFF, Ui.MEDIUM);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), dp(9), dp(16), dp(9));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> view.animate()
                .scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(180).start());
        v.setOnKeyListener((view, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                View first = apps.strip.getChildAt(0);
                if (first != null) { first.requestFocus(); return true; }
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(3);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) Ui.focusStyle(c, 0xF2FFFFFF, 0x55000000, dp(18));
        render(apps);
        render(inputs);
        background();
        tick();
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                            fresh.color = old.color;
                        }
                    }
                }
                apps.all = list;
                render(apps);
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        row.strip.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all) : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Catalog.APPS.equals(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnFocusChangeListener((view, has) -> {
                if (has && Catalog.APPS.equals(row.kind)) select(t);
            });
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnLongClickListener(v -> {
                menu((Tile) v);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = index == shown.size() - 1 ? 0 : dp(20);
            row.strip.addView(t, lp);
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && !it.artRequested) loadArt(it);
        }
        row.setVisible(!shown.isEmpty());
        if (Catalog.APPS.equals(row.kind)) {
            dockHint.setText(getString(R.string.dock_hint, shown.size()));
            selectedName.setVisibility("never".equals(labels) ? View.INVISIBLE : View.VISIBLE);
        }
        if (restore >= 0) {
            row.strip.getChildAt(restore).requestFocus();
        } else if (keep != null && row.strip.getChildCount() > 0) {
            row.strip.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            it.color = Ui.sampleColor(it.banner != null ? it.banner : it.icon);
            ui.post(() -> {
                for (int i = 0; i < apps.strip.getChildCount(); i++) apps.strip.getChildAt(i).invalidate();
                if (it.id.equals(focusedApp)) setGlow(it.color);
            });
        });
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(144), dp(81)};
            case "large":
                return new int[] {dp(224), dp(126)};
            default:
                return new int[] {dp(180), dp(101)};
        }
    }

    private void focusFirst() {
        apps.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        View first = apps.strip.getChildAt(0);
        if (first == null) first = inputs.strip.getChildAt(0);
        landed = apps.strip.getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void select(Tile tile) {
        focusedApp = tile.item.id;
        selectedName.setText(Catalog.label(cfg, tile.item));
        setGlow(tile.item.color);
    }

    private void setGlow(int color) {
        GradientDrawable light = new GradientDrawable();
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        light.setGradientCenter(0.45f, 0.88f);
        light.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.55f);
        light.setColors(new int[] {(color & 0x00FFFFFF) | 0x30000000,
                (color & 0x00FFFFFF) | 0x0A000000, Color.TRANSPARENT});
        glow.setBackground(light);
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.30f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour < 5 || hour >= 18 ? R.string.good_evening
                : hour < 12 ? R.string.good_morning : R.string.good_afternoon);
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now)
                .toString().toUpperCase(locale));
    }

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        boolean scenic = "scenic".equals(mode);
        File file = "image".equals(mode) ? cfg.wallpaper() : null;
        boolean photo = scenic || file != null;
        root.setBackgroundColor(cfg.background());
        if (photo) {
            int dim = Integer.parseInt(cfg.get(Config.DIM));
            int top = Math.min(220, 80 + dim);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {Color.argb(top, 0, 0, 0), 0x18000000, 0x22000000, 0x88000000, 0xCC000000}));
        } else shade.setBackgroundColor(Color.TRANSPARENT);
        String key = scenic ? "scenic" : file == null ? "none" : file.getPath() + ":" + file.lastModified();
        if (key.equals(wallKey)) return;
        wallKey = key;
        if (!photo) { wall.setImageDrawable(null); return; }
        io.execute(() -> {
            Bitmap bitmap;
            if (scenic) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.lake_louise, options);
            } else {
                bitmap = decode(file, getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels);
            }
            ui.post(() -> {
                if (!isDestroyed() && key.equals(wallKey)) wall.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap decode(File file, int w, int h) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        options.inSampleSize = 1;
        while (options.outWidth / (options.inSampleSize * 2) >= w
                && options.outHeight / (options.inSampleSize * 2) >= h) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Tile t) {
        launch(new Intent(t.item.intent), Catalog.APPS.equals(t.kind) ? t.item.id : null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {getString(R.string.move), getString(R.string.rename), getString(R.string.hide), getString(R.string.app_info)}
                : new String[] {getString(R.string.move), getString(R.string.rename), getString(R.string.hide)};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, R.string.hidden_hint, Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(R.string.save, (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton(R.string.original, (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        LinearLayout strip = (LinearLayout) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        LinearLayout strip = (LinearLayout) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
