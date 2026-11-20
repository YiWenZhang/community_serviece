package cn.community.bootstrap;

import cn.community.ai.*;
import cn.community.common.*;
import cn.community.elder.ElderPersonalInfo;
import cn.community.elder.ElderService;
import cn.community.files.FileService;
import cn.community.identity.*;
import cn.community.notice.NoticeService;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit offline maintenance entry point. Never registered as a web endpoint or startup runner.
 */
public final class InitialDataInstaller {
  private static final Set<String> TABLES =
      Set.of(
          "ai_chunk",
          "ai_conversation",
          "ai_document",
          "ai_message",
          "ai_provider",
          "ai_schema_version",
          "app_schema_version",
          "audit_log",
          "community",
          "community_profile",
          "delivery_attachment",
          "delivery_attempt",
          "elder_assignment_history",
          "elder_global_sequence",
          "elder_no_sequence",
          "elder_profile",
          "elder_service_need",
          "elder_vaccine_status",
          "file_object",
          "followup_record",
          "followup_task",
          "grid",
          "job_run",
          "notice",
          "notice_recipient",
          "notice_recipient_event",
          "notice_rule",
          "outbox_event",
          "report_job",
          "report_metric",
          "service_feedback",
          "service_need",
          "sys_permission",
          "sys_role",
          "sys_role_permission",
          "sys_user",
          "sys_user_role",
          "user_community",
          "vaccination_record",
          "vaccine_campaign",
          "vaccine_product");
  private final Path project, storage;
  private final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  private final Privacy privacy = new Privacy();
  private final Crypto crypto;
  private final DriverManagerDataSource source;
  private final Db db;
  private final Map<String, Object> data;
  private final Map<String, String> policyText = new LinkedHashMap<>();
  private final String database;
  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
  private final Map<Long, List<Long>> elderIds = new LinkedHashMap<>();
  private final Map<Long, Long> campaigns = new LinkedHashMap<>();
  private final Map<Long, Long> additionalCampaigns = new LinkedHashMap<>();
  private int accountVersion;

  public static void run(String[] args) throws Exception {
    var options =
        Arrays.stream(args)
            .filter(a -> a.startsWith("--") && a.contains("="))
            .map(a -> a.substring(2).split("=", 2))
            .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    var installer = new InitialDataInstaller(Path.of(required("IMPORT_PROJECT_ROOT")));
    installer.validatePackage();
    installer.validateSchema();
    if (Arrays.asList(args).contains("--upgrade-elder-private-info")) {
      installer.upgradePrivateSchema();
      return;
    }
    if (Arrays.asList(args).contains("--check-initial-data")) {
      System.out.println("初始数据包及目标41张表检查通过：" + installer.database);
      return;
    }
    Problem.require(
        installer.database.equals(options.get("confirm-reset")),
        "必须提供与目标数据库完全一致的--confirm-reset参数");
    installer.install();
  }

  public InitialDataInstaller(Path project) throws Exception {
    this.project = project.toRealPath();
    storage = safePath(Path.of(required("APP_STORAGE")));
    String url = required("DB_URL");
    URI target = URI.create(url.substring("jdbc:".length()));
    database = target.getPath().substring(1);
    Problem.require(
        url.startsWith("jdbc:mysql://")
            && Set.of("127.0.0.1", "localhost").contains(target.getHost())
            && database.matches("community_elder_service(?:_[a-z0-9_]+_(?:test|regression))?"),
        "此维护工具只允许本机community_elder_service或明确命名的隔离测试库");
    source =
        new DriverManagerDataSource(
            url, required("DB_USERNAME"), System.getenv().getOrDefault("DB_PASSWORD", ""));
    db = new Db(new NamedParameterJdbcTemplate(source));
    crypto = new Crypto(required("APP_AES_KEY"), required("APP_HMAC_KEY"));
    data =
        json.map(Files.readString(this.project.resolve("database/initial-data/communities.json")));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    Problem.require(value != null && !value.isBlank(), "缺少维护参数：" + name);
    return value;
  }

