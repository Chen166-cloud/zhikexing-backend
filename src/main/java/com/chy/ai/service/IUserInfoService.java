package com.chy.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chy.ai.entity.po.UserInfo;
import com.chy.ai.entity.vo.LoginFormDTO;
import com.chy.ai.entity.vo.Result;
import com.chy.ai.entity.vo.UpdateNicknameDTO;

public interface IUserInfoService extends IService<UserInfo> {

    Result login(LoginFormDTO loginForm);

    Result register(LoginFormDTO registerForm);

    Result logout(String authorization);

    Result updateNickname(UpdateNicknameDTO updateNicknameDTO, String authorization);
}
