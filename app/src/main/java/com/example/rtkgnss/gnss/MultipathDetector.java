package com.example.rtkgnss.gnss;

import android.util.Log;

/**
 * Computes a combined weight for a satellite measurement based on:
 * - CNR (carrier-to-noise ratio): low CNR → low weight
 * - Elevation angle: low elevation → multipath susceptibility → low weight
 */
public class MultipathDetector {

    private static final String TAG = "MultipathDetector";

    private static final double CNR_THRESHOLD_DB = 30.0;
    private static final double CNR_GOOD_DB = 45.0;
    private static final double ELEV_THRESHOLD_DEG = 15.0;
    private static final double ELEV_GOOD_DEG = 45.0;

    public static class WeightedMeasurement {
        public double pseudorangeMeters;
        public double weight;
        public boolean excluded;
        public String reason;
    }

    /**
     * Returns a weight in [0, 1] for the given measurement.
     * Measurements below minimum CNR or elevation are excluded (weight = 0).
     */
    public WeightedMeasurement evaluate(double pseudorangeMeters, double cnrDbHz,
                                         float elevationDeg, boolean hasMultipathFlag) {
        WeightedMeasurement result = new WeightedMeasurement();
        result.pseudorangeMeters = pseudorangeMeters;

        if (hasMultipathFlag) {
            result.excluded = true;
            result.weight = 0.0;
            result.reason = "multipath flag";
            Log.d(TAG, "Excluded: multipath indicator set");
            return result;
        }

        if (cnrDbHz < CNR_THRESHOLD_DB) {
            result.excluded = true;
            result.weight = 0.0;
            result.reason = String.format("CNR=%.1f < %.0fdBHz", cnrDbHz, CNR_THRESHOLD_DB);
            Log.d(TAG, "Excluded: " + result.reason);
            return result;
        }

        if (elevationDeg < ELEV_THRESHOLD_DEG) {
            result.excluded = true;
            result.weight = 0.0;
            result.reason = String.format("El=%.1f° < %.0f°", elevationDeg, ELEV_THRESHOLD_DEG);
            Log.d(TAG, "Excluded: " + result.reason);
            return result;
        }

        double cnrWeight = computeCnrWeight(cnrDbHz);
        double elevWeight = computeElevationWeight(elevationDeg);
        result.weight = cnrWeight * elevWeight;
        result.excluded = false;
        result.reason = "ok";

        Log.d(TAG, String.format("PR=%.2fm CNR=%.1f El=%.1f° w_cnr=%.3f w_el=%.3f w=%.3f",
                pseudorangeMeters, cnrDbHz, elevationDeg, cnrWeight, elevWeight, result.weight));

        return result;
    }

    private double computeCnrWeight(double cnrDbHz) {
        if (cnrDbHz >= CNR_GOOD_DB) return 1.0;
        double ratio = (cnrDbHz - CNR_THRESHOLD_DB) / (CNR_GOOD_DB - CNR_THRESHOLD_DB);
        return Math.max(0.0, ratio * ratio);
    }

    private double computeElevationWeight(double elevDeg) {
        // sin²(elevation) weighting is standard in GNSS
        double sinEl = Math.sin(Math.toRadians(elevDeg));
        return sinEl * sinEl;
    }
}
