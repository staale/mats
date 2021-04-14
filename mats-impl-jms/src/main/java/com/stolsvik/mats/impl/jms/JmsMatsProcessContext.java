package com.stolsvik.mats.impl.jms;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;
import com.stolsvik.mats.MatsInitiator.MatsInitiate;
import com.stolsvik.mats.MatsInitiator.MessageReference;
import com.stolsvik.mats.MatsStage;
import com.stolsvik.mats.impl.jms.JmsMatsInitiator.MessageReferenceImpl;
import com.stolsvik.mats.api.intercept.MatsOutgoingMessage.DispatchType;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.MatsTrace;
import com.stolsvik.mats.serial.MatsTrace.Call;
import com.stolsvik.mats.serial.MatsTrace.Call.Channel;
import com.stolsvik.mats.serial.MatsTrace.Call.MessagingModel;
import com.stolsvik.mats.serial.MatsTrace.KeepMatsTrace;

/**
 * The JMS MATS implementation of {@link ProcessContext}. Instantiated for each incoming JMS message that is processed,
 * given to the {@link MatsStage}'s process lambda.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsProcessContext<R, S, Z> implements ProcessContext<R>, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsProcessContext.class);

    private final JmsMatsFactory<Z> _parentFactory;

    private final String _endpointId;
    private final String _stageId;
    private final String _systemMessageId;
    private final String _nextStageId;

    private final byte[] _incomingSerializedMatsTrace;
    private final int _mtSerOffset;
    private final int _mtSerLength; // The reason for having this separate, is when unstashing: Length != entire thing.
    private final String _incomingSerializedMatsTraceMeta;
    private final MatsTrace<Z> _incomingMatsTrace;
    private final LinkedHashMap<String, byte[]> _incomingBinaries;
    private final LinkedHashMap<String, String> _incomingStrings;
    private final S _incomingAndOutgoingState;
    private final Supplier<MatsInitiate> _initiateSupplier;
    private final List<JmsMatsMessage<Z>> _messagesToSend;
    private final JmsMatsInternalExecutionContext _jmsMatsInternalExecutionContext;
    private final DoAfterCommitRunnableHolder _doAfterCommitRunnableHolder;

    // Outgoing:

    // Hack to be able to later enforce the legal call flows
    private RuntimeException _requestOrNextSent;
    private RuntimeException _replySent;

    private final LinkedHashMap<String, Object> _outgoingProps = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> _outgoingBinaries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> _outgoingStrings = new LinkedHashMap<>();

    JmsMatsProcessContext(JmsMatsFactory<Z> parentFactory,
            String endpointId,
            String stageId,
            String systemMessageId,
            String nextStageId,
            byte[] incomingSerializedMatsTrace, int mtSerOffset, int mtSerLength,
            String incomingSerializedMatsTraceMeta,
            MatsTrace<Z> incomingMatsTrace, S incomingAndOutgoingState,
            Supplier<MatsInitiate> initiateSupplier,
            LinkedHashMap<String, byte[]> incomingBinaries, LinkedHashMap<String, String> incomingStrings,
            List<JmsMatsMessage<Z>> out_messagesToSend,
            JmsMatsInternalExecutionContext jmsMatsInternalExecutionContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder) {
        _parentFactory = parentFactory;

        _endpointId = endpointId;
        _stageId = stageId;
        _systemMessageId = systemMessageId;
        _nextStageId = nextStageId;

        _incomingSerializedMatsTrace = incomingSerializedMatsTrace;
        _mtSerOffset = mtSerOffset;
        _mtSerLength = mtSerLength;
        _incomingSerializedMatsTraceMeta = incomingSerializedMatsTraceMeta;
        _incomingMatsTrace = incomingMatsTrace;
        _incomingBinaries = incomingBinaries;
        _incomingStrings = incomingStrings;
        _incomingAndOutgoingState = incomingAndOutgoingState;
        _initiateSupplier = initiateSupplier;
        _messagesToSend = out_messagesToSend;
        _jmsMatsInternalExecutionContext = jmsMatsInternalExecutionContext;
        _doAfterCommitRunnableHolder = doAfterCommitRunnableHolder;
    }

    /**
     * Holds any Runnable set by {@link #doAfterCommit(Runnable)}.
     */
    static class DoAfterCommitRunnableHolder {
        private Runnable _doAfterCommit;

        void setDoAfterCommit(Runnable runnable) {
            _doAfterCommit = runnable;
        }

        public void runDoAfterCommitIfAny() {
            if (_doAfterCommit != null) {
                _doAfterCommit.run();
            }
        }
    }

    @Override
    public String getStageId() {
        return _stageId;
    }

    @Override
    public String getFromAppName() {
        return _incomingMatsTrace.getCurrentCall().getCallingAppName();
    }

    @Override
    public String getFromAppVersion() {
        return _incomingMatsTrace.getCurrentCall().getCallingAppVersion();
    }

    @Override
    public String getFromStageId() {
        return _incomingMatsTrace.getCurrentCall().getFrom();
    }

    @Override
    public String getInitiatingAppName() {
        return _incomingMatsTrace.getInitializingAppName();
    }

    @Override
    public String getInitiatingAppVersion() {
        return _incomingMatsTrace.getInitializingAppVersion();
    }

    @Override
    public String getInitiatorId() {
        return _incomingMatsTrace.getInitiatorId();
    }

    @Override
    public String getMatsMessageId() {
        return _incomingMatsTrace.getCurrentCall().getMatsMessageId();
    }

    @Override
    public String getSystemMessageId() {
        return _systemMessageId;
    }

    @Override
    public boolean isNonPersistent() {
        return _incomingMatsTrace.isNonPersistent();
    }

    @Override
    public boolean isInteractive() {
        return _incomingMatsTrace.isInteractive();
    }

    @Override
    public boolean isNoAudit() {
        return _incomingMatsTrace.isNoAudit();
    }

    @Override
    public String toString() {
        return _incomingMatsTrace.toString();
    }

    @Override
    public String getTraceId() {
        return _incomingMatsTrace.getTraceId();
    }

    @Override
    public String getEndpointId() {
        return _endpointId;
    }

    @Override
    public byte[] getBytes(String key) {
        return _incomingBinaries.get(key);
    }

    @Override
    public String getString(String key) {
        return _incomingStrings.get(key);
    }

    @Override
    public void addBytes(String key, byte[] payload) {
        _outgoingBinaries.put(key, payload);
    }

    @Override
    public void addString(String key, String payload) {
        _outgoingStrings.put(key, payload);
    }

    @Override
    public void setTraceProperty(String propertyName, Object propertyValue) {
        _outgoingProps.put(propertyName, propertyValue);
    }

    static final byte[] NO_NEXT_STAGE = "-".getBytes(StandardCharsets.UTF_8);

    @Override
    public byte[] stash() {
        long nanosStart = System.nanoTime();

        // Serialize the endpointId
        byte[] b_endpointId = _endpointId.getBytes(StandardCharsets.UTF_8);
        // .. stageId
        byte[] b_stageId = _stageId.getBytes(StandardCharsets.UTF_8);
        // .. nextStageId, handling that it might be null.
        byte[] b_nextStageId = _nextStageId == null ? NO_NEXT_STAGE : _nextStageId.getBytes(StandardCharsets.UTF_8);
        // .. serialized MatsTrace's meta info:
        byte[] b_meta = _incomingSerializedMatsTraceMeta.getBytes(StandardCharsets.UTF_8);
        // .. messageId
        byte[] b_systemMessageId = _systemMessageId.getBytes(StandardCharsets.UTF_8);

        // :: Create the byte array in one go

        // NOTICE: We use 0-delimiting, UTF-8 does not have zeros: https://stackoverflow.com/a/6907327/39334

        // Total length:
        // = 8 for the 2 x FourCC's "MATSjmts"
        int fullStashLength = 8
                // + 1 for the version, '1'
                + 1
                // + 1 for the number of zeros, currently 6.
                + 1
                // + 1 for the 0-delimiter // NOTICE: Can add more future data between n.o.Zeros and this
                // zero-delimiter.
                // + b_endpointId.length
                + 1 + b_endpointId.length
                // + 1 for the 0-delimiter
                // + b_stageId.length
                + 1 + b_stageId.length
                // + 1 for the 0-delimiter
                // + b_nextStageId.length
                + 1 + b_nextStageId.length
                // + 1 for the 0-delimiter
                // + b_meta.length
                + 1 + b_meta.length
                // + 1 for the 0-delimiter
                // + b_systemMessageId.length
                + 1 + b_systemMessageId.length
                // + 1 for the 0-delimiter
                // + length of incoming serialized MatsTrace, _mtSerLength
                + 1 + _mtSerLength;
        byte[] b_fullStash = new byte[fullStashLength];

        // :: Fill the byte array with the stash

        // "MATSjmts":
        // * "MATS" as FourCC/"Magic Number", per spec.
        // * "jmts" for "Jms MatsTrace Serializer": This is the JMS impl of Mats, which employs MatsTraceSerializer.
        b_fullStash[0] = 77; // M
        b_fullStash[1] = 65; // A
        b_fullStash[2] = 84; // T
        b_fullStash[3] = 83; // S
        b_fullStash[4] = 106; // j
        b_fullStash[5] = 109; // m
        b_fullStash[6] = 116; // t
        b_fullStash[7] = 115; // s
        b_fullStash[8] = 1; // Version
        b_fullStash[9] = 6; // Number of zeros - to be able to add stuff later, and have older deserializers handle it.
        // -- NOTICE! There are room to add more stuff here before first 0-byte.

        // ZERO 1: All bytes in new initialized array is 0 already
        // EndpointId:
        int startPos_EndpointId = /* 4CC */ 8 + /* Version */ 1 + /* n.o.Zeros */ 1 + /* 0-delimiter */ 1;
        System.arraycopy(b_endpointId, 0, b_fullStash, startPos_EndpointId, b_endpointId.length);
        // ZERO 2: All bytes in new initialized array is 0 already
        // StageId start pos:
        int /* next field start */ startPos_StageId = /* last field start */ startPos_EndpointId
                + /* last field length */ b_endpointId.length
                + /* 0-delimiter */ 1;
        System.arraycopy(b_stageId, 0, b_fullStash, startPos_StageId, b_stageId.length);
        // ZERO 3: All bytes in new initialized array is 0 already
        // NextStageId start pos:
        int startPos_NextStageId = startPos_StageId + b_stageId.length + 1;
        System.arraycopy(b_nextStageId, 0, b_fullStash, startPos_NextStageId, b_nextStageId.length);
        // ZERO 4: All bytes in new initialized array is 0 already
        // MatsTrace Meta start pos:
        int startPos_Meta = startPos_NextStageId + b_nextStageId.length + 1;
        System.arraycopy(b_meta, 0, b_fullStash, startPos_Meta, b_meta.length);
        // ZERO 5: All bytes in new initialized array is 0 already
        // MessageId start pos:
        int startPos_MessageId = startPos_Meta + b_meta.length + 1;
        System.arraycopy(b_systemMessageId, 0, b_fullStash, startPos_MessageId, b_systemMessageId.length);
        // ZERO 6: All bytes in new initialized array is 0 already
        // Actual Serialized MatsTrace start pos:
        int startPos_MatsTrace = startPos_MessageId + b_systemMessageId.length + 1;
        System.arraycopy(_incomingSerializedMatsTrace, _mtSerOffset,
                b_fullStash, startPos_MatsTrace, _mtSerLength);

        double millisSerializing = (System.nanoTime() - nanosStart) / 1_000_000d;

        log.info(LOG_PREFIX + "Stashed Mats flow, stash:[" + b_fullStash.length + " B], serializing took:["
                + millisSerializing + " ms].");

        return b_fullStash;
    }

    @Override
    public <T> T getTraceProperty(String propertyName, Class<T> clazz) {
        Z value = _incomingMatsTrace.getTraceProperty(propertyName);
        if (value == null) {
            return null;
        }
        return _parentFactory.getMatsSerializer().deserializeObject(value, clazz);
    }

    private static final String REPLY_TO_VOID = "REPLY_TO_VOID_NO_MESSAGE_SENT";

    private static final String ILLEGAL_CALL_FLOWS = "ILLEGAL CALL FLOWS! ";

    @Override
    public MessageReference reply(Object replyDto) {
        long nanosStart = System.nanoTime();

        /*
         * Sending reply more than once is NOT LEGAL, but has never been enforced. Therefore, for now currently just log
         * hard, and then at a later time throw IllegalStateException or some such. -2020-01-09.
         */
        // TODO: Reimplement to throw once all are > v0.16.0
        // ?: Have reply already been invoked?
        if (_replySent != null) {
            // -> Yes, and this is not legal. But it has not been enforced before, so currently just log.error
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + "Reply has already been invoked! This is not legal,"
                    + " and will throw exception in a later version!");
            log.error(LOG_PREFIX + "   PREVIOUS REPLY DEBUG STACKTRACE:", _replySent);
            log.error(LOG_PREFIX + "   THIS REPLY DEBUG STACKTRACE:", new RuntimeException("THIS REPLY STACKTRACE"));
        }

        if (_requestOrNextSent != null) {
            // -> Yes, and this is not legal. But it has not been enforced before, so currently just log.error
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + "Request or Next has already been invoked! It is not legal to"
                    + " mix Reply with Request or Next, and will throw exception in a later version!");
            log.error(LOG_PREFIX + "   PREVIOUS REQUEST/NEXT DEBUG STACKTRACE:", _requestOrNextSent);
            log.error(LOG_PREFIX + "   THIS REPLY DEBUG STACKTRACE:", new RuntimeException("THIS REPLY STACKTRACE"));
        }

        _replySent = new RuntimeException("PREVIOUS REPLY STACKTRACE");

        // :: Short-circuit the reply (to no-op) if there is nothing on the stack to reply to.
        List<Channel> stack = _incomingMatsTrace.getCurrentCall().getReplyStack();
        if (stack.size() == 0) {
            // This is OK, it is just like a normal java call where you do not use the return value, e.g. map.put(k, v).
            // It happens if you use "send" (aka "fire-and-forget") to an endpoint which has reply-semantics, which
            // is legal.
            log.info(LOG_PREFIX + "Stage [" + _stageId + "] invoked context.reply(..), but there are no elements"
                    + " on the stack, hence no one to reply to, ignoring.");
            return new MessageReferenceImpl(REPLY_TO_VOID);
        }

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z replyZ = matsSerializer.serializeObject(replyDto);
        MatsTrace<Z> replyMatsTrace = _incomingMatsTrace.addReplyCall(_stageId, replyZ);

        String matsMessageId = produceMessage(replyDto, nanosStart, replyMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    @Override
    public MessageReference request(String endpointId, Object requestDto) {
        long nanosStart = System.nanoTime();
        // :: Assert that we have a next-stage
        if (_nextStageId == null) {
            throw new IllegalStateException("Stage [" + _stageId
                    + "] invoked context.request(..), but there is no next stage to reply to."
                    + " Use context.initiate(..send()..) if you want to 'invoke' the endpoint w/o req/rep semantics.");
        }

        /*
         * Sending request/next in addition to reply is NOT LEGAL, but has never been enforced. Therefore, for now
         * currently just log hard, and then at a later time throw IllegalStateException or some such. -2020-01-24.
         */
        // TODO: Reimplement to throw once all are > v0.16.0
        // ?: Have reply already been invoked?
        if (_replySent != null) {
            // -> Yes, and this is not legal. But it has not been enforced before, so currently just log.error
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + "Reply has been invoked! It is not legal to mix Reply with"
                    + " Request or Next, and will throw exception in a later version!");
            log.error(LOG_PREFIX + "   PREVIOUS REPLY DEBUG STACKTRACE:", _replySent);
            log.error(LOG_PREFIX + "   THIS REQUEST DEBUG STACKTRACE:",
                    new RuntimeException("THIS REQUEST STACKTRACE"));
        }
        // NOTE! IT IS LEGAL TO SEND MULTIPLE REQUEST/NEXT MESSAGES!
        _requestOrNextSent = new RuntimeException("PREVIOUS REQUEST STACKTRACE");

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z requestZ = matsSerializer.serializeObject(requestDto);
        Z stateZ = matsSerializer.serializeObject(_incomingAndOutgoingState);
        MatsTrace<Z> requestMatsTrace = _incomingMatsTrace.addRequestCall(_stageId,
                endpointId, MessagingModel.QUEUE, _nextStageId, MessagingModel.QUEUE, requestZ, stateZ, null);

        String matsMessageId = produceMessage(requestDto, nanosStart, requestMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    @Override
    public MessageReference next(Object incomingDto) {
        long nanosStart = System.nanoTime();
        // :: Assert that we have a next-stage
        if (_nextStageId == null) {
            throw new IllegalStateException("Stage [" + _stageId
                    + "] invoked context.next(..), but there is no next stage.");
        }

        /*
         * Sending request/next in addition to reply is NOT LEGAL, but has never been enforced. Therefore, for now
         * currently just log hard, and then at a later time throw IllegalStateException or some such. -2020-01-24.
         */
        // TODO: Reimplement to throw once all are > v0.16.0
        // ?: Have reply already been invoked?
        if (_replySent != null) {
            // -> Yes, and this is not legal. But it has not been enforced before, so currently just log.error
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + "Reply has been invoked! It is not legal to mix Reply with"
                    + " Request or Next, and will throw exception in a later version!");
            log.error(LOG_PREFIX + "   PREVIOUS REPLY DEBUG STACKTRACE:", _replySent);
            log.error(LOG_PREFIX + "   THIS NEXT DEBUG STACKTRACE:",
                    new RuntimeException("THIS NEXT STACKTRACE"));
        }
        // NOTE! IT IS LEGAL TO SEND MULTIPLE REQUEST/NEXT MESSAGES!
        _requestOrNextSent = new RuntimeException("PREVIOUS NEXT STACKTRACE");

        // :: Create next (heh!) MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z nextZ = matsSerializer.serializeObject(incomingDto);
        Z stateZ = matsSerializer.serializeObject(_incomingAndOutgoingState);
        MatsTrace<Z> nextMatsTrace = _incomingMatsTrace.addNextCall(_stageId, _nextStageId, nextZ, stateZ);

        String matsMessageId = produceMessage(incomingDto, nanosStart, nextMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    private String produceMessage(Object incomingDto, long nanosStart, MatsTrace<Z> outgoingMatsTrace) {
        long now = System.currentTimeMillis();
        Call<Z> currentCall = outgoingMatsTrace.getCurrentCall();
        String matsMessageId = createMatsMessageId(outgoingMatsTrace.getFlowId(),
                outgoingMatsTrace.getInitializedTimestamp(), now, outgoingMatsTrace.getCallNumber());

        String debugInfo = outgoingMatsTrace.getKeepTrace() != KeepMatsTrace.MINIMAL
                ? getInvocationPoint()
                : null;
        currentCall.setDebugInfo(_parentFactory.getFactoryConfig().getAppName(),
                _parentFactory.getFactoryConfig().getAppVersion(),
                _parentFactory.getFactoryConfig().getNodename(), now, matsMessageId,
                debugInfo);

        // Produce the JmsMatsMessage to send
        JmsMatsMessage<Z> next = JmsMatsMessage.produceMessage(DispatchType.STAGE, nanosStart,
                _parentFactory.getMatsSerializer(), outgoingMatsTrace,
                incomingDto, null, null,
                _outgoingProps, _outgoingBinaries, _outgoingStrings);
        _messagesToSend.add(next);

        // Clear all outgoingProps, outgoingBinaries and outgoingStrings, for any new request(..) or send(..)
        // (Clearing, but copied off by the produceMessage(..) call)
        _outgoingProps.clear();
        _outgoingBinaries.clear();
        _outgoingStrings.clear();
        return matsMessageId;
    }


    @Override
    public void initiate(InitiateLambda lambda) {
        // Store the existing TraceId, since it should hopefully be set (extended) in the initiate.
        String existingTraceId = MDC.get(MDC_TRACE_ID);
        // Do the actual initiation
        lambda.initiate(_initiateSupplier.get());
        // Put back the previous TraceId.
        MDC.put(MDC_TRACE_ID, existingTraceId);
    }

    @Override
    public void doAfterCommit(Runnable runnable) {
        _doAfterCommitRunnableHolder.setDoAfterCommit(runnable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getAttribute(Class<T> type, String... name) {
        // TODO: Way to stick in MatsFactory-configured attributes. Notice: both in ProcessContext and Initiate.
        // ?: Is this a query for SQL Connection, without any names?
        if ((type == Connection.class) && (name.length == 0)) {
            // -> Yes, then it is the default transactional SQL Connection.
            return (Optional<T>) _jmsMatsInternalExecutionContext.getSqlConnection();
        }
        return Optional.empty();
    }
}
