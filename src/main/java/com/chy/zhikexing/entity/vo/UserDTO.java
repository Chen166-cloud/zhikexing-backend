package com.chy.zhikexing.entity.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Data
public class UserDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String userName;
    private String nickName;
}
