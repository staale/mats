package com.stolsvik.mats.spring.jms.factories;

import javax.jms.ConnectionFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

import com.stolsvik.mats.util.wrappers.ConnectionFactoryWrapper;

/**
 * A <code>ConnectionFactoryWrapper</code> which lazily decides which of the three {@link MatsScenario}s are active, and
 * produces the wrapper-target {@link ConnectionFactory} based on that - you most probably want to use
 * {@link ScenarioConnectionFactoryProducer} to make an instance of this class, but you can configure it directly too.
 * <p />
 * <b>The main documentation for this MatsScenario concept is in the JavaDoc of
 * {@link ScenarioConnectionFactoryProducer}</b>.
 *
 * @see ScenarioConnectionFactoryProducer
 * @see MatsProfiles
 * @see MatsScenario
 * @author Endre Stølsvik 2019-06-10 23:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class ScenarioConnectionFactoryWrapper
        extends ConnectionFactoryWrapper
        implements EnvironmentAware, BeanNameAware, SmartLifecycle {
    // Use clogging, since that's what Spring does.
    private static final Log log = LogFactory.getLog(ScenarioConnectionFactoryWrapper.class);
    private static final String LOG_PREFIX = "#SPRINGJMATS# ";

    /**
     * A ConnectionFactory provider which can throw Exceptions - if it returns a
     * {@link ConnectionFactoryWithStartStopWrapper}, start() and stop() will be invoked on that, read more on its
     * JavaDoc.
     */
    @FunctionalInterface
    public interface ConnectionFactoryProvider {
        ConnectionFactory get(Environment springEnvironment) throws Exception;
    }

    /**
     * We need a way to decide between the three different {@link MatsScenario}s. Check out the
     * {@link ConfigurableScenarioDecider}.
     *
     * @see ConfigurableScenarioDecider
     */
    @FunctionalInterface
    public interface ScenarioDecider {
        MatsScenario decision(Environment springEnvironment);
    }

    protected ConnectionFactoryProvider _regularConnectionFactoryProvider;
    protected ConnectionFactoryProvider _localhostConnectionFactoryProvider;
    protected ConnectionFactoryProvider _localVmConnectionFactoryProvider;
    protected ScenarioDecider _scenarioDecider;

    /**
     * Constructor taking {@link ConnectionFactoryProvider}s for each of the three {@link MatsScenario}s and a
     * {@link ScenarioDecider} to decide which of these to employ - you most probably want to use
     * {@link ScenarioConnectionFactoryProducer} to make one of these.
     */
    public ScenarioConnectionFactoryWrapper(ConnectionFactoryProvider regular, ConnectionFactoryProvider localhost,
            ConnectionFactoryProvider localvm, ScenarioDecider scenarioDecider) {
        _regularConnectionFactoryProvider = regular;
        _localhostConnectionFactoryProvider = localhost;
        _localVmConnectionFactoryProvider = localvm;
        _scenarioDecider = scenarioDecider;
    }

    protected String _beanName;

    @Override
    public void setBeanName(String name) {
        _beanName = name;
    }

    protected Environment _environment;

    @Override
    public void setEnvironment(Environment environment) {
        _environment = environment;
    }

    @Override
    public void setWrappee(ConnectionFactory targetConnectionFactory) {
        throw new IllegalStateException("You cannot set a target ConnectionFactory on a "
                + this.getClass().getSimpleName()
                + "; A set of suppliers will have to be provided in the constructor.");
    }

    protected volatile ConnectionFactory _targetConnectionFactory;
    protected volatile MatsScenario _matsScenarioDecision;

    @Override
    public ConnectionFactory unwrap() {
        /*
         * Perform lazy init, even though it should have been produced by SmartLifeCycle.start() below. It is here for
         * the situation where all beans have been put into lazy init mode (the test-helper project "Remock" does this).
         * Evidently what can happen then, is that life cycle process can have been run, and then you get more beans
         * being pulled up - but these were too late to be lifecycled. Thus, the start() won't be run, so we'll get a
         * null target ConnectionFactory when we request it. By performing lazy-init check here, we hack it in place in
         * such scenarios.
         */
        if (_targetConnectionFactory == null) {
            log.info(LOG_PREFIX + "Whoops! TargetConnectionFactory is null! - perform lazy-init!");
            synchronized (this) {
                if (_targetConnectionFactory == null) {
                    createTargetConnectionFactoryBasedOnScenarioDecider();
                }
            }
        }
        return _targetConnectionFactory;
    }

    /**
     * @return the {@link MatsScenario} that was used to make the {@link ConnectionFactory} returned by
     *         {@link #unwrap()}.
     */
    public MatsScenario getMatsScenarioUsedToMakeConnectionFactory() {
        // Ensure lazy-init if we're in such scenario (lazy-init of entire Spring factory, comments in invoked method).
        unwrap();
        // Now return whatever decision was used to make the ConnectionFactory.
        return _matsScenarioDecision;
    }

    protected void createTargetConnectionFactoryBasedOnScenarioDecider() {
        if (_targetConnectionFactory != null) {
            log.info(LOG_PREFIX + "  \\- Target ConnectionFactory already present, not creating again.");
            return;
        }
        ConnectionFactoryProvider provider;
        _matsScenarioDecision = _scenarioDecider.decision(_environment);
        switch (_matsScenarioDecision) {
            case REGULAR:
                provider = _regularConnectionFactoryProvider;
                break;
            case LOCALHOST:
                provider = _localhostConnectionFactoryProvider;
                break;
            case LOCALVM:
                provider = _localVmConnectionFactoryProvider;
                break;
            default:
                throw new AssertionError("Unknown MatsScenario enum value [" + _matsScenarioDecision + "]!");
        }
        log.info(LOG_PREFIX + "Creating ConnectionFactory decided by MatsScenario [" + _matsScenarioDecision
                + "] from provider [" + provider + "].");

        // :: Actually get the ConnectionFactory.

        try {
            _targetConnectionFactory = provider.get(_environment);
        }
        catch (Exception e) {
            throw new CouldNotGetConnectionFactoryFromProviderException("Got problems when getting the"
                    + " ConnectionFactory from ConnectionFactoryProvider [" + provider + "] from Scenario ["
                    + _matsScenarioDecision
                    + "]", e);
        }

        // :: If the provided ConnectionFactory is "start-stoppable", then we must start it

        // ?: Is it a start-stoppable ConnectionFactory?
        if (_targetConnectionFactory instanceof ConnectionFactoryWithStartStopWrapper) {
            // -> Yes, start-stoppable, so start it now (and set any returned target ConnectionFactory..)
            log.info(LOG_PREFIX + "The provided ConnectionFactory from Scenario [" + _matsScenarioDecision
                    + "] implements " + ConnectionFactoryWithStartStopWrapper.class.getSimpleName()
                    + ", so invoking start(..) on it.");
            ConnectionFactoryWithStartStopWrapper startStopWrapper = (ConnectionFactoryWithStartStopWrapper) _targetConnectionFactory;
            try {
                ConnectionFactory targetConnectionFactory = startStopWrapper.start(_beanName);
                // ?: If the return value is non-null, we'll set it.
                if (targetConnectionFactory != null) {
                    // -> Yes, non-null, so set it per contract.
                    startStopWrapper.setWrappee(targetConnectionFactory);
                }
            }
            catch (Exception e) {
                throw new CouldNotStartConnectionFactoryWithStartStopWrapperException("Got problems starting the"
                        + " ConnectionFactoryWithStartStopWrapper [" + startStopWrapper + "] from Scenario ["
                        + _matsScenarioDecision
                        + "].", e);
            }
        }
    }

    protected static class CouldNotGetConnectionFactoryFromProviderException extends RuntimeException {
        public CouldNotGetConnectionFactoryFromProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected static class CouldNotStartConnectionFactoryWithStartStopWrapperException extends RuntimeException {
        public CouldNotStartConnectionFactoryWithStartStopWrapperException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected static class CouldNotStopConnectionFactoryWithStartStopWrapperException extends RuntimeException {
        public CouldNotStopConnectionFactoryWithStartStopWrapperException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ===== Implementation of SmartLifeCycle

    @Override
    public int getPhase() {
        // Returning a quite low number to be STARTED early, and STOPPED late.
        return -2_000_000;
    }

    private boolean _started;

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        log.info(LOG_PREFIX + "SmartLifeCycle.start on [" + _beanName
                + "]: Creating Target ConnectionFactory based on ScenarioDecider [" + _scenarioDecider + "].");
        createTargetConnectionFactoryBasedOnScenarioDecider();
        _started = true;
    }

    @Override
    public boolean isRunning() {
        return _started;
    }

    @Override
    public void stop() {
        _started = false;
        if ((_targetConnectionFactory != null)
                && (_targetConnectionFactory instanceof ConnectionFactoryWithStartStopWrapper)) {
            try {
                log.info(LOG_PREFIX + "  \\- The current target ConnectionFactory implements "
                        + ConnectionFactoryWithStartStopWrapper.class.getSimpleName()
                        + ", so invoking stop(..) on it.");
                ((ConnectionFactoryWithStartStopWrapper) _targetConnectionFactory).stop();
            }
            catch (Exception e) {
                throw new CouldNotStopConnectionFactoryWithStartStopWrapperException("Got problems stopping the"
                        + " current target ConnectionFactoryWithStartStopWrapper [" + _targetConnectionFactory + "].",
                        e);
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        log.info(LOG_PREFIX + "SmartLifeCycle.stop(callback) on [" + _beanName
                + "].");
        stop();
        callback.run();
    }
}