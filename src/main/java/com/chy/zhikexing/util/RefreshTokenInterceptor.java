package com.chy.zhikexing.util;

import com.chy.zhikexing.auth.AuthSessionService;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RefreshTokenInterceptor implements AsyncHandlerInterceptor {

    private final AuthSessionService sessions;

    public RefreshTokenInterceptor(AuthSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserHolder.removeUser();
        String token = TokenUtils.normalize(request.getHeader("Authorization"));
        if (!StringUtils.hasText(token)) {
            return true;
        }
        var user = sessions.resolveAndRefresh(token);
        if (user != null) UserHolder.saveUser(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserHolder.removeUser();
    }

}
