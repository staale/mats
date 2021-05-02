package com.stolsvik.mats.impl.jms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import com.stolsvik.mats.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;
import com.stolsvik.mats.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import com.stolsvik.mats.impl.jms.JmsMatsException.JmsMatsOverflowRuntimeException;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.MatsSerializer.SerializedMatsTrace;
import com.stolsvik.mats.serial.MatsTrace;
import com.stolsvik.mats.serial.MatsTrace.Call.CallType;
import com.stolsvik.mats.serial.MatsTrace.Call.Channel;
import com.stolsvik.mats.serial.MatsTrace.Call.MessagingModel;
import com.stolsvik.mats.serial.MatsTrace.StackState;

/**
 * Holds the entire contents of a "Mats Message" - so that it can be sent later.
 *
 * @author Endre Stølsvik 2018-09-30 - http://stolsvik.com/, endre@stolsvik.com
 */
public class JmsMatsMessage<Z> implements MatsEditableOutgoingMessage, MatsSentOutgoingMessage {
    private final DispatchType _dispatchType;

    private final MatsSerializer<Z> _matsSerializer;

    private final MatsTrace<Z> _matsTrace;

    private final Object _outgoingMessage;
    private final Object _initialTargetState;
    private final Object _replyToState;

    private final Map<String, byte[]> _bytes;
    private final Map<String, String> _strings;

    private final long _nanosTakenProduceOutgoingMessage;

    /**
     * NOTE: The Maps are copied/cloned out, so invoker can do whatever he feels like with them afterwards.
     */
    public static <Z> JmsMatsMessage<Z> produceMessage(DispatchType dispatchType,
            long nanosAtStart_ProducingOutgoingMessage,
            MatsSerializer<Z> matsSerializer,
            MatsTrace<Z> outgoingMatsTrace,
            Object outgoingMessage, Object initialTargetState, Object replyToState,
            HashMap<String, Object> props,
            HashMap<String, byte[]> bytes,
            HashMap<String, String> strings) {
        // :: Add the MatsTrace properties
        for (Entry<String, Object> entry : props.entrySet()) {
            outgoingMatsTrace.setTraceProperty(entry.getKey(), matsSerializer.serializeObject(entry.getValue()));
        }

        // :: Clone the bytes and strings Maps
        @SuppressWarnings("unchecked")
        HashMap<String, byte[]> bytesCopied = (HashMap<String, byte[]>) bytes.clone();
        @SuppressWarnings("unchecked")
        HashMap<String, String> stringsCopied = (HashMap<String, String>) strings.clone();

        // ?: Do we have a "stack overflow" situation - i.e. the stack height of the outgoing message is too high?
        if (outgoingMatsTrace.getCurrentCall().getReplyStackHeight() > JmsMatsStatics.MAX_STACK_HEIGHT) {
            throw new JmsMatsOverflowRuntimeException("\"Stack Overflow\": Outgoing message with"
                    + " MatsMessageId [" + outgoingMatsTrace.getCurrentCall().getMatsMessageId() + "]"
                    + " and TraceId [" + outgoingMatsTrace.getTraceId() + "] had a stack height higher"
                    + " than " + JmsMatsStatics.MAX_STACK_HEIGHT + ", so stopping this flow now by refusing the"
                    + " _incoming_ message.");
        }

        // ?: Do we have a "call overflow" situation - i.e. too high totalCallNumber and not REPLY?
        if (outgoingMatsTrace.getTotalCallNumber() > JmsMatsStatics.MAX_TOTAL_CALL_NUMBER
                && (outgoingMatsTrace.getCurrentCall().getCallType() != CallType.REPLY)) {
            throw new JmsMatsOverflowRuntimeException("\"Call Overflow\": Outgoing message with"
                    + " MatsMessageId [" + outgoingMatsTrace.getCurrentCall().getMatsMessageId() + "]"
                    + " and TraceId [" + outgoingMatsTrace.getTraceId() + "] had a total call number higher"
                    + " than " + JmsMatsStatics.MAX_TOTAL_CALL_NUMBER + ", and it's not a REPLY, so stopping this"
                    + " flow now by refusing the _incoming_ message");
        }

        // Produce and return the JmsMatsMessage
        return new JmsMatsMessage<>(dispatchType, matsSerializer, outgoingMatsTrace,
                outgoingMessage, initialTargetState, replyToState,
                bytesCopied, stringsCopied, nanosAtStart_ProducingOutgoingMessage);
    }

    public JmsMatsMessage(DispatchType dispatchType, MatsSerializer<Z> matsSerializer, MatsTrace<Z> matsTrace,
            Object outgoingMessage, Object initialTargetState, Object replyToState,
            Map<String, byte[]> bytes, Map<String, String> strings, long nanosAtStart_ProducingOutgoingMessage) {
        _dispatchType = dispatchType;
        _matsSerializer = matsSerializer;
        _matsTrace = matsTrace;
        _outgoingMessage = outgoingMessage;
        _initialTargetState = initialTargetState;
        _replyToState = replyToState;
        _bytes = bytes;
        _strings = strings;
        _nanosTakenProduceOutgoingMessage = System.nanoTime() - nanosAtStart_ProducingOutgoingMessage;
    }

