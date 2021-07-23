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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.textfield.TextInputLayout;
import androidx.appcompat.app.AlertDialog;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mbientlab.function.Action;
import com.mbientlab.metawear.AnonymousRoute;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.impl.JseMetaWearBoard;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Led;
import com.mbientlab.metawear.module.Logging;
import com.mbientlab.metawear.module.Macro;
import com.mbientlab.metawear.module.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import bolts.Capture;
import bolts.Task;
import bolts.TaskCompletionSource;

import static com.mbientlab.metawear.metabase.Global.FIREBASE_PARAM_LOG_DURATION;

public class DataDownloadFragment extends AppFragmentBase implements ServiceConnection {
    final static String TEMP_CSV_TIME = "@time";
    private static final String FIREBASE_EVENT_DOWNLOAD = "download_log",
            FIREBASE_PARAM_DOWNLOAD_DURATION = "download_duration";

    private static class DownloadUi {
        ProgressBar progress;
        ImageView success;
        TextView failure;
    }
    private static class DownloadDevice {
        MetaWearBoard m;
        MetaBaseDevice d;
        DownloadUi ui;
        List<Pair<SensorConfig, AnonymousRoute>> routes;
        Boolean succeeded;
    }

    public static class Service extends android.app.Service implements ServiceConnection {
        private final LocalBinder localBinder = new LocalBinder();
        private final Handler handler = new Handler();
        private boolean bound = false;
        TaskCompletionSource<BtleService.LocalBinder> btleBinderTaskSource;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            btleBinderTaskSource.setResult((BtleService.LocalBinder) service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }

        class LocalBinder extends Binder {
            boolean active;
            SelectedGrouping parameter;
            List<DownloadDevice> dlDevices;
            Action<Boolean> finished;
            Activity activity;

            void setUi(List<DownloadUi> devicesUi) {
                int i = 0;
                for(DownloadDevice d: dlDevices) {
                    d.ui = devicesUi.get(i);

                    if (d.succeeded != null) {
                        if (!d.succeeded) {
                            d.ui.progress.setVisibility(View.GONE);
                            d.ui.failure.setText(R.string.error_download_fail);
                            d.ui.failure.setVisibility(View.VISIBLE);
                        } else {
                            d.ui.progress.setVisibility(View.GONE);
                            d.ui.success.setVisibility(View.VISIBLE);
                        }
                    }
                    i++;
                }
            }

            void clearUi() {
                for(DownloadDevice d: dlDevices) {
                    d.ui = null;
                }
            }

