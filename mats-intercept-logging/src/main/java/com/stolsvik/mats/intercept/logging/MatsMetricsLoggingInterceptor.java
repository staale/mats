package com.stolsvik.mats.intercept.logging;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.api.intercept.CommonCompletedContext;
import com.stolsvik.mats.api.intercept.MatsInitiateInterceptor;
import com.stolsvik.mats.api.intercept.MatsInterceptable;
import com.stolsvik.mats.api.intercept.MatsLoggingInterceptor;
import com.stolsvik.mats.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import com.stolsvik.mats.api.intercept.MatsStageInterceptor;

/**
 * A logging interceptor that writes loglines to two SLF4J loggers, including multiple pieces of information on the MDC
 * (timing and endpointIds/stageIds), so as to be able to use the logging system (e.g. Kibana over ElasticSearch) to
 * create statistics.
 * <p />
 * <b>Note: This interceptor (SLF4J Logger with Metrics on MDC) has special support in <code>JmsMatsFactory</code>: If
 * present on the classpath, it is automatically installed using the {@link #install(MatsInterceptable)} install
 * method.</b> This interceptor implements the special marker-interface {@link MatsLoggingInterceptor} of which there
 * can only be one instance installed in a <code>JmsMatsFactory</code> - implying that if you want a different type of
 * logging, you may implement a custom variant (either subclassing this, on your own risk, or start from scratch), and
 * simply install it, leading to this instance being removed (or just not have this variant on the classpath).
 *
 * @author Endre Stølsvik - 2021-02-07 12:45 - http://endre.stolsvik.com
 */
