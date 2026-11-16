import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成自适应图标的前景层：透明底 + 白色「网易」两字。
 *
 * <p>用法：{@code java IconGen <输出 png 路径> [字号]}。108dp 画布按 4x 输出为 432px，
 * 系统裁切的安全区是中心 72dp（288px），所以字宽必须留在 288px 以内。</p>
 */
public final class IconGen {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: IconGen <out.png> [fontSize]");
            System.exit(2);
        }
        int size = 432;
        int fontSize = args.length > 1 ? Integer.parseInt(args[1]) : 128;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        if (args.length > 2) {
            g.setColor(Color.decode(args[2]));
            g.fillRect(0, 0, size, size);
        } else {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, size, size);
            g.setComposite(AlphaComposite.SrcOver);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, fontSize));
        String text = "\u7F51\u6613";
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        int x = (size - w) / 2;
        int y = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(text, x, y);
        g.dispose();

        File out = new File(args[0]);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        ImageIO.write(img, "png", out);
        System.out.println("written " + out.getAbsolutePath()
                + " textWidth=" + w + " textHeight=" + fm.getHeight());
    }
}
