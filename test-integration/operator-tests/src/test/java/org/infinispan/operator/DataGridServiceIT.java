package org.infinispan.operator;

import java.io.IOException;
import java.nio.file.Paths;

import cz.xtf.core.http.Https;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.assertj.core.api.Assertions;
import org.infinispan.Caches;
import org.infinispan.Infinispan;
import org.infinispan.Infinispans;
import org.infinispan.TestServer;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.infinispan.commons.configuration.XMLStringConfiguration;
import org.infinispan.identities.Credentials;
import org.infinispan.util.CleanUpValidator;
import org.infinispan.util.KeystoreGenerator;
import org.infinispan.util.client.Http;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cz.xtf.builder.builders.SecretBuilder;
import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import cz.xtf.junit5.annotations.CleanBeforeAll;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLHandshakeException;

/**
 * Compared to MinimalSetupIT this set of tests are running
 * against features that need to be configured.
 *
 * Check datagrid_service.yaml Infinispan CR in test resources for input configuration.
 */
@Slf4j
@CleanBeforeAll
class DataGridServiceIT {
   private static final OpenShift openShift = OpenShifts.master();
   private static final Infinispan infinispan = Infinispans.dataGridService();
   private static final TestServer testServer = TestServer.get();

   private static KeystoreGenerator.CertPaths certs;
   private static KeystoreGenerator.CertPaths clientCerts;
   private static KeystoreGenerator.CertPaths anotherClientCerts;
   private static String appName;
   private static String hostName;
   private static String user;
   private static String pass;

   @BeforeAll
   static void deploy() throws Exception {
      appName = infinispan.getClusterName();
      hostName = openShift.generateHostname(appName + "-external");

      certs = KeystoreGenerator.generateCerts(hostName, new String[]{appName});
      clientCerts = KeystoreGenerator.generateCerts("client");
      anotherClientCerts = KeystoreGenerator.generateCerts("anotherclient");

      clientCerts.includeCertInTruststore();

      Secret encryptionSecret = new SecretBuilder("encryption-secret")
            .addData("keystore.p12", certs.keystore)
            .addData("alias", hostName.getBytes())
            .addData("password", "password".getBytes()).build();
      Secret authSecret = new SecretBuilder("connect-secret")
            .addData("identities.yaml", Paths.get("src/test/resources/secrets/identities.yaml")).build();
      Secret clientSecret = new SecretBuilder("client-secret")
            .addData("keystore", clientCerts.keystore).build();
      Secret truststoreSecret = new SecretBuilder("truststore-secret")
            .addData("truststore-password", "password".getBytes())
            .addData("truststore.p12", clientCerts.truststore).build();
      Secret clientCertSecret = new SecretBuilder(appName + "-client-cert-secret")
              .addData("trust.ca", clientCerts.caPem)
              .addData("trust.cert.client1", clientCerts.certPem)
              .addData("trust.cert.client2", anotherClientCerts.certPem).build();

      openShift.secrets().create(encryptionSecret);
      openShift.secrets().create(authSecret);
      openShift.secrets().create(clientSecret);
      openShift.secrets().create(truststoreSecret);
      openShift.secrets().create(clientCertSecret);

      testServer.withSecret("client-secret");
      testServer.withSecret("truststore-secret");

      infinispan.deploy();
      testServer.deploy();

      infinispan.waitFor();
      testServer.waitFor();

      Credentials developer = infinispan.getCredentials("testuser");
      user = developer.getUsername();
      pass = developer.getPassword();

      Https.doesUrlReturnOK("http://" + testServer.host() + "/ping").waitFor();

//      Http http = Http.get("https://" + hostName).stores(clientCerts.keystore, "password", certs.truststore, "password", new DefaultHostnameVerifier());
//      http.waiters().code(200).waitFor();

//      Https.doesUrlReturnCode("https://" + hostName, 200).waitFor();
   }


   /**
    * Ensure that all resource created by Operator in this tests are deleted.
    */
//   @AfterAll
   static void undeploy() throws IOException {
      infinispan.delete();

      new CleanUpValidator(openShift, appName).withExposedRoute().withServiceMonitor().validate();
   }

   /**
    * Default cache should be available only for Cache type of service
    */
   @Test
   void defaultCacheNotPresentTest() throws IOException {
      String cacheUrl = "https://" + hostName + "/rest/v2/caches/default/";
      Http get = Http.get(cacheUrl).basicAuth(user, pass).trustAll();
      Assertions.assertThat(get.execute().code()).isEqualTo(404);
   }

   /**
    * Verify that logging configuration is properly reflected in logs.
    */
   @Test
   void loggingTest() {
      Pod node = openShift.pods().withLabel("clusterName", appName).list().getItems().stream().findFirst().orElseThrow(() -> new IllegalStateException("Data Grid nodes are missing!"));

      String log = openShift.getPodLog(node);

      Assertions.assertThat(log).contains("DEBUG (main) [org.jgroups");
      Assertions.assertThat(log).doesNotContain("INFO (main) [org.infinispan");
   }

