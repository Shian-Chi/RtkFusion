# Android RTK-Grade GNSS App

## 專案概述
純手機端高精度定位 App，透過演算法降低 GNSS 誤差。
測試機：Samsung Galaxy S20（支援 L1+L5 雙頻）

## 技術規格
- 語言：Java
- minSdkVersion：26（Android 8.0）
- targetSdkVersion：34
- 建置：Gradle (Groovy DSL)
- 第三方限制：GNSS 演算法全部自己實作，不用外部 GNSS library

## 依賴套件
- org.apache.commons:commons-math3（EKF 矩陣運算）
- com.google.android.gms:play-services-maps（地圖顯示）
- osmdroid（離線地圖備選）

## 專案結構
app/src/main/java/com/example/rtkgnss/
├── gnss/
│   ├── GnssMeasurementCollector.java   # GnssMeasurementsEvent.Callback
│   ├── DualFrequencyCorrector.java     # L1/L5 電離層線性組合消除
│   ├── HatchFilter.java                # 載波相位平滑偽距（每顆衛星獨立）
│   └── MultipathDetector.java          # CNR 加權 + 仰角加權
├── ntrip/
│   ├── NtripClient.java                # TCP Socket，Foreground Service 內執行
│   ├── RtcmParser.java                 # RTCM3 byte stream 解析
│   └── CorrectionApplier.java          # 差分修正套用至偽距
├── fusion/
│   ├── ImuCollector.java               # SensorManager，加速度計+陀螺儀
│   └── ExtendedKalmanFilter.java       # 狀態向量：位置+速度+鐘差
├── ui/
│   ├── MapActivity.java                # 主畫面，位置顯示
│   └── StatusFragment.java             # 衛星數、SNR、修正前後精度對比
└── data/
    ├── PositionRepository.java
    └── LogExporter.java                # 輸出原始測量值供離線分析

## 權限（AndroidManifest.xml）
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION  
- FOREGROUND_SERVICE
- INTERNET

## 開發順序（嚴格照此 Phase 順序）

### Phase 1：GNSS 原始資料收集
- GnssStatus.Callback → 衛星數、仰角、方位角
- GnssMeasurementsEvent.Callback → 原始測量值
- 確認 L5 頻率：getCarrierFrequencyHz() ≈ 1176MHz
- 確認 L1 頻率：getCarrierFrequencyHz() ≈ 1575MHz
- Log 輸出所有原始值，UI 顯示衛星天空圖

### Phase 2：NTRIP Client
- Foreground Service 內跑 TCP Socket
- 連線流程：HTTP GET → 送 NMEA GGA → 接收 RTCM3 byte stream
- NTRIP Server：[使用者自行填入]
- Log 確認 RTCM byte 有進來即可，先不套用

### Phase 3：演算法實作
- DualFrequencyCorrector：L1/L5 線性組合消電離層
- HatchFilter：等 20 epoch 穩定後才輸出，每顆衛星獨立狀態
- CorrectionApplier：將 RTCM 修正套用至偽距
- MultipathDetector：CNR < 30dB-Hz 降權，仰角 < 15° 降權

### Phase 4：EKF 融合
- ImuCollector：TYPE_ACCELEROMETER + TYPE_GYROSCOPE
- EKF 狀態向量：[x, y, z, vx, vy, vz, clock_bias]
- 預測步：IMU 積分（dt 從感測器 timestamp 計算）
- 更新步：GNSS 偽距殘差
- 協方差矩陣每步做正定性檢查

### Phase 5：UI 與驗證
- MapActivity：即時位置軌跡
- StatusFragment：修正前 vs 修正後精度數字對比
- LogExporter：輸出 CSV 供 RTKLIB 離線驗證

## 已知坑
1. L5 訊號不穩定，實作時要 fallback 回純 L1
2. Hatch Filter 初始化前 20 epoch 不輸出修正值
3. NTRIP GGA 格式錯誤會導致 Caster 回傳錯誤基站資料
4. Android 背景定位需 Foreground Service，否則被系統殺掉
5. EKF 協方差矩陣發散時重置狀態，不要讓 app crash

## 驗證方式
- 靜態測試：固定點放置 10 分鐘，看位置收斂情況
- 對比基準：同地點用外接 F9P 或已知座標比較
- 精度目標：靜態 < 30cm，動態 < 1m
