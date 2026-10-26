package io.cleanyourtv.ui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards: in the Plex tab (first, with a Plex server set), what is being watched there (Plex); in the others,
 *    the titles recommended for right now (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up 700 px, the shelf ends at the top and the grid of the other apps appears
 *    below it (same tiles, 5 columns), on a darker background; further down, the drawer (inputs, shortcuts, weather
 *    for the next hours and quotes with the 24 h change). ↑ from the grid's first row, or Back, comes home;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        Plex.Listener, Stocks.Listener, Updates.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (300 × 180) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge). Up to 5 apps, the shelf sits on
    // the apps grid's 5 columns.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22, TILE_MAX_W = 300;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // Apps grid under the shelf: ↓ lifts the whole screen by APPS_LIFT (the shelf ends at the top), the other apps below
    // in the shelf's tiles and columns; the drawer comes after the last row.
    private static final int COLUMNS = 5;
    private static final float GRID_W = 300, GRID_H = 180, GRID_GAP = 48, GRID_ROW_GAP = 56, APPS_LIFT = 700;
    /** Screen y (at rest, before the lift) of the grid's first row: under the shelf glass (760 + 260), 56 apart. */
    private static final float GRID_TOP = 1076;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Ambilight + Hue calls (binder calls to Philips' services): never in the way of loading the apps. */
    private static final ExecutorService AMBI = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 240, L_CARD_H = 380;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 760, L_TILE_H = 180;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_RU = {"Обзор", "Фильмы", "Сериалы"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series"};
    private static final String[] TABS_DE = {"Entdecken", "Filme", "Serien"};
    private static final int Z_TOP = 4, Z_CARDS = 5;
    /** topRects: the tabs first (up to 4: Plex, Explore, Movies, Shows), then the round buttons from B0 (up to 6). */
    private static final int B0 = 4;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    // Network, for the Wi-Fi symbol at the top (with a padlock while a VPN is on).
    private final android.net.ConnectivityManager connectivity;
    private volatile boolean netWifi, netOther, netVpn;
    // Ambilight + Hue (AmbiHue): on while Philips' HueService drives the Hue lamps. The ring turns while on.
    private volatile boolean ambiOn;
    private final View ambiGlow;
    private final android.net.ConnectivityManager.NetworkCallback netCallback = new android.net.ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities caps) { netChanged(); }
        @Override public void onLost(android.net.Network n) { netChanged(); }
        @Override public void onAvailable(android.net.Network n) { netChanged(); }
    };
    private final Plex plex;
    /** The tabs this build has: "plex" (with a Plex server), then null (Explore), "movie", "tv" (with a TMDB key). */
    private final String[] tabKinds;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 3) and icon-only buttons (from B0); the three cards. In the Plex tab the list can be
    // longer: a carousel. The three cards show list[plexStart] and the next two (wrapping around); ← → past the edge
    // and, every 12 s while the focus is elsewhere, the row turns by one title, sliding in.
    private int tab, topIndex, cardIndex, plexStart;
    private static final long CAROUSEL_MS = 12_000;
    private final Spring slide = new Spring(0.6f, 1f, 0);
    private boolean carouselOn;
    private final Runnable carousel = new Runnable() {
        @Override public void run() {
            if (!running || !carouselActive()) return;
            turnCarousel(1);
            restartCarousel();
        }
    };
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[B0 + 6];
    private final RectF[] topRects = new RectF[B0 + 6];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lPill;
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private boolean gridMoving;                         // Move on an app of the grid: the arrows carry it
    private List<HomeApps.Item> gridOrigin;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) appMenu(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;        // the screen is lifted: the grid or the drawer has the focus
    private int drawerIndex;

    // Apps grid: `drawer` lifts the screen (1 = the grid's first row under the shelf; more = scrolled further down)
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private final RectF focusedCell = new RectF();
    private float gridLeft;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    /** The round buttons' names, in a small smoked-glass capsule above the focused one (drawButtonLabel). */
    private Paint buttonLabel;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            refreshAmbilight();     // Philips may switch Ambilight + Hue by itself (at power on, from its own menu)
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        connectivity = (android.net.ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        plex = Plex.get(a);
        List<String> kinds = new ArrayList<>();
        if (Plex.on()) kinds.add("plex");
        tabKinds = kinds.toArray(new String[0]);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms). On a Philips OLED907 (Android 11, MediaTek) both cost the same: the app draws a frame in
        // ~3 ms and waits for the display (60 fps either way, measured 2026-10-25), and the layers would hold ~50 MB of
        // textures: off there too. Ui.cachedLayers lets a TV be measured both ways.
        applyLayerType();
        ambiGlow = new View(a);
        ambiGlow.setVisibility(INVISIBLE);
        ambiGlow.setBackground(new android.graphics.drawable.Drawable() {
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                float cx = b.exactCenterX(), cy = b.exactCenterY(), r = b.width() / 2f - b.width() * 0.08f;
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(b.width() * 0.08f);
                ring.setStrokeCap(Paint.Cap.ROUND);
                ring.setShader(new android.graphics.SweepGradient(cx, cy, new int[] {0xFFFF6A3D, 0xFFFF2D95, 0xFF8E5CFF,
                        0xFF2FA8FF, 0xFF30D158, 0x00FFD60A, 0xFFFF6A3D}, null));
                c.drawCircle(cx, cy, r, ring);
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
        for (View v : new View[] {sky, heroLayer, status, ambiGlow, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("CleanYourTV UI");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        applyLayerType();
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        plex.addListener(this);
        placeAmbiGlow();
        Stocks.get().addListener(this);
        Stocks.get().refreshIfStale();
        Updates.get(getContext()).addListener(this);
        Updates.get(getContext()).refreshIfStale();
        refreshAmbilight();
        // At power on HueService starts the lamps some seconds after the home screen shows: read again soon.
        handler.postDelayed(this::refreshAmbilight, 4000);
        handler.postDelayed(this::refreshAmbilight, 10_000);
        try {
            connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), netCallback, handler);
        } catch (RuntimeException ignored) {
        }
        netChanged();
        data.refreshIfStale();
        recs.refreshIfStale();
        plex.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        carouselOn = false;
        syncCarousel();
        reload();
        updatePalette();
        updatePicks();
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    /** GPU layers or not (Ui.cachedLayers): read again on every resume, so the debug property applies without a restart. */
    private void applyLayerType() {
        int type = Ui.cachedLayers(getContext()) ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        if (type == layerType) return;
        layerType = type;
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(type, null);
    }

    private int layerType = -1;

    void pause() {
        if (!running) return;
        running = false;
        // While an app plays, keep only the three cards on screen (the next ones come back from disk).
        List<Recs.Pick> shown = new ArrayList<>();
        for (int i = 0; i < cardSlots(); i++) if (cardAt(i) != null) shown.add(cardAt(i));
        plex.trim(shown);
        cancelReorder(true);
        cancelGridMove();
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        carouselOn = true;      // running is false: this stops the clock and hides the bar
        syncCarousel();
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        plex.removeListener(this);
        Stocks.get().removeListener(this);
        Updates.get(getContext()).removeListener(this);
        try {
            connectivity.unregisterNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        cancelGridMove();
        zone = Z_SHELF;
        shelfIndex = 0;
        // Back from what was opened from Home a moment ago: the focus where it was (the shelf tile), or on the wide Plex
        // card (Plex puts the title just watched first in "continue watching").
        if (SystemClock.uptimeMillis() - openedAt < BACK_TO_OPENED_MS) {
            if (openedCard && !cards.isEmpty()) {
                zone = Z_CARDS;
                plexStart = 0;
                cardIndex = 0;
            } else if (openedKey != null) {
                for (int i = 0; i < shelf.size(); i++) if (openedKey.equals(shelf.get(i).key)) shelfIndex = i;
            }
        }
        drawerOpen = false;
        if (animated) drawer.target = 0;
        else drawer.snap(0);
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** What was opened from Home last (a shelf app, or a Plex card) and when: the focus goes back to it (reset). */
    private String openedKey;
    private boolean openedCard;
    private long openedAt = -BACK_TO_OPENED_MS;
    private static final long BACK_TO_OPENED_MS = 6 * 60 * 60_000L;

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas", "Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.WIFI, "wifi", vpnIntent() != null ? Ui.tr("Wi-Fi e VPN", "Wi-Fi and VPN") : "Wi-Fi", "wifi"));
        lightButtons.add(HomeApps.action(HomeApps.AMBILIGHT, "ambilight", "Ambilight", "ambilight"));
        if (showUpdateButton()) lightButtons.add(HomeApps.action(HomeApps.UPDATE, "update", Ui.tr("Atualização", "Update"), "sync"));
        lightButtons.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "settings", Ui.tr("Ajustes da TV", "TV settings"), "settings"));
        lightButtons.add(HomeApps.action(HomeApps.POWER, "power", Ui.tr("Energia", "Power"), "power"));
    }

    private static String[] tabs() {
        return Ui.fr ? French.TABS : Ui.ru ? TABS_RU : Ui.es ? TABS_ES : Ui.de ? TABS_DE : Ui.en ? TABS_EN : TABS;
    }

    private int tabCount() {
        return tabKinds.length;
    }

    /** The top of the screen shows tabs and cards (otherwise the date, the clock and the weather). */
    private boolean topOn() {
        return tabKinds.length > 0;
    }

    private String tabLabel(int i) {
        String kind = tabKinds[i];
        return "plex".equals(kind) ? "Plex" : tabs()[kind == null ? 0 : kind.equals("movie") ? 1 : 2];
    }

    /** A single tab (Plex) has no tab bar: its name is not shown and the focus never goes there. */
    private boolean tabsShown() {
        return tabKinds.length > 1;
    }

    /** "Good morning" (5 to 12 h), "Good afternoon" (12 to 18 h), "Good evening" (otherwise). */
    private static String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? Ui.tr("Bom dia", "Good morning")
                : hour >= 12 && hour < 18 ? Ui.tr("Boa tarde", "Good afternoon") : Ui.tr("Boa noite", "Good evening");
    }

    private boolean plexTab() {
        return tab < tabKinds.length && "plex".equals(tabKinds[tab]);
    }

    /** The list index shown on card slot i (0 = the wide card). */
    private int cardItem(int i) {
        if (plexTab() && !cards.isEmpty()) return (plexStart + i) % cards.size();
        return i;
    }

    private boolean carouselActive() {
        return running && plexTab() && zone != Z_CARDS && !drawerOpen && !reordering && cards.size() > 1;
    }

    /** Starts or stops the carousel's clock when it becomes (in)active: focus on the cards, drawer, tab, titles. */
    private void syncCarousel() {
        boolean on = carouselActive();
        if (on == carouselOn) return;
        carouselOn = on;
        if (on) {
            restartCarousel();
        } else {
            handler.removeCallbacks(carousel);
        }
    }

    private void restartCarousel() {
        handler.removeCallbacks(carousel);
        handler.postDelayed(carousel, CAROUSEL_MS);
    }

    /** The Plex carousel turns by one title (dir 1: the next comes in from the right). */
    private void turnCarousel(int dir) {
        int n = cards.size();
        if (n < 2) return;
        plexStart = ((plexStart + dir) % n + n) % n;
        slide.snap(dir);
        slide.target = 0;
        wake();
        updateCards();      // the background follows
    }

    private Recs.Pick cardAt(int i) {
        int item = cardItem(i);
        return item < cards.size() ? cards.get(item) : null;
    }

    /** How many card slots show a title. */
    private int cardSlots() {
        return Math.min(colors ? 1 : 3, cards.size());
    }

    private Backdrop backdropOf(Recs.Pick p) {
        return Plex.is(p) ? plex.backdrop(p) : recs.backdrop(p);
    }

    /** The VPN apps this button opens, the first one installed. */
    private static final String[] VPN_APPS = {"com.surfshark.vpnclient.android"};

    private android.content.Intent vpnIntent() {
        android.content.pm.PackageManager pm = getContext().getPackageManager();
        for (String pkg : VPN_APPS) {
            android.content.Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    /** The update button only shows when there is something to say: an update, one on its way, or why it stopped. */
    private boolean showUpdateButton() {
        if (!Updates.enabled()) return false;
        Updates u = Updates.get(getContext());
        return u.available() || u.running || "conflict".equals(u.nasState) || "error".equals(u.nasState);
    }

    /** A newer launcher-ui on GitHub, or none: the dot on the update button, which appears or goes. */
    @Override public void onUpdates() {
        Updates u = Updates.get(getContext());
        boolean shown = false;
        for (HomeApps.Item b : lightButtons) shown |= b.kind == HomeApps.UPDATE;
        if (shown != showUpdateButton()) {
            int focusedKind = zone == Z_TOP && topIndex >= B0 && topIndex - B0 < lightButtons.size() ? lightButtons.get(topIndex - B0).kind : -1;
            makeLightButtons();
            if (focusedKind >= 0) {
                int at = 0;
                for (int i = 0; i < lightButtons.size(); i++) if (lightButtons.get(i).kind == focusedKind) at = i;
                topIndex = B0 + at;
                retarget(true);
            }
            layoutLight();
            placeAmbiGlow();
        }
        String notice = u.notice;
        if (notice != null) {
            u.notice = null;
            showToast(notice);
        }
        statusDirty = true;
        wake();
    }

    @Override public void onStocks() {
        wake();
        layoutDrawer();     // the quotes card appears with the first prices
        drawerDirty = true;
    }

    @Override public void onPlex() {
        wake();
        updatePicks();
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        // Down to 890: the Editorial theme's captions and the Minimal theme's list and button go lower than the cards.
        measure(heroLayer, width, Math.round(890 * kk));
        measure(status, Math.round(1320 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(340 * kk));
        measure(tiles, width, Math.round(300 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(ambiGlow, Math.round(86 * kk), Math.round(86 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(730 * kk));
        place(tiles, 0, Math.round((colors ? 600 : 760) * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        placeAmbiGlow();
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lPill = Ui.text(Ui.semibold, 27 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        buttonLabel = Ui.text(Ui.medium, 20 * k, 0xF2FFFFFF);
        buttonLabel.setTextAlign(Paint.Align.CENTER);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        glassText();
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | slide.step(dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt);
        boolean drawerMoving = drawer.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (colors && (heroDirty || heroFading)) platter.invalidate();     // "Keep watching" follows the carousel
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty || drawerMoving) sheetLayer.invalidate();   // the grid follows the lift
        if ((drawerMoving && Math.min(drawer.value, drawer.target) < 1) || bgFading) sky.invalidate();   // the veil stops at 1
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            heroLayer.invalidate();     // the time is drawn there in both designs
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || toastFading || heroFading || bgFading || nightMoving
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** The lift (apps grid and drawer): the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d);
        float top = 1 - smoothstep(0, 0.32f, dc);
        float lift = -d * APPS_LIFT * k;
        show(heroLayer, top, lift);
        ambiGlow.setAlpha(top);
        ambiGlow.setTranslationY(lift);
        show(status, top, lift);
        // Colors: "Keep watching" (on the platter layer) fades with the top, or it would run into the apps grid.
        show(platter, colors ? top : 1, lift);
        show(tiles, 1, lift);
        // The drawer comes after the grid's last row; it is drawn resting in place.
        float dy = (drawerPage() - d) * APPS_LIFT * k;
        drawerLayer.setTranslationY(dy);
        drawerLayer.setAlpha(1);
        drawerLayer.setVisibility(d > 0.002f && drawerLayer.getTop() + dy < h ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(d > 0.002f ? VISIBLE : INVISIBLE);
    }

    private int gridRows() {
        return (others.size() + COLUMNS - 1) / COLUMNS;
    }

    /** The lift that shows grid row r: rows 0 to 2 fit under the shelf; further down, the page scrolls a row at a time. */
    private float gridPage(int r) {
        return 1 + Math.max(0, r - 2) * (GRID_H + GRID_ROW_GAP) / APPS_LIFT;
    }

    /** The lift that shows the drawer, resting in place, under the grid's last row. */
    private float drawerPage() {
        int rows = gridRows();
        if (rows == 0) return 1;
        float lastBottom = GRID_TOP + (rows - 1) * (GRID_H + GRID_ROW_GAP) + GRID_H;
        return Math.max(1, (lastBottom - 430) / APPS_LIFT);
    }

    private float gridColumnX(int col) {
        return gridLeft + (col * (GRID_W + GRID_GAP) + GRID_W / 2) * k;
    }

    private int nearestColumn(float x) {
        int col = Math.round((x - gridLeft - GRID_W / 2 * k) / ((GRID_W + GRID_GAP) * k));
        return Math.max(0, Math.min(COLUMNS - 1, col));
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        syncStyle();
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        crispLogos.clear();     // a tile restyled in place keeps its key
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        if (editorial) {
            layoutEditorialShelf();
            return;
        }
        if (colors) {
            layoutColorsShelf();
            return;
        }
        if (minimal) {
            layoutMinimalShelf();
            return;
        }
        if (hud) {
            layoutHudShelf();
            return;
        }
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        // More than 5 apps: narrower tiles keep their shape (shorter too), so logos and pictures aren't squeezed.
        float th = L_TILE_H * k * tw / (TILE_MAX_W * k);
        float width = n * tw + Math.max(0, n - 1) * gap;
        // Up to 5 apps: from the grid's first column, so the shelf and the grid line up; more: centered, narrower.
        gridLeft = (w - (COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP) * k) / 2f;
        float left = n <= COLUMNS ? gridLeft : (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        if (editorial) {
            drawEditorialDivider(c);
            return;
        }
        if (colors) {
            drawColorsContinue(c);
            return;
        }
        if (minimal) {
            drawMinimalShelfFrame(c);
            return;
        }
        if (hud) return;    // its header is on the tiles' layer (it shows the focused app's position)
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night; lighter, clearer glass in the glass theme.
        if (glass) {
            scene.liquid(c, platterRect, radius, 0xFF1B2D33, 0.5f, 0.03f, 34 * k, 28 * k, 0.8f, 1);
            return;
        }
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !topOn();
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = B0; i < B0 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        if (hud && !shelf.isEmpty()) drawHudShelfHeader(c);
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        if (editorial) {
            edPill(c, tileRects[i], tileFocus[i].value, l, it, it.kind == HomeApps.INPUT && !it.connected && !it.standby);
            return;
        }
        if (colors && glassIcons) {
            colorTile(c, tileRects[i], tileFocus[i].value, l, it, it.kind == HomeApps.INPUT && !it.connected && !it.standby);
            return;
        }
        if (minimal) {
            mnAppName(c, tileRects[i], tileFocus[i].value, l, it, it.kind == HomeApps.INPUT && !it.connected && !it.standby);
            return;
        }
        if (hud) {
            hudTile(c, tileRects[i], tileFocus[i].value, l, it, it.kind == HomeApps.INPUT && !it.connected && !it.standby);
            return;
        }
        if (glass && glassIcons) {
            glassTile(c, tileRects[i], tileFocus[i].value, l, it, it.kind == HomeApps.INPUT && !it.connected && !it.standby);
            return;
        }
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = it.key != null && it.key.startsWith("custom:") ? CustomTiles.style(it.key.substring(7)) : TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        glassFocus(c, cx, cy, bw, bh, s, r0, fc);
    }

    private LinearGradient glassSheen, glassRim;
    private float glassH;

    /**
     * Liquid Glass focus on a tile: a light sheen from the top edge and a bright rim that fades toward the bottom, as
     * if the tile were under a pane of glass catching the light. Drawn at rest size and scaled (shaders stay cached).
     */
    private void glassFocus(Canvas c, float cx, float cy, float bw, float bh, float s, float r0, float fc) {
        if (fc <= 0.01f) return;
        if (glassSheen == null || glassH != bh) {
            glassSheen = new LinearGradient(0, -bh / 2, 0, bh * 0.1f, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            glassRim = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xF2FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            glassH = bh;
        }
        int alpha = Math.round(255 * fc);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        sheen.setShader(glassSheen);
        sheen.setAlpha(alpha);
        c.drawRoundRect(tmp, r0, r0, sheen);
        sheen.setAlpha(255);
        // The rim sits just outside the tile, so it shows on white tiles too (on the dark glass around them).
        float out = 5 * k / s;
        tmp.inset(-out, -out);
        rim.setShader(glassRim);
        rim.setAlpha(alpha);
        rim.setStrokeWidth(3 * k / s);
        c.drawRoundRect(tmp, r0 + out, r0 + out, rim);
        rim.setStrokeWidth(2.5f * k);
        rim.setAlpha(255);
        rim.setShader(null);
        c.restore();
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    /** imageMatrix: `image` filling `cell` with its proportions kept, centered (cropped, never stretched). */
    private void coverMatrix(Bitmap image, RectF cell) {
        float scale = Math.max(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(cell.centerX() - image.getWidth() * scale / 2, cell.centerY() - image.getHeight() * scale / 2);
    }

    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            coverMatrix(image, cell);
            c.drawBitmap(image, imageMatrix, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        coverMatrix(image, cell);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected
                    && list.get(i).standby == drawerItems.get(i).standby && list.get(i).label.equals(drawerItems.get(i).label);
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Stocks.get().any();
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        float x = zone == GRID ? gridColumnX(gridIndex % COLUMNS) : shelf.isEmpty() ? 0 : tileRects[shelfIndex].centerX();
        drawerOpen = true;
        drawer.target = drawerPage();
        zone = Z_DRAWER;
        drawerIndex = nearest(drawerRects, x);
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    /** ↓ from the shelf: the screen lifts and the grid's tile under the focused app takes the focus. */
    private void openGrid() {
        if (others.isEmpty()) {
            openDrawer();
            return;
        }
        drawerOpen = true;
        zone = GRID;
        int col = shelf.isEmpty() ? 0 : nearestColumn(tileRects[shelfIndex].centerX());
        gridIndex = Math.min(others.size() - 1, col);
        drawer.target = gridPage(0);
    }

    /** Back to the home screen (↑ from the grid's first row, or Back), the focus on the shelf app above. */
    private void closeDrawer() {
        float x = zone == GRID ? gridColumnX(gridIndex % COLUMNS)
                : drawerIndex < drawerRects.length ? drawerRects[drawerIndex].centerX() : -1;
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && x >= 0) shelfIndex = nearest(tileRects, x);
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        if (editorial) {
            edCard(c, r);
            return;
        }
        if (minimal) {
            mnCard(c, r);
            return;
        }
        if (hud) {
            hudPanel(c, r, 0, 22 * k, 0, 0xE6141B26);
            return;
        }
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        rowTitle.setLetterSpacing(glass || editorial || minimal || hud ? 0.12f : 0);
        if (hud) rowTitle.setColor(HUD_CYAN);
        if (colors) rowTitle.setColor(0xFFFFFFFF);
        if (minimal) rowTitle.setColor(MN_LIME);
        if (editorial) rowTitle.setColor(edAccent());
        String inputsTitle = Ui.tr("Entradas", "Inputs"), shortcutsTitle = Ui.tr("Atalhos", "Shortcuts");
        if (glass || editorial || minimal || hud) {
            inputsTitle = inputsTitle.toUpperCase(Ui.locale());
            shortcutsTitle = shortcutsTitle.toUpperCase(Ui.locale());
        }
        if (drawerInputs > 0) c.drawText(inputsTitle, drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(shortcutsTitle, drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Stocks.get().any()) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        if (editorial) {
            edSmallTile(c, drawerRects[i], drawerFocus[i].value, it);
            return;
        }
        if (colors) {
            colorSmallTile(c, drawerRects[i], drawerFocus[i].value, it);
            return;
        }
        if (minimal) {
            mnSmallTile(c, drawerRects[i], drawerFocus[i].value, it, i < drawerInputs ? i : i - drawerInputs);
            return;
        }
        if (hud) {
            hudSmallTile(c, drawerRects[i], drawerFocus[i].value, it, i < drawerInputs ? i : i - drawerInputs);
            return;
        }
        if (glass) {
            glassSmallTile(c, drawerRects[i], drawerFocus[i].value, it);
            return;
        }
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        // Laid out once at the resting size, then grown with the tile: laid out again on every frame of the focus
        // animation, the cut moved from frame to frame and the name flickered.
        drawerLabel.setTextSize(23 * k);
        float room = base.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name (an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k, 23 * k * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.save();
        c.scale(s, s, cx, cy);
        c.drawText(label, cx, base.top + base.height() * 0.8f, drawerLabel);
        c.restore();
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "   Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                    : Ui.fr ? French.highLow(HomeData.degrees(wx.max), HomeData.degrees(wx.min), "   ")
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "   T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min);
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise);
            sunsetText = Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset);
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale;
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + Stocks.get().at + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    /** Power: a small menu under the button, Turn off (standby) and Restart (through the system's power menu). */
    private void powerMenu(RectF r) {
        int[] at = new int[2];
        getLocationInWindow(at);
        android.graphics.Rect anchor = new android.graphics.Rect(Math.round(r.left) + at[0], Math.round(r.top) + at[1],
                Math.round(r.right) + at[0], Math.round(r.bottom) + at[1]);
        String[] items = {Ui.tr("Desligar", "Turn off"), Ui.tr("Reiniciar", "Restart"), Ui.tr("Cancelar", "Cancel")};
        TvUi.OnPick power = which -> {
            if (which == 2) return;
            try {
                activity.startService(new android.content.Intent().setClassName(activity, RemoteKeyService.class.getName())
                        .setAction(which == 0 ? RemoteKeyService.ACTION_SLEEP : RemoteKeyService.ACTION_RESTART));
            } catch (RuntimeException e) {
                showToast(Ui.tr("Ative os botões do controle para usar isto.", "Turn on the remote buttons service to use this."));
            }
        };
        if (glass || editorial || colors || minimal || hud) {    // Back closes it: no Cancel
            List<GlassMenu.Entry> entries = new ArrayList<>();
            entries.add(new GlassMenu.Entry("power", items[0], Ui.tr("Modo de espera", "Standby")));
            entries.add(new GlassMenu.Entry("sync", items[1], null));
            if (hud) {
                HudMenu.show(activity, this, anchor, Ui.tr("Energia", "Power"), null, entries, Ui.tr("Confirmar", "Confirm"), power);
            } else if (minimal) {
                MinimalMenu.show(activity, this, Ui.tr("Energia", "Power"), null, Ui.tr("Até logo?", "See you soon?"), entries,
                        Ui.tr("Confirmar", "Confirm"), power);
            } else if (editorial) {
                EditorialMenu.show(activity, this, anchor, Ui.tr("Energia", "Power"), null, Ui.tr("Até logo?", "See you soon?"),
                        entries, Ui.tr("Confirmar", "Confirm"), night < 0.5f, power);
            } else {
                GlassMenu.show(activity, this, anchor, Ui.tr("Energia", "Power"), null, entries, Ui.tr("Confirmar", "Confirm"), power);
            }
            return;
        }
        TvUi.popover(activity, this, anchor, Ui.tr("Energia", "Power"), items, power, night < 0.5f);
    }

    /** The dot on the update button (the orange of the Plex badges). */
    private static final int UPDATE_DOT = 0xFFFF9F0A;

    /**
     * OK on the update button: what is new on GitHub and how to install it (on the PC: the TV can't build the app, and
     * the build carries this TV's config.properties), or that it is up to date. It also asks GitHub again.
     */
    private void updateMenu(RectF r) {
        Updates u = Updates.get(getContext());
        if (Updates.nas()) {
            u.askNasThen(() -> showUpdateMenu(u, r));
            return;
        }
        u.refreshSoon();
        showUpdateMenu(u, r);
    }

    private void showUpdateMenu(Updates u, RectF r) {
        if (!running) return;       // the home screen went away while the NAS answered
        int[] at = new int[2];
        getLocationInWindow(at);
        android.graphics.Rect anchor = new android.graphics.Rect(Math.round(r.left) + at[0], Math.round(r.top) + at[1],
                Math.round(r.right) + at[0], Math.round(r.bottom) + at[1]);
        if (Updates.nas()) {
            nasUpdateMenu(u, anchor);
            return;
        }
        String title, message;
        if (u.available()) {
            List<String> t = u.titles;
            int n = t.size();
            StringBuilder m = new StringBuilder();
            m.append(n).append(u.more ? "+ " : " ")
                    .append(n == 1 && !u.more ? Ui.tr("mudança no GitHub:", "change on GitHub:") : Ui.tr("mudanças no GitHub:", "changes on GitHub:"));
            for (int i = 0; i < Math.min(5, n); i++) m.append("\n•  ").append(t.get(i));
            if (n > 5 || u.more) m.append("\n•  …");
            m.append("\n\n").append(Ui.tr("A TV não consegue instalar sozinha: a atualização é feita no PC, que recompila a tela inicial com os seus ajustes (config.properties) e a reinstala. No PC, na pasta do projeto, rode:",
                    "The TV can't install it by itself: the update is made on the PC, which rebuilds the home screen with your settings (config.properties) and installs it again. On the PC, in the project folder, run:"))
                    .append("\n    bash launcher-ui/update.sh\n")
                    .append(Ui.tr("ou peça ao Claude: “faça a atualização”.", "or ask Claude: “do the update”."));
            title = Ui.tr("Atualização disponível", "Update available");
            message = m.toString();
        } else if (u.checkedAt > 0) {
            title = Ui.tr("Atualização", "Update");
            message = Ui.tr("Atualizado. Verificado às ", "Up to date. Checked at ")
                    + android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(u.checkedAt)) + ".";
        } else {
            title = Ui.tr("Atualização", "Update");
            message = Ui.tr("O GitHub ainda não respondeu. Tente de novo daqui a pouco.", "GitHub hasn't answered yet. Try again in a moment.");
        }
        TvUi.popover(activity, this, anchor, title, message, new String[] {"OK"}, which -> {}, night < 0.5f);
    }

    /**
     * The update button with the NAS (nas_url): what's new and "Update" (the NAS merges and builds, then Android asks to
     * confirm the install), or why the last try stopped (a conflict, a failed build), or that the NAS can't be reached.
     */
    private void nasUpdateMenu(Updates u, android.graphics.Rect anchor) {
        String update = Ui.tr("Atualizar", "Update now"), later = Ui.tr("Mais tarde", "Later");
        String title, message;
        String[] items = {"OK"};
        boolean offer = false;
        if (u.running) {
            title = Ui.tr("Atualização", "Update");
            message = Ui.tr("Atualização em andamento: ", "Update in progress: ") + (u.nasMessage.isEmpty() ? "…" : u.nasMessage);
        } else if ("conflict".equals(u.nasState)) {
            title = Ui.tr("Atualização bloqueada", "Update blocked");
            StringBuilder m = new StringBuilder(Ui.tr("O repositório de origem mudou os mesmos trechos que as suas personalizações:",
                    "The original repository changed the same lines as your customizations:"));
            for (int i = 0; i < Math.min(5, u.nasFiles.size()); i++) m.append("\n•  ").append(u.nasFiles.get(i));
            m.append("\n\n").append(Ui.tr("Nada mudou. Peça ao Claude: “faça a atualização”.", "Nothing changed. Ask Claude: “do the update”."));
            message = m.toString();
            items = new String[] {Ui.tr("Tentar de novo", "Try again"), "OK"};
            offer = true;
        } else if ("error".equals(u.nasState)) {
            title = Ui.tr("Atualização impossível", "Update failed");
            message = u.nasMessage;
            items = new String[] {Ui.tr("Tentar de novo", "Try again"), "OK"};
            offer = true;
        } else if (u.available()) {
            title = Ui.tr("Atualização disponível", "Update available");
            List<String> t = u.titles;
            int n = t.size();
            StringBuilder m = new StringBuilder();
            m.append(n).append(" ").append(n == 1 ? Ui.tr("novidade:", "change:") : Ui.tr("novidades:", "changes:"));
            for (int i = 0; i < Math.min(5, n); i++) m.append("\n•  ").append(t.get(i));
            if (n > 5) m.append("\n•  …");
            m.append("\n\n").append(Ui.tr("O NAS prepara a nova versão e a TV pede para confirmar a instalação. Depois, a tela inicial recomeça.",
                    "The NAS builds the new version, then the TV asks you to confirm the install. The home screen then starts again."));
            message = m.toString();
            items = new String[] {update, later};
            offer = true;
        } else if (u.unreachable) {
            title = Ui.tr("Atualização", "Update");
            message = Ui.tr("O NAS não responde (", "The NAS doesn't answer (") + Config.NAS_URL
                    + Ui.tr("). Ele está ligado, com o projeto cytv rodando no Docker?", "). Is it on, with the cytv project running in Docker?");
        } else if (u.checkedAt > 0) {
            title = Ui.tr("Atualização", "Update");
            message = Ui.tr("Atualizado. Verificado às ", "Up to date. Checked at ")
                    + android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(u.checkedAt)) + ".";
        } else {
            title = Ui.tr("Atualização", "Update");
            message = Ui.tr("O NAS ainda não respondeu. Tente de novo daqui a pouco.", "The NAS hasn't answered yet. Try again in a moment.");
        }
        boolean start = offer;
        TvUi.popover(activity, this, anchor, title, message, items, which -> {
            if (start && which == 0) u.startNasUpdate();
        }, night < 0.5f);
    }

    /** The VPN app when there is one (the padlock on the Wi-Fi button says whether it is on), else the Wi-Fi settings. */
    private void openWifi() {
        android.content.Intent vpn = vpnIntent();
        if (vpn != null) {
            try {
                activity.startActivity(vpn);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        try {
            activity.startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            showToast(Ui.tr("Esta TV não abre os ajustes de Wi-Fi.", "This TV can't open the Wi-Fi settings."));
        }
    }

    /** OK on Sources in the Colors theme: the quick settings panel (Wi-Fi, Ambilight, standby, the sources). */
    private void quickPanel(List<HomeApps.Item> inputs, List<GlassMenu.Entry> entries, TvUi.OnPick open) {
        List<QuickPanel.Tile> tiles = new ArrayList<>();
        boolean online = netWifi || netOther;
        tiles.add(new QuickPanel.Tile("wifi", netVpn ? "Wi-Fi · VPN" : "Wi-Fi", online ? Ui.tr("Conectado", "Connected")
                : Ui.tr("Desconectado", "Disconnected"), online, this::openWifi));
        tiles.add(new QuickPanel.Tile("monitor", "Ambilight", ambiOn ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off"), ambiOn,
                this::toggleAmbilight));
        tiles.add(new QuickPanel.Tile("power", Ui.tr("Espera", "Standby"), Ui.tr("Desligar a TV", "Turn off the TV"), false, () -> {
            try {
                activity.startService(new android.content.Intent().setClassName(activity, RemoteKeyService.class.getName())
                        .setAction(RemoteKeyService.ACTION_SLEEP));
            } catch (RuntimeException e) {
                showToast(Ui.tr("Ative os botões do controle para usar isto.", "Turn on the remote buttons service to use this."));
            }
        }));
        HomeData.Weather wx = data.weather;
        String corner = clockText + (Prefs.weather() && !Double.isNaN(wx.temp) ? " · " + HomeData.degrees(wx.temp) : "");
        QuickPanel.show(activity, this, corner, tiles, entries, open, () -> openPhilips(android.provider.Settings.ACTION_SETTINGS));
    }

    /** "PlayStation 5  ·  HDMI 1  ·  In standby": the device's name, its port when the name isn't the port's, its state. */
    private static String inputLine(HomeApps.Item in) {
        String port = "HDMI " + in.number;
        String line = in.label.equalsIgnoreCase(port) ? in.label : in.label + "  ·  " + port;
        if (in.connected) return line + "  ·  " + Ui.tr("Conectado", "Connected");
        if (in.standby) return line + "  ·  " + Ui.tr("Em espera", "In standby");
        return line;
    }

    /** OK on Sources: the same small menu as Power, with Live TV and every HDMI input (the ones with a signal said so). */
    private void sourcesMenu(RectF r) {
        // Only what can show something: Live TV and the inputs with a device on or asleep (not the empty ports).
        List<HomeApps.Item> inputs = new ArrayList<>();
        for (HomeApps.Item in : HomeApps.allInputs(activity)) {
            if (in.kind != HomeApps.INPUT || in.connected || in.standby) inputs.add(in);
        }
        if (inputs.isEmpty()) {
            showToast(Ui.tr("Nenhuma fonte encontrada.", "No sources found."));
            return;
        }
        int[] at = new int[2];
        getLocationInWindow(at);
        android.graphics.Rect anchor = new android.graphics.Rect(Math.round(r.left) + at[0], Math.round(r.top) + at[1],
                Math.round(r.right) + at[0], Math.round(r.bottom) + at[1]);
        if (glass || editorial || colors || minimal || hud) {
            List<GlassMenu.Entry> entries = new ArrayList<>();
            for (HomeApps.Item in : inputs) {
                boolean hdmi = in.kind == HomeApps.INPUT;
                GlassMenu.Entry e = new GlassMenu.Entry(hdmi ? Icons.forSymbol(in.symbol) : "broadcast", in.label,
                        hdmi ? "HDMI " + in.number : Ui.tr("Canais de TV", "TV channels"));
                if (hdmi && in.connected) e.state(Ui.tr("Conectado", "Connected"), GlassMenu.DOT_ON);
                else if (hdmi && in.standby) e.state(Ui.tr("Em espera", "In standby"), GlassMenu.DOT_ASLEEP);
                entries.add(e);
            }
            String count = inputs.size() + " " + (inputs.size() == 1 ? Ui.tr("disponível", "available, one") : Ui.tr("disponíveis", "available"));
            TvUi.OnPick open = which -> {
                HomeApps.Item in = inputs.get(which);
                if (!HomeApps.open(activity, in, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()))) {
                    showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + in.label + ".");
                }
            };
            if (colors) {
                quickPanel(inputs, entries, open);
            } else if (minimal) {
                MinimalMenu.show(activity, this, Ui.tr("Fontes", "Sources"), two(inputs.size()), Ui.tr("Mudar de entrada", "Change input"),
                        entries, Ui.tr("Mudar", "Switch"), open);
            } else if (hud) {
                String n = inputs.size() + " " + (inputs.size() == 1 ? Ui.tr("entrada", "entry") : Ui.tr("entradas", "entries"));
                HudMenu.show(activity, this, anchor, Ui.tr("Fontes", "Sources"), n, entries, Ui.tr("Mudar", "Switch"), open);
            } else if (editorial) {
                String n = inputs.size() + " " + (inputs.size() == 1 ? Ui.tr("entrada", "entry") : Ui.tr("entradas", "entries"));
                EditorialMenu.show(activity, this, anchor, Ui.tr("Fontes", "Sources"), n, Ui.tr("O que assistir?", "What to watch?"),
                        entries, Ui.tr("Mudar", "Switch"), night < 0.5f, open);
            } else {
                GlassMenu.show(activity, this, anchor, Ui.tr("Fontes", "Sources"), count, entries, Ui.tr("Mudar", "Switch"), open);
            }
            return;
        }
        String[] items = new String[inputs.size() + 1];
        for (int i = 0; i < inputs.size(); i++) {
            HomeApps.Item in = inputs.get(i);
            items[i] = in.kind == HomeApps.INPUT ? inputLine(in) : in.label;
        }
        items[inputs.size()] = Ui.tr("Cancelar", "Cancel");
        TvUi.popover(activity, this, anchor, Ui.tr("Entradas", "Inputs"), items, which -> {
            if (which >= inputs.size()) return;
            HomeApps.Item in = inputs.get(which);
            if (!HomeApps.open(activity, in, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()))) {
                showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + in.label + ".");
            }
        }, night < 0.5f);
    }

    /** Stocks and ETFs: name, last price and the change since the previous close (green up, red down). */
    private void drawPrices(Canvas c, RectF r) {
        Stocks st = Stocks.get();
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Bolsa · 24 h", "Markets · 24 h"), x, r.top + 54 * k, cardTitle);
        String stale = HomeData.staleness(st.at, 30 * 60_000L);
        if (!stale.isEmpty()) c.drawText(stale, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        for (int i = 0; i < Stocks.LIST.length; i++) {
            float base = r.top + (104 + 44 * i) * k, mid = base - cap / 2;
            fitPaint.set(cardText);
            c.drawText(TextUtils.ellipsize(Stocks.LIST[i][1], fitPaint, right - x - 330 * k, TextUtils.TruncateAt.END).toString(), x, base, cardText);
            c.drawText(st.price(i), right - 128 * k, base, pricePaint);
            double change = st.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 112 * k, mid - 16 * k, right, mid + 16 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 16 * k, 16 * k, fill);
            changePaint.setColor(color);
            c.drawText(st.change(i), tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, zone == Z_DRAWER ? drawerIndex : -1, snap);
        gridDirty |= aim(gridFocus, zone == GRID ? gridIndex : -1, snap) || snap;
        boolean heroChanged = heroFocus.target != (zone == Z_HERO ? 1 : 0);
        heroFocus.target = zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
        syncCarousel();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - Apps grid (under the shelf)

    private void setOthers(List<HomeApps.Item> list) {
        if (gridMoving) {   // the list changed under the move: the new one wins
            gridMoving = false;
            gridOrigin = null;
        }
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.34f, 0.74f, 0);
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        if (zone == GRID && list.isEmpty()) closeDrawer();
        if (zone == GRID) drawer.target = gridPage(gridIndex / COLUMNS);
        else if (zone == Z_DRAWER || zone == WIDGETS) drawer.target = drawerPage();
        retarget(true);
        gridDirty = motionDirty = true;
    }

    /** The other apps, 5 columns under the shelf, following the lift (only the rows on screen are drawn). */
    private void drawSheet(Canvas c) {
        int n = others.size();
        if (n == 0) return;
        float lift = drawer.value * APPS_LIFT * k, step = (GRID_H + GRID_ROW_GAP) * k;
        int focused = zone == GRID ? gridIndex : -1;
        // The focused tile last: it grows over its neighbors without being covered by them.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((pass == 1) != (i == focused)) continue;
                float y = GRID_TOP * k + (i / COLUMNS) * step - lift;
                if (y > h || y + step < 0) continue;
                float x = gridLeft + (i % COLUMNS) * (GRID_W + GRID_GAP) * k;
                gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
                // No name under the tile: the TV art carries it (and the shortcuts' cards too).
                // Being moved: a little bigger than focused.
                drawGridTile(c, others.get(i), gridCell, gridFocus[i].value * (gridMoving && i == focused ? 1.4f : 1));
                if (i == focused) focusedCell.set(cell);
            }
        }
    }

    /** A grid app: the shelf's tile (its TV art, edge to edge) and focus (20% bigger, with a deeper shadow). */
    private void drawGridTile(Canvas c, HomeApps.Item it, RectF base, float f) {
        if (minimal) {
            mnGridTile(c, base, Math.min(f, 1), Math.max(0, f - 1) * 2.5f, it, others.indexOf(it));
            cell.set(base);
            return;
        }
        if (hud) {
            hudTile(c, base, Math.min(f, 1), Math.max(0, f - 1) * 2.5f, it, false);
            cell.set(base);
            return;
        }
        if (colors && glassIcons) {
            colorTile(c, base, Math.min(f, 1), Math.max(0, f - 1) * 2.5f, it, false);
            float s = 1 + 0.08f * Math.min(f, 1);
            cell.set(base.centerX() - base.width() * s / 2, base.centerY() - base.height() * s / 2,
                    base.centerX() + base.width() * s / 2, base.centerY() + base.height() * s / 2);
            return;
        }
        if (editorial) {
            edGridTile(c, base, Math.min(f, 1), Math.max(0, f - 1) * 2.5f, it);
            float s = 1 + 0.04f * Math.min(f, 1);
            cell.set(base.centerX() - base.width() * s / 2, base.centerY() - base.height() * s / 2,
                    base.centerX() + base.width() * s / 2, base.centerY() + base.height() * s / 2);
            return;
        }
        if (glass && glassIcons) {
            glassTile(c, base, Math.min(f, 1), Math.max(0, f - 1) * 2.5f, it, false);
            float s = 1 + 0.06f * Math.min(f, 1);
            cell.set(base.centerX() - base.width() * s / 2, base.centerY() - base.height() * s / 2,
                    base.centerX() + base.width() * s / 2, base.centerY() + base.height() * s / 2);
            return;
        }
        float fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.3f + 0.3f * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        if (it.tile != null) {
            roundImage(c, it.tile, cell, r0 * s);
        } else {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r0 * s, r0 * s, tileFill);
        }
        glassFocus(c, cx, cy, bw, bh, s, r0, fc);
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        if (carouselOn) restartCarousel();     // the carousel turns only after 12 s without a key
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        if (gridMoving) return gridMoveKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): its menu (move, logo and color). A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canHold();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        appMenu();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (zone == GRID || zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (zone == GRID) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (others.isEmpty() && drawerItems.isEmpty()) return;
                    openGrid();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0 && !others.isEmpty()) {
                    // ↑ from the drawer: the grid's last row, in the same column
                    int last = gridRows() - 1;
                    gridIndex = Math.min(others.size() - 1, last * COLUMNS + nearestColumn(drawerRects[drawerIndex].centerX()));
                    zone = GRID;
                    drawer.target = gridPage(last);
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate < 0 || candidate >= n || candidate / COLUMNS != r) return;
            gridIndex = candidate;
        } else if (dy < 0 && r == 0) {
            closeDrawer();      // ↑ from the first row: back to the home screen
        } else if (dy < 0) {
            gridIndex = (r - 1) * COLUMNS + col;
        } else if (r + 1 < gridRows()) {
            gridIndex = Math.min(n - 1, (r + 1) * COLUMNS + col);
        } else if (!drawerItems.isEmpty()) {
            openDrawer();       // ↓ from the last row: the drawer (inputs, shortcuts, weather)
        } else {
            return;
        }
        if (zone == GRID) drawer.target = gridPage(gridIndex / COLUMNS);
        retarget(false);
        click.move();
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        if (gridMoving) {
            dropGridMove();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (zone == WIDGETS) return;   // a widget is only read
        if (zone == GRID) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null) return;
            click.open();
            DetailActivity.open(getContext(), p.kind, p.id);
            return;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < B0) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - B0);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardAt(cardIndex);
            if (p == null) return;
            click.open();
            if (Plex.is(p)) {
                if (!plex.open(activity, p)) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + "Plex.");
                else {
                    openedCard = true;
                    openedAt = SystemClock.uptimeMillis();
                }
                return;
            }
            DetailActivity.open(getContext(), p.kind, p.id);
            return;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openGrid();
            retarget(false);
            return;
        }
        if (it.kind == HomeApps.POWER) {
            powerMenu(r);
            return;
        }
        if (it.kind == HomeApps.AMBILIGHT) {
            toggleAmbilight();
            return;
        }
        if (it.kind == HomeApps.UPDATE) {
            updateMenu(r);
            return;
        }
        if (it.kind == HomeApps.WIFI) {
            openWifi();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            sourcesMenu(r);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
        else if (zone == Z_SHELF) {
            openedKey = it.key;
            openedCard = false;
            openedAt = SystemClock.uptimeMillis();
        }
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        return movable(shelf.get(shelfIndex));
    }

    /** An app, or an HDMI input put on the shelf ("hdmi:N"). */
    private static boolean movable(HomeApps.Item it) {
        return it.pkg != null && (it.kind == HomeApps.APP || it.pkg.startsWith(HomeApps.HDMI_PREFIX));
    }

    /** The app under the focus that OK can be held on: a shelf app or one in "All Apps". */
    private HomeApps.Item heldApp() {
        HomeApps.Item it = null;
        if (zone == GRID) {
            if (gridIndex < others.size()) it = others.get(gridIndex);
        } else if (zone == Z_SHELF && shelfIndex < shelf.size()) {
            it = shelf.get(shelfIndex);
            return it != null && movable(it) ? it : null;
        }
        return it != null && it.kind == HomeApps.APP && it.pkg != null ? it : null;
    }

    private boolean canHold() {
        return heldApp() != null;
    }

    /**
     * OK held on an app: a small menu drops from its card: Move, Add to / Remove from the shelf, Logo and color and the
     * app's Android settings (stop it, clear its cache). Back closes it. An HDMI tile has only Move and Remove.
     */
    private void appMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        HomeApps.Item it = heldApp();
        if (reordering || it == null) return;
        okHeld = true;      // the release of this press opens nothing
        boolean inGrid = zone == GRID, movable = inGrid ? gridMovable(gridIndex) : canReorder();
        boolean input = it.kind != HomeApps.APP;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (movable) {
            labels.add(Ui.tr("Mover", "Move"));
            actions.add(inGrid ? this::startGridMove : this::startReorder);
        }
        labels.add(inGrid ? Ui.tr("Adicionar à estante", "Add to shelf") : Ui.tr("Tirar da estante", "Remove from shelf"));
        actions.add(() -> putOnShelf(it.pkg, inGrid));
        if (!input) {
            labels.add(Ui.tr("Logo e cor", "Logo and color"));
            actions.add(() -> CustomTiles.edit(activity, it.pkg, it.label, this::reload));
            labels.add(Ui.tr("Configurações do app", "App settings"));
            actions.add(() -> {
                try {
                    activity.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + it.pkg)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    showToast(Ui.tr("Esta TV não abre os ajustes do app.", "This TV can't open the app's settings."));
                }
            });
        }
        RectF r = inGrid ? focusedCell : tileRects[shelfIndex];
        int[] at = new int[2];
        getLocationInWindow(at);
        android.graphics.Rect anchor = new android.graphics.Rect(Math.round(r.left) + at[0], Math.round(r.top) + at[1],
                Math.round(r.right) + at[0], Math.round(r.bottom) + at[1]);
        click.open();
        if (glass || editorial || colors || minimal || hud) {
            List<GlassMenu.Entry> entries = new ArrayList<>();
            for (String label : labels) {
                String icon = label.equals(Ui.tr("Mover", "Move")) ? "move"
                        : label.equals(Ui.tr("Adicionar à estante", "Add to shelf")) ? "plus"
                        : label.equals(Ui.tr("Tirar da estante", "Remove from shelf")) ? "minus"
                        : label.equals(Ui.tr("Logo e cor", "Logo and color")) ? "sparkles" : "sliders";
                entries.add(new GlassMenu.Entry(icon, label, null));
            }
            if (hud) {
                HudMenu.show(activity, this, anchor, it.label, null, entries, Ui.tr("Escolher", "Choose"), which -> actions.get(which).run());
            } else if (minimal) {
                MinimalMenu.show(activity, this, Ui.tr("Aplicativo", "Application"), null, it.label, entries,
                        Ui.tr("Escolher", "Choose"), which -> actions.get(which).run());
            } else if (editorial) {
                EditorialMenu.show(activity, this, anchor, Ui.tr("Aplicativo", "Application"), null, it.label, entries,
                        Ui.tr("Escolher", "Choose"), night < 0.5f, which -> actions.get(which).run());
            } else {
                GlassMenu.show(activity, this, anchor, it.label, null, entries, Ui.tr("Escolher", "Choose"), which -> actions.get(which).run());
            }
            return;
        }
        TvUi.popover(activity, this, anchor, it.label, labels.toArray(new String[0]), which -> actions.get(which).run(), night < 0.5f);
    }

    // MARK: - Moving apps in the grid

    /** An app of the grid between "Inputs" (always first) and this app's settings (always last). */
    private boolean gridMovable(int i) {
        if (i < 0 || i >= others.size() || others.get(i).kind != HomeApps.APP) return false;
        int apps = 0;
        for (HomeApps.Item it : others) if (it.kind == HomeApps.APP) apps++;
        return apps > 1;
    }

    private void startGridMove() {
        if (gridMoving || zone != GRID || !gridMovable(gridIndex)) return;
        wake();
        gridMoving = okHeld = true;
        gridOrigin = new ArrayList<>(others);
        gridDirty = motionDirty = true;
        click.open();
        showToast(Ui.tr("Setas para mover · OK para soltar · Voltar para cancelar",
                "Arrows to move · OK to drop · Back to cancel"));
    }

    /** Keys while moving a grid app: the arrows carry it, OK keeps the order, Back undoes it. */
    private boolean gridMoveKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) shiftGridMove(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftGridMove(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) shiftGridMove(-COLUMNS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) shiftGridMove(COLUMNS);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that opened the menu may still be held: only a new press drops the app.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropGridMove();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        if (gridOrigin != null) setOthersOrder(gridOrigin);
                        cancelGridMove();
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Moves the lifted app `d` places (±1 along the row, ±COLUMNS up or down); "Inputs" and our settings stay put. */
    private void shiftGridMove(int d) {
        int i = gridIndex, j = i + d;
        if (j < 0 || j >= others.size() || others.get(j).kind != HomeApps.APP) return;
        if (Math.abs(d) == 1 && j / COLUMNS != i / COLUMNS) return;
        List<HomeApps.Item> list = new ArrayList<>(others);
        list.add(j, list.remove(i));
        Spring f = gridFocus[i];
        gridFocus[i] = gridFocus[j];
        gridFocus[j] = f;
        others = list;
        gridIndex = j;
        drawer.target = gridPage(gridIndex / COLUMNS);
        gridDirty = motionDirty = true;
        wake();
        retarget(false);
        click.move();
    }

    /** Puts back an order (Back) without rebuilding the springs. */
    private void setOthersOrder(List<HomeApps.Item> list) {
        HomeApps.Item lifted = others.get(gridIndex);
        others = list;
        gridIndex = Math.max(0, list.indexOf(lifted));
        drawer.target = gridPage(gridIndex / COLUMNS);
        retarget(true);
    }

    private void cancelGridMove() {
        if (!gridMoving) return;
        gridMoving = false;
        gridOrigin = null;
        gridDirty = motionDirty = true;
        retarget(false);
    }

    /** OK: the grid keeps this order (saved by package; "Inputs" and our settings are left out). */
    private void dropGridMove() {
        if (!gridMoving) return;
        boolean changed = !others.equals(gridOrigin);
        cancelGridMove();
        click.open();
        if (!changed) return;
        List<String> order = new ArrayList<>();
        for (HomeApps.Item it : others) if (it.kind == HomeApps.APP && it.pkg != null) order.add(it.pkg);
        Prefs.setGridOrder(order);
    }

    /** Adds the app at the end of the shelf, or takes it off (it goes back to the grid). */
    private void putOnShelf(String pkg, boolean on) {
        if (on && shelf.size() >= HomeApps.MAX_SHELF) {
            showToast(Ui.tr("A estante está cheia.", "The shelf is full."));
            return;
        }
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            list.remove(pkg);
            if (on) list.add(pkg);
            handler.post(() -> {
                Prefs.setShelf(list);
                reload();
            });
        });
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → para mover · OK para soltar · Voltar para cancelar",
                "← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        for (int i = 0; tabsShown() && i < tabCount(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < lightButtons.size(); i++) out.add(a11yId(Z_TOP, B0 + i));
        for (int i = 0; i < cardSlots(); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Stocks.get().any()) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i >= B0) return label(lightButtons, i - B0);
                if (i >= tabCount()) return "";
                return tabLabel(i) + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de ", " of ") + tabCount();
            case Z_CARDS: return pickText(cardAt(i));
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.tr("Movendo ", "Moving ", "Moviendo ") + label(shelf, i)
                            + Ui.tr(", posição ", ", position ", ", posición ") + (i + 1) + Ui.tr(" de ", " of ") + shelf.size();
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (i >= others.size()) return false;
                float x = gridLeft + (i % COLUMNS) * (GRID_W + GRID_GAP) * k;
                float y = (GRID_TOP + (i / COLUMNS) * (GRID_H + GRID_ROW_GAP) - drawer.target * APPS_LIFT) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= B0 + lightButtons.size() || (i < B0 && i >= tabCount())) return false;
                a11yRect.set(topRects[i]);
                dy = (i < B0 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= cardSlots()) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = (drawerPage() - drawer.target) * APPS_LIFT * k;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = (drawerPage() - drawer.target) * APPS_LIFT * k;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        switch (zone) {
            case GRID: return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cardSlots() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Stocks.get().any()) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (reordering) return;
        switch (group) {
            case GRID:
                if (i >= others.size()) return;
                drawerOpen = true;
                gridIndex = i;
                drawer.target = gridPage(i / COLUMNS);
                break;
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                drawer.target = drawerPage();
                break;
            case Z_TOP:
                if (i >= B0 + lightButtons.size() || (i < B0 && i >= tabCount())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < B0) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cardSlots()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (Plex.is(p)) return s.append(", ").append(p.meta).append(". ").append(Ui.tr("Continuar no Plex", "Continue on Plex")).toString();
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("Sem dados do clima agora.", "No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
                .append(Ui.tr(". Máxima ", ". High ")).append(HomeData.degrees(wx.max))
                .append(Ui.tr(", mínima ", ", low ")).append(HomeData.degrees(wx.min)).append('.');
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) s.append(Ui.tr(" Próximas horas: ", " Next hours: "));
        for (int i = 0; i < hours; i++) {
            s.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i))).append(i < hours - 1 ? ", " : ".");
        }
        if (wx.sunrise != 0) s.append(Ui.tr(" Nascer do sol ", " Sunrise ")).append(Ui.time(wx.sunrise)).append('.');
        if (wx.sunset != 0) s.append(Ui.tr(" Pôr do sol ", " Sunset ")).append(Ui.time(wx.sunset)).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Cotações", "Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < tabCount(); i++) {
            float tw = Math.max(lTabOn.measureText(tabLabel(i)), lTab.measureText(tabLabel(i)));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        // Glass capsules with an icon and a name (Inputs, Wi-Fi, Settings), right-aligned.
        float b = L_BUTTON * k, right = (CONTENT_R - (glass ? 10 : 0)) * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            float w = pillWidth(i);
            topRects[B0 + i].set(right - w, cy - b / 2, right, cy + b / 2);
            // Glass: all in one capsule, Power apart after a thin line.
            float gap = !glass ? L_BUTTON_GAP : lightButtons.get(i).kind == HomeApps.POWER ? 30 : 6;
            right -= w + gap * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        if (editorial) layoutEditorial();
        if (colors) layoutColors();
        if (minimal) layoutMinimal();
        if (hud) layoutHud();
        heroDirty = statusDirty = true;
    }

    /**
     * The cards of the current tab (Plex: what is being watched; Explore, Movies, Shows: TMDB picks) and the background
     * taken from the first one (in the Plex tab, from the focused one).
     */
    private void updateCards() {
        String kind = tab < tabKinds.length ? tabKinds[tab] : null;
        List<Recs.Pick> list = "plex".equals(kind) ? plex.items() : recs.now(kind);
        if (!samePicks(list, cards)) {
            cards = list;
            plexStart = 0;
            cardIndex = Math.min(cardIndex, Math.max(0, cardSlots() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        // Plex: only what the carousel shows now and the next title (memory: each built image is ~4 MB).
        if (plexTab()) {
            for (int i = 0; i < Math.min(4, cards.size()); i++) {
                Recs.Pick p = cards.get((plexStart + i) % cards.size());
                plex.backdrop(p);
                plex.poster(p);
            }
        }
        Recs.Pick shown = cards.isEmpty() ? null : plexTab() ? cardAt(zone == Z_CARDS ? cardIndex : 0) : cards.get(0);
        Backdrop b = shown == null ? null : backdropOf(shown);
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
        syncCarousel();
    }

    private void selectTab(int index) {
        if (index == tab || index >= tabCount()) return;
        tab = index;
        if (plexTab()) plex.refreshIfStale();
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        if (editorial) {    // paper: one flat color, nothing to blend
            c.drawColor(edPaper());
            return;
        }
        if (colors) {
            drawColorsSky(c);
            return;
        }
        if (minimal) {
            c.drawColor(0xFF000000);
            return;
        }
        if (hud) {
            drawHudSky(c);
            return;
        }
        if (glass) {
            drawGlassSky(c);
            return;
        }
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Apps grid: the background darkens, as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.45f, 0.5f) * Ui.clamp(drawer.value), 0xFF000000);
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (editorial) {
            drawEditorialTop(c);
            return;
        }
        if (colors) {
            drawColorsTop(c);
            return;
        }
        if (minimal) {
            drawMinimalTop(c);
            return;
        }
        if (hud) {
            drawHudTop(c);
            return;
        }
        if (!topOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        // The time (large), and next to it the greeting for this part of the day and the date.
        float tx = MARGIN * k - Ui.leftBearing(lTitle, clockText);
        c.drawText(clockText, tx, L_TITLE_BASE * k, lTitle);
        float gx = tx + lTitle.measureText(clockText) + 30 * k;
        c.drawText(greeting(), gx, (L_TITLE_BASE - 38) * k, lTabOn);
        c.drawText(dateText, gx, L_TITLE_BASE * k, lTab);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; tabsShown() && i < tabCount(); i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabLabel(i), r.centerX() - p.measureText(tabLabel(i)) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (plexTab() && cards.isEmpty()) {
            // Nothing to show: a short line where the cards go (the tabs stay a ← → away).
            int st = plex.state();
            if (st != Plex.LOADING) {
                String msg = st == Plex.UNREACHABLE ? Ui.tr("Servidor Plex inacessível", "Plex server unreachable")
                        : Ui.tr("Nada para continuar no Plex", "Nothing to resume on Plex");
                c.drawText(msg, MARGIN * k - Ui.leftBearing(lCardTitle, msg), (L_CARDS_TOP + L_CARD_H / 2) * k, lCardTitle);
            }
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        boolean blank = plexTab();      // Plex: no gray placeholders for missing cards
        for (int i = 0; i < 3; i++) if (i != top && !(blank && i >= cardSlots())) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (plexTab() && cards.size() > 1) drawCarouselDots(c);
        drawWeatherSummary(c);
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + (Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.fr ? French.highLow(HomeData.degrees(wx.max), HomeData.degrees(wx.min), "  ")
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min));
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private final RectF slideFrom = new RectF(), slideAt = new RectF();

    /** Where card slot `i` is; -1 and 3 are just off the row, left and right (where cards come from and go to). */
    private void slotRect(int i, RectF out) {
        float g = L_CARD_GAP * k;
        if (i < 0) {
            out.set(cardRects[0]);
            out.offset(-(cardRects[0].width() + g), 0);
        } else if (i > 2) {
            out.set(cardRects[2]);
            out.offset(cardRects[2].width() + g, 0);
        } else {
            out.set(cardRects[i]);
        }
    }

    /**
     * Carousel turning, as on Netflix: every card slides from the slot it was in to its new one (growing or shrinking
     * on the way), the one leaving goes off the row. No fading, no offscreen layers: just moving rectangles.
     */
    private void drawLightCard(Canvas c, int i) {
        float sl = plexTab() ? slide.value : 0;
        if (sl == 0 || cards.size() < 2) {
            drawLightCardAt(c, i, cardRects[i], cardAt(i), false);
            return;
        }
        int dir = sl > 0 ? 1 : -1;
        float t = Math.abs(sl);         // 1 when the turn starts, 0 when it is done
        float e = t * t * (3 - 2 * t);  // eased
        slotRect(i + dir, slideFrom);
        lerp(cardRects[i], slideFrom, e, slideAt);
        drawLightCardAt(c, i, slideAt, cardAt(i), false);
        // The card leaving the row: the old first one going left, or the old last one going right.
        int n = cards.size();
        if (dir > 0 && i == 0) {
            slotRect(-1, slideFrom);
            lerp(slideFrom, cardRects[0], e, slideAt);
            drawLightCardAt(c, 0, slideAt, cards.get(((plexStart - 1) % n + n) % n), true);
        } else if (dir < 0 && i == 2 && n > 3) {
            slotRect(3, slideFrom);
            lerp(slideFrom, cardRects[2], e, slideAt);
            drawLightCardAt(c, 2, slideAt, cards.get((plexStart + 3) % n), true);
        }
    }

    private static void lerp(RectF a, RectF b, float t, RectF out) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    private void drawLightCardAt(Canvas c, int i, RectF base, Recs.Pick p, boolean leaving) {
        if (editorial) {
            drawEditorialCard(c, i, base, p, leaving);
            return;
        }
        if (colors) {
            drawColorsCard(c, i, base, p, leaving);
            return;
        }
        if (minimal) {
            drawMinimalCard(c, i, base, p, leaving);
            return;
        }
        if (hud) {
            drawHudCard(c, i, base, p, leaving);
            return;
        }
        float v = leaving ? 0 : cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = (glass ? 28 : 18) * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        boolean fromPlex = Plex.is(p);
        Backdrop b = p == null || (fromPlex && i > 0) ? null : backdropOf(p);
        Bitmap img = fromPlex && i > 0 ? plex.poster(p) : b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        if (fromPlex && glass) drawGlassPlexMarks(c, i, p, cx, cy, bw, bh, s);
        else if (fromPlex) drawPlexMarks(c, i, p, cx, cy, bw, bh, s);
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        String meta = fromPlex && i > 0 ? p.shortMeta : p.meta;
        if ((i == 0 || fromPlex) && meta != null && !meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * On a Plex card (scaled with it): the white progress bar at the bottom and, on the wide card, the "Continue on
     * Plex" chip with Plex's orange dot.
     */
    private void drawPlexMarks(Canvas c, int i, Recs.Pick p, float cx, float cy, float bw, float bh, float s) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float left = -bw / 2, top = -bh / 2;
        if (p.progress >= 0) {
            float inset = 20 * k, bar = 6 * k, y = bh / 2 - inset - bar;
            tmp.set(left + inset, y, bw / 2 - inset, y + bar);
            fill.setShader(null);
            fill.setColor(0x59FFFFFF);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
            tmp.right = tmp.left + Math.max(bar, (tmp.right - tmp.left) * p.progress);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
        }
        if (i == 0) {
            String chip = p.recent ? Ui.tr("Adicionado recentemente no Plex", "Recently added on Plex")
                    : Ui.tr("Continuar no Plex", "Continue on Plex");
            float h = 44 * k, pad = 18 * k, dot = 12 * k, x = left + 24 * k, y = top + 24 * k;
            float tw = lTip.measureText(chip);
            tmp.set(x, y, x + pad + dot + 12 * k + tw + pad, y + h);
            fill.setShader(null);
            fill.setColor(0xB3000000);
            c.drawRoundRect(tmp, h / 2, h / 2, fill);
            fill.setColor(0xFFE5A00D);
            c.drawCircle(x + pad + dot / 2, y + h / 2, dot / 2, fill);
            int was = lTip.getColor();
            Paint.Align align = lTip.getTextAlign();
            lTip.setColor(0xFFFFFFFF);
            lTip.setAlpha(255);
            lTip.setTextAlign(Paint.Align.LEFT);
            c.drawText(chip, x + pad + dot + 12 * k, y + h / 2 + Ui.halfCap(lTip), lTip);
            lTip.setTextAlign(align);
            lTip.setColor(was);
        }
        c.restore();
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    /** Wi-Fi, another network (Ethernet) or none, and a VPN on any of them. */
    private void netChanged() {
        boolean wifi = false, other = false, vpn = false;
        try {
            for (android.net.Network n : connectivity.getAllNetworks()) {
                android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(n);
                if (caps == null) continue;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
                else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
                else if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) other = true;
            }
        } catch (RuntimeException ignored) {
        }
        if (wifi == netWifi && other == netOther && vpn == netVpn) return;
        netWifi = wifi;
        netOther = other;
        netVpn = vpn;
        if (lPill != null) {
            layoutLight();      // the Wi-Fi capsule makes room for the padlock
            placeAmbiGlow();
        }
        statusDirty = true;
        wake();
    }

    /** Wi-Fi: three arcs and a dot (dimmed and struck through with no network; a square for Ethernet). */
    private void drawWifi(Canvas c, float cx, float cy, float size, int ink) {
        boolean on = netWifi || netOther;
        int color = on ? ink : Ui.alpha(ink, 0.4f);
        float sw = border.getStrokeWidth();
        border.setColor(color);
        border.setStrokeWidth(size * 0.1f);
        border.setStrokeCap(Paint.Cap.ROUND);
        fill.setShader(null);
        fill.setColor(color);
        float base = cy + size * 0.34f;
        if (netWifi || !on) {
            for (int i = 1; i <= 3; i++) {
                float r = size * 0.24f * i;
                tmp.set(cx - r, base - r, cx + r, base + r);
                c.drawArc(tmp, 225, 90, false, border);
            }
            c.drawCircle(cx, base - size * 0.04f, size * 0.1f, fill);
        } else {
            tmp.set(cx - size * 0.3f, cy - size * 0.3f, cx + size * 0.3f, cy + size * 0.3f);
            c.drawRoundRect(tmp, size * 0.12f, size * 0.12f, border);
        }
        if (!on) c.drawLine(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f, border);
        border.setStrokeWidth(sw);
        border.setStrokeCap(Paint.Cap.BUTT);
    }

    /**
     * Ambilight: a small screen with light spilling around it in Ambilight's colors (always colored, unlike the other
     * icons); dimmed while Ambilight is off. While on, a rainbow ring turns around it (ambiGlow).
     */
    private void drawAmbilight(Canvas c, float cx, float cy, float size, int ink) {
        float w = size * 0.62f, h = size * 0.42f;
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        if (ambiGlowShader == null || ambiGlowSize != size) {
            ambiGlowShader = new android.graphics.SweepGradient(0, 0, new int[] {0xFFFF6A3D, 0xFFFF2D95, 0xFF8E5CFF,
                    0xFF2FA8FF, 0xFF30D158, 0xFFFFD60A, 0xFFFF6A3D}, null);
            ambiGlowSize = size;
        }
        float sw = border.getStrokeWidth();
        c.save();
        c.translate(cx, cy);
        tmp.offset(-cx, -cy);
        border.setShader(ambiGlowShader);
        border.setAlpha(ambiOn ? 255 : 110);
        border.setStrokeWidth(size * 0.11f);
        RectF halo = new RectF(tmp);
        halo.inset(-size * 0.13f, -size * 0.13f);
        c.drawRoundRect(halo, size * 0.16f, size * 0.16f, border);
        border.setShader(null);
        border.setAlpha(255);
        fill.setShader(null);
        fill.setColor(ink);
        c.drawRoundRect(tmp, size * 0.06f, size * 0.06f, fill);
        c.restore();
        border.setStrokeWidth(sw);
    }

    private android.graphics.SweepGradient ambiGlowShader;
    private float ambiGlowSize;

    private int ambiIndex() {
        for (int i = 0; i < lightButtons.size(); i++) if (lightButtons.get(i).kind == HomeApps.AMBILIGHT) return i;
        return -1;
    }

    /** The turning ring sits on the Ambilight icon (its capsule's left part); hidden while Ambilight is off. */
    private void placeAmbiGlow() {
        int i = ambiIndex();
        if (i < 0 || topRects[B0 + i] == null || ambiGlow.getMeasuredWidth() == 0) return;
        RectF r = topRects[B0 + i];
        int cx = Math.round(r.centerX()), cy = Math.round(r.centerY()), half = ambiGlow.getMeasuredWidth() / 2;
        ambiGlow.layout(cx - half, cy - half, cx + half, cy + half);
        boolean show = ambiOn;
        if (show && ambiGlow.getVisibility() != VISIBLE) {
            ambiGlow.setVisibility(VISIBLE);
            ambiGlow.setRotation(0);
            ambiGlow.animate().rotationBy(360 * 1000f).setDuration(4000L * 1000)
                    .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        } else if (!show && ambiGlow.getVisibility() == VISIBLE) {
            ambiGlow.animate().cancel();
            ambiGlow.setVisibility(INVISIBLE);
        }
    }

    /** Reads Ambilight + Hue from the TV (background thread), then redraws the button. */
    private void refreshAmbilight() {
        if (ambiBusy || !running) return;
        int seq = ambiSeq;
        Context app = getContext().getApplicationContext();
        AMBI.execute(() -> {
            Boolean on = AmbiHue.isOn(app);
            if (on != null) handler.post(() -> { if (seq == ambiSeq && !ambiBusy) showAmbilight(on); });
        });
    }

    /** Bumped by every press on Ambilight: a reading taken before the latest press is dropped. */
    private int ambiSeq;
    /** A switch is on its way (HueService takes up to 3 s, one command at a time): presses meanwhile are ignored. */
    private volatile boolean ambiBusy;

    private void showAmbilight(boolean on) {
        if (ambiOn == on) return;
        ambiOn = on;
        placeAmbiGlow();
        statusDirty = true;
        wake();
    }

    /**
     * OK on Ambilight: the TV's real state is read first (Philips may have changed it), then the other one is asked
     * for and remembered (RemoteKeyService puts it back at the next power on). The ring follows at once; the real
     * state is read back when HueService is done. An ON that HueService refused (Ambilight+hue off in Philips'
     * settings) turns the ring off again.
     */
    private void toggleAmbilight() {
        if (ambiBusy) return;
        ambiBusy = true;
        int seq = ++ambiSeq;
        boolean shown = ambiOn;
        showAmbilight(!shown);
        Context app = getContext().getApplicationContext();
        AMBI.execute(() -> {
            Boolean now = AmbiHue.isOn(app);
            boolean on = !(now != null ? now : shown);
            AmbiHue.remember(app, on);
            if (!AmbiHue.set(app, on)) {
                handler.post(() -> {
                    ambiBusy = false;
                    if (seq == ambiSeq) showAmbilight(!on);
                    showToast(Ui.tr("Esta TV não tem Ambilight + Hue.", "This TV has no Ambilight + Hue."));
                });
                return;
            }
            handler.post(() -> showAmbilight(on));
            // HueService finds the bridge in about 0.3 s, and gives it back 3 s after OFF (the lamps return as they were).
            SystemClock.sleep(on ? 2500 : 3500);
            Boolean after = AmbiHue.isOn(app);
            handler.post(() -> {
                ambiBusy = false;
                if (seq != ambiSeq || after == null) return;
                showAmbilight(after);
                if (on && !after) showToast(Ui.tr("Ative Ambilight+hue nos ajustes Ambilight da TV.",
                        "Turn on Ambilight+hue in the TV's Ambilight settings."));
            });
        });
    }

    private void openPhilips(String action) {
        try {
            activity.startActivity(new android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            showToast(Ui.tr("Esta TV não abre esta tela.", "This TV can't open that screen."));
        }
    }

    /** The power symbol: an open circle with a bar through its gap. */
    private void drawPower(Canvas c, float cx, float cy, float size, int ink) {
        float sw = border.getStrokeWidth(), r = size * 0.4f;
        border.setColor(ink);
        border.setStrokeWidth(size * 0.11f);
        border.setStrokeCap(Paint.Cap.ROUND);
        tmp.set(cx - r, cy - r + size * 0.04f, cx + r, cy + r + size * 0.04f);
        c.drawArc(tmp, -60, 300, false, border);
        c.drawLine(cx, cy - size * 0.48f, cx, cy - size * 0.02f, border);
        border.setStrokeWidth(sw);
        border.setStrokeCap(Paint.Cap.BUTT);
    }

    /** A padlock (VPN on), centered on (cx, cy). */
    private void drawLock(Canvas c, float cx, float cy, float s, int ink) {
        float w2 = 9 * k * s, top = cy - 2 * k * s;
        fill.setShader(null);
        fill.setColor(ink);
        tmp.set(cx - w2, top, cx + w2, top + 15 * k * s);
        c.drawRoundRect(tmp, 3 * k * s, 3 * k * s, fill);
        float sw = border.getStrokeWidth();
        border.setColor(ink);
        border.setStrokeWidth(3 * k * s);
        float a = 6 * k * s;
        tmp.set(cx - a, top - 11 * k * s, cx + a, top + 1 * k * s);
        c.drawArc(tmp, 180, 180, false, border);
        c.drawLine(cx - a, top - 5 * k * s, cx - a, top, border);
        c.drawLine(cx + a, top - 5 * k * s, cx + a, top, border);
        border.setStrokeWidth(sw);
    }

    /** Icon room (34, plus the padlock with a VPN) + 14 + name, with 28 and 30 of padding. */
    private float pillWidth(int i) {
        return L_BUTTON * k;    // icons only: glass circles (the names went back off)
    }

    /**
     * Inputs, Wi-Fi and Settings: Liquid Glass capsules (light glass by day, smoked at night) with the icon and its name;
     * the focused one grows a little and turns white with dark ink (the tvOS focus).
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        if (editorial) {
            drawEditorialButtons(c);
            return;
        }
        if (colors) {
            drawColorsButtons(c);
            return;
        }
        if (minimal) {
            drawMinimalButtons(c);
            return;
        }
        if (hud) {
            drawHudButtons(c);
            return;
        }
        if (glass) {
            drawGlassButtons(c);
            return;
        }
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[B0 + i];
            float f = Ui.clamp(topFocus[B0 + i].value), s = 1 + 0.06f * f;
            float hw = r.width() / 2 * s, hh = r.height() / 2 * s, rad = hh;
            cell.set(r.centerX() - hw, r.centerY() - hh, r.centerX() + hw, r.centerY() + hh);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(cell, rad, rad, fill);
            }
            int ink = blend(tone(L_INK, 0xEBFFFFFF), L_INK, f);
            float cx = cell.centerX(), cy = cell.centerY(), icon = 32 * k * s;
            if (it.kind == HomeApps.POWER) {
                drawPower(c, cx, cy, 30 * k * s, ink);
            } else if (it.kind == HomeApps.AMBILIGHT) {
                drawAmbilight(c, cx, cy, icon, ink);
            } else if (it.kind == HomeApps.WIFI) {
                drawWifi(c, cx, cy - 1 * k, icon, ink);
                if (netVpn) drawLock(c, cx + 13 * k * s, cy + 9 * k * s, 0.62f * s, ink);   // small padlock at the corner
            } else if (it.kind == HomeApps.UPDATE) {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 27 * k * s, ink);
                if (Updates.get(getContext()).available()) {     // the dot: a newer launcher-ui on GitHub
                    fill.setShader(null);
                    fill.setColor(UPDATE_DOT);
                    c.drawCircle(cell.right - 13 * k * s, cell.top + 13 * k * s, 8 * k * s, fill);
                }
            } else {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 27 * k * s, ink);
            }
        }
        drawButtonLabel(c);
    }

    /**
     * The focused round button's name, in a smoked-glass capsule above it (the empty band at the top of the screen:
     * nothing else is covered). One capsule that glides from button to button with the focus and fades in and out.
     */
    private void drawButtonLabel(Canvas c) {
        if (buttonLabel == null) return;
        float sum = 0, x = 0, best = 0;
        int top = -1;
        for (int i = 0; i < lightButtons.size(); i++) {
            float f = Ui.clamp(topFocus[B0 + i].value);
            if (f <= 0) continue;
            sum += f;
            x += f * topRects[B0 + i].centerX();
            if (f > best) {
                best = f;
                top = i;
            }
        }
        if (top < 0 || best < 0.02f) return;
        String text = lightButtons.get(top).label;
        if (text == null || text.isEmpty()) return;
        x /= sum;
        float a = Ui.clamp((best - 0.15f) / 0.85f);     // appears once the focus has mostly landed
        float tw = buttonLabel.measureText(text), padX = 16 * k, h = 34 * k;
        float half = tw / 2 + padX, right = CONTENT_R * k;
        x = Math.min(x, right - half);
        float bottom = topRects[B0 + top].top - 8 * k + (1 - a) * 6 * k;
        tmp.set(x - half, bottom - h, x + half, bottom);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, h / 2, SMOKE, 0.55f, 0, 0, 0.02f, 1);
        c.drawText(text, x, tmp.centerY() + Ui.halfCap(buttonLabel), buttonLabel);
        c.restoreToCount(save);
    }

    /**
     * The weather in one line at the right end of the tab row (symbol, temperature, sky, high and low), where the
     * crypto quotes were; it fades out while a round button's name shows there.
     */
    private void drawWeatherSummary(Canvas c) {
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        // Faded by the sum of the buttons' focus: moving from one button to the next keeps it hidden (with the
        // smallest of them it came back for a moment halfway, and flickered).
        float fade = 1;     // the buttons' names are inside them now: nothing shows over the weather
        if (fade <= 0.01f) return;
        String line = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                + (Ui.fr ? French.highLow(HomeData.degrees(wx.max), HomeData.degrees(wx.min), "  ")
                : Ui.tr("Máx. ", "H:") + HomeData.degrees(wx.max) + "  " + Ui.tr("Mín. ", "L:") + HomeData.degrees(wx.min));
        applyTone();
        int color = lQuote.getColor();
        float size = lQuote.getTextSize() * 1.25f;
        lQuote.setTextSize(size);
        float base = (L_TABBAR_TOP + L_TABBAR_H / 2) * k + Ui.halfCap(lQuote);
        float right = CONTENT_R * k, tw = lQuote.measureText(line);
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        lQuote.setColor(Ui.alpha(color, fade));
        Symbols.draw(getContext(), c, symbol, right - tw - 12 * k - sw / 2, base - Ui.halfCap(lQuote), size, Ui.alpha(color, fade));
        c.drawText(line, right - tw, base, lQuote);
        lQuote.setColor(color);
        lQuote.setTextSize(size / 1.25f);
    }

    /** One dot per Plex title under the cards, the one on the wide card brighter. */
    private void drawCarouselDots(Canvas c) {
        int n = cards.size();
        float d = 10 * k, gap = 12 * k, y = (L_CARDS_TOP + L_CARD_H + 112) * k;
        float x = (CONTENT_R * k) - n * d - (n - 1) * gap + d / 2;
        fill.setShader(null);
        for (int i = 0; i < n; i++) {
            fill.setColor(Ui.alpha(tone(L_INK, 0xFFFFFFFF), i == plexStart ? 0.9f : 0.3f));
            c.drawCircle(x + i * (d + gap), y, d / 2, fill);
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = tabsShown() ? tab : B0;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cardSlots(), tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = cardIndex + dx;
                    if (next < 0 || next >= cardSlots()) {
                        // Plex: past the edge, the carousel turns (the focus stays on the edge card).
                        if (!plexTab() || cards.size() < 2) return true;
                        turnCarousel(dx);
                    } else {
                        cardIndex = next;
                    }
                    heroDirty = true;
                    if (plexTab()) updateCards();   // the background follows the focused card
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tabsShown() ? tab : B0;     // as on Apple TV: up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                    if (plexTab()) updateCards();
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = topIndex + dx;
                    if (topIndex < B0 && next >= tabCount()) next = B0;     // from the last tab to the first button
                    else if (topIndex == B0 && dx < 0) next = tabsShown() ? tabCount() - 1 : B0;
                    next = Math.max(0, Math.min(B0 + lightButtons.size() - 1, next));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < B0) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cardSlots(), topRects[topIndex].centerX());
                        if (plexTab()) updateCards();
                    }
                } else if (topIndex < B0) {
                    topIndex = B0;   // up from the tabs: the first of the round buttons
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Glass theme (Settings > Home settings > Theme > Glass): dark aurora, glass tiles with line icons

    /** Light cyan of the glass theme: progress, the dot on the Plex chip. */
    private static final int ACCENT = 0xFF8CD6F2;
    private boolean glass;
    /** Glass theme with the apps' own logos and banners on the tiles (Home settings > App icons > Original): false. */
    private boolean glassIcons = true;
    private final Paint glassStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint glassLabel, glassSub, glassChip;
    private final RectF groupRect = new RectF(), badge = new RectF();
    private final Map<String, String> iconNames = new java.util.HashMap<>();
    private LinearGradient auroraBase;
    private android.graphics.RadialGradient auroraWarm, auroraCool;
    private float auroraW;
    private android.graphics.DashPathEffect dashes;

    /** The theme picked in the settings: everything is laid out and drawn again when it switches to or from glass. */
    private void syncStyle() {
        boolean g = Prefs.glass(), e = Prefs.editorial(), co = Prefs.colors(), mn = Prefs.minimal(), hu = Prefs.hud();
        boolean gi = Prefs.glassIcons();
        if (g == glass && e == editorial && co == colors && mn == minimal && hu == hud && gi == glassIcons) return;
        minimal = mn;
        hud = hu;
        boolean relayout = co != colors;
        glass = g;
        editorial = e;
        colors = co;
        if (relayout) requestLayout();     // the app tiles' layer sits higher in the Colors theme
        glassIcons = gi;
        glassText();
        if (lTab != null) layoutLight();
        if (shelf.size() == tileRects.length && !shelf.isEmpty()) layoutShelf();
        layoutDrawer();
        placeAmbiGlow();
        heroDirty = statusDirty = tilesDirty = drawerDirty = gridDirty = motionDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** The glass theme's text: a light, large clock; the tiles' names and second lines. */
    private void glassText() {
        if (lTitle == null) return;
        editorialText();
        colorsText();
        minimalText();
        hudText();
        lTitle.setTypeface(glass ? Ui.light : Ui.displaySemibold);
        lTitle.setTextSize((glass ? 108 : 76) * k);
        glassLabel = Ui.text(Ui.semibold, 26 * k, 0xF2FFFFFF);
        glassSub = Ui.text(Ui.medium, 20 * k, 0xA6FFFFFF);
        glassChip = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        glassStroke.setStyle(Paint.Style.STROKE);
        glassStroke.setStrokeWidth(1.5f * k);
        dashes = new android.graphics.DashPathEffect(new float[] {8 * k, 7 * k}, 0);
    }

    /** Background: a teal-to-indigo aurora with a warm glow, the hero's photo faintly through it. */
    private void drawGlassSky(Canvas c) {
        if (auroraBase == null || auroraW != w) {
            auroraW = w;
            auroraBase = new LinearGradient(0, 0, w, h, new int[] {0xFF245B60, 0xFF22324F, 0xFF191B24},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            auroraWarm = new android.graphics.RadialGradient(w * 0.6f, h * 0.9f, w * 0.45f, 0x47C0894A, 0x00C0894A, Shader.TileMode.CLAMP);
            auroraCool = new android.graphics.RadialGradient(w * 0.85f, h * 0.1f, w * 0.4f, 0x405A60C8, 0x005A60C8, Shader.TileMode.CLAMP);
        }
        android.graphics.ColorFilter v = veil(0.45f * Ui.clamp(drawer.value), 0xFF000000);
        fill.setColorFilter(v);
        fill.setColor(0xFF000000);
        for (Shader sh : new Shader[] {auroraBase, auroraWarm, auroraCool}) {
            fill.setShader(sh);
            c.drawRect(screen, fill);
        }
        fill.setShader(null);
        fill.setColorFilter(null);
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawImage(c, glassPhoto(bgFrom), 0.35f * (bgTo == null ? 1 - e : 1));
        if (bgTo != null) drawImage(c, glassPhoto(bgTo), 0.35f * (fading ? e : 1));
        imagePaint.setColorFilter(null);
    }

    private static Bitmap glassPhoto(Backdrop b) {
        return b.nightImage != null ? b.nightImage : b.lightImage;
    }

    /** The round buttons in one smoked-glass capsule, Power apart after a thin line; the focused one turns white. */
    private void drawGlassButtons(Canvas c) {
        int n = lightButtons.size();
        if (n == 0) return;
        float pad = 10 * k;
        RectF first = topRects[B0], last = topRects[B0 + n - 1];
        groupRect.set(first.left - pad, first.top - pad, last.right + pad, last.bottom + pad);
        float rad = groupRect.height() / 2;
        tmp.set(groupRect);
        tmp.offset(0, 10 * k);
        shadow.setColor(0x4D000000);
        shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        scene.liquid(c, groupRect, rad, SMOKE, 0.5f, 0.01f, 12 * k, 8 * k, 0.45f, 1);
        glassStroke.setColor(0x33FFFFFF);
        c.drawRoundRect(groupRect, rad, rad, glassStroke);
        for (int i = 0; i < n; i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[B0 + i];
            if (it.kind == HomeApps.POWER && i > 0) {
                float x = (topRects[B0 + i - 1].right + r.left) / 2;
                glassStroke.setColor(0x33FFFFFF);
                c.drawLine(x, groupRect.top + 20 * k, x, groupRect.bottom - 20 * k, glassStroke);
            }
            float f = Ui.clamp(topFocus[B0 + i].value), s = 1 + 0.06f * f;
            float hw = r.width() / 2 * s, cx = r.centerX(), cy = r.centerY();
            fill.setShader(null);
            if (it.kind == HomeApps.POWER) {
                fill.setColor(0x1FFFFFFF);
                c.drawCircle(cx, cy, hw, fill);
            }
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cx, cy, hw, fill);
            }
            int ink = blend(0xEBFFFFFF, L_INK, f);
            float icon = 32 * k * s;
            if (it.kind == HomeApps.POWER) {
                drawPower(c, cx, cy, 30 * k * s, ink);
            } else if (it.kind == HomeApps.AMBILIGHT) {
                drawAmbilight(c, cx, cy, icon, ink);
            } else if (it.kind == HomeApps.WIFI) {
                drawWifi(c, cx, cy - 1 * k, icon, ink);
                if (netVpn) drawLock(c, cx + 13 * k * s, cy + 9 * k * s, 0.62f * s, ink);
            } else if (it.kind == HomeApps.UPDATE) {
                Icons.draw(c, "sync", cx, cy, icon, ink);
                if (Updates.get(getContext()).available()) {
                    fill.setColor(UPDATE_DOT);
                    c.drawCircle(cx + hw * 0.62f, cy - hw * 0.62f, 7 * k * s, fill);
                }
            } else if (it.kind == HomeApps.INPUTS) {
                Icons.draw(c, "plug", cx, cy, icon, ink);
            } else if (it.kind == HomeApps.ANDROID_SETTINGS || it.kind == HomeApps.SETTINGS) {
                Icons.draw(c, "sliders", cx, cy, icon, ink);
            } else {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 27 * k * s, ink);
            }
        }
        drawButtonLabel(c);
    }

    /** On a Plex card in the glass theme: a glass chip with a cyan dot, and on the wide card a glass bar to resume. */
    private void drawGlassPlexMarks(Canvas c, int i, Recs.Pick p, float cx, float cy, float bw, float bh, float s) {
        if (i != 0) return;     // the tall cards: only the poster (the time left is written under them)
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float left = -bw / 2, top = -bh / 2, right = bw / 2, bottom = bh / 2;
        String chip = p.recent ? Ui.tr("Adicionado recentemente no Plex", "Recently added on Plex")
                : Ui.tr("Continuar no Plex", "Continue on Plex");
        float h = 48 * k, pad = 20 * k, dot = 10 * k, x = left + 22 * k, y = top + 22 * k;
        float tw = glassChip.measureText(chip);
        tmp.set(x, y, x + pad + dot + 12 * k + tw + pad, y + h);
        fill.setShader(null);
        fill.setColor(0x66223038);
        c.drawRoundRect(tmp, h / 2, h / 2, fill);
        glassStroke.setColor(0x4DFFFFFF);
        c.drawRoundRect(tmp, h / 2, h / 2, glassStroke);
        fill.setColor(ACCENT);
        c.drawCircle(x + pad + dot / 2, y + h / 2, dot / 2, fill);
        c.drawText(chip, x + pad + dot + 12 * k, y + h / 2 + Ui.halfCap(glassChip), glassChip);
        if (p.progress >= 0) {
            float barH = 88 * k, inset = 22 * k;
            tmp.set(left + inset, bottom - inset - barH, right - inset, bottom - inset);
            fill.setColor(0x59223038);
            c.drawRoundRect(tmp, barH / 2, barH / 2, fill);
            glassStroke.setColor(0x59FFFFFF);
            c.drawRoundRect(tmp, barH / 2, barH / 2, glassStroke);
            float pr = 32 * k, pcx = tmp.right - 12 * k - pr, pcy = tmp.centerY();
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(pcx, pcy, pr, fill);
            clip.reset();
            clip.moveTo(pcx - 8 * k, pcy - 12 * k);
            clip.lineTo(pcx + 12 * k, pcy);
            clip.lineTo(pcx - 8 * k, pcy + 12 * k);
            clip.close();
            fill.setColor(0xFF1C1C1E);
            c.drawPath(clip, fill);
            float textRight = pcx - pr - 24 * k;
            if (p.left != null) {
                glassChip.setTextAlign(Paint.Align.RIGHT);
                c.drawText(p.left, textRight, pcy + Ui.halfCap(glassChip), glassChip);
                glassChip.setTextAlign(Paint.Align.LEFT);
                textRight -= glassChip.measureText(p.left) + 26 * k;
            }
            float bar = 6 * k, bl = tmp.left + 32 * k;
            tmp.set(bl, pcy - bar / 2, Math.max(bl + bar, textRight), pcy + bar / 2);
            fill.setColor(0x47FFFFFF);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
            tmp.right = tmp.left + Math.max(bar, tmp.width() * p.progress);
            fill.setColor(ACCENT);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
        }
        c.restore();
    }

    /** The line icon for a tile (cached: the shelf is drawn on every frame of a focus move). */
    private String iconOf(HomeApps.Item it) {
        String key = it.key == null ? String.valueOf(it.pkg) : it.key;
        String name = iconNames.get(key);
        if (name == null) {
            name = it.kind == HomeApps.APP ? Icons.forApp(it.pkg, it.label) : Icons.forSymbol(it.symbol);
            iconNames.put(key, name);
        }
        return name;
    }

    /**
     * An app in the glass theme: a pane of glass with the icon in a small rounded square at the top left and the name
     * at the bottom left. Focused: brighter glass and rim, the icon's square turns white. Drawn at rest size and scaled.
     */
    private void glassTile(Canvas c, RectF base, float f, float l, HomeApps.Item it, boolean dim) {
        float fc = Ui.clamp(f), s = 1 + 0.06f * f + LIFT_GROW * l, r = 26 * k;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        if (fc > 0.01f || l > 0.01f) {
            tmp.set(base);
            tmp.offset(0, (8 + 10 * f) * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, 0.34f * fc + 0.14f * Ui.clamp(l)));
            shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        }
        fill.setShader(null);
        fill.setColor(blend(0x1FFFFFFF, 0x59FFFFFF, fc));
        c.drawRoundRect(base, r, r, fill);
        glassStroke.setColor(blend(0x33FFFFFF, 0xD9FFFFFF, fc));
        c.drawRoundRect(base, r, r, glassStroke);
        float b = Math.min(64 * k, base.height() * 0.38f), bx = base.left + 22 * k, by = base.top + 22 * k;
        badge.set(bx, by, bx + b, by + b);
        fill.setColor(blend(0x1FFFFFFF, 0xFFFFFFFF, fc));
        c.drawRoundRect(badge, b * 0.28f, b * 0.28f, fill);
        if (fc < 0.99f) {
            glassStroke.setColor(Ui.alpha(0x2EFFFFFF, 1 - fc));
            c.drawRoundRect(badge, b * 0.28f, b * 0.28f, glassStroke);
        }
        int ink = blend(dim ? 0x80FFFFFF : 0xF2FFFFFF, 0xFF1C1C1E, fc);
        Icons.draw(c, iconOf(it), badge.centerX(), badge.centerY(), b * 0.56f, ink);
        glassLabel.setColor(dim ? 0x80FFFFFF : 0xF2FFFFFF);
        fitPaint.set(glassLabel);
        String label = TextUtils.ellipsize(it.label == null ? "" : it.label, fitPaint, base.width() - 46 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(label, base.left + 24 * k, base.bottom - 26 * k, glassLabel);
        c.restore();
    }

    /**
     * A source or shortcut under the grid in the glass theme: the icon's square at the top left, the name, and for an
     * input a second line (its port and state, with a colored dot when it is on).
     */
    private void glassSmallTile(Canvas c, RectF base, float f, HomeApps.Item it) {
        float fc = Ui.clamp(f), s = 1 + 0.05f * f, r = 22 * k;
        boolean input = it.kind == HomeApps.INPUT, off = input && !it.connected && !it.standby;
        String sub = null;
        int dot = 0;
        if (it.kind == HomeApps.LIVE_TV) {
            sub = Ui.tr("Canais de TV", "TV channels");
        } else if (input) {
            String port = "HDMI " + it.number;
            boolean named = !it.label.equalsIgnoreCase(port);
            if (it.connected) {
                sub = (named ? port + " · " : "") + Ui.tr("Conectado", "Connected");
                dot = ACCENT;
            } else if (it.standby) {
                sub = (named ? port + " · " : "") + Ui.tr("Em espera", "In standby");
                dot = 0x99FFFFFF;
            } else {
                sub = Ui.tr("Livre", "Not in use");
            }
        }
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        if (fc > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 10 * k * fc);
            shadow.setColor(Ui.alpha(0xFF000000, 0.4f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        fill.setShader(null);
        fill.setColor(blend(off ? 0x0AFFFFFF : 0x17FFFFFF, 0x52FFFFFF, fc));
        c.drawRoundRect(base, r, r, fill);
        if (off && fc < 0.5f) {
            glassStroke.setPathEffect(dashes);
            glassStroke.setColor(0x33FFFFFF);
            c.drawRoundRect(base, r, r, glassStroke);
            glassStroke.setPathEffect(null);
        } else {
            glassStroke.setColor(blend(0x29FFFFFF, 0xD9FFFFFF, fc));
            c.drawRoundRect(base, r, r, glassStroke);
        }
        float b = 44 * k, bx = base.left + 16 * k, by = base.top + 14 * k;
        badge.set(bx, by, bx + b, by + b);
        fill.setColor(blend(0x1FFFFFFF, 0xFFFFFFFF, fc));
        c.drawRoundRect(badge, 12 * k, 12 * k, fill);
        int ink = blend(off ? 0x73FFFFFF : 0xF2FFFFFF, 0xFF1C1C1E, fc);
        Icons.draw(c, iconOf(it), badge.centerX(), badge.centerY(), b * 0.58f, ink);
        float x = base.left + 18 * k, room = base.width() - 34 * k;
        glassLabel.setColor(off ? 0x73FFFFFF : 0xF2FFFFFF);
        glassLabel.setTextSize(24 * k);
        fitPaint.set(glassLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, x, sub == null ? base.bottom - 20 * k : base.top + 88 * k, glassLabel);
        glassLabel.setTextSize(26 * k);
        if (sub != null) {
            glassSub.setColor(off ? 0x59FFFFFF : 0xA6FFFFFF);
            float sx = x;
            if (dot != 0) {
                fill.setColor(dot);
                c.drawCircle(sx + 5 * k, base.top + 108 * k, 5 * k, fill);
                sx += 16 * k;
            }
            fitPaint.set(glassSub);
            c.drawText(TextUtils.ellipsize(sub, fitPaint, room - (sx - x), TextUtils.TruncateAt.END).toString(),
                    sx, base.top + 108 * k + Ui.halfCap(glassSub), glassSub);
        }
        c.restore();
    }

    // MARK: - Editorial theme (Settings > Home settings > Theme > Editorial): cream by day, black at night, serif titles

    private static final int ED_CREAM = 0xFFEFE8DE, ED_INK = 0xFF1F1B18, ED_RED = 0xFFD9553A, ED_RED_NIGHT = 0xFFE8603F;
    private boolean editorial;
    private Paint edClock, edGreet, edLine, edKicker, edTitle, edSub, edMeta, edLink, edPosterTitle, edPosterMeta, edPill;
    private final Paint edStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path archPath = new Path();
    private final float[] archRadii = new float[8];

    private int edInk() { return tone(ED_INK, ED_CREAM); }
    private int edPaper() { return tone(ED_CREAM, 0xFF000000); }
    private int edAccent() { return tone(ED_RED, ED_RED_NIGHT); }
    private int edDim() { return tone(0x991F1B18, 0x99EFE8DE); }

    private void editorialText() {
        edClock = Ui.numbers(Ui.serif, 158 * k, ED_INK);
        edGreet = Ui.text(Ui.serifItalic, 46 * k, ED_INK);
        edLine = Ui.text(Ui.medium, 22 * k, ED_INK);
        edKicker = Ui.text(Ui.semibold, 18 * k, ED_RED);
        edKicker.setLetterSpacing(0.2f);
        edTitle = Ui.text(Ui.serifItalic, 96 * k, ED_INK);
        edSub = Ui.text(Ui.serif, 48 * k, ED_INK);
        edMeta = Ui.text(Ui.medium, 22 * k, ED_INK);
        edLink = Ui.text(Ui.semibold, 25 * k, ED_INK);
        edPosterTitle = Ui.text(Ui.serif, 36 * k, ED_INK);
        edPosterMeta = Ui.text(Ui.medium, 19 * k, ED_INK);
        edPill = Ui.text(Ui.semibold, 24 * k, ED_INK);
        edStroke.setStyle(Paint.Style.STROKE);
        edStroke.setStrokeWidth(1.5f * k);
    }

    /** Layout: the photo on the left, the title beside it, two arched posters on the right; buttons without capsule. */
    private void layoutEditorial() {
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = 108 * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[B0 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + 14 * k;
        }
        float top = 250 * k, left = MARGIN * k, tall = 200 * k, g = 30 * k;
        cardRects[0].set(left, top, left + 810 * k, top + 572 * k);
        cardRects[2].set(CONTENT_R * k - tall, top, CONTENT_R * k, top + 490 * k);
        cardRects[1].set(cardRects[2].left - g - tall, top, cardRects[2].left - g, top + 490 * k);
    }

    /** The apps: pills in one row under a thin line, as wide as the screen allows. */
    private void layoutEditorialShelf() {
        int n = shelf.size();
        float gap = 20 * k, full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(300 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n)), th = 98 * k;
        float x = MARGIN * k, y = 912 * k;
        gridLeft = (w - (COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP) * k) / 2f;
        platterRect.set(x, y, x + n * tw + Math.max(0, n - 1) * gap, y + th);
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** The time, the greeting in italics, and one line: date · weather · high / low. */
    private void drawEditorialTop(Canvas c) {
        int ink = edInk();
        edClock.setColor(ink);
        float x = MARGIN * k - Ui.leftBearing(edClock, clockText);
        c.drawText(clockText, x, 182 * k, edClock);
        float gx = x + edClock.measureText(clockText) + 38 * k;
        edGreet.setColor(ink);
        c.drawText(greeting() + ",", gx, 145 * k, edGreet);
        StringBuilder line = new StringBuilder(dateText);
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            line.append(" · ").append(HomeData.degrees(wx.temp)).append(' ')
                    .append(HomeData.condition(wx.code, wx.isDay).toLowerCase(Ui.locale()))
                    .append(" · ").append(HomeData.degrees(wx.max)).append(" / ").append(HomeData.degrees(wx.min));
        }
        edLine.setColor(edDim());
        c.drawText(line.toString(), gx, 182 * k, edLine);
        if (plexTab() && cards.isEmpty()) {
            int st = plex.state();
            if (st != Plex.LOADING) {
                String msg = st == Plex.UNREACHABLE ? Ui.tr("Servidor Plex inacessível", "Plex server unreachable")
                        : Ui.tr("Nada para continuar no Plex", "Nothing to resume on Plex");
                edSub.setColor(ink);
                c.drawText(msg, MARGIN * k, 520 * k, edSub);
            }
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        boolean blank = plexTab();
        for (int i = 0; i < 3; i++) if (i != top && !(blank && i >= cardSlots())) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        Recs.Pick hero = cardAt(0);
        if (hero != null) drawEditorialHeroText(c, hero, Ui.clamp(cardFocus[0].value));
    }

    /** A card: the wide photo with round corners, or a poster in an arch; focused, a thin outline in the accent. */
    private void drawEditorialCard(Canvas c, int i, RectF base, Recs.Pick p, boolean leaving) {
        float v = leaving ? 0 : cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.03f * v;
        float bw = base.width(), bh = base.height(), cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        boolean fromPlex = Plex.is(p);
        Backdrop b = p == null || (fromPlex && i > 0) ? null : backdropOf(p);
        Bitmap img = fromPlex && i > 0 ? plex.poster(p) : b == null ? null : (i == 0 ? b.wide : b.tall);
        if (i == 0) {
            float r = 40 * k * s;
            if (img != null) {
                roundImage(c, img, cell, r);
            } else {
                fill.setShader(null);
                fill.setColor(tone(0xFFDDD3C4, 0xFF1C1A18));
                c.drawRoundRect(cell, r, r, fill);
            }
            if (f > 0.01f) {
                edStroke.setColor(Ui.alpha(edAccent(), f));
                edStroke.setStrokeWidth(3 * k);
                tmp.set(cell);
                tmp.inset(-8 * k, -8 * k);
                c.drawRoundRect(tmp, r + 8 * k, r + 8 * k, edStroke);
                edStroke.setStrokeWidth(1.5f * k);
            }
            return;     // its title is beside it (drawEditorialHeroText)
        }
        arch(cell, 0);
        if (img != null) {
            archImage(c, img, cell);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDDD3C4, 0xFF1C1A18));
            c.drawPath(archPath, fill);
        }
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.inset(-8 * k, -8 * k);
            arch(tmp, 0);
            edStroke.setColor(Ui.alpha(edAccent(), f));
            edStroke.setStrokeWidth(3 * k);
            c.drawPath(archPath, edStroke);
            edStroke.setStrokeWidth(1.5f * k);
        }
        if (p == null) return;
        edPosterTitle.setColor(edInk());
        fitPaint.set(edPosterTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 50 * k, edPosterTitle);
        String meta = fromPlex ? p.shortMeta : p.meta;
        if (meta != null && !meta.isEmpty()) {
            edPosterMeta.setColor(edDim());
            fitPaint.set(edPosterMeta);
            c.drawText(TextUtils.ellipsize(meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 84 * k, edPosterMeta);
        }
    }

    /** archPath: `r` with a half-circle top (the poster's arch). */
    private void arch(RectF r, float unused) {
        float rr = r.width() / 2;
        for (int j = 0; j < 4; j++) archRadii[j] = rr;
        for (int j = 4; j < 8; j++) archRadii[j] = 0;
        archPath.reset();
        archPath.addRoundRect(r, archRadii, Path.Direction.CW);
    }

    /** `image` cropped into archPath (set for `cell`); on Android 9 to 11 as a shader, as roundImage does. */
    private void archImage(Canvas c, Bitmap image, RectF cell) {
        coverMatrix(image, cell);
        if (!Ui.LEGACY) {
            c.save();
            c.clipPath(archPath);
            c.drawBitmap(image, imageMatrix, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawPath(archPath, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Beside the photo: the kicker in the accent, the title in large italics (what comes after a colon on the next
     * line, upright), the details, the progress as a thin line with a dot, and "Resume playback →".
     */
    private void drawEditorialHeroText(Canvas c, Recs.Pick p, float f) {
        float x = cardRects[0].right + 64 * k, room = cardRects[1].left - 40 * k - x;
        if (room < 120 * k) return;
        boolean fromPlex = Plex.is(p);
        String kicker = !fromPlex ? Ui.tr("Para descobrir", "To discover")
                : p.recent ? Ui.tr("Adicionado recentemente", "Recently added") : Ui.tr("Continuar no Plex", "Continue on Plex");
        edKicker.setColor(edAccent());
        c.drawText(kicker.toUpperCase(Ui.locale()), x, 372 * k, edKicker);
        String title = p.title == null ? "" : p.title, head = title, tail = "";
        int colon = title.indexOf(':');
        if (colon > 0 && colon < title.length() - 1) {
            head = title.substring(0, colon).trim();
            tail = title.substring(colon + 1).trim();
        }
        int ink = edInk();
        edTitle.setColor(ink);
        float size = 96 * k;
        edTitle.setTextSize(size);
        while (edTitle.measureText(head) > room && size > 60 * k) {
            size -= 4 * k;
            edTitle.setTextSize(size);
        }
        fitPaint.set(edTitle);
        c.drawText(TextUtils.ellipsize(head, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, 470 * k, edTitle);
        edTitle.setTextSize(96 * k);
        if (!tail.isEmpty()) {
            edSub.setColor(ink);
            fitPaint.set(edSub);
            c.drawText(TextUtils.ellipsize(tail, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, 540 * k, edSub);
        }
        if (p.meta != null && !p.meta.isEmpty()) {
            edMeta.setColor(edDim());
            fitPaint.set(edMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, 598 * k, edMeta);
        }
        if (p.progress >= 0) {
            float y = 640 * k;
            edStroke.setColor(tone(0x331F1B18, 0x33EFE8DE));
            c.drawLine(x, y, x + room, y, edStroke);
            fill.setShader(null);
            fill.setColor(edAccent());
            c.drawCircle(x + room * p.progress, y, 7 * k, fill);
        }
        String link = (fromPlex && p.progress >= 0 ? Ui.tr("Retomar", "Resume playback") : Ui.tr("Assistir", "Watch")) + "  →";
        edLink.setColor(ink);
        c.drawText(link, x, 698 * k, edLink);
        edStroke.setColor(blend(ink, edAccent(), f));
        edStroke.setStrokeWidth((1.5f + 1.5f * f) * k);
        float lw = edLink.measureText(link);
        c.drawLine(x, 714 * k, x + lw, 714 * k, edStroke);
        edStroke.setStrokeWidth(1.5f * k);
    }

    /** The top buttons: bare icons; the focused one gets a filled circle (ink on cream, cream on black). */
    private void drawEditorialButtons(Canvas c) {
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[B0 + i];
            float f = Ui.clamp(topFocus[B0 + i].value), s = 1 + 0.04f * f;
            float hw = r.width() / 2 * s, cx = r.centerX(), cy = r.centerY();
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(edInk(), f));
                c.drawCircle(cx, cy, hw, fill);
            }
            int ink = blend(edInk(), edPaper(), f);
            float icon = 30 * k * s;
            if (it.kind == HomeApps.POWER) {
                drawPower(c, cx, cy, 28 * k * s, ink);
            } else if (it.kind == HomeApps.AMBILIGHT) {
                drawAmbilight(c, cx, cy, icon, ink);
            } else if (it.kind == HomeApps.WIFI) {
                drawWifi(c, cx, cy - 1 * k, icon, ink);
                if (netVpn) drawLock(c, cx + 13 * k * s, cy + 9 * k * s, 0.62f * s, ink);
            } else if (it.kind == HomeApps.UPDATE) {
                Icons.draw(c, "sync", cx, cy, icon, ink);
                if (Updates.get(getContext()).available()) {
                    fill.setColor(UPDATE_DOT);
                    c.drawCircle(cx + hw * 0.62f, cy - hw * 0.62f, 7 * k * s, fill);
                }
            } else if (it.kind == HomeApps.INPUTS) {
                Icons.draw(c, "plug", cx, cy, icon, ink);
            } else if (it.kind == HomeApps.ANDROID_SETTINGS || it.kind == HomeApps.SETTINGS) {
                Icons.draw(c, "sliders", cx, cy, icon, ink);
            } else {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 26 * k * s, ink);
            }
        }
        drawButtonLabel(c);
    }

    /** The thin line over the app pills. */
    private void drawEditorialDivider(Canvas c) {
        edStroke.setColor(tone(0x241F1B18, 0x29EFE8DE));
        float y = 877 * k;
        c.drawLine(MARGIN * k, y, CONTENT_R * k, y, edStroke);
    }

    /**
     * An app as a pill: an outline, the icon in a soft circle and the name. Focused: the pill fills with the ink (cream
     * at night), the circle turns to the accent with a white icon. Drawn at rest size and scaled.
     */
    private void edPill(Canvas c, RectF base, float f, float l, HomeApps.Item it, boolean dim) {
        float fc = Ui.clamp(f), s = 1 + 0.03f * f + LIFT_GROW * l, r = base.height() / 2;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        if (fc > 0.01f || l > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 12 * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.18f, 0.4f) * Math.max(fc, Ui.clamp(l))));
            shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        }
        fill.setShader(null);
        if (fc > 0.01f) {
            fill.setColor(Ui.alpha(edInk(), fc));
            c.drawRoundRect(base, r, r, fill);
        }
        if (fc < 0.99f) {
            edStroke.setColor(Ui.alpha(tone(0x2E1F1B18, 0x33EFE8DE), 1 - fc));
            c.drawRoundRect(base, r, r, edStroke);
        }
        float d = base.height() - 28 * k, ccx = base.left + 14 * k + d / 2, ccy = base.centerY();
        fill.setColor(blend(tone(0x0F1F1B18, 0x1AEFE8DE), edAccent(), fc));
        c.drawCircle(ccx, ccy, d / 2, fill);
        int ink = blend(edInk(), 0xFFFFFFFF, fc);
        Icons.draw(c, iconOf(it), ccx, ccy, d * 0.5f, dim ? Ui.alpha(ink, 0.5f) : ink);
        int text = blend(edInk(), edPaper(), fc);
        edPill.setColor(dim ? Ui.alpha(text, 0.5f) : text);
        float tx = ccx + d / 2 + 20 * k, room = base.right - 24 * k - tx;
        String name = it.label == null ? "" : it.label;
        float wide = edPill.measureText(name);
        if (wide > room) edPill.setTextSize(Math.max(18 * k, 24 * k * room / wide));    // a long name shrinks before it is cut
        fitPaint.set(edPill);
        String label = TextUtils.ellipsize(name, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, tx, ccy + Ui.halfCap(edPill), edPill);
        edPill.setTextSize(24 * k);
        c.restore();
    }

    /** A grid app: the pill's look as a card (circle at the top left, the name at the bottom). */
    private void edGridTile(Canvas c, RectF base, float f, float l, HomeApps.Item it) {
        float fc = Ui.clamp(f), s = 1 + 0.04f * f + LIFT_GROW * l, r = 36 * k;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        if (fc > 0.01f || l > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 12 * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.18f, 0.4f) * Math.max(fc, Ui.clamp(l))));
            shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        }
        fill.setShader(null);
        fill.setColor(blend(tone(0x08000000, 0x0DFFFFFF), edInk(), fc));
        c.drawRoundRect(base, r, r, fill);
        if (fc < 0.99f) {
            edStroke.setColor(Ui.alpha(tone(0x2E1F1B18, 0x33EFE8DE), 1 - fc));
            c.drawRoundRect(base, r, r, edStroke);
        }
        float d = 60 * k, ccx = base.left + 22 * k + d / 2, ccy = base.top + 22 * k + d / 2;
        fill.setColor(blend(tone(0x0F1F1B18, 0x1AEFE8DE), edAccent(), fc));
        c.drawCircle(ccx, ccy, d / 2, fill);
        Icons.draw(c, iconOf(it), ccx, ccy, d * 0.5f, blend(edInk(), 0xFFFFFFFF, fc));
        edPill.setColor(blend(edInk(), edPaper(), fc));
        fitPaint.set(edPill);
        c.drawText(TextUtils.ellipsize(it.label == null ? "" : it.label, fitPaint, base.width() - 44 * k, TextUtils.TruncateAt.END).toString(),
                base.left + 24 * k, base.bottom - 26 * k, edPill);
        c.restore();
    }

    /** A source or shortcut under the grid: a dot (or a small icon), the name and its state, as in the Sources menu. */
    private void edSmallTile(Canvas c, RectF base, float f, HomeApps.Item it) {
        float fc = Ui.clamp(f), s = 1 + 0.04f * f, r = 26 * k;
        boolean input = it.kind == HomeApps.INPUT, live = it.kind == HomeApps.LIVE_TV, off = input && !it.connected && !it.standby;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        fill.setShader(null);
        fill.setColor(blend(tone(0x08000000, 0x0DFFFFFF), edInk(), fc));
        c.drawRoundRect(base, r, r, fill);
        if (fc < 0.99f) {
            edStroke.setColor(Ui.alpha(tone(0x2E1F1B18, 0x33EFE8DE), 1 - fc));
            c.drawRoundRect(base, r, r, edStroke);
        }
        int ink = blend(edInk(), edPaper(), fc);
        if (off) ink = Ui.alpha(ink, 0.45f);
        float x = base.left + 20 * k, y = base.top + 50 * k;
        if (!input && !live) {     // a shortcut: its icon on the first line, its name on the second
            Icons.draw(c, iconOf(it), x + 13 * k, y - 8 * k, 28 * k, ink);
            edPill.setColor(ink);
            edPill.setTextSize(23 * k);
            fitPaint.set(edPill);
            c.drawText(TextUtils.ellipsize(it.label, fitPaint, base.width() - 40 * k, TextUtils.TruncateAt.END).toString(), x, y + 40 * k, edPill);
            edPill.setTextSize(24 * k);
            c.restore();
            return;
        }
        {
            fill.setColor(live ? edAccent() : ink);
            if (input && !it.connected) {
                edStroke.setColor(ink);
                c.drawCircle(x + 6 * k, y - 8 * k, 6 * k, edStroke);
            } else {
                c.drawCircle(x + 6 * k, y - 8 * k, 6 * k, fill);
            }
        }
        float tx = x + 26 * k, room = base.right - 14 * k - tx;
        edPill.setColor(ink);
        edPill.setTextSize(23 * k);
        fitPaint.set(edPill);
        c.drawText(TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString(), tx, y, edPill);
        edPill.setTextSize(24 * k);
        String sub = live ? Ui.tr("Canais de TV", "TV channels") : !input ? null
                : it.connected ? Ui.tr("Conectado", "Connected") : it.standby ? Ui.tr("Em espera", "In standby") : Ui.tr("Livre", "Not in use");
        if (input && !it.label.equalsIgnoreCase("HDMI " + it.number)) sub = "HDMI " + it.number + " · " + sub.toLowerCase(Ui.locale());
        if (sub != null) {
            edPosterMeta.setColor(Ui.alpha(ink, 0.6f));
            edPosterMeta.setTextSize(17 * k);
            fitPaint.set(edPosterMeta);
            c.drawText(TextUtils.ellipsize(sub, fitPaint, room, TextUtils.TruncateAt.END).toString(), tx, y + 34 * k, edPosterMeta);
            edPosterMeta.setTextSize(19 * k);
        }
        c.restore();
    }

    /** The weather and markets cards: paper, a hairline, no glass. */
    private void edCard(Canvas c, RectF r) {
        float rad = 30 * k;
        fill.setShader(null);
        fill.setColor(tone(0xFFF6F1E9, 0xFF121110));
        c.drawRoundRect(r, rad, rad, fill);
        edStroke.setColor(tone(0x241F1B18, 0x26EFE8DE));
        c.drawRoundRect(r, rad, rad, edStroke);
    }

    // MARK: - Colors theme (Settings > Home settings > Theme > Colors): a wide picture, colored app tiles, quick settings

    private static final int CO_ORANGE = 0xFFFF9A3C, CO_GREEN = 0xFF34C759;
    /** Color pairs (top left, bottom right) of the app tiles, by the tile's icon; others take one by their name. */
    private static final String[] CO_ICONS = {"tv", "stack", "play", "broadcast", "list", "gamepad", "bag", "shield", "folder",
            "film", "music", "globe", "sparkles", "plug", "speaker", "monitor", "home"};
    private static final int[][] CO_PAIRS = {
        {0xFFE8834A, 0xFFB4471E}, {0xFFD4A62E, 0xFF7A5A10}, {0xFFE5505C, 0xFF9E1525}, {0xFF4A6BE6, 0xFF1E2E9A},
        {0xFF2FB0A5, 0xFF0D6B66}, {0xFF6A78F2, 0xFF3237B0}, {0xFF3FA66B, 0xFF1C6B3E}, {0xFF2E9BD6, 0xFF155E8E},
        {0xFF8C6BD9, 0xFF4E3394}, {0xFFD9566E, 0xFF8A2238}, {0xFF2FBF71, 0xFF16703F}, {0xFF4F8FE8, 0xFF24519A},
        {0xFF8A7BE8, 0xFF4B3FA6}, {0xFF6A78F2, 0xFF3237B0}, {0xFF2FB0A5, 0xFF0D6B66}, {0xFF5C6B7F, 0xFF2E3846},
        {0xFF5C6B7F, 0xFF2E3846},
    };
    private boolean colors;
    private Paint coTab, coTime, coTemp, coChip, coTitle, coMeta, coButton, coSection, coTile, coSmall, coSub;
    private LinearGradient coSkyBase, coHeroShade;
    private android.graphics.RadialGradient coSkyWarm;
    private float coSkyW, coShadeW;
    private final Map<String, LinearGradient> coTileShaders = new java.util.HashMap<>();
    private final RectF coRow = new RectF(), coShadow = new RectF();

    private void colorsText() {
        coTab = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        coTime = Ui.numbers(Ui.displayBold, 36 * k, 0xFFFFFFFF);
        coTemp = Ui.numbers(Ui.medium, 28 * k, 0xCCFFFFFF);
        coChip = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        coTitle = Ui.text(Ui.displayBold, 66 * k, 0xFFFFFFFF);
        coTitle.setLetterSpacing(-0.01f);
        coMeta = Ui.text(Ui.medium, 26 * k, 0xD9FFFFFF);
        coButton = Ui.text(Ui.semibold, 27 * k, 0xFF1C1C1E);
        coSection = Ui.text(Ui.displayBold, 31 * k, 0xFFFFFFFF);
        coTile = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
        coTile.setTextAlign(Paint.Align.CENTER);
        coSmall = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        coSub = Ui.text(Ui.medium, 18 * k, 0xB3FFFFFF);
        coTileShaders.clear();
    }

    /**
     * Layout: tabs (or the greeting) at the top left, the buttons in a capsule with the temperature and the time at the
     * top right, one wide picture; the other titles wait off the screen (the carousel brings them in).
     */
    private void layoutColors() {
        float x = MARGIN * k;
        for (int i = 0; i < tabCount(); i++) {
            float tw = coTab == null ? 120 * k : coTab.measureText(tabLabel(i));
            topRects[i].set(x, 45 * k, x + tw + 56 * k, 105 * k);
            x = topRects[i].right + 12 * k;
        }
        float b = 56 * k, right = (CONTENT_R - 210) * k, cy = 75 * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[B0 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + 6 * k;
        }
        float left = MARGIN * k, top = 139 * k, wide = (CONTENT_R - MARGIN) * k, ch = 427 * k, g = 40 * k;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + wide, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + wide, top + ch);
    }

    /** The apps: colored tiles in one row under "Your apps". */
    private void layoutColorsShelf() {
        int n = shelf.size();
        float gap = 26 * k, full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(271 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n)), th = 149 * k;
        float x = MARGIN * k, y = 653 * k;
        gridLeft = (w - (COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP) * k) / 2f;
        platterRect.set(x, y, x + n * tw + Math.max(0, n - 1) * gap, y + th);
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Background: warm dark brown, the hero's photo faintly through it. */
    private void drawColorsSky(Canvas c) {
        if (coSkyBase == null || coSkyW != w) {
            coSkyW = w;
            coSkyBase = new LinearGradient(0, 0, 0, h, new int[] {0xFF4C3A28, 0xFF2A2119, 0xFF121212},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
            coSkyWarm = new android.graphics.RadialGradient(w * 0.65f, h * 0.25f, w * 0.5f, 0x40B07A45, 0x00B07A45, Shader.TileMode.CLAMP);
        }
        android.graphics.ColorFilter v = veil(0.45f * Ui.clamp(drawer.value), 0xFF000000);
        fill.setColorFilter(v);
        fill.setColor(0xFF000000);
        fill.setShader(coSkyBase);
        c.drawRect(screen, fill);
        fill.setShader(coSkyWarm);
        c.drawRect(screen, fill);
        fill.setShader(null);
        fill.setColorFilter(null);
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawImage(c, glassPhoto(bgFrom), 0.4f * (bgTo == null ? 1 - e : 1));
        if (bgTo != null) drawImage(c, glassPhoto(bgTo), 0.4f * (fading ? e : 1));
        imagePaint.setColorFilter(null);
    }

    /** The top left (tabs, or the greeting with a single tab), the wide picture, and the apps' section title. */
    private void drawColorsTop(Canvas c) {
        if (tabsShown()) {
            float focus = Ui.clamp(topFocus[tab].value);
            for (int i = 0; i < tabCount(); i++) {
                RectF r = topRects[i];
                boolean on = i == tab;
                if (on) {
                    fill.setShader(null);
                    fill.setColor(blend(0x33FFFFFF, 0xFFFFFFFF, focus));
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                    fill.setColor(CO_ORANGE);
                    tmp.set(r.left + 28 * k, r.bottom + 8 * k, r.right - 28 * k, r.bottom + 13 * k);
                    c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
                }
                coTab.setColor(on ? blend(0xFFFFFFFF, 0xFF1C1C1E, focus) : 0xB3FFFFFF);
                c.drawText(tabLabel(i), r.left + 28 * k, r.centerY() + Ui.halfCap(coTab), coTab);
            }
        } else {
            coTab.setColor(0xFFFFFFFF);
            c.drawText(greeting(), MARGIN * k, 75 * k + Ui.halfCap(coTab), coTab);
        }
        if (plexTab() && cards.isEmpty()) {
            int st = plex.state();
            if (st != Plex.LOADING) {
                String msg = st == Plex.UNREACHABLE ? Ui.tr("Servidor Plex inacessível", "Plex server unreachable")
                        : Ui.tr("Nada para continuar no Plex", "Nothing to resume on Plex");
                c.drawText(msg, MARGIN * k, 360 * k, coMeta);
            }
        }
        if (!cards.isEmpty()) {
            drawLightCard(c, 0);
            if (slide.value != 0) {
                drawLightCard(c, 1);
                drawLightCard(c, 2);
            }
        }
        if (!shelf.isEmpty()) c.drawText(Ui.tr("Seus apps", "Your apps"), MARGIN * k, 626 * k, coSection);
    }

    /**
     * The wide picture: darkened toward the left, the chip, the title, the details, the progress in orange and the
     * "Resume" button; focused, a white outline. The page dots at the bottom right.
     */
    private void drawColorsCard(Canvas c, int i, RectF base, Recs.Pick p, boolean leaving) {
        float v = leaving ? 0 : cardFocus[i].value, f = Ui.clamp(v);
        float r = 40 * k;
        Backdrop b = p == null ? null : backdropOf(p);
        Bitmap img = b == null ? null : b.wide;
        if (img != null) {
            roundImage(c, img, base, r);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2A2420);
            c.drawRoundRect(base, r, r, fill);
        }
        if (coHeroShade == null || coShadeW != base.width()) {
            coShadeW = base.width();
            coHeroShade = new LinearGradient(0, 0, base.width() * 0.7f, 0, 0xD9000000, 0x00000000, Shader.TileMode.CLAMP);
        }
        c.save();
        c.translate(base.left, 0);
        fill.setShader(coHeroShade);
        tmp.set(0, base.top, base.width(), base.bottom);
        c.drawRoundRect(tmp, r, r, fill);
        fill.setShader(null);
        c.restore();
        if (f > 0.01f) {
            border.setColor(Ui.alpha(0xFFFFFFFF, f));
            float sw = border.getStrokeWidth();
            border.setStrokeWidth(4 * k);
            tmp.set(base);
            tmp.inset(-6 * k, -6 * k);
            c.drawRoundRect(tmp, r + 6 * k, r + 6 * k, border);
            border.setStrokeWidth(sw);
        }
        if (p == null) return;
        float x = base.left + 62 * k, room = base.width() * 0.55f;
        boolean fromPlex = Plex.is(p);
        String chip = !fromPlex ? Ui.tr("Para descobrir", "To discover")
                : p.recent ? Ui.tr("Adicionado recentemente no Plex", "Recently added on Plex") : Ui.tr("Continuar no Plex", "Continue on Plex");
        float ch = 48 * k, cy = base.top + 82 * k, cw = coChip.measureText(chip) + 76 * k;
        tmp.set(x, cy - ch / 2, x + cw, cy + ch / 2);
        fill.setColor(0x33FFFFFF);
        c.drawRoundRect(tmp, ch / 2, ch / 2, fill);
        border.setColor(0x33FFFFFF);
        c.drawRoundRect(tmp, ch / 2, ch / 2, border);
        fill.setColor(fromPlex ? 0xFFE5A00D : CO_ORANGE);
        c.drawCircle(x + 26 * k, cy, 6 * k, fill);
        c.drawText(chip, x + 46 * k, cy + Ui.halfCap(coChip), coChip);
        float size = 66 * k;
        coTitle.setTextSize(size);
        while (coTitle.measureText(p.title) > room && size > 44 * k) {
            size -= 3 * k;
            coTitle.setTextSize(size);
        }
        fitPaint.set(coTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, room, TextUtils.TruncateAt.END).toString(), x - Ui.leftBearing(coTitle, p.title),
                base.top + 186 * k, coTitle);
        coTitle.setTextSize(66 * k);
        if (p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(coMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, base.top + 236 * k, coMeta);
        }
        float bx = x, by = base.top + 300 * k;
        if (p.progress >= 0) {
            float bw = 420 * k, bh = 7 * k, py = base.top + 262 * k;
            tmp.set(x, py, x + bw, py + bh);
            fill.setColor(0x4DFFFFFF);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            tmp.right = tmp.left + Math.max(bh, bw * p.progress);
            fill.setColor(CO_ORANGE);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        }
        String label = fromPlex && p.progress >= 0 ? Ui.tr("Retomar", "Resume") : Ui.tr("Assistir", "Watch");
        float bh = 78 * k, bw = coButton.measureText(label) + 118 * k;
        tmp.set(bx, by, bx + bw, by + bh);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        clip.reset();
        float tx = bx + 44 * k, tcy = by + bh / 2;
        clip.moveTo(tx, tcy - 10 * k);
        clip.lineTo(tx + 16 * k, tcy);
        clip.lineTo(tx, tcy + 10 * k);
        clip.close();
        fill.setColor(0xFF1C1C1E);
        c.drawPath(clip, fill);
        c.drawText(label, tx + 34 * k, tcy + Ui.halfCap(coButton), coButton);
        // Page dots: the shown title is a short bar.
        int n = Math.min(8, cards.size());
        if (i == 0 && n > 1 && !leaving) {
            float dot = 9 * k, gap = 10 * k, bar = 34 * k, y = base.bottom - 42 * k;
            float xr = base.right - 44 * k, xx = xr - (bar + (n - 1) * (dot + gap));
            int cur = cards.isEmpty() ? 0 : plexStart % Math.max(1, cards.size());
            for (int j = 0; j < n; j++) {
                boolean on = j == Math.min(cur, n - 1);
                float wdt = on ? bar : dot;
                tmp.set(xx, y - dot / 2, xx + wdt, y + dot / 2);
                fill.setColor(on ? 0xFFFFFFFF : 0x80FFFFFF);
                c.drawRoundRect(tmp, dot / 2, dot / 2, fill);
                xx += wdt + gap;
            }
        }
    }

    /** The top buttons in one dark capsule, the temperature and the time after it. */
    private void drawColorsButtons(Canvas c) {
        int n = lightButtons.size();
        if (n > 0) {
            float pad = 8 * k;
            RectF first = topRects[B0], last = topRects[B0 + n - 1];
            groupRect.set(first.left - pad - 8 * k, first.top - pad, last.right + pad + 8 * k, last.bottom + pad);
            float rad = groupRect.height() / 2;
            fill.setShader(null);
            fill.setColor(0x59000000);
            c.drawRoundRect(groupRect, rad, rad, fill);
            glassStroke.setColor(0x26FFFFFF);
            c.drawRoundRect(groupRect, rad, rad, glassStroke);
            for (int i = 0; i < n; i++) {
                HomeApps.Item it = lightButtons.get(i);
                RectF r = topRects[B0 + i];
                float f = Ui.clamp(topFocus[B0 + i].value), hw = r.width() / 2 * (1 + 0.06f * f), cx = r.centerX(), cy = r.centerY();
                if (f > 0.01f) {
                    fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                    c.drawCircle(cx, cy, hw, fill);
                }
                int ink = blend(0xEBFFFFFF, 0xFF1C1C1E, f);
                float icon = 28 * k;
                if (it.kind == HomeApps.POWER) {
                    drawPower(c, cx, cy, 26 * k, ink);
                } else if (it.kind == HomeApps.AMBILIGHT) {
                    drawAmbilight(c, cx, cy, icon, ink);
                } else if (it.kind == HomeApps.WIFI) {
                    drawWifi(c, cx, cy - 1 * k, icon, ink);
                    if (netVpn) drawLock(c, cx + 12 * k, cy + 8 * k, 0.58f, ink);
                } else if (it.kind == HomeApps.UPDATE) {
                    Icons.draw(c, "sync", cx, cy, icon, ink);
                    if (Updates.get(getContext()).available()) {
                        fill.setColor(UPDATE_DOT);
                        c.drawCircle(cx + hw * 0.62f, cy - hw * 0.62f, 6 * k, fill);
                    }
                } else if (it.kind == HomeApps.INPUTS) {
                    Icons.draw(c, "plug", cx, cy, icon, ink);
                } else if (it.kind == HomeApps.ANDROID_SETTINGS || it.kind == HomeApps.SETTINGS) {
                    Icons.draw(c, "sliders", cx, cy, icon, ink);
                } else {
                    Symbols.draw(getContext(), c, it.symbol, cx, cy, 24 * k, ink);
                }
            }
        }
        float right = CONTENT_R * k, base = 75 * k + Ui.halfCap(coTime);
        float tw = coTime.measureText(clockText);
        c.drawText(clockText, right - tw, base, coTime);
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            String t = HomeData.degrees(wx.temp);
            c.drawText(t, right - tw - 18 * k - coTemp.measureText(t), base, coTemp);
        }
        // No name capsule over the buttons here: they sit at the very top of the screen.
    }

    /** Under the apps: "Keep watching" and the next titles' pictures (the row goes on below the screen). */
    private void drawColorsContinue(Canvas c) {
        int n = cards.size();
        if (n < 2) return;
        c.drawText(Ui.tr("Continuar assistindo", "Keep watching"), MARGIN * k, 874 * k, coSection);
        float x = MARGIN * k, cw = 420 * k, gap = 26 * k, top = 901 * k, r = 28 * k;
        for (int j = 1; j < Math.min(n, 5); j++) {
            Recs.Pick p = cards.get((plexStart + j) % n);
            coRow.set(x, top, x + cw, top + cw * 9 / 16);
            Backdrop b = backdropOf(p);
            if (b != null && b.wide != null) {
                roundImage(c, b.wide, coRow, r);
            } else {
                fill.setShader(null);
                fill.setColor(0xFF2A2724);
                c.drawRoundRect(coRow, r, r, fill);
            }
            x += cw + gap;
        }
    }

    /** The two colors of an app's tile (cached as a gradient for the tile's height). */
    private LinearGradient coShader(HomeApps.Item it, float height) {
        String icon = iconOf(it);
        String key = icon + "|" + (it.key == null ? "" : it.key) + "|" + Math.round(height);
        LinearGradient g = coTileShaders.get(key);
        if (g != null) return g;
        int at = java.util.Arrays.asList(CO_ICONS).indexOf(icon);
        if (at < 0 || "grid".equals(icon)) at = Math.abs((it.label == null ? "" : it.label).hashCode()) % CO_PAIRS.length;
        int[] pair = CO_PAIRS[at];
        g = new LinearGradient(0, -height / 2, height * 0.6f, height / 2, pair[0], pair[1], Shader.TileMode.CLAMP);
        coTileShaders.put(key, g);
        return g;
    }

    /** An app in its color: the icon and the name centered. Focused: bigger, a white outline, a glow of its color. */
    private void colorTile(Canvas c, RectF base, float f, float l, HomeApps.Item it, boolean dim) {
        float fc = Ui.clamp(f), s = 1 + 0.08f * f + LIFT_GROW * l, r = 24 * k;
        float cx = base.centerX(), cy = base.centerY(), bw = base.width(), bh = base.height();
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        if (fc > 0.01f || l > 0.01f) {
            coShadow.set(tmp);
            coShadow.offset(0, 14 * k);
            int[] pair = CO_PAIRS[Math.max(0, java.util.Arrays.asList(CO_ICONS).indexOf(iconOf(it)))];
            shadowDeep.setColor(Ui.alpha(pair[1], 0.7f * Math.max(fc, Ui.clamp(l))));
            shadows.draw(c, coShadow, r, DEEP_BLUR * k, shadowDeep);
        }
        tileFill.setShader(coShader(it, bh));
        tileFill.setAlpha(dim ? 110 : 255);
        c.drawRoundRect(tmp, r, r, tileFill);
        tileFill.setShader(null);
        tileFill.setAlpha(255);
        if (fc > 0.01f) {
            float sw = border.getStrokeWidth();
            border.setColor(Ui.alpha(0xFFFFFFFF, fc));
            border.setStrokeWidth(4 * k / s);
            float o = 4 * k / s;
            tmp.inset(-o, -o);
            c.drawRoundRect(tmp, r + o, r + o, border);
            tmp.inset(o, o);
            border.setStrokeWidth(sw);
        }
        Icons.draw(c, iconOf(it), 0, -bh * 0.13f, Math.min(46 * k, bh * 0.3f), 0xFFFFFFFF);
        fitPaint.set(coTile);
        c.drawText(TextUtils.ellipsize(it.label == null ? "" : it.label, fitPaint, bw - 30 * k, TextUtils.TruncateAt.END).toString(),
                0, bh * 0.28f, coTile);
        c.restore();
    }

    /** A source or shortcut under the grid: a colored square with the icon, the name and its state. */
    private void colorSmallTile(Canvas c, RectF base, float f, HomeApps.Item it) {
        float fc = Ui.clamp(f), s = 1 + 0.05f * f, r = 22 * k;
        boolean input = it.kind == HomeApps.INPUT, live = it.kind == HomeApps.LIVE_TV, off = input && !it.connected && !it.standby;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        if (fc > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 10 * k * fc);
            shadow.setColor(Ui.alpha(0xFF000000, 0.4f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        fill.setShader(null);
        fill.setColor(blend(0x1AFFFFFF, 0xFFFFFFFF, fc));
        c.drawRoundRect(base, r, r, fill);
        float b = 50 * k, bx = base.left + 16 * k, by = base.top + 16 * k;
        badge.set(bx, by, bx + b, by + b);
        tileFill.setShader(coShader(it, b));
        tileFill.setAlpha(off ? 90 : 255);
        c.save();
        c.translate(badge.centerX(), badge.centerY());
        tmp.set(-b / 2, -b / 2, b / 2, b / 2);
        c.drawRoundRect(tmp, 14 * k, 14 * k, tileFill);
        c.restore();
        tileFill.setShader(null);
        tileFill.setAlpha(255);
        Icons.draw(c, iconOf(it), badge.centerX(), badge.centerY(), b * 0.56f, off ? 0x99FFFFFF : 0xFFFFFFFF);
        int ink = blend(off ? 0x80FFFFFF : 0xFFFFFFFF, 0xFF1C1C1E, fc);
        coSmall.setColor(ink);
        fitPaint.set(coSmall);
        float tx = base.left + 18 * k, room = base.width() - 34 * k;
        String sub = live ? Ui.tr("Canais de TV", "TV channels") : !input ? null
                : it.connected ? Ui.tr("Conectado", "Connected") : it.standby ? Ui.tr("Em espera", "In standby") : Ui.tr("Livre", "Not in use");
        c.drawText(TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString(), tx,
                sub == null ? base.bottom - 22 * k : base.top + 92 * k, coSmall);
        if (sub != null) {
            float sx = tx;
            if (input && it.connected) {
                fill.setColor(CO_GREEN);
                c.drawCircle(sx + 5 * k, base.top + 110 * k, 5 * k, fill);
                sx += 16 * k;
            }
            coSub.setColor(Ui.alpha(ink, 0.7f));
            fitPaint.set(coSub);
            c.drawText(TextUtils.ellipsize(sub, fitPaint, room - (sx - tx), TextUtils.TruncateAt.END).toString(), sx,
                    base.top + 110 * k + Ui.halfCap(coSub), coSub);
        }
        c.restore();
    }

    // MARK: - Minimal theme (Settings > Home settings > Theme > Minimal): black, lime, a huge clock, plain names

    static final int MN_LIME = 0xFFD7F542;
    private static final int MN_GRAY = 0x80FFFFFF, MN_DIM = 0x99FFFFFF, MN_LINE = 0x29FFFFFF;
    private boolean minimal;
    private Paint mnClock, mnGreet, mnMono, mnMonoLime, mnTitle, mnMeta, mnButton, mnItem, mnItemMeta, mnApp, mnAppsLabel, mnName;
    private final Paint mnStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void minimalText() {
        mnClock = Ui.numbers(Ui.light, 214 * k, 0xFFFFFFFF);
        mnClock.setLetterSpacing(-0.02f);
        mnGreet = Ui.text(Ui.regular, 32 * k, 0xFFFFFFFF);
        mnMono = Ui.text(Ui.mono, 19 * k, MN_DIM);
        mnMono.setLetterSpacing(0.14f);
        mnMonoLime = Ui.text(Ui.monoMedium, 19 * k, MN_LIME);
        mnMonoLime.setLetterSpacing(0.14f);
        mnTitle = Ui.text(Ui.semibold, 56 * k, 0xFFFFFFFF);
        mnTitle.setLetterSpacing(-0.01f);
        mnMeta = Ui.text(Ui.regular, 23 * k, 0xCCFFFFFF);
        mnButton = Ui.text(Ui.semibold, 25 * k, 0xFF000000);
        mnItem = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
        mnItemMeta = Ui.text(Ui.mono, 16 * k, MN_DIM);
        mnItemMeta.setLetterSpacing(0.14f);
        mnApp = Ui.text(Ui.light, 48 * k, MN_GRAY);
        mnAppsLabel = Ui.text(Ui.mono, 17 * k, MN_DIM);
        mnAppsLabel.setLetterSpacing(0.16f);
        mnName = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
        mnStroke.setStyle(Paint.Style.STROKE);
        mnStroke.setStrokeWidth(1.5f * k);
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** Layout: the picture on the right, the next two titles as a list under it; bare icons at the top right. */
    private void layoutMinimal() {
        float b = 60 * k, right = CONTENT_R * k, cy = 88 * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[B0 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + (lightButtons.get(i).kind == HomeApps.POWER ? 34 : 10) * k;
        }
        float left = 732 * k, rightEdge = CONTENT_R * k, mid = (left + rightEdge) / 2;
        cardRects[0].set(left, 141 * k, rightEdge, 780 * k);
        cardRects[1].set(left, 796 * k, mid - 12 * k, 880 * k);
        cardRects[2].set(mid + 12 * k, 796 * k, rightEdge, 880 * k);
    }

    /** The apps: their names in a row after "APPS" (smaller if they don't fit). */
    private void layoutMinimalShelf() {
        int n = shelf.size();
        float start = 240 * k, room = CONTENT_R * k - start, gap = 68 * k, size = 48 * k;
        gridLeft = (w - (COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP) * k) / 2f;
        if (mnApp != null) {
            while (size > 30 * k) {
                mnApp.setTextSize(size);
                float total = Math.max(0, n - 1) * gap;
                for (HomeApps.Item it : shelf) total += mnApp.measureText(it.label == null ? "" : it.label);
                if (total <= room) break;
                size -= 2 * k;
                gap = Math.max(40 * k, gap - 2 * k);
            }
        }
        float x = start, top = 958 * k, h = 96 * k;
        for (int i = 0; i < n; i++) {
            String label = shelf.get(i).label == null ? "" : shelf.get(i).label;
            float tw = mnApp == null ? 200 * k : mnApp.measureText(label);
            tileRects[i].set(x, top, x + tw, top + h);
            x += tw + gap;
        }
        platterRect.set(MARGIN * k, top, x, top + h);
        crispLogos.clear();
    }

    /** The clock, the greeting, the date line; then the shown title's details beside the picture. */
    private void drawMinimalTop(Canvas c) {
        float x = MARGIN * k;
        c.drawText(clockText, x - Ui.leftBearing(mnClock, clockText), 226 * k, mnClock);
        c.drawText(greeting(), x, 305 * k, mnGreet);
        // Short: "MON. 26 OCT.   17° SUNNY   19° / 10°", kept left of the picture.
        StringBuilder line = new StringBuilder(Ui.once("mnDate", System.currentTimeMillis() / 3_600_000L,
                () -> new java.text.SimpleDateFormat("EEE d MMM", Ui.locale()).format(new java.util.Date())));
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            line.append("   ").append(HomeData.degrees(wx.temp)).append(' ').append(HomeData.condition(wx.code, wx.isDay))
                    .append("   ").append(HomeData.degrees(wx.max)).append(" / ").append(HomeData.degrees(wx.min));
        }
        fitPaint.set(mnMono);
        c.drawText(TextUtils.ellipsize(line.toString().toUpperCase(Ui.locale()), fitPaint, 660 * k - x, TextUtils.TruncateAt.END).toString(),
                x, 352 * k, mnMono);
        if (plexTab() && cards.isEmpty()) {
            int st = plex.state();
            if (st != Plex.LOADING) {
                String msg = st == Plex.UNREACHABLE ? Ui.tr("Servidor Plex inacessível", "Plex server unreachable")
                        : Ui.tr("Nada para continuar no Plex", "Nothing to resume on Plex");
                c.drawText(msg, x, 669 * k, mnMeta);
            }
            return;
        }
        for (int i = 0; i < Math.min(3, cardSlots()); i++) drawLightCardAt(c, i, cardRects[i], cardAt(i), false);
        Recs.Pick p = cardAt(0);
        if (p == null) return;
        boolean fromPlex = Plex.is(p);
        float f = Ui.clamp(cardFocus[0].value), room = 660 * k - x;
        String kicker = two(plexStart % Math.max(1, cards.size()) + 1) + " — " + (!fromPlex ? Ui.tr("Para descobrir", "To discover")
                : p.recent ? Ui.tr("Adicionado recentemente no Plex", "Recently added on Plex") : Ui.tr("Continuar no Plex", "Continue on Plex"));
        fill.setShader(null);
        fill.setColor(MN_LIME);
        tmp.set(x, 600 * k, x + 13 * k, 613 * k);
        c.drawRect(tmp, fill);
        fitPaint.set(mnMonoLime);
        c.drawText(TextUtils.ellipsize(kicker.toUpperCase(Ui.locale()), fitPaint, room - 28 * k, TextUtils.TruncateAt.END).toString(),
                x + 28 * k, 613 * k, mnMonoLime);
        float size = 56 * k;
        mnTitle.setTextSize(size);
        while (mnTitle.measureText(p.title) > room && size > 38 * k) {
            size -= 2 * k;
            mnTitle.setTextSize(size);
        }
        fitPaint.set(mnTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, room, TextUtils.TruncateAt.END).toString(), x - Ui.leftBearing(mnTitle, p.title),
                669 * k, mnTitle);
        mnTitle.setTextSize(56 * k);
        if (p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(mnMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, 725 * k, mnMeta);
        }
        float py = 765 * k;
        fill.setColor(MN_LINE);
        tmp.set(x, py - 1.5f * k, x + room, py + 1.5f * k);
        c.drawRect(tmp, fill);
        if (p.progress >= 0) {
            fill.setColor(MN_LIME);
            tmp.set(x, py - 2 * k, x + room * p.progress, py + 2 * k);
            c.drawRect(tmp, fill);
        }
        String label = fromPlex && p.progress >= 0 ? Ui.tr("Retomar", "Resume") : Ui.tr("Assistir", "Watch");
        float bh = 72 * k, bw = mnButton.measureText(label) + 112 * k, by = 798 * k;
        tmp.set(x, by, x + bw, by + bh);
        fill.setColor(MN_LIME);
        c.drawRect(tmp, fill);
        if (f > 0.01f) {      // focused: an outline around the button too
            mnStroke.setColor(Ui.alpha(0xFFFFFFFF, f));
            mnStroke.setStrokeWidth(3 * k);
            tmp.inset(-7 * k, -7 * k);
            c.drawRect(tmp, mnStroke);
            tmp.inset(7 * k, 7 * k);
            mnStroke.setStrokeWidth(1.5f * k);
        }
        clip.reset();
        float tx = x + 34 * k, tcy = by + bh / 2;
        clip.moveTo(tx, tcy - 10 * k);
        clip.lineTo(tx + 16 * k, tcy);
        clip.lineTo(tx, tcy + 10 * k);
        clip.close();
        fill.setColor(0xFF000000);
        c.drawPath(clip, fill);
        c.drawText(label, tx + 34 * k, tcy + Ui.halfCap(mnButton), mnButton);
    }

    /**
     * The picture (square corners, the position "01 / 12" in its corner, a lime outline when focused), or one of the
     * next titles: its number, a small picture, the title and its details in small capitals.
     */
    private void drawMinimalCard(Canvas c, int i, RectF base, Recs.Pick p, boolean leaving) {
        float f = leaving ? 0 : Ui.clamp(cardFocus[i].value);
        Backdrop b = p == null ? null : backdropOf(p);
        Bitmap img = b == null ? null : b.wide;
        if (i == 0) {
            if (img != null) {
                roundImage(c, img, base, 0);
            } else {
                fill.setShader(null);
                fill.setColor(0xFF161616);
                c.drawRect(base, fill);
            }
            int n = cards.size();
            if (n > 1) {
                String pos = two(plexStart % n + 1) + " / " + two(n);
                mnItemMeta.setColor(0xB3FFFFFF);
                c.drawText(pos, base.right - 26 * k - mnItemMeta.measureText(pos), base.bottom - 26 * k, mnItemMeta);
                mnItemMeta.setColor(MN_DIM);
            }
            if (f > 0.01f) {
                mnStroke.setColor(Ui.alpha(MN_LIME, f));
                mnStroke.setStrokeWidth(4 * k);
                tmp.set(base);
                tmp.inset(-8 * k, -8 * k);
                c.drawRect(tmp, mnStroke);
                mnStroke.setStrokeWidth(1.5f * k);
            }
            return;
        }
        if (p == null) return;
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x17FFFFFF, f));
            c.drawRect(base, fill);
            fill.setColor(Ui.alpha(MN_LIME, f));
            tmp.set(base.left, base.top, base.left + 5 * k, base.bottom);
            c.drawRect(tmp, fill);
        }
        int n = cards.size();
        String num = two((plexStart + i) % Math.max(1, n) + 1);
        Paint np = f > 0.5f ? mnMonoLime : mnMono;
        c.drawText(num, base.left + 22 * k, base.centerY() + Ui.halfCap(np), np);
        float ix = base.left + 74 * k, iw = 108 * k, ih = 64 * k;
        coRow.set(ix, base.centerY() - ih / 2, ix + iw, base.centerY() + ih / 2);
        if (img != null) {
            roundImage(c, img, coRow, 0);
        } else {
            fill.setColor(0xFF1C1C1C);
            c.drawRect(coRow, fill);
        }
        float tx = coRow.right + 26 * k, room = base.right - 16 * k - tx;
        fitPaint.set(mnItem);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, room, TextUtils.TruncateAt.END).toString(), tx, base.centerY() - 4 * k, mnItem);
        String meta = Plex.is(p) ? p.shortMeta : p.meta;
        if (meta != null && !meta.isEmpty()) {
            fitPaint.set(mnItemMeta);
            c.drawText(TextUtils.ellipsize(meta.toUpperCase(Ui.locale()), fitPaint, room, TextUtils.TruncateAt.END).toString(),
                    tx, base.centerY() + 28 * k, mnItemMeta);
        }
    }

    /** Bare icons at the top right, a thin line before Power; the focused one in white with a lime bar under it. */
    private void drawMinimalButtons(Canvas c) {
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[B0 + i];
            float f = Ui.clamp(topFocus[B0 + i].value), cx = r.centerX(), cy = r.centerY();
            if (it.kind == HomeApps.POWER && i > 0) {
                float lx = (topRects[B0 + i - 1].right + r.left) / 2;
                mnStroke.setColor(MN_LINE);
                c.drawLine(lx, cy - 20 * k, lx, cy + 20 * k, mnStroke);
            }
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(MN_LIME, f));
                tmp.set(cx - 18 * k, r.bottom + 4 * k, cx + 18 * k, r.bottom + 8 * k);
                c.drawRect(tmp, fill);
            }
            int ink = blend(0xB3FFFFFF, 0xFFFFFFFF, f);
            float icon = 30 * k;
            if (it.kind == HomeApps.POWER) {
                drawPower(c, cx, cy, 28 * k, ink);
            } else if (it.kind == HomeApps.AMBILIGHT) {
                drawAmbilight(c, cx, cy, icon, ink);
            } else if (it.kind == HomeApps.WIFI) {
                drawWifi(c, cx, cy - 1 * k, icon, ink);
                if (netVpn) drawLock(c, cx + 13 * k, cy + 9 * k, 0.6f, ink);
            } else if (it.kind == HomeApps.UPDATE) {
                Icons.draw(c, "sync", cx, cy, icon, ink);
                if (Updates.get(getContext()).available()) {
                    fill.setColor(MN_LIME);
                    c.drawRect(cx + 10 * k, cy - 18 * k, cx + 18 * k, cy - 10 * k, fill);
                }
            } else if (it.kind == HomeApps.INPUTS) {
                Icons.draw(c, "plug", cx, cy, icon, ink);
            } else if (it.kind == HomeApps.ANDROID_SETTINGS || it.kind == HomeApps.SETTINGS) {
                Icons.draw(c, "sliders", cx, cy, icon, ink);
            } else {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 26 * k, ink);
            }
        }
    }

    /** The line across the screen and "APPS" before the names. */
    private void drawMinimalShelfFrame(Canvas c) {
        mnStroke.setColor(MN_LINE);
        c.drawLine(MARGIN * k, 912 * k, CONTENT_R * k, 912 * k, mnStroke);
        c.drawText(Ui.tr("Apps", "Apps").toUpperCase(Ui.locale()), MARGIN * k, 1013 * k, mnAppsLabel);
    }

    /** An app as its name: gray, white when focused with a lime line under it. */
    private void mnAppName(Canvas c, RectF base, float f, float l, HomeApps.Item it, boolean dim) {
        float fc = Ui.clamp(Math.max(f, l));
        mnApp.setColor(dim ? 0x4DFFFFFF : blend(MN_GRAY, 0xFFFFFFFF, fc));
        c.drawText(it.label == null ? "" : it.label, base.left, 1013 * k + 14 * k, mnApp);
        if (fc > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(MN_LIME, fc));
            tmp.set(base.left, 1045 * k, base.right, 1050 * k);
            c.drawRect(tmp, fill);
        }
    }

    /** A grid app: a hairline box, its number at the top left and its name at the bottom; focused, lime and white. */
    private void mnGridTile(Canvas c, RectF base, float f, float l, HomeApps.Item it, int index) {
        float fc = Ui.clamp(Math.max(f, l));
        fill.setShader(null);
        if (fc > 0.01f) {
            fill.setColor(Ui.alpha(0x14FFFFFF, fc));
            c.drawRect(base, fill);
        }
        mnStroke.setColor(blend(MN_LINE, MN_LIME, fc));
        mnStroke.setStrokeWidth((1.5f + 1.5f * fc) * k);
        c.drawRect(base, mnStroke);
        mnStroke.setStrokeWidth(1.5f * k);
        Paint np = fc > 0.5f ? mnMonoLime : mnMono;
        c.drawText(two(index + 1), base.left + 22 * k, base.top + 40 * k, np);
        mnName.setColor(blend(0xCCFFFFFF, 0xFFFFFFFF, fc));
        mnName.setTextSize(28 * k);
        fitPaint.set(mnName);
        c.drawText(TextUtils.ellipsize(it.label == null ? "" : it.label, fitPaint, base.width() - 44 * k, TextUtils.TruncateAt.END).toString(),
                base.left + 22 * k, base.bottom - 26 * k, mnName);
        mnName.setTextSize(25 * k);
    }

    /** A source or shortcut: hairline box, number, name, and its port and state in small capitals with a square. */
    private void mnSmallTile(Canvas c, RectF base, float f, HomeApps.Item it, int index) {
        float fc = Ui.clamp(f);
        boolean input = it.kind == HomeApps.INPUT, live = it.kind == HomeApps.LIVE_TV, off = input && !it.connected && !it.standby;
        fill.setShader(null);
        if (fc > 0.01f) {
            fill.setColor(Ui.alpha(0x14FFFFFF, fc));
            c.drawRect(base, fill);
            fill.setColor(Ui.alpha(MN_LIME, fc));
            tmp.set(base.left, base.top, base.left + 5 * k, base.bottom);
            c.drawRect(tmp, fill);
        }
        mnStroke.setColor(MN_LINE);
        c.drawRect(base, mnStroke);
        Paint np = fc > 0.5f ? mnMonoLime : mnMono;
        float x = base.left + 20 * k;
        c.drawText(two(index + 1), x, base.top + 36 * k, np);
        mnName.setColor(off ? 0x66FFFFFF : 0xFFFFFFFF);
        float room = base.width() - 36 * k, wide = mnName.measureText(it.label);
        if (wide > room) mnName.setTextSize(Math.max(19 * k, 25 * k * room / wide));    // a long name shrinks before it is cut
        fitPaint.set(mnName);
        c.drawText(TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString(), x, base.top + 76 * k, mnName);
        mnName.setTextSize(25 * k);
        String sub = live ? Ui.tr("Canais de TV", "TV channels") : !input ? null
                : it.connected ? Ui.tr("Conectado", "Connected") : it.standby ? Ui.tr("Em espera", "In standby") : Ui.tr("Livre", "Not in use");
        if (sub == null) return;
        float sx = x, sy = base.top + 106 * k;
        if (input && (it.connected || it.standby)) {
            tmp.set(sx, sy - 11 * k, sx + 10 * k, sy - 1 * k);
            if (it.connected) {
                fill.setColor(MN_LIME);
                c.drawRect(tmp, fill);
            } else {
                mnStroke.setColor(MN_DIM);
                c.drawRect(tmp, mnStroke);
            }
            sx += 18 * k;
        }
        mnItemMeta.setColor(off ? 0x4DFFFFFF : MN_DIM);
        fitPaint.set(mnItemMeta);
        c.drawText(TextUtils.ellipsize(sub.toUpperCase(Ui.locale()), fitPaint, base.right - 14 * k - sx, TextUtils.TruncateAt.END).toString(),
                sx, sy, mnItemMeta);
        mnItemMeta.setColor(MN_DIM);
    }

    /** Weather and markets: a hairline box on the black. */
    private void mnCard(Canvas c, RectF r) {
        mnStroke.setColor(MN_LINE);
        c.drawRect(r, mnStroke);
    }

    // MARK: - HUD theme (Settings > Home settings > Theme > HUD): night blue grid, cyan, cut corners

    static final int HUD_CYAN = 0xFF3CD3F5, HUD_PANEL = 0xFF141B26, HUD_LINE = 0x3335D0F0;
    private static final int HUD_BG = 0xFF0A0F18, HUD_DIM = 0x99E6F4FF;
    private boolean hud;
    private Paint hdClock, hdGreet, hdMono, hdMonoCyan, hdTitle, hdButton, hdRingNum, hdRingSub, hdCardTitle, hdTile;
    private final Paint hdStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hudPath = new Path();
    private LinearGradient hdShade;
    private final RectF hdRect = new RectF();
    private float hdShadeH;

    private void hudText() {
        hdClock = Ui.numbers(Ui.wide, 78 * k, 0xFFFFFFFF);
        hdGreet = Ui.text(Ui.wide, 24 * k, 0xFFFFFFFF);
        hdGreet.setLetterSpacing(0.18f);
        hdMono = Ui.text(Ui.mono, 19 * k, HUD_DIM);
        hdMono.setLetterSpacing(0.12f);
        hdMonoCyan = Ui.text(Ui.monoMedium, 19 * k, HUD_CYAN);
        hdMonoCyan.setLetterSpacing(0.12f);
        hdTitle = Ui.text(Ui.bold, 62 * k, 0xFFFFFFFF);
        hdTitle.setLetterSpacing(-0.02f);
        hdButton = Ui.text(Ui.bold, 24 * k, 0xFF06121A);
        hdRingNum = Ui.numbers(Ui.wide, 32 * k, 0xFFFFFFFF);
        hdRingNum.setTextAlign(Paint.Align.CENTER);
        hdRingSub = Ui.text(Ui.mono, 13 * k, HUD_DIM);
        hdRingSub.setLetterSpacing(0.14f);
        hdRingSub.setTextAlign(Paint.Align.CENTER);
        hdCardTitle = Ui.text(Ui.bold, 25 * k, 0xFFFFFFFF);
        hdTile = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
        hdStroke.setStyle(Paint.Style.STROKE);
        hdStroke.setStrokeWidth(1.5f * k);
        hdStroke.setStrokeJoin(Paint.Join.MITER);
    }

    /** hudPath: `r` with its top-left and bottom-right corners cut (by `tl` and `br`). */
    private void hudShape(RectF r, float tl, float br) {
        hudPath.reset();
        hudPath.moveTo(r.left + tl, r.top);
        hudPath.lineTo(r.right, r.top);
        hudPath.lineTo(r.right, r.bottom - br);
        hudPath.lineTo(r.right - br, r.bottom);
        hudPath.lineTo(r.left, r.bottom);
        hudPath.lineTo(r.left, r.top + tl);
        hudPath.close();
    }

    /** `image` cropped into hudPath (set for `cell`); on Android 9 to 11 as a shader, as roundImage does. */
    private void hudImage(Canvas c, Bitmap image, RectF cell) {
        coverMatrix(image, cell);
        if (!Ui.LEGACY) {
            c.save();
            c.clipPath(hudPath);
            c.drawBitmap(image, imageMatrix, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawPath(hudPath, imageFill);
        imageFill.setShader(null);
    }

    /** A panel: dark fill and a hairline (cyan and brighter when focused), with a cyan glow under it. */
    private void hudPanel(Canvas c, RectF r, float tl, float br, float f, int fillColor) {
        float fc = Ui.clamp(f);
        if (fc > 0.01f) {
            tmp.set(r);
            shadowDeep.setColor(Ui.alpha(HUD_CYAN, 0.35f * fc));
            shadows.draw(c, tmp, 6 * k, DEEP_BLUR * k, shadowDeep);
        }
        hudShape(r, tl, br);
        fill.setShader(null);
        fill.setColor(blend(fillColor, 0xFF0E1C28, fc));
        c.drawPath(hudPath, fill);
        hdStroke.setColor(blend(0x1FFFFFFF, HUD_CYAN, fc));
        hdStroke.setStrokeWidth((1.5f + 1.5f * fc) * k);
        c.drawPath(hudPath, hdStroke);
        hdStroke.setStrokeWidth(1.5f * k);
    }

    /** Layout: square buttons at the top right, the big picture on the left, the queue (two titles) on the right. */
    private void layoutHud() {
        float b = 66 * k, right = CONTENT_R * k, cy = 98 * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[B0 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + 13 * k;
        }
        cardRects[0].set(MARGIN * k, 171 * k, 1361 * k, 812 * k);
        cardRects[1].set(1398 * k, 213 * k, CONTENT_R * k, 445 * k);
        cardRects[2].set(1398 * k, 523 * k, CONTENT_R * k, 755 * k);
    }

    /** The apps: dark panels with cut corners in one row. */
    private void layoutHudShelf() {
        int n = shelf.size();
        float gap = 20 * k, full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(278 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n)), th = 144 * k;
        float x = MARGIN * k, y = 885 * k;
        gridLeft = (w - (COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP) * k) / 2f;
        platterRect.set(x, y, x + n * tw + Math.max(0, n - 1) * gap, y + th);
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Night blue with a faint cyan grid (drawn only when the background changes). */
    private void drawHudSky(Canvas c) {
        c.drawColor(HUD_BG);
        hdStroke.setColor(0x0F5AC8FA);
        hdStroke.setStrokeWidth(1 * k);
        float step = 48 * k;
        for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, hdStroke);
        for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, hdStroke);
        hdStroke.setStrokeWidth(1.5f * k);
    }

    /** The clock, a thin line, the greeting and the date line; then the cards and the queue's title. */
    private void drawHudTop(Canvas c) {
        float x = MARGIN * k;
        c.drawText(clockText, x - Ui.leftBearing(hdClock, clockText), 134 * k, hdClock);
        float lx = x + hdClock.measureText(clockText) + 40 * k;
        hdStroke.setColor(0x33FFFFFF);
        c.drawLine(lx, 64 * k, lx, 138 * k, hdStroke);
        float gx = lx + 36 * k;
        c.drawText(greeting().toUpperCase(Ui.locale()), gx, 92 * k, hdGreet);
        StringBuilder line = new StringBuilder(Ui.once("hudDate2", System.currentTimeMillis() / 3_600_000L,
                () -> new java.text.SimpleDateFormat("EEE", Ui.locale()).format(new java.util.Date()).replace(".", "") + " "
                        + new java.text.SimpleDateFormat("dd.MM", Ui.locale()).format(new java.util.Date())));
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            line.append(" — ").append(HomeData.degrees(wx.temp)).append(' ').append(HomeData.condition(wx.code, wx.isDay))
                    .append(" — ").append(HomeData.degrees(wx.max)).append(" / ").append(HomeData.degrees(wx.min));
        }
        fitPaint.set(hdMono);
        c.drawText(TextUtils.ellipsize(line.toString().toUpperCase(Ui.locale()), fitPaint, topRects[B0].left - 40 * k - gx,
                TextUtils.TruncateAt.END).toString(), gx, 128 * k, hdMono);
        if (plexTab() && cards.isEmpty()) {
            int st = plex.state();
            if (st != Plex.LOADING) {
                String msg = st == Plex.UNREACHABLE ? Ui.tr("Servidor Plex inacessível", "Plex server unreachable")
                        : Ui.tr("Nada para continuar no Plex", "Nothing to resume on Plex");
                c.drawText(msg, x, 480 * k, hdCardTitle);
            }
            return;
        }
        if (cards.size() > 1) {
            c.drawText(Ui.tr("Fila", "Queue").toUpperCase(Ui.locale()), cardRects[1].left, 196 * k, hdMono);
            String count = two(Math.min(2, cards.size() - 1));
            c.drawText(count, cardRects[1].right - hdMono.measureText(count), 196 * k, hdMono);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < cardSlots(); i++) if (i != top) drawLightCardAt(c, i, cardRects[i], cardAt(i), false);
        if (top >= 0 && top < cardSlots()) drawLightCardAt(c, top, cardRects[top], cardAt(top), false);
    }

    /**
     * The big picture: cut corners, cyan brackets, the title and its details at the bottom left with "Resume" and the
     * time left in a ring; or a queue card: the picture, its progress, the title and its details.
     */
    private void drawHudCard(Canvas c, int i, RectF base, Recs.Pick p, boolean leaving) {
        float f = leaving ? 0 : Ui.clamp(cardFocus[i].value);
        Backdrop b = p == null ? null : backdropOf(p);
        Bitmap img = b == null ? null : b.wide;
        float cut = 22 * k;
        if (f > 0.01f) {
            shadowDeep.setColor(Ui.alpha(HUD_CYAN, 0.3f * f));
            shadows.draw(c, base, 6 * k, DEEP_BLUR * k, shadowDeep);
        }
        hudShape(base, i == 0 ? cut : 0, cut);
        if (img != null) {
            hudImage(c, img, base);
        } else {
            fill.setShader(null);
            fill.setColor(HUD_PANEL);
            c.drawPath(hudPath, fill);
        }
        if (i == 0) {
            if (hdShade == null || hdShadeH != base.height()) {
                hdShadeH = base.height();
                hdShade = new LinearGradient(0, base.height(), 0, base.height() * 0.3f, 0xE6060A12, 0x00060A12, Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(0, base.top);
            fill.setShader(hdShade);
            hdRect.set(base.left, 0, base.right, base.height());
            hudShape(hdRect, cut, cut);
            c.drawPath(hudPath, fill);
            fill.setShader(null);
            c.restore();
            hudShape(base, cut, cut);
        }
        hdStroke.setColor(blend(HUD_LINE, HUD_CYAN, f));
        hdStroke.setStrokeWidth((1.5f + 1.5f * f) * k);
        c.drawPath(hudPath, hdStroke);
        hdStroke.setStrokeWidth(1.5f * k);
        if (p == null) return;
        if (i > 0) {
            float y = base.bottom + 18 * k;
            fill.setColor(0x26FFFFFF);
            tmp.set(base.left, y - 1.5f * k, base.right, y + 1.5f * k);
            c.drawRect(tmp, fill);
            if (p.progress >= 0) {
                fill.setColor(HUD_CYAN);
                tmp.right = base.left + base.width() * p.progress;
                c.drawRect(tmp, fill);
            }
            String meta = Plex.is(p) ? p.shortMeta : p.meta;
            if (meta != null) meta = meta.replace(Ui.tr("Faltam ", "Left: "), "");     // "S2 · E4 · 55 MIN", as in the queue's column
            float mw = 0;
            if (meta != null && !meta.isEmpty()) {
                fitPaint.set(hdMono);
                String m = TextUtils.ellipsize(meta.toUpperCase(Ui.locale()), fitPaint, base.width() * 0.5f, TextUtils.TruncateAt.END).toString();
                mw = hdMono.measureText(m);
                c.drawText(m, base.right - mw, base.bottom + 56 * k, hdMono);
            }
            fitPaint.set(hdCardTitle);
            c.drawText(TextUtils.ellipsize(p.title, fitPaint, base.width() - mw - 20 * k, TextUtils.TruncateAt.END).toString(),
                    base.left, base.bottom + 56 * k, hdCardTitle);
            return;
        }
        // Brackets at the top left and bottom right, inside the picture.
        hdStroke.setColor(HUD_CYAN);
        hdStroke.setStrokeWidth(2.5f * k);
        float bx = base.left + 34 * k, by = base.top + 34 * k, len = 26 * k;
        c.drawLine(bx, by, bx + len, by, hdStroke);
        c.drawLine(bx, by, bx, by + len, hdStroke);
        float ex = base.right - 34 * k, ey = base.bottom - 34 * k;
        c.drawLine(ex, ey, ex - len, ey, hdStroke);
        c.drawLine(ex, ey, ex, ey - len, hdStroke);
        hdStroke.setStrokeWidth(1.5f * k);
        boolean fromPlex = Plex.is(p);
        float x = base.left + 52 * k, room = base.width() * 0.62f;
        String kicker = "▸ " + (!fromPlex ? Ui.tr("Para descobrir", "To discover")
                : p.recent ? Ui.tr("Adicionado recentemente", "Recently added") : Ui.tr("Retomar", "Resume") + " · Plex");
        c.drawText(kicker.toUpperCase(Ui.locale()), x, base.bottom - 238 * k, hdMonoCyan);
        float size = 62 * k;
        hdTitle.setTextSize(size);
        while (hdTitle.measureText(p.title) > room && size > 40 * k) {
            size -= 2 * k;
            hdTitle.setTextSize(size);
        }
        fitPaint.set(hdTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, room, TextUtils.TruncateAt.END).toString(), x - Ui.leftBearing(hdTitle, p.title),
                base.bottom - 172 * k, hdTitle);
        hdTitle.setTextSize(62 * k);
        if (p.meta != null && !p.meta.isEmpty()) {
            hdMono.setColor(0xD9E6F4FF);
            fitPaint.set(hdMono);
            c.drawText(TextUtils.ellipsize(p.meta.replace(" · ", " — ").toUpperCase(Ui.locale()), fitPaint, room,
                    TextUtils.TruncateAt.END).toString(), x, base.bottom - 128 * k, hdMono);
            hdMono.setColor(HUD_DIM);
        }
        String label = (fromPlex && p.progress >= 0 ? Ui.tr("Retomar", "Resume") : Ui.tr("Assistir", "Watch")).toUpperCase(Ui.locale());
        float bh = 70 * k, bw = hdButton.measureText(label) + 112 * k, btop = base.bottom - 102 * k;
        RectF br = hdRect;
        br.set(x, btop, x + bw, btop + bh);
        hudShape(br, 0, 14 * k);
        fill.setColor(HUD_CYAN);
        c.drawPath(hudPath, fill);
        if (f > 0.01f) {
            shadowDeep.setColor(Ui.alpha(HUD_CYAN, 0.5f * f));
            shadows.draw(c, br, 4 * k, DEEP_BLUR * k, shadowDeep);
            fill.setColor(HUD_CYAN);
            c.drawPath(hudPath, fill);
        }
        clip.reset();
        float tx = x + 34 * k, tcy = btop + bh / 2;
        clip.moveTo(tx, tcy - 10 * k);
        clip.lineTo(tx + 16 * k, tcy);
        clip.lineTo(tx, tcy + 10 * k);
        clip.close();
        fill.setColor(0xFF06121A);
        c.drawPath(clip, fill);
        c.drawText(label, tx + 34 * k, tcy + Ui.halfCap(hdButton), hdButton);
        if (p.progress >= 0 && p.leftMin > 0) {
            float rcx = base.right - 118 * k, rcy = base.bottom - 118 * k, rr = 56 * k;
            hdStroke.setColor(0x33FFFFFF);
            hdStroke.setStrokeWidth(3 * k);
            c.drawCircle(rcx, rcy, rr, hdStroke);
            hdStroke.setColor(HUD_CYAN);
            tmp.set(rcx - rr, rcy - rr, rcx + rr, rcy + rr);
            hdStroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(tmp, -90, 360 * (1 - p.progress), false, hdStroke);
            hdStroke.setStrokeCap(Paint.Cap.BUTT);
            hdStroke.setStrokeWidth(1.5f * k);
            int m = p.leftMin;
            String num = m < 60 ? String.valueOf(m) : (m / 60) + "H" + two(m % 60);
            hdRingNum.setTextSize((m < 60 ? 32 : 24) * k);
            c.drawText(num, rcx, rcy + 6 * k, hdRingNum);
            c.drawText(m < 60 ? "MIN" : "", rcx, rcy + 30 * k, hdRingSub);
        }
    }

    /** The top buttons: dark squares with a cut corner; the focused one outlined in cyan with a glow. */
    private void drawHudButtons(Canvas c) {
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[B0 + i];
            float f = Ui.clamp(topFocus[B0 + i].value), cx = r.centerX(), cy = r.centerY();
            hudPanel(c, r, f > 0.01f ? 12 * k : 0, 12 * k, f, HUD_PANEL);
            int ink = blend(0xD9E6F4FF, HUD_CYAN, f);
            float icon = 30 * k;
            if (it.kind == HomeApps.POWER) {
                drawPower(c, cx, cy, 28 * k, ink);
            } else if (it.kind == HomeApps.AMBILIGHT) {
                drawAmbilight(c, cx, cy, icon, ink);
            } else if (it.kind == HomeApps.WIFI) {
                drawWifi(c, cx, cy - 1 * k, icon, ink);
                if (netVpn) drawLock(c, cx + 13 * k, cy + 9 * k, 0.6f, ink);
            } else if (it.kind == HomeApps.UPDATE) {
                Icons.draw(c, "sync", cx, cy, icon, ink);
                if (Updates.get(getContext()).available()) {
                    fill.setColor(HUD_CYAN);
                    c.drawRect(r.right - 16 * k, r.top + 8 * k, r.right - 8 * k, r.top + 16 * k, fill);
                }
            } else if (it.kind == HomeApps.INPUTS) {
                Icons.draw(c, "plug", cx, cy, icon, ink);
            } else if (it.kind == HomeApps.ANDROID_SETTINGS || it.kind == HomeApps.SETTINGS) {
                Icons.draw(c, "sliders", cx, cy, icon, ink);
            } else {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 26 * k, ink);
            }
        }
    }

    /** Over the apps: "APPLICATIONS", a line, and the focused app's position "[ 03 / 06 ]" in cyan. */
    private void drawHudShelfHeader(Canvas c) {
        float y = 862 * k, x = MARGIN * k;
        String label = Ui.tr("Aplicativos", "Applications").toUpperCase(Ui.locale());
        c.drawText(label, x, y, hdMono);
        int n = shelf.size();
        String pos = "[ " + two(Math.min(n, shelfIndex + 1)) + " / " + two(n) + " ]";
        float pw = hdMonoCyan.measureText(pos);
        c.drawText(pos, CONTENT_R * k - pw, y, hdMonoCyan);
        hdStroke.setColor(0x26FFFFFF);
        float lx = x + hdMono.measureText(label) + 24 * k;
        c.drawLine(lx, y - 7 * k, CONTENT_R * k - pw - 24 * k, y - 7 * k, hdStroke);
    }

    /** An app: a dark panel, its line icon at the top left and its name at the bottom; focused, cyan with a glow. */
    private void hudTile(Canvas c, RectF base, float f, float l, HomeApps.Item it, boolean dim) {
        float fc = Ui.clamp(Math.max(f, l)), s = 1 + 0.03f * f;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        hudPanel(c, base, 0, 18 * k, fc, HUD_PANEL);
        int ink = dim ? 0x66FFFFFF : blend(0xD9E6F4FF, HUD_CYAN, fc);
        Icons.draw(c, iconOf(it), base.left + 46 * k, base.top + 46 * k, 40 * k, ink);
        hdTile.setTypeface(fc > 0.5f ? Ui.bold : Ui.semibold);
        hdTile.setColor(dim ? 0x66FFFFFF : 0xFFFFFFFF);
        fitPaint.set(hdTile);
        c.drawText(TextUtils.ellipsize(it.label == null ? "" : it.label, fitPaint, base.width() - 48 * k, TextUtils.TruncateAt.END).toString(),
                base.left + 26 * k, base.bottom - 28 * k, hdTile);
        c.restore();
    }

    /** A source or shortcut: a small panel, the icon, its number, its name, and its port and state with a square. */
    private void hudSmallTile(Canvas c, RectF base, float f, HomeApps.Item it, int index) {
        float fc = Ui.clamp(f);
        boolean input = it.kind == HomeApps.INPUT, live = it.kind == HomeApps.LIVE_TV, off = input && !it.connected && !it.standby;
        hudPanel(c, base, 0, 14 * k, fc, HUD_PANEL);
        int ink = off ? 0x66FFFFFF : blend(0xD9E6F4FF, HUD_CYAN, fc);
        float x = base.left + 18 * k;
        Icons.draw(c, iconOf(it), x + 14 * k, base.top + 30 * k, 28 * k, ink);
        String num = two(index + 1);
        c.drawText(num, base.right - 16 * k - hdMono.measureText(num), base.top + 36 * k, fc > 0.5f ? hdMonoCyan : hdMono);
        hdTile.setTypeface(Ui.bold);
        hdTile.setTextSize(23 * k);
        hdTile.setColor(off ? 0x66FFFFFF : 0xFFFFFFFF);
        fitPaint.set(hdTile);
        c.drawText(TextUtils.ellipsize(it.label, fitPaint, base.width() - 36 * k, TextUtils.TruncateAt.END).toString(), x, base.top + 82 * k, hdTile);
        hdTile.setTextSize(25 * k);
        String sub = live ? Ui.tr("Canais de TV", "TV channels") : !input ? null
                : it.connected ? Ui.tr("Conectado", "Connected") : it.standby ? Ui.tr("Em espera", "In standby") : Ui.tr("Livre", "Not in use");
        if (sub == null) return;
        float sx = x, sy = base.top + 110 * k;
        if (input && (it.connected || it.standby)) {
            tmp.set(sx, sy - 11 * k, sx + 10 * k, sy - 1 * k);
            if (it.connected) {
                fill.setColor(HUD_CYAN);
                c.drawRect(tmp, fill);
            } else {
                hdStroke.setColor(HUD_DIM);
                c.drawRect(tmp, hdStroke);
            }
            sx += 18 * k;
        }
        hdMono.setTextSize(16 * k);
        fitPaint.set(hdMono);
        c.drawText(TextUtils.ellipsize(sub.toUpperCase(Ui.locale()), fitPaint, base.right - 12 * k - sx, TextUtils.TruncateAt.END).toString(),
                sx, sy, hdMono);
        hdMono.setTextSize(19 * k);
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
