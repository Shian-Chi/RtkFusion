package com.example.rtkgnss.data;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Exports position log to CSV for offline analysis with RTKLIB or similar tools.
 */
public class LogExporter {

    private static final String TAG = "LogExporter";
    private static final String CSV_HEADER =
            "timestamp_ms,lat_raw,lon_raw,alt_raw_m,lat_corrected,lon_corrected,alt_corrected_m," +
            "uncertainty_m,satellite_count,dual_freq,has_rtcm";

    private final Context context;
    private PrintWriter activeWriter;
    private File activeFile;

    public LogExporter(Context context) {
        this.context = context;
    }

    public File startNewSession() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = getExportDir();
        activeFile = new File(dir, "gnss_log_" + ts + ".csv");
        activeWriter = new PrintWriter(new FileWriter(activeFile, true));
        activeWriter.println(CSV_HEADER);
        activeWriter.flush();
        Log.i(TAG, "Log session started: " + activeFile.getAbsolutePath());
        return activeFile;
    }

    public void appendEntry(PositionRepository.PositionEntry entry) {
        if (activeWriter == null) return;
        activeWriter.printf(Locale.US,
                "%d,%.8f,%.8f,%.3f,%.8f,%.8f,%.3f,%.3f,%d,%b,%b%n",
                entry.timestampMs,
                entry.latRaw, entry.lonRaw, entry.altRaw,
                entry.latCorrected, entry.lonCorrected, entry.altCorrected,
                entry.uncertaintyM, entry.satelliteCount,
                entry.hasDualFreq, entry.hasRtcm);
        activeWriter.flush();
    }

    public void exportAll(List<PositionRepository.PositionEntry> entries) throws IOException {
        File file = startNewSession();
        for (PositionRepository.PositionEntry e : entries) {
            appendEntry(e);
        }
        closeSession();
        Log.i(TAG, "Exported " + entries.size() + " entries to " + file.getAbsolutePath());
    }

    public void closeSession() {
        if (activeWriter != null) {
            activeWriter.close();
            activeWriter = null;
        }
    }

    private File getExportDir() {
        File dir;
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "RtkFusion");
        } else {
            dir = new File(context.getFilesDir(), "logs");
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
