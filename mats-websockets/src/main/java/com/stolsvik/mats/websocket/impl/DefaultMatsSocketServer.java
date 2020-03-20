package com.stolsvik.mats.websocket.impl;

import static com.stolsvik.mats.websocket.MatsSocketServer.MessageType.REJECT;
import static com.stolsvik.mats.websocket.MatsSocketServer.MessageType.REQUEST;
import static com.stolsvik.mats.websocket.MatsSocketServer.MessageType.RESOLVE;
import static com.stolsvik.mats.websocket.MatsSocketServer.MessageType.SEND;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Builder;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stolsvik.mats.MatsEndpoint.DetachedProcessContext;
import com.stolsvik.mats.MatsEndpoint.MatsObject;
import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.MatsFactory.FactoryConfig;
import com.stolsvik.mats.MatsInitiator.MatsBackendException;
import com.stolsvik.mats.MatsInitiator.MatsMessageSendException;
import com.stolsvik.mats.websocket.AuthenticationPlugin;
import com.stolsvik.mats.websocket.AuthenticationPlugin.DebugOption;
import com.stolsvik.mats.websocket.AuthenticationPlugin.SessionAuthenticator;
import com.stolsvik.mats.websocket.ClusterStoreAndForward;
import com.stolsvik.mats.websocket.ClusterStoreAndForward.CurrentNode;
import com.stolsvik.mats.websocket.ClusterStoreAndForward.DataAccessException;
import com.stolsvik.mats.websocket.MatsSocketServer;
import com.stolsvik.mats.websocket.impl.MatsSocketEnvelopeDto.DebugDto;
import com.stolsvik.mats.websocket.impl.MatsSocketSessionAndMessageHandler.Processed;

