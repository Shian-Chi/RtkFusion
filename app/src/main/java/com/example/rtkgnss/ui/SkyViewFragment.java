package com.example.rtkgnss.ui;

import android.location.GnssStatus;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.rtkgnss.R;

public class SkyViewFragment extends Fragment {

    private SkyView skyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_skyview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        skyView = view.findViewById(R.id.sky_view);
    }

    public void updateStatus(GnssStatus status) {
        if (skyView != null) skyView.setGnssStatus(status);
    }
}
