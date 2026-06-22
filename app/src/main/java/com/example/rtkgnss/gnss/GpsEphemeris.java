package com.example.rtkgnss.gnss;

/**
 * GPS satellite ephemeris (Keplerian elements) from RTCM 1019 message.
 * Used to compute SV ECEF position at any given GPS time.
 */
public class GpsEphemeris {
    public int svid;

    // Orbital elements
    public double sqrtA;          // Square root of semi-major axis (m^0.5)
    public double e;              // Eccentricity
    public double i0;             // Inclination angle at ref time (rad)
    public double omega0;         // Longitude of ascending node at weekly epoch (rad)
    public double omega;          // Argument of perigee (rad)
    public double m0;             // Mean anomaly at ref time (rad)

    // Correction terms
    public double deltaN;         // Mean motion difference (rad/s)
    public double omegaDot;       // Rate of right ascension (rad/s)
    public double iDot;           // Rate of inclination angle (rad/s)
    public double cUc, cUs;       // Argument of latitude corrections
    public double cRc, cRs;       // Orbit radius corrections
    public double cIc, cIs;       // Inclination corrections

    // Clock corrections
    public double af0, af1, af2;  // Clock polynomial coefficients
    public double tgd;            // Group delay (s)

    // Reference times
    public double toe;            // Reference time of ephemeris (GPS seconds in week)
    public double toc;            // Reference time of clock (GPS seconds in week)
    public int weekNumber;        // GPS week number

    // Health
    public int svHealth;          // 0 = healthy

    public long receivedTimeMs = System.currentTimeMillis();

    // WGS-84 constants
    static final double GM = 3.986004418e14;     // m³/s²
    static final double OMEGA_DOT_E = 7.2921151467e-5; // rad/s

    public boolean isHealthy() {
        return svHealth == 0;
    }

    /**
     * Compute SV ECEF position and clock correction at GPS time-of-week tow (seconds).
     * Returns double[4]: {X, Y, Z (metres), clockCorrectionMetres}
     */
    public double[] computePositionEcef(double tow) {
        double a = sqrtA * sqrtA;
        double n0 = Math.sqrt(GM / (a * a * a));
        double n = n0 + deltaN;

        double tk = tow - toe;
        // Handle week crossovers
        if (tk > 302400) tk -= 604800;
        if (tk < -302400) tk += 604800;

        double mk = m0 + n * tk;

        // Solve Kepler's equation iteratively
        double ek = mk;
        for (int i = 0; i < 12; i++) {
            ek = mk + e * Math.sin(ek);
        }

        double sinEk = Math.sin(ek);
        double cosEk = Math.cos(ek);
        double vk = Math.atan2(Math.sqrt(1 - e * e) * sinEk, cosEk - e);

        double phiK = vk + omega;
        double sin2phi = Math.sin(2 * phiK);
        double cos2phi = Math.cos(2 * phiK);

        double deltaU = cUs * sin2phi + cUc * cos2phi;
        double deltaR = cRs * sin2phi + cRc * cos2phi;
        double deltaI = cIs * sin2phi + cIc * cos2phi;

        double uk = phiK + deltaU;
        double rk = a * (1 - e * cosEk) + deltaR;
        double ik = i0 + deltaI + iDot * tk;

        double xkPrime = rk * Math.cos(uk);
        double ykPrime = rk * Math.sin(uk);

        double omegaK = omega0 + (omegaDot - OMEGA_DOT_E) * tk - OMEGA_DOT_E * toe;

        double cosOmega = Math.cos(omegaK);
        double sinOmega = Math.sin(omegaK);
        double cosIk = Math.cos(ik);
        double sinIk = Math.sin(ik);

        double x = xkPrime * cosOmega - ykPrime * cosIk * sinOmega;
        double y = xkPrime * sinOmega + ykPrime * cosIk * cosOmega;
        double z = ykPrime * sinIk;

        // Clock correction (in metres = seconds * c)
        double dtc = tow - toc;
        if (dtc > 302400) dtc -= 604800;
        if (dtc < -302400) dtc += 604800;
        double clockCorr = (af0 + af1 * dtc + af2 * dtc * dtc - tgd) * 299792458.0;

        return new double[]{x, y, z, clockCorr};
    }
}
