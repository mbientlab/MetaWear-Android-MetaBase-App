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

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.mbientlab.metawear.IllegalRouteOperationException;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.impl.JseMetaWearBoard;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Led;
import com.mbientlab.metawear.module.Logging;
import com.mbientlab.metawear.module.Macro;
import com.mbientlab.metawear.module.Settings;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import bolts.Task;

import static com.mbientlab.metawear.metabase.Global.FIREBASE_PARAM_DEVICE_NAME;
import static com.mbientlab.metawear.metabase.Global.FIREBASE_PARAM_FIRMWARE;
import static com.mbientlab.metawear.metabase.Global.FIREBASE_PARAM_MAC;
import static com.mbientlab.metawear.metabase.Global.FIREBASE_PARAM_MODEL;

public class DeviceConfigFragment extends AppFragmentBase {
    private static final String FIREBASE_EVENT_RECORD = "start_log", FIREBASE_EVENT_STREAM = "start_stream";

    private SelectedGrouping parameter;
    private SensorConfig.Adapter sensorsAdapter;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        parameter = (SelectedGrouping) activityBus.parameter();

        sensorsAdapter.reset();

        final AlertDialog syncDialog = new AlertDialog.Builder(owner)
                .setTitle(R.string.title_config)
                .setView(R.layout.indeterminate_task)
                .create();
        syncDialog.show();
        ((TextView) syncDialog.findViewById(R.id.message)).setText(R.string.message_loading_sensors);