  private Path safePath(Path path) throws IOException {
    Path resolved = path.toAbsolutePath().normalize();
    Problem.require(resolved.startsWith(project) && !resolved.equals(project), "维护文件路径必须位于当前项目目录内");
    if (Files.exists(resolved))
      Problem.require(resolved.toRealPath().startsWith(project), "维护路径不能指向项目外部");
    return resolved;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rows(String key) {
    return (List<Map<String, Object>>) data.get(key);
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  @SuppressWarnings("unchecked")
  private static List<Number> numbers(Map<String, Object> row, String key) {
    return (List<Number>) row.get(key);
  }

  public void validatePackage() throws Exception {
    Problem.require(
        rows("communities").size() == 4
            && rows("users").size() == 7
            && rows("elders").size() == 20
            && rows("policies").size() == 11,
        "初始数据包规模与预期不符");
    Problem.require(
        rows("communities").stream()
            .map(c -> c.get("name"))
            .toList()
            .equals(List.of("智园", "慧园", "卓园", "越园")),
        "社区清单不符");
    for (var c : rows("communities")) {
      privacy.check(c.get("name").toString());
      privacy.check(c.get("description").toString());
    }
    for (var u : rows("users")) {
      privacy.check(u.get("username").toString());
      privacy.check(u.get("displayName").toString());
    }
    for (var e : rows("elders")) {
      privacy.check(e.get("remark").toString());
      new ElderPersonalInfo(crypto, json, privacy).validate(personalInfo(e));
      Problem.require(
          rows("users").stream()
              .anyMatch(
                  u ->
                      number(u, "id") == number(e, "owner")
                          && "GRID_WORKER".equals(u.get("role"))
                          && numbers(u, "communities").stream()
                              .anyMatch(c -> c.longValue() == number(e, "community"))),
          "老人负责人未获社区授权");
    }
    for (var p : rows("policies")) {
      Path file = safePath(project.resolve(p.get("path").toString()));
      String text = Files.readString(file, StandardCharsets.UTF_8);
      Problem.require(crypto.digest(text).equals(p.get("sha256")), "政策文件校验失败，请重新生成数据包清单");
      privacy.check(text);
      new PolicyParser().split(text);
      policyText.put(p.get("path").toString(), text);
    }
  }

  public void validateSchema() {
    Set<String> actual =
        db
            .list(
                "SELECT table_name AS t FROM information_schema.tables WHERE"
                    + " table_schema=DATABASE() AND table_type='BASE TABLE'",
                Map.of())
            .stream()
            .map(r -> r.get("t").toString())
            .collect(Collectors.toSet());
    Problem.require(actual.equals(TABLES), "目标数据库结构不是预期41张表，请先执行V002与AI表升级；未清理任何数据");
    Problem.require(
        database.equals(db.one("SELECT DATABASE() AS d", Map.of()).get("d")), "目标数据库校验失败");
    Problem.require(
        db.count("SELECT COUNT(*) FROM app_schema_version WHERE version=2", Map.of()) == 1,
        "请先完成V002升级");
  }

  public void install() throws Exception {
    validatePackage();
    validateSchema();
    elderIds.clear();
    campaigns.clear();
    additionalCampaigns.clear();
    try (Connection lock = source.getConnection();
        var statement = lock.prepareStatement("SELECT GET_LOCK(?,0)")) {
      statement.setString(1, "initial-data:" + database);
      try (var result = statement.executeQuery()) {
        result.next();
        Problem.require(result.getInt(1) == 1, "另一个初始数据更新正在执行");
      }
      String run =
          LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
              + "-"
              + UUID.randomUUID().toString().substring(0, 8);
      Path backup = safePath(project.resolve("database/backups/four-communities").resolve(run));
      Files.createDirectories(backup);
      Path staged = backup.resolve("new-storage");
      Files.createDirectories(staged.resolve("policies"));
      Map<String, Object> journal =
          new LinkedHashMap<>(
              Db.p(
                  "database",
                  database,
                  "dataset",
                  data.get("version"),
                  "backup",
                  backup.toString(),
                  "storage",
                  storage.toString(),
                  "status",
                  "PREPARING"));
      Map<String, Object> before = new TreeMap<>();
      for (String table : TABLES)
        before.put(table, db.count("SELECT COUNT(*) FROM `" + table + "`", Map.of()));
      journal.put("previousCounts", before);
      long previousVersion = db.count("SELECT COALESCE(MAX(version),0) FROM sys_user", Map.of());
      Problem.require(previousVersion < Integer.MAX_VALUE - 1, "账号版本已达维护上限，停止更新");
      accountVersion = (int) previousVersion + 1;
      writeJournal(backup, journal);
      backupDatabase(backup);
      migratePrivateSchema();
      journal.put("status", "BACKED_UP");
      writeJournal(backup, journal);
      boolean[] moved = {false, false};
      try {
        new TransactionTemplate(new DataSourceTransactionManager(source))
            .executeWithoutResult(
                tx -> {
                  try {
                    clearRows();
                    seedReferenceData();
                    seedCommunitiesAndUsers();
                    var auth = new Auth(db);
                    var audit = new Audit(db, json);
                    var elders =
                        new ElderService(
                            db,
                            crypto,
                            audit,
                            privacy,
                            auth,
                            new ElderPersonalInfo(crypto, json, privacy));
                    var files = new FileService(staged.toString(), db, audit);
                    var notices =
                        new NoticeService(db, elders, files, audit, privacy, json, crypto);
                    seedBusiness(auth, elders, notices);
                    seedPolicies(staged);
                    try (var vectors = new VectorStoreResource(staged, json)) {
                      vectors.store.rebuild(db.list("SELECT * FROM ai_chunk", Map.of()));
                    }
                    verify();
                    db.insert(
                        "audit_log",
                        Db.p(
                            "system_actor",
                            "INITIAL_DATA_IMPORTER",
                            "action",
                            "INITIAL_DATA_INSTALLED",
                            "target_type",
                            "DATABASE",
                            "target_id",
                            database,
                            "trace_id",
                            UUID.randomUUID().toString(),
                            "result",
                            "SUCCESS",
                            "change_summary_json",
                            json.write(
                                Db.p(
                                    "dataset",
                                    data.get("version"),
                                    "syntheticProfiles",
                                    true,
                                    "communities",
                                    4,
                                    "users",
                                    7,
                                    "elders",
                                    20,
                                    "policies",
                                    32)),
                            "occurred_at",
                            ElderService.utc()));
                    journal.put("status", "REPLACING_STORAGE");
                    writeJournal(backup, journal);
                    if (Files.exists(storage)) {
                      Files.move(storage, backup.resolve("storage"));
                      moved[0] = true;
                    }
                    Files.createDirectories(storage.getParent());
                    Files.move(staged, storage);
                    moved[1] = true;
                  } catch (Exception e) {
                    throw new IllegalStateException("初始数据事务未完成", e);
                  }
                });
      } catch (Exception e) {
        if (moved[1]) Files.move(storage, backup.resolve("failed-storage"));
        if (moved[0]) Files.move(backup.resolve("storage"), storage);
        journal.put("status", "ROLLED_BACK");
        writeJournal(backup, journal);
        throw e;
      }
      journal.put("status", "SUCCESS");
      journal.put("installedAt", Instant.now().toString());
      writeJournal(backup, journal);
      Files.writeString(
          project.resolve("database/initial-data-install.local"),
          json.write(journal),
          StandardCharsets.UTF_8);
      System.out.println("四社区初始数据导入完成：4个社区、7个账号、20名老人、12批通知、32份已入库政策。");
      System.out.println("完整备份：" + backup);
    }
  }

  private ElderPersonalInfo.Info personalInfo(Map<String, Object> row) {
    var p = json.map(row.get("personalInfo"));
    return new ElderPersonalInfo.Info(
        p.get("name").toString(),
        p.get("phone").toString(),
        p.get("address").toString(),
        p.get("familyName").toString(),
        p.get("familyRelation").toString(),
        p.get("familyPhone").toString());
  }

  private void migratePrivateSchema() {
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.FileSystemResource(
                project.resolve("database/migrations/V003__elder_personal_info_encryption.sql")))
        .execute(source);
  }

  public void upgradePrivateSchema() throws Exception {
    Path backup =
        safePath(
            project
                .resolve("database/backups/private-profile")
                .resolve(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8)));
    Files.createDirectories(backup);
    var journal =
        new LinkedHashMap<String, Object>(
            Db.p(
                "database",
                database,
                "status",
                "PREPARING",
                "kind",
                "V003_SCHEMA_UPGRADE",
                "storage",
                storage.toString()));
    writeJournal(backup, journal);
    backupDatabase(backup);
    journal.put("status", "BACKED_UP");
    writeJournal(backup, journal);
    migratePrivateSchema();
    journal.put("status", "SUCCESS");
    writeJournal(backup, journal);
    System.out.println("V003升级完成，原有老人、账号、政策及运行文件保留。旧档案姓名和联系方式请补录；梁山姓名初始包需使用重建命令安装。");
    System.out.println("数据库备份：" + backup);
  }

