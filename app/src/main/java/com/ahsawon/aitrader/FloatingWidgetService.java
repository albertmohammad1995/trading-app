package com.ahsawon.aitrader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

public class FloatingWidgetService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private Spinner spinnerCurrency;
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

    // Gemini API Key
    private static final String GEMINI_API_KEY = "AQ.Ab8RN6LeSxQQuI2grPNiubo5H-lS2mRvGpmVv26BtGSE5XngUQ";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";

    // প্রতি ৬০ সেকেন্ডের সাইকেলে ৫০ থেকে ৫৫ সেকেন্ড পর্যন্ত UP / DOWN / WAIT নির্দেশনা দেবে
    private static final int SIGNAL_START_SECOND = 50;
    private static final int SIGNAL_END_SECOND = 55;

    // প্রতি ১.২৫ সেকেন্ড (১২৫০ms) পরপর ননস্টপ ডেটা কালেকশন (মিনিটে ৪৮ বার)
    private static final long COLLECTION_INTERVAL_MS = 1250;

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
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable nonstopEngineRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAutoRunning) return;

            long nowMs = System.currentTimeMillis();
            long elapsedSec = Math.max(0L, (nowMs - sessionStartTimeMs) / 1000L);
            long cycleNumber = elapsedSec / 60L;
            int cycleSec = (int) (elapsedSec % 60L);

            String selectedPair = spinnerCurrency.getSelectedItem().toString();

            // ১. পুরো ৬০ সেকেন্ড ধরে ননস্টপ ডেটা কালেকশন
            if (!isScanInFlight && (nowMs - lastCollectionTimeMs >= COLLECTION_INTERVAL_MS)) {
                lastCollectionTimeMs = nowMs;
                collectLiveDataNonstop(selectedPair);
            }

            // ২. শুধুমাত্র ৫০ থেকে ৫৫ সেকেন্ডের উইন্ডোতে সিগন্যাল নির্দেশনা (UP / DOWN / WAIT)
            if (cycleSec >= SIGNAL_START_SECOND && cycleSec <= SIGNAL_END_SECOND) {
                if (cycleNumber != lastAiCycleNumber && !isAiInFlight && !latestCandlesSummary.isEmpty()) {
                    lastAiCycleNumber = cycleNumber;
                    triggerAiDecision(selectedPair, cycleSec, cycleNumber);
                } else if (!isAiInFlight) {
                    tvSignalResult.setText(lockedSignalText);
                    tvSignalResult.setTextColor(lockedSignalColor);
                    tvReason.setText("[" + cycleSec + "s / 50-55s] " + lockedReasonText);
                }
            } else if (!isAiInFlight) {
                String liveBias = (latestEma9 > latestEma21) ? "LIVE: BULLISH"
                        : (latestEma9 < latestEma21) ? "LIVE: BEARISH" : "SCANNING 48/m";
                int biasColor = (latestEma9 > latestEma21) ? Color.parseColor("#34D399")
                        : (latestEma9 < latestEma21) ? Color.parseColor("#F87171") : Color.CYAN;

                tvSignalResult.setText(liveBias);
                tvSignalResult.setTextColor(biasColor);
                tvReason.setText("[" + cycleSec + "s] RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick + " | " + lastUsedKeyLabel);
            }

            mainHandler.postDelayed(this, 200);
        }
    };

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
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 200;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingView, params);

        spinnerCurrency = floatingView.findViewById(R.id.spinnerCurrency);
        btnAnalyze = floatingView.findViewById(R.id.btnAnalyze);
        tvSignalResult = floatingView.findViewById(R.id.tvSignalResult);
        tvReason = floatingView.findViewById(R.id.tvReason);
        TextView btnClose = floatingView.findViewById(R.id.btnClose);

        String[] pairs = {"EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "USD/CAD", "EUR/JPY", "BTC/USD", "ETH/USD"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, pairs);
        spinnerCurrency.setAdapter(adapter);

        resetSessionState();

        btnClose.setOnClickListener(v -> {
            stopAutoLoop();
            stopSelf();
        });

        floatingView.findViewById(R.id.root_container).setOnTouchListener(new View.OnTouchListener() {
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

        lockedSignalText = "READY";
        lockedSignalColor = Color.WHITE;
        lockedReasonText = "0s - Fresh scan started";

        if (tvSignalResult != null) {
            tvSignalResult.setText(lockedSignalText);
            tvSignalResult.setTextColor(lockedSignalColor);
        }
        if (tvReason != null) {
            tvReason.setText(lockedReasonText);
        }
    }

    private void startAutoLoop() {
        resetSessionState();
        isAutoRunning = true;
        btnAnalyze.setText("STOP 48/m ENGINE");
        btnAnalyze.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444")));
        tvSignalResult.setText("NONSTOP 48/m");
        tvSignalResult.setTextColor(Color.CYAN);
        tvReason.setText("[0s] Starting fresh scan...");
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

    private void collectLiveDataNonstop(String symbol) {
        isScanInFlight = true;
        scanExecutor.execute(() -> {
            try {
                JSONObject json = fetchMarketDataSmartPool(symbol);
                String fhTick = fetchFinnhubLivePriceRotated(symbol);

                JSONArray values = json.optJSONArray("values");
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

    private void triggerAiDecision(String symbol, int triggerCycleSec, long cycleNumber) {
        isAiInFlight = true;
        mainHandler.post(() -> {
            tvSignalResult.setText("ANALYZING...");
            tvSignalResult.setTextColor(Color.YELLOW);
            tvReason.setText("[" + triggerCycleSec + "s / 50-55s] Combining TD + FH + AI...");
        });

        aiExecutor.execute(() -> {
            String effectivePriceRef = !"N/A".equals(latestFinnhubTick)
                    ? latestFinnhubTick
                    : String.valueOf(latestPrice);

            try {
                String prompt = "You are an expert 1-minute binary & forex scalping analyst. We are in the 50s-55s decision window before the next 1-minute candle opens. Analyze this live data for " + symbol + ":\n" +
                        "Twelve Data Close: " + latestPrice + " | Finnhub Tick: " + latestFinnhubTick + " (Ref Price: " + effectivePriceRef + ")\n" +
                        "RSI(14): " + String.format("%.2f", latestRsi) + "\n" +
                        "EMA(9): " + String.format("%.5f", latestEma9) + " | EMA(21): " + String.format("%.5f", latestEma21) + "\n" +
                        "Last 8 Candles (oldest to newest): " + latestCandlesSummary + "\n\n" +
                        "Rules:\n" +
                        "- Strong bullish setup -> UP\n" +
                        "- Strong bearish setup -> DOWN\n" +
                        "- Choppy/flat/unclear -> WAIT\n" +
                        "Reply strictly in JSON: {\"signal\": \"UP\" or \"DOWN\" or \"WAIT\", \"confidence\": \"80%\", \"reason\": \"max 6 words\"}";

                String aiResultJson = callGeminiAPI(prompt);
                JSONObject aiObj = extractJsonObject(aiResultJson);

                String signal = aiObj.optString("signal", "WAIT").toUpperCase().trim();
                if (!signal.equals("UP") && !signal.equals("DOWN") && !signal.equals("WAIT")) {
                    signal = computeTechnicalFallbackSignal();
                }

                String confidence = aiObj.optString("confidence", "");
                String reason = aiObj.optString("reason", "AI analysis");

                applyLockedSignal(signal, confidence, reason + " | RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick + " [" + lastUsedKeyLabel + "]", cycleNumber);

            } catch (Exception e) {
                String fallbackSignal = computeTechnicalFallbackSignal();
                String shortErr = e.getMessage() != null ? e.getMessage() : "AI err";
                if (shortErr.length() > 28) shortErr = shortErr.substring(0, 28);
                applyLockedSignal(
                        fallbackSignal,
                        "TECH",
                        "AI:" + shortErr + " | RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick,
                        cycleNumber
                );
            }
        });
    }

    private String computeTechnicalFallbackSignal() {
        if (latestEma9 > latestEma21 && latestRsi >= 52 && latestRsi <= 78) {
            return "UP";
        } else if (latestEma9 < latestEma21 && latestRsi <= 48 && latestRsi >= 22) {
            return "DOWN";
        }
        return "WAIT";
    }

    private void applyLockedSignal(String signal, String confidence, String reasonDetail, long targetCycleNumber) {
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
            long currentCycle = eSec / 60L;
            int cSec = (int) (eSec % 60L);
            if (currentCycle == targetCycleNumber && cSec >= SIGNAL_START_SECOND && cSec <= SIGNAL_END_SECOND) {
                tvSignalResult.setText(lockedSignalText);
                tvSignalResult.setTextColor(lockedSignalColor);
                tvReason.setText("[" + cSec + "s / 50-55s] " + lockedReasonText);
            }
        });
    }

    private JSONObject fetchMarketDataSmartPool(String symbol) throws Exception {
        String[] activePool = usingBackupPool ? BACKUP_KEYS : PRIMARY_KEYS;
        JSONObject result = tryPool(activePool, symbol, usingBackupPool ? "Set2" : "Set1");
        if (result != null) return result;

        if (!usingBackupPool) {
            usingBackupPool = true;
            roundRobinIndex = 0;
            result = tryPool(BACKUP_KEYS, symbol, "Set2");
            if (result != null) return result;
        }

        usingBackupPool = false;
        throw new Exception("All Twelve Data keys busy");
    }

    private JSONObject tryPool(String[] pool, String symbol, String poolName) throws Exception {
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
                    "&interval=1min&outputsize=30&apikey=" + key.trim();

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

    private String fetchFinnhubLivePriceRotated(String pair) {
        if (FINNHUB_KEYS.length == 0) return "N/A";
        for (int i = 0; i < FINNHUB_KEYS.length; i++) {
            int idx = finnhubIndex % FINNHUB_KEYS.length;
            finnhubIndex = (finnhubIndex + 1) % FINNHUB_KEYS.length;
            String key = FINNHUB_KEYS[idx];
            if (key == null || key.trim().isEmpty()) continue;

            String[] candidates;
            if (pair.contains("BTC") || pair.contains("ETH")) {
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

    private String callGeminiAPI(String promptText) throws Exception {
        String key = GEMINI_API_KEY.trim();
        String baseEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + GEMINI_MODEL + ":generateContent";

        JSONObject textPart = new JSONObject().put("text", promptText);
        JSONObject parts = new JSONObject().put("parts", new JSONArray().put(textPart));
        JSONObject genConfig = new JSONObject().put("responseMimeType", "application/json");
        JSONObject payload = new JSONObject()
                .put("contents", new JSONArray().put(parts))
                .put("generationConfig", genConfig);

        // ১. Bearer হেডার দিয়ে চেষ্টা
        try {
            return postGeminiRequest(baseEndpoint, payload.toString(), "Bearer " + key);
        } catch (Exception bearerEx) {
            String msg = bearerEx.getMessage() != null ? bearerEx.getMessage() : "";
            if (!msg.contains("401") && !msg.contains("403")) {
                throw bearerEx;
            }
            // ২. Bearer না কাজ করলে query ?key= দিয়ে ফলব্যাক চেষ্টা
            return postGeminiRequest(baseEndpoint + "?key=" + key, payload.toString(), null);
        }
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
                .setContentText("48/min nonstop data & 50-55s signal active")
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
