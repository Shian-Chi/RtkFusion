package com.example.rtkgnss.gnss;

import android.util.Log;

import com.example.rtkgnss.ntrip.RtcmParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses RTCM 1019 (GPS ephemeris) messages and provides SV ECEF positions.
 * Falls back to null if ephemeris is not yet received for a given SV.
 */
public class EphemerisProcessor implements RtcmParser.MessageListener {

    private static final String TAG = "EphemerisProcessor";
    private static final int RTCM_GPS_EPH = 1019;
    private static final long EPH_MAX_AGE_MS = 2 * 3600 * 1000L; // 2 hours

    // svid → latest ephemeris
    private final Map<Integer, GpsEphemeris> ephemerisMap = new HashMap<>();

    @Override
    public void onRtcmMessage(int messageType, byte[] payload, int length) {
        if (messageType == RTCM_GPS_EPH) {
            parseGpsEphemeris(payload, length);
        }
    }

    /**
     * Compute SV ECEF position for given svid at GPS time-of-week.
     * Returns null if no ephemeris available or SV is unhealthy.
     */
    public double[] getSvEcef(int svid, double tow) {
        GpsEphemeris eph = ephemerisMap.get(svid);
        if (eph == null) return null;
        if (!eph.isHealthy()) return null;
        long age = System.currentTimeMillis() - eph.receivedTimeMs;
        if (age > EPH_MAX_AGE_MS) {
            Log.w(TAG, "Ephemeris for SV" + svid + " too old (" + age / 1000 + "s)");
            return null;
        }
        return eph.computePositionEcef(tow);
    }

    public int getEphemerisCount() {
        return ephemerisMap.size();
    }

    /**
     * Parse RTCM 1019 GPS ephemeris message.
     * Bit layout per RTCM 10403.3 Table 3.5-25.
     */
    private void parseGpsEphemeris(byte[] payload, int length) {
        if (length < 60) return;
        try {
            BitReader b = new BitReader(payload);
            b.skip(12); // message number

            GpsEphemeris eph = new GpsEphemeris();
            eph.svid        = (int) b.readU(6);
            eph.weekNumber  = (int) b.readU(10);
            b.skip(4);  // SV accuracy
            b.skip(2);  // GPS code on L2
            eph.iDot        = b.readS(14) * Math.pow(2, -43) * Math.PI;
            b.skip(8);  // IODE
            eph.toc         = b.readU(16) * 16.0;
            eph.af2         = b.readS(8)  * Math.pow(2, -55);
            eph.af1         = b.readS(16) * Math.pow(2, -43);
            eph.af0         = b.readS(22) * Math.pow(2, -31);
            b.skip(10); // IODC
            eph.cRs         = b.readS(16) * Math.pow(2, -5);
            eph.deltaN      = b.readS(16) * Math.pow(2, -43) * Math.PI;
            eph.m0          = b.readS(32) * Math.pow(2, -31) * Math.PI;
            eph.cUc         = b.readS(16) * Math.pow(2, -29);
            eph.e           = b.readU(32) * Math.pow(2, -33);
            eph.cUs         = b.readS(16) * Math.pow(2, -29);
            eph.sqrtA       = b.readU(32) * Math.pow(2, -19);
            eph.toe         = b.readU(16) * 16.0;
            eph.cIc         = b.readS(16) * Math.pow(2, -29);
            eph.omega0      = b.readS(32) * Math.pow(2, -31) * Math.PI;
            eph.cIs         = b.readS(16) * Math.pow(2, -29);
            eph.i0          = b.readS(32) * Math.pow(2, -31) * Math.PI;
            eph.cRc         = b.readS(16) * Math.pow(2, -5);
            eph.omega       = b.readS(32) * Math.pow(2, -31) * Math.PI;
            eph.omegaDot    = b.readS(24) * Math.pow(2, -43) * Math.PI;
            eph.tgd         = b.readS(8)  * Math.pow(2, -31);
            eph.svHealth    = (int) b.readU(6);
            b.skip(1);  // L2 P flag
            b.skip(1);  // fit interval flag

            eph.receivedTimeMs = System.currentTimeMillis();
            ephemerisMap.put(eph.svid, eph);

            Log.i(TAG, String.format("GPS Eph SV%d week=%d toe=%.0f e=%.6f sqrtA=%.3f",
                    eph.svid, eph.weekNumber, eph.toe, eph.e, eph.sqrtA));
        } catch (Exception ex) {
            Log.e(TAG, "Parse error GPS eph: " + ex.getMessage());
        }
    }

    private static class BitReader {
        private final byte[] data;
        private int pos;

        BitReader(byte[] d) { data = d; pos = 0; }

        void skip(int bits) { pos += bits; }

        long readU(int bits) {
            long v = 0;
            for (int i = 0; i < bits; i++) {
                int by = pos / 8, bi = 7 - (pos % 8);
                v = (v << 1) | (by < data.length ? ((data[by] >> bi) & 1) : 0);
                pos++;
            }
            return v;
        }

        double readS(int bits) {
            long v = readU(bits);
            if (bits > 0 && (v & (1L << (bits - 1))) != 0) v -= (1L << bits);
            return v;
        }
    }
}
