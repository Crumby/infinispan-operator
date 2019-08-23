package org.infinispan.operator.security;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.apache.http.entity.ContentType;
import org.infinispan.Caches;
import org.infinispan.Infinispan;
import org.infinispan.Infinispans;
import org.infinispan.TestServer;
import org.infinispan.identities.Credentials;
import org.infinispan.identities.Identities;
import org.junit.jupiter.api.BeforeAll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import cz.xtf.builder.builders.RouteBuilder;
import cz.xtf.builder.builders.SecretBuilder;
import cz.xtf.client.Http;
import cz.xtf.core.http.Https;
import cz.xtf.junit5.annotations.CleanBeforeAll;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.api.model.Route;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@CleanBeforeAll
public class CustomAuthTest extends AuthTest {
   private static final Infinispan infinispan = Infinispans.customAuth();

   @BeforeAll
   public static void deploy() throws IOException {
      testServer.deploy();

      appName = infinispan.getClusterName();
      host = openShift.generateHostname(appName);

      Secret secret = new SecretBuilder("connect-secret").addData("identities.yaml", Paths.get("src/test/resources/secrets/identities.yaml")).build();
      Route route = new RouteBuilder(appName).forService(appName).targetPort(11222).exposedAsHost(host).build();

      openShift.secrets().create(secret);
      openShift.customResource(infinispanContext).create(openShift.getNamespace(), infinispan.getInputStream());
      openShift.createRoute(route);

      openShift.waiters().areExactlyNPodsReady(2, "clusterName", appName).timeout(TimeUnit.MINUTES, 5).waitFor();
      openShift.waiters().isDcReady(TestServer.getName()).waitFor();

      String identitiesYaml = new String(Base64.getDecoder().decode(secret.getData().get("identities.yaml")));
      Identities identities = new ObjectMapper(new YAMLFactory()).readValue(identitiesYaml, Identities.class);
      Credentials developer = identities.getCredentials("testuser");

      user = developer.getUsername();
      pass = developer.getPassword();

      Https.doesUrlReturnOK("http://" + testServerHost + "/ping").waitFor();
      Https.doesUrlReturnCode("http://" + host, 401).waitFor();

      Http.post("http://" + host + "/rest/v2/caches/testcache").basicAuth(user, pass).data(Caches.testCache(), ContentType.APPLICATION_XML).execute().code();
   }
}
