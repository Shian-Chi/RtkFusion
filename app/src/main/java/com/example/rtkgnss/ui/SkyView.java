package com.example.rtkgnss.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.GnssStatus;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom View that renders a satellite sky plot.
 * North is up. Elevation 90° is at centre; 0° is at the edge.
 */
public class SkyView extends View {

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satUsedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satUnusedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satL5Paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private GnssStatus gnssStatus;

    public SkyView(Context context) { this(context, null); }
    public SkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setColor(Color.LTGRAY);
        circlePaint.setStrokeWidth(1.5f);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.DKGRAY);
        gridPaint.setStrokeWidth(1f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(22f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        satUsedPaint.setStyle(Paint.Style.FILL);
        satUsedPaint.setColor(Color.GREEN);

        satUnusedPaint.setStyle(Paint.Style.FILL);
        satUnusedPaint.setColor(Color.GRAY);

        satL5Paint.setStyle(Paint.Style.FILL);
        satL5Paint.setColor(Color.CYAN);
    }

    public void setGnssStatus(GnssStatus status) {
        gnssStatus = status;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);

        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(cx, cy) - 20f;

        drawGrid(canvas, cx, cy, radius);

        if (gnssStatus == null) return;

        Paint svLabel = new Paint(textPaint);
        svLabel.setTextSize(18f);
        svLabel.setColor(Color.YELLOW);

        for (int i = 0; i < gnssStatus.getSatelliteCount(); i++) {
            float el = gnssStatus.getElevationDegrees(i);
            float az = gnssStatus.getAzimuthDegrees(i);
            boolean used = gnssStatus.usedInFix(i);
            int svid = gnssStatus.getSvid(i);

            // Map to canvas: elevation 90° → centre, 0° → edge
            float r = radius * (1f - el / 90f);
            float azRad = (float) Math.toRadians(az);
            float x = cx + r * (float) Math.sin(azRad);
            float y = cy - r * (float) Math.cos(azRad);

            Paint p = used ? satUsedPaint : satUnusedPaint;
            canvas.drawCircle(x, y, 14f, p);
            canvas.drawText(String.valueOf(svid), x, y + 6f, svLabel);
        }
    }

    private void drawGrid(Canvas canvas, float cx, float cy, float radius) {
        // Elevation rings at 30°, 60°, 90° (horizon, mid, zenith)
        for (int el : new int[]{0, 30, 60}) {
            float r = radius * (1f - el / 90f);
            canvas.drawCircle(cx, cy, r, circlePaint);
        }
        // Cardinal direction lines
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint);

        Paint label = new Paint(textPaint);
        label.setTextSize(24f);
        label.setColor(Color.LTGRAY);
        canvas.drawText("N", cx, cy - radius - 4f, label);
        canvas.drawText("S", cx, cy + radius + 26f, label);
        label.setTextSize(20f);
        canvas.drawText("E", cx + radius + 12f, cy + 8f, label);
        canvas.drawText("W", cx - radius - 12f, cy + 8f, label);

        // Elevation labels
        Paint elLabel = new Paint(label);
        elLabel.setTextSize(18f);
        elLabel.setColor(Color.DKGRAY);
        canvas.drawText("30°", cx + 4f, cy - radius * (1f - 30f / 90f) - 4f, elLabel);
        canvas.drawText("60°", cx + 4f, cy - radius * (1f - 60f / 90f) - 4f, elLabel);
    }
}
