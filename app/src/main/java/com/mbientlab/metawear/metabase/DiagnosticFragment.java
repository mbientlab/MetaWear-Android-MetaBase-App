package com.mbientlab.metawear.metabase;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.List;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.os.EnvironmentCompat;
import androidx.fragment.app.DialogFragment;
import android.os.Bundle;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.appcompat.app.AlertDialog;
import android.util.JsonWriter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.TaskTimeoutException;
import com.mbientlab.metawear.impl.JseMetaWearBoard;
import com.mbientlab.metawear.impl.platform.TimedTask;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Led;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import bolts.Capture;
import bolts.Continuation;
import bolts.Task;
import bolts.TaskCompletionSource;

import static android.app.Activity.RESULT_OK;
import static com.mbientlab.metawear.metabase.Global.FIREBASE_PARAM_MAC;

/**
 * A placeholder fragment containing a simple view.
 */
public class DiagnosticFragment extends AppFragmentBase implements LoaderManager.LoaderCallbacks<Cursor> {
    private static final int SELECT_FILE_REQ = 1;
    private static final String METABOOT_WARNING_TAG= "metaboot_warning_tag",
            EXTRA_URI = "com.mbientlab.metawear.metabase.DiagnosticFragment.EXTRA_URI",
            DIAGNOSTIC_DIALOG_TAG = "com.mbientlab.metawear.metabase.DiagnosticFragment.DIAGNOSTIC_DIALOG_TAG",
            FIREBASE_EVENT_DIAGNOSTIC = "run_diagnostic";

