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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.IBinder;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Model;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Led;
import com.mbientlab.metawear.module.Settings;
import com.mbientlab.metawear.module.Switch;

import java.util.UUID;

import bolts.Task;
import bolts.TaskCompletionSource;

public class TutorialActivity extends AppCompatActivity implements ServiceConnection {
    static final String EXTRA_DEVICE = "com.mbientlab.metawear.metabase.TutorialActivity.EXTRA_DEVICE";

    private BtleScanner scanner;
    private TaskCompletionSource<BtleService.LocalBinder> binderTaskCompletionSource;
    private MetaWearBoard metawear;
    private BluetoothDevice device;

    private static final int MAX_SAMPLES = 100;
    private LineGraphSeries<DataPoint> accDataSeries;
    private int samples;

    @Override
    public void onBackPressed() {
        terminate();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_tutorial, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_skip:
                terminate();
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tutorial);

        scanner = BtleScanner.getScanner(((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(), new UUID[] {
                MetaWearBoard.METAWEAR_GATT_SERVICE
        });

        binderTaskCompletionSource = new TaskCompletionSource<>();
        getApplicationContext().bindService(new Intent(this, BtleService.class),this, Context.BIND_AUTO_CREATE);

        GraphView graph = findViewById(R.id.graph);
        accDataSeries = new LineGraphSeries<>();
        accDataSeries.setColor(Color.argb(0xff, 0xff, 0xff, 0xff));
        graph.addSeries(accDataSeries);

        Viewport viewport = graph.getViewport();
        viewport.setXAxisBoundsManual(true);
        viewport.setMinX(0);
        viewport.setMaxX(MAX_SAMPLES);
        viewport.setYAxisBoundsManual(true);
        viewport.setMinY(-0.5f);
        viewport.setMaxY(4f);

        // https://stackoverflow.com/a/36400198
        GridLabelRenderer renderer = graph.getGridLabelRenderer();
        renderer.setGridStyle(GridLabelRenderer.GridStyle.NONE);// It will remove the background grids
        renderer.setHorizontalLabelsVisible(false);// remove horizontal x labels and line
        renderer.setVerticalLabelsVisible(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unbind the service when the activity is destroyed
        getApplicationContext().unbindService(this);
    }

    public void goto02(View view) {
        findViewById(R.id.tutorial_01).setVisibility(View.GONE);
        findViewById(R.id.tutorial_02).setVisibility(View.VISIBLE);

        scanner.start(arg1 -> {
            if (arg1.rssi > -55) {
                device = arg1.btDevice;
                scanner.stop();

                findViewById(R.id.tutorial_02).setVisibility(View.GONE);
                findViewById(R.id.tutorial_03).setVisibility(View.VISIBLE);

                binderTaskCompletionSource.getTask().onSuccessTask(task -> {
                    metawear = task.getResult().getMetaWearBoard(arg1.btDevice);
                    return metawear.connectAsync();
                }).continueWithTask(task ->
                    task.isFaulted() || task.isCancelled() ?
                        HomeActivity.reconnect(this, metawear, 0L, 3, false) :
                        Task.forResult(null)
                ).continueWithTask(task -> {
                    if (task.isFaulted()) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.title_error)
                                .setMessage(R.string.message_tutorial_connection)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> terminate())
                                .create()
                                .show();
                    }

                    return task;
                }).onSuccessTask(ignored -> {
                    metawear.onUnexpectedDisconnect(status -> {
                        HomeActivity.reconnect(this, metawear, 0L, 3, true).continueWith(task -> {
                            if (task.isFaulted()) {
                                new AlertDialog.Builder(this)
                                        .setTitle(R.string.title_error)
                                        .setMessage(R.string.message_tutorial_lost_connection)
                                        .setPositiveButton(android.R.string.ok, (dialog, which) -> terminate())
                                        .create()
                                        .show();
                            }
                            return null;
                        }, Task.UI_THREAD_EXECUTOR);
                    });
                    Settings.BleConnectionParametersEditor editor = metawear.getModule(Settings.class).editBleConnParams();
                    if (editor != null) {
                        editor.maxConnectionInterval(Global.connInterval)
                                .commit();
                    }
                    return Task.forResult(null);
                }).onSuccessTask(ignored -> {
                    runOnUiThread(() -> {
                        findViewById(R.id.tutorial_03).setVisibility(View.GONE);
                        findViewById(R.id.tutorial_04).setVisibility(View.VISIBLE);
                    });
                    return Task.delay(2500L);
                }).onSuccess(ignored -> {
                    runOnUiThread(() -> {
                        findViewById(R.id.tutorial_04).setVisibility(View.GONE);
                        findViewById(R.id.tutorial_05).setVisibility(View.VISIBLE);
                    });

                    return null;
                });
            }
        });
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        binderTaskCompletionSource.setResult((BtleService.LocalBinder) service);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    public void goto06(View view) {
        view.setEnabled(false);

        final Led led = metawear.getModule(Led.class);
        led.editPattern(Led.Color.GREEN, Led.PatternPreset.BLINK)
                .repeatCount((byte) 5)
                .commit();
        led.play();

        final TaskCompletionSource<Void> switchTutorialTaskSource = new TaskCompletionSource<>();
        Task.delay(2500L).onSuccess(ignored -> {
            Switch onBoardSwitch = metawear.getModule(Switch.class);
            return (onBoardSwitch == null || metawear.getModel() == Model.METATRACKER) ?
                    Task.forResult(null) :
                    onBoardSwitch.state().addRouteAsync(source -> source.stream((data, env) -> {
                        switchTutorialTaskSource.trySetResult(null);
                    }));
        }).onSuccessTask(ignored -> {
            findViewById(R.id.tutorial_05).setVisibility(View.GONE);
            findViewById(R.id.tutorial_06).setVisibility(View.VISIBLE);
            return switchTutorialTaskSource.getTask();
        }, Task.UI_THREAD_EXECUTOR).onSuccessTask(ignored -> {
            samples = 0;
            final Accelerometer acc = metawear.getModule(Accelerometer.class);
            acc.configure()
                    .odr(25f)
                    .range(4f)
                    .commit();
            return acc.acceleration().addRouteAsync(source -> source.stream((data, env) -> {
                Acceleration value = data.value(Acceleration.class);
                final double magnitude = Math.sqrt(value.x() * value.x() + value.y() * value.y() + value.z() * value.z());
                runOnUiThread(() -> {
                    accDataSeries.appendData(new DataPoint(samples, magnitude), true, MAX_SAMPLES);
                    samples++;
                });
            }));
        }).onSuccessTask(ignored -> {
            runOnUiThread(() -> {
                findViewById(R.id.tutorial_06).setVisibility(View.GONE);
                findViewById(R.id.tutorial_07).setVisibility(View.VISIBLE);
            });

            final Accelerometer acc = metawear.getModule(Accelerometer.class);
            acc.acceleration().start();
            acc.start();

            return Task.delay(10000L);
        }).onSuccessTask(ignored -> {
            runOnUiThread(() -> {
                findViewById(R.id.tutorial_07).setVisibility(View.GONE);
                findViewById(R.id.tutorial_08).setVisibility(View.VISIBLE);
            });

            metawear.getModule(Debug.class).resetAsync();
            return binderTaskCompletionSource.getTask();
        }).onSuccess(task -> {
            task.getResult().removeMetaWearBoard(device);
            return null;
        });
    }

    public void endTutorial(View view) {
        terminate();
    }

    private void terminate() {
        scanner.stop();

        if (device != null) {
            Intent intent = new Intent();
            intent.putExtra(EXTRA_DEVICE, device);
            setResult(RESULT_CANCELED, intent);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
