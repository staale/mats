package com.stolsvik.mats.spring.jms.factories;

import javax.jms.ConnectionFactory;

import org.springframework.core.env.Environment;

import com.stolsvik.mats.spring.jms.factories.ConnectionFactoryScenarioWrapper.ConnectionFactoryProvider;

/**
 * Specifies Spring Profiles (and also which Spring Environment variables) that are relevant for Mats when used with
 * conjunction with {@link JmsSpringConnectionFactoryProducer} and the default configuration of
 * {@link ConfigurableScenarioDecider}. The latter both checks the Spring Profiles for active profiles with the names
 * (or, for {@link MatsProfiles#PROFILE_MATS_TEST}, name prefix) specified here, but also checks the Spring
 * {@link Environment} for the existence of a property of the same names but with the dash ("-") replaced by dot (".").
 * The Spring Environment by default consist of System Properties and Environment Variables.
 * 
 * @author Endre Stølsvik 2019-06-11 23:58 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsProfiles {
    /**
     * If this Spring Profile ("mats-regular") is active, the {@link ConnectionFactory} specified by
     * {@link JmsSpringConnectionFactoryProducer#regularConnectionFactory(ConnectionFactoryProvider)} will be used.
     * Notice that {@link #PROFILE_PRODUCTION "production"} and {@link #PROFILE_STAGING "staging"} for this decision's
     * point of view are pure synonyms to this Profile.
     * <p>
     * Notice: If this Profile (or the synonyms) is active, and any of {@link #PROFILE_MATS_LOCALHOST "mats-localhost"},
     * {@link #PROFILE_MATS_LOCALVM "mats-localvm"} or {@link #PROFILE_MATS_MOCKS "mats-mocks"} (or any profile which
     * starts with "mats-mocks") is also active, the setup refuses to start by throwing an Exception - any combination
     * of "regular", "localhost" and "localvm" is meaningless, while the combination "regular" and any resemblance of
     * mocks ("mats-mocks") is probably disastrous.
     */
    String PROFILE_MATS_REGULAR = "mats-regular";

    /**
     * Common Profile name ("production") that is a synonym to {@link #PROFILE_MATS_REGULAR} wrt. deciding to use the
     * {@link ConnectionFactory} specified by
     * {@link JmsSpringConnectionFactoryProducer#regularConnectionFactory(ConnectionFactoryProvider)}.
     */
    String PROFILE_PRODUCTION = "production";

    /**
     * Common Profile name ("staging") that is a synonym to {@link #PROFILE_MATS_REGULAR} wrt. deciding to use the
     * {@link ConnectionFactory} specified by
     * {@link JmsSpringConnectionFactoryProducer#regularConnectionFactory(ConnectionFactoryProvider)}.
     */
    String PROFILE_STAGING = "staging";

    /**
     * If this Spring Profile ("mats-localhost") is active, the ConnectionFactory specified by
     * {@link JmsSpringConnectionFactoryProducer#localhostConnectionFactory(ConnectionFactoryProvider)} will be used.
     */
    String PROFILE_MATS_LOCALHOST = "mats-localhost";

    /**
     * If this Spring Profile ("mats-localvm") is active, the ConnectionFactory specified by
     * {@link JmsSpringConnectionFactoryProducer#localVmConnectionFactory(ConnectionFactoryProvider)} will be used.
     */
    String PROFILE_MATS_LOCALVM = "mats-localvm";

    /**
     * Profile name ("mats-test") that is a synonym to {@link #PROFILE_MATS_LOCALVM} wrt. deciding to use the
     * {@link ConnectionFactory} specified by
     * {@link JmsSpringConnectionFactoryProducer#localVmConnectionFactory(ConnectionFactoryProvider)}.
     * <p>
     * Notice: The <code>@MatsTestProfile</code>-annotation is meta-annotated with @ActiveProfiles("mats-test").
     */
    String PROFILE_MATS_TEST = "mats-test";

    /**
     * Suggested Profile name ("mats-mocks") (or Profile name prefix if you want to divide the mocks into sets) to use
     * when you mock out project-external collaborator Mats Endpoints for use in the "LocalVM" scenario. Point is that
     * if this e.g. ShoppingCartService needs to talk to the OrderService, you will obviously not have the OrderService
     * Mats Endpoints available when running the ShoppingCartService inside your IDE with an in-vm Local Broker
     * instance. Thus, you mock these out - but annotated with <code>@Profile("mats-mocks")</code> (or maybe
     * <code>@Profile("mats-mocks-orderservice")</code>), so that when running with Profile "production", these mocks do
     * not start consuming messages destined for the production OrderService from the production MQ Broker.
     * <p>
     * A benefit of using this Profile name (or a name that starts with this), is that if by some accident both this
     * profile and Profile {@link #PROFILE_MATS_REGULAR "mats-regular"} or any of its synonyms is enabled at the same
     * time, the service will refuse to start, read javaDoc at {@link #PROFILE_MATS_REGULAR}.
     */
    String PROFILE_MATS_MOCKS = "mats-mocks";
}
