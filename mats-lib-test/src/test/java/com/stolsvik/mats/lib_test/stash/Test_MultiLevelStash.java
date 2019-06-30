package com.stolsvik.mats.lib_test.stash;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.MatsEndpoint;
import com.stolsvik.mats.MatsInitiator.MatsBackendException;
import com.stolsvik.mats.MatsInitiator.MatsMessageSendException;
import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsBasicTest;
import com.stolsvik.mats.lib_test.StateTO;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.test.MatsTestLatch.Result;

/**
 * More involved test of stash/unstash: Two-stage service invoking "Single-stage" leaf service, where a stash/unstash
 * cycle is performed on each of the two stages and in the leaf service. Elements of the Context are tested within the
 * "continuation" process lambda, i.e. checking that it "feels identical" to be within the unstashed process lambda as
 * within the original stage's process lambda). Furthermore, unstashing more than once is tested: The stash from the
 * second stage of the multistage is unstashed with a reply to the waiting terminator TWICE.
 *
 * @author Endre Stølsvik - 2018-10-24 - http://endre.stolsvik.com
 */
public class Test_MultiLevelStash extends MatsBasicTest {

    // Stash Stage0
    private byte[] _stash_stage0;
    private MatsTestLatch _stashLatch_Stage0 = new MatsTestLatch();

    // Stash Stage1
    private byte[] _stash_stage1;
    private MatsTestLatch _stashLatch_Stage1 = new MatsTestLatch();

    @Before
    public void setupStagedService() {
        MatsEndpoint<DataTO, StateTO> staged = matsRule.getMatsFactory().staged(SERVICE, DataTO.class, StateTO.class);
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash_stage0 = context.stash();
            _stashLatch_Stage0.resolve(context, state, incomingDto);
            // NOTE! Not request, next, nor reply..! (We've stashed)
        }));
        // NOTICE!! NOT employing "lastStage(..)", as that requires us to reply with something.
        // Instead using ordinary stage(..), and then invoking .finishedSetup().
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash_stage1 = context.stash();
            _stashLatch_Stage1.resolve(context, state, incomingDto);
        }));
        staged.finishSetup();
    }

    // Stash Leaf
    private byte[] _stash_leaf;
    private MatsTestLatch _stashLatch_leaf = new MatsTestLatch();

    @Before
    public void setupService() {
        MatsEndpoint<DataTO, StateTO> staged = matsRule.getMatsFactory().staged(SERVICE + ".Leaf", DataTO.class,
                StateTO.class);
        // Cannot employ a single-stage, since that requires a reply (by returning something, even null).
        // Thus, employing multistage, with only one stage, where we do not invoke context.reply(..)
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash_leaf = context.stash();
            _stashLatch_leaf.resolve(context, state, incomingDto);
        }));
        staged.finishSetup();
    }

    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    matsTestLatch.resolve(context, sto, dto);
                });

    }

    @Test
    public void doTest() throws InterruptedException, MatsMessageSendException, MatsBackendException {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String traceId = randomId();
        matsRule.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(traceId)
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // ### STASHED AT SERVICE (stage0) - Wait synchronously for stash to appear
        Result<StateTO, DataTO> stashContext_stage0 = _stashLatch_Stage0.waitForResult();

        Assert.assertEquals(INITIATOR, stashContext_stage0.getContext().getFromStageId());
        Assert.assertEquals(SERVICE, stashContext_stage0.getContext().getEndpointId());
        Assert.assertEquals(SERVICE, stashContext_stage0.getContext().getStageId());

        // Unstash!
        matsRule.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_stage0,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    state.number1 = 1337;
                    state.number2 = Math.PI;
                    context.request(SERVICE + ".Leaf", new DataTO(incomingDto.number * 2, incomingDto.string
                            + ":RequestToLeaf"));
                }));

        // ### STASHED AT LEAF - Wait synchronously for stash to appear
        Result<StateTO, DataTO> stashContext_leaf = _stashLatch_leaf.waitForResult();

        Assert.assertEquals(SERVICE, stashContext_leaf.getContext().getFromStageId());
        Assert.assertEquals(SERVICE + ".Leaf", stashContext_leaf.getContext().getEndpointId());
        Assert.assertEquals(SERVICE + ".Leaf", stashContext_leaf.getContext().getStageId());

        byte[][] restash = new byte[1][];
        // Unstash!
        matsRule.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_leaf,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    // Restashing bytes. Absurd idea, but everything should work inside the "continued" process lambda.
                    restash[0] = context.stash();
                    context.reply(new DataTO(incomingDto.number * 3, incomingDto.string + ":FromLeaf"));
                }));

        // ### STASHED AT SERVICE.stage1 - Wait synchronously for stash to appear
        Result<StateTO, DataTO> stashContext_stage1 = _stashLatch_Stage1.waitForResult();

        Assert.assertEquals(SERVICE + ".Leaf", stashContext_stage1.getContext().getFromStageId());
        Assert.assertEquals(SERVICE, stashContext_stage1.getContext().getEndpointId());
        Assert.assertEquals(SERVICE + ".stage1", stashContext_stage1.getContext().getStageId());
        Assert.assertEquals(1337, stashContext_stage1.getState().number1);
        Assert.assertEquals(Math.PI, stashContext_stage1.getState().number2, 0d);
        // Check that the restash is the same as the stash (which with current impl is true)
        Assert.assertArrayEquals(_stash_leaf, restash[0]);

        String messageId_AtStage1 = stashContext_stage1.getContext().getSystemMessageId();
        Assert.assertNotNull(messageId_AtStage1);
        log.info("Here's the MessageId at stage1: [" + messageId_AtStage1 + "]");

        // Unstash!
        matsRule.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_stage1,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    // State is present
                    Assert.assertEquals(1337, state.number1);
                    Assert.assertEquals(Math.PI, state.number2, 0d);
                    Assert.assertEquals(messageId_AtStage1, context.getSystemMessageId());
                    context.reply(new DataTO(incomingDto.number * 5, incomingDto.string + ":FromService"));
                }));

        // ### PROCESS FINISHED @ Terminator, first time! - Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        // :: Assert that the flow went through as expected, with the traceId intact.
        Assert.assertEquals(traceId, result.getContext().getTraceId());
        Assert.assertNotNull(result.getContext().getSystemMessageId());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 5, dto.string + ":RequestToLeaf:FromLeaf:FromService"),
                result.getData());

        // :: USING STASH FROM SERVICE.stage1 AGAIN TO UNSTASH TWICE - the terminator will get its answer once more.

        // Unstash AGAIN!
        matsRule.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_stage1,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    // Different reply this time around
                    Assert.assertEquals(messageId_AtStage1, context.getSystemMessageId());
                    context.reply(new DataTO(incomingDto.number * 7, incomingDto.string + ":FromServiceAGAIN"));
                }));

        // ### PROCESS FINISHED @ Terminator, second time! - Wait synchronously for terminator to finish for a second
        // time.
        Result<StateTO, DataTO> result_Again = matsTestLatch.waitForResult();
        // :: Assert that the flow went through as expected, with the traceId intact.
        Assert.assertEquals(traceId, result_Again.getContext().getTraceId());
        Assert.assertNotNull(result_Again.getContext().getSystemMessageId());
        Assert.assertEquals(sto, result_Again.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 7, dto.string + ":RequestToLeaf:FromLeaf:FromServiceAGAIN"),
                result_Again.getData());
    }
}