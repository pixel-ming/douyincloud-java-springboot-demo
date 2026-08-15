package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class SetNameRequest {
    private String target;
    private String name;

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
