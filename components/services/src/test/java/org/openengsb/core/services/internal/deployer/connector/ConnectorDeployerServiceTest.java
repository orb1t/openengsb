/**
 * Licensed to the Austrian Association for Software Tool Integration (AASTI)
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The AASTI licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openengsb.core.services.internal.deployer.connector;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;
import static org.junit.matchers.JUnitMatchers.hasItems;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.shiro.SecurityUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openengsb.core.api.Connector;
import org.openengsb.core.api.ConnectorInstanceFactory;
import org.openengsb.core.api.ConnectorManager;
import org.openengsb.core.api.WiringService;
import org.openengsb.core.api.model.ConnectorDescription;
import org.openengsb.core.api.persistence.ConfigPersistenceService;
import org.openengsb.core.api.security.Credentials;
import org.openengsb.core.api.security.model.Authentication;
import org.openengsb.core.common.SecurityAttributeProviderImpl;
import org.openengsb.core.ekb.api.TransformationEngine;
import org.openengsb.core.persistence.internal.DefaultConfigPersistenceService;
import org.openengsb.core.services.OpenEngSBShiroAuthenticator;
import org.openengsb.core.services.internal.ConnectorManagerImpl;
import org.openengsb.core.services.internal.ConnectorRegistrationManager;
import org.openengsb.core.services.internal.DefaultWiringService;
import org.openengsb.core.services.internal.ForwardMethodInterceptor;
import org.openengsb.core.services.internal.security.OpenEngSBSecurityManager;
import org.openengsb.core.test.AbstractOsgiMockServiceTest;
import org.openengsb.core.test.DummyConfigPersistenceService;
import org.openengsb.core.test.NullDomain;
import org.openengsb.core.test.NullDomainImpl;
import org.openengsb.core.util.DefaultOsgiUtilsService;
import org.openengsb.core.util.MergeException;
import org.openengsb.domain.authentication.AuthenticationDomain;
import org.openengsb.domain.authentication.AuthenticationException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import com.google.common.collect.ImmutableMap;

public class ConnectorDeployerServiceTest extends AbstractOsgiMockServiceTest {

    private ConnectorDeployerService connectorDeployerService;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private NullDomainImpl createdService;
    private ConnectorManager serviceManager;
    private static final String TEST_FILE_NAME = "test-connector-instance.connector";
    private String testConnectorData = "domainType=mydomain\n"
            + "connectorType=aconnector\n"
            + "attribute.a-key=a-value";
    private ConnectorInstanceFactory factory;
    private String testConnectorId;
    private DefaultConfigPersistenceService configPersistence;

    private DefaultOsgiUtilsService serviceUtils;
    private WiringService wiringService;

    @Before
    public void setUp() throws Exception {
        connectorDeployerService = new ConnectorDeployerService();
        connectorDeployerService.setBundleContext(bundleContext);

        setupPersistence();
        createServiceManagerMock();

        connectorDeployerService.setServiceManager(serviceManager);

        factory = mock(ConnectorInstanceFactory.class);
        createdService = mock(NullDomainImpl.class);
        when(factory.createNewInstance(anyString())).thenReturn(createdService);
        when(factory.applyAttributes(any(Connector.class), anyMap())).thenAnswer(new Answer<Connector>() {
            @Override
            public Connector answer(InvocationOnMock invocation) throws Throwable {
                return (Connector) invocation.getArguments()[0];
            }
        });

        setupSecurityManager();

        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(org.openengsb.core.api.Constants.CONNECTOR_KEY, "aconnector");
        props.put(org.openengsb.core.api.Constants.DOMAIN_KEY, "mydomain");
        registerService(factory, props, ConnectorInstanceFactory.class);

        createDomainProviderMock(NullDomain.class, "mydomain");

        DefaultWiringService defaultWiringService = new DefaultWiringService();
        defaultWiringService.setBundleContext(bundleContext);
        registerServiceViaId(defaultWiringService, "wiring", WiringService.class);
        testConnectorId = "test-connector-instance";
        wiringService = defaultWiringService;
    }

    private void setupSecurityManager() throws AuthenticationException {
        AuthenticationDomain authManager = mock(AuthenticationDomain.class);
        when(authManager.authenticate(anyString(), any(Credentials.class))).thenAnswer(new Answer<Authentication>() {
            @Override
            public Authentication answer(InvocationOnMock invocation) throws Throwable {
                String username = (String) invocation.getArguments()[0];
                Credentials credentials = (Credentials) invocation.getArguments()[1];
                return new Authentication(username, credentials);
            }
        });
        OpenEngSBSecurityManager openEngSBSecurityManager = new OpenEngSBSecurityManager();
        OpenEngSBShiroAuthenticator authenticator = new OpenEngSBShiroAuthenticator();
        authenticator.setAuthenticator(authManager);
        openEngSBSecurityManager.setAuthenticator(authenticator);
        SecurityUtils.setSecurityManager(openEngSBSecurityManager);
    }

    private void setupPersistence() {
        DummyConfigPersistenceService<ConnectorDescription> backend =
            new DummyConfigPersistenceService<ConnectorDescription>();
        configPersistence = new DefaultConfigPersistenceService(backend);
        Dictionary<String, Object> props2 = new Hashtable<String, Object>();
        props2.put("configuration.id", org.openengsb.core.api.Constants.CONFIG_CONNECTOR);
        registerService(configPersistence, props2, ConfigPersistenceService.class);
    }

    private ConnectorManagerImpl createServiceManagerMock() {
        ConnectorManagerImpl serviceManagerImpl = new ConnectorManagerImpl();
        ConnectorRegistrationManager registrationManager = new ConnectorRegistrationManager(bundleContext,
                mock(TransformationEngine.class), new ForwardMethodInterceptor(), new SecurityAttributeProviderImpl());
        serviceUtils = new DefaultOsgiUtilsService(bundleContext);
        serviceManagerImpl.setRegistrationManager(registrationManager);
        serviceManagerImpl.setConfigPersistence(configPersistence);
        serviceManager = serviceManagerImpl;
        return serviceManagerImpl;
    }

    @Test
    public void testConnectorFiles_shouldBeHandledByDeployer() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        assertThat(connectorDeployerService.canHandle(connectorFile), is(true));
    }

    @Test
    public void testUnknownFiles_shouldNotBeHandledByDeplyoer() throws Exception {
        File otherFile = temporaryFolder.newFile("other.txt");
        assertThat(connectorDeployerService.canHandle(otherFile), is(false));
    }

    @Test
    public void testConnectorFile_shouldBeInstalled() throws Exception {
        File connectorFile = createSampleConnectorFile();
        connectorDeployerService.install(connectorFile);

        NullDomain domainEndpoints = wiringService.getDomainEndpoint(NullDomain.class, "*");
        domainEndpoints.nullMethod(42);
        verify(createdService).nullMethod(42);
    }

    @Test
    public void testConnectorFileWithArrays_shouldBeInstalled() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeStringToFile(connectorFile, testConnectorData + "\nproperty.bla=foo,bar");
        connectorDeployerService.install(connectorFile);

        serviceUtils.getService("(bla=foo)", 100L);
        serviceUtils.getService("(bla=bar)", 100L);
    }

    @Test
    public void testConnectorFileWithRanking_shouldBeInstalledWithNumericRanking() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils
            .writeStringToFile(connectorFile, testConnectorData + "\nproperty.service.ranking=2\nproperty.bla=foo");
        connectorDeployerService.install(connectorFile);

        serviceUtils.getService("(bla=foo)", 100L);

        ServiceReference serviceReference = bundleContext.getServiceReferences((String) null, "(bla=foo)")[0];
        Integer ranking = (Integer) serviceReference.getProperty(Constants.SERVICE_RANKING);
        assertThat(ranking, notNullValue());
    }

    @Test
    public void testConnectorFileWithArraysAndTrailingSpace_shouldBeInstalled() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeStringToFile(connectorFile, testConnectorData + "\nproperty.bla=foo , bar");
        connectorDeployerService.install(connectorFile);

        serviceUtils.getService("(bla=foo)", 100L);
        serviceUtils.getService("(bla=bar)", 100L);
    }

    private File createSampleConnectorFile() throws IOException {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeStringToFile(connectorFile, testConnectorData);
        return connectorFile;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUpdateConnectorFile_shouldBeUpdated() throws Exception {
        File connectorFile = createSampleConnectorFile();
        connectorDeployerService.install(connectorFile);
        FileUtils.writeStringToFile(connectorFile, testConnectorData + "\nattribute.another=foo");
        connectorDeployerService.update(connectorFile);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> attributeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(factory, times(2)).applyAttributes(any(Connector.class), attributeCaptor.capture());
        String value = (String) attributeCaptor.getAllValues().get(1).get("another");
        assertThat(value, is("foo"));
    }

    @Test
    public void testRootService_shouldHaveLowerRanking() throws Exception {
        File connectorFile = new File(temporaryFolder.getRoot() + "/etc/mydomain+aconnector+myroot.connector");
        FileUtils.touch(connectorFile);
        FileUtils.writeStringToFile(connectorFile, testConnectorData);

        connectorDeployerService.install(connectorFile);

        ServiceReference reference = bundleContext.getServiceReferences(NullDomain.class.getName(), "")[0];
        Integer ranking = (Integer) reference.getProperty(Constants.SERVICE_RANKING);
        assertThat(ranking, lessThan(0));
    }

    @Test
    public void testNormalService_shouldHaveNoRankingAdded() throws Exception {
        File connectorFile = new File(temporaryFolder.getRoot() + "/config/mydomain+aconnector+myroot.connector");
        FileUtils.touch(connectorFile);
        FileUtils.writeStringToFile(connectorFile, testConnectorData);

        connectorDeployerService.install(connectorFile);

        ServiceReference reference = bundleContext.getServiceReferences(NullDomain.class.getName(), "")[0];

        assertThat(reference.getProperty(Constants.SERVICE_RANKING), nullValue());
    }

    @Test
    public void testOverridenRanking_shouldNotBeAltered() throws Exception {
        File connectorFile = new File(temporaryFolder.getRoot() + "/etc/mydomain+aconnector+myroot.connector");
        FileUtils.touch(connectorFile);
        FileUtils.writeStringToFile(connectorFile, testConnectorData + "\n"
                + "property." + Constants.SERVICE_RANKING + "=24");

        connectorDeployerService.install(connectorFile);

        ServiceReference ref =
            bundleContext.getServiceReferences(NullDomain.class.getName(),
                String.format("(%s=%s)", Constants.SERVICE_RANKING, 24))[0];
        assertThat(ref, not(nullValue()));
    }

    @Test
    public void testUninstallService_shouldRemoveFromRegistry() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeStringToFile(connectorFile, testConnectorData);

        connectorDeployerService.install(connectorFile);
        connectorFile.delete();
        connectorDeployerService.uninstall(connectorFile);

        ServiceReference[] reference = bundleContext.getServiceReferences(NullDomain.class.getName(), "");

        assertThat(reference, nullValue());
    }

    @Test
    public void testUpdateService_shouldKeepInternalProperties() throws Exception {
        File connectorFile = createSampleConnectorFile();
        connectorDeployerService.install(connectorFile);
        FileUtils.writeStringToFile(connectorFile, testConnectorData + "\nproperty.another=foo");
        connectorDeployerService.update(connectorFile);

        ServiceReference[] serviceReferences =
            bundleContext.getServiceReferences(NullDomain.class.getName(), "(another=foo)");
        assertThat(serviceReferences, not(nullValue()));
        ServiceReference reference = serviceReferences[0];
        assertThat((String) reference.getProperty(org.openengsb.core.api.Constants.CONNECTOR_KEY), is("aconnector"));
    }

    @Test
    @Ignore
    public void testInstallService_shouldNotOverwriteExistingService() throws Exception {
        Map<String, Object> properties = new Hashtable<String, Object>();
        properties.put("foo", "bar");
        ConnectorDescription connectorDescription =
            new ConnectorDescription("mydomain", "aconnector", null, properties);
        serviceManager.createWithId(testConnectorId, connectorDescription);

        File connectorFile = createSampleConnectorFile();
        connectorDeployerService.install(connectorFile);

        ServiceReference[] references = bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=bar)");
        assertThat("old service is not there anymore", references, not(nullValue()));
    }

    @Test
    public void testUpdateService_shouldNotOverrideOtherwiseModifedProperties() throws Exception {
        File connectorFile = createSampleConnectorFile();
        connectorDeployerService.install(connectorFile);
        ConnectorDescription attributeValues = serviceManager.getAttributeValues(testConnectorId);
        Map<String, Object> propertyValues = attributeValues.getProperties();
        propertyValues.put("foo", "bar");
        ConnectorDescription newDesc = new ConnectorDescription("mydomain", "aconnector",
            attributeValues.getAttributes(), propertyValues);
        serviceManager.update(testConnectorId, newDesc);

        FileUtils.writeStringToFile(connectorFile, testConnectorData + "\nproperty.foo=notbar");
        try {
            connectorDeployerService.update(connectorFile);
        } catch (MergeException e) {
            // expected
        }
        ServiceReference[] serviceReferences2 =
            bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=bar)");
        assertThat(serviceReferences2, not(nullValue()));
    }

    @Test
    public void testUpdateServiceViaPersistence_shouldNotOverwriteProperties() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=y"));
        connectorDeployerService.install(connectorFile);
        ConnectorDescription desc =
            serviceManager.getAttributeValues(testConnectorId);
        desc.getProperties().put("foo", "42");
        serviceManager.update(testConnectorId, desc);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=y", "property.x=y"));
        connectorDeployerService.update(connectorFile);
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=42)"), not(nullValue()));
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(x=y)"), not(nullValue()));
    }

    @Test
    public void testUpdateAttributeViaPersistence_shouldNotOverwrite() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=y"));
        connectorDeployerService.install(connectorFile);
        ConnectorDescription desc = serviceManager.getAttributeValues(testConnectorId);
        ConnectorDescription newDesc = new ConnectorDescription("mydomain", "aconnector",
            ImmutableMap.of("x", "z"), desc.getProperties());
        serviceManager.update(testConnectorId, newDesc);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=y", "property.x=y"));
        connectorDeployerService.update(connectorFile);
        ConnectorDescription attributeValues = serviceManager.getAttributeValues(testConnectorId);
        assertThat(attributeValues.getAttributes().get("x"), is("z"));
    }

    @Test
    public void testRemovePropertyFromConfig_shouldRemoveProperty() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=y"));
        connectorDeployerService.install(connectorFile);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "attribute.x=y", "property.x=y"));
        connectorDeployerService.update(connectorFile);
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=bar)"), nullValue());
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(x=y)"), not(nullValue()));
    }

    @Test
    public void testModifyAttributeInBothPlaces_shouldThrowException() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=original-file-value"));
        connectorDeployerService.install(connectorFile);
        String id = testConnectorId;
        ConnectorDescription desc = serviceManager.getAttributeValues(id);

        Map<String, String> attributes = ImmutableMap.of("x", "new-persistence-value");
        ConnectorDescription newDesc = new ConnectorDescription("mydomain", "aconnector",
            attributes, desc.getProperties());

        serviceManager.update(id, newDesc);
        FileUtils.writeLines(connectorFile,
            Arrays.asList("property.foo=bar", "attribute.x=new-value-value"));
        try {
            connectorDeployerService.update(connectorFile);
            fail("update should have failed, because of a merge-conflict");
        } catch (MergeException e) {
            assertThat(serviceManager.getAttributeValues(id).getAttributes().get("x"), is("new-persistence-value"));
        }
    }

    @Test
    public void testRemovePropertyOnBothEnds_shouldStayRemovedWithoutError() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=original-file-value"));
        connectorDeployerService.install(connectorFile);
        ConnectorDescription desc = serviceManager.getAttributeValues(testConnectorId);
        Map<String, Object> properties = new Hashtable<String, Object>();
        ConnectorDescription newDesc = new ConnectorDescription("mydomain", "aconnector",
            desc.getAttributes(), properties);

        serviceManager.update(testConnectorId, newDesc);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "attribute.x=original-file-value"));

        connectorDeployerService.update(connectorFile);
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=bar)"), nullValue());
    }

    @Test
    public void testUpdateAttributeViaFileTwice_shouldUpdateTwice() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=43", "attribute.x=y"));
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=42", "attribute.x=y"));
        connectorDeployerService.install(connectorFile);
        connectorDeployerService.update(connectorFile);
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=43)"), nullValue());
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=42)"), not(nullValue()));
    }

    @Test
    public void testUpdateFailure_shouldCreateBackupFile() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=original-file-value"));
        connectorDeployerService.install(connectorFile);
        String id = testConnectorId;
        ConnectorDescription desc = serviceManager.getAttributeValues(id);

        Map<String, String> attributes = ImmutableMap.of("x", "new-persistence-value");
        ConnectorDescription newDesc = new ConnectorDescription("mydomain", "aconnector",
            attributes, desc.getProperties());

        serviceManager.update(id, newDesc);
        FileUtils.writeLines(connectorFile,
            Arrays.asList("property.foo=bar", "attribute.x=new-value-value"));
        try {
            connectorDeployerService.update(connectorFile);
            fail("update should have failed, because of a merge-conflict");
        } catch (MergeException e) {
            File backupFile = new File(temporaryFolder.getRoot(), TEST_FILE_NAME + "_001");
            assertThat("no backup-file was created", backupFile.exists(), is(true));
        }
    }

    @Test
    @Ignore
    public void testInstallFailure_shouldLeaveFileAsIs() throws Exception {
        Map<String, Object> properties = new Hashtable<String, Object>();
        properties.put("foo", "bar");
        ConnectorDescription connectorDescription = new ConnectorDescription("mydomain", "aconnector",
            new HashMap<String, String>(), properties);
        serviceManager.createWithId(testConnectorId, connectorDescription);

        File connectorFile = createSampleConnectorFile();
        connectorDeployerService.install(connectorFile);

        ServiceReference[] references = bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=bar)");
        assertThat(connectorFile.exists(), is(true));
        assertThat("old service is not there anymore", references, not(nullValue()));
    }

    @Test
    public void testUpdateFailure_shouldReplaceWithOldConfigFile() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=original-file-value"));
        connectorDeployerService.install(connectorFile);
        String id = testConnectorId;
        ConnectorDescription desc = serviceManager.getAttributeValues(id);

        Map<String, String> attributes = ImmutableMap.of("x", "new-persistence-value");
        ConnectorDescription newDesc = new ConnectorDescription("mydomain", "aconnector",
            attributes, desc.getProperties());

        serviceManager.update(id, newDesc);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=new-value-value"));
        try {
            connectorDeployerService.update(connectorFile);
            fail("update should have failed, because of a merge-conflict");
        } catch (MergeException e) {
            List<String> lines = FileUtils.readLines(connectorFile);
            assertThat(lines, hasItems("property.foo=bar", "attribute.x=original-file-value"));
        }
    }

    @Test
    public void testUpdateTwice_shouldUpdateCachedVersion() throws Exception {
        File connectorFile = temporaryFolder.newFile(TEST_FILE_NAME);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar", "attribute.x=original-file-value"));
        connectorDeployerService.install(connectorFile);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar2", "attribute.x=original-file-value"));
        connectorDeployerService.update(connectorFile);
        FileUtils.writeLines(connectorFile, Arrays.asList("domainType=mydomain", "connectorType=aconnector",
            "property.foo=bar3", "attribute.x=original-file-value"));
        connectorDeployerService.update(connectorFile);
        assertThat(bundleContext.getServiceReferences(NullDomain.class.getName(), "(foo=bar3)"), not(nullValue()));
    }
}
