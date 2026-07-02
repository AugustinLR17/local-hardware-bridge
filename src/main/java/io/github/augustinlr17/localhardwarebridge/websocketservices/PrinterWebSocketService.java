package io.github.augustinlr17.localhardwarebridge.websocketservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;
import io.github.augustinlr17.localhardwarebridge.dtos.Config;
import io.github.augustinlr17.localhardwarebridge.dtos.NotificationDTO;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServerInterface;
import io.github.augustinlr17.localhardwarebridge.interfaces.WebSocketServiceInterface;
import io.github.augustinlr17.localhardwarebridge.responses.PrintDocument;
import io.github.augustinlr17.localhardwarebridge.responses.PrintResult;
import io.github.augustinlr17.localhardwarebridge.services.ConfigService;
import io.github.augustinlr17.localhardwarebridge.services.DocumentService;
import io.github.augustinlr17.localhardwarebridge.utils.AnnotatedPrintable;
import io.github.augustinlr17.localhardwarebridge.utils.ImagePrintable;

import javax.imageio.ImageIO;
import javax.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.io.FileUtils;

@Log4j2
public class PrinterWebSocketService implements WebSocketServiceInterface {
    private WebSocketServerInterface server;

    private static final ConfigService configService = ConfigService.getInstance();
    private static final DocumentService documentService = DocumentService.getInstance();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Per-type lock so prints to different printers/types don't serialize globally.
    private final ConcurrentHashMap<String, Object> printLocks = new ConcurrentHashMap<>();
    private static final String DEFAULT_LOCK_KEY = "__default__";

    public PrinterWebSocketService() {
        log.info("Starting PrinterWebSocketService");
    }

    @Override
    public void start() { /* no-op: initialization done in constructor */ }

    @Override
    public void stop() { /* no-op: no resources to clean up */ }

    @Override
    public void messageToService(String message) {
        try {
            PrintDocument printDocument = objectMapper.readValue(message, PrintDocument.class);
            printDocument(printDocument);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public void messageToService(byte[] message) {
        log.error("PrinterWebSocketService onDataReceived: binary data not supported");
    }

    @Override
    public void onRegister(WebSocketServerInterface server) {
        this.server = server;
    }

    @Override
    public void onUnregister() {
        this.server = null;
    }

    @Override
    public String getChannel() {
        return "/printer";
    }

    /**
     * Prints a PrintDocument
     */
    public PrintResult printDocument(PrintDocument printDocument) throws Exception {
        String lockKey = printDocument.getType() != null ? printDocument.getType() : DEFAULT_LOCK_KEY;
        Object printLock = printLocks.computeIfAbsent(lockKey, k -> new Object());

        synchronized (printLock) {
            log.info("Printing Document {}, {}", printDocument.getType(), printDocument.getUrl());

            PrinterSearchResult printerSearchResult = null;
        try {
            printerSearchResult = searchPrinterForType(printDocument.getType());

            server.messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("INFO", "Printing " + printDocument.getType(), printDocument.getUrl())));

            if (isRaw(printDocument)) {
                printRawWithFallback(printDocument, printerSearchResult);
            } else if (isImage(printDocument)) {
                printImageWithFallback(printDocument, printerSearchResult);
            } else if (isPDF(printDocument)) {
                printPDFWithFallback(printDocument, printerSearchResult);
            } else {
                throw new Exception("Unknown file type: " + printDocument.getUrl());
            }

            PrintResult result = new PrintResult(true, "Success", printDocument.getId(), printerSearchResult.getName());
            server.messageToServer(getChannel(), objectMapper.writeValueAsString(result));
            return result;
        } catch (Exception e) {
            String errorMessage = e.getMessage();

            if (e instanceof PrinterAbortException) {
                errorMessage = "Printing aborted";
            }

            log.error("Print Error: {}, {}", e.getClass().getName(), errorMessage);

            server.messageToService("/notification", objectMapper.writeValueAsString(new NotificationDTO("ERROR", "Print Error " + printDocument.getType(), String.valueOf(errorMessage))));

            PrintResult result = new PrintResult(false, errorMessage, printDocument.getId(), printerSearchResult != null ? printerSearchResult.getName() : null);
            server.messageToServer(getChannel(), objectMapper.writeValueAsString(result));
            return result;
        } finally {
            // Always clean up the prepared/downloaded file for non-raw prints (success or
            // failure) so the downloads/ directory does not grow unbounded.
            if (!isRaw(printDocument)) {
                try {
                    documentService.deleteDocument(printDocument);
                } catch (Exception deleteEx) {
                    log.warn("Failed to delete document after print: {}", String.valueOf(deleteEx.getMessage()));
                }
            }
        }
        }
    }

