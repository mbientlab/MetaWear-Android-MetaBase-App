package com.mbientlab.metawear.metabase;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import com.mbientlab.function.Action;

import java.util.ArrayList;
import java.util.List;

public class MetaBaseDevice {
    BluetoothDevice btDevice;
    String name, modelName, modelNumber;;
    final String mac;
    final List<AppState.Session> sessions;

    boolean isRecording, isSelected, isDiscovered;
    int rssi;

    Runnable resetDiscoered;

    MetaBaseDevice(BluetoothDevice btDevice, String name) {
        this(name, btDevice.getAddress(), new ArrayList<>());
        this.btDevice = btDevice;
    }

    MetaBaseDevice(String name, String mac, List<AppState.Session> sessions) {
        this.name = name;
        this.mac = mac;
        this.sessions = sessions;
        this.rssi = 0;
    }

    String getFileFriendlyMac() {
        return mac.replaceAll(":", "");
    }

    @Override
    public boolean equals(Object obj) {
        return (obj == this) || ((obj instanceof MetaBaseDevice) && mac.equals(((MetaBaseDevice) obj).mac));
    }

    static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
        private final static int RSSI_BAR_LEVELS= 5;
        private final static int RSSI_BAR_SCALE= 100 / RSSI_BAR_LEVELS;

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(View itemView) {
                super(itemView);
                itemView.setOnClickListener(v -> {
                    MetaBaseDevice device = items.get(getAdapterPosition());
                    device.isSelected= !device.isSelected;

                    notifyItemChanged(getAdapterPosition());
                    if (itemClicked != null) {
                        itemClicked.apply(items.get(getAdapterPosition()));
                    }
                });
            }
        }

        final List<MetaBaseDevice> items;

        Action<MetaBaseDevice> itemClicked;
        AdapterSelectionMode selectionMode = AdapterSelectionMode.SINGLE;
        private Handler deviceUpdaer = new Handler();

        // Provide a suitable constructor (depends on the kind of data set)
        Adapter() {
            items = new ArrayList<>();
        }

        void toggleSelectionMode() {
            switch(selectionMode) {
                case SINGLE:
                    selectionMode = AdapterSelectionMode.MULTIPLE;
                    for(MetaBaseDevice d: items) {
                        d.isSelected = false;
                    }
                    break;
                case MULTIPLE:
                    selectionMode = AdapterSelectionMode.SINGLE;
                    break;
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.device_scan_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MetaBaseDevice device = items.get(position);

            CheckBox selected = holder.itemView.findViewById(R.id.selected);
            selected.setVisibility(selectionMode == AdapterSelectionMode.MULTIPLE ? View.VISIBLE : View.GONE);
            selected.setChecked(device.isSelected);

            ((TextView) holder.itemView.findViewById(R.id.device_name)).setText(device.name);
            ((TextView) holder.itemView.findViewById(R.id.device_mac)).setText(device.mac);

            ImageView rssi = holder.itemView.findViewById(R.id.device_rssi);
            rssi.setImageLevel(device.isDiscovered ? Math.min(RSSI_BAR_LEVELS - 1, (127 + device.rssi + 5) / RSSI_BAR_SCALE) :10000);
            rssi.setVisibility(selectionMode == AdapterSelectionMode.MULTIPLE ? View.INVISIBLE : View.VISIBLE);

            holder.itemView.findViewById(R.id.device_recording).setVisibility(device.isRecording ? View.VISIBLE : View.INVISIBLE);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void update(MetaBaseDevice key, int pos) {
            MetaBaseDevice current = items.get(pos);
            if (current.btDevice == null) {
                current.btDevice = key.btDevice;
            }

            deviceUpdaer.removeCallbacks(current.resetDiscoered);

            current.isDiscovered = key.isDiscovered;
            current.rssi= key.rssi;
            current.isRecording= key.isRecording;
            current.resetDiscoered = () -> {
                current.isDiscovered = false;
                notifyItemChanged(pos);
            };

            deviceUpdaer.postDelayed(current.resetDiscoered, 10000L);

            notifyItemChanged(pos);
        }

        void update(MetaBaseDevice key) {
            int pos= items.indexOf(key);
            if (pos != -1)  {
                update(key, pos);
            }
        }

        void add(MetaBaseDevice key) {
            int pos= items.indexOf(key);
            if (pos == -1) {
                items.add(key);
                notifyDataSetChanged();
            } else {
                update(key, pos);
            }
        }

        void remove(MetaBaseDevice key) {
            if (items.remove(key)) {
                notifyDataSetChanged();
            }
        }

        void remove(int pos) {
            items.remove(pos);
            notifyDataSetChanged();
        }

        MetaBaseDevice get(int pos) {
            return items.get(pos);
        }
    }
}
