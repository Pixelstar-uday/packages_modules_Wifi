/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wifi;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiContext;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Message;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.WaitingState;
import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit test harness for InterfaceConflictManager.
 */
@SmallTest
public class InterfaceConflictManagerTest {
    private TestLooper mTestLooper;
    private InterfaceConflictManager mDut;

    @Mock WifiContext mWifiContext;
    @Mock Resources mResources;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock HalDeviceManager mHdm;
    @Mock StateMachine mStateMachine;
    @Mock State mTargetState;
    @Mock WaitingState mWaitingState;
    @Mock WifiDialogManager mWifiDialogManager;
    @Mock WifiDialogManager.DialogHandle mDialogHandle;

    private static final int TEST_UID = 1234;
    private static final String TEST_PACKAGE_NAME = "some.package.name";
    private static final String TEST_APP_NAME = "Some App Name";
    private static final WorkSource TEST_WS = new WorkSource(TEST_UID, TEST_PACKAGE_NAME);

    ArgumentCaptor<WifiDialogManager.SimpleDialogCallback> mCallbackCaptor =
            ArgumentCaptor.forClass(WifiDialogManager.SimpleDialogCallback.class);

    /**
     * Initializes mocks.
     */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestLooper = new TestLooper();

        // enable user approval (needed for most tests)
        when(mWifiContext.getResources()).thenReturn(mResources);
        when(mResources.getBoolean(
                R.bool.config_wifiUserApprovalRequiredForD2dInterfacePriority)).thenReturn(true);

