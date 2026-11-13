package dev.domengine.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateFeaturesTest {
    @Test
    void featuresAndPrefilterKeepTheTrueCrop() {
        TemplateMatch.Gray template = pattern("horizontal", 0);
        TemplateMatch.Features features = TemplateMatch.features(template);
        assertEquals(features.dhash(), TemplateMatch.features(pattern("horizontal", 0)).dhash());
        assertTrue(features.stddev() > 0);
        assertTrue(TemplateMatch.prefilter(features, features));
        String[] others = {"vertical", "checker", "diagonal", "solid-dark", "solid-light"};
        int rejected = 0;
        for (String name : others) {
            TemplateMatch.Features candidate = TemplateMatch.features(pattern(name, 0));
            if (!TemplateMatch.prefilter(candidate, features)) rejected++;
        }
        assertTrue(rejected >= 4, "rejected " + rejected + " of " + others.length);
        assertEquals(1.0, TemplateMatch.score(template, template), 0);
    }

    private static TemplateMatch.Gray pattern(String name, int seed) {
        int width = 32;
        int height = 32;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int on = switch (name) {
                    case "horizontal" -> (y / 2) % 2 == 0 ? 20 : 220;
                    case "vertical" -> (x / 2) % 2 == 0 ? 20 : 220;
                    case "checker" -> ((x / 4) + (y / 4)) % 2 == 0 ? 30 : 200;
                    case "diagonal" -> ((x + y) / 3) % 2 == 0 ? 10 : 240;
                    case "solid-dark" -> 15;
                    default -> 240;
                };
                pixels[y * width + x] = on + seed;
            }
        }
        return new TemplateMatch.Gray(width, height, pixels);
    }
}
