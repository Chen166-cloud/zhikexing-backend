package com.chy.zhikexing.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("zhikexing_pdf_file")
public class ZhikexingPdfFile implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String chatId;

    private Long userId;

    private String originalFilename;

    private String ossBucket;

    private String ossKey;

    private Long fileSize;

    private String contentType;

    private String vectorIndexName;

    private Integer vectorStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
