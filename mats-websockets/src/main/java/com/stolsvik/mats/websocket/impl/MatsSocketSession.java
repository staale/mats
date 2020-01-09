package com.stolsvik.mats.websocket.impl;

import java.io.IOException;
import java.io.Writer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;
import com.stolsvik.mats.MatsInitiator.MatsBackendRuntimeException;
import com.stolsvik.mats.MatsInitiator.MatsMessageSendRuntimeException;
import com.stolsvik.mats.websocket.MatsSocketServer.MatsSocketEndpointIncomingAuthEval;
import com.stolsvik.mats.websocket.MatsSocketServer.MatsSocketEndpointRequestContext;
import com.stolsvik.mats.websocket.impl.ClusterStoreAndForward.DataAccessException;
import com.stolsvik.mats.websocket.impl.DefaultMatsSocketServer.MatsSocketEndpointRegistration;
import com.stolsvik.mats.websocket.impl.DefaultMatsSocketServer.ReplyHandleStateDto;

/**
 * @author Endre Stølsvik 2019-11-28 12:17 - http://stolsvik.com/, endre@stolsvik.com
 */
class MatsSocketSession implements Whole<String> {
    private static final Logger log = LoggerFactory.getLogger(MatsSocketSession.class);
    private static final JavaType LIST_OF_MSG_TYPE = TypeFactory.defaultInstance().constructType(
            new TypeReference<List<MatsSocketEnvelopeDto>>() {
            });

    private final Session _webSocketSession;
    private final String _connectionId;

    // Derived
    private final DefaultMatsSocketServer _matsSocketServer;

    // Set
    private String _matsSocketSessionId;
    private String _authorization;
    private Principal _principal;

    private String _clientLibAndVersion;
    private String _appName;
    private String _appVersion;

    MatsSocketSession(DefaultMatsSocketServer matsSocketServer, Session webSocketSession) {
        _webSocketSession = webSocketSession;
        _connectionId = webSocketSession.getId() + "_" + DefaultMatsSocketServer.rnd(10);

        // Derived
        _matsSocketServer = matsSocketServer;
    }

    String getId() {
        return _matsSocketSessionId;
    }

    Session getWebSocketSession() {
        return _webSocketSession;
    }

    String getConnectionId() {
        return _connectionId;
    }

