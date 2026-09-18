package com.chy.ai.util;

import com.chy.ai.entity.vo.UserDTO;

public class UserHolder {
    private static final ThreadLocal<UserDTO> TL = new ThreadLocal<>();

    private UserHolder() {
    }

    public static void saveUser(UserDTO user) {
        TL.set(user);
    }

    public static UserDTO getUser() {
        return TL.get();
    }

    public static long requireUserId() {
        UserDTO user = TL.get();
        if (user == null || user.getId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user.getId();
    }

    public static void removeUser() {
        TL.remove();
    }
}
