package com.stolsvik.mats.spring.test.apptest1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.spring.test.MatsTestProfile;
import com.stolsvik.mats.spring.test.SpringTestDataTO;
import com.stolsvik.mats.test.MatsTestHelp;
import com.stolsvik.mats.util.MatsFuturizer;
import com.stolsvik.mats.util.MatsFuturizer.Reply;

/**
 * This test directly employs the full application Spring configuration, thus taking up all components, Mats Endpoints,
 * MatsFuturizer and everything. It uses the {@link MatsTestProfile} annotation (which only is specifying which Spring
 * Profile to take up, it is directly equivalent to <code>@ActiveProfiles(MatsProfiles.PROFILE_MATS_TEST)</code>), which
 * ensures that the "magic ActiveMQ decider" inside the application's configuration changes which ActiveMQ it connects
 * to: Instead of connecting to an external ActiveMQ, it takes up an in-vm ActiveMQ and connects to this.
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = AppMain_MockAndTestingHarnesses.class)
@MatsTestProfile // This overrides the configured ConnectionFactories in the app to be LocalVM testing instances.
public class Test_A_UseFullApplicationConfiguration {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private AppServiceCalculator _calculator;

    @Inject
    private AppServiceMatsInvoker _matsInvoker;

    private void matsEndpointTest(double number, String string) {
        SpringTestDataTO dto = new SpringTestDataTO(number, string);
        CompletableFuture<Reply<SpringTestDataTO>> future = _matsFuturizer.futurizeNonessential(MatsTestHelp.traceId(),
                MatsTestHelp.from("testX"),
                AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINSERVICE,
                SpringTestDataTO.class, dto);

        SpringTestDataTO reply;
        try {
            reply = future.get(10, TimeUnit.SECONDS).getReply();
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Didn't get reply", e);
        }

        Assert.assertEquals(expectedNumber(dto.number), reply.number, 0d);
        Assert.assertEquals(expectedString(dto.string), reply.string);
    }

    static double expectedNumber(double fromNumber) {
        return fromNumber * AppServiceCalculator.Π * AppServiceCalculator.Φ * fromNumber;
    }

    static String expectedString(String from) {
        return from
                + ":(π=" + AppServiceCalculator.Π + "):" + AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFSERVICE1
                + ":(φ=" + AppServiceCalculator.Φ + "):" + AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFSERVICE2
                + ":" + AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINSERVICE;
    }

    // Running the test a few times, to check that the network "stays up" between tests.
    //
    // NOTICE: JUnit actually creates a new instance of the test class for each test, i.e. each of the methods below is
    // run within a new instance. This implies that the injection of the fields in this class is done multiple times,
    // i.e. for each new test. However, the Spring Context stays the same between the tests, thus the injected fields
    // get the same values each time.

    @Test
    public void matsEndpointTest1() {
        matsEndpointTest(10, "StartString");
    }

    @Test
    public void matsEndpointTest2() {
        matsEndpointTest(20, "BeginnerString");
    }

    @Test
    public void matsEndpointTest3() {
        matsEndpointTest(42, "TheAnswer");
    }

    /**
     * Employing the injected {@link AppServiceMatsInvoker} and utilizes that to query the endpoint
     */
    @Test
    public void applicationServiceMatsInvoker() {
        SpringTestDataTO answer = _matsInvoker.invokeMatsWithFuturizer(15, "Rock on");
        Assert.assertEquals(expectedNumber(15), answer.number, 0);
        Assert.assertEquals(expectedString("Rock on"), answer.string);
    }

    /**
     * Employing the injected {@link AppServiceCalculator} and check its calculations.
     */
    @Test
    public void applicationServiceCalculator() {
        Assert.assertEquals(AppServiceCalculator.Π * Math.E, _calculator.multiplyByΠ(Math.E), 0d);
        Assert.assertEquals(AppServiceCalculator.Φ * Math.E, _calculator.multiplyByΦ(Math.E), 0d);
    }
}