   /**
    * Verifies valid default authentication configuration for rest protocol.
    */
   @Test
   void restTest() throws IOException {
      String cacheUrl = "https://" + hostName + "/rest/v2/caches/rest-auth-test/";
      String keyUrl = cacheUrl + "authorized-rest-key";

      Http authorizedCachePut = Http.post(cacheUrl).basicAuth(user, pass).data(Caches.fragile("rest-auth-test"), ContentType.APPLICATION_XML).trustStore(KeystoreGenerator.getTruststore(), "password");
      Http authorizedKeyPut = Http.put(keyUrl).basicAuth(user, pass).data("credentials", ContentType.TEXT_PLAIN).trustStore(KeystoreGenerator.getTruststore(), "password");
      Http unauthorizedPut = Http.post(cacheUrl).basicAuth(user, "DenitelyNotAPass").data(Caches.fragile("rest-auth-test"), ContentType.APPLICATION_XML).trustStore(KeystoreGenerator.getTruststore(), "password");
      Http noAuthPut = Http.post(cacheUrl).data(Caches.fragile("rest-auth-test"), ContentType.APPLICATION_XML).trustStore(KeystoreGenerator.getTruststore(), "password");

      Assertions.assertThat(authorizedCachePut.execute().code()).isEqualTo(200);
      Assertions.assertThat(authorizedKeyPut.execute().code()).isEqualTo(204);
      Assertions.assertThat(unauthorizedPut.execute().code()).isEqualTo(401);
      Assertions.assertThat(noAuthPut.execute().code()).isEqualTo(401);
   }

   /**
    * Verifies valid default authentication configuration for hotrod protocol.
    */
   @Test
   void hotrodTest() {
      RemoteCache<String, String> rc = getConfiguration(user, pass).administration().getOrCreateCache("testcache", new XMLStringConfiguration(Caches.testCache()));
      rc.put("hotrod-encryption-external", "hotrod-encryption-value");
      Assertions.assertThat(rc.get("hotrod-encryption-external")).isEqualTo("hotrod-encryption-value");

      Assertions.assertThatThrownBy(
            () -> getConfiguration(user, "NotAPass").administration().getOrCreateCache("testcache", new XMLStringConfiguration(Caches.testCache()))
      ).isInstanceOf(HotRodClientException.class);

      Assertions.assertThatThrownBy(
            () -> getConfiguration(null, null).administration().getOrCreateCache("testcache", new XMLStringConfiguration(Caches.testCache()))
      ).isInstanceOf(HotRodClientException.class);
   }

   @Test
   void hotrodCertTest() {
      RemoteCache<String, String> rc = getConfiguration(null, null).administration().getOrCreateCache("testcache", new XMLStringConfiguration(Caches.testCache()));
      rc.put("hotrod-encryption-external", "hotrod-encryption-value");
      Assertions.assertThat(rc.get("hotrod-encryption-external")).isEqualTo("hotrod-encryption-value");
   }

   @Test
   void certAuthTest() throws Exception {
      // REST
      Http httpWithKey = Http.get("https://" + hostName).stores(clientCerts.keystore, "password", certs.truststore, "password", new DefaultHostnameVerifier());
      Http httpWithWrongKey = Http.get("https://" + hostName).stores(anotherClientCerts.keystore, "password", certs.truststore, "password", new DefaultHostnameVerifier());
      Http httpWithoutKey = Http.get("https://" + hostName).trustStore(certs.truststore, "password", new DefaultHostnameVerifier());
      try {
         httpWithKey.execute().code();
      } catch (SSLHandshakeException e) {
         log.info("Request with key: {}", e.getMessage());
      }
      try {
         httpWithWrongKey.execute().code();
      } catch (SSLHandshakeException e) {
         log.info("Request with wrong key: {}", e.getMessage());
      }
      try {
         httpWithoutKey.execute().code();
      } catch (SSLHandshakeException e) {
         log.info("Request without key: {}", e.getMessage());
      }

      // HOTROD
      String get = String.format("http://" + testServer.host() + "/hotrod/cert?servicename=%s", appName);
      Assertions.assertThat(Http.get(get).execute().code()).isEqualTo(200);
   }

   /**
    * Verifies that AntiAffinity settings get propagated to StatefulSet.
    */
   @Test
   void antiAffinityTest() {
      StatefulSet ss = openShift.getStatefulSet(appName);
      Affinity affinity = ss.getSpec().getTemplate().getSpec().getAffinity();

      Assertions.assertThat(affinity).isNotNull();
      Assertions.assertThat(affinity.getPodAntiAffinity().getRequiredDuringSchedulingIgnoredDuringExecution()).isNotNull();
   }

   private RemoteCacheManager getConfiguration(String username, String password) {
      String truststore = certs.truststore.toAbsolutePath().toString();
      String keystore = clientCerts.keystore.toAbsolutePath().toString();

      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.maxRetries(1);
      builder.addServer().host(hostName).port(443);
      // TODO
//      builder.security().authentication().saslMechanism("EXTERNAL").realm("default").serverName("infinispan").enable();

      builder.security().ssl().sniHostName(hostName)
              .trustStoreFileName(truststore).trustStorePassword("password".toCharArray());
              //.keyStoreFileName(keystore).keyStorePassword("password".toCharArray()).keyStoreType("PCKS12");
      builder.clientIntelligence(ClientIntelligence.BASIC);

      if (username != null && password != null) {
         builder.security().authentication().realm("default").serverName("infinispan").username(username).password(password).enable();
      }

      return new RemoteCacheManager(builder.build());
   }
}
