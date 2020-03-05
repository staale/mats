package com.stolsvik.mats.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.websocket.MatsSocketServer.MatsSocketEndpoint;

/**
 * Sets up the test endpoints used from the integration tests (and the HTML test-pages).
 *
 * @author Endre Stølsvik 2020-02-20 18:33 - http://stolsvik.com/, endre@stolsvik.com
 */
public class SetupTestMatsAndMatsSocketEndpoints {
    private static final Logger log = LoggerFactory.getLogger(SetupTestMatsAndMatsSocketEndpoints.class);

    static void setupMatsAndMatsSocketEndpoints(MatsFactory matsFactory, MatsSocketServer matsSocketServer) {
        // "Standard" test endpoint
        setupMats_StandardTestSingle(matsFactory);
        setupSocket_StandardTestSingle(matsSocketServer);

        // Resolve/Reject/Throws in incomingHandler and replyAdapter
        setupSocket_IgnoreInIncoming(matsSocketServer);
        setupSocket_DenyInIncoming(matsSocketServer);
        setupSocket_ResolveInIncoming(matsSocketServer);
        setupSocket_RejectInIncoming(matsSocketServer);
        setupSocket_ThrowsInIncoming(matsSocketServer);
        setupSocket_ResolveInReplyAdapter(matsSocketServer);
        setupSocket_RejectInReplyAdapter(matsSocketServer);
        setupSocket_ThrowsInReplyAdapter(matsSocketServer);

        setup_TestSlow(matsSocketServer, matsFactory);

        setup_ServerPush_Send(matsSocketServer, matsFactory);

        setup_ServerPush_Request(matsSocketServer, matsFactory);
    }

    // ===== "Standard Endpoint".

    private static final String STANDARD_ENDPOINT = "Test.single";

    private static void setupSocket_StandardTestSingle(MatsSocketServer matsSocketServer) {
        // :: Make default MatsSocket Endpoint
        MatsSocketEndpoint<MatsSocketRequestDto, MatsDataTO, MatsDataTO, MatsSocketReplyDto> matsSocketEndpoint = matsSocketServer
                .matsSocketEndpoint(STANDARD_ENDPOINT,
                        MatsSocketRequestDto.class, MatsDataTO.class, MatsDataTO.class, MatsSocketReplyDto.class,
                        (ctx, principal, msIncoming) -> {
                            log.info("Got MatsSocket request on MatsSocket EndpointId: " +
                                    ctx.getMatsSocketEndpointId());
                            log.info(" \\- Authorization: " + ctx.getAuthorizationHeader());
                            log.info(" \\- Principal:     " + ctx.getPrincipal());
                            log.info(" \\- UserId:        " + ctx.getUserId());
                            log.info(" \\- Message:       " + msIncoming);
                            ctx.forwardCustom(new MatsDataTO(msIncoming.number, msIncoming.string),
                                    msg -> msg.to(ctx.getMatsSocketEndpointId())
                                            .interactive()
                                            .nonPersistent()
                                            .setTraceProperty("requestTimestamp", msIncoming.requestTimestamp));
                        },
                        (ctx, matsReply) -> {
                            log.info("Adapting message: " + matsReply);
                            MatsSocketReplyDto reply = new MatsSocketReplyDto(matsReply.string.length(),
                                    matsReply.number,
                                    ctx.getMatsContext().getTraceProperty("requestTimestamp", Long.class));
                            ctx.resolve(reply);
                        });
    }

    private static void setupMats_StandardTestSingle(MatsFactory matsFactory) {
        // :: Make simple single Mats Endpoint
        matsFactory.single(STANDARD_ENDPOINT, SetupTestMatsAndMatsSocketEndpoints.MatsDataTO.class,
                SetupTestMatsAndMatsSocketEndpoints.MatsDataTO.class, (processContext, incomingDto) -> new MatsDataTO(
                        incomingDto.number,
                        incomingDto.string + ":FromSimple",
                        incomingDto.sleepTime));
    }

    // ===== IncomingHandler

    private static void setupSocket_IgnoreInIncoming(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketDirectReplyEndpoint("Test.ignoreInIncomingHandler",
                MatsSocketRequestDto.class, MatsSocketReplyDto.class,
                // IGNORE - i.e. do nothing
                (ctx, principal, msIncoming) -> {
                });
    }

    private static void setupSocket_DenyInIncoming(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketDirectReplyEndpoint("Test.denyInIncomingHandler",
                MatsSocketRequestDto.class, MatsSocketReplyDto.class,
                // DENY
                (ctx, principal, msIncoming) -> ctx.deny());
    }

    private static void setupSocket_ResolveInIncoming(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketDirectReplyEndpoint("Test.resolveInIncomingHandler",
                MatsSocketRequestDto.class, MatsSocketReplyDto.class,
                // RESOLVE
                (ctx, principal, msIncoming) -> ctx.resolve(
                        new MatsSocketReplyDto(1, 2, msIncoming.requestTimestamp)));
    }