    @Override
    public void onMessage(String message) {
        if (_matsSocketSessionId != null) {
            MDC.put("matssocket.sessionId", _matsSocketSessionId);
            MDC.put("matssocket.principal", _principal.getName());
        }
        long clientMessageReceivedTimestamp = System.currentTimeMillis();
        log.info("WebSocket received message:" + message + ", session:" + _webSocketSession.getId() + ", this:"
                + DefaultMatsSocketServer.id(this));

        List<MatsSocketEnvelopeDto> envelopes;
        try {
            envelopes = _matsSocketServer.getJackson().readValue(message, LIST_OF_MSG_TYPE);
        }
        catch (JsonProcessingException e) {
            // TODO: Handle parse exceptions.
            throw new AssertionError("Parse exception", e);
        }

        log.info("Messages: " + envelopes);
        boolean shouldNotifyAboutExistingMessages = false;
        String shouldCloseSession = null;
        String allMessagesReceivedFailSubtype = null;
        String allMessagesReceivedFailDescription = null;

        // :: First look for AUTH in any of the messages
        // NOTE! Authorization header can come with ANY message!
        for (MatsSocketEnvelopeDto envelope : envelopes) {
            // ?: Pick out any Authorization header, i.e. the auth-string - it can come in any message.
            if (envelope.auth != null) {
                // -> Yes, there was an authorization header sent along with this message
                _principal = _matsSocketServer.getAuthorizationToPrincipalFunction().apply(envelope.auth);
                if (_principal == null) {
                    allMessagesReceivedFailSubtype = "AUTH_FAIL";
                    allMessagesReceivedFailDescription = "The authorization header ["
                            + DefaultMatsSocketServer.escape(envelope.auth) + "] did not produce a Principal.";

                    // TODO: SEND AUTH_FAILED (also if auth function throws)
                }
                _authorization = envelope.auth;
            }
        }
        // :: Then look for a HELLO message (should be first, but we will reply to it immediately even if part of
        // pipeline).
        for (Iterator<MatsSocketEnvelopeDto> it = envelopes.iterator(); it.hasNext();) {
            MatsSocketEnvelopeDto envelope = it.next();
            if ("HELLO".equals(envelope.t)) {
                try { // try-finally: MDC.remove(..)
                    MDC.put("matssocket.type", envelope.t);
                    if (envelope.st != null) {
                        MDC.put("matssocket.subType", envelope.st);
                    }
                    // Remove this HELLO envelope
                    it.remove();
                    // Handle the HELLO
                    try {
                        handleHello(clientMessageReceivedTimestamp, envelope);
                        // Notify client about "new" (as in existing) messages, just in case there are any.
                        shouldNotifyAboutExistingMessages = true;
                    }
                    catch (FailedHelloException e) {
                        allMessagesReceivedFailSubtype = e.subType;
                        allMessagesReceivedFailDescription = e.getMessage();
                    }
                }
                finally {
                    MDC.remove("matssocket.type");
                    MDC.remove("matssocket.subType");
                }
                break;
            }
        }
        List<MatsSocketEnvelopeDto> replyEnvelopes = new ArrayList<>();
        // :: Now go through and handle all the messages
        for (MatsSocketEnvelopeDto envelope : envelopes) {
            try { // try-finally: MDC.clear()
                MDC.put("matssocket.type", envelope.t);
                if (envelope.st != null) {
                    MDC.put("matssocket.subType", envelope.st);
                }

                if ("CLOSE_SESSION".equals(envelope.t)) {
                    handleCloseSession();
                    shouldCloseSession = (envelope.desc != null ? envelope.desc : "");
                    // Any remaining messages will be rejected..
                    allMessagesReceivedFailSubtype = "ERROR";
                    allMessagesReceivedFailDescription = "Client just closed the session!";
                    continue;
                }

                // ----- We do NOT KNOW whether we're authenticated!

                // ?: Should we fail all messages?
                if (allMessagesReceivedFailSubtype != null) {
                    MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
                    replyEnvelope.t = "RECEIVED";
                    replyEnvelope.st = allMessagesReceivedFailSubtype;
                    replyEnvelope.desc = allMessagesReceivedFailDescription;
                    replyEnvelope.cmseq = envelope.cmseq;
                    replyEnvelope.tid = envelope.tid; // TraceId
                    replyEnvelope.cid = envelope.cid; // CorrelationId
                    replyEnvelope.cmcts = envelope.cmcts; // Set by client..
                    replyEnvelope.cmrts = clientMessageReceivedTimestamp;
                    replyEnvelope.cmrnn = _matsSocketServer.getMyNodename();
                    replyEnvelope.mscts = System.currentTimeMillis();
                    replyEnvelope.mscnn = _matsSocketServer.getMyNodename();

                    // Add RECEIVED:<failed> message to "queue"
                    replyEnvelopes.add(replyEnvelope);
                    // This is handled, so go to next..
                    continue;
                }

                // ?: We do not accept other messages before authentication
                if ((_matsSocketSessionId == null) || (_principal == null)) {
                    MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
                    replyEnvelope.t = "RECEIVED";
                    replyEnvelope.st = "AUTH_FAIL";
                    replyEnvelope.desc = "Missing Authorization.";
                    replyEnvelope.cmseq = envelope.cmseq;
                    replyEnvelope.tid = envelope.tid; // TraceId
                    replyEnvelope.cid = envelope.cid; // CorrelationId
                    replyEnvelope.cmcts = envelope.cmcts; // Set by client..
                    replyEnvelope.cmrts = clientMessageReceivedTimestamp;
                    replyEnvelope.cmrnn = _matsSocketServer.getMyNodename();
                    replyEnvelope.mscts = System.currentTimeMillis();
                    replyEnvelope.mscnn = _matsSocketServer.getMyNodename();

                    // Add RECEIVED:<failed> message to "queue"
                    replyEnvelopes.add(replyEnvelope);
                    // This ERROR is handled, so go to next message
                    continue;
                }

                // ----- We are authenticated.

                if ("PING".equals(envelope.t)) {
                    MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
                    replyEnvelope.t = "PONG";
                    replyEnvelope.cmseq = envelope.cmseq;
                    replyEnvelope.tid = envelope.tid; // TraceId
                    replyEnvelope.cid = envelope.cid; // CorrelationId
                    replyEnvelope.cmcts = envelope.cmcts; // Set by client..
                    replyEnvelope.cmrts = clientMessageReceivedTimestamp;
                    replyEnvelope.cmrnn = _matsSocketServer.getMyNodename();
                    replyEnvelope.mscts = System.currentTimeMillis();
                    replyEnvelope.mscnn = _matsSocketServer.getMyNodename();

                    // Add PONG message to "queue" (should be sole message, really)
                    replyEnvelopes.add(replyEnvelope);
                    continue;
                }

                if ("SEND".equals(envelope.t) || "REQUEST".equals(envelope.t)) {
                    handleSendOrRequest(clientMessageReceivedTimestamp, replyEnvelopes, envelope);
                    continue;
                }
            }
            finally {
                MDC.remove("matssocket.type");
                MDC.remove("matssocket.subType");
            }
        }

        // TODO: Store last messageSequenceId

        // Send all replies
        if (replyEnvelopes.size() > 0) {
            sendReplies(replyEnvelopes);
        }
        // ?: Notify about existing messages
        if (shouldNotifyAboutExistingMessages) {
            // -> Yes, so do it now.
            _matsSocketServer.getMessageToWebSocketForwarder().notifyMessageFor(this);
        }
        // ?: Should we close the session?
        if (shouldCloseSession != null) {
            closeWebSocket(CloseCodes.NORMAL_CLOSURE, "From Server: Client said CLOSE_SESSION (" +
                    DefaultMatsSocketServer.escape(shouldCloseSession)
                    + "): Terminated MatsSocketSession, closing WebSocket.");
        }
    }

