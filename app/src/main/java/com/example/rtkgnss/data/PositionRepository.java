package com.example.rtkgnss.data;

import android.location.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PositionRepository {

    public static class PositionEntry {
        public final long timestampMs;
        public final double latRaw;
        public final double lonRaw;
        public final double altRaw;
        public final double latCorrected;
        public final double lonCorrected;
        public final double altCorrected;
        public final double uncertaintyM;
        public final int satelliteCount;
        public final boolean hasDualFreq;
        public final boolean hasRtcm;

        public PositionEntry(long ts,
                             double latRaw, double lonRaw, double altRaw,
                             double latCorr, double lonCorr, double altCorr,
                             double uncertainty, int satCount,
                             boolean dualFreq, boolean rtcm) {
            this.timestampMs = ts;
            this.latRaw = latRaw;
            this.lonRaw = lonRaw;
            this.altRaw = altRaw;
            this.latCorrected = latCorr;
            this.lonCorrected = lonCorr;
            this.altCorrected = altCorr;
            this.uncertaintyM = uncertainty;
            this.satelliteCount = satCount;
            this.hasDualFreq = dualFreq;
            this.hasRtcm = rtcm;
        }
    }

    public interface Observer {
        void onPositionUpdated(PositionEntry entry);
    }

    private static final int MAX_HISTORY = 1000;

    private final List<PositionEntry> history = new ArrayList<>();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();

    private PositionEntry latestEntry;

    public synchronized void addEntry(PositionEntry entry) {
        history.add(entry);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        latestEntry = entry;
        for (Observer o : observers) {
            o.onPositionUpdated(entry);
        }
    }

    public synchronized PositionEntry getLatest() {
        return latestEntry;
    }

    public synchronized List<PositionEntry> getHistory() {
        return new ArrayList<>(history);
    }

    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public synchronized void clear() {
        history.clear();
        latestEntry = null;
    }
}
