package cn.community;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 社区业务与政策RAG模块；领域包内部协作使用本地事务。 */
@SpringBootApplication
@EnableScheduling
public class CommunityApplication {
  public static void main(String[] args) {
    SpringApplication.run(CommunityApplication.class, args);
  }
}
