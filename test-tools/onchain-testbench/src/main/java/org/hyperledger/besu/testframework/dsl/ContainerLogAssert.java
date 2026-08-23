package org.hyperledger.besu.testframework.dsl;

import org.assertj.core.api.AbstractAssert;

/**
 * AssertJ assertions for Docker container logs, focused on PermissioningPlugin
 * and blockchain-specific log patterns.
 */
public class ContainerLogAssert extends AbstractAssert<ContainerLogAssert, String> {

    protected ContainerLogAssert(String actual) {
        super(actual, ContainerLogAssert.class);
    }

    public static ContainerLogAssert assertThat(String logs) {
        return new ContainerLogAssert(logs);
    }

    public ContainerLogAssert containsPluginMarker(String marker) {
        isNotNull();
        if (!actual.contains(marker)) {
            failWithMessage("Expected logs to contain plugin marker <%s> but it was not found.", marker);
        }
        return this;
    }

    public ContainerLogAssert containsPluginDenial() {
        isNotNull();
        if (!actual.contains("PermissioningPlugin: P2P connection DENIED")) {
            failWithMessage("Expected logs to contain PermissioningPlugin denial message.");
        }
        return this;
    }

    public ContainerLogAssert containsPluginApproval() {
        isNotNull();
        if (!actual.contains("P2P connection") || actual.contains("DENIED")) {
            failWithMessage("Expected logs to contain P2P connection approval (not denied).");
        }
        return this;
    }

    public ContainerLogAssert containsFailCloseIndicator() {
        isNotNull();
        if (!actual.contains("FAIL-CLOSE") && !actual.contains("fail-close")) {
            failWithMessage("Expected logs to contain Fail-Close indicator.");
        }
        return this;
    }

    public ContainerLogAssert containsIngressLookup(String address) {
        isNotNull();
        if (!actual.contains("Ingress") && !actual.contains(address)) {
            failWithMessage("Expected logs to contain Ingress lookup for address <%s>.", address);
        }
        return this;
    }
}
