package com.aiarch.systemdesign.service.impl;

import com.aiarch.systemdesign.dto.document.ApiContract;
import com.aiarch.systemdesign.dto.document.Component;
import com.aiarch.systemdesign.dto.document.ComponentLLD;
import com.aiarch.systemdesign.dto.document.DataFlowScenario;
import com.aiarch.systemdesign.dto.document.DatabaseSchema;
import com.aiarch.systemdesign.dto.document.DiagramEdge;
import com.aiarch.systemdesign.dto.document.DiagramNode;
import com.aiarch.systemdesign.dto.document.DiagramMetadata;
import com.aiarch.systemdesign.dto.document.SystemDesignDocument;
import com.aiarch.systemdesign.exception.ResourceNotFoundException;
import com.aiarch.systemdesign.mapper.SystemDesignDocumentMapper;
import com.aiarch.systemdesign.model.SystemDesign;
import com.aiarch.systemdesign.repository.SystemDesignRepository;
import com.aiarch.systemdesign.service.DesignDocumentService;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DesignDocumentServiceImpl implements DesignDocumentService {

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 11);
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final int DIAGRAM_WIDTH = 1100;
    private static final int DIAGRAM_HEIGHT = 700;

    private final SystemDesignRepository systemDesignRepository;
    private final SystemDesignDocumentMapper documentMapper;

    @Override
    @Transactional(readOnly = true)
    public SystemDesignDocument getDocument(UUID designId) {
        SystemDesign design = systemDesignRepository.findById(designId)
                .orElseThrow(() -> new ResourceNotFoundException("System design not found with id: " + designId));
        return documentMapper.fromJsonNode(design.getDocumentJson());
    }

    @Override
    @Transactional
    public SystemDesignDocument updateDocument(UUID designId, SystemDesignDocument document) {
        SystemDesign design = systemDesignRepository.findById(designId)
                .orElseThrow(() -> new ResourceNotFoundException("System design not found with id: " + designId));
        design.setDocumentJson(documentMapper.toJsonNode(document));
        systemDesignRepository.save(design);
        return documentMapper.fromJsonNode(design.getDocumentJson());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportDocumentPdf(UUID designId) {
        SystemDesign design = systemDesignRepository.findById(designId)
                .orElseThrow(() -> new ResourceNotFoundException("System design not found with id: " + designId));
        SystemDesignDocument documentModel = documentMapper.fromJsonNode(design.getDocumentJson());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document pdfDocument = new Document();
        PdfWriter writer;
        try {
            writer = PdfWriter.getInstance(pdfDocument, outputStream);
        } catch (DocumentException ex) {
            throw new IllegalStateException("Failed to initialize PDF writer", ex);
        }

        writer.setPageEvent(new PageNumberEventHandler());
        pdfDocument.open();
        try {
            addTitlePage(pdfDocument, design);
            addSection(pdfDocument, "Overview", documentModel.getOverview());
            addListSection(pdfDocument, "Assumptions", documentModel.getAssumptions());
            addSection(pdfDocument, "Capacity Estimation", documentModel.getCapacityEstimation());
            addSection(pdfDocument, "High Level Design", documentModel.getHld());
            addComponentsSection(pdfDocument, documentModel.getComponents());
            addLldSection(pdfDocument, documentModel.getLld());
            addApiSection(pdfDocument, documentModel.getApiContracts());
            addDatabaseSection(pdfDocument, documentModel.getDatabaseSchemas());
            addDataFlowSection(pdfDocument, documentModel.getDataFlowScenarios());
            addSection(pdfDocument, "Scaling Strategy", documentModel.getScalingStrategy());
            addSection(pdfDocument, "Failure Handling", documentModel.getFailureHandling());
            addSection(pdfDocument, "Tradeoffs", documentModel.getTradeoffs());
            addDiagramSection(pdfDocument, documentModel);
        } catch (DocumentException ex) {
            throw new IllegalStateException("Failed to generate PDF", ex);
        } finally {
            pdfDocument.close();
        }

        return outputStream.toByteArray();
    }

    private void addTitlePage(Document document, SystemDesign design) throws DocumentException {
        Paragraph title = new Paragraph("System Design Document", TITLE_FONT);
        title.setSpacingAfter(24f);
        document.add(title);
        document.add(new Paragraph("Product: " + design.getProductName(), SUBTITLE_FONT));
        document.add(new Paragraph("Version: " + design.getVersion(), SUBTITLE_FONT));
        document.add(new Paragraph("Generated On: " + design.getCreatedAt(), BODY_FONT));
        document.newPage();
    }

    private void addSection(Document document, String heading, String content) throws DocumentException {
        document.add(new Paragraph(heading, SECTION_FONT));
        document.add(new Paragraph(safe(content), BODY_FONT));
        document.add(Chunk.NEWLINE);
    }

    private void addListSection(Document document, String heading, List<String> content) throws DocumentException {
        document.add(new Paragraph(heading, SECTION_FONT));
        if (content == null || content.isEmpty()) {
            document.add(new Paragraph("N/A", BODY_FONT));
        } else {
            for (String line : content) {
                document.add(new Paragraph("- " + safe(line), BODY_FONT));
            }
        }
        document.add(Chunk.NEWLINE);
    }

    private void addComponentsSection(Document document, List<Component> components) throws DocumentException {
        document.add(new Paragraph("Components", SECTION_FONT));
        if (components == null || components.isEmpty()) {
            document.add(new Paragraph("N/A", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }
        for (Component component : components) {
            document.add(new Paragraph(component.getName() + " (" + safe(component.getType()) + ")", SUBTITLE_FONT));
            document.add(new Paragraph("Responsibility: " + safe(component.getResponsibility()), BODY_FONT));
            document.add(new Paragraph("Dependencies: " + String.join(", ", safeList(component.getDependencies())), BODY_FONT));
            document.add(Chunk.NEWLINE);
        }
    }

    private void addLldSection(Document document, List<ComponentLLD> lldList) throws DocumentException {
        document.add(new Paragraph("Low Level Design", SECTION_FONT));
        if (lldList == null || lldList.isEmpty()) {
            document.add(new Paragraph("N/A", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }
        for (ComponentLLD lld : lldList) {
            document.add(new Paragraph(safe(lld.getComponentName()), SUBTITLE_FONT));
            document.add(new Paragraph("Module: " + safe(lld.getModuleDescription()), BODY_FONT));
            document.add(new Paragraph("Classes: " + String.join(", ", safeList(lld.getClasses())), BODY_FONT));
            document.add(new Paragraph("Interfaces: " + String.join(", ", safeList(lld.getInterfaces())), BODY_FONT));
            document.add(new Paragraph("Sequence: " + String.join(" -> ", safeList(lld.getSequence())), BODY_FONT));
            document.add(Chunk.NEWLINE);
        }
    }

    private void addApiSection(Document document, List<ApiContract> contracts) throws DocumentException {
        document.add(new Paragraph("API Contracts", SECTION_FONT));
        if (contracts == null || contracts.isEmpty()) {
            document.add(new Paragraph("N/A", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }
        for (ApiContract contract : contracts) {
            document.add(new Paragraph(safe(contract.getName()), SUBTITLE_FONT));
            document.add(new Paragraph("Method: " + safe(contract.getMethod()), BODY_FONT));
            document.add(new Paragraph("Path: " + safe(contract.getPath()), BODY_FONT));
            document.add(new Paragraph("Request Schema: " + safe(contract.getRequestSchema()), BODY_FONT));
            document.add(new Paragraph("Response Schema: " + safe(contract.getResponseSchema()), BODY_FONT));
            document.add(new Paragraph("Error Codes: " + String.join(", ", safeList(contract.getErrorCodes())), BODY_FONT));
            document.add(Chunk.NEWLINE);
        }
    }

    private void addDatabaseSection(Document document, List<DatabaseSchema> schemas) throws DocumentException {
        document.add(new Paragraph("Database Schemas", SECTION_FONT));
        if (schemas == null || schemas.isEmpty()) {
            document.add(new Paragraph("N/A", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }
        for (DatabaseSchema schema : schemas) {
            document.add(new Paragraph(safe(schema.getEntityName()), SUBTITLE_FONT));
            document.add(new Paragraph("Fields: " + String.join(", ", safeList(schema.getFields())), BODY_FONT));
            document.add(new Paragraph("Indexes: " + String.join(", ", safeList(schema.getIndexes())), BODY_FONT));
            document.add(Chunk.NEWLINE);
        }
    }

    private void addDataFlowSection(Document document, List<DataFlowScenario> scenarios) throws DocumentException {
        document.add(new Paragraph("Data Flow Scenarios", SECTION_FONT));
        if (scenarios == null || scenarios.isEmpty()) {
            document.add(new Paragraph("N/A", BODY_FONT));
            document.add(Chunk.NEWLINE);
            return;
        }
        for (DataFlowScenario scenario : scenarios) {
            document.add(new Paragraph(safe(scenario.getName()), SUBTITLE_FONT));
            document.add(new Paragraph("Trigger: " + safe(scenario.getTrigger()), BODY_FONT));
            document.add(new Paragraph("Steps: " + String.join(" -> ", safeList(scenario.getSteps())), BODY_FONT));
            document.add(new Paragraph("Expected Outcome: " + safe(scenario.getExpectedOutcome()), BODY_FONT));
            document.add(Chunk.NEWLINE);
        }
    }

    private void addDiagramSection(Document document, SystemDesignDocument documentModel) throws DocumentException {
        document.add(new Paragraph("Diagram", SECTION_FONT));
        DiagramMetadata metadata = documentModel.getDiagramMetadata();
        if (metadata == null || metadata.getNodes() == null || metadata.getNodes().isEmpty()) {
            document.add(new Paragraph("Metadata not available", BODY_FONT));
            return;
        }
        List<DiagramNode> nodes = metadata.getNodes();
        List<DiagramEdge> edges = metadata.getEdges();
        com.lowagie.text.Image diagramImage = renderDiagramImage(nodes, edges);
        if (diagramImage != null) {
            diagramImage.scaleToFit(520f, 340f);
            diagramImage.setAlignment(com.lowagie.text.Image.ALIGN_CENTER);
            document.add(diagramImage);
        } else {
            document.add(new Paragraph("Unable to render diagram image from metadata", BODY_FONT));
        }
        document.add(Chunk.NEWLINE);
        document.add(new Paragraph("Nodes: " + (nodes == null ? 0 : nodes.size()), BODY_FONT));
        document.add(new Paragraph("Edges: " + (edges == null ? 0 : edges.size()), BODY_FONT));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private com.lowagie.text.Image renderDiagramImage(List<DiagramNode> nodes, List<DiagramEdge> edges) {
        try {
            BufferedImage image = new BufferedImage(DIAGRAM_WIDTH, DIAGRAM_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                g2.setColor(new Color(245, 247, 250));
                g2.fillRect(0, 0, DIAGRAM_WIDTH, DIAGRAM_HEIGHT);

                Map<String, java.awt.Point> positions = calculateNodePositions(nodes);
                drawEdges(g2, positions, edges);
                drawNodes(g2, nodes, positions);
            } finally {
                g2.dispose();
            }
            return com.lowagie.text.Image.getInstance(image, null);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, java.awt.Point> calculateNodePositions(List<DiagramNode> nodes) {
        Map<String, java.awt.Point> map = new HashMap<>();
        int count = nodes.size();
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / columns);
        int xSpacing = DIAGRAM_WIDTH / (columns + 1);
        int ySpacing = DIAGRAM_HEIGHT / (rows + 1);

        for (int i = 0; i < count; i++) {
            DiagramNode node = nodes.get(i);
            if (node.getPosition() != null && node.getPosition().getX() != null && node.getPosition().getY() != null) {
                map.put(node.getId(), new java.awt.Point(node.getPosition().getX(), node.getPosition().getY()));
                continue;
            }
            int row = i / columns;
            int col = i % columns;
            int x = (col + 1) * xSpacing;
            int y = (row + 1) * ySpacing;
            map.put(node.getId(), new java.awt.Point(x, y));
        }
        return map;
    }

    private void drawNodes(Graphics2D g2, List<DiagramNode> nodes, Map<String, java.awt.Point> positions) {
        int width = 160;
        int height = 64;

        for (DiagramNode node : nodes) {
            java.awt.Point p = positions.get(node.getId());
            if (p == null) {
                continue;
            }
            int x = p.x - width / 2;
            int y = p.y - height / 2;

            g2.setColor(colorByType(node.getType()));
            g2.fillRoundRect(x, y, width, height, 20, 20);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(x, y, width, height, 20, 20);

            g2.setColor(Color.WHITE);
            g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
            drawCenteredString(g2, safe(labelForNode(node)), x, y + 10, width, 22);
            g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
            drawCenteredString(g2, safe(node.getType()), x, y + 34, width, 16);
        }
    }

    private void drawEdges(Graphics2D g2, Map<String, java.awt.Point> positions, List<DiagramEdge> edges) {
        if (edges == null) {
            return;
        }
        g2.setColor(new Color(69, 90, 100));
        g2.setStroke(new BasicStroke(2f));
        g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));

        for (DiagramEdge edge : edges) {
            java.awt.Point from = positions.get(resolveEdgeSource(edge));
            java.awt.Point to = positions.get(resolveEdgeTarget(edge));
            if (from == null || to == null) {
                continue;
            }
            g2.drawLine(from.x, from.y, to.x, to.y);
            drawArrowHead(g2, from.x, from.y, to.x, to.y);

            int midX = (from.x + to.x) / 2;
            int midY = (from.y + to.y) / 2 - 6;
            String label = safe(edge.getLabel());
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(label);
            g2.setColor(new Color(255, 255, 255, 220));
            g2.fillRoundRect(midX - textWidth / 2 - 5, midY - 12, textWidth + 10, 16, 8, 8);
            g2.setColor(new Color(55, 71, 79));
            g2.drawString(label, midX - textWidth / 2, midY);
            g2.setColor(new Color(69, 90, 100));
        }
    }

    private void drawArrowHead(Graphics2D g2, int x1, int y1, int x2, int y2) {
        double phi = Math.toRadians(28);
        int barb = 14;
        double dy = y2 - y1;
        double dx = x2 - x1;
        double theta = Math.atan2(dy, dx);

        AffineTransform old = g2.getTransform();
        g2.translate(x2, y2);
        g2.rotate(theta);
        g2.drawLine(0, 0, -barb, -((int) (barb * Math.tan(phi / 2))));
        g2.drawLine(0, 0, -barb, ((int) (barb * Math.tan(phi / 2))));
        g2.setTransform(old);
    }

    private void drawCenteredString(Graphics2D g2, String text, int x, int y, int width, int height) {
        FontMetrics metrics = g2.getFontMetrics(g2.getFont());
        int drawX = x + (width - metrics.stringWidth(text)) / 2;
        int drawY = y + ((height - metrics.getHeight()) / 2) + metrics.getAscent();
        g2.drawString(text, drawX, drawY);
    }

    private String resolveEdgeSource(DiagramEdge edge) {
        if (edge.getSource() != null && !edge.getSource().isBlank()) {
            return edge.getSource();
        }
        return edge.getFrom();
    }

    private String resolveEdgeTarget(DiagramEdge edge) {
        if (edge.getTarget() != null && !edge.getTarget().isBlank()) {
            return edge.getTarget();
        }
        return edge.getTo();
    }

    private String labelForNode(DiagramNode node) {
        if (node.getData() != null && node.getData().getLabel() != null && !node.getData().getLabel().isBlank()) {
            return node.getData().getLabel();
        }
        return node.getId();
    }

    private Color colorByType(String rawType) {
        if (rawType == null) {
            return new Color(33, 150, 243);
        }
        String type = rawType.toLowerCase();
        if (type.contains("gateway") || type.contains("cdn") || type.contains("client")) {
            return new Color(79, 70, 229);
        }
        if (type.contains("database") || type.contains("cache") || type.contains("storage")) {
            return new Color(234, 88, 12);
        }
        if (type.contains("queue") || type.contains("worker")) {
            return new Color(202, 138, 4);
        }
        if (type.contains("observability") || type.contains("monitor")) {
            return new Color(22, 163, 74);
        }
        return new Color(33, 150, 243);
    }

    private static class PageNumberEventHandler extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle pageSize = document.getPageSize();
            com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
            cb.beginText();
            try {
                cb.setFontAndSize(
                        FontFactory.getFont(FontFactory.HELVETICA, 9).getBaseFont(),
                        9
                );
                cb.showTextAligned(
                        com.lowagie.text.Element.ALIGN_CENTER,
                        "Page " + writer.getPageNumber(),
                        (pageSize.getLeft() + pageSize.getRight()) / 2,
                        pageSize.getBottom() + 20,
                        0
                );
            } finally {
                cb.endText();
            }
        }
    }
}
