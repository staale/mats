package io.mats3.lib_test.basics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsInitiator;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;

/**
 * Test sending 20 roughly 1 MB messages directly to a terminator. 1 MB messages should be considered pretty big. Using
 * the default compression level on the {@code MatsSerializer_DefaultJson} implementation {@link Deflater#BEST_SPEED},
 * the deflate operation takes just around 10ms for each of these simple messages (resulting size ~271kB), and total
 * production time (with serialization) is right about 16 ms. On the receiving size, the inflate operation takes about
 * 4.6 ms.
 *
 * @author Endre Stølsvik - 2018-10-25 - http://endre.stolsvik.com
 */
public class Test_LargeMessages  {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static class LargeMessageDTO {
        private final int _index;
        private final List<DataTO> _dataTransferObjects;

        public LargeMessageDTO() {
            /* need no-args constructor due to Jackson */
            _index = 0;
            _dataTransferObjects = null;
        }

        public LargeMessageDTO(int index, List<DataTO> dataTransferObjects) {
            _index = index;
            _dataTransferObjects = dataTransferObjects;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LargeMessageDTO that = (LargeMessageDTO) o;
            return _index == that._index &&
                    Objects.equals(_dataTransferObjects, that._dataTransferObjects);
        }
    }

    private static int NUMBER_OF_MESSAGES = 20;
    private static int NUMBER_OF_DATATO_PER_MESSAGE = 11204; // Serializes to close around 1048576 bytes, which is 1MB.

    private static final CountDownLatch _latch = new CountDownLatch(NUMBER_OF_MESSAGES);

    private static final LargeMessageDTO[] _receivedMessages = new LargeMessageDTO[NUMBER_OF_MESSAGES];

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, LargeMessageDTO.class,
                config -> {
                    // Endpoint config - setting concurrency.
                    config.setConcurrency(NUMBER_OF_MESSAGES);
                },
                config -> {
                    // Stage config - nothing to do.
                }, (context, sto, dto) -> {
                    // Just putting the message in the right slot on the received-messages array.
                    synchronized (_receivedMessages) {
                        _receivedMessages[dto._index] = dto;
                    }
                    // Counting down the latch, so that when last message is received, the test can continue.
                    _latch.countDown();
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        LargeMessageDTO[] messages = new LargeMessageDTO[NUMBER_OF_MESSAGES];
        for (int i = 0; i < NUMBER_OF_MESSAGES; i++) {
            List<DataTO> messageContent = new ArrayList<>(NUMBER_OF_DATATO_PER_MESSAGE);
            for (int j = 0; j < NUMBER_OF_DATATO_PER_MESSAGE; j++) {
                messageContent.add(new DataTO(Math.random(), "Random:" + Math.random(), j));
            }
            messages[i] = new LargeMessageDTO(i, messageContent);
        }
        MatsInitiator matsInitiator = MATS.getMatsInitiator();
        for (int i = 0; i < NUMBER_OF_MESSAGES; i++) {
            final int finalI = i;
            new Thread(() -> {
                log.info(
                        "Sending messsage: Outside of initiator-lambda: Invoking .initiateUnchecked(..) on initiator.");
                matsInitiator.initiateUnchecked(
                        (msg) -> {
                            log.info("Sending messsage: Inside initiator-lambda, before sending.");
                            msg.traceId(MatsTestHelp.traceId())
                                    .from(MatsTestHelp.from("test"))
                                    .to(TERMINATOR)
                                    .send(messages[finalI]);
                            log.info("Message sent: Inside initiator-lambda, message sent.");
                        });
                log.info("Message sent: Outside of initiator-lambda: Finished, exiting thread.");
            }, "Initiator-thread #" + i).start();
        }

        // Wait synchronously for terminator to finish.
        boolean gotIt = _latch.await(10, TimeUnit.SECONDS);
        if (!gotIt) {
            throw new AssertionError("Didn't get all " + NUMBER_OF_MESSAGES + " messages in 10 seconds!");
        }

        Assert.assertArrayEquals(messages, _receivedMessages);
    }
}
