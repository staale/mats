package com.stolsvik.mats.impl.jms;

import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;
import com.stolsvik.mats.MatsStage;
import com.stolsvik.mats.impl.jms.JmsMatsInitiator.JmsMatsInitiate;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.MatsTrace;
import com.stolsvik.mats.serial.MatsTrace.Call;
import com.stolsvik.mats.serial.MatsTrace.Call.Channel;
import com.stolsvik.mats.serial.MatsTrace.Call.MessagingModel;

/**
 * The JMS MATS implementation of {@link ProcessContext}. Instantiated for each incoming JMS message that is processed,
 * given to the {@link MatsStage}'s process lambda.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsProcessContext<S, R, Z> implements ProcessContext<R>, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsProcessContext.class);

    private final JmsMatsStage<R, ?, ?, Z> _matsStage;
    private final MatsTrace<Z> _matsTrace;
    private final LinkedHashMap<String, byte[]> _incomingBinaries;
    private final LinkedHashMap<String, String> _incomingStrings;
    private final S _sto;
    private final List<JmsMatsMessage<Z>> _messagesToSend;

    JmsMatsProcessContext(JmsMatsStage<R, ?, ?, Z> matsStage, LinkedHashMap<String, byte[]> incomingBinaries,
            LinkedHashMap<String, String> incomingStrings, List<JmsMatsMessage<Z>> messagesToSend,
            MatsTrace<Z> matsTrace, S sto) {
        _matsStage = matsStage;
        _matsTrace = matsTrace;
        _incomingBinaries = incomingBinaries;
        _incomingStrings = incomingStrings;
        _sto = sto;
        _messagesToSend = messagesToSend;
    }

    private final LinkedHashMap<String, Object> _outgoingProps = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> _outgoingBinaries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> _outgoingStrings = new LinkedHashMap<>();

    @Override
    public String getStageId() {
        return _matsStage.getStageId();
    }

    @Override
    public String getFromStageId() {
        return _matsTrace.getCurrentCall().getFrom();
    }

    @Override
    public String toString() {
        return _matsTrace.toString();
    }

    @Override
    public String getTraceId() {
        return _matsTrace.getTraceId();
    }

    @Override
    public String getEndpointId() {
        return _matsStage.getParentEndpoint().getEndpointId();
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

    @Override
    public <T> T getTraceProperty(String propertyName, Class<T> clazz) {
        MatsSerializer<Z> matsSerializer = _matsStage
                .getParentEndpoint().getParentFactory().getMatsSerializer();
        Z value = _matsTrace.getTraceProperty(propertyName);
        if (value == null) {
            return null;
        }
        return matsSerializer.deserializeObject(value, clazz);
    }

    @Override
    public void request(String endpointId, Object requestDto) {
        long nanosStart = System.nanoTime();
        JmsMatsFactory<Z> parentFactory = _matsStage.getParentEndpoint().getParentFactory();

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = parentFactory.getMatsSerializer();
        MatsTrace<Z> requestMatsTrace = _matsTrace.addRequestCall(_matsStage.getStageId(),
                endpointId, MessagingModel.QUEUE,
                _matsStage.getNextStageId(), MessagingModel.QUEUE,
                matsSerializer.serializeObject(requestDto),
                matsSerializer.serializeObject(_sto), null);

        // TODO: Add debug info!
        requestMatsTrace.getCurrentCall().setDebugInfo(parentFactory.getFactoryConfig().getAppName(),
                parentFactory.getFactoryConfig().getAppVersion(),
                parentFactory.getFactoryConfig().getNodename(), System.currentTimeMillis(), "Callalala!");

        // Produce the REQUEST JmsMatsMessage to send
        JmsMatsMessage<Z> request = produceJmsMatsMessage(log, nanosStart, _matsStage.getParentEndpoint()
                .getParentFactory(), requestMatsTrace, _outgoingProps, _outgoingBinaries, _outgoingStrings, "REQUEST");
        _messagesToSend.add(request);
    }

    @Override
    public void reply(Object replyDto) {
        long nanosStart = System.nanoTime();
        // :: Short-circuit the reply (to no-op) if there is nothing on the stack to reply to.
        List<Channel> stack = _matsTrace.getCurrentCall().getStack();
        if (stack.size() == 0) {
            // This is OK, it is just like a normal java call where you do not use return value, e.g. map.put(k, v).
            log.info("Stage [" + _matsStage.getStageId() + " invoked context.reply(..), but there are no elements"
                    + " on the stack, hence no one to reply to, ignoring.");
            return;
        }

        JmsMatsFactory<Z> parentFactory = _matsStage.getParentEndpoint().getParentFactory();

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = parentFactory.getMatsSerializer();
        MatsTrace<Z> replyMatsTrace = _matsTrace.addReplyCall(_matsStage.getStageId(),
                matsSerializer.serializeObject(replyDto));

        // TODO: Add debug info!
        Call<Z> currentCall = replyMatsTrace.getCurrentCall();
        currentCall.setDebugInfo(parentFactory.getFactoryConfig().getAppName(),
                parentFactory.getFactoryConfig().getAppVersion(),
                parentFactory.getFactoryConfig().getNodename(), System.currentTimeMillis(), "Callalala!");

        // Produce the REPLY JmsMatsMessage to send
        JmsMatsMessage<Z> request = produceJmsMatsMessage(log, nanosStart, _matsStage.getParentEndpoint()
                .getParentFactory(), replyMatsTrace, _outgoingProps, _outgoingBinaries, _outgoingStrings, "REPLY");
        _messagesToSend.add(request);
    }

    @Override
    public void next(Object incomingDto) {
        long nanosStart = System.nanoTime();
        // :: Assert that we have a next-stage
        if (_matsStage.getNextStageId() == null) {
            throw new IllegalStateException("Stage [" + _matsStage.getStageId()
                    + "] invoked context.next(..), but there is no next stage.");
        }

        JmsMatsFactory<Z> parentFactory = _matsStage.getParentEndpoint().getParentFactory();

        // :: Create next (heh!) MatsTrace
        MatsSerializer<Z> matsSerializer = _matsStage
                .getParentEndpoint().getParentFactory().getMatsSerializer();
        MatsTrace<Z> nextMatsTrace = _matsTrace.addNextCall(_matsStage.getStageId(), _matsStage.getNextStageId(),
                matsSerializer.serializeObject(incomingDto), matsSerializer.serializeObject(_sto));

        // TODO: Add debug info!
        nextMatsTrace.getCurrentCall().setDebugInfo(parentFactory.getFactoryConfig().getAppName(),
                parentFactory.getFactoryConfig().getAppVersion(),
                parentFactory.getFactoryConfig().getNodename(), System.currentTimeMillis(), "Callalala!");

        // Produce the NEXT JmsMatsMessage to send
        JmsMatsMessage<Z> request = produceJmsMatsMessage(log, nanosStart, _matsStage.getParentEndpoint()
                .getParentFactory(), nextMatsTrace, _outgoingProps, _outgoingBinaries, _outgoingStrings, "NEXT");
        _messagesToSend.add(request);
    }

    @Override
    public void initiate(InitiateLambda lambda) {
        lambda.initiate(new JmsMatsInitiate<>(_matsStage.getParentEndpoint().getParentFactory(),
                _messagesToSend, _matsTrace, _outgoingProps));
    }
}
