/****************************************************************************
 **
 ** Copyright (C) 2016 The Qt Company Ltd.
 ** Contact: https://www.qt.io/licensing/
 **
 ** This file is part of the QtBluetooth module of the Qt Toolkit.
 **
 ** $QT_BEGIN_LICENSE:LGPL$
 ** Commercial License Usage
 ** Licensees holding valid commercial Qt licenses may use this file in
 ** accordance with the commercial license agreement provided with the
 ** Software or, alternatively, in accordance with the terms contained in
 ** a written agreement between you and The Qt Company. For licensing terms
 ** and conditions see https://www.qt.io/terms-conditions. For further
 ** information use the contact form at https://www.qt.io/contact-us.
 **
 ** GNU Lesser General Public License Usage
 ** Alternatively, this file may be used under the terms of the GNU Lesser
 ** General Public License version 3 as published by the Free Software
 ** Foundation and appearing in the file LICENSE.LGPL3 included in the
 ** packaging of this file. Please review the following information to
 ** ensure the GNU Lesser General Public License version 3 requirements
 ** will be met: https://www.gnu.org/licenses/lgpl-3.0.html.
 **
 ** GNU General Public License Usage
 ** Alternatively, this file may be used under the terms of the GNU
 ** General Public License version 2.0 or (at your option) the GNU General
 ** Public license version 3 or any later version approved by the KDE Free
 ** Qt Foundation. The licenses are as published by the Free Software
 ** Foundation and appearing in the file LICENSE.GPL2 and LICENSE.GPL3
 ** included in the packaging of this file. Please review the following
 ** information to ensure the GNU General Public License requirements will
 ** be met: https://www.gnu.org/licenses/gpl-2.0.html and
 ** https://www.gnu.org/licenses/gpl-3.0.html.
 **
 ** $QT_END_LICENSE$
 **
 ****************************************************************************/

package org.qtproject.qt5.android.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.NoSuchElementException;
import java.util.UUID;

public class QtBluetoothLE {
    private static final String TAG = "QtBluetoothGatt";
    private final BluetoothAdapter mBluetoothAdapter;
    private boolean mLeScanRunning = false;

    private BluetoothGatt mBluetoothGatt = null;
    private String mRemoteGattAddress;
    private final UUID clientCharacteristicUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final UUID veServiceUuid = UUID.fromString("97580001-ddf1-48be-b73e-182664615d8e");
    private final UUID startDfuUuid = UUID.fromString("97580004-ddf1-48be-b73e-182664615d8e");

    private final int MAX_MTU = 512;
    private final int DEFAULT_MTU = 23;
    private int mSupportedMtu = -1;

    private boolean mInitialServiceDiscovery = false;
    private boolean mWaitingStartDfu = false;

    private static boolean mUseExternalLogFunc = false;
    public native static void ExternalLogFunc(int level, final String str);

    public static void EnableExternalLogFunc() {
        mUseExternalLogFunc = true;
    }

    private static void Logv(String tag, String str) {
        if (mUseExternalLogFunc) {
            ExternalLogFunc(0, str);
        } else {
            Log.v(tag, str);
        }
    }

    private static void Logd(String tag, String str) {
        if (mUseExternalLogFunc) {
            ExternalLogFunc(1, str);
        } else {
            Log.d(tag, str);
        }
    }

    private static void Logi(String tag, String str) {
        if (mUseExternalLogFunc) {
            ExternalLogFunc(2, str);
        } else {
            Log.i(tag, str);
        }
    }

    private static void Logw(String tag, String str) {
        if (mUseExternalLogFunc) {
            ExternalLogFunc(3, str);
        } else {
            Log.w(tag, str);
        }
    }

    private static void Loge(String tag, String str) {
        if (mUseExternalLogFunc) {
            ExternalLogFunc(4, str);
        } else {
            Log.e(tag, str);
        }
    }

    /*
     *  The atomic synchronizes the timeoutRunnable thread and the response thread for the pending
     *  I/O job. Whichever thread comes first will pass the atomic gate. The other thread is
     *  cut short.
     */
    // handle values above zero are for regular handle specific read/write requests
    // handle values below zero are reserved for handle-independent requests
    private int HANDLE_FOR_RESET = -1;
    private int HANDLE_FOR_MTU_EXCHANGE = -2;
    private AtomicInteger handleForTimeout = new AtomicInteger(HANDLE_FOR_RESET); // implies not running by default

    private final int RUNNABLE_TIMEOUT = 3000; // 3 seconds
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    private class TimeoutRunnable implements Runnable {
        public TimeoutRunnable(int handle) { pendingJobHandle = handle; }
        @Override
        public void run() {
            if (mBluetoothGatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDING) {
                return;
            }

            boolean timeoutStillValid = handleForTimeout.compareAndSet(pendingJobHandle, HANDLE_FOR_RESET);
            if (timeoutStillValid) {
                Logw(TAG, "****** Timeout for request on handle " + (pendingJobHandle & 0xffff));
                Logw(TAG, "****** Looks like the peripheral does NOT act in " +
                        "accordance to Bluetooth 4.x spec.");
                Logw(TAG, "****** Please check server implementation. Continuing under " +
                        "reservation.");

                if (pendingJobHandle > HANDLE_FOR_RESET)
                    interruptCurrentIO(pendingJobHandle & 0xffff);
                else if (pendingJobHandle < HANDLE_FOR_RESET)
                    interruptCurrentIO(pendingJobHandle);
            }
        }

        // contains handle (0xffff) and top 2 byte contain the job type (0xffff0000)
        private int pendingJobHandle = -1;
    };


    /* Pointer to the Qt object that "owns" the Java object */
    @SuppressWarnings({"CanBeFinal", "WeakerAccess"})
    long qtObject = 0;
    @SuppressWarnings("WeakerAccess")
    Context qtContext = null;

    @SuppressWarnings("WeakerAccess")
    public QtBluetoothLE() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public QtBluetoothLE(final String remoteAddress, Context context) {
        this();
        qtContext = context;
        mRemoteGattAddress = remoteAddress;
    }

    private void refreshDeviceCache() {
        /*
         * If the device is bonded this is up to the Service Changed characteristic to notify Android that the services has changed.
         * There is no need for this trick in that case.
         * If not bonded, the Android should not keep the services cached when the Service Changed characteristic is present in the target device database.
         * However, due to the Android bug (still exists in Android 5.0.1), it is keeping them anyway and the only way to clear services is by using this hidden refresh method.
         */
        Logd(TAG, "gatt.refresh() (hidden)");
        /*
         * There is a refresh() method in BluetoothGatt class but for now it's hidden. We will call it using reflections.
         */
        try {
            final Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
            if (refresh != null) {
                final boolean success = (Boolean) refresh.invoke(mBluetoothGatt);
                Logd(TAG, "Refreshing result: " + success);
            }
        } catch (Exception e) {
            Logw(TAG, "An exception occurred while refreshing device");
        }
    }


