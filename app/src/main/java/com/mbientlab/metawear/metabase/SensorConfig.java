/*
 * Copyright 2014-2018 MbientLab Inc. All rights reserved.
 *
 * IMPORTANT: Your use of this Software is limited to those specific rights
 * granted under the terms of a software license agreement between the user who
 * downloaded the software, his/her employer (which must be your employer) and
 * MbientLab Inc, (the "License").  You may not use this Software unless you
 * agree to abide by the terms of the License which can be found at
 * www.mbientlab.com/terms . The License limits your use, and you acknowledge,
 * that the  Software may not be modified, copied or distributed and can be used
 * solely and exclusively in conjunction with a MbientLab Inc, product.  Other
 * than for the foregoing purpose, you may not use, reproduce, copy, prepare
 * derivative works of, modify, distribute, perform, display or sell this
 * Software and/or its documentation for any purpose.
 *
 * YOU FURTHER ACKNOWLEDGE AND AGREE THAT THE SOFTWARE AND DOCUMENTATION ARE
 * PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESS OR IMPLIED,
 * INCLUDING WITHOUT LIMITATION, ANY WARRANTY OF MERCHANTABILITY, TITLE,
 * NON-INFRINGEMENT AND FITNESS FOR A PARTICULAR PURPOSE. IN NO EVENT SHALL
 * MBIENTLAB OR ITS LICENSORS BE LIABLE OR OBLIGATED UNDER CONTRACT, NEGLIGENCE,
 * STRICT LIABILITY, CONTRIBUTION, BREACH OF WARRANTY, OR OTHER LEGAL EQUITABLE
 * THEORY ANY DIRECT OR INDIRECT DAMAGES OR EXPENSES INCLUDING BUT NOT LIMITED
 * TO ANY INCIDENTAL, SPECIAL, INDIRECT, PUNITIVE OR CONSEQUENTIAL DAMAGES, LOST
 * PROFITS OR LOST DATA, COST OF PROCUREMENT OF SUBSTITUTE GOODS, TECHNOLOGY,
 * SERVICES, OR ANY CLAIMS BY THIRD PARTIES (INCLUDING BUT NOT LIMITED TO ANY
 * DEFENSE THEREOF), OR OTHER SIMILAR COSTS.
 *
 * Should you have any questions regarding your right to use this Software,
 * contact MbientLab Inc, at www.mbientlab.com.
 */

package com.mbientlab.metawear.metabase;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.mbientlab.metawear.AsyncDataProducer;
import com.mbientlab.metawear.DataProducer;
import com.mbientlab.metawear.ForcedDataProducer;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.module.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import bolts.Task;

abstract class SensorConfig {
    static final String KEY_IMU = "imu", KEY_FUSED = "fused_imu";
    static final Map<String, String> DISJOINT_KEY;

    static {
        DISJOINT_KEY = new HashMap<>();
        DISJOINT_KEY.put(KEY_IMU, KEY_FUSED);
        DISJOINT_KEY.put(KEY_FUSED, KEY_IMU);
    }

    static SensorConfig[] All() {
        return new SensorConfig[] {
                new AccelerometerConfig(),
                new AmbientLightConfig(),
                new EulerAnglesConfig(),
                new GravityConfig(),
                new GyroConfig(),
                new HumidityConfig(),
                new LinearAccelerationConfig(),
                new MagnetometerConfig(),
                new PressureConfig(),
                new QuaternionConfig(),
                new TemperatureConfig(),
        };
    }

    final String key, identifier;
    final int imageResId, nameResId;

    boolean isStreaming, isEnabled;
    int freqIndex, rangeIndex;

    SensorConfig(int imageResId, int nameResId, String identifier) {
        this(imageResId, nameResId, identifier, null);
    }

    SensorConfig(int imageResId, int nameResId, String identifier, String key) {
        this.imageResId = imageResId;
        this.nameResId = nameResId;
        this.identifier = identifier;
        this.key = key;
        isStreaming = true;
    }

    void setStreaming(boolean isStreaming) {
        this.isStreaming= isStreaming;

        float[] available = frequencies();
        if (available != null && freqIndex >= available.length) {
            freqIndex = available.length - 1;
        }
    }

