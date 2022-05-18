/*
 * Copyright 2015 MbientLab Inc. All rights reserved.
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

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.os.Bundle;
import com.google.android.material.textfield.TextInputLayout;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import bolts.Capture;
import bolts.Task;
import bolts.TaskCompletionSource;

import static com.mbientlab.metawear.metabase.HomeActivity.SHOW_TUTORIAL;
import static com.mbientlab.metawear.metabase.TutorialActivity.EXTRA_DEVICE;

/**
 * A placeholder fragment containing a simple view.
 */
public class HomeActivityFragment extends AppFragmentBase {
    static final int PERMISSION_REQUEST_EXT_STORAGE_WRITE = 0, PERMISSION_REQUEST_COARSE_LOCATION= 2,
            PERMISSION_REQUEST_FINE_LOCATION= 3, PERMISSION_REQUEST_BLUETOOTH= 4;
    private static final int TUTORIAL= 1;

    abstract class SwipeToDelete extends ItemTouchHelper.SimpleCallback {
        private final Drawable deleteIcon;
        private final Paint clearPaint;
        private final ColorDrawable background;

        SwipeToDelete(Context ctx) {
            super(0, ItemTouchHelper.LEFT);

            clearPaint = new Paint();
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            background = new ColorDrawable();
            background.setColor(Color.parseColor("#f44336"));

            deleteIcon = ContextCompat.getDrawable(ctx, android.R.drawable.ic_menu_delete);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            return false;
        }

        // Adapted from https://medium.com/@kitek/recyclerview-swipe-to-delete-easier-than-you-thought-cff67ff5e5f6
        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            int height = viewHolder.itemView.getBottom() - viewHolder.itemView.getTop();

            if (dX == 0f && isCurrentlyActive) {
                clearCanvas(c,
                        viewHolder.itemView.getRight() + dX, viewHolder.itemView.getTop(),
                        viewHolder.itemView.getRight(), viewHolder.itemView.getBottom());
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

                return;
            }

            background.setBounds((int) (viewHolder.itemView.getRight() + dX), viewHolder.itemView.getTop(),
                    viewHolder.itemView.getRight(), viewHolder.itemView.getBottom());
            background.draw(c);

            if (-dX > deleteIcon.getIntrinsicWidth()) {
                // Calculate position of delete icon
                int deleteIconTop = viewHolder.itemView.getTop() + (height - deleteIcon.getIntrinsicHeight()) / 2;
                int deleteIconMargin = (height - deleteIcon.getIntrinsicHeight()) / 2;
                int deleteIconLeft = viewHolder.itemView.getRight() - deleteIconMargin - deleteIcon.getIntrinsicWidth();
                int deleteIconRight = viewHolder.itemView.getRight() - deleteIconMargin;
                int deleteIconBottom = deleteIconTop + deleteIcon.getIntrinsicHeight();

                // Draw the delete icon
                deleteIcon.setBounds(deleteIconLeft, deleteIconTop, deleteIconRight, deleteIconBottom);
                deleteIcon.draw(c);
            }

            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }

        private void clearCanvas(Canvas c, float left, float top, float right, float bottom) {
            c.drawRect(left, top, right, bottom, clearPaint);
        }
    }
    class SwipeToDeleteDevice extends SwipeToDelete {
        SwipeToDeleteDevice(Context ctx) {
            super(ctx);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            MetaBaseDevice selected = devicesAdapter.get(viewHolder.getAdapterPosition());
            AppState.devices.remove(selected.mac);
            devicesAdapter.remove(viewHolder.getAdapterPosition());

            File current = new File(AppState.devicesPath, selected.getFileFriendlyMac());
            String[] scanPaths = new String[current.listFiles().length * 2 + 1];
            int i = 0;
            for(File f: current.listFiles()) {
                scanPaths[i] = f.getAbsolutePath();
                i++;
            }

            File oldDevice = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                oldDevice = new File(AppState.oldDevicesPath, selected.getFileFriendlyMac());
            } else {
                oldDevice = new File(AppState.oldDevicesPath, selected.getFileFriendlyMac());
            }
            if (!current.renameTo(oldDevice)) {
                for (File f : oldDevice.listFiles()) {
                    scanPaths[i] = f.getAbsolutePath();
                    i++;
                }

                scanPaths[i] = current.getAbsolutePath();

                MediaScannerConnection.scanFile(owner, scanPaths, null, null);
            } else {
                String msg = "Failed to move device to \'old_devices\' folder";
                Log.w("metabase", msg);
            }
        }
    }
    class SwipeToDeleteGroup extends SwipeToDelete {
        SwipeToDeleteGroup(Context ctx) {
            super(ctx);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            AppState.Group selected = groupsAdapter.items.get(viewHolder.getAdapterPosition());
            groupsAdapter.items.remove(viewHolder.getAdapterPosition());
            groupsAdapter.notifyDataSetChanged();

            File group = new File(AppState.groupsPath, selected.name);
            AppState.groups.remove(selected.name);
            String[] paths = new String[selected.devices.size() + 1];
            int i = 0;
            boolean aggregate = true;

            for(MetaBaseDevice d: selected.devices.values()) {
                devicesAdapter.add(d);
                File device = new File(group, d.getFileFriendlyMac());
                paths[i] = device.getAbsolutePath();
                aggregate &= device.delete();

                i++;
            }
            paths[i] = group.getAbsolutePath();
            aggregate &= group.delete();

            if (aggregate) {
                MediaScannerConnection.scanFile(owner, paths, null, null);
            } else {
                String msg = "Failed to delete group folder [" + selected.name + "]";
                Log.w("metabase", msg);
            }
        }
    }

    private MetaBaseDevice.Adapter devicesAdapter;
    private ItemTouchHelper swipeHelper, groupSwipeHelper;
    private AppState.Group.Adapter groupsAdapter;

    public HomeActivityFragment() {
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
            case TUTORIAL:
                if (data != null && data.hasExtra(EXTRA_DEVICE)) {
                    activityBus.removeMetaWearBoard(data.getParcelableExtra(EXTRA_DEVICE));
                }
                owner.getPreferences(Context.MODE_PRIVATE).edit()
                        .putBoolean(SHOW_TUTORIAL, false)
                        .apply();
                activityBus.scanner().start();
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    void toggleDeviceSelection() {
        devicesAdapter.toggleSelectionMode();

        owner.invalidateOptionsMenu();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        devicesAdapter = new MetaBaseDevice.Adapter();
        devicesAdapter.itemClicked = arg1 -> {
            if (devicesAdapter.selectionMode == AdapterSelectionMode.SINGLE) {
                SelectedGrouping grouping = new SelectedGrouping(arg1.sessions, arg1.name);
                grouping.devices.add(arg1);
                activityBus.swapFragment(DeviceInfoFragment.class, grouping);
            }
        };

        groupsAdapter = new AppState.Group.Adapter();
        groupsAdapter.itemSelected = arg1 -> {
            SelectedGrouping grouping = new SelectedGrouping(arg1.sessions, arg1.name);
            grouping.devices.addAll(arg1.devices.values());
            activityBus.swapFragment(DeviceInfoFragment.class, grouping);
        };

        setHasOptionsMenu(true);
    }

    private void createDirectory(File dir) {
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                String msg = "Failed to create directory [" + dir.getAbsolutePath() + "]";
                Log.e("metabase", msg);
                owner.finish();
            }
        }
    }
    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (checkPermissionTask == null) {
            permissionTaskSource = new TaskCompletionSource<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (owner.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_REQUEST_BLUETOOTH);
                } else {
                    permissionTaskSource.setResult(null);
                }
                checkPermissionTask = permissionTaskSource.getTask();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                checkPermissionTask = checkRuntimePermission(android.Manifest.permission.ACCESS_FINE_LOCATION, R.string.message_location_permission,
                        dialog -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_FINE_LOCATION)
                );
            } else {
                checkPermissionTask = checkRuntimePermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, R.string.message_ext_storage_permission,
                        dialog -> requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_EXT_STORAGE_WRITE)
                ).onSuccessTask(ignored -> {
                    permissionTaskSource = new TaskCompletionSource<>();
                    return checkRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION, R.string.message_location_permission,
                            dialog -> requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION)
                    );
                });
            }
        }
        checkPermissionTask.continueWith(task -> {
            if (task.isFaulted()) {
                Log.e("metabase", "Permission request denied, terminating app", task.getError());
                owner.finish();
            } else {
                activityBus.scanner().start(device -> owner.runOnUiThread(() -> {
                    if (AppState.devices.containsKey(device.mac)) {
                        devicesAdapter.update(device);
                    }
                }));

                File root = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    root = owner.getExternalFilesDir(null);
                } else {
                    root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), owner.getPackageName());
                }
                createDirectory(root);

                if (AppState.devicesPath == null) {
                    AppState.devicesPath = new File(root ,"devices");
                    createDirectory(AppState.devicesPath);

                    for(File folder: AppState.devicesPath.listFiles()) {
                        String mac = folder.getName().substring(0, 2) + ':' +
                                folder.getName().substring(2, 4) + ':' +
                                folder.getName().substring(4, 6) + ':' +
                                folder.getName().substring(6, 8) + ':' +
                                folder.getName().substring(8, 10) + ':' +
                                folder.getName().substring(10, 12);
                        String name, modelName = null, modelNumber = null;

                        File[] result = folder.listFiles(pathname -> pathname.getName().equals("info"));
                        if (result.length == 1) {
                            try (BufferedReader br = new BufferedReader(new FileReader(result[0]))) {
                                JSONObject json = new JSONObject(br.readLine());
                                if (json.has("model-name")) {
                                    modelName = json.getString("model-name");
                                }
                                modelNumber = json.getString("model-number");
                                name = json.getString("name");
                            } catch (IOException ignored) {
                                name = "MetaWear";
                            }
                        } else if (result.length == 0) {
                            File nameFile = new File(folder, "name");
                            try (BufferedReader br = new BufferedReader(new FileReader(nameFile))) {
                                name = br.readLine();
                            } catch (IOException ignored) {
                                name = "MetaWear";
                            }
                        } else {
                            name = "MetaWear";
                        }

                        MetaBaseDevice device = new MetaBaseDevice(name, mac, createSession(folder));
                        device.modelNumber = modelNumber;
                        device.modelName = modelName;
                        AppState.devices.put(mac, device);
                    }
                }
                devicesAdapter.items.clear();
                devicesAdapter.items.addAll(AppState.devices.values());
                devicesAdapter.notifyDataSetChanged();

                if (AppState.groupsPath == null) {
                    AppState.groupsPath = new File(root, "groups");
                    createDirectory(AppState.groupsPath);

                    for(File folder: AppState.groupsPath.listFiles()) {
                        addGroup(folder);
                    }
                } else {
                    for(AppState.Group it: AppState.groups.values()) {
                        devicesAdapter.items.removeAll(it.devices.values());
                    }
                    devicesAdapter.notifyDataSetChanged();
                }
                groupsAdapter.items.clear();
                groupsAdapter.items.addAll(AppState.groups.values());
                groupsAdapter.notifyDataSetChanged();

                if (AppState.oldDevicesPath == null) {
                    AppState.oldDevicesPath = new File(root, "old_devices");
                    createDirectory(AppState.oldDevicesPath);
                }

                if (owner.getPreferences(Context.MODE_PRIVATE).getBoolean(SHOW_TUTORIAL, true)) {
                    activityBus.scanner().stop();
                    startActivityForResult(new Intent(owner, TutorialActivity.class), TUTORIAL);
                    return null;
                }
            }
            return null;
        });
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        boolean visible = devicesAdapter.selectionMode == AdapterSelectionMode.MULTIPLE;
        menu.findItem(R.id.action_create_group).setVisible(!visible);
        menu.findItem(R.id.action_create_device).setVisible(!visible);
        menu.findItem(R.id.action_create_group_ok).setVisible(visible);
        menu.findItem(R.id.action_create_group_cancel).setVisible(visible);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_create_device:
                activityBus.swapFragment(ScannerFragment.class);
                return true;
            case R.id.action_create_group:
            case R.id.action_create_group_cancel:
                toggleDeviceSelection();
                return true;
            case R.id.action_create_group_ok:
                final Capture<EditText> userGroupName = new Capture<>();
                final Capture<TextInputLayout> userGroupNameTextWrapper = new Capture<>();
                final List<MetaBaseDevice> selected = new ArrayList<>();

                for(MetaBaseDevice d: devicesAdapter.items) {
                    if (d.isSelected) {
                        selected.add(d);
                    }
                }

                if (selected.size() < 2) {
                    new AlertDialog.Builder(owner)
                            .setTitle(R.string.title_error)
                            .setMessage(R.string.error_no_devices)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                            .show();
                } else {
                    final AlertDialog dialog = new AlertDialog.Builder(owner)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setCancelable(false)
                            .setTitle(R.string.title_group_name)
                            .setView(R.layout.dialog_item_naming)
                            .create();
                    dialog.show();

                    userGroupName.set(dialog.findViewById(R.id.item_name));
                    userGroupNameTextWrapper.set(dialog.findViewById(R.id.item_name_wrapper));
                    ((TextView) dialog.findViewById(R.id.instructions_text)).setText(R.string.instruction_name_group);
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                        String customName = userGroupName.get().getText().toString();
                        if (customName.isEmpty()) {
                            userGroupNameTextWrapper.get().setError(owner.getString(R.string.error_empty_name));
                        } else {
                            devicesAdapter.items.removeAll(selected);
                            devicesAdapter.notifyDataSetChanged();

                            File group = new File(AppState.groupsPath, customName);
                            createDirectory(group);

                            String[] files = new String[selected.size() + 1];
                            int i = 0;
                            for (MetaBaseDevice d : selected) {
                                File file = new File(group, d.getFileFriendlyMac());
                                try {
                                    if (!file.createNewFile()) {
                                        String msg = "Failed to create file to mark device [" + d.getFileFriendlyMac() + "]";
                                        Log.e("metabase", msg);
                                        owner.finish();
                                    }
                                    files[i] = file.getAbsolutePath();
                                    i++;
                                } catch (IOException ex) {
                                    Log.e("metabase", "Failed to create file to mark device [" + d.getFileFriendlyMac() + "]", ex);
                                    owner.finish();
                                }
                            }
                            files[i] = group.getAbsolutePath();

                            MediaScannerConnection.scanFile(owner, files, null, null);

                            toggleDeviceSelection();
                            dialog.dismiss();

                            groupsAdapter.add(addGroup(group));
                            groupsAdapter.notifyDataSetChanged();
                            devicesAdapter.notifyDataSetChanged();
                        }
                    });
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    Integer getMenuGroupResId() {
        return R.id.group_home;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    private List<AppState.Session> createSession(File folder) {
        Map<String, AppState.Session> sessions = new HashMap<>();
        for(File file: folder.listFiles(pathname -> !pathname.getName().startsWith("name") && !pathname.getName().startsWith("info"))) {
            String[] parts = file.getName().split("_");
            if (!parts[1].equals(DataDownloadFragment.TEMP_CSV_TIME)) {
                if (!sessions.containsKey(parts[0])) {
                    AppState.Session newSession = new AppState.Session(parts[0], parts[2]);
                    newSession.files.add(file);

                    sessions.put(parts[0], newSession);
                } else {
                    sessions.get(parts[0]).files.add(file);
                }
            }
        }

        List<AppState.Session> sessionsList = new ArrayList<>(sessions.values());
        Collections.sort(sessionsList, (o1, o2) -> o2.time.compareTo(o1.time));
        return sessionsList;
    }

    private AppState.Group addGroup(File folder) {
        Map<String, MetaBaseDevice> devices = new HashMap<>();
        Map<Pair<String, String>, List<File>> sessions = new HashMap<>();
        Map<Pair<String, String>, Integer> hits = new HashMap<>();

        for(File d: folder.listFiles()) {
            String mac = d.getName().substring(0, 2) + ':' +
                    d.getName().substring(2, 4) + ':' +
                    d.getName().substring(4, 6) + ':' +
                    d.getName().substring(6, 8) + ':' +
                    d.getName().substring(8, 10) + ':' +
                    d.getName().substring(10, 12);
            MetaBaseDevice device = AppState.devices.get(mac);

            device.isSelected = false;
            devicesAdapter.remove(device);
            devices.put(device.mac, device);

            for(AppState.Session s: device.sessions) {
                Pair<String, String> key = new Pair<>(s.name, s.time);
                if (sessions.containsKey(key)) {
                    sessions.get(key).addAll(s.files);
                    hits.put(key, hits.get(key) + 1);
                } else {
                    sessions.put(key, new ArrayList<>(s.files));
                    hits.put(key, 1);
                }
            }
        }

        AppState.Group newGroup = new AppState.Group(folder.getName(), devices);
        for(Map.Entry<Pair<String, String>, List<File>> s: sessions.entrySet()) {
            if (hits.get(s.getKey()) > 1) {
                newGroup.sessions.add(new AppState.Session(s.getKey().first, s.getKey().second, s.getValue()));
            }
        }
        Collections.sort(newGroup.sessions, (o1, o2) -> o2.time.compareTo(o1.time));
        AppState.groups.put(newGroup.name, newGroup);

        return newGroup;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView devices = view.findViewById(R.id.known_devices);
        devices.setAdapter(devicesAdapter);
        swipeHelper = new ItemTouchHelper(new SwipeToDeleteDevice(getContext()));
        swipeHelper.attachToRecyclerView(devices);

        RecyclerView groups = view.findViewById(R.id.known_groups);
        groups.setAdapter(groupsAdapter);
        groupSwipeHelper = new ItemTouchHelper(new SwipeToDeleteGroup(getContext()));
        groupSwipeHelper.attachToRecyclerView(groups);
    }
    private TaskCompletionSource<Void> permissionTaskSource = null;
    private Task<Void> checkPermissionTask;

    @TargetApi(Build.VERSION_CODES.M)
    private Task<Void> checkRuntimePermission(final String permissionId, final int msgResId, final DialogInterface.OnDismissListener dismissListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && owner.checkSelfPermission(permissionId) != PackageManager.PERMISSION_GRANTED) {
            // Android M Permission check
            new AlertDialog.Builder(owner).setTitle(R.string.title_request_permission)
                    .setMessage(msgResId)
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener(dismissListener)
                    .show();
        } else {
            permissionTaskSource.setResult(null);
        }
        return permissionTaskSource.getTask();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch(requestCode) {
            case PERMISSION_REQUEST_EXT_STORAGE_WRITE:
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    permissionTaskSource.setError(new RuntimeException("External file-write permission denied"));
                } else {
                    permissionTaskSource.setResult(null);
                }
                break;
            case PERMISSION_REQUEST_COARSE_LOCATION:
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    permissionTaskSource.setError(new RuntimeException("Coarse location permission denied"));
                } else {
                    permissionTaskSource.setResult(null);
                }
                break;
            case PERMISSION_REQUEST_FINE_LOCATION:
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    permissionTaskSource.setError(new RuntimeException("Fine location permission denied"));
                } else {
                    permissionTaskSource.setResult(null);
                }
                break;
            case PERMISSION_REQUEST_BLUETOOTH:
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    permissionTaskSource.setError(new RuntimeException("Bluetooth permission denied"));
                } else {
                    permissionTaskSource.setResult(null);
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                break;
        }
    }

    void deviceAdded(MetaBaseDevice newDevice) {
        AppState.devices.put(newDevice.mac, newDevice);

        List<String> scanPaths = new ArrayList<>();
        File[] existing = AppState.oldDevicesPath.listFiles(pathname -> pathname.getName().equals(newDevice.getFileFriendlyMac()));
        File folder = new File(AppState.devicesPath, newDevice.getFileFriendlyMac());

        if (existing.length > 0) {
            scanPaths.add(existing[0].getAbsolutePath());
            for(File f: existing[0].listFiles()) {
                scanPaths.add(f.getAbsolutePath());
            }
            if (existing[0].renameTo(folder)) {
                for (File f : folder.listFiles()) {
                    scanPaths.add(f.getAbsolutePath());
                }
            } else {
                String msg = "Failed to restore device from \'old_devices\' folder [" + newDevice.mac + "]";
                Log.e("metabase", msg);
                owner.finish();
            }
        } else {
            if (!folder.mkdir()) {
                String msg = "Failed to create folder for device [" + newDevice.mac + "]";
                Log.e("metabase", msg);
                owner.finish();
            }
        }

        File name = new File(folder, "info");
        try(FileOutputStream fos = new FileOutputStream(name)) {
            Map<String, String> deviceInfo = new HashMap<>();
            if (newDevice.modelName != null) {
                deviceInfo.put("model-name", newDevice.modelName);
            }
            deviceInfo.put("model-number", newDevice.modelNumber);
            deviceInfo.put("name", newDevice.name);

            fos.write(new JSONObject(deviceInfo).toString().getBytes());
        } catch (IOException ignored) {
        } finally {
            if (existing.length <= 0) {
                scanPaths.add(name.getAbsolutePath());
            }
        }

        String[] scanPathsArray = new String[scanPaths.size()];
        scanPaths.toArray(scanPathsArray);
        MediaScannerConnection.scanFile(owner, scanPathsArray, null, null);

        newDevice.sessions.addAll(createSession(folder));
        devicesAdapter.add(newDevice);
    }
}