    public String getWhat() {
        return getDispatchType() + ":" + getMessageType();
    }

    public MatsTrace<Z> getMatsTrace() {
        return _matsTrace;
    }

    private SerializedMatsTrace _serialized;

    void serializeAndCacheMatsTrace() {
        _serialized = _matsSerializer.serializeMatsTrace(getMatsTrace());
    }

    void removeCachedSerializedMatsTrace() {
        _serialized = null;
    }

    SerializedMatsTrace getCachedSerializedMatsTrace() {
        if (_serialized == null) {
            throw new IllegalStateException("This " + this.getClass().getSimpleName()
                    + " does not have serialized trace.");
        }
        return _serialized;
    }

    public Map<String, byte[]> getBytes() {
        return _bytes;
    }

    public Map<String, String> getStrings() {
        return _strings;
    }

    @Override
    public String getTraceId() {
        return _matsTrace.getTraceId();
    }

    @Override
    public String getFlowId() {
        return _matsTrace.getFlowId();
    }

    @Override
    public boolean isNonPersistent() {
        return _matsTrace.isNonPersistent();
    }

    @Override
    public long getTimeToLive() {
        return _matsTrace.getTimeToLive();
    }

    @Override
    public boolean isInteractive() {
        return _matsTrace.isInteractive();
    }

    @Override
    public boolean isNoAudit() {
        return _matsTrace.isNoAudit();
    }

    @Override
    public String getInitiatingAppName() {
        return _matsTrace.getInitializingAppName();
    }

    @Override
    public String getInitiatingAppVersion() {
        return _matsTrace.getInitializingAppName();
    }

    @Override
    public String getInitiatorId() {
        return _matsTrace.getInitiatorId();
    }

    @Override
    public String getMatsMessageId() {
        return _matsTrace.getCurrentCall().getMatsMessageId();
    }

    @Override
    public MessageType getMessageType() {
        CallType callType = _matsTrace.getCurrentCall().getCallType();
        if (callType == CallType.REQUEST) {
            return MessageType.REQUEST;
        }
        else if (callType == CallType.REPLY) {
            return MessageType.REPLY;
        }
        else if (callType == CallType.NEXT) {
            return MessageType.NEXT;
        }
        else if (callType == CallType.SEND) {
            // -> SEND, so must evaluate SEND or PUBLISH
            if (_matsTrace.getCurrentCall().getTo().getMessagingModel() == MessagingModel.QUEUE) {
                return MessageType.SEND;
            }
            else {
                return MessageType.PUBLISH;
            }
        }
        throw new AssertionError("Unknown CallType of matsTrace.currentCall: " + callType);
    }

    @Override
    public DispatchType getDispatchType() {
        return _dispatchType;
    }

    @Override
    public Set<String> getTracePropertyKeys() {
        return _matsTrace.getTracePropertyKeys();
    }

    @Override
    public <T> T getTraceProperty(String propertyName, Class<T> type) {
        Z serializedTraceProperty = _matsTrace.getTraceProperty(propertyName);
        return _matsSerializer.deserializeObject(serializedTraceProperty, type);
    }

    @Override
    public Set<String> getBytesKeys() {
        return _bytes.keySet();
    }

    @Override
    public byte[] getBytes(String key) {
        return _bytes.get(key);
    }

    @Override
    public Set<String> getStringKeys() {
        return _strings.keySet();
    }

    @Override
    public String getString(String key) {
        return _strings.get(key);
    }

    @Override
    public String getFrom() {
        return _matsTrace.getCurrentCall().getFrom();
    }

    @Override
    public String getTo() {
        return _matsTrace.getCurrentCall().getTo().getId();
    }

    @Override
    public boolean isToSubscription() {
        return _matsTrace.getCurrentCall().getTo().getMessagingModel() == MessagingModel.TOPIC;
    }

    @Override
    public Optional<String> getReplyTo() {
        return getReplyToChannel().map(Channel::getId);
    }

    @Override
    public Optional<Boolean> isReplyToSubscription() {
        return getReplyToChannel().map(c -> c.getMessagingModel() == MessagingModel.TOPIC);
    }

    private Optional<Channel> getReplyToChannel() {
        List<Channel> stack = _matsTrace.getCurrentCall().getReplyStack();
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(stack.get(stack.size() - 1));
    }

    @Override
    public Optional<Object> getReplyToState() {
        return Optional.ofNullable(_replyToState);
    }

    @Override
    public Object getMessage() {
        return _outgoingMessage;
    }

    @Override
    public Optional<Object> getInitialTargetState() {
        return Optional.ofNullable(_initialTargetState);
    }

    // :: MatsEditableOutgoingMessage

    @Override
    public void setTraceProperty(String propertyName, Object object) {
        Z serializeObject = _matsSerializer.serializeObject(object);
        _matsTrace.setTraceProperty(propertyName, serializeObject);
    }

