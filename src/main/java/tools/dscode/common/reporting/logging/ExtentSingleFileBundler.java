package tools.dscode.common.reporting.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundles an Extent Spark offline report (HTML + spark/ assets)
 * into a SINGLE self-contained HTML file.
 *
 * Works by inlining:
 *  - spark CSS
 *  - spark JS
 *  - fonts/images referenced from CSS (as data URIs)
 *
 * Call AFTER extent.flush().
 */
public final class ExtentSingleFileBundler {

    private static final boolean DEBUG = true;

    private ExtentSingleFileBundler() {}

    /* --------------------------------------------------------------------- */

    public static Path bundle(Path reportHtml) {
        return bundle(reportHtml, reportHtml.resolveSibling(stripExt(reportHtml) + "-single.html"));
    }

    public static Path bundleInPlace(Path reportHtml) {
        return bundle(reportHtml, reportHtml);
    }

    public static Path bundle(Path reportHtml, Path outputHtml) {
        try {
            dbg("Bundling Extent Spark report: " + reportHtml.toAbsolutePath());

            String html = Files.readString(reportHtml, StandardCharsets.UTF_8);
            Path baseDir = reportHtml.getParent();
            Path sparkDir = baseDir.resolve("spark");

            if (!Files.isDirectory(sparkDir)) {
                dbg("No spark/ directory found — nothing to bundle");
                Files.writeString(outputHtml, html, StandardCharsets.UTF_8);
                return outputHtml;
            }

            // Inline CSS
            html = inlineCss(html, sparkDir);

            // Inline JS
            html = inlineJs(html, sparkDir);

            Files.writeString(outputHtml, html, StandardCharsets.UTF_8);

            dbg("Bundled HTML written to: " + outputHtml.toAbsolutePath());
            return outputHtml;

        } catch (IOException e) {
            throw new RuntimeException("Failed to bundle Extent Spark report", e);
        }
    }

    /* --------------------------------------------------------------------- */

    private static String inlineCss(String html, Path sparkDir) throws IOException {
        Pattern linkCss = Pattern.compile(
                "<link[^>]+href=[\"'](spark/[^\"']+\\.css)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = linkCss.matcher(html);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String href = m.group(1);
            Path cssPath = sparkDir.getParent().resolve(href);

            dbg("Inlining CSS: " + href);

            String css = Files.readString(cssPath, StandardCharsets.UTF_8);
            css = inlineCssAssets(css, cssPath.getParent());

            String replacement = "<style>\n" + css + "\n</style>";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String inlineJs(String html, Path sparkDir) throws IOException {
        Pattern scriptJs = Pattern.compile(
                "<script[^>]+src=[\"'](spark/[^\"']+\\.js)[\"'][^>]*></script>",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = scriptJs.matcher(html);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String src = m.group(1);
            Path jsPath = sparkDir.getParent().resolve(src);

            dbg("Inlining JS: " + src);

            String js = Files.readString(jsPath, StandardCharsets.UTF_8);
            String replacement = "<script>\n" + js + "\n</script>";

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /* --------------------------------------------------------------------- */

    /**
     * Replace url(...) references inside CSS with data URIs
     */
    private static String inlineCssAssets(String css, Path cssDir) throws IOException {
        Pattern urlPattern = Pattern.compile("url\\(['\"]?([^'\")]+)['\"]?\\)");

        Matcher m = urlPattern.matcher(css);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String url = m.group(1);

            // Skip already inlined or remote URLs
            if (url.startsWith("data:") || url.startsWith("http")) {
                m.appendReplacement(sb, m.group(0));
                continue;
            }

            Path asset = cssDir.resolve(url).normalize();
            if (!Files.exists(asset)) {
                dbg("CSS asset not found: " + asset);
                m.appendReplacement(sb, m.group(0));
                continue;
            }

            String mime = mimeType(asset);
            byte[] bytes = Files.readAllBytes(asset);
            String b64 = Base64.getEncoder().encodeToString(bytes);

            dbg("Inlining CSS asset: " + asset.getFileName() + " (" + mime + ")");

            String dataUri = "url('data:" + mime + ";base64," + b64 + "')";
            m.appendReplacement(sb, Matcher.quoteReplacement(dataUri));
        }

        m.appendTail(sb);
        return sb.toString();
    }

    /* --------------------------------------------------------------------- */

    private static String mimeType(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".otf")) return "font/otf";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static String stripExt(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static void dbg(String s) {
        if (DEBUG) System.out.println("[ExtentSingleFileBundler] " + s);
    }
}
