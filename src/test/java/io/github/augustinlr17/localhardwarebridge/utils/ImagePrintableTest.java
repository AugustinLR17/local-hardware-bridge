package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link ImagePrintable}.
 * Uses a BufferedImage + mock PageFormat to test the print logic
 * without a real printer.
 */
public class ImagePrintableTest {

    @Test
    public void pageIndexZeroReturnsPageExists() throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImagePrintable printable = new ImagePrintable(image);

        Graphics2D g2d = createTestGraphics();
        PageFormat format = createTestPageFormat();

        int result = printable.print(g2d, format, 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    @Test
    public void pageIndexOneReturnsNoSuchPage() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImagePrintable printable = new ImagePrintable(image);

        Graphics2D g2d = createTestGraphics();
        PageFormat format = createTestPageFormat();

        int result = printable.print(g2d, format, 1);
        assertEquals(Printable.NO_SUCH_PAGE, result);
    }

    @Test
    public void pageIndexLargeReturnsNoSuchPage() {
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImagePrintable printable = new ImagePrintable(image);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 99);
        assertEquals(Printable.NO_SUCH_PAGE, result);
    }

    @Test
    public void constructorAcceptsAnyImage() {
        // Even a 1x1 image should be accepted
        BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ImagePrintable printable = new ImagePrintable(tiny);

        int result = printable.print(createTestGraphics(), createTestPageFormat(), 0);
        assertEquals(Printable.PAGE_EXISTS, result);
    }

    private Graphics2D createTestGraphics() {
        BufferedImage buffer = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        return buffer.createGraphics();
    }

    private PageFormat createTestPageFormat() {
        PageFormat format = new PageFormat();
        Paper paper = new Paper();
        paper.setSize(595, 842); // A4
        paper.setImageableArea(0, 0, 595, 842);
        format.setPaper(paper);
        return format;
    }
}