    String selectedFreqText() {
        return String.format(Locale.US, "%.3fHz", frequencies()[freqIndex]);
    }

    String selectedRangeText() {
        return "";
    }

    RouteComponent setupStream(RouteComponent source) {
        return source.stream(null);
    }
    Task<Route> addRouteAsync(MetaWearBoard metawear) {
        configure(metawear);
        return getProducer(metawear).addRouteAsync(source -> {
            if (isStreaming) {
                setupStream(source);
            } else {
                source.log(null);
            }
        });
    }
    abstract float[] frequencies();
    float[] ranges() {
        return new float[] {};
    }
    void setInitialRangeIndex() {
        float[] values = ranges();
        if (values.length > 0) {
            rangeIndex = values.length - 1;
        }
    }

    abstract boolean isValid(MetaWearBoard metawear);
    abstract void configure(MetaWearBoard metawear);
    abstract DataProducer getProducer(MetaWearBoard metawear);
    abstract void start(MetaWearBoard metawear);
    abstract void stop(MetaWearBoard metawear);
    float dataThroughputSum(MetaWearBoard metawear) {
        return frequencies()[freqIndex];
    }
    float frequency(MetaWearBoard metawear) {
        return frequencies()[freqIndex];
    }

    static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
        final SortedMap<Integer, SensorConfig> hidden;
        final List<MetaWearBoard> metawears;
        final List<SensorConfig> items;
        boolean isStreaming = true;

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(View itemView) {
                super(itemView);
            }
        }

        // Provide a suitable constructor (depends on the kind of data set)
        Adapter() {
            items = new ArrayList<>();
            metawears = new ArrayList<>();
            hidden = new TreeMap<>();
        }

        boolean checkTotalDataThroughput() {
            float sum= 0;

            for(SensorConfig c: items) {
                if (c.isEnabled) {
                    for(MetaWearBoard metawear: metawears) {
                        if (c.isValid(metawear)) {
                            sum += c.dataThroughputSum(metawear);
                        }
                    }
                }
            }

            return !(sum > 125f * metawears.size());
        }

        void reset() {
            metawears.clear();
            hidden.clear();
            items.clear();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.setting, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SensorConfig current = items.get(position);

            ((ImageView) holder.itemView.findViewById(R.id.sensor_image)).setImageResource(current.imageResId);
            ((TextView) holder.itemView.findViewById(R.id.text_sensor)).setText(current.nameResId);

            ((TextView) holder.itemView.findViewById(R.id.sampling_rate_text)).setText(current.selectedFreqText());
            SeekBar freqSelector = holder.itemView.findViewById(R.id.sampling_rate_selector);
            float[] freqs = current.frequencies();

            if (freqs.length == 1) {
                freqSelector.setVisibility(View.GONE);
            } else {
                freqSelector.setVisibility(View.VISIBLE);
                freqSelector.setMax(freqs.length - 1);
                freqSelector.setProgress(current.freqIndex);
                freqSelector.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser) {
                            int prev = current.freqIndex;

                            current.freqIndex = progress;
                            if (isStreaming) {
                                if (!checkTotalDataThroughput()) {
                                    new AlertDialog.Builder(holder.itemView.getContext())
                                            .setTitle(R.string.title_error)
                                            .setMessage(R.string.message_data_throughput)
                                            .setPositiveButton(android.R.string.ok, (dialog, which) -> freqSelector.setProgress(prev))
                                            .show();
                                }
                            }
                        }

                        holder.itemView.post(() -> notifyItemChanged(holder.getAdapterPosition()));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {

                    }
                });
            }

            TextView rangeTxt = holder.itemView.findViewById(R.id.data_range_text);
            SeekBar rangeSelector = holder.itemView.findViewById(R.id.data_range_selector);
            float[] ranges = current.ranges();

            if (ranges.length > 0) {
                rangeTxt.setVisibility(View.VISIBLE);
                rangeTxt.setText(current.selectedRangeText());

                rangeSelector.setVisibility(View.VISIBLE);
                rangeSelector.setMax(ranges.length - 1);
                rangeSelector.setProgress(current.rangeIndex);
                rangeSelector.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser) {
                            current.rangeIndex = progress;
                            holder.itemView.post(() -> notifyItemChanged(holder.getAdapterPosition()));
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {

                    }
                });
            } else {
                rangeTxt.setVisibility(View.GONE);
                rangeSelector.setVisibility(View.GONE);
            }

            final Switch enable = holder.itemView.findViewById(R.id.enable);
            enable.setOnCheckedChangeListener(null);
            enable.setChecked(current.isEnabled);
            enable.setOnCheckedChangeListener((buttonView, isChecked) -> {
                current.isEnabled = isChecked;

                if (current.isEnabled) {
                    boolean throughputOk = true;
                    if (isStreaming) {
                        throughputOk = checkTotalDataThroughput();
                    }

                    if (throughputOk) {
                        List<SensorConfig> remove = new ArrayList<>();
                        int i = 0;
                        for(SensorConfig it: items) {
                            if (DISJOINT_KEY.containsKey(current.key) && DISJOINT_KEY.get(current.key).equals(it.key)) {
                                hidden.put(i, it);
                                remove.add(it);
                            }
                            i++;
                        }

                        for(SensorConfig it: remove) {
                            items.remove(it);
                        }

                        holder.itemView.post(this::notifyDataSetChanged);
                    } else {
                        new AlertDialog.Builder(holder.itemView.getContext())
                                .setTitle(R.string.title_error)
                                .setMessage(R.string.message_data_throughput)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    current.isEnabled = false;
                                    holder.itemView.post(() -> notifyItemChanged(holder.getAdapterPosition()));
                                })
                                .show();
                    }
                } else {
                    boolean remaining = false;
                    for(SensorConfig it: items) {
                        remaining|= it.isEnabled && it.key.equals(current.key);
                    }

                    if (!remaining) {
                        for(SortedMap.Entry<Integer, SensorConfig> it: hidden.entrySet()) {
                            items.add(it.getKey(), it.getValue());
                        }
                        hidden.clear();

                        holder.itemView.post(this::notifyDataSetChanged);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        void populate() {
            for(SensorConfig c: SensorConfig.All()) {
                boolean valid = false;
                for(MetaWearBoard m: metawears) {
                    valid|= c.isValid(m);
                }

                if (valid) {
                    items.add(c);
                }
            }
            for(SensorConfig c: items) {
                c.setInitialRangeIndex();
            }

            notifyDataSetChanged();
        }

        void setStreaming(boolean isStreaming) {
            this.isStreaming = isStreaming;

            for(SensorConfig c: items) {
                c.setStreaming(isStreaming);
            }

            notifyDataSetChanged();
        }
    }

    abstract static class ActiveSensorConfig extends SensorConfig {
        ActiveSensorConfig(int imageResId, int nameResId, String identifier) {
            super(imageResId, nameResId, identifier);
        }

        ActiveSensorConfig(int imageResId, int nameResId, String identifier, String key) {
            super(imageResId, nameResId, identifier, key);
        }

        @Override
        void start(MetaWearBoard metawear) {
            ((AsyncDataProducer) getProducer(metawear)).start();
        }

        @Override
        void stop(MetaWearBoard metawear) {
            ((AsyncDataProducer) getProducer(metawear)).stop();
        }
    }

    static class AccelerometerConfig extends ActiveSensorConfig {
        private static final float[] BMI160 = new float[] { 12.5f, 25f, 50f, 100f, 200f, 400f, 800f },
                BMI270 = new float[] { 12.5f, 25f, 50f, 100f, 200f, 400f, 800f },
                MMA8452Q = new float[] { 1.56f, 6.25f, 12.5f, 50f, 100f, 200f, 400f, 800f },
                BMA255 = new float[] { 15.62f, 31.26f, 62.5f, 125f, 250f, 500f },
                BOSCH_RANGE = new float[] {2f, 4f, 8f, 16f},
                MMA_RANGE = new float[] {2f, 4f, 8f, 16f};

        private float[] frequencies, ranges;

        AccelerometerConfig() {
            super(R.mipmap.ic_accelerometer, R.string.sensor_accelerometer, "acceleration", KEY_IMU);
        }


        @Override
        String selectedRangeText() {
            return String.format(Locale.US, "%.0fg", ranges()[rangeIndex]);
        }

        @Override
        float[] frequencies() {
            if (isStreaming) {
                List<Float> filtered = new ArrayList<>();
                for (float f : frequencies) {
                    if (f < 300f) {
                        filtered.add(f);
                    }
                }

                float[] result = new float[filtered.size()];
                int i = 0;
                for (float f : filtered) {
                    result[i] = f;
                    i++;
                }

                return result;
            }
            return frequencies;
        }

        @Override
        float[] ranges() {
            return ranges;
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            Accelerometer acc = metawear.getModule(Accelerometer.class);
            if (acc instanceof AccelerometerBmi160) {
                frequencies = BMI160;
                ranges = BOSCH_RANGE;
            } else if (acc instanceof AccelerometerBmi270) {
                frequencies = BMI270;
                ranges = BOSCH_RANGE;
            } else if (acc instanceof AccelerometerBma255) {
                frequencies = frequencies == null || frequencies == BMA255? BMA255 : BMI160;
                ranges = BOSCH_RANGE;
            } else if (acc instanceof AccelerometerMma8452q) {
                frequencies = frequencies == null || frequencies == MMA8452Q ? MMA8452Q : BMI160;
                ranges = ranges == null || frequencies == MMA_RANGE ? MMA_RANGE : BOSCH_RANGE;
            } else {
                return false;
            }
            return true;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            metawear.getModule(Accelerometer.class).configure()
                    .odr(frequencies()[freqIndex])
                    .range(ranges[rangeIndex])
                    .commit();
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(Accelerometer.class).acceleration();
        }

        @Override
        float dataThroughputSum(MetaWearBoard metawear) {
            return metawear.getModule(Accelerometer.class).getOdr() / 2f;
        }

        @Override
        float frequency(MetaWearBoard metawear) {
            return metawear.getModule(Accelerometer.class).getOdr();
        }

        @Override
        void start(MetaWearBoard metawear) {
            super.start(metawear);
            metawear.getModule(Accelerometer.class).start();
        }

        @Override
        void stop(MetaWearBoard metawear) {
            metawear.getModule(Accelerometer.class).stop();
            super.stop(metawear);
        }

        @Override
        RouteComponent setupStream(RouteComponent source) {
            return source.pack((byte) 2).account(RouteComponent.AccountType.COUNT).stream(null);
        }
    }
    static class GyroConfig extends ActiveSensorConfig {
        private float[] frequencies, ranges;

        GyroConfig() {
            super(R.mipmap.ic_gyroscope, R.string.sensor_gyroscope, "angular-velocity", KEY_IMU);

            frequencies = new float[] { 25f, 50f, 100f, 200f, 400f, 800f };
            ranges = new float[] { 125f, 250f, 500f, 1000f, 2000f };
        }

        @Override
        String selectedRangeText() {
            return String.format(Locale.US, "%.0f\u00B0/s", ranges()[rangeIndex]);
        }

        @Override
        float[] frequencies() {
            if (isStreaming) {
                List<Float> filtered = new ArrayList<>();
                for (float f : frequencies) {
                    if (f < 300f) {
                        filtered.add(f);
                    }
                }

                float[] result = new float[filtered.size()];
                int i = 0;
                for (float f : filtered) {
                    result[i] = f;
                    i++;
                }

                return result;
            }
            return frequencies;
        }

        @Override
        float[] ranges() {
            return ranges;
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            //Gyro gyro = metawear.getModule(Gyro.class);
            //if (gyro instanceof GyroBmi270) {
            //    return metawear.getModule(GyroBmi270.class) != null;
            //} else {
            //    return metawear.getModule(GyroBmi160.class) != null;
            //}
            return metawear.getModule(Gyro.class) != null;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            Gyro gyro = metawear.getModule(Gyro.class);
            gyro.configure()
                    .odr(Gyro.OutputDataRate.values()[freqIndex])
                    .range(Gyro.Range.values()[ranges.length - rangeIndex - 1])
                    .commit();
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            Gyro gyro = metawear.getModule(Gyro.class);
            if (gyro instanceof GyroBmi270) {
                return metawear.getModule(GyroBmi270.class).angularVelocity();
            } else {
                return metawear.getModule(GyroBmi160.class).angularVelocity();
            }
        }

        @Override
        float dataThroughputSum(MetaWearBoard metawear) {
            return frequencies[freqIndex] / 2f;
        }

        @Override
        void start(MetaWearBoard metawear) {
            super.start(metawear);
            metawear.getModule(Gyro.class).start();
        }

        @Override
        void stop(MetaWearBoard metawear) {
            metawear.getModule(Gyro.class).stop();
            super.stop(metawear);
        }

        @Override
        RouteComponent setupStream(RouteComponent source) {
            return source.pack((byte) 2).account(RouteComponent.AccountType.COUNT).stream(null);
        }
    }
    static class MagnetometerConfig extends ActiveSensorConfig {
        MagnetometerConfig() {
            super(R.mipmap.ic_magnetometer, R.string.sensor_magnetometer, "magnetic-field", KEY_IMU);
        }

        @Override
        float[] frequencies() {
            return new float[] { 10f, 15f, 20f, 25f };
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            return metawear.getModule(MagnetometerBmm150.class) != null;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            MagnetometerBmm150 mag = metawear.getModule(MagnetometerBmm150.class);
            MagnetometerBmm150.OutputDataRate odr;

            switch(freqIndex) {
                case 0:
                    odr= MagnetometerBmm150.OutputDataRate.ODR_10_HZ;
                    break;
                case 1:
                    odr= MagnetometerBmm150.OutputDataRate.ODR_15_HZ;
                    break;
                case 2:
                    odr= MagnetometerBmm150.OutputDataRate.ODR_20_HZ;
                    break;
                case 3:
                    odr= MagnetometerBmm150.OutputDataRate.ODR_25_HZ;
                    break;
                default:
                    odr = null;
                    break;
            }

            mag.configure()
                    .outputDataRate(odr)
                    .commit();

        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(MagnetometerBmm150.class).magneticField();
        }

        @Override
        float dataThroughputSum(MetaWearBoard metawear) {
            return frequencies()[freqIndex] / 2f;
        }

        @Override
        void start(MetaWearBoard metawear) {
            super.start(metawear);
            metawear.getModule(MagnetometerBmm150.class).start();
        }

        @Override
        void stop(MetaWearBoard metawear) {
            metawear.getModule(MagnetometerBmm150.class).stop();
            super.stop(metawear);
        }

        @Override
        RouteComponent setupStream(RouteComponent source) {
            return source.pack((byte) 2).account(RouteComponent.AccountType.COUNT).stream(null);
        }
    }

    static abstract class SensorFusionConfig extends ActiveSensorConfig {
        SensorFusionConfig(int imageResId, int nameResId, String identifier) {
            super(imageResId, nameResId, identifier, KEY_FUSED);
        }

        @Override
        float[] frequencies() {
            return new float[] { 100f };
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            return metawear.getModule(SensorFusionBosch.class) != null;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            metawear.getModule(SensorFusionBosch.class).configure()
                    .accRange(SensorFusionBosch.AccRange.AR_16G)
                    .gyroRange(SensorFusionBosch.GyroRange.GR_2000DPS)
                    .mode(SensorFusionBosch.Mode.NDOF)
                    .commit();
        }

        @Override
        void start(MetaWearBoard metawear) {
            super.start(metawear);
            metawear.getModule(SensorFusionBosch.class).start();
        }

        @Override
        void stop(MetaWearBoard metawear) {
            metawear.getModule(SensorFusionBosch.class).stop();
            super.stop(metawear);
        }

        @Override
        RouteComponent setupStream(RouteComponent source) {
            return source.account(RouteComponent.AccountType.COUNT).stream(null);
        }
    }
    static class QuaternionConfig extends SensorFusionConfig {
        QuaternionConfig() {
            super(R.mipmap.ic_gyroscope, R.string.sensor_quaternion, "quaternion");
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(SensorFusionBosch.class).quaternion();
        }
    }
    static class EulerAnglesConfig extends SensorFusionConfig {
        EulerAnglesConfig() {
            super(R.mipmap.ic_gyroscope, R.string.sensor_euler_angles, "euler-angles");
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(SensorFusionBosch.class).eulerAngles();
        }
    }
    static class LinearAccelerationConfig extends SensorFusionConfig {
        LinearAccelerationConfig() {
            super(R.mipmap.ic_accelerometer, R.string.sensor_linear_acc, "linear-acceleration");
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(SensorFusionBosch.class).linearAcceleration();
        }
    }
    static class GravityConfig extends SensorFusionConfig {
        GravityConfig() {
            super(R.mipmap.ic_accelerometer, R.string.sensor_gravity, "gravity");
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(SensorFusionBosch.class).gravity();
        }
    }

    static class AmbientLightConfig extends ActiveSensorConfig {
        AmbientLightConfig() {
            super(R.mipmap.ic_ambientlight, R.string.sensor_ambient_light, "illuminance");
        }

        @Override
        float[] frequencies() {
            return new float[] { 0.5f, 1f, 2f, 5f, 10f };
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            return metawear.getModule(AmbientLightLtr329.class) != null;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            AmbientLightLtr329.MeasurementRate[] rates= AmbientLightLtr329.MeasurementRate.values();

            metawear.getModule(AmbientLightLtr329.class).configure()
                    .gain(AmbientLightLtr329.Gain.LTR329_1X)
                    .integrationTime(AmbientLightLtr329.IntegrationTime.LTR329_TIME_100MS)
                    .measurementRate(rates[AmbientLightLtr329.MeasurementRate.LTR329_RATE_2000MS.ordinal() - freqIndex])
                    .commit();
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(AmbientLightLtr329.class).illuminance();
        }

        @Override
        RouteComponent setupStream(RouteComponent source) {
            return source.account().stream(null);
        }
    }

    static class PressureConfig extends ActiveSensorConfig {
        private static final float[] BMP280 = new float[] { 0.25f, 0.50f, 0.99f, 1.96f, 3.82f, 7.33f, 13.51f, 83.33f },
                BME280 = new float[] { 0.99f, 1.96f, 3.82f, 7.33f, 13.51f, 31.75f, 46.51f, 83.33f },
                COMBINED = new float[] { 0.99f, 1.96f, 3.82f, 7.33f, 13.51f, 83.33f };

        private float[] frequencies;

        PressureConfig() {
            super(R.mipmap.ic_pressure, R.string.sensor_pressure, "pressure");
        }

        @Override
        float[] frequencies() {
            return frequencies;
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            BarometerBosch barometer = metawear.getModule(BarometerBosch.class);
            if (barometer instanceof BarometerBmp280) {
                frequencies = (frequencies == null || frequencies == BMP280) ? BMP280 : COMBINED;
            } else if (barometer instanceof BarometerBme280) {
                frequencies = (frequencies == null || frequencies == BME280) ? BME280 : COMBINED;
            } else {
                return false;
            }
            return true;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            BarometerBosch barometer = metawear.getModule(BarometerBosch.class);

            if (barometer instanceof BarometerBmp280) {
                BarometerBmp280 bmp280 = (BarometerBmp280) barometer;
                BarometerBmp280.StandbyTime offset = frequencies == COMBINED ? BarometerBmp280.StandbyTime.TIME_1000 : BarometerBmp280.StandbyTime.TIME_4000;

                bmp280.configure()
                        .filterCoeff(BarometerBosch.FilterCoeff.OFF)
                        .pressureOversampling(BarometerBosch.OversamplingMode.LOW_POWER)
                        .standbyTime(BarometerBmp280.StandbyTime.values()[offset.ordinal() - freqIndex])
                        .commit();
            } else {
                BarometerBme280 bme280 = (BarometerBme280) barometer;
                BarometerBme280.ConfigEditor editor = bme280.configure()
                        .filterCoeff(BarometerBosch.FilterCoeff.OFF)
                        .pressureOversampling(BarometerBosch.OversamplingMode.LOW_POWER);

                if (frequencies == COMBINED) {
                    switch (freqIndex) {
                        case 0:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_1000);
                            break;
                        case 1:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_500);
                            break;
                        case 2:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_250);
                            break;
                        case 3:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_125);
                            break;
                        case 4:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_62_5);
                            break;
                        case 5:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_0_5);
                            break;
                    }
                } else {
                    switch (freqIndex) {
                        case 0:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_1000);
                            break;
                        case 1:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_500);
                            break;
                        case 2:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_250);
                            break;
                        case 3:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_125);
                            break;
                        case 4:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_62_5);
                            break;
                        case 5:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_20);
                            break;
                        case 6:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_10);
                            break;
                        case 7:
                            editor.standbyTime(BarometerBme280.StandbyTime.TIME_0_5);
                            break;
                    }
                }

                editor.commit();
            }
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(BarometerBosch.class).pressure();
        }

        @Override
        void start(MetaWearBoard metawear) {
            super.start(metawear);

            metawear.getModule(BarometerBosch.class).start();
        }

        @Override
        void stop(MetaWearBoard metawear) {
            metawear.getModule(BarometerBosch.class).stop();

            super.stop(metawear);
        }

        @Override
        RouteComponent setupStream(RouteComponent source) {
            return source.account().stream(null);
        }
    }

    abstract static class ForcedSensorConfig extends SensorConfig {
        private final Map<MetaWearBoard, Timer.ScheduledTask> scheduledTasks = new HashMap<>();


        ForcedSensorConfig(int imageResId, int nameResId, String identifier) {
            super(imageResId, nameResId, identifier);
        }

        @Override
        Task<Route> addRouteAsync(MetaWearBoard metawear) {
            Task<Route> result = super.addRouteAsync(metawear);
            return result.onSuccessTask(ignored ->
                    metawear.getModule(Timer.class).scheduleAsync(
                            (int) (frequencies()[freqIndex] * 1000),
                            false,
                            () -> ((ForcedDataProducer) getProducer(metawear)).read()
                    )
            ).onSuccessTask(task -> {
                scheduledTasks.put(metawear, task.getResult());
                return result;
            });
        }

        @Override
        void start(MetaWearBoard metawear) {
            if (scheduledTasks.containsKey(metawear)) {
                scheduledTasks.get(metawear).start();
            }
        }

        @Override
        void stop(MetaWearBoard metawear) {
            if (scheduledTasks.containsKey(metawear)) {
                scheduledTasks.get(metawear).stop();
            }
        }
    }
    abstract static class SlowSensorConfig extends ForcedSensorConfig {

        SlowSensorConfig(int imageResId, int nameResId, String identifier) {
            super(imageResId, nameResId, identifier);
        }

        @Override
        String selectedFreqText() {
            float[] available = frequencies();
            if (available[freqIndex] >= 3600) {
                return String.format(Locale.US, "%dhr", (int) (available[freqIndex] / 3600));
            }
            if (available[freqIndex] >= 60) {
                return String.format(Locale.US, "%dm", (int) (available[freqIndex] / 60));
            }
            return String.format(Locale.US, "%ds", (int)available[freqIndex]);
        }

        @Override
        float[] frequencies() {
            return new float[] { 3600, 1800, 900, 60, 30, 15, 1 };
        }

        @Override
        float dataThroughputSum(MetaWearBoard metawear) {
            return 1f / frequencies()[freqIndex];
        }
    }
    static class TemperatureConfig extends SlowSensorConfig {
        TemperatureConfig() {
            super(R.mipmap.ic_temperature, R.string.sensor_temperature, "temperature");
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            Temperature temperature = metawear.getModule(Temperature.class);
            return temperature != null && temperature.findSensors(Temperature.SensorType.PRESET_THERMISTOR) != null;
        }

        @Override
        void configure(MetaWearBoard metawear) {

        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(Temperature.class).findSensors(Temperature.SensorType.PRESET_THERMISTOR)[0];
        }
    }
    static class HumidityConfig extends SlowSensorConfig {
        HumidityConfig() {
            super(R.mipmap.ic_humidity, R.string.sensor_humidity, "relative-humidity");
        }

        @Override
        boolean isValid(MetaWearBoard metawear) {
            return metawear.getModule(HumidityBme280.class) != null;
        }

        @Override
        void configure(MetaWearBoard metawear) {
            metawear.getModule(HumidityBme280.class).setOversampling(HumidityBme280.OversamplingMode.SETTING_1X);
        }

        @Override
        DataProducer getProducer(MetaWearBoard metawear) {
            return metawear.getModule(HumidityBme280.class).value();
        }
    }
}
