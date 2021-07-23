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

import android.app.Activity;;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import bolts.Capture;
import bolts.Task;
import bolts.TaskCompletionSource;

public class DeviceInfoFragment extends AppFragmentBase {
    private static final String FIREBASE_EVENT_CLOUD_SYNC_SUCCESS = "sync_metacloud";

    interface MetaCloudOptions {
        boolean showAbout();
        void disableAbout();
    }

    private static final int LOGIN_REQUEST = 0;

    private TaskCompletionSource<Void> loginTaskSource;

    private MetaBaseDevice.Adapter devicesAdapter;
    private AppState.Session.Adapter sessionsAdapter;
    private Button startBtn;
    private TextView title;
    private SelectedGrouping parameter;
    private MetaCloudOptions options;

    public DeviceInfoFragment() {
        // Required empty public constructor
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
            case LOGIN_REQUEST:
                if (resultCode == Activity.RESULT_OK) {
                    loginTaskSource.setResult(null);
                } else if (resultCode == Activity.RESULT_CANCELED) {
                    loginTaskSource.setCancelled();
                } else {
                    loginTaskSource.setError(new RuntimeException("MetaCloud login failed"));
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void updateStartBtn() {
        boolean present = true;
        boolean enableDownload = false;

        for(MetaBaseDevice d: parameter.devices) {
            enableDownload |= d.isRecording;
            present &= d.isDiscovered;
        }

        if (present) {
            startBtn.setVisibility(View.VISIBLE);
            if (enableDownload) {
                startBtn.setText(R.string.title_download);
                startBtn.setOnClickListener(v -> activityBus.swapFragment(DataDownloadFragment.class, parameter));
            } else {
                startBtn.setText(R.string.label_new_session);
                startBtn.setOnClickListener(v -> activityBus.swapFragment(DeviceConfigFragment.class, parameter));
            }
        } else {
            startBtn.setVisibility(View.GONE);
            startBtn.setOnClickListener(null);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        devicesAdapter = new MetaBaseDevice.Adapter();
        devicesAdapter.itemClicked = arg1 -> activityBus.swapFragment(DiagnosticFragment.class, arg1);

        sessionsAdapter = new AppState.Session.Adapter();
        setHasOptionsMenu(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (!(getActivity() instanceof MetaCloudOptions)) {
            throw new ClassCastException("Owning activity does not derive from MetaCloudOptions");
        }
        options = (MetaCloudOptions) getActivity();

        parameter = (SelectedGrouping) activityBus.parameter();

        if (parameter.devices.size() == 1) {
            title.setText(R.string.label_device);
        } else {
            title.setText(parameter.name);
        }

        devicesAdapter.items.clear();
        devicesAdapter.items.addAll(parameter.devices);
        devicesAdapter.notifyDataSetChanged();

        sessionsAdapter.items.clear();
        sessionsAdapter.items.addAll(parameter.sessions);
        sessionsAdapter.notifyDataSetChanged();
        sessionsAdapter.shareSession = arg1 -> {
            final StringBuilder devices = new StringBuilder();
            boolean first = true;
            for(MetaBaseDevice d: parameter.devices) {
                if (!first) {
                    devices.append(", ");
                }
                devices.append(d.mac);
                first = false;
            }

            final Intent intentShareFile = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intentShareFile.setType("text/plain");
            intentShareFile.putExtra(Intent.EXTRA_SUBJECT, String.format(Locale.US, "MetaBase Session: %s", arg1.name));
            ArrayList<Uri> uris = new ArrayList<>();
            for (File it : arg1.files) {
                uris.add(FileProvider.getUriForFile(owner, "com.mbientlab.metawear.metabase.fileprovider", it));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intentShareFile.setData(FileProvider.getUriForFile(owner, "com.mbientlab.metawear.metabase.fileprovider", it));
                    intentShareFile.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            }
            intentShareFile.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intentShareFile.putExtra(Intent.EXTRA_TEXT, String.format(Locale.US, "Boards: [%s]", devices.toString()));
            //owner.startActivity(Intent.createChooser(intent, "Saving Data..."));

            Intent chooser = Intent.createChooser(intentShareFile, "Share File");
            List<ResolveInfo> resInfoList = owner.getPackageManager().queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                for (Uri uri : uris) {
                    owner.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
            startActivity(chooser);
        };

        activityBus.scanner().start(arg1 -> {
            devicesAdapter.update(arg1);
            updateStartBtn();
        });
        updateStartBtn();
    }

    @Override
    public void onResume() {
        super.onResume();

        owner.invalidateOptionsMenu();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    Integer getMenuGroupResId() {
        return R.id.group_dev_info;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView devices = view.findViewById(R.id.known_devices);
        devices.setAdapter(devicesAdapter);
        devicesAdapter.notifyDataSetChanged();

        RecyclerView sessions = view.findViewById(R.id.sessions);
        sessions.setAdapter(sessionsAdapter);

        startBtn = view.findViewById(R.id.start);
        title = view.findViewById(R.id.title_devices);
    }
}
