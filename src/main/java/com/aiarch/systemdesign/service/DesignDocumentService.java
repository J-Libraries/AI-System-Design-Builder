package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import java.util.UUID;

public interface DesignDocumentService {

    SystemDesignDocument getDocument(UUID designId);

    byte[] exportDocumentPdf(UUID designId);
}
