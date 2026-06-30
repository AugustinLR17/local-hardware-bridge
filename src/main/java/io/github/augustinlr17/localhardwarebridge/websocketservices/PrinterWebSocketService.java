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
import java.awt.print.*;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
                printRaw(printDocument, printerSearchResult);
            } else if (isImage(printDocument)) {
                printImage(printDocument, printerSearchResult);
            } else if (isPDF(printDocument)) {
                printPDF(printDocument, printerSearchResult);
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