            void start(SelectedGrouping parameter, List<DownloadUi> devicesUi) {
                this.parameter = parameter;
                dlDevices = new ArrayList<>();
                active = true;

                btleBinderTaskSource = new TaskCompletionSource<>();
                getApplicationContext().bindService(new Intent(Service.this, BtleService.class), Service.this, Context.BIND_AUTO_CREATE);
                bound = true;

                btleBinderTaskSource.getTask().onSuccess(binderTask -> {
                    int i = 0;
                    for (MetaBaseDevice d : parameter.devices) {
                        binderTask.getResult().removeMetaWearBoard(d.btDevice);

                        DownloadDevice newDlDevice = new DownloadDevice();
                        newDlDevice.d = d;
                        newDlDevice.m = binderTask.getResult().getMetaWearBoard(d.btDevice);
                        newDlDevice.ui = devicesUi.get(i);
                        newDlDevice.succeeded = null;

                        dlDevices.add(newDlDevice);

                        i++;
                    }

                    final Capture<Boolean> allSucceeded = new Capture<>(true);
                    SensorConfig[] configs = SensorConfig.All();
                    Task<Void> task = Task.forResult(null);

                    for (final DownloadDevice dl : dlDevices) {
                        task = task.onSuccessTask(ignored ->
                            dl.m.connectAsync()
                        ).onSuccessTask(ignored -> {
                            dl.m.getModule(Logging.class).stop();
                            dl.m.getModule(Logging.class).flushPage();
                            return dl.m.createAnonymousRoutesAsync();
                        }).onSuccessTask(routeTask -> {
                            dl.routes = new ArrayList<>();
                            for (AnonymousRoute r : routeTask.getResult()) {
                                for (SensorConfig c : configs) {
                                    if (c.identifier.equals(r.identifier()) || (c.identifier.equals("temperature") && r.identifier().startsWith("temperature"))) {
                                        c.stop(dl.m);
                                        dl.routes.add(new Pair<>(c, r));
                                        break;
                                    }
                                }
                            }

                            return dl.m.getModule(Debug.class).disconnectAsync();
                        }).continueWithTask(initTask -> {
                            if (initTask.isFaulted()) {
                                allSucceeded.set(false);

                                handler.post(() -> {
                                    if (dl.ui != null) {
                                        dl.ui.progress.setVisibility(View.GONE);
                                        dl.ui.failure.setText(String.format(Locale.US, "Failed to sync the logging state for %s", dl.d.name));
                                        dl.ui.failure.setVisibility(View.VISIBLE);
                                    }
                                });
                            }

                            return Task.forResult(null);
                        });
                    }

                    final Map<File, String> firmwareLookup = new HashMap<>();
                    final List<File> files = new ArrayList<>();
                    final Capture<DataHandler.CsvDataHandler> earliest = new Capture<>(null);
                    task.onSuccessTask(ignored -> {
                        Task<Void> task2 = Task.forResult(null);
                        for (final DownloadDevice dl : dlDevices) {
                            final List<File> csvFiles = new ArrayList<>();
                            final List<DataHandler.CsvDataHandler> handlers = new ArrayList<>();
                            final List<Pair<SensorConfig, DataHandler.SampleCountDataHandler>> samples = new ArrayList<>();
                            final Capture<Long> dlStart = new Capture<>();

                            task2 = task2.continueWithTask(ignored2 -> {
                                String filenameBase = String.format(Locale.US, "%s_%s_%s", dl.d.name, TEMP_CSV_TIME, dl.d.getFileFriendlyMac());
                                File destDir = new File(AppState.devicesPath, dl.d.getFileFriendlyMac());
                                boolean resume = false;

                                for (Pair<SensorConfig, AnonymousRoute> it : dl.routes) {
                                    DataHandler.CsvDataHandler handler;
                                    DataHandler.SampleCountDataHandler count = new DataHandler.SampleCountDataHandler();

                                    File csv = new File(destDir, String.format("%s_%s", filenameBase, Service.this.getString(it.first.nameResId)));
                                    if (csv.exists()) {
                                        resume = true;

                                        BufferedReader reader = new BufferedReader(new FileReader(csv));
                                        reader.readLine();

                                        handler = new DataHandler.CsvDataHandler(new FileOutputStream(csv, true), it.first.identifier, 0f, false);
                                        if (reader.ready()) {
                                            long start = Long.parseLong(reader.readLine().split(",")[0]);
                                            handler.setFirst(start);
                                        }
                                    } else {
                                        handler = new DataHandler.CsvDataHandler(new FileOutputStream(csv), it.first.identifier, 0f, false);
                                        handler.init();
                                    }

                                    it.second.subscribe((data, env) -> {
                                        handler.process(data);
                                        count.process(data);
                                    });

                                    samples.add(new Pair<>(it.first, count));
                                    csvFiles.add(csv);
                                    handlers.add(handler);
                                }

                                if (resume) {
                                    try {
                                        dl.m.deserialize();
                                    } catch (Exception ignored3) {
                                    }
                                }
                                return dl.m.connectAsync();
                            }).onSuccessTask(ignored2 -> {
                                Settings.BleConnectionParametersEditor editor = dl.m.getModule(Settings.class).editBleConnParams();
                                if (editor != null) {
                                    editor.maxConnectionInterval(Global.connInterval)
                                            .commit();
                                }

                                JseMetaWearBoard casted = (JseMetaWearBoard) dl.m;
                                for (File f : csvFiles) {
                                    firmwareLookup.put(f, casted.getFirmware());
                                }
                                return Task.delay(1000);
                            }).onSuccessTask(ignored2 -> {
                                Led led = dl.m.getModule(Led.class);
                                led.stop(true);
                                led.editPattern(Led.Color.BLUE)
                                        .highIntensity((byte) 31)
                                        .lowIntensity((byte) 0)
                                        .riseTime((short) 100)
                                        .highTime((short) 200)
                                        .fallTime((short) 100)
                                        .pulseDuration((short) 800)
                                        .repeatCount((byte) 3)
                                        .commit();
                                led.play();

                                handler.post(() -> {
                                    if (dl.ui != null) {
                                        dl.ui.progress.setIndeterminate(false);
                                        dl.ui.progress.setMax(0);
                                    }
                                });
                                dlStart.set(System.nanoTime());
                                return dl.m.getModule(Logging.class).downloadAsync(100, ((nEntriesLeft, totalEntries) -> {
                                    handler.post(() -> {
                                        if (dl.ui != null) {
                                            if (dl.ui.progress.getMax() == 0) {
                                                dl.ui.progress.setMax((int) totalEntries);
                                            }
                                            dl.ui.progress.setProgress((int) (totalEntries - nEntriesLeft));
                                        }
                                    });
                                }));
                            }).continueWithTask(dlTask -> {
                                long dlStop = System.nanoTime();

                                for (DataHandler h : handlers) {
                                    h.stop();
                                }

                                if (dlTask.isFaulted()) {
                                    dl.succeeded = false;
                                    allSucceeded.set(false);
                                    handler.post(() -> {
                                        if (dl.ui != null) {
                                            dl.ui.progress.setVisibility(View.GONE);
                                            dl.ui.failure.setText(R.string.error_download_fail);
                                            dl.ui.failure.setVisibility(View.VISIBLE);
                                        }
                                    });
                                    dl.m.serialize();

                                } else {
                                    dl.succeeded = true;
                                    if (!handlers.isEmpty()) {
                                        handler.post(() -> {
                                            if (dl.ui != null) {
                                                dl.ui.progress.setVisibility(View.GONE);
                                                dl.ui.success.setVisibility(View.VISIBLE);
                                            }
                                        });

                                        Calendar temp = Calendar.getInstance();
                                        Collections.sort(handlers, (o1, o2) -> ((o2.first == null) ? temp : o2.first).compareTo(o1.first == null ? temp : o1.first));
                                        if (earliest.get() == null || (handlers.get(0).first != null && earliest.get().first.compareTo(handlers.get(0).first) > 0)) {
                                            earliest.set(handlers.get(0));
                                        }
                                        if (handlers.get(0).first != null && BuildConfig.LOG_EVENT) {
                                            Bundle bundle = new Bundle();
                                            bundle.putLong(FIREBASE_PARAM_LOG_DURATION, handlers.get(0).last.getTimeInMillis() - handlers.get(0).first.getTimeInMillis());
                                            bundle.putLong(FIREBASE_PARAM_DOWNLOAD_DURATION, (dlStop - dlStart.get()) / 1000L);
                                            for (Pair<SensorConfig, DataHandler.SampleCountDataHandler> it : samples) {
                                                bundle.putInt(getString(it.first.nameResId).toLowerCase().replaceAll(" ", "_"), it.second.samples);
                                            }
                                        }
                                        files.addAll(csvFiles);
                                    } else {
                                        handler.post(() -> {
                                            if (dl.ui != null) {
                                                dl.ui.progress.setVisibility(View.GONE);
                                                dl.ui.failure.setText(String.format(Locale.US, "No logged data for %s", dl.d.name));
                                                dl.ui.failure.setVisibility(View.VISIBLE);
                                            }
                                        });
                                    }

                                    dl.m.getModule(Macro.class).eraseAll();
                                    dl.m.getModule(Debug.class).resetAfterGc();
                                }

                                return dl.m.getModule(Debug.class).disconnectAsync();
                            });
                        }

                        return task2;
                    }).continueWith(ignored -> {
                        for (final DownloadDevice dl : dlDevices) {
                            binderTask.getResult().removeMetaWearBoard(dl.d.btDevice);
                        }

                        if (allSucceeded.get() && earliest.get() != null) {
                            String earliestTime = String.format(Locale.US, DataHandler.CsvDataHandler.TIMESTAMP_FORMAT, earliest.get().first);

                            AppState.Session session = new AppState.Session("", earliestTime, files);

                            final Capture<EditText> sessionName = new Capture<>();
                            final Capture<TextInputLayout> sessionNameTextWrapper = new Capture<>();
                            TaskCompletionSource<String> sessionNameTaskSource = new TaskCompletionSource<>();
                            handler.post(() -> {
                                final AlertDialog sessionDialog = new AlertDialog.Builder(activity)
                                        .setTitle(R.string.title_session_name)
                                        .setView(R.layout.dialog_item_naming)
                                        .setCancelable(false)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .create();
                                sessionDialog.show();

                                ((TextView) sessionDialog.findViewById(R.id.instructions_text)).setText(R.string.instruction_name_session);
                                sessionName.set(sessionDialog.findViewById(R.id.item_name));
                                sessionNameTextWrapper.set(sessionDialog.findViewById(R.id.item_name_wrapper));

                                sessionDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v2 -> {
                                    String customName = sessionName.get().getText().toString();
                                    if (customName.contains("_")) {
                                        sessionNameTextWrapper.get().setError(Service.this.getString(R.string.error_underscore));
                                    } else {
                                        sessionNameTextWrapper.get().setError(null);
                                        sessionDialog.dismiss();

                                        sessionNameTaskSource.setResult(customName);
                                    }
                                });
                            });
                            sessionNameTaskSource.getTask().continueWith(nameTask -> {
                                session.name = nameTask.getResult().length() == 0 ?
                                        String.format(Locale.US, "%sSession %d", parameter.devices.size() > 1 ? parameter.name + " " : "", parameter.sessions.size() + 1) :
                                        nameTask.getResult();

                                List<File> renamed = new ArrayList<>();
                                String[] paths = new String[session.files.size()];
                                int j = 0;
                                for (File it : session.files) {
                                    File newName = new File(it.getParent(), String.format(Locale.US, "%s_%s_%s.csv",
                                            session.name, it.getName().replace(TEMP_CSV_TIME, earliestTime), firmwareLookup.get(it))
                                    );
                                    if (it.renameTo(newName)) {
                                        renamed.add(newName);
                                        paths[j] = newName.getAbsolutePath();
                                    } else {
                                        renamed.add(it);
                                        paths[j] = it.getAbsolutePath();
                                    }
                                    j++;
                                }

                                MediaScannerConnection.scanFile(Service.this, paths, null, (path, uri) -> {
                                    session.files.clear();
                                    session.files.addAll(renamed);
                                    parameter.sessions.add(0, session);
                                    finished.apply(true);
                                });

                                return null;
                            });
                        } else {
                            handler.post(() -> {
                                new AlertDialog.Builder(activity)
                                        .setTitle(R.string.title_error)
                                        .setMessage(R.string.message_dl_fail)
                                        .setCancelable(false)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .create()
                                        .show();
                            });
                            finished.apply(false);
                        }
                        return null;
                    });

                    return null;
                });
            }
        }

