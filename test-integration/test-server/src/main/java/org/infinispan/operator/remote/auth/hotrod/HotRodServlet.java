package org.infinispan.operator.remote.auth.hotrod;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.SaslQop;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;

@WebServlet("/hotrod/auth")
public class HotRodServlet extends HttpServlet {
   private static final long serialVersionUID = 1L;

   protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
      try (PrintWriter pw = new PrintWriter(response.getOutputStream())) {
         String serviceName = request.getParameter("servicename");
         String username = request.getParameter("username");
         String password = request.getParameter("password");

         ConfigurationBuilder builder = new ConfigurationBuilder();
         builder.addServer().host(serviceName).port(11222);
         builder.marshaller(new ProtoStreamMarshaller());
         builder.security().authentication().realm("default").serverName("infinispan").saslMechanism("DIGEST-MD5").saslQop(SaslQop.AUTH).username(username).password(password).enable();

         try {
            RemoteCacheManager rcm = new RemoteCacheManager(builder.build());
            RemoteCache<String, String> rc = rcm.getCache("testcache");

            rc.put(username, password);

            if(!password.equals(rc.get(username))) {
               response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
               pw.println("UNAUTHORIZED");
            }
         } catch (HotRodClientException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            pw.println("UNAUTHORIZED");
         }
      }
   }
}
