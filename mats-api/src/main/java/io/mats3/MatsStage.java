package io.mats3;

import io.mats3.MatsConfig.StartStoppable;
import io.mats3.MatsEndpoint.EndpointConfig;

/**
 * A representation of a process stage of a {@link MatsEndpoint}. Either constructed implicitly (for single-stage
 * endpoints, and terminators), or by invoking the
 * {@link MatsEndpoint#stage(Class, io.mats3.MatsEndpoint.ProcessLambda) MatsEndpoint.stage(...)}-methods on
 * {@link MatsFactory#staged(String, Class, Class) multi-stage} endpoints.
 * <p />
 * Note: It should be possible to use instances of <code>MatsStage</code> as keys in a <code>HashMap</code>, i.e. their
 * equals and hashCode should remain stable throughout the life of the MatsFactory - and similar instances but with
 * different MatsFactory are <i>not</i> equals. Depending on the implementation, instance equality may be sufficient.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsStage<R, S, I> extends StartStoppable {

    /**
     * @return the {@link StageConfig} for this stage.
     */
    StageConfig<R, S, I> getStageConfig();

    /**
     * @return the parent {@link MatsEndpoint}.
     */
    MatsEndpoint<R, S> getParentEndpoint();

    /**
     * Starts this stage, thereby firing up the queue processing using a set of threads, the number decided by the
     * {@link StageConfig#getConcurrency()} for each stage.
     * <p/>
     * Will generally be invoked implicitly by {@link MatsEndpoint#start()}. The only reason for calling this should be
     * if its corresponding {@link #stop(int)} method has been invoked to stop processing.
     */
    @Override
    void start();

    /**
     * Will wait until at least one processor of the stage has entered its receive-loop.
     */
    @Override
    boolean waitForReceiving(int timeoutMillis);

    /**
     * Stops this stage. This may be used to temporarily stop processing of this stage by means of some external
     * monitor/inspecting mechanism (e.g. in cases where it has been showed to produce results that breaks downstream
     * stages or endpoints, or itself produces <i>Dead Letter Queue</i>-entries due to some external problem). It is
     * possible to {@link #start()} the stage again.
     */
    @Override
    boolean stop(int gracefulShutdownMillis);

    /**
     * Provides for both configuring the stage (before it is started), and introspecting the configuration.
     */
    interface StageConfig<R, S, I> extends MatsConfig {

        /**
         * @return the stageId for this Stage - for the initial stage of an endpoint, this is the same as the
         *         {@link EndpointConfig#getEndpointId()}.
         */
        String getStageId();

        /**
         * TODO: Remove once all are >= 0.17
         *
         * @deprecated use correctly named {@link #getIncomingClass()}.
         */
        @Deprecated
        Class<I> getIncomingMessageClass();

        /**
         * @return the class expected for incoming messages to this process stage.
         */
        Class<I> getIncomingClass();

        /**
         * @return the currently number of running Stage Processors (the actual concurrency - this might be different
         *         from {@link #getConcurrency} if the concurrency was set when stage was running.
         */
        int getRunningStageProcessors();
    }
}