/**
 * @author Endre Stølsvik 2019-11-28 12:17 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DefaultMatsSocketServer implements MatsSocketServer, MatsSocketStatics {
    private static final Logger log = LoggerFactory.getLogger(DefaultMatsSocketServer.class);

    private static final String MATS_EP_PREFIX = "MatsSocket";

    private static final String MATS_EP_POSTFIX_REPLY_HANDLER = "replyHandler";

    private static final String MATS_EP_MIDFIX_NEWMESSAGE = "notifyNewMessage";

    private static final String MATS_EP_MIDFIX_NODECONTROL = "nodeControl";

    private static final JavaType TYPE_LIST_OF_MSG = TypeFactory.defaultInstance().constructType(
            new TypeReference<List<MatsSocketEnvelopeDto>>() {
            });

    /**
     * Variant of the
     * {@link #createMatsSocketServer(ServerContainer, MatsFactory, ClusterStoreAndForward, AuthenticationPlugin, String, String)
     * full method} that uses the {@link FactoryConfig#getAppName() appName} that the MatsFactory is configured with as
     * the 'instanceName' parameter.
     *
     * @param serverContainer
     *            the WebSocket {@link ServerContainer}, typically gotten from the Servlet Container.
     * @param matsFactory
     *            The {@link MatsFactory} which we should hook into for both sending requests and setting up endpoints
     *            to receive replies.
     * @param clusterStoreAndForward
     *            an implementation of {@link ClusterStoreAndForward} which temporarily holds replies while finding the
     *            right node that holds the WebSocket connection - and hold them till the client reconnects in case he
     *            has disconnected in the mean time.
     * @param authenticationPlugin
     *            the piece of code that turns an Authorization String into a Principal. Must be pretty fast, as it is
     *            invoked synchronously - keep any IPC fast, otherwise all your threads of the container might be used
     *            up. If the function throws or returns null, authorization did not go through.
     * @param websocketPath
     *            The path onto which the WebSocket Server Endpoint will be mounted. Suggestion: "/matssocket". If you
     *            need multiple {@link MatsSocketServer}s, e.g. because you need two types of authentication, they need
     *            to be mounted on different paths.
     *
     * @return a MatsSocketServer instance, now hooked into both the WebSocket {@link ServerContainer} and the
     *         {@link MatsFactory}.
     */
    public static MatsSocketServer createMatsSocketServer(ServerContainer serverContainer,
            MatsFactory matsFactory,
            ClusterStoreAndForward clusterStoreAndForward,
            AuthenticationPlugin authenticationPlugin,
            String websocketPath) {
        String serverName = matsFactory.getFactoryConfig().getAppName();
        return createMatsSocketServer(serverContainer, matsFactory, clusterStoreAndForward, authenticationPlugin,
                serverName, websocketPath);
    }

    /**
     * Create a MatsSocketServer, piecing together necessary bits.
     *
     * @param serverContainer
     *            the WebSocket {@link ServerContainer}, typically gotten from the Servlet Container.
     * @param matsFactory
     *            The {@link MatsFactory} which we should hook into for both sending requests and setting up endpoints
     *            to receive replies.
     * @param clusterStoreAndForward
     *            an implementation of {@link ClusterStoreAndForward} which temporarily holds replies while finding the
     *            right node that holds the WebSocket connection - and hold them till the client reconnects in case he
     *            has disconnected in the mean time.
     * @param authenticationPlugin
     *            the piece of code that turns an Authorization String into a Principal. Must be pretty fast, as it is
     *            invoked synchronously - keep any IPC fast, otherwise all your threads of the container might be used
     *            up. If the function throws or returns null, authorization did not go through.
     * @param instanceName
     *            a unique name of this MatsSocketServer setup, at least within the MQ system the MatsFactory is
     *            connected to, as it is used to postfix/uniquify the endpoints that the MatsSocketServer creates on the
     *            MatsFactory. To illustrate: The variant of this factory method that does not take 'instanceName' uses
     *            the {@link FactoryConfig#getAppName() appName} that the MatsFactory is configured with.
     * @param websocketPath
     *            The path onto which the WebSocket Server Endpoint will be mounted. Suggestion: "/matssocket". If you
     *            need multiple {@link MatsSocketServer}s, e.g. because you need two types of authentication, they need
     *            to be mounted on different paths.
     *
     * @return a MatsSocketServer instance, now hooked into both the WebSocket {@link ServerContainer} and the
     *         {@link MatsFactory}.
     */
    public static MatsSocketServer createMatsSocketServer(ServerContainer serverContainer,
            MatsFactory matsFactory,
            ClusterStoreAndForward clusterStoreAndForward,
            AuthenticationPlugin authenticationPlugin,
            String instanceName,
            String websocketPath) {
        // Boot ClusterStoreAndForward
        clusterStoreAndForward.boot();

        // Create the MatsSocketServer..
        DefaultMatsSocketServer matsSocketServer = new DefaultMatsSocketServer(matsFactory, clusterStoreAndForward,
                instanceName, authenticationPlugin);

        log.info("Registering MatsSockets' sole WebSocket endpoint.");
        Configurator configurator = new Configurator() {

            ThreadLocal<HandshakeRequestResponse> _threadLocal_HandShake = new ThreadLocal<>();
            ThreadLocal<SessionAuthenticator> _threadLocal_SessionAuthenticator = new ThreadLocal<>();

            private SessionAuthenticator getSessionAuthenticator() {
                SessionAuthenticator sessionAuthenticator = _threadLocal_SessionAuthenticator.get();
                if (sessionAuthenticator == null) {
                    sessionAuthenticator = matsSocketServer.getAuthenticationPlugin().newSessionAuthenticator();
                    _threadLocal_SessionAuthenticator.set(sessionAuthenticator);
                }
                return sessionAuthenticator;
            }

            @Override
            public boolean checkOrigin(String originHeaderValue) {
                log.info("WebSocket connection!");
                boolean ok = getSessionAuthenticator().checkOrigin(originHeaderValue);
                log.info(" \\- checkOrigin(" + originHeaderValue + "). Asked SessionAuthenticator, returned: "
                        + (ok ? "OK" : "NOT OK!"));
                return ok;
            }

            @Override
            public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
                log.info(" \\- getNegotiatedSubprotocol: supported by MatsSocket impl:" + supported
                        + ", requested by client:" + requested);
                return super.getNegotiatedSubprotocol(supported, requested);
            }

            @Override
            public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
                log.info(" \\- getNegotiatedExtensions: installed in server:" + installed + ", requested by client:"
                        + requested);
                return super.getNegotiatedExtensions(installed, requested);
            }

            @Override
            public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request,
                    HandshakeResponse response) {
                _threadLocal_HandShake.set(new HandshakeRequestResponse(request, response));
                boolean ok = getSessionAuthenticator().checkHandshake(sec, request, response);
                log.info(" \\- modifyHandshake(config, request, response). Asked SessionAuthenticator, returned: "
                        + (ok ? "OK" : "NOT OK!"));
                if (!ok) {
                    throw new IllegalStateException("SessionAuthenticator did nok like the Handshake.");
                }
                super.modifyHandshake(sec, request, response);
            }

            @Override
            @SuppressWarnings("unchecked") // The cast to (T) is not dodgy.
            public <T> T getEndpointInstance(Class<T> endpointClass) {
                if (endpointClass != MatsWebSocketInstance.class) {
                    throw new AssertionError("Cannot create Endpoints of type [" + endpointClass.getName()
                            + "]");
                }
                log.info(" \\- Instantiating a " + MatsWebSocketInstance.class.getSimpleName() + "!");
                HandshakeRequestResponse handshakeRequestResponse = _threadLocal_HandShake.get();
                _threadLocal_HandShake.remove();
                return (T) new MatsWebSocketInstance(matsSocketServer, getSessionAuthenticator(),
                        handshakeRequestResponse);
            }
        };
        try {
            serverContainer.addEndpoint(Builder.create(MatsWebSocketInstance.class, websocketPath)
                    .subprotocols(Collections.singletonList("matssocket"))
                    .configurator(configurator)
                    .build());
        }
        catch (DeploymentException e) {
            throw new AssertionError("Could not register MatsSocket endpoint", e);
        }
        return matsSocketServer;
    }

    private static class HandshakeRequestResponse {
        private final HandshakeRequest _handshakeRequest;
        private final HandshakeResponse _handshakeResponse;

        public HandshakeRequestResponse(HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse) {
            _handshakeRequest = handshakeRequest;
            _handshakeResponse = handshakeResponse;
        }
    }

    // Constructor init
    private final MatsFactory _matsFactory;
    private final ClusterStoreAndForward _clusterStoreAndForward;
    private final ObjectMapper _jackson;
    private final ObjectReader _envelopeObjectReader;
    private final ObjectWriter _envelopeObjectWriter;
    private final ObjectReader _envelopeListObjectReader;
    private final ObjectWriter _envelopeListObjectWriter;
    private final String _terminatorId_ReplyHandler;
    private final String _terminatorId_NewMessage_NodePrefix;
    private final String _terminatorId_NodeControl_NodePrefix;
    private final MessageToWebSocketForwarder _messageToWebSocketForwarder;
    private final AuthenticationPlugin _authenticationPlugin;

    // In-line init
    private final ConcurrentHashMap<String, MatsSocketEndpointRegistration<?, ?, ?, ?>> _matsSocketEndpointsByMatsSocketEndpointId = new ConcurrentHashMap<>();
    private final Map<String, MatsSocketSessionAndMessageHandler> _activeSessionsByMatsSocketSessionId_x = new LinkedHashMap<>();

    private DefaultMatsSocketServer(MatsFactory matsFactory, ClusterStoreAndForward clusterStoreAndForward,
            String instanceName, AuthenticationPlugin authenticationPlugin) {
        _matsFactory = matsFactory;
        _clusterStoreAndForward = clusterStoreAndForward;
        _jackson = jacksonMapper();
        _authenticationPlugin = authenticationPlugin;
        _envelopeObjectReader = _jackson.readerFor(MatsSocketEnvelopeDto.class);
        _envelopeObjectWriter = _jackson.writerFor(MatsSocketEnvelopeDto.class);
        _envelopeListObjectReader = _jackson.readerFor(TYPE_LIST_OF_MSG);
        _envelopeListObjectWriter = _jackson.writerFor(TYPE_LIST_OF_MSG);
        // TODO: "Escape" the instanceName.
        _terminatorId_ReplyHandler = MATS_EP_PREFIX + '.' + instanceName + '.' + MATS_EP_POSTFIX_REPLY_HANDLER;
        // TODO: "Escape" the instanceName.
        _terminatorId_NewMessage_NodePrefix = MATS_EP_PREFIX + '.' + instanceName + '.' + MATS_EP_MIDFIX_NEWMESSAGE;
        // TODO: "Escape" the instanceName.
        _terminatorId_NodeControl_NodePrefix = MATS_EP_PREFIX + '.' + instanceName + '.' + MATS_EP_MIDFIX_NODECONTROL;

        int corePoolSize = Math.max(5, matsFactory.getFactoryConfig().getConcurrency() * 4);
        int maximumPoolSize = Math.max(100, matsFactory.getFactoryConfig().getConcurrency() * 20);
        _messageToWebSocketForwarder = new MessageToWebSocketForwarder(this,
                clusterStoreAndForward, corePoolSize, maximumPoolSize);

        // Register our Reply-handler (common on all nodes). Note that the reply often comes in on wrong note: Forward.
        matsFactory.terminator(_terminatorId_ReplyHandler,
                ReplyHandleStateDto.class, MatsObject.class, this::mats_replyHandler);

        // Register node control endpoint (node-specific)
        matsFactory.subscriptionTerminator(terminatorId_NodeControl_ForNode(getMyNodename()),
                NodeControlStateDto.class, MatsObject.class, this::mats_nodeControl);

        // Register our Forward to WebSocket handler (node-specific).
        matsFactory.subscriptionTerminator(terminatorId_NotifyNewMessage_ForNode(getMyNodename()),
                Void.class, String.class, this::mats_notifyNewMessage);
    }

    private volatile boolean _stopped = false;

    String getMyNodename() {
        return _matsFactory.getFactoryConfig().getNodename();
    }

    MatsFactory getMatsFactory() {
        return _matsFactory;
    }

    ClusterStoreAndForward getClusterStoreAndForward() {
        return _clusterStoreAndForward;
    }

    ObjectMapper getJackson() {
        return _jackson;
    }

    ObjectReader getEnvelopeObjectReader() {
        return _envelopeObjectReader;
    }

    ObjectWriter getEnvelopeObjectWriter() {
        return _envelopeObjectWriter;
    }

    ObjectReader getEnvelopeListObjectReader() {
        return _envelopeListObjectReader;
    }

    ObjectWriter getEnvelopeListObjectWriter() {
        return _envelopeListObjectWriter;
    }

    MessageToWebSocketForwarder getMessageToWebSocketForwarder() {
        return _messageToWebSocketForwarder;
    }

    String getReplyTerminatorId() {
        return _terminatorId_ReplyHandler;
    }

    AuthenticationPlugin getAuthenticationPlugin() {
        return _authenticationPlugin;
    }

    private String terminatorId_NotifyNewMessage_ForNode(String nodename) {
        // TODO: "Escape" the nodename (just in case)
        return _terminatorId_NewMessage_NodePrefix + '.' + nodename;
    }

    String terminatorId_NodeControl_ForNode(String nodename) {
        // TODO: "Escape" the nodename (just in case)
        return _terminatorId_NodeControl_NodePrefix + '.' + nodename;
    }

    @Override
    public <I, MI, MR, R> MatsSocketEndpoint<I, MI, MR, R> matsSocketEndpoint(String matsSocketEndpointId,
            Class<I> msIncomingClass, Class<MI> matsIncomingClass, Class<MR> matsReplyClass, Class<R> msReplyClass,
            IncomingAuthorizationAndAdapter<I, MI, R> incomingAuthEval, ReplyAdapter<MR, R> replyAdapter) {
        // :: Create it
        MatsSocketEndpointRegistration<I, MI, MR, R> matsSocketRegistration = new MatsSocketEndpointRegistration<>(
                matsSocketEndpointId, msIncomingClass, matsIncomingClass, matsReplyClass, msReplyClass,
                incomingAuthEval, replyAdapter);
        // Register it.
        MatsSocketEndpointRegistration<?, ?, ?, ?> existing = _matsSocketEndpointsByMatsSocketEndpointId.putIfAbsent(
                matsSocketEndpointId, matsSocketRegistration);
        // Assert that there was no existing mapping
        if (existing != null) {
            // -> There was existing mapping - shall not happen.
            throw new IllegalStateException("Cannot register a MatsSocket onto an EndpointId [" + matsSocketEndpointId
                    + "] which already is taken, existing registration: [" + existing
                    + "]. NOTE: The Cause of this exception is the DebugStacktrace of the existing registration!",
                    existing._registrationPoint);
        }
        return matsSocketRegistration;
    }

    @Override
    public void send(String sessionId, String traceId, String clientTerminatorId, Object messageDto) {
        // Create ServerMessageId
        String serverMessageId = serverMessageId();
        // Create DebugDto - must do this "eagerly", as we do not know what the client actually wants.
        DebugDto debug = new DebugDto();
        debug.smcts = System.currentTimeMillis();
        debug.smcnn = getMyNodename();
        // TODO move these:
        debug.mscts = REPLACE_VALUE_TIMESTAMP; // Reply Message to Client Timestamp (not yet determined)
        debug.mscnn = REPLACE_VALUE_REPLY_NODENAME; // The replying nodename (not yet determined)

        // Create Envelope
        MatsSocketEnvelopeDto msReplyEnvelope = new MatsSocketEnvelopeDto();
        msReplyEnvelope.t = SEND;
        msReplyEnvelope.eid = clientTerminatorId;
        msReplyEnvelope.smid = serverMessageId;
        msReplyEnvelope.tid = traceId;
        msReplyEnvelope.debug = debug;

        // Serialize and store the envelope for forward ("StoreAndForward")
        String serializedEnvelope = serializeEnvelope(msReplyEnvelope);
        // Serialize the actual message
        String serializedMessage = serializeMessageObject(messageDto);

        Optional<CurrentNode> nodeNameHoldingWebSocket;
        try {
            nodeNameHoldingWebSocket = _clusterStoreAndForward.storeMessageInOutbox(
                    sessionId, serverMessageId, null, traceId, msReplyEnvelope.t, serializedEnvelope, serializedMessage,
                    null);
        }
        catch (DataAccessException e) {
            // TODO: Fix
            throw new AssertionError("Damn", e);
        }

        pingLocalOrRemoteNode(sessionId, nodeNameHoldingWebSocket, "MatsSocketServer.send");
    }

    @Override
    public void request(String sessionId, String traceId, String clientEndpointId, Object requestDto,
            String replyToMatsSocketTerminatorId, String correlationString, byte[] correlationBinary) {
        // Create ServerMessageId
        String serverMessageId = serverMessageId();
        // Create DebugDto - must do this "eagerly", as we do not know what the client actually wants.
        DebugDto debug = new DebugDto();
        debug.smcts = System.currentTimeMillis();
        debug.smcnn = getMyNodename();
        // TODO move these:
        debug.mscts = REPLACE_VALUE_TIMESTAMP; // Reply Message to Client Timestamp (not yet determined)
        debug.mscnn = REPLACE_VALUE_REPLY_NODENAME; // The replying nodename (not yet determined)

        // Create Envelope
        MatsSocketEnvelopeDto msReplyEnvelope = new MatsSocketEnvelopeDto();
        msReplyEnvelope.t = REQUEST;
        msReplyEnvelope.eid = clientEndpointId;
        msReplyEnvelope.smid = serverMessageId;
        msReplyEnvelope.tid = traceId;
        msReplyEnvelope.debug = debug;

        // Serialize and store the envelope for forward ("StoreAndForward")
        String serializedEnvelope = serializeEnvelope(msReplyEnvelope);
        // Serialize the actual message
        String serializedMessage = serializeMessageObject(requestDto);

        Optional<CurrentNode> nodeNameHoldingWebSocket;
        try {
            // Store Correlation information
            _clusterStoreAndForward.storeRequestCorrelation(sessionId, serverMessageId, System.currentTimeMillis(),
                    replyToMatsSocketTerminatorId, correlationString, correlationBinary);
            // Stick the message in Outbox
            nodeNameHoldingWebSocket = _clusterStoreAndForward.storeMessageInOutbox(
                    sessionId, serverMessageId, null, traceId, msReplyEnvelope.t, serializedEnvelope, serializedMessage,
                    null);
        }
        catch (DataAccessException e) {
            // TODO: Fix
            throw new AssertionError("Damn", e);
        }

        pingLocalOrRemoteNode(sessionId, nodeNameHoldingWebSocket, "MatsSocketServer.request");
    }

    private void pingLocalOrRemoteNode(String sessionId, Optional<CurrentNode> nodeNameHoldingWebSocket,
            String from) {
        // ?: Check if WE have the session locally
        Optional<MatsSocketSessionAndMessageHandler> localMatsSocketSession = getRegisteredLocalMatsSocketSession(sessionId);
        if (localMatsSocketSession.isPresent()) {
            // -> Yes, evidently we have it! Do local forward.
            _messageToWebSocketForwarder.newMessagesInCsafNotify(localMatsSocketSession.get());
            return;
        }
        // E-> We did not have it locally - do remote ping.
        // ?: If we do have a nodename, ping it about new message
        if (nodeNameHoldingWebSocket.isPresent()) {
            CurrentNode currentNode = nodeNameHoldingWebSocket.get();
            // -> Yes we got a nodename, ping it.
            try {
                // TODO: If this is invoked within a Mats stage, then it should use the process context's initiator
                // TODO: Idea: Either have a specific "MatsFactory.getCurrentTreadLocalMatsInitiate()"
                // TODO: .. or let matsFactory.getDefaultInitiator() be magic and do such switcheroo internally.

                _matsFactory.getDefaultInitiator().initiate(
                        init -> init
                                .from(from)
                                .traceId("PingWebSocketHolder_" + rnd(6))
                                .to(terminatorId_NotifyNewMessage_ForNode(currentNode.getNodename()))
                                .publish(sessionId));
            }
            catch (MatsBackendException | MatsMessageSendException e) {
                log.warn("Got [" + e.getClass().getSimpleName()
                        + "] when trying to send Mats-ping to current node holding"
                        + " the websocket [" + currentNode.getNodename()
                        + "], ignoring, hoping for self-healer to figure it out", e);
            }
        }
    }

    @Override
    public void closeSession(String matsSocketSessionId) {
        log.info("Got 'out-of-band' request to Close MatsSocketSessionId: [" + matsSocketSessionId + "].");
        if (matsSocketSessionId == null) {
            throw new NullPointerException("matsSocketSessionId");
        }
        // :: Check if session is still registered with CSAF
        try {
            Optional<CurrentNode> currentNode = _clusterStoreAndForward
                    .getCurrentRegisteredNodeForSession(matsSocketSessionId);
            // ?: Did we currently have a registered session?
            if (currentNode.isPresent()) {
                // -> Yes - Send this request over to that node to kill it both locally there, and in CSAF.
                // NOTE: It might be this node that is the current node, and that's fine.
                try {
                    _matsFactory.getDefaultInitiator().initiate(msg -> msg
                            .traceId("MatsSocket.internal.outOfBandSessionClose[" + matsSocketSessionId + "]" + rnd(5))
                            .from(MATS_EP_PREFIX + ".internal.outOfBandSessionClose")
                            .to(terminatorId_NodeControl_ForNode(currentNode.get().getNodename()))
                            .publish(new NodeControl_CloseSessionDto(matsSocketSessionId), new NodeControlStateDto(
                                    NodeControlStateDto.CLOSE_SESSION)));
                    return;
                }
                catch (MatsBackendException | MatsMessageSendException e) {
                    log.warn("'Out of band'/Server side Close Session, and the MatsSocket Session was still registered"
                            + " in CSAF, but we didn't manage to communicate with MQ. Will ignore this and close it"
                            + " from CSAF anyway.", e);
                }
            }
        }
        catch (DataAccessException e) {
            // TODO: Fix.
            throw new AssertionError("Damn.");
        }

        // E-> No currently registered local session, so close it directly in CSAF here.

        // :: Close it from the CSAF
        try {
            _clusterStoreAndForward.closeSession(matsSocketSessionId);
        }
        catch (DataAccessException e) {
            // TODO: Fix.
            throw new AssertionError("Damn.");
        }
    }

    void closeWebSocketFor(String matsSocketSessionId, CurrentNode currentNode) {
        try {
            _matsFactory.getDefaultInitiator().initiate(msg -> msg
                    .traceId("MatsSocket.internal.webSocketClose[" + matsSocketSessionId + "]" + rnd(5))
                    .from(MATS_EP_PREFIX + ".internal.outOfBandSessionClose")
                    .to(terminatorId_NodeControl_ForNode(currentNode.getNodename()))
                    .publish(new NodeControl_CloseWebSocketDto(matsSocketSessionId, currentNode.getConnectionId()),
                            new NodeControlStateDto(NodeControlStateDto.CLOSE_WEBSOCKET)));
        }
        catch (MatsBackendException | MatsMessageSendException e) {
            log.warn("Trying to close existing WebSocket due to reconnect for MatsSocketSessionId, but we"
                    + " didn't manage to communicate with the MQ.", e);
        }
    }

    void registerLocalMatsSocketSession(MatsSocketSessionAndMessageHandler matsSocketSessionAndMessageHandler) {
        synchronized (_activeSessionsByMatsSocketSessionId_x) {
            _activeSessionsByMatsSocketSessionId_x.put(matsSocketSessionAndMessageHandler.getMatsSocketSessionId(),
                    matsSocketSessionAndMessageHandler);
        }
    }

    Optional<MatsSocketSessionAndMessageHandler> getRegisteredLocalMatsSocketSession(String matsSocketSessionId) {
        synchronized (_activeSessionsByMatsSocketSessionId_x) {
            return Optional.ofNullable(_activeSessionsByMatsSocketSessionId_x.get(matsSocketSessionId));
        }
    }

    private int numberOfRegisteredLocalMatsSocketSessions() {
        synchronized (_activeSessionsByMatsSocketSessionId_x) {
            return _activeSessionsByMatsSocketSessionId_x.size();
        }
    }

    private List<MatsSocketSessionAndMessageHandler> getCurrentLocalRegisteredMatsSocketSessions() {
        synchronized (_activeSessionsByMatsSocketSessionId_x) {
            return new ArrayList<>(_activeSessionsByMatsSocketSessionId_x.values());
        }
    }

    void deregisterLocalMatsSocketSession(String matsSocketSessionId, String connectionId) {
        synchronized (_activeSessionsByMatsSocketSessionId_x) {
            MatsSocketSessionAndMessageHandler matsSocketSessionAndMessageHandler = _activeSessionsByMatsSocketSessionId_x.get(
                    matsSocketSessionId);
            if (matsSocketSessionAndMessageHandler != null) {
                if (matsSocketSessionAndMessageHandler.getConnectionId().equals(connectionId)) {
                    _activeSessionsByMatsSocketSessionId_x.remove(matsSocketSessionId);
                }
            }
        }
    }

    @Override
    public void stop(int gracefulShutdownMillis) {
        log.info("Asked to shut down MatsSocketServer [" + id(this)
                + "], containing [" + getCurrentLocalRegisteredMatsSocketSessions() + "] sessions.");

        // Hinder further WebSockets connecting to us.
        _stopped = true;

        // Shut down forwarder thread
        _messageToWebSocketForwarder.shutdown();

        // Deregister all MatsSocketSession from us, with SERVICE_RESTART, which asks them to reconnect
        getCurrentLocalRegisteredMatsSocketSessions().forEach(msmh -> {
            msmh.deregisterSessionAndCloseWebSocket(MatsSocketCloseCodes.SERVICE_RESTART,
                    "From Server: Server instance is going down, please reconnect.");
        });
    }

    Optional<MatsSocketEndpointRegistration<?, ?, ?, ?>> getMatsSocketEndpointRegistration(String endpointId) {
        if (endpointId == null) {
            throw new NullPointerException("endpointId");
        }
        MatsSocketEndpointRegistration<?, ?, ?, ?> registration = _matsSocketEndpointsByMatsSocketEndpointId.get(
                endpointId);
        return Optional.ofNullable(registration);
    }

    /**
     * Shall be one instance per socket (i.e. from the docs: "..there will be precisely one endpoint instance per active
     * client connection"), thus there should be 1:1 correlation between this instance and the single Session object for
     * the same cardinality (per client:server connection).
     */
    static class MatsWebSocketInstance extends Endpoint {
        private final DefaultMatsSocketServer _matsSocketServer;
        private final SessionAuthenticator _sessionAuthenticator;
        private final HandshakeRequestResponse _handshakeRequestResponse;

        // Will be set when onOpen is invoked
        private String _connectionId;
        private MatsSocketSessionAndMessageHandler _matsSocketSessionAndMessageHandler;

        public MatsWebSocketInstance(DefaultMatsSocketServer matsSocketServer,
                SessionAuthenticator sessionAuthenticator,
                HandshakeRequestResponse handshakeRequestResponse) {
            log.info("Created MatsWebSocketEndpointInstance: " + id(this));
            _matsSocketServer = matsSocketServer;
            _sessionAuthenticator = sessionAuthenticator;
            _handshakeRequestResponse = handshakeRequestResponse;
        }

        private boolean _isTimeoutException;

        public DefaultMatsSocketServer getMatsSocketServer() {
            return _matsSocketServer;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            log.info("WebSocket @OnOpen, WebSocket SessionId:" + session.getId()
                    + ", WebSocket Session:" + id(session) + ", this:" + id(this));

            // Notice: On a particular server, the session.getId() is already unique. On Jetty: integer sequence.
            _connectionId = session.getId() + "_" + rnd(6);

            // ?: If we are going down, then immediately close it.
            if (_matsSocketServer._stopped) {
                closeWebSocket(session, MatsSocketCloseCodes.SERVICE_RESTART,
                        "This server is going down, perform a (re)connect to another instance.");
                return;
            }

            // We do not (yet) handle binary messages, so limit that pretty hard.
            session.setMaxBinaryMessageBufferSize(1024);
            // Set low limits for the HELLO message, 20KiB should be plenty even for quite large Oauth2 bearer tokens.
            session.setMaxTextMessageBufferSize(20 * 1024);
            // Set low time to say HELLO after the connect. (The default clients say it immediately on "onopen".)
            session.setMaxIdleTimeout(2500);

            try {
                boolean ok = _sessionAuthenticator.onOpen(session, (ServerEndpointConfig) config);
                log.info("webSocket.onOpen(..): Asked SessionAuthenticator.onOpen(..), returned: "
                        + (ok ? "OK" : "NOT OK!"));
                if (!ok) {
                    closeWebSocket(session, MatsSocketCloseCodes.VIOLATED_POLICY, "SessionAuthenticator did not want"
                            + " this session to proceed");
                    return;
                }
            }
            catch (Throwable t) {
                log.error("webSocket.onOpen(..): Got throwable when invoking SessionAuthenticator.onOpen(..)."
                        + " Closing WebSocket.", t);
                closeWebSocket(session, MatsSocketCloseCodes.VIOLATED_POLICY, "SessionAuthenticator did not want this"
                        + " session to proceed");
                return;
            }

            _matsSocketSessionAndMessageHandler = new MatsSocketSessionAndMessageHandler(_matsSocketServer, session, _connectionId,
                    _handshakeRequestResponse._handshakeRequest, _sessionAuthenticator);
            session.addMessageHandler(_matsSocketSessionAndMessageHandler);
        }

        @Override
        public void onError(Session session, Throwable thr) {
            // Deduce if this is a Server side timeout
            // Note: This is modelled after Jetty. If different with other JSR 356 implementations, please expand.
            _isTimeoutException = (thr.getCause() instanceof TimeoutException
                    || ((thr.getMessage() != null) && thr.getMessage().toLowerCase().contains("timeout expired")));

            // ?: Is it a timeout situation?
            if (_isTimeoutException) {
                // -> Yes, timeout. This is handled, and does not constitute a "close session", client might
                // just have lost connection and want's to reconnect soon. Just log info.
                log.info("WebSocket @OnError: WebSocket server timed out the connection. MatsSocket SessionId: ["
                        + (
                        _matsSocketSessionAndMessageHandler == null
                                ? "no MatsSocketSession"
                                : _matsSocketSessionAndMessageHandler.getMatsSocketSessionId())
                        + "], WebSocket SessionId:" + session.getId() + ", this:" + id(this));
            }
            else {
                // -> No, not timeout. So this is some kind of unexpected situation, typically raised from the
                // MastSocket implementation on MessageHandler. Log warn. This will close the session.
                log.warn("WebSocket @OnError, MatsSocket SessionId: ["
                        + (
                                _matsSocketSessionAndMessageHandler == null
                                ? "no MatsSocketSession"
                                : _matsSocketSessionAndMessageHandler.getMatsSocketSessionId())
                        + "], WebSocket SessionId:" + session.getId() + ", this:" + id(this),
                        new Exception("MatsSocketServer's webSocket.onError(..) handler", thr));
            }
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            log.info("WebSocket @OnClose, code:[" + MatsSocketCloseCodes.getCloseCode(closeReason.getCloseCode()
                    .getCode()) + "] (timeout:[" + _isTimeoutException + "]), reason:[" + closeReason.getReasonPhrase()
                    + "], MatsSocket SessionId: [" + (
                    _matsSocketSessionAndMessageHandler == null
                            ? "no MatsSocketSession"
                            : _matsSocketSessionAndMessageHandler.getMatsSocketSessionId())
                    + "], ConnectionId:" + _connectionId + ", this:" + id(this));

            // ?: Have we gotten MatsSocketSession yet? (In case "onOpen" has not been invoked yet. Can it happen?!).
            if (_matsSocketSessionAndMessageHandler != null) {
                // -> Yes, so either close session, or just deregister us from local and CSAF
                // Is this a GOING_AWAY that is NOT from the server side? (Jetty gives this on timeout)
                boolean goingAwayFromClientSide = (MatsSocketCloseCodes.GOING_AWAY.getCode() == closeReason
                        .getCloseCode().getCode()) && (!_isTimeoutException);
                // ?: Did the client or Server want to actually Close Session?
                // NOTE: Need to check by the 'code' integers, since no real enum (CloseCode is an interface).
                if ((MatsSocketCloseCodes.UNEXPECTED_CONDITION.getCode() == closeReason.getCloseCode().getCode())
                        || (MatsSocketCloseCodes.PROTOCOL_ERROR.getCode() == closeReason.getCloseCode().getCode())
                        || (MatsSocketCloseCodes.VIOLATED_POLICY.getCode() == closeReason.getCloseCode().getCode())
                        || (MatsSocketCloseCodes.CLOSE_SESSION.getCode() == closeReason.getCloseCode().getCode())
                        || (MatsSocketCloseCodes.SESSION_LOST.getCode() == closeReason.getCloseCode().getCode())
                        || goingAwayFromClientSide) {
                    // -> Yes, this was a one of the actual-close CloseCodes, or a "GOING AWAY" that was NOT
                    // initiated from server side, which means that we should actually close this session
                    log.info("Explicitly Closed MatsSocketSession due to CloseCode ["
                            + MatsSocketCloseCodes.getCloseCode(closeReason.getCloseCode().getCode())
                            + "] (timeout:[" + _isTimeoutException + "]), actually closing (terminating) it.");
                    // Close MatsSocketSession
                    _matsSocketSessionAndMessageHandler.closeSession();
                }
                else {
                    // -> No, this was a broken connection, or something else like an explicit disconnect w/o close
                    log.info("Got a non-closing CloseCode [" + MatsSocketCloseCodes.getCloseCode(closeReason
                            .getCloseCode().getCode()) + "] (timeout:[" + _isTimeoutException
                            + "]), assuming that Client might want to reconnect - deregistering"
                            + " MatsSocketSession from CSAF.");
                    // Deregister MatsSocketSession
                    _matsSocketSessionAndMessageHandler.deregisterSession();
                }
            }
        }
    }

    static void closeWebSocket(Session webSocketSession, CloseCode closeCode, String reasonPhrase) {
        log.info("Closing WebSocket SessionId [" + webSocketSession.getId() + "]: code: [" + closeCode
                + "(" + closeCode.getCode() + ")], reason:[" + reasonPhrase + "]");
        try {
            webSocketSession.close(new CloseReason(closeCode, reasonPhrase));
        }
        catch (IOException e) {
            log.warn("Got Exception when trying to close WebSocket SessionId [" + webSocketSession.getId()
                    + "], ignoring.", e);
        }
    }

    /**
     * Registrations of MatsSocketEndpoint - these are the definitions of which targets a MatsSocket client can send
     * messages to, i.e. SEND or REQUEST.
     *
     * @param <I>
     *            incoming MatsSocket DTO
     * @param <MI>
     *            incoming Mats DTO
     * @param <MR>
     *            reply Mats DTO
     * @param <R>
     *            reply MatsSocket DTO
     */
    static class MatsSocketEndpointRegistration<I, MI, MR, R> implements MatsSocketEndpoint<I, MI, MR, R> {
        private final String _matsSocketEndpointId;
        private final Class<I> _msIncomingClass;
        private final Class<MI> _matsIncomingClass;
        private final Class<MR> _matsReplyClass;
        private final Class<R> _msReplyClass;
        private final IncomingAuthorizationAndAdapter<I, MI, R> _incomingAuthEval;
        private final ReplyAdapter<MR, R> _replyAdapter;

        private final DebugStackTrace _registrationPoint;

        public MatsSocketEndpointRegistration(String matsSocketEndpointId, Class<I> msIncomingClass,
                Class<MI> matsIncomingClass, Class<MR> matsReplyClass, Class<R> msReplyClass,
                IncomingAuthorizationAndAdapter<I, MI, R> incomingAuthEval, ReplyAdapter<MR, R> replyAdapter) {
            _matsSocketEndpointId = matsSocketEndpointId;
            _msIncomingClass = msIncomingClass;
            _matsIncomingClass = matsIncomingClass;
            _matsReplyClass = matsReplyClass;
            _msReplyClass = msReplyClass;
            _incomingAuthEval = incomingAuthEval;
            _replyAdapter = replyAdapter;

            _registrationPoint = new DebugStackTrace("registration of MatsSocketEndpoint [" + matsSocketEndpointId
                    + "]");
        }

        String getMatsSocketEndpointId() {
            return _matsSocketEndpointId;
        }

        public Class<I> getMatsSocketIncomingClass() {
            return _msIncomingClass;
        }

        public Class<R> getMatsSocketReplyClass() {
            return _msReplyClass;
        }

        IncomingAuthorizationAndAdapter<I, MI, R> getIncomingAuthEval() {
            return _incomingAuthEval;
        }
    }

    private static class MatsSocketEndpointReplyContextImpl<MR, R> implements MatsSocketEndpointReplyContext<MR, R> {
        private final String _matsSocketEndpointId;
        private final DetachedProcessContext _detachedProcessContext;

        public MatsSocketEndpointReplyContextImpl(String matsSocketEndpointId,
                DetachedProcessContext detachedProcessContext) {
            _matsSocketEndpointId = matsSocketEndpointId;
            _detachedProcessContext = detachedProcessContext;
        }

        private R _matsSocketReplyMessage;
        private Processed _handled = Processed.IGNORED;

        @Override
        public String getMatsSocketEndpointId() {
            return _matsSocketEndpointId;
        }

        @Override
        public DetachedProcessContext getMatsContext() {
            return _detachedProcessContext;
        }

        @Override
        public void resolve(R matsSocketResolveMessage) {
            if (_handled != Processed.IGNORED) {
                throw new IllegalStateException("Already handled.");
            }
            _matsSocketReplyMessage = matsSocketResolveMessage;
            _handled = Processed.SETTLED_RESOLVE;
        }

        @Override
        public void reject(R matsSocketRejectMessage) {
            if (_handled != Processed.IGNORED) {
                throw new IllegalStateException("Already handled.");
            }
            _matsSocketReplyMessage = matsSocketRejectMessage;
            _handled = Processed.SETTLED_REJECT;
        }
    }

    protected static ObjectMapper jacksonMapper() {
        // NOTE: This is stolen directly from MatsSerializer_DefaultJson.
        ObjectMapper mapper = new ObjectMapper();

        // Read and write any access modifier fields (e.g. private)
        mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

        // Drop nulls
        mapper.setSerializationInclusion(Include.NON_NULL);

        // If props are in JSON that aren't in Java DTO, do not fail.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Write e.g. Dates as "1975-03-11" instead of timestamp, and instead of array-of-ints [1975, 3, 11].
        // Uses ISO8601 with milliseconds and timezone (if present).
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Handle Optional, OptionalLong, OptionalDouble
        mapper.registerModule(new Jdk8Module());

        return mapper;
    }

    private static class NodeControlStateDto {
        static String CLOSE_SESSION = "Close session";
        static String CLOSE_WEBSOCKET = "Close websocket";
        String type;

        public NodeControlStateDto() {
            /* for Jackson */
        }

        private NodeControlStateDto(String type) {
            this.type = type;
        }
    }

    private static class NodeControl_CloseSessionDto {
        String sessionId;

        public NodeControl_CloseSessionDto() {
            /* for Jackson */
        }

        public NodeControl_CloseSessionDto(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private static class NodeControl_CloseWebSocketDto {
        String sessionId;
        String connectionId;

        public NodeControl_CloseWebSocketDto() {
            /* for Jackson */
        }

        public NodeControl_CloseWebSocketDto(String sessionId, String connectionId) {
            this.sessionId = sessionId;
            this.connectionId = connectionId;
        }
    }

    private void mats_nodeControl(ProcessContext<Void> processContext,
            NodeControlStateDto state, MatsObject incomingMsg) {
        if (NodeControlStateDto.CLOSE_SESSION.equals(state.type)) {
            NodeControl_CloseSessionDto closeSessionDto = incomingMsg.toClass(NodeControl_CloseSessionDto.class);
            node_closeSession(closeSessionDto.sessionId);
        }
        if (NodeControlStateDto.CLOSE_WEBSOCKET.equals(state.type)) {
            NodeControl_CloseWebSocketDto closeWebSocketDto = incomingMsg.toClass(NodeControl_CloseWebSocketDto.class);
            node_closeWebSocket(closeWebSocketDto.sessionId, closeWebSocketDto.connectionId);
        }
    }

    private void node_closeSession(String matsSocketSessionId) {
        // Find local session
        Optional<MatsSocketSessionAndMessageHandler> localMsmh = getRegisteredLocalMatsSocketSession(
                matsSocketSessionId);
        // Close the session if we have it.
        localMsmh.ifPresent(msmh -> msmh.closeSessionAndWebSocket(MatsSocketCloseCodes.CLOSE_SESSION,
                "'Out-of-band'/Server side Close Session"));

        // :: Close it from the CSAF
        try {
            _clusterStoreAndForward.closeSession(matsSocketSessionId);
        }
        catch (DataAccessException e) {
            // TODO: Fix.
            throw new AssertionError("Damn.");
        }
    }

    private void node_closeWebSocket(String matsSocketSessionId, String connectionId) {
        // Find local session
        Optional<MatsSocketSessionAndMessageHandler> localMatsSocketSession = getRegisteredLocalMatsSocketSession(
                matsSocketSessionId);
        // ?: Do we have this session, and is it the right ConnectionId
        if (localMatsSocketSession.isPresent()
                && connectionId.equals(localMatsSocketSession.get().getConnectionId())) {
            // -> Yes, so close it.
            localMatsSocketSession.get().deregisterSessionAndCloseWebSocket(MatsSocketCloseCodes.DISCONNECT,
                    "Cannot have two MatsSockets with the same MatsSocketSessionId - closing the previous"
                            + " (from remote node)");
        }
    }

    static class ReplyHandleStateDto {
        private final String sid;
        private final String cmid;
        private final String ms_eid;

        private final Integer resd; // Resolved (Requested & Allowed) DebugOptions - CAN BE NULL

        private final Long cmrts; // Client Message Received Timestamp (Server timestamp)
        private final String cmrnn; // Received Nodeanme

        private final Long mmsts; // Mats Message Sent Timestamp (when the message was sent onto Mats MQ fabric, Server
                                  // timestamp)

        private ReplyHandleStateDto() {
            /* no-args constructor for Jackson */
            sid = null;
            cmid = null;
            ms_eid = null;

            resd = null;

            cmrts = null;
            cmrnn = null;

            mmsts = null;
        }

        ReplyHandleStateDto(String matsSocketSessionId, String matsSocketEndpointId,
                String clientMessageId, Integer resolvedDebugFlags,
                Long clientMessageReceivedTimestamp, String clientMessageReceivedNodeName,
                Long matsMessageSentTimestamp) {
            sid = matsSocketSessionId;
            cmid = clientMessageId;
            ms_eid = matsSocketEndpointId;

            resd = resolvedDebugFlags;

            cmrts = clientMessageReceivedTimestamp;
            cmrnn = clientMessageReceivedNodeName; // Also where MatsMessage is Sent.

            mmsts = matsMessageSentTimestamp;
        }
    }

    /**
     * A "random" number picked by basically hammering on the keyboard and then carefully manually randomize it some
     * more ;-) - used to string-replace the sending timestamp into the envelope on the WebSocket-forward side. If this
     * by sheer randomness appears in the actual payload message (as ASCII literals), then it might be accidentally
     * swapped out. If this happens, sorry - but you should <i>definitely</i> also sell your house and put all proceeds
     * into all of your country's Lotto systems.
     */
    static final long REPLACE_VALUE_TIMESTAMP = 3_945_608_518_157_027_723L;
    static final Pattern REPLACE_VALUE_TIMESTAMP_REGEX = Pattern.compile(
            Long.toString(REPLACE_VALUE_TIMESTAMP), Pattern.LITERAL);

    /**
     * A "random" String made manually by hammering a little on the keyboard, and trying to find a very implausible
     * string. This will be string-replaced by the sending hostname on the WebSocket-forward side. Must not include
     * characters that will be JSON-encoded, as the replace will be literal.
     */
    static final String REPLACE_VALUE_REPLY_NODENAME = "X&~est,O}@w.h£X";
    static final Pattern REPLACE_VALUE_REPLY_NODENAME_REGEX = Pattern.compile(
            REPLACE_VALUE_REPLY_NODENAME, Pattern.LITERAL);

    private void mats_replyHandler(ProcessContext<Void> processContext,
            ReplyHandleStateDto state, MatsObject incomingMsg) {
        long matsMessageReplyReceivedTimestamp = System.currentTimeMillis();

        // Find the MatsSocketEndpoint for this reply
        Optional<MatsSocketEndpointRegistration<?, ?, ?, ?>> regO = getMatsSocketEndpointRegistration(state.ms_eid);
        if (!regO.isPresent()) {
            throw new AssertionError("The MatsSocketEndpoint has disappeared since this message was initiated."
                    + " This can literally only happen if the server has been restarted with new code in between the"
                    + " request and its reply");
        }
        MatsSocketEndpointRegistration registration = regO.get();

        Object matsReply = incomingMsg.toClass(registration._matsReplyClass);

        MatsSocketEnvelopeDto msReplyEnvelope = new MatsSocketEnvelopeDto();
        Object msReply;
        if (registration._replyAdapter != null) {
            MatsSocketEndpointReplyContextImpl replyContext = new MatsSocketEndpointReplyContextImpl(
                    registration._matsSocketEndpointId, processContext);
            try {
                registration._replyAdapter.adaptReply(replyContext, matsReply);

                switch (replyContext._handled) {
                    case IGNORED:
                        // -> The user did not invoke neither .resolve() nor .reject().
                        msReplyEnvelope.t = REJECT;
                        msReply = null;
                        log.info("adaptReply(..) evidently ignored the Mats message. Responding [REJECT].");
                        break;
                    case SETTLED_RESOLVE:
                    case SETTLED_REJECT:
                        // -> The user settled with .resolve() or .reject()
                        msReplyEnvelope.t = replyContext._handled == Processed.SETTLED_RESOLVE
                                ? RESOLVE
                                : REJECT;
                        msReply = replyContext._matsSocketReplyMessage;
                        log.info("adaptReply(..) settled the reply with [" + msReplyEnvelope.t + "]");
                        break;
                    default:
                        throw new AssertionError("Unhandled enum value [" + replyContext._handled + "]");
                }
            }
            catch (RuntimeException rte) {
                log.warn("adaptReply(..)  raised [" + rte.getClass().getSimpleName() + "], settling with REJECT", rte);
                msReply = null;
                // TODO: If debug enabled for authenticated user, set description to full stacktrace.
                msReplyEnvelope.t = REJECT;
            }
        }
        else if (registration._matsReplyClass == registration._msReplyClass) {
            // -> Return same class
            msReply = matsReply;
            log.info("No ReplyAdapter, so replying with RESOLVE.");
            msReplyEnvelope.t = RESOLVE;
        }
        else {
            throw new AssertionError("No adapter present, but the class from Mats ["
                    + registration._matsReplyClass.getName() + "] != the expected reply from MatsSocketEndpoint ["
                    + registration._msReplyClass.getName() + "].");
        }

        String serverMessageId = serverMessageId();

        // Create Envelope
        msReplyEnvelope.smid = serverMessageId;
        msReplyEnvelope.cmid = state.cmid;
        msReplyEnvelope.tid = processContext.getTraceId(); // TODO: Chop off last ":xyz", as that is added serverside.

        EnumSet<DebugOption> debugOptions = DebugOption.enumSetOf(state.resd);

        if (!debugOptions.isEmpty()) {
            // Create DebugDto - must do this "eagerly", as we do not know what the client actually wants.
            DebugDto debug = new DebugDto();
            // As long as there is ANY DebugOptions, we store the resolved debug options in the DebugDto
            debug.resd = state.resd;
            // :: Timestamps
            if (debugOptions.contains(DebugOption.TIMINGS)) {
                debug.cmrts = state.cmrts;
                debug.mmsts = state.mmsts;
                debug.mmrrts = matsMessageReplyReceivedTimestamp;
            }
            // :: Node names
            if (debugOptions.contains(DebugOption.NODES)) {
                debug.cmrnn = state.cmrnn; // The receiving nodename
                debug.mmrrnn = getMyNodename(); // The Mats-receiving nodename (this processing)
            }
            // TODO: Move these
            debug.mscts = REPLACE_VALUE_TIMESTAMP; // Reply Message to Client Timestamp (not yet determined)
            debug.mscnn = REPLACE_VALUE_REPLY_NODENAME; // The replying nodename (not yet determined)

            msReplyEnvelope.debug = debug;
        }

        // Serialize and store the envelope for forward ("StoreAndForward")
        String serializedEnvelope = serializeEnvelope(msReplyEnvelope);
        // Serialize the actual message
        String serializedMessage = serializeMessageObject(msReply);

        Optional<CurrentNode> nodeNameHoldingWebSocket;
        try {
            nodeNameHoldingWebSocket = _clusterStoreAndForward.storeMessageInOutbox(
                    state.sid, serverMessageId, msReplyEnvelope.cmid, processContext.getTraceId(), msReplyEnvelope.t,
                    serializedEnvelope, serializedMessage, null);
        }
        catch (DataAccessException e) {
            // TODO: Fix
            throw new AssertionError("Damn", e);
        }

        // ?: Check if WE have the session locally
        Optional<MatsSocketSessionAndMessageHandler> localMatsSocketSession = getRegisteredLocalMatsSocketSession(state.sid);
        if (localMatsSocketSession.isPresent()) {
            // -> Yes, evidently we have it! Do local forward.
            _messageToWebSocketForwarder.newMessagesInCsafNotify(localMatsSocketSession.get());
            return;
        }
        // E-> We did not have it locally - do remote ping.

        // ?: If we do have a nodename, ping it about new message
        nodeNameHoldingWebSocket.ifPresent(
                // -> Yes we got a nodename, ping it.
                currentNode -> processContext.initiate(
                        init -> init
                                .traceId("PingWebSocketHolder")
                                .to(terminatorId_NotifyNewMessage_ForNode(currentNode.getNodename()))
                                .publish(state.sid)));
    }

    private void mats_notifyNewMessage(ProcessContext<Void> processContext,
            Void state, String matsSocketSessionId) {
        Optional<MatsSocketSessionAndMessageHandler> localMatsSocketSession = getRegisteredLocalMatsSocketSession(
                matsSocketSessionId);
        // ?: If this Session does not exist at this node, we cannot deliver.
        if (!localMatsSocketSession.isPresent()) {
            // -> Someone must have found that we had it, but this must have asynchronously have been deregistered.
            // Do forward of notification - it will handle if we're the node being registered.
            notifyHomeNodeIfAnyAboutNewMessage(matsSocketSessionId);
        }
        else {
            // ----- We have determined that MatsSocketSession has home here
            _messageToWebSocketForwarder.newMessagesInCsafNotify(localMatsSocketSession.get());
        }
    }

    void notifyHomeNodeIfAnyAboutNewMessage(String matsSocketSessionId) {
        Optional<CurrentNode> currentNode;
        try {
            // Find if the session resides on a different node
            currentNode = _clusterStoreAndForward.getCurrentRegisteredNodeForSession(matsSocketSessionId);

            // ?: Did we get a node?
            if (!currentNode.isPresent()) {
                // -> No, so nothing to do - MatsSocket will get messages when he reconnect.
                log.info("MatsSocketSession [" + matsSocketSessionId + "] is not present on any node. Ignoring,"
                        + " hoping that client will come back and get his messages later.");
                return;
            }

            // ?: Was the node /this/ node?
            if (currentNode.get().getNodename().equalsIgnoreCase(getMyNodename())) {
                // -> Oops, yes.
                // Find the local session.
                Optional<MatsSocketSessionAndMessageHandler> localSession = getRegisteredLocalMatsSocketSession(
                        matsSocketSessionId);
                // ?: Do we have this session locally?!
                if (!localSession.isPresent()) {
                    // -> No, we do NOT have this session locally!
                    // NOTICE: This could e.g. happen if DB down when trying to deregister the MatsSocketSession.
                    log.info("MatsSocketSession [" + matsSocketSessionId + "] is said to live on this node, but we do"
                            + " not have it. Tell the CSAF this, and ignore, hoping that client will come back and get"
                            + " his messages later.");
                    // Fix this wrongness: Tell CSAF that we do not have this session!
                    _clusterStoreAndForward.deregisterSessionFromThisNode(matsSocketSessionId, currentNode.get()
                            .getConnectionId());
                    // No can do.
                    return;
                }
            }
        }
        catch (DataAccessException e) {
            log.warn("Got '" + e.getClass().getSimpleName() + "' when trying to find node home for"
                    + " MatsSocketSession [" + matsSocketSessionId + "] using '"
                    + _clusterStoreAndForward.getClass().getSimpleName() + "'. Bailing out, hoping for"
                    + " self-healer process to figure it out.", e);
            return;
        }

        // Send message to home for MatsSocketSession (it /might/ be us if massive async, but that'll be handled.)
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId("MatsSystemOutgoingMessageNotify[ms_sid:" + matsSocketSessionId + "]" + rnd(5))
                    .from("MatsSocketSystem.notifyNodeAboutMessage")
                    .to(terminatorId_NotifyNewMessage_ForNode(currentNode.get().getNodename()))
                    .publish(matsSocketSessionId);
        });
    }

    String serializeEnvelope(MatsSocketEnvelopeDto msReplyEnvelope) {
        if (msReplyEnvelope.t == null) {
            throw new IllegalStateException("Type ('t') cannot be null.");
        }
        try {
            return _envelopeObjectWriter.writeValueAsString(msReplyEnvelope);
        }
        catch (JsonProcessingException e) {
            throw new AssertionError("Huh, couldn't serialize envelope?!");
        }
    }

    String serializeMessageObject(Object message) {
        try {
            return _jackson.writeValueAsString(message);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize message of type [" + message.getClass().getName()
                    + "].");
        }
    }

    /**
     * A-Z, a-z, 0-9, which is 62 chars.
     */
    public static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * All chars from 20-7f, except:
     * <ul>
     * <li>Space (32, 0x20) - since spaces are always annoying to use in Ids</li>
     * <li>" (34, 0x22) - since this is the start and end symbol of a String</li>
     * <li>\ (92 0x5c) - since this is the escape char</li>
     * <li>DEL (127, 0x7f) - since this is a control char</li>
     * </ul>
     * This is 92 chars.
     */
    public static final String ALPHABET_JSON_ID;
    static {
        StringBuilder buf = new StringBuilder();
        // Just to make the point explicit..:
        for (int c = 0x20; c <= 0x7f; c++) {
            if (c == 0x20 || c == 0x22 || c == 0x5c || c == 0x7f) {
                continue;
            }
            buf.append((char) c);
        }
        ALPHABET_JSON_ID = buf.toString();
    }

    /**
     * The alphabet is A-Z, a-z, 0-9, which is 62 chars.
     *
     * @param length
     *            the desired length of the returned random string.
     * @return a random string of the specified length.
     */
    static String rnd(int length) {
        StringBuilder buf = new StringBuilder(length);
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++)
            buf.append(ALPHABET.charAt(tlr.nextInt(ALPHABET.length())));
        return buf.toString();
    }

    /**
     * All visible and JSON non-quoted ASCII chars, i.e. from 0x20-0x7f, except 0x20, 0x22, 0x5c and 0x7f, which is 92
     * chars.
     *
     * @param length
     *            the desired length of the returned random string.
     * @return a random string of the specified length.
     */
    static String rndJsonId(int length) {
        StringBuilder buf = new StringBuilder(length);
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++)
            buf.append(ALPHABET_JSON_ID.charAt(tlr.nextInt(ALPHABET_JSON_ID.length())));
        return buf.toString();
    }

    /**
     * Returns a string of 4 chars from a 92-char alphabet, consisting of all the visible and JSON-non-quoted ASCII
     * chars. 92^4 = 71.639.296. Notice that if we end up duplicating on the server side, this can be caught. The ONLY
     * time span where this MUST be "globally unique", is after ACK, when we delete the id on server side (and another
     * message conceivably can get the Id again) and then send ACK2, before the client receives the ACK2 and deletes the
     * Id from its inbox. If a message from the server managed to pick the same Id in <i>that particular timespan</i>,
     * the client would refuse it as a double delivery.
     *
     * @return a 4-char invocation of {@link #rndJsonId(int)}.
     */
    static String serverMessageId() {
        return rndJsonId(4);
    }

    static String id(Object x) {
        return x.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(x));
    }

    static String escape(String string) {
        // TODO: Implement HTML escaping (No messages from us should not go through JSONifying already).
        return string;
    }
}
