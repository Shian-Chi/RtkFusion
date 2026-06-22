package com.example.rtkgnss.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
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
import androidx.fragment.app.FragmentTransaction;

import com.example.rtkgnss.R;
import com.example.rtkgnss.data.LogExporter;
import com.example.rtkgnss.data.PositionRepository;
import com.example.rtkgnss.fusion.ExtendedKalmanFilter;
import com.example.rtkgnss.fusion.ImuCollector;
import com.example.rtkgnss.gnss.DualFrequencyCorrector;
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

    // --- GNSS pipeline ---
    private GnssMeasurementCollector measurementCollector;
    private DualFrequencyCorrector dualFreqCorrector;
    private HatchFilter hatchFilter;
    private MultipathDetector multipathDetector;
    private CorrectionApplier correctionApplier;

    // --- Fusion ---
    private ImuCollector imuCollector;
    private ExtendedKalmanFilter ekf;

    // --- Data ---
    private PositionRepository positionRepository;
    private LogExporter logExporter;

    // --- NTRIP ---
    private NtripClient ntripService;
    private boolean ntripBound = false;
    private final RtcmParser rtcmParser = new RtcmParser();

    // --- UI ---
    private MapView mapView;
    private Marker positionMarker;
    private Polyline trackPolyline;
    private final List<GeoPoint> trackPoints = new ArrayList<>();
    private StatusFragment statusFragment;

    private Location lastRawLocation;

    private final ServiceConnection ntripConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ntripBound = true;
            ntripService = ((NtripClient.LocalBinder) service).getService();
            ntripService.addRtcmListener(rtcmParser::feed);
            Log.i(TAG, "NTRIP service bound");
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            ntripBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_map);

        initAlgorithms();
        initUi();
        checkPermissionsAndStart();
    }

    private void initAlgorithms() {
        dualFreqCorrector = new DualFrequencyCorrector();
        hatchFilter = new HatchFilter();
        multipathDetector = new MultipathDetector();
        correctionApplier = new CorrectionApplier();
        ekf = new ExtendedKalmanFilter();
        positionRepository = new PositionRepository();
        logExporter = new LogExporter(this);

        rtcmParser.addListener(correctionApplier);
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

        statusFragment = new StatusFragment();
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.status_container, statusFragment);
        ft.commit();
    }

    private void checkPermissionsAndStart() {
        String[] perms = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST);
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

        bindService(new Intent(this, NtripClient.class),
                ntripConnection, BIND_AUTO_CREATE);

        try {
            logExporter.startNewSession();
        } catch (IOException e) {
            Log.e(TAG, "Could not start log: " + e.getMessage());
        }
    }

    // --- GnssMeasurementCollector.Listener ---

    @Override
    public void onMeasurementsReceived(List<GnssMeasurementCollector.SatelliteMeasurement> measurements) {
        // 1. Dual-frequency ionosphere correction
        Map<Integer, DualFrequencyCorrector.CorrectedMeasurement> corrected =
                dualFreqCorrector.correct(measurements);

        boolean anyDualFreq = false;
        boolean anyRtcm = false;

        for (DualFrequencyCorrector.CorrectedMeasurement cm : corrected.values()) {
            if (cm.isDualFreq) anyDualFreq = true;

            // 2. Hatch filter (carrier-phase smoothing)
            HatchFilter.SmoothedPseudorange smoothed = hatchFilter.update(
                    cm.svid, cm.constellationType,
                    cm.pseudorangeMeters, cm.pseudorangeMeters);

            if (!smoothed.isReady) continue;

            // 3. Multipath detection / weighting
            MultipathDetector.WeightedMeasurement wm = multipathDetector.evaluate(
                    smoothed.smoothedPseudorangeMeters, cm.cnrDbHz,
                    cm.elevationDeg, cm.multipath);

            if (wm.excluded) continue;

            // 4. Apply NTRIP differential correction
            double finalPR = correctionApplier.applyCorrection(cm.svid, wm.pseudorangeMeters);
            if (correctionApplier.hasCorrectionFor(cm.svid)) anyRtcm = true;

            // 5. EKF update — SV position from last known location (simplified)
            //    In production, use proper ephemeris to compute SV ECEF position
            if (ekf.isInitialized() && lastRawLocation != null) {
                double[] svEcef = estimateSvEcef(cm.svid, cm.elevationDeg);
                ekf.updateWithPseudorange(svEcef[0], svEcef[1], svEcef[2], finalPR, wm.weight);
            }
        }

        updatePositionDisplay(anyDualFreq, anyRtcm, corrected.size());
    }

    @Override
    public void onGnssStatusChanged(GnssStatus status) {
        if (statusFragment != null) {
            statusFragment.updateSatelliteStatus(status);
        }
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

    // --- ImuCollector.ImuListener ---

    @Override
    public void onImuSample(ImuCollector.ImuSample sample) {
        ekf.predict(sample);
    }

    // --- UI update ---

    private void updatePositionDisplay(boolean dualFreq, boolean rtcm, int satCount) {
        ExtendedKalmanFilter.EkfResult result = ekf.getResult();
        if (!ekf.isInitialized() || lastRawLocation == null) return;

        double[] correctedLlh = ecefToWgs84(result.x, result.y, result.z);

        PositionRepository.PositionEntry entry = new PositionRepository.PositionEntry(
                System.currentTimeMillis(),
                lastRawLocation.getLatitude(), lastRawLocation.getLongitude(),
                lastRawLocation.getAltitude(),
                correctedLlh[0], correctedLlh[1], correctedLlh[2],
                result.positionUncertaintyM, satCount, dualFreq, rtcm);

        positionRepository.addEntry(entry);
        logExporter.appendEntry(entry);

        runOnUiThread(() -> {
            GeoPoint gp = new GeoPoint(correctedLlh[0], correctedLlh[1]);
            positionMarker.setPosition(gp);
            mapView.getController().animateTo(gp);

            trackPoints.add(gp);
            trackPolyline.setPoints(trackPoints);
            mapView.invalidate();

            if (statusFragment != null) {
                statusFragment.updatePosition(entry);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_export) {
            try {
                logExporter.exportAll(positionRepository.getHistory());
                Toast.makeText(this, "Log exported", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_clear) {
            trackPoints.clear();
            trackPolyline.setPoints(trackPoints);
            positionRepository.clear();
            mapView.invalidate();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
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
            unbindService(ntripConnection);
            ntripBound = false;
        }
        logExporter.closeSession();
    }

    // --- Coordinate conversions ---

    private double[] wgs84ToEcef(double latDeg, double lonDeg, double altM) {
        double a = 6378137.0;
        double e2 = 0.00669437999014;
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double N = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        double x = (N + altM) * Math.cos(lat) * Math.cos(lon);
        double y = (N + altM) * Math.cos(lat) * Math.sin(lon);
        double z = (N * (1 - e2) + altM) * Math.sin(lat);
        return new double[]{x, y, z};
    }

    private double[] ecefToWgs84(double x, double y, double z) {
        double a = 6378137.0;
        double e2 = 0.00669437999014;
        double lon = Math.atan2(y, x);
        double p = Math.sqrt(x * x + y * y);
        double lat = Math.atan2(z, p * (1 - e2));
        for (int i = 0; i < 10; i++) {
            double N = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
            lat = Math.atan2(z + e2 * N * Math.sin(lat), p);
        }
        double N = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        double alt = p / Math.cos(lat) - N;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }

    /**
     * Simplified SV ECEF position estimate from elevation angle.
     * In production, replace with ephemeris-based computation.
     */
    private double[] estimateSvEcef(int svid, float elevDeg) {
        if (lastRawLocation == null) return new double[]{0, 0, 0};
        double[] rxEcef = wgs84ToEcef(lastRawLocation.getLatitude(),
                lastRawLocation.getLongitude(), lastRawLocation.getAltitude());
        double dist = 20200000.0; // approximate GPS orbital radius above surface
        double elRad = Math.toRadians(Math.max(elevDeg, 5));
        double azRad = Math.toRadians(svid * 30.0 % 360); // placeholder azimuth
        // Local East-North-Up offset
        double de = dist * Math.cos(elRad) * Math.sin(azRad);
        double dn = dist * Math.cos(elRad) * Math.cos(azRad);
        double du = dist * Math.sin(elRad);
        double lat = Math.toRadians(lastRawLocation.getLatitude());
        double lon = Math.toRadians(lastRawLocation.getLongitude());
        // ENU to ECEF rotation
        double dx = -Math.sin(lon) * de - Math.sin(lat) * Math.cos(lon) * dn + Math.cos(lat) * Math.cos(lon) * du;
        double dy =  Math.cos(lon) * de - Math.sin(lat) * Math.sin(lon) * dn + Math.cos(lat) * Math.sin(lon) * du;
        double dz =  Math.cos(lat) * dn + Math.sin(lat) * du;
        return new double[]{rxEcef[0] + dx, rxEcef[1] + dy, rxEcef[2] + dz};
    }
}
