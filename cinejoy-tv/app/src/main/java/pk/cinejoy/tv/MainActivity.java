package pk.cinejoy.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Cinejoy for Android TV.
 *
 * Remote controls:
 *   D-pad            move the on-screen pointer (pushing past the top/bottom edge scrolls)
 *   OK / Enter       click where the pointer is
 *   Hold OK / Menu   open the options menu (home, reload, zoom, pointer on/off, exit)
 *   Back             leave fullscreen video, go back a page, or (twice) exit
 *   Play/Pause, FF, Rewind, CH+/CH-   control video / scroll the page
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://cinejoy.pk/";
    /** Top-level pages may only be on these domains (blocks ad redirects and pop-unders). */
    private static final String[] ALLOWED_HOSTS = {"cinejoy.pk"};

    private static final long LONG_PRESS_MS = 650;
    private static final long CURSOR_IDLE_HIDE_MS = 4000;

    private FrameLayout root;
    private WebView web;
    private FrameLayout fullscreenHolder;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private CursorView cursor;
    private ProgressBar progress;
    private TextView hint;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private boolean cursorMode = true;
    private boolean blockerOn = true;
    private float pageZoom = 1.0f;

    // Pointer movement state
    private final Set<Integer> heldDirs = new HashSet<>();
    private float speed;
    private int frame;
    private float unit = 1f;       // pixels per "1080p pixel"
    private boolean cursorHidden = false;

    // OK-button state
    private boolean okDown = false;
    private boolean okLongFired = false;
    private long okDownTime;

    private long lastBackPress = 0;

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("cinejoy_tv", MODE_PRIVATE);
        cursorMode = prefs.getBoolean("cursorMode", true);
        blockerOn = prefs.getBoolean("blocker", true);
        pageZoom = prefs.getFloat("zoom", 1.0f);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        root.addView(web, match());

        fullscreenHolder = new FrameLayout(this);
        fullscreenHolder.setBackgroundColor(Color.BLACK);
        fullscreenHolder.setVisibility(View.GONE);
        root.addView(fullscreenHolder, match());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF95FF50));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP);
        root.addView(progress, plp);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setBackgroundColor(0xCC000000);
        hint.setPadding(dp(18), dp(10), dp(18), dp(10));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(28);
        root.addView(hint, hlp);

        cursor = new CursorView(this);
        root.addView(cursor, match());
        cursor.setVisibility(cursorMode ? View.VISIBLE : View.GONE);

        setContentView(root);
        hideSystemUi();

        // Keep pointer speed consistent on 720p, 1080p and 4K screens.
        root.post(() -> unit = Math.max(0.5f, root.getWidth() / 1920f));

        setupWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME_URL);
        }

        int launches = prefs.getInt("launches", 0);
        prefs.edit().putInt("launches", launches + 1).apply();
        if (launches < 3) {
            showHint("Arrows: move pointer  •  OK: click  •  Hold OK: menu  •  Back: go back", 7000);
        }
        scheduleCursorHide();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // A dialog or the keyboard took focus: forget held arrows so the pointer can't drift.
        if (!hasFocus) {
            heldDirs.clear();
            handler.removeCallbacks(longPress);
            okDown = false;
            cursor.setPressed2(false);
        } else {
            hideSystemUi();
        }
    }

    @Override
    protected void onPause() {
        heldDirs.clear();
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // Present as regular Chrome rather than an embedded WebView.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                applyZoom();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showErrorPage(request.getUrl().toString(),
                        String.valueOf(error.getDescription()));
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 100) progress.setVisibility(View.GONE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // Pop-ups: only follow ones the user clicked, and only to allowed sites.
                if (blockerOn && !isUserGesture) return false;
                final WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    boolean handled = false;

                    private void take(String url) {
                        if (handled || url == null || url.equals("about:blank")) return;
                        handled = true;
                        Uri uri = Uri.parse(url);
                        if (!handleNavigation(uri)) web.loadUrl(url);
                        handler.post(temp::destroy);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        take(r.getUrl().toString());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        take(url);
                        v.stopLoading();
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // Avoid the grey "play" placeholder some WebViews draw before a video starts.
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
    }

    /** @return true if the navigation was blocked / handled, false to let the WebView load it. */
    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            // intent://, market://, etc. are almost always ads on streaming sites.
            showHint("Blocked: " + scheme + " link", 2500);
            return true;
        }
        if (!blockerOn || isAllowedHost(uri.getHost())) return false;
        showHint("Blocked redirect to " + uri.getHost() + "  (Hold OK → menu to turn blocker off)", 3500);
        return true;
    }

    private static boolean isAllowedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    private void showErrorPage(String failingUrl, String description) {
        String safeUrl = failingUrl.replace("'", "%27").replace("\"", "%22");
        String html = "<html><head><meta name='viewport' content='width=device-width'></head>"
                + "<body style='background:#000;color:#fff;font-family:sans-serif;text-align:center;padding-top:18vh'>"
                + "<h1 style='color:#95FF50'>Can't reach Cinejoy</h1>"
                + "<p style='font-size:20px;opacity:.8'>Check the TV's internet connection.</p>"
                + "<p style='font-size:14px;opacity:.5'>" + android.text.TextUtils.htmlEncode(description) + "</p>"
                + "<button autofocus onclick=\"location.href='" + safeUrl + "'\" "
                + "style='margin-top:30px;font-size:26px;padding:16px 48px;border-radius:40px;border:0;"
                + "background:#95FF50;color:#000'>Retry</button></body></html>";
        web.loadDataWithBaseURL(HOME_URL, html, "text/html", "utf-8", failingUrl);
    }

    private void applyZoom() {
        web.evaluateJavascript("document.documentElement.style.zoom='" + pageZoom + "';", null);
    }

    // ---------------------------------------------------------------- fullscreen video

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        fullscreenHolder.addView(view, match());
        fullscreenHolder.setVisibility(View.VISIBLE);
        web.setVisibility(View.INVISIBLE);
        cursor.bringToFront();
        hint.bringToFront();
        hideSystemUi();
        scheduleCursorHide();
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreenHolder.removeView(customView);
        fullscreenHolder.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        hideSystemUi();
    }

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;

        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (up) onBackKey();
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                if (up) showMenu();
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down && event.getRepeatCount() == 0) mediaPlayPause(code);
                return true;

            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
                if (down) mediaSeek(10);
                return true;

            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
                if (down) mediaSeek(-10);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) scrollAtCursor(0, 6);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) scrollAtCursor(0, -6);
                return true;
        }

        if (!cursorMode) {
            // Native focus navigation; still allow "hold OK" to reach the menu.
            if (isOk(code)) {
                if (down && event.getRepeatCount() == 0) {
                    okDownTime = SystemClock.uptimeMillis();
                } else if (down && SystemClock.uptimeMillis() - okDownTime > LONG_PRESS_MS && !okLongFired) {
                    okLongFired = true;
                    showMenu();
                    return true;
                } else if (up && okLongFired) {
                    okLongFired = false;
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        int dir = dirOf(code);
        if (dir != 0) {
            if (down && event.getRepeatCount() == 0) {
                if (heldDirs.isEmpty()) {
                    speed = 6f * unit;
                    frame = 0;
                    handler.removeCallbacks(mover);
                    handler.post(mover);
                }
                heldDirs.add(code);
                showCursor();
            } else if (up) {
                heldDirs.remove(code);
            }
            return true;
        }

        if (isOk(code)) {
            if (down && event.getRepeatCount() == 0) {
                okDown = true;
                okLongFired = false;
                okDownTime = SystemClock.uptimeMillis();
                if (cursorHidden) {
                    // First press only wakes the pointer, so a stray OK doesn't click under a video.
                    showCursor();
                    okDown = false;
                    return true;
                }
                cursor.setPressed2(true);
                handler.postDelayed(longPress, LONG_PRESS_MS);
            } else if (up) {
                handler.removeCallbacks(longPress);
                cursor.setPressed2(false);
                if (okDown && !okLongFired) clickAtCursor();
                okDown = false;
                scheduleCursorHide();
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private final Runnable longPress = () -> {
        okLongFired = true;
        cursor.setPressed2(false);
        showMenu();
    };

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static int dirOf(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return code;
            default:
                return 0;
        }
    }

    /** Moves the pointer every frame while a direction is held, accelerating smoothly. */
    private final Runnable mover = new Runnable() {
        @Override
        public void run() {
            if (heldDirs.isEmpty()) {
                scheduleCursorHide();
                return;
            }
            int dx = 0, dy = 0;
            if (heldDirs.contains(KeyEvent.KEYCODE_DPAD_LEFT)) dx -= 1;
            if (heldDirs.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) dx += 1;
            if (heldDirs.contains(KeyEvent.KEYCODE_DPAD_UP)) dy -= 1;
            if (heldDirs.contains(KeyEvent.KEYCODE_DPAD_DOWN)) dy += 1;

            float w = cursor.getWidth(), h = cursor.getHeight();
            float x = cursor.getCursorX() + dx * speed;
            float y = cursor.getCursorY() + dy * speed;

            float edge = 2f;
            boolean hitTop = y < edge, hitBottom = y > h - edge;
            boolean hitLeft = x < edge, hitRight = x > w - edge;
            x = clamp(x, edge, w - edge);
            y = clamp(y, edge, h - edge);
            cursor.setPosition(x, y);
            sendHover(x, y);

            // Pushing against an edge scrolls whatever is under the pointer.
            if (frame % 2 == 0) {
                if (hitTop && dy < 0) scrollAtCursor(0, 1);
                if (hitBottom && dy > 0) scrollAtCursor(0, -1);
                if (hitLeft && dx < 0) scrollAtCursor(1, 0);
                if (hitRight && dx > 0) scrollAtCursor(-1, 0);
            }

            frame++;
            speed = Math.min(speed + 0.9f * unit, 32f * unit);
            handler.postDelayed(this, 16);
        }
    };

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void clickAtCursor() {
        float x = cursor.getCursorX(), y = cursor.getCursorY();
        long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(downEv);
        downEv.recycle();
        MotionEvent upEv = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(upEv);
        upEv.recycle();
    }

    private void sendHover(float x, float y) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        root.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    /** Positive v scrolls up, negative scrolls down (mouse-wheel semantics). */
    private void scrollAtCursor(float h, float v) {
        float x = cursor.getCursorX() < 0 ? root.getWidth() / 2f : cursor.getCursorX();
        float y = cursor.getCursorY() < 0 ? root.getHeight() / 2f : cursor.getCursorY();
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        root.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private final Runnable hideCursor = () -> {
        if (!heldDirs.isEmpty() || okDown) return;
        cursorHidden = true;
        cursor.animate().alpha(0f).setDuration(400).start();
    };

    private void showCursor() {
        if (!cursorMode) return;
        handler.removeCallbacks(hideCursor);
        cursorHidden = false;
        cursor.animate().cancel();
        cursor.setAlpha(1f);
        sendHover(cursor.getCursorX(), cursor.getCursorY());
        scheduleCursorHide();
    }

    private void scheduleCursorHide() {
        handler.removeCallbacks(hideCursor);
        // Only auto-hide over fullscreen video, so the pointer never covers the movie.
        if (customView != null) handler.postDelayed(hideCursor, CURSOR_IDLE_HIDE_MS);
    }

    // ---------------------------------------------------------------- media keys

    private static final String JS_FIND_VIDEO =
            "(function(){var v=document.querySelector('video');"
            + "if(!v){var f=document.querySelectorAll('iframe');for(var i=0;i<f.length&&!v;i++){"
            + "try{v=f[i].contentDocument.querySelector('video');}catch(e){}}}";

    private void mediaPlayPause(int code) {
        String action;
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY) action = "v.play();";
        else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) action = "v.pause();";
        else action = "if(v.paused)v.play();else v.pause();";
        web.evaluateJavascript(JS_FIND_VIDEO + "if(!v)return 'none';" + action + "return 'ok';})()",
                result -> {
                    // The player lives in a cross-origin frame: click the middle of the screen instead.
                    if (result == null || result.contains("none")) {
                        float x = cursor.getCursorX(), y = cursor.getCursorY();
                        cursor.setPosition(root.getWidth() / 2f, root.getHeight() / 2f);
                        clickAtCursor();
                        cursor.setPosition(x, y);
                    }
                });
    }

    private void mediaSeek(int seconds) {
        web.evaluateJavascript(JS_FIND_VIDEO + "if(!v)return 'none';"
                + "v.currentTime=Math.max(0,v.currentTime+(" + seconds + "));return 'ok';})()", null);
    }

    // ---------------------------------------------------------------- back & menu

    private void onBackKey() {
        heldDirs.clear();
        if (customView != null) {
            exitFullscreen();
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPress < 2000) {
            finish();
        } else {
            lastBackPress = now;
            showHint("Press Back again to exit", 2000);
        }
    }

    private void showMenu() {
        heldDirs.clear();
        okDown = false;
        final String[] items = {
                "Home",
                "Reload page",
                "Go back",
                "Go forward",
                "Zoom in  (now " + Math.round(pageZoom * 100) + "%)",
                "Zoom out",
                "Reset zoom",
                "Pointer: " + (cursorMode ? "ON  (turn off for arrow-key focus mode)" : "OFF  (turn on)"),
                "Pop-up / redirect blocker: " + (blockerOn ? "ON" : "OFF"),
                "Exit app"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cinejoy TV")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: web.loadUrl(HOME_URL); break;
                        case 1: web.reload(); break;
                        case 2: if (web.canGoBack()) web.goBack(); break;
                        case 3: if (web.canGoForward()) web.goForward(); break;
                        case 4: setZoom(pageZoom + 0.1f); break;
                        case 5: setZoom(pageZoom - 0.1f); break;
                        case 6: setZoom(1.0f); break;
                        case 7: toggleCursorMode(); break;
                        case 8:
                            blockerOn = !blockerOn;
                            prefs.edit().putBoolean("blocker", blockerOn).apply();
                            showHint("Blocker " + (blockerOn ? "on" : "off"), 2000);
                            break;
                        case 9: finish(); break;
                    }
                })
                .show();
    }

    private void setZoom(float z) {
        pageZoom = Math.round(clamp(z, 0.5f, 2.5f) * 10f) / 10f;
        prefs.edit().putFloat("zoom", pageZoom).apply();
        applyZoom();
        showHint("Zoom " + Math.round(pageZoom * 100) + "%", 1500);
    }

    private void toggleCursorMode() {
        cursorMode = !cursorMode;
        prefs.edit().putBoolean("cursorMode", cursorMode).apply();
        cursor.setVisibility(cursorMode ? View.VISIBLE : View.GONE);
        if (cursorMode) showCursor();
        else web.requestFocus();
        showHint(cursorMode ? "Pointer on" : "Pointer off — arrows move between links", 2500);
    }

    // ---------------------------------------------------------------- helpers

    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);

    private void showHint(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.bringToFront();
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, ms);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
