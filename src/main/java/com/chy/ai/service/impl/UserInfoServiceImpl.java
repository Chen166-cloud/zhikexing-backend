package com.chy.ai.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.chy.ai.entity.po.UserInfo;
import com.chy.ai.entity.vo.LoginFormDTO;
import com.chy.ai.entity.vo.Result;
import com.chy.ai.entity.vo.UpdateNicknameDTO;
import com.chy.ai.entity.vo.UserDTO;
import com.chy.ai.mapper.UserInfoMapper;
import com.chy.ai.service.IUserInfoService;
import com.chy.ai.util.PasswordEncoder;
import com.chy.ai.util.TokenUtils;
import com.chy.ai.util.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.chy.ai.contants.RedisConstants.LOGIN_USER_KEY;
import static com.chy.ai.contants.RedisConstants.LOGIN_USER_TTL;

@Service
@RequiredArgsConstructor
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    private static final int MAX_USERNAME_LENGTH = 11;
    private static final int MAX_NICKNAME_LENGTH = 20;

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result login(LoginFormDTO loginForm) {
        String userName = normalizeUserName(loginForm);
        String password = loginForm == null ? "" : loginForm.getPassword();
        Result validation = validateLogin(userName, password);
        if (validation != null) {
            return validation;
        }

        UserInfo user = lambdaQuery()
                .eq(UserInfo::getUserName, userName)
                .last("limit 1")
                .one();
        if (user == null) {
            return Result.fail("用户不存在，请先注册");
        }
        if (!PasswordEncoder.matches(user.getPassword(), password)) {
            return Result.fail("密码错误");
        }
        if (PasswordEncoder.needsUpgrade(user.getPassword())) {
            lambdaUpdate().eq(UserInfo::getId, user.getId())
                    .eq(UserInfo::getPassword, user.getPassword())
                    .set(UserInfo::getPassword, PasswordEncoder.encode(password)).update();
        }
        return Result.ok(saveUserToRedis(user));
    }

    @Override
    public Result register(LoginFormDTO registerForm) {
        String userName = normalizeUserName(registerForm);
        String password = registerForm == null ? "" : registerForm.getPassword();
        Result validation = validateRegister(userName, password);
        if (validation != null) {
            return validation;
        }

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
        return Result.ok(saveUserToRedis(user));
    }

    @Override
    public Result logout(String authorization) {
        String token = TokenUtils.normalize(authorization);
        if (StringUtils.hasText(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
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
        refreshCurrentLoginUser(authorization, updatedUser);
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

    private void refreshCurrentLoginUser(String authorization, UserDTO userDTO) {
        String token = TokenUtils.normalize(authorization);
        if (!StringUtils.hasText(token)) {
            return;
        }
        String key = LOGIN_USER_KEY + token;
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userDTO.getId()));
        userMap.put("userName", userDTO.getUserName());
        userMap.put("nickName", userDTO.getNickName());
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
    }

    private String saveUserToRedis(UserInfo user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        UserDTO userDTO = toUserDTO(user);
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userDTO.getId()));
        userMap.put("userName", userDTO.getUserName());
        userMap.put("nickName", userDTO.getNickName());
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
    }

    private UserDTO toUserDTO(UserInfo user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUserName(user.getUserName());
        userDTO.setNickName(StringUtils.hasText(user.getNickName()) ? user.getNickName() : String.valueOf(user.getId()));
        return userDTO;
    }
}
