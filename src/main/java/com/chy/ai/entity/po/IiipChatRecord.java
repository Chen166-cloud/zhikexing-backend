package com.chy.ai.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 会话历史记录
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("iiip_chat_record")
public class IiipChatRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话id
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * chat:聊天机器人；service：智能客服；pdf：个人知识库
     */
    private String type;

    /**
     * 会话创建时间
     */
    private LocalDateTime createTime;


}
