package io.mats3;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import io.mats3.MatsConfig.StartStoppable;
import io.mats3.MatsFactory.ContextLocal;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsFactory.MatsWrapper;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.MatsStage.StageConfig;

/**
 * Represents a MATS Endpoint - you create instances from the {@link MatsFactory} (or use the Spring integration).
 * <p />
 * Note: It should be possible to use instances of <code>MatsEndpoint</code> as keys in a <code>HashMap</code>, i.e.
 * their equals and hashCode should remain stable throughout the life of the MatsFactory - and similar instances but
 * with different MatsFactory are <i>not</i> equals. Depending on the implementation, instance equality may be
 * sufficient.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsEndpoint<R, S> extends StartStoppable {

    /**
     * @return the config for this endpoint. If endpoint is not yet started, you may invoke mutators on it.
     */
    EndpointConfig<R, S> getEndpointConfig();

    /**
     * @return the parent {@link MatsFactory}.
     */
    MatsFactory getParentFactory();

    /**
     * Adds a new stage to a multi-stage endpoint. If this is the last stage of a multi-stage endpoint, you must invoke
     * {@link #finishSetup()} afterwards - or you could instead use the {@link #lastStage(Class, ProcessReturnLambda)}
     * variant which does this automatically.
     *
     * @see MatsObject
     * @param <I>
     *            the type of the incoming DTO. The very first stage's incoming DTO is the endpoint's incoming DTO. If
     *            the special type {@link MatsObject}, this stage can take any type.
     * @param processor
     *            the lambda that will be invoked when messages arrive in the corresponding queue.
     */
    <I> MatsStage<R, S, I> stage(Class<I> incomingClass, ProcessLambda<R, S, I> processor);

    /**
     * Variation of {@link #stage(Class, ProcessLambda)} that can be configured "on the fly".
     */
    <I> MatsStage<R, S, I> stage(Class<I> incomingClass, Consumer<? super StageConfig<R, S, I>> stageConfigLambda,
            ProcessLambda<R, S, I> processor);

    /**
     * Adds the last stage to a multi-stage endpoint, which also {@link #finishSetup() finishes setup} of the endpoint.
     * Note that the last-stage concept is just a convenience that lets the developer reply from the endpoint with a
     * <code>return replyDTO</code> statement - you may just as well add a standard stage, and invoke the
     * {@link ProcessContext#reply(Object)} method. Note: If using a normal stage as the last stage, you must remember
     * to invoke {@link #finishSetup()} afterwards, as that is then not done automatically.
     *
     * @param <I>
     *            the type of the incoming DTO. The very first stage's incoming DTO is the endpoint's incoming DTO. If
     *            the special type {@link MatsObject}, this stage can take any type.
     * @param processor
     *            the lambda that will be invoked when messages arrive in the corresponding queue.
     */
    <I> MatsStage<R, S, I> lastStage(Class<I> incomingClass, ProcessReturnLambda<R, S, I> processor);

    /**
     * Variation of {@link #lastStage(Class, ProcessReturnLambda)} that can be configured "on the fly".
     */
    <I> MatsStage<R, S, I> lastStage(Class<I> incomingClass, Consumer<? super StageConfig<R, S, I>> stageConfigLambda,
            ProcessReturnLambda<R, S, I> processor);

    /**
     * @return a List of {@link MatsStage}s, representing all the stages of the endpoint. The order is the same as the
     *         order in which the stages will be invoked. For single-staged endpoints and terminators, this list is of
     *         size 1.
     */
    List<MatsStage<R, S, ?>> getStages();

    /**
     * The lambda that shall be provided by the developer for the process stage(s) for the endpoint - provides the
     * context, state and incoming message DTO.
     */
    @FunctionalInterface
    interface ProcessLambda<R, S, I> {
        void process(ProcessContext<R> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} that makes it possible to do a "return
     * replyDto" at the end of the stage, which is just a convenient way to invoke
     * {@link MatsEndpoint.ProcessContext#reply(Object)}. Used for the last process stage of a multistage endpoint.
     */
    @FunctionalInterface
    interface ProcessReturnLambda<R, S, I> {
        R process(ProcessContext<R> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} which does not have a state, and have the same
     * return-semantics as {@link MatsEndpoint.ProcessReturnLambda ProcessLambda} - used for single-stage endpoints as
     * these does not have multiple stages to transfer state between.
     * <p />
     * However, since it is possible to send state along with the request, one may still use the
     * {@link MatsEndpoint.ProcessReturnLambda ProcessReturnLambda} for single-stage endpoints, but in this case you
     * need to code it up yourself by making a multi-stage and then just adding a single lastStage.
     */
    @FunctionalInterface
    interface ProcessSingleLambda<R, I> {
        R process(ProcessContext<R> ctx, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} which does not have reply specified - used for
     * terminator endpoints. It has state, as the initiator typically have state that it wants the terminator to get.
     */
    @FunctionalInterface
    interface ProcessTerminatorLambda<S, I> {
        void process(ProcessContext<Void> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Should be invoked when all stages has been added. Will automatically be invoked by invocation of
     * {@link #lastStage(Class, ProcessReturnLambda)}, which again implies that it will be invoked when creating
     * {@link MatsFactory#single(String, Class, Class, ProcessSingleLambda) single-stage endpoints} and
     * {@link MatsFactory#terminator(String, Class, Class, ProcessTerminatorLambda) terminators} and
     * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda) subscription
     * terminators}.
     * <p />
     * This sets the state of the endpoint to "finished setup", and will invoke {@link #start()} on the endpoint,
     * <b>unless</b> {@link MatsFactory#holdEndpointsUntilFactoryIsStarted()} has been invoked prior to creating the
     * endpoint.
     * <p />
     * You may implement "delayed start" of an endpoint by <b>not</b> invoking finishedSetup() after setting it up.
     * Taking into account the first chapter of this JavaDoc, note that you must then <b>only</b> use the
     * {@link MatsFactory#staged(String, Class, Class) staged} type of Endpoint setup, as the others implicitly invokes
     * finishSetup(), and also <b>not</b> invoke {@link #lastStage(Class, ProcessReturnLambda) lastStage} on it as this
     * also implicitly invokes finishSetup(). When setting an Endpoint up without calling finishSetup(), even when
     * {@link MatsFactory#start()} is invoked, such a not-finished endpoint will then not be started. You may then later
     * invoke finishSetup(), e.g. when any needed caches are finished populated, and the endpoint will then be finished
     * and started.
     * <p />
     * Another way to implement "delayed start" is to obviously just not create the endpoint until later: MatsFactory
     * has no <i>"that's it, now all endpoints must have been created"</i>-lifecycle stage, and can fire up new
     * endpoints until the JVM is dead.
     */
    void finishSetup();

    /**
     * Starts the endpoint (unless {@link #finishSetup() has NOT been invoked), invoking {@link MatsStage#start()} on
     * any not-yet started stages of the endpoint (which should be all of them at application startup).
     */
    @Override
    void start();

    /**
     * Waits till all stages of the endpoint has entered their receive-loops, i.e. invokes
     * {@link MatsStage#waitForReceiving(int)} on all {@link MatsStage}s of the endpoint.
     * <p />
     * Note: This method makes most sense for
     * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)
     * SubscriptionTerminators}: These are based on MQ Topics, whose semantics are that if you do not listen right when
     * someone says something, you will not hear it. This means that a SubscriptionTerminator will never receive a
     * message that was sent <i>before</i> it had started the receive-loop. Thus, if you in a service-"boot" phase send
     * a message whose result will come in to a SubscriptionTerminator, you will not receive this result if the
     * receive-loop has not started. This is relevant for certain cache setups where you listen for event updates, and
     * when "booting" the cache, you need to be certain that you have started receiving updates before asking for the
     * "initial load" of the cache. It is also relevant for tools like the <code>MatsFuturizer</code>, which uses a
     * node-specific Topic for the final reply message from the requested service; If the SubscriptionTerminator has not
     * yet made it to the receive-loop, any replies will simply be lost and the future never completed.
     * <p />
     * Note: Currently, this only holds for the initial start. If the entity has started the receive-loop at some point,
     * it will always immediately return - even though it is currently stopped.
     *
     * @return whether it was started (i.e. <code>true</code> if successfully started listening for messages).
     */
    @Override
    boolean waitForReceiving(int timeoutMillis);

    /**
     * Stops the endpoint, invoking {@link MatsStage#stop(int)} on all {@link MatsStage}s.
     *
     * @return whether it was successfully stopped (i.e. <code>true</code> if successfully stopped all listening
     *         threads).
     */
    @Override
    boolean stop(int gracefulShutdownMillis);

    /**
     * <b>Should most probably only be used for testing!</b>
     * <p />
     * First invokes {@link #stop(int) stop(gracefulShutdownMillis)}, and if successful <b>removes</b> the endpoint from
     * its MatsFactory. This enables a new endpoint to be registered with the same endpointId as the one removed (which
     * otherwise is not accepted). This might be of interest in testing scenarios, where you want to <i>change</i> the
     * implementation of an endpoint from one test to another. There is currently no known situation where this makes
     * sense to do "in production": Once the system is set up with the correct endpoints, it should most probably stay
     * that way!
     *
     * @return whether the {@link #stop(int gracefulShutdownMillis)} was successful, and thus whether the endpoint was
     *         removed from its MatsFactory.
     */
    boolean remove(int gracefulShutdownMillis);

    /**
     * For the incoming message type, this represents the equivalent of Java's {@link Object} - a "generic" incoming
     * message whose type is not yet determined. When you know, you invoke {@link #toClass(Class)} to get it "casted"
     * (i.e. deserialized) to the specified type.
     */
    interface MatsObject {
        /**
         * Deserializes the incoming message class to the desired type - assuming that it actually is a serialized
         * representation of that class.
         *
         * @param type
         *            the class that the incoming message should be deserialized to.
         * @param <T>
         *            the type of 'type'
         * @return the deserialized object.
         * @throws IllegalArgumentException
         *             if the incoming message could not be deserialized to the desired type.
         */
        <T> T toClass(Class<T> type) throws IllegalArgumentException;
    }

    /**
     * Provides for both configuring the endpoint (before it is started), and introspecting the configuration.
     */
    interface EndpointConfig<R, S> extends MatsConfig {
        /**
         * @return the endpointId if this {@link MatsEndpoint}.
         */
        String getEndpointId();

        /**
         * @return whether this Endpoint is "subscription based", as when created with
         *         {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)}.
         */
        boolean isSubscription();

        /**
         * @return the class that will be sent as reply for this endpoint.
         */
        Class<R> getReplyClass();

        /**
         * @return the class used for the endpoint's state.
         */
        Class<S> getStateClass();

        /**
         * @return the class expected for incoming messages to this endpoint (decided by the first {@link MatsStage}).
         */
        Class<?> getIncomingClass();

        /**
         * @deprecated will be removed, use {@link MatsEndpoint#getStages()}.
         */
        @Deprecated
        List<MatsStage<R, S, ?>> getStages();
    }

    /**
     * The part of {@link ProcessContext} that exposes the "getter" side of the context, which enables it to be exposed
     * outside of the process lambda. It is effectively the "passive" parts of the context, i.e. not initiating new
     * messages, setting properties etc. Look for usage in the "MatsFuturizer" tool in the tools-lib.
     */
    interface DetachedProcessContext {
        /**
         * @return the {@link MatsInitiate#traceId(String) trace id} for the processed message.
         * @see MatsInitiate#traceId(String)
         */
        String getTraceId();

        /**
         * @return the endpointId that is processed, i.e. the id of <i>this</i> endpoint. Should probably never be
         *         necessary, but accessible for introspection.
         */
        String getEndpointId();

        /**
         * @return the stageId that is processed, i.e. the id of <i>this</i> stage. It will be equal to
         *         {@link #getEndpointId()} for the first stage in multi-stage-endpoint, and for the sole stage of a
         *         single-stage and terminator endpoint. Should probably never be necessary, but accessible for
         *         introspection.
         */
        String getStageId();

        /**
         * @return the {@link FactoryConfig#getAppName() AppName} of the MatsFactory from which the currently processing
         *         message came. Thus, if this message is the result of a 'next' call, it will be yourself.
         */
        String getFromAppName();

        /**
         * @return the {@link FactoryConfig#getAppVersion() AppVersion} of the MatsFactory from which the currently
         *         processing message came. Thus, if this message is the result of a 'next' call, it will be yourself.
         */
        String getFromAppVersion();

        /**
         * @return the stageId from which the currently processing message came. Note that the stageId of the initial
         *         stage of an endpoint is equal to the endpointId. If this endpoint is the initial target of the
         *         initiation, this value is equal to {@link #getInitiatorId()}.
         */
        String getFromStageId();

        /**
         * @return the {@link Instant} which this message was created on the sending stage.
         */
        Instant getFromTimestamp();

        /**
         * @return the {@link FactoryConfig#getAppName() AppName} of the MatsFactory that initiated the Flow which the
         *         currently processing is a part of. Thus, if this endpoint is the initial target of the initiation,
         *         this value is equal to {@link #getFromAppName()}.
         */
        String getInitiatingAppName();

        /**
         * @return the {@link FactoryConfig#getAppVersion() AppVersion} of the MatsFactory that initiated the Flow which
         *         the currently processing is a part of. Thus, if this endpoint is the initial target of the
         *         initiation, this value is equal to {@link #getFromAppVersion()}.
         */
        String getInitiatingAppVersion();

        /**
         * @return the "initiatorId" set by the initiation with {@link MatsInitiate#from(String)}.
         */
        String getInitiatorId();

        /**
         * @return the {@link Instant} which this message was initiated, i.e. sent from a MatsInitiator (or within a
         *         Stage).
         */
        Instant getInitiatingTimestamp();

        /**
         * @return the unique messageId for the incoming message from Mats - which can be used to catch
         *         double-deliveries.
         */
        String getMatsMessageId();

        /**
         * @return the unique messageId for the incoming message, from the underlying message system - which could be
         *         used to catch double-deliveries, but do prefer {@link #getMatsMessageId()}. (For a JMS
         *         Implementation, this will be the "JMSMessageID").
         */
        String getSystemMessageId();

        /**
         * This is relevant if stashing or otherwise when a stage is accessing an external system (e.g. another MQ)
         * which have a notion of persistence.
         *
         * @return whether the current Mats flow is non-persistent - read {@link MatsInitiate#nonPersistent()}.
         */
        boolean isNonPersistent();

        /**
         * This is relevant if stashing or otherwise when a stage is accessing an external system (e.g. another MQ)
         * which have a notion of prioritization.
         *
         * @return whether the current Mats flow is interactive (prioritized) - read {@link MatsInitiate#interactive()}.
         */
        boolean isInteractive();

        /**
         * Hint to monitoring/logging/auditing systems that this call flow is not very valuable to fully audit,
         * typically because it is just a "getter" of information for display to a user, or is health check request to
         * see if the endpoint is up and answers in a timely manner.
         *
         * @return whether the current Mats flow is "no audit" - read {@link MatsInitiate#noAudit()}.
         */
        boolean isNoAudit();

        /**
         * @param key
         *            the key for which to retrieve a binary payload from the incoming message.
         * @return the requested byte array.
         *
         * @see ProcessContext#addBytes(String, byte[])
         * @see #getString(String)
         * @see #getTraceProperty(String, Class)
         */
        byte[] getBytes(String key);

        /**
         * @param key
         *            the key for which to retrieve a String payload from the incoming message.
         * @return the requested String.
         *
         * @see ProcessContext#addString(String, String)
         * @see #getBytes(String)
         * @see #getTraceProperty(String, Class)
         */
        String getString(String key);

        /**
         * Retrieves the Mats Trace property with the specified name, deserializing the value to the specified class,
         * using the active MATS serializer. Read more on {@link ProcessContext#setTraceProperty(String, Object)}.
         *
         * @param propertyName
         *            the name of the Mats Trace property to retrieve.
         * @param clazz
         *            the class to which the value should be deserialized.
         * @return the value of the Mats Trace property, deserialized as the specified class.
         * @see ProcessContext#setTraceProperty(String, Object)
         */
        <T> T getTraceProperty(String propertyName, Class<T> clazz);

        /**
         * @return a for-human-consumption, multi-line debug-String representing the current processing context,
         *         typically the "MatsTrace" up to the current stage. The format is utterly arbitrary, can and will
         *         change between versions and revisions, and <b>shall <u>NOT</u> be used programmatically!!</b>
         */
        String toString();
    }

    /**
     * A way for the process stage to communicate with the library, providing methods to invoke a request, send a reply
     * (for multi-stage endpoints, this provides a way to do a "early return"), initiate a new message etc.
     */
    interface ProcessContext<R> extends DetachedProcessContext {
        /**
         * Attaches a binary payload to the next outgoing message, being it a request or a reply. Note that for
         * initiations, you have the same method on the {@link MatsInitiate} instance.
         * <p />
         * The rationale for having this is to not have to encode a largish byte array inside the JSON structure that
         * carries the Request or Reply DTO - byte arrays represent very badly in JSON.
         * <p />
         * Note: The byte array is not compressed (as might happen with the DTO), so if the payload is large, you might
         * want to consider compressing it before attaching it (and will then have to decompress it on the receiving
         * side).
         * <p />
         * Note: This will be added to the subsequent {@link #request(String, Object) request}, {@link #reply(Object)
         * reply} or {@link #next(Object) next} message - and then cleared. Thus, if you perform multiple request or
         * next calls, then each must have their binaries, strings and trace properties set separately. (Any
         * {@link #initiate(InitiateLambda) initiations} are separate from this, neither getting nor consuming binaries,
         * strings nor trace properties set on the <code>ProcessContext</code> - they must be set on the
         * {@link MatsInitiate MatsInitiate} instance within the initiate-lambda).
         *
         * @param key
         *            the key on which to store the byte array payload. The receiver will have to use this key to get
         *            the payload out again, so either it will be a specific key that the sender and receiver agree
         *            upon, or you could generate a random key, and reference this key as a field in the outgoing DTO.
         * @param payload
         *            the payload to store.
         * @see #getBytes(String)
         * @see #addString(String, String)
         * @see #getString(String)
         */
        void addBytes(String key, byte[] payload);

        /**
         * Attaches a String payload to the next outgoing message, being it a request or a reply. Note that for
         * initiations, you have the same method on the {@link MatsInitiate} instance.
         * <p />
         * The rationale for having this is to not have to encode a largish string document inside the JSON structure
         * that carries the Request or Reply DTO.
         * <p />
         * Note: The String payload is not compressed (as might happen with the DTO), so if the payload is large, you
         * might want to consider compressing it before attaching it and instead use the
         * {@link #addBytes(String, byte[]) addBytes(..)} method (and will then have to decompress it on the receiving
         * side).
         * <p />
         * Note: This will be added to the subsequent {@link #request(String, Object) request}, {@link #reply(Object)
         * reply} or {@link #next(Object) next} message - and then cleared. Thus, if you perform multiple request or
         * next calls, then each must have their binaries, strings and trace properties set separately. (Any
         * {@link #initiate(InitiateLambda) initiations} are separate from this, neither getting nor consuming binaries,
         * strings nor trace properties set on the <code>ProcessContext</code> - they must be set on the
         * {@link MatsInitiate MatsInitiate} instance within the initiate-lambda).
         *
         * @param key
         *            the key on which to store the String payload. The receiver will have to use this key to get the
         *            payload out again, so either it will be a specific key that the sender and receiver agree upon, or
         *            you could generate a random key, and reference this key as a field in the outgoing DTO.
         * @param payload
         *            the payload to store.
         * @see #getString(String)
         * @see #addBytes(String, byte[])
         * @see #getBytes(String)
         */
        void addString(String key, String payload);

        /**
         * Adds a property that will "stick" with the Mats Trace from this call on out. Note that for initiations, you
         * have the same method on the {@link MatsInitiate} instance. The functionality effectively acts like a
         * {@link ThreadLocal} when compared to normal java method invocations: If the Initiator adds it, all subsequent
         * stages will see it, on any stack level, including the terminator. If a stage in a service nested some levels
         * down in the stack adds it, it will be present in all subsequent stages including all the way up to the
         * Terminator. Note that any initiations within a Stage will also inherit trace properties present on the
         * Stage's incoming message.
         * <p />
         * Possible use cases: You can for example "sneak along" some property meant for Service X through an invocation
         * of intermediate Service A (which subsequently calls Service X), where the signature (DTO) of the intermediate
         * Service A does not provide such functionality. Another usage would be to add some "global context variable",
         * e.g. "current user", that is available for any down-stream Service that requires it. Both of these scenarios
         * can obviously lead to pretty hard-to-understand code if used extensively: When employed, you should code
         * rather defensively, where if this property is not present when a stage needs it, it should throw
         * {@link MatsRefuseMessageException} and clearly explain that the property needs to be present.
         * <p />
         * Note: This will be added to the subsequent {@link #request(String, Object) request}, {@link #reply(Object)
         * reply} or {@link #next(Object) next} message - and then cleared. Thus, if you perform multiple request or
         * next calls, then each must have their binaries, strings and trace properties set separately. (Any
         * {@link #initiate(InitiateLambda) initiations} are separate from this, neither getting nor consuming binaries,
         * strings nor trace properties set on the <code>ProcessContext</code> - they must be set on the
         * {@link MatsInitiate MatsInitiate} instance within the initiate-lambda).
         * <p />
         * Note: <i>incoming</i> trace properties (that was present on the incoming message) will be added to <i>all</i>
         * outgoing message, <i>including initiations within the stage</i>.
         *
         * @param propertyName
         *            the name of the property
         * @param propertyValue
         *            the value of the property, which will be serialized using the active MATS serializer.
         * @see #getTraceProperty(String, Class)
         * @see #addString(String, String)
         * @see #addBytes(String, byte[])
         */
        void setTraceProperty(String propertyName, Object propertyValue);

        /**
         * Returns a binary representation of the current Mats flow's incoming execution point, which can be
         * {@link MatsInitiate#unstash(byte[], Class, Class, Class, ProcessLambda) unstashed} again at a later time
         * using the {@link MatsInitiator}, thereby providing a simplistic "continuation" feature in Mats. You will have
         * to find storage for these bytes yourself - an obvious place is the co-transactional database that the stage
         * typically has available. This feature gives the ability to "pause" the current Mats flow, and later restore
         * the execution from where it left off, probably with some new information that have been gathered in the
         * meantime. This can typically relieve the Mats Stage Processing thread from having to wait for another
         * service's execution (whose execution must then be handled by some other thread). This could be a longer
         * running process, or a process whose execution time is variable, maybe residing on a Mats-external service
         * structure: E.g. some REST service that sometimes lags, or sometimes is down in smaller periods. Or a service
         * on a different Message Broker. Once this "Mats external" processing has finished, that thread can invoke
         * {@link MatsInitiate#unstash(byte[], Class, Class, Class, ProcessLambda) unstash(stashBytes,...)} to get the
         * Mats flow going again. Notice that functionally, the unstash-operation is a kind of initiation, only that
         * this type of initiation doesn't start a <i>new</i> Mats flow, rather <i>continuing an existing flow</i>.
         * <p />
         * <b>Notice that this feature should not typically be used to "park" a Mats flow for days.</b> One might have a
         * situation where a part of an order flow potentially needs manual handling, e.g. validating a person's
         * identity if this has not been validated before. It might (should!) be tempting to employ the stash function
         * then: Stash the Mats flow in a database. Make a GUI where the ID-validation can be performed by some
         * employee. When the ID is either accepted or denied, you unstash the Mats flow with the result, getting a very
         * nice continuous mats flow for new orders which is identical whether or not ID validation needs to be
         * performed. However, if this ID-validation process can take days or weeks to execute, it will be a poor
         * candidate for the stash-feature. The reason is that embedded within the execution context which you get a
         * binary serialization of, there might be several serialized <i>state</i> representations of the endpoints
         * laying upstream of this call flow. When you "freeze" these by invoking stash, you have immediately made a
         * potential future deserialization-crash if you change the code of those upstream endpoints (which quite
         * probably resides in different code bases than the one employing the stash feature), specifically changes of
         * the state classes they employ: When you deploy these code changes while having multiple flows frozen in
         * stashes, you will have a problem when they are later unstashed and the Mats flow returns to those endpoints
         * whose state classes won't deserialize back anymore. It is worth noting that you always have these problems
         * when doing deploys where the state classes of Mats endpoints change - it is just that usually, there won't be
         * any, and at least not many, such flows in execution at the precise deploy moment (and also, that changing the
         * state classes are in practice really not that frequent). However, by stashing over days, instead of a normal
         * Mats flow that take seconds, you massively increase the time window in which such deserialization problems
         * can occur. You at least have to consider this if employing the stash-functionality.
         * <p />
         * <b>Note about data and metadata which should be stored along with the stash-bytes:</b> You only get a binary
         * serialized incoming execution context in return from this method (which includes the incoming message,
         * incoming state, the execution stack and {@link ProcessContext#getTraceProperty(String, Class) trace
         * properties}, but not "sideloaded" {@link ProcessContext#getBytes(String) bytes} and
         * {@link ProcessContext#getString(String) strings}). The returned byte array are utterly opaque seen from the
         * Mats API side (however, depending on the serialization mechanism employed in the Mats implementation, you
         * might be able to peek into them anyway - but this should at most be used for debugging/monitoring
         * introspection). Therefore, any information from the incoming message, or from your state object, or anything
         * else from the {@link DetachedProcessContext} which is needed to actually execute the job that should be
         * performed outside of the Mats flow, <u>must be picked out manually</u> before exiting the process lambda.
         * This also goes for "sideloaded" objects ({@link ProcessContext#getBytes(String) bytes} and
         * {@link ProcessContext#getString(String) strings}) - which will not be available inside the unstashed process
         * lambda (they are not a part of the stash-bytes). Also, you should for debugging/monitoring purposes also
         * store at least the Mats flow's {@link ProcessContext#getTraceId() TraceId} and a timestamp along with the
         * stash and data. You should probably also have some kind of monitoring / health checks for stashes that have
         * become stale - i.e. stashes that have not been unstashed for a considerable time, and whose Mats flow have
         * thus stopped up, and where the downstream endpoints/stages therefore will not get invoked.
         * <p />
         * <b>Notes:</b>
         * <ul>
         * <li>Invoking {@code stash()} will not affect the stage processing in any way other than producing a
         * serialized representation of the current incoming execution point. You can still send out messages. You could
         * even reply, but then, what would be the point of stashing?</li>
         * <li>Repeated invocations within the same stage will yield (effectively) the same stash, as any processing
         * done inside the stage before invoking {@code stash()} don't affect the <i>incoming</i> execution point.</li>
         * <li>You will have to exit the current process lambda yourself - meaning that this cannot be used in a
         * {@link MatsEndpoint#lastStage(Class, ProcessReturnLambda) lastStage}, as you cannot return from such a stage
         * without actually sending a reply ({@code return null} replies with {@code null}). Instead employ a
         * {@link MatsEndpoint#stage(Class, ProcessLambda) normal stage}, using {@link ProcessContext#reply(Object)} to
         * return a reply if needed.</li>
         * <li>Mats won't care if you unstash() the same stash multiple times, but your downstream parts of the Mats
         * flow might find this a bit strange.</li>
         * </ul>
         *
         * @return a binary representation of the current Mats flow's incoming execution point (i.e. any incoming state
         *         and the incoming message - along with the Mats flow stack at this point). It shall start with the 4
         *         ASCII letters "MATS", and then 4 more letters representing which mechanism is employed to construct
         *         the rest of the byte array.
         */
        byte[] stash();

        /**
         * Sends a request message, meaning that the specified endpoint will be invoked, with the reply-to endpointId
         * set to the next stage in the multi-stage endpoint. This will throw if the current process stage is a
         * terminator, single-stage endpoint or the last endpoint of a multi-stage endpoint, as there then is no next
         * stage to reply to.
         * <p />
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} and/or
         * {@link #next(Object) next} message, OR a single {@link #reply(Object) reply}. The reason that multiple
         * request/next are allowed, is that this could be used in a scatter-gather scenario - where the replies (or
         * next) comes in to the next stage <i>of the same endpoint</i>. However, multiple replies to the
         * <i>invoking</i> endpoint makes very little sense, which is why only one reply is allowed, and it cannot be
         * combined with reply/next, as then the next stage could also perform a reply.
         * <p />
         * Note: The current state is serialized when invoking this method. This means that in case of multiple
         * requests/nexts, you may change the state in between, and the next stage will get different "incoming states",
         * which may be of use in a scatter-gather scenario.
         *
         * @param endpointId
         *            which endpoint to invoke
         * @param requestDto
         *            the message that should be sent to the specified endpoint.
         */
        MessageReference request(String endpointId, Object requestDto);

        /**
         * Sends a reply to the requesting service. This will be ignored if there is no endpointId on the stack, i.e. if
         * this endpoint it is semantically a terminator (the <code>replyTo</code> of an initiation's request), or if it
         * is the last stage of an endpoint that was invoked directly (using {@link MatsInitiate#send(Object)
         * MatsInitiate.send(msg)}).
         * <p />
         * It is possible to do "early return" in a multi-stage endpoint by invoking this method in a stage that is not
         * the last. (You are then not allowed to also perform {@link #request(String, Object)} or
         * {@link #next(Object)}).
         * <p />
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} and/or
         * {@link #next(Object) next} message, OR a single {@link #reply(Object) reply}. The reason that multiple
         * request/next are allowed, is that this could be used in a scatter-gather scenario - where the replies (or
         * next) comes in to the next stage <i>of the same endpoint</i>. However, multiple replies to the
         * <i>invoking</i> endpoint makes very little sense, which is why only one reply is allowed, and it cannot be
         * combined with reply/next, as then the next stage could also perform a reply.
         *
         * @param replyDto
         *            the reply DTO to return to the invoker.
         */
        MessageReference reply(R replyDto);

        /**
         * Invokes the next stage of a multi-stage endpoint directly, instead of going through a request-reply to some
         * service. The rationale for this method is that in certain situation you might not need to invoke some service
         * after all (e.g. in situation X, you do not need the holding information of the customer): Basically, you can
         * do something like <code>if (condition) { request service } else { next }</code>.
         * <p />
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} and/or
         * {@link #next(Object) next} message, OR a single {@link #reply(Object) reply}. The reason that multiple
         * request/next are allowed, is that this could be used in a scatter-gather scenario - where the replies (or
         * next) comes in to the next stage <i>of the same endpoint</i>. However, multiple replies to the
         * <i>invoking</i> endpoint makes very little sense, which is why only one reply is allowed, and it cannot be
         * combined with reply/next, as then the next stage could also perform a reply.
         * <p />
         * Note: The current state is serialized when invoking this method. This means that in case of multiple
         * requests/nexts, you may change the state in between, and the next stage will get different "incoming states",
         * which may be of use in a scatter-gather scenario.
         *
         * @param incomingDto
         *            the object for the next stage's incoming DTO, which must match what the next stage expects. When
         *            using this method to skip a request, it probably often makes sense to set it to <code>null</code>,
         *            which the next stage then must handle correctly.
         */
        MessageReference next(Object incomingDto);

        /**
         * Initiates a new message out to an endpoint. This is effectively the same as invoking
         * {@link MatsInitiator#initiate(InitiateLambda lambda) the same method} on a {@link MatsInitiator} gotten via
         * {@link MatsFactory#getOrCreateInitiator(String)}, only that this way works within the transactional context
         * of the {@link MatsStage} which this method is invoked within. Also, the traceId and from-endpointId is
         * predefined, but it is still recommended to set the traceId, as that will append the new string on the
         * existing traceId, making log tracking (e.g. when debugging) better.
         * <p />
         * <b>IMPORTANT NOTICE!!</b> The {@link MatsInitiator} returned from {@link MatsFactory#getDefaultInitiator()
         * MatsFactory.getDefaultInitiator()} is "magic" in that when employed from within a Mats Stage's context
         * (thread), it works exactly as this method: Any initiations performed participates in the Mats Stage's
         * transactional demarcation. Read more at the JavaDoc of default initiator's
         * {@link MatsFactory#getDefaultInitiator() JavaDoc}..
         *
         * @param lambda
         *            provides the {@link MatsInitiate} instance on which to create the message to be sent.
         */
        void initiate(InitiateLambda lambda);

        /**
         * The Runnable will be performed after messaging and external resources (DB) have been committed. An example
         * can be if the Mats-lambda inserts a row in a database that should be processed by some other component (i.e.
         * a service running with some Threads), and thus wants to wake up that component telling it that new work is
         * available. Problem is then that if this "wakeUp()" call is done within the lambda, the row is not technically
         * there yet - as we're still within the SQL transaction demarcation. Therefore, if the process-service wakes up
         * really fast and tries to find the new work, it will not see anything yet. (It might then presume that e.g.
         * another node of the service-cluster took care of whatever woke it up, and go back to sleep.)
         * <p />
         * Note: This is per processing; Setting it is only relevant for the current message. If you invoke the method
         * more than once, only the last Runnable will be run. If you set it to <code>null</code>, you "cancel" any
         * previously set Runnable.
         * <p />
         * Note: If any Exception is raised from the code after the Runnable has been set, or any Exception is raised by
         * the processing or committing, the Runnable will not be run.
         * <p />
         * Note: If the Runnable throws a {@link RuntimeException}, it will be logged on ERROR level, then ignored.
         *
         * @param runnable
         *            the code to run right after the transaction of both external resources and messaging has been
         *            committed. Setting to <code>null</code> "cancels" any previously set Runnable.
         */
        void doAfterCommit(Runnable runnable);

        /**
         * Provides a way to get hold of (optional) attributes/objects from the Mats implementation, either specific to
         * the Mats implementation in use, or configured into this instance of the Mats implementation. Is mirrored by
         * the same method at {@link MatsInitiate#getAttribute(Class, String...)}. There is also a
         * ThreadLocal-accessible version at {@link ContextLocal#getAttribute(Class, String...)}.
         * <p />
         * Mandatory: If the Mats implementation has a transactional SQL Connection, it shall be available by
         * <code>'context.getAttribute(Connection.class)'</code>.
         *
         * @param type
         *            The expected type of the attribute
         * @param name
         *            The (optional) (hierarchical) name(s) of the attribute.
         * @param <T>
         *            The type of the attribute.
         * @return Optional of the attribute in question, the optionality pointing out that it depends on the Mats
         *         implementation or configuration whether it is available.
         *
         * @see ProcessContext#getAttribute(Class, String...)
         * @see ContextLocal#getAttribute(Class, String...)
         */
        <T> Optional<T> getAttribute(Class<T> type, String... name);

        /**
         * @return default <code>this</code> for implementations, overridden by wrappers.
         */
        default ProcessContext<R> unwrapFully() {
            return this;
        }
    }

    /**
     * Can be thrown by any of the {@link ProcessLambda}s of the {@link MatsStage}s to denote that it would prefer this
     * message to be instantly put on a <i>Dead Letter Queue</i> (the stage processing, including any database actions,
     * will still be rolled back as with any other exception thrown out of a ProcessLambda). This is just advisory - the
     * message might still be presented a number of times to the {@link MatsStage} in question (i.e. for the
     * backend-configured number of retries, e.g. default 1 delivery + 5 redeliveries for ActiveMQ).
     *
     * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
     */
    class MatsRefuseMessageException extends Exception {
        public MatsRefuseMessageException(String message, Throwable cause) {
            super(message, cause);
        }

        public MatsRefuseMessageException(String message) {
            super(message);
        }
    }

    /**
     * A base Wrapper for {@link ProcessContext}, which simply implements ProcessContext, takes a ProcessContext
     * instance and forwards all calls to that. Meant to be extended to add extra functionality, e.g. Spring
     * integration.
     */
    class ProcessContextWrapper<R> implements MatsWrapper<ProcessContext<R>>, ProcessContext<R> {
        /**
         * This field is private - all methods invoke {@link #unwrap()} to get the instance, which you should too if you
         * override any methods. If you want to take control of the wrapped ProcessContext instance, then override
         * {@link #unwrap()}.
         */
        private ProcessContext<R> _targetProcessContext;

        /**
         * Standard constructor, taking the wrapped {@link ProcessContext} instance.
         *
         * @param targetProcessContext
         *            the {@link ProcessContext} instance which {@link #unwrap()} will return (and hence all forwarded
         *            methods will use).
         */
        public ProcessContextWrapper(ProcessContext<R> targetProcessContext) {
            setWrappee(targetProcessContext);
        }

        /**
         * No-args constructor, which implies that you either need to invoke {@link #setWrappee(ProcessContext)} before
         * publishing the instance (making it available for other threads), or override {@link #unwrap()} to provide the
         * desired {@link ProcessContext} instance. In these cases, make sure to honor memory visibility semantics -
         * i.e. establish a happens-before edge between the setting of the instance and any other threads getting it.
         */
        public ProcessContextWrapper() {
            /* no-op */
        }

        /**
         * Sets the wrapped {@link ProcessContext}, e.g. in case you instantiated it with the no-args constructor. <b>Do
         * note that the field holding the wrapped instance is not volatile nor synchronized</b>. This means that if you
         * want to set it after it has been published to other threads, you will have to override both this method and
         * {@link #unwrap()} to provide for needed memory visibility semantics, i.e. establish a happens-before edge
         * between the setting of the instance and any other threads getting it. A <code>volatile</code> field would
         * work nice.
         *
         * @param targetProcessContext
         *            the {@link ProcessContext} which is returned by {@link #unwrap()}, unless that is overridden.
         */
        public void setWrappee(ProcessContext<R> targetProcessContext) {
            _targetProcessContext = targetProcessContext;
        }

        /**
         * @return the wrapped {@link ProcessContext}. All forwarding methods invokes this method to get the wrapped
         *         {@link ProcessContext}, thus if you want to get creative wrt. how and when the ProcessContext is
         *         decided, you can override this method.
         */
        public ProcessContext<R> unwrap() {
            if (_targetProcessContext == null) {
                throw new IllegalStateException("MatsEndpoint.ProcessContextWrapper.unwrap():"
                        + " The '_targetProcessContext' is not set!");
            }
            return _targetProcessContext;
        }

        @Override
        public ProcessContext<R> unwrapFully() {
            return unwrap().unwrapFully();
        }

        /**
         * @deprecated #setTarget()
         */
        @Deprecated
        public void setTargetProcessContext(ProcessContext<R> targetProcessContext) {
            setWrappee(targetProcessContext);
        }

        /**
         * @deprecated #getTarget()
         */
        @Deprecated
        public ProcessContext<R> getTargetProcessContext() {
            return unwrap();
        }

        /**
         * @deprecated #getEndTarget()
         */
        @Deprecated
        public ProcessContext<R> getEndTargetProcessContext() {
            return unwrapFully();
        }

        @Override
        public String getTraceId() {
            return unwrap().getTraceId();
        }

        @Override
        public String getEndpointId() {
            return unwrap().getEndpointId();
        }

        @Override
        public String getStageId() {
            return unwrap().getStageId();
        }

        @Override
        public String getFromAppName() {
            return unwrap().getFromAppName();
        }

        @Override
        public String getFromAppVersion() {
            return unwrap().getFromAppVersion();
        }

        @Override
        public String getFromStageId() {
            return unwrap().getFromStageId();
        }

        @Override
        public Instant getFromTimestamp() {
            return unwrap().getFromTimestamp();
        }

        @Override
        public String getInitiatingAppName() {
            return unwrap().getInitiatingAppName();
        }

        @Override
        public String getInitiatingAppVersion() {
            return unwrap().getInitiatingAppVersion();
        }

        @Override
        public String getInitiatorId() {
            return unwrap().getInitiatorId();
        }

        @Override
        public Instant getInitiatingTimestamp() {
            return unwrap().getInitiatingTimestamp();
        }

        @Override
        public String getMatsMessageId() {
            return unwrap().getMatsMessageId();
        }

        @Override
        public String getSystemMessageId() {
            return unwrap().getSystemMessageId();
        }

        @Override
        public boolean isNonPersistent() {
            return unwrap().isNonPersistent();
        }

        @Override
        public boolean isInteractive() {
            return unwrap().isInteractive();
        }

        @Override
        public boolean isNoAudit() {
            return unwrap().isNoAudit();
        }

        @Override
        public byte[] getBytes(String key) {
            return unwrap().getBytes(key);
        }

        @Override
        public String getString(String key) {
            return unwrap().getString(key);
        }

        @Override
        public <T> T getTraceProperty(String propertyName, Class<T> clazz) {
            return unwrap().getTraceProperty(propertyName, clazz);
        }

        @Override
        public void addBytes(String key, byte[] payload) {
            unwrap().addBytes(key, payload);
        }

        @Override
        public void addString(String key, String payload) {
            unwrap().addString(key, payload);
        }

        @Override
        public void setTraceProperty(String propertyName, Object propertyValue) {
            unwrap().setTraceProperty(propertyName, propertyValue);
        }

        @Override
        public byte[] stash() {
            return unwrap().stash();
        }

        @Override
        public MessageReference request(String endpointId, Object requestDto) {
            return unwrap().request(endpointId, requestDto);
        }

        @Override
        public MessageReference reply(R replyDto) {
            return unwrap().reply(replyDto);
        }

        @Override
        public MessageReference next(Object incomingDto) {
            return unwrap().next(incomingDto);
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            unwrap().initiate(lambda);
        }

        @Override
        public void doAfterCommit(Runnable runnable) {
            unwrap().doAfterCommit(runnable);
        }

        @Override
        public <T> Optional<T> getAttribute(Class<T> type, String... name) {
            return unwrap().getAttribute(type, name);
        }

        @Override
        public String toString() {
            return "ProcessContextWrapper[" + this.getClass().getSimpleName() + "]:" + unwrap().toString();
        }
    }
}
