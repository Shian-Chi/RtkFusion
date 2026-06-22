package com.example.rtkgnss.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.rtkgnss.R;
import com.example.rtkgnss.data.LogExporter;
import com.example.rtkgnss.data.PositionRepository;
import com.example.rtkgnss.fusion.ExtendedKalmanFilter;
import com.example.rtkgnss.fusion.ImuCollector;
import com.example.rtkgnss.gnss.DualFrequencyCorrector;
import com.example.rtkgnss.gnss.EphemerisProcessor;
import com.example.rtkgnss.gnss.GnssMeasurementCollector;
import com.example.rtkgnss.gnss.HatchFilter;
import com.example.rtkgnss.gnss.MultipathDetector;
import com.example.rtkgnss.ntrip.CorrectionApplier;
import com.example.rtkgnss.ntrip.NtripClient;
import com.example.rtkgnss.ntrip.RtcmParser;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MapActivity extends AppCompatActivity
        implements GnssMeasurementCollector.Listener, ImuCollector.ImuListener {

    private static final String TAG = "MapActivity";
    private static final int PERM_REQUEST = 100;

    // Pipeline components
    private GnssMeasurementCollector measurementCollector;
    private DualFrequencyCorrector dualFreqCorrector;
    private HatchFilter hatchFilter;
    private MultipathDetector multipathDetector;
    private CorrectionApplier correctionApplier;
    private EphemerisProcessor ephemerisProcessor;

    // Fusion
    private ImuCollector imuCollector;
    private ExtendedKalmanFilter ekf;

    // Data
    private PositionRepository positionRepository;
    private LogExporter logExporter;

    // NTRIP
    private NtripClient ntripService;
    private boolean ntripBound = false;
    private final RtcmParser rtcmParser = new RtcmParser();

    // UI
    private MapView mapView;
    private Marker positionMarker;
    private Polyline trackPolyline;
    private final List<GeoPoint> trackPoints = new ArrayList<>();
    private StatusFragment statusFragment;
    private SkyViewFragment skyViewFragment;

    private Location lastRawLocation;

    private final ServiceConnection ntripConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ntripBound = true;
            ntripService = ((NtripClient.LocalBinder) service).getService();
            ntripService.addRtcmListener((data, length) -> rtcmParser.feed(data, length));
            applyNtripSettings();
            Log.i(TAG, "NTRIP service bound");
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            ntripBound = false;
            ntripService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_map);

        initPipeline();
        initUi();
        checkPermissionsAndStart();
    }

    private void initPipeline() {
        dualFreqCorrector = new DualFrequencyCorrector();
        hatchFilter = new HatchFilter();
        multipathDetector = new MultipathDetector();
        correctionApplier = new CorrectionApplier();
        ephemerisProcessor = new EphemerisProcessor();
        ekf = new ExtendedKalmanFilter();
        positionRepository = new PositionRepository();
        logExporter = new LogExporter(this);

        // Wire RTCM listeners: corrections and ephemeris both parse RTCM stream
        rtcmParser.addListener(correctionApplier);
        rtcmParser.addListener(ephemerisProcessor);
    }

    private void initUi() {
        mapView = findViewById(R.id.map_view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(17.0);

        positionMarker = new Marker(mapView);
        positionMarker.setTitle("Current Position");
        mapView.getOverlays().add(positionMarker);

        trackPolyline = new Polyline();
        trackPolyline.setWidth(4f);
        mapView.getOverlays().add(trackPolyline);

        // Status panel (bottom)
        statusFragment = new StatusFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.status_container, statusFragment)
                .commit();
    }

    private void checkPermissionsAndStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERM_REQUEST);
        } else {
            startCollection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCollection();
        } else {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startCollection() {
        measurementCollector = new GnssMeasurementCollector(this);
        measurementCollector.addListener(this);
        measurementCollector.start();

        imuCollector = new ImuCollector(this);
        imuCollector.addListener(this);
        imuCollector.start();

        Intent ntripIntent = new Intent(this, NtripClient.class);
        startForegroundService(ntripIntent);
        bindService(ntripIntent, ntripConnection, BIND_AUTO_CREATE);

        try {
            logExporter.startNewSession();
        } catch (IOException e) {
            Log.e(TAG, "Log start failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GnssMeasurementCollector.Listener
    // -------------------------------------------------------------------------

    @Override
    public void onMeasurementsReceived(List<GnssMeasurementCollector.SatelliteMeasurement> measurements) {
        Map<Integer, DualFrequencyCorrector.CorrectedMeasurement> corrected =
                dualFreqCorrector.correct(measurements);

        boolean anyDualFreq = false;
        boolean anyRtcm = false;
        double tow = measurementCollector.getCurrentTow();

        for (DualFrequencyCorrector.CorrectedMeasurement cm : corrected.values()) {
            if (cm.isDualFreq) anyDualFreq = true;

            // Carrier-phase smoothing (Hatch filter)
            HatchFilter.SmoothedPseudorange smoothed = hatchFilter.update(
                    cm.svid, cm.constellationType,
                    cm.pseudorangeMeters,
                    getCarrierPhaseForSv(measurements, cm.svid, cm.constellationType));
            if (!smoothed.isReady) continue;

            // Multipath / elevation weighting
            MultipathDetector.WeightedMeasurement wm = multipathDetector.evaluate(
                    smoothed.smoothedPseudorangeMeters, cm.cnrDbHz,
                    cm.elevationDeg, cm.multipath);
            if (wm.excluded) continue;

            // RTCM differential correction
            double finalPR = correctionApplier.applyCorrection(cm.svid, wm.pseudorangeMeters);
            if (correctionApplier.hasCorrectionFor(cm.svid)) anyRtcm = true;

            // EKF update using ephemeris-derived SV position
            if (ekf.isInitialized()) {
                double[] svEcef = ephemerisProcessor.getSvEcef(cm.svid, tow);
                if (svEcef != null) {
                    // Apply SV clock correction to pseudorange
                    double correctedPR = finalPR - svEcef[3];
                    ekf.updateWithPseudorange(svEcef[0], svEcef[1], svEcef[2],
                            correctedPR, wm.weight);
                }
            }
        }

        publishPosition(anyDualFreq, anyRtcm, corrected.size());
    }

    @Override
    public void onGnssStatusChanged(GnssStatus status) {
        if (statusFragment != null) statusFragment.updateSatelliteStatus(status);
        if (skyViewFragment != null) skyViewFragment.updateStatus(status);
    }

    @Override
    public void onLocationUpdated(Location location) {
        lastRawLocation = location;

        if (!ekf.isInitialized()) {
            double[] ecef = wgs84ToEcef(location.getLatitude(),
                    location.getLongitude(), location.getAltitude());
            ekf.initialize(ecef[0], ecef[1], ecef[2], System.nanoTime());
        }

        if (ntripBound && ntripService != null) {
            ntripService.updateLocation(location);
        }
    }

    // -------------------------------------------------------------------------
    // ImuCollector.ImuListener
    // -------------------------------------------------------------------------

    @Override
    public void onImuSample(ImuCollector.ImuSample sample) {
        ekf.predict(sample);
    }

    // -------------------------------------------------------------------------
    // Position publishing
    // -------------------------------------------------------------------------

    private void publishPosition(boolean dualFreq, boolean rtcm, int satCount) {
        if (lastRawLocation == null) return;

        double[] correctedLlh;
        double uncertainty;

        if (ekf.isInitialized()) {
            ExtendedKalmanFilter.EkfResult result = ekf.getResult();
            correctedLlh = ecefToWgs84(result.x, result.y, result.z);
            uncertainty = result.positionUncertaintyM;
        } else {
            correctedLlh = new double[]{
                    lastRawLocation.getLatitude(),
                    lastRawLocation.getLongitude(),
                    lastRawLocation.getAltitude()
            };
            uncertainty = lastRawLocation.getAccuracy();
        }

        PositionRepository.PositionEntry entry = new PositionRepository.PositionEntry(
                System.currentTimeMillis(),
                lastRawLocation.getLatitude(), lastRawLocation.getLongitude(),
                lastRawLocation.getAltitude(),
                correctedLlh[0], correctedLlh[1], correctedLlh[2],
                uncertainty, satCount, dualFreq, rtcm);

        positionRepository.addEntry(entry);
        logExporter.appendEntry(entry);

        runOnUiThread(() -> {
            GeoPoint gp = new GeoPoint(correctedLlh[0], correctedLlh[1]);
            positionMarker.setPosition(gp);
            mapView.getController().setCenter(gp);

            trackPoints.add(gp);
            trackPolyline.setPoints(new ArrayList<>(trackPoints));
            mapView.invalidate();

            if (statusFragment != null) statusFragment.updatePosition(entry);
        });
    }

    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_skyview) {
            toggleSkyView();
            return true;
        }
        if (id == R.id.action_ntrip_connect) {
            toggleNtrip(item);
            return true;
        }
        if (id == R.id.action_export) {
            exportLog();
            return true;
        }
        if (id == R.id.action_clear) {
            trackPoints.clear();
            trackPolyline.setPoints(trackPoints);
            positionRepository.clear();
            mapView.invalidate();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleSkyView() {
        Fragment existing = getSupportFragmentManager().findFragmentByTag("skyview");
        if (existing != null) {
            getSupportFragmentManager().beginTransaction().remove(existing).commit();
            skyViewFragment = null;
        } else {
            skyViewFragment = new SkyViewFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.skyview_container, skyViewFragment, "skyview")
                    .commit();
        }
    }

    private boolean ntripConnected = false;

    private void toggleNtrip(MenuItem item) {
        if (!ntripBound || ntripService == null) return;
        if (ntripConnected) {
            ntripService.disconnect();
            ntripConnected = false;
            item.setTitle("Connect NTRIP");
        } else {
            applyNtripSettings();
            ntripService.connect();
            ntripConnected = true;
            item.setTitle("Disconnect NTRIP");
        }
    }

    private void applyNtripSettings() {
        if (ntripService == null) return;
        SharedPreferences prefs = getSharedPreferences(
                SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String host = prefs.getString(SettingsActivity.KEY_HOST, "");
        int port = prefs.getInt(SettingsActivity.KEY_PORT, 2101);
        String mount = prefs.getString(SettingsActivity.KEY_MOUNT, "");
        String user = prefs.getString(SettingsActivity.KEY_USER, "");
        String pass = prefs.getString(SettingsActivity.KEY_PASS, "");
        if (!host.isEmpty() && !mount.isEmpty()) {
            ntripService.configure(host, port, mount, user, pass);
        }
    }

    private void exportLog() {
        try {
            logExporter.exportAll(positionRepository.getHistory());
            Toast.makeText(this, "Log exported to Documents/RtkFusion/", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        // Re-apply settings if they changed
        if (ntripBound) applyNtripSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (measurementCollector != null) measurementCollector.stop();
        if (imuCollector != null) imuCollector.stop();
        if (ntripBound) {
            ntripService.disconnect();
            unbindService(ntripConnection);
            ntripBound = false;
        }
        logExporter.closeSession();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double getCarrierPhaseForSv(
            List<GnssMeasurementCollector.SatelliteMeasurement> measurements,
            int svid, int constellation) {
        for (GnssMeasurementCollector.SatelliteMeasurement m : measurements) {
            if (m.svid == svid && m.constellationType == constellation && m.isL1) {
                return m.carrierPhaseMeters;
            }
        }
        return 0;
    }

    private double[] wgs84ToEcef(double latDeg, double lonDeg, double altM) {
        final double a = 6378137.0, e2 = 0.00669437999014;
        double lat = Math.toRadians(latDeg), lon = Math.toRadians(lonDeg);
        double N = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        return new double[]{
                (N + altM) * Math.cos(lat) * Math.cos(lon),
                (N + altM) * Math.cos(lat) * Math.sin(lon),
                (N * (1 - e2) + altM) * Math.sin(lat)
        };
    }

    private double[] ecefToWgs84(double x, double y, double z) {
        final double a = 6378137.0, e2 = 0.00669437999014;
        double lon = Math.atan2(y, x);
        double p = Math.sqrt(x * x + y * y);
        double lat = Math.atan2(z, p * (1 - e2));
        for (int i = 0; i < 10; i++) {
            double sinLat = Math.sin(lat);
            double N = a / Math.sqrt(1 - e2 * sinLat * sinLat);
            lat = Math.atan2(z + e2 * N * sinLat, p);
        }
        double N = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        double alt = p / Math.cos(lat) - N;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }
}
