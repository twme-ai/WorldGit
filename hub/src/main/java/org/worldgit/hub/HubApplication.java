package org.worldgit.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/** WorldGit Hub：smart HTTP git 伺服器 + 世界歷史 API + 3D 檢視器靜態檔。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class HubApplication {
  public static void main(String[] args) {
    SpringApplication.run(HubApplication.class, args);
  }
}