    /**
     * Return if PrintDocument is raw
     */
    private boolean isRaw(PrintDocument printDocument) {
        return printDocument.getRawContent() != null && !printDocument.getRawContent().isEmpty();
    }

    /**
     * Extract a directory-stripped filename from the URL path only, so a query string
     * or fragment (e.g. {@code http://host/file.exe#x.pdf}) cannot spoof the type.
     */
    private String urlFilename(PrintDocument printDocument) {
        String url = printDocument.getUrl();
        if (url == null) {
            return "";
        }
        try {
            return FilenameUtils.getName(new URL(url).getPath());
        } catch (MalformedURLException e) {
            return FilenameUtils.getName(url);
        }
    }

    /**
     * Return if PrintDocument is image
     */
    private boolean isImage(PrintDocument printDocument) {
        String filename = urlFilename(printDocument);

        return filename.toLowerCase().matches("^.*\\.(jpg|jpeg|png|gif)$");
    }

    /**
     * Return if PrintDocument is PDF
     */
    private boolean isPDF(PrintDocument printDocument) {
        String filename = urlFilename(printDocument);

        return filename.toLowerCase().matches("^.*\\.(pdf)$");
    }

    // --- Printer capability detection ---

    /**
     * Detects what content types a printer natively supports via its driver.
     *
     * <p>Uses {@link PrintService#getSupportedDocFlavors()} to probe the printer's
     * capabilities. Thermal/ESC-POS printers typically only support
     * {@code BYTE_ARRAY.AUTOSENSE} (raw), while laser/inkjet printers support image
     * and/or PDF flavors but usually not AUTOSENSE.
     *
     * @param printService the printer to probe
     * @return a {@link PrinterCapabilities} describing what the printer supports
     */
    PrinterCapabilities detectPrinterCapabilities(PrintService printService) {
        boolean supportsRaw = false;
        boolean supportsImage = false;
        boolean supportsPDF = false;

        try {
            DocFlavor[] flavors = printService.getSupportedDocFlavors();
            if (flavors != null) {
                for (DocFlavor flavor : flavors) {
                    String mimeType = flavor.getMimeType();
                    if (mimeType == null) continue;
                    String lower = mimeType.toLowerCase();

                    if (flavor.equals(DocFlavor.BYTE_ARRAY.AUTOSENSE)
                            || flavor.equals(DocFlavor.INPUT_STREAM.AUTOSENSE)
                            || flavor.equals(DocFlavor.URL.AUTOSENSE)) {
                        supportsRaw = true;
                    }
                    if (lower.contains("image/png") || lower.contains("image/jpeg")
                            || lower.contains("image/gif") || lower.contains("image")) {
                        supportsImage = true;
                    }
                    if (lower.contains("application/pdf")) {
                        supportsPDF = true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Capability probe failed for {}: {}", printService.getName(), e.getMessage());
        }

        PrinterCapabilities caps = new PrinterCapabilities(supportsRaw, supportsImage, supportsPDF);
        log.info("Printer capabilities for '{}': raw={}, image={}, pdf={}",
                printService.getName(), caps.supportsRaw, caps.supportsImage, caps.supportsPDF);
        return caps;
    }

    /**
     * Immutable container for a printer's detected content-type capabilities.
     */
    @Getter
    @AllArgsConstructor
    static class PrinterCapabilities {
        private final boolean supportsRaw;
        private final boolean supportsImage;
        private final boolean supportsPDF;

        /**
         * A printer that supports only raw (AUTOSENSE) and neither image nor PDF
         * is almost certainly a thermal/ESC-POS printer.
         */
        boolean isThermalOnly() {
            return supportsRaw && !supportsImage && !supportsPDF;
        }
    }

    // --- Smart dispatch with fallback ---

    /**
     * Prints raw content, with automatic fallback to image rendering if the printer
     * does not support raw/AUTOSENSE (e.g. a laser printer receiving ESC/POS data).
     *
     * <p>If the printer supports raw, it prints raw directly. Otherwise, it decodes the
     * raw content as text and renders it as a PNG image, then prints via the image pipeline.
     *
     * @param printDocument the print job containing raw_content
     * @param printerSearchResult the resolved printer
     * @throws Exception if printing fails
     */
    private void printRawWithFallback(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        PrintService printService = printerSearchResult.getDocPrintJob().getPrintService();
        PrinterCapabilities caps = detectPrinterCapabilities(printService);

        if (caps.supportsRaw) {
            printRaw(printDocument, printerSearchResult);
        } else {
            log.info("Printer '{}' does not support raw/AUTOSENSE, converting raw content to image",
                    printerSearchResult.getName());
            printRawAsImage(printDocument, printerSearchResult);
        }
    }

    /**
     * Prints image content, with fallback to PDF if the printer supports PDF but not images.
     *
     * @param printDocument the print job containing an image URL/content
     * @param printerSearchResult the resolved printer
     * @throws Exception if printing fails
     */
    private void printImageWithFallback(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        PrintService printService = printerSearchResult.getDocPrintJob().getPrintService();
        PrinterCapabilities caps = detectPrinterCapabilities(printService);

        if (caps.supportsImage) {
            printImage(printDocument, printerSearchResult);
        } else if (caps.supportsPDF) {
            log.info("Printer '{}' does not support image, converting image to PDF",
                    printerSearchResult.getName());
            printImageAsPDF(printDocument, printerSearchResult);
        } else {
            // Even thermal-only printers can receive image via Java's Printable API
            // (the driver rasterizes it). So try printImage as last resort.
            log.info("Printer '{}' capability unclear, attempting image print via Printable API",
                    printerSearchResult.getName());
            printImage(printDocument, printerSearchResult);
        }
    }

    /**
     * Prints PDF content, with fallback to image if the printer does not support PDF.
     *
     * @param printDocument the print job containing a PDF URL/content
     * @param printerSearchResult the resolved printer
     * @throws Exception if printing fails
     */
    private void printPDFWithFallback(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        PrintService printService = printerSearchResult.getDocPrintJob().getPrintService();
        PrinterCapabilities caps = detectPrinterCapabilities(printService);

        if (caps.supportsPDF || caps.supportsImage) {
            // PDFBox's PDFPrintable goes through Java's Printable API, which works
            // for both PDF-capable and image-capable printers (it rasterizes if needed).
            printPDF(printDocument, printerSearchResult);
        } else {
            // Last resort: rasterize the PDF to an image and print via Printable API
            log.info("Printer '{}' supports neither PDF nor image natively, rasterizing PDF",
                    printerSearchResult.getName());
            printPDF(printDocument, printerSearchResult);
        }
    }

    /**
     * Decodes raw_content (Base64) as text, renders it as a PNG image, and prints it
     * via the Printable API. Used when a non-thermal printer receives raw/ESC-POS data.
     *
     * <p>The raw bytes are decoded and interpreted as text (UTF-8 with ISO-8859-1 fallback).
     * Control characters (ESC/POS commands) are filtered out, leaving only printable text.
     * The text is rendered onto a receipt-sized image (max 576px wide for 80mm paper)
     * with automatic line wrapping.
     *
     * @param printDocument the print job containing raw_content
     * @param printerSearchResult the resolved printer
     * @throws Exception if rendering or printing fails
     */
    private void printRawAsImage(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printRawAsImage::{}", printDocument.getType());
        long timeStart = System.currentTimeMillis();

        byte[] rawBytes = Base64.decodeBase64(printDocument.getRawContent());
        String text = decodeRawAsText(rawBytes);
        BufferedImage image = renderTextToImage(text);

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(printerSearchResult.getDocPrintJob().getPrintService());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);

        Book book = new Book();
        AnnotatedPrintable printable = new AnnotatedPrintable(new ImagePrintable(image));

        for (AnnotatedPrintable.AnnotatedPrintableAnnotation extra : printDocument.getExtras()) {
            printable.addAnnotation(extra);
        }

        book.append(printable, pageFormat);
        job.setPageable(book);
        job.setJobName("raw-converted-" + printDocument.getType());
        job.setCopies(printDocument.getQty());
        job.print(buildPrintAttributes(printDocument));

        long timeFinish = System.currentTimeMillis();
        log.info("printRawAsImage {} finished in {} ms", printDocument.getType(), timeFinish - timeStart);
    }

    /**
     * Converts an image file to a single-page PDF and prints it via the PDF pipeline.
     * Used when a printer supports PDF but not images directly.
     *
     * @param printDocument the print job containing an image
     * @param printerSearchResult the resolved printer
     * @throws Exception if conversion or printing fails
     */
    private void printImageAsPDF(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printImageAsPDF::{}", printDocument.getType());
        File imageFile = documentService.prepareDocument(printDocument);

        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                throw new Exception("Failed to read image: " + imageFile.getName());
            }

            File pdfFile = convertImageToPDF(image, imageFile);
            try {
                printPDFFromFile(pdfFile, printDocument, printerSearchResult);
            } finally {
                FileUtils.deleteQuietly(pdfFile);
            }
        } finally {
            documentService.deleteDocument(printDocument);
        }
    }

