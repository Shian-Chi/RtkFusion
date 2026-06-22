package com.example.rtkgnss.ntrip;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies RTCM differential corrections to pseudoranges.
 *
 * RTCM 1001-1004 (GPS) and 1009-1012 (GLONASS) carry pseudorange corrections (PRC)
 * and range rate corrections (RRC) per satellite. This class stores the latest
 * corrections and applies them on demand.
 *
 * Corrected PR = Raw PR + PRC + RRC * (t - t0)
 */
public class CorrectionApplier implements RtcmParser.MessageListener {

    private static final String TAG = "CorrectionApplier";

    // Supported RTCM3 message types for pseudorange corrections
    private static final int MSG_GPS_L1 = 1001;
    private static final int MSG_GPS_L1_L2 = 1003;
    private static final int MSG_GLONASS_L1 = 1009;

    private static class SvCorrection {
        double prc;       // pseudorange correction (m)
        double rrc;       // range rate correction (m/s)
        long timestampMs; // when this correction was received
    }

    // key = svid
    private final Map<Integer, SvCorrection> corrections = new HashMap<>();
    private static final long CORRECTION_MAX_AGE_MS = 30_000;

    @Override
    public void onRtcmMessage(int messageType, byte[] payload, int length) {
        if (messageType == MSG_GPS_L1 || messageType == MSG_GPS_L1_L2) {
            parseGpsCorrections(payload, length);
        } else if (messageType == MSG_GLONASS_L1) {
            parseGlonassCorrections(payload, length);
        }
        // Other message types (ephemeris, SSR, etc.) are logged but not processed
        Log.d(TAG, "RTCM msg " + messageType + " received (" + length + " bytes)");
    }

    /**
     * Apply differential correction to a raw pseudorange for the given SV.
     * Returns the corrected pseudorange, or the original if no correction is available.
     */
    public double applyCorrection(int svid, double rawPseudorangeMeters) {
        SvCorrection corr = corrections.get(svid);
        if (corr == null) {
            Log.d(TAG, "No correction for SV" + svid);
            return rawPseudorangeMeters;
        }

        long ageMs = System.currentTimeMillis() - corr.timestampMs;
        if (ageMs > CORRECTION_MAX_AGE_MS) {
            Log.w(TAG, "Correction for SV" + svid + " expired (" + ageMs + "ms)");
            corrections.remove(svid);
            return rawPseudorangeMeters;
        }

        double ageSeconds = ageMs / 1000.0;
        double correction = corr.prc + corr.rrc * ageSeconds;
        double corrected = rawPseudorangeMeters + correction;

        Log.d(TAG, String.format("SV%d raw=%.3fm prc=%.3f rrc=%.4f age=%.1fs corrected=%.3fm",
                svid, rawPseudorangeMeters, corr.prc, corr.rrc, ageSeconds, corrected));

        return corrected;
    }

    public boolean hasCorrectionFor(int svid) {
        SvCorrection corr = corrections.get(svid);
        if (corr == null) return false;
        return (System.currentTimeMillis() - corr.timestampMs) < CORRECTION_MAX_AGE_MS;
    }

    private void parseGpsCorrections(byte[] payload, int length) {
        if (length < 8) return;
        try {
            BitReader bits = new BitReader(payload);
            bits.read(12); // message number
            bits.read(12); // reference station ID
            bits.read(23); // GPS epoch time
            bits.read(1);  // synchronous GNSS flag
            int numSvs = (int) bits.read(5);
            bits.read(1);  // smoothing indicator
            bits.read(3);  // smoothing interval

            for (int i = 0; i < numSvs && bits.hasMore(58); i++) {
                int svid = (int) bits.read(6);
                bits.read(1);  // L1 code indicator
                bits.read(10); // L1 pseudorange modulus ambiguity
                double prc = bits.readSigned(16) * 0.02;  // 0.02m resolution
                double rrc = bits.readSigned(8) * 0.002;  // 0.002m/s resolution
                bits.read(8);  // IODE
                bits.read(7);  // UDRE

                SvCorrection corr = new SvCorrection();
                corr.prc = prc;
                corr.rrc = rrc;
                corr.timestampMs = System.currentTimeMillis();
                corrections.put(svid, corr);

                Log.d(TAG, String.format("GPS SV%d PRC=%.2fm RRC=%.3fm/s", svid, prc, rrc));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse GPS corrections: " + e.getMessage());
        }
    }

    private void parseGlonassCorrections(byte[] payload, int length) {
        if (length < 8) return;
        try {
            BitReader bits = new BitReader(payload);
            bits.read(12); // message number
            bits.read(12); // reference station ID
            bits.read(27); // GLONASS epoch time
            bits.read(1);  // synchronous GNSS flag
            int numSvs = (int) bits.read(5);
            bits.read(1);  // smoothing indicator
            bits.read(3);  // smoothing interval

            for (int i = 0; i < numSvs && bits.hasMore(64); i++) {
                int svid = (int) bits.read(6);
                bits.read(1);  // L1 code indicator
                bits.read(5);  // frequency channel
                bits.read(10); // L1 pseudorange modulus ambiguity
                double prc = bits.readSigned(16) * 0.02;
                double rrc = bits.readSigned(8) * 0.002;
                bits.read(8);  // IOD
                bits.read(7);  // UDRE

                // GLONASS svid offset to avoid collision with GPS
                SvCorrection corr = new SvCorrection();
                corr.prc = prc;
                corr.rrc = rrc;
                corr.timestampMs = System.currentTimeMillis();
                corrections.put(svid + 100, corr);

                Log.d(TAG, String.format("GLONASS SV%d PRC=%.2fm RRC=%.3fm/s", svid, prc, rrc));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse GLONASS corrections: " + e.getMessage());
        }
    }

    private static class BitReader {
        private final byte[] data;
        private int bitPos;

        BitReader(byte[] data) {
            this.data = data;
            this.bitPos = 0;
        }

        long read(int bits) {
            long value = 0;
            for (int i = 0; i < bits; i++) {
                int byteIdx = bitPos / 8;
                int bitIdx = 7 - (bitPos % 8);
                if (byteIdx < data.length) {
                    value = (value << 1) | ((data[byteIdx] >> bitIdx) & 1);
                }
                bitPos++;
            }
            return value;
        }

        long readSigned(int bits) {
            long value = read(bits);
            if (bits > 0 && (value & (1L << (bits - 1))) != 0) {
                value -= (1L << bits);
            }
            return value;
        }

        boolean hasMore(int bits) {
            return (bitPos + bits) <= data.length * 8;
        }
    }
}
