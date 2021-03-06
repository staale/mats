package com.stolsvik.mats.spring.matsfactoryqualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.inject.Inject;
import javax.jms.ConnectionFactory;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.spring.Dto;
import com.stolsvik.mats.spring.MatsMapping;
import com.stolsvik.mats.spring.Sto;
import com.stolsvik.mats.test.MatsTestLatch;

/**
 * Test where one MatsFactory is annotated with custom qualifier annotation (an annotation annotated with @Qualifier)
 * having an element 'endre', and the @MatsMapping refers to that, and another is annotated with the same custom
 * qualifier, but where the element 'endre' has another value.
 *
 * @author Endre Stølsvik 2019-05-26 00:50 - http://stolsvik.com/, endre@stolsvik.com
 */
public class OkCustomQualifierWithElementTest extends AbstractQualificationTest {
    private final static String ENDPOINT_ID = "QualifierTest";

    @Inject
    @CustomMatsFactoryQualifierWithElement(endre = "factory1")
    private MatsFactory _matsFactory_factory1;

    @Inject
    @CustomMatsFactoryQualifierWithElement(endre = "factory2")
    private MatsFactory _matsFactory_factory2;

    @Inject
    private MatsTestLatch _latch;

    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface CustomMatsFactoryQualifierWithElement {
        String endre();
    }

    @Bean
    @CustomMatsFactoryQualifierWithElement(endre = "factory1")
    protected MatsFactory matsFactory1(@Qualifier("connectionFactory1") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    @Bean
    @CustomMatsFactoryQualifierWithElement(endre = "factory2")
    protected MatsFactory matsFactory2(@Qualifier("connectionFactory2") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    /**
     * Test "Single" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".single")
    @CustomMatsFactoryQualifierWithElement(endre = "factory1")
    protected SpringTestDataTO springMatsSingleEndpoint(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(msg.number * 2, msg.string + ":single");
    }

    /**
     * Test "Terminator" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    @CustomMatsFactoryQualifierWithElement(endre = "factory1")
    protected void springMatsTerminatorEndpoint_MatsFactoryX(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    /**
     * Test "Terminator" endpoint to other factory
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    @CustomMatsFactoryQualifierWithElement(endre = "factory2")
    protected void springMatsTerminatorEndpoint_MatsFactoryY(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    @Test
    public void test() {
        startSpring();
        Assert.assertEquals(2, _matsFactory_factory1.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactory_factory1.getEndpoint(ENDPOINT_ID + ".single")
                .isPresent());
        Assert.assertTrue("Missing endpoint", _matsFactory_factory1.getEndpoint(ENDPOINT_ID + ".terminator")
                .isPresent());

        Assert.assertEquals(1, _matsFactory_factory2.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactory_factory2.getEndpoint(ENDPOINT_ID + ".terminator")
                .isPresent());
        try {
            doStandardTest(_matsFactory_factory1, ENDPOINT_ID);
        }
        finally {
            stopSpring();
        }
    }

}
