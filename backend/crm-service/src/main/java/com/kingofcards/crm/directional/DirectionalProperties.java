package com.kingofcards.crm.directional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("crm.directional")
public record DirectionalProperties(@DefaultValue("false") boolean enabled,@DefaultValue("false") boolean writesEnabled,
    @DefaultValue("10000") int exportLimit,@DefaultValue("2000") int importLimit,@DefaultValue("UTC") String xlsxTimeZone,java.util.List<String> serviceOptions) {
    public DirectionalProperties(boolean enabled,boolean writesEnabled,int exportLimit,int importLimit){this(enabled,writesEnabled,exportLimit,importLimit,"UTC",java.util.List.of());}
    public DirectionalProperties(boolean enabled,boolean writesEnabled,int exportLimit,int importLimit,String zone){this(enabled,writesEnabled,exportLimit,importLimit,zone,java.util.List.of());}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DirectionalProperties {if(exportLimit<1 || exportLimit>100000 || importLimit<1 || importLimit>100000)throw new IllegalArgumentException("Directional resource limits are invalid.");java.time.ZoneId.of(xlsxTimeZone);serviceOptions=serviceOptions==null?java.util.List.of():serviceOptions.stream().filter(java.util.Objects::nonNull).map(String::strip).filter(s->!s.isBlank()).distinct().toList();if(serviceOptions.stream().anyMatch(s->s.length()>80))throw new IllegalArgumentException("Service options must not exceed 80 characters.");}
}