    /*************************************************************/
    /* Device scan                                               */
    /*************************************************************/

    /*
        Returns true, if request was successfully completed
     */
    public boolean scanForLeDevice(final boolean isEnabled) {
        if (isEnabled == mLeScanRunning)
            return true;

        if (isEnabled) {
            mLeScanRunning = mBluetoothAdapter.startLeScan(leScanCallback);
        } else {
            mBluetoothAdapter.stopLeScan(leScanCallback);
            mLeScanRunning = false;
        }

        return (mLeScanRunning == isEnabled);
    }

    // Device scan callback
    private final BluetoothAdapter.LeScanCallback leScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    if (qtObject == 0)
                        return;

                    leScanResult(qtObject, device, rssi, scanRecord);
                }
            };

    public native void leScanResult(long qtObject, BluetoothDevice device, int rssi, byte[] scanRecord);

    /*************************************************************/
    /* Service Discovery                                         */
    /*************************************************************/

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        class delayedCallback_TimerTask extends TimerTask {

            private int delayedCallback_errorCode;
            private int delayedCallback_qLowEnergyController_State;

            public delayedCallback_TimerTask(int errorCode, int state) {
                delayedCallback_errorCode = errorCode;
                delayedCallback_qLowEnergyController_State = state;
            }

            @Override
            public void run() {
                Logd(TAG, "Delayed leConnectionStateChange callback");
                leConnectionStateChange(qtObject, delayedCallback_errorCode, delayedCallback_qLowEnergyController_State);
            }
        };

        private TimerTask delayedCallback_timerTask = null;
        private Timer delayedCallback_timer = new Timer();

        class delayedDiscover_TimerTask extends TimerTask {

            public delayedDiscover_TimerTask() {
            }

            @Override
            public void run() {
                Logd(TAG, "Delayed discoverServices call");

                mInitialServiceDiscovery = true;
                if (mBluetoothGatt.discoverServices()) {
                    return;
                }
                Logd(TAG, "Starting initial service discovery failed, refreshing cache and delaying callback");
                mInitialServiceDiscovery = false;
                refreshDeviceCache();
                Logd(TAG, "Scheduling delayedCallback_timerTask");
                delayedCallback_timerTask = new delayedCallback_TimerTask(0, 2);
                delayedCallback_timer.schedule(delayedCallback_timerTask, 3000);
            }
        };

        private TimerTask delayedDiscover_timerTask = null;
        private Timer delayedDiscover_timer = new Timer();

        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (qtObject == 0)
                return;

            int qLowEnergyController_State = 0;
            boolean delayCallback = false;
            //This must be in sync with QLowEnergyController::ControllerState
            switch (newState) {
                case BluetoothProfile.STATE_DISCONNECTED:
                    Logd(TAG, ">>>>>>>>>>>>>>>>>> onConnectionStateChange:STATE_DISCONNECTED");
                    if (delayedCallback_timerTask != null) {
                        Logd(TAG, "Canceling delayedCallback_timerTask");
                        delayedCallback_timerTask.cancel();
                        delayedCallback_timerTask = null;
                    }
                    if (delayedDiscover_timerTask != null) {
                        Logd(TAG, "Canceling delayedDiscover_timerTask");
                        delayedDiscover_timerTask.cancel();
                        delayedDiscover_timerTask = null;
                    }
                    if (mBluetoothGatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                        refreshDeviceCache();
                    }
                    qLowEnergyController_State = 0;
                    // we disconnected -> get rid of data from previous run
                    resetData();
                    // reset mBluetoothGatt, reusing same object is not very reliable
                    // sometimes it reconnects and sometimes it does not.
                    if (mBluetoothGatt != null)
                        mBluetoothGatt.close();
                    mBluetoothGatt = null;
                    break;
                case BluetoothProfile.STATE_CONNECTED:
                    Logd(TAG, ">>>>>>>>>>>>>>>>>> onConnectionStateChange:STATE_CONNECTED");
                    requestConnectionUpdatePriority(0);
                    if (mBluetoothGatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                        Logd(TAG, "Not bonded so no refresh needed, but delaying callback");
                        qLowEnergyController_State = 2;
                        mInitialServiceDiscovery = false;
                        delayCallback = true;
                    } else {
                        Logd(TAG, "Connected with a bonded device. Delaying first service discovery.");
                        delayedDiscover_timerTask = new delayedDiscover_TimerTask();
                        delayedDiscover_timer.schedule(delayedDiscover_timerTask, 500);
                        return;
                    }
            }

            //This must be in sync with QLowEnergyController::Error
            int errorCode;
            switch (status) {
                case BluetoothGatt.GATT_SUCCESS:
                    errorCode = 0; break; //QLowEnergyController::NoError
                case BluetoothGatt.GATT_FAILURE: // Android's equivalent of "do not know what error it is"
                    errorCode = 1; break; //QLowEnergyController::UnknownError
                case 8:  // BLE_HCI_CONNECTION_TIMEOUT
                case 22: // BLE_HCI_LOCAL_HOST_TERMINATED_CONNECTION
                    Logw(TAG, "Connection Error: Try to delay connect() call after previous activity");
                    errorCode = 5; break; //QLowEnergyController::ConnectionError
                default:
                    Logw(TAG, "Unhandled error code on connectionStateChanged: " + status + " " + newState);
                    errorCode = status; break; //TODO deal with all errors
            }

            if (delayCallback) {
                Logd(TAG, "Scheduling delayedCallback_timerTask");
                delayedCallback_timerTask = new delayedCallback_TimerTask(errorCode, qLowEnergyController_State);
                delayedCallback_timer.schedule(delayedCallback_timerTask, 3000);
            } else {
                leConnectionStateChange(qtObject, errorCode, qLowEnergyController_State);
            }
        }

        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (mInitialServiceDiscovery) {
                Logd(TAG, "Services discovered of the bonded device (initial service discovery)");
                mInitialServiceDiscovery = false;
                BluetoothGattService veService = mBluetoothGatt.getService(veServiceUuid);
                if (veService != null) {
                    BluetoothGattCharacteristic startDfuCharacteristic = veService.getCharacteristic(startDfuUuid);
                    if (startDfuCharacteristic != null) {
                        Logd(TAG, "Writing \\0 to the startDfu characteristic");
                        mWaitingStartDfu = true;
                        startDfuCharacteristic.setValue("\0");
                        if (mBluetoothGatt.writeCharacteristic(startDfuCharacteristic)) {
                            // Wait until the characteristic written
                            return;
                        }
                        Logd(TAG, "Writing startDfu failed, refreshing cache");
                        mWaitingStartDfu = false;
                    } else {
                        Logd(TAG, "startDfu characteristic not found, refreshing cache");
                    }
                } else {
                    Logd(TAG, "VeService not available, refreshing cache");
                }
                refreshDeviceCache();
                leConnectionStateChange(qtObject, 0, 2);
                return;
            }
            //This must be in sync with QLowEnergyController::Error
            int errorCode;
            StringBuilder builder = new StringBuilder();
            switch (status) {
                case BluetoothGatt.GATT_SUCCESS:
                    errorCode = 0; //QLowEnergyController::NoError
                    final List<BluetoothGattService> services = mBluetoothGatt.getServices();
                    for (BluetoothGattService service: services) {
                        builder.append(service.getUuid().toString()).append(" "); //space is separator
                    }
                    break;
                default:
                    Logw(TAG, "Unhandled error code on onServicesDiscovered: " + status);
                    errorCode = status; break; //TODO deal with all errors
            }
            leServicesDiscovered(qtObject, errorCode, builder.toString());

            scheduleMtuExchange();
        }

        public void onCharacteristicRead(android.bluetooth.BluetoothGatt gatt,
                                         android.bluetooth.BluetoothGattCharacteristic characteristic,
                                         int status)
        {
            int foundHandle = -1;
            synchronized (this) {
                foundHandle = handleForCharacteristic(characteristic);
                if (foundHandle == -1 || foundHandle >= entries.size() ) {
                    Logw(TAG, "Cannot find characteristic read request for read notification - handle: " +
                            foundHandle + " size: " + entries.size());

                    //unlock the queue for next item
                    synchronized (readWriteQueue) {
                        ioJobPending = false;
                    }

                    performNextIO();
                    return;
                }
            }

            boolean requestTimedOut = !handleForTimeout.compareAndSet(
                                        modifiedReadWriteHandle(foundHandle, IoJobType.Read), HANDLE_FOR_RESET);
            if (requestTimedOut) {
                Logw(TAG, "Late char read reply after timeout was hit for handle " + foundHandle);
                // Timeout has hit before this response -> ignore the response
                // no need to unlock ioJobPending -> the timeout has done that already
                return;
            }

            GattEntry entry = entries.get(foundHandle);
            final boolean isServiceDiscoveryRun = !entry.valueKnown;
            entry.valueKnown = true;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Qt manages handles starting at 1, in Java we use a system starting with 0
                //TODO avoid sending service uuid -> service handle should be sufficient
                leCharacteristicRead(qtObject, characteristic.getService().getUuid().toString(),
                    foundHandle + 1, characteristic.getUuid().toString(),
                    characteristic.getProperties(), characteristic.getValue());
            } else {
                if (isServiceDiscoveryRun) {
                    Logw(TAG, "onCharacteristicRead during discovery error: " + status);

                    Logd(TAG, "Non-readable characteristic " + characteristic.getUuid() +
                            " for service " + characteristic.getService().getUuid());
                    leCharacteristicRead(qtObject, characteristic.getService().getUuid().toString(),
                        foundHandle + 1, characteristic.getUuid().toString(),
                        characteristic.getProperties(), characteristic.getValue());
                } else {
                    // This must be in sync with QLowEnergyService::CharacteristicReadError
                    final int characteristicReadError = 5;
                    leServiceError(qtObject, foundHandle + 1, characteristicReadError);
                }
            }

            if (isServiceDiscoveryRun) {

                // last entry of pending service discovery run -> send discovery finished state update
                GattEntry serviceEntry = entries.get(entry.associatedServiceHandle);
                if (serviceEntry.endHandle == foundHandle)
                    finishCurrentServiceDiscovery(entry.associatedServiceHandle);
            }

            //unlock the queue for next item
            synchronized (readWriteQueue) {
                ioJobPending = false;
            }

            performNextIO();
        }

        public void onCharacteristicWrite(android.bluetooth.BluetoothGatt gatt,
                                          android.bluetooth.BluetoothGattCharacteristic characteristic,
                                          int status)
        {
            if (status != BluetoothGatt.GATT_SUCCESS)
                Logw(TAG, "onCharacteristicWrite: error " + status);

            if (mWaitingStartDfu) {
                Logd(TAG, "Characteristic " + characteristic.getUuid().toString() + " written while waiting for startDfu write");
                mWaitingStartDfu = false;
                if (qtObject == 0)
                    return;

                leConnectionStateChange(qtObject, 0, 2);
                return;
            }

            int handle = handleForCharacteristic(characteristic);
            if (handle == -1) {
                Logw(TAG,"onCharacteristicWrite: cannot find handle");
                return;
            }

            boolean requestTimedOut = !handleForTimeout.compareAndSet(
                                            modifiedReadWriteHandle(handle, IoJobType.Write), HANDLE_FOR_RESET);
            if (requestTimedOut) {
                Logw(TAG, "Late char write reply after timeout was hit for handle " + handle);
                // Timeout has hit before this response -> ignore the response
                // no need to unlock ioJobPending -> the timeout has done that already
                return;
            }

            int errorCode;
            //This must be in sync with QLowEnergyService::ServiceError
            switch (status) {
                case BluetoothGatt.GATT_SUCCESS:
                    errorCode = 0; break; // NoError
                default:
                    errorCode = 2; break; // CharacteristicWriteError
            }

            synchronized (readWriteQueue) {
                ioJobPending = false;
            }
            leCharacteristicWritten(qtObject, handle+1, characteristic.getValue(), errorCode);
            performNextIO();
        }

        public void onCharacteristicChanged(android.bluetooth.BluetoothGatt gatt,
                                            android.bluetooth.BluetoothGattCharacteristic characteristic)
        {
            int handle = handleForCharacteristic(characteristic);
            if (handle == -1) {
                Logw(TAG,"onCharacteristicChanged: cannot find handle");
                return;
            }

            leCharacteristicChanged(qtObject, handle+1, characteristic.getValue());
        }

        public void onDescriptorRead(android.bluetooth.BluetoothGatt gatt,
                                     android.bluetooth.BluetoothGattDescriptor descriptor,
                                     int status)
        {
            int foundHandle = -1;
            synchronized (this) {
                foundHandle = handleForDescriptor(descriptor);
                if (foundHandle == -1 || foundHandle >= entries.size() ) {
                    Logw(TAG, "Cannot find descriptor read request for read notification - handle: " +
                            foundHandle + " size: " + entries.size());

                    //unlock the queue for next item
                    synchronized (readWriteQueue) {
                        ioJobPending = false;
                    }
                    performNextIO();
                    return;
                }
            }

            boolean requestTimedOut = !handleForTimeout.compareAndSet(
                                        modifiedReadWriteHandle(foundHandle, IoJobType.Read), HANDLE_FOR_RESET);
            if (requestTimedOut) {
                Logw(TAG, "Late descriptor read reply after timeout was hit for handle " +
                        foundHandle);
                // Timeout has hit before this response -> ignore the response
                // no need to unlock ioJobPending -> the timeout has done that already
                return;
            }

            GattEntry entry = entries.get(foundHandle);
            final boolean isServiceDiscoveryRun = !entry.valueKnown;
            entry.valueKnown = true;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                //TODO avoid sending service and characteristic uuid -> handles should be sufficient
                leDescriptorRead(qtObject, descriptor.getCharacteristic().getService().getUuid().toString(),
                        descriptor.getCharacteristic().getUuid().toString(), foundHandle + 1,
                        descriptor.getUuid().toString(), descriptor.getValue());
            } else {
                if (isServiceDiscoveryRun) {
                    //ignore
                    Logw(TAG, "onDescriptorcRead during discovery error: " + status);
                } else {
                    // This must be in sync with QLowEnergyService::DescriptorReadError
                    final int descriptorReadError = 6;
                    leServiceError(qtObject, foundHandle + 1, descriptorReadError);
                }

            }

            if (isServiceDiscoveryRun) {
                // last entry of pending service discovery run? ->send discovery finished state update
                GattEntry serviceEntry = entries.get(entry.associatedServiceHandle);
                if (serviceEntry.endHandle == foundHandle) {
                    finishCurrentServiceDiscovery(entry.associatedServiceHandle);
                }

                /* Some devices preset ClientCharacteristicConfiguration descriptors
                 * to enable notifications out of the box. However the additional
                 * BluetoothGatt.setCharacteristicNotification call prevents
                 * automatic notifications from coming through. Hence we manually set them
                 * up here.
                 */
                if (descriptor.getUuid().compareTo(clientCharacteristicUuid) == 0) {
                    byte[] bytearray = descriptor.getValue();
                    final int value = (bytearray != null && bytearray.length > 0) ? bytearray[0] : 0;
                    // notification or indication bit set?
                    if ((value & 0x03) > 0) {
                        Logd(TAG, "Found descriptor with automatic notifications.");
                        mBluetoothGatt.setCharacteristicNotification(
                                descriptor.getCharacteristic(), true);
                    }
                }
            }

            //unlock the queue for next item
            synchronized (readWriteQueue) {
                ioJobPending = false;
            }

            performNextIO();
        }

        public void onDescriptorWrite(android.bluetooth.BluetoothGatt gatt,
                                      android.bluetooth.BluetoothGattDescriptor descriptor,
                                      int status)
        {
            if (status != BluetoothGatt.GATT_SUCCESS)
                Logw(TAG, "onDescriptorWrite: error " + status);

            int handle = handleForDescriptor(descriptor);

            boolean requestTimedOut = !handleForTimeout.compareAndSet(
                                        modifiedReadWriteHandle(handle, IoJobType.Write), HANDLE_FOR_RESET);
            if (requestTimedOut) {
                Logw(TAG, "Late descriptor write reply after timeout was hit for handle " +
                        handle);
                // Timeout has hit before this response -> ignore the response
                // no need to unlock ioJobPending -> the timeout has done that already
                return;
            }

            int errorCode;
            //This must be in sync with QLowEnergyService::ServiceError
            switch (status) {
                case BluetoothGatt.GATT_SUCCESS:
                    errorCode = 0; break; // NoError
                default:
                    errorCode = 3; break; // DescriptorWriteError
            }

            synchronized (readWriteQueue) {
                ioJobPending = false;
            }

            leDescriptorWritten(qtObject, handle+1, descriptor.getValue(), errorCode);
            performNextIO();
        }
        //TODO Requires Android API 21 which is not available on CI yet.
