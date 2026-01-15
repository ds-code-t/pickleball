package tools.dscode.common.reporting.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundles an Extent Spark offline report (HTML + spark/ assets) into ONE self-contained HTML file.
 *
 * IMPORTANT:
 *  Spark offline assets are typically under:
 *    <reportDir>/spark/spark/... and <reportDir>/spark/commons/...
 *  So we resolve asset URLs relative to report HTML's parent directory.
 */
public final class ExtentSingleFileBundler {

    private static final boolean DEBUG = true;

    private ExtentSingleFileBundler() {}

    public static Path bundleInPlace(Path reportHtml) {
        return bundle(reportHtml, reportHtml);
    }

    public static Path bundle(Path reportHtml, Path outputHtml) {
        try {
            Path htmlPath = reportHtml.toAbsolutePath();
            Path baseDir = htmlPath.getParent();
            dbg("bundle: html=" + htmlPath);
            dbg("bundle: baseDir=" + baseDir);

            String html = Files.readString(htmlPath, StandardCharsets.UTF_8);

            int cssInlined = 0;
            int jsInlined = 0;

            // Inline CSS <link ... href="...css"...>
            // More permissive than before: handles any attribute order.
            Pattern cssLink = Pattern.compile(
                    "<link\\b[^>]*href\\s*=\\s*[\"']([^\"']+\\.css)[\"'][^>]*>",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher mCss = cssLink.matcher(html);
            StringBuffer outCss = new StringBuffer();

            while (mCss.find()) {
                String href = mCss.group(1);

                // Only inline spark assets (avoid inlining arbitrary 3rd party CSS if any)
                if (!looksLikeSparkAssetUrl(href)) {
                    mCss.appendReplacement(outCss, mCss.group(0));
                    continue;
                }

                Path cssPath = resolveAsset(baseDir, href);
                if (!Files.exists(cssPath)) {
                    dbg("CSS not found; leaving link as-is: href=" + href + " resolved=" + cssPath);
                    mCss.appendReplacement(outCss, mCss.group(0));
                    continue;
                }

                dbg("Inlining CSS: href=" + href + " -> " + cssPath);
                String css = Files.readString(cssPath, StandardCharsets.UTF_8);

                // Inline url(...) assets referenced by the CSS (fonts, images)
                css = inlineCssAssets(css, cssPath.getParent());

                String replacement = "<style>\n" + css + "\n</style>";
                mCss.appendReplacement(outCss, Matcher.quoteReplacement(replacement));
                cssInlined++;
            }
            mCss.appendTail(outCss);
            html = outCss.toString();

            // Inline JS <script ... src="...js"></script>
            Pattern jsScript = Pattern.compile(
                    "<script\\b[^>]*src\\s*=\\s*[\"']([^\"']+\\.js)[\"'][^>]*>\\s*</script>",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher mJs = jsScript.matcher(html);
            StringBuffer outJs = new StringBuffer();

            while (mJs.find()) {
                String src = mJs.group(1);

                if (!looksLikeSparkAssetUrl(src)) {
                    mJs.appendReplacement(outJs, mJs.group(0));
                    continue;
                }

                Path jsPath = resolveAsset(baseDir, src);
                if (!Files.exists(jsPath)) {
                    dbg("JS not found; leaving script as-is: src=" + src + " resolved=" + jsPath);
                    mJs.appendReplacement(outJs, mJs.group(0));
                    continue;
                }

                dbg("Inlining JS: src=" + src + " -> " + jsPath);
                String js = Files.readString(jsPath, StandardCharsets.UTF_8);

                String replacement = "<script>\n" + js + "\n</script>";
                mJs.appendReplacement(outJs, Matcher.quoteReplacement(replacement));
                jsInlined++;
            }
            mJs.appendTail(outJs);
            html = outJs.toString();

            Files.writeString(outputHtml.toAbsolutePath(), html, StandardCharsets.UTF_8);

            dbg("bundle: done -> " + outputHtml.toAbsolutePath());
            dbg("bundle: cssInlined=" + cssInlined + " jsInlined=" + jsInlined);

            return outputHtml;

        } catch (IOException e) {
            throw new RuntimeException("Failed to bundle Extent Spark report", e);
        }
    }

    private static boolean looksLikeSparkAssetUrl(String url) {
        if (url == null) return false;
        String u = url.replace('\\', '/');
        // Spark offline assets are typically referenced like:
        //  spark/spark/css/... OR spark/commons/js/... etc.
        return u.startsWith("spark/") || u.contains("/spark/") || u.contains("/commons/");
    }

    private static Path resolveAsset(Path baseDir, String url) {
        // Strip querystrings like "...css?v=..."
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);

        clean = clean.replace('\\', '/');
        // Make relative to baseDir
        return baseDir.resolve(clean).normalize();
    }

    /**
     * Replace url(...) references inside CSS with data URIs (fonts/images).
     */
    private static String inlineCssAssets(String css, Path cssDir) throws IOException {
        Pattern urlPattern = Pattern.compile("url\\(['\"]?([^'\")]+)['\"]?\\)");

        Matcher m = urlPattern.matcher(css);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String url = m.group(1);

            // Skip already inlined or remote URLs
            if (url.startsWith("data:") || url.startsWith("http:") || url.startsWith("https:")) {
                m.appendReplacement(sb, m.group(0));
                continue;
            }

            // Strip querystrings
            String clean = url;
            int q = clean.indexOf('?');
            if (q >= 0) clean = clean.substring(0, q);

            Path asset = cssDir.resolve(clean).normalize();
            if (!Files.exists(asset)) {
                dbg("CSS asset not found (leaving as-is): " + asset);
                m.appendReplacement(sb, m.group(0));
                continue;
            }

            String mime = mimeType(asset);
            byte[] bytes = Files.readAllBytes(asset);
            String b64 = Base64.getEncoder().encodeToString(bytes);

            dbg("Inlining CSS asset: " + asset.getFileName() + " (" + mime + "), bytes=" + bytes.length);

            String dataUri = "url('data:" + mime + ";base64," + b64 + "')";
            m.appendReplacement(sb, Matcher.quoteReplacement(dataUri));
        }

        m.appendTail(sb);
        return sb.toString();
    }

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

    private static void dbg(String s) {
        if (DEBUG) System.out.println("[ExtentSingleFileBundler] " + s);
    }
}
