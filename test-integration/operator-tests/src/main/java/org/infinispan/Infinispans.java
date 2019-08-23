package org.infinispan;

public class Infinispans {

   public static Infinispan defaultAuth() {
      return new Infinispan("infinispan-minimal", "src/test/resources/infinispans/cr_minimal.yaml");
   }

   public static Infinispan customAuth() {
      return new Infinispan("infinispan-minimal-auth", "src/test/resources/infinispans/cr_minimal_with_auth.yaml");
   }
}
