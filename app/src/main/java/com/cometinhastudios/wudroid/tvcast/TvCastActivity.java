package com.cometinhastudios.wudroid.tvcast;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TvCastActivity extends Activity {
    private static final int DISCOVERY_PORT = 58340;
    private static final int VIDEO_PORT = 58341;
    private static final int MAGIC = 0x57564839;
    private static final int HEADER_SIZE = 28;
    private static final int MAX_ACCESS_UNIT_BYTES = 2_500_000;
    private static final int MAX_CHUNKS = 1024;
    private static final String DISCOVER = "WUDROID_TV_DISCOVER_V1";
    private static final String HERE = "WUDROID_TV_HERE_V1";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket discoverySocket;
    private DatagramSocket videoSocket;
    private Thread discoveryThread;
    private Thread videoThread;

    private Surface outputSurface;
    private MediaCodec decoder;
    private int decoderWidth;
    private int decoderHeight;
    private boolean decoderConfigQueued;
    private byte[] cachedConfig;
    private int cachedWidth = 640;
    private int cachedHeight = 360;
    private final MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
    private final Object decoderLock = new Object();

    private ReceiverHomeView homeView;
    private TextView fatalView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastFrameReceivedMs;
    private boolean videoVisible;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (running.get()) {
                long now = System.currentTimeMillis();
                if (videoVisible && lastFrameReceivedMs > 0L && now - lastFrameReceivedMs > 2500L) {
                    videoVisible = false;
                    if (homeView != null) {
                        homeView.setReceiverState("Conexão pausada", "Aguardando novos quadros do Wudroid no celular.");
                        homeView.setVisibility(View.VISIBLE);
                    }
                }
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private static final class Assembly {
        final byte[][] chunks;
        final int flags;
        final long ptsUs;
        final int width;
        final int height;
        int received;
        int bytes;

        Assembly(int chunkCount, int flags, long ptsUs, int width, int height) {
            this.chunks = new byte[chunkCount][];
            this.flags = flags;
            this.ptsUs = ptsUs;
            this.width = width;
            this.height = height;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            enterImmersive();
            buildUi();
            // Startup used to initialize MediaCodec immediately. Some phones/TVs can fail
            // while the Surface is still being created. Start transport after the UI settles;
            // decoder creation now happens only after real codec data arrives.
            mainHandler.postDelayed(this::startTransportSafely, 300L);
        } catch (Throwable t) {
            showFatalFallback("Falha ao iniciar o Wudroid TV Cast", t);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(4, 10, 16));

        SurfaceView video = new SurfaceView(this);
        video.setBackgroundColor(Color.BLACK);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        homeView = new ReceiverHomeView(this);
        root.addView(homeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                synchronized (decoderLock) {
                    outputSurface = holder.getSurface();
                    // Do not create a decoder here. Waiting for the first real codec config
                    // avoids startup crashes on devices with picky H.264 implementations.
                }
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                synchronized (decoderLock) {
                    outputSurface = holder.getSurface();
                }
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                synchronized (decoderLock) {
                    outputSurface = null;
                    releaseDecoderLocked();
                }
            }
        });

        setContentView(root);
    }

    private void showFatalFallback(String title, Throwable t) {
        try {
            TextView text = new TextView(this);
            text.setBackgroundColor(Color.rgb(4, 10, 16));
            text.setTextColor(Color.WHITE);
            text.setTextSize(22f);
            text.setPadding(48, 48, 48, 48);
            text.setGravity(android.view.Gravity.CENTER);
            String detail = t == null ? "" : ("\n\n" + t.getClass().getSimpleName());
            text.setText(title + "\n\nReabra o app ou envie o log para corrigirmos." + detail);
            fatalView = text;
            setContentView(text);
        } catch (Throwable ignored) {
            // Last-resort: do not throw again from the crash fallback.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        if (!running.get()) mainHandler.postDelayed(this::startTransportSafely, 200L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep receiving while temporarily obscured by system UI. Transport is stopped only
        // when Activity is destroyed, which is friendlier to TV launchers and HDMI mode changes.
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopTransport();
        synchronized (decoderLock) {
            outputSurface = null;
            releaseDecoderLocked();
        }
        super.onDestroy();
    }

    private void enterImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {}
    }

    private void startTransportSafely() {
        try {
            startTransport();
        } catch (Throwable t) {
            running.set(false);
            if (homeView != null) {
                homeView.setReceiverState("Falha ao iniciar o receptor", "Feche e abra o app novamente.");
            }
        }
    }

    private void startTransport() {
        if (!running.compareAndSet(false, true)) return;
        if (homeView != null) {
            homeView.setReceiverState("Pronto para transmitir", "Transmita o conteúdo do Wudroid para a sua TV.\nConecte-se pelo Wudroid no celular.");
        }

        try {
            videoSocket = new DatagramSocket(null);
            videoSocket.setReuseAddress(true);
            videoSocket.setReceiveBufferSize(2 * 1024 * 1024);
            videoSocket.bind(new InetSocketAddress(VIDEO_PORT));
        } catch (Throwable t) {
            setReceiverError("Porta de vídeo indisponível", "Outra instância pode estar usando a porta " + VIDEO_PORT + ".");
            stopTransport();
            return;
        }

        try {
            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);
            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(500);
            discoverySocket.bind(new InetSocketAddress(DISCOVERY_PORT));
        } catch (Throwable t) {
            setReceiverError("Falha na descoberta", "Não foi possível abrir a rede local.");
            stopTransport();
            return;
        }

        discoveryThread = new Thread(this::discoveryLoop, "Wudroid-TV-Cast-Discovery");
        discoveryThread.start();
        videoThread = new Thread(this::videoLoop, "Wudroid-TV-Cast-Video");
        videoThread.start();
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, 1000L);
    }

    private void stopTransport() {
        running.set(false);
        if (discoverySocket != null) try { discoverySocket.close(); } catch (Throwable ignored) {}
        if (videoSocket != null) try { videoSocket.close(); } catch (Throwable ignored) {}
        discoverySocket = null;
        videoSocket = null;
        discoveryThread = null;
        videoThread = null;
    }

    private void setReceiverError(String title, String detail) {
        runOnUiThread(() -> {
            if (homeView != null) {
                homeView.setReceiverState(title, detail);
                homeView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void discoveryLoop() {
        byte[] buffer = new byte[1024];
        DatagramSocket socket = discoverySocket;
        if (socket == null) return;
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                String[] parts = text.split("\\|", 3);
                if (parts.length < 2 || !DISCOVER.equals(parts[0])) continue;
                String nonce = parts[1].replace("|", "");
                if (nonce.length() > 32) nonce = nonce.substring(0, 32);
                String model = Build.MODEL == null ? "TV" : Build.MODEL;
                String name = ("Wudroid TV Cast - " + model).replace("|", " ");
                String replyText = HERE + "|" + nonce + "|" + name + "|" + VIDEO_PORT;
                byte[] reply = replyText.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (Throwable ignored) {
                if (!running.get()) break;
            }
        }
    }

    private void videoLoop() {
        DatagramSocket socket = videoSocket;
        if (socket == null) return;
        LinkedHashMap<Integer, Assembly> assemblies = new LinkedHashMap<>();
        int latestDeliveredUnitId = 0;
        byte[] packetBuffer = new byte[1500];

        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
                socket.receive(packet);
                if (packet.getLength() <= HEADER_SIZE) continue;

                ByteBuffer header = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
                if (header.getInt() != MAGIC) continue;
                int unitId = header.getInt();
                int chunkIndex = header.getShort() & 0xFFFF;
                int chunkCount = header.getShort() & 0xFFFF;
                int flags = header.getInt();
                long ptsUs = header.getLong();
                int width = header.getShort() & 0xFFFF;
                int height = header.getShort() & 0xFFFF;

                if (latestDeliveredUnitId > 0 && unitId <= latestDeliveredUnitId) continue;
                if (width < 64 || width > 4096 || height < 64 || height > 4096) continue;
                if (chunkCount < 1 || chunkCount > MAX_CHUNKS || chunkIndex >= chunkCount) continue;
                int payloadSize = packet.getLength() - HEADER_SIZE;
                if (payloadSize <= 0) continue;

                Assembly assembly = assemblies.get(unitId);
                if (assembly == null) {
                    assembly = new Assembly(chunkCount, flags, ptsUs, width, height);
                    assemblies.put(unitId, assembly);
                }
                if (assembly.chunks.length != chunkCount || assembly.flags != flags
                        || assembly.width != width || assembly.height != height) {
                    assemblies.remove(unitId);
                    continue;
                }

                if (assembly.chunks[chunkIndex] == null) {
                    byte[] data = new byte[payloadSize];
                    System.arraycopy(packet.getData(), packet.getOffset() + HEADER_SIZE, data, 0, payloadSize);
                    assembly.chunks[chunkIndex] = data;
                    assembly.received++;
                    assembly.bytes += payloadSize;
                }

                if (assembly.bytes > MAX_ACCESS_UNIT_BYTES) {
                    assemblies.remove(unitId);
                    continue;
                }

                if (assembly.received == chunkCount) {
                    byte[] complete = reassemble(assembly);
                    assemblies.remove(unitId);
                    if (complete != null) {
                        latestDeliveredUnitId = unitId;
                        Iterator<Integer> it = assemblies.keySet().iterator();
                        while (it.hasNext()) if (it.next() <= latestDeliveredUnitId) it.remove();
                        handleAccessUnit(complete, assembly.flags, assembly.ptsUs, assembly.width, assembly.height);
                    }
                }

                while (assemblies.size() > 4) {
                    Integer first = assemblies.keySet().iterator().next();
                    assemblies.remove(first);
                }
            } catch (Throwable ignored) {
                if (!running.get()) break;
            }
        }
    }

    private byte[] reassemble(Assembly assembly) {
        byte[] out = new byte[assembly.bytes];
        int pos = 0;
        for (byte[] chunk : assembly.chunks) {
            if (chunk == null) return null;
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }

    private void handleAccessUnit(byte[] bytes, int flags, long ptsUs, int width, int height) {
        boolean isConfig = (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        synchronized (decoderLock) {
            if (isConfig) {
                cachedConfig = bytes;
                cachedWidth = width;
                cachedHeight = height;
                if (outputSurface != null && ensureDecoderLocked(width, height)) {
                    if (!decoderConfigQueued) {
                        decoderConfigQueued = queueDecoderDataLocked(bytes, ptsUs, true);
                    }
                }
                runOnUiThread(() -> {
                    if (homeView != null) {
                        homeView.setReceiverState("Conectado • " + width + "×" + height,
                                "Recebendo vídeo do Wudroid no celular.");
                    }
                });
                return;
            }

            if (outputSurface == null || !ensureDecoderLocked(width, height)) return;
            if (!decoderConfigQueued) {
                if (cachedConfig == null) return;
                decoderConfigQueued = queueDecoderDataLocked(cachedConfig, ptsUs, true);
                if (!decoderConfigQueued) return;
            }

            if (queueDecoderDataLocked(bytes, ptsUs, false)) {
                lastFrameReceivedMs = System.currentTimeMillis();
                if (!videoVisible) {
                    videoVisible = true;
                    runOnUiThread(() -> {
                        if (homeView != null) homeView.setVisibility(View.GONE);
                    });
                }
            }
        }
    }

    private boolean ensureDecoderLocked(int width, int height) {
        if (outputSurface == null || !outputSurface.isValid()) return false;
        if (decoder != null && decoderWidth == width && decoderHeight == height) return true;
        releaseDecoderLocked();
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            if (Build.VERSION.SDK_INT >= 23) {
                try { format.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Throwable ignored) {}
                try { format.setFloat(MediaFormat.KEY_OPERATING_RATE, 60f); } catch (Throwable ignored) {}
            }
            if (Build.VERSION.SDK_INT >= 30) {
                try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); } catch (Throwable ignored) {}
            }
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();
            try { decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); }
            catch (Throwable ignored) {}
            decoderWidth = width;
            decoderHeight = height;
            decoderConfigQueued = false;
            return true;
        } catch (Throwable t) {
            releaseDecoderLocked();
            setReceiverError("Decoder H.264 indisponível", "O dispositivo não conseguiu iniciar o decoder de vídeo.");
            return false;
        }
    }

    private void releaseDecoderLocked() {
        MediaCodec codec = decoder;
        decoder = null;
        decoderWidth = 0;
        decoderHeight = 0;
        decoderConfigQueued = false;
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
        }
    }

    private boolean queueDecoderDataLocked(byte[] bytes, long ptsUs, boolean isConfig) {
        MediaCodec codec = decoder;
        if (codec == null) return false;
        try {
            drainDecoderLocked(codec);
            int index = codec.dequeueInputBuffer(3000L);
            if (index < 0) return false;
            ByteBuffer input = codec.getInputBuffer(index);
            if (input == null || input.capacity() < bytes.length) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
                return false;
            }
            input.clear();
            input.put(bytes);
            codec.queueInputBuffer(index, 0, bytes.length, ptsUs,
                    isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
            drainDecoderLocked(codec);
            return true;
        } catch (Throwable t) {
            releaseDecoderLocked();
            return false;
        }
    }

    private void drainDecoderLocked(MediaCodec codec) {
        ArrayList<Integer> ready = new ArrayList<>();
        while (true) {
            int index = codec.dequeueOutputBuffer(decoderInfo, 0L);
            if (index >= 0) {
                ready.add(index);
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            break;
        }
        if (ready.isEmpty()) return;
        for (int i = 0; i < ready.size() - 1; i++) {
            try { codec.releaseOutputBuffer(ready.get(i), false); } catch (Throwable ignored) {}
        }
        try { codec.releaseOutputBuffer(ready.get(ready.size() - 1), true); } catch (Throwable ignored) {}
    }

    private boolean hasLocalNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private final class ReceiverHomeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap icon;
        private String stateTitle = "Pronto para transmitir";
        private String stateDetail = "Transmita o conteúdo do Wudroid para a sua TV.\nConecte-se pelo Wudroid no celular.";
        private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 15000L);
            }
        };

        ReceiverHomeView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            try { icon = BitmapFactory.decodeResource(getResources(), R.drawable.wudroid_cast_icon); }
            catch (Throwable ignored) { icon = null; }
            strokePaint.setStyle(Paint.Style.STROKE);
            post(tick);
        }

        void setReceiverState(String title, String detail) {
            stateTitle = title == null ? "" : title;
            stateDetail = detail == null ? "" : detail;
            invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w / 1600f, h / 900f);
            float ox = (w - 1600f * s) * 0.5f;
            float oy = (h - 900f * s) * 0.5f;

            // Background: same dark/navy Wudroid family, with restrained blue arcs.
            c.drawColor(Color.rgb(4, 12, 20));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(6, 21, 34));
            c.drawCircle(ox - 80*s, oy + 820*s, 330*s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(58*s);
            paint.setColor(Color.rgb(8, 43, 73));
            c.drawCircle(ox - 70*s, oy + 825*s, 285*s, paint);
            paint.setStrokeWidth(48*s);
            c.drawCircle(ox + 1640*s, oy + 930*s, 360*s, paint);
            paint.setStyle(Paint.Style.FILL);

            // Header icon + WUDROID / TV Cast.
            drawIcon(c, ox + 68*s, oy + 58*s, 86*s, 86*s);
            drawText(c, "WUDROID", ox + 175*s, oy + 98*s, 38*s, true, Color.rgb(13, 187, 238));
            drawText(c, "TV Cast", ox + 176*s, oy + 135*s, 25*s, false, Color.rgb(166, 178, 194));
            drawTextRight(c, clock.format(new Date()), ox + 1515*s, oy + 100*s, 30*s, false, Color.rgb(205, 214, 225));

            // Main receiver card.
            RectF hero = new RectF(ox + 225*s, oy + 175*s, ox + 1375*s, oy + 615*s);
            drawCard(c, hero, Color.rgb(7, 25, 40), Color.rgb(14, 54, 80), 34*s, 2*s);
            drawIcon(c, ox + 720*s, oy + 220*s, 160*s, 160*s);
            drawCenteredText(c, "Wudroid TV Cast", ox + 800*s, oy + 433*s, 55*s, true, Color.WHITE);
            drawCenteredText(c, stateTitle, ox + 800*s, oy + 495*s, 34*s, false, Color.rgb(236, 240, 246));
            drawCenteredMultiline(c, stateDetail, ox + 800*s, oy + 548*s, 27*s, Color.rgb(148, 167, 189), 34*s);

            // Three status cards.
            float cardTop = oy + 650*s;
            float cardBottom = oy + 810*s;
            RectF card1 = new RectF(ox + 65*s, cardTop, ox + 530*s, cardBottom);
            RectF card2 = new RectF(ox + 565*s, cardTop, ox + 1030*s, cardBottom);
            RectF card3 = new RectF(ox + 1065*s, cardTop, ox + 1535*s, cardBottom);
            drawCard(c, card1, Color.rgb(7, 24, 38), Color.rgb(12, 45, 67), 26*s, 2*s);
            drawCard(c, card2, Color.rgb(7, 24, 38), Color.rgb(12, 45, 67), 26*s, 2*s);
            drawCard(c, card3, Color.rgb(7, 24, 38), Color.rgb(12, 45, 67), 26*s, 2*s);

            boolean net = hasLocalNetwork();
            drawWifiIcon(c, ox + 125*s, oy + 700*s, 38*s, net ? Color.rgb(22, 220, 178) : Color.rgb(116, 132, 151));
            drawText(c, net ? "Rede local detectada" : "Rede local indisponível", ox + 190*s, oy + 708*s, 25*s, true, Color.WHITE);
            drawText(c, net ? "Conectado à sua rede local" : "Conecte Wi‑Fi ou Ethernet", ox + 190*s, oy + 746*s, 20*s, false, Color.rgb(139, 163, 189));

            drawGearIcon(c, ox + 625*s, oy + 700*s, 35*s, Color.rgb(20, 156, 244));
            drawText(c, "Modo receptor ativo", ox + 690*s, oy + 708*s, 25*s, true, Color.WHITE);
            drawText(c, "Pronto para receber conteúdo", ox + 690*s, oy + 746*s, 20*s, false, Color.rgb(139, 163, 189));

            drawPhoneIcon(c, ox + 1125*s, oy + 700*s, 30*s, Color.rgb(20, 156, 244));
            drawText(c, "Abra o Wudroid no celular", ox + 1190*s, oy + 708*s, 24*s, true, Color.WHITE);
            drawText(c, "e toque no ícone Cast", ox + 1190*s, oy + 746*s, 20*s, false, Color.rgb(139, 163, 189));
        }

        private void drawCard(Canvas c, RectF r, int fill, int stroke, float radius, float strokeWidth) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(fill); c.drawRoundRect(r, radius, radius, paint);
            strokePaint.setColor(stroke); strokePaint.setStrokeWidth(strokeWidth); c.drawRoundRect(r, radius, radius, strokePaint);
        }

        private void drawIcon(Canvas c, float x, float y, float width, float height) {
            if (icon == null) return;
            RectF dst = new RectF(x, y, x + width, y + height);
            c.drawBitmap(icon, null, dst, paint);
        }

        private void drawText(Canvas c, String text, float x, float y, float size, boolean bold, int color) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size);
            paint.setTypeface(bold ? android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
                    : android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            c.drawText(text, x, y, paint);
        }

        private void drawTextRight(Canvas c, String text, float x, float y, float size, boolean bold, int color) {
            paint.setTextAlign(Paint.Align.RIGHT); drawText(c, text, x, y, size, bold, color); paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawCenteredText(Canvas c, String text, float cx, float y, float size, boolean bold, int color) {
            paint.setTextAlign(Paint.Align.CENTER); drawText(c, text, cx, y, size, bold, color); paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawCenteredMultiline(Canvas c, String text, float cx, float y, float size, int color, float lineHeight) {
            String[] lines = text.split("\\n");
            for (int i = 0; i < lines.length; i++) drawCenteredText(c, lines[i], cx, y + i*lineHeight, size, false, color);
        }

        private void drawWifiIcon(Canvas c, float cx, float cy, float r, int color) {
            strokePaint.setColor(color); strokePaint.setStrokeWidth(8f * (getWidth()/1600f)); strokePaint.setStrokeCap(Paint.Cap.ROUND);
            RectF a = new RectF(cx-r, cy-r, cx+r, cy+r);
            c.drawArc(a, 220, 100, false, strokePaint);
            RectF b = new RectF(cx-r*0.62f, cy-r*0.62f, cx+r*0.62f, cy+r*0.62f);
            c.drawArc(b, 220, 100, false, strokePaint);
            paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy+r*0.45f, r*0.13f, paint);
            strokePaint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawGearIcon(Canvas c, float cx, float cy, float r, int color) {
            paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, r*0.72f, paint);
            paint.setColor(Color.rgb(7,24,38)); c.drawCircle(cx, cy, r*0.28f, paint);
            strokePaint.setColor(color); strokePaint.setStrokeWidth(r*0.18f);
            for (int i=0;i<8;i++) {
                double a = Math.PI*2*i/8.0;
                float x1 = cx + (float)Math.cos(a)*r*0.72f;
                float y1 = cy + (float)Math.sin(a)*r*0.72f;
                float x2 = cx + (float)Math.cos(a)*r;
                float y2 = cy + (float)Math.sin(a)*r;
                c.drawLine(x1,y1,x2,y2,strokePaint);
            }
        }

        private void drawPhoneIcon(Canvas c, float cx, float cy, float r, int color) {
            strokePaint.setColor(color); strokePaint.setStrokeWidth(r*0.15f);
            RectF body = new RectF(cx-r*0.55f, cy-r, cx+r*0.55f, cy+r);
            c.drawRoundRect(body, r*0.15f, r*0.15f, strokePaint);
            paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy+r*0.72f, r*0.08f, paint);
        }
    }
}
