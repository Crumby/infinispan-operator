package org.infinispan;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Infinispan {
   private final String clusterName;
   private final String crPath;

   public InputStream getInputStream() throws IOException {
      return new FileInputStream(new File(crPath));
   }
}