        when(mFrameworkFacade.getAppName(any(), anyString(), anyInt())).thenReturn(TEST_APP_NAME);
        when(mWifiDialogManager.createLegacySimpleDialog(any(), any(), any(), any(), any(), any(),
                any())).thenReturn(mDialogHandle);
    }

    private void initInterfaceConflictManager() {
        mDut = new InterfaceConflictManager(mWifiContext, mFrameworkFacade, mHdm,
                new WifiThreadRunner(new Handler(mTestLooper.getLooper())), mWifiDialogManager);
    }

    /**
     * Verify that w/o user approval enabled will always continue operation
     */
    @Test
    public void testUserApprovalDisabled() {
        // disable user approval
        when(mResources.getBoolean(
                R.bool.config_wifiUserApprovalRequiredForD2dInterfacePriority)).thenReturn(false);

        initInterfaceConflictManager();

        assertEquals(InterfaceConflictManager.ICM_EXECUTE_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", Message.obtain(),
                        mStateMachine, mWaitingState, mTargetState,
                        HalDeviceManager.HDM_CREATE_IFACE_NAN, TEST_WS));

        verify(mStateMachine, never()).transitionTo(mWaitingState);
        verify(mWifiDialogManager, never()).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, never()).launchDialog();
    }

    /**
     * Verify that requests from packages exempt from user approval will always continue operation
     */
    @Test
    public void testUserApprovalDisabledForSpecificPackage() {
        // disable user approval for specific package
        when(mResources.getStringArray(
                R.array.config_wifiExcludedFromUserApprovalForD2dInterfacePriority)).thenReturn(
                new String[]{TEST_PACKAGE_NAME});

        initInterfaceConflictManager();

        assertEquals(InterfaceConflictManager.ICM_EXECUTE_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", Message.obtain(),
                        mStateMachine, mWaitingState, mTargetState,
                        HalDeviceManager.HDM_CREATE_IFACE_NAN, TEST_WS));

        verify(mStateMachine, never()).transitionTo(mWaitingState);
        verify(mWifiDialogManager, never()).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, never()).launchDialog();
    }

    /**
     * Verify that if interface cannot be created or if interface can be created w/o side effects
     * then command simply proceeds.
     */
    @Test
    public void testUserApprovalNeededButCommandCanProceed() {
        initInterfaceConflictManager();

        int interfaceType = HalDeviceManager.HDM_CREATE_IFACE_NAN;
        Message msg = Message.obtain();

        // can't create interface
        when(mHdm.reportImpactToCreateIface(eq(interfaceType), eq(false), eq(TEST_WS))).thenReturn(
                null);
        assertEquals(InterfaceConflictManager.ICM_EXECUTE_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg,
                        mStateMachine, mWaitingState, mTargetState,
                        interfaceType, TEST_WS));
        verify(mStateMachine, never()).transitionTo(mWaitingState);
        verify(mStateMachine, never()).deferMessage(msg);
        verify(mWifiDialogManager, never()).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, never()).launchDialog();

        // can create interface w/o side effects
        when(mHdm.reportImpactToCreateIface(eq(interfaceType), eq(false), eq(TEST_WS))).thenReturn(
                Collections.emptyList());
        assertEquals(InterfaceConflictManager.ICM_EXECUTE_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg,
                        mStateMachine, mWaitingState, mTargetState,
                        interfaceType, TEST_WS));
        verify(mStateMachine, never()).transitionTo(mWaitingState);
        verify(mStateMachine, never()).deferMessage(msg);
        verify(mWifiDialogManager, never()).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, never()).launchDialog();
    }

    /**
     * Verify flow with user approval.
     */
    @Test
    public void testUserApproved() {
        initInterfaceConflictManager();

        int interfaceType = HalDeviceManager.HDM_CREATE_IFACE_P2P;
        Message msg = Message.obtain();

        // can create interface - but with side effects
        when(mHdm.reportImpactToCreateIface(eq(interfaceType), eq(false), eq(TEST_WS))).thenReturn(
                Arrays.asList(Pair.create(HalDeviceManager.HDM_CREATE_IFACE_NAN,
                        new WorkSource(10, "something else"))));

        // send request
        assertEquals(InterfaceConflictManager.ICM_SKIP_COMMAND_WAIT_FOR_USER,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg,
                        mStateMachine, mWaitingState, mTargetState,
                        interfaceType, TEST_WS));
        verify(mStateMachine).transitionTo(mWaitingState);
        verify(mStateMachine).deferMessage(msg);
        verify(mWifiDialogManager).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), mCallbackCaptor.capture(), any());
        verify(mDialogHandle).launchDialog();

        // user approve
        mCallbackCaptor.getValue().onPositiveButtonClicked();
        verify(mWaitingState).sendTransitionStateCommand(mTargetState);

        // re-execute command and get indication to proceed without waiting/dialog
        assertEquals(InterfaceConflictManager.ICM_EXECUTE_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg, mStateMachine,
                        mWaitingState, mTargetState, interfaceType, TEST_WS));
        verify(mStateMachine, times(1)).transitionTo(mWaitingState);
        verify(mStateMachine, times(1)).deferMessage(msg);
        verify(mWifiDialogManager, times(1)).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, times(1)).launchDialog();
    }

    /**
     * Verify flow with user rejection.
     */
    @Test
    public void testUserRejected() {
        initInterfaceConflictManager();

        int interfaceType = HalDeviceManager.HDM_CREATE_IFACE_P2P;
        Message msg = Message.obtain();

        // can create interface - but with side effects
        when(mHdm.reportImpactToCreateIface(eq(interfaceType), eq(false), eq(TEST_WS))).thenReturn(
                Arrays.asList(Pair.create(HalDeviceManager.HDM_CREATE_IFACE_NAN,
                        new WorkSource(10, "something else"))));

        // send request
        assertEquals(InterfaceConflictManager.ICM_SKIP_COMMAND_WAIT_FOR_USER,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg,
                        mStateMachine, mWaitingState, mTargetState,
                        interfaceType, TEST_WS));
        verify(mStateMachine).transitionTo(mWaitingState);
        verify(mStateMachine).deferMessage(msg);
        verify(mWifiDialogManager).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), mCallbackCaptor.capture(), any());
        verify(mDialogHandle).launchDialog();

        // user rejects
        mCallbackCaptor.getValue().onNegativeButtonClicked();
        verify(mWaitingState).sendTransitionStateCommand(mTargetState);

        // re-execute command and get indication to abort
        assertEquals(InterfaceConflictManager.ICM_ABORT_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg, mStateMachine,
                        mWaitingState, mTargetState, interfaceType, TEST_WS));
        verify(mStateMachine, times(1)).transitionTo(mWaitingState);
        verify(mStateMachine, times(1)).deferMessage(msg);
        verify(mWifiDialogManager, times(1)).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, times(1)).launchDialog();
    }

    @Test
    public void testP2pInterfaceRemovalIsAutoApprovedWhenP2pIsDisconnected()
            throws Exception {
        when(mResources.getBoolean(R.bool.config_wifiUserApprovalNotRequireForDisconnectedP2p))
                .thenReturn(true);

        initInterfaceConflictManager();

        ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mWifiContext).registerReceiver(receiver.capture(), any(IntentFilter.class));

        // Notify that P2P is disconnected.
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        NetworkInfo info = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P,
                0, "WIFI_P2P", "");
        info.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, info);
        receiver.getValue().onReceive(mWifiContext, intent);

        int interfaceType = HalDeviceManager.HDM_CREATE_IFACE_NAN;
        Message msg = Message.obtain();

        // can create interface - but with side effects
        when(mHdm.reportImpactToCreateIface(eq(interfaceType), eq(false), eq(TEST_WS))).thenReturn(
                Arrays.asList(Pair.create(HalDeviceManager.HDM_CREATE_IFACE_P2P,
                        new WorkSource(10, "something else"))));

        // send request
        assertEquals(InterfaceConflictManager.ICM_EXECUTE_COMMAND,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg,
                        mStateMachine, mWaitingState, mTargetState,
                        interfaceType, TEST_WS));
        verify(mStateMachine, never()).transitionTo(mWaitingState);
        verify(mStateMachine, never()).deferMessage(msg);
        verify(mWifiDialogManager, never()).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle, never()).launchDialog();
    }

    @Test
    public void testP2pInterfaceRemovalNeedUserApprovalWhenP2pIsConnected()
            throws Exception {
        when(mResources.getBoolean(R.bool.config_wifiUserApprovalNotRequireForDisconnectedP2p))
                .thenReturn(true);

        initInterfaceConflictManager();

        ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mWifiContext).registerReceiver(receiver.capture(), any(IntentFilter.class));

        // Notify that P2P is connected.
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        NetworkInfo info = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P,
                0, "WIFI_P2P", "");
        info.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, info);
        receiver.getValue().onReceive(mWifiContext, intent);

        int interfaceType = HalDeviceManager.HDM_CREATE_IFACE_NAN;
        Message msg = Message.obtain();

        // can create interface - but with side effects
        when(mHdm.reportImpactToCreateIface(eq(interfaceType), eq(false), eq(TEST_WS))).thenReturn(
                Arrays.asList(Pair.create(HalDeviceManager.HDM_CREATE_IFACE_P2P,
                        new WorkSource(10, "something else"))));

        // send request
        assertEquals(InterfaceConflictManager.ICM_SKIP_COMMAND_WAIT_FOR_USER,
                mDut.manageInterfaceConflictForStateMachine("Some Tag", msg,
                        mStateMachine, mWaitingState, mTargetState,
                        interfaceType, TEST_WS));
        verify(mStateMachine).transitionTo(mWaitingState);
        verify(mStateMachine).deferMessage(msg);
        verify(mWifiDialogManager).createLegacySimpleDialog(any(), any(), any(), any(),
                any(), any(), any());
        verify(mDialogHandle).launchDialog();
    }
}
