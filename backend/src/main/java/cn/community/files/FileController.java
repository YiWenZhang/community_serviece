package cn.community.files;

import cn.community.common.*;
import cn.community.identity.Auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "06 私有脱敏附件")
public class FileController {
  private final FileService files;
  private final Auth auth;

  public FileController(FileService files, Auth auth) {
    this.files = files;
    this.auth = auth;
  }

  public record Confirm(boolean redactionConfirmed) {}

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "上传PNG或JPEG，重编码隔离保存")
  public Object upload(
      @RequestPart MultipartFile file,
      @RequestParam(defaultValue = "false") boolean redactionConfirmed)
      throws Exception {
    var a = auth.require("file:upload");
    Problem.require(redactionConfirmed, "请先确认图片已经脱敏，不含身份证、家庭住址或原始手机号");
    return Api.ok(files.upload(a, file));
  }

  @PostMapping("/{id}/finalize")
  @Operation(summary = "上传者人工确认脱敏后启用附件")
  public Object finalizeFile(@PathVariable long id, @RequestBody Confirm b) {
    return Api.ok(files.finalizeFile(auth.current(), id, b.redactionConfirmed()));
  }

  @GetMapping("/{id}/content")
  @Operation(summary = "鉴权下载业务附件或本人的导出文件")
  public ResponseEntity<byte[]> download(@PathVariable long id) throws Exception {
    var file = files.readable(auth.current(), id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.get("mime_type").toString()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.get("safe_name").toString(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(files.read(file));
  }
}
