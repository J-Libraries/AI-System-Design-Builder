package com.aiarch.systemdesign.service;

import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import java.util.UUID;

public interface DesignDocumentService {

    SystemDesignDocument getDocument(UUID designId);

    SystemDesignDocument updateDocument(UUID designId, SystemDesignDocument document);

    byte[] exportDocumentPdf(UUID designId);

    byte[] exportSowPdf(UUID designId);

    byte[] exportTaskBreakdownCsv(UUID designId);
}
