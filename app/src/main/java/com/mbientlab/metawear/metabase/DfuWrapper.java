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
import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mbientlab.metawear.DeviceInformation;
import com.mbientlab.metawear.MetaWearBoard;

import java.io.File;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import bolts.Task;
import bolts.TaskCompletionSource;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

class DfuWrapper {
    static class DfuException extends RuntimeException {
        DfuException(String message) {
            super(message);
        }
    }

    public static class DfuProgressFragment extends DialogFragment {
        private AlertDialog dialog;
        private ProgressBar progressBar;

        public static DfuProgressFragment newInstance(int messageStringId) {
            Bundle bundle= new Bundle();
            bundle.putInt("message_string_id", messageStringId);

            DfuProgressFragment newFragment= new DfuProgressFragment();
            newFragment.setArguments(bundle);
            return newFragment;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            dialog = new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.title_firmware_update)
                    .setView(R.layout.fragment_dfu_dialog)
                    .setCancelable(false)
                    .create();
            return dialog;
        }

        @Override
        public void onStart() {
            super.onStart();

            progressBar = dialog.findViewById(R.id.dfu_progress);
            ((TextView) dialog.findViewById(R.id.dfu_message)).setText(getArguments().getInt("message_string_id"));
        }

        public void updateProgress(int newProgress) {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(newProgress);
            }
        }
    }

    private static final String DFU_PROGRESS_FRAGMENT_TAG = "com.mbientlab.metawear.metabase.DfuWrapper.DFU_PROGRESS_FRAGMENT_TAG";
    private final static Map<String, String> EXTENSION_TO_APP_TYPE;
    static {
        EXTENSION_TO_APP_TYPE= new HashMap<>();
        EXTENSION_TO_APP_TYPE.put("hex", DfuBaseService.MIME_TYPE_OCTET_STREAM);
        EXTENSION_TO_APP_TYPE.put("bin", DfuBaseService.MIME_TYPE_OCTET_STREAM);
        EXTENSION_TO_APP_TYPE.put("zip", DfuBaseService.MIME_TYPE_ZIP);
    }

    private static String getExtension(String path) {
        int i= path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1) : null;
    }

    private static boolean addMimeType(DfuServiceInitiator starter, String filename, Object path) {
        if (path instanceof File) {
            File filePath= (File) path;

            String mimeType= EXTENSION_TO_APP_TYPE.get(getExtension(filePath.getAbsolutePath()));
            if (mimeType == null) {
                mimeType= EXTENSION_TO_APP_TYPE.get(getExtension(filename));
            }

            if (mimeType.equals(HomeActivity.DfuService.MIME_TYPE_OCTET_STREAM)) {
                starter.setBinOrHex(DfuBaseService.TYPE_APPLICATION, filePath.getAbsolutePath());
            } else {
                starter.setZip(filePath.getAbsolutePath());
            }
        } else if (path instanceof Uri) {
            Uri uriPath = (Uri) path;

            String mimeType= EXTENSION_TO_APP_TYPE.get(getExtension(uriPath.toString()));
            if (mimeType == null) {
                mimeType= EXTENSION_TO_APP_TYPE.get(getExtension(filename));
            }

            if (mimeType.equals(HomeActivity.DfuService.MIME_TYPE_OCTET_STREAM)) {
                starter.setBinOrHex(DfuBaseService.TYPE_APPLICATION, uriPath);
            } else {
                starter.setZip(uriPath);
            }
        } else return path == null;

        return true;
    }

    private TaskCompletionSource<Void> updateTaskSource;
    private DeviceInformation devInfo;
    private String model;

    private final FragmentManager manager;
    private final Activity activity;
    private final DfuProgressListener dfuProgressListener= new DfuProgressListenerAdapter() {
        @Override
        public void onProgressChanged(String deviceAddress, int percent, float speed, float avgSpeed, int currentPart, int partsTotal) {
            DfuProgressFragment value = ((DfuProgressFragment) manager.findFragmentByTag(DFU_PROGRESS_FRAGMENT_TAG));
            if (value != null) {
                value.updateProgress(percent);
            }
        }

        @Override
        public void onDfuCompleted(String deviceAddress) {
            updateTaskSource.setResult(null);
        }

        @Override
        public void onDfuAborted(String deviceAddress) {
            updateTaskSource.setCancelled();
        }

        @Override
        public void onError(String deviceAddress, int error, int errorType, String message) {
            String msg = String.format(Locale.US, "DFU failed: {error: %d, errorType: %d, message: %s, hardware: %s, model: %s}",
                    error, errorType, message, devInfo.hardwareRevision, model);

            updateTaskSource.setError(new RuntimeException(msg));
        }
    };

    DfuWrapper(Activity activity, FragmentManager manager) {
        this.activity = activity;
        this.manager = manager;
    }

    Task<Void> start(MetaWearBoard metawear, String deviceName, Object path, String filename) {
        if (updateTaskSource != null && !updateTaskSource.getTask().isCompleted()) {
            return updateTaskSource.getTask();
        }

        DfuServiceListenerHelper.registerProgressListener(activity, dfuProgressListener);
        return metawear.readDeviceInformationAsync().onSuccessTask(task -> {
            devInfo = task.getResult();
            model = Global.getRealModel(metawear.getModelString(), devInfo.modelNumber);

            if (path == null) {
                activity.runOnUiThread(() -> DfuProgressFragment.newInstance(R.string.message_dfu).show(manager, DFU_PROGRESS_FRAGMENT_TAG));
                return metawear.downloadFirmwareUpdateFilesAsyncV2().onSuccessTask(task2 -> {
                    List<Object> entries = new ArrayList<>(task2.getResult());
                    return Task.forResult(entries);
                });
            } else {
                activity.runOnUiThread(() ->DfuProgressFragment.newInstance(R.string.message_manual_dfu).show(manager, DFU_PROGRESS_FRAGMENT_TAG));

                final List<Object> entries;
                if (path instanceof List) {
                    entries = (List<Object>) path;
                } else {
                    entries = new ArrayList<>();
                    entries.add(path);
                }
                return Task.forResult(entries);
            }
        }).onSuccessTask(task -> {
            Task<Void> uploadTask = Task.forResult(null);

            for(Object o: task.getResult()) {
                uploadTask = uploadTask.onSuccessTask(ignored -> Task.delay(1000)).onSuccessTask(task2 -> {
                    final DfuServiceInitiator starter = new DfuServiceInitiator(metawear.getMacAddress())
                            // need next 2 settings for rev 0.3 RG boards
                            // https://github.com/NordicSemiconductor/Android-DFU-Library/issues/84
                            .setPacketsReceiptNotificationsEnabled(true)
                            .setPacketsReceiptNotificationsValue(10)
                            .setDeviceName(deviceName)
                            .setKeepBond(false)
                            .setForceDfu(true);

                    // Init packet is required by Bootloader/DFU from SDK 7.0+ if HEX or BIN file is given above.
                    // In case of a ZIP file, the init packet (a DAT file) must be included inside the ZIP file.
                    //service.putExtra(NordicDfuService.EXTRA_INIT_FILE_PATH, mInitFilePath);
                    //service.putExtra(NordicDfuService.EXTRA_INIT_FILE_URI, mInitFileStreamUri);
                    if (!addMimeType(starter, filename, o)) {
                        return Task.forError(new InvalidParameterException(activity.getString(R.string.dfu_firmware_path_type)));
                    }

                    updateTaskSource = new TaskCompletionSource<>();
                    starter.start(activity, HomeActivity.DfuService.class);
                    return updateTaskSource.getTask();
                });
            }
            return uploadTask;
        }).continueWithTask(task -> {
            DfuServiceListenerHelper.unregisterProgressListener(activity, dfuProgressListener);
            DfuProgressFragment value = ((DfuProgressFragment) manager.findFragmentByTag(DFU_PROGRESS_FRAGMENT_TAG));
            if (value != null) {
                value.dismissAllowingStateLoss();
            }
            return task;
        }, Task.UI_THREAD_EXECUTOR);
    }
}
