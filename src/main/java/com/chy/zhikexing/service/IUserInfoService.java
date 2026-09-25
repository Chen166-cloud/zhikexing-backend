package com.chy.zhikexing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.chy.zhikexing.entity.po.UserInfo;
import com.chy.zhikexing.entity.vo.LoginFormDTO;
import com.chy.zhikexing.entity.vo.Result;
import com.chy.zhikexing.entity.vo.UpdateNicknameDTO;

public interface IUserInfoService extends IService<UserInfo> {

    Result login(LoginFormDTO loginForm);

    Result register(LoginFormDTO registerForm);

    Result logout(String authorization);

    Result updateNickname(UpdateNicknameDTO updateNicknameDTO, String authorization);
}
