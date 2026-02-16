package com.aiarch.systemdesign.mapper;

import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class SystemDesignDocumentMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    public JsonNode toJsonNode(SystemDesignDocument document) {
        return objectMapper.valueToTree(document);
    }

    public SystemDesignDocument fromJsonNode(JsonNode jsonNode) {
        return objectMapper.convertValue(jsonNode, SystemDesignDocument.class);
    }
}