    private void sendReplies(List<MatsSocketEnvelopeDto> replyEnvelopes) {
        try {
            Writer sendWriter = _webSocketSession.getBasicRemote().getSendWriter();
            // Evidently this closes the Writer..
            _matsSocketServer.getJackson().writeValue(sendWriter, replyEnvelopes);
        }
        catch (JsonProcessingException e) {
            throw new AssertionError("Huh, couldn't serialize message?!", e);
        }
        catch (IOException e) {
            // TODO: Handle!
            // TODO: At least store last messageSequenceId that we had ASAP. Maybe do it async?!
            throw new AssertionError("Hot damn.", e);
        }
    }

    private static class FailedHelloException extends Exception {
        private final String subType;

        public FailedHelloException(String subType, String message) {
            super(message);
            this.subType = subType;
        }
    }

    private void handleHello(long clientMessageReceivedTimestamp, MatsSocketEnvelopeDto envelope)
            throws FailedHelloException {
        log.info("MatsSocket HELLO!");
        // ?: Auth is required - should already have been processed
        if ((_principal == null) || (_authorization == null)) {
            throw new FailedHelloException("AUTH_FAIL",
                    "While processing HELLO, we had not gotten Authorization header.");
        }

        _clientLibAndVersion = envelope.clv;
        if (_clientLibAndVersion == null) {
            throw new FailedHelloException("ERROR", "Missing ClientLibAndVersion (clv) in HELLO envelope.");
        }
        _appName = envelope.an;
        if (_appName == null) {
            throw new FailedHelloException("ERROR", "Missing AppName (an) in HELLO envelope.");
        }
        _appVersion = envelope.av;
        if (_appVersion == null) {
            throw new FailedHelloException("ERROR", "Missing AppVersion (av) in HELLO envelope.");
        }

        // ----- We're authenticated.

        boolean reconnectedOk = false;
        // ?: Do the client assume that there is an already existing session?
        if (envelope.sid != null) {
            log.info("MatsSocketSession Reconnect requested to MatsSocketSessionId [" + envelope.sid + "]");
            // -> Yes, try to find it

            // TODO: Implement remote invalidation

            // :: Local invalidation of existing session.
            Optional<MatsSocketSession> existingSession = _matsSocketServer
                    .getRegisteredLocalMatsSocketSession(envelope.sid);
            // ?: Is there an existing local Session?
            if (existingSession.isPresent()) {
                log.info(" \\- Existing LOCAL Session found!");
                // -> Yes, thus you can use it.
                /*
                 * NOTE: If it is open - which it "by definition" should not be - we close the *previous*. The question
                 * of whether to close this or previous: We chose previous because there might be reasons where the
                 * client feels that it has lost the connection, but the server hasn't yet found out. The client will
                 * then try to reconnect, and that is ok. So we close the existing. Since it is always the server that
                 * creates session Ids and they are large and globally unique, AND since we've already authenticated the
                 * user so things should be OK, this ID is obviously the one the client got the last time. So if he
                 * really wants to screw up his life by doing reconnects when he does not need to, then OK.
                 */
                // ?: If the existing is open, then close it.
                if (existingSession.get()._webSocketSession.isOpen()) {
                    try {
                        existingSession.get()._webSocketSession.close(new CloseReason(
                                CloseCodes.PROTOCOL_ERROR,
                                "Cannot have two MatsSockets with the same SessionId - closing the previous"));
                    }
                    catch (IOException e) {
                        log.warn("Got IOException when trying to close an existing session upon reconnect.",
                                e);
                    }
                }
                // You're allowed to use this, since the sessionId was already existing.
                _matsSocketSessionId = envelope.sid;
                reconnectedOk = true;
            }
            else {
                log.info(" \\- No existing local Session found, check CSAF..");
                // -> No, no local existing session, but is there an existing session in CSAF?
                try {
                    boolean sessionExists = _matsSocketServer.getClusterStoreAndForward()
                            .isSessionExists(envelope.sid);
                    // ?: Is there a CSAF Session?
                    if (sessionExists) {
                        log.info(" \\- Existing CSAF Session found!");
                        // -> Yes, there is a CSAF Session - so client can use this session
                        _matsSocketSessionId = envelope.sid;
                        reconnectedOk = true;
                    }
                    else {
                        log.info(" \\- No existing Session found..");
                    }
                }
                catch (DataAccessException e) {
                    // TODO: Fixup
                    throw new AssertionError("Damn.", e);
                }
            }
        }

        // ?: Do we have a MatsSocketSessionId by now?
        if (_matsSocketSessionId == null) {
            // -> No, so make one.
            _matsSocketSessionId = DefaultMatsSocketServer.rnd(16);
        }

        // Add Session to our active-map
        _matsSocketServer.registerLocalMatsSocketSession(this);
        try {
            _matsSocketServer.getClusterStoreAndForward().registerSessionAtThisNode(_matsSocketSessionId,
                    _connectionId);
        }
        catch (DataAccessException e) {
            // TODO: Fix
            throw new AssertionError("Damn", e);
        }

        // ----- We're now a live MatsSocketSession

        // Increase timeout to "prod timeout", now that client has said HELLO
        // TODO: Increase timeout, e.g. 75 seconds.
        _webSocketSession.setMaxIdleTimeout(30_000);

        // :: Create reply WELCOME message

        MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
        // Stack it up with props
        replyEnvelope.t = "WELCOME";
        replyEnvelope.st = (_matsSocketSessionId.equalsIgnoreCase(envelope.sid) ? "RECONNECTED" : "NEW");
        replyEnvelope.sid = _matsSocketSessionId;
        replyEnvelope.cid = envelope.cid;
        replyEnvelope.tid = envelope.tid;
        replyEnvelope.cmcts = envelope.cmcts;
        replyEnvelope.cmrts = clientMessageReceivedTimestamp;
        replyEnvelope.mscts = System.currentTimeMillis();
        replyEnvelope.mscnn = _matsSocketServer.getMyNodename();

        // Pack it over to client
        List<MatsSocketEnvelopeDto> replySingleton = Collections.singletonList(replyEnvelope);
        sendReplies(replySingleton);

        // ?: Did the client expect existing session, but there was none?
        if ("EXPECT_EXISTING".equals(envelope.st) && (!reconnectedOk)) {
            // -> Yes, so then we drop any pipelined messages with LOST_SESSION
            throw new FailedHelloException("LOST_SESSION",
                    "After an HELLO:EXPECT_EXISTING, we could not find existing session.");
        }
    }