    /**
     * Prints a PDF file from disk via the PDFBox pipeline.
     *
     * @param pdfFile the PDF file to print
     * @param printDocument the original print job (for qty, extras, attributes)
     * @param printerSearchResult the resolved printer
     * @throws Exception if printing fails
     */
    private void printPDFFromFile(File pdfFile, PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        long timeStart = System.currentTimeMillis();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(printerSearchResult.getDocPrintJob().getPrintService());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);

        try (PDDocument document = PDDocument.load(pdfFile)) {
            Book book = new Book();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PageFormat eachPageFormat = (PageFormat) pageFormat.clone();

                if (printerSearchResult.getMapping().isAutoRotate()) {
                    if (document.getPage(i).getCropBox().getWidth() > document.getPage(i).getCropBox().getHeight()) {
                        eachPageFormat.setOrientation(PageFormat.LANDSCAPE);
                    } else {
                        eachPageFormat.setOrientation(PageFormat.PORTRAIT);
                    }
                }

                PDFPrintable pdfPrintable = new PDFPrintable(document, Scaling.SHRINK_TO_FIT, false, printerSearchResult.getMapping().getForceDPI());
                AnnotatedPrintable annotatedPrintable = new AnnotatedPrintable(pdfPrintable);
                for (AnnotatedPrintable.AnnotatedPrintableAnnotation extra : printDocument.getExtras()) {
                    annotatedPrintable.addAnnotation(extra);
                }
                book.append(annotatedPrintable, eachPageFormat);
            }

