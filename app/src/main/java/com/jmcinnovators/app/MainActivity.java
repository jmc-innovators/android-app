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

    private static final String BASE_URL = "https://jmcinnovators.netlify.app";
    private static final String HOME_URL = BASE_URL + "/index.html";

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

        // Cache & offline support
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Viewport & zoom
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // For Google Sign-In popups
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // User agent - identify as app but keep WebView UA for compatibility
        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA + " JMCInnovatorsApp/1.0");

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
                if (url.contains("jmcinnovators.netlify.app") ||
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

                // Inject CSS to hide the website's own bottom nav if any,
                // and add padding at bottom for our native nav bar
                view.evaluateJavascript(
                    "(function() {" +
                    "  var style = document.createElement('style');" +
                    "  style.textContent = 'body { padding-bottom: 64px !important; }';" +
                    "  document.head.appendChild(style);" +
                    "  var navbar = document.querySelector('.navbar .btn-app');" +
                    "  if(navbar) navbar.style.display = 'none';" +
                    "})();", null);
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
        popupSettings.setSupportMultipleWindows(true);
        popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);

        popup.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // If the popup navigates back to our site, load in main WebView
                if (url.contains("jmcinnovators.netlify.app")) {
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
                url = BASE_URL + "/index.html";
            } else if (id == R.id.nav_classroom) {
                url = BASE_URL + "/jmc_Classroom.html";
            } else if (id == R.id.nav_exams) {
                url = BASE_URL + "/exampapers.html";
            } else if (id == R.id.nav_tools) {
                url = BASE_URL + "/educational_tools.html";
            } else if (id == R.id.nav_dashboard) {
                url = BASE_URL + "/dashboard.html";
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
        if (url.contains("index.html") || url.equals(BASE_URL) || url.equals(BASE_URL + "/")) {
            selectedId = R.id.nav_home;
        } else if (url.contains("jmc_Classroom")) {
            selectedId = R.id.nav_classroom;
        } else if (url.contains("exampapers")) {
            selectedId = R.id.nav_exams;
        } else if (url.contains("educational_tools") || url.contains("dictionary") ||
                   url.contains("mathslab") || url.contains("science")) {
            selectedId = R.id.nav_tools;
        } else if (url.contains("dashboard") || url.contains("profile") ||
                   url.contains("settings") || url.contains("parent-control")) {
            selectedId = R.id.nav_dashboard;
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
}