    private static void setupSocket_RejectInIncoming(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketDirectReplyEndpoint("Test.rejectInIncomingHandler",
                MatsSocketRequestDto.class, MatsSocketReplyDto.class,
                // REJECT
                (ctx, principal, msIncoming) -> ctx.reject(
                        new MatsSocketReplyDto(3, 4, msIncoming.requestTimestamp)));
    }

    private static void setupSocket_ThrowsInIncoming(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketDirectReplyEndpoint("Test.throwsInIncomingHandler",
                MatsSocketRequestDto.class, MatsSocketReplyDto.class,
                // THROW
                (ctx, principal, msIncoming) -> {
                    throw new IllegalStateException("Exception in IncomingAuthorizationAndAdapter should REJECT");
                });
    }

    // ===== ReplyAdapter
    // TODO: Make, and handle, "IGNORE" in replyAdapter. Should reject.

    private static void setupSocket_ResolveInReplyAdapter(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketEndpoint("Test.resolveInReplyAdapter",
                MatsSocketRequestDto.class, MatsDataTO.class, MatsDataTO.class, MatsSocketReplyDto.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(1, "string1"),
                        msg -> msg.to(STANDARD_ENDPOINT)),
                // RESOLVE
                (ctx, matsReply) -> ctx.resolve(new MatsSocketReplyDto(1, 2, 123)));
    }

    private static void setupSocket_RejectInReplyAdapter(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketEndpoint("Test.rejectInReplyAdapter",
                MatsSocketRequestDto.class, MatsDataTO.class, MatsDataTO.class, MatsSocketReplyDto.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(2, "string2"),
                        msg -> msg.to(STANDARD_ENDPOINT)),
                // REJECT
                (ctx, matsReply) -> ctx.reject(new MatsSocketReplyDto(1, 2, 123)));
    }

    private static void setupSocket_ThrowsInReplyAdapter(MatsSocketServer matsSocketServer) {
        matsSocketServer.matsSocketEndpoint("Test.throwsInReplyAdapter",
                MatsSocketRequestDto.class, MatsDataTO.class, MatsDataTO.class, MatsSocketReplyDto.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(3, "string3"),
                        msg -> msg.to(STANDARD_ENDPOINT)),
                // THROW
                (ctx, matsReply) -> {
                    throw new IllegalStateException("Exception in ReplyAdapter should REJECT.");
                });
    }

    // ===== Slow endpoint

    private static void setup_TestSlow(MatsSocketServer matsSocketServer, MatsFactory matsFactory) {
        // :: Forwards directly to Mats, no replyAdapter
        matsSocketServer.matsSocketTerminator("Test.slow",
                MatsDataTO.class, MatsDataTO.class,
                (ctx, principal, msIncoming) -> ctx.forwardInteractivePersistent(msIncoming));

        // :: Simple endpoint that just sleeps a tad, to simulate "long(er) running process".
        matsFactory.single("Test.slow", MatsDataTO.class, MatsDataTO.class,
                (processContext, incomingDto) -> {
                    if (incomingDto.sleepTime > 0) {
                        log.info("incoming.sleepTime > 0, sleeping specified [" + incomingDto.sleepTime + "] ms.");
                        try {
                            Thread.sleep(incomingDto.sleepTime);
                        }
                        catch (InterruptedException e) {
                            throw new AssertionError("Got interrupted while slow-sleeping..!");
                        }
                    }
                    return new MatsDataTO(incomingDto.number,
                            incomingDto.string + ":FromSimple",
                            incomingDto.sleepTime);
                });
    }

    // ===== Server Push: MatsSocketServer.send(..) and .request(..)

    private static void setup_ServerPush_Send(MatsSocketServer matsSocketServer, MatsFactory matsFactory) {
        matsSocketServer.matsSocketTerminator("Test.server.send.matsStage",
                MatsDataTO.class, MatsDataTO.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(msIncoming.number,
                        ctx.getMatsSocketSessionId(), 1), init -> init.to("Test.server.send")));

        matsSocketServer.matsSocketTerminator("Test.server.send.thread",
                MatsDataTO.class, MatsDataTO.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(msIncoming.number,
                        ctx.getMatsSocketSessionId(), 2), init -> init.to("Test.server.send")));

        // :: Simple endpoint that does a MatsSocketServer.send(..), either inside MatsStage, or in separate thread.
        matsFactory.terminator("Test.server.send", Void.class, MatsDataTO.class,
                (processContext, state, incomingDto) -> {
                    if (incomingDto.sleepTime == 1) {
                        // Fire the sending off directly within the MatsStage, to prove that this is possible.
                        matsSocketServer.send(incomingDto.string, processContext.getTraceId()
                                + ":SentFromMatsStage", "ClientSide.terminator", incomingDto);
                    }
                    else if (incomingDto.sleepTime == 2) {
                        // Fire the sending in a new Thread, to prove that this can be done totally outside context.
                        new Thread(() -> {
                            matsSocketServer.send(incomingDto.string, processContext.getTraceId()
                                    + ":SentFromThread", "ClientSide.terminator", incomingDto);
                        }, "Test-MatsSocketServer.send()").start();
                    }
                });

    }

    private static void setup_ServerPush_Request(MatsSocketServer matsSocketServer, MatsFactory matsFactory) {
        // Receives the "start", which starts the cascade - and forwards to the following Mats endpoint
        matsSocketServer.matsSocketTerminator("Test.server.request.start",
                MatsDataTO.class, MatsDataTO.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(msIncoming.number,
                        msIncoming.string), init -> init.to("Test.server.requestToClient")));

        // .. which initiates a request to Client Endpoint, asking for reply to go to the following MatsSocket Endpoint
        matsFactory.terminator("Test.server.requestToClient", Void.class, MatsDataTO.class,
                (processContext, state, incomingDto) -> {
                    String matsSocketSessionId = processContext.getString("matsSocketSessionId");
                    matsSocketServer.request(matsSocketSessionId, processContext.getTraceId(),
                            "ClientSide.endpoint",
                            new MatsDataTO(incomingDto.number, incomingDto.string),
                            "Test.server.request.replyReceiver",
                            "CorrelationString", "CorrelationBinary".getBytes(StandardCharsets.UTF_8));
                });

        // .. which gets the Reply from Client, and forwards the it to the following Mats endpoint
        matsSocketServer.matsSocketTerminator("Test.server.request.replyReceiver",
                MatsDataTO.class, MatsDataTO.class,
                (ctx, principal, msIncoming) -> ctx.forwardCustom(new MatsDataTO(msIncoming.number,
                        msIncoming.string), init -> init.to("Test.server.sendReplyBackToClient")));

        // .. which finally sends the Reply back to the Client on a Terminator there.
        matsFactory.terminator("Test.server.sendReplyBackToClient", Void.class, MatsDataTO.class,
                (processContext, state, incomingDto) -> {
                    String matsSocketSessionId = processContext.getString("matsSocketSessionId");
                    matsSocketServer.send(matsSocketSessionId, processContext.getTraceId(),
                            "ClientSide.terminator",
                            new MatsDataTO(incomingDto.number, incomingDto.string));
                });
    }

    /**
     * Request DTO class for MatsSocket Endpoint.
     */
    public static class MatsSocketRequestDto {
        public String string;
        public double number;
        public long requestTimestamp;

        @Override
        public int hashCode() {
            return string.hashCode() + (int) Double.doubleToLongBits(number * 99713.80309);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MatsSocketRequestDto)) {
                throw new AssertionError(MatsSocketRequestDto.class.getSimpleName() + " was attempted equalled to ["
                        + obj + "].");
            }
            MatsSocketRequestDto other = (MatsSocketRequestDto) obj;
            return Objects.equals(this.string, other.string) && (this.number == other.number);
        }

        @Override
        public String toString() {
            return "MatsSocketRequestDto [string=" + string + ", number=" + number + "]";
        }
    }

    /**
     * A DTO for Mats-side endpoint.
     */
    public static class MatsDataTO {
        public double number;
        public String string;
        public int sleepTime;

        public MatsDataTO() {
        }

        public MatsDataTO(double number, String string) {
            this.number = number;
            this.string = string;
        }

        public MatsDataTO(double number, String string, int sleepTime) {
            this.number = number;
            this.string = string;
            this.sleepTime = sleepTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            // NOTICE: Not Class-equals, but "instanceof", since we accept the "SubDataTO" too.
            if (o == null || !(o instanceof MatsDataTO)) return false;
            MatsDataTO matsDataTO = (MatsDataTO) o;
            return Double.compare(matsDataTO.number, number) == 0 &&
                    sleepTime == matsDataTO.sleepTime &&
                    Objects.equals(string, matsDataTO.string);
        }

        @Override
        public int hashCode() {
            return Objects.hash(number, string, sleepTime);
        }

        @Override
        public String toString() {
            return "MatsDataTO [number=" + number
                    + ", string=" + string
                    + (sleepTime != 0 ? ", multiplier=" + sleepTime : "")
                    + "]";
        }
    }

    /**
     * Reply DTO class for MatsSocket Endpoint.
     */
    public static class MatsSocketReplyDto {
        public int number1;
        public double number2;
        public long requestTimestamp;

        public MatsSocketReplyDto() {
        }

        public MatsSocketReplyDto(int number1, double number2, long requestTimestamp) {
            this.number1 = number1;
            this.number2 = number2;
            this.requestTimestamp = requestTimestamp;
        }

        @Override
        public int hashCode() {
            return (number1 * 3539) + (int) Double.doubleToLongBits(number2 * 99713.80309);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MatsSocketReplyDto)) {
                throw new AssertionError(MatsSocketReplyDto.class.getSimpleName() + " was attempted equalled to [" + obj
                        + "].");
            }
            MatsSocketReplyDto other = (MatsSocketReplyDto) obj;
            return (this.number1 == other.number1) && (this.number2 == other.number2);
        }

        @Override
        public String toString() {
            return "MatsSocketReplyDto [number1=" + number1 + ", number2=" + number2 + "]";
        }
    }
}
