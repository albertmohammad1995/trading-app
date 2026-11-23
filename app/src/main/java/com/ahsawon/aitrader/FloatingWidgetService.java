package com.ahsawon.aitrader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FloatingWidgetService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private Spinner spinnerCurrency;
    private Spinner spinnerTimeframe;
    private Button btnAnalyze;
    private TextView tvSignalResult, tvReason;

    // Twelve Data সেটআপ ১ (প্রথম ৪টি Key)
    private static final String[] PRIMARY_KEYS = {
            "4a107e9bf68b4701ae35ff5371754e5a",
            "ce0949a1e8ee46bdb9d0b2eb25708966",
            "3cf1615473f34f78ba03abbfaca2ec11",
            "2e344f74e7e549d2ad200439df898288"
    };

    // Twelve Data সেটআপ ২ (পরের ৪টি Key ফলব্যাক পুল)
    private static final String[] BACKUP_KEYS = {
            "d91db237fd9049fe896097e9ab853d66",
            "47c1007ece4e4dc4823d64cb4d6c8314",
            "8534e9ad2b554fbc880534b49d9391f9",
            "4a4da53be96741359cbb1262b0cd0bf0"
    };

    // Finnhub Key পুল (পালাক্রমে লাইভ টিক প্রাইস আনবে)
    private static final String[] FINNHUB_KEYS = {
            "dc199qhr01qgfid6qm8gdc199qhr01qgfid6qm90",
            "dc19bdhr01qgfid6qqi0dc19bdhr01qgfid6qqig"
    };

    private int roundRobinIndex = 0;
    private int finnhubIndex = 0;
    private boolean usingBackupPool = false;
    private String lastUsedKeyLabel = "TD-Init";

    // Groq API সেটআপ
    private static final String GROQ_API_KEY = "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";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    // Cloudflare Workers AI সেটআপ
    private static final String CF_ACCOUNT_ID = "6840586dc663a170acb2fdbbb00d6168";
    private static final String CF_API_TOKEN = "cfut_oY0U9aGLDaXZmG3oxukQRgpslgqIHT5c7Xkq0hXt423d94d9";
    private static final String CF_MODEL = "@cf/moonshotai/kimi-k2.7-code";

    // Gemini API Keys (AIzaSy Standard Key + AQ Auth Key)
    private static final String GEMINI_STANDARD_KEY = "AIzaSyB4Ru9WugjRfL6Wir0Yo0_cIVfdeuIj_70";
    private static final String GEMINI_AUTH_KEY = "AQ.Ab8RN6L6tVivJPnCVgj6sgW9QWgfbPau98h5SbpLmBDQUST_mA";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";

    // 1M মোড: প্রতি ৬০ সেকেন্ডের সাইকেলে ৫০ থেকে ৫৫ সেকেন্ড পর্যন্ত সিগন্যাল
    private static final int SIGNAL_START_SECOND_1M = 50;
    private static final int SIGNAL_END_SECOND_1M = 55;

    // 15M মোড: প্রতি ৯০০ সেকেন্ড (১৫ মিনিট) সাইকেলে ১৪ মিনিট ৪০ থেকে ৫০ সেকেন্ড (14m 40s - 14m 50s) সিগন্যাল
    private static final int SIGNAL_MINUTE_15M = 14;
    private static final int SIGNAL_START_SECOND_IN_14M = 40;
    private static final int SIGNAL_END_SECOND_IN_14M = 50;

    // প্রতি ১.২৫ সেকেন্ড (১২৫০ms) পরপর ননস্টপ ডেটা কালেকশন (মিনিটে ৪৮ বার)
    private static final long COLLECTION_INTERVAL_MS = 1250;

    private boolean is15MinuteMode = false;
    private boolean isAutoRunning = false;
    private boolean isScanInFlight = false;
    private boolean isAiInFlight = false;
    private long lastAiCycleNumber = -1L;
    private long lastCollectionTimeMs = 0L;
    private long sessionStartTimeMs = 0L;

    // সম্মিলিত লাইভ মার্কেট ডেটা ক্যাশ
    private volatile double latestPrice = 0.0;
    private volatile String latestFinnhubTick = "N/A";
    private volatile double latestRsi = 50.0;
    private volatile double latestEma9 = 0.0;
    private volatile double latestEma21 = 0.0;
    private volatile String latestCandlesSummary = "";

    private String lockedSignalText = "READY";
    private int lockedSignalColor = Color.WHITE;
    private String lockedReasonText = "0s - 48/min nonstop scan ready";

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class AiVoteResult {
        final String provider;
        final String signal;
        final String confidence;
        final String reason;

        AiVoteResult(String provider, String signal, String confidence, String reason) {
            this.provider = provider;
            this.signal = signal;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private boolean isIn15mSignalWindow(int minPart, int secPart) {
        return minPart == SIGNAL_MINUTE_15M
                && secPart >= SIGNAL_START_SECOND_IN_14M
                && secPart <= SIGNAL_END_SECOND_IN_14M;
    }

    private final Runnable nonstopEngineRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAutoRunning) return;

            long nowMs = System.currentTimeMillis();
            long elapsedSec = Math.max(0L, (nowMs - sessionStartTimeMs) / 1000L);
            String selectedPair = getSelectedCurrencyPair();

            // ১. পুরো সাইকেল ধরে ননস্টপ ডেটা কালেকশন
            if (!isScanInFlight && (nowMs - lastCollectionTimeMs >= COLLECTION_INTERVAL_MS)) {
                lastCollectionTimeMs = nowMs;
                collectLiveDataNonstop(selectedPair, is15MinuteMode ? "15min" : "1min");
            }

            if (!is15MinuteMode) {
                // === ১ মিনিট (০–৬০ সেকেন্ড) মোড: ৫০ থেকে ৫৫ সেকেন্ডে সিগন্যাল ===
                long cycleNumber = elapsedSec / 60L;
                int cycleSec = (int) (elapsedSec % 60L);

                if (cycleSec >= SIGNAL_START_SECOND_1M && cycleSec <= SIGNAL_END_SECOND_1M) {
                    if (cycleNumber != lastAiCycleNumber && !isAiInFlight && !latestCandlesSummary.isEmpty()) {
                        lastAiCycleNumber = cycleNumber;
                        triggerAiDecision(selectedPair, cycleSec + "s / 50-55s", cycleNumber, false);
                    } else if (!isAiInFlight) {
                        tvSignalResult.setText(lockedSignalText);
                        tvSignalResult.setTextColor(lockedSignalColor);
                        tvReason.setText("[" + selectedPair + " | 1M " + cycleSec + "s] " + lockedReasonText);
                    }
                } else if (!isAiInFlight) {
                    updateLiveBiasUi(selectedPair, "1M " + cycleSec + "s");
                }
            } else {
                // === ১৫ মিনিট (০–১৫ মিনিট) মোড: ১৪ মিনিট ৪০ থেকে ৫০ সেকেন্ডে সিগন্যাল ===
                long cycleNumber = elapsedSec / 900L;
                long secIn15m = elapsedSec % 900L;
                int minPart = (int) (secIn15m / 60L);
                int secPart = (int) (secIn15m % 60L);

                if (isIn15mSignalWindow(minPart, secPart)) {
                    if (cycleNumber != lastAiCycleNumber && !isAiInFlight && !latestCandlesSummary.isEmpty()) {
                        lastAiCycleNumber = cycleNumber;
                        triggerAiDecision(selectedPair, minPart + "m" + secPart + "s / 14m40-50s", cycleNumber, true);
                    } else if (!isAiInFlight) {
                        tvSignalResult.setText(lockedSignalText);
                        tvSignalResult.setTextColor(lockedSignalColor);
                        tvReason.setText("[" + selectedPair + " | 15M " + minPart + "m" + secPart + "s] " + lockedReasonText);
                    }
                } else if (!isAiInFlight) {
                    updateLiveBiasUi(selectedPair, "15M " + minPart + "m" + secPart + "s");
                }
            }

            mainHandler.postDelayed(this, 200);
        }
    };

    private String getSelectedCurrencyPair() {
        if (spinnerCurrency != null && spinnerCurrency.getSelectedItem() != null) {
            return spinnerCurrency.getSelectedItem().toString();
        }
        return "EUR/USD";
    }

    private void updateLiveBiasUi(String pair, String timerLabel) {
        String liveBias = (latestEma9 > latestEma21) ? "LIVE: BULLISH"
                : (latestEma9 < latestEma21) ? "LIVE: BEARISH" : "SCANNING 48/m";
        int biasColor = (latestEma9 > latestEma21) ? Color.parseColor("#34D399")
                : (latestEma9 < latestEma21) ? Color.parseColor("#F87171") : Color.CYAN;

        tvSignalResult.setText(liveBias);
        tvSignalResult.setTextColor(biasColor);
        tvReason.setText("[" + pair + " | " + timerLabel + "] RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(292),
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(120);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        btnAnalyze = floatingView.findViewById(R.id.btnAnalyze);
        tvSignalResult = floatingView.findViewById(R.id.tvSignalResult);
        tvReason = floatingView.findViewById(R.id.tvReason);
        TextView btnClose = floatingView.findViewById(R.id.btnClose);

        View existingSpinnerView = floatingView.findViewById(R.id.spinnerCurrency);
        if (existingSpinnerView instanceof Spinner) {
            spinnerCurrency = (Spinner) existingSpinnerView;
        }

        setupClearControlsLayout();
        resetSessionState();

        btnClose.setOnClickListener(v -> {
            stopAutoLoop();
            stopSelf();
        });

        View dragTarget = floatingView.findViewById(R.id.root_container);
        if (dragTarget == null) dragTarget = floatingView;

        dragTarget.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        btnAnalyze.setOnClickListener(v -> {
            if (!isAutoRunning) {
                startAutoLoop();
            } else {
                stopAutoLoop();
            }
        });
    }

    private ArrayAdapter<String> createDarkSpinnerAdapter(String[] items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                tv.setSingleLine(true);
                tv.setPadding(dp(8), dp(6), dp(8), dp(6));
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setBackgroundColor(Color.parseColor("#1E293B"));
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                return tv;
            }
        };
    }

    private GradientDrawable createSpinnerBoxBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E293B"));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), Color.parseColor("#475569"));
        return gd;
    }

    private LinearLayout createLabeledRow(String labelText, Spinner spinner) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(rowLp);

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.parseColor("#94A3B8"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(label, labelLp);

        if (spinner.getParent() instanceof ViewGroup) {
            ((ViewGroup) spinner.getParent()).removeView(spinner);
        }
        spinner.setBackground(createSpinnerBoxBg());
        spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.parseColor("#1E293B")));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        row.addView(spinner, spLp);

        return row;
    }

    private void setupClearControlsLayout() {
        String[] pairs = {
                "EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "USD/CAD", "EUR/JPY",
                "BTC/USD", "ETH/USD", "GRAM/USD", "LTC/USD", "BNB/USD"
        };
        String[] modes = {
                "1M (0-60s -> 50-55s)",
                "15M (0-15m -> 14m40-50s)"
        };

        if (spinnerCurrency == null) {
            spinnerCurrency = new Spinner(this);
        }
        spinnerCurrency.setAdapter(createDarkSpinnerAdapter(pairs));
        spinnerCurrency.setSelection(0);

        spinnerTimeframe = new Spinner(this);
        spinnerTimeframe.setAdapter(createDarkSpinnerAdapter(modes));
        spinnerTimeframe.setSelection(0);

        int tfId = getResources().getIdentifier("spinnerTimeframe", "id", getPackageName());
        if (tfId != 0) {
            View oldTf = floatingView.findViewById(tfId);
            if (oldTf != null && oldTf.getParent() instanceof ViewGroup) {
                ((ViewGroup) oldTf.getParent()).removeView(oldTf);
            }
        }

        LinearLayout controlsBox = new LinearLayout(this);
        controlsBox.setOrientation(LinearLayout.VERTICAL);
        controlsBox.setPadding(dp(4), dp(4), dp(4), dp(6));
        controlsBox.addView(createLabeledRow("PAIR", spinnerCurrency));
        controlsBox.addView(createLabeledRow("TIMER", spinnerTimeframe));

        ViewGroup targetContainer = null;
        if (tvSignalResult != null && tvSignalResult.getParent() instanceof ViewGroup) {
            targetContainer = (ViewGroup) tvSignalResult.getParent();
        } else if (floatingView instanceof ViewGroup) {
            targetContainer = (ViewGroup) floatingView;
        }

        if (targetContainer != null) {
            int insertIndex = 0;
            if (tvSignalResult != null) {
                int idx = targetContainer.indexOfChild(tvSignalResult);
                if (idx >= 0) insertIndex = idx;
            }
            targetContainer.addView(controlsBox, insertIndex);
        }

        spinnerCurrency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isAutoRunning) {
                    startAutoLoop();
                } else {
                    resetSessionState();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinnerTimeframe.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean newModeIs15M = (position == 1);
                if (newModeIs15M != is15MinuteMode) {
                    is15MinuteMode = newModeIs15M;
                    if (isAutoRunning) {
                        startAutoLoop();
                    } else {
                        resetSessionState();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void resetSessionState() {
        long now = System.currentTimeMillis();
        sessionStartTimeMs = now;
        lastCollectionTimeMs = 0L;
        lastAiCycleNumber = -1L;
        isScanInFlight = false;
        isAiInFlight = false;
        usingBackupPool = false;
        roundRobinIndex = 0;
        finnhubIndex = 0;
        lastUsedKeyLabel = "TD-Init";

        latestPrice = 0.0;
        latestFinnhubTick = "N/A";
        latestRsi = 50.0;
        latestEma9 = 0.0;
        latestEma21 = 0.0;
        latestCandlesSummary = "";

        String pair = getSelectedCurrencyPair();
        lockedSignalText = "READY";
        lockedSignalColor = Color.WHITE;
        lockedReasonText = pair + " | " + (is15MinuteMode
                ? "0s - 15M (signal at 14m40-50s)"
                : "0s - 1M (signal at 50-55s)");

        if (tvSignalResult != null) {
            tvSignalResult.setText(lockedSignalText);
            tvSignalResult.setTextColor(lockedSignalColor);
        }
        if (tvReason != null) {
            tvReason.setText(lockedReasonText);
            tvReason.setTextColor(Color.parseColor("#E2E8F0"));
        }
    }

    private void startAutoLoop() {
        resetSessionState();
        isAutoRunning = true;
        String pair = getSelectedCurrencyPair();
        btnAnalyze.setText("STOP 48/m ENGINE");
        btnAnalyze.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444")));
        tvSignalResult.setText(is15MinuteMode ? "15M ENGINE" : "1M ENGINE");
        tvSignalResult.setTextColor(Color.CYAN);
        tvReason.setText("[" + pair + " | 0s] " + (is15MinuteMode
                ? "Scanning for 14m40-50s..."
                : "Scanning for 50-55s..."));
        mainHandler.removeCallbacks(nonstopEngineRunnable);
        mainHandler.post(nonstopEngineRunnable);
    }

    private void stopAutoLoop() {
        isAutoRunning = false;
        mainHandler.removeCallbacks(nonstopEngineRunnable);
        resetSessionState();
        btnAnalyze.setText("START 48/m AUTO");
        btnAnalyze.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981")));
        tvReason.setText("Stopped & reset to 0s");
    }

    private void collectLiveDataNonstop(String symbol, String tdInterval) {
        isScanInFlight = true;
        scanExecutor.execute(() -> {
            try {
                JSONObject json = fetchMarketDataSmartPool(symbol, tdInterval);
                if ((json == null || json.optJSONArray("values") == null) && !"1min".equals(tdInterval)) {
                    json = fetchMarketDataSmartPool(symbol, "1min");
                }
                String fhTick = fetchFinnhubLivePriceRotated(symbol);

                JSONArray values = json != null ? json.optJSONArray("values") : null;
                if (values == null || values.length() == 0) {
                    throw new Exception("Empty candles");
                }

                List<Double> closes = new ArrayList<>();
                StringBuilder recentCandles = new StringBuilder();

                for (int i = values.length() - 1; i >= 0; i--) {
                    JSONObject candle = values.getJSONObject(i);
                    double close = Double.parseDouble(candle.getString("close"));
                    closes.add(close);

                    if (i < 8) {
                        recentCandles.append(String.format("O:%s H:%s L:%s C:%s | ",
                                candle.getString("open"),
                                candle.getString("high"),
                                candle.getString("low"),
                                candle.getString("close")));
                    }
                }

                latestPrice = closes.get(closes.size() - 1);
                latestFinnhubTick = fhTick;
                latestRsi = calculateRSI(closes, 14);
                latestEma9 = calculateEMA(closes, 9);
                latestEma21 = calculateEMA(closes, 21);
                latestCandlesSummary = recentCandles.toString();

            } catch (Exception ignored) {
            } finally {
                isScanInFlight = false;
            }
        });
    }

    private void triggerAiDecision(String symbol, String windowLabel, long cycleNumber, boolean fifteenMinMode) {
        isAiInFlight = true;
        mainHandler.post(() -> {
            tvSignalResult.setText("ANALYZING...");
            tvSignalResult.setTextColor(Color.YELLOW);
            tvReason.setText("[" + symbol + " | " + windowLabel + "] Groq + CF + Gem...");
        });

        aiExecutor.execute(() -> {
            String effectivePriceRef = !"N/A".equals(latestFinnhubTick)
                    ? latestFinnhubTick
                    : String.valueOf(latestPrice);

            String horizonText = fifteenMinMode
                    ? "We are at 14m40s-14m50s in the 14th minute before the next 15-minute candle opens."
                    : "We are in the 50s-55s decision window before the next 1-minute candle opens.";

            String prompt = "You are an expert binary & forex scalping analyst. " + horizonText + " Analyze this live data for " + symbol + ":\n" +
                    "Twelve Data Close: " + latestPrice + " | Finnhub Tick: " + latestFinnhubTick + " (Ref Price: " + effectivePriceRef + ")\n" +
                    "RSI(14): " + String.format("%.2f", latestRsi) + "\n" +
                    "EMA(9): " + String.format("%.5f", latestEma9) + " | EMA(21): " + String.format("%.5f", latestEma21) + "\n" +
                    "Last 8 Candles (oldest to newest): " + latestCandlesSummary + "\n\n" +
                    "Rules:\n" +
                    "- Strong bullish setup -> UP\n" +
                    "- Strong bearish setup -> DOWN\n" +
                    "- Choppy/flat/unclear -> WAIT\n" +
                    "Reply strictly in JSON: {\"signal\": \"UP\" or \"DOWN\" or \"WAIT\", \"confidence\": \"80%\", \"reason\": \"max 5 words\"}";

            List<Future<AiVoteResult>> futures = new ArrayList<>();

            futures.add(aiExecutor.submit(() -> {
                String raw = callGroqAPI(prompt);
                JSONObject obj = extractJsonObject(raw);
                String sig = normalizeSignal(obj.optString("signal", "WAIT"));
                return new AiVoteResult("Groq", sig, obj.optString("confidence", ""), obj.optString("reason", "Groq"));
            }));

            futures.add(aiExecutor.submit(() -> {
                String raw = callCloudflareAI(prompt);
                JSONObject obj = extractJsonObject(raw);
                String sig = normalizeSignal(obj.optString("signal", "WAIT"));
                return new AiVoteResult("CF", sig, obj.optString("confidence", ""), obj.optString("reason", "CF"));
            }));

            futures.add(aiExecutor.submit(() -> {
                String raw = callGeminiAPI(prompt);
                JSONObject obj = extractJsonObject(raw);
                String sig = normalizeSignal(obj.optString("signal", "WAIT"));
                return new AiVoteResult("Gem", sig, obj.optString("confidence", ""), obj.optString("reason", "Gem"));
            }));

            int upVotes = 0;
            int downVotes = 0;
            int waitVotes = 0;
            List<String> voteTags = new ArrayList<>();
            String chosenReason = "";
            String chosenConf = "";

            for (Future<AiVoteResult> f : futures) {
                try {
                    AiVoteResult res = f.get(7500, TimeUnit.MILLISECONDS);
                    if (res != null) {
                        voteTags.add(res.provider + ":" + res.signal);
                        if ("UP".equals(res.signal)) {
                            upVotes++;
                            if (chosenReason.isEmpty()) {
                                chosenReason = res.reason;
                                chosenConf = res.confidence;
                            }
                        } else if ("DOWN".equals(res.signal)) {
                            downVotes++;
                            if (chosenReason.isEmpty()) {
                                chosenReason = res.reason;
                                chosenConf = res.confidence;
                            }
                        } else {
                            waitVotes++;
                            if (chosenReason.isEmpty()) {
                                chosenReason = res.reason;
                                chosenConf = res.confidence;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            int totalVotes = upVotes + downVotes + waitVotes;
            if (totalVotes == 0) {
                String fallbackSignal = computeTechnicalFallbackSignal();
                applyLockedSignal(
                        fallbackSignal,
                        "TECH",
                        "All AI busy | RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick,
                        cycleNumber,
                        fifteenMinMode
                );
                return;
            }

            String finalSignal;
            if (upVotes > downVotes && upVotes >= waitVotes) {
                finalSignal = "UP";
            } else if (downVotes > upVotes && downVotes >= waitVotes) {
                finalSignal = "DOWN";
            } else if (waitVotes > upVotes && waitVotes > downVotes) {
                finalSignal = "WAIT";
            } else {
                finalSignal = computeTechnicalFallbackSignal();
            }

            String votesSummary = String.join(",", voteTags);
            if (chosenReason.isEmpty()) chosenReason = "Multi-AI consensus";
            applyLockedSignal(
                    finalSignal,
                    chosenConf,
                    chosenReason + " [" + votesSummary + "] RSI:" + (int) latestRsi,
                    cycleNumber,
                    fifteenMinMode
            );
        });
    }

    private String normalizeSignal(String rawSignal) {
        if (rawSignal == null) return "WAIT";
        String s = rawSignal.toUpperCase().trim();
        if (s.contains("UP") && !s.contains("DOWN")) return "UP";
        if (s.contains("DOWN")) return "DOWN";
        if (s.equals("UP") || s.equals("DOWN") || s.equals("WAIT")) return s;
        return "WAIT";
    }

    private String computeTechnicalFallbackSignal() {
        if (latestEma9 > latestEma21 && latestRsi >= 52 && latestRsi <= 78) {
            return "UP";
        } else if (latestEma9 < latestEma21 && latestRsi <= 48 && latestRsi >= 22) {
            return "DOWN";
        }
        return "WAIT";
    }

    private void applyLockedSignal(String signal, String confidence, String reasonDetail, long targetCycleNumber, boolean fifteenMinMode) {
        lockedSignalText = signal + (confidence.isEmpty() ? "" : " (" + confidence + ")");
        lockedReasonText = reasonDetail;

        if ("UP".equals(signal)) {
            lockedSignalColor = Color.parseColor("#10B981");
        } else if ("DOWN".equals(signal)) {
            lockedSignalColor = Color.parseColor("#EF4444");
        } else {
            lockedSignalColor = Color.parseColor("#F59E0B");
        }

        mainHandler.post(() -> {
            isAiInFlight = false;
            if (!isAutoRunning) return;
            long eSec = Math.max(0L, (System.currentTimeMillis() - sessionStartTimeMs) / 1000L);
            String pair = getSelectedCurrencyPair();

            if (!fifteenMinMode) {
                long currentCycle = eSec / 60L;
                int cSec = (int) (eSec % 60L);
                if (currentCycle == targetCycleNumber && cSec >= SIGNAL_START_SECOND_1M && cSec <= SIGNAL_END_SECOND_1M) {
                    tvSignalResult.setText(lockedSignalText);
                    tvSignalResult.setTextColor(lockedSignalColor);
                    tvReason.setText("[" + pair + " | 1M " + cSec + "s] " + lockedReasonText);
                }
            } else {
                long currentCycle = eSec / 900L;
                long secIn15m = eSec % 900L;
                int minPart = (int) (secIn15m / 60L);
                int secPart = (int) (secIn15m % 60L);
                if (currentCycle == targetCycleNumber && isIn15mSignalWindow(minPart, secPart)) {
                    tvSignalResult.setText(lockedSignalText);
                    tvSignalResult.setTextColor(lockedSignalColor);
                    tvReason.setText("[" + pair + " | 15M " + minPart + "m" + secPart + "s] " + lockedReasonText);
                }
            }
        });
    }

    private JSONObject fetchMarketDataSmartPool(String symbol, String interval) throws Exception {
        String[] activePool = usingBackupPool ? BACKUP_KEYS : PRIMARY_KEYS;
        JSONObject result = tryPool(activePool, symbol, interval, usingBackupPool ? "Set2" : "Set1");
        if (result != null) return result;

        if (!usingBackupPool) {
            usingBackupPool = true;
            roundRobinIndex = 0;
            result = tryPool(BACKUP_KEYS, symbol, interval, "Set2");
            if (result != null) return result;
        }

        usingBackupPool = false;
        throw new Exception("All Twelve Data keys busy");
    }

    private JSONObject tryPool(String[] pool, String symbol, String interval, String poolName) throws Exception {
        int attempts = 0;
        while (attempts < pool.length) {
            int idx = roundRobinIndex % pool.length;
            roundRobinIndex = (roundRobinIndex + 1) % pool.length;

            String key = pool[idx];
            if (key == null || key.trim().isEmpty()) {
                attempts++;
                continue;
            }

            String apiUrl = "https://api.twelvedata.com/time_series?symbol=" + symbol +
                    "&interval=" + interval + "&outputsize=30&apikey=" + key.trim();

            String response = httpGet(apiUrl);
            JSONObject json = new JSONObject(response);

            if (json.has("values")) {
                lastUsedKeyLabel = poolName + "-K" + (idx + 1);
                return json;
            }
            attempts++;
        }
        return null;
    }

    private boolean isCryptoPair(String pair) {
        return pair.contains("BTC") || pair.contains("ETH")
                || pair.contains("GRAM") || pair.contains("LTC") || pair.contains("BNB");
    }

    private String fetchFinnhubLivePriceRotated(String pair) {
        if (FINNHUB_KEYS.length == 0) return "N/A";
        for (int i = 0; i < FINNHUB_KEYS.length; i++) {
            int idx = finnhubIndex % FINNHUB_KEYS.length;
            finnhubIndex = (finnhubIndex + 1) % FINNHUB_KEYS.length;
            String key = FINNHUB_KEYS[idx];
            if (key == null || key.trim().isEmpty()) continue;

            String[] candidates;
            if (isCryptoPair(pair)) {
                String base = pair.replace("/", "");
                candidates = new String[]{"BINANCE:" + base + "T", "BINANCE:" + base};
            } else {
                String underscored = pair.replace("/", "_");
                candidates = new String[]{"OANDA:" + underscored, "FOREX:" + pair};
            }

            for (String fhSymbol : candidates) {
                try {
                    String url = "https://finnhub.io/api/v1/quote?symbol=" + fhSymbol + "&token=" + key.trim();
                    JSONObject obj = new JSONObject(httpGet(url));
                    double c = obj.optDouble("c", 0.0);
                    if (c > 0) {
                        return String.valueOf(c);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return "N/A";
    }

    private double calculateRSI(List<Double> closes, int period) {
        if (closes.size() <= period) return 50.0;
        double gain = 0, loss = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            if (diff >= 0) gain += diff;
            else loss -= diff;
        }
        if (loss == 0) return 100.0;
        double rs = (gain / period) / (loss / period);
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calculateEMA(List<Double> closes, int period) {
        if (closes.isEmpty()) return 0.0;
        double multiplier = 2.0 / (period + 1);
        double ema = closes.get(0);
        for (int i = 1; i < closes.size(); i++) {
            ema = ((closes.get(i) - ema) * multiplier) + ema;
        }
        return ema;
    }

    private String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);

        int code = conn.getResponseCode();
        InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "{}";

        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) content.append(line);
        in.close();
        conn.disconnect();
        return content.toString();
    }

    private String callGroqAPI(String promptText) throws Exception {
        String key = GROQ_API_KEY.trim();
        if (key.isEmpty()) throw new Exception("No Groq key");

        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setConnectTimeout(6500);
        conn.setReadTimeout(6500);
        conn.setDoOutput(true);

        JSONObject sysMsg = new JSONObject()
                .put("role", "system")
                .put("content", "Reply strictly in JSON: {\"signal\":\"UP|DOWN|WAIT\",\"confidence\":\"80%\",\"reason\":\"max 5 words\"}");
        JSONObject userMsg = new JSONObject()
                .put("role", "user")
                .put("content", promptText);

        JSONObject payload = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("temperature", 0.2)
                .put("messages", new JSONArray().put(sysMsg).put(userMsg));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) response.append(line);
        in.close();
        conn.disconnect();

        if (code >= 400) throw new Exception("Groq HTTP " + code);

        JSONObject jsonResponse = new JSONObject(response.toString());
        return jsonResponse.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }

    private String callCloudflareAI(String promptText) throws Exception {
        String accId = CF_ACCOUNT_ID.trim();
        String token = CF_API_TOKEN.trim();
        if (accId.isEmpty() || token.isEmpty()) throw new Exception("No CF credentials");

        String endpoint = "https://api.cloudflare.com/client/v4/accounts/"
                + accId + "/ai/run/" + CF_MODEL;
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setDoOutput(true);

        JSONObject sysMsg = new JSONObject()
                .put("role", "system")
                .put("content", "Reply strictly in JSON: {\"signal\":\"UP|DOWN|WAIT\",\"confidence\":\"80%\",\"reason\":\"max 5 words\"}");
        JSONObject userMsg = new JSONObject()
                .put("role", "user")
                .put("content", promptText);

        JSONObject payload = new JSONObject()
                .put("messages", new JSONArray().put(sysMsg).put(userMsg));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) response.append(line);
        in.close();
        conn.disconnect();

        if (code >= 400) throw new Exception("CF HTTP " + code);

        JSONObject jsonResponse = new JSONObject(response.toString());
        JSONObject resultObj = jsonResponse.optJSONObject("result");
        if (resultObj != null && resultObj.has("response")) {
            return resultObj.getString("response");
        }
        return response.toString();
    }

    private String callGeminiAPI(String promptText) throws Exception {
        String stdKey = GEMINI_STANDARD_KEY.trim();
        String authKey = GEMINI_AUTH_KEY.trim();
        String baseEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + GEMINI_MODEL + ":generateContent";

        JSONObject textPart = new JSONObject().put("text", promptText);
        JSONObject parts = new JSONObject().put("parts", new JSONArray().put(textPart));
        JSONObject genConfig = new JSONObject().put("responseMimeType", "application/json");
        JSONObject payload = new JSONObject()
                .put("contents", new JSONArray().put(parts))
                .put("generationConfig", genConfig);

        Exception lastEx = null;

        if (!stdKey.isEmpty()) {
            try {
                return postGeminiRequest(baseEndpoint + "?key=" + stdKey, payload.toString(), null);
            } catch (Exception ex) {
                lastEx = ex;
            }
        }

        if (!authKey.isEmpty()) {
            try {
                return postGeminiRequest(baseEndpoint, payload.toString(), "Bearer " + authKey);
            } catch (Exception ex) {
                lastEx = ex;
            }
        }

        throw lastEx != null ? lastEx : new Exception("No Gemini key");
    }

    private String postGeminiRequest(String endpointUrl, String jsonBody, String authHeader) throws Exception {
        URL url = new URL(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (authHeader != null && !authHeader.isEmpty()) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) response.append(line);
        in.close();
        conn.disconnect();

        if (code >= 400) {
            throw new Exception("HTTP " + code);
        }

        JSONObject jsonResponse = new JSONObject(response.toString());
        return jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text");
    }

    private JSONObject extractJsonObject(String raw) throws Exception {
        if (raw == null) throw new Exception("Empty AI text");
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return new JSONObject(cleaned);
    }

    private void startForegroundNotification() {
        String channelId = "ai_trader_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "AI Trader Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("AI Trade Assistant Active")
                .setContentText("PAIR & TIMER controls active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAutoLoop();
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {
            }
            floatingView = null;
        }
    }
}
