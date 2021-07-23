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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.mbientlab.function.Action;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AppState {
    static class Session {
        public String name;
        public final String time;
        public final List<File> files;

        Session(String name, String time) {
            this(name, time, new ArrayList<>());
        }

        Session(String name, String time, List<File> files) {
            this.name = name;
            this.time = time;
            this.files = files;
        }

        List<File> filter(MetaBaseDevice device) {
            final List<File> result = new ArrayList<>();
            String target = device.getFileFriendlyMac();
            for(File f: files) {
                if (target.equals(f.getName().split("_")[3])) {
                    result.add(f);
                }
            }
            return result;
        }

        static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
            class ViewHolder extends RecyclerView.ViewHolder {
                ViewHolder(View itemView) {
                    super(itemView);

                    itemView.findViewById(R.id.session_share).setOnClickListener(v -> {
                        if (shareSession != null) {
                            shareSession.apply(items.get(getAdapterPosition()));
                        }
                    });
                    //itemView.findViewById(R.id.session_sync).setOnClickListener(v -> {
                    //    if (syncSession != null) {
                    //        syncSession.apply(items.get(getAdapterPosition()));
                    //    }
                    //});
                }
            }

            final List<Session> items;
            Action<Session> shareSession, syncSession;

            // Provide a suitable constructor (depends on the kind of data set)
            Adapter() {
                items = new ArrayList<>();
            }

            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Adapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.session_item, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                Session session = items.get(position);

                ((TextView) holder.itemView.findViewById(R.id.session_name)).setText(session.name);
                ((TextView) holder.itemView.findViewById(R.id.session_time)).setText(session.time);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            void add(Session value) {
                items.add(value);
                notifyDataSetChanged();
            }
        }
    }
    static class Group {
        public final String name;
        public final Map<String, MetaBaseDevice> devices;
        public final List<Session> sessions;

        Group(String name, Map<String, MetaBaseDevice> devices) {
            this.name = name;
            this.devices = devices;
            sessions = new ArrayList<>();
        }

        static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
            class ViewHolder extends RecyclerView.ViewHolder {
                ViewHolder(View itemView) {
                    super(itemView);

                    itemView.setOnClickListener(v -> {
                        if (itemSelected != null) {
                            itemSelected.apply(items.get(getAdapterPosition()));
                        }
                    });
                }
            }

            final List<Group> items;
            Action<Group> itemSelected;

            Adapter() {
                items = new ArrayList<>();
            }

            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Adapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.group_item, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                Group group =items.get(position);

                ((TextView) holder.itemView.findViewById(R.id.group_name)).setText(group.name);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            void add(Group key) {
                items.add(key);
                notifyDataSetChanged();
            }
        }
    }

    static Map<String, MetaBaseDevice> devices = new HashMap<>();
    static Map<String, Group> groups = new HashMap<>();
    static File devicesPath = null, groupsPath = null, oldDevicesPath = null;
}
