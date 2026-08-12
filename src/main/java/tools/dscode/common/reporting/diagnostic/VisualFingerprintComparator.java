package tools.dscode.common.reporting.diagnostic;

import java.util.Arrays;

public final class VisualFingerprintComparator {
    public enum Category { IDENTICAL, VERY_SIMILAR, SOMEWHAT_SIMILAR, VERY_DIFFERENT }

    public record Result(
            double similarity,
            double luminanceSimilarity,
            double colorSimilarity,
            double edgeSimilarity,
            double histogramSimilarity,
            double changedCellRatio,
            int dHashDistance,
            boolean decodedPixelsExactlyEqual,
            boolean dimensionsEqual,
            Category category
    ) {
        public java.util.Map<String, Object> asMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("similarity", similarity);
            map.put("luminanceSimilarity", luminanceSimilarity);
            map.put("colorSimilarity", colorSimilarity);
            map.put("edgeSimilarity", edgeSimilarity);
            map.put("histogramSimilarity", histogramSimilarity);
            map.put("changedCellRatio", changedCellRatio);
            map.put("dHashDistance", dHashDistance);
            map.put("decodedPixelsExactlyEqual", decodedPixelsExactlyEqual);
            map.put("dimensionsEqual", dimensionsEqual);
            map.put("category", category.name());
            return map;
        }
    }

    private VisualFingerprintComparator() {
    }

    public static Result compare(VisualFingerprint left, VisualFingerprint right) {
        boolean dimensionsEqual = left.width() == right.width() && left.height() == right.height();
        boolean pixelsEqual = dimensionsEqual && Arrays.equals(left.sha256(), right.sha256());
        if (pixelsEqual) {
            return new Result(1, 1, 1, 1, 1, 0, 0, true, true, Category.IDENTICAL);
        }

        byte[] a = left.grid();
        byte[] b = right.grid();
        long yDiff = 0;
        long colorDiff = 0;
        int changed = 0;
        int cells = VisualFingerprint.GRID_W * VisualFingerprint.GRID_H;
        for (int cell = 0; cell < cells; cell++) {
            int base = cell * 3;
            int dy = Math.abs((a[base] & 0xff) - (b[base] & 0xff));
            int dcb = Math.abs((a[base + 1] & 0xff) - (b[base + 1] & 0xff));
            int dcr = Math.abs((a[base + 2] & 0xff) - (b[base + 2] & 0xff));
            yDiff += dy;
            colorDiff += dcb + dcr;
            if (dy >= 24 || dcb + dcr >= 36) changed++;
        }

        double ySimilarity = 1.0 - yDiff / (cells * 255.0);
        double colorSimilarity = 1.0 - colorDiff / (cells * 510.0);
        double edgeSimilarity = byteSimilarity(left.edges(), right.edges());
        double histSimilarity = histogramSimilarity(left.histogram(), right.histogram());
        int hashDistance = Long.bitCount(left.dHash() ^ right.dHash());
        double hashSimilarity = 1.0 - hashDistance / 64.0;

        double similarity = clamp01(
                ySimilarity * 0.45
                        + colorSimilarity * 0.20
                        + edgeSimilarity * 0.15
                        + histSimilarity * 0.10
                        + hashSimilarity * 0.10
        );
        double changedRatio = changed / (double) cells;
        Category category = similarity >= 0.985 && changedRatio <= 0.02
                ? Category.VERY_SIMILAR
                : similarity >= 0.88 && changedRatio <= 0.25
                ? Category.SOMEWHAT_SIMILAR
                : Category.VERY_DIFFERENT;

        return new Result(
                round(similarity), round(ySimilarity), round(colorSimilarity),
                round(edgeSimilarity), round(histSimilarity), round(changedRatio),
                hashDistance, false, dimensionsEqual, category
        );
    }

    private static double byteSimilarity(byte[] a, byte[] b) {
        long diff = 0;
        for (int i = 0; i < a.length; i++) diff += Math.abs((a[i] & 0xff) - (b[i] & 0xff));
        return clamp01(1.0 - diff / (a.length * 255.0));
    }

    private static double histogramSimilarity(short[] a, short[] b) {
        long diff = 0;
        for (int i = 0; i < a.length; i++) diff += Math.abs((a[i] & 0xffff) - (b[i] & 0xffff));
        return clamp01(1.0 - diff / (2.0 * 65535.0));
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
