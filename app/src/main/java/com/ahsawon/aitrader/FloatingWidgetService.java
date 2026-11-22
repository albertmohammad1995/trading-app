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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
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

    // Finnhub ২টি Key (পালাক্রমে লাইভ টিক প্রাইস আনবে)
    private static final String[] FINNHUB_KEYS = {
            "dc199qhr01qgfid6qm8gdc199qhr01qgfid6qm90",
            "Dc19bdhr01qgfid6qqi0dc19bdhr01qgfid6qqig"
    };

    private int roundRobinIndex = 0;
    private int finnhubIndex = 0;
    private boolean usingBackupPool = false;
    private String lastUsedKeyLabel = "";

    // Gemini API Key
    private static final String GEMINI_API_KEY = "AQ.Ab8RN6LeSxQQuI2grPNiubo5H-lS2mRvGpmVv26BtGSE5XngUQ";

    // প্রতি মিনিটের ৫০ থেকে ৫৫ সেকেন্ড পর্যন্ত UP / DOWN / WAIT নির্দেশনা দেবে
    private static final int SIGNAL_START_SECOND = 50;
    private static final int SIGNAL_END_SECOND = 55;

    // প্রতি ১.২৫ সেকেন্ড (১২৫০ms) পরপর ননস্টপ ডেটা কালেকশন (মিনিটে ৪৮ বার)
    private static final long COLLECTION_INTERVAL_MS = 1250;

    private boolean isAutoRunning = false;
    private boolean isScanInFlight = false;
    private boolean isAiInFlight = false;
    private int lastAiMinuteKey = -1;
    private long lastCollectionTimeMs = 0;

    // সম্মিলিত লাইভ মার্কেট ডেটা ক্যাশ
    private volatile double latestPrice = 0.0;
    private volatile String latestFinnhubTick = "N/A";
    private volatile double latestRsi = 50.0;
    private volatile double latestEma9 = 0.0;
    private volatile double latestEma21 = 0.0;
    private volatile String latestCandlesSummary = "";

    private String lockedSignalText = "READY";
    private int lockedSignalColor = Color.WHITE;
    private String lockedReasonText = "48/min nonstop scan ready";

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable nonstopEngineRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAutoRunning) return;

            Calendar now = Calendar.getInstance();
            int sec = now.get(Calendar.SECOND);
            int minuteKey = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            long nowMs = System.currentTimeMillis();

            String selectedPair = spinnerCurrency.getSelectedItem().toString();

            // ১. পুরো ৬০ সেকেন্ড ধরে ননস্টপ ডেটা কালেকশন
            if (!isScanInFlight && (nowMs - lastCollectionTimeMs >= COLLECTION_INTERVAL_MS)) {
                lastCollectionTimeMs = nowMs;
                collectLiveDataNonstop(selectedPair);
            }

            // ২. ৫০ থেকে ৫৫ সেকেন্ডের উইন্ডোতে AI নির্দেশনা (UP / DOWN / WAIT)
            if (sec >= SIGNAL_START_SECOND && sec <= SIGNAL_END_SECOND) {
                if (minuteKey != lastAiMinuteKey && !isAiInFlight && !latestCandlesSummary.isEmpty()) {
                    lastAiMinuteKey = minuteKey;
                    triggerAiDecision(selectedPair, sec);
                } else if (!isAiInFlight) {
                    tvSignalResult.setText(lockedSignalText);
                    tvSignalResult.setTextColor(lockedSignalColor);
                    tvReason.setText("[" + sec + "s / 50-55s] " + lockedReasonText);
                }
            } else if (!isAiInFlight) {
                String liveBias = (latestEma9 > latestEma21) ? "LIVE: BULLISH"
                        : (latestEma9 < latestEma21) ? "LIVE: BEARISH" : "SCANNING 48/m";
                int biasColor = (latestEma9 > latestEma21) ? Color.parseColor("#34D399")
                        : (latestEma9 < latestEma21) ? Color.parseColor("#F87171") : Color.CYAN;

                tvSignalResult.setText(liveBias);
                tvSignalResult.setTextColor(biasColor);
                tvReason.setText("[" + sec + "s] RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick + " | " + lastUsedKeyLabel);
            }

            mainHandler.postDelayed(this, 200);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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

    private void startAutoLoop() {
        isAutoRunning = true;
        lastAiMinuteKey = -1;
        lastCollectionTimeMs = 0;
        btnAnalyze.setText("STOP 48/m ENGINE");
        btnAnalyze.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444")));
        tvSignalResult.setText("NONSTOP 48/m");
        tvSignalResult.setTextColor(Color.CYAN);
        mainHandler.removeCallbacks(nonstopEngineRunnable);
        mainHandler.post(nonstopEngineRunnable);
    }

    private void stopAutoLoop() {
        isAutoRunning = false;
        mainHandler.removeCallbacks(nonstopEngineRunnable);
        btnAnalyze.setText("START 48/m AUTO");
        btnAnalyze.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981")));
        tvReason.setText("Stopped by user");
    }

    private void collectLiveDataNonstop(String symbol) {
        isScanInFlight = true;
        scanExecutor.execute(() -> {
            try {
                JSONObject json = fetchMarketDataSmartPool(symbol);
                String fhTick = fetchFinnhubLivePriceRotated(symbol);

                JSONArray values = json.getJSONArray("values");
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

    private void triggerAiDecision(String symbol, int triggerSec) {
        isAiInFlight = true;
        mainHandler.post(() -> {
            tvSignalResult.setText("ANALYZING...");
            tvSignalResult.setTextColor(Color.YELLOW);
            tvReason.setText("[" + triggerSec + "s] Combining TD + FH + AI...");
        });

        aiExecutor.execute(() -> {
            try {
                String prompt = "You are an expert 1-minute binary & forex scalping analyst. We are in the 50s-55s decision window before the next 1-minute candle opens. Combine and analyze this live data from Twelve Data, Finnhub, and indicators for " + symbol + ":\n" +
                        "Twelve Data Candle Close: " + latestPrice + " | Live Finnhub Tick: " + latestFinnhubTick + "\n" +
                        "RSI(14): " + String.format("%.2f", latestRsi) + "\n" +
                        "EMA(9): " + String.format("%.5f", latestEma9) + " | EMA(21): " + String.format("%.5f", latestEma21) + "\n" +
                        "Last 8 Candles (oldest to newest): " + latestCandlesSummary + "\n\n" +
                        "Rules:\n" +
                        "- If strong bullish setup for next candle, reply UP.\n" +
                        "- If strong bearish setup for next candle, reply DOWN.\n" +
                        "- If choppy/flat/risky, reply WAIT.\n" +
                        "Respond strictly in JSON format only: {\"signal\": \"UP\" or \"DOWN\" or \"WAIT\", \"confidence\": \"85%\", \"reason\": \"max 6 words reason\"}";

                String aiResultJson = callGeminiAPI(prompt);
                JSONObject aiObj = new JSONObject(aiResultJson);

                String signal = aiObj.optString("signal", "WAIT").toUpperCase();
                String confidence = aiObj.optString("confidence", "");
                String reason = aiObj.optString("reason", "Signal ready");

                lockedSignalText = signal + (confidence.isEmpty() ? "" : " (" + confidence + ")");
                lockedReasonText = reason + " | RSI:" + (int) latestRsi + " | FH:" + latestFinnhubTick + " [" + lastUsedKeyLabel + "]";

                if (signal.contains("UP")) {
                    lockedSignalColor = Color.parseColor("#10B981");
                } else if (signal.contains("DOWN")) {
                    lockedSignalColor = Color.parseColor("#EF4444");
                } else {
                    lockedSignalColor = Color.parseColor("#F59E0B");
                }

                mainHandler.post(() -> {
                    isAiInFlight = false;
                    if (!isAutoRunning) return;
                    int s = Calendar.getInstance().get(Calendar.SECOND);
                    tvSignalResult.setText(lockedSignalText);
                    tvSignalResult.setTextColor(lockedSignalColor);
                    tvReason.setText("[" + s + "s / 50-55s] " + lockedReasonText);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    isAiInFlight = false;
                    if (!isAutoRunning) return;
                    lockedSignalText = "WAIT";
                    lockedSignalColor = Color.parseColor("#F59E0B");
                    lockedReasonText = e.getMessage();
                    tvSignalResult.setText(lockedSignalText);
                    tvSignalResult.setTextColor(lockedSignalColor);
                    tvReason.setText(lockedReasonText);
                });
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
        throw new Exception("All 8 Twelve Data keys reached limit!");
    }

    private JSONObject tryPool(String[] pool, String symbol, String poolName) throws Exception {
        int attempts = 0;
        while (attempts < pool.length) {
            int idx = roundRobinIndex % pool.length;
            roundRobinIndex = (roundRobinIndex + 1) % pool.length;

            String key = pool[idx];
            if (key == null || key.isEmpty()) {
                attempts++;
                continue;
            }

            String apiUrl = "https://api.twelvedata.com/time_series?symbol=" + symbol +
                    "&interval=1min&outputsize=30&apikey=" + key;

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
            if (key == null || key.isEmpty()) continue;

            try {
                String fhSymbol = pair.contains("BTC") || pair.contains("ETH")
                        ? "BINANCE:" + pair.replace("/", "") + (pair.endsWith("/USD") ? "T" : "")
                        : "OANDA:" + pair.replace("/", "_");
                String url = "https://finnhub.io/api/v1/quote?symbol=" + fhSymbol + "&token=" + key;
                JSONObject obj = new JSONObject(httpGet(url));
                double c = obj.optDouble("c", 0.0);
                if (c > 0) return String.valueOf(c);
            } catch (Exception ignored) {
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
        BufferedReader in = new BufferedReader(new InputStreamReader(
                conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) content.append(line);
        in.close();
        return content.toString();
    }

    private String callGeminiAPI(String promptText) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + GEMINI_API_KEY);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JSONObject textPart = new JSONObject().put("text", promptText);
        JSONObject parts = new JSONObject().put("parts", new JSONArray().put(textPart));
        JSONObject payload = new JSONObject().put("contents", new JSONArray().put(parts));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes("UTF-8"));
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) response.append(line);
        in.close();

        JSONObject jsonResponse = new JSONObject(response.toString());
        String rawText = jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text");

        return rawText.replace("```json", "").replace("```", "").trim();
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
        if (floatingView != null) windowManager.removeView(floatingView);
    }
}
