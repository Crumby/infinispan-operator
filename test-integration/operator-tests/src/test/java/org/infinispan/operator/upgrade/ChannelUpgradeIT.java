package org.infinispan.operator.upgrade;

import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import cz.xtf.core.waiting.Waiters;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageChannel;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifest;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.infinispan.*;
import org.infinispan.config.SuiteConfig;
import org.infinispan.identities.Credentials;
import org.infinispan.util.ChannelComparator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Instantiates latest version in previous channel and verifies the upgrade
 */
@Slf4j
public class ChannelUpgradeIT {
    private static final OpenShift openShift = OpenShifts.master();
    private static final Infinispan infinispan = Infinispans.cacheService();

    private static final String releaseCatalog = SuiteConfig.releaseCatalogName();
    private static final String devCatalog = SuiteConfig.devCatalogName();

    private static final String operatorName = InfinispanOperatorConfig.name();

    private static InfinispanOperator operator;

    private static String sourceChannel;
    private static String targetChannel;
    private static String targetCSVVersion;

    /**
     * Install released version.
     */
    @BeforeAll
    static void prepare() throws Exception {
        initChannels();

        log.info("Channel upgrade will be performed from {} to {}", sourceChannel, targetChannel);
        log.info("Expecting {} to be the final version of the upgrade", targetCSVVersion);

        operator = new InfinispanOperator(sourceChannel, "Manual");

        operator.subscribe();

        operator.isUpgradeReadyWaiter().waitFor();
        operator.approveInstallPlan();
        operator.hasInstallCompletedWaiter().waitFor();

        infinispan.deploy();
        infinispan.waitFor();
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if(!SuiteConfig.keepRunning()) {
            log.info("Removing Infinispan cluster and the Operator");
            infinispan.delete();
            operator.unsubscribe();
        }
    }

    /**
     * Trigger manual upgrade to dev version.
     */
    @Test
    void upgradeTest() throws Exception {
        int clusterSize = infinispan.getSize();

        Credentials credentials = infinispan.getDefaultCredentials();
        String user = credentials.getUsername();
        String pass = credentials.getPassword();

        // Create persisted cache, put some data
        String hostname = infinispan.getHostname();
        String cacheName = "update-cache";

        RestCache rest = new RestCache(hostname, user, pass);

        rest.createCache(cacheName, Caches.persisted(cacheName));
        rest.put(cacheName, "test-key-read", "read");
        rest.put(cacheName, "test-key-update", "update");
        rest.put(cacheName, "test-key-delete", "delete");

        // Trigger the upgrade
        operator.changeChannel(targetChannel);

        operator.isUpgradeReadyWaiter().waitFor();
        operator.approveInstallPlan();
        operator.hasInstallCompletedWaiter().waitFor();

//        openShift.pods().withLabel("clusterName", infinispan.getClusterName()).delete();

        // Wait for Infinispan to upgrade
        String serverImage = operator.getServerImage();
        log.info("Expecting the cluster to start with: {}", serverImage);
        infinispan.isClusterRunningWithServerImageWaiter(serverImage, clusterSize).waitFor();

        // Verify the cache and the data
        Assertions.assertThat(rest.get(cacheName, "test-key-read")).isEqualTo("read");
        Assertions.assertThat(rest.get(cacheName, "test-key-update")).isEqualTo("update");
        Assertions.assertThat(rest.get(cacheName, "test-key-delete")).isEqualTo("delete");

        // Modify the data
        rest.put(cacheName, "test-key-update", "updated");
        rest.delete(cacheName, "test-key-delete");

        // Verify the data can be read correctly on restart
        openShift.pods().withLabel("clusterName", infinispan.getClusterName()).delete();

        Waiters.sleep(TimeUnit.SECONDS, 30);
        infinispan.waitFor();

        Assertions.assertThat(rest.get(cacheName, "test-key-read")).isEqualTo("read");
        Assertions.assertThat(rest.get(cacheName, "test-key-update")).isEqualTo("updated");
        Assertions.assertThat(rest.get(cacheName, "test-key-delete")).isEqualTo("");
    }

    private static void initChannels() {
        // Retrieve available Infinispan package manifests (released and dev)
        List<PackageManifest> pmList = openShift.operatorHub().packageManifests().list().getItems()
                .stream().filter(pm -> operatorName.equals(pm.getMetadata().getName())).collect(Collectors.toList());

        PackageManifest releasedPM = getPackageManifest(pmList, releaseCatalog);
        PackageManifest devPM = getPackageManifest(pmList, devCatalog);

        List<String> releasedChannels = getChannels(releasedPM);
        List<String> devChannels = getChannels(devPM);

        targetChannel = devChannels.get(devChannels.size() - 1);
        targetCSVVersion = getCurrentCSV(devPM, targetChannel);

        // If there is more x.y.z channels in dev catalog then in released catalog, new minor is in development
        if (releasedChannels.size() != devChannels.size()) {
            sourceChannel = releasedChannels.get(releasedChannels.size() - 1);
        }
        sourceChannel = releasedChannels.get(releasedChannels.size() - 2);
    }

    private static PackageManifest getPackageManifest(List<PackageManifest> pmList, String catalogName) {
        return pmList.stream().filter(manifest -> catalogName.equals(manifest.getStatus().getCatalogSource()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Unable retrieve released PackageManifests"));
    }

    private static List<String> getChannels(PackageManifest pm) {
        return pm.getStatus().getChannels().stream().map(PackageChannel::getName).filter(ch -> ch.split("\\.").length == 3)
                .sorted(new ChannelComparator()).collect(Collectors.toList());
    }

    private static String getCurrentCSV(PackageManifest pm, String channel) {
        return pm.getStatus().getChannels().stream().filter(ch -> ch.getName().equals(channel)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable retrieve CurrentCSV for " + channel + "channel")).getCurrentCSV();
    }
}
