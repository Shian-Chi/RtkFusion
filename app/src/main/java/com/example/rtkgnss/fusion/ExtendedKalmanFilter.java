package com.example.rtkgnss.fusion;

import android.util.Log;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

/**
 * Extended Kalman Filter for GNSS/IMU tight coupling.
 *
 * State vector (7): [x, y, z, vx, vy, vz, clock_bias]
 *   x, y, z        : ECEF position (m)
 *   vx, vy, vz     : ECEF velocity (m/s)
 *   clock_bias      : receiver clock bias (m, i.e. dt * c)
 *
 * Predict step: IMU integration with accelerometer + gyro
 * Update step:  GNSS pseudorange residuals per satellite
 */
public class ExtendedKalmanFilter {

    private static final String TAG = "EKF";
    private static final int STATE_DIM = 7;

    // Process noise (tuning parameters)
    private static final double ACCEL_NOISE_M_S2 = 0.5;
    private static final double CLOCK_DRIFT_NOISE_M = 1.0;

    // Measurement noise
    private static final double PSEUDORANGE_NOISE_M = 5.0;

    private RealVector state;   // 7-element state vector
    private RealMatrix P;       // 7x7 covariance matrix

    private long lastTimestampNs = -1;
    private boolean initialized = false;

    public static class EkfResult {
        public double x, y, z;
        public double vx, vy, vz;
        public double clockBias;
        public double positionUncertaintyM;
    }

    public ExtendedKalmanFilter() {
        reset();
    }

    public void reset() {
        state = new ArrayRealVector(STATE_DIM);
        P = new Array2DRowRealMatrix(STATE_DIM, STATE_DIM);
        // Large initial uncertainty
        for (int i = 0; i < 3; i++) P.setEntry(i, i, 1e6);     // position
        for (int i = 3; i < 6; i++) P.setEntry(i, i, 1e4);     // velocity
        P.setEntry(6, 6, 1e8);                                   // clock bias
        initialized = false;
        lastTimestampNs = -1;
        Log.i(TAG, "EKF reset");
    }

    /**
     * Initialize state from a known position (e.g., first GPS fix).
     */
    public void initialize(double x, double y, double z, long timestampNs) {
        state.setEntry(0, x);
        state.setEntry(1, y);
        state.setEntry(2, z);
        lastTimestampNs = timestampNs;
        initialized = true;
        Log.i(TAG, String.format("EKF initialized at (%.1f, %.1f, %.1f)", x, y, z));
    }

    /**
     * Prediction step using IMU measurements.
     */
    public void predict(ImuCollector.ImuSample imu) {
        if (!initialized) return;

        if (lastTimestampNs < 0) {
            lastTimestampNs = imu.timestampNs;
            return;
        }

        double dt = (imu.timestampNs - lastTimestampNs) * 1e-9;
        lastTimestampNs = imu.timestampNs;

        if (dt <= 0 || dt > 1.0) return; // sanity check

        // State propagation: constant acceleration model
        // x(k+1) = F * x(k) + B * u
        double ax = imu.ax, ay = imu.ay, az = imu.az - 9.81; // remove gravity (simplified)

        double x  = state.getEntry(0);
        double y  = state.getEntry(1);
        double z  = state.getEntry(2);
        double vx = state.getEntry(3);
        double vy = state.getEntry(4);
        double vz = state.getEntry(5);
        double cb = state.getEntry(6);

        // Position update
        state.setEntry(0, x + vx * dt + 0.5 * ax * dt * dt);
        state.setEntry(1, y + vy * dt + 0.5 * ay * dt * dt);
        state.setEntry(2, z + vz * dt + 0.5 * az * dt * dt);
        // Velocity update
        state.setEntry(3, vx + ax * dt);
        state.setEntry(4, vy + ay * dt);
        state.setEntry(5, vz + az * dt);
        // Clock bias — assume constant (random walk modeled in Q)

        // State transition matrix F (Jacobian)
        RealMatrix F = buildF(dt);

        // Process noise matrix Q
        RealMatrix Q = buildQ(dt);

        // P = F * P * F' + Q
        P = F.multiply(P).multiply(F.transpose()).add(Q);

        enforcePSD();
    }

