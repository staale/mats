package com.stolsvik.mats.test.abstractunit;

import java.util.concurrent.CopyOnWriteArrayList;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.MatsEndpoint.MatsRefuseMessageException;
import com.stolsvik.mats.MatsEndpoint.ProcessTerminatorLambda;
import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.MatsInitiator;
import com.stolsvik.mats.impl.jms.JmsMatsFactory;
import com.stolsvik.mats.impl.jms.JmsMatsJmsSessionHandler;
import com.stolsvik.mats.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import com.stolsvik.mats.intercept.logging.MatsMetricsLoggingInterceptor;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.MatsTrace;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.test.MatsTestMqInterface;
import com.stolsvik.mats.test.TestH2DataSource;
import com.stolsvik.mats.util.MatsFuturizer;
import com.stolsvik.mats.util_activemq.MatsLocalVmActiveMq;

/**
 * Base class containing common code for Rule_Mats and Extension_Mats located in the following modules:
 * <ul>
 * <li>mats-test-junit</li>
 * <li>mats-test-jupiter</li>
 * </ul>
 * This class sets up an in-vm Active MQ broker through the use of {@link MatsLocalVmActiveMq} which is again utilized
 * to create the {@link MatsFactory} which can be utilized to create unit tests which rely on testing functionality
 * utilizing MATS.
 * <p>
 * The setup and creation of these objects are located in the {@link #beforeAll()} method, this method should be called
 * through the use JUnit and Jupiters life
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 * @author Kevin Mc Tiernan, 2020-10-18, kmctiernan@gmail.com
 */