    private void handleSendOrRequest(long clientMessageReceivedTimestamp, List<MatsSocketEnvelopeDto> replyEnvelopes,
            MatsSocketEnvelopeDto envelope) {
        String eid = envelope.eid;
        log.info("  \\- " + envelope.t + " to:[" + eid + "], reply:[" + envelope.reid + "], msg:["
                + envelope.msg + "].");
        MatsSocketEndpointRegistration<?, ?, ?, ?> registration = _matsSocketServer
                .getMatsSocketEndpointRegistration(eid);
        MatsSocketEndpointIncomingAuthEval incomingAuthEval = registration.getIncomingAuthEval();
        log.info("MatsSocketEndpointHandler for [" + eid + "]: " + incomingAuthEval);

        Object msg = deserialize((String) envelope.msg, registration.getMsIncomingClass());
        MatsSocketEndpointRequestContextImpl<?, ?> matsSocketContext = new MatsSocketEndpointRequestContextImpl(
                _matsSocketServer, registration, _matsSocketSessionId, envelope,
                clientMessageReceivedTimestamp, _authorization, _principal, msg);

        try {
            incomingAuthEval.handleIncoming(matsSocketContext, _principal, msg);
        }
        catch (MatsBackendRuntimeException | MatsMessageSendRuntimeException e) {
            // Evidently got problems talking to MQ. This is a ERROR
            // TODO: If this throws, send error back.
        }
        long nowMillis = System.currentTimeMillis();

        // :: Pipleline RECEIVED message
        MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
        replyEnvelope.t = "RECEIVED";
        replyEnvelope.st = "ACK"; // TODO: Handle failures.
        replyEnvelope.cmseq = envelope.cmseq; //
        replyEnvelope.tid = envelope.tid; // TraceId
        replyEnvelope.cid = envelope.cid; // CorrelationId
        replyEnvelope.cmcts = envelope.cmcts; // Set by client..
        replyEnvelope.cmrts = clientMessageReceivedTimestamp;
        replyEnvelope.cmrnn = _matsSocketServer.getMyNodename();
        replyEnvelope.mmsts = nowMillis;
        replyEnvelope.mscts = nowMillis;
        replyEnvelope.mscnn = _matsSocketServer.getMyNodename();

        // Add RECEIVED message to "queue"
        replyEnvelopes.add(replyEnvelope);
    }

