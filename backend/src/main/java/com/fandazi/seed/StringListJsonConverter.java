// 演示数据模块：转换持久化字段与领域数据。
package com.fandazi.seed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize seed string list", exception);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String value) {
        if (value == null) return null;
        try {
            return List.copyOf(MAPPER.readValue(value, LIST_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to deserialize seed string list", exception);
        }
    }
}
