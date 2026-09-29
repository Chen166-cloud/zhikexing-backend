package com.chy.zhikexing.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.chy.zhikexing.controller.UserController;
import com.chy.zhikexing.entity.po.UserInfo;
import com.chy.zhikexing.entity.vo.LoginFormDTO;
import com.chy.zhikexing.entity.vo.UserDTO;
import com.chy.zhikexing.service.impl.UserInfoServiceImpl;
import com.chy.zhikexing.util.PasswordEncoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthFlowTest {
    private AuthLoginGuard guard;
    private AuthLoginGuard.Permit permit;
    private AuthSessionService sessions;
    private UserInfoServiceImpl service;
    private LambdaQueryChainWrapper<UserInfo> query;
    private LambdaUpdateChainWrapper<UserInfo> update;
    private LoginFormDTO form;
    private UserInfo user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        guard = mock(AuthLoginGuard.class);
        permit = mock(AuthLoginGuard.Permit.class);
        sessions = mock(AuthSessionService.class);
        service = spy(new UserInfoServiceImpl(guard, sessions));
        query = mock(LambdaQueryChainWrapper.class);
        update = mock(LambdaUpdateChainWrapper.class);
        doReturn(query).when(query).eq(any(), any());
        doReturn(query).when(query).last(anyString());
        doReturn(update).when(update).eq(any(), any());
        doReturn(update).when(update).set(any(), any());
        doReturn(query).when(service).lambdaQuery();
        doReturn(update).when(service).lambdaUpdate();
        when(guard.acquire()).thenReturn(permit);
        when(update.update()).thenReturn(true);
        form = new LoginFormDTO();
        form.setUserName("demo");
        form.setPassword("password");
        user = new UserInfo().setId(77L).setUserName("demo")
                .setPassword("$2a$existing-hash").setNickName("Demo");
        when(query.one()).thenReturn(user);
    }

    @Test
    void globalLimitStopsLoginAndRegistrationBeforeDatabaseAndBcrypt() {
        doThrow(rejected()).when(guard).checkRequestRate();
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            assertThrows(AuthLoginGuard.Rejected.class, () -> service.login(form));
            assertThrows(AuthLoginGuard.Rejected.class, () -> service.register(form));
            passwords.verifyNoInteractions();
        }
        verifyNoInteractions(query, sessions);
        verify(permit, times(2)).close();
    }

    @Test
    void cooldownStopsLoginBeforeBcrypt() {
        doThrow(rejected()).when(guard).checkAccount(77L);
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            assertThrows(AuthLoginGuard.Rejected.class, () -> service.login(form));
            passwords.verifyNoInteractions();
        }
        verify(guard).checkRequestRate();
        verify(permit).close();
        verifyNoInteractions(sessions);
    }

    @Test
    void fullConcurrencyStopsPasswordCheckingAndRegistrationEncoding() {
        doThrow(rejected()).when(guard).acquire();
        when(query.count()).thenReturn(0L);
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            assertThrows(AuthLoginGuard.Rejected.class, () -> service.login(form));
            assertThrows(AuthLoginGuard.Rejected.class, () -> service.register(form));
            passwords.verifyNoInteractions();
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void unknownUserAndWrongPasswordReturnTheSameFailure() {
        when(query.one()).thenReturn(null, user);
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            var unknown = service.login(form);
            var incorrect = service.login(form);
            assertEquals(0, unknown.getOk());
            assertEquals(0, incorrect.getOk());
            assertEquals(unknown.getMsg(), incorrect.getMsg());
            passwords.verify(() -> PasswordEncoder.matches(user.getPassword(), "password"));
        }
        verify(guard).loginFailed(77L);
        verify(guard, never()).loginSucceeded(anyLong());
        verifyNoInteractions(sessions);
        verify(permit, times(2)).close();
    }

    @Test
    void successfulLoginClearsFailuresAndCreatesSession() {
        when(sessions.create(any(UserDTO.class))).thenReturn("new-token");
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            passwords.when(() -> PasswordEncoder.matches(user.getPassword(), "password"))
                    .thenReturn(true);
            var result = service.login(form);
            assertEquals(1, result.getOk());
            assertEquals("new-token", result.getData());
        }
        var order = inOrder(guard, permit);
        order.verify(guard).acquire();
        order.verify(guard).checkRequestRate();
        order.verify(guard).checkAccount(77L);
        order.verify(permit).close();
        verify(guard).loginSucceeded(77L);
        verify(guard, never()).loginFailed(anyLong());
        var captured = ArgumentCaptor.forClass(UserDTO.class);
        verify(sessions).create(captured.capture());
        assertEquals(77L, captured.getValue().getId());
        assertEquals("Demo", captured.getValue().getNickName());
    }

    @Test
    void legacyPasswordUpgradeAlsoRunsInsideTheConcurrencyPermit() {
        user.setPassword("legacy@hash");
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            passwords.when(() -> PasswordEncoder.matches("legacy@hash", "password"))
                    .thenReturn(true);
            passwords.when(() -> PasswordEncoder.needsUpgrade("legacy@hash")).thenReturn(true);
            passwords.when(() -> PasswordEncoder.encode("password")).thenAnswer(invocation -> {
                verify(guard).acquire();
                verify(permit, never()).close();
                return "$2a$upgraded";
            });
            assertEquals(1, service.login(form).getOk());
            passwords.verify(() -> PasswordEncoder.encode("password"));
        }
        verify(update).update();
        verify(permit).close();
    }

    @Test
    void registrationUsesSharedGuardAndEncodesWithinPermit() {
        when(query.count()).thenReturn(0L);
        doAnswer(invocation -> {
            UserInfo registered = invocation.getArgument(0);
            registered.setId(88L);
            return true;
        }).when(service).save(any(UserInfo.class));
        when(sessions.create(any(UserDTO.class))).thenReturn("registration-token");
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            passwords.when(() -> PasswordEncoder.encode("password")).thenAnswer(invocation -> {
                verify(guard).acquire();
                verify(permit, never()).close();
                return "$2a$new-hash";
            });
            var result = service.register(form);
            assertEquals(1, result.getOk());
            assertEquals("registration-token", result.getData());
        }
        verify(guard).checkRequestRate();
        verify(permit).close();
        var captured = ArgumentCaptor.forClass(UserDTO.class);
        verify(sessions).create(captured.capture());
        assertEquals(88L, captured.getValue().getId());
    }

    @Test
    void passwordFailureReleasesThePermit() {
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            passwords.when(() -> PasswordEncoder.matches(user.getPassword(), "password"))
                    .thenThrow(new IllegalStateException("password operation failed"));
            assertThrows(IllegalStateException.class, () -> service.login(form));
        }
        verify(permit).close();
        verifyNoInteractions(sessions);
    }

    @Test
    void httpRejectionKeepsResultFormatAndIncludesRetryAfter() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
                .setControllerAdvice(new AuthErrorHandler()).build();
        doThrow(new AuthLoginGuard.Rejected("Too many attempts", 15))
                .when(guard).checkRequestRate();
        mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"demo\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "15"))
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("Too many attempts"));

        doThrow(new AuthLoginGuard.Rejected(HttpStatus.SERVICE_UNAVAILABLE, "Busy", 1))
                .when(guard).acquire();
        mvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"demo\",\"password\":\"password\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("Busy"));
    }

    @Test
    void redisFailureReturnsUnavailableAndReleasesPermit() throws Exception {
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .when(guard).checkRequestRate();
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
                .setControllerAdvice(new AuthErrorHandler()).build();
        try (var passwords = mockStatic(PasswordEncoder.class)) {
            mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userName\":\"demo\",\"password\":\"password\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.ok").value(0));
            passwords.verifyNoInteractions();
        }
        verify(permit).close();
    }

    private AuthLoginGuard.Rejected rejected() {
        return new AuthLoginGuard.Rejected("Too many attempts", 1);
    }
}
