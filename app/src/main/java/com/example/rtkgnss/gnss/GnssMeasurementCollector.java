package com.example.rtkgnss.gnss;

import android.content.Context;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GnssMeasurementCollector {

    private static final String TAG = "GnssMeasCollector";

    // L1: 1575.42 MHz, L5: 1176.45 MHz
    private static final double L1_FREQ_HZ = 1575.42e6;
    private static final double L5_FREQ_HZ = 1176.45e6;
    private static final double FREQ_TOLERANCE_HZ = 10e6;

    public interface Listener {
        void onMeasurementsReceived(List<SatelliteMeasurement> measurements);
        void onGnssStatusChanged(GnssStatus status);
        void onLocationUpdated(Location location);
    }

    private final LocationManager locationManager;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GnssStatus lastGnssStatus;
    private boolean isCollecting = false;

    private final GnssMeasurementsEvent.Callback measurementCallback = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            processMeasurements(event);
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            lastGnssStatus = status;
            for (Listener l : listeners) {
                l.onGnssStatusChanged(status);
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            for (Listener l : listeners) {
                l.onLocationUpdated(location);
            }
        }
    };

    public GnssMeasurementCollector(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @SuppressWarnings("MissingPermission")
    public void start() {
        if (isCollecting) return;
        isCollecting = true;

        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
        locationManager.registerGnssMeasurementsCallback(measurementCallback, mainHandler);
        locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);

        Log.i(TAG, "GNSS measurement collection started");
    }

    public void stop() {
        if (!isCollecting) return;
        isCollecting = false;

        locationManager.removeUpdates(locationListener);
        locationManager.unregisterGnssMeasurementsCallback(measurementCallback);
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback);

        Log.i(TAG, "GNSS measurement collection stopped");
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public GnssStatus getLastGnssStatus() {
        return lastGnssStatus;
    }

    private void processMeasurements(GnssMeasurementsEvent event) {
        List<SatelliteMeasurement> measurements = new ArrayList<>();

        for (GnssMeasurement m : event.getMeasurements()) {
            if (!isUsable(m)) continue;

            SatelliteMeasurement sat = new SatelliteMeasurement();
            sat.svid = m.getSvid();
            sat.constellationType = m.getConstellationType();
            sat.carrierFreqHz = m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : L1_FREQ_HZ;
            sat.isL1 = isL1(sat.carrierFreqHz);
            sat.isL5 = isL5(sat.carrierFreqHz);
            sat.pseudorangeMeters = computePseudorange(m);
            sat.carrierPhaseRadians = m.getAccumulatedDeltaRangeMeters();
            sat.cnrDbHz = m.getCn0DbHz();
            sat.elevationDeg = getElevationFromStatus(sat.svid, sat.constellationType);
            sat.timeNanos = m.getTimeOffsetNanos();
            sat.multipath = (m.getMultipathIndicator() == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED);

            logMeasurement(sat);
            measurements.add(sat);
        }

        if (!measurements.isEmpty()) {
            for (Listener l : listeners) {
                l.onMeasurementsReceived(measurements);
            }
        }
    }

    private boolean isUsable(GnssMeasurement m) {
        int state = m.getState();
        // Need at least pseudorange decoded
        return (state & GnssMeasurement.STATE_CODE_LOCK) != 0
                && (state & GnssMeasurement.STATE_TOW_DECODED) != 0;
    }

    private boolean isL1(double freqHz) {
        return Math.abs(freqHz - L1_FREQ_HZ) < FREQ_TOLERANCE_HZ;
    }

    private boolean isL5(double freqHz) {
        return Math.abs(freqHz - L5_FREQ_HZ) < FREQ_TOLERANCE_HZ;
    }

    private double computePseudorange(GnssMeasurement m) {
        // pseudorange = (receivedSvTimeNs - timeOffsetNs) * c / 1e9
        double receivedSvTimeSeconds = m.getReceivedSvTimeNanos() * 1e-9;
        double timeOffsetSeconds = m.getTimeOffsetNanos() * 1e-9;
        double tRx = receivedSvTimeSeconds - timeOffsetSeconds;
        // tRx is in GPS week seconds; wrap to handle week rollover
        tRx = tRx % (7 * 24 * 3600);
        double tTx = m.getReceivedSvTimeNanos() * 1e-9;
        tTx = tTx % (7 * 24 * 3600);
        double deltaT = tRx - tTx;
        return deltaT * 299792458.0;
    }

    private float getElevationFromStatus(int svid, int constellationType) {
        if (lastGnssStatus == null) return 0f;
        for (int i = 0; i < lastGnssStatus.getSatelliteCount(); i++) {
            if (lastGnssStatus.getSvid(i) == svid
                    && lastGnssStatus.getConstellationType(i) == constellationType) {
                return lastGnssStatus.getElevationDegrees(i);
            }
        }
        return 0f;
    }

    private void logMeasurement(SatelliteMeasurement sat) {
        Log.d(TAG, String.format(
                "SV=%d Const=%d Freq=%.2fMHz L1=%b L5=%b PR=%.2fm CNR=%.1fdBHz El=%.1f°",
                sat.svid, sat.constellationType,
                sat.carrierFreqHz / 1e6,
                sat.isL1, sat.isL5,
                sat.pseudorangeMeters,
                sat.cnrDbHz,
                sat.elevationDeg));
    }

    public static class SatelliteMeasurement {
        public int svid;
        public int constellationType;
        public double carrierFreqHz;
        public boolean isL1;
        public boolean isL5;
        public double pseudorangeMeters;
        public double carrierPhaseRadians;
        public double cnrDbHz;
        public float elevationDeg;
        public double timeNanos;
        public boolean multipath;
    }
}