    @Override
    public void setExtraStateForReplyOrNext(String key, Object object) {
        if (getMessageType() == MessageType.REQUEST) {
            // :: Get StackState for the REPLY to this REQUEST.
            /*
             * Note: Check the implementation for MatsTraceStringImpl.addRequestCall(..). The StackState we need is
             * /either/ the very last added, /or/ it is the next to last. The reason for this, is how the contract is:
             * You may add two distinct states: The state for the REPLY is mandatory (may be null, though, but a
             * StackState is added nevertheless). This is added to the "state flow" first. The "initial incoming state"
             * is optional - this is the state which the called service "starts with". This is added to the "state flow"
             * after the first. This is usually null, and thus not added, meaning that an invoked multi-stage service
             * starts out with a "blank state".
             *
             * Therefore, the state we need to modify is either the very last (no initial incoming state), or the
             * next-to-last (if initial incoming state was added).
             */
            // :: First, find the stack height for the REPLY stack frame
            // This stack height is for the REQUEST frame (+1)..
            int currentStackHeight = _matsTrace.getCurrentCall().getReplyStackHeight();
            // .. we need the StackState for the stack frame below.
            int stackHeightForReply = currentStackHeight - 1;

            // :: Get the StackState to modify
            // Fetch the StateFlow (really need the StateStack, but for our use, there is no difference, and the state
            // flow is the pure representation, while the StateStack might need processing to produce.)
            List<StackState<Z>> stateFlow = _matsTrace.getStateFlow();
            // So, either very last, or the next-to-last
            StackState<Z> stateToModify = stateFlow.get(stateFlow.size() - 1).getHeight() == stackHeightForReply
                    ? stateFlow.get(stateFlow.size() - 1)
                    : stateFlow.get(stateFlow.size() - 2);

            // :: Add the extra-state
            stateToModify.setExtraState(key, _matsSerializer.serializeObject(object));
        }
        else if (getMessageType() == MessageType.NEXT) {
            // :: Get StackState for the NEXT call
            /*
             * Note: Check the implementation for MatsTraceStringImpl.addNextCall(..). The StackState we need is
             * the very last added.
             */
            // :: Get the StackState to modify
            // Fetch the StateFlow
            List<StackState<Z>> stateFlow = _matsTrace.getStateFlow();
            // Get the very last.
            StackState<Z> stateToModify = stateFlow.get(stateFlow.size() - 1);

            // :: Add the extra-state
            stateToModify.setExtraState(key, _matsSerializer.serializeObject(object));
        }
        else {
            throw new IllegalStateException("setExtraStateForReply(..) is only applicable for MessageType.REQUEST"
                    + " messages, this is [" + getMessageType() + "].");
        }
    }

    @Override
    public void addBytes(String key, byte[] payload) {
        _bytes.put(key, payload);
    }

    @Override
    public void addString(String key, String payload) {
        _strings.put(key, payload);
    }

    // :: MatsSentOutgoingMessage

    private String _systemMessageId;
    private long _envelopeSerializationNanos;
    private int _envelopeRawSize;
    private long _envelopeCompressionNanos;
    private int _envelopeCompressedSize;
    private long _messageSystemMessageCreationAndSendNanos;

    void setSentProperties(
            String systemMessageId,
            long envelopeSerializationNanos,
            int envelopeRawSize,
            long envelopeCompressionNanos,
            int envelopeCompressedSize,
            long messageSystemMessageCreationAndSendNanos) {
        _systemMessageId = systemMessageId;
        _envelopeSerializationNanos = envelopeSerializationNanos;
        _envelopeRawSize = envelopeRawSize;
        _envelopeCompressionNanos = envelopeCompressionNanos;
        _envelopeCompressedSize = envelopeCompressedSize;
        _messageSystemMessageCreationAndSendNanos = messageSystemMessageCreationAndSendNanos;
    }

    @Override
    public String getSystemMessageId() {
        return _systemMessageId;
    }

    @Override
    public long getEnvelopeProduceNanos() {
        return _nanosTakenProduceOutgoingMessage;
    }

    @Override
    public long getEnvelopeSerializationNanos() {
        return _envelopeSerializationNanos;
    }

    @Override
    public int getEnvelopeSerializedSize() {
        return _envelopeRawSize;
    }

    @Override
    public long getEnvelopeCompressionNanos() {
        return _envelopeCompressionNanos;
    }

    @Override
    public int getEnvelopeWireSize() {
        return _envelopeCompressedSize;
    }

    @Override
    public long getMessageSystemProductionAndSendNanos() {
        return _messageSystemMessageCreationAndSendNanos;
    }

    @Override
    public int hashCode() {
        // Hash the MatsMessageId.
        return _matsTrace.getCurrentCall().getMatsMessageId().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        // handles null.
        if (!(obj instanceof JmsMatsMessage)) {
            return false;
        }
        @SuppressWarnings("rawtypes")
        JmsMatsMessage other = (JmsMatsMessage) obj;
        // Compare the MatsMessageId.
        return this.getMatsTrace().getCurrentCall().getMatsMessageId()
                .equals(other.getMatsTrace().getCurrentCall().getMatsMessageId());

    }
}
