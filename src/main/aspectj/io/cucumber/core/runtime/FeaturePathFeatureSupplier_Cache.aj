package io.cucumber.core.runtime;

import java.util.function.Supplier;

/**
 * Surefire discovers twice. The second pass reuses features whose files
 * have not changed. See {@link FeaturePathLoadCache}.
 */
public privileged aspect FeaturePathFeatureSupplier_Cache {

    pointcut supply(FeaturePathFeatureSupplier supplier):
            execution(public java.util.List io.cucumber.core.runtime.FeaturePathFeatureSupplier.get())
                    && this(supplier);

    java.util.List around(FeaturePathFeatureSupplier supplier): supply(supplier) {
        Supplier<ClassLoader> classLoader = supplier.featureScanner.classLoaderSupplier;
        return FeaturePathLoadCache.load(
                supplier.featureOptions.getFeaturePaths(),
                classLoader,
                () -> proceed(supplier)
        );
    }
}
