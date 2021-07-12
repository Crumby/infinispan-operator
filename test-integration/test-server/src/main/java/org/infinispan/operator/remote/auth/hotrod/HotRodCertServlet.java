package org.infinispan.operator.remote.auth.hotrod;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.exceptions.HotRodClientException;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Used by MinimalSetupIT with unencrypted endpoints.
 */
@WebServlet("/hotrod/cert")
public class HotRodCertServlet extends HttpServlet {
   private static final long serialVersionUID = 1L;

   protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
      PrintWriter pw = new PrintWriter(response.getOutputStream());

      try {
         String serviceName = request.getParameter("servicename");

         ConfigurationBuilder builder = new ConfigurationBuilder();
         builder.addServer().host(serviceName).port(11222);
         //builder.security().authentication().realm("default").serverName("infinispan").username("testuser").password("testpass");
         builder.security().ssl()
                 .keyStoreFileName("/etc/client-secret/keystore").trustStorePassword("password".toCharArray())
                 .trustStoreFileName("/etc/truststore-secret/truststore.p12").keyStorePassword("password".toCharArray());

         RemoteCacheManager rcm = new RemoteCacheManager(builder.build());
         RemoteCache<String, String> rc = rcm.administration().getOrCreateCache("hotrod-auth-test", "org.infinispan.DIST_SYNC");

         rc.put("authorized-hotrod-key", "secret-value");

         if(!"secret-value".equals(rc.get("authorized-hotrod-key"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            pw.println("UNAUTHORIZED");
         }
      } catch (HotRodClientException e) {
         response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
         System.out.println(e.getMessage());
         pw.println("UNAUTHORIZED: " + e.getMessage());
      } catch (Exception e) {
         response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
         System.out.println(e.getMessage());
         pw.println("INTERNAL SERVER ERROR: " + e.getMessage());
      } finally {
         pw.close();
      }
   }
}
