import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the app's icon and TV banner. Run from launcher-ui/: java art/MakeArt.java */
public class MakeArt {
    static final Color FROM = new Color(0x0B1E3F), TO = new Color(0x13A89E), INK = new Color(0xF5F7FA);

    public static void main(String[] args) throws Exception {
        ImageIO.write(icon(192), "png", new File("app/res/drawable-nodpi/icon.png"));
        ImageIO.write(banner(640, 360), "png", new File("app/res/drawable-nodpi/banner.png"));
    }

    static Graphics2D paint(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static BufferedImage icon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = paint(img);
        g.setPaint(new GradientPaint(0, 0, FROM, s, s, TO));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.45f, s * 0.45f));
        tv(g, s * 0.2f, s * 0.24f, s * 0.6f, s);
        g.dispose();
        return img;
    }

    static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = paint(img);
        g.setPaint(new GradientPaint(0, 0, FROM, w, h, TO));
        g.fillRect(0, 0, w, h);
        tv(g, 56, h * 0.31f, 120, w);
        g.setColor(INK);
        g.setFont(new Font("Helvetica Neue", Font.BOLD, 54));
        g.drawString("CleanYourTV", 206, h * 0.5f + 6);
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 30));
        g.setColor(new Color(0xD8F3F0));
        g.drawString("UI", 206, h * 0.5f + 48);
        g.dispose();
        return img;
    }

    /** A TV outline with a sparkle on the screen, `w` wide, its top-left at (x, y). */
    static void tv(Graphics2D g, float x, float y, float w, float scale) {
        float h = w * 0.62f, stroke = w * 0.075f;
        g.setColor(INK);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, w * 0.16f, w * 0.16f));
        g.draw(new java.awt.geom.Line2D.Float(x + w * 0.32f, y + h + w * 0.16f, x + w * 0.68f, y + h + w * 0.16f));
        sparkle(g, x + w * 0.5f, y + h * 0.5f, h * 0.3f);
    }

    static void sparkle(Graphics2D g, float cx, float cy, float r) {
        float t = r * 0.22f;
        Path2D p = new Path2D.Float();
        p.moveTo(cx, cy - r);
        p.quadTo(cx + t * 0.4f, cy - t * 0.4f, cx + r, cy);
        p.quadTo(cx + t * 0.4f, cy + t * 0.4f, cx, cy + r);
        p.quadTo(cx - t * 0.4f, cy + t * 0.4f, cx - r, cy);
        p.quadTo(cx - t * 0.4f, cy - t * 0.4f, cx, cy - r);
        p.closePath();
        g.fill(p);
    }
}