  private void writeJournal(Path backup, Map<String, Object> journal) throws IOException {
    Files.writeString(backup.resolve("manifest.json"), json.write(journal), StandardCharsets.UTF_8);
  }

  private void backupDatabase(Path backup) throws Exception {
    URI target = URI.create(required("DB_URL").substring(5));
    var builder =
        new ProcessBuilder(
            required("IMPORT_MYSQLDUMP"),
            "--no-defaults",
            "--protocol=TCP",
            "--host=" + target.getHost(),
            "--port=" + (target.getPort() < 0 ? 3306 : target.getPort()),
            "--user=" + required("DB_USERNAME"),
            "--single-transaction",
            "--skip-lock-tables",
            "--hex-blob",
            "--no-tablespaces",
            "--set-gtid-purged=OFF",
            "--column-statistics=0",
            "--default-character-set=utf8mb4",
            "--databases",
            database);
    builder.environment().put("MYSQL_PWD", System.getenv().getOrDefault("DB_PASSWORD", ""));
    builder.redirectOutput(backup.resolve("database.sql").toFile());
    builder.redirectError(backup.resolve("dump-error.log").toFile());
    var process = builder.start();
    Problem.require(
        process.waitFor() == 0 && Files.size(backup.resolve("database.sql")) > 1024,
        "数据库备份失败，未开始清理；请检查备份目录日志");
  }

