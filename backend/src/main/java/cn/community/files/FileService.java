package cn.community.files;

import cn.community.common.*;
import cn.community.identity.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
  private final Path root;
  private final Db db;
  private final Audit audit;

  public FileService(@Value("${app.storage}") String root, Db db, Audit audit) throws IOException {
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
    this.db = db;
    this.audit = audit;
  }

  @Transactional
  public Object upload(Actor a, MultipartFile upload) throws Exception {
    a.require("file:upload");
    Problem.require(!upload.isEmpty() && upload.getSize() <= 20 * 1024 * 1024, "图片大小须1字节至20MB");
    byte[] bytes;
    // 只接受可解码图片，并重新编码为PNG，移除原文件元数据及附加内容。
    try (var input = ImageIO.createImageInputStream(upload.getInputStream())) {
      var readers = ImageIO.getImageReaders(input);
      Problem.require(readers.hasNext(), "仅接受有效PNG或JPEG图片");
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        String format = reader.getFormatName();
        Problem.require(
            Set.of("png", "jpeg", "jpg").contains(format.toLowerCase(Locale.ROOT)), "仅支持PNG或JPEG");
        Problem.require((long) reader.getWidth(0) * reader.getHeight(0) <= 25_000_000L, "图片分辨率过大");
        var image = reader.read(0);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        bytes = output.toByteArray();
      } finally {
        reader.dispose();
      }
    }
    long id = save(a, "EVIDENCE", "evidence.png", "image/png", bytes, false);
    audit.log(a, "FILE_UPLOADED", "FILE", id, Map.of("size", bytes.length));
    return Db.p("id", id, "status", "TEMP", "redactionStatus", "PENDING");
  }

  public long save(
      Actor a, String purpose, String filename, String mime, byte[] bytes, boolean active)
      throws Exception {
    String storage = UUID.randomUUID() + ".bin";
    Path path = root.resolve(storage);
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    try {
      return db.insert(
          "file_object",
          Db.p(
              "community_id",
              a.communityId(),
              "purpose",
              purpose,
              "storage_key",
              storage,
              "safe_name",
              filename,
              "mime_type",
              mime,
              "size_bytes",
              bytes.length,
              "sha256",
              MessageDigest.getInstance("SHA-256").digest(bytes),
              "scan_status",
              "PASSED",
              "redaction_status",
              active ? "PASSED" : "PENDING",
              "status",
              active ? "ACTIVE" : "TEMP",
              "expires_at",
              LocalDateTime.now(ZoneOffset.UTC).plusDays(active ? 7 : 1),
              "created_by",
              a.id()));
    } catch (RuntimeException e) {
      Files.deleteIfExists(path);
      throw e;
    }
  }

  @Transactional
  public Object finalizeFile(Actor a, long id, boolean confirmed) {
    a.require("file:upload");
    Problem.require(confirmed, "必须人工确认图片已脱敏，不含身份证手机家庭地址等信息");
    var file =
        db.one(
            "SELECT * FROM file_object WHERE id=:id AND community_id=:community AND"
                + " created_by=:actor AND purpose='EVIDENCE' FOR UPDATE",
            Db.p("id", id, "community", a.communityId(), "actor", a.id()));
    Problem.require("PASSED".equals(file.get("scan_status")), "图片结构校验未通过");
    db.update(
        "UPDATE file_object SET"
            + " redaction_status='PASSED',status='ACTIVE',expires_at=NULL,version=version+1,updated_by=:actor"
            + " WHERE id=:id",
        Db.p("id", id, "actor", a.id()));
    audit.log(a, "FILE_REDACTION_CONFIRMED", "FILE", id, Map.of());
    return Db.p("id", id, "status", "ACTIVE");
  }

  public void attachable(Actor a, long id) {
    db.one(
        "SELECT id FROM file_object WHERE id=:id AND community_id=:community AND created_by=:actor"
            + " AND status='ACTIVE' AND purpose='EVIDENCE' AND scan_status='PASSED' AND"
            + " redaction_status='PASSED'",
        Db.p("id", id, "community", a.communityId(), "actor", a.id()));
  }

  public Map<String, Object> readable(Actor a, long id) {
    var p = Db.p("id", id, "community", a.communityId(), "actor", a.id());
    var file =
        db.one(
            "SELECT * FROM file_object WHERE id=:id AND community_id=:community AND status='ACTIVE'"
                + " AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(3))",
            p);
    boolean allowed = false;
    if ("EVIDENCE".equals(file.get("purpose")) && a.has("file:read")) {
      boolean attached =
          db.count("SELECT COUNT(*) FROM delivery_attachment WHERE file_id=:id", p) > 0;
      allowed =
          attached
              ? db.count(
                      "SELECT COUNT(*) FROM delivery_attachment x JOIN delivery_attempt d ON"
                          + " d.id=x.delivery_attempt_id JOIN notice_recipient r ON"
                          + " r.id=d.recipient_id JOIN elder_profile e ON e.id=r.elder_id WHERE"
                          + " x.file_id=:id AND e.owner_user_id=:actor AND"
                          + " e.community_id=:community",
                      p)
                  > 0
              : Objects.equals(((Number) file.get("created_by")).longValue(), a.id());
    }
    if ("REPORT".equals(file.get("purpose"))) {
      var report =
          db.optional(
              "SELECT scope_owner_id FROM report_job WHERE output_file_id=:id AND"
                  + " requested_by=:actor AND community_id=:community",
              p);
      if (report.isPresent())
        allowed =
            a.has("report:export:community")
                || (a.has("report:export:own")
                    && report.get().get("scope_owner_id") != null
                    && Db.id(report.get(), "scope_owner_id") == a.id());
    }
    if (!allowed) throw Problem.missing();
    return file;
  }

  public byte[] read(Map<String, Object> row) throws IOException {
    Path file = root.resolve(row.get("storage_key").toString()).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) throw Problem.missing();
    return Files.readAllBytes(file);
  }
}
