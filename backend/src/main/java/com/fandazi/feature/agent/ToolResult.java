// 智能助手模块：定义该模块的数据与行为。
package com.fandazi.feature.agent;
import java.util.Map;
public record ToolResult(boolean ok,Map<String,Object> data,String error,String errorKind){public ToolResult{data=data==null?Map.of():Map.copyOf(data);}public static ToolResult ok(Map<String,Object> data){return new ToolResult(true,data,null,null);}}
