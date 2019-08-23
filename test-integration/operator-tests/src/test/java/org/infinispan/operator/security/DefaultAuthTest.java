package org.infinispan.operator.security;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.http.entity.ContentType;
import org.infinispan.Caches;
import org.infinispan.identities.Identities;
import org.infinispan.Infinispan;
import org.infinispan.Infinispans;
import org.infinispan.TestServer;
import org.infinispan.identities.Credentials;
import org.junit.jupiter.api.BeforeAll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import cz.xtf.builder.builders.RouteBuilder;
import cz.xtf.client.Http;
import cz.xtf.core.http.Https;
import cz.xtf.junit5.annotations.CleanBeforeAll;
import io.fabric8.openshift.api.model.Route;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@CleanBeforeAll
class DefaultAuthTest extends AuthTest {
   private static Infinispan infinispan = Infinispans.defaultAuth();

   @BeforeAll
   static void deployInfinispanCluster() throws IOException {
      testServer.deploy();

      appName =  infinispan.getClusterName();
      host = openShift.generateHostname(appName);

      Route route = new RouteBuilder(appName).forService(appName).targetPort(11222).exposedAsHost(host).build();

      openShift.customResource(infinispanContext).create(openShift.getNamespace(), infinispan.getInputStream());
      openShift.createRoute(route);

      openShift.waiters().isDcReady(TestServer.getName()).waitFor();
      openShift.waiters().areExactlyNPodsReady(2, "clusterName", appName).timeout(TimeUnit.MINUTES, 5).waitFor();


      Map<String, String> creds = openShift.getSecret(appName + "-generated-secret").getData();
      String identitiesYaml = new String(Base64.getDecoder().decode(creds.get("identities.yaml")));
      Identities identities = new ObjectMapper(new YAMLFactory()).readValue(identitiesYaml, Identities.class);
      Credentials developer = identities.getCredentials("developer");

      user = developer.getUsername();
      pass = developer.getPassword();

      Https.doesUrlReturnOK("http://" + testServerHost + "/ping").waitFor();
      Https.doesUrlReturnCode("http://" + host, 401).waitFor();

      Http.post("http://" + host + "/rest/v2/caches/testcache").basicAuth(user, pass).data(Caches.testCache(), ContentType.APPLICATION_XML).execute().code();
   }
}
