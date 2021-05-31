package io.mats3.lib_test.basics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that the {@link io.mats3.MatsFactory.FactoryConfig#setInitiateTraceIdModifier(Function) Initiate
 * TraceId Modifier Function} works. There are also tests that checks that if the TraceId modifier employs the MDC,
 * we do not get a recursive situation when sending many messages in one initiation (this happened because when
 * setting the TraceId from within a initiation, the MDC is also set to the result. Thus, if you sent one more,
 * the modifier function would pick up the newly set traceId, and thus you got the result of "recursion" where
 * this "one more" message would get the previous message's traceId prepended, and not only the initial MDC).
 *
 * @author Endre Stølsvik 2021-04-12 21:15 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_InitiateTraceIdModifier {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();
    private static final String TERMINATOR_MANY = MatsTestHelp.endpointId("Terminator_Many");

    // For the "many messages" situation:
    private static volatile List<String> _traceIds;
    private static volatile CountDownLatch _countDownLatch;

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });

        MATS.getMatsFactory().terminator(TERMINATOR_MANY, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    _traceIds.add(context.getTraceId());
                    _countDownLatch.countDown();
                });
    }

    @Test
    public void simpleModifyTraceId() {
        // :: Arrange
        MATS.getMatsFactory().getFactoryConfig().setInitiateTraceIdModifier((origTraceId) -> "Prefixed|" + origTraceId);

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals("Prefixed|" + origTraceId, result.getContext().getTraceId());
    }

    @Test
    public void withinStageShouldNotModify() {
        // :: Arrange
        MATS.getMatsFactory().getFactoryConfig().setInitiateTraceIdModifier((origTraceId) -> "Prefixed|" + origTraceId);

        // Create a single-stage service
        String sendingTerminator = MatsTestHelp.service();
        MATS.getMatsFactory().terminator(sendingTerminator, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            ctx.initiate(init -> init.traceId("WithinStage").to(TERMINATOR).send(msg, state));
        });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(sendingTerminator)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());

        // NOTE: The TraceId was prefixed with "Prefixed|" at the "from the outside" initiation, but NOT when
        // initiated new message within Stage (otherwise, there would be 2x the prefix).
        Assert.assertEquals("Prefixed|" + origTraceId + "|WithinStage", result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_set() {
        MDC.put("traceId", "Prefixed");

        // :: Arrange
        setMdcPrefixingTraceIdModifier();

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        // Should have been modified (since MDC was set)
        Assert.assertEquals("Prefixed|" + origTraceId, result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_null() {
        MDC.remove("traceId");

        // :: Arrange
        setMdcPrefixingTraceIdModifier();

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        // Should NOT have been modified (since MDC.traceId was null)
        Assert.assertEquals(origTraceId, result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_manyMessages_mdcSet() throws InterruptedException {
        MDC.put("traceId", "Prefixed");

        int numMessages = 20;
        _countDownLatch = new CountDownLatch(20);
        _traceIds = new CopyOnWriteArrayList<>();

        // :: Arrange
        setMdcPrefixingTraceIdModifier();

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    for (int i = 0; i < numMessages; i++) {
                        init.traceId(origTraceId)
                                .from(MatsTestHelp.from("test"))
                                .to(TERMINATOR_MANY)
                                .send(dto, sto);
                    }
                });

        // :: Assert
        boolean waited = _countDownLatch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("Should have counted down!", waited);

        // Assert that we have all the traceIds
        Assert.assertEquals(_traceIds.size(), numMessages);
        log.info("######### All traceIds: " + _traceIds);
        // Should have been modified (since MDC was set)
        for (String traceId : _traceIds) {
            Assert.assertEquals("Prefixed|" + origTraceId, traceId);
        }
    }

    @Test
    public void usingExistingMdcTraceIdToModify_manyMessages_mdcNull() throws InterruptedException {
        MDC.remove("traceId");

        int numMessages = 20;
        _countDownLatch = new CountDownLatch(20);
        _traceIds = new CopyOnWriteArrayList<>();

        // :: Arrange
        setMdcPrefixingTraceIdModifier();

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    for (int i = 0; i < numMessages; i++) {
                        init.traceId(origTraceId)
                                .from(MatsTestHelp.from("test"))
                                .to(TERMINATOR_MANY)
                                .send(dto, sto);
                    }
                });

        // :: Assert
        boolean waited = _countDownLatch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("Should have counted down!", waited);

        // Assert that we have all the traceIds
        Assert.assertEquals(_traceIds.size(), numMessages);
        log.info("######### All traceIds: " + _traceIds);
        // Should NOT have been modified (since MDC was null)
        for (String traceId : _traceIds) {
            Assert.assertEquals(origTraceId, traceId);
        }
    }

    private void setMdcPrefixingTraceIdModifier() {
        MATS.getMatsFactory().getFactoryConfig().setInitiateTraceIdModifier((origTraceId) -> {
            String mdcTraceId = MDC.get("traceId");
            if (mdcTraceId != null) {
                return mdcTraceId + "|" + origTraceId;
            }
            // E-> No MDC
            return origTraceId;
        });
    }
}
