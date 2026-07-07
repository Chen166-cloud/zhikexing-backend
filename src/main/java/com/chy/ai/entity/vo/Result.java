package com.chy.ai.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Result {
    private Integer ok;
    private String msg;
    private Object data;

    private Result(Integer ok, String msg) {
        this.ok = ok;
        this.msg = msg;
    }

    private Result(Integer ok, String msg, Object data) {
        this.ok = ok;
        this.msg = msg;
        this.data = data;
    }

    public static Result ok() {
        return new Result(1, "ok");
    }

    public static Result ok(Object data) {
        return new Result(1, "ok", data);
    }

    public static Result fail(String msg) {
        return new Result(0, msg);
    }
}
