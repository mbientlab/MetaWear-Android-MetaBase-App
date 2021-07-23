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

/**
 * Created by eric on 10/26/16.
 */

class Global {
    static final String FIREBASE_PARAM_MODEL = "model",
            FIREBASE_PARAM_DEVICE_NAME = "device_name",
            FIREBASE_PARAM_FIRMWARE = "firmware",
            FIREBASE_PARAM_MAC = "mac",
            FIREBASE_PARAM_LOG_DURATION = "duration";
    static float connInterval = 11.25f;

    static final int nameMaxChar = 26, METABASE_SCAN_ID = 0x2, COMPANY_ID = 0x067e, OLD_COMPANY_Id = 0x626d;

    static String getRealModel(String original, String modelNumber) {
        if (original != null) return original;
        if (modelNumber == null) return "Unknown";
        switch(modelNumber) {
            case "10":
                return "Smilables";
            case "11":
                return "Beiersdorf";
            case "12":
                return "BlueWillow";
            case "13":
                return "Andres";
            case "14":
                return "Panasonic";
            case "15":
                return "MAS";
            case "16":
                return "Palarum";
        }
        return "Unknown";
    }
}
