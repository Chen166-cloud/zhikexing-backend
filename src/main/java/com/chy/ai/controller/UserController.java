package com.chy.ai.controller;

import com.chy.ai.entity.vo.LoginFormDTO;
import com.chy.ai.entity.vo.Result;
import com.chy.ai.entity.vo.UpdateNicknameDTO;
import com.chy.ai.entity.vo.UserDTO;
import com.chy.ai.service.IUserInfoService;
import com.chy.ai.util.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final IUserInfoService userInfoService;

    @PostMapping("/login")
    public Result login(@RequestBody(required = false) LoginFormDTO loginForm) {
        return userInfoService.login(loginForm);
    }

    @PostMapping("/register")
    public Result register(@RequestBody(required = false) LoginFormDTO registerForm) {
        return userInfoService.register(registerForm);
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return userInfoService.logout(authorization);
    }

    @PostMapping("/nickname")
    public Result updateNickname(
            @RequestBody(required = false) UpdateNicknameDTO updateNicknameDTO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userInfoService.updateNickname(updateNicknameDTO, authorization);
    }

    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(user);
    }
}
