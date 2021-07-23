package com.mbientlab.metawear.metabase;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import com.mbientlab.function.Action;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public interface BtleScanner {
    static BtleScanner getScanner(BluetoothAdapter adapter, UUID[] serviceFilter) {
        return new LollipopPlusScanner(adapter, serviceFilter);
    }

    void start();
    void start(Action<MetaBaseDevice> handler);
    void stop();

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    class LollipopPlusScanner implements BtleScanner {
        private final BluetoothAdapter adapter;
        private final Set<ParcelUuid> serviceFilter;
        private final ScanCallback api21ScanCallback;
        private Action<MetaBaseDevice> handler;

        private LollipopPlusScanner(BluetoothAdapter adapter, UUID[] serviceFilter) {
            this.adapter = adapter;

            this.serviceFilter = new HashSet<>();
            for(UUID it: serviceFilter) {
                this.serviceFilter.add(new ParcelUuid(it));
            }

            this.api21ScanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, final ScanResult result) {
                    if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                        boolean valid = false;
                        for (ParcelUuid it : result.getScanRecord().getServiceUuids()) {
                            if (LollipopPlusScanner.this.serviceFilter.contains(it)) {
                                valid = true;
                                break;
                            }
                        }

                        if (valid) {
                            Action<byte[]> callHandler = arg1 -> {
                                MetaBaseDevice found = new MetaBaseDevice(result.getDevice(), arg1 != null ? new String(arg1) : result.getDevice().getName());
                                found.isRecording = arg1 != null;
                                found.rssi = result.getRssi();
                                found.isDiscovered = true;

                                if (handler != null) {
                                    handler.apply(found);
                                }
                            };
                            SparseArray<byte[]> mftData = result.getScanRecord().getManufacturerSpecificData();
                            int index = mftData.indexOfKey(Global.OLD_COMPANY_Id);

                            if (index >= 0) {
                                callHandler.apply(mftData.valueAt(index));
                            } else {
                                index = mftData.indexOfKey(Global.COMPANY_ID);
                                if (index >= 0) {
                                    byte[] value = mftData.valueAt(index);
                                    if (value[0] == Global.METABASE_SCAN_ID) {
                                        byte[] offset = new byte[value.length - 1];
                                        System.arraycopy(value, 1, offset, 0, offset.length);
                                        callHandler.apply(offset);
                                    }
                                } else {
                                    callHandler.apply(null);
                                }
                            }
                        }
                    }
                }
            };
        }

        @Override
        public void start() {
            start(null, false);
        }

        @Override
        public void start(Action<MetaBaseDevice> handler) {
            start(handler, true);
        }

        private void start(Action<MetaBaseDevice> handler, boolean overwrite) {
            if (overwrite) {
                this.handler = handler;
            }

            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().startScan(api21ScanCallback);
            } else {
                Log.w("metabase", "Null BT adapter, can't start a scan");
            }
        }

        @Override
        public void stop() {
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(api21ScanCallback);
            } else {
                Log.w("metabase", "Null BT adapter, can't stop the scan");
            }
        }
    }
}
