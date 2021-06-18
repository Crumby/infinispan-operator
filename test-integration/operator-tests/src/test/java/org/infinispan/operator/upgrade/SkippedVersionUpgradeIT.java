package org.infinispan.operator.upgrade;

import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import cz.xtf.core.waiting.Waiter;
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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Execute only if the current CSV contains skip
 */
@Slf4j
public class SkippedVersionUpgradeIT {
    private static final OpenShift openShift = OpenShifts.master();

//    private static final Infinispan cache = Infinispans.cache();
    private static final Infinispan datagrid = Infinispans.datagrid();

    private static final String releaseCatalog = SuiteConfig.releaseCatalogName();
    private static final String devCatalog = SuiteConfig.devCatalogName();

    private static final String operatorName = InfinispanOperatorConfig.name();

    private static InfinispanOperator operator;

    private static String sourceChannel;
    private static String targetChannel;
    private static String targetCSVVersion;
    private static String latestReleasedCSVVersion;

    /**
     * Install released version.
     */
    @BeforeAll
    static void prepare() throws Exception {
        initChannels();

        log.info("Channel upgrade will be performed from {} to {}", sourceChannel, targetChannel);
        log.info("Expecting {} to be the final version of the upgrade", targetCSVVersion);

        operator = new InfinispanOperator(sourceChannel, "Manual", releaseCatalog);

        operator.subscribe();

        operator.isUpgradeReadyWaiter().waitFor();
        operator.approveInstallPlan();
        operator.hasInstallCompletedWaiter().waitFor();

//        cache.deploy();
        datagrid.deploy();

//        cache.waitFor();
        datagrid.waitFor();
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if(!SuiteConfig.keepRunning()) {
            log.info("Removing Infinispan cluster and the Operator");
//            cache.delete();
            datagrid.delete();
            operator.unsubscribe();
        }
    }

    /**
     * Trigger manual upgrade to dev version.
     */
    @Test
    void upgradeTest() throws Exception {
        int clusterSize = datagrid.getSize();

//        Credentials cacheCredentials = datagrid.getDefaultCredentials();
        Credentials datagridCrednetials = datagrid.getDefaultCredentials();

        // Create persisted cache, put some data
        String cacheName = "update-cache";

        RestCache datagridRest = new RestCache(datagrid.getHostname(), datagridCrednetials.getUsername(), datagridCrednetials.getPassword());
//        RestCache cacheRest = new RestCache(cache.getHostname(), cacheCredentials.getUsername(), cacheCredentials.getPassword());

        datagridRest.createCache(cacheName, Caches.persisted(cacheName));
        datagridRest.put(cacheName, "test-key-read", "read");
        datagridRest.put(cacheName, "test-key-update", "update");
        datagridRest.put(cacheName, "test-key-delete", "delete");

        log.info("Uploading 1000 entries...");
        for(int i = 0; i < 1000; i++) {
            datagridRest.put(cacheName, "random-test-key-" + i, "random-test-value-" + i);
        }

//        cacheRest.createCache(cacheName, Caches.persisted(cacheName));
//        cacheRest.put(cacheName, "test-key-read", "read");
//        cacheRest.put(cacheName, "test-key-update", "update");
//        cacheRest.put(cacheName, "test-key-delete", "delete");

        // Trigger the upgrade
        operator.changeChannel(targetChannel);

        operator.isUpgradeReadyWaiter().waitFor();
        operator.approveInstallPlan();
        operator.hasInstallCompletedWaiter().waitFor();

        // Wait for Infinispan to upgrade
        String serverImage = operator.getServerImage();
        log.info("Expecting the cluster to start with: {}", serverImage);
        datagrid.isClusterRunningWithServerImageWaiter(serverImage, clusterSize).waitFor();

        openShift.routes().withName(datagrid.getClusterName() + "-external").delete();

        Waiters.sleep(TimeUnit.SECONDS, 30);

        // Verify the cache and the data
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-read")).isEqualTo("read");
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-update")).isEqualTo("update");
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-delete")).isEqualTo("delete");

        // Modify the data
        datagridRest.put(cacheName, "test-key-update", "updated");
        datagridRest.delete(cacheName, "test-key-delete");

        log.info("Uploading another 1000 entries...");
        for(int i = 1000; i < 2000; i++) {
            datagridRest.put(cacheName, "random-test-key-" + i, "random-test-value-" + i);
        }

        operator.changeCatalog(devCatalog);

        operator.isUpgradeReadyWaiter().waitFor();
        operator.approveInstallPlan();
        operator.hasInstallCompletedWaiter().waitFor();

        // Wait for Infinispan to upgrade
        serverImage = operator.getServerImage();
        log.info("Expecting the cluster to start with: {}", serverImage);
        datagrid.isClusterRunningWithServerImageWaiter(serverImage, clusterSize).waitFor();

        Assertions.assertThat(datagridRest.get(cacheName, "test-key-read")).isEqualTo("read");
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-update")).isEqualTo("updated");
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-delete")).isEqualTo("");

        // Verify the data can be read correctly on restart
        openShift.pods().withLabel("clusterName", datagrid.getClusterName()).delete();

        Waiters.sleep(TimeUnit.SECONDS, 30);
        datagrid.waitFor();

        Assertions.assertThat(datagridRest.get(cacheName, "test-key-read")).isEqualTo("read");
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-update")).isEqualTo("updated");
        Assertions.assertThat(datagridRest.get(cacheName, "test-key-delete")).isEqualTo("");
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
        latestReleasedCSVVersion = getCurrentCSV(releasedPM, targetChannel);

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