    public static class MetaBootWarningFragment extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(getActivity()).setTitle(R.string.title_warning)
                    .setPositiveButton(android.R.string.ok, null)
                    .setCancelable(false)
                    .setMessage(R.string.message_metaboot)
                    .create();
        }
    }

    private DfuWrapper dfu;
    private Uri fileStreamUri;
    private MetaBaseDevice parameter;
    private final ArrayList<TextView> entries = new ArrayList<>();
    private String[] names;
    private MetaWearBoard metawear;
    private Button runDiagnostic;

    public DiagnosticFragment() {
    }

    private void dfuCompleted(Task<Void> dfuTask) {
        dfuTask.continueWithTask(task -> {
            TaskCompletionSource<Void> taskCompletionSource = new TaskCompletionSource<>();
            if (task.isFaulted()) {
                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_error)
                        .setMessage(owner.getString(R.string.error_dfu_failed, task.getError().getLocalizedMessage()))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> taskCompletionSource.setResult(null))
                        .create()
                        .show();
            } else if (task.isCancelled()) {
                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_error)
                        .setMessage(R.string.error_dfu_cancelled)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> taskCompletionSource.setResult(null))
                        .create()
                        .show();
            } else {
                taskCompletionSource.setResult(null);
            }
            return taskCompletionSource.getTask();
        }).onSuccessTask(ignored ->
            activityBus.reconnect(metawear, 5000L, 3)
        ).continueWith(task -> {
            if (task.isCancelled() || task.isFaulted()) {
                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_error)
                        .setMessage(owner.getString(R.string.message_reconnect_fail, 3))
                        .setPositiveButton(android.R.string.ok, ((dialog, which) -> {
                            metawear.onUnexpectedDisconnect(null);
                            parameter.isDiscovered = false;
                            activityBus.navigateBack();
                        }))
                        .setCancelable(false)
                        .create()
                        .show();
            } else {
                if (metawear.inMetaBootMode()) {
                    if (owner.getSupportFragmentManager().findFragmentByTag(METABOOT_WARNING_TAG) == null) {
                        new MetaBootWarningFragment().show(owner.getSupportFragmentManager(), METABOOT_WARNING_TAG);
                    }
                    runDiagnostic.setAlpha(.5f);
                    runDiagnostic.setEnabled(false);
                } else {
                    runDiagnostic.setAlpha(1f);
                    runDiagnostic.setEnabled(true);
                }
                entries.get(3).setText(((JseMetaWearBoard) metawear).getFirmware());
            }

            return null;
        }, Task.UI_THREAD_EXECUTOR);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_diagnostic, container, false);

        Resources res = getResources();
        names = res.getStringArray(R.array.values_diagnostic);

        entries.clear();
        LinearLayout metrics= view.findViewById(R.id.metrics);
        // using code from: http://stackoverflow.com/questions/5183968/how-to-add-rows-dynamically-into-table-layout
        for(String it: names) {
            View status = inflater.inflate(R.layout.diagnostic_entry, null);
            ((TextView) status.findViewById(R.id.entry_key)).setText(it);
            entries.add(status.findViewById(R.id.entry_value));

            metrics.addView(status);
        }

        runDiagnostic = view.findViewById(R.id.run_diagnostic);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.update_firmware).setOnClickListener(v -> dfuCompleted(dfu.start(metawear, parameter.btDevice.getName(), null, null)));
        view.findViewById(R.id.run_diagnostic).setOnClickListener(v -> {
            final Capture<Boolean> terminate = new Capture<>(false);
            final Capture<JSONObject> partial = new Capture<>(null);
            Task.forResult(null).continueWhile(() -> !terminate.get(), ignored ->
                metawear.dumpModuleInfo(partial.get()).continueWithTask(task -> {
                    if (task.isFaulted()) {
                        TaskTimeoutException exception = (TaskTimeoutException) task.getError();
                        partial.set((JSONObject) exception.partial);
                    } else if (!task.isCancelled()) {
                        partial.set(task.getResult());
                        terminate.set(true);
                    }
                    return Task.forResult(null);
                })
            ).onSuccessTask(task -> {
                Map<String, String> attributes = new LinkedHashMap<>();

                attributes.put("App", owner.getString(R.string.app_name));
                attributes.put("App Revision", BuildConfig.VERSION_NAME);
                attributes.put("Host Device", getDeviceName());
                attributes.put("OS", "Android");
                attributes.put("OS Version", Build.VERSION.RELEASE);

                for(int i = 0; i < names.length; i++) {
                    attributes.put(names[i], entries.get(i).getText().toString());
                }

                JSONObject result = partial.get();

                File root = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    root = owner.getExternalFilesDir(null);
                } else {
                    root = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), owner.getPackageName()), "diagnostic");
                }

                File realpath = new File(root, String.format(Locale.US, "diagnostic_%s_Android.json", metawear.getMacAddress().replaceAll(":", "")));
                if (!root.exists()) {
                    root.mkdirs();
                }
                realpath.setReadable(true, true);

                FileOutputStream fos = new FileOutputStream(realpath);
                JsonWriter writer = new JsonWriter(new OutputStreamWriter(fos));
                writer.setIndent("  ");

                writer.beginObject();
                {
                    for (Map.Entry<String, String> it : attributes.entrySet()) {
                        writer.name(it.getKey()).value(it.getValue());
                    }

                    Set<String> modules = new TreeSet<>();
                    {
                        Iterator<String> it = result.keys();
                        while (it.hasNext()) {
                            modules.add(it.next());
                        }
                    }

                    final String[] moduleAttr = new String[]{
                            "implementation", "revision", "extra"
                    };
                    writer.name("Modules");
                    writer.beginObject();
                    {
                        for (String it : modules) {
                            writer.name(it).beginObject();

                            {
                                JSONObject module = result.optJSONObject(it);
                                for (String attr : moduleAttr) {
                                    if (module.has(attr)) {
                                        writer.name(attr);

                                        Object value = module.opt(attr);
                                        if (value instanceof Byte) {
                                            writer.value((Byte) value);
                                        } else {
                                            writer.value((String) value);
                                        }
                                    }

                                }
                            }

                            writer.endObject();
                        }
                    }

                    writer.endObject();
                }
                writer.endObject();
                writer.close();

                MediaScannerConnection.scanFile(owner, new String[] {realpath.toString()}, null, null);

                Uri path = FileProvider.getUriForFile(owner, "com.mbientlab.metawear.metabase.fileprovider", realpath);

                final Intent intent = new Intent(Intent.ACTION_SEND);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intent.setType("text/plain");
                } else {
                    intent.setType("vnd.android.cursor.item/email");
                }
                intent.putExtra(Intent.EXTRA_EMAIL, new String[] {"hello@mbientlab.com"});
                intent.putExtra(Intent.EXTRA_SUBJECT, String.format(Locale.US, "[MbientLab Diagnostic] MetaSensor %s", metawear.getMacAddress()));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    intent.putExtra(Intent.EXTRA_STREAM, path);
                    intent.setData(path);
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(realpath));
                }
                intent.putExtra(Intent.EXTRA_TEXT, "<Add additional information here>");
                startActivity(Intent.createChooser(intent, "Emailing diagnostics to MbientLab..."));
                return Task.forResult(null);
            }).continueWith((Continuation<Object, Void>) task -> {
                ((DialogFragment) owner.getSupportFragmentManager().findFragmentByTag(DIAGNOSTIC_DIALOG_TAG)).dismiss();
                if (task.isFaulted()) {
                    Log.w("metabase", "Read diagnostic task failed", task.getError());
                    new androidx.appcompat.app.AlertDialog.Builder(owner).setTitle(R.string.title_error)
                            .setMessage(String.format(Locale.US, "Error running diagnostics (msg=%s)", task.getError().getLocalizedMessage()))
                            .setCancelable(false)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else if (BuildConfig.LOG_EVENT) {
                    Bundle bundle = new Bundle();
                    bundle.putString(FIREBASE_PARAM_MAC, metawear.getMacAddress());
                }
                return null;
            }, Task.UI_THREAD_EXECUTOR);
        });
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        parameter = (MetaBaseDevice) activityBus.parameter();

        dfu = new DfuWrapper(getActivity(), getFragmentManager());

        activityBus.onBackPressed((ignored) -> {
            parameter.isDiscovered = false;

            if (metawear != null) {
                metawear.disconnectAsync();
            }
        });

        final AlertDialog connDialog = new AlertDialog.Builder(owner)
                .setTitle(R.string.title_connecting)
                .setView(R.layout.indeterminate_task)
                .setCancelable(false)
                .create();
        connDialog.show();
        ((TextView) connDialog.findViewById(R.id.message)).setText(R.string.message_read_info);

        TimedTask<BluetoothDevice> deviceFinder = new TimedTask<>();
        deviceFinder.execute("Did not find device after scanning for %dms", 10000, () -> {
            if (parameter.btDevice == null) {
                final BtleScanner scanner = BtleScanner.getScanner(((BluetoothManager) owner.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(), new UUID[] {
                        MetaWearBoard.METAWEAR_GATT_SERVICE,
                        MetaWearBoard.METABOOT_SERVICE
                });
                scanner.start(args1 -> {
                    if (args1.mac.equals(parameter.mac)) {
                        scanner.stop();
                        deviceFinder.setResult(args1.btDevice);
                    }
                });
            } else {
                deviceFinder.setResult(parameter.btDevice);
            }
        }).onSuccessTask(task -> {
            parameter.btDevice = task.getResult();
            metawear = activityBus.getMetaWearBoard(task.getResult());
            metawear.onUnexpectedDisconnect(status -> {
                final DialogFragment metabootWarning= (DialogFragment) owner.getSupportFragmentManager().findFragmentByTag(METABOOT_WARNING_TAG);
                if (metabootWarning != null) {
                    owner.runOnUiThread(metabootWarning::dismiss);
                }

                activityBus.reconnect(metawear, 3).continueWith(task2 -> {
                    if (task2.isFaulted()) {
                        new AlertDialog.Builder(owner)
                                .setTitle(R.string.title_error)
                                .setMessage(owner.getString(R.string.message_reconnect_fail, 3))
                                .setPositiveButton(android.R.string.ok, ((dialog, which) -> {
                                    metawear.onUnexpectedDisconnect(null);
                                    parameter.isDiscovered = false;
                                    activityBus.navigateBack();
                                }))
                                .setCancelable(false)
                                .create()
                                .show();
                    } else {
                        owner.invalidateOptionsMenu();
                        if (metawear.inMetaBootMode()) {
                            owner.runOnUiThread(() -> {
                                connDialog.dismiss();
                                if (owner.getSupportFragmentManager().findFragmentByTag(METABOOT_WARNING_TAG) == null) {
                                    new MetaBootWarningFragment().show(owner.getSupportFragmentManager(), METABOOT_WARNING_TAG);
                                }
                                runDiagnostic.setAlpha(.5f);
                                runDiagnostic.setEnabled(false);
                            });
                        } else {
                            owner.runOnUiThread(() -> {
                                connDialog.dismiss();
                                runDiagnostic.setAlpha(1f);
                                runDiagnostic.setEnabled(true);
                            });
                        }
                    }
                    return null;
                }, Task.UI_THREAD_EXECUTOR);
            });

            return metawear.connectAsync();
        }).continueWith(task -> {
            if (task.isFaulted()) {
                owner.runOnUiThread(() -> {
                    connDialog.dismiss();
                    new AlertDialog.Builder(owner)
                            .setTitle(R.string.title_error)
                            .setMessage(R.string.message_connect_fail)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> activityBus.navigateBack())
                            .create()
                            .show();
                });
            } else {
                owner.invalidateOptionsMenu();
                if (metawear.inMetaBootMode()) {
                    owner.runOnUiThread(() -> {
                        connDialog.dismiss();
                        if (owner.getSupportFragmentManager().findFragmentByTag(METABOOT_WARNING_TAG) == null) {
                            new MetaBootWarningFragment().show(owner.getSupportFragmentManager(), METABOOT_WARNING_TAG);
                        }
                        runDiagnostic.setAlpha(.5f);
                        runDiagnostic.setEnabled(false);
                    });
                } else {
                    owner.runOnUiThread(() -> {
                        connDialog.dismiss();
                        runDiagnostic.setAlpha(1f);
                        runDiagnostic.setEnabled(true);
                    });
                }

                Led led;
                if ((led = metawear.getModule(Led.class)) != null) {
                    led.editPattern(Led.Color.GREEN)
                            .highIntensity((byte) 31)
                            .highTime((short) 50)
                            .pulseDuration((short) 500)
                            .repeatCount((byte) 5)
                            .commit();
                    led.play();
                }

                metawear.readDeviceInformationAsync().continueWithTask(task2 -> {
                    entries.get(0).setText(metawear.getMacAddress());
                    if (!task2.isFaulted() && !task2.isCancelled()) {
                        entries.get(1).setText(Global.getRealModel(metawear.getModelString(), task2.getResult().modelNumber));
                        entries.get(2).setText(task2.getResult().modelNumber);
                        entries.get(3).setText(task2.getResult().firmwareRevision);
                        entries.get(4).setText(task2.getResult().hardwareRevision);
                        entries.get(5).setText(task2.getResult().serialNumber);
                        entries.get(6).setText(task2.getResult().manufacturer);
                    } else {
                        entries.get(1).setText("---");
                        entries.get(2).setText("---");
                        entries.get(3).setText("---");
                        entries.get(4).setText("---");
                        entries.get(5).setText("---");
                        entries.get(6).setText("---");
                    }
                    return metawear.readRssiAsync();
                }, Task.UI_THREAD_EXECUTOR).continueWithTask(task2 -> {
                    entries.get(7).setText(task2.isFaulted() || task2.isCancelled() ? "---" : String.format(Locale.US, "%d dBm", task2.getResult()));
                    return metawear.readBatteryLevelAsync();
                }, Task.UI_THREAD_EXECUTOR).continueWithTask(task2 -> {
                    entries.get(8).setText(task.isFaulted() || task.isCancelled() ? "---" : String.format(Locale.US, "%d%%", task2.getResult()));
                    return metawear.inMetaBootMode() ? Task.forResult(false) : metawear.checkForFirmwareUpdateAsync();
                }, Task.UI_THREAD_EXECUTOR).continueWithTask(task2 -> {
                    if (task2.isFaulted()) {
                        Log.w("metabase", "Unable to check firmware status", task2.getError());
                        Snackbar.make(owner.findViewById(R.id.coordinator_layout), "Firmware check failed", Snackbar.LENGTH_SHORT).show();
                    } else if (task2.getResult()) {
                        new AlertDialog.Builder(owner)
                                .setTitle(R.string.title_firmware_update)
                                .setMessage(R.string.message_firmware_available)
                                .setCancelable(false)
                                .setPositiveButton(android.R.string.yes, (dialog, which) -> dfuCompleted(dfu.start(metawear, parameter.btDevice.getName(), null, null)))
                                .setNegativeButton(R.string.label_later, null)
                                .show();
                    }
                    return null;
                }, Task.UI_THREAD_EXECUTOR);
            }
            return null;
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (metawear != null) {
            metawear.onUnexpectedDisconnect(null);
            metawear.disconnectAsync();
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        if (metawear != null) {
            menu.findItem(R.id.action_sleep).setVisible(!metawear.inMetaBootMode());
            menu.findItem(R.id.action_reset).setVisible(!metawear.inMetaBootMode());
        }
    }

    @Override
    Integer getMenuGroupResId() {
        return R.id.group_diagnostic;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_manual_dfu:
                final Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, SELECT_FILE_REQ);
                break;
            case R.id.action_dfu_version: {
                final AlertDialog dialog = new AlertDialog.Builder(owner)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setCancelable(true)
                        .setTitle(R.string.title_dfu)
                        .setView(R.layout.dfu_version_input)
                        .create();
                dialog.show();

                final TextInputLayout firmwareTextWrapper = dialog.findViewById(R.id.firmware_version_wrapper);
                final EditText versionInput = dialog.findViewById(R.id.firmware_version);

                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try {
                        metawear.downloadFirmwareUpdateFilesAsyncV2(versionInput.getText().toString()).continueWith(task -> {
                            if (task.isFaulted()) {
                                firmwareTextWrapper.setError(task.getError().getLocalizedMessage());
                                Log.w("metabase", "Could not update to firmware v" + versionInput.getText(), task.getError());
                            } else {
                                owner.runOnUiThread(() -> {
                                    firmwareTextWrapper.setError(null);
                                    dialog.dismiss();
                                });
                                dfuCompleted(dfu.start(metawear, parameter.btDevice.getName(), task.getResult(), null));
                            }
                            return null;
                        });
                    } catch (RuntimeException e) {
                        firmwareTextWrapper.setError(e.getLocalizedMessage());
                    }
                });
                break;
            }
            case R.id.action_sleep: {
                final Debug debug = metawear.getModule(Debug.class);
                debug.enablePowersave();
                debug.resetAsync();

                new AlertDialog.Builder(owner)
                        .setTitle(R.string.title_success)
                        .setMessage(R.string.message_sleep)
                        .setPositiveButton(android.R.string.ok, ((dialog, which) -> {
                            parameter.isDiscovered = false;
                            activityBus.navigateBack();
                        }))
                        .setCancelable(false)
                        .show();
                break;
            }
            case R.id.action_reset:
                HomeActivity.teardownAndDc(metawear).continueWithTask(ignored -> activityBus.reconnect(metawear, 3))
                    .continueWith(task -> {
                        if (task.isFaulted()) {
                            new AlertDialog.Builder(owner)
                                    .setTitle(R.string.title_error)
                                    .setMessage(owner.getString(R.string.message_reconnect_fail, 3))
                                    .setPositiveButton(android.R.string.ok, ((dialog, which) -> {
                                        parameter.isDiscovered = false;
                                        activityBus.navigateBack();
                                    }))
                                    .setCancelable(false)
                                    .create()
                                    .show();
                        }
                        return null;
                    }, Task.UI_THREAD_EXECUTOR);
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (resultCode != RESULT_OK)
            return;

        fileStreamUri= null;
        switch (requestCode) {
            case SELECT_FILE_REQ:
                // and read new one
                final Uri uri = data.getData();
                /*
                 * The URI returned from application may be in 'file' or 'content' schema.
                 * 'File' schema allows us to create a File object and read details from if directly.
                 *
                 * Data from 'Content' schema must be read by Content Provider. To do that we are using a Loader.
                 */
                if (uri.getScheme().equals("file")) {
                    // the direct path to the file has been returned
                    dfuCompleted(dfu.start(metawear, parameter.btDevice.getName(), new File(uri.getPath()), null));
                } else if (uri.getScheme().equals("content")) {
                    fileStreamUri= uri;

                    // file name and size must be obtained from Content Provider
                    final Bundle bundle = new Bundle();
                    bundle.putParcelable(EXTRA_URI, uri);
                    owner.getSupportLoaderManager().restartLoader(0, bundle, this);
                }
                break;
            default:
                break;
        }
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
        final Uri uri = args.getParcelable(EXTRA_URI);
        /*
         * Some apps, f.e. Google Drive allow to select file that is not on the device. There is no "_data" column handled by that provider. Let's try to obtain all columns and than check
         * which columns are present.
         */
        //final String[] projection = new String[] { MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA };
        return new CursorLoader(owner, uri, null /*all columns, instead of projection*/, null, null, null);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor data) {
        if (data.moveToNext()) {
            /*
             * Here we have to check the column indexes by name as we have requested for all. The order may be different.
             */
            String filename = data.getString(data.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)/* 0 DISPLAY_NAME */);
            //final int fileSize = data.getInt(data.getColumnIndex(MediaStore.MediaColumns.SIZE) /* 1 SIZE */);

            final int dataIndex = data.getColumnIndex(MediaStore.MediaColumns.DATA);
            dfuCompleted(dfu.start(metawear, parameter.btDevice.getName(), dataIndex != -1 ? new File(data.getString(dataIndex /*2 DATA */)) : fileStreamUri, filename));
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {

    }
}