public abstract class AbstractMatsTest<Z> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractMatsTest.class);

    protected MatsSerializer<Z> _matsSerializer;
    protected DataSource _dataSource;

    protected MatsLocalVmActiveMq _matsLocalVmActiveMq;
    protected JmsMatsFactory<Z> _matsFactory;
    protected CopyOnWriteArrayList<MatsFactory> _createdMatsFactories = new CopyOnWriteArrayList<>();

    // :: Lazy init:
    protected MatsInitiator _matsInitiator;
    protected MatsTestLatch _matsTestLatch;
    protected MatsFuturizer _matsFuturizer;
    protected MatsTestMqInterface _matsTestMqInterface;

    protected AbstractMatsTest(MatsSerializer<Z> matsSerializer) {
        _matsSerializer = matsSerializer;
    }

    protected AbstractMatsTest(MatsSerializer<Z> matsSerializer, DataSource dataSource) {
        _matsSerializer = matsSerializer;
        _dataSource = dataSource;
    }

    /**
     * Creates an in-vm ActiveMQ Broker which again is utilized to create a {@link JmsMatsFactory}.
     * <p>
     * This method should be called as a result of the following life cycle events for either JUnit or Jupiter:
     * <ul>
     * <li>BeforeClass - JUnit - static ClassRule</li>
     * <li>BeforeAllCallback - Jupiter - static Extension</li>
     * </ul>
     */
    public void beforeAll() {
        log.debug("+++ JUnit/Jupiter +++ BEFORE_CLASS on ClassRule/Extension '" + id(getClass()) + "', JMS and MATS:");

        // ::: ActiveMQ BrokerService and ConnectionFactory
        // ==================================================

        _matsLocalVmActiveMq = MatsLocalVmActiveMq.createRandomInVmActiveMq();

        // ::: MatsFactory
        // ==================================================

        log.debug("Setting up JmsMatsFactory.");
        // Allow for override in specialization classes, in particular the one with DB.
        _matsFactory = createMatsFactory();
        log.debug("--- JUnit/Jupiter --- BEFORE_CLASS done on ClassRule/Extension '" + id(getClass())
                + "', JMS and MATS.");
    }

    /**
     * Tear down method, stopping all {@link MatsFactory} created during a test setup and close the AMQ broker.
     * <p>
     * This method should be called as a result of the following life cycle events for either JUnit or Jupiter:
     * <ul>
     * <li>AfterClass - JUnit - static ClassRule</li>
     * <li>AfterAllCallback - Jupiter - static Extension</li>
     * </ul>
     */
    public void afterAll() {
        log.info("+++ JUnit/Jupiter +++ AFTER_CLASS on ClassRule/Extension '" + id(getClass()) + "':");

        // :: Close the MatsFuturizer if we've made it
        if (_matsFuturizer != null) {
            _matsFuturizer.close();
        }

        // :: Close all MatsFactories (thereby closing all endpoints and initiators, and thus their connections).
        for (MatsFactory createdMatsFactory : _createdMatsFactories) {
            createdMatsFactory.stop(30_000);
        }

        // :: Close the AMQ Broker
        _matsLocalVmActiveMq.close();

        // :: If the DataSource is a TestH2DataSource, then close that
        if (_dataSource instanceof TestH2DataSource) {
            ((TestH2DataSource) _dataSource).close();
        }

        // :: Clean up all possibly created "sub pieces" of this. The reason is that if this is put as a @ClassRule
        // on a super class of multiple tests, then this instance will be re-used upon the next test class's run
        // (the next class that extends the "base test class" that contains the static @ClassRule).
        // NOTE: NOT doing that for H2 DataSource, as we cannot recreate that here, and instead rely on it re-starting.
        _matsLocalVmActiveMq = null;
        _matsFactory = null;
        _matsInitiator = null;
        _matsTestLatch = null;
        _matsFuturizer = null;
        _matsTestMqInterface = null;

        log.info("--- JUnit/Jupiter --- AFTER_CLASS done on ClassRule/Extension '" + id(getClass()) + "' DONE.");
    }

    /**
     * @return the default {@link MatsInitiator} from this rule's {@link MatsFactory}.
     */
    public synchronized MatsInitiator getMatsInitiator() {
        if (_matsInitiator == null) {
            _matsInitiator = getMatsFactory().getDefaultInitiator();
        }
        return _matsInitiator;
    }

    /**
     * @return a singleton {@link MatsTestLatch}
     */
    public synchronized MatsTestLatch getMatsTestLatch() {
        if (_matsTestLatch == null) {
            _matsTestLatch = new MatsTestLatch();
        }
        return _matsTestLatch;
    }

    /**
     * @return a convenience singleton {@link MatsFuturizer} created with this rule's {@link MatsFactory}.
     */
    public synchronized MatsFuturizer getMatsFuturizer() {
        if (_matsFuturizer == null) {
            _matsFuturizer = MatsFuturizer.createMatsFuturizer(_matsFactory, "UnitTestingFuturizer");
        }
        return _matsFuturizer;
    }

    /**
     * @return a {@link MatsTestMqInterface} instance for getting DLQs (and hopefully other snacks at a later time).
     */
    public synchronized MatsTestMqInterface getMatsTestMqInterface() {
        if (_matsTestMqInterface == null) {
            _matsTestMqInterface = MatsTestMqInterface
                    .create(_matsLocalVmActiveMq.getConnectionFactory(),
                            _matsSerializer,
                            _matsFactory.getFactoryConfig().getMatsDestinationPrefix(),
                            _matsFactory.getFactoryConfig().getMatsTraceKey());

        }
        return _matsTestMqInterface;
    }

    /**
     * @return the JMS ConnectionFactory that this JUnit Rule sets up.
     */
    public ConnectionFactory getJmsConnectionFactory() {
        return _matsLocalVmActiveMq.getConnectionFactory();
    }

    /**
     * Waits a couple of seconds for a message to appear on the Dead Letter Queue for the provided endpointId - useful
     * if the test is designed to fail a stage (i.e. that a stage raises some {@link RuntimeException}, or the special
     * {@link MatsRefuseMessageException}).
     *
     * @param endpointId
     *            the endpoint which is expected to generate a DLQ message.
     * @return the {@link MatsTrace} of the DLQ'ed message.
     * @deprecated use the {@link MatsTestMqInterface}, as provided by the {@link #getMatsTestMqInterface()} method.
     */
    @Deprecated
    public MatsTrace<Z> getDlqMessage(String endpointId) {
        return _matsLocalVmActiveMq.getDlqMessage(_matsSerializer,
                _matsFactory.getFactoryConfig().getMatsDestinationPrefix(),
                _matsFactory.getFactoryConfig().getMatsTraceKey(),
                endpointId);
    }

    /**
     * @return the {@link MatsFactory} that this JUnit Rule sets up.
     */
    public MatsFactory getMatsFactory() {
        return _matsFactory;
    }

    public JmsMatsFactory<Z> getJmsMatsFactory() {
        return _matsFactory;
    }

    /**
     * <b>You should probably NOT use this method, but instead the {@link #getMatsFactory()}!</b>.
     * <p />
     * This method is public for a single reason: If you need a <i>new, separate</i> {@link MatsFactory} using the same
     * JMS ConnectionFactory as the one provided by {@link #getMatsFactory()}. The only currently known reason for this
     * is if you want to register two endpoints with the same endpointId, and the only reason for this again is to test
     * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)
     * subscriptionTerminators}.
     *
     * @return a <i>new, separate</i> {@link MatsFactory} in addition to the one provided by {@link #getMatsFactory()}.
     */
    public JmsMatsFactory<Z> createMatsFactory() {
        JmsMatsJmsSessionHandler sessionHandler = JmsMatsJmsSessionHandler_Pooling
                .create(_matsLocalVmActiveMq.getConnectionFactory());

        JmsMatsFactory<Z> matsFactory;
        if (_dataSource == null) {
            // -> No DataSource
            matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(this.getClass().getSimpleName(),
                    "*testing*", sessionHandler, _matsSerializer);
        }
        else {
            // -> We have DataSource
            // Create the JMS and JDBC TransactionManager-backed JMS MatsFactory.
            matsFactory = JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions(this.getClass().getSimpleName(),
                    "*testing*", sessionHandler, _dataSource, _matsSerializer);

        }

        // Set name
        matsFactory.getFactoryConfig().setName(this.getClass().getSimpleName());

        /*
         * For most test scenarios, it really makes little meaning to have a concurrency of more than 1 - unless
         * explicitly testing Mats' handling of concurrency. However, due to some testing scenarios might come to rely
         * on such sequential processing, we set it to 2, to try to weed out such dependencies - hopefully tests will
         * (at least occasionally) fail by two consumers each getting a message and thus processing them concurrently.
         */
        matsFactory.getFactoryConfig().setConcurrency(2);
        // Add it to the list of created MatsFactories.
        _createdMatsFactories.add(matsFactory);
        return matsFactory;
    }

    /**
     * @return the DataSource if this Rule/Extension was created with one, throws {@link IllegalStateException}
     *         otherwise.
     * @throws IllegalStateException
     *             if this Rule/Extension wasn't created with a DataSource.
     */
    public DataSource getDataSource() {
        if (_dataSource == null) {
            throw new IllegalStateException("This " + this.getClass().getSimpleName()
                    + " was not created with a DataSource, use the 'createWithDb' factory methods.");
        }
        return _dataSource;
    }

    /**
     * Loops through all the {@link MatsFactory}'s contained within the {@link #_createdMatsFactories}, this ensures
     * that all factories are "clean".
     * <p />
     * You may want to utilize this if you have multiple tests in a class, and set up the Endpoints using a @Before type
     * annotation in the test, as opposed to @BeforeClass. This because otherwise you will on the second test try to
     * create the endpoints one more time, and they will already exist, thus you'll get an Exception from the
     * MatsFactory.
     */
    public void cleanMatsFactory() {
        // :: Since removing all endpoints will destroy the MatsFuturizer if it is made, we'll first close that
        synchronized (this) {
            // ?: Have we made the MatsFuturizer?
            if (_matsFuturizer != null) {
                // -> Yes, so close and null it.
                _matsFuturizer.close();
                _matsFuturizer = null;
            }
        }
        // :: Loop through all created MATS factories and remove the endpoints
        for (MatsFactory createdMatsFactory : _createdMatsFactories) {
            createdMatsFactory.getEndpoints()
                    .forEach(matsEndpoint -> matsEndpoint.remove(30_000));
        }
    }

    protected String id(Class<?> clazz) {
        return clazz.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this));
    }
}
