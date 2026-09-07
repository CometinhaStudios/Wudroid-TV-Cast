package com.cometinhastudios.wudroid.tvcast;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private TextView statusView;
    private long lastFrameUiMs;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        enterImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView video = new SurfaceView(this);
        video.setBackgroundColor(Color.BLACK);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(18f);
        statusView.setBackgroundColor(0xAA071018);
        statusView.setPadding(24, 16, 24, 16);
        statusView.setText("Wudroid TV Cast\nPronto para receber");
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        statusParams.leftMargin = 28;
        statusParams.topMargin = 28;
        root.addView(statusView, statusParams);

        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                synchronized (decoderLock) {
                    outputSurface = holder.getSurface();
                    releaseDecoderLocked();
                    ensureDecoderLocked(cachedWidth, cachedHeight);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                synchronized (decoderLock) {
                    outputSurface = holder.getSurface();
                    if (decoder == null) {
                        ensureDecoderLocked(cachedWidth, cachedHeight);
                    }
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                synchronized (decoderLock) {
                    outputSurface = null;
                    releaseDecoderLocked();
                }
            }
        });

        setContentView(root);
        startTransport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        if (!running.get()) startTransport();
    }

    @Override
    protected void onDestroy() {
        stopTransport();
        synchronized (decoderLock) {
            outputSurface = null;
            releaseDecoderLocked();
        }
        super.onDestroy();
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
    }

    private void startTransport() {
        if (!running.compareAndSet(false, true)) return;
        setStatus("Wudroid TV Cast\nPronto para receber");

        try {
            videoSocket = new DatagramSocket(null);
            videoSocket.setReuseAddress(true);
            videoSocket.setReceiveBufferSize(1024 * 1024);
            videoSocket.bind(new InetSocketAddress(VIDEO_PORT));
        } catch (Throwable t) {
            setStatus("Wudroid TV Cast\nFalha na porta de vídeo");
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
            setStatus("Wudroid TV Cast\nFalha na descoberta");
            stopTransport();
            return;
        }

        discoveryThread = new Thread(this::discoveryLoop, "Wudroid-TV-Cast-Discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        videoThread = new Thread(this::videoLoop, "Wudroid-TV-Cast-Video");
        videoThread.setDaemon(true);
        videoThread.start();
    }

    private void stopTransport() {
        running.set(false);
        if (discoverySocket != null) {
            try { discoverySocket.close(); } catch (Throwable ignored) {}
        }
        if (videoSocket != null) {
            try { videoSocket.close(); } catch (Throwable ignored) {}
        }
        discoverySocket = null;
        videoSocket = null;
        discoveryThread = null;
        videoThread = null;
    }

    private void discoveryLoop() {
        byte[] buffer = new byte[1024];
        DatagramSocket socket = discoverySocket;
        if (socket == null) return;

        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                String[] parts = text.split("\\|", 3);
                if (parts.length < 2 || !DISCOVER.equals(parts[0])) continue;

                String nonce = parts[1].replace("|", "");
                if (nonce.length() > 32) nonce = nonce.substring(0, 32);
                String name = ("Wudroid TV Cast - " + Build.MODEL).replace("|", " ");
                String replyText = HERE + "|" + nonce + "|" + name + "|" + VIDEO_PORT;
                byte[] reply = replyText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        byte[] packetBuffer = new byte[1400];

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
                if (chunkCount < 1 || chunkCount > MAX_CHUNKS || chunkIndex < 0 || chunkIndex >= chunkCount) continue;

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
                        while (it.hasNext()) {
                            if (it.next() <= latestDeliveredUnitId) it.remove();
                        }
                        handleAccessUnit(complete, assembly.flags, assembly.ptsUs, assembly.width, assembly.height);
                    }
                }

                while (assemblies.size() > 3) {
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
                    queueDecoderDataLocked(bytes, ptsUs, true);
                    decoderConfigQueued = true;
                }
                setStatus("Wudroid TV Cast\nConectado • " + width + "×" + height);
                return;
            }

            if (outputSurface == null || !ensureDecoderLocked(width, height)) return;
            if (!decoderConfigQueued) {
                if (cachedConfig == null) {
                    setStatus("Wudroid TV Cast\nEsperando sinal de vídeo…");
                    return;
                }
                queueDecoderDataLocked(cachedConfig, ptsUs, true);
                decoderConfigQueued = true;
            }

            if (queueDecoderDataLocked(bytes, ptsUs, false)) {
                long now = System.currentTimeMillis();
                if (now - lastFrameUiMs > 1000) {
                    lastFrameUiMs = now;
                    hideStatusSoon();
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
            try { format.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 30) {
                try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); } catch (Throwable ignored) {}
            }
            try { format.setFloat(MediaFormat.KEY_OPERATING_RATE, 60f); } catch (Throwable ignored) {}

            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();
            try {
                decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } catch (Throwable ignored) {}
            decoderWidth = width;
            decoderHeight = height;
            decoderConfigQueued = false;
            return true;
        } catch (Throwable t) {
            releaseDecoderLocked();
            setStatus("Wudroid TV Cast\nFalha no decoder H.264");
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
            int index = codec.dequeueInputBuffer(0L);
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
        // Drop older decoded frames if the TV fell behind; render only the newest.
        for (int i = 0; i < ready.size() - 1; i++) {
            try { codec.releaseOutputBuffer(ready.get(i), false); } catch (Throwable ignored) {}
        }
        try { codec.releaseOutputBuffer(ready.get(ready.size() - 1), true); } catch (Throwable ignored) {}
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            if (statusView != null) {
                statusView.setVisibility(View.VISIBLE);
                statusView.setText(text);
            }
        });
    }

    private void hideStatusSoon() {
        runOnUiThread(() -> {
            if (statusView != null) {
                statusView.setText("Wudroid TV Cast\nRecebendo jogo");
                statusView.setVisibility(View.VISIBLE);
                statusView.animate().cancel();
                statusView.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction(() -> {
                    if (statusView != null) {
                        statusView.setVisibility(View.GONE);
                        statusView.setAlpha(1f);
                    }
                }).start();
            }
        });
    }
}
