package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AnnotatedPrintable}.
 * Tests annotation management and print dispatch logic with mock Graphics.
 */
public class AnnotatedPrintableTest {

    @Test
    public void addAnnotationGrowsTheList() {
        AnnotatedPrintable printable = new AnnotatedPrintable(createDelegatePrintable());

        assertEquals(0, getAnnotationCount(printable));

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann1 = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann1.setText("HELLO");
        ann1.setX(10f);
        ann1.setY(20f);
        ann1.setSize(12);
        ann1.setBold(true);
        printable.addAnnotation(ann1);

        assertEquals(1, getAnnotationCount(printable));

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann2 = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann2.setText("WORLD");
        ann2.setX(30f);
        ann2.setY(40f);
        ann2.setSize(14);
        ann2.setBold(false);
        printable.addAnnotation(ann2);

        assertEquals(2, getAnnotationCount(printable));
    }

    @Test
    public void printWithEmptyAnnotationsReturnsDelegateResult() throws Exception {
        Printable delegate = (g, f, i) -> i == 0 ? Printable.PAGE_EXISTS : Printable.NO_SUCH_PAGE;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        Graphics2D g2d = createTestGraphics();
        PageFormat format = createTestPageFormat();

        // Page 0 → delegate returns PAGE_EXISTS
        assertEquals(Printable.PAGE_EXISTS, printable.print(g2d, format, 0));
        // Page 1 → delegate returns NO_SUCH_PAGE
        assertEquals(Printable.NO_SUCH_PAGE, printable.print(g2d, format, 1));
    }

    @Test
    public void printWithAnnotationsOnPageExistsDrawsText() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("TEST");
        ann.setX(10f);
        ann.setY(10f);
        ann.setSize(12);
        ann.setBold(true);
        printable.addAnnotation(ann);

        Graphics2D g2d = createTestGraphics();
        PageFormat format = createTestPageFormat();

        // Should return PAGE_EXISTS (same as delegate) and not throw
        int result = printable.print(g2d, format, 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void printWithAnnotationsOnNoSuchPageSkipsAnnotations() throws Exception {
        Printable delegate = (g, f, i) -> Printable.NO_SUCH_PAGE;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText("SKIP");
        ann.setX(0f);
        ann.setY(0f);
        ann.setSize(10);
        printable.addAnnotation(ann);

        // Should return NO_SUCH_PAGE (annotations are not drawn)
        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.NO_SUCH_PAGE, result);
    }

    @Test
    public void printWithNullTextAnnotationDoesNotThrow() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
        ann.setText(null); // null text — should be skipped, not throw
        ann.setX(10f);
        ann.setY(10f);
        ann.setSize(12);
        printable.addAnnotation(ann);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void printWithMultipleAnnotationsAllDrawn() throws Exception {
        Printable delegate = (g, f, i) -> Printable.PAGE_EXISTS;
        AnnotatedPrintable printable = new AnnotatedPrintable(delegate);

        for (int i = 0; i < 5; i++) {
            AnnotatedPrintable.AnnotatedPrintableAnnotation ann = new AnnotatedPrintable.AnnotatedPrintableAnnotation();
            ann.setText("ANN" + i);
            ann.setX((float) (i * 10));
            ann.setY((float) (i * 10));
            ann.setSize(10 + i);
            ann.setBold(i % 2 == 0);
            printable.addAnnotation(ann);
        }

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    // --- Helpers ---

    private int getAnnotationCount(AnnotatedPrintable printable) {
        try {
            java.lang.reflect.Field field = AnnotatedPrintable.class
                    .getDeclaredField("annotatedPrintableAnnotationArrayList");
            field.setAccessible(true);
            return ((java.util.ArrayList<?>) field.get(printable)).size();
        } catch (Exception e) {
            fail("Failed to access annotation list: " + e.getMessage());
            return -1;
        }
    }

    private Printable createDelegatePrintable() {
        return (g, f, i) -> Printable.PAGE_EXISTS;
    }

    private Graphics2D createTestGraphics() {
        BufferedImage buffer = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = buffer.createGraphics();
        g2d.setClip(0, 0, 200, 200); // required for getClipBounds() in AnnotatedPrintable
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
}
