package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverDownloadRedactionTest {

    @Test
    void proxyUrlIsRedactedLikeAReportPortalPassword() {
        String secret = "http://user:s3cret@proxy.example:8080";
        assertTrue(SensitiveConfiguration.isSensitive(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY));
        assertEquals(SensitiveConfiguration.REDACTED,
                SensitiveConfiguration.displayValue(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY, secret));
        assertEquals(SensitiveConfiguration.REDACTED,
                SensitiveConfiguration.displayValue("pkb_rp_http_proxy_password", "s3cret"));

        Map<String, String> values = new LinkedHashMap<>();
        values.put(PKB_props.PKB_DRIVER_DOWNLOAD_PROXY, secret);
        values.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        String profile = PickleballProfiles.serializeRunProfile(values);
        assertFalse(profile.contains("s3cret"));
        assertFalse(profile.contains("proxy.example"));
        assertTrue(profile.contains("${protected:pkb_driver_download_proxy}"));
        assertTrue(profile.contains("pkb_browser=CHROME_HEADLESS"));
    }
}
