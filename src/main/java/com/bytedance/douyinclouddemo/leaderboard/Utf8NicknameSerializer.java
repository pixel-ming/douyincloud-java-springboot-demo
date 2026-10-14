package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

public class Utf8NicknameSerializer extends JsonSerializer<String> {
    @Override
    public void serialize(String value,JsonGenerator generator,SerializerProvider provider) throws IOException {
        // 兼容旧客户端：直接输出 UTF-8 昵称，同时由 Jackson 保留必要的 JSON 转义。
        generator.writeString(new SerializedString(value));
    }
}