  private void clearRows() {
    // Audit creator links form community->user->community cycles; break only nullable audit FKs.
    for (var r :
        db.list(
            "SELECT DISTINCT k.table_name AS t,k.column_name AS c FROM"
                + " information_schema.key_column_usage k JOIN information_schema.columns c ON"
                + " c.table_schema=k.table_schema AND c.table_name=k.table_name AND"
                + " c.column_name=k.column_name WHERE k.table_schema=DATABASE() AND"
                + " k.referenced_table_name IS NOT NULL AND k.column_name IN"
                + " ('created_by','updated_by') AND c.is_nullable='YES'",
            Map.of())) {
      String table = r.get("t").toString(), column = r.get("c").toString();
      Problem.require(TABLES.contains(table), "审计关联结构校验失败");
      db.update("UPDATE `" + table + "` SET `" + column + "`=NULL", Map.of());
    }
    // Nullable self-references are broken inside the same transaction, not by disabling FK checks.
    for (var r :
        db.list(
            "SELECT DISTINCT table_name AS t,column_name AS c FROM"
                + " information_schema.key_column_usage WHERE table_schema=DATABASE() AND"
                + " table_name=referenced_table_name AND table_name<>'vaccination_record'",
            Map.of())) {
      String table = r.get("t").toString(), column = r.get("c").toString();
      Problem.require(TABLES.contains(table) && column.matches("[a-z_]+"), "自引用结构校验失败");
      db.update("UPDATE `" + table + "` SET `" + column + "`=NULL", Map.of());
    }
    Map<String, Set<String>> parents = new TreeMap<>();
    for (String t : TABLES) parents.put(t, new TreeSet<>());
    for (var r :
        db.list(
            "SELECT table_name AS child,referenced_table_name AS parent FROM"
                + " information_schema.key_column_usage WHERE table_schema=DATABASE() AND"
                + " referenced_table_name IS NOT NULL AND table_name<>referenced_table_name AND"
                + " column_name NOT IN ('created_by','updated_by')",
            Map.of())) parents.get(r.get("child").toString()).add(r.get("parent").toString());
    List<String> order = new ArrayList<>();
    Set<String> visited = new HashSet<>(), visiting = new HashSet<>();
    for (String t : parents.keySet()) visit(t, parents, visited, visiting, order);
    Collections.reverse(order);
    for (String table : order) {
      if (table.equals("vaccination_record")) {
        while (db.count("SELECT COUNT(*) FROM vaccination_record", Map.of()) > 0)
          Problem.require(
              db.update(
                      "DELETE p FROM vaccination_record p LEFT JOIN vaccination_record c ON"
                          + " c.supersedes_record_id=p.id WHERE c.id IS NULL",
                      Map.of())
                  > 0,
              "接种更正历史存在循环，停止清理");
      } else db.update("DELETE FROM `" + table + "`", Map.of());
    }
  }

  private static void visit(
      String table,
      Map<String, Set<String>> parents,
      Set<String> done,
      Set<String> busy,
      List<String> result) {
    if (done.contains(table)) return;
    Problem.require(busy.add(table), "发现循环外键，未能执行受控清理");
    for (String parent : parents.get(table)) visit(parent, parents, done, busy, result);
    busy.remove(table);
    done.add(table);
    result.add(table);
  }

