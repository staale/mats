package com.stolsvik.mats.util.futurizer;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import com.stolsvik.mats.MatsInitiator.MatsInitiate;
import com.stolsvik.mats.test.MatsTestHelp;
import com.stolsvik.mats.test.junit.Rule_Mats;
import com.stolsvik.mats.util.MatsFuturizer;
import com.stolsvik.mats.util.MatsFuturizer.MatsFuturizerTimeoutException;
import com.stolsvik.mats.util.MatsFuturizer.Reply;

/**
 * Basic tests of timeouts of MatsFuturizer, of which there are two completely independent variants: Either you
 * synchronously timeout on the {@link CompletableFuture#get(long, TimeUnit) get(...)}, which really should be your
 * preferred way, IMHO, <b>or</b> you let the MatsFuturizer do the timeout by its timeout thread.
 *
 * @author Endre Stølsvik 2019-08-30 21:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsFuturizer_Timeouts {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();

    @Test
    public void oneShotTimeoutByMatsFuturizer() throws InterruptedException, TimeoutException {
        assertOneShotTimeoutByMatsFuturizer(MATS.getMatsFuturizer());
    }

    private void assertOneShotTimeoutByMatsFuturizer(MatsFuturizer futurizer) throws InterruptedException,
            TimeoutException {
        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futureToEmptiness(futurizer, dto, 25, null);

        // ----- There will never be a reply, as there is no consumer for the sent message..!

        try {
            future.get(1, TimeUnit.MINUTES);
            // We should not get to the next line.
            Assert.fail("We should have gotten an ExecutionException with getCause() MatsFuturizerTimeoutException,"
                    + " as the MatzFuturizer should have timed us out.");
        }
        catch (ExecutionException e) {
            // The cause of this ExecutionException should be a MatsFuturizerTimeoutException, as we were happy
            // to wait for 1 minute, but the timeout was specified to 25 ms.
            Assert.assertEquals(MatsFuturizerTimeoutException.class, e.getCause().getClass());
            // There should be 0 outstanding promises, as the one we added just got timed out.
            Assert.assertEquals(0, futurizer.getOutstandingPromiseCount());
        }
    }

    @Test
    public void oneShotTimeoutByCompletableFuture() throws ExecutionException, InterruptedException {
        // =============================================================================================
        // == NOTE: Using try-with-resources in this test - NOT TO BE USED IN NORMAL CIRCUMSTANCES!!!
        // ==
        // == NOTE: The reason for creating a different MatsFuturizer for this one test, is that we do
        // == not want to pollute the Rule_Mats-instance of the MatsFuturizer with an outstanding
        // == promise for 500 ms, ref. the futurizer.getOutstandingPromiseCount() calls in all tests.
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(MATS.getMatsFactory(),
                this.getClass().getSimpleName())) {
            DataTO dto = new DataTO(42, "TheAnswer");
            CompletableFuture<Reply<DataTO>> future = futureToEmptiness(futurizer, dto, 500, null);

            // ----- There will never be a reply, as there is no consumer for the sent message..!

            try {
                future.get(50, TimeUnit.MILLISECONDS);
                // We should not get to the next line.
                Assert.fail("We should have gotten an TimeoutException, as the CompletableFuture should have timed"
                        + " out our wait..");
            }
            catch (TimeoutException e) {
                // Top notch: We were expecting the TimeoutException: good-stuff!
                // ASSERT: There should still be one outstanding Promise, i.e. the one we just added.
                Assert.assertEquals(1, futurizer.getOutstandingPromiseCount());
            }
        }
    }

    private CompletableFuture<Reply<DataTO>> futureToEmptiness(MatsFuturizer futurizer, DataTO dto, int timeoutMillis,
            Consumer<Throwable> exceptionally) {
        CompletableFuture<Reply<DataTO>> future = futurizer.futurize(
                dto.string, "TimeoutTester.oneshot", SERVICE, timeoutMillis, TimeUnit.MILLISECONDS, DataTO.class, dto,
                MatsInitiate::nonPersistent);
        if (exceptionally != null) {
            future.exceptionally((in) -> {
                exceptionally.accept(in);
                return null;
            });
        }
        return future;
    }

    @Test
    public void severalTimeoutsByMatsFuturizer() throws InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();
        // :: PRE-ARRANGE:

        // :: First do a warm-up of the infrastructure, as we need somewhat good performance of the code
        // to do the test, which relies on asynchronous timings.
        for (int i = 0; i < 10; i++) {
            assertOneShotTimeoutByMatsFuturizer(futurizer);
        }
        Assert.assertEquals(0, futurizer.getOutstandingPromiseCount());

        // ----- The test infrastructure should now be somewhat warm, not incurring sudden halts to timings.

        // :: ARRANGE:

        // We will receive each of the timeout exceptions as they happen, by using future.thenAccept(..)
        // We'll stick the "results" in this COWAL.
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();
        Consumer<Throwable> resulter = (t) -> {
            MatsFuturizerTimeoutException mfte = (MatsFuturizerTimeoutException) t;
            results.add(mfte.getTraceId());
        };

        // :: Stack up a specific set of futures out-of-order, which should time out in timeout-order
        // NOTICE how the order of the entered futures's timeouts are NOT in order.
        // This is to test that the Timeouter handles both adding new entries that are later than the current
        // earliest, but also earlier than the current earliest.
        futureToEmptiness(futurizer, new DataTO(1, "50"), 50, resulter);
        futureToEmptiness(futurizer, new DataTO(2, "100"), 100, resulter);
        futureToEmptiness(futurizer, new DataTO(3, "125"), 125, resulter);
        futureToEmptiness(futurizer, new DataTO(4, "75"), 75, resulter);
        futureToEmptiness(futurizer, new DataTO(5, "25"), 25, resulter);
        // .. we add the last timeout with the longest timeout.
        CompletableFuture<Reply<DataTO>> last = futureToEmptiness(futurizer, new DataTO(6, "150"), 150, resulter);

        // "ACT": (well, each of the above futures have /already/ started executing, but wait for them to finish)

        // :: Now we wait for the last future to timeout.
        try {
            last.get(5, TimeUnit.SECONDS);
            // We should not get to the next line.
            Assert.fail("We should have gotten an ExecutionException with getCause() MatsFuturizerTimeoutException,"
                    + " as the MatzFuturizer should have timed us out.");
        }
        catch (ExecutionException e) {
            // expected.
        }

        // ASSERT:

        // :: All the futures should now have timed out, and they shall have timed out in the order of timeouts.
        Assert.assertEquals(Arrays.asList("25", "50", "75", "100", "125", "150"), results);
        // .. and there should not be any Promises left in the MatsFuturizer.
        Assert.assertEquals(0, futurizer.getOutstandingPromiseCount());
    }
}
