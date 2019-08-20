package com.stolsvik.mats.spring.test.apptest_two_mf;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.spring.test.apptest_two_mf.AppMain.TestQualifier;
import com.stolsvik.mats.spring.test.mapping.SpringTestDataTO;
import com.stolsvik.mats.spring.test.mapping.SpringTestStateTO;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.test.MatsTestLatch.Result;

@Component
public class Runner {
    private static final Logger log = LoggerFactory.getLogger(Runner.class);

    @Inject
    private MatsTestLatch _latch;

    @Inject
    private AtomicInteger _atomicInteger;

    @Inject
    @TestQualifier(name = "SouthWest")
    private MatsFactory _matsFactory;

    void run() {
        once("Test1", 7, 1);
        once("Test2", 13, 2);
    }

    void once(String string, int number, int atomic) {
        SpringTestDataTO dto = new SpringTestDataTO(Math.PI * number, string + ":Data");
        SpringTestStateTO sto = new SpringTestStateTO(256 * number, string + "State");
        _matsFactory.getDefaultInitiator().initiateUnchecked(
                msg -> msg.traceId("TraceId")
                        .from("FromId")
                        .to(AppMain.ENDPOINT_ID + ".multi")
                        .replyTo(AppMain.ENDPOINT_ID + ".terminator", sto)
                        .request(dto));

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        System.out.println("XXX State: " + result.getState());
        System.out.println("YYY Reply: " + result.getData());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 3, dto.string + ":single:multi"),
                result.getData());
        Assert.assertEquals(atomic, _atomicInteger.get());
    }
}
