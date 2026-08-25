package com.depo.controller;

import com.depo.dto.CreateUserRequest;
import com.depo.dto.UpdateUserRequest;
import com.depo.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerAuthorizationTest {

    @Test
    void userManagementEndpointsRequireAdminRole() throws NoSuchMethodException {
        assertAdminOnly(UserController.class.getDeclaredMethod("getAllUsers"));
        assertAdminOnly(UserController.class.getDeclaredMethod("getUserById", Long.class));
        assertAdminOnly(UserController.class.getDeclaredMethod("createUser", CreateUserRequest.class));
        assertAdminOnly(UserController.class.getDeclaredMethod("updateUser", Long.class, UpdateUserRequest.class));
        assertAdminOnly(UserController.class.getDeclaredMethod("deleteUser", Long.class));
    }

    @Test
    void methodSecurityIsEnabledForControllerAuthorizations() {
        assertThat(SecurityConfig.class.isAnnotationPresent(EnableMethodSecurity.class)).isTrue();
    }

    private void assertAdminOnly(java.lang.reflect.Method method) {
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
        assertThat(authorization)
                .as("%s must require ADMIN", method.getName())
                .isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }
}