  private void seedReferenceData() {
    String[] roles = {"ADMIN|管理员", "GRID_WORKER|网格员", "STATISTICIAN|统计员"};
    for (int i = 0; i < roles.length; i++) {
      var v = roles[i].split("\\|");
      db.insert("sys_role", Db.p("id", i + 1, "code", v[0], "name", v[1]));
    }
    String[] permissions = {
      "system:user:manage|用户管理",
      "system:role:manage|角色权限管理",
      "system:config:manage|系统参数管理",
      "elder:transfer|老人归属移交",
      "audit:read|查看操作日志",
      "elder:read|读取老人档案",
      "elder:create|登记老人档案",
      "elder:update|维护老人档案",
      "vaccine:verify|资格与接种核验",
      "notice:read|读取通知",
      "notice:create|创建通知",
      "notice:receive|登记触达及接收",
      "followup:read|读取跟进",
      "followup:update|记录跟进",
      "feedback:create|服务反馈",
      "report:read:own|读取本人汇总",
      "report:export:own|导出本人汇总",
      "report:read:community|读取全社区统计",
      "report:export:community|导出全社区报表",
      "file:upload|上传业务附件",
      "file:read|按权限读取附件",
      "notice:rule:manage|维护通知规则",
      "ai:knowledge:manage|维护政策知识库",
      "ai:chat|使用政策问答",
      "ai:model:manage|管理AI模型配置",
      "system:community:manage|社区与授权管理"
    };
    for (int i = 0; i < permissions.length; i++) {
      var p = permissions[i].split("\\|");
      long id = i + 1;
      db.insert(
          "sys_permission", Db.p("id", id, "code", p[0], "name", p[1], "resource_type", "ACTION"));
      db.insert("sys_role_permission", Db.p("role_id", 1, "permission_id", id));
      if ((id >= 6 && id <= 17) || id == 20 || id == 21 || id == 24)
        db.insert("sys_role_permission", Db.p("role_id", 2, "permission_id", id));
      if (id == 18 || id == 19)
        db.insert("sys_role_permission", Db.p("role_id", 3, "permission_id", id));
    }
    for (var n : rows("serviceNeeds"))
      db.insert(
          "service_need", Db.p("id", n.get("id"), "code", n.get("code"), "name", n.get("name")));
    String[] products = {"INFLUENZA|流感疫苗", "PNEUMOCOCCAL|肺炎球菌疫苗", "HERPES_ZOSTER|带状疱疹疫苗"};
    for (int i = 0; i < products.length; i++) {
      var p = products[i].split("\\|");
      db.insert("vaccine_product", Db.p("id", i + 1, "code", p[0], "name", p[1]));
    }
    db.update("INSERT INTO app_schema_version(version) VALUES(2),(3)", Map.of());
    db.update("INSERT INTO ai_schema_version(version) VALUES(1)", Map.of());
  }

  private void seedCommunitiesAndUsers() throws Exception {
    for (var c : rows("communities")) {
      long cid = number(c, "id");
      db.insert("community", Db.p("id", cid, "code", c.get("code"), "name", c.get("name")));
      @SuppressWarnings("unchecked")
      var grids = (List<String>) c.get("grids");
      for (int i = 0; i < grids.size(); i++)
        db.insert(
            "grid",
            Db.p(
                "id",
                (cid - 1) * 2 + i + 1,
                "community_id",
                cid,
                "code",
                "G" + (i + 1),
                "name",
                grids.get(i)));
      db.update(
          "INSERT INTO community_profile(community_id,description,icon_data,icon_mime)"
              + " VALUES(:c,:description,:icon,'image/png')",
          Db.p("c", cid, "description", c.get("description"), "icon", icon(c)));
    }
    var encoder = new BCryptPasswordEncoder(12);
    for (var u : rows("users")) {
      long uid = number(u, "id");
      db.insert(
          "sys_user",
          Db.p(
              "id",
              uid,
              "community_id",
              u.get("home"),
              "username",
              u.get("username"),
              "display_name",
              u.get("displayName"),
              "password_hash",
              encoder.encode(Passwords.INITIAL),
              "status",
              "ACTIVE",
              "version",
              accountVersion));
      db.insert(
          "sys_user_role",
          Db.p(
              "user_id",
              uid,
              "role_id",
              switch (u.get("role").toString()) {
                case "ADMIN" -> 1;
                case "GRID_WORKER" -> 2;
                default -> 3;
              }));
      for (Number cid : numbers(u, "communities"))
        db.update(
            "INSERT INTO user_community(user_id,community_id,created_by) VALUES(:u,:c,1)",
            Db.p("u", uid, "c", cid));
    }
    for (var c : rows("communities")) {
      long cid = number(c, "id");
      String name = c.get("name").toString();
      for (int product = 1; product <= 2; product++) {
        long id =
            db.insert(
                "vaccine_campaign",
                Db.p(
                    "community_id",
                    cid,
                    "vaccine_id",
                    product,
                    "cycle_key",
                    Integer.toString(today.getYear()),
                    "dose_stage",
                    product == 1 ? "ANNUAL" : "CONSULTATION",
                    "name",
                    today.getYear() + "年度" + name + (product == 1 ? "流感接种服务" : "肺炎球菌疫苗咨询与核验"),
                    "start_date",
                    LocalDate.of(today.getYear(), 1, 1),
                    "end_date",
                    LocalDate.of(today.getYear(), 12, 31),
                    "eligibility_rule_ref",
                    "SQ-YM-2026-03；接种机构专业核验",
                    "created_by",
                    1));
        (product == 1 ? campaigns : additionalCampaigns).put(cid, id);
      }
      for (String type : List.of("BIRTHDAY", "VACCINE"))
        db.insert(
            "notice_rule",
            Db.p(
                "community_id",
                cid,
                "code",
                type.equals("BIRTHDAY") ? "BIRTH_MONTH_CARE" : "FLU_CARE",
                "name",
                name + (type.equals("BIRTHDAY") ? "生日月份关怀" : "接种服务联系"),
                "notice_type",
                type,
                "trigger_kind",
                type.equals("BIRTHDAY") ? "BIRTH_MONTH" : "VACCINE_STATUS",
                "config_json",
                json.write(
                    Db.p(
                        "leadDays",
                        7,
                        "campaignId",
                        type.equals("VACCINE") ? campaigns.get(cid) : null)),
                "enabled",
                0,
                "created_by",
                1));
      String key = System.getenv("DEEPSEEK_API_KEY");
      if (key != null && !key.isBlank())
        db.insert(
            "ai_provider",
            Db.p(
                "community_id",
                cid,
                "name",
                "DeepSeek Flash",
                "kind",
                "CHAT",
                "api_format",
                "OPENAI",
                "base_url",
                "https://api.deepseek.com",
                "model",
                "deepseek-flash",
                "api_key_cipher",
                crypto.encrypt(key.trim()),
                "active",
                true,
                "created_by",
                1));
    }
  }

