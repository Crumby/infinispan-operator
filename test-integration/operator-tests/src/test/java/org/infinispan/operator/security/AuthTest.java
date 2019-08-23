package org.infinispan.operator.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.http.entity.ContentType;
import org.assertj.core.api.Assertions;
import org.infinispan.TestServer;
import org.infinispan.crd.InfinispanContextProvider;
import org.junit.jupiter.api.Test;

import cz.xtf.client.Http;
import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

abstract class AuthTest {
   static final CustomResourceDefinitionContext infinispanContext = new InfinispanContextProvider().getContext();
   static final OpenShift openShift = OpenShifts.master();

   static final TestServer testServer = TestServer.get();
   static final String testServerHost = testServer.host();

   static String appName;
   static String host;

   static String user;
   static String pass;

   @Test
   void restCorrectCredentials() throws IOException {
      int putCode = Http.put("http://" + host + "/rest/testcache/correct").basicAuth(user, pass).data("credentials", ContentType.TEXT_PLAIN).execute().code();
      int getCode = Http.get("http://" + host + "/rest/testcache/correct").basicAuth(user, pass).execute().code();

      Assertions.assertThat(putCode).isEqualTo(204);
      Assertions.assertThat(getCode).isEqualTo(200);
   }

   @Test
   void restInvalidPassword() throws IOException {
      Assertions.assertThat(Http.put("http://" + host + "/rest/testcache/invalid").basicAuth(user, "invalid").data("credentials", ContentType.TEXT_PLAIN).execute().code()).isEqualTo(401);
   }

   @Test
   void restInvalidUser() throws IOException {
      Assertions.assertThat(Http.put("http://" + host + "/rest/testcache/incorrect").basicAuth("notauser", pass).data("user", ContentType.TEXT_PLAIN).execute().code()).isEqualTo(401);
   }

   @Test
   void restNoCredentials() throws IOException {
      Assertions.assertThat(Http.put("http://" + host + "/rest/testcache/no").data("credentials", ContentType.TEXT_PLAIN).execute().code()).isEqualTo(401);
   }

   @Test
   void hotrodCorrectCredentials() throws IOException {
      String get = String.format("http://" + testServerHost + "/hotrod/auth?username=%s&password=%s&servicename=%s", user, URLEncoder.encode(pass, StandardCharsets.UTF_8.toString()), appName);
      Assertions.assertThat(Http.get(get).execute().code()).isEqualTo(200);
   }

   @Test
   void hotrodInvalidPassword() throws IOException {
      String get = String.format("http://" + testServerHost + "/hotrod/auth?username=%s&password=%s&servicename=%s", user, URLEncoder.encode("invalid", StandardCharsets.UTF_8.toString()), appName);
      Assertions.assertThat(Http.get(get).execute().code()).isEqualTo(401);
   }

   @Test
   void hotrodInvalidUser() throws IOException {
      String get = String.format("http://" + testServerHost + "/hotrod/auth?username=%s&password=%s&servicename=%s", "notauser", URLEncoder.encode(pass, StandardCharsets.UTF_8.toString()), appName);
      Assertions.assertThat(Http.get(get).execute().code()).isEqualTo(401);
   }
}
