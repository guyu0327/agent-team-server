package com.guyu.agentteam.config;

import com.guyu.agentteam.common.SecretCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 敏感字符串字段透明加解密：实体字段标 @Convert(converter = DpapiStringConverter.class) 即可 */
@Converter
public class DpapiStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return SecretCipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return SecretCipher.decrypt(dbData);
    }
}
