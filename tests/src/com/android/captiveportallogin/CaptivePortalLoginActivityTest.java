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

import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.isInternal;
import static androidx.test.espresso.web.sugar.Web.onWebView;
import static androidx.test.espresso.web.webdriver.DriverAtoms.findElement;
import static androidx.test.espresso.web.webdriver.DriverAtoms.webClick;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;

import static junit.framework.Assert.assertEquals;

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.intent.rule.IntentsTestRule;
import androidx.test.espresso.web.webdriver.Locator;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.function.BooleanSupplier;

import fi.iki.elonen.NanoHTTPD;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CaptivePortalLoginActivityTest {
    private static final String TEST_URL = "http://android.test.com";
    private static final int TEST_NETID = 1234;
    private static final String TEST_URL_QUERY = "testquery";
    private static final long TEST_TIMEOUT_MS = 10_000L;
    private static final LinkAddress TEST_LINKADDR = new LinkAddress(
            InetAddresses.parseNumericAddress("2001:db8::8"), 64);
    private CaptivePortalLoginActivity mActivity;
    private MockitoSession mSession;
    private Network mNetwork = new Network(TEST_NETID);
    private TestNetworkTracker mTestNetworkTracker;

    private static ConnectivityManager sConnectivityManager;
    private static DevicePolicyManager sMockDevicePolicyManager;

    public static class InstrumentedCaptivePortalLoginActivity extends CaptivePortalLoginActivity {
        @Override
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return sConnectivityManager;
            if (Context.DEVICE_POLICY_SERVICE.equals(name)) return sMockDevicePolicyManager;
            return super.getSystemService(name);
        }
    }

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

    @Rule
    public final IntentsTestRule mActivityRule =
            new IntentsTestRule<>(InstrumentedCaptivePortalLoginActivity.class,
                    false /* initialTouchMode */, false  /* launchActivity */);

    @Before
    public void setUp() throws Exception {
        final Context context = getInstrumentation().getContext();
        sConnectivityManager = spy(context.getSystemService(ConnectivityManager.class));
        sMockDevicePolicyManager = mock(DevicePolicyManager.class);
        MockitoAnnotations.initMocks(this);
        mSession = mockitoSession()
                .spyStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        setDismissPortalInValidatedNetwork(true);
        // Use a real (but test) network for the application. The application will bind its process
        // to the given network. Using a mock network object would not allow loading a portal from
        // a real server, even from localhost, while using an actual network (even on a tun
        // interface) allows the phone to create a test server on localhost and have WebView open
        // the portal on it.
        mTestNetworkTracker = initTestNetwork(
                getInstrumentation().getContext(), TEST_LINKADDR, TEST_TIMEOUT_MS);
        mNetwork = mTestNetworkTracker.getNetwork();
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
        mActivityRule.finishActivity();
        getInstrumentation().getContext().getSystemService(ConnectivityManager.class)
                .bindProcessToNetwork(null);
        mTestNetworkTracker.teardown();
    }

    private void initActivity(String url) {
        // onCreate will be triggered in launchActivity(). Handle mock objects after
        // launchActivity() if any new mock objects. Activity launching flow will be
        //  1. launchActivity()
        //  2. onCreate()
        //  3. end of launchActivity()
        mActivity = (InstrumentedCaptivePortalLoginActivity) mActivityRule.launchActivity(
            new Intent(ACTION_CAPTIVE_PORTAL_SIGN_IN)
                .putExtra(EXTRA_CAPTIVE_PORTAL_URL, url)
                .putExtra(EXTRA_NETWORK, mNetwork)
                .putExtra(EXTRA_CAPTIVE_PORTAL, new MockCaptivePortal())
        );
        // Verify activity created successfully.
        assertNotNull(mActivity);
        getInstrumentation().getContext().getSystemService(KeyguardManager.class)
                .requestDismissKeyguard(mActivity, null);
        // Dismiss dialogs or notification shade, so that the test can interact with the activity.
        mActivity.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    private MockCaptivePortal getCaptivePortal() {
        return (MockCaptivePortal) mActivity.mCaptivePortal;
    }

    private void configNonVpnNetwork() {
        final Network[] networks = new Network[] {new Network(mNetwork)};
        doReturn(networks).when(sConnectivityManager).getAllNetworks();
        final NetworkCapabilities nonVpnCapabilities = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        doReturn(nonVpnCapabilities).when(sConnectivityManager).getNetworkCapabilities(
                mNetwork);
    }

    private void configVpnNetwork() {
        final Network network1 = new Network(TEST_NETID + 1);
        final Network network2 = new Network(TEST_NETID + 2);
        final Network[] networks = new Network[] {network1, network2};
        doReturn(networks).when(sConnectivityManager).getAllNetworks();
        final NetworkCapabilities underlyingCapabilities = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        final NetworkCapabilities vpnCapabilities = new NetworkCapabilities(underlyingCapabilities)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        doReturn(underlyingCapabilities).when(sConnectivityManager).getNetworkCapabilities(
                network1);
        doReturn(vpnCapabilities).when(sConnectivityManager).getNetworkCapabilities(network2);
    }

    @Test
    public void testHasVpnNetwork() throws Exception {
        initActivity(TEST_URL);
        // Test non-vpn case.
        configNonVpnNetwork();
        assertFalse(mActivity.hasVpnNetwork());
        // Test vpn case.
        configVpnNetwork();
        assertTrue(mActivity.hasVpnNetwork());
    }

    @Test
    public void testIsAlwaysOnVpnEnabled() throws Exception {
        initActivity(TEST_URL);
        doReturn(false).when(sMockDevicePolicyManager).isAlwaysOnVpnLockdownEnabled(any());
        assertFalse(mActivity.isAlwaysOnVpnEnabled());
        doReturn(true).when(sMockDevicePolicyManager).isAlwaysOnVpnLockdownEnabled(any());
        assertTrue(mActivity.isAlwaysOnVpnEnabled());
    }

    @Test
    public void testVpnMsgOrLinkToBrowser() throws Exception {
        initActivity(TEST_URL);
        // Test non-vpn case.
        configNonVpnNetwork();
        doReturn(false).when(sMockDevicePolicyManager).isAlwaysOnVpnLockdownEnabled(any());
        final String linkMatcher = ".*<a[^>]+href.*";
        assertTrue(mActivity.getWebViewClient().getVpnMsgOrLinkToBrowser().matches(linkMatcher));

        // Test has vpn case.
        configVpnNetwork();
        final String vpnMatcher = ".*<div.*vpnwarning.*";
        assertTrue(mActivity.getWebViewClient().getVpnMsgOrLinkToBrowser().matches(vpnMatcher));

        // Test always-on vpn case.
        configNonVpnNetwork();
        doReturn(true).when(sMockDevicePolicyManager).isAlwaysOnVpnLockdownEnabled(any());
        assertTrue(mActivity.getWebViewClient().getVpnMsgOrLinkToBrowser().matches(vpnMatcher));
    }

    private void notifyCapabilitiesChanged(final NetworkCapabilities nc) {
        mActivity.handleCapabilitiesChanged(mNetwork, nc);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void verifyDismissed() {
        final MockCaptivePortal cp = getCaptivePortal();
        assertEquals(cp.mDismissTimes, 1);
        assertEquals(cp.mIgnoreTimes, 0);
        assertEquals(cp.mUseTimes, 0);
    }

    private void notifyValidatedChangedAndDismissed(final NetworkCapabilities nc) {
        notifyCapabilitiesChanged(nc);
        verifyDismissed();
    }

    private void verifyNotDone() {
        final MockCaptivePortal cp = getCaptivePortal();
        assertEquals(cp.mDismissTimes, 0);
        assertEquals(cp.mIgnoreTimes, 0);
        assertEquals(cp.mUseTimes, 0);
    }

    private void notifyValidatedChangedNotDone(final NetworkCapabilities nc) {
        notifyCapabilitiesChanged(nc);
        verifyNotDone();
    }

    private void verifyUseAsIs() {
        final MockCaptivePortal cp = getCaptivePortal();
        assertEquals(cp.mDismissTimes, 0);
        assertEquals(cp.mIgnoreTimes, 0);
        assertEquals(cp.mUseTimes, 1);
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
        initActivity(TEST_URL);
        // NetworkCapabilities updates w/o NET_CAPABILITY_VALIDATED.
        final NetworkCapabilities nc = new NetworkCapabilities();
        notifyValidatedChangedNotDone(nc);

        // NetworkCapabilities updates w/ NET_CAPABILITY_VALIDATED.
        nc.setCapability(NET_CAPABILITY_VALIDATED, true);
        notifyValidatedChangedAndDismissed(nc);
    }

    @Test
    public void testNetworkCapabilitiesUpdateWithFlag() throws Exception {
        initActivity(TEST_URL);
        final NetworkCapabilities nc = new NetworkCapabilities();
        nc.setCapability(NET_CAPABILITY_VALIDATED, true);
        // Disable flag. Auto-dismiss should not happen.
        setDismissPortalInValidatedNetwork(false);
        notifyValidatedChangedNotDone(nc);

        // Enable flag. Auto-dismissed.
        setDismissPortalInValidatedNetwork(true);
        notifyValidatedChangedAndDismissed(nc);
    }

    private HttpServer runCustomSchemeTest(String linkUri) throws Exception {
        final HttpServer server = new HttpServer();
        server.setResponseBody(TEST_URL_QUERY,
                "<a id='tst_link' href='" + linkUri + "'>Test link</a>");

        server.start();
        ActivityScenario.launch(RequestDismissKeyguardActivity.class);
        initActivity(server.makeUrl(TEST_URL_QUERY));
        // Mock all external intents
        intending(not(isInternal())).respondWith(new ActivityResult(Activity.RESULT_OK, null));

        onWebView().withElement(findElement(Locator.ID, "tst_link")).perform(webClick());
        getInstrumentation().waitForIdleSync();
        return server;
    }

    @Test
    public void testTelScheme() throws Exception {
        final String telUri = "tel:0123456789";
        final HttpServer server = runCustomSchemeTest(telUri);

        final Intent sentIntent = Intents.getIntents().get(0);
        assertEquals(Intent.ACTION_DIAL, sentIntent.getAction());
        assertEquals(Uri.parse(telUri), sentIntent.getData());

        server.stop();
    }

    @Test
    public void testSmsScheme() throws Exception {
        final String telUri = "sms:0123456789";
        final HttpServer server = runCustomSchemeTest(telUri);

        final Intent sentIntent = Intents.getIntents().get(0);
        assertEquals(Intent.ACTION_SENDTO, sentIntent.getAction());
        assertEquals(Uri.parse(telUri), sentIntent.getData());

        server.stop();
    }

    @Test
    public void testUnsupportedScheme() throws Exception {
        final HttpServer server = runCustomSchemeTest("mailto:test@example.com");
        assertEquals(0, Intents.getIntents().size());

        onWebView().withElement(findElement(Locator.ID, "continue_link"))
                .perform(webClick());

        // The intent is sent in onDestroy(); there is no way to wait for that event, so poll
        // until the intent is found.
        assertTrue(isEventually(() -> Intents.getIntents().size() == 1, TEST_TIMEOUT_MS));
        verifyUseAsIs();
        final Intent sentIntent = Intents.getIntents().get(0);
        assertEquals(Intent.ACTION_VIEW, sentIntent.getAction());
        assertEquals(Uri.parse(server.makeUrl(TEST_URL_QUERY)), sentIntent.getData());

        server.stop();
    }

    private static boolean isEventually(BooleanSupplier condition, long timeout)
            throws InterruptedException {
        final long start = System.currentTimeMillis();
        do {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        } while ((System.currentTimeMillis() - start) < timeout);

        return false;
    }

    private static class HttpServer extends NanoHTTPD {
        private final ServerSocket mSocket;
        // Responses per URL query
        private final HashMap<String, String> mResponseBodies = new HashMap<>();

        HttpServer() throws IOException {
            this(new ServerSocket());
        }

        private HttpServer(ServerSocket socket) {
            // 0 as port for picking a port automatically
            super("localhost", 0);
            mSocket = socket;
        }

        @Override
        public ServerSocketFactory getServerSocketFactory() {
            return () -> mSocket;
        }

        private String makeUrl(String query) {
            return new Uri.Builder()
                    .scheme("http")
                    .encodedAuthority("localhost:" + mSocket.getLocalPort())
                    .query(query)
                    .build()
                    .toString();
        }

        private void setResponseBody(String query, String body) {
            mResponseBodies.put(query, body);
        }

        @Override
        public Response serve(IHTTPSession session) {
            final String body = mResponseBodies.get(session.getQueryParameterString());
            if (body == null) {
                // Default response is a 404
                return super.serve(session);
            }

            return newFixedLengthResponse("<!doctype html>"
                    + "<html>"
                    + "<head><title>Test portal</title></head>"
                    + "<body>" + body + "</body>"
                    + "</html>");
        }
    }
}
