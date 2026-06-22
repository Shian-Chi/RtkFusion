package com.example.rtkgnss.gnss;

import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L1/L5 ionosphere-free linear combination to eliminate first-order ionospheric delay.
 * Formula: PR_IF = (f1² * PR_L1 - f5² * PR_L5) / (f1² - f5²)
 */
public class DualFrequencyCorrector {

    private static final String TAG = "DualFreqCorrector";

    private static final double F1 = 1575.42e6; // L1 Hz
    private static final double F5 = 1176.45e6; // L5 Hz
    private static final double F1_SQ = F1 * F1;
    private static final double F5_SQ = F5 * F5;
    private static final double DENOM = F1_SQ - F5_SQ;

    // alpha/beta coefficients for ionosphere-free combination
    private static final double ALPHA = F1_SQ / DENOM;
    private static final double BETA = F5_SQ / DENOM;

    // key = svid + constellationType * 1000
    private final Map<Integer, Double> l1Cache = new HashMap<>();
    private final Map<Integer, Double> l5Cache = new HashMap<>();

    public static class CorrectedMeasurement {
        public int svid;
        public int constellationType;
        public double pseudorangeMeters;
        public boolean isDualFreq;
        public double cnrDbHz;
        public float elevationDeg;
        public boolean multipath;
    }

    /**
     * Process a batch of measurements. For SVs with both L1 and L5,
     * returns the ionosphere-free combination. Falls back to L1 only.
     */
    public Map<Integer, CorrectedMeasurement> correct(
            List<GnssMeasurementCollector.SatelliteMeasurement> measurements) {

        l1Cache.clear();
        l5Cache.clear();

        Map<Integer, GnssMeasurementCollector.SatelliteMeasurement> l1Map = new HashMap<>();
        Map<Integer, GnssMeasurementCollector.SatelliteMeasurement> l5Map = new HashMap<>();

        for (GnssMeasurementCollector.SatelliteMeasurement m : measurements) {
            int key = svKey(m.svid, m.constellationType);
            if (m.isL5) {
                l5Map.put(key, m);
            } else if (m.isL1) {
                l1Map.put(key, m);
            }
        }

        Map<Integer, CorrectedMeasurement> result = new HashMap<>();

        // Process L1-only SVs first
        for (Map.Entry<Integer, GnssMeasurementCollector.SatelliteMeasurement> e : l1Map.entrySet()) {
            int key = e.getKey();
            GnssMeasurementCollector.SatelliteMeasurement l1 = e.getValue();
            CorrectedMeasurement cm = new CorrectedMeasurement();
            cm.svid = l1.svid;
            cm.constellationType = l1.constellationType;
            cm.cnrDbHz = l1.cnrDbHz;
            cm.elevationDeg = l1.elevationDeg;
            cm.multipath = l1.multipath;

            if (l5Map.containsKey(key)) {
                GnssMeasurementCollector.SatelliteMeasurement l5 = l5Map.get(key);
                cm.pseudorangeMeters = ionoFreeCombination(l1.pseudorangeMeters, l5.pseudorangeMeters);
                cm.isDualFreq = true;
                Log.d(TAG, String.format("SV%d dual-freq IF=%.3fm (L1=%.3f L5=%.3f)",
                        l1.svid, cm.pseudorangeMeters, l1.pseudorangeMeters, l5.pseudorangeMeters));
            } else {
                // L5 not available for this SV — use L1 only
                cm.pseudorangeMeters = l1.pseudorangeMeters;
                cm.isDualFreq = false;
                Log.d(TAG, String.format("SV%d L1-only PR=%.3fm", l1.svid, cm.pseudorangeMeters));
            }

            result.put(key, cm);
        }

        return result;
    }

    private double ionoFreeCombination(double prL1, double prL5) {
        return ALPHA * prL1 - BETA * prL5;
    }

    private int svKey(int svid, int constellation) {
        return constellation * 1000 + svid;
    }
}
