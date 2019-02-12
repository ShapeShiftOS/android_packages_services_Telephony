/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.content.Context;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ShortcutViewUtils {
    private static final String LOG_TAG = "ShortcutViewUtils";

    // Emergency services which will be promoted on the shortcut view.
    static final int[] PROMOTED_CATEGORIES = {
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE,
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE,
    };

    static final int PROMOTED_CATEGORIES_BITMASK;

    static {
        int bitmask = 0;
        for (int category : PROMOTED_CATEGORIES) {
            bitmask |= category;
        }
        PROMOTED_CATEGORIES_BITMASK = bitmask;
    }

    // Info and emergency call capability of every phone.
    static class PhoneInfo {
        private final PhoneAccountHandle mHandle;
        private final boolean mCanPlaceEmergencyCall;
        private final int mSubId;
        private final String mCountryIso;
        private final List<EmergencyNumber> mPromotedEmergencyNumbers;

        private PhoneInfo(int subId, String countryIso,
                List<EmergencyNumber> promotedEmergencyNumbers) {
            this(null, true, subId, countryIso, promotedEmergencyNumbers);
        }

        private PhoneInfo(PhoneAccountHandle handle, boolean canPlaceEmergencyCall, int subId,
                String countryIso, List<EmergencyNumber> promotedEmergencyNumbers) {
            mHandle = handle;
            mCanPlaceEmergencyCall = canPlaceEmergencyCall;
            mSubId = subId;
            mCountryIso = countryIso;
            mPromotedEmergencyNumbers = promotedEmergencyNumbers;
        }

        public PhoneAccountHandle getPhoneAccountHandle() {
            return mHandle;
        }

        public boolean canPlaceEmergencyCall() {
            return mCanPlaceEmergencyCall;
        }

        public int getSubId() {
            return mSubId;
        }

        public String getCountryIso() {
            return mCountryIso;
        }

        public List<EmergencyNumber> getPromotedEmergencyNumbers() {
            return mPromotedEmergencyNumbers;
        }

        public boolean isSufficientForEmergencyCall() {
            // Checking mCountryIso because the emergency number list is not reliable to be
            // suggested to users if the device didn't camp to any network. In this case, users
            // can still try to dial emergency numbers with dial pad.
            return mCanPlaceEmergencyCall && mPromotedEmergencyNumbers != null
                    && !TextUtils.isEmpty(mCountryIso);
        }

        public boolean hasPromotedEmergencyNumber(String number) {
            for (EmergencyNumber emergencyNumber : mPromotedEmergencyNumbers) {
                if (emergencyNumber.getNumber().equalsIgnoreCase(number)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            if (mHandle != null) {
                sb.append("handle=").append(mHandle.getId()).append(", ");
            }
            sb.append("subId=").append(mSubId)
                    .append(", canPlaceEmergencyCall=").append(mCanPlaceEmergencyCall)
                    .append(", networkCountryIso=").append(mCountryIso);
            if (mPromotedEmergencyNumbers != null) {
                sb.append(", emergencyNumbers=");
                for (EmergencyNumber emergencyNumber : mPromotedEmergencyNumbers) {
                    sb.append(emergencyNumber.getNumber()).append(":")
                            .append(emergencyNumber).append(",");
                }
            }
            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * Picks a preferred phone (SIM slot) which is sufficient for emergency call and can provide
     * promoted emergency numbers.
     *
     * A promoted emergency number should be dialed out over the preferred phone. Other emergency
     * numbers should be still dialable over the system default phone.
     *
     * @return A preferred phone and its promoted emergency number, or null if no phone/promoted
     * emergency numbers available.
     */
    @Nullable
    static PhoneInfo pickPreferredPhone(@NonNull Context context) {
        TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
        if (telephonyManager.getPhoneCount() <= 0) {
            Log.w(LOG_TAG, "No phone available!");
            return null;
        }

        Map<Integer, List<EmergencyNumber>> promotedLists =
                getPromotedEmergencyNumberLists(telephonyManager);
        if (promotedLists == null || promotedLists.isEmpty()) {
            return null;
        }

        // For a multi-phone device, tries the default phone account.
        TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
        PhoneAccountHandle defaultHandle = telecomManager.getDefaultOutgoingPhoneAccount(
                PhoneAccount.SCHEME_TEL);
        if (defaultHandle != null) {
            PhoneInfo phone = loadPhoneInfo(defaultHandle, telephonyManager, telecomManager,
                    promotedLists);
            if (phone.isSufficientForEmergencyCall()) {
                return phone;
            }
            Log.w(LOG_TAG, "Default PhoneAccount is insufficient for emergency call: "
                    + phone.toString());
        } else {
            Log.w(LOG_TAG, "Missing default PhoneAccount! Is this really a phone device?");
        }

        // Looks for any one phone which supports emergency call.
        List<PhoneAccountHandle> allHandles = telecomManager.getCallCapablePhoneAccounts();
        if (allHandles != null && !allHandles.isEmpty()) {
            for (PhoneAccountHandle handle : allHandles) {
                PhoneInfo phone = loadPhoneInfo(handle, telephonyManager, telecomManager,
                        promotedLists);
                if (phone.isSufficientForEmergencyCall()) {
                    return phone;
                } else {
                    if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                        Log.d(LOG_TAG, "PhoneAccount " + phone.toString()
                                + " is insufficient for emergency call.");
                    }
                }
            }
        }

        Log.w(LOG_TAG, "No PhoneAccount available for emergency call!");
        return null;
    }

    private static PhoneInfo loadPhoneInfo(@NonNull PhoneAccountHandle handle,
            @NonNull TelephonyManager telephonyManager, @NonNull TelecomManager telecomManager,
            Map<Integer, List<EmergencyNumber>> promotedLists) {
        boolean canPlaceEmergencyCall = false;
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        String countryIso = null;
        List<EmergencyNumber> emergencyNumberList = null;

        PhoneAccount phoneAccount = telecomManager.getPhoneAccount(handle);
        if (phoneAccount != null) {
            canPlaceEmergencyCall = phoneAccount.hasCapabilities(
                    PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS);
            subId = telephonyManager.getSubIdForPhoneAccount(phoneAccount);
        }

        TelephonyManager subTelephonyManager = telephonyManager.createForSubscriptionId(subId);
        if (subTelephonyManager != null) {
            countryIso = subTelephonyManager.getNetworkCountryIso();
        }

        if (promotedLists != null) {
            emergencyNumberList = promotedLists.get(subId);
        }

        return new PhoneInfo(handle, canPlaceEmergencyCall, subId, countryIso, emergencyNumberList);
    }

    @NonNull
    private static Map<Integer, List<EmergencyNumber>> getPromotedEmergencyNumberLists(
            @NonNull TelephonyManager telephonyManager) {
        Map<Integer, List<EmergencyNumber>> allLists =
                telephonyManager.getCurrentEmergencyNumberList();
        if (allLists == null || allLists.isEmpty()) {
            Log.w(LOG_TAG, "Unable to retrieve emergency number lists!");
            return new ArrayMap<>();
        }

        boolean isDebugLoggable = Log.isLoggable(LOG_TAG, Log.DEBUG);
        Map<Integer, List<EmergencyNumber>> promotedEmergencyNumberLists = new ArrayMap<>();
        for (Map.Entry<Integer, List<EmergencyNumber>> entry : allLists.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            List<EmergencyNumber> emergencyNumberList = entry.getValue();
            if (isDebugLoggable) {
                Log.d(LOG_TAG, "Emergency numbers of " + entry.getKey());
            }

            // The list of promoted emergency numbers which will be visible on shortcut view.
            List<EmergencyNumber> promotedList = new ArrayList<>();
            // A temporary list for non-prioritized emergency numbers.
            List<EmergencyNumber> tempList = new ArrayList<>();

            for (EmergencyNumber emergencyNumber : emergencyNumberList) {
                boolean isPromotedCategory = (emergencyNumber.getEmergencyServiceCategoryBitmask()
                        & PROMOTED_CATEGORIES_BITMASK) != 0;

                // Emergency numbers in DATABASE are prioritized for shortcut view since they were
                // well-categorized.
                boolean isFromPrioritizedSource =
                        (emergencyNumber.getEmergencyNumberSourceBitmask()
                                & EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE) != 0;
                if (isDebugLoggable) {
                    Log.d(LOG_TAG, "  " + emergencyNumber
                            + (isPromotedCategory ? "M" : "")
                            + (isFromPrioritizedSource ? "P" : ""));
                }

                if (isPromotedCategory) {
                    if (isFromPrioritizedSource) {
                        promotedList.add(emergencyNumber);
                    } else {
                        tempList.add(emergencyNumber);
                    }
                }
            }
            // Puts numbers in temp list after prioritized numbers.
            promotedList.addAll(tempList);

            if (!promotedList.isEmpty()) {
                promotedEmergencyNumberLists.put(entry.getKey(), promotedList);
            }
        }

        if (promotedEmergencyNumberLists.isEmpty()) {
            Log.w(LOG_TAG, "No promoted emergency number found!");
        }
        return promotedEmergencyNumberLists;
    }
}
