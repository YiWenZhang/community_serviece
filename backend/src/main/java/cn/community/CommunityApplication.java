package cn.community;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 社区业务与政策RAG模块；领域包内部协作使用本地事务。 */
@SpringBootApplication
@EnableScheduling
public class CommunityApplication {
  public static void main(String[] args) {
    if (java.util.Arrays.asList(args).contains("--install-initial-data")
        || java.util.Arrays.asList(args).contains("--check-initial-data")
        || java.util.Arrays.asList(args).contains("--upgrade-elder-private-info")) {
      try {
        cn.community.bootstrap.InitialDataInstaller.run(args);
      } catch (Exception e) {
        System.err.println("初始数据维护失败：" + e.getClass().getSimpleName());
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof cn.community.common.Problem) System.err.println(cause.getMessage());
        System.err.println("请查看备份目录manifest.json；正常运行的HTTP服务不会自动执行此维护操作。");
        System.exit(1);
      }
      return;
    }
    SpringApplication.run(CommunityApplication.class, args);
  }
}
