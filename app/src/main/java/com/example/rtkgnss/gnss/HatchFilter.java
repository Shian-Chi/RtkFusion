package com.example.rtkgnss.gnss;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Carrier-phase smoothed pseudorange (Hatch filter) per satellite.
 * Output is withheld until 20 epochs of stable carrier phase accumulation.
 *
 * Smoothed PR: P_s(k) = (1/k)*P(k) + ((k-1)/k)*(P_s(k-1) + delta_phi(k))
 * where delta_phi is carrier phase change in metres between epochs.
 */
public class HatchFilter {

    private static final String TAG = "HatchFilter";
    private static final int WARM_UP_EPOCHS = 20;
    private static final double CYCLE_SLIP_THRESHOLD_M = 10.0;

    private final Map<Integer, SatState> states = new HashMap<>();

    public static class SmoothedPseudorange {
        public int svid;
        public int constellationType;
        public double smoothedPseudorangeMeters;
        public int epochCount;
        public boolean isReady;
    }

    /**
     * Feed one epoch of (pseudorange, carrier phase metres) for a given SV.
     * Returns null if not yet warmed up.
     */
    public SmoothedPseudorange update(int svid, int constellationType,
                                       double pseudorangeMeters, double carrierPhaseMeters) {
        int key = svKey(svid, constellationType);
        SatState state = states.get(key);

        if (state == null) {
            state = new SatState();
            state.smoothedPR = pseudorangeMeters;
            state.lastCarrierPhase = carrierPhaseMeters;
            state.epochCount = 1;
            states.put(key, state);
        } else {
            double deltaPhi = carrierPhaseMeters - state.lastCarrierPhase;

            // Cycle slip detection: if carrier phase jumps too much, reset
            if (Math.abs(deltaPhi) > CYCLE_SLIP_THRESHOLD_M) {
                Log.w(TAG, String.format("SV%d cycle slip detected (delta=%.2fm), resetting", svid, deltaPhi));
                state.smoothedPR = pseudorangeMeters;
                state.lastCarrierPhase = carrierPhaseMeters;
                state.epochCount = 1;
            } else {
                state.epochCount++;
                int k = state.epochCount;
                double predicted = state.smoothedPR + deltaPhi;
                state.smoothedPR = (pseudorangeMeters + (k - 1) * predicted) / k;
                state.lastCarrierPhase = carrierPhaseMeters;
            }
        }

        SmoothedPseudorange result = new SmoothedPseudorange();
        result.svid = svid;
        result.constellationType = constellationType;
        result.smoothedPseudorangeMeters = state.smoothedPR;
        result.epochCount = state.epochCount;
        result.isReady = (state.epochCount >= WARM_UP_EPOCHS);

        if (result.isReady) {
            Log.d(TAG, String.format("SV%d Hatch smooth=%.3fm (epoch=%d)",
                    svid, result.smoothedPseudorangeMeters, result.epochCount));
        }

        return result;
    }

    public void resetSatellite(int svid, int constellationType) {
        states.remove(svKey(svid, constellationType));
    }

    public void resetAll() {
        states.clear();
    }

    private int svKey(int svid, int constellation) {
        return constellation * 1000 + svid;
    }

    private static class SatState {
        double smoothedPR;
        double lastCarrierPhase;
        int epochCount;
    }
}
