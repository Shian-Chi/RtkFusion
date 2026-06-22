package com.example.rtkgnss.fusion;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

public class ImuCollector implements SensorEventListener {

    private static final String TAG = "ImuCollector";
    private static final int SAMPLING_PERIOD_US = SensorManager.SENSOR_DELAY_FASTEST;

    public interface ImuListener {
        void onImuSample(ImuSample sample);
    }

    public static class ImuSample {
        public long timestampNs;
        public float ax, ay, az;   // m/s² in device frame
        public float gx, gy, gz;   // rad/s in device frame
        public boolean hasAccel;
        public boolean hasGyro;
    }

    private final SensorManager sensorManager;
    private final CopyOnWriteArrayList<ImuListener> listeners = new CopyOnWriteArrayList<>();

    private float[] lastAccel = null;
    private long lastAccelTs = 0;
    private float[] lastGyro = null;
    private long lastGyroTs = 0;

    public ImuCollector(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accel != null) {
            sensorManager.registerListener(this, accel, SAMPLING_PERIOD_US);
            Log.i(TAG, "Accelerometer registered");
        } else {
            Log.e(TAG, "No accelerometer available");
        }
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SAMPLING_PERIOD_US);
            Log.i(TAG, "Gyroscope registered");
        } else {
            Log.e(TAG, "No gyroscope available");
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    public void addListener(ImuListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ImuListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lastAccel = event.values.clone();
            lastAccelTs = event.timestamp;
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            lastGyro = event.values.clone();
            lastGyroTs = event.timestamp;
        }

        // Emit a combined sample when both sensors have fresh data
        if (lastAccel != null && lastGyro != null) {
            ImuSample sample = new ImuSample();
            // Use the more recent timestamp
            sample.timestampNs = Math.max(lastAccelTs, lastGyroTs);
            sample.ax = lastAccel[0];
            sample.ay = lastAccel[1];
            sample.az = lastAccel[2];
            sample.gx = lastGyro[0];
            sample.gy = lastGyro[1];
            sample.gz = lastGyro[2];
            sample.hasAccel = true;
            sample.hasGyro = true;

            for (ImuListener listener : listeners) {
                listener.onImuSample(sample);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, sensor.getName() + " accuracy changed to " + accuracy);
    }
}
