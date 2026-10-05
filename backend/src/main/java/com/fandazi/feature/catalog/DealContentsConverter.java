// 持久化转换模块：可空结构化公开内容以JSON保存在新列，依赖固定Jackson而不改变旧目录迁移。
package com.fandazi.feature.catalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
/** null代表运营未提供内容，空数组也不被替换成标题推导的菜品。 */
@Converter
public class DealContentsConverter implements AttributeConverter<List<DealContent>,String> {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    /** 写入独立JSON；序列化错误明确阻断本次持久化，不留下部分内容。 */
    @Override public String convertToDatabaseColumn(List<DealContent> value) {
        if(value==null)return null;
        try{return MAPPER.writeValueAsString(value);}catch(Exception invalid){throw new IllegalArgumentException("套餐内容无法保存",invalid);}
    }
    /** 读取仅接受有界数组并验证每项record；坏数据明确失败，不显示貌似完整的假内容。 */
    @Override public List<DealContent> convertToEntityAttribute(String value) {
        if(value==null)return null;
        try {List<DealContent> rows=MAPPER.readValue(value,new TypeReference<List<DealContent>>(){});if(rows==null || rows.size()>100)throw new IllegalArgumentException("内容规模无效");return List.copyOf(rows);}
        catch(Exception invalid){throw new IllegalArgumentException("套餐内容格式无效",invalid);}
    }
}
