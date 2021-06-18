package org.infinispan.junit;

import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionBuilder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class OperatorProvisioner implements BeforeAllCallback, AfterAllCallback {
    private static final OpenShift openShift = OpenShifts.master();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // Create subscription
        String name = "grafana-operator";
        String channel = "alpha";
        String installPlanApproval = "Automatic";
        String source = "community-operators";
        String sourceNamespace = "openshift-marketplace";
        String startingCSV = null;                          // grafana-operator.v3.9.0

        subscribe(name, channel, installPlanApproval, source, sourceNamespace, startingCSV);
    }

    private static void subscribe(String name, String channel, String installPlanApproval, String source, String sourceNamespace, String startingCSV) {
        SubscriptionBuilder subBuilder = new SubscriptionBuilder();
        subBuilder.withNewMetadata().withName(name).endMetadata();
        subBuilder.withNewSpec()
                .withChannel(channel)
                .withInstallPlanApproval(installPlanApproval)
                .withSource(source)
                .withSourceNamespace(sourceNamespace)
                .withStartingCSV(startingCSV)
                .endSpec();

        openShift.operatorHub().subscriptions().create(subBuilder.build());
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        // Uninstall subscription
    }
}
