package org.infinispan.junit;

public interface OperatorProvisionerDetails {
    String name = "grafana-operator";
    String channel = "alpha";
    String installPlanApproval = "Automatic";
    String source = "community-operators";
    String sourceNamespace = "openshift-marketplace";
    String startingCSV = null;                          // grafana-operator.v3.9.0

    String getName();

    String getChannel();

    String getInstallPlanApproval();

    String getSource();

    String getSourceNamespace();

    String getStartingCSV();
}