        Task<Void> result = Task.forResult(null);
        for(MetaBaseDevice d: parameter.devices) {
            final MetaWearBoard metawear = activityBus.getMetaWearBoard(d.btDevice);
            result = result.onSuccessTask(ignored -> {
                try {
                    ((JseMetaWearBoard) metawear).loadBoardAttributes();
                    sensorsAdapter.metawears.add(metawear);
                    return Task.forResult(null);
                } catch (IOException | ClassNotFoundException e) {
                    return metawear.connectAsync().onSuccessTask(ignored2 -> {
                        sensorsAdapter.metawears.add(metawear);
                        return metawear.disconnectAsync();
                    }).continueWithTask(task -> {
                        if (task.isFaulted()) {
                            throw new RuntimeException(String.format(Locale.US, "Unable to determine available sensors for '%s'", d.name));
                        }
                        return task;
                    });
                }
            });
        }
        result.continueWith(task -> {
            syncDialog.dismiss();
            if (task.isFaulted()) {
                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_error)
                        .setMessage(task.getError().getMessage())
                        .setPositiveButton(android.R.string.ok, ((dialog, which) -> activityBus.navigateBack()))
                        .setCancelable(false)
                        .create()
                        .show();
            } else {
                sensorsAdapter.populate();
            }

            ((Switch) getView().findViewById(R.id.data_collection_toggle)).setChecked(parameter.devices.size() > 2);

            return null;
        }, Task.UI_THREAD_EXECUTOR);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorsAdapter = new SensorConfig.Adapter();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView sensors = view.findViewById(R.id.sensors);
        sensors.setAdapter(sensorsAdapter);

        final TextView recordTypeDesc = view.findViewById(R.id.data_collection_description);
        final TextView recordTypeType = view.findViewById(R.id.data_collection_type);

        ((Switch) view.findViewById(R.id.data_collection_toggle)).setOnCheckedChangeListener((buttonView, isChecked) -> {
            sensorsAdapter.setStreaming(!isChecked);
            if (sensorsAdapter.isStreaming && !sensorsAdapter.checkTotalDataThroughput()) {
                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_error)
                        .setMessage(R.string.message_data_throughput)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }

            if (isChecked) {
                recordTypeType.setText(R.string.label_logging);
                recordTypeDesc.setText(R.string.description_logging);
            } else {
                recordTypeType.setText(R.string.label_streaming);
                recordTypeDesc.setText(R.string.description_streaming);
            }
        });

        view.findViewById(R.id.configure).setOnClickListener(v -> {
            if (sensorsAdapter.isStreaming && !sensorsAdapter.checkTotalDataThroughput()) {
                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_error)
                        .setMessage(R.string.error_stream_throughput)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                        .show();
            } else {
                boolean anyEnabled = false;
                for (SensorConfig c : sensorsAdapter.items) {
                    anyEnabled |= c.isEnabled;
                }

                if (!anyEnabled) {
                    new AlertDialog.Builder(owner)
                            .setTitle(R.string.title_error)
                            .setMessage(R.string.message_select_sensor)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    final List<MetaBaseDevice> eraseable = new ArrayList<>();
                    final List<Pair<MetaBaseDevice, Map<SensorConfig, Route>>> activeDevices = new ArrayList<>();

                    final AlertDialog configDialog = new AlertDialog.Builder(owner)
                            .setTitle(R.string.title_config)
                            .setView(R.layout.indeterminate_task)
                            .setCancelable(false)
                            .create();
                    configDialog.show();

                    Task.callInBackground(() -> {
                        Task<Void> task = Task.forResult(null);
                        for (final MetaBaseDevice d : parameter.devices) {
                            final MetaWearBoard m = activityBus.getMetaWearBoard(d.btDevice);
                            task = task.onSuccessTask(ignored -> {
                                owner.runOnUiThread(() -> ((TextView) configDialog.findViewById(R.id.message)).setText(owner.getString(R.string.message_config_board, d.name)));
                                return m.connectAsync();
                            }).continueWithTask(task2 -> {
                                if (task2.isFaulted()) {
                                    throw new RuntimeException(String.format("This session has been cancelled because the app failed to configure '%s'", d.name), task2.getError());
                                }
                                return task2;
                            }).onSuccessTask(ignored -> {
                                eraseable.add(d);
                                Settings.BleConnectionParametersEditor editor = m.getModule(Settings.class).editBleConnParams();
                                if (editor != null) {
                                    editor.maxConnectionInterval(Global.connInterval)
                                            .commit();
                                    return Task.delay(1000L);
                                }
                                return Task.forResult(null);
                            }).onSuccessTask(ignored -> {
                                if (!sensorsAdapter.isStreaming) {
                                    m.getModule(Macro.class).startRecord();
                                }

                                Led led = m.getModule(Led.class);
                                if (led != null) {
                                    led.editPattern(Led.Color.GREEN)
                                            .highIntensity((byte) 31).lowIntensity((byte) 0)
                                            .riseTime((short) 100).highTime((short) 200).fallTime((short) 100).pulseDuration((short) 800)
                                            .repeatCount((byte) 3)
                                            .commit();
                                    led.editPattern(Led.Color.RED)
                                            .highIntensity((byte) 10).lowIntensity((byte) 0)
                                            .riseTime((short) 100).highTime((short) 200).fallTime((short) 100).pulseDuration((short) 15000)
                                            .delay((short) 2400)
                                            .repeatCount(Led.PATTERN_REPEAT_INDEFINITELY)
                                            .commit();
                                    led.play();
                                }

                                final Pair<MetaBaseDevice, Map<SensorConfig, Route>> active = new Pair<>(d, new HashMap<>());
                                Task<Void> createRouteTask = Task.forResult(null);

                                for (SensorConfig c : sensorsAdapter.items) {
                                    createRouteTask = createRouteTask.onSuccessTask(ignored2 -> {
                                        if (c.isEnabled && c.isValid((m))) {
                                            return c.addRouteAsync(m).onSuccessTask(task2 -> {
                                                active.second.put(c, task2.getResult());
                                                return Task.forResult(null);
                                            });
                                        }
                                        return Task.forResult(null);
                                    });
                                }
                                return createRouteTask.onSuccessTask(ignored2 -> Task.forResult(active));
                            }).onSuccessTask(task2 -> {
                                activeDevices.add(task2.getResult());
                                if (!sensorsAdapter.isStreaming) {
                                    byte[] name = new byte[]{0x4d, 0x65, 0x74, 0x61, 0x57, 0x65, 0x61, 0x72};
                                    try {
                                        name = d.name.getBytes("ASCII");
                                    } catch (UnsupportedEncodingException ignored) {
                                        name = d.name.getBytes();
                                    } finally {
                                        int length = Math.min(name.length, Global.nameMaxChar);
                                        byte[] response = new byte[5 + length];
                                        response[0] = (byte) (response.length - 1);
                                        response[1] = (byte) 0xff;
                                        response[2] = Global.COMPANY_ID & 0xff;
                                        response[3] = (Global.COMPANY_ID >> 8) & 0xff;
                                        response[4] = Global.METABASE_SCAN_ID;
                                        System.arraycopy(name, 0, response, 5, length);

                                        m.getModule(Settings.class).editBleAdConfig()
                                                .scanResponse(response)
                                                .commit();
                                    }

                                    return m.getModule(Macro.class).endRecordAsync().continueWithTask(macroTask -> {
                                        if (macroTask.isFaulted()) {
                                            throw macroTask.getError();
                                        }

                                        if (BuildConfig.LOG_EVENT) {
                                            JseMetaWearBoard casted = (JseMetaWearBoard) m;
                                            Bundle bundle = new Bundle();
                                            bundle.putString(FIREBASE_PARAM_DEVICE_NAME, d.name);
                                            bundle.putString(FIREBASE_PARAM_MAC, d.mac);
                                            bundle.putString(FIREBASE_PARAM_MODEL, Global.getRealModel(m.getModelString(), casted.getModelNumber()));
                                            bundle.putString(FIREBASE_PARAM_FIRMWARE, casted.getFirmware());

                                        }

                                        return Task.forResult(null);
                                    });
                                }
                                return Task.forResult(null);
                            });
                        }
                        task.continueWith(task2 -> {
                            configDialog.dismiss();
                            if (task2.isFaulted()) {
                                String errorMsg;
                                if (task2.getError() instanceof IllegalRouteOperationException) {
                                    StringBuilder builder = new StringBuilder();
                                    builder.append("Firmware v1.3.4 or new required.  Please update the firmware for: \n\n");

                                    boolean first = true;
                                    for (MetaBaseDevice d : eraseable) {
                                        if (!first) builder.append(", ");
                                        builder.append("'").append(d.name).append("'");

                                        first = false;

                                    }

                                    errorMsg = builder.toString();
                                } else {
                                    errorMsg = task2.getError().getLocalizedMessage();
                                }

                                new AlertDialog.Builder(owner)
                                        .setTitle(R.string.title_error)
                                        .setMessage(errorMsg)
                                        .setPositiveButton(android.R.string.ok, ((dialog, which) -> {
                                            Task<Void> eraseTask = Task.forResult(null);
                                            for (MetaBaseDevice d : eraseable) {
                                                MetaWearBoard m = activityBus.getMetaWearBoard(d.btDevice);
                                                eraseTask = eraseTask.continueWithTask(ignored -> m.connectAsync())
                                                        .continueWithTask(connTask -> {
                                                            if (!(connTask.isFaulted() || connTask.isCancelled())) {
                                                                m.getModule(Macro.class).eraseAll();
                                                                m.getModule(Debug.class).resetAfterGc();
                                                                m.getModule(Debug.class).disconnectAsync();
                                                            }
                                                            return Task.forResult(null);
                                                        });
                                            }
                                            eraseTask.continueWith(ignored -> {
                                                activityBus.navigateBack();
                                                return null;
                                            }, Task.UI_THREAD_EXECUTOR);
                                        }))
                                        .create()
                                        .show();
                            } else {
                                new AlertDialog.Builder(owner)
                                        .setTitle(R.string.title_success)
                                        .setMessage(owner.getString(R.string.message_sensors_active, sensorsAdapter.isStreaming ? "streaming" : "logging"))
                                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                            if (!sensorsAdapter.isStreaming) {
                                                for (Pair<MetaBaseDevice, Map<SensorConfig, Route>> d : activeDevices) {
                                                    final MetaWearBoard m = activityBus.getMetaWearBoard(d.first.btDevice);

                                                    m.getModule(Logging.class).start(false);
                                                    for (SensorConfig c : d.second.keySet()) {
                                                        c.start(m);
                                                    }

                                                    d.first.isDiscovered = false;
                                                    m.getModule(Debug.class).disconnectAsync();
                                                }
                                                activityBus.navigateBack();
                                            } else {
                                                StreamMonitorFragment.Parameter streamParameters = new StreamMonitorFragment.Parameter();
                                                streamParameters.devices = activeDevices;
                                                streamParameters.sessions = parameter.sessions;
                                                streamParameters.name = parameter.name;

                                                activityBus.swapFragment(StreamMonitorFragment.class, streamParameters);
                                            }
                                        })
                                        .setCancelable(false)
                                        .create()
                                        .show();
                            }

                            return null;
                        }, Task.UI_THREAD_EXECUTOR);

                        return null;
                    });
                }
            }
        });
    }

    @Override
    Integer getMenuGroupResId() {
        return null;
    }
}