  private byte[] icon(Map<String, Object> community) throws IOException {
    var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
    var g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.decode(community.get("color").toString()));
    g.fillRoundRect(0, 0, 256, 256, 54, 54);
    g.setColor(new Color(255, 255, 255, 35));
    g.fillOval(145, -30, 150, 150);
    g.fillOval(-30, 160, 110, 110);
    g.setColor(Color.WHITE);
    g.setFont(new Font("SansSerif", Font.BOLD, 77));
    String name = community.get("name").toString();
    var metrics = g.getFontMetrics();
    g.drawString(name, (256 - metrics.stringWidth(name)) / 2, 151);
    g.setFont(new Font("SansSerif", Font.PLAIN, 17));
    String caption = "COMMUNITY CARE";
    g.drawString(caption, (256 - g.getFontMetrics().stringWidth(caption)) / 2, 202);
    g.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  private void seedBusiness(Auth auth, ElderService elders, NoticeService notices) {
    for (var p : rows("elders")) {
      long cid = number(p, "community"), owner = number(p, "owner");
      var actor = auth.worker(owner, cid);
      Set<Long> needs =
          numbers(p, "needs").stream()
              .map(Number::longValue)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      var result =
          json.map(
              elders.create(
                  actor,
                  new ElderService.Draft(
                      (cid - 1) * 2 + number(p, "gridIndex") + 1,
                      p.get("sex").toString(),
                      p.get("birthMonth").toString(),
                      p.get("remark").toString(),
                      needs,
                      null,
                      personalInfo(p))));
      long id = number(result, "id");
      elderIds.computeIfAbsent(cid, k -> new ArrayList<>()).add(id);
      String status = p.get("vaccine").toString();
      String eligibility =
          status.equals("UNKNOWN")
              ? "UNKNOWN"
              : status.equals("INELIGIBLE") ? "INELIGIBLE" : "ELIGIBLE";
      String vaccination = status.equals("INELIGIBLE") ? "NOT_VACCINATED" : status;
      boolean completed = status.equals("COMPLETED"),
          appointment = status.equals("NOT_VACCINATED") && id % 2 == 0;
      elders.vaccine(
          actor,
          id,
          new ElderService.Vaccine(
              0,
              campaigns.get(cid),
              eligibility,
              vaccination,
              appointment ? today.plusDays(5) : null,
              completed ? today.minusDays(14) : null,
              completed ? 1 : null,
              completed
                  ? "已核对接种日期和实际针次，登记接种事实"
                  : status.equals("UNKNOWN")
                      ? "尚未取得接种机构核验结论，保持资格与状态待核实"
                      : status.equals("INELIGIBLE")
                          ? "已核对本期评估结果，暂不纳入本期适宜接种对象；具体医学意见由接种机构解释"
                          : "已核对资格信息，接种事实尚未确认；按本人意愿协助预约"));
      if (id % 2 == 0)
        elders.vaccine(
            actor,
            id,
            new ElderService.Vaccine(
                0,
                additionalCampaigns.get(cid),
                "UNKNOWN",
                "UNKNOWN",
                null,
                null,
                null,
                "本人咨询肺炎球菌疫苗服务，待接种机构评估，不据年龄自行判定资格"));
    }
    for (var c : rows("communities")) {
      long cid = number(c, "id");
      String name = c.get("name").toString();
      var admin = auth.worker(1, cid);
      List<Long> ids = elderIds.get(cid);
      for (int kind = 0; kind < 3; kind++) {
        Set<Long> targets =
            kind == 0
                ? db
                    .list(
                        "SELECT elder_id FROM elder_vaccine_status WHERE campaign_id=:campaign AND"
                            + " eligibility<>'INELIGIBLE' AND vaccination_status<>'COMPLETED' ORDER"
                            + " BY elder_id",
                        Db.p("campaign", campaigns.get(cid)))
                    .stream()
                    .map(r -> number(r, "elder_id"))
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                : new LinkedHashSet<>(ids);
        String type = kind == 0 ? "VACCINE" : kind == 1 ? "OTHER" : "LECTURE";
        String title =
            name + (kind == 0 ? "冬季疫苗服务咨询与预约联系" : kind == 1 ? "助餐与居家支持需求确认" : "银龄健康与社区活动邀请");
        String content =
            kind == 0
                ? "请按本人意愿了解本年度流感疫苗服务。资格、费用和预约由接种机构核实；确认接收通知不表示同意或已经接种。需要协助预约的，请告知责任网格员合适的联系时段。"
                : kind == 1
                    ? "社区正在核对工作日午餐、居家照护和适老化转介需求。可以线下提出申请，服务承接和收费由相应机构确认，不以智能手机使用为条件。请按本人实际需要选择参与。"
                    : "邀请参加社区健康科普、阅读及适宜文体活动。具体时间与场地由本期活动安排另行确认，参与自愿；出行或沟通需要协助的，可向责任网格员提出。";
        long nid =
            number(
                json.map(
                    notices.create(
                        admin,
                        new NoticeService.Draft(
                            type,
                            title,
                            content,
                            targets,
                            kind == 0 ? campaigns.get(cid) : null,
                            YearMonth.from(today).toString()))),
                "id");
        notices.activate(admin, nid, 0);
        var recipients =
            db.list(
                "SELECT id,elder_id,owner_user_id FROM notice_recipient WHERE notice_id=:id ORDER"
                    + " BY elder_id",
                Db.p("id", nid));
        for (int i = 0; i < recipients.size(); i++) {
          var r = recipients.get(i);
          long rid = number(r, "id");
          var worker = auth.worker(number(r, "owner_user_id"), cid);
          if (i == 4 && kind == 2) continue;
          if (i == 2)
            attempt(
                notices,
                worker,
                rid,
                "HOME_VISIT",
                "UNREACHABLE",
                "NO_ONE_HOME",
                "按约定时间上门未遇到本人，下一工作日另约时段联系",
                false);
          else if (i == 3 && kind == 2)
            attempt(
                notices,
                worker,
                rid,
                "FAMILY_RELAY",
                "REFUSED",
                null,
                "本人经家属明确表示本期不参加集体活动，尊重意愿并保留其他服务渠道",
                false);
          else {
            Long task = null;
            if (i == 0 && kind == 1) {
              attempt(
                  notices,
                  worker,
                  rid,
                  "FAMILY_RELAY",
                  "UNREACHABLE",
                  "OTHER",
                  "家属当时不便确认是否已转告，约定稍后核实",
                  false);
              task =
                  db.count(
                      "SELECT id FROM followup_task WHERE recipient_id=:id AND"
                          + " task_type='RECONTACT' AND status='PENDING'",
                      Db.p("id", rid));
            }
            long aid = attempt(notices, worker, rid, "FAMILY_RELAY", "CONFIRMED", null, null, true);
            if (task != null) {
              db.update(
                  "UPDATE followup_task SET"
                      + " status='COMPLETED',completed_at=UTC_TIMESTAMP(3),cancel_reason=NULL,open_dedupe_key=NULL,version=version+1"
                      + " WHERE id=:id",
                  Db.p("id", task));
              db.insert(
                  "followup_record",
                  Db.p(
                      "task_id",
                      task,
                      "performed_at",
                      ElderService.utc(),
                      "outcome",
                      "RESOLVED",
                      "result_text",
                      "再次联系后家属明确确认已向本人转告，并反馈实际服务需求，事项闭环。",
                      "delivery_attempt_id",
                      aid,
                      "idempotency_key",
                      UUID.randomUUID().toString(),
                      "created_by",
                      worker.id()));
            }
            if (kind == 1 && i < 2)
              notices.feedback(
                  worker,
                  rid,
                  new NoticeService.Feedback(
                      0,
                      i == 0 ? "SATISFIED" : "NEUTRAL",
                      "FAMILY",
                      i == 0 ? "说明清楚，能够线下登记，家人愿意配合后续安排。" : "已了解服务内容，希望后续告知更多可选择的时段。"));
          }
        }
      }
    }
  }

