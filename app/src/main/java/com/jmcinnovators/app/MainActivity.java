package com.jmcinnovators.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String BASE_URL = "https://jmcinnovators.vercel.app";
    private static final String HOME_URL = BASE_URL + "/";

    private WebView webView;
    private WebView popupWebView;
    private FrameLayout webViewContainer;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private BottomNavigationView bottomNav;
    private LinearLayout errorLayout;
    private TextView errorMessage;

    private ValueCallback<Uri[]> fileUploadCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private boolean isOffline = false;
    private String pendingUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge dark theme
        setupSystemBars();

        setContentView(R.layout.activity_main);

        // Find views
        webViewContainer = findViewById(R.id.webViewContainer);
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        bottomNav = findViewById(R.id.bottomNavigation);
        errorLayout = findViewById(R.id.errorLayout);
        errorMessage = findViewById(R.id.errorMessage);

        // Setup components
        setupWebView();
        setupSwipeRefresh();
        setupBottomNav();
        setupBackHandler();
        setupNetworkMonitor();

        // Handle deep links
        Intent intent = getIntent();
        String url = HOME_URL;
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                url = data.toString();
            }
        }

        // Load the website
        if (isNetworkAvailable()) {
            webView.loadUrl(url);
        } else {
            showOfflineError();
        }
    }

    private void setupSystemBars() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.parseColor("#05070f"));
        window.setNavigationBarColor(Color.parseColor("#0e1424"));

        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        // Core settings
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Hardware acceleration for video rendering
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Cache & offline support
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Mobile viewport, zoom & responsive rendering
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);

        // Smooth mobile scrolling & overscroll
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        // For Google Sign-In popups
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // User Agent: Google OAuth blocks requests containing 'wv' or custom WebView identifiers
        // ("disallowed_useragent"). Clean the User-Agent so Google accounts sign-in succeeds seamlessly.
        String defaultUA = settings.getUserAgentString();
        String cleanUA = defaultUA.replace("; wv", "").replace("Version/4.0 ", "");
        settings.setUserAgentString(cleanUA);

        // Cookie persistence (critical for Firebase Auth)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // JavaScript interface
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidApp");

        // WebView client - keeps navigation inside the app
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Keep JMC Innovators URLs in the WebView
                if (url.contains("jmcinnovators.vercel.app") ||
                    url.contains("jmcinnovators.netlify.app") ||
                    url.contains("jmc-home2.firebaseapp.com") ||
                    url.contains("firebasestorage.googleapis.com") ||
                    url.contains("accounts.google.com") ||
                    url.contains("googleapis.com")) {
                    return false; // Load in WebView
                }

                // Open external links in browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    // ignore
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                errorLayout.setVisibility(View.GONE);
                updateBottomNavForUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                // Inject banner video enhancements, animations, autoplay, and styling
                injectBannerVideoAndStyles(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOfflineError();
                }
            }
        });

        // WebChromeClient - handles popups (Google Sign-In), file uploads, progress
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            // Google Sign-In popup support
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                popupWebView = new WebView(MainActivity.this);
                setupPopupWebView(popupWebView);

                webViewContainer.addView(popupWebView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWebView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (popupWebView != null) {
                    webViewContainer.removeView(popupWebView);
                    popupWebView.destroy();
                    popupWebView = null;
                }
            }

            // File upload support
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file chooser", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            // JavaScript alerts
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return super.onJsAlert(view, url, message, result);
            }

            // Geolocation
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        // Download support
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);

                    request.setTitle(fileName);
                    request.setDescription("Downloading from JMC Innovators...");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    request.setMimeType(mimetype);

                    // Add cookies for authenticated downloads
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        request.addRequestHeader("Cookie", cookies);
                    }
                    request.addRequestHeader("User-Agent", userAgent);

                    DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    downloadManager.enqueue(request);

                    Toast.makeText(MainActivity.this, "Downloading: " + fileName, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    // Fallback: open in browser
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception ex) {
                        Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupPopupWebView(WebView popup) {
        WebSettings popupSettings = popup.getSettings();
        popupSettings.setJavaScriptEnabled(true);
        popupSettings.setDomStorageEnabled(true);
        popupSettings.setDatabaseEnabled(true);
        popupSettings.setSupportMultipleWindows(true);
        popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Match clean User-Agent to avoid Google's "disallowed_useragent" 403 screen in popup
        String cleanUA = webView.getSettings().getUserAgentString();
        popupSettings.setUserAgentString(cleanUA);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(popup, true);

        popup.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Keep auth and app URLs within popup/app
                if (url.contains("accounts.google.com") ||
                    url.contains("googleapis.com") ||
                    url.contains("firebaseapp.com") ||
                    url.contains("gstatic.com")) {
                    return false;
                }

                // If the popup navigates back to our site, load in main WebView
                if (url.contains("jmcinnovators.vercel.app") || url.contains("jmcinnovators.netlify.app")) {
                    webView.loadUrl(url);
                    if (popupWebView != null) {
                        webViewContainer.removeView(popupWebView);
                        popupWebView.destroy();
                        popupWebView = null;
                    }
                    return true;
                }
                return false;
            }
        });

        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                if (popupWebView != null) {
                    webViewContainer.removeView(popupWebView);
                    popupWebView.destroy();
                    popupWebView = null;
                }
            }
        });
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            Color.parseColor("#f2a71b"), // gold
            Color.parseColor("#3b82f6"), // blue
            Color.parseColor("#8b5cf6")  // purple
        );
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#0e1424"));
        swipeRefresh.setOnRefreshListener(() -> {
            if (isNetworkAvailable()) {
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
                showOfflineError();
            }
        });
    }

    private void setupBottomNav() {
        bottomNav.setBackgroundColor(Color.parseColor("#0e1424"));
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            String url;

            if (id == R.id.nav_home) {
                url = BASE_URL + "/";
            } else if (id == R.id.nav_classroom) {
                url = BASE_URL + "/jmc_Classroom";
            } else if (id == R.id.nav_exams) {
                url = BASE_URL + "/exampapers";
            } else if (id == R.id.nav_tools) {
                url = BASE_URL + "/educational_tools";
            } else if (id == R.id.nav_dashboard) {
                url = BASE_URL + "/dashboard";
            } else {
                return false;
            }

            // Only load if different from current URL
            String currentUrl = webView.getUrl();
            if (currentUrl == null || !currentUrl.equals(url)) {
                webView.loadUrl(url);
            }
            return true;
        });
    }

    private void updateBottomNavForUrl(String url) {
        if (url == null) return;

        int selectedId = -1;
        if (url.contains("jmc_Classroom") || url.contains("classroom")) {
            selectedId = R.id.nav_classroom;
        } else if (url.contains("exampapers") || url.contains("exam")) {
            selectedId = R.id.nav_exams;
        } else if (url.contains("educational_tools") || url.contains("dictionary") ||
                   url.contains("mathslab") || url.contains("science") || url.contains("tools")) {
            selectedId = R.id.nav_tools;
        } else if (url.contains("dashboard") || url.contains("profile") ||
                   url.contains("settings") || url.contains("parent-control")) {
            selectedId = R.id.nav_dashboard;
        } else if (url.contains("index") || url.equals(BASE_URL) || url.equals(BASE_URL + "/") ||
                   url.contains("jmcinnovators.vercel.app") || url.contains("jmcinnovators.netlify.app")) {
            selectedId = R.id.nav_home;
        }

        if (selectedId != -1) {
            bottomNav.setSelectedItemId(selectedId);
        }
    }

    private void setupBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Close popup first
                if (popupWebView != null) {
                    webViewContainer.removeView(popupWebView);
                    popupWebView.destroy();
                    popupWebView = null;
                    return;
                }

                // Navigate WebView back
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    // Exit app
                    finish();
                }
            }
        });
    }

    private void setupNetworkMonitor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();

        cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> {
                    if (isOffline) {
                        isOffline = false;
                        errorLayout.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                        if (pendingUrl != null) {
                            webView.loadUrl(pendingUrl);
                            pendingUrl = null;
                        } else {
                            webView.reload();
                        }
                    }
                });
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> {
                    if (!isNetworkAvailable()) {
                        isOffline = true;
                    }
                });
            }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showOfflineError() {
        isOffline = true;
        pendingUrl = webView.getUrl();
        errorLayout.setVisibility(View.VISIBLE);
        errorMessage.setText("You're offline. Please check your internet connection.");

        // Retry button
        findViewById(R.id.retryButton).setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                isOffline = false;
                errorLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                if (pendingUrl != null) {
                    webView.loadUrl(pendingUrl);
                    pendingUrl = null;
                } else {
                    webView.loadUrl(HOME_URL);
                }
            } else {
                Toast.makeText(this, "Still offline. Please check your connection.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (popupWebView != null) {
            popupWebView.destroy();
        }
        webView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                webView.loadUrl(data.toString());
            }
        }
    }

    /**
     * Injects custom CSS styling and JavaScript to ensure the banner video
     * on the home page displays with autoplay, zoom, sheen, scanlines, and glow animations
     * as seen on https://jmcinnovators.vercel.app/
     */
    private void injectBannerVideoAndStyles(WebView view) {
        if (view == null) return;

        String script =
            "(function() {" +
            "  var style = document.getElementById('jmc-custom-banner-style');" +
            "  if (!style) {" +
            "    style = document.createElement('style');" +
            "    style.id = 'jmc-custom-banner-style';" +
            "    style.textContent = `" +
            "      body { padding-bottom: 64px !important; -webkit-tap-highlight-color: transparent !important; }" +
            "      html, body { overflow-x: hidden !important; max-width: 100vw !important; }" +
            "      * { -webkit-tap-highlight-color: rgba(242, 167, 27, 0.12) !important; }" +
            "      .navbar .btn-app, .jmcnav-app, a[href*='app-download'], a[href*='apk'] { display: none !important; }" +
            "      .hamburger, .jmcnav-burger { min-width: 44px !important; min-height: 44px !important; }" +
            "      .mobile-panel { padding-bottom: 84px !important; -webkit-overflow-scrolling: touch !important; }" +
            "      .mobile-menu { z-index: 9999 !important; }" +
            "      a, button, [role='button'], input, select, textarea { touch-action: manipulation !important; }" +
            "      button, a.btn, .tool-card, .quick-link-card { min-height: 44px !important; }" +
            "      .hero-art {" +
            "        position: relative !important;" +
            "        z-index: 1 !important;" +
            "        border-radius: 24px !important;" +
            "        overflow: hidden !important;" +
            "        aspect-ratio: 1/0.82 !important;" +
            "        background: radial-gradient(ellipse at 50% 30%, rgba(59,130,246,0.28), transparent 60%), linear-gradient(160deg,#0b1024,#070a15) !important;" +
            "        border: 1px solid rgba(255,255,255,0.12) !important;" +
            "        display: flex !important;" +
            "        align-items: center !important;" +
            "        justify-content: center !important;" +
            "        box-shadow: 0 20px 40px -15px rgba(0,0,0,0.7) !important;" +
            "        transform-style: preserve-3d !important;" +
            "        will-change: transform, box-shadow !important;" +
            "        animation: jmcHeroArtGlow 6s ease-in-out infinite !important;" +
            "      }" +
            "      @keyframes jmcHeroArtGlow {" +
            "        0%, 100% { box-shadow: 0 20px 40px -15px rgba(0,0,0,0.7), 0 0 0 0 rgba(79,70,229,0); }" +
            "        50% { box-shadow: 0 20px 40px -15px rgba(0,0,0,0.7), 0 0 35px 4px rgba(79,70,229,0.4); }" +
            "      }" +
            "      .hero-banner-video {" +
            "        position: relative !important;" +
            "        z-index: 1 !important;" +
            "        width: 100% !important;" +
            "        height: 100% !important;" +
            "        object-fit: cover !important;" +
            "        object-position: center !important;" +
            "        display: block !important;" +
            "        background: #0b1024 !important;" +
            "        transform-origin: center !important;" +
            "        animation: jmcHeroVideoZoom 16s ease-in-out infinite alternate !important;" +
            "        filter: saturate(1.12) contrast(1.05) brightness(1.03) !important;" +
            "      }" +
            "      @keyframes jmcHeroVideoZoom {" +
            "        0% { transform: scale(1); }" +
            "        100% { transform: scale(1.08); }" +
            "      }" +
            "      .hero-video-sheen {" +
            "        position: absolute !important;" +
            "        inset: 0 !important;" +
            "        z-index: 2 !important;" +
            "        pointer-events: none !important;" +
            "        background: linear-gradient(115deg, transparent 20%, rgba(255,255,255,0.18) 38%, transparent 52%) !important;" +
            "        background-size: 260% 260% !important;" +
            "        animation: jmcHeroSheen 6.5s ease-in-out infinite !important;" +
            "        mix-blend-mode: overlay !important;" +
            "      }" +
            "      @keyframes jmcHeroSheen {" +
            "        0% { background-position: 135% 0%; }" +
            "        50% { background-position: -35% 100%; }" +
            "        100% { background-position: 135% 0%; }" +
            "      }" +
            "      .hero-video-scanline {" +
            "        position: absolute !important;" +
            "        inset: 0 !important;" +
            "        z-index: 2 !important;" +
            "        pointer-events: none !important;" +
            "        background: linear-gradient(180deg, transparent 0%, rgba(120,150,255,0.16) 48%, transparent 100%) !important;" +
            "        height: 40% !important;" +
            "        animation: jmcHeroScan 5s linear infinite !important;" +
            "      }" +
            "      @keyframes jmcHeroScan {" +
            "        0% { transform: translateY(-120%); }" +
            "        100% { transform: translateY(320%); }" +
            "      }" +
            "      .hero-video-vignette {" +
            "        position: absolute !important;" +
            "        inset: 0 !important;" +
            "        z-index: 2 !important;" +
            "        pointer-events: none !important;" +
            "        box-shadow: inset 0 0 45px rgba(5,7,15,0.75) !important;" +
            "      }" +
            "      .hero-video-frame {" +
            "        position: absolute !important;" +
            "        inset: 0 !important;" +
            "        z-index: 2 !important;" +
            "        pointer-events: none !important;" +
            "        border-radius: inherit !important;" +
            "        border: 1px solid rgba(255,255,255,0.12) !important;" +
            "      }" +
            "      .hero-video-badge {" +
            "        position: absolute !important;" +
            "        top: 14px !important;" +
            "        right: 14px !important;" +
            "        z-index: 4 !important;" +
            "        display: inline-flex !important;" +
            "        align-items: center !important;" +
            "        gap: 6px !important;" +
            "        padding: 5px 12px !important;" +
            "        border-radius: 999px !important;" +
            "        font-size: 11px !important;" +
            "        font-weight: 600 !important;" +
            "        letter-spacing: .4px !important;" +
            "        text-transform: uppercase !important;" +
            "        color: #fff !important;" +
            "        background: rgba(10, 14, 28, 0.78) !important;" +
            "        border: 1px solid rgba(255,255,255,0.18) !important;" +
            "        backdrop-filter: blur(8px) !important;" +
            "      }" +
            "      .hero-live-dot {" +
            "        display: inline-block !important;" +
            "        width: 7px !important;" +
            "        height: 7px !important;" +
            "        border-radius: 50% !important;" +
            "        background: #22c55e !important;" +
            "        box-shadow: 0 0 10px #22c55e !important;" +
            "        animation: jmcLivePulse 2s ease-in-out infinite !important;" +
            "      }" +
            "      @keyframes jmcLivePulse {" +
            "        0%, 100% { opacity: 1; transform: scale(1); }" +
            "        50% { opacity: 0.4; transform: scale(0.8); }" +
            "      }" +
            "    `;" +
            "    document.head.appendChild(style);" +
            "  }" +
            "  function setupVideo() {" +
            "    var vid = document.querySelector('.hero-banner-video, video.hero-banner-img');" +
            "    if (vid) {" +
            "      vid.muted = true;" +
            "      vid.defaultMuted = true;" +
            "      vid.loop = true;" +
            "      vid.autoplay = true;" +
            "      vid.playsInline = true;" +
            "      vid.setAttribute('playsinline', '');" +
            "      vid.setAttribute('webkit-playsinline', '');" +
            "      vid.setAttribute('autoplay', '');" +
            "      vid.setAttribute('muted', '');" +
            "      vid.setAttribute('loop', '');" +
            "      var playPromise = vid.play();" +
            "      if (playPromise !== undefined) {" +
            "        playPromise.catch(function() {" +
            "          vid.addEventListener('canplay', function() { vid.play().catch(function(){}); }, {once:true});" +
            "        });" +
            "      }" +
            "      return;" +
            "    }" +
            "    var heroArt = document.getElementById('heroArt') || document.querySelector('.hero-art');" +
            "    if (!heroArt) {" +
            "      var img = document.querySelector('img.hero-banner-img');" +
            "      if (img && img.parentElement) heroArt = img.parentElement;" +
            "    }" +
            "    if (heroArt && !heroArt.querySelector('video')) {" +
            "      var poster = 'https://cdn.corenexis.com/f/ew2RivRngRw.png';" +
            "      var src = 'https://jmcinnovators.vercel.app/assets/video/banner-video.mp4';" +
            "      heroArt.innerHTML = '<video class=\"hero-banner-img hero-banner-video\" autoplay muted loop playsinline webkit-playsinline disablepictureinpicture preload=\"auto\" poster=\"' + poster + '\" aria-label=\"JMC Innovators Learning Platform showcase video\"><source src=\"' + src + '\" type=\"video/mp4\"><img src=\"' + poster + '\" alt=\"JMC Innovators\" class=\"hero-banner-img\"></video><div class=\"hero-video-sheen\"></div><div class=\"hero-video-scanline\"></div><div class=\"hero-video-vignette\"></div><div class=\"hero-video-frame\"></div><span class=\"hero-video-badge\"><i class=\"hero-live-dot\"></i>Live Preview</span>';" +
            "      var newVid = heroArt.querySelector('video');" +
            "      if (newVid) {" +
            "        newVid.muted = true;" +
            "        newVid.defaultMuted = true;" +
            "        newVid.loop = true;" +
            "        newVid.autoplay = true;" +
            "        newVid.playsInline = true;" +
            "        newVid.play().catch(function(){});" +
            "      }" +
            "    }" +
            "  }" +
            "  setupVideo();" +
            "  setTimeout(setupVideo, 400);" +
            "  setTimeout(setupVideo, 1200);" +
            "  setTimeout(setupVideo, 2500);" +
            "  if (window.MutationObserver && !window._jmcVidObs) {" +
            "    window._jmcVidObs = true;" +
            "    var obs = new MutationObserver(function() { setupVideo(); });" +
            "    obs.observe(document.body, { childList: true, subtree: true });" +
            "  }" +
            "})();";

        view.evaluateJavascript(script, null);
    }
}
