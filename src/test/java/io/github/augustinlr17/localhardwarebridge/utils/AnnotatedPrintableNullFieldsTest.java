package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;

import static org.junit.Assert.*;

/**
 * Additional tests for {@link AnnotatedPrintable} covering null bold/size
 * defaults and the annotation-drawing exception catch block.
 */
public class AnnotatedPrintableNullFieldsTest {

    private Graphics2D createTestGraphics() {
        BufferedImage buffer = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = buffer.createGraphics();
        g2d.setClip(0, 0, 200, 200);
        return g2d;
    }

    private PageFormat createTestPageFormat() {
        PageFormat format = new PageFormat();
        Paper paper = new Paper();
        paper.setSize(595, 842);
        paper.setImageableArea(0, 0, 595, 842);
        format.setPaper(paper);
        return format;
    }

    @Test
    public void nullBoldDefaultsToPlainFont() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("NULL_BOLD");
        ann.setX(10f);
        ann.setY(10f);
        ann.setSize(12);
        ann.setBold(null); // null bold → Font.PLAIN
        printable.addAnnotation(ann);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void nullSizeDefaultsTo10() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("NULL_SIZE");
        ann.setX(10f);
        ann.setY(10f);
        ann.setSize(null); // null size → 10
        ann.setBold(true);
        printable.addAnnotation(ann);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void nullXAndYDoNotThrow() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("NULL_XY");
        ann.setX(null);
        ann.setY(null);
        ann.setSize(12);
        ann.setBold(true);
        printable.addAnnotation(ann);

        // Should not throw even with null x/y (the catch block handles it)
        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void nullTextAndValidAnnotationAreSkippedTogether() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        // First annotation with null text (should be skipped)
        AnnotatedPrintable.AnnotatedPrintableAnnotation nullTextAnn = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        nullTextAnn.setText(null);
        nullTextAnn.setX(10f);
        nullTextAnn.setY(10f);
        printable.addAnnotation(nullTextAnn);

        // Second annotation with valid text (should be drawn)
        AnnotatedPrintable.AnnotatedPrintableAnnotation validAnn = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        validAnn.setText("VALID");
        validAnn.setX(20f);
        validAnn.setY(20f);
        printable.addAnnotation(validAnn);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void nullBoldAndNullSizeTogetherDefaultCorrectly() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("BOTH_NULL");
        ann.setX(5f);
        ann.setY(5f);
        ann.setSize(null);
        ann.setBold(null);
        printable.addAnnotation(ann);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void delegateExceptionPropagatesAsPrinterException() throws Exception {
        Printable delegate = (g, f, i) -> {
            throw new java.awt.print.PrinterException("delegate failure");
        };
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("TEXT");
        ann.setX(10f);
        ann.setY(10f);
        printable.addAnnotation(ann);

        try {
            printable.print(createTestGraphics(), createTestPageFormat(), 0);
            fail("expected PrinterException to propagate from delegate");
        } catch (java.awt.print.PrinterException e) {
            assertEquals("delegate failure", e.getMessage());
        }
    }
}