  private long attempt(
      NoticeService notices,
      Actor actor,
      long recipient,
      String channel,
      String result,
      String unreachable,
      String reason,
      boolean confirmed) {
    int version =
        ((Number)
                db.one("SELECT version FROM notice_recipient WHERE id=:id", Db.p("id", recipient))
                    .get("version"))
            .intValue();
    boolean family = channel.equals("FAMILY_RELAY");
    var raw =
        db.one(
            "SELECT e.personal_info_cipher FROM notice_recipient r JOIN elder_profile e ON"
                + " e.id=r.elder_id WHERE r.id=:id",
            Db.p("id", recipient));
    var contact =
        new ElderPersonalInfo(crypto, json, privacy).read(raw.get("personal_info_cipher"));
    String familyName = Objects.toString(contact.get("familyName"), "某某"),
        relation = Objects.toString(contact.get("familyRelation"), "家属");
    return number(
        json.map(
            notices.attempt(
                actor,
                recipient,
                UUID.randomUUID().toString(),
                new NoticeService.Attempt(
                    version,
                    channel,
                    result,
                    unreachable,
                    reason,
                    confirmed ? "已经向本人说明，确认收到本次服务信息。" : null,
                    family ? familyName : null,
                    family ? relation : null,
                    family ? confirmed : null,
                    Set.of()))),
        "id");
  }