        @Override
        public void onDestroy() {
            localBinder.active = false;
            if (bound) {
                getApplicationContext().unbindService(this);
            }

            super.onDestroy();
        }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) {
            return localBinder;
        }
    }

    private SelectedGrouping parameter;
    private Service.LocalBinder binder;
    private Intent downloadServiceIntent;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        parameter = (SelectedGrouping) activityBus.parameter();

        downloadServiceIntent = new Intent(owner, Service.class);
        owner.startService(downloadServiceIntent);
        owner.getApplicationContext().bindService(downloadServiceIntent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binder.clearUi();
        owner.getApplicationContext().unbindService(this);
    }

    @Override
    Integer getMenuGroupResId() {
        return null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_data_download, container, false);
    }


    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        binder = (Service.LocalBinder) service;
        binder.activity = owner;

        LinearLayout devicesLayout = getView().findViewById(R.id.devices);

        final List<DownloadUi> devicesUi = new ArrayList<>();
        for (MetaBaseDevice d : parameter.devices) {
            View status = getLayoutInflater().inflate(R.layout.device_download_status, null);
            ((TextView) status.findViewById(R.id.device_name)).setText(d.name);

            DownloadUi ui = new DownloadUi();
            ui.progress = status.findViewById(R.id.download_progress);
            ui.success = status.findViewById(R.id.download_succeeded);
            ui.failure = status.findViewById(R.id.download_failed_msg);

            devicesLayout.addView(status);
            devicesUi.add(ui);
        }

        if (!binder.active) {
            binder.start(parameter, devicesUi);
        } else {
            binder.setUi(devicesUi);
        }
        binder.finished = arg1 -> {
            if (arg1) {
                activityBus.navigateBack();
            }
            owner.stopService(downloadServiceIntent);
        };
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

}
