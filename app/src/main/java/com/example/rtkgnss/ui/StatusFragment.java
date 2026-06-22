package com.example.rtkgnss.ui;

import android.location.GnssStatus;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.rtkgnss.R;
import com.example.rtkgnss.data.PositionRepository;

import java.util.Locale;

public class StatusFragment extends Fragment {

    private TextView tvSatCount;
    private TextView tvSnrAvg;
    private TextView tvRawAccuracy;
    private TextView tvCorrectedAccuracy;
    private TextView tvMode;
    private TextView tvLatLon;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvSatCount = view.findViewById(R.id.tv_sat_count);
        tvSnrAvg = view.findViewById(R.id.tv_snr_avg);
        tvRawAccuracy = view.findViewById(R.id.tv_raw_accuracy);
        tvCorrectedAccuracy = view.findViewById(R.id.tv_corrected_accuracy);
        tvMode = view.findViewById(R.id.tv_mode);
        tvLatLon = view.findViewById(R.id.tv_latlon);
    }

    public void updateSatelliteStatus(GnssStatus status) {
        if (!isAdded() || getView() == null) return;

        int count = 0;
        float snrSum = 0;
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            if (status.usedInFix(i)) {
                count++;
                snrSum += status.getCn0DbHz(i);
            }
        }
        float avgSnr = count > 0 ? snrSum / count : 0;
        int total = status.getSatelliteCount();

        tvSatCount.setText(String.format(Locale.US, "Sats: %d/%d", count, total));
        tvSnrAvg.setText(String.format(Locale.US, "Avg SNR: %.1f dB-Hz", avgSnr));
    }

    public void updatePosition(PositionRepository.PositionEntry entry) {
        if (!isAdded() || getView() == null) return;

        // Estimate raw accuracy from Android (coarse ~5-10m CEP)
        double rawAccuracy = 5.0;

        tvRawAccuracy.setText(String.format(Locale.US, "Raw: ~%.1fm", rawAccuracy));
        tvCorrectedAccuracy.setText(String.format(Locale.US,
                "EKF: ±%.2fm", entry.uncertaintyM));

        String modes = "";
        if (entry.hasDualFreq) modes += "L1+L5 ";
        if (entry.hasRtcm) modes += "RTCM";
        tvMode.setText(modes.isEmpty() ? "L1 only" : modes.trim());

        tvLatLon.setText(String.format(Locale.US,
                "%.6f, %.6f\nAlt: %.1fm",
                entry.latCorrected, entry.lonCorrected, entry.altCorrected));
    }
}
