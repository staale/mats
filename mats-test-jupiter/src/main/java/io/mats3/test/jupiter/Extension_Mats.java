package io.mats3.test.jupiter;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.serial.json.MatsSerializer_DefaultJson;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.abstractunit.AbstractMatsTest;

/**
 * Provides a full MATS harness for unit testing by creating {@link JmsMatsFactory MatsFactory} utilizing an in-vm
 * Active MQ broker.
 * <p>
 * By default the {@link #create() rule} will create a {@link MatsSerializer_DefaultJson} which will be the serializer
 * utilized by the created {@link JmsMatsFactory MatsFactory}. Should one want to use a different serializer which
 * serializes to the type of {@link String} then this can be specified using the method {@link #create(MatsSerializer)}.
 * However should one want to specify a serializer which serializes into anything other than {@link String}, then
 * {@link Extension_MatsGeneric} offers this possibility.
 * <p>
 * {@link Extension_Mats} shall be annotated with {@link org.junit.jupiter.api.extension.RegisterExtension} and the
 * instance field shall be static for the Jupiter life cycle to pick up the extension at the correct time.
 * {@link Extension_Mats} can be viewed in the same manner as one would view a ClassRule in JUnit4.
 * <p>
 * Example:
 *
 * <pre>
 *     public class YourTestClass {
 *         &#64;RegisterExtension
 *         public static final Extension_Mats MATS = Extension_Mats.createRule()
 *     }
 * </pre>
 *
 * @author Kevin Mc Tiernan, 2020-10-18, kmctiernan@gmail.com
 * @see Extension_MatsGeneric
 */
public class Extension_Mats extends AbstractMatsTest<String>
        implements BeforeAllCallback, AfterAllCallback {

    protected Extension_Mats(MatsSerializer<String> matsSerializer) {
        super(matsSerializer);
    }

    protected Extension_Mats(MatsSerializer<String> matsSerializer, DataSource dataSource) {
        super(matsSerializer, dataSource);
    }

    /**
     * Creates an {@link Extension_Mats} utilizing the {@link MatsSerializerJson MATS default serializer}
     */
    public static Extension_Mats create() {
        return new Extension_Mats(MatsSerializerJson.create());
    }

    /**
     * Creates an {@link Extension_Mats} utilizing the user provided {@link MatsSerializer} which serializes to the type
     * of String.
     */
    public static Extension_Mats create(MatsSerializer<String> matsSerializer) {
        return new Extension_Mats(matsSerializer);
    }

    public static Extension_Mats createWithDb() {
        return createWithDb(MatsSerializerJson.create());
    }

    public static Extension_Mats createWithDb(MatsSerializer<String> matsSerializer) {
        TestH2DataSource testH2DataSource = TestH2DataSource.createStandard();
        return new Extension_Mats(matsSerializer, testH2DataSource);
    }

    /**
     * Executed by Jupiter before any test method is executed. (Once at the start of the class.)
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        super.beforeAll();
    }

    /**
     * Executed by Jupiter after all test methods have been executed. (Once at the end of the class.)
     */
    @Override
    public void afterAll(ExtensionContext context) {
        super.afterAll();
    }

}
