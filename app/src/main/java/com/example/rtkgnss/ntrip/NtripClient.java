package com.example.rtkgnss.ntrip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.rtkgnss.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class NtripClient extends Service {

    private static final String TAG = "NtripClient";
    private static final String CHANNEL_ID = "ntrip_channel";
    private static final int NOTIF_ID = 1;

    private static final int RECONNECT_DELAY_MS = 5000;

    public interface RtcmListener {
        void onRtcmData(byte[] data, int length);
    }

    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<RtcmListener> rtcmListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    // Connection parameters — set via configure() before start
    private String host = "";
    private int port = 2101;
    private String mountpoint = "";
    private String username = "";
    private String password = "";
    private Location lastLocation;

    public class LocalBinder extends Binder {
        public NtripClient getService() {
            return NtripClient.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Connecting..."));
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    public void configure(String host, int port, String mountpoint,
                          String username, String password) {
        this.host = host;
        this.port = port;
        this.mountpoint = mountpoint;
        this.username = username;
        this.password = password;
    }

    public void updateLocation(Location location) {
        this.lastLocation = location;
    }

    public void connect() {
        if (running.getAndSet(true)) return;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(this::connectionLoop);
    }

    public void disconnect() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void addRtcmListener(RtcmListener listener) {
        rtcmListeners.add(listener);
    }

    public void removeRtcmListener(RtcmListener listener) {
        rtcmListeners.remove(listener);
    }

    private void connectionLoop() {
        while (running.get()) {
            try {
                connectOnce();
            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            }
            if (running.get()) {
                Log.i(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms");
                updateNotification("Reconnecting...");
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Log.i(TAG, "Connection loop ended");
    }

    private void connectOnce() throws IOException {
        Log.i(TAG, "Connecting to " + host + ":" + port + "/" + mountpoint);
        updateNotification("Connecting to " + host);

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(15000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            PrintWriter writer = new PrintWriter(out, true);

            // Send NTRIP HTTP GET request
            sendNtripRequest(writer);

            // Read server response
            BufferedReader headerReader = new BufferedReader(new InputStreamReader(in));
            String statusLine = headerReader.readLine();
            if (statusLine == null || !statusLine.contains("200")) {
                Log.e(TAG, "Server rejected: " + statusLine);
                return;
            }
            Log.i(TAG, "NTRIP server accepted: " + statusLine);

            // Skip remaining headers
            String line;
            while ((line = headerReader.readLine()) != null && !line.isEmpty()) {
                Log.d(TAG, "Header: " + line);
            }

            // Send GGA if we have a location
            if (lastLocation != null) {
                String gga = buildNmeaGga(lastLocation);
                out.write((gga + "\r\n").getBytes());
                out.flush();
                Log.i(TAG, "Sent GGA: " + gga);
            }

            updateNotification("Receiving RTCM data");
            socket.setSoTimeout(30000);

            // Read RTCM binary stream
            byte[] buffer = new byte[4096];
            int bytesRead;
            while (running.get() && (bytesRead = in.read(buffer)) != -1) {
                Log.d(TAG, "RTCM bytes received: " + bytesRead);
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                for (RtcmListener listener : rtcmListeners) {
                    listener.onRtcmData(chunk, bytesRead);
                }
            }
        }
    }

    private void sendNtripRequest(PrintWriter writer) {
        String credentials = Base64.encodeToString(
                (username + ":" + password).getBytes(), Base64.NO_WRAP);

        writer.print("GET /" + mountpoint + " HTTP/1.1\r\n");
        writer.print("Host: " + host + "\r\n");
        writer.print("Ntrip-Version: Ntrip/2.0\r\n");
        writer.print("User-Agent: NTRIP AndroidRTK/1.0\r\n");
        writer.print("Authorization: Basic " + credentials + "\r\n");
        writer.print("Connection: close\r\n");
        writer.print("\r\n");
        writer.flush();
    }

    /**
     * Builds a minimal NMEA GGA sentence with current location.
     * NTRIP casters use this to determine which base station data to send.
     */
    private String buildNmeaGga(Location loc) {
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        double alt = loc.getAltitude();

        String latDir = lat >= 0 ? "N" : "S";
        String lonDir = lon >= 0 ? "E" : "W";
        lat = Math.abs(lat);
        lon = Math.abs(lon);

        int latDeg = (int) lat;
        double latMin = (lat - latDeg) * 60;
        int lonDeg = (int) lon;
        double lonMin = (lon - lonDeg) * 60;

        String body = String.format("GPGGA,000000.00,%02d%08.5f,%s,%03d%08.5f,%s,1,08,1.0,%.1f,M,0.0,M,,",
                latDeg, latMin, latDir, lonDeg, lonMin, lonDir, alt);

        int checksum = 0;
        for (char c : body.toCharArray()) checksum ^= c;
        return "$" + body + "*" + String.format("%02X", checksum);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "NTRIP Service", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("NTRIP correction data receiver");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RTK GNSS")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_satellite)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification n = buildNotification(text);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }
}
