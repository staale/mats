package com.stolsvik.mats.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsBasicTest;
import com.stolsvik.mats.util.MatsFuturizer.Reply;

/**
 * Basic tests of the MatsFuturizer.
 *
 * @author Endre Stølsvik 2019-08-28 00:22 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsFuturizer_Basics extends MatsBasicTest {
    @Before
    public void setupService() {
        matsRule.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, msg) -> new DataTO(msg.number * 2, msg.string + ":FromService"));
    }

    @Test
    public void normalMessage() throws ExecutionException, InterruptedException, TimeoutException {
        // =============================================================================================
        // == NOTE: Using try-with-resources in this test - NOT TO BE USED IN NORMAL CIRCUMSTANCES!!! ==
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(matsRule.getMatsFactory(),
                this.getClass().getSimpleName())) {

            DataTO dto = new DataTO(42, "TheAnswer");
            CompletableFuture<Reply<DataTO>> future = futurizer.futurizeInteractiveUnreliable(
                    "traceId", "OneSingleMessage", SERVICE, DataTO.class, dto);

            Reply<DataTO> result = future.get(1, TimeUnit.SECONDS);

            Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.reply);

            log.info("Got the reply from the Future - the latency was " + (
                    System.currentTimeMillis()
                            - result.initiationTimestamp) + " milliseconds");
        }
    }

    @Test
    public void manyMessages() throws ExecutionException, InterruptedException, TimeoutException {
        // =============================================================================================
        // == NOTE: Using try-with-resources in this test - NOT TO BE USED IN NORMAL CIRCUMSTANCES!!! ==
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(matsRule.getMatsFactory(),
                this.getClass().getSimpleName())) {

            // Warm-up:
            runTest(futurizer, 50); // 1000 msgs -> 1197.108 ms total -> 1.197 ms per message

            // Timed run:
            runTest(futurizer, 50); // 1000 msgs -> 679.391 ms total -> 0.679 ms per message

            // For 10k messages, with logging set to INFO level, "timed run":
            // Got the reply from all [10000] Futures - total time:[1975.412943 ms] , per message:[0.1975412943 ms]
        }
    }

    private void runTest(MatsFuturizer futurizer, int number) throws InterruptedException, ExecutionException,
            TimeoutException {
        // :: Send a bunch of messages
        long startNanos = System.nanoTime();
        List<CompletableFuture<Reply<DataTO>>> futures = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            DataTO dto = new DataTO(i, "TheAnswer");

            futures.add(futurizer.futurizeInteractiveUnreliable(
                    "traceId", "SeveralMessages.futurized", SERVICE, DataTO.class, dto));
        }

        // :: Wait for each of them to complete
        for (int i = 0; i < number; i++) {
            Reply<DataTO> result = futures.get(i).get(60, TimeUnit.SECONDS);
            Assert.assertEquals(new DataTO(i * 2, "TheAnswer:FromService"), result.reply);
        }
        double totalTimeMs = (System.nanoTime() - startNanos) / 1_000_000d;
        String msg = "#TIMED# Got the reply from all [" + number + "] Futures - total time:[" + (totalTimeMs)
                + " ms] , per message:[" + (totalTimeMs / number) + " ms]";
        log.info(msg);
        System.out.println(msg);
    }
}

