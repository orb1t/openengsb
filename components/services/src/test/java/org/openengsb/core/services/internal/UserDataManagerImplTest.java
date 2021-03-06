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

package org.openengsb.core.services.internal;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import javax.persistence.EntityManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.openengsb.core.api.security.model.Permission;
import org.openengsb.core.api.security.service.UserDataManager;
import org.openengsb.core.api.security.service.UserNotFoundException;
import org.openengsb.core.services.internal.security.EntryUtils;
import org.openengsb.core.services.internal.security.UserDataManagerImpl;
import org.openengsb.core.services.internal.security.model.UserData;
import org.openengsb.core.test.AbstractOsgiMockServiceTest;
import org.openengsb.core.util.DefaultOsgiUtilsService;
import org.openengsb.domain.authorization.AuthorizationDomain.Access;
import org.openengsb.labs.delegation.service.ClassProvider;
import org.openengsb.labs.delegation.service.Constants;
import org.openengsb.labs.delegation.service.internal.ClassProviderImpl;
import org.openengsb.labs.jpatest.junit.TestPersistenceUnit;

import com.google.common.base.Objects;
import com.google.common.collect.Sets;

public class UserDataManagerImplTest extends AbstractOsgiMockServiceTest {

    public static class TestPermission implements Permission {
        private String desiredResult;

        public TestPermission() {
        }

        public TestPermission(Access desiredResult) {
            super();
            this.desiredResult = desiredResult.name();
        }

        @Override
        public String describe() {
            return "for testing purposes";
        }

        public String getDesiredResult() {
            return desiredResult;
        }

        public void setDesiredResult(String desiredResult) {
            this.desiredResult = desiredResult;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(desiredResult);
        }

