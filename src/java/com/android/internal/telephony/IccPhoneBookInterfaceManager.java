/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.text.TextUtils;

import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.AdnRecordCache;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SimPhoneBookInterfaceManager to provide an inter-process communication to
 * access ADN-like SIM records.
 */
public abstract class IccPhoneBookInterfaceManager extends IIccPhoneBook.Stub {
    protected static final boolean DBG = true;

    protected PhoneBase mPhone;
    private   UiccCardApplication mCurrentApp = null;
    protected AdnRecordCache mAdnCache;
    protected final Object mLock = new Object();
    protected int mRecordSize[];
    protected boolean mSuccess;
    private   boolean mIs3gCard = false;  // flag to determine if card is 3G or 2G
    protected List<AdnRecord> mRecords;


    protected static final boolean ALLOW_SIM_OP_IN_UI_THREAD = false;

    protected static final int EVENT_GET_SIZE_DONE = 1;
    protected static final int EVENT_LOAD_DONE = 2;
    protected static final int EVENT_UPDATE_DONE = 3;

    protected Handler mBaseHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_GET_SIZE_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mRecordSize = (int[])ar.result;
                            // recordSize[0]  is the record length
                            // recordSize[1]  is the total length of the EF file
                            // recordSize[2]  is the number of records in the EF file
                            logd("GET_RECORD_SIZE Size " + mRecordSize[0] +
                                    " total " + mRecordSize[1] +
                                    " #record " + mRecordSize[2]);
                        }
                        notifyPending(ar);
                    }
                    break;
                case EVENT_UPDATE_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        notifyPending(ar);
                    }
                    break;
                case EVENT_LOAD_DONE:
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mRecords = (List<AdnRecord>) ar.result;
                        } else {
                            if(DBG) logd("Cannot load ADN records");
                            if (mRecords != null) {
                                mRecords.clear();
                            }
                        }
                        notifyPending(ar);
                    }
                    break;
            }
        }

        private void notifyPending(AsyncResult ar) {
            if (ar.userObj == null) {
                return;
            }
            AtomicBoolean status = (AtomicBoolean) ar.userObj;
            status.set(true);
            mLock.notifyAll();
        }
    };

    public IccPhoneBookInterfaceManager(PhoneBase phone) {
        this.mPhone = phone;
    }

    private void cleanUp() {
        if (mAdnCache != null) {
            mAdnCache.reset();
            mAdnCache = null;
        }
        mIs3gCard = false;
        mCurrentApp = null;
        if (mRecords != null) {
            mRecords.clear();
        }
    }

    public void dispose() {
        cleanUp();
    }

    public void setIccCard(UiccCard card) {
        logd("Card update received: " + card);

        if (card == null) {
            logd("Card is null. Cleanup");
            cleanUp();
            return;
        }

        UiccCardApplication validApp = null;
        int numApps = card.getNumApplications();
        boolean isCurrentAppFound = false;
        mIs3gCard = false;
        for (int i = 0; i < numApps; i++) {
            UiccCardApplication app = card.getApplicationIndex(i);
            if (app != null) {
                // Determine if the card is a 3G card by looking
                // for a CSIM/USIM/ISIM app on the card
                AppType type = app.getType();
                if (type == AppType.APPTYPE_CSIM || type == AppType.APPTYPE_USIM
                        || type == AppType.APPTYPE_ISIM) {
                    logd("Card is 3G");
                    mIs3gCard = true;
                }
                // Check if the app we have is present.
                // If yes, then continue using that.
                if (!isCurrentAppFound) {
                    // if not, then find a valid app.
                    // It does not matter which app, since we are
                    // accessing non-app specific files
                    if (validApp == null && type != AppType.APPTYPE_UNKNOWN) {
                        validApp = app;
                    }

                    if (mCurrentApp == app) {
                        logd("Existing app found");
                        isCurrentAppFound = true;
                    }
                }

                // We have determined that this is 3g card
                // and we also found the current app
                // We are done
                if (mIs3gCard && isCurrentAppFound) {
                    break;
                }
            }
        }

        //Set a new currentApp if
        // - one was not set before
        // OR
        // - the previously set app no longer exists
        if (mCurrentApp == null || !isCurrentAppFound) {
            if (validApp != null) {
                logd("Setting currentApp: " + validApp);
                mCurrentApp = validApp;
                mAdnCache = mCurrentApp.getIccRecords().getAdnCache();
            }
        }
    }

    protected void publish() {
        //NOTE service "simphonebook" added by IccSmsInterfaceManagerProxy
        ServiceManager.addService("simphonebook", this);
    }

    protected abstract void logd(String msg);

    protected abstract void loge(String msg);

    /**
     * Replace oldAdn with newAdn in ADN-like record in EF
     *
     * getAdnRecordsInEf must be called at least once before this function,
     * otherwise an error will be returned. Currently the email field
     * if set in the ADN record is ignored.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param oldTag adn tag to be replaced
     * @param oldPhoneNumber adn number to be replaced
     *        Set both oldTag and oldPhoneNubmer to "" means to replace an
     *        empty record, aka, insert new record
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number ot be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    @Override
    public boolean
    updateAdnRecordsInEfBySearch (int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) {


        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }


        if (DBG) logd("updateAdnRecordsInEfBySearch: efid=" + efid +
                " ("+ oldTag + "," + oldPhoneNumber + ")"+ "==>" +
                " ("+ newTag + "," + newPhoneNumber + ")"+ " pin2=" + pin2);

        efid = updateEfForIccType(efid);

        synchronized(mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord oldAdn = new AdnRecord(oldTag, oldPhoneNumber);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnBySearch(efid, oldAdn, newAdn, pin2, response);
                waitForResult(status);
            } else {
                loge("Failure while trying to update by search due to uninitialised adncache");
            }
        }
        return mSuccess;
    }

    @Override
    public boolean updateAdnRecordsWithContentValuesInEfBySearch(int efid, ContentValues values,
            String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires android.permission.WRITE_CONTACTS permission");
        }

        String oldTag = values.getAsString(IccProvider.STR_TAG);
        String newTag = values.getAsString(IccProvider.STR_NEW_TAG);
        String oldPhoneNumber = values.getAsString(IccProvider.STR_NUMBER);
        String newPhoneNumber = values.getAsString(IccProvider.STR_NEW_NUMBER);
        String oldEmail = values.getAsString(IccProvider.STR_EMAILS);
        String newEmail = values.getAsString(IccProvider.STR_NEW_EMAILS);
        String oldAnr = values.getAsString(IccProvider.STR_ANRS);
        String newAnr = values.getAsString(IccProvider.STR_NEW_ANRS);
        String[] oldEmailArray = TextUtils.isEmpty(oldEmail) ? null : getStringArray(oldEmail);
        String[] newEmailArray = TextUtils.isEmpty(newEmail) ? null : getStringArray(newEmail);
        String[] oldAnrArray = TextUtils.isEmpty(oldAnr) ? null : getStringArray(oldAnr);
        String[] newAnrArray = TextUtils.isEmpty(newAnr) ? null : getStringArray(newAnr);
        efid = updateEfForIccType(efid);

        if (DBG)
            logd("updateAdnRecordsInEfBySearch: efid=" + efid + ", values = " + values + ", pin2="
                    + pin2);
        synchronized (mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord oldAdn = new AdnRecord(oldTag, oldPhoneNumber, oldEmailArray, oldAnrArray);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber, newEmailArray, newAnrArray);
            if (mAdnCache != null) {
                mAdnCache.updateAdnBySearch(efid, oldAdn, newAdn, pin2, response);
                waitForResult(status);
            } else {
                loge("Failure while trying to update by search due to uninitialised adncache");
            }
        }
        return mSuccess;
    }

    /**
     * Update an ADN-like EF record by record index
     *
     * This is useful for iteration the whole ADN file, such as write the whole
     * phone book or erase/format the whole phonebook. Currently the email field
     * if set in the ADN record is ignored.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number to be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param index is 1-based adn record index to be updated
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    @Override
    public boolean
    updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }

        if (DBG) logd("updateAdnRecordsInEfByIndex: efid=" + efid +
                " Index=" + index + " ==> " +
                "("+ newTag + "," + newPhoneNumber + ")"+ " pin2=" + pin2);
        synchronized(mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnByIndex(efid, newAdn, index, pin2, response);
                waitForResult(status);
            } else {
                loge("Failure while trying to update by index due to uninitialised adncache");
            }
        }
        return mSuccess;
    }

    /**
     * Get the capacity of records in efid
     *
     * @param efid the EF id of a ADN-like ICC
     * @return  int[3] array
     *            recordSizes[0]  is the single record length
     *            recordSizes[1]  is the total length of the EF file
     *            recordSizes[2]  is the number of records in the EF file
     */
    @Override
    public abstract int[] getAdnRecordsSize(int efid);

    /**
     * Loads the AdnRecords in efid and returns them as a
     * List of AdnRecords
     *
     * throws SecurityException if no READ_CONTACTS permission
     *
     * @param efid the EF id of a ADN-like ICC
     * @return List of AdnRecord
     */
    @Override
    public List<AdnRecord> getAdnRecordsInEf(int efid) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }

        efid = updateEfForIccType(efid);
        if (DBG) logd("getAdnRecordsInEF: efid=" + efid);

        synchronized(mLock) {
            checkThread();
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_LOAD_DONE, status);
            if (mAdnCache != null) {
                mAdnCache.requestLoadAllAdnLike(efid, mAdnCache.extensionEfForEf(efid), response);
                waitForResult(status);
            } else {
                loge("Failure while trying to load from SIM due to uninitialised adncache");
            }
        }
        return mRecords;
    }

    protected void checkThread() {
        if (!ALLOW_SIM_OP_IN_UI_THREAD) {
            // Make sure this isn't the UI thread, since it will block
            if (mBaseHandler.getLooper().equals(Looper.myLooper())) {
                loge("query() called on the main UI thread!");
                throw new IllegalStateException(
                        "You cannot call query on this provder from the main UI thread.");
            }
        }
    }

    private String[] getStringArray(String str) {
        if (str != null) {
            return str.split(",");
        }
        return null;
    }

    protected void waitForResult(AtomicBoolean status) {
        while (!status.get()) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                logd("interrupted while trying to update by search");
            }
        }
    }

    private int updateEfForIccType(int efid) {
        // If we are trying to read ADN records on a 3G card
        // use EF_PBR
        if (efid == IccConstants.EF_ADN && mIs3gCard) {
            logd("Translate EF_ADN to EF_PBR");
            return IccConstants.EF_PBR;
        }
        return efid;
    }

    @Override
    public boolean updateUsimAdnRecordsInEfByIndex(int efid, String newTag, String newPhoneNumber,
            String[] anrNumbers, String[] emails, int index, String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires android.permission.WRITE_CONTACTS permission");
        }

        if (DBG)
            logd("updateAdnRecordsInEfByIndex: efid=" + efid + " Index=" + index + " ==> " + "("
                    + newTag + "," + newPhoneNumber + ")" + " pin2=" + pin2);
        synchronized (mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber, emails, anrNumbers);
            efid = updateEfForIccType(efid);
            if (mAdnCache != null) {
                mAdnCache.updateUsimAdnByIndex(efid, newAdn, index, pin2, response);
                waitForResult(status);
            } else {
                if (DBG)
                    logd("Failure while trying to update by index due to uninitialised adncache");
            }
        }
        return mSuccess;
    }

    @Override
    public int getAdnCount() {
        int adnCount = 0;
        if (mAdnCache != null) {
            if (mPhone.getCurrentUiccAppType() == AppType.APPTYPE_USIM) {
                adnCount = mAdnCache.getUsimAdnCount();
            } else {
                adnCount = mAdnCache.getAdnCount();
            }
        } else {
            loge("mAdnCache is NULL when getAdnCount.");
        }
        return adnCount;
    }

    @Override
    public int getAnrCount() {
        int anrCount = 0;
        if (mAdnCache != null) {
            anrCount = mAdnCache.getAnrCount();
        } else {
            loge("mAdnCache is NULL when getAnrCount.");
        }
        return anrCount;
    }

    @Override
    public int getEmailCount() {
        int emailCount = 0;
        if (mAdnCache != null) {
            emailCount = mAdnCache.getEmailCount();
        } else {
            loge("mAdnCache is NULL when getEmailCount.");
        }
        return emailCount;
    }

    @Override
    public int getSpareAnrCount() {
        int spareAnrCount = 0;
        if (mAdnCache != null) {
            spareAnrCount = mAdnCache.getSpareAnrCount();
        } else {
            loge("mAdnCache is NULL when getSpareAnrCount.");
        }
        return spareAnrCount;
    }

    @Override
    public int getSpareEmailCount() {
        int spareEmailCount = 0;
        if (mAdnCache != null) {
            spareEmailCount = mAdnCache.getSpareEmailCount();
        } else {
            loge("mAdnCache is NULL when getSpareEmailCount.");
        }
        return spareEmailCount;
    }
}

