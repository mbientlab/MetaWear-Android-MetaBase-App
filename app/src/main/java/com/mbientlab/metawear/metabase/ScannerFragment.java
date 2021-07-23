package com.mbientlab.metawear.metabase;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.textfield.TextInputLayout;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.impl.JseMetaWearBoard;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Led;

import java.io.UnsupportedEncodingException;

import bolts.Capture;
import bolts.Task;
import bolts.TaskCompletionSource;


/**
 * A simple {@link Fragment} subclass.
 */
public class ScannerFragment extends AppFragmentBase {
    interface EventBus {
        void deviceAdded(MetaBaseDevice device);
    }

    private MetaBaseDevice.Adapter devicesAdapter;
    private EventBus eventBus;

    public ScannerFragment() {
        // Required empty public constructor
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Activity activity= getActivity();
        if (!(activity instanceof EventBus)) {
            throw new ClassCastException("Owning activity does not implement ScannerFragment.EventBus interface");
        }
        eventBus = (EventBus) activity;

        activityBus.scanner().start(device -> {
            if (!AppState.devices.containsKey(device.mac)) {
                owner.runOnUiThread(() -> devicesAdapter.add(device));
            }
        });
    }

    @Override
    Integer getMenuGroupResId() {
        return null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        devicesAdapter = new MetaBaseDevice.Adapter();

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_scanner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        final RecyclerView devices = view.findViewById(R.id.new_devices);
        devices.setAdapter(devicesAdapter);

        devicesAdapter.itemClicked = arg1 -> {
            arg1.isSelected = false;

            activityBus.scanner().stop();

            final MetaWearBoard metawear = activityBus.getMetaWearBoard(arg1.btDevice);

            final AlertDialog connDialog = new AlertDialog.Builder(owner)
                    .setTitle(R.string.title_connecting)
                    .setView(R.layout.indeterminate_task)
                    .setCancelable(false)
                    .create();
            connDialog.show();
            ((TextView) connDialog.findViewById(R.id.message)).setText(R.string.message_connecting);

            Capture<Boolean> terminate = new Capture<>(false);
            Task.forResult(null).continueWhile(() -> !terminate.get(), ignored -> metawear.connectAsync().continueWithTask(connTask -> {
                if (connTask.isFaulted() || connTask.isCancelled()) {
                    TaskCompletionSource<Void> retryTaskSource = new TaskCompletionSource<>();

                    owner.runOnUiThread(() -> {
                        connDialog.dismiss();
                        new AlertDialog.Builder(owner)
                                .setTitle(R.string.title_error)
                                .setMessage(R.string.message_connect_retry)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    connDialog.show();
                                    retryTaskSource.setResult(null);
                                })
                                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                                    terminate.set(true);
                                    retryTaskSource.setCancelled();
                                })
                                .create()
                                .show();
                    });

                    return retryTaskSource.getTask();
                } else {
                    terminate.set(true);
                    return connTask;
                }
            })).continueWith(task -> {
                if (!task.isFaulted() && !task.isCancelled()) {
                    final Led led = metawear.getModule(Led.class);
                    if (led != null) {
                        led.editPattern(Led.Color.GREEN, Led.PatternPreset.BLINK)
                                .repeatCount(Led.PATTERN_REPEAT_INDEFINITELY)
                                .commit();
                        led.play();
                    }

                    final Capture<EditText> userDeviceName = new Capture<>();
                    final Capture<TextInputLayout> deviceNameTextWrapper = new Capture<>();

                    owner.runOnUiThread(() -> {
                        connDialog.dismiss();
                        final AlertDialog dialog = new AlertDialog.Builder(owner)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNegativeButton(android.R.string.cancel, (dialog1, which) -> {
                                    if (led != null) {
                                        led.stop(true);
                                    }
                                    metawear.getModule(Debug.class).disconnectAsync();

                                    activityBus.scanner().start();
                                })
                                .setCancelable(false)
                                .setTitle(R.string.title_device_name)
                                .setView(R.layout.dialog_item_naming)
                                .create();
                        dialog.show();
                        ((TextView) dialog.findViewById(R.id.instructions_text)).setText(R.string.instruction_name_device);

                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                            String customName = userDeviceName.get().getText().toString();
                            byte[] raw;
                            try {
                                raw = customName.getBytes("ASCII");
                                if (raw.length == 0) {
                                    deviceNameTextWrapper.get().setError(owner.getString(R.string.error_empty_name));
                                } else if (raw.length > Global.nameMaxChar) {
                                    deviceNameTextWrapper.get().setError(owner.getString(R.string.error_max_characters, Global.nameMaxChar));
                                } else if (!customName.matches("^[A-Za-z0-9\\- ]+")) {
                                    deviceNameTextWrapper.get().setError(owner.getString(R.string.error_empty_alphanum));
                                } else {
                                    deviceNameTextWrapper.get().setError(null);

                                    arg1.name = customName;

                                    if (led != null) {
                                        led.stop(true);
                                    }
                                    metawear.getModule(Debug.class).disconnectAsync();

                                    dialog.dismiss();

                                    JseMetaWearBoard casted = (JseMetaWearBoard) metawear;
                                    arg1.modelName = casted.getModelString();
                                    arg1.modelNumber = casted.getModelNumber();
                                    eventBus.deviceAdded(arg1);
                                }
                            } catch (UnsupportedEncodingException e) {
                                deviceNameTextWrapper.get().setError(owner.getString(R.string.error_max_characters, Global.nameMaxChar));
                            }
                        });

                        deviceNameTextWrapper.set(dialog.findViewById(R.id.item_name_wrapper));
                        userDeviceName.set(dialog.findViewById(R.id.item_name));
                        userDeviceName.get().setText(arg1.name);
                    });
                } else {
                    owner.runOnUiThread(connDialog::dismiss);
                    activityBus.scanner().start();
                }
                return null;
            });
        };
    }
}
