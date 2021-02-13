package com.stolsvik.mats.api.intercept;

import java.util.Optional;
import java.util.Set;

import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.api.intercept.MatsInitiateInterceptor.InitiateCompletedContext;
import com.stolsvik.mats.api.intercept.MatsStageInterceptor.StageCompletedContext;

/**
 * Represents an Outgoing Mats Message.
 *
 * @author Endre Stølsvik - 2021-01-08 - http://endre.stolsvik.com
 */
public interface MatsOutgoingMessage {

    // ===== Flow Ids and properties

    String getTraceId();

    String getFlowId();

    boolean isNonPersistent();

    long getTimeToLive();

    boolean isInteractive();

    boolean isNoAudit();

    // ===== Message Ids and properties

    String getMatsMessageId();

    MessageType getMessageType();

    DispatchType getDispatchType();

    // ===== "Sideloads" and trace props

    Set<String> getTracePropertyKeys();

    <T> T getTraceProperty(String propertyName, Class<T> type);

    Set<String> getBytesKeys();

    byte[] getBytes(String key);

    Set<String> getStringKeys();

    String getString(String key);

    // ===== Basics

    String getFrom();

    String getTo();

    boolean isToSubscription();

    Optional<String> getReplyTo();

    Optional<Boolean> isReplyToSubscription();

    Optional<Object> getReplyToState();

    Object getMessage();

    /**
     * @return for initiations, it is possible, albeit should be uncommon, to send along an initial <i>incoming</i>
     *         target state - this returns that.
     */
    Optional<Object> getInitialTargetState();

    enum MessageType {
        /**
         * For {@link DispatchType#INIT} or {@link DispatchType#STAGE_INIT}.
         */
        SEND,

        /**
         * For {@link DispatchType#INIT} or {@link DispatchType#STAGE_INIT}.
         */
        PUBLISH,

        /**
         * For {@link DispatchType#INIT} or {@link DispatchType#STAGE_INIT}; and for {@link DispatchType#STAGE}.
         */
        REQUEST,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        REPLY,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        NEXT,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        GOTO
    }

    enum DispatchType {
        INIT, STAGE, STAGE_INIT
    }

    interface MatsEditableOutgoingMessage extends MatsOutgoingMessage {
        void setTraceProperty(String propertyName, Object object);

        void addBytes(String key, byte[] payload);

        void addString(String key, String payload);
    }

    interface MatsSentOutgoingMessage extends MatsOutgoingMessage {
        // ===== MessageIds

        String getSystemMessageId();

        // ===== Serialization stats

        /**
         * @return time taken (in nanoseconds) to create the Mats envelope, including serialization of all relevant
         *         constituents: DTO, STO and any Trace Properties. <b>Note that this will be a part of the user lambda
         *         timing (for {@link StageCompletedContext#getUserLambdaNanos() stage}, or for
         *         {@link InitiateCompletedContext#getUserLambdaNanos() init}), as it is done at the time of e.g.
         *         invoking {@link ProcessContext#request(String, Object) processContext.request()}, which happens
         *         within the user lambda.</b>
         */
        long getEnvelopeProduceNanos();

        /**
         * @return time taken (in nanoseconds) to serialize the Mats envelope.
         */
        long getEnvelopeSerializationNanos();

        /**
         * @return size (in bytes) of the serialized envelope - before compression. Do read the JavaDoc of
         *         {@link #getEnvelopeWireSize()} too, as the same applies here: This size only refers to the Mats
         *         envelope, not the messaging system's final message size.
         */
        int getEnvelopeSerializedSize();

        /**
         * @return time taken (in nanoseconds) to compress the envelope - will be <code>0</code> if no compression was
         *         applied, while it will return > 0 if compression was applied.
         */
        long getEnvelopeCompressionNanos();

        /**
         * @return size (in bytes) of the envelope after compression, which will be put inside the messaging system's
         *         message envelope. If compression was not applied, returns the same value as
         *         {@link #getEnvelopeSerializedSize()}. Note that the returned size is only the (compressed) Mats
         *         envelope, and does not include the size of the messaging system's message/envelope and any meta-data
         *         that Mats adds to this. This means that the message size on the wire will be larger.
         */
        int getEnvelopeWireSize();

        /**
         * @return time taken (in nanoseconds) to produce, and then send (transfer) the message to the message broker.
         */
        long getMessageSystemConstructAndSendNanos();
    }
}