  private void seedPolicies(Path staged) throws IOException {
    var parser = new PolicyParser();
    for (var community : rows("communities"))
      for (var p : rows("policies")) {
        long cid = number(community, "id");
        if (p.get("community") != null && number(p, "community") != cid) continue;
        Path file = project.resolve(p.get("path").toString());
        String text = policyText.get(p.get("path").toString()), key = UUID.randomUUID() + ".bin";
        Files.writeString(
            staged.resolve("policies").resolve(key),
            text,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
        var parts = parser.split(text);
        long id =
            db.insert(
                "ai_document",
                Db.p(
                    "community_id",
                    cid,
                    "title",
                    p.get("title"),
                    "category",
                    p.get("category"),
                    "issuer",
                    p.get("issuer"),
                    "policy_no",
                    p.get("policyNo"),
                    "effective_date",
                    LocalDate.parse(p.get("effectiveDate").toString()),
                    "is_demo",
                    false,
                    "safe_name",
                    file.getFileName().toString(),
                    "storage_key",
                    key,
                    "sha256",
                    p.get("sha256"),
                    "status",
                    "READY",
                    "extracted_text",
                    text,
                    "summary",
                    "按公开原文或内部规范条款建立的初始知识条目；摘要为原文摘录，完整依据见原文片段。",
                    "chunk_count",
                    parts.size(),
                    "created_by",
                    1));
        for (var part : parts) {
          String summary = part.content().strip();
          if (summary.length() > 300) summary = summary.substring(0, 300);
          String keywords = p.get("title") + " " + p.get("policyNo");
          db.insert(
              "ai_chunk",
              Db.p(
                  "document_id",
                  id,
                  "community_id",
                  cid,
                  "ordinal",
                  part.ordinal(),
                  "section",
                  part.section(),
                  "page_no",
                  part.page(),
                  "start_offset",
                  part.start(),
                  "end_offset",
                  part.end(),
                  "content",
                  part.content(),
                  "summary",
                  summary,
                  "keywords",
                  keywords,
                  "embedding_json",
                  json.write(Embeddings.local(part.content() + "\n" + summary + "\n" + keywords)),
                  "embedding_space",
                  "local-zh-hash-v1"));
        }
      }
  }

  public void verify() {
    for (var entry :
        Map.of("community", 4, "sys_user", 7, "elder_profile", 20, "notice", 12, "ai_document", 32)
            .entrySet())
      Problem.require(
          db.count("SELECT COUNT(*) FROM `" + entry.getKey() + "`", Map.of()) == entry.getValue(),
          "导入行数校验失败：" + entry.getKey());
    Problem.require(
        db.count(
                "SELECT COUNT(*) FROM elder_profile e LEFT JOIN user_community u ON"
                    + " u.user_id=e.owner_user_id AND u.community_id=e.community_id AND"
                    + " u.status='ACTIVE' WHERE u.user_id IS NULL",
                Map.of())
            == 0,
        "存在未授权负责人");
    Problem.require(
        db.count("SELECT COUNT(*) FROM ai_document WHERE status<>'READY' OR is_demo=1", Map.of())
            == 0,
        "初始政策未完全就绪");
    for (var c : rows("communities"))
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM elder_profile WHERE community_id=:id",
                  Db.p("id", c.get("id")))
              == 5,
          "社区老人数量不符");
  }

  private static final class VectorStoreResource implements AutoCloseable {
    final VectorStore store;

    VectorStoreResource(Path path, Json json) throws IOException {
      store = new VectorStore(path.toString(), json);
    }

    public void close() throws IOException {
      store.close();
    }
  }
}