//        public void onReliableWriteCompleted(android.bluetooth.BluetoothGatt gatt,
//                                             int status) {
//            System.out.println("onReliableWriteCompleted");
//        }
//
//        public void onReadRemoteRssi(android.bluetooth.BluetoothGatt gatt,
//                                     int rssi, int status) {
//            System.out.println("onReadRemoteRssi");
//        }

        // requires Android API v21
        public void onMtuChanged(android.bluetooth.BluetoothGatt gatt, int mtu, int status)
        {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Logd(TAG, "MTU changed to " + mtu);
                mSupportedMtu = mtu;
            } else {
                Logw(TAG, "MTU change error " + status + ". New MTU " + mtu);
                mSupportedMtu = DEFAULT_MTU;
            }

            boolean requestTimedOut = !handleForTimeout.compareAndSet(
                    modifiedReadWriteHandle(HANDLE_FOR_MTU_EXCHANGE, IoJobType.Mtu), HANDLE_FOR_RESET);
            if (requestTimedOut) {
                Logw(TAG, "Late mtu reply after timeout was hit");
                // Timeout has hit before this response -> ignore the response
                // no need to unlock ioJobPending -> the timeout has done that already
                return;
            }

            synchronized (readWriteQueue) {
                ioJobPending = false;
            }

            performNextIO();
        }
    };


    public boolean connect() {
        BluetoothDevice mRemoteGattDevice;

        try {
            mRemoteGattDevice = mBluetoothAdapter.getRemoteDevice(mRemoteGattAddress);
        } catch (IllegalArgumentException ex) {
            Logw(TAG, "Remote address is not valid: " + mRemoteGattAddress);
            return false;
        }

        try {
            // BluetoothDevice.,connectGatt(Context, boolean, BluetoothGattCallback, int) was
            // officially introduced by Android API v23. Earlier Android versions have a private
            // implementation already though. Let's check at runtime and use it if possible.
            //
            // In general the new connectGatt() seems to be much more reliable than the function
            // that doesn't specify the transport layer.

            Class[] args = new Class[4];
            args[0] = android.content.Context.class;
            args[1] = boolean.class;
            args[2] = android.bluetooth.BluetoothGattCallback.class;
            args[3] = int.class;
            Method connectMethod = mRemoteGattDevice.getClass().getDeclaredMethod("connectGatt", args);
            if (connectMethod != null) {
                mBluetoothGatt = (BluetoothGatt) connectMethod.invoke(mRemoteGattDevice, qtContext,
                                                                      false, gattCallback,
                                                                      2 /*TRANSPORT_LE*/);
                Logw(TAG, "Using Android v23 BluetoothDevice.connectGatt()");
            }
        } catch (Exception ex) {
            // fallback to less reliable API 18 version
            mBluetoothGatt = mRemoteGattDevice.connectGatt(qtContext, false, gattCallback);
        }

        return mBluetoothGatt != null;
    }

    public void disconnect() {
        if (mBluetoothGatt == null)
            return;

        mBluetoothGatt.disconnect();
    }

    public boolean discoverServices()
    {
        return mBluetoothGatt != null && mBluetoothGatt.discoverServices();
    }

    private enum GattEntryType
    {
        Service, Characteristic, CharacteristicValue, Descriptor
    }
    private class GattEntry
    {
        public GattEntryType type;
        public boolean valueKnown = false;
        public BluetoothGattService service = null;
        public BluetoothGattCharacteristic characteristic = null;
        public BluetoothGattDescriptor descriptor = null;
        /*
         *  endHandle defined for GattEntryType.Service and GattEntryType.CharacteristicValue
         *  If the type is service this is the value of the last Gatt entry belonging to the very
         *  same service. If the type is a char value it is the entries index inside
         *  the "entries" list.
         */
        public int endHandle = -1;
        // pointer back to the handle that describes the service that this GATT entry belongs to
        public int associatedServiceHandle;
    }

    private enum IoJobType
    {
        Read, Write, Mtu
    }

    private class ReadWriteJob
    {
        public GattEntry entry;
        public byte[] newValue;
        public int requestedWriteType;
        public IoJobType jobType;
    }

    // service uuid -> service handle mapping (there can be more than one service with same uuid)
    private final Hashtable<UUID, List<Integer>> uuidToEntry = new Hashtable<UUID, List<Integer>>(100);
    // index into array is equivalent to handle id
    private final ArrayList<GattEntry> entries = new ArrayList<GattEntry>(100);
    //backlog of to be discovered services
    // TODO remove
    private final LinkedList<Integer> servicesToBeDiscovered = new LinkedList<Integer>();


    private final LinkedList<ReadWriteJob> readWriteQueue = new LinkedList<ReadWriteJob>();
    private boolean ioJobPending;

    /*
        Internal helper function
        Returns the handle id for the given characteristic; otherwise returns -1.

        Note that this is the Java handle. The Qt handle is the Java handle +1.
     */
    private int handleForCharacteristic(BluetoothGattCharacteristic characteristic)
    {
        if (characteristic == null)
            return -1;

        List<Integer> handles = uuidToEntry.get(characteristic.getService().getUuid());
        if (handles == null || handles.isEmpty())
            return -1;

        //TODO for now we assume we always want the first service in case of uuid collision
        int serviceHandle = handles.get(0);

        try {
            GattEntry entry;
            for (int i = serviceHandle+1; i < entries.size(); i++) {
                entry = entries.get(i);
                if (entry == null)
                    continue;

                switch (entry.type) {
                    case Descriptor:
                    case CharacteristicValue:
                        continue;
                    case Service:
                        break;
                    case Characteristic:
                        if (entry.characteristic == characteristic)
                            return i;
                        break;
                }
            }
        } catch (IndexOutOfBoundsException ex) { /*nothing*/ }
        return -1;
    }

    /*
        Internal helper function
        Returns the handle id for the given descriptor; otherwise returns -1.

        Note that this is the Java handle. The Qt handle is the Java handle +1.
     */
    private int handleForDescriptor(BluetoothGattDescriptor descriptor)
    {
        if (descriptor == null)
            return -1;

        List<Integer> handles = uuidToEntry.get(descriptor.getCharacteristic().getService().getUuid());
        if (handles == null || handles.isEmpty())
            return -1;

        //TODO for now we assume we always want the first service in case of uuid collision
        int serviceHandle = handles.get(0);

        try {
            GattEntry entry;
            for (int i = serviceHandle+1; i < entries.size(); i++) {
                entry = entries.get(i);
                if (entry == null)
                    continue;

                switch (entry.type) {
                    case Characteristic:
                    case CharacteristicValue:
                        continue;
                    case Service:
                        break;
                    case Descriptor:
                        if (entry.descriptor == descriptor)
                            return i;
                        break;
                }
            }
        } catch (IndexOutOfBoundsException ignored) { }
        return -1;
    }

    private void populateHandles()
    {
        // We introduce the notion of artificial handles. While GATT handles
        // are not exposed on Android they help to quickly identify GATT attributes
        // on the C++ side. The Qt Api will not expose the handles
        GattEntry entry = null;
        List<BluetoothGattService> services = mBluetoothGatt.getServices();
        for (BluetoothGattService service: services) {
            GattEntry serviceEntry = new GattEntry();
            serviceEntry.type = GattEntryType.Service;
            serviceEntry.service = service;
            entries.add(serviceEntry);

            // remember handle for the service for later update
            int serviceHandle = entries.size() - 1;
            //point to itself -> mostly done for consistence reasons with other entries
            serviceEntry.associatedServiceHandle = serviceHandle;

            //some devices may have more than one service with the same uuid
            List<Integer> old = uuidToEntry.get(service.getUuid());
            if (old == null)
                old = new ArrayList<Integer>();
            old.add(entries.size()-1);
            uuidToEntry.put(service.getUuid(), old);

            // add all characteristics
            List<BluetoothGattCharacteristic> charList = service.getCharacteristics();
            for (BluetoothGattCharacteristic characteristic: charList) {
                entry = new GattEntry();
                entry.type = GattEntryType.Characteristic;
                entry.characteristic = characteristic;
                entry.associatedServiceHandle = serviceHandle;
                //entry.endHandle = .. undefined
                entries.add(entry);

                // this emulates GATT value attributes
                entry = new GattEntry();
                entry.type = GattEntryType.CharacteristicValue;
                entry.associatedServiceHandle = serviceHandle;
                entry.endHandle = entries.size(); // special case -> current index in entries list
                entries.add(entry);

                // add all descriptors
                List<BluetoothGattDescriptor> descList = characteristic.getDescriptors();
                for (BluetoothGattDescriptor desc: descList) {
                    entry = new GattEntry();
                    entry.type = GattEntryType.Descriptor;
                    entry.descriptor = desc;
                    entry.associatedServiceHandle = serviceHandle;
                    //entry.endHandle = .. undefined
                    entries.add(entry);
                }
            }

            // update endHandle of current service
            serviceEntry.endHandle = entries.size() - 1;
        }

        entries.trimToSize();
    }

    private void resetData()
    {
        synchronized (this) {
            uuidToEntry.clear();
            entries.clear();
            servicesToBeDiscovered.clear();
        }

        // kill all timeout handlers
        timeoutHandler.removeCallbacksAndMessages(null);
        handleForTimeout.set(HANDLE_FOR_RESET);

        synchronized (readWriteQueue) {
            readWriteQueue.clear();
            ioJobPending = false;
        }
    }

    public synchronized boolean discoverServiceDetails(String serviceUuid)
    {
        try {
            if (mBluetoothGatt == null)
                return false;

            if (entries.isEmpty())
                populateHandles();

            GattEntry entry;
            int serviceHandle;
            try {
                UUID service = UUID.fromString(serviceUuid);
                List<Integer> handles = uuidToEntry.get(service);
                if (handles == null || handles.isEmpty()) {
                    Logw(TAG, "Unknown service uuid for current device: " + service.toString());
                    return false;
                }

                //TODO for now we assume we always want the first service in case of uuid collision
                serviceHandle = handles.get(0);
                entry = entries.get(serviceHandle);
                if (entry == null) {
                    Logw(TAG, "Service with UUID " + service.toString() + " not found");
                    return false;
                }
            } catch (IllegalArgumentException ex) {
                //invalid UUID string passed
                Logw(TAG, "Cannot parse given UUID");
                return false;
            }

            if (entry.type != GattEntryType.Service) {
                Logw(TAG, "Given UUID is not a service UUID: " + serviceUuid);
                return false;
            }

            // current service already discovered or under investigation
            if (entry.valueKnown || servicesToBeDiscovered.contains(serviceHandle)) {
                Logw(TAG, "Service already known or to be discovered");
                return true;
            }

            servicesToBeDiscovered.add(serviceHandle);
            scheduleServiceDetailDiscovery(serviceHandle);
            performNextIO();

        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }

        return true;
    }

    /*
        Returns the uuids of the services included by the given service. Otherwise returns null.
        Directly called from Qt.
     */
    public String includedServices(String serviceUuid)
    {
        UUID uuid;
        try {
            uuid = UUID.fromString(serviceUuid);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        //TODO Breaks in case of two services with same uuid
        BluetoothGattService service = mBluetoothGatt.getService(uuid);
        if (service == null)
            return null;

        final List<BluetoothGattService> includes = service.getIncludedServices();
        if (includes.isEmpty())
            return null;

        StringBuilder builder = new StringBuilder();
        for (BluetoothGattService includedService: includes) {
            builder.append(includedService.getUuid().toString()).append(" "); //space is separator
        }

        return builder.toString();
    }

    //TODO function not yet used
    private void finishCurrentServiceDiscovery(int handleDiscoveredService)
    {
        Logw(TAG, "Finished current discovery for service handle " + handleDiscoveredService);
        GattEntry discoveredService = entries.get(handleDiscoveredService);
        discoveredService.valueKnown = true;
        synchronized (this) {
            try {
                servicesToBeDiscovered.removeFirst();
            } catch (NoSuchElementException ex) {
                Logw(TAG, "Expected queued service but didn't find any");
            }
        }

        leServiceDetailDiscoveryFinished(qtObject, discoveredService.service.getUuid().toString(),
                handleDiscoveredService + 1, discoveredService.endHandle + 1);
    }

    private boolean executeMtuExchange()
    {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Method mtuMethod = mBluetoothGatt.getClass().getDeclaredMethod("requestMtu", int.class);
                if (mtuMethod != null) {
                    Boolean success = (Boolean) mtuMethod.invoke(mBluetoothGatt, MAX_MTU);
                    if (success.booleanValue()) {
                        Logd(TAG, "MTU change initiated");
                        return false;
                    } else {
                        Logw(TAG, "MTU change request failed");
                    }
                }
            } catch (Exception ex) {}
        }

        Logw(TAG, "Assuming default MTU value of 23 bytes");

        mSupportedMtu = DEFAULT_MTU;
        return true;
    }

    private void scheduleMtuExchange()
    {
        ReadWriteJob newJob = new ReadWriteJob();
        newJob.jobType = IoJobType.Mtu;
        newJob.entry = null;

        synchronized (readWriteQueue) {
            readWriteQueue.add(newJob);
        }

        performNextIO();
    }

    /*
        Internal Helper function for discoverServiceDetails()

        Adds all Gatt entries for the given service to the readWriteQueue to be discovered.
        This function only ever adds read requests to the queue.

        //TODO function not yet used
     */
    private void scheduleServiceDetailDiscovery(int serviceHandle)
    {
        GattEntry serviceEntry = entries.get(serviceHandle);
        final int endHandle = serviceEntry.endHandle;

        if (serviceHandle == endHandle) {
            Logw(TAG, "scheduleServiceDetailDiscovery: service is empty; nothing to discover");
            finishCurrentServiceDiscovery(serviceHandle);
            return;
        }

        synchronized (readWriteQueue) {
            // entire block inside mutex to ensure all service discovery jobs go in one after the other
            // ensures that serviceDiscovered() signal is sent when required


            // serviceHandle + 1 -> ignore service handle itself
            for (int i = serviceHandle + 1; i <= endHandle; i++) {
                GattEntry entry = entries.get(i);

                switch (entry.type) {
                    case Characteristic:
                    case Descriptor:
                    // we schedule CharacteristicValue for initial discovery to simplify
                    // detection of the end of service discovery process
                    // performNextIO() ignores CharacteristicValue GATT entries
                    case CharacteristicValue:
                        break;
                    case Service:
                        // should not really happen unless endHandle is wrong
                        Logw(TAG, "scheduleServiceDetailDiscovery: wrong endHandle");
                        return;
                }

                // only descriptor and characteristic fall through to this point
                ReadWriteJob newJob = new ReadWriteJob();
                newJob.entry = entry;
                newJob.jobType = IoJobType.Read;

                final boolean result = readWriteQueue.add(newJob);
                if (!result)
                    Logw(TAG, "Cannot add service discovery job for " + serviceEntry.service.getUuid()
                                + " on item " + entry.type);
            }
        }
    }

    /*************************************************************/
    /* Write Characteristics                                     */
    /*************************************************************/

    public boolean writeCharacteristic(int charHandle, byte[] newValue,
                                       int writeMode)
    {
        if (mBluetoothGatt == null)
            return false;

        GattEntry entry;
        try {
            entry = entries.get(charHandle-1); //Qt always uses handles+1
        } catch (IndexOutOfBoundsException ex) {
            ex.printStackTrace();
            return false;
        }

        ReadWriteJob newJob = new ReadWriteJob();
        newJob.newValue = newValue;
        newJob.entry = entry;
        newJob.jobType = IoJobType.Write;

        // writeMode must be in sync with QLowEnergyService::WriteMode
        switch (writeMode) {
            case 1: //WriteWithoutResponse
                newJob.requestedWriteType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                break;
            case 2: //WriteSigned
                newJob.requestedWriteType = BluetoothGattCharacteristic.WRITE_TYPE_SIGNED;
                break;
            default:
                newJob.requestedWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                break;
        }

        boolean result;
        synchronized (readWriteQueue) {
            result = readWriteQueue.add(newJob);
        }

        if (!result) {
            Logw(TAG, "Cannot add characteristic write request for " + charHandle + " to queue" );
            return false;
        }

        performNextIO();
        return true;
    }

    /*************************************************************/
    /* Write Descriptors                                         */
    /*************************************************************/

    public boolean writeDescriptor(int descHandle, byte[] newValue)
    {
        if (mBluetoothGatt == null)
            return false;

        GattEntry entry;
        try {
            entry = entries.get(descHandle-1); //Qt always uses handles+1
        } catch (IndexOutOfBoundsException ex) {
            ex.printStackTrace();
            return false;
        }

        ReadWriteJob newJob = new ReadWriteJob();
        newJob.newValue = newValue;
        newJob.entry = entry;
        newJob.requestedWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        newJob.jobType = IoJobType.Write;

        boolean result;
        synchronized (readWriteQueue) {
            result = readWriteQueue.add(newJob);
        }

        if (!result) {
            Logw(TAG, "Cannot add descriptor write request for " + descHandle + " to queue" );
            return false;
        }

        performNextIO();
        return true;
    }

    /*************************************************************/
    /* Read Characteristics                                      */
    /*************************************************************/

    public boolean readCharacteristic(int charHandle)
    {
        if (mBluetoothGatt == null)
            return false;

        GattEntry entry;
        try {
            entry = entries.get(charHandle-1); //Qt always uses handles+1
        } catch (IndexOutOfBoundsException ex) {
            ex.printStackTrace();
            return false;
        }

        ReadWriteJob newJob = new ReadWriteJob();
        newJob.entry = entry;
        newJob.jobType = IoJobType.Read;

        boolean result;
        synchronized (readWriteQueue) {
            result = readWriteQueue.add(newJob);
        }

        if (!result) {
            Logw(TAG, "Cannot add characteristic read request for " + charHandle + " to queue" );
            return false;
        }

        performNextIO();
        return true;
    }

    public boolean readDescriptor(int descHandle)
    {
        if (mBluetoothGatt == null)
            return false;

        GattEntry entry;
        try {
            entry = entries.get(descHandle-1); //Qt always uses handles+1
        } catch (IndexOutOfBoundsException ex) {
            ex.printStackTrace();
            return false;
        }

        ReadWriteJob newJob = new ReadWriteJob();
        newJob.entry = entry;
        newJob.jobType = IoJobType.Read;

        boolean result;
        synchronized (readWriteQueue) {
            result = readWriteQueue.add(newJob);
        }

        if (!result) {
            Logw(TAG, "Cannot add descriptor read request for " + descHandle + " to queue" );
            return false;
        }

        performNextIO();
        return true;
    }

    // Called by TimeoutRunnable if the current I/O job timeoutd out.
    // By the time we reach this point the handleForTimeout counter has already been reset
    // and the regular responses will be blocked off.
    private void interruptCurrentIO(int handle)
    {
        //unlock the queue for next item
        synchronized (readWriteQueue) {
            ioJobPending = false;
        }

        performNextIO();

        if (handle == HANDLE_FOR_MTU_EXCHANGE)
            return;

        GattEntry entry = entries.get(handle);
        if (entry == null)
            return;
        if (entry.valueKnown)
            return;
        entry.valueKnown = true;

        GattEntry serviceEntry = entries.get(entry.associatedServiceHandle);
        if (serviceEntry != null && serviceEntry.endHandle == handle)
            finishCurrentServiceDiscovery(entry.associatedServiceHandle);
    }

    /*
       The queuing is required because two writeCharacteristic/writeDescriptor calls
       cannot execute at the same time. The second write must happen after the
       previous write has finished with on(Characteristic|Descriptor)Write().
    */
    private void performNextIO()
    {
        if (mBluetoothGatt == null)
            return;

        boolean skip = false;
        final ReadWriteJob nextJob;
        int handle = HANDLE_FOR_RESET;

        synchronized (readWriteQueue) {
            if (readWriteQueue.isEmpty() || ioJobPending)
                return;

            nextJob = readWriteQueue.remove();
            if (nextJob.jobType == IoJobType.Mtu) {
                handle = HANDLE_FOR_MTU_EXCHANGE; //mtu request is special case
            } else {
                switch (nextJob.entry.type) {
                    case Characteristic:
                        handle = handleForCharacteristic(nextJob.entry.characteristic);
                        break;
                    case Descriptor:
                        handle = handleForDescriptor(nextJob.entry.descriptor);
                        break;
                    case CharacteristicValue:
                        handle = nextJob.entry.endHandle;
                    default:
                        break;
                }
            }

            // timeout handler and handleForTimeout atomic must be setup before
            // executing the request. Sometimes the callback is quicker than executing the
            // remainder of this function. Therefore enable the atomic early such that
            // callback handlers start hanging in the readWriteQueue sync block which
            // we are still occupying here.
            timeoutHandler.removeCallbacksAndMessages(null); // remove any timeout handlers
            handleForTimeout.set(modifiedReadWriteHandle(handle, nextJob.jobType));

            switch (nextJob.jobType) {
                case Read:
                    skip = executeReadJob(nextJob);
                    break;
                case Write:
                    skip = executeWriteJob(nextJob);
                    break;
                case Mtu:
                    skip = executeMtuExchange();
                    break;
            }

            if (skip) {
                handleForTimeout.set(HANDLE_FOR_RESET); // not a pending call -> release atomic
            } else {
                ioJobPending = true;
                timeoutHandler.postDelayed(new TimeoutRunnable(
                        modifiedReadWriteHandle(handle, nextJob.jobType)), RUNNABLE_TIMEOUT);
            }

            if (nextJob.jobType != IoJobType.Mtu) {
                Logd(TAG, "Performing queued job, handle: " + handle + " " + nextJob.jobType + " (" +
                        (nextJob.requestedWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) +
                        ") ValueKnown: " + nextJob.entry.valueKnown + " Skipping: " + skip +
                        " " + nextJob.entry.type);
            }
        }

        GattEntry entry = nextJob.entry;

        if (skip) {
            /*
                BluetoothGatt.[read|write][Characteristic|Descriptor]() immediately
                return in cases where meta data doesn't match the intended action
                (e.g. trying to write to read-only char). When this happens
                we have to report an error back to Qt. The error report is not required during
                the initial service discovery though.
             */
            if (handle > HANDLE_FOR_RESET) {
                // during service discovery we do not report error but emit characteristicRead()
                // any other time a failure emits serviceError() signal

                final boolean isServiceDiscovery = !entry.valueKnown;

                if (isServiceDiscovery) {
                    entry.valueKnown = true;
                    switch (entry.type) {
                        case Characteristic:
                            Logd(TAG, "Non-readable characteristic " + entry.characteristic.getUuid() +
                                    " for service " + entry.characteristic.getService().getUuid());
                            leCharacteristicRead(qtObject, entry.characteristic.getService().getUuid().toString(),
                                    handle + 1, entry.characteristic.getUuid().toString(),
                                    entry.characteristic.getProperties(), entry.characteristic.getValue());
                            break;
                        case Descriptor:
                            // atm all descriptor types are readable
                            Logd(TAG, "Non-readable descriptor " + entry.descriptor.getUuid() +
                                    " for service/char" + entry.descriptor.getCharacteristic().getService().getUuid() +
                                    "/" + entry.descriptor.getCharacteristic().getUuid());
                            leDescriptorRead(qtObject,
                                    entry.descriptor.getCharacteristic().getService().getUuid().toString(),
                                    entry.descriptor.getCharacteristic().getUuid().toString(),
                                    handle + 1, entry.descriptor.getUuid().toString(),
                                    entry.descriptor.getValue());
                            break;
                        case CharacteristicValue:
                            // for more details see scheduleServiceDetailDiscovery(int)
                            break;
                        case Service:
                            Logw(TAG, "Scheduling of Service Gatt entry for service discovery should never happen.");
                            break;
                    }

                    // last entry of current discovery run?
                    GattEntry serviceEntry = entries.get(entry.associatedServiceHandle);
                    if (serviceEntry.endHandle == handle)
                        finishCurrentServiceDiscovery(entry.associatedServiceHandle);
                } else {
                    int errorCode = 0;

                    // The error codes below must be in sync with QLowEnergyService::ServiceError
                    if (nextJob.jobType == IoJobType.Read) {
                        errorCode = (entry.type == GattEntryType.Characteristic) ?
                                5 : 6; // CharacteristicReadError : DescriptorReadError
                    } else {
                        errorCode = (entry.type == GattEntryType.Characteristic) ?
                                2 : 3; // CharacteristicWriteError : DescriptorWriteError
                    }

                    leServiceError(qtObject, handle + 1, errorCode);
                }
            }

            performNextIO();
        }
    }

    // Runs inside the Mutex on readWriteQueue.
    // Returns true if nextJob should be skipped.
    private boolean executeWriteJob(ReadWriteJob nextJob)
    {
        boolean result;
        switch (nextJob.entry.type) {
            case Characteristic:
                if (nextJob.entry.characteristic.getWriteType() != nextJob.requestedWriteType) {
                    nextJob.entry.characteristic.setWriteType(nextJob.requestedWriteType);
                }
                result = nextJob.entry.characteristic.setValue(nextJob.newValue);
                if (!result || !mBluetoothGatt.writeCharacteristic(nextJob.entry.characteristic))
                    return true;
                break;
            case Descriptor:
                if (nextJob.entry.descriptor.getUuid().compareTo(clientCharacteristicUuid) == 0) {
                        /*
                            For some reason, Android splits characteristic notifications
                            into two operations. BluetoothGatt.enableCharacteristicNotification
                            ensures the local Bluetooth stack forwards the notifications. In addition,
                            BluetoothGattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            must be written to the peripheral.
                         */


                        /*  There is no documentation on indication behavior. The assumption is
                            that when indication or notification are requested we call
                            BluetoothGatt.setCharacteristicNotification. Furthermore it is assumed
                            indications are send via onCharacteristicChanged too and Android itself
                            will do the confirmation required for an indication as per
                            Bluetooth spec Vol 3, Part G, 4.11 . If neither of the two bits are set
                            we disable the signals.
                         */
                    boolean enableNotifications = false;
                    int value = (nextJob.newValue[0] & 0xff);
                    // first or second bit must be set
                    if (((value & 0x1) == 1) || (((value >> 1) & 0x1) == 1)) {
                        enableNotifications = true;
                    }

                    result = mBluetoothGatt.setCharacteristicNotification(
                            nextJob.entry.descriptor.getCharacteristic(), enableNotifications);
                    if (!result) {
                        Logw(TAG, "Cannot set characteristic notification");
                        //we continue anyway to ensure that we write the requested value
                        //to the device
                    }

                    Logd(TAG, "Enable notifications: " + enableNotifications);
                }

                result = nextJob.entry.descriptor.setValue(nextJob.newValue);
                if (!result || !mBluetoothGatt.writeDescriptor(nextJob.entry.descriptor))
                    return true;
                break;
            case Service:
            case CharacteristicValue:
                return true;
        }
        return false;
    }

    // Runs inside the Mutex on readWriteQueue.
    // Returns true if nextJob should be skipped.
    private boolean executeReadJob(ReadWriteJob nextJob)
    {
        boolean result;
        switch (nextJob.entry.type) {
            case Characteristic:
                try {
                    result = mBluetoothGatt.readCharacteristic(nextJob.entry.characteristic);
                } catch (java.lang.SecurityException se) {
                    // QTBUG-59917 -> HID services cause problems since Android 5.1
                    se.printStackTrace();
                    result = false;
                }
                if (!result)
                    return true; // skip
                break;
            case Descriptor:
                try {
                    result = mBluetoothGatt.readDescriptor(nextJob.entry.descriptor);
                } catch (java.lang.SecurityException se) {
                    // QTBUG-59917 -> HID services cause problems since Android 5.1
                    se.printStackTrace();
                    result = false;
                }
                if (!result)
                    return true; // skip
                break;
            case Service:
                return true;
            case CharacteristicValue:
                return true; //skip
        }
        return false;
    }

    /*
     *  Modifies and returns the given \a handle such that the job
     *  \a type is encoded into the returned handle. Hereby we take advantage of the fact that
     *  a Bluetooth Low Energy handle is only 16 bit. The handle will be the bottom two bytes
     *  and the job type will be in the top 2 bytes.
     *
     *  top 2 bytes
     *   - 0x01 -> Read Job
     *   - 0x02 -> Write Job
     *
     *   This is done in connection with handleForTimeout and assists in the process of
     *   detecting accidental interruption by the timeout handler.
     *   If two requests for the same handle are scheduled behind each other there is the
     *   theoretical chance that the first request comes back normally while the second request
     *   is interrupted by the timeout handler. This risk still exists but this function ensures that
     *   at least back to back requests of differing types cannot affect each other via the timeout
     *   handler.
     */
    private int modifiedReadWriteHandle(int handle, IoJobType type)
    {
        int modifiedHandle = handle;
        // ensure we have 16bit handle only
        if (handle > 0xFFFF)
            Logw(TAG, "Invalid handle");

        modifiedHandle = (modifiedHandle & 0xFFFF);

        switch (type) {
            case Write:
                modifiedHandle = (modifiedHandle | 0x00010000);
                break;
            case Read:
                modifiedHandle = (modifiedHandle | 0x00020000);
                break;
            case Mtu:
                modifiedHandle = HANDLE_FOR_MTU_EXCHANGE;
                break;
        }

        return modifiedHandle;
    }

    // Directly called from public Qt API
    public boolean requestConnectionUpdatePriority(double minimalInterval)
    {
        if (mBluetoothGatt == null)
            return false;

        try {
            //Android API v21
            Method connectionUpdateMethod = mBluetoothGatt.getClass().getDeclaredMethod(
                                                "requestConnectionPriority", int.class);
            if (connectionUpdateMethod == null)
                return false;

            int requestPriority = 0; // BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            if (minimalInterval < 30)
                requestPriority = 1; // BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else if (minimalInterval > 100)
                requestPriority = 2; //BluetoothGatt/CONNECTION_PRIORITY_LOW_POWER

            Object result = connectionUpdateMethod.invoke(mBluetoothGatt, requestPriority);
            return (Boolean) result;
        } catch (Exception ex) {
            return false;
        }
    }

    public native void leConnectionStateChange(long qtObject, int wasErrorTransition, int newState);
    public native void leServicesDiscovered(long qtObject, int errorCode, String uuidList);
    public native void leServiceDetailDiscoveryFinished(long qtObject, final String serviceUuid,
                                                        int startHandle, int endHandle);
    public native void leCharacteristicRead(long qtObject, String serviceUuid,
                                            int charHandle, String charUuid,
                                            int properties, byte[] data);
    public native void leDescriptorRead(long qtObject, String serviceUuid, String charUuid,
                                        int descHandle, String descUuid, byte[] data);
    public native void leCharacteristicWritten(long qtObject, int charHandle, byte[] newData,
                                               int errorCode);
    public native void leDescriptorWritten(long qtObject, int charHandle, byte[] newData,
                                           int errorCode);
    public native void leCharacteristicChanged(long qtObject, int charHandle, byte[] newData);
    public native void leServiceError(long qtObject, int attributeHandle, int errorCode);
}
