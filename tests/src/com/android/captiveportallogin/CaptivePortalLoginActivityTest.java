/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.captiveportallogin;

import static android.net.ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN;
import static android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL;
import static android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL;
import static android.net.ConnectivityManager.EXTRA_NETWORK;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;
import android.net.CaptivePortal;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CaptivePortalLoginActivityTest {
    private static final String TEST_URL = "http://android.test.com";
    private static final int TEST_NETID = 1234;
    private CaptivePortalLoginActivity mActivity;
    private MockitoSession mSession;
    private Network mNetwork = new Network(TEST_NETID);

    /** Class to replace CaptivePortal to prevent mock object is updated and replaced by parcel. */
    public static class MockCaptivePortal extends CaptivePortal {
        int mDismissTimes;
        int mIgnoreTimes;
        int mUseTimes;

        private MockCaptivePortal() {
            this(0, 0, 0);
        }
        private MockCaptivePortal(int dismissTimes, int ignoreTimes, int useTimes) {
            super(null);
            mDismissTimes = dismissTimes;
            mIgnoreTimes = ignoreTimes;
            mUseTimes = useTimes;
        }
        @Override
        public void reportCaptivePortalDismissed() {
            mDismissTimes++;
        }

        @Override
        public void ignoreNetwork() {
            mIgnoreTimes++;
        }

        @Override
        public void useNetwork() {
            mUseTimes++;
        }

        @Override
        public void logEvent(int eventId, String packageName) {
            // Do nothing
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(mDismissTimes);
            out.writeInt(mIgnoreTimes);
            out.writeInt(mUseTimes);
        }

        public static final Parcelable.Creator<MockCaptivePortal> CREATOR =
                new Parcelable.Creator<MockCaptivePortal>() {
                @Override
                public MockCaptivePortal createFromParcel(Parcel in) {
                    return new MockCaptivePortal(in.readInt(), in.readInt(), in.readInt());
                }

                @Override
                public MockCaptivePortal[] newArray(int size) {
                    return new MockCaptivePortal[size];
                }
        };
    }

    // TODO: Update to ActivityScenarioRule.
    @Rule
    public final ActivityTestRule mActivityRule =
            new ActivityTestRule<>(CaptivePortalLoginActivity.class, false /* initialTouchMode */,
                    false  /* launchActivity */);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSession = mockitoSession()
                .spyStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        final Context context = InstrumentationRegistry.getContext();
        setDismissPortalInValidatedNetwork(true);
        // onCreate will be triggered in launchActivity(). Handle mock objects after
        // launchActivity() if any new mock objects. Activity launching flow will be
        //  1. launchActivity()
        //  2. onCreate()
        //  3. end of launchActivity()
        mActivity = (CaptivePortalLoginActivity) mActivityRule.launchActivity(
            new Intent(ACTION_CAPTIVE_PORTAL_SIGN_IN)
                .putExtra(EXTRA_CAPTIVE_PORTAL_URL, TEST_URL)
                .putExtra(EXTRA_NETWORK, mNetwork)
                .putExtra(EXTRA_CAPTIVE_PORTAL, new MockCaptivePortal())
        );
        // Verify activity created successfully.
        assertNotNull(mActivity);
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
        mActivityRule.finishActivity();
    }

    private void notifyCapabilitiesChanged(final NetworkCapabilities nc) {
        mActivity.handleCapabilitiesChanged(mNetwork, nc);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void verifyDismissed() {
        final MockCaptivePortal cp = (MockCaptivePortal) mActivity.mCaptivePortal;
        assertEquals(cp.mDismissTimes, 1);
        assertEquals(cp.mIgnoreTimes, 0);
        assertEquals(cp.mUseTimes, 0);
    }

    private void notifyValidatedChangedAndDismissed(final NetworkCapabilities nc) {
        notifyCapabilitiesChanged(nc);
        verifyDismissed();
    }

    private void verifyNotDone() {
        final MockCaptivePortal cp = (MockCaptivePortal) mActivity.mCaptivePortal;
        assertEquals(cp.mDismissTimes, 0);
        assertEquals(cp.mIgnoreTimes, 0);
        assertEquals(cp.mUseTimes, 0);
    }

    private void notifyValidatedChangedNotDone(final NetworkCapabilities nc) {
        notifyCapabilitiesChanged(nc);
        verifyNotDone();
    }

    private void setDismissPortalInValidatedNetwork(final boolean enable) {
        // Feature is enabled if the package version greater than configuration. Instead of reading
        // the package version, use Long.MAX_VALUE to replace disable configuration and 1 for
        // enabling.
        doReturn(enable ? 1 : Long.MAX_VALUE).when(() -> DeviceConfig.getLong(
                NAMESPACE_CONNECTIVITY,
                CaptivePortalLoginActivity.DISMISS_PORTAL_IN_VALIDATED_NETWORK, 0 /* default */));
    }

    @Test
    public void testNetworkCapabilitiesUpdate() throws Exception {
        // NetworkCapabilities updates w/o NET_CAPABILITY_VALIDATED.
        final NetworkCapabilities nc = new NetworkCapabilities();
        notifyValidatedChangedNotDone(nc);

        // NetworkCapabilities updates w/ NET_CAPABILITY_VALIDATED.
        nc.setCapability(NET_CAPABILITY_VALIDATED, true);
        notifyValidatedChangedAndDismissed(nc);
    }

    @Test
    public void testNetworkCapabilitiesUpdateWithFlag() throws Exception {
        final NetworkCapabilities nc = new NetworkCapabilities();
        nc.setCapability(NET_CAPABILITY_VALIDATED, true);
        // Disable flag. Auto-dismiss should not happen.
        setDismissPortalInValidatedNetwork(false);
        notifyValidatedChangedNotDone(nc);

        // Enable flag. Auto-dismissed.
        setDismissPortalInValidatedNetwork(true);
        notifyValidatedChangedAndDismissed(nc);
    }
}
