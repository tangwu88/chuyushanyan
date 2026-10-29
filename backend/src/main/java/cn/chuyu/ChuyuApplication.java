package cn.chuyu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ChuyuApplication {
  static {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
  }

  public static void main(String[] args) {
    SpringApplication.run(ChuyuApplication.class, args);
  }
}
