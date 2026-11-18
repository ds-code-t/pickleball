package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;

import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchFrameLoadTest {
    @Test
    void aLargeCatalogIsAppliedOnceWithAFixedRowAndOneSelectionCall() throws Exception {
        ConsumerFeatureCatalog catalog = syntheticCatalog(1_700, 10_000);

        AtomicReference<FeaturePickerPanel.CatalogLoad> unfiltered = new AtomicReference<>();
        AtomicReference<FeaturePickerPanel.CatalogLoad> selectedLoad = new AtomicReference<>();
        AtomicReference<int[]> selected = new AtomicReference<>();
        AtomicReference<Component> firstRender = new AtomicReference<>();
        AtomicReference<Component> secondRender = new AtomicReference<>();
        AtomicReference<Integer> featureHeight = new AtomicReference<>();
        long[] elapsedMs = new long[1];
        int[] observedFeatureModelSets = new int[1];
        int[] observedScenarioModelSets = new int[1];

        SwingUtilities.invokeAndWait(() -> {
            FeaturePickerPanel panel = new FeaturePickerPanel();
            assertEquals(FeaturePickerPanel.FIXED_ROW_HEIGHT, panel.featureListForTest().getFixedCellHeight());
            assertEquals(FeaturePickerPanel.FIXED_ROW_HEIGHT, panel.scenarioListForTest().getFixedCellHeight());
            // Count real JList "model" property changes, not only the panel's own counters.
            panel.featureListForTest().addPropertyChangeListener("model", event -> observedFeatureModelSets[0]++);
            panel.scenarioListForTest().addPropertyChangeListener("model", event -> observedScenarioModelSets[0]++);
            long started = System.nanoTime();
            panel.setCatalog(catalog);
            panel.revalidate();
            panel.scenarioListForTest().getPreferredSize();
            panel.featureListForTest().getPreferredSize();
            elapsedMs[0] = (System.nanoTime() - started) / 1_000_000L;
            unfiltered.set(panel.lastCatalogLoad());
            assertEquals(1, observedFeatureModelSets[0], "JList feature model property changed once");
            assertEquals(1, observedScenarioModelSets[0], "JList scenario model property changed once");
            List<ConsumerFeatureCatalog.FeatureEntry> browse = catalog.featuresForBrowse();
            catalog.selectFeature(browse.get(0).file());
            catalog.selectFeature(browse.get(10).file());
            catalog.selectFeature(browse.get(100).file());
            panel.setCatalog(catalog);
            selectedLoad.set(panel.lastCatalogLoad());
            selected.set(panel.featureListForTest().getSelectedIndices());
            featureHeight.set(panel.featureListForTest().getFixedCellHeight());
            JList<ConsumerFeatureCatalog.ScenarioEntry> scenarios = panel.scenarioListForTest();
            ListCellRenderer<? super ConsumerFeatureCatalog.ScenarioEntry> renderer = scenarios.getCellRenderer();
            firstRender.set(renderer.getListCellRendererComponent(
                    scenarios, scenarios.getModel().getElementAt(0), 0, false, false
            ));
            secondRender.set(renderer.getListCellRendererComponent(
                    scenarios, scenarios.getModel().getElementAt(1), 1, true, false
            ));
        });

        FeaturePickerPanel.CatalogLoad applied = unfiltered.get();
        assertEquals(1, applied.featureModelSets(), "feature model is set once");
        assertEquals(1, applied.scenarioModelSets(), "scenario model is set once");
        assertEquals(1, applied.selectionSets(), "selection is one call");
        assertEquals(FeaturePickerPanel.FIXED_ROW_HEIGHT, applied.fixedCellHeight());
        assertEquals(FeaturePickerPanel.FIXED_ROW_HEIGHT, featureHeight.get());
        assertEquals(1_700, applied.featureRows());
        assertEquals(10_000, applied.scenarioRows());
        assertEquals(1, selectedLoad.get().selectionSets());
        assertEquals(1, selectedLoad.get().featureModelSets());
        assertEquals(3, selected.get().length);
        assertSame(firstRender.get(), secondRender.get(), "renderer reuses one component");
        assertTrue(elapsedMs[0] < 2_000, "UI apply took " + elapsedMs[0] + "ms");
    }

    @Test
    void aSecondApplyStillSetsTheModelOnce() throws Exception {
        ConsumerFeatureCatalog catalog = syntheticCatalog(2, 4);
        AtomicReference<FeaturePickerPanel.CatalogLoad> load = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FeaturePickerPanel panel = new FeaturePickerPanel();
            panel.setCatalog(catalog);
            panel.setCatalog(catalog);
            load.set(panel.lastCatalogLoad());
        });
        assertEquals(1, load.get().featureModelSets());
        assertEquals(1, load.get().scenarioModelSets());
        assertEquals(1, load.get().selectionSets());
        assertEquals(4, load.get().scenarioRows());
    }

    @Test
    void aMissingCatalogClearsBothListsInOneAssignment() throws Exception {
        AtomicReference<FeaturePickerPanel.CatalogLoad> load = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FeaturePickerPanel panel = new FeaturePickerPanel();
            panel.setCatalog(null);
            load.set(panel.lastCatalogLoad());
        });
        assertEquals(1, load.get().featureModelSets());
        assertEquals(1, load.get().scenarioModelSets());
        assertEquals(0, load.get().selectionSets());
        assertEquals(0, load.get().scenarioRows());
    }

    private static ConsumerFeatureCatalog syntheticCatalog(int features, int scenarios) {
        int perFeature = Math.max(1, scenarios / features);
        int leftover = scenarios - perFeature * features;
        List<ConsumerFeatureCatalog.FeatureEntry> entries = new ArrayList<>();
        int made = 0;
        for (int featureIndex = 0; featureIndex < features; featureIndex++) {
            int count = perFeature + (featureIndex < leftover ? 1 : 0);
            Path file = Path.of("/synthetic/feature-" + String.format("%05d", featureIndex) + ".feature");
            List<ConsumerFeatureCatalog.ScenarioEntry> rows = new ArrayList<>();
            for (int scenarioIndex = 0; scenarioIndex < count; scenarioIndex++) {
                String name = "Scenario " + String.format("%05d", made);
                rows.add(new ConsumerFeatureCatalog.ScenarioEntry(
                        name,
                        "Feature " + featureIndex,
                        file,
                        "features/feature-" + featureIndex + ".feature",
                        scenarioIndex + 2,
                        scenarioIndex + 4,
                        List.of("  Scenario: " + name),
                        List.of("fast"),
                        List.of("fast")
                ));
                made++;
            }
            entries.add(new ConsumerFeatureCatalog.FeatureEntry(
                    file,
                    "features/feature-" + featureIndex + ".feature",
                    "Feature " + String.format("%05d", featureIndex),
                    "features",
                    "feature-" + featureIndex + ".feature",
                    rows,
                    List.of("fast")
            ));
        }
        assertEquals(scenarios, made);
        return ConsumerFeatureCatalog.fromEntries(Path.of("/synthetic"), entries);
    }
}
