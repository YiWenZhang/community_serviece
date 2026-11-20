package cn.community.identity;

import cn.community.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/communities")
public class CommunityController {
  private final Db db;
  private final Auth auth;
  private final Audit audit;
  private final Privacy privacy;

  public CommunityController(Db db, Auth auth, Audit audit, Privacy privacy) {
    this.db = db;
    this.auth = auth;
    this.audit = audit;
    this.privacy = privacy;
  }

  public record Draft(
      @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,32}") String code,
      @NotBlank @Size(max = 80) String name,
      @Size(max = 2000) String description,
      @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
      @Min(0) int version) {}

  @GetMapping
  public Object list() {
    return Api.ok(auth.communities(auth.current()));
  }

  @PostMapping
  @Transactional
  public Object create(@Valid @RequestBody Draft b) {
    var a = auth.require("system:community:manage");
    privacy.check(b.name());
    privacy.check(b.description());
    long id =
        db.insert(
            "community",
            Db.p("code", b.code(), "name", b.name(), "status", b.status(), "created_by", a.id()));
    db.update(
        "INSERT INTO community_profile(community_id,description) VALUES(:community,:description)",
        Db.p("community", id, "description", b.description()));
    db.insert(
        "grid", Db.p("community_id", id, "code", "DEFAULT", "name", "默认网格", "created_by", a.id()));
    audit.log(
        auth.inCommunity(a, id), "COMMUNITY_CREATED", "COMMUNITY", id, Map.of("name", b.name()));
    return Api.ok(Map.of("id", id));
  }

  @PutMapping("/{id}")
  @Transactional
  public Object update(@PathVariable long id, @Valid @RequestBody Draft b) {
    var a = auth.require("system:community:manage");
    privacy.check(b.name());
    privacy.check(b.description());
    if (b.status().equals("INACTIVE"))
      Problem.require(
          db.count(
                  "SELECT COUNT(*) FROM community WHERE status='ACTIVE' AND id<>:id",
                  Db.p("id", id))
              > 0,
          "必须保留至少一个启用社区");
    Problem.changed(
        db.update(
            "UPDATE community SET"
                + " code=:code,name=:name,status=:status,version=version+1,updated_by=:actor WHERE"
                + " id=:id AND version=:version",
            Db.p(
                "code",
                b.code(),
                "name",
                b.name(),
                "status",
                b.status(),
                "actor",
                a.id(),
                "id",
                id,
                "version",
                b.version())));
    db.update(
        "INSERT INTO community_profile(community_id,description) VALUES(:id,:description) ON"
            + " DUPLICATE KEY UPDATE description=:description",
        Db.p("id", id, "description", b.description()));
    // 每次请求都重新核验社区状态，不因修改名称将管理员自己退出登录。
    audit.log(
        auth.inCommunity(a, id),
        "COMMUNITY_UPDATED",
        "COMMUNITY",
        id,
        Map.of("name", b.name(), "status", b.status()));
    return Api.ok(Map.of("id", id));
  }

  @GetMapping("/{id}/workers")
  public Object workers(@PathVariable long id) {
    var a = auth.require("system:community:manage");
    auth.inCommunity(a, id);
    return Api.ok(
        db.list(
            "SELECT DISTINCT u.id,u.username,u.display_name FROM sys_user u JOIN user_community m"
                + " ON m.user_id=u.id JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON"
                + " r.id=ur.role_id WHERE m.community_id=:id AND m.status='ACTIVE' AND"
                + " u.status='ACTIVE' AND r.code='GRID_WORKER' AND r.status='ACTIVE'",
            Db.p("id", id)));
  }

  @GetMapping("/{id}/grids")
  public Object grids(@PathVariable long id) {
    var a = auth.require("system:community:manage");
    auth.inCommunity(a, id);
    return Api.ok(
        db.list(
            "SELECT id,name FROM grid WHERE community_id=:id AND status='ACTIVE'", Db.p("id", id)));
  }

  @PostMapping(value = "/{id}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Object icon(@PathVariable long id, @RequestPart MultipartFile file) throws Exception {
    var a = auth.require("system:community:manage");
    auth.inCommunity(a, id);
    Problem.require(!file.isEmpty() && file.getSize() <= 2 * 1024 * 1024, "图标须为2MB以内PNG或JPEG");
    BufferedImage source;
    try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
      var readers = ImageIO.getImageReaders(input);
      Problem.require(readers.hasNext(), "仅支持有效PNG或JPEG");
      var reader = readers.next();
      try {
        reader.setInput(input);
        Problem.require(
            Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)),
            "仅支持PNG或JPEG");
        Problem.require((long) reader.getWidth(0) * reader.getHeight(0) <= 4_000_000, "图标分辨率过大");
        source = reader.read(0);
      } finally {
        reader.dispose();
      }
    }
    int width = Math.min(256, source.getWidth()),
        height = Math.max(1, source.getHeight() * width / source.getWidth());
    if (height > 256) {
      width = Math.max(1, width * 256 / height);
      height = 256;
    }
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    var g = image.createGraphics();
    g.drawImage(source, 0, 0, width, height, null);
    g.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    db.update(
        "INSERT INTO community_profile(community_id,icon_data,icon_mime)"
            + " VALUES(:id,:bytes,'image/png') ON DUPLICATE KEY UPDATE"
            + " icon_data=:bytes,icon_mime='image/png'",
        Db.p("id", id, "bytes", bytes.toByteArray()));
    audit.log(
        auth.inCommunity(a, id),
        "COMMUNITY_ICON_UPDATED",
        "COMMUNITY",
        id,
        Map.of("size", bytes.size()));
    return Api.ok(Map.of("id", id));
  }

  @GetMapping("/{id}/icon")
  public ResponseEntity<byte[]> icon(@PathVariable long id) {
    auth.inCommunity(auth.current(), id);
    var row =
        db.one(
            "SELECT icon_data FROM community_profile WHERE community_id=:id AND icon_data IS NOT"
                + " NULL",
            Db.p("id", id));
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .header("Cache-Control", "no-store")
        .body((byte[]) row.get("icon_data"));
  }
}