public class MatsMetricsLoggingInterceptor
        implements MatsLoggingInterceptor, MatsInitiateInterceptor, MatsStageInterceptor {

    public static final Logger log_init = LoggerFactory.getLogger("com.stolsvik.mats.log.init");
    public static final Logger log_stage = LoggerFactory.getLogger("com.stolsvik.mats.log.stage");

    public static final String LOG_PREFIX = "#MATSLOG# ";

    public static final MatsMetricsLoggingInterceptor INSTANCE = new MatsMetricsLoggingInterceptor();

    // Not using "mats." prefix for "traceId", as it is hopefully generic yet specific
    // enough that it might be used in similar applications.
    public String MDC_TRACE_ID = "traceId";

    // ============================================================================================================
    // ===== For Initiate Completed, with timings:
    // 'true' on a single logline per completed initiation:
    public String MDC_MATS_INITIATE_COMPLETED = "mats.InitiateCompleted";

    // ..... Stage/Init complete metrics.
    public String MDC_MATS_COMPLETE_TOTAL_EXECUTION = "mats.done.ms.TotalExecution";
    public String MDC_MATS_COMPLETE_USER_LAMBDA = "mats.done.ms.UserLambda";
    public String MDC_MATS_COMPLETE_SUM_MSG_OUT_HANDLING = "mats.done.ms.SumMsgOutHandling";
    public String MDC_MATS_COMPLETE_SUM_DB_COMMIT = "mats.done.ms.DbCommit";
    public String MDC_MATS_COMPLETE_SUM_MSG_SYS_COMMIT = "mats.done.ms.MsgSysCommit";

    // ============================================================================================================
    // ===== For Receiving a message
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_STAGE_ID = "mats.StageId";
    // MDC_MATS_IN_SYS_MESSAGE_ID = "mats.in.SystemMessageId";
    // MDC_TRACE_ID = "traceId"

    // 'true' on a single logline per received message:
    public String MDC_MATS_MESSAGE_RECEIVED = "mats.MessageReceived";

    public String MDC_MATS_IN_FROM_APP_NAME = "mats.in.from.App";
    public String MDC_MATS_IN_FROM_ID = "mats.in.from.Id";
    // NOTICE: NOT using MDC_MATS_IN_TO_APP, as that is identical to MDC_MATS_APP_NAME
    // NOTICE: NOT using MDC_MATS_IN_TO_ID, as that is identical to MDC_MATS_STAGE_ID
    public String MDC_MATS_IN_MATS_MESSAGE_ID = "mats.in.MatsMsgId";

    // NOTICE: Lots of Stage Receive&Processing MDCs are set by the JMS Mats Implementation.

    // ... Metrics:
    public String MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL = "mats.in.ms.TotalPreprocDeserial";
    public String MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT = "mats.in.ms.MsgSysDeconstruct";
    public String MDC_MATS_IN_SIZE_ENVELOPE_WIRE = "mats.in.bytes.EnvelopeWire";
    public String MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS = "mats.in.ms.EnvelopeDecompress";
    public String MDC_MATS_IN_SIZE_ENVELOPE_SERIAL = "mats.in.bytes.EnvelopeSerial";
    public String MDC_MATS_IN_TIME_ENVELOPE_DESERIAL = "mats.in.ms.EnvelopeDeserial";
    public String MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL = "mats.in.ms.MsgAndStateDeserial";

    // ============================================================================================================
    // ===== For Stage Completed
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_STAGE_ID = "mats.StageId";
    // MDC_TRACE_ID = "traceId"
    public String MDC_MATS_STAGE_COMPLETED = "mats.StageCompleted"; // Set on a single logline per completed stage.
    public String MDC_MATS_COMPLETE_PROCESS_RESULT = "mats.done.ProcessResult"; // Set on a single logline per completed

    // ..... specific Stage complete metric.
    public String MDC_MATS_COMPLETE_TOTAL_PREPROC_AND_DESERIAL = "mats.done.ms.TotalPreprocDeserial";

    // ============================================================================================================
    // ===== For Sending a single message (from init, or stage) - one line per message
    // 'true' on single logline per msg:
    public String MDC_MATS_MESSAGE_SENT = "mats.MessageSent";
    public String MDC_MATS_DISPATCH_TYPE = "mats.DispatchType"; // Set on single logline per msg: INIT, STAGE,
                                                                // STAGE_INIT
    public String MDC_MATS_OUT_MATS_MESSAGE_ID = "mats.out.MatsMsgId";
    public String MDC_MATS_OUT_MESSAGE_SYSTEM_ID = "mats.out.MsgSysId";

    // NOTICE: NOT using MDC_MATS_OUT_FROM_APP / "mats.out.from.App", as that is 'this' App: MDC_MATS_APP_NAME.
    // NOTICE: MDC_MATS_OUT_FROM_ID == MDC_MATS_STAGE_ID for Stages - but there is no corresponding for InitiatorId.
    public String MDC_MATS_OUT_FROM_ID = "mats.out.from.Id"; // "this" EndpointId/StageId/InitiatorId.
    public String MDC_MATS_OUT_TO_ID = "mats.out.to.Id"; // target EndpointId/StageId.
    // NOTICE: NOT using MDC_MATS_OUT_TO_APP, since we do not know which app will consume it.

    // ... Metrics:
    public String MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE = "mats.out.ms.EnvelopeProduce";
    public String MDC_MATS_OUT_TIME_ENVELOPE_SERIAL = "mats.out.ms.EnvelopeSerial";
    public String MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL = "mats.out.bytes.EnvelopeSerial";
    public String MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS = "mats.out.ms.EnvelopeCompress";
    public String MDC_MATS_OUT_SIZE_ENVELOPE_WIRE = "mats.out.bytes.EnvelopeWire";
    public String MDC_MATS_OUT_TIME_MSGSYS_CONSTRUCT_AND_SEND = "mats.out.ms.MsgSysConstructAndSend";
    public String MDC_MATS_OUT_TIME_TOTAL = "mats.out.ms.Total";

    // ============================================================================================================
    // ===== For both Message Received and Message Send (note: part of root MatsTrace, common for all msgs in flow)
    public String MDC_MATS_INIT_APP = "mats.init.App";
    public String MDC_MATS_INIT_ID = "mats.init.Id"; // matsInitiate.from(initiatorId).
    public String MDC_MATS_AUDIT = "mats.Audit";
    public String MDC_MATS_PERSISTENT = "mats.Persistent";

    /**
     * Adds the singleton {@link #INSTANCE} as both Initiation and Stage interceptors.
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} MatsFactory to add it to.
     */
    public static void install(MatsInterceptable matsInterceptableMatsFactory) {
        matsInterceptableMatsFactory.addInitiationInterceptorSingleton(INSTANCE);
        matsInterceptableMatsFactory.addStageInterceptorSingleton(INSTANCE);
    }

    /**
     * Removes the singleton {@link #INSTANCE} as both Initiation and Stage interceptors.
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} to remove it from.
     */
    public static void remove(MatsInterceptable matsInterceptableMatsFactory) {
        matsInterceptableMatsFactory.removeInitiationInterceptorSingleton(INSTANCE);
        matsInterceptableMatsFactory.removeStageInterceptorSingleton(INSTANCE);
    }

    @Override
    public void initiateCompleted(InitiateCompletedContext ctx) {
        try {
            MDC.put(MDC_MATS_INITIATE_COMPLETED, "true");
            List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();
            String messageSenderName = ctx.getInitiator().getParentFactory()
                    .getFactoryConfig().getName() + "|" + ctx.getInitiator().getName();

            commonStageAndInitiateCompleted(ctx, "", log_init, outgoingMessages, messageSenderName, "", 0L);
        }
        finally {
            MDC.remove(MDC_MATS_INITIATE_COMPLETED);
        }
    }

    @Override
    public void stageReceived(StageReceivedContext ctx) {
        try {
            MDC.put(MDC_MATS_MESSAGE_RECEIVED, "true");
            ProcessContext<Object> processContext = ctx.getProcessContext();

            long sumNanosPieces = ctx.getMessageSystemDeconstructNanos()
                    + ctx.getEnvelopeDecompressionNanos()
                    + ctx.getEnvelopeDeserializationNanos()
                    + ctx.getMessageAndStateDeserializationNanos();

            MDC.put(MDC_MATS_INIT_APP, processContext.getInitiatingAppName());
            MDC.put(MDC_MATS_INIT_ID, processContext.getInitiatorId());
            MDC.put(MDC_MATS_AUDIT, Boolean.toString(!processContext.isNoAudit()));
            MDC.put(MDC_MATS_PERSISTENT, Boolean.toString(!processContext.isNonPersistent()));

            MDC.put(MDC_MATS_IN_FROM_APP_NAME, processContext.getFromAppName());
            MDC.put(MDC_MATS_IN_FROM_ID, processContext.getFromStageId());
            MDC.put(MDC_MATS_IN_MATS_MESSAGE_ID, processContext.getMatsMessageId());

            // Total:
            MDC.put(MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL,
                    msS(ctx.getTotalPreprocessAndDeserializeNanos()));

            // Breakdown:
            MDC.put(MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT, msS(ctx.getMessageSystemDeconstructNanos()));
            MDC.put(MDC_MATS_IN_SIZE_ENVELOPE_WIRE, Long.toString(ctx.getEnvelopeWireSize()));
            MDC.put(MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS, msS(ctx.getEnvelopeDecompressionNanos()));
            MDC.put(MDC_MATS_IN_SIZE_ENVELOPE_SERIAL, Long.toString(ctx.getEnvelopeSerializedSize()));
            MDC.put(MDC_MATS_IN_TIME_ENVELOPE_DESERIAL, msS(ctx.getEnvelopeDeserializationNanos()));
            MDC.put(MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL, msS(ctx.getMessageAndStateDeserializationNanos()));

            log_stage.info(LOG_PREFIX + "RECEIVED [" + ctx.getIncomingMessageType()
                    + "] message from [" + processContext.getFromStageId()
                    + "@" + processContext.getFromAppName() + ",v." + processContext.getFromAppVersion()
                    + "], totPreprocAndDeserial:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos())
                    + "] || breakdown: msgSysDeconstruct:[" + ms(ctx.getMessageSystemDeconstructNanos())
                    + " ms]->envelopeWireSize:[" + ctx.getEnvelopeWireSize()
                    + " B]->decomp:[" + ms(ctx.getEnvelopeDecompressionNanos())
                    + " ms]->serialSize:[" + ctx.getEnvelopeSerializedSize()
                    + " B]->deserial:[" + ms(ctx.getEnvelopeDeserializationNanos())
                    + " ms]->(envelope)->dto&stoDeserial:[" + ms(ctx.getMessageAndStateDeserializationNanos())
                    + " ms] - sum pieces:[" + ms(sumNanosPieces)
                    + " ms], diff:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos() - sumNanosPieces)
                    + " ms]");
        }
        finally {
            MDC.remove(MDC_MATS_MESSAGE_RECEIVED);

            MDC.remove(MDC_MATS_INIT_APP);
            MDC.remove(MDC_MATS_INIT_ID);
            MDC.remove(MDC_MATS_AUDIT);
            MDC.remove(MDC_MATS_PERSISTENT);

            MDC.remove(MDC_MATS_IN_FROM_APP_NAME);
            MDC.remove(MDC_MATS_IN_FROM_ID);
            MDC.remove(MDC_MATS_IN_MATS_MESSAGE_ID);

            MDC.remove(MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL);

            MDC.remove(MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT);
            MDC.remove(MDC_MATS_IN_SIZE_ENVELOPE_WIRE);
            MDC.remove(MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS);
            MDC.remove(MDC_MATS_IN_SIZE_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_IN_TIME_ENVELOPE_DESERIAL);
            MDC.remove(MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL);
        }
    }

    @Override
    public void stageCompleted(StageCompletedContext ctx) {
        try {
            MDC.put(MDC_MATS_STAGE_COMPLETED, "true");
            List<MatsSentOutgoingMessage> outgoingMessages = ctx
                    .getOutgoingMessages();

            String messageSenderName = ctx.getStage().getParentEndpoint().getParentFactory()
                    .getFactoryConfig().getName();

            // :: Specific metric for stage completed
            String extraBreakdown = " totPreprocAndDeserial:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos())
                    + " ms],";
            long extraNanosBreakdown = ctx.getTotalPreprocessAndDeserializeNanos();
            MDC.put(MDC_MATS_COMPLETE_TOTAL_PREPROC_AND_DESERIAL, msS(ctx
                    .getTotalPreprocessAndDeserializeNanos()));

            MDC.put(MDC_MATS_COMPLETE_PROCESS_RESULT, ctx.getProcessResult().toString());

            commonStageAndInitiateCompleted(ctx, " with result " + ctx.getProcessResult(), log_stage,
                    outgoingMessages, messageSenderName, extraBreakdown, extraNanosBreakdown);
        }
        finally {
            MDC.remove(MDC_MATS_STAGE_COMPLETED);
            MDC.remove(MDC_MATS_COMPLETE_TOTAL_PREPROC_AND_DESERIAL);
            MDC.remove(MDC_MATS_COMPLETE_PROCESS_RESULT);
        }
    }

    private void commonStageAndInitiateCompleted(CommonCompletedContext ctx, String extraResult, Logger logger,
            List<MatsSentOutgoingMessage> outgoingMessages, String messageSenderName,
            String extraBreakdown, long extraNanosBreakdown) {

        String what = extraBreakdown.isEmpty() ? "INIT" : "STAGE";

        // ?: Do we have a Throwable, indicating that processing (stage or init) failed?
        if (ctx.getThrowable().isPresent()) {
            // -> Yes, we have a Throwable
            // :: Output the 'completed' (now FAILED) line
            // NOTE: We do NOT output any messages, even if there are any (they can have been produced before the
            // error occurred), as they will NOT have been sent, and the log lines are supposed to represent
            // actual messages that have been put on the wire.
            Throwable t = ctx.getThrowable().get();
            completedLog(ctx, LOG_PREFIX + what + " !!FAILED!!" + extraResult, extraBreakdown, extraNanosBreakdown,
                    Collections.emptyList(), Level.ERROR, logger, t, "");
        }
        else if (outgoingMessages.size() != 1) {
            // -> Yes, >1 or 0 messages

            // :: Output the 'completed' line
            completedLog(ctx, LOG_PREFIX + what + " completed" + extraResult, extraBreakdown, extraNanosBreakdown,
                    outgoingMessages, Level.INFO, logger, null, "");

            // :: Output the per-message logline (there might be >1, or 0, messages)
            for (MatsSentOutgoingMessage outgoingMessage : ctx.getOutgoingMessages()) {
                msgMdcLog(outgoingMessage, () -> log_init.info(LOG_PREFIX
                        + msgLogLine(messageSenderName, outgoingMessage)));
            }
        }
        else {
            // -> Only 1 message and no Throwable: Concat the two lines.
            msgMdcLog(outgoingMessages.get(0), () -> {
                String msgLine = msgLogLine(messageSenderName, outgoingMessages.get(0));
                completedLog(ctx, LOG_PREFIX + what + " completed" + extraResult, extraBreakdown, extraNanosBreakdown,
                        outgoingMessages, Level.INFO, logger, null, "\n    " + LOG_PREFIX + msgLine);
            });
        }
    }

    private enum Level {
        INFO, ERROR
    }

    private void completedLog(CommonCompletedContext ctx, String logPrefix, String extraBreakdown,
            long extraNanosBreakdown, List<MatsSentOutgoingMessage> outgoingMessages, Level level, Logger log,
            Throwable t, String logPostfix) {

        /*
         * NOTE! The timings are a bit user-unfriendly wrt. "user lambda" timing, as this includes the production of
         * outgoing envelopes, including DTO, STO and TraceProps serialization, due to how it must be implemented. Thus,
         * we do some tricking here to get more relevant "split up" of the separate pieces.
         */
        // :: Find total DtoAndSto Serialization of outgoing messages
        long nanosTaken_SumDtoAndStoSerialNanos = 0;
        for (MatsSentOutgoingMessage msg : outgoingMessages) {
            nanosTaken_SumDtoAndStoSerialNanos += msg.getEnvelopeProduceNanos();
        }

        // :: Subtract the total DtoAndSto Serialization from the user lambda time.
        long nanosTaken_UserLambdaAlone = ctx.getUserLambdaNanos() - nanosTaken_SumDtoAndStoSerialNanos;

        // :: Sum up the total "message handling": Dto&Sto + envelope serial + msg.sys. handling.
        long nanosTaken_SumMessageOutHandling = nanosTaken_SumDtoAndStoSerialNanos
                + ctx.getSumEnvelopeSerializationAndCompressionNanos()
                + ctx.getSumMessageSystemProductionAndSendNanos();

        // Sum of all the pieces, to compare against the total execution time.
        long nanosTaken_SumPieces = extraNanosBreakdown
                + nanosTaken_UserLambdaAlone
                + nanosTaken_SumMessageOutHandling
                + ctx.getDbCommitNanos()
                + ctx.getMessageSystemCommitNanos();

        String numMessages;
        switch (outgoingMessages.size()) {
            case 0:
                numMessages = "no outgoing messages";
                break;
            case 1:
                numMessages = "single outgoing " + outgoingMessages.get(0).getMessageType() + " message";
                break;
            default:
                numMessages = "[" + outgoingMessages.size() + "] outgoing messages";
        }

        try {
            MDC.put(MDC_MATS_COMPLETE_TOTAL_EXECUTION, msS(ctx.getTotalExecutionNanos()));
            MDC.put(MDC_MATS_COMPLETE_USER_LAMBDA, msS(nanosTaken_UserLambdaAlone));
            MDC.put(MDC_MATS_COMPLETE_SUM_MSG_OUT_HANDLING, msS(nanosTaken_SumMessageOutHandling));
            MDC.put(MDC_MATS_COMPLETE_SUM_DB_COMMIT, msS(ctx.getDbCommitNanos()));
            MDC.put(MDC_MATS_COMPLETE_SUM_MSG_SYS_COMMIT, msS(ctx.getMessageSystemCommitNanos()));

            String msg = logPrefix
                    + ", " + numMessages
                    + ", total:[" + ms(ctx.getTotalExecutionNanos()) + " ms]"
                    + " || breakdown:"
                    + extraBreakdown
                    + " userLambda (excl. produceEnvelopes):[" + ms(nanosTaken_UserLambdaAlone)
                    + " ms], sumMsgOutHandling:[" + ms(nanosTaken_SumMessageOutHandling)
                    + " ms], dbCommit:[" + ms(ctx.getDbCommitNanos())
                    + " ms], msgSysCommit:[" + ms(ctx.getMessageSystemCommitNanos())
                    + " ms] - sum pieces:[" + ms(nanosTaken_SumPieces)
                    + " ms], diff:[" + ms(ctx.getTotalExecutionNanos() - nanosTaken_SumPieces)
                    + " ms]"
                    + logPostfix;

            // ?: Log at what level?
            if (level == Level.INFO) {
                // -> INFO
                log.info(msg);
            }
            else {
                // -> ERROR
                log.error(msg, t);
            }
        }
        finally {
            MDC.remove(MDC_MATS_COMPLETE_TOTAL_EXECUTION);
            MDC.remove(MDC_MATS_COMPLETE_USER_LAMBDA);
            MDC.remove(MDC_MATS_COMPLETE_SUM_MSG_OUT_HANDLING);
            MDC.remove(MDC_MATS_COMPLETE_SUM_DB_COMMIT);
            MDC.remove(MDC_MATS_COMPLETE_SUM_MSG_SYS_COMMIT);
        }
    }

    private void msgMdcLog(MatsSentOutgoingMessage msg, Runnable runnable) {
        String existingTraceId = MDC.get(MDC_TRACE_ID);
        try {
            MDC.put(MDC_TRACE_ID, msg.getTraceId());

            MDC.put(MDC_MATS_MESSAGE_SENT, "true");
            MDC.put(MDC_MATS_DISPATCH_TYPE, msg.getDispatchType().toString());

            MDC.put(MDC_MATS_OUT_MATS_MESSAGE_ID, msg.getMatsMessageId());
            MDC.put(MDC_MATS_OUT_MESSAGE_SYSTEM_ID, msg.getSystemMessageId());

            MDC.put(MDC_MATS_INIT_APP, msg.getInitiatingAppName());
            MDC.put(MDC_MATS_INIT_ID, msg.getInitiatorId());
            MDC.put(MDC_MATS_OUT_FROM_ID, msg.getFrom());
            MDC.put(MDC_MATS_OUT_TO_ID, msg.getTo());
            MDC.put(MDC_MATS_AUDIT, Boolean.toString(!msg.isNoAudit()));
            MDC.put(MDC_MATS_PERSISTENT, Boolean.toString(!msg.isNonPersistent()));

            // Metrics:
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE, msS(msg.getEnvelopeProduceNanos()));
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_SERIAL, msS(msg.getEnvelopeSerializationNanos()));
            MDC.put(MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL, Long.toString(msg.getEnvelopeSerializedSize()));
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS, msS(msg.getEnvelopeCompressionNanos()));
            MDC.put(MDC_MATS_OUT_SIZE_ENVELOPE_WIRE, Long.toString(msg.getEnvelopeWireSize()));
            MDC.put(MDC_MATS_OUT_TIME_MSGSYS_CONSTRUCT_AND_SEND, msS(msg.getMessageSystemProductionAndSendNanos()));
            long nanosTaken_Total = msg.getEnvelopeProduceNanos()
                    + msg.getEnvelopeSerializationNanos()
                    + msg.getEnvelopeCompressionNanos()
                    + msg.getMessageSystemProductionAndSendNanos();
            MDC.put(MDC_MATS_OUT_TIME_TOTAL, msS(nanosTaken_Total));

            // :: Actually run the Runnable
            runnable.run();
        }
        finally {

            // :: Restore MDC
            // TraceId
            if (existingTraceId == null) {
                MDC.remove(MDC_TRACE_ID);
            }
            else {
                MDC.put(MDC_TRACE_ID, existingTraceId);
            }
            MDC.remove(MDC_MATS_MESSAGE_SENT);
            MDC.remove(MDC_MATS_DISPATCH_TYPE);

            MDC.remove(MDC_MATS_OUT_MATS_MESSAGE_ID);
            MDC.remove(MDC_MATS_OUT_MESSAGE_SYSTEM_ID);

            MDC.remove(MDC_MATS_INIT_APP);
            MDC.remove(MDC_MATS_INIT_ID);
            MDC.remove(MDC_MATS_OUT_FROM_ID);
            MDC.remove(MDC_MATS_OUT_TO_ID);
            MDC.remove(MDC_MATS_AUDIT);
            MDC.remove(MDC_MATS_PERSISTENT);

            MDC.remove(MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE);
            MDC.remove(MDC_MATS_OUT_TIME_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS);
            MDC.remove(MDC_MATS_OUT_SIZE_ENVELOPE_WIRE);
            MDC.remove(MDC_MATS_OUT_TIME_MSGSYS_CONSTRUCT_AND_SEND);
            MDC.remove(MDC_MATS_OUT_TIME_TOTAL);
        }
    }

    private String msgLogLine(String messageSenderName, MatsSentOutgoingMessage msg) {
        long nanosTaken_Total = msg.getEnvelopeProduceNanos()
                + msg.getEnvelopeSerializationNanos()
                + msg.getEnvelopeCompressionNanos()
                + msg.getMessageSystemProductionAndSendNanos();

        return msg.getDispatchType() + " outgoing " + msg.getMessageType()
                + " message from [" + messageSenderName + "|" + msg.getFrom()
                + "] -> [" + msg.getTo()
                + "], total:[" + ms(nanosTaken_Total)
                + " ms] || breakdown: produce:[" + ms(msg.getEnvelopeProduceNanos())
                + " ms]->(envelope)->serial:[" + ms(msg.getEnvelopeSerializationNanos())
                + " ms]->serialSize:[" + msg.getEnvelopeSerializedSize()
                + " B]->comp:[" + ms(msg.getEnvelopeCompressionNanos())
                + " ms]->envelopeWireSize:[" + msg.getEnvelopeWireSize()
                + " B]->msgSysConstruct&Send:[" + ms(msg.getMessageSystemProductionAndSendNanos())
                + " ms]";
    }

    private static String msS(long nanosTake) {
        return Double.toString(ms(nanosTake));
    }

    /**
     * Converts nanos to millis with a sane number of significant digits ("3.5" significant digits), but assuming that
     * this is not used to measure things that take less than 0.001 milliseconds (in which case it will be "rounded" to
     * 0.0001, 1e-4, as a special value). Takes care of handling the difference between 0 and >0 nanoseconds when
     * rounding - in that 1 nanosecond will become 0.0001 (1e-4 ms, which if used to measure things that are really
     * short lived might be magnitudes wrong), while 0 will be 0.0 exactly. Note that printing of a double always
     * include the ".0" (unless scientific notation kicks in), which can lead your interpretation astray when running
     * this over e.g. the number 555_555_555, which will print as "556.0", and 5_555_555_555 prints "5560.0".
     */
    private static double ms(long nanosTaken) {
        if (nanosTaken == 0) {
            return 0.0;
        }
        // >=500_000 ms?
        if (nanosTaken >= 1_000_000L * 500_000) {
            // -> Yes, >500_000ms, thus chop into the integer part of the number (dropping 3 digits)
            // (note: printing of a double always include ".0"), e.g. 612000.0
            return Math.round(nanosTaken / 1_000_000_000d) * 1_000d;
        }
        // >=50_000 ms?
        if (nanosTaken >= 1_000_000L * 50_000) {
            // -> Yes, >50_000ms, thus chop into the integer part of the number (dropping 2 digits)
            // (note: printing of a double always include ".0"), e.g. 61200.0
            return Math.round(nanosTaken / 100_000_000d) * 100d;
        }
        // >=5_000 ms?
        if (nanosTaken >= 1_000_000L * 5_000) {
            // -> Yes, >5_000ms, thus chop into the integer part of the number (dropping 1 digit)
            // (note: printing of a double always include ".0"), e.g. 6120.0
            return Math.round(nanosTaken / 10_000_000d) * 10d;
        }
        // >=500 ms?
        if (nanosTaken >= 1_000_000L * 500) {
            // -> Yes, >500ms, so chop off fraction entirely
            // (note: printing of a double always include ".0"), e.g. 612.0
            return Math.round(nanosTaken / 1_000_000d);
        }
        // >=50 ms?
        if (nanosTaken >= 1_000_000L * 50) {
            // -> Yes, >50ms, so use 1 decimal, e.g. 61.2
            return Math.round(nanosTaken / 100_000d) / 10d;
        }
        // >=5 ms?
        if (nanosTaken >= 1_000_000L * 5) {
            // -> Yes, >5ms, so use 2 decimal, e.g. 6.12
            return Math.round(nanosTaken / 10_000d) / 100d;
        }
        // E-> <5 ms
        // Use 3 decimals, but at least '0.0001' if round to zero, so as to point out that it is NOT 0.0d
        // e.g. 0.612
        return Math.max(Math.round(nanosTaken / 1_000d) / 1_000d, 0.0001d);
    }
}