        @Override
        public String toString() {
            return "TestPermission: " + desiredResult;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof UserDataManagerImplTest.TestPermission)) {
                return false;
            }
            final UserDataManagerImplTest.TestPermission other = (UserDataManagerImplTest.TestPermission) obj;
            return Objects.equal(desiredResult, other.desiredResult);
        }

    }

    @Rule
    public final TestPersistenceUnit testPersistenceUnit = new TestPersistenceUnit();

    private EntityManager entityManager;

    private UserDataManager userManager;

    private UserData testUser2;
    private UserData testUser3;

    @Before
    public void setUp() throws Exception {
        EntryUtils.setUtilsService(new DefaultOsgiUtilsService(bundleContext));
        entityManager = testPersistenceUnit.getEntityManager("openengsb-security");
        setupUserManager();
        testUser2 = new UserData("testUser2");
        entityManager.persist(testUser2);
        testUser3 = new UserData("testUser3");
        entityManager.persist(testUser3);
    }

    private void setupUserManager() {
        final UserDataManagerImpl userManager = new UserDataManagerImpl();
        userManager.setEntityManager(entityManager);
        InvocationHandler invocationHandler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                entityManager.getTransaction().begin();
                Object result;
                try {
                    result = method.invoke(userManager, args);
                } catch (InvocationTargetException e) {
                    entityManager.getTransaction().rollback();
                    throw e.getCause();
                }
                entityManager.getTransaction().commit();
                return result;
            }
        };
        this.userManager =
            (UserDataManager) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                new Class<?>[]{ UserDataManager.class }, invocationHandler);

        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(Constants.PROVIDED_CLASSES_KEY, TestPermission.class.getName());
        props.put(Constants.DELEGATION_CONTEXT_KEY, org.openengsb.core.api.Constants.DELEGATION_CONTEXT_PERMISSIONS);
        ClassProvider permissionProvider =
            new ClassProviderImpl(bundle, Sets.newHashSet(TestPermission.class.getName()));
        registerService(permissionProvider, props, ClassProvider.class);

    }

    @Test
    public void testCreateUserWithPassword_shouldHavePassword() throws Exception {
        userManager.createUser("admin2");
        userManager.setUserCredentials("admin2", "password", "testpassword");
        String userCredentials = userManager.getUserCredentials("admin2", "password");
        assertThat(userCredentials, is("testpassword"));
    }

    @Test(expected = UserNotFoundException.class)
    public void testCreateAndDeleteUser_shouldNotFindUser() throws Exception {
        userManager.createUser("admin2");
        userManager.setUserCredentials("admin2", "password", "testpassword");
        userManager.deleteUser("admin2");
        userManager.getUserCredentials("admin2", "password");
    }

    @Test
    public void testStoreUserPermission_shouldBeStored() throws Exception {
        userManager.createUser("admin2");
        Permission permission = new TestPermission(Access.GRANTED);
        userManager.addPermissionToUser("admin2", permission);
        Collection<Permission> userPermissions =
            userManager.getPermissionsForUser("admin2");
        assertThat(userPermissions, hasItem(equalTo(permission)));
    }

    @Test
    public void testStoreUserPermissionAndDeleteAgain_shouldBeDeleted() throws Exception {
        userManager.createUser("admin2");
        Permission permission = new TestPermission(Access.GRANTED);
        userManager.addPermissionToUser("admin2", permission);
        userManager.removePermissionFromUser("admin2", permission);
        Collection<Permission> userPermissions =
            userManager.getPermissionsForUser("admin2");
        assertThat(userPermissions, not(hasItem(equalTo(permission))));
    }

    @Test
    public void testStoreUserPermissionSet_shouldBeStored() throws Exception {
        userManager.createUser("admin2");
        Permission permission = new TestPermission(Access.GRANTED);
        userManager.createPermissionSet("ROLE_ADMIN", permission);
        userManager.addPermissionSetToUser("admin2", "ROLE_ADMIN");
        Collection<String> userPermissions = userManager.getPermissionSetsFromUser("admin2");
        assertThat(userPermissions, hasItem("ROLE_ADMIN"));
    }

    @Test
    public void testStoreUserPermissionSet_shouldGrantAllPermissions() throws Exception {
        userManager.createUser("admin2");
        Permission permission = new TestPermission(Access.GRANTED);
        userManager.createPermissionSet("ROLE_ADMIN", permission);
        userManager.addPermissionSetToUser("admin2", "ROLE_ADMIN");
        Collection<Permission> allUserPermissions = userManager.getAllPermissionsForUser("admin2");
        assertThat(allUserPermissions, hasItem(permission));
    }

    @Test
    public void testAddPermissionSetToSet_shouldBeListedAsMember() throws Exception {
        userManager.createPermissionSet("ROLE_ADMIN");
        userManager.createPermissionSet("ROLE_ROOT");
        userManager.addPermissionSetToPermissionSet("ROLE_ROOT", "ROLE_ADMIN");
        Collection<String> memberPermissionSets = userManager.getPermissionSetsFromPermissionSet("ROLE_ROOT");
        assertThat(memberPermissionSets, hasItem("ROLE_ADMIN"));
    }

    @Test
    public void testAddPermissionSetToSet_shouldGrantAllPermissions() throws Exception {
        userManager.createUser("admin2");
        Permission permission = new TestPermission(Access.GRANTED);
        userManager.createPermissionSet("ROLE_PROJECTMEMBER", permission);
        userManager.createPermissionSet("ROLE_MANAGER");
        userManager.addPermissionSetToPermissionSet("ROLE_MANAGER", "ROLE_PROJECTMEMBER");
        userManager.addPermissionSetToUser("admin2", "ROLE_MANAGER");
        Collection<Permission> allUserPermissions = userManager.getAllPermissionsForUser("admin2");
        assertThat(allUserPermissions, hasItem(permission));
    }

    @Test
    public void testAddSingleUserAttribute_shouldContainAttribute() throws Exception {
        userManager.createUser("admin1");
        userManager.createUser("admin2");
        userManager.setUserAttribute("admin1", "test", 42);
        userManager.setUserAttribute("admin2", "test", 21);

        List<Object> admin1Attribute = userManager.getUserAttribute("admin1", "test");
        assertAttributeValue(admin1Attribute, 42);

        List<Object> admin2Attribute = userManager.getUserAttribute("admin2", "test");
        assertAttributeValue(admin2Attribute, 21);
    }

    @Test
    public void testAddAndRemoveUserAttribute_shouldNotBeSetAnymore() throws Exception {
        userManager.createUser("admin1");
        userManager.setUserAttribute("admin1", "test", 42);
        userManager.removeUserAttribute("admin1", "test");
        assertThat(userManager.getUserAttribute("admin1", "test"), nullValue());
    }

    private void assertAttributeValue(List<Object> actual, Object... expected) {
        assertThat(actual, is(Arrays.asList(expected)));
    }
}
