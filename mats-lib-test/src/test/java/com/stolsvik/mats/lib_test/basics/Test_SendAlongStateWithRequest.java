package com.stolsvik.mats.lib_test.basics;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.MatsEndpoint;
import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsBasicTest;
import com.stolsvik.mats.lib_test.StateTO;
import com.stolsvik.mats.test.MatsTestLatch.Result;

/**
 * Variation of {@link Test_SendAlongState} that instead of using "send" to send directly to a terminator, instead does
 * a "request" to a service. Notice that the service is just a bungled multi-stage with one stage - it is only the
 * initial stage that will get a different situation than if state is not sent along (in which case an "empty object" is
 * created). <b>Notice that the empty-state situation is the normal - this ability to send along state with the request
 * should seldom be employed, unless the initiator and the receiving service resides in the same code base, i.e. the
 * service is a "private" service to the code base</b>.
 *
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - init request
 *     [Service]
 *     -- stops here --
 * </pre>
 *
 * @author Endre Stølsvik - 2015-07-31 - http://endre.stolsvik.com
 */
public class Test_SendAlongStateWithRequest extends MatsBasicTest {
    @Before
    public void setupService() {
        MatsEndpoint<DataTO, StateTO> ep = matsRule.getMatsFactory().staged(SERVICE, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            log.debug("STAGE 0 MatsTrace:\n" + context.toString());
            matsTestLatch.resolve(sto, dto);
        });
        // We need to manually finish setup, since we did not employ lastStage.
        ep.finishSetup();
    }

    @Test
    public void doTest() {
        StateTO initialTargetSto = new StateTO(420, 420.024);
        DataTO requestDto = new DataTO(42, "TheAnswer");
        matsRule.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(randomId())
                        .from(INITIATOR)
                        .to(SERVICE)
                        .replyTo(TERMINATOR, null) // TERMINATOR Will never be invoked..
                        .request(requestDto, initialTargetSto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(requestDto, result.getData());
        Assert.assertEquals(initialTargetSto, result.getState());
    }
}