    private void handleCloseSession() {
        // Local deregister
        _matsSocketServer.deregisterLocalMatsSocketSession(_matsSocketSessionId, _connectionId);
        try {
            // CSAF terminate
            _matsSocketServer.getClusterStoreAndForward().terminateSession(_matsSocketSessionId);
        }
        catch (DataAccessException e) {
            // TODO: Fix
            throw new AssertionError("Damn", e);
        }
    }

    void closeWebSocket(CloseCode closeCode, String reasonPhrase) {
        log.info("Shutting down WebSocket Session [" + _webSocketSession + "]");
        try {
            _webSocketSession.close(new CloseReason(closeCode, reasonPhrase));
        }
        catch (IOException e) {
            log.warn("Got Exception when trying to close WebSocket Session [" + _webSocketSession
                    + "], ignoring.", e);
        }
    }

    private <T> T deserialize(String serialized, Class<T> clazz) {
        try {
            return _matsSocketServer.getJackson().readValue(serialized, clazz);
        }
        catch (JsonProcessingException e) {
            // TODO: Handle parse exceptions.
            throw new AssertionError("Damn", e);
        }
    }

    private static class MatsSocketEndpointRequestContextImpl<MI, R> implements
            MatsSocketEndpointRequestContext<MI, R> {
        private final DefaultMatsSocketServer _matsSocketServer;
        private final MatsSocketEndpointRegistration _matsSocketEndpointRegistration;

        private final String _matsSocketSessionId;

        private final MatsSocketEnvelopeDto _envelope;
        private final long _clientMessageReceivedTimestamp;

        private final String _authorization;
        private final Principal _principal;
        private final MI _incomingMessage;

        public MatsSocketEndpointRequestContextImpl(DefaultMatsSocketServer matsSocketServer,
                MatsSocketEndpointRegistration matsSocketEndpointRegistration, String matsSocketSessionId,
                MatsSocketEnvelopeDto envelope, long clientMessageReceivedTimestamp, String authorization,
                Principal principal, MI incomingMessage) {
            _matsSocketServer = matsSocketServer;
            _matsSocketEndpointRegistration = matsSocketEndpointRegistration;
            _matsSocketSessionId = matsSocketSessionId;
            _envelope = envelope;
            _clientMessageReceivedTimestamp = clientMessageReceivedTimestamp;
            _authorization = authorization;
            _principal = principal;
            _incomingMessage = incomingMessage;
        }

        @Override
        public String getMatsSocketEndpointId() {
            return _envelope.eid;
        }

        @Override
        public String getAuthorization() {
            return _authorization;
        }

        @Override
        public Principal getPrincipal() {
            return _principal;
        }

        @Override
        public MI getMatsSocketIncomingMessage() {
            return _incomingMessage;
        }

        @Override
        public void forwardInteractiveUnreliable(MI matsMessage) {
            forwardCustom(matsMessage, customInit -> {
                customInit.to(getMatsSocketEndpointId());
                customInit.nonPersistent();
                customInit.interactive();
            });
        }

        @Override
        public void forwardInteractivePersistent(MI matsMessage) {
            forwardCustom(matsMessage, customInit -> {
                customInit.to(getMatsSocketEndpointId());
                customInit.interactive();
            });
        }

        @Override
        public void forwardCustom(MI matsMessage, InitiateLambda customInit) {
            _matsSocketServer.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
                init.from("MatsSocketEndpoint." + _envelope.eid)
                        .traceId(_envelope.tid);
                if (isRequest()) {
                    ReplyHandleStateDto sto = new ReplyHandleStateDto(_matsSocketSessionId,
                            _matsSocketEndpointRegistration.getMatsSocketEndpointId(), _envelope.reid,
                            _envelope.cid, _envelope.cmseq, _envelope.cmcts, _clientMessageReceivedTimestamp,
                            System.currentTimeMillis(), _matsSocketServer.getMyNodename());
                    // Set ReplyTo parameter
                    init.replyTo(_matsSocketServer.getReplyTerminatorId(), sto);
                    // Invoke the customizer
                    customInit.initiate(init);
                    // Send the REQUEST message
                    init.request(matsMessage);
                }
                else {
                    // Invoke the customizer
                    customInit.initiate(init);
                    // Send the SEND message
                    init.send(matsMessage);
                }
            });
        }

        @Override
        public boolean isRequest() {
            return _envelope.t.equals("REQUEST");
        }

        @Override
        public void reply(R matsSocketReplyMessage) {
            // TODO: Implement
            throw new IllegalStateException("Not yet implemented.");
        }
    }
}