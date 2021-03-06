package com.stolsvik.mats.lib_test.basics;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsBasicTest;
import com.stolsvik.mats.lib_test.StateTO;
import com.stolsvik.mats.serial.MatsTrace;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.test.MatsTestLatch.Result;

/**
 * Tests the initiation within a stage functionality.
 * <p />
 * FIVE Terminators are set up: One for the normal service return, two for the initiations that are done using the
 * service's context.initiate(...), plus one for an initiation done directly on the MatsFactory.getDefaultInitiator()
 * (which when running within a Mats Stage should take part in the Stage's transactional demarcation), plus one more for
 * an initiation done directly on a MatsFactory.getOrCreateInitiator("NON default") from within the Stage (NOT
 * recommended way to code!).
 * <p />
 * A single-stage service is set up - which initiates three new messages to the three extra Terminators (2 x initiations
 * on ProcessContext, and 1 initiation directly on the MatsFactory), and returns a result to the normal Terminator.
 * <p />
 * TWO tests are performed:
 * <ol>
 * <li>An initiator does a request to the service, setting replyTo(Terminator) - which should result in all FIVE
 * Terminators getting its message</li>
 * <li>An initiator does a request to the service, setting replyTo(Terminator), <b>BUT DIRECTS THE SERVICE TO THROW
 * after it has done all the in-stage initiations</b>. This shall result in the message to the SERVICE going to DLQ, and
 * all the In-stage initiated messages should NOT be sent - EXCEPT for the one using Non-Default Initiator, which do not
 * participate in the Stage-specific transactional demarcation.</li>
 * </ol>
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - request
 *     [Service] - reply + sends to Terminator "stageInit1", to "stageInit2", to "stageInit_withMatsFactory_DefaultInitiator", AND to "stageInit_withMatsFactory_NonDefaultInitiator"
 * [Terminator]          +         [Terminator "stageInit1"] [T "stageInit2"] [T "stageInit_withMatsFactory_DefaultInitiator"]     [T "stageInit_withMatsFactory_NonDefaultInitiator"]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_InitiateWithinStage extends MatsBasicTest {
    private MatsTestLatch matsTestLatch_stageInit1 = new MatsTestLatch();
    private MatsTestLatch matsTestLatch_stageInit2 = new MatsTestLatch();
    private MatsTestLatch matsTestLatch_stageInit_withMatsFactory_DefaultInitiator = new MatsTestLatch();
    private MatsTestLatch matsTestLatch_stageInit_withMatsFactory_NonDefaultInitiator = new MatsTestLatch();

    private volatile String _traceId;
    private volatile String _traceId_stageInit1;
    private volatile String _traceId_stageInit2;
    private volatile String _traceId_stageInit_withMatsFactory_DefaultInitiator;
    private volatile String _traceId_stageInit_withMatsFactory_NonDefaultInitiator;

    @Before
    public void setupService() {
        matsRule.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Fire off two new initiations to the two Terminators
                    context.initiate(
                            msg -> {
                                msg.traceId("subtraceId1")
                                        .to(TERMINATOR + "_stageInit1")
                                        .send(new DataTO(Math.E, "xyz"));
                                msg.traceId("subtraceId2")
                                        .to(TERMINATOR + "_stageInit2")
                                        .send(new DataTO(-Math.E, "abc"), new StateTO(Integer.MAX_VALUE, Math.PI));

                                /*
                                 * Initiate directly on MatsFactory.getDefaultInitiator(), not the ProcessContext.
                                 *
                                 * NOTICE!!! THIS WILL HAPPEN *INSIDE* THE TRANSACTION DEMARCATION FOR THIS STAGE!
                                 *
                                 * This USED to happen outside the transaction demarcation for this stage, but I've
                                 * changed this so that getting the *default* initiator inside a stage will return you a
                                 * ThreadLocal bound "magic initiator". The rationale here is that you then can make
                                 * methods which can be invoked "out of context", OR be invoked "in stage", and if the
                                 * latter, will participate in the same transactional demarcation as the stage.
                                 */
                                matsRule.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
                                    init.traceId("subtraceId3_with_MatsFactory.getDefaultInitiator()")
                                            .from("MatsFactory.getDefaultInitiator")
                                            .to(TERMINATOR + "_stageInit_withMatsFactory_DefaultInitiator")
                                            .send(new DataTO(Math.PI, "Endre"));
                                });

                                /*
                                 * Initiate on MatsFactory.getOrCreateInitiator("NOT default!"), not the ProcessContext.
                                 *
                                 * NOTICE!!! THIS WILL HAPPEN *OUTSIDE* THE TRANSACTION DEMARCATION FOR THIS STAGE! That
                                 * means that this 'send' basically happens immediately, and it will happen even if some
                                 * exception is raised later in the code. It will not be committed nor rolled back along
                                 * with the rest of operations happening in the stage - it is separate.
                                 *
                                 * It is very much like (actually: pretty much literally) getting a new Connection from
                                 * a DataSource while already being within a transaction on an existing Connection:
                                 * Anything happening on this new Connection is totally separate from the existing
                                 * transaction demarcation.
                                 *
                                 * This also means that the send initiated on such an independent Initiator will happen
                                 * before the two sends initiated on the ProcessContext above, since this new
                                 * transaction will commit before the transaction surrounding the Mats process lambda
                                 * that we're within.
                                 *
                                 * Please check through the logs to see what happens.
                                 *
                                 * TAKEAWAY: This should not be a normal way to code! But it is possible to do.
                                 */
                                matsRule.getMatsFactory().getOrCreateInitiator("NOT default").initiateUnchecked(
                                        init -> {
                                            init.traceId("New TraceId")
                                                    .from("New Init")
                                                    .to(TERMINATOR + "_stageInit_withMatsFactory_NonDefaultInitiator")
                                                    .send(new DataTO(-Math.PI, "Stølsvik"));
                                        });

                                // ?: Should we throw at this point?
                                if (dto.string.equals("THROW!")) {
                                    throw new RuntimeException("This should lead to DLQ!");
                                }

                            });
                    // Return our result
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @Before
    public void setupTerminators() {
        // :: Termintor for the normal service REPLY
        matsRule.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("Normal TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId = context.getTraceId();
                    matsTestLatch.resolve(sto, dto);
                });
        // :: Two terminators for the initiations within the stage executed on the ProcessContext of the stage
        matsRule.getMatsFactory().terminator(TERMINATOR + "_stageInit1", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit1 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit1 = context.getTraceId();
                    matsTestLatch_stageInit1.resolve(sto, dto);
                });
        matsRule.getMatsFactory().terminator(TERMINATOR + "_stageInit2", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit2 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit2 = context.getTraceId();
                    matsTestLatch_stageInit2.resolve(sto, dto);
                });
        // :: Terminator for the initiation within the stage executed on MatsFactory.getDefaultInitiator()
        matsRule.getMatsFactory().terminator(TERMINATOR + "_stageInit_withMatsFactory_DefaultInitiator", StateTO.class,
                DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit2 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit_withMatsFactory_DefaultInitiator = context.getTraceId();
                    matsTestLatch_stageInit_withMatsFactory_DefaultInitiator.resolve(sto, dto);
                });
        // :: Terminator for the initiation within the stage executed on MatsFactory.getOrCreateInitiator(...)
        matsRule.getMatsFactory().terminator(TERMINATOR + "_stageInit_withMatsFactory_NonDefaultInitiator",
                StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit2 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit_withMatsFactory_NonDefaultInitiator = context.getTraceId();
                    matsTestLatch_stageInit_withMatsFactory_NonDefaultInitiator.resolve(sto, dto);
                });
    }

    @Test
    public void serviceCompletesSuccessfully() {
        // :: Send message to the single stage service.
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String randomId = randomId();
        matsRule.getMatsInitiator().initiateUnchecked(
                msg -> msg.traceId(randomId)
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // :: Wait synchronously for all terminators terminators to finish.
        // "Normal" Terminator from the service call
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
        Assert.assertEquals(randomId, _traceId);

        // Terminator "stageInit1", for the first initiation within the service's stage
        Result<StateTO, DataTO> result_stageInit1 = matsTestLatch_stageInit1.waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_stageInit1.getState());
        Assert.assertEquals(new DataTO(Math.E, "xyz"), result_stageInit1.getData());
        Assert.assertEquals(randomId + "|subtraceId1", _traceId_stageInit1);

        // Terminator "stageInit2", for the second initiation within the service's stage
        Result<StateTO, DataTO> result_stageInit2 = matsTestLatch_stageInit2.waitForResult();
        Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.PI), result_stageInit2.getState());
        Assert.assertEquals(new DataTO(-Math.E, "abc"), result_stageInit2.getData());
        Assert.assertEquals(randomId + "|subtraceId2", _traceId_stageInit2);

        // Terminator "stageInit_withMatsFactory", for the initiation using MatsFactory within the service's stage
        Result<StateTO, DataTO> result_withMatsFactory_DefaultInitiator = matsTestLatch_stageInit_withMatsFactory_DefaultInitiator
                .waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_withMatsFactory_DefaultInitiator.getState());
        Assert.assertEquals(new DataTO(Math.PI, "Endre"), result_withMatsFactory_DefaultInitiator.getData());
        Assert.assertEquals(randomId + "|subtraceId3_with_MatsFactory.getDefaultInitiator()",
                _traceId_stageInit_withMatsFactory_DefaultInitiator);

        // Terminator "stageInit_withMatsFactory", for the initiation using MatsFactory within the service's stage
        Result<StateTO, DataTO> result_withMatsFactory_NonDefaultInitiator = matsTestLatch_stageInit_withMatsFactory_NonDefaultInitiator
                .waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_withMatsFactory_NonDefaultInitiator.getState());
        Assert.assertEquals(new DataTO(-Math.PI, "Stølsvik"), result_withMatsFactory_NonDefaultInitiator.getData());
        Assert.assertEquals("New TraceId", _traceId_stageInit_withMatsFactory_NonDefaultInitiator);
    }

    @Test
    public void serviceThrowsAndOnlyNonDefaultInitiatorShouldPersevere() throws InterruptedException {
        // :: Send message to the single stage service.
        DataTO dto = new DataTO(42, "THROW!");
        StateTO sto = new StateTO(420, 420.024);
        String randomId = randomId();
        matsRule.getMatsInitiator().initiateUnchecked(
                msg -> msg.traceId(randomId)
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // The the messages sent to the service should appear in the DLQ!

        MatsTrace<String> dlqMessage = matsRule.getDlqMessage(SERVICE);
        Assert.assertEquals(randomId, dlqMessage.getTraceId());
        Assert.assertEquals(SERVICE, dlqMessage.getCurrentCall().getTo().getId());

        // NOTE NOTE! The SINGLE message sent with the NON-default initiator should come through!

        // Terminator "stageInit_withMatsFactory_NonDefaultInitiator"
        Result<StateTO, DataTO> result_withMatsFactory_NonDefaultInitiator = matsTestLatch_stageInit_withMatsFactory_NonDefaultInitiator
                .waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_withMatsFactory_NonDefaultInitiator.getState());
        Assert.assertEquals(new DataTO(-Math.PI, "Stølsvik"), result_withMatsFactory_NonDefaultInitiator.getData());
        Assert.assertEquals("New TraceId", _traceId_stageInit_withMatsFactory_NonDefaultInitiator);

        // HOWEVER, NONE of the IN-STAGE-DEMARCATED messages should have come!

        // "Normal" Terminator from the service call - Wait a bit for this in case these messages slacks in the queue.
        try {
            matsTestLatch.waitForResult(500);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }

        // Terminator "stageInit1", for the first initiation within the service's stage - we've already waited a bit.
        try {
            matsTestLatch_stageInit1.waitForResult(5);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }

        // Terminator "stageInit2", for the second initiation within the service's stage
        try {
            matsTestLatch_stageInit2.waitForResult(5);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }

        // Terminator "stageInit_withMatsFactory", for the initiation using MatsFactory.getDefaultInitiator()
        try {
            matsTestLatch_stageInit_withMatsFactory_DefaultInitiator.waitForResult(5);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }
    }
}
