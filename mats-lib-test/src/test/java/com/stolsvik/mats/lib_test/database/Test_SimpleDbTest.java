package com.stolsvik.mats.lib_test.database;

import java.sql.Connection;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsDbTest;
import com.stolsvik.mats.lib_test.StateTO;
import com.stolsvik.mats.lib_test.basics.Test_SimplestServiceRequest;
import com.stolsvik.mats.test.MatsTestLatch.Result;

/**
 * Simple test that looks quite a bit like {@link Test_SimplestServiceRequest}, only the Initiator now populate a table
 * with some data, which the Mats service retrieves and replies with.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - inserts into database - request
 *     [Service] - fetches from database - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimpleDbTest extends MatsDbTest {
    @Before
    public void setupService() {
        matsRule.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // :: Get the data from the SQL table
                    String data = matsRule.getDataFromDataTable(context.getAttribute(Connection.class).get());
                    return new DataTO(dto.number * 2, dto.string + ":FromService:" + data);
                });
    }

    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    matsTestLatch.resolve(sto, dto);
                });

    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String randomData = UUID.randomUUID().toString();

        // Create the SQL datatable - outside of the MATS transaction.
        matsRule.createDataTable();

        // :: Insert into 'datatable' and send the request to SERVICE.
        matsRule.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    matsRule.insertDataIntoDataTable(msg.getAttribute(Connection.class).get(), randomData);
                    // :: Send the request
                    msg.traceId(randomId())
                            .from(INITIATOR)
                            .to(SERVICE)
                            .replyTo(TERMINATOR, sto)
                            .request(dto);
                });

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService:" + randomData), result.getData());
    }
}