            job.setPageable(book);
            job.setJobName(pdfFile.getName());
            job.setCopies(printDocument.getQty());
            job.print(buildPrintAttributes(printDocument));

            long timeFinish = System.currentTimeMillis();
            log.info("printPDFFromFile {} finished in {} ms", pdfFile.getName(), timeFinish - timeStart);
        }
    }

    // --- Text-to-image rendering helpers ---

    /**
     * Decodes raw bytes (from raw_content) into a text string.
     *
     * <p>Tries UTF-8 first, then falls back to ISO-8859-1 (which never fails).
     * ESC/POS control sequences (bytes outside printable ASCII range) are replaced
     * with spaces or newlines (for LF/CR).
     *
     * @param rawBytes the raw byte array
     * @return decoded text with control characters filtered
     */
    static String decodeRawAsText(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return "";
        }
        String decoded;
        try {
            decoded = new String(rawBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = new String(rawBytes, StandardCharsets.ISO_8859_1);
        }

        StringBuilder sb = new StringBuilder(decoded.length());
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append('\n');
            } else if (c >= 32 && c <= 126) {
                // Printable ASCII
                sb.append(c);
            } else if (c >= 128 && c <= 255) {
                // Extended Latin-1 characters (accented letters etc.)
                sb.append(c);
            }
            // Skip all other control characters (ESC, GS, etc.)
        }
        return sb.toString();
    }

    /**
     * Renders text into a BufferedImage suitable for receipt printing.
     *
     * <p>Uses a monospace font, wraps lines to fit the image width (default 576px
     * for 80mm thermal paper at 72 DPI), and calculates the image height dynamically
     * based on the number of lines.
     *
     * @param text the text to render
     * @return a BufferedImage containing the rendered text
     */
    static BufferedImage renderTextToImage(String text) {
        if (text == null || text.isEmpty()) {
            text = "";
        }
        int imageWidth = 576;
        int margin = 10;
        int font_SIZE = 12;
        int lineSpacing = 4;

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, font_SIZE);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tmpG = tmp.createGraphics();
        tmpG.setFont(font);
        FontMetrics fm = tmpG.getFontMetrics();
        int charWidth = fm.charWidth('M');
        int lineHeight = fm.getHeight() + lineSpacing;
        tmpG.dispose();

        int maxCharsPerLine = Math.max(1, (imageWidth - 2 * margin) / charWidth);

        String[] rawLines = text.split("\n", -1);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : rawLines) {
            if (line.isEmpty()) {
                lines.add("");
                continue;
            }
            // Wrap long lines
            int pos = 0;
            while (pos < line.length()) {
                int end = Math.min(pos + maxCharsPerLine, line.length());
                lines.add(line.substring(pos, end));
                pos = end;
            }
        }

        int imageHeight = Math.max(lineHeight * lines.size() + 2 * margin, lineHeight + 2 * margin);
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, imageWidth, imageHeight);

        g2d.setColor(Color.BLACK);
        g2d.setFont(font);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int y = margin + fm.getAscent();
        for (String line : lines) {
            g2d.drawString(line, margin, y);
            y += lineHeight;
        }
        g2d.dispose();

        return image;
    }

    // --- Image-to-PDF conversion ---

    /**
     * Converts a BufferedImage to a single-page PDF file.
     *
     * @param image the image to convert
     * @param baseFile the original image file (used to derive the PDF filename)
     * @return the generated PDF file
     * @throws Exception if PDF creation fails
     */
    private File convertImageToPDF(BufferedImage image, File baseFile) throws Exception {
        File pdfFile = new File(baseFile.getParentFile(),
                baseFile.getName().replaceAll("\\.[^.]+$", "") + ".pdf");

        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);

            org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page);

            // Fit image to page with margins
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float margin = 36; // 0.5 inch
            float maxWidth = pageWidth - 2 * margin;
            float maxHeight = pageHeight - 2 * margin;

            float imageWidth = image.getWidth();
            float imageHeight = image.getHeight();
            float scale = Math.min(maxWidth / imageWidth, maxHeight / imageHeight);
            float scaledWidth = imageWidth * scale;
            float scaledHeight = imageHeight * scale;
            float x = (pageWidth - scaledWidth) / 2;
            float y = (pageHeight - scaledHeight) / 2;

            // Convert BufferedImage to PDFBox image
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                    org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(
                            doc, baos.toByteArray(), "converted-image");

            contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight);
            contentStream.close();

            doc.save(pdfFile);
        }

        log.info("Converted image {} to PDF {}", baseFile.getName(), pdfFile.getName());
        return pdfFile;
    }

    /**
     * Prints raw bytes to specified printer.
     */
    private void printRaw(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws PrintException {
        log.debug("printRaw::{}", printDocument);
        long timeStart = System.currentTimeMillis();

        byte[] bytes = Base64.decodeBase64(printDocument.getRawContent());

        DocPrintJob docPrintJob = printerSearchResult.getDocPrintJob();
        Doc doc = new SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        docPrintJob.print(doc, buildPrintAttributes(printDocument));

        long timeFinish = System.currentTimeMillis();
        log.info("printRaw finished in {} ms", timeFinish - timeStart);
    }

    /**
     * Prints image to specified printer.
     */
    private void printImage(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printImage::{}", printDocument);

        File file = documentService.prepareDocument(printDocument);
        String path = file.getPath();
        String filename = file.getName();

        long timeStart = System.currentTimeMillis();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(printerSearchResult.getDocPrintJob().getPrintService());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);

        Image image = ImageIO.read(new File(path));

        Book book = new Book();
        AnnotatedPrintable printable = new AnnotatedPrintable(new ImagePrintable(image));

        for (AnnotatedPrintable.AnnotatedPrintableAnnotation printDocumentExtra : printDocument.getExtras()) {
            printable.addAnnotation(printDocumentExtra);
        }

        book.append(printable, pageFormat);

        job.setPageable(book);
        job.setJobName(filename);
        job.setCopies(printDocument.getQty());
        job.print(buildPrintAttributes(printDocument));

        long timeFinish = System.currentTimeMillis();

        log.info("printImage {} finished in {} ms", filename, timeFinish - timeStart);
    }

    /**
     * Prints PDF to specified printer.
     */
    private void printPDF(PrintDocument printDocument, PrinterSearchResult printerSearchResult) throws Exception {
        log.debug("printPDF::{}", printDocument);

        File file = documentService.prepareDocument(printDocument);
        String path = file.getPath();
        String filename = file.getName();

        long timeStart = System.currentTimeMillis();

        DocPrintJob docPrintJob = printerSearchResult.getDocPrintJob();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(docPrintJob.getPrintService());

        PageFormat pageFormat = getPageFormat(job, printerSearchResult);

        try (PDDocument document = PDDocument.load(new File(path))) {
            Book book = new Book();
            for (int i = 0; i < document.getNumberOfPages(); i += 1) {
                // Rotate Page Automatically
                PageFormat eachPageFormat = (PageFormat) pageFormat.clone();

                if (printerSearchResult.getMapping().isAutoRotate()) {
                    if (document.getPage(i).getCropBox().getWidth() > document.getPage(i).getCropBox().getHeight()) {
                        log.debug("Auto rotation result: LANDSCAPE");
                        eachPageFormat.setOrientation(PageFormat.LANDSCAPE);
                    } else {
                        log.debug("Auto rotation result: PORTRAIT");
                        eachPageFormat.setOrientation(PageFormat.PORTRAIT);
                    }
                }

                PDFPrintable pdfPrintable = new PDFPrintable(document, Scaling.SHRINK_TO_FIT, false, printerSearchResult.getMapping().getForceDPI());

                // Annotate Printable
                AnnotatedPrintable annotatedPrintable = new AnnotatedPrintable(pdfPrintable);
                for (AnnotatedPrintable.AnnotatedPrintableAnnotation printDocumentExtra : printDocument.getExtras()) {
                    annotatedPrintable.addAnnotation(printDocumentExtra);
                }

                book.append(annotatedPrintable, eachPageFormat);
            }

            job.setPageable(book);
            job.setJobName(filename);
            job.setCopies(printDocument.getQty());
            job.print(buildPrintAttributes(printDocument));

            long timeFinish = System.currentTimeMillis();

            log.info("printPDF {} finished in {} ms", path, timeFinish - timeStart);
        }
    }

    private PageFormat getPageFormat(PrinterJob job, PrinterSearchResult printerSearchResult) {
        final PageFormat pageFormat = job.defaultPage();

        log.debug("PageFormat Size: {} x {}", pageFormat.getWidth(), pageFormat.getHeight());
        log.debug("PageFormat Imageable Size:{} x {}, XY: {}, {}", pageFormat.getImageableWidth(), pageFormat.getImageableHeight(), pageFormat.getImageableX(), pageFormat.getImageableY());
        log.debug("Paper Size: {} x {}", pageFormat.getPaper().getWidth(), pageFormat.getPaper().getHeight());
        log.debug("Paper Imageable Size: {} x {}, XY: {}, {}", pageFormat.getPaper().getImageableWidth(), pageFormat.getPaper().getImageableHeight(), pageFormat.getPaper().getImageableX(), pageFormat.getPaper().getImageableY());

        // Reset Imageable Area
        if (printerSearchResult.getMapping().isResetImageableArea()) {
            log.debug("PageFormat reset enabled");
            Paper paper = pageFormat.getPaper();
            paper.setImageableArea(0, 0, paper.getWidth(), paper.getHeight());
            pageFormat.setPaper(paper);
        }

        log.debug("Final Paper Size: {} x {}", pageFormat.getPaper().getWidth(), pageFormat.getPaper().getHeight());
        log.debug("Final Paper Imageable Size: {} x {}, XY: {}, {}", pageFormat.getPaper().getImageableWidth(), pageFormat.getPaper().getImageableHeight(), pageFormat.getPaper().getImageableX(), pageFormat.getPaper().getImageableY());

        return pageFormat;
    }

    /**
     * Builds a {@link PrintRequestAttributeSet} from the optional print options
     * carried by a {@link PrintDocument}: duplex (recto-verso), color (couleur/NB),
     * paper tray (bac), and copies.
     *
     * <p>All fields are optional (null = printer default). Unknown paper-tray
     * names are silently ignored so a bad value never breaks a print job.
     *
     * @param printDocument the print job (may have null duplex/color/paperTray)
     * @return a mutable attribute set (never null, possibly empty)
     */
    private PrintRequestAttributeSet buildPrintAttributes(PrintDocument printDocument) {
        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

        if (printDocument.getDuplex() != null) {
            attrs.add(printDocument.getDuplex() ? Sides.DUPLEX : Sides.ONE_SIDED);
        }

        if (printDocument.getColor() != null) {
            attrs.add(printDocument.getColor() ? Chromaticity.COLOR : Chromaticity.MONOCHROME);
        }

        if (printDocument.getPaperTray() != null && !printDocument.getPaperTray().isEmpty()) {
            MediaTray tray = mapPaperTray(printDocument.getPaperTray());
            if (tray != null) {
                attrs.add(tray);
            } else {
                log.warn("Unknown paper_tray '{}', ignoring", printDocument.getPaperTray());
            }
        }

        if (printDocument.getQty() != null && printDocument.getQty() > 0) {
            attrs.add(new Copies(printDocument.getQty()));
        }

        return attrs;
    }

    /**
     * Maps a paper-tray string to a {@link MediaTray} constant.
     *
     * <p>Supports the standard Java {@link MediaTray} values (case-insensitive):
     * MAIN, MANUAL, TOP, BOTTOM, SIDE, ENVELOPE, LARGE_CAPACITY.
     * Returns {@code null} for unknown or null/empty values.
     *
     * @param trayName the tray name from the print request (case-insensitive)
     * @return the matching MediaTray, or null if not recognised
     */
    private MediaTray mapPaperTray(String trayName) {
        if (trayName == null || trayName.trim().isEmpty()) {
            return null;
        }
        switch (trayName.trim().toUpperCase()) {
            case "MAIN":           return MediaTray.MAIN;
            case "MANUAL":         return MediaTray.MANUAL;
            case "TOP":            return MediaTray.TOP;
            case "BOTTOM":         return MediaTray.BOTTOM;
            case "SIDE":           return MediaTray.SIDE;
            case "ENVELOPE":       return MediaTray.ENVELOPE;
            case "LARGE_CAPACITY": return MediaTray.LARGE_CAPACITY;
            default:               return null;
        }
    }

    /**
     * Get PrinterSearchResult for specified type
     */
    private PrinterSearchResult searchPrinterForType(String type) throws PrinterException {
        PrintService[] printServices = PrintServiceLookup.lookupPrintServices(null, null);

        Optional<Config.PrinterMapping> printerMappingOptional = configService.getConfig().getPrinter().getMappings().stream().filter(it -> it.getType().equals(type)).findFirst();

        if (printerMappingOptional.isPresent()) {
            Config.PrinterMapping printerMapping = printerMappingOptional.get();

            for (PrintService printService : printServices) {
                if (printService.getName().equalsIgnoreCase(printerMapping.getName())) {
                    log.info("Sending print job type: {} to printer: {}", type, printService.getName());

                    return new PrinterSearchResult(printService.getName(), printerMapping, printService.createPrintJob(), false);
                }
            }

            throw new PrinterException("Printer not found on system: " + printerMapping.getName() + " (mapped to type: " + type + ")");
        }

         if (configService.getConfig().getPrinter().isAutoAddUnknownType()) {
             // Add unknown type does not already exist
             if (configService.getConfig().getPrinter().getMappings().stream().noneMatch(it -> it.getType().equals(type))) {
                 configService.addPrintTypeToList(type);
             }
        }

         if (configService.getConfig().getPrinter().isFallbackToDefault()) {
             log.info("No mapped print job type: {}, falling back to default printer", type);

             PrintService printService = PrintServiceLookup.lookupDefaultPrintService();

             if (printService == null) {
                 throw new PrinterException("No default printer found");
             }

             return new PrinterSearchResult(printService.getName(), new Config.PrinterMapping(), printService.createPrintJob(), true);
        }

         throw new PrinterException("No printer mapping found for type: " + type);
    }

    @Getter
    @AllArgsConstructor
    private static class PrinterSearchResult {
        private String name;
        private Config.PrinterMapping mapping;
        private DocPrintJob docPrintJob;
        private Boolean isDefault;
    }
}
