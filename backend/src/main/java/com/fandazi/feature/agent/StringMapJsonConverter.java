package com.fandazi.feature.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;

@Converter
public class StringMapJsonConverter implements AttributeConverter<Map<String, Object>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Override public String convertToDatabaseColumn(Map<String, Object> value) { try { return MAPPER.writeValueAsString(value == null ? Map.of() : value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    @Override public Map<String, Object> convertToEntityAttribute(String value) { try { return value == null || value.isBlank() ? Map.of() : MAPPER.readValue(value, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