    /**
     * Update step with a single GNSS pseudorange observation.
     *
     * @param svEcefX  SV ECEF X (m) — computed from ephemeris/almanac
     * @param svEcefY  SV ECEF Y (m)
     * @param svEcefZ  SV ECEF Z (m)
     * @param measuredPseudorange  corrected pseudorange (m)
     * @param weight   measurement weight from MultipathDetector [0,1]
     */
    public void updateWithPseudorange(double svEcefX, double svEcefY, double svEcefZ,
                                       double measuredPseudorange, double weight) {
        if (!initialized || weight <= 0) return;

        double rx = state.getEntry(0);
        double ry = state.getEntry(1);
        double rz = state.getEntry(2);
        double cb = state.getEntry(6);

        double dx = rx - svEcefX;
        double dy = ry - svEcefY;
        double dz = rz - svEcefZ;
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (range < 1e-6) return;

        // Predicted pseudorange
        double predictedPR = range + cb;
        double residual = measuredPseudorange - predictedPR;

        // Measurement Jacobian H (1×7)
        RealMatrix H = new Array2DRowRealMatrix(1, STATE_DIM);
        H.setEntry(0, 0, dx / range);
        H.setEntry(0, 1, dy / range);
        H.setEntry(0, 2, dz / range);
        // velocity and clock components
        H.setEntry(0, 6, 1.0);

        // Measurement noise R (scaled by weight)
        double R = (PSEUDORANGE_NOISE_M * PSEUDORANGE_NOISE_M) / Math.max(weight, 0.01);

        // Innovation covariance S = H*P*H' + R
        RealMatrix HPH = H.multiply(P).multiply(H.transpose());
        double S = HPH.getEntry(0, 0) + R;

        if (S < 1e-10) return;

        // Kalman gain K = P*H'/S  (7×1)
        RealMatrix K = P.multiply(H.transpose()).scalarMultiply(1.0 / S);

        // State update
        state = state.add(K.getColumnVector(0).mapMultiply(residual));

        // Covariance update: P = (I - K*H) * P
        RealMatrix I = new Array2DRowRealMatrix(STATE_DIM, STATE_DIM);
        for (int i = 0; i < STATE_DIM; i++) I.setEntry(i, i, 1.0);
        P = I.subtract(K.multiply(H)).multiply(P);

        enforcePSD();

        Log.d(TAG, String.format("EKF update: residual=%.3fm S=%.3f", residual, S));
    }

    public EkfResult getResult() {
        EkfResult r = new EkfResult();
        r.x = state.getEntry(0);
        r.y = state.getEntry(1);
        r.z = state.getEntry(2);
        r.vx = state.getEntry(3);
        r.vy = state.getEntry(4);
        r.vz = state.getEntry(5);
        r.clockBias = state.getEntry(6);
        r.positionUncertaintyM = Math.sqrt(
                P.getEntry(0, 0) + P.getEntry(1, 1) + P.getEntry(2, 2));
        return r;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private RealMatrix buildF(double dt) {
        double[][] f = new double[STATE_DIM][STATE_DIM];
        for (int i = 0; i < STATE_DIM; i++) f[i][i] = 1.0;
        // position += velocity * dt
        f[0][3] = dt;
        f[1][4] = dt;
        f[2][5] = dt;
        return new Array2DRowRealMatrix(f);
    }

    private RealMatrix buildQ(double dt) {
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;
        double dt4 = dt2 * dt2;
        double sA2 = ACCEL_NOISE_M_S2 * ACCEL_NOISE_M_S2;
        double sC2 = CLOCK_DRIFT_NOISE_M * CLOCK_DRIFT_NOISE_M;

        double[][] q = new double[STATE_DIM][STATE_DIM];
        // Position-position
        for (int i = 0; i < 3; i++) q[i][i] = sA2 * dt4 / 4.0;
        // Velocity-velocity
        for (int i = 3; i < 6; i++) q[i][i] = sA2 * dt2;
        // Position-velocity cross terms
        for (int i = 0; i < 3; i++) {
            q[i][i + 3] = sA2 * dt3 / 2.0;
            q[i + 3][i] = sA2 * dt3 / 2.0;
        }
        // Clock bias
        q[6][6] = sC2 * dt2;
        return new Array2DRowRealMatrix(q);
    }

    /**
     * Enforce positive semi-definiteness by symmetrizing P and
     * resetting if it becomes singular or has negative diagonal entries.
     */
    private void enforcePSD() {
        // Symmetrize
        P = P.add(P.transpose()).scalarMultiply(0.5);

        // Check diagonal entries
        boolean needReset = false;
        for (int i = 0; i < STATE_DIM; i++) {
            if (P.getEntry(i, i) < 0) {
                needReset = true;
                break;
            }
        }

        if (needReset) {
            Log.w(TAG, "P covariance diverged — resetting");
            reset();
        }
    }
}
