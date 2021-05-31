package io.mats3.lib_test.concurrency;

import org.junit.BeforeClass;
import org.junit.Test;

import io.mats3.MatsFactory;
import io.mats3.lib_test.DataTO;
import io.mats3.test.MatsTestHelp;

/**
 * Tests concurrency by sending 8 requests to a service, where the processing takes 500 ms, but where the concurrency is
 * also set to 8, thereby all those 8 requests should go through in just a tad over 500 ms, not 4000 ms as if there was
 * only 1 processor. Implicitly tests "lambdaconfig" for endpoint.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator] x 1, firing off 8 requests.
 *     [Service] x 8 StageProcessors (sleeping 500 ms) - reply
 * [Terminator] x 1 StageProcessor, getting all the 8 replies, counting down a 8-latch.
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_ConcurrencyForEndpoint extends ATest_AbstractConcurrency {

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (endpointConfig) -> endpointConfig.setConcurrency(CONCURRENCY_TEST),
                MatsFactory.NO_CONFIG,
                (context, dto) -> {
                    // Emulate some lengthy processing...
                    MatsTestHelp.takeNap(PROCESSING_TIME);
                    return new DataTO(dto.number * 2, dto.string + ":FromService:" + (int) dto.number);
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        performTest(2, "TheAnswer:FromService:");
    }
}
