package com.chy.zhikexing.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.chy.zhikexing.auth.AuthLoginGuard;
import com.chy.zhikexing.auth.AuthSessionService;
import com.chy.zhikexing.entity.po.UserInfo;
import com.chy.zhikexing.entity.vo.LoginFormDTO;
import com.chy.zhikexing.entity.vo.Result;
import com.chy.zhikexing.entity.vo.UpdateNicknameDTO;
import com.chy.zhikexing.entity.vo.UserDTO;
import com.chy.zhikexing.mapper.UserInfoMapper;
import com.chy.zhikexing.service.IUserInfoService;
import com.chy.zhikexing.util.PasswordEncoder;
import com.chy.zhikexing.util.TokenUtils;
import com.chy.zhikexing.util.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    private static final int MAX_USERNAME_LENGTH = 11;
    private static final int MAX_NICKNAME_LENGTH = 20;

    private final AuthLoginGuard loginGuard;
    private final AuthSessionService sessions;

    @Override
    public Result login(LoginFormDTO loginForm) {
        String userName = normalizeUserName(loginForm);
        String password = loginForm == null ? "" : loginForm.getPassword();
        Result validation = validateLogin(userName, password);
        if (validation != null) {
            return validation;
        }

        try (var permit = loginGuard.acquire()) {
            loginGuard.checkRequestRate();
            UserInfo user = lambdaQuery()
                    .eq(UserInfo::getUserName, userName)
                    .last("limit 1")
                    .one();
            if (user == null) {
                return Result.fail("用户名或密码错误");
            }
            loginGuard.checkAccount(user.getId());
            if (!PasswordEncoder.matches(user.getPassword(), password)) {
                loginGuard.loginFailed(user.getId());
                return Result.fail("用户名或密码错误");
            }
            if (PasswordEncoder.needsUpgrade(user.getPassword())) {
                lambdaUpdate().eq(UserInfo::getId, user.getId())
                        .eq(UserInfo::getPassword, user.getPassword())
                        .set(UserInfo::getPassword, PasswordEncoder.encode(password)).update();
            }
            loginGuard.loginSucceeded(user.getId());
            return Result.ok(sessions.create(toUserDTO(user)));
        }
    }

    @Override
    public Result register(LoginFormDTO registerForm) {
        String userName = normalizeUserName(registerForm);
        String password = registerForm == null ? "" : registerForm.getPassword();
        Result validation = validateRegister(userName, password);
        if (validation != null) {
            return validation;
        }

        try (var permit = loginGuard.acquire()) {
            loginGuard.checkRequestRate();
            Long count = lambdaQuery().eq(UserInfo::getUserName, userName).count();
            if (count != null && count > 0) {
                return Result.fail("用户名已存在");
            }

            UserInfo user = new UserInfo()
                    .setUserName(userName)
                    .setPassword(PasswordEncoder.encode(password))
                    .setNickName("");
            try {
                save(user);
            } catch (DuplicateKeyException e) {
                return Result.fail("用户名已存在");
            }

            String nickName = String.valueOf(user.getId());
            user.setNickName(nickName);
            lambdaUpdate()
                    .eq(UserInfo::getId, user.getId())
                    .set(UserInfo::getNickName, nickName)
                    .update();
            return Result.ok(sessions.create(toUserDTO(user)));
        }
    }

    @Override
    public Result logout(String authorization) {
        String token = TokenUtils.normalize(authorization);
        if (StringUtils.hasText(token)) {
            sessions.logout(token);
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result updateNickname(UpdateNicknameDTO updateNicknameDTO, String authorization) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            return Result.fail("请先登录");
        }

        String nickName = normalizeNickname(updateNicknameDTO);
        Result validation = validateNickname(nickName);
        if (validation != null) {
            return validation;
        }

        boolean updated = lambdaUpdate()
                .eq(UserInfo::getId, currentUser.getId())
                .set(UserInfo::getNickName, nickName)
                .set(UserInfo::getUpdateTime, LocalDateTime.now())
                .update();
        if (!updated) {
            return Result.fail("用户不存在");
        }

        UserDTO updatedUser = new UserDTO();
        updatedUser.setId(currentUser.getId());
        updatedUser.setUserName(currentUser.getUserName());
        updatedUser.setNickName(nickName);
        UserHolder.saveUser(updatedUser);
        sessions.updateNickname(TokenUtils.normalize(authorization), updatedUser);
        return Result.ok(updatedUser);
    }

    private Result validateLogin(String userName, String password) {
        if (!StringUtils.hasText(userName)) {
            return Result.fail("用户名不能为空");
        }
        if (userName.length() > MAX_USERNAME_LENGTH) {
            return Result.fail("用户名不能超过11个字符");
        }
        if (!StringUtils.hasText(password)) {
            return Result.fail("密码不能为空");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return Result.fail("密码不能超过72字节");
        }
        return null;
    }

    private Result validateRegister(String userName, String password) {
        return validateLogin(userName, password);
    }

    private Result validateNickname(String nickName) {
        if (!StringUtils.hasText(nickName)) {
            return Result.fail("昵称不能为空");
        }
        if (nickName.length() > MAX_NICKNAME_LENGTH) {
            return Result.fail("昵称不能超过20个字符");
        }
        return null;
    }

    private String normalizeUserName(LoginFormDTO form) {
        if (form == null || form.getUserName() == null) {
            return "";
        }
        return form.getUserName().trim();
    }

    private String normalizeNickname(UpdateNicknameDTO form) {
        if (form == null || form.getNickName() == null) {
            return "";
        }
        return form.getNickName().trim();
    }

    private UserDTO toUserDTO(UserInfo user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUserName(user.getUserName());
        userDTO.setNickName(StringUtils.hasText(user.getNickName()) ? user.getNickName() : String.valueOf(user.getId()));
        return userDTO;
    }
